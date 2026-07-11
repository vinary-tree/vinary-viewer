'use strict';
// ssh_config.js — PURE, dependency-light helpers for the SSH/SFTP remote-file transport.
//
// This module contains only string/crypto arithmetic (no `fs`, no `net`, no `electron`), so it is fully
// node-testable in isolation (test/ssh-config-smoke.js). All filesystem I/O — reading ~/.ssh/config,
// expanding Include globs, reading key files, reading/appending known_hosts, running `Match exec` commands,
// resolving remote home directories — is performed by ssh_transport.js and fed in here as plain data.
//
// It provides four pure capabilities:
//   1. parseSshUri / resolveSftpPath — ssh://[user@]host[:port]/path parsing + the ~ path rule.
//   2. parseSshConfig / resolveHostConfig / resolveDescriptor — an OpenSSH ssh_config(5) resolver
//      (Host + Match + Include, %-token expansion, first-value-wins, IdentityFile accumulation).
//   3. parseKnownHosts / checkHostKey / knownHostsLine — known_hosts(5) matching (plain, [host]:port,
//      and |1| hashed entries), the trust decision, and the line to append on trust-on-first-use.
//   4. fingerprintSha256 — the OpenSSH SHA256 host-key fingerprint shown in the TOFU prompt.

const crypto = require('crypto');

// ───────────────────────────── small string utilities ─────────────────────────────

function stripComment(line) {
  // A '#' starts a comment only when not inside a quoted value. ssh_config comments are whole-line or
  // trailing; quotes group tokens. Walk the line honoring double quotes.
  let out = '';
  let inQuote = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') { inQuote = !inQuote; out += ch; continue; }
    if (ch === '#' && !inQuote) break;
    out += ch;
  }
  return out;
}

function tokenize(value) {
  // Whitespace-separated tokens, honoring double quotes (which may contain spaces). Commas are NOT split
  // here (callers that want comma-lists, e.g. Host patterns, split further).
  const toks = [];
  const re = /"([^"]*)"|(\S+)/g;
  let m;
  while ((m = re.exec(value)) !== null) toks.push(m[1] !== undefined ? m[1] : m[2]);
  return toks;
}

function splitKeyValue(line) {
  // `Keyword value...` or `Keyword=value...` (OpenSSH allows an '=' with optional surrounding space).
  const eq = line.match(/^(\S+?)\s*=\s*(.*)$/);
  if (eq) return [eq[1], eq[2].trim()];
  const sp = line.match(/^(\S+)\s+(.*)$/);
  if (sp) return [sp[1], sp[2].trim()];
  return [line, ''];
}

