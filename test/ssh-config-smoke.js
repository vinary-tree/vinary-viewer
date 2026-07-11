'use strict';
// Hermetic unit tests for src/vinary/main/ssh_config.js (pure — no fs/net). Run: node test/ssh-config-smoke.js
const assert = require('assert');
const crypto = require('crypto');
const cfg = require('../src/vinary/main/ssh_config.js');

let n = 0;
function ok(cond, msg) { assert.ok(cond, msg); n++; }
function eq(a, b, msg) { assert.deepStrictEqual(a, b, msg); n++; }

// ── parseSshUri ──────────────────────────────────────────────────────────────
(() => {
  eq(cfg.parseSshUri('ssh://user@host:2222/etc/hosts'),
     { scheme: 'ssh', rawHost: 'host', user: 'user', port: 2222, path: '/etc/hosts', homeRelative: false, uri: 'ssh://user@host:2222/etc/hosts' },
     'full ssh uri');
  const bare = cfg.parseSshUri('ssh://host');
  ok(bare.path === '.' && bare.homeRelative === true && bare.user === null && bare.port === null, 'bare host → remote home');
  const home = cfg.parseSshUri('ssh://host/~/logs/app.log');
  ok(home.path === '~/logs/app.log' && home.homeRelative === true, '/~ → home-relative, leading / stripped');
  const root = cfg.parseSshUri('ssh://host/');
  ok(root.path === '/' && root.homeRelative === false, 'explicit root');
  eq(cfg.parseSshUri('sftp://h/a').scheme, 'sftp', 'sftp scheme retained');
  const v6 = cfg.parseSshUri('ssh://bob@[2001:db8::1]:22/x');
  ok(v6.rawHost === '2001:db8::1' && v6.port === 22 && v6.user === 'bob' && v6.path === '/x', 'IPv6 bracketed host');
  ok(cfg.parseSshUri('ssh://h/a%20b.txt').path === '/a b.txt', 'percent-decoded path');
  assert.throws(() => cfg.parseSshUri('http://h/a'), 'non-ssh scheme throws'); n++;
  // SECURITY: shell/config metacharacters in the authority are rejected at parse time (host/user flow into
  // `Match exec` %-tokens → cp.execSync; a URI can be document-supplied) — command-injection guard.
  for (const bad of ['ssh://a;id/x', 'ssh://$(id)/x', 'ssh://a b/x', 'ssh://a|b/x', 'ssh://a&b/x', 'ssh://u;id@h/x', 'ssh://u$(x)@h/x']) {
    assert.throws(() => cfg.parseSshUri(bad), 'rejects an injection-y authority: ' + bad); n++;
  }
  // …while legitimate hosts / users / IPs / IPv6 / config-aliases still parse fine
  for (const good of ['ssh://host.example.com/x', 'ssh://user@127.0.0.1:22/x', 'ssh://[::1]:22/x', 'ssh://prod-web/x', 'ssh://a_b.c-d/x']) {
    assert.doesNotThrow(() => cfg.parseSshUri(good), 'accepts a valid authority: ' + good); n++;
  }
})();

// ── resolveSftpPath ──────────────────────────────────────────────────────────
(() => {
  const homes = { self: '/home/me', users: { bob: '/home/bob' } };
  eq(cfg.resolveSftpPath('/etc/hosts', homes), '/etc/hosts', 'absolute passes through');
  eq(cfg.resolveSftpPath('.', homes), '/home/me', '. → self home');
  eq(cfg.resolveSftpPath('~/x/y', homes), '/home/me/x/y', '~/x → home + x');
  eq(cfg.resolveSftpPath('~bob/z', homes), '/home/bob/z', '~user → that user home');
  eq(cfg.resolveSftpPath('/', homes), '/', 'root');
})();

