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
//     remoteReadText / remoteReadPrefix / remoteCreateReadStream, plus lifecycle (open/close/count).
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
    opts.onError({ connKey: sshcfg.connKeyOf(null, host, port), host, port, kind: 'sftp-error', message: 'could not write known_hosts: ' + e.message });
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
      opts.onError({ connKey: resolved.connKey, host, port, kind: 'hostkey-rejected', message: 'could not parse host key' });
      return callback(false);
    }
    const status = sshcfg.checkHostKey(loadKnownHosts(), host, port, keyType, keyB64);
    if (status === 'ok') return callback(true);
    if (status === 'changed') {
      opts.onError({ connKey: resolved.connKey, host, port, kind: 'hostkey-changed', message: `REMOTE HOST IDENTIFICATION FOR ${host} HAS CHANGED (${fp}) — refusing to connect` });
      return callback(false);
    }
    if (status === 'revoked') {
      opts.onError({ connKey: resolved.connKey, host, port, kind: 'hostkey-rejected', message: `host key for ${host} is revoked` });
      return callback(false);
    }
    Promise.resolve(opts.promptHostKey({ host, port, keyType, fingerprint: fp, changed: false }))
      .then((accepted) => {
        if (accepted) { appendKnownHost(host, port, keyType, keyB64); callback(true); }
        else { opts.onError({ connKey: resolved.connKey, host, port, kind: 'hostkey-rejected', message: 'host key not trusted' }); callback(false); }
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
    .catch((e) => opts.onError({ connKey: resolved.connKey, host: resolved.host, port: resolved.port, kind: 'sftp-error', message: 'AddKeysToAgent failed: ' + e.message }));
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
  opts.onStatus({ connKey: conn.connKey, host: resolved.host, state: 'connecting' });

  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = (err) => { if (!settled) { settled = true; reject(err); } };
    client.on('ready', () => {
      conn.state = 'ready'; conn.lastUsed = Date.now();
      opts.onStatus({ connKey: conn.connKey, host: resolved.host, state: 'ready' });
      settled = true; resolve(conn);
    });
    client.on('error', (err) => {
      conn.state = 'error'; conn.lastError = err; pool.delete(conn.connKey); destroyStreams(conn);
      opts.onError(errInfo(err, resolved)); fail(err);
    });
    client.on('close', () => {
      const wasReady = conn.state === 'ready';
      conn.state = 'closed'; pool.delete(conn.connKey); destroyStreams(conn); conn.sftpP = null;
      if (wasReady) opts.onStatus({ connKey: conn.connKey, host: resolved.host, state: 'closed' });
      fail(new Error('connection closed'));
    });

    const connectOpts = {
      host: resolved.host, port: resolved.port, username: resolved.user || osUser() || undefined,
      tryKeyboard: true, keepaliveInterval: 15000, keepaliveCountMax: 3, readyTimeout: 25000,
      hostVerifier: makeHostVerifier(resolved), authHandler: makeAuthHandler(conn, resolved),
    };
    if (sock) connectOpts.sock = sock;
    try { client.connect(connectOpts); } catch (e) { pool.delete(conn.connKey); fail(e); }
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

async function remoteReadFile(uri, o) {
  o = o || {};
  const { sftp, path: p } = await resolvePath(uri);
  return readAll(sftp.createReadStream(p), o.maxBytes || opts.maxBytes);
}

async function remoteReadText(uri, o) {
  o = o || {};
  return (await remoteReadFile(uri, o)).toString(o.encoding || 'utf8');
}

async function remoteReadPrefix(uri, nBytes) {
  const { sftp, path: p } = await resolvePath(uri);
  const stream = sftp.createReadStream(p, { start: 0, end: Math.max(0, nBytes - 1) });
  const buf = await readAll(stream, nBytes + 16);
  return buf.slice(0, nBytes).toString('utf8');
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

const reaper = setInterval(() => {
  const now = Date.now();
  for (const [key, conn] of pool) {
    if (conn.state === 'ready' && conn.openStreams.size === 0 && (now - conn.lastUsed) > opts.idleMs) {
      closeConn(conn);
      pool.delete(key);
    }
  }
}, 30000);
if (reaper.unref) reaper.unref();

module.exports = {
  configure, isRemoteUri,
  remoteStat, remoteReaddir, remoteReadFile, remoteReadText, remoteReadPrefix, remoteCreateReadStream,
  openConnection, closeConnection, closeAll, connectionCount, connectionHealth,
  // exposed for tests / diagnostics
  _resolveForUri: resolveForUri, _childUri: childUri, _execCapture: execCapture,
};