function globToRegExp(pattern) {
  // ssh glob: '*' → any run, '?' → single char. Everything else literal. Anchored, case-insensitive.
  let re = '^';
  for (const ch of pattern) {
    if (ch === '*') re += '.*';
    else if (ch === '?') re += '.';
    else re += ch.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(re + '$', 'i');
}

function globMatch(pattern, str) {
  if (str == null) return false;
  return globToRegExp(pattern).test(str);
}

// Match a value against a comma/space-separated pattern list with optional '!' negation (OpenSSH rule:
// a negated match vetoes; otherwise any positive match accepts).
function matchPatternList(patternList, value) {
  const patterns = String(patternList).split(/[,\s]+/).filter(Boolean);
  let matched = false;
  for (let pat of patterns) {
    let neg = false;
    if (pat.startsWith('!')) { neg = true; pat = pat.slice(1); }
    if (globMatch(pat, value)) {
      if (neg) return false;
      matched = true;
    }
  }
  return matched;
}

// ───────────────────────────── URI parsing ─────────────────────────────

function safeDecode(s) {
  try { return decodeURIComponent(s); } catch (_e) { return s; }
}

// parseSshUri('ssh://user@host:22/~/logs/app.log') →
//   { scheme:'ssh', rawHost:'host', user:'user', port:22, path:'~/logs/app.log', homeRelative:true, uri }
// The absolute-vs-home rule mirrors git's ssh:// convention: a pathname beginning '/~' is home-relative
// (the leading '/' is stripped), any other leading-'/' pathname is absolute, and an empty pathname is the
// remote home (path '.').
function parseSshUri(uri) {
  const s = String(uri);
  const m = /^(ssh|sftp):\/\/(.*)$/i.exec(s);
  if (!m) throw new Error('Not an ssh:// or sftp:// URI: ' + s);
  const scheme = m[1].toLowerCase();
  let rest = m[2];

  // Split authority from path (the first '/' after the authority; IPv6 hosts are bracketed).
  let authority, pathPart;
  if (rest.startsWith('[')) {
    const rb = rest.indexOf(']');
    if (rb < 0) throw new Error('Malformed IPv6 authority in URI: ' + s);
    const after = rest.slice(rb + 1);
    const slash = after.indexOf('/');
    authority = rest.slice(0, rb + 1) + (slash < 0 ? after : after.slice(0, slash));
    pathPart = slash < 0 ? '' : after.slice(slash);
  } else {
    const slash = rest.indexOf('/');
    authority = slash < 0 ? rest : rest.slice(0, slash);
    pathPart = slash < 0 ? '' : rest.slice(slash);
  }

  // authority = [user@]host[:port]
  let user = null, hostport = authority;
  const at = authority.lastIndexOf('@');
  if (at >= 0) { user = safeDecode(authority.slice(0, at)); hostport = authority.slice(at + 1); }

  let host, port = null;
  if (hostport.startsWith('[')) {
    const rb = hostport.indexOf(']');
    host = hostport.slice(1, rb);
    const tail = hostport.slice(rb + 1);
    if (tail.startsWith(':') && /^\d+$/.test(tail.slice(1))) port = parseInt(tail.slice(1), 10);
  } else {
    const colon = hostport.lastIndexOf(':');
    if (colon >= 0 && /^\d+$/.test(hostport.slice(colon + 1))) {
      host = hostport.slice(0, colon);
      port = parseInt(hostport.slice(colon + 1), 10);
    } else {
      host = hostport;
    }
  }
  host = safeDecode(host);
  if (!host) throw new Error('Missing host in URI: ' + s);

  let path, homeRelative;
  if (pathPart === '') { path = '.'; homeRelative = true; }              // bare host → remote home
  else if (pathPart === '/') { path = '/'; homeRelative = false; }        // explicit root
  else if (pathPart.startsWith('/~')) { path = safeDecode(pathPart.slice(1)); homeRelative = true; } // /~/x → ~/x
  else { path = safeDecode(pathPart); homeRelative = false; }             // absolute

  return { scheme, rawHost: host, user, port, path, homeRelative, uri: s };
}

// Map a descriptor path to a concrete SFTP path given resolved home directories.
//   homes = { self: '/home/me', users: { bob: '/home/bob' } }   (resolved by the transport via realpath/exec)
// Absolute paths pass through; '.' → self-home; '~'/'~/x' → self-home + x; '~user/x' → that user's home + x.
function resolveSftpPath(path, homes) {
  homes = homes || {};
  const p = String(path);
  if (p === '.' || p === '') return homes.self || '.';
  if (p === '/') return '/';
  if (p === '~') return homes.self || '~';
  if (p.startsWith('~/')) return homes.self ? joinPosix(homes.self, p.slice(2)) : p;
  const um = /^~([^/]+)(?:\/(.*))?$/.exec(p);
  if (um) {
    const uh = homes.users && homes.users[um[1]];
    return uh ? joinPosix(uh, um[2] || '') : p;
  }
  return p; // absolute or already-relative (SFTP resolves relative paths against the session's home)
}

function joinPosix(a, b) {
  if (!b) return a;
  return a.replace(/\/+$/, '') + '/' + String(b).replace(/^\/+/, '');
}

function expandTilde(p, home) {
  const s = String(p);
  if (s === '~') return home;
  if (s.startsWith('~/')) return joinPosix(home, s.slice(2));
  return s;
}

// ───────────────────────────── ssh_config(5) ─────────────────────────────

// parseSshConfig(text) → an ordered list of directive nodes:
//   { type:'host', patterns }              a Host section header
//   { type:'match', criteria }             a Match section header (criteria = [{negated, keyword, arg}])
//   { type:'include', globs }              an Include directive (expanded by expandIncludes)
//   { type:'keyword', key, value }         a keyword=value line (key lower-cased)
// Directives before the first Host/Match are global (always active). Resolution walks these in order.
function parseSshConfig(text) {
  const nodes = [];
  for (const raw of String(text).split(/\r?\n/)) {
    const line = stripComment(raw).trim();
    if (!line) continue;
    const [key, value] = splitKeyValue(line);
    const k = key.toLowerCase();
    if (k === 'host') {
      nodes.push({ type: 'host', patterns: value.split(/[,\s]+/).filter(Boolean) });
    } else if (k === 'match') {
      nodes.push({ type: 'match', criteria: parseMatchCriteria(value) });
    } else if (k === 'include') {
      nodes.push({ type: 'include', globs: tokenize(value) });
    } else {
      nodes.push({ type: 'keyword', key: k, value });
    }
  }
  return nodes;
}

function parseMatchCriteria(value) {
  // e.g. "host foo,bar user bob exec \"test -n $X\" all final canonical"
  const toks = tokenize(value);
  const crit = [];
  const noArg = new Set(['all', 'canonical', 'final']);
  for (let i = 0; i < toks.length; i++) {
    let kw = toks[i].toLowerCase();
    let negated = false;
    if (kw.startsWith('!')) { negated = true; kw = kw.slice(1); }
    if (noArg.has(kw)) { crit.push({ negated, keyword: kw, arg: null }); continue; }
    const arg = toks[++i];
    crit.push({ negated, keyword: kw, arg });
  }
  return crit;
}

// expandIncludes(nodes, loader, depth) → nodes with every {type:'include'} replaced, in place, by the parsed
// directives of the files its globs resolve to. `loader(glob) → [{path, text}]` is provided by the transport
// (it does the fs glob + read + ~ expansion). Recursion is bounded (OpenSSH caps Include depth at 16).
function expandIncludes(nodes, loader, depth) {
  depth = depth || 0;
  if (!loader || depth > 16) {
    // Cannot (or should not) resolve further: drop include nodes so resolution ignores them.
    return nodes.filter((n) => n.type !== 'include');
  }
  const out = [];
  for (const n of nodes) {
    if (n.type !== 'include') { out.push(n); continue; }
    for (const glob of n.globs) {
      for (const file of loader(glob) || []) {
        const parsed = parseSshConfig(file.text);
        for (const inner of expandIncludes(parsed, loader, depth + 1)) out.push(inner);
      }
    }
  }
  return out;
}

// %-token expansion for values (HostName, ProxyJump, IdentityFile, …). Supports the tokens that matter for
// connecting: %% %h (host) %n (original host) %p (port) %r (remote user) %u (local user) %L/%l (localhost).
function expandTokens(value, ctx) {
  return String(value).replace(/%(.)/g, (whole, c) => {
    switch (c) {
      case '%': return '%';
      case 'h': return ctx.host != null ? String(ctx.host) : whole;
      case 'n': return ctx.originalHost != null ? String(ctx.originalHost) : whole;
      case 'p': return ctx.port != null ? String(ctx.port) : whole;
      case 'r': return ctx.user != null ? String(ctx.user) : whole;
      case 'u': return ctx.localUser != null ? String(ctx.localUser) : whole;
      case 'L': return ctx.localHostShort != null ? String(ctx.localHostShort) : whole;
      case 'l': return ctx.localHost != null ? String(ctx.localHost) : whole;
      default: return whole;
    }
  });
}

function matchMatchCriteria(criteria, ctx) {
  for (const c of criteria) {
    let ok;
    switch (c.keyword) {
      case 'all': ok = true; break;
      case 'canonical': ok = !!ctx.canonical; break;
      case 'final': ok = !!ctx.final; break;
      case 'host': ok = matchPatternList(c.arg, ctx.host); break;
      case 'originalhost': ok = matchPatternList(c.arg, ctx.originalHost); break;
      case 'user': ok = matchPatternList(c.arg, ctx.user); break;
      case 'localuser': ok = matchPatternList(c.arg, ctx.localUser); break;
      case 'exec': ok = ctx.execResults ? !!ctx.execResults[c.arg] : false; break;
      default: ok = false; // unknown criterion → does not match (conservative)
    }
    if (c.negated) ok = !ok;
    if (!ok) return false;
  }
  return true;
}

// resolveHostConfig(nodes, ctx) → merged keyword map for one target (first-value-wins; IdentityFile
// accumulates in order). `nodes` must already have Includes expanded. ctx supplies host/originalHost/user/…
// for Host/Match evaluation and %-token expansion.
function resolveHostConfig(nodes, ctx) {
  const result = {};
  const identityFiles = [];
  let active = true; // directives before the first Host/Match are global
  for (const n of nodes) {
    if (n.type === 'host') {
      active = n.patterns.some((_) => true) && matchPatternList(n.patterns.join(','), ctx.originalHost || ctx.host);
    } else if (n.type === 'match') {
      active = matchMatchCriteria(n.criteria, ctx);
    } else if (n.type === 'keyword' && active) {
      if (n.key === 'identityfile') {
        identityFiles.push(expandTokens(n.value, ctx));
      } else if (!(n.key in result)) {
        result[n.key] = expandTokens(n.value, ctx);
      }
    }
  }
  if (identityFiles.length) result.identityfile = identityFiles;
  return result;
}

function parseProxyJump(value) {
  // "[user@]host[:port][,[user@]host[:port]...]" or "none"
  const v = String(value).trim();
  if (!v || v.toLowerCase() === 'none') return null;
  const hops = [];
  for (const tok of v.split(',').map((t) => t.trim()).filter(Boolean)) {
    let user = null, hostport = tok;
    const at = tok.lastIndexOf('@');
    if (at >= 0) { user = tok.slice(0, at); hostport = tok.slice(at + 1); }
    let host = hostport, port = null;
    if (hostport.startsWith('[')) {
      const rb = hostport.indexOf(']');
      host = hostport.slice(1, rb);
      const tail = hostport.slice(rb + 1);
      if (tail.startsWith(':')) port = parseInt(tail.slice(1), 10);
    } else {
      const colon = hostport.lastIndexOf(':');
      if (colon >= 0 && /^\d+$/.test(hostport.slice(colon + 1))) { host = hostport.slice(0, colon); port = parseInt(hostport.slice(colon + 1), 10); }
    }
    hops.push({ user, host, port });
  }
  return hops.length ? hops : null;
}

function connKeyOf(user, host, port) {
  return `${user || ''}@${host}:${port || 22}`;
}

// resolveDescriptor(descriptor, opts) → the concrete connection parameters after applying ssh_config.
//   opts = { configNodes, osUser, localUser, home, execResults, canonical, localHost, localHostShort }
// Two passes (OpenSSH-style): pass 1 keyed on the original host resolves HostName/User/Port; pass 2 keyed on
// the resolved HostName with final=true lets `Match host <realname>` / `Match final` apply. First-pass values
// win. IdentityFiles from both passes are concatenated (deduped, order-preserving) and ~-expanded.
function resolveDescriptor(descriptor, opts) {
  opts = opts || {};
  const nodes = opts.configNodes || [];
  const home = opts.home || '';
  const base = {
    originalHost: descriptor.rawHost,
    localUser: opts.localUser || null,
    execResults: opts.execResults || null,
    localHost: opts.localHost || null,
    localHostShort: opts.localHostShort || null,
  };

  const ctx1 = Object.assign({}, base, { host: descriptor.rawHost, user: descriptor.user, port: descriptor.port, canonical: false, final: false });
  const kw1 = resolveHostConfig(nodes, ctx1);

  const host = kw1.hostname ? expandTokens(kw1.hostname, ctx1) : descriptor.rawHost;
  const user = descriptor.user || kw1.user || opts.osUser || opts.localUser || null;
  const port = descriptor.port || (kw1.port ? parseInt(kw1.port, 10) : null) || 22;

  const ctx2 = Object.assign({}, base, { host, user, port, canonical: !!opts.canonical, final: true });
  const kw2 = resolveHostConfig(nodes, ctx2);
  const kw = Object.assign({}, kw2, kw1); // first pass (kw1) wins on conflicts

  const identityFiles = [];
  for (const list of [kw1.identityfile, kw2.identityfile]) {
    for (const f of list || []) {
      const abs = expandTilde(f, home);
      if (!identityFiles.includes(abs)) identityFiles.push(abs);
    }
  }

  const identitiesOnly = /^yes$/i.test(kw.identitiesonly || '');
  const addKeysToAgent = kw.addkeystoagent || null;
  const proxyJump = kw.proxyjump ? parseProxyJump(expandTokens(kw.proxyjump, ctx2)) : null;

  return {
    host, user, port,
    identityFiles, identitiesOnly, addKeysToAgent, proxyJump,
    connKey: connKeyOf(user, host, port),
    descriptor,
  };
}

// ───────────────────────────── known_hosts(5) ─────────────────────────────

function hostMatchName(host, port) {
  return (port && port !== 22) ? `[${host}]:${port}` : String(host);
}

function parseKnownHosts(text) {
  const entries = [];
  for (const raw of String(text).split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    let toks = line.split(/\s+/);
    let marker = null;
    if (toks[0] === '@cert-authority' || toks[0] === '@revoked') { marker = toks[0].slice(1); toks = toks.slice(1); }
    if (toks.length < 3) continue;
    entries.push({ marker, hosts: toks[0].split(','), keyType: toks[1], keyB64: toks[2] });
  }
  return entries;
}

// Does a single known_hosts host-pattern token match (host, port)?
function hostPatternMatches(pattern, host, port) {
  if (pattern.startsWith('|1|')) {
    const parts = pattern.split('|'); // ['', '1', saltB64, hashB64]
    if (parts.length !== 4) return false;
    try {
      const salt = Buffer.from(parts[2], 'base64');
      const name = hostMatchName(host, port);
      const mac = crypto.createHmac('sha1', salt).update(name).digest('base64');
      return mac === parts[3];
    } catch (_e) { return false; }
  }
  if (pattern.startsWith('[')) {
    const m = /^\[([^\]]+)\](?::(\d+))?$/.exec(pattern);
    if (!m) return false;
    const patPort = m[2] ? parseInt(m[2], 10) : 22;
    return globMatch(m[1], host) && patPort === (port || 22);
  }
  // Plain / wildcard pattern → applies to the default port (22). Negation supported.
  if ((port || 22) !== 22) return false;
  if (pattern.startsWith('!')) return false; // a lone negation never positively matches
  return globMatch(pattern, host);
}

