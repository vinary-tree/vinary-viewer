'use strict';
// ssh_transport.js — the SSH/SFTP transport that powers opening remote files & directories over ssh://.
//
// Runs entirely in the trusted MAIN process (never the sandboxed renderer). It owns:
//   • a connection POOL keyed by the resolved user@host:port (one ssh2.Client reused across tabs/reads/streams),
//   • the AUTH chain — ssh-agent, identity files (with passphrase prompts), password, and full multi-prompt
//     keyboard-interactive (MFA) — driven by ssh2's authHandler,
//   • HOST-KEY verification against ~/.ssh/known_hosts with a trust-on-first-use prompt (and a hard reject of
//     a changed key), via the pure checkHostKey in ssh_config.js,
//   • ProxyJump multi-hop chaining, ~/.ssh/config resolution (Host/Match/Include), and ~user home resolution,
//   • the async SFTP API the content service consumes: remoteStat / remoteReaddir / remoteReadFile /
//     remoteReadText / remoteReadPrefix / remoteCreateReadStream; plus canonical remote-path resolution and
//     direct-tcpip streams for the authenticated daemon-event bridge; plus lifecycle (open/close/count).
//
// It is Electron-FREE: the two prompts and the error/status sinks are injected via configure(), and the home
// directory / agent socket are overridable, so the module is fully node-testable against an in-process
// ssh2.Server (test/ssh-transport-smoke.js). Only node builtins (fs/os/net/path/child_process/crypto) and ssh2.

const fs = require('fs');
const os = require('os');
const net = require('net');
const path = require('path');
const cp = require('child_process');
const { Client, utils } = require('ssh2');
const sshcfg = require('./ssh_config.js');
const sshAgent = require('./ssh_agent.js');

// ───────────────────────────── injectable configuration ─────────────────────────────

const opts = {
  // async (info:{host,port,keyType,fingerprint,changed}) → Promise<boolean> — trust an unknown/changed host key
  promptHostKey: async () => false,
  // async (req:{kind,host,user,connKey,keyPath?,prompt?,echo?,attempt}) → Promise<string|null> — a secret
  promptSecret: async () => null,
  onError: () => {},     // (info:{connKey,host,port,kind,message})
  onStatus: () => {},    // (info:{connKey,host,state})
  homeDir: null,         // override os.homedir() (tests point this at a temp ~)
  agentSock: undefined,  // override SSH_AUTH_SOCK ('' disables the agent; undefined → env)
  maxBytes: 1024 * 1024 * 1024,   // whole-file read ceiling (1 GiB); large logs/text stream instead
  idleMs: 5 * 60 * 1000,          // reap a pooled connection after this long idle with no open streams
  systemConfigPath: '/etc/ssh/ssh_config',        // overridable ('' skips) so tests stay hermetic
  systemKnownHostsPath: '/etc/ssh/ssh_known_hosts',
};

function configure(o) { Object.assign(opts, o || {}); }

// This module is Electron-FREE and its error/status sinks are INJECTED via configure(). A sink must never be able
// to turn a transport event into an uncaught main-process exception — especially one fired from a keepalive/idle
// TIMER (client 'error'/'close' emitted by ssh2's sendKA), where a throw has no catch above it and crashes main.
// Every sink invocation goes through these guards; a broken sink is swallowed (there is no safe channel to report
// it on without risking the same crash).
function safeError(info) { try { opts.onError(info); } catch (_e) { /* a broken sink must not crash the transport */ } }
function safeStatus(info) { try { opts.onStatus(info); } catch (_e) { /* a broken sink must not crash the transport */ } }

function homeDir() { return opts.homeDir || os.homedir(); }
function osUser() { try { return os.userInfo().username; } catch (_e) { return process.env.USER || process.env.USERNAME || null; } }
function agentSock() { return opts.agentSock !== undefined ? opts.agentSock : process.env.SSH_AUTH_SOCK; }
// The username every auth method is offered under (ssh2 reads `method.username` per attempt) — must match the
// connect username.
function connUsername(resolved) { return resolved.user || osUser() || undefined; }

function isRemoteUri(uri) {
  const s = String(uri);
  return s.startsWith('ssh://') || s.startsWith('sftp://');
}

// ───────────────────────────── ssh_config + known_hosts loading (fs) ─────────────────────────────

function readTextSafe(p) { try { return fs.readFileSync(p, 'utf8'); } catch (_e) { return null; } }
function readBufSafe(p) { try { return fs.readFileSync(p); } catch (_e) { return null; } }

