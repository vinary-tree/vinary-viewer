'use strict';
// Authenticated vinary-viewer daemon event transport.
//
// A target daemon binds an ephemeral LOOPBACK TCP listener and advertises it in an owner-readable file under
// the user's home.  A source daemon reads that descriptor over the already authenticated SFTP connection and
// reaches the listener with SSH direct-tcpip forwarding.  SSH protects the channel; the challenge/response
// below additionally proves that the endpoint is the advertised vinary-viewer daemon instead of an unrelated
// process that happened to acquire a stale loopback port.

const crypto = require('crypto');
const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');
const transport = require('./ssh_transport.js');

const PROTOCOL = 'vv-events/2';
const CONTROL_MAX = 64 * 1024;
const SERVER_MAX = 96 * 1024 * 1024; // bounded above service.cljs' 64 MiB git output ceiling + JSON overhead
const HANDSHAKE_MS = 7000;
const REQUEST_MS = 30000;
const HEARTBEAT_MS = 30000;
const HEARTBEAT_DEAD_MS = 90000;
const DESCRIPTOR_REL = '.vinary-viewer/runtime/daemon-events.json';

const defaults = {
  homeDir: null,
  descriptorPath: null,
  handlers: {},
  onTreeUpdate: () => {},
  onContentState: () => {},
  onConnectionState: () => {},
};
const opts = { ...defaults };

function configure(o) {
  if (!o) return;
  if (o.handlers) opts.handlers = { ...opts.handlers, ...o.handlers };
  for (const [k, v] of Object.entries(o)) if (k !== 'handlers' && v !== undefined) opts[k] = v;
}

function homeDir() { return opts.homeDir || os.homedir(); }
function descriptorPath() {
  return opts.descriptorPath || process.env.VV_DAEMON_EVENTS_DESCRIPTOR
    || path.join(homeDir(), ...DESCRIPTOR_REL.split('/'));
}
function randomB64(n = 32) { return crypto.randomBytes(n).toString('base64url'); }
function hmac(secret, fields) {
  return crypto.createHmac('sha256', secret).update(JSON.stringify(fields)).digest('base64url');
}
function safeEqual(a, b) {
  try {
    const aa = Buffer.from(String(a), 'base64url');
    const bb = Buffer.from(String(b), 'base64url');
    return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
  } catch (_e) { return false; }
}
function errorMessage(e) { return e && e.message ? e.message : String(e); }

function sendLine(stream, value) {
  if (!stream || stream.destroyed || !stream.writable) return false;
  try { stream.write(JSON.stringify(value) + '\n'); return true; } catch (_e) { return false; }
}

function lineReader(stream, maxBytes, onValue, onFailure) {
  let buf = '';
  let bufferedBytes = 0;
  stream.setEncoding('utf8');
  stream.on('data', (chunk) => {
    buf += chunk; bufferedBytes += Buffer.byteLength(chunk, 'utf8');
    for (;;) {
      const i = buf.indexOf('\n');
      if (i < 0) break;
      const raw = buf.slice(0, i); buf = buf.slice(i + 1);
      bufferedBytes -= Buffer.byteLength(raw + '\n', 'utf8');
      if (!raw.trim()) continue;
      if (Buffer.byteLength(raw, 'utf8') > maxBytes) {
        onFailure(new Error('daemon event frame exceeds the configured limit'));
        stream.destroy(); return;
      }
      try { onValue(JSON.parse(raw)); }
      catch (_e) { onFailure(new Error('invalid daemon event JSON frame')); stream.destroy(); return; }
    }
    if (bufferedBytes > maxBytes) {
      onFailure(new Error('daemon event frame exceeds the configured limit'));
      stream.destroy();
    }
  });
}

function pathCodec() { return process.platform === 'win32' ? 'windows-openssh-v1' : 'posix-v1'; }

function sftpToLocal(p, codec) {
  p = String(p || '');
  if (!p || p.includes('\0')) throw new Error('invalid remote filesystem path');
  if (codec === 'windows-openssh-v1') {
    const m = /^\/?([A-Za-z]):(?:\/|$)(.*)$/.exec(p.replace(/\\/g, '/'));
    if (!m) throw new Error('remote path cannot be mapped to a Windows drive');
    return path.win32.normalize(`${m[1]}:\\${m[2].replace(/\//g, '\\')}`);
  }
  if (!p.startsWith('/')) throw new Error('remote path is not absolute');
  return path.posix.normalize(p);
}