function entryMatchesHost(entry, host, port) {
  let matched = false;
  for (const pat of entry.hosts) {
    if (pat.startsWith('!')) {
      if (globMatch(pat.slice(1), host)) return false; // negation vetoes
    } else if (hostPatternMatches(pat, host, port)) {
      matched = true;
    }
  }
  return matched;
}

// checkHostKey(entries, host, port, keyType, keyB64) → 'ok' | 'changed' | 'revoked' | 'unknown'
//   'ok'      — a matching entry has this exact keyType+key.
//   'revoked' — a matching @revoked entry has this exact key.
//   'changed' — a matching entry has this keyType but a DIFFERENT key (the dangerous MITM case).
//   'unknown' — no matching entry for this host, or matches exist but not for this key type (safe to add).
function checkHostKey(entries, host, port, keyType, keyB64) {
  const matching = entries.filter((e) => entryMatchesHost(e, host, port));
  for (const e of matching) {
    if (e.marker === 'revoked' && e.keyType === keyType && e.keyB64 === keyB64) return 'revoked';
  }
  for (const e of matching) {
    if (e.marker) continue; // cert-authority / revoked handled elsewhere
    if (e.keyType === keyType && e.keyB64 === keyB64) return 'ok';
  }
  for (const e of matching) {
    if (e.marker) continue;
    if (e.keyType === keyType && e.keyB64 !== keyB64) return 'changed';
  }
  return 'unknown';
}