function globToRe(base) {
  let re = '^';
  for (const ch of base) {
    if (ch === '*') re += '[^/]*';
    else if (ch === '?') re += '[^/]';
    else re += ch.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(re + '$');
}

// Resolve an Include glob to concrete files (~ + relative-to-baseDir; a '*'/'?' glob in the basename).
function globFiles(pattern, baseDir) {
  let pat = sshcfg.expandTilde(pattern, homeDir());
  if (!path.isAbsolute(pat)) pat = path.join(baseDir, pat);
  if (!/[*?]/.test(pat)) return fs.existsSync(pat) ? [pat] : [];
  const dir = path.dirname(pat);
  const re = globToRe(path.basename(pat));
  let names;
  try { names = fs.readdirSync(dir); } catch (_e) { return []; }
  return names.filter((nm) => re.test(nm)).sort().map((nm) => path.join(dir, nm));
}

function includeLoader(baseDir) {
  return (glob) => globFiles(glob, baseDir).map((p) => ({ path: p, text: readTextSafe(p) || '' }));
}

function loadConfigNodes() {
  const userCfg = path.join(homeDir(), '.ssh', 'config');
  const sysCfg = opts.systemConfigPath;
  const userText = readTextSafe(userCfg);
  const sysText = readTextSafe(sysCfg);
  const userNodes = userText ? sshcfg.expandIncludes(sshcfg.parseSshConfig(userText), includeLoader(path.dirname(userCfg))) : [];
  const sysNodes = sysText ? sshcfg.expandIncludes(sshcfg.parseSshConfig(sysText), includeLoader('/etc/ssh')) : [];
  return userNodes.concat(sysNodes);   // user config first → first-value-wins prefers it (OpenSSH order)
}

// Run every `Match exec` command once (bounded), keyed by the ORIGINAL (un-expanded) arg so the pure matcher
// can look them up. Exit 0 → the criterion matches.
function buildExecResults(nodes, tokenCtx) {
  const results = {};
  for (const n of nodes) {
    if (n.type !== 'match') continue;
    for (const c of n.criteria) {
      if (c.keyword === 'exec' && c.arg && !(c.arg in results)) {
        try {
          // `Match exec` runs a shell command by design — this is the user's OWN ~/.ssh/config (the same file
          // OpenSSH executes via /bin/sh), not remote or document-supplied input; a shell is the correct, spec
          // behavior. Bounded by a 5s timeout; a non-zero exit (or timeout) → the criterion does not match.
          cp.execSync(sshcfg.expandTokens(c.arg, tokenCtx), { stdio: 'ignore', timeout: 5000 });
          results[c.arg] = true;
        } catch (_e) { results[c.arg] = false; }
      }
    }
  }
  return results;
}

const resolveCache = new Map();   // authority → {resolved, ts}

function resolveForUri(uri) {
  const desc = sshcfg.parseSshUri(uri);
  const authority = `${desc.user || ''}@${desc.rawHost}:${desc.port || ''}`;
  const hit = resolveCache.get(authority);
  if (hit && (Date.now() - hit.ts) < 60000) return hit.resolved;
  const nodes = loadConfigNodes();
  const localUser = osUser();
  const tokenCtx = { host: desc.rawHost, originalHost: desc.rawHost, user: desc.user, port: desc.port, localUser };
  const execResults = buildExecResults(nodes, tokenCtx);
  const resolved = sshcfg.resolveDescriptor(desc, {
    configNodes: nodes, osUser: localUser, localUser, home: homeDir(), execResults,
    localHost: os.hostname(), localHostShort: String(os.hostname()).split('.')[0],
  });
  resolveCache.set(authority, { resolved, ts: Date.now() });
  return resolved;
}

// ───────────────────────────── host-key verification (TOFU) ─────────────────────────────

function loadKnownHosts() {
  const paths = [
    path.join(homeDir(), '.ssh', 'known_hosts'),
    path.join(homeDir(), '.ssh', 'known_hosts2'),
    opts.systemKnownHostsPath,
  ];
  let all = [];
  for (const p of paths) {
    const t = readTextSafe(p);
    if (t) all = all.concat(sshcfg.parseKnownHosts(t));
  }
  return all;
}

function appendKnownHost(host, port, keyType, keyB64) {
  const p = path.join(homeDir(), '.ssh', 'known_hosts');
  try {
    fs.mkdirSync(path.dirname(p), { recursive: true, mode: 0o700 });
    fs.appendFileSync(p, sshcfg.knownHostsLine(host, port, keyType, keyB64) + '\n', { mode: 0o600 });
  } catch (e) {
    safeError({ connKey: sshcfg.connKeyOf(null, host, port), host, port, kind: 'sftp-error', message: 'could not write known_hosts: ' + e.message });
  }
}

function makeHostVerifier(resolved) {
  const { host, port } = resolved;
  return (keyBuffer, callback) => {
    let keyType, keyB64, fp;
    try {
      const parsed = utils.parseKey(keyBuffer);
      const k = Array.isArray(parsed) ? parsed[0] : parsed;
      if (k instanceof Error) throw k;
      keyType = k.type;
      keyB64 = keyBuffer.toString('base64');
      fp = sshcfg.fingerprintSha256(keyBuffer);
    } catch (_e) {
      safeError({ connKey: resolved.connKey, host, port, kind: 'hostkey-rejected', message: 'could not parse host key' });
      return callback(false);
    }
    const status = sshcfg.checkHostKey(loadKnownHosts(), host, port, keyType, keyB64);
    if (status === 'ok') return callback(true);
    if (status === 'changed') {
      safeError({ connKey: resolved.connKey, host, port, kind: 'hostkey-changed', message: `REMOTE HOST IDENTIFICATION FOR ${host} HAS CHANGED (${fp}) — refusing to connect` });
      return callback(false);
    }
    if (status === 'revoked') {
      safeError({ connKey: resolved.connKey, host, port, kind: 'hostkey-rejected', message: `host key for ${host} is revoked` });
      return callback(false);
    }
    Promise.resolve(opts.promptHostKey({ host, port, keyType, fingerprint: fp, changed: false }))
      .then((accepted) => {
        if (accepted) { appendKnownHost(host, port, keyType, keyB64); callback(true); }
        else { safeError({ connKey: resolved.connKey, host, port, kind: 'hostkey-rejected', message: 'host key not trusted' }); callback(false); }
      })
      .catch(() => callback(false));
  };
}

// ───────────────────────────── authentication ─────────────────────────────

// Add a just-decrypted key to the running agent when AddKeysToAgent asks (yes/ask/confirm/<lifetime>).
function maybeAddKeyToAgent(resolved, parsedKey, keyPath) {
  const directive = resolved.addKeysToAgent;
  if (!directive || /^no$/i.test(directive)) return;
  const sock = agentSock();
  if (!sock) return;
  let lifetime = 0, confirm = false;
  if (/^confirm/i.test(directive)) confirm = true;
  const timeMatch = /(\d+)([smhdw]?)/.exec(directive);
  if (timeMatch && !/^(yes|ask|confirm|no)$/i.test(directive)) {
    const mult = { s: 1, m: 60, h: 3600, d: 86400, w: 604800 }[timeMatch[2] || 's'] || 1;
    lifetime = parseInt(timeMatch[1], 10) * mult;
  }
  sshAgent.addKey(sock, parsedKey, path.basename(keyPath), { lifetime, confirm })
    .catch((e) => safeError({ connKey: resolved.connKey, host: resolved.host, port: resolved.port, kind: 'sftp-error', message: 'AddKeysToAgent failed: ' + e.message }));
}

async function produceKeyMethod(conn, resolved, keyPath) {
  const raw = readBufSafe(keyPath);
  if (!raw) return null;                       // no such identity file → skip
  let key = utils.parseKey(raw);
  if (key instanceof Error) {
    if (!/encrypted|passphrase/i.test(key.message)) return null;   // unparseable (not an encryption issue) → skip
    for (let attempt = 1; attempt <= 3; attempt++) {
      let pass = conn.secrets.passphrases.get(keyPath);
      if (pass == null) {
        pass = await opts.promptSecret({ kind: 'passphrase', host: resolved.host, user: resolved.user, connKey: resolved.connKey, keyPath, attempt });
      }
      if (pass == null) return null;           // cancelled → skip this key
      const k2 = utils.parseKey(raw, pass);
      if (!(k2 instanceof Error)) { conn.secrets.passphrases.set(keyPath, pass); key = k2; maybeAddKeyToAgent(resolved, Array.isArray(k2) ? k2[0] : k2, keyPath); break; }
      conn.secrets.passphrases.delete(keyPath);
      if (attempt === 3) return null;          // exhausted attempts → skip
    }
  }
  if (key instanceof Error) return null;
  return { type: 'publickey', username: connUsername(resolved), key: Array.isArray(key) ? key[0] : key };
}

async function producePasswordMethod(conn, resolved, attempt) {
  if (conn.secrets.pwCancelled) return null;
  let pass = (attempt === 1) ? conn.secrets.password : null;   // reuse a cached password only on the 1st try (reconnects)
  if (pass == null) {
    pass = await opts.promptSecret({ kind: 'password', host: resolved.host, user: resolved.user, connKey: resolved.connKey, attempt });
  }
  if (pass == null) { conn.secrets.pwCancelled = true; return null; }
  conn.secrets.password = pass;
  return { type: 'password', username: connUsername(resolved), password: pass };
}

function buildAuthCandidates(conn, resolved) {
  const username = connUsername(resolved);
  const cands = [{ ssh: 'none', produce: async () => ({ type: 'none', username }) }];   // probe → learn methodsLeft
  const sock = agentSock();
  if (sock && !resolved.identitiesOnly) {
    cands.push({ ssh: 'publickey', produce: async () => ({ type: 'agent', username, agent: sock }) });
  }
  const idFiles = resolved.identityFiles.slice();
  if (!resolved.identitiesOnly) {
    for (const def of ['id_ed25519', 'id_ecdsa', 'id_rsa', 'id_dsa']) {
      const p = path.join(homeDir(), '.ssh', def);
      if (!idFiles.includes(p)) idFiles.push(p);
    }
  }
  for (const kp of idFiles) cands.push({ ssh: 'publickey', produce: () => produceKeyMethod(conn, resolved, kp) });
  // ssh2 skips a keyboard-interactive method whose object has no `prompt` FUNCTION (client.js) — so the
  // multi-prompt (MFA) handler lives in the method object itself, not a separate client 'keyboard-interactive'
  // listener (which ssh2 only wires for the bare-string shorthand).
  cands.push({ ssh: 'keyboard-interactive',
               produce: async () => ({ type: 'keyboard-interactive', username, prompt: makeKeyboardHandler(conn, resolved) }) });
  for (let a = 1; a <= 3; a++) cands.push({ ssh: 'password', produce: () => producePasswordMethod(conn, resolved, a) });
  return cands;
}

function makeAuthHandler(conn, resolved) {
  const cands = buildAuthCandidates(conn, resolved);
  let idx = 0;
  return (methodsLeft, _partialSuccess, callback) => {
    const tryNext = () => {
      if (idx >= cands.length) return callback(false);
      const cand = cands[idx++];
      if (methodsLeft && cand.ssh !== 'none' && !methodsLeft.includes(cand.ssh)) return tryNext();
      Promise.resolve(cand.produce()).then((m) => (m ? callback(m) : tryNext())).catch(() => tryNext());
    };
    tryNext();
  };
}

function makeKeyboardHandler(conn, resolved) {
  return (_name, _instructions, _lang, prompts, finish) => {
    (async () => {
      const answers = [];
      for (const p of prompts) {
        const ans = await opts.promptSecret({
          kind: 'keyboard-interactive', host: resolved.host, user: resolved.user, connKey: resolved.connKey,
          prompt: p.prompt, echo: !!p.echo,
        });
        if (ans == null) return finish([]);
        answers.push(ans);
      }
      finish(answers);
    })();
  };
}

// ───────────────────────────── connection pool ─────────────────────────────

const pool = new Map();   // connKey → conn

function newConn(resolved) {
  return {
    connKey: resolved.connKey, host: resolved.host, user: resolved.user, port: resolved.port, resolved,
    client: null, state: 'connecting', readyP: null, sftpP: null,
    secrets: { password: null, passphrases: new Map(), pwCancelled: false },
    homes: { self: null, users: {} }, openStreams: new Set(), lastUsed: Date.now(), lastError: null,
  };
}

function destroyStreams(conn) {
  // Destroy WITH an error so an in-flight read surfaces the drop rather than a silent EOF (a silent close would
  // look like a complete read and truncate content undetected — the streaming layer flags it partial instead).
  const err = new Error('SSH connection lost');
  for (const s of conn.openStreams) { try { s.destroy(err); } catch (_e) { /* already gone */ } }
  conn.openStreams.clear();
}

// Remove THIS connection from the pool — never a successor pooled under the same key since. ssh2 delivers a
// connection's 'error' and 'close' asynchronously: after closeAll / closeConnection / the idle reaper (which already
// unpooled it), or after its own 'error', a reconnect can pool the successor — getConn pools it BEFORE its handshake —
// before the old connection's 'close' arrives. Deleting by key there evicted the successor: it stayed open, unpooled
// and unreachable (never closed), and the next request dialled a third connection — a second password prompt.
function unpool(conn) {
  if (pool.get(conn.connKey) === conn) pool.delete(conn.connKey);
}

function closeConn(conn) {
  try { if (conn.client) conn.client.end(); } catch (_e) { /* already closed */ }
  destroyStreams(conn);
  conn.state = 'closed';
  conn.sftpP = null;
}

function errInfo(err, resolved) {
  const msg = (err && err.message) || String(err);
  let kind = 'dropped';
  if (err) {
    if (err.level === 'client-authentication' || /authentication methods failed/i.test(msg)) kind = 'auth-failed';
    else if (['ECONNREFUSED', 'ENOTFOUND', 'EHOSTUNREACH', 'ENETUNREACH'].includes(err.code)) kind = 'unreachable';
    else if (/timed out|timeout/i.test(msg)) kind = 'timeout';
  }
  return { connKey: resolved.connKey, host: resolved.host, port: resolved.port, kind, message: msg };
}

function forwardOut(client, host, port) {
  return new Promise((resolve, reject) => {
    client.forwardOut('127.0.0.1', 0, host, port, (err, stream) => (err ? reject(err) : resolve(stream)));
  });
}

// Build the socket that reaches `resolved`'s host through its ProxyJump chain (each hop pooled + authed).
async function makeProxySock(resolved, depth) {
  let sock;
  const hops = resolved.proxyJump;
  for (let i = 0; i < hops.length; i++) {
    const hop = hops[i];
    const hopUri = `ssh://${hop.user ? encodeURIComponent(hop.user) + '@' : ''}${hop.host}${hop.port ? ':' + hop.port : ''}/`;
    const hopConn = await getConn(hopUri, sock, depth + 1);
    const next = hops[i + 1];
    const [th, tp] = next ? [next.host, next.port || 22] : [resolved.host, resolved.port];
    sock = await forwardOut(hopConn.client, th, tp);
  }
  return sock;
}

async function connect(conn, resolved, inboundSock, depth) {
  if (depth > 10) throw new Error('ProxyJump chain too deep (possible loop)');
  let sock = inboundSock;
  if (!sock && resolved.proxyJump) sock = await makeProxySock(resolved, depth);

  const client = new Client();
  conn.client = client;
  safeStatus({ connKey: conn.connKey, host: resolved.host, state: 'connecting' });

  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = (err) => { if (!settled) { settled = true; reject(err); } };
    client.on('ready', () => {
      conn.state = 'ready'; conn.lastUsed = Date.now();
      safeStatus({ connKey: conn.connKey, host: resolved.host, state: 'ready' });
      settled = true; resolve(conn);
    });
    client.on('error', (err) => {
      conn.state = 'error'; conn.lastError = err; unpool(conn); destroyStreams(conn);
      safeError(errInfo(err, resolved)); fail(err);
    });
    client.on('close', () => {
      const wasReady = conn.state === 'ready';
      conn.state = 'closed'; unpool(conn); destroyStreams(conn); conn.sftpP = null;
      if (wasReady) safeStatus({ connKey: conn.connKey, host: resolved.host, state: 'closed' });
      fail(new Error('connection closed'));
    });

    const connectOpts = {
      host: resolved.host, port: resolved.port, username: resolved.user || osUser() || undefined,
      tryKeyboard: true, keepaliveInterval: 15000, keepaliveCountMax: 3, readyTimeout: 25000,
      hostVerifier: makeHostVerifier(resolved), authHandler: makeAuthHandler(conn, resolved),
    };
    if (sock) connectOpts.sock = sock;
    try {
      client.connect(connectOpts);
      // TCP_NODELAY (Nagle off) — ssh2 never sets it. With Nagle on, every SFTP request/response turn waits out
      // the peer's delayed-ACK timer (~40 ms on Linux): a whole-file read issues one request per 64 KiB, so a
      // 1 MiB read on a LAN-near host took ~2 s instead of ~0.2 s (docs/scientific/11 H0/E0, ADR-0046). OpenSSH's
      // sshd already disables Nagle on its side; this is the client half. ssh2 created the socket synchronously in
      // connect(), so this also covers the handshake; a ProxyJump hop's forwarded channel has no TCP socket and
      // ssh2 skips it (its own hop's TCP connection is set when that hop connects).
      client.setNoDelay(true);
    } catch (e) { unpool(conn); fail(e); }
  });
}