function localToSftp(p, codec) {
  p = String(p || '');
  if (codec === 'windows-openssh-v1') {
    const m = /^([A-Za-z]):[\\/](.*)$/.exec(p);
    if (!m) throw new Error('Windows path cannot be represented in the SFTP namespace');
    return `/${m[1]}:/${m[2].replace(/\\/g, '/')}`;
  }
  if (!path.posix.isAbsolute(p)) throw new Error('target path is not absolute');
  return path.posix.normalize(p);
}

function wireEntry(entry, codec) {
  if (!entry) return entry;
  const out = { ...entry, root: localToSftp(entry.root, codec),
    files: (entry.files || []).map(encodeRelative) };
  if (typeof entry.scope === 'string') out.scope = encodeRelative(entry.scope);
  return out;
}

function uriPrefix(uri) {
  const m = /^(s(?:sh|ftp):\/\/[^/]*)/i.exec(String(uri));
  if (!m) throw new Error('not an SSH/SFTP URI');
  return m[1];
}

function encodePath(p) {
  const leading = String(p).startsWith('/');
  const parts = String(p).split('/').filter((x, i) => !(i === 0 && leading));
  const body = parts.map((s) => encodeURIComponent(s).replace(/%3A/gi, ':')).join('/');
  return (leading ? '/' : '') + body;
}

function encodeRelative(p) {
  return String(p || '').replace(/\\/g, '/').split('/').map((s) => encodeURIComponent(s)).join('/');
}

function uriWithPath(baseUri, p) { return uriPrefix(baseUri) + encodePath(p.startsWith('/') ? p : `/${p}`); }
function clientEntry(baseUri, entry) {
  return entry ? { ...entry, root: uriWithPath(baseUri, entry.root) } : entry;
}
function descriptorUri(uri) { return `${uriPrefix(uri)}/~/${DESCRIPTOR_REL}`; }

// -------------------------------------------------------------------------------------------------
// Target endpoint

let target = null;
let targetStart = null;
let targetEpoch = 0;

async function callHandler(name, ...args) {
  const fn = opts.handlers && opts.handlers[name];
  if (typeof fn !== 'function') throw new Error(`daemon event capability unavailable: ${name}`);
  return await fn(...args);
}

function writeDescriptor(desc) {
  const dest = descriptorPath();
  const dir = path.dirname(dest);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  try { fs.chmodSync(dir, 0o700); } catch (_e) { /* Windows inherits the user's profile ACL */ }
  const tmp = `${dest}.${process.pid}.${randomB64(6)}.tmp`;
  try {
    fs.writeFileSync(tmp, JSON.stringify(desc) + '\n', { mode: 0o600 });
    try { fs.chmodSync(tmp, 0o600); } catch (_e) { /* Windows */ }
    fs.renameSync(tmp, dest);
  } catch (e) {
    try { fs.unlinkSync(tmp); } catch (_cleanupError) {}
    throw e;
  }
}

function removeDescriptor(desc) {
  const p = descriptorPath();
  try {
    const current = JSON.parse(fs.readFileSync(p, 'utf8'));
    if (current.daemonId === desc.daemonId && current.secret === desc.secret) fs.unlinkSync(p);
  } catch (_e) { /* absent, stale, or replaced by a newer daemon */ }
}

function sameDescriptorIdentity(sftpPath, codec) {
  try {
    const actual = fs.realpathSync(descriptorPath());
    const mapped = fs.realpathSync(sftpToLocal(sftpPath, codec));
    const actualStat = fs.statSync(actual);
    const mappedStat = fs.statSync(mapped);
    // dev+ino is the strongest portable identity available from Node. Keep the real-path comparison for
    // filesystems which report zero/unstable inode numbers (notably some Windows/network filesystems).
    if ((actualStat.ino !== 0 || mappedStat.ino !== 0)
        && actualStat.dev === mappedStat.dev && actualStat.ino === mappedStat.ino) return true;
    const normalize = (p) => process.platform === 'win32' ? p.toLowerCase() : p;
    return normalize(actual) === normalize(mapped);
  } catch (_e) { return false; }
}