function knownHostsLine(host, port, keyType, keyB64) {
  return `${hostMatchName(host, port)} ${keyType} ${keyB64}`;
}

// A hashed known_hosts line (|1|salt|hash keytype key) — the format `ssh-keygen -H` / HashKnownHosts=yes use.
function knownHostsLineHashed(host, port, keyType, keyB64, saltBuf) {
  const salt = saltBuf || crypto.randomBytes(20);
  const name = hostMatchName(host, port);
  const mac = crypto.createHmac('sha1', salt).update(name).digest('base64');
  return `|1|${salt.toString('base64')}|${mac} ${keyType} ${keyB64}`;
}

// The OpenSSH SHA256 fingerprint of a host/public key wire blob: base64(sha256(blob)) without '=' padding.
function fingerprintSha256(keyBuffer) {
  const digest = crypto.createHash('sha256').update(keyBuffer).digest('base64');
  return 'SHA256:' + digest.replace(/=+$/, '');
}

module.exports = {
  // uri
  parseSshUri, resolveSftpPath, expandTilde, joinPosix,
  // ssh_config
  parseSshConfig, expandIncludes, resolveHostConfig, resolveDescriptor, parseProxyJump, expandTokens, connKeyOf,
  // known_hosts
  parseKnownHosts, checkHostKey, knownHostsLine, knownHostsLineHashed, fingerprintSha256,
  // exported for tests
  matchPatternList, globMatch, hostMatchName, hostPatternMatches, tokenize,
};