function getConn(uri, inboundSock, depth) {
  depth = depth || 0;
  let resolved;
  try { resolved = resolveForUri(uri); } catch (e) { return Promise.reject(e); }
  const existing = pool.get(resolved.connKey);
  if (existing && existing.state === 'ready') { existing.lastUsed = Date.now(); return Promise.resolve(existing); }
  if (existing && existing.state === 'connecting' && existing.readyP) return existing.readyP;
  const conn = newConn(resolved);
  pool.set(resolved.connKey, conn);
  conn.readyP = connect(conn, resolved, inboundSock, depth);
  return conn.readyP;
}

// ───────────────────────────── SFTP session + path resolution ─────────────────────────────

function getSftp(conn) {
  if (!conn.sftpP) {
    conn.sftpP = new Promise((resolve, reject) => {
      conn.client.sftp((err, sftp) => (err ? reject(err) : resolve(sftp)));
    });
  }
  return conn.sftpP;
}

function execCapture(client, cmd) {
  return new Promise((resolve) => {
    try {
      client.exec(cmd, (err, stream) => {
        if (err) return resolve(null);
        let out = '';
        stream.on('data', (d) => { out += d.toString('utf8'); });
        stream.stderr.on('data', () => {});
        stream.on('close', () => resolve(out));
        stream.on('error', () => resolve(null));
      });
    } catch (_e) { resolve(null); }
  });
}