function serveAuthenticated(socket, desc, sockets) {
  sockets.add(socket);
  let phase = 'hello';
  let clientId = null; let clientNonce = null; let serverNonce = null; let sessionId = null;
  let clientDescriptorPath = null;
  let lastSeen = Date.now();
  const codec = desc.pathCodec;
  const finish = () => {
    sockets.delete(socket);
    if (sessionId) Promise.resolve(callHandler('releaseSession', sessionId)).catch(() => {});
  };
  socket.once('close', finish);
  socket.once('error', () => {});
  const handshakeTimer = setTimeout(() => { if (phase !== 'ready') socket.destroy(); }, HANDSHAKE_MS);
  if (handshakeTimer.unref) handshakeTimer.unref();

  const reply = (id, result, error) => sendLine(socket, error
    ? { type: 'response', id, ok: false, error }
    : { type: 'response', id, ok: true, result });
  const pushTree = (ownerId, entry) => sendLine(socket,
    { type: 'tree-update', ownerId, entry: wireEntry(entry, codec) });
  const pushContent = (subId, event) => sendLine(socket,
    { type: 'content-invalidate', subId, event: event || 'change' });

  async function request(msg) {
    const id = String(msg.id || '');
    const p = msg.payload || {};
    if (!id || typeof msg.op !== 'string') throw new Error('invalid daemon event request');
    switch (msg.op) {
      case 'ping': return { now: Date.now() };
      case 'content-subscribe':
        await callHandler('contentSubscribe', sessionId, String(p.subId), sftpToLocal(p.path, codec),
          (event) => pushContent(String(p.subId), event));
        return { subscribed: true };
      case 'content-unsubscribe':
        await callHandler('contentUnsubscribe', sessionId, String(p.subId));
        return { subscribed: false };
      case 'tree-open': {
        const ownerId = String(p.ownerId);
        const entry = await callHandler('treeOpen', sessionId, ownerId, sftpToLocal(p.path, codec),
          (next) => pushTree(ownerId, next));
        return { entry: wireEntry(entry, codec) };
      }
      case 'tree-roots':
        await callHandler('treeRoots', sessionId, String(p.ownerId),
          (p.roots || []).map((x) => sftpToLocal(x, codec)));
        return { synced: true };
      case 'tree-expanded':
        await callHandler('treeExpanded', sessionId, String(p.ownerId), (p.scopes || []).map((s) => ({
          root: sftpToLocal(s.root, codec), path: sftpToLocal(s.path, codec),
        })));
        return { synced: true };
      case 'tree-refresh': {
        const entry = await callHandler('treeRefresh', sessionId, String(p.ownerId), {
          root: sftpToLocal(p.root, codec), path: sftpToLocal(p.path, codec),
        });
        return { entry: wireEntry(entry, codec) };
      }
      case 'tree-refresh-all': {
        const entries = await callHandler('treeRefreshAll', sessionId, String(p.ownerId));
        return { entries: (entries || []).map((e) => wireEntry(e, codec)) };
      }
      case 'release-owner':
        await callHandler('releaseTreeOwner', sessionId, String(p.ownerId));
        return { released: true };
      default: throw new Error(`unknown daemon event operation: ${msg.op}`);
    }
  }

  lineReader(socket, CONTROL_MAX, (msg) => {
    lastSeen = Date.now();
    if (phase === 'hello') {
      if (msg.type !== 'hello' || msg.protocol !== PROTOCOL || typeof msg.clientId !== 'string'
          || typeof msg.nonce !== 'string' || typeof msg.descriptorPath !== 'string'
          || !sameDescriptorIdentity(msg.descriptorPath, codec)) return socket.destroy();
      clientId = msg.clientId; clientNonce = msg.nonce; clientDescriptorPath = msg.descriptorPath;
      serverNonce = randomB64(); phase = 'authenticate';
      sendLine(socket, { type: 'challenge', protocol: PROTOCOL, daemonId: desc.daemonId,
        nonce: serverNonce, capabilities: desc.capabilities });
      return;
    }
    if (phase === 'authenticate') {
      if (msg.type !== 'authenticate') return socket.destroy();
      const expected = hmac(desc.secret,
        ['client', PROTOCOL, clientId, clientNonce, desc.daemonId, serverNonce, clientDescriptorPath]);
      if (!safeEqual(msg.proof, expected)) return socket.destroy();
      sessionId = randomB64(18); phase = 'ready'; clearTimeout(handshakeTimer);
      sendLine(socket, { type: 'ready', protocol: PROTOCOL, sessionId,
        proof: hmac(desc.secret,
          ['server', PROTOCOL, clientId, clientNonce, desc.daemonId, serverNonce, sessionId,
            clientDescriptorPath]) });
      return;
    }
    if (msg.type === 'heartbeat') { sendLine(socket, { type: 'heartbeat-ack', at: Date.now() }); return; }
    if (msg.type !== 'request') return socket.destroy();
    request(msg).then((result) => reply(msg.id, result)).catch((e) => reply(msg.id, null, errorMessage(e)));
  }, () => socket.destroy());

  const heartbeat = setInterval(() => {
    if ((Date.now() - lastSeen) > HEARTBEAT_DEAD_MS) socket.destroy();
  }, HEARTBEAT_MS);
  if (heartbeat.unref) heartbeat.unref();
  socket.once('close', () => clearInterval(heartbeat));
}