// ── ssh_config: Host alias, wildcards, IdentityFile, IdentitiesOnly, ProxyJump, tokens ──
(() => {
  // Specific block first, then the catch-all — the conventional ordering. IdentityFiles accumulate in file
  // order (OpenSSH semantics), so the prod-specific key is tried before the general one.
  const text = [
    'Host prod prod-*',
    '  HostName real.example.com',
    '  User bob',
    '  Port 2222',
    '  IdentityFile ~/.ssh/prod_key',
    '  IdentitiesOnly yes',
    '  ProxyJump jump.example.com',
    '',
    'Host token',
    '  HostName %h.internal',
    '',
    'Host *',
    '  IdentityFile ~/.ssh/id_ed25519',
  ].join('\n');
  const nodes = cfg.parseSshConfig(text);

  const r = cfg.resolveDescriptor(cfg.parseSshUri('ssh://prod/~/f'), { configNodes: nodes, osUser: 'me', home: '/home/me' });
  eq(r.host, 'real.example.com', 'HostName from alias');
  eq(r.user, 'bob', 'User from alias');
  eq(r.port, 2222, 'Port from alias');
  eq(r.identitiesOnly, true, 'IdentitiesOnly yes');
  // both IdentityFiles apply (prod block + Host * block), ~-expanded, order preserved, deduped
  eq(r.identityFiles, ['/home/me/.ssh/prod_key', '/home/me/.ssh/id_ed25519'], 'IdentityFile accumulation + ~-expand');
  eq(r.proxyJump, [{ user: null, host: 'jump.example.com', port: null }], 'ProxyJump parsed');
  eq(r.connKey, 'bob@real.example.com:2222', 'connKey from resolved params');

  // wildcard alias prod-web matches "prod-*"
  eq(cfg.resolveDescriptor(cfg.parseSshUri('ssh://prod-web/x'), { configNodes: nodes, osUser: 'me' }).host, 'real.example.com', 'Host wildcard');

  // %h token expands to the original host
  eq(cfg.resolveDescriptor(cfg.parseSshUri('ssh://token/x'), { configNodes: nodes, osUser: 'me' }).host, 'token.internal', '%h token');

  // explicit user in the URI overrides config User
  eq(cfg.resolveDescriptor(cfg.parseSshUri('ssh://alice@prod/x'), { configNodes: nodes, osUser: 'me' }).user, 'alice', 'URI user overrides config');

  // no config match → OS user + default port
  const plain = cfg.resolveDescriptor(cfg.parseSshUri('ssh://elsewhere/x'), { configNodes: [], osUser: 'me' });
  eq([plain.host, plain.user, plain.port], ['elsewhere', 'me', 22], 'unconfigured host falls back to OS user + 22');
})();

// ── ssh_config: Match blocks (host/user/exec) ────────────────────────────────
(() => {
  const text = [
    'Match host real.example.com',
    '  User matched-by-hostname',
    'Match user deploy',
    '  Port 40',
    'Match exec "test-jump"',
    '  ProxyJump via',
    'Host real.example.com',
    '  HostName real.example.com',
  ].join('\n');
  const nodes = cfg.parseSshConfig(text);
  // Match host applies in the final pass (keyed on resolved HostName == original here)
  eq(cfg.resolveDescriptor(cfg.parseSshUri('ssh://real.example.com/x'), { configNodes: nodes, osUser: 'me' }).user,
     'matched-by-hostname', 'Match host matches resolved hostname');
  // Match user (user supplied in the URI)
  eq(cfg.resolveDescriptor(cfg.parseSshUri('ssh://deploy@h/x'), { configNodes: nodes, osUser: 'me' }).port,
     40, 'Match user matches the connecting user');
  // Match exec — driven by injected execResults (the transport runs the command)
  eq(cfg.resolveDescriptor(cfg.parseSshUri('ssh://h/x'), { configNodes: nodes, osUser: 'me', execResults: { 'test-jump': true } }).proxyJump,
     [{ user: null, host: 'via', port: null }], 'Match exec via injected execResults');
  eq(cfg.resolveDescriptor(cfg.parseSshUri('ssh://h/x'), { configNodes: nodes, osUser: 'me', execResults: { 'test-jump': false } }).proxyJump,
     null, 'Match exec false → block skipped');
})();