function sftpRealpath(sftp, p) {
  return new Promise((resolve) => sftp.realpath(p, (e, rp) => resolve(e ? null : rp)));
}

async function ensureHomes(conn, sftp, wantUser) {
  if (!conn.homes.self) conn.homes.self = (await sftpRealpath(sftp, '.')) || null;
  if (wantUser && conn.homes.users[wantUser] === undefined) {
    const out = await execCapture(conn.client, `echo ~${wantUser}`);
    const home = out && out.trim() && !out.includes('~') ? out.trim() : null;
    conn.homes.users[wantUser] = home;
  }
}

// Resolve a remote uri to {conn, sftp, path} — establishing the connection, the SFTP channel, and the concrete
// remote path (absolute, or ~/~user expanded against realpath('.')/`echo ~user`).
async function resolvePath(uri) {
  const desc = sshcfg.parseSshUri(uri);
  const conn = await getConn(uri);
  const sftp = await getSftp(conn);
  // A ~user path needs a remote `echo ~user` to find that user's home. `user` comes from the URI, which an
  // untrusted document link controls, so it is validated to a strict POSIX username BEFORE it can reach the
  // remote shell — no shell metacharacters can be smuggled into the exec. A non-matching ~name is left literal
  // (SFTP will simply not find it), never executed.
  const um = /^~([^/]+)(?:\/|$)/.exec(desc.path);
  const wantUser = um && /^[A-Za-z_][A-Za-z0-9_-]*$/.test(um[1]) ? um[1] : null;
  await ensureHomes(conn, sftp, wantUser);
  return { conn, sftp, path: sshcfg.resolveSftpPath(desc.path, conn.homes) };
}