function startServer(meta) {
  if (target) return Promise.resolve(target.descriptor);
  if (targetStart) return targetStart;
  const epoch = targetEpoch;
  const secret = randomB64();
  const daemonId = randomB64(18);
  const sockets = new Set();
  let descriptor = null;
  const server = net.createServer((socket) => {
    // A close can race an already-accepted connection. Use this endpoint's captured descriptor rather than the
    // mutable global target, and fail closed if publication never completed.
    if (!descriptor) { socket.destroy(); return; }
    serveAuthenticated(socket, descriptor, sockets);
  });
  const starting = new Promise((resolve, reject) => {
    const fail = (e) => { server.close(); reject(e); };
    server.once('error', fail);
    server.listen(0, '127.0.0.1', () => {
      server.off('error', fail);
      // A listening net.Server can still fail later. Consume that operational error, retire the endpoint, and
      // remove its descriptor so a source never discovers a dead port and the Electron process never crashes.
      server.on('error', (e) => {
        if (!target || target.server !== server) return;
        const broken = target; target = null;
        for (const socket of broken.sockets) { try { socket.destroy(); } catch (_socketError) {} }
        try { server.close(); } catch (_closeError) {}
        removeDescriptor(broken.descriptor);
        console.warn('[vinary] daemon event endpoint failed:', errorMessage(e));
      });
      if (epoch !== targetEpoch) {
        server.close();
        reject(new Error('daemon event endpoint start was cancelled'));
        return;
      }
      const address = server.address();
      descriptor = {
        protocol: PROTOCOL, host: '127.0.0.1', port: address.port, secret, daemonId,
        pid: process.pid, version: (meta && meta.version) || null,
        bundleMtimeMs: (meta && meta.bundleMtimeMs) || null,
        platform: process.platform, pathCodec: pathCodec(),
        capabilities: ['content-invalidate', 'directory-invalidate', 'expansion-scoped-tree'],
      };
      target = { server, sockets, descriptor };
      try { writeDescriptor(descriptor); }
      catch (e) { target = null; server.close(); reject(e); return; }
      resolve(descriptor);
    });
  });
  targetStart = starting;
  starting.then(
    () => { if (targetStart === starting) targetStart = null; },
    () => { if (targetStart === starting) targetStart = null; });
  return starting;
}

function stopServer() {
  targetEpoch++;
  if (!target) return;
  const t = target; target = null;
  for (const s of t.sockets) { try { s.destroy(); } catch (_e) {} }
  try { t.server.close(); } catch (_e) {}
  removeDescriptor(t.descriptor);
}

// -------------------------------------------------------------------------------------------------
// Source/client side

const clients = new Map(); // SSH connKey -> persistent desired state + transient tunnel
let requestSeq = 0;

function newClient(uri) {
  return {
    key: transport.connectionKey(uri), baseUri: uri, state: 'idle', stream: null, descriptor: null,
    readyP: null, pending: new Map(), desiredContent: new Map(), desiredOwners: new Map(),
    reconnectTimer: null, backoff: 1000, heartbeat: null, lastSeen: 0, closing: false,
    everReady: false, restoreRequired: false,
  };
}
function getClient(uri) {
  const key = transport.connectionKey(uri);
  let c = clients.get(key);
  if (!c) { c = newClient(uri); clients.set(key, c); }
  return c;
}
function hasDesired(c) {
  if (c.desiredContent.size) return true;
  for (const o of c.desiredOwners.values()) if (o.opens.size || o.roots.size || o.expanded.size) return true;
  return false;
}
function owner(c, ownerId, uri) {
  let o = c.desiredOwners.get(ownerId);
  if (!o) { o = { uri: uri || c.baseUri, opens: new Set(), deliveredOpens: new Set(),
    roots: new Set(), expanded: new Map() };
    c.desiredOwners.set(ownerId, o); }
  return o;
}
function notifyConnection(c, state, message) {
  try { opts.onConnectionState({ connKey: c.key, uri: c.baseUri, state, message: message || null }); } catch (_e) {}
}
function notifyContent(c, subId, active, message) {
  try { opts.onContentState({ connKey: c.key, uri: c.baseUri, subId, active, message: message || null }); } catch (_e) {}
}