// ── ssh_config: Include expansion (mock loader) ──────────────────────────────
(() => {
  const main = 'Include ~/.ssh/config.d/*\nHost fallback\n  User base';
  const loader = (glob) => glob.includes('config.d')
    ? [{ path: '/home/me/.ssh/config.d/10-prod', text: 'Host inc\n  HostName included.example.com\n  User incuser' }]
    : [];
  const nodes = cfg.expandIncludes(cfg.parseSshConfig(main), loader);
  const r = cfg.resolveDescriptor(cfg.parseSshUri('ssh://inc/x'), { configNodes: nodes, osUser: 'me' });
  eq([r.host, r.user], ['included.example.com', 'incuser'], 'Include splices the included file in position');
  // includes dropped when no loader is available (never left dangling)
  ok(cfg.expandIncludes(cfg.parseSshConfig(main), null).every((x) => x.type !== 'include'), 'no loader → include nodes dropped');
})();

// ── known_hosts: plain / [host]:port / hashed / changed / revoked / unknown ──
(() => {
  const KT = 'ssh-ed25519';
  const K1 = Buffer.from('key-one').toString('base64');
  const K2 = Buffer.from('key-two').toString('base64');
  const plain = cfg.parseKnownHosts([
    `example.com ${KT} ${K1}`,
    `[secure.example.com]:2222 ${KT} ${K1}`,
    `wild.* ${KT} ${K1}`,
    `@revoked bad.example.com ${KT} ${K2}`,
  ].join('\n'));
  eq(cfg.checkHostKey(plain, 'example.com', 22, KT, K1), 'ok', 'plain host + default port → ok');
  eq(cfg.checkHostKey(plain, 'example.com', 22, KT, K2), 'changed', 'same host+type, different key → changed');
  eq(cfg.checkHostKey(plain, 'example.com', 2022, KT, K1), 'unknown', 'plain entry does not match a non-default port');
  eq(cfg.checkHostKey(plain, 'secure.example.com', 2222, KT, K1), 'ok', '[host]:port entry matches that port');
  eq(cfg.checkHostKey(plain, 'wild.foo', 22, KT, K1), 'ok', 'wildcard host pattern');
  eq(cfg.checkHostKey(plain, 'unseen.example.com', 22, KT, K1), 'unknown', 'no entry → unknown');
  eq(cfg.checkHostKey(plain, 'bad.example.com', 22, KT, K2), 'revoked', '@revoked key → revoked');

  // hashed entry round-trips: build one with knownHostsLineHashed, parse it back, and match
  const salt = crypto.randomBytes(20);
  const hashedLine = cfg.knownHostsLineHashed('hidden.example.com', 22, KT, K1, salt);
  ok(hashedLine.startsWith('|1|'), 'hashed line format');
  const hashed = cfg.parseKnownHosts(hashedLine);
  eq(cfg.checkHostKey(hashed, 'hidden.example.com', 22, KT, K1), 'ok', 'hashed |1| entry matches its host');
  eq(cfg.checkHostKey(hashed, 'other.example.com', 22, KT, K1), 'unknown', 'hashed entry does not match a different host');

  eq(cfg.knownHostsLine('h.example.com', 22, KT, K1), `h.example.com ${KT} ${K1}`, 'plain line for default port');
  eq(cfg.knownHostsLine('h.example.com', 2222, KT, K1), `[h.example.com]:2222 ${KT} ${K1}`, 'bracketed line for non-default port');
})();

// ── fingerprintSha256 ────────────────────────────────────────────────────────
(() => {
  const blob = Buffer.from('a-host-key-blob');
  const expected = 'SHA256:' + crypto.createHash('sha256').update(blob).digest('base64').replace(/=+$/, '');
  eq(cfg.fingerprintSha256(blob), expected, 'OpenSSH-style SHA256 fingerprint (unpadded base64)');
  ok(!cfg.fingerprintSha256(blob).endsWith('='), 'no base64 padding');
})();

console.log(`[ok] ssh-config-smoke: ${n} assertions passed`);