function lstat(sftp, p) { return new Promise((res, rej) => sftp.lstat(p, (e, s) => (e ? rej(e) : res(s)))); }
function statFollow(sftp, p) { return new Promise((res, rej) => sftp.stat(p, (e, s) => (e ? rej(e) : res(s)))); }
function readdirRaw(sftp, p) { return new Promise((res, rej) => sftp.readdir(p, (e, l) => (e ? rej(e) : res(l)))); }

function toStat(s, symlink) {
  return {
    size: s.size, mtime: (s.mtime || 0) * 1000,
    isDirectory: s.isDirectory(), isFile: s.isFile(), symlink: !!symlink, mode: s.mode,
  };
}

// ───────────────────────────── child-URI construction ─────────────────────────────

function remotePartsJs(uri) {
  const m = /^(s(?:sh|ftp):\/\/[^/]*)(\/.*)?$/i.exec(String(uri));
  return m ? [m[1], m[2] || '/'] : [String(uri), '/'];
}

function childUri(parentUri, name) {
  const [prefix, p] = remotePartsJs(parentUri);
  const base = p === '/' ? '' : p.replace(/\/+$/, '');
  return prefix + base + '/' + encodeURIComponent(name);
}

// ───────────────────────────── the public SFTP API ─────────────────────────────

async function remoteStat(uri) {
  const { sftp, path: p } = await resolvePath(uri);
  const l = await lstat(sftp, p);
  const sym = l.isSymbolicLink && l.isSymbolicLink();
  if (sym) {
    const followed = await statFollow(sftp, p).catch(() => null);
    if (followed) return toStat(followed, true);
  }
  return toStat(l, sym);
}

async function remoteReaddir(uri) {
  const { sftp, path: p } = await resolvePath(uri);
  const list = await readdirRaw(sftp, p);
  const out = [];
  for (const e of list) {
    const a = e.attrs;
    const sym = a.isSymbolicLink && a.isSymbolicLink();
    let dir = a.isDirectory(); let size = a.size; let mtime = (a.mtime || 0) * 1000;
    if (sym) {
      const followed = await statFollow(sftp, sshcfg.joinPosix(p, e.filename)).catch(() => null);
      if (followed) { dir = followed.isDirectory(); size = followed.size; mtime = (followed.mtime || 0) * 1000; }
    }
    out.push({ name: e.filename, path: childUri(uri, e.filename), dir, size, mtime, symlink: !!sym });
  }
  return out;
}