function scheduleReconnect(c) {
  if (c.closing || c.reconnectTimer || !hasDesired(c)) return;
  // This also covers discovery failing before the channel was ever ready. The next successful connection is
  // autonomous (the original API call has already rejected), so it must replay the desired state itself.
  c.restoreRequired = true;
  const jitter = Math.floor(Math.random() * c.backoff * 0.25);
  c.reconnectTimer = setTimeout(() => {
    c.reconnectTimer = null;
    ensureConnected(c).catch(() => {});
  }, c.backoff + jitter);
  if (c.reconnectTimer.unref) c.reconnectTimer.unref();
  c.backoff = Math.min(60000, c.backoff * 2);
}

function disconnect(c, message) {
  if (c.state === 'closed' || (c.state === 'idle' && !c.stream)) return;
  c.state = 'idle'; c.readyP = null;
  if (c.heartbeat) { clearInterval(c.heartbeat); c.heartbeat = null; }
  const stream = c.stream; c.stream = null;
  if (stream && !stream.destroyed) { try { stream.destroy(); } catch (_e) {} }
  for (const [, p] of c.pending) {
    clearTimeout(p.timer);
    p.reject(new Error(message || 'daemon event channel closed'));
  }
  c.pending.clear();
  for (const subId of c.desiredContent.keys()) notifyContent(c, subId, false, message);
  notifyConnection(c, 'disconnected', message);
  scheduleReconnect(c);
}