// ───────────────────────────── whole-file and ranged reads (ADR-0046 §Faster remote loading) ─────────────────────────────
//
// WHY. ssh2's createReadStream keeps ONE request in flight, so reading a whole file costs a network round trip per
// 64 KiB — three, against a server whose ident caps reads at 31 952 bytes. Experiment 11 measured that reader within
// 1–2 % of exactly that model (H1, docs/scientific/11). A file HANDLE lets one read keep D requests of C bytes in
// flight instead (H2): round trips fall to ⌈S/(D·C)⌉ until the SSH channel window caps the bytes in flight.

// The pre-ADR-0046 whole-file reader — ssh2's createReadStream (one request in flight), concatenated — kept as the
// measured BASELINE: scripts/bench-remote-read.cjs times it as `stream-legacy` (via _readFileStream).
function readAll(stream, max) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    stream.on('data', (c) => {
      total += c.length;
      if (total > max) { stream.destroy(); reject(new Error(`remote file exceeds the ${max}-byte read limit`)); return; }
      chunks.push(c);
    });
    stream.on('error', reject);
    stream.on('end', () => resolve(Buffer.concat(chunks)));
  });
}

async function readFileStream(uri, o) {
  o = o || {};
  const { sftp, path: p } = await resolvePath(uri);
  return readAll(sftp.createReadStream(p), o.maxBytes || opts.maxBytes);
}

const READ_LEN_FALLBACK = 31952;              // ssh2's read cap for a non-OpenSSH ident (34 000 − 2 048 overhead)
const PIPELINE_BYTES = 2 * 1024 * 1024;       // bytes kept in flight ≈ ssh2's default SSH channel window
const MIN_DEPTH = 4;
const MAX_DEPTH = 64;

// ssh2's per-request read cap — a PRIVATE field, `_maxReadLen`: 260 096 for an OpenSSH/dropbear ident (or what the
// server's limits@openssh.com says), else 31 952. ssh2 splits a larger request into SEQUENTIAL sub-reads, which
// would serialize a pipelined reader, so a chunk never exceeds it. Guarded: should ssh2 rename the field, the safe
// minimum keeps every read correct, only smaller (test/ssh-transport-smoke.js watches the field).
function maxReadLenOf(sftp) {
  const n = sftp && sftp._maxReadLen;
  return Number.isInteger(n) && n > 0 ? n : READ_LEN_FALLBACK;
}

// The pipeline's shape: chunk C = min(requested, L); depth D = clamp(⌈2 MiB / C⌉, 4, 64) unless asked.
function pipelineShape(sftp, o) {
  const L = maxReadLenOf(sftp);
  const chunk = Math.max(1, Math.min(o && o.chunk > 0 ? o.chunk : L, L));
  const depth = o && o.depth > 0 ? o.depth
    : Math.min(MAX_DEPTH, Math.max(MIN_DEPTH, Math.ceil(PIPELINE_BYTES / chunk)));
  return { chunk, depth };
}

// Fill buf[0, len) from file offset `base` with at most `depth` requests of at most `chunk` bytes in flight.
// Resolves to the bytes filled: `len`, or the offset (relative to base) at which the file ENDED, when it ends first.
//   • `splitAt` forces a request boundary there: readAll puts the file's expected end at it, so a file of exactly
//     that size fills its body with whole requests and ends with ONE request starting at `splitAt` that reads nothing
//     — end-of-file confirmed in the same round trip as the body, not one later.
//   • A SHORT read re-requests its remainder. If that remainder then returns data, the server caps its reads (a
//     server answering fewer bytes than asked), and every later request shrinks to the first short read's size; if
//     it returns nothing, the file simply ended there — a short read at end-of-file never shrinks the pipeline.
//   • On an error no new request is issued; the promise rejects once those in flight have settled — so `buf` is
//     never written after this returns.
function pipelinedFill(file, buf, base, len, shape, splitAt) {
  return new Promise((resolve, reject) => {
    let chunk = shape.chunk;
    const queue = [];                       // [offset, length, size of the short read this re-requests, or 0]
    const cut = Math.max(0, Math.min(len, splitAt === undefined ? len : splitAt));
    for (let off = 0; off < cut; off += chunk) queue.push([off, Math.min(chunk, cut - off), 0]);
    for (let off = cut; off < len; off += chunk) queue.push([off, Math.min(chunk, len - off), 0]);
    let inFlight = 0;
    let end = len;            // the smallest offset at which a read found end-of-file
    let error = null;
    let settled = false;
    const pump = () => {
      if (settled) return;
      if (error) {
        if (inFlight === 0) { settled = true; reject(error); }
        return;
      }
      while (inFlight < shape.depth && queue.length) {
        let [off, n, after] = queue.shift();
        if (off >= end) continue;                                   // past the known end of the file
        if (n > chunk) { queue.unshift([off + chunk, n - chunk, 0]); n = chunk; }   // honour a shrunken chunk
        inFlight += 1;
        file.readAt(buf, off, n, base + off).then((got) => {
          inFlight -= 1;
          if (got === 0) end = Math.min(end, off);
          else {
            if (after > 0) chunk = Math.max(1, Math.min(chunk, after));   // data after a short read: a capping server
            if (got < n) queue.unshift([off + got, n - got, got]);
          }
          pump();
        }, (err) => {
          inFlight -= 1;
          error = error || err;
          pump();
        });
      }
      if (!error && inFlight === 0) { settled = true; resolve(end); }
    };
    pump();
  });
}

// The bytes [0, n) of `buf` as the result: the buffer itself when it is (nearly) full, else a right-sized COPY — a
// file that shrank must not pin a much larger allocation, and no byte past n (never written) is ever returned.
function filledPart(buf, n) {
  if (n === buf.length) return buf;
  return n >= buf.length * 0.75 ? buf.subarray(0, n) : Buffer.from(buf.subarray(0, n));
}

// A remote file HANDLE: one OPEN, then random-access reads (readAt, readRange, readAll), fstat, and close. It is
// registered in its connection's openStreams like a stream: a dropped connection REJECTS its pending requests —
// ssh2 silently drops writes on a dead channel, so they would otherwise never settle — and the idle reaper leaves
// the connection alone while the handle is open. Callers close it in a `finally`.
async function remoteOpenFile(uri) {
  const { conn, sftp, path: p } = await resolvePath(uri);
  const handle = await new Promise((resolve, reject) => sftp.open(p, 'r', (e, h) => (e ? reject(e) : resolve(h))));
  const pending = new Set();
  let dropped = null;
  let closed = false;
  const file = {
    uri,
    destroy(err) {                                   // the connection dropped (destroyStreams)
      dropped = dropped || err || new Error('SSH connection lost');
      for (const reject of pending) reject(dropped);
      pending.clear();
    },
    dropped: () => dropped,
  };
  conn.openStreams.add(file);
  conn.lastUsed = Date.now();
  // one SFTP request → a Promise, rejected at once if the connection drops
  const request = (issue) => new Promise((resolve, reject) => {
    if (dropped) { reject(dropped); return; }
    if (closed) { reject(new Error('remote file handle is closed')); return; }
    pending.add(reject);
    conn.lastUsed = Date.now();
    issue((err, ...results) => {
      if (!pending.delete(reject)) return;          // already rejected by a drop
      if (err) reject(err); else resolve(results);
    });
  });
  // read `len` bytes at `pos` into buf[off…]: the number of bytes read — 0 at end-of-file (ssh2 reports EOF that way)
  file.readAt = (buf, off, len, pos) => request((cb) => sftp.read(handle, buf, off, len, pos, cb)).then(([n]) => n || 0);
  file.fstat = () => request((cb) => sftp.fstat(handle, cb)).then(([st]) => toStat(st, false));
  // `len` bytes at `pos` — fewer when the file ends first
  file.readRange = async (pos, len, o) => {
    const buf = Buffer.allocUnsafe(Math.max(0, len));
    return filledPart(buf, await pipelinedFill(file, buf, pos, buf.length, pipelineShape(sftp, o)));
  };
  // the whole file (see readAllFrom)
  file.readAll = (o) => readAllFrom(file, sftp, o);
  file.close = async () => {
    if (closed) return;
    try {
      if (!dropped) await request((cb) => sftp.close(handle, cb));
    } catch (_e) {
      /* the handle dies with its session */
    } finally {
      closed = true;
      conn.openStreams.delete(file);
      conn.lastUsed = Date.now();
    }
  };
  return file;
}

// Read a whole remote file through an open handle, PREALLOCATED and PIPELINED.
//   • size: o.size when the caller already stat'ed the file (no extra round trip), else fstat(handle);
//   • a file over the cap (o.maxBytes, default opts.maxBytes) is refused before a single READ;
//   • the buffer is size + one chunk, filled in ONE pipeline — the extra chunk, a request of its own starting
//     exactly at `size`, is the end-of-file probe, overlapped with the body and inside the same depth limit: the file
//     is exactly `size` when it reads nothing (one round trip for body and EOF together), shorter when an earlier
//     read hit end-of-file, LONGER when the probe chunk came back full — then it is read on, doubling the buffer, to
//     its real end (still capped);
//   • an SFTP-level refusal (a STATUS reply — ssh2 gives it a numeric `code`) retries once at depth 1: a server that
//     cannot serve many requests at once. A transport failure — the connection dropped (EPIPE, ECONNRESET, or the
//     handle destroyed by destroyStreams) — never retries: the next request would fail the same way.
async function readAllFrom(file, sftp, o) {
  o = o || {};
  const maxBytes = o.maxBytes || opts.maxBytes;
  const size = Number.isInteger(o.size) && o.size >= 0 ? o.size : (await file.fstat()).size;
  if (size > maxBytes) throw new Error(`remote file exceeds the ${maxBytes}-byte read limit`);
  const shape = pipelineShape(sftp, o);
  const attempt = async (depth) => {
    const s = { chunk: shape.chunk, depth };
    let buf = Buffer.allocUnsafe(size + s.chunk);
    let filled = await pipelinedFill(file, buf, 0, buf.length, s, size);
    while (filled === buf.length) {                     // the file grew past the probe: read on to its end
      if (filled > maxBytes) throw new Error(`remote file exceeds the ${maxBytes}-byte read limit`);
      const grown = Buffer.allocUnsafe(Math.min(maxBytes + 1, buf.length * 2));
      buf.copy(grown, 0, 0, filled);
      buf = grown;
      filled += await pipelinedFill(file, buf.subarray(filled), filled, buf.length - filled, s);
    }
    if (filled > maxBytes) throw new Error(`remote file exceeds the ${maxBytes}-byte read limit`);
    return filledPart(buf, filled);
  };
  try {
    return await attempt(shape.depth);
  } catch (e) {
    const refused = e && typeof e.code === 'number';     // an SFTP STATUS, not a socket error
    if (!refused || file.dropped() || shape.depth === 1) throw e;
    return attempt(1);
  }
}

// The read helpers below do not WAIT for the handle's CLOSE: it completes in the background (the handle stays in
// openStreams until it has, so the reaper still leaves the connection alone), and a read resolves a round trip
// sooner — the legacy stream likewise resolved at end-of-stream, before its handle closed.
const closeSoon = (file) => { file.close().catch(() => {}); };