function request(c, op, payload) {
  if (c.state !== 'ready') return Promise.reject(new Error('daemon event channel is not ready'));
  const id = `de-${++requestSeq}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      c.pending.delete(id);
      const message = `target daemon request timed out: ${op}`;
      reject(new Error(message));
      disconnect(c, message);
    }, REQUEST_MS);
    if (timer.unref) timer.unref();
    c.pending.set(id, { resolve, reject, timer });
    if (!sendLine(c.stream, { type: 'request', id, op, payload })) {
      const message = 'could not write daemon event request';
      clearTimeout(timer); c.pending.delete(id); reject(new Error(message)); disconnect(c, message);
    }
  });
}

async function restoreDesired(c) {
  for (const [subId, s] of c.desiredContent) {
    const resolved = await transport.remoteResolvePath(s.uri);
    if (c.desiredContent.get(subId) !== s) continue;
    await request(c, 'content-subscribe', { subId, path: resolved });
    if (c.desiredContent.get(subId) === s) notifyContent(c, subId, true);
  }
  for (const [ownerId, o] of c.desiredOwners) {
    for (const opened of o.opens) {
      // Re-establish target-side root knowledge without re-offering it to the renderer.  The renderer already
      // owns delivered projects; pushing every historical open could resurrect one removed from Files. An open
      // which never connected, however, still needs its FIRST tree when a compatible daemon later appears.
      const resolved = await transport.remoteResolvePath(opened);
      if (c.desiredOwners.get(ownerId) !== o || !o.opens.has(opened)) continue;
      const r = await request(c, 'tree-open', { ownerId, path: resolved });
      if (c.desiredOwners.get(ownerId) === o && o.opens.has(opened)
          && r && r.entry && !o.deliveredOpens.has(opened)) {
        o.deliveredOpens.add(opened);
        opts.onTreeUpdate({ connKey: c.key, uri: o.uri, ownerId, openedPath: opened,
          entry: clientEntry(o.uri, r.entry) });
      }
    }
    const roots = o.roots;
    if (roots.size) {
      const resolved = await Promise.all([...roots].map((uri) => transport.remoteResolvePath(uri)));
      if (c.desiredOwners.get(ownerId) === o && o.roots === roots)
        await request(c, 'tree-roots', { ownerId, roots: resolved });
    }
    const expanded = o.expanded;
    if (expanded.size) {
      const resolved = await Promise.all([...expanded.values()].map(async (s) => (
        { root: await transport.remoteResolvePath(s.root), path: await transport.remoteResolvePath(s.path) })));
      if (c.desiredOwners.get(ownerId) === o && o.expanded === expanded)
        await request(c, 'tree-expanded', { ownerId, scopes: resolved });
    }
  }
}

async function connectClient(c) {
  if (c.closing) throw new Error('daemon event connection was cancelled');
  c.state = 'connecting'; notifyConnection(c, 'connecting');
  const discoveryUri = descriptorUri(c.baseUri);
  const remoteDescriptorPath = await transport.remoteResolvePath(discoveryUri);
  if (c.closing) throw new Error('daemon event connection was cancelled');
  const text = await transport.remoteReadText(discoveryUri, { maxBytes: CONTROL_MAX });
  if (c.closing) throw new Error('daemon event connection was cancelled');
  let desc;
  try { desc = JSON.parse(text); } catch (_e) { throw new Error('invalid target daemon descriptor'); }
  let secretBytes = 0;
  try { secretBytes = Buffer.from(String(desc.secret || ''), 'base64url').length; } catch (_e) {}
  if (desc.protocol !== PROTOCOL || !Number.isInteger(desc.port) || desc.port < 1 || desc.port > 65535
      || typeof desc.secret !== 'string' || secretBytes !== 32 || typeof desc.daemonId !== 'string'
      || desc.daemonId.length < 16 || !['posix-v1', 'windows-openssh-v1'].includes(desc.pathCodec)) {
    throw new Error('target daemon event protocol is unavailable or incompatible');
  }
  const stream = await transport.remoteForwardOut(c.baseUri, '127.0.0.1', desc.port);
  c.stream = stream; c.descriptor = desc; c.lastSeen = Date.now();
  if (c.closing) throw new Error('daemon event connection was cancelled');
  const clientId = randomB64(18); const clientNonce = randomB64();
  await new Promise((resolve, reject) => {
    let phase = 'challenge';
    const timer = setTimeout(() => reject(new Error('target daemon handshake timed out')), HANDSHAKE_MS);
    if (timer.unref) timer.unref();
    const fail = (e) => { clearTimeout(timer); reject(e); };
    lineReader(stream, SERVER_MAX, (msg) => {
      c.lastSeen = Date.now();
      if (phase === 'challenge') {
        if (msg.type !== 'challenge' || msg.protocol !== PROTOCOL || msg.daemonId !== desc.daemonId
            || typeof msg.nonce !== 'string') return fail(new Error('invalid target daemon challenge'));
        c.serverNonce = msg.nonce; phase = 'ready';
        sendLine(stream, { type: 'authenticate', proof: hmac(desc.secret,
          ['client', PROTOCOL, clientId, clientNonce, desc.daemonId, msg.nonce,
            remoteDescriptorPath]) });
        return;
      }
      if (phase === 'ready') {
        if (msg.type !== 'ready' || msg.protocol !== PROTOCOL || typeof msg.sessionId !== 'string')
          return fail(new Error('invalid target daemon ready proof'));
        const expected = hmac(desc.secret,
          ['server', PROTOCOL, clientId, clientNonce, desc.daemonId, c.serverNonce, msg.sessionId,
            remoteDescriptorPath]);
        if (!safeEqual(msg.proof, expected)) return fail(new Error('target daemon authentication failed'));
        c.sessionId = msg.sessionId; phase = 'done'; clearTimeout(timer); resolve();
        return;
      }
      handleClientFrame(c, msg);
    }, fail);
    stream.once('error', (e) => { if (phase !== 'done') fail(e); });
    stream.once('close', () => { if (phase !== 'done') fail(new Error('target daemon closed during handshake')); });
    sendLine(stream, { type: 'hello', protocol: PROTOCOL, clientId, nonce: clientNonce,
      descriptorPath: remoteDescriptorPath });
  });
  if (c.closing) throw new Error('daemon event connection was cancelled');
  // The parser installed for the handshake stays active and routes all later frames through handleClientFrame.
  c.state = 'ready'; c.backoff = 1000; notifyConnection(c, 'ready');
  stream.once('close', () => disconnect(c, 'target daemon event channel closed'));
  stream.once('error', (e) => disconnect(c, errorMessage(e)));
  c.heartbeat = setInterval(() => {
    if ((Date.now() - c.lastSeen) > HEARTBEAT_DEAD_MS) return disconnect(c, 'target daemon heartbeat timed out');
    sendLine(c.stream, { type: 'heartbeat', at: Date.now() });
  }, HEARTBEAT_MS);
  if (c.heartbeat.unref) c.heartbeat.unref();
  // The operation which caused the FIRST connection sends its own request below.  Only reconnects restore
  // the complete desired state; restoring on the first connection would subscribe/open every item twice.
  const reconnect = c.everReady || c.restoreRequired;
  c.everReady = true;
  if (reconnect) await restoreDesired(c);
  c.restoreRequired = false;
  return c;
}

function handleClientFrame(c, msg) {
  c.lastSeen = Date.now();
  if (msg.type === 'heartbeat-ack') return;
  if (msg.type === 'response') {
    const p = c.pending.get(String(msg.id));
    if (!p) return;
    c.pending.delete(String(msg.id));
    clearTimeout(p.timer);
    if (msg.ok) p.resolve(msg.result); else p.reject(new Error(msg.error || 'target daemon request failed'));
    return;
  }
  if (msg.type === 'content-invalidate') {
    const desired = c.desiredContent.get(String(msg.subId));
    if (desired && typeof desired.onInvalidate === 'function') desired.onInvalidate(msg.event || 'change');
    return;
  }
  if (msg.type === 'tree-update') {
    const o = c.desiredOwners.get(String(msg.ownerId));
    if (o && msg.entry) opts.onTreeUpdate({ connKey: c.key, uri: o.uri, ownerId: String(msg.ownerId),
      entry: clientEntry(o.uri, msg.entry) });
  }
}

function ensureConnected(c) {
  if (c.state === 'ready') return Promise.resolve(c);
  if (c.readyP) return c.readyP;
  c.readyP = connectClient(c).catch((e) => {
    c.readyP = null; c.state = c.closing ? 'closed' : 'idle';
    if (!c.closing) notifyConnection(c, 'unavailable', errorMessage(e));
    if (c.stream && !c.stream.destroyed) c.stream.destroy(); c.stream = null;
    if (!c.closing) scheduleReconnect(c);
    throw e;
  });
  return c.readyP;
}

async function subscribeContent(uri, subId, onInvalidate) {
  const c = getClient(uri);
  const subIdString = String(subId);
  const desired = { uri, onInvalidate };
  c.desiredContent.set(subIdString, desired);
  await ensureConnected(c);
  if (c.desiredContent.get(subIdString) !== desired) return { connKey: c.key, cancelled: true };
  const resolved = await transport.remoteResolvePath(uri);
  if (c.desiredContent.get(subIdString) !== desired) return { connKey: c.key, cancelled: true };
  await request(c, 'content-subscribe', { subId: subIdString, path: resolved });
  if (c.desiredContent.get(subIdString) === desired) notifyContent(c, subIdString, true);
  return { connKey: c.key };
}
async function unsubscribeContent(uri, subId) {
  const c = clients.get(transport.connectionKey(uri));
  if (!c) return;
  c.desiredContent.delete(String(subId));
  if (c.state === 'ready') await request(c, 'content-unsubscribe', { subId: String(subId) }).catch(() => {});
  maybeClose(c);
}
async function treeOpen(uri, ownerId, openedPath) {
  const ownerIdString = String(ownerId);
  const c = getClient(uri); const o = owner(c, ownerIdString, uri); o.opens.add(openedPath);
  await ensureConnected(c);
  if (c.desiredOwners.get(ownerIdString) !== o || !o.opens.has(openedPath)) return null;
  const resolved = await transport.remoteResolvePath(openedPath);
  if (c.desiredOwners.get(ownerIdString) !== o || !o.opens.has(openedPath)) return null;
  const r = await request(c, 'tree-open', { ownerId: ownerIdString, path: resolved });
  if (c.desiredOwners.get(ownerIdString) !== o || !o.opens.has(openedPath)) return null;
  if (r && r.entry) o.deliveredOpens.add(openedPath);
  return r && r.entry ? clientEntry(uri, r.entry) : null;
}
async function cancelTreeOpen(uri, ownerId, openedPath) {
  const c = clients.get(transport.connectionKey(uri));
  if (!c) return;
  const ownerIdString = String(ownerId);
  const o = c.desiredOwners.get(ownerIdString);
  if (!o) return;
  o.opens.delete(openedPath); o.deliveredOpens.delete(openedPath);
  if (!o.opens.size && !o.roots.size && !o.expanded.size) {
    c.desiredOwners.delete(ownerIdString);
    if (c.state === 'ready') await request(c, 'release-owner', { ownerId: ownerIdString }).catch(() => {});
  }
  maybeClose(c);
}
async function treeRoots(uri, ownerId, roots) {
  const ownerIdString = String(ownerId);
  const c = getClient(uri); const o = owner(c, ownerIdString, uri); o.roots = new Set(roots || []);
  const desired = o.roots;
  await ensureConnected(c);
  if (c.desiredOwners.get(ownerIdString) !== o || o.roots !== desired)
    return { synced: false, superseded: true };
  const resolved = await Promise.all([...desired].map((root) => transport.remoteResolvePath(root)));
  if (c.desiredOwners.get(ownerIdString) !== o || o.roots !== desired)
    return { synced: false, superseded: true };
  return request(c, 'tree-roots', { ownerId: ownerIdString, roots: resolved });
}
async function treeExpanded(uri, ownerId, scopes) {
  const ownerIdString = String(ownerId);
  const c = getClient(uri); const o = owner(c, ownerIdString, uri);
  o.expanded = new Map((scopes || []).map((s) => [`${s.root}\0${s.path}`, s]));
  const desired = o.expanded;
  await ensureConnected(c);
  if (c.desiredOwners.get(ownerIdString) !== o || o.expanded !== desired)
    return { synced: false, superseded: true };
  const resolved = await Promise.all([...desired.values()].map(async (s) => (
    { root: await transport.remoteResolvePath(s.root), path: await transport.remoteResolvePath(s.path) })));
  if (c.desiredOwners.get(ownerIdString) !== o || o.expanded !== desired)
    return { synced: false, superseded: true };
  return request(c, 'tree-expanded', { ownerId: ownerIdString, scopes: resolved });
}
async function treeRefresh(uri, ownerId, root, p) {
  const c = getClient(uri); await ensureConnected(c);
  const r = await request(c, 'tree-refresh',
    { ownerId: String(ownerId), root: await transport.remoteResolvePath(root),
      path: await transport.remoteResolvePath(p) });
  return r && r.entry ? clientEntry(uri, r.entry) : null;
}
async function treeRefreshAll(uri, ownerId) {
  const c = getClient(uri); await ensureConnected(c);
  const r = await request(c, 'tree-refresh-all', { ownerId: String(ownerId) });
  return (r && r.entries || []).map((e) => clientEntry(uri, e));
}
async function releaseOwner(uri, ownerId) {
  const c = clients.get(transport.connectionKey(uri));
  if (!c) return;
  c.desiredOwners.delete(String(ownerId));
  if (c.state === 'ready') await request(c, 'release-owner', { ownerId: String(ownerId) }).catch(() => {});
  maybeClose(c);
}
function maybeClose(c) {
  if (hasDesired(c)) return;
  if (c.reconnectTimer) { clearTimeout(c.reconnectTimer); c.reconnectTimer = null; }
  if (c.heartbeat) { clearInterval(c.heartbeat); c.heartbeat = null; }
  c.closing = true;
  for (const [, p] of c.pending) {
    clearTimeout(p.timer);
    p.reject(new Error('daemon event ownership was released'));
  }
  c.pending.clear();
  if (c.stream && !c.stream.destroyed) c.stream.end();
  c.readyP = null; c.state = 'closed'; clients.delete(c.key);
}
function stopClients() {
  for (const c of clients.values()) {
    c.closing = true;
    if (c.reconnectTimer) clearTimeout(c.reconnectTimer);
    if (c.heartbeat) clearInterval(c.heartbeat);
    for (const [, p] of c.pending) { clearTimeout(p.timer); p.reject(new Error('daemon events shut down')); }
    c.pending.clear();
    if (c.stream && !c.stream.destroyed) c.stream.destroy();
  }
  clients.clear();
}
function shutdown() { stopClients(); stopServer(); }

module.exports = {
  PROTOCOL, DESCRIPTOR_REL, configure, startServer, stopServer, shutdown,
  subscribeContent, unsubscribeContent, treeOpen, cancelTreeOpen, treeRoots, treeExpanded, treeRefresh, treeRefreshAll,
  releaseOwner,
  // Pure seams used by hermetic protocol/path tests.
  _hmac: hmac, _safeEqual: safeEqual, _sftpToLocal: sftpToLocal, _localToSftp: localToSftp,
  _uriWithPath: uriWithPath, _descriptorUri: descriptorUri, _descriptorPath: descriptorPath,
  _clientCount: () => clients.size,
};