async function remoteReadFile(uri, o) {
  o = o || {};
  const file = await remoteOpenFile(uri);
  try {
    return await file.readAll({ size: o.size, maxBytes: o.maxBytes });
  } finally {
    closeSoon(file);
  }
}

async function remoteReadText(uri, o) {
  o = o || {};
  return (await remoteReadFile(uri, o)).toString(o.encoding || 'utf8');
}

async function remoteReadPrefix(uri, nBytes) {
  const file = await remoteOpenFile(uri);
  try {
    return (await file.readRange(0, Math.max(0, nBytes))).toString('utf8');
  } finally {
    closeSoon(file);
  }
}

// The whole-file reader with an explicit pipeline shape — the benchmark's `pipelined:D:C` (docs/scientific/11 E1/E3).
async function readAllPipelined(uri, o) {
  const file = await remoteOpenFile(uri);
  try {
    return await file.readAll({ depth: o && o.depth, chunk: o && o.chunk, size: o && o.size });
  } finally {
    closeSoon(file);
  }
}

// A Node Readable over a remote file — a drop-in for fs.createReadStream so the streaming/paging engines are
// unchanged. Registered against the connection so the idle reaper won't close it mid-stream.
async function remoteCreateReadStream(uri, o) {
  o = o || {};
  const { conn, sftp, path: p } = await resolvePath(uri);
  const stream = sftp.createReadStream(p, o);
  conn.openStreams.add(stream);
  const done = () => conn.openStreams.delete(stream);
  stream.on('close', done); stream.on('end', done); stream.on('error', done);
  return stream;
}

// Resolve the SFTP-visible path behind a remote URI.  The daemon-event bridge needs the same canonical
// namespace as ordinary reads so that a target daemon can map an opened ssh:// URI onto its local filesystem.
// No connection internals or credentials escape; callers receive only the already-authorized remote path.
async function remoteResolvePath(uri) {
  const { path: p } = await resolvePath(uri);
  return p;
}

// Open a direct-tcpip channel to a service bound on the TARGET host.  This is the cross-platform transport for
// daemon events: unlike OpenSSH stream-local forwarding it works for Linux, macOS, and Windows targets.  The
// target service itself binds loopback only, and daemon_events.js performs a separate authenticated handshake.
// Register the channel as an open stream so the SSH idle reaper cannot close a connection carrying live watches.
async function remoteForwardOut(uri, host, port) {
  const conn = await getConn(uri);
  return new Promise((resolve, reject) => {
    try {
      conn.client.forwardOut('127.0.0.1', 0, host, port, (err, stream) => {
        if (err) return reject(err);
        conn.lastUsed = Date.now();
        conn.openStreams.add(stream);
        const done = () => conn.openStreams.delete(stream);
        stream.on('close', done); stream.on('end', done); stream.on('error', done);
        resolve(stream);
      });
    } catch (e) { reject(e); }
  });
}

function connectionKey(uri) {
  return resolveForUri(uri).connKey;
}

// ───────────────────────────── lifecycle ─────────────────────────────

function openConnection(uri) {
  return getConn(uri).then((c) => ({ connKey: c.connKey, host: c.host, user: c.user, port: c.port }));
}

function closeConnection(connKey) {
  const conn = pool.get(connKey);
  if (conn) { closeConn(conn); pool.delete(connKey); }
}

function closeAll() {
  for (const conn of pool.values()) closeConn(conn);
  pool.clear();
}

function connectionCount() { return pool.size; }

// Whether a pooled connection to `uri`'s host is currently healthy (for the poller to avoid hammering a
// down host). null = no pooled connection (unknown); true = ready; false = connecting/closed/errored.
function connectionHealth(uri) {
  try {
    const conn = pool.get(resolveForUri(uri).connKey);
    if (!conn) return null;
    return conn.state === 'ready';
  } catch (_e) { return null; }
}

// Close every pooled connection idle longer than opts.idleMs with nothing open on it (no stream, no file handle).
// `now` is injectable so a test can run the reaper at a chosen moment instead of waiting for its timer.
function reapIdle(now) {
  const t = now === undefined ? Date.now() : now;
  for (const [key, conn] of pool) {
    if (conn.state === 'ready' && conn.openStreams.size === 0 && (t - conn.lastUsed) > opts.idleMs) {
      closeConn(conn);
      pool.delete(key);
    }
  }
}

const reaper = setInterval(() => reapIdle(), 30000);
if (reaper.unref) reaper.unref();

module.exports = {
  configure, isRemoteUri,
  remoteStat, remoteReaddir, remoteReadFile, remoteReadText, remoteReadPrefix, remoteCreateReadStream,
  remoteOpenFile,
  remoteResolvePath, remoteForwardOut, connectionKey,
  openConnection, closeConnection, closeAll, connectionCount, connectionHealth,
  // exposed for tests / diagnostics (scripts/bench-remote-read.cjs reaches the raw SFTP channel through
  // _resolvePath to time ssh2's own readFile/fastGet as reference readers)
  _resolveForUri: resolveForUri, _childUri: childUri, _execCapture: execCapture, _resolvePath: resolvePath,
  // the ADR-0046 reader's knobs + the pre-ADR-0046 baseline, for the benchmark and the white-box smoke
  _readAllPipelined: readAllPipelined, _readFileStream: readFileStream, _maxReadLenOf: maxReadLenOf,
  _pipelineShape: pipelineShape, _reapIdle: reapIdle,
};
