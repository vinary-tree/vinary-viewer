'use strict';
// End-to-end test of src/vinary/main/ssh_transport.js against the hermetic in-process ssh2.Server SFTP fixture
// (no network, no external host). Also asserts ssh2 runs pure-JS (no native crypto addon) and that AddKeysToAgent
// (ssh_agent.js) adds a key to a throwaway ssh-agent. Run: node test/ssh-transport-smoke.js
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');
const crypto = require('crypto');
const transport = require('../src/vinary/main/ssh_transport.js');
const sshAgent = require('../src/vinary/main/ssh_agent.js');
const { startSftpServer } = require('./fixtures/ssh-server.js');

let n = 0;
const ok = (c, m) => { assert.ok(c, m); n++; };
const eq = (a, b, m) => { assert.deepStrictEqual(a, b, m); n++; };

async function drain(stream) {
  return new Promise((res, rej) => { let s = ''; stream.on('data', (c) => (s += c)); stream.on('end', () => res(s)); stream.on('error', rej); });
}

async function main() {
  // 0. pure-JS crypto: ssh2's CRYPTO is Node's built-in crypto — its optional native crypto addon (sshcrypto.node)
  // is never built, so ssh2 imposes no hard native-build requirement and stays portable. (cpu-features, a small
  // CPU-detection optimization, may or may not be compiled; ssh2 works either way — the functional loopback below
  // proves crypto works regardless.)
  ok(!fs.existsSync(path.join(__dirname, '..', 'node_modules', 'ssh2', 'lib', 'protocol', 'crypto', 'build', 'Release', 'sshcrypto.node')),
     'ssh2 uses no compiled crypto addon (sshcrypto.node) — its crypto is Node crypto');

  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });

  const srv = await startSftpServer({
    password: 'pw-123',
    files: {
      'notes.md': '# Remote\ncontent here\n',
      'sub/a.txt': 'alpha', 'sub/b.txt': 'beta',
      'big.log': Array.from({ length: 5000 }, (_, i) => `line ${i}`).join('\n') + '\n',
      'home/bob/hello.txt': 'hi bob',
    },
    userHomes: { bob: '/home/bob' },
  });

  const prompts = [];
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async (info) => { prompts.push(['hostkey', info.host, info.fingerprint]); return true; },
    promptSecret: async (req) => { prompts.push(['secret', req.kind]); return srv.password; },
    onError: (e) => prompts.push(['error', e.kind, e.message]),
  });

  // 1. stat (file + directory), symlink flag present
  const fst = await transport.remoteStat(srv.url('/notes.md'));
  ok(fst.isFile && !fst.isDirectory && fst.size > 0 && typeof fst.mtime === 'number', 'stat file');
  ok((await transport.remoteStat(srv.url('/sub'))).isDirectory, 'stat directory');

  // 2. readdir — entries carry child ssh:// URIs + dir?/size/mtime
  const entries = await transport.remoteReaddir(srv.url('/sub'));
  eq(entries.map((e) => e.name).sort(), ['a.txt', 'b.txt'], 'readdir names');
  ok(entries.every((e) => e.path.startsWith(`ssh://tester@127.0.0.1:${srv.port}/sub/`)), 'entries carry child ssh:// URIs');
  ok(entries.every((e) => e.dir === false && typeof e.size === 'number'), 'entry attrs');

  // 3. text / bytes / prefix
  ok(/content here/.test(await transport.remoteReadText(srv.url('/notes.md'))), 'readText');
  eq((await transport.remoteReadFile(srv.url('/sub/a.txt'))).toString(), 'alpha', 'readFile bytes');
  eq(await transport.remoteReadPrefix(srv.url('/notes.md'), 6), '# Remo', 'readPrefix (first N bytes)');

  // 4. stream a large file (drop-in Readable)
  const streamed = await drain(await transport.remoteCreateReadStream(srv.url('/big.log')));
  ok(streamed.split('\n').length > 5000, 'createReadStream streams the whole file');

  // 5. ~user path resolution (echo ~bob over exec → /home/bob)
  eq(await transport.remoteReadText(srv.url('/~bob/hello.txt')), 'hi bob', '~user home resolution');

  // 6. host-key TOFU: prompted once, written to the temp known_hosts, no re-prompt afterward
  const hkPrompts = prompts.filter((p) => p[0] === 'hostkey').length;
  ok(hkPrompts === 1, 'host key prompted exactly once (TOFU)');
  ok(prompts.find((p) => p[0] === 'hostkey')[2].startsWith('SHA256:'), 'TOFU prompt carries a SHA256 fingerprint');
  ok(fs.existsSync(path.join(home, '.ssh', 'known_hosts')), 'known_hosts written on accept');
  await transport.remoteStat(srv.url('/notes.md'));
  eq(prompts.filter((p) => p[0] === 'hostkey').length, hkPrompts, 'a known host is not re-prompted');

  // 7. pooling + lifecycle
  eq(transport.connectionCount(), 1, 'a single pooled connection is reused');
  ok(transport.connectionHealth(srv.url('/x')) === true, 'connectionHealth reports ready');
  transport.closeAll();
  eq(transport.connectionCount(), 0, 'closeAll drains the pool');

  // 8. wrong password → auth-failed (not a hang)
  transport.configure({ promptSecret: async () => 'WRONG' });
  let threw = false;
  try { await transport.remoteStat(srv.url('/notes.md')); } catch (_e) { threw = true; }
  ok(threw, 'a wrong password rejects (auth-failed) rather than hanging');
  ok(prompts.some((p) => p[0] === 'error' && p[1] === 'auth-failed'), 'onError reports auth-failed');
  transport.closeAll();

  await srv.close();

  // 9. ProxyJump — reach a target host THROUGH a jump host (both pooled + authed independently).
  await testProxyJump();

  // 9b. keyboard-interactive (MFA) — multi-prompt auth; the transport answers each prompt via promptSecret.
  await testKeyboardInteractive();

  // 9c. publickey (key file) auth — the transport reads ~/.ssh/id_ed25519, parses it, offers it; server verifies.
  await testPublicKeyAuth();

  // 9d. agent auth — a key added to a throwaway ssh-agent authenticates via SSH_AUTH_SOCK (no key files present).
  await testAgentConnect();

  // 10. AddKeysToAgent (ssh_agent.js) — add ed25519 / rsa / ecdsa keys to a throwaway ssh-agent, list them back.
  await testAgentAdd(home);

  console.log(`[ok] ssh-transport-smoke: ${n} assertions passed`);
}

async function testPublicKeyAuth() {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-pkhome-'));
  const sshDir = path.join(home, '.ssh');
  fs.mkdirSync(sshDir, { recursive: true });
  const keyPath = path.join(sshDir, 'id_ed25519');   // a default identity the transport reads
  cp.execFileSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-f', keyPath]);
  const pub = fs.readFileSync(keyPath + '.pub', 'utf8');
  const srv = await startSftpServer({ password: null, authorizedKeys: [pub], files: { 'k.txt': 'authed by public key' } });
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => null, onError: () => {},
  });
  try {
    eq(await transport.remoteReadText(srv.url('/k.txt')), 'authed by public key', 'publickey (key file) auth succeeds');
    transport.closeAll();
  } finally {
    await srv.close();
    fs.rmSync(home, { recursive: true, force: true });
  }
}

async function testAgentConnect() {
  let agent;
  try { agent = cp.execSync('ssh-agent -s', { encoding: 'utf8' }); }
  catch (_e) { console.log('[skip] ssh-agent unavailable — agent-connect path not exercised'); return; }
  const sock = /SSH_AUTH_SOCK=([^;]+);/.exec(agent)[1];
  const pid = /SSH_AGENT_PID=(\d+);/.exec(agent)[1];
  try {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-agentconn-'));
    const keyPath = path.join(dir, 'id_ed25519');
    cp.execFileSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-f', keyPath]);
    const pub = fs.readFileSync(keyPath + '.pub', 'utf8');
    const pem = require('ssh2').utils.parseKey(fs.readFileSync(keyPath)).getPrivatePEM();
    await sshAgent.addKey(sock, pem, 'conn@vv', {});   // add it to the agent (exercises ssh_agent.addKey)
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-agenthome-'));
    fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });   // NO key files → only the agent can authenticate
    const srv = await startSftpServer({ password: null, authorizedKeys: [pub], files: { 'a.txt': 'authed by agent' } });
    transport.configure({
      homeDir: home, agentSock: sock, systemConfigPath: '', systemKnownHostsPath: '',
      promptHostKey: async () => true, promptSecret: async () => null, onError: () => {},
    });
    try {
      eq(await transport.remoteReadText(srv.url('/a.txt')), 'authed by agent', 'agent (SSH_AUTH_SOCK) auth succeeds');
      transport.closeAll();
    } finally {
      await srv.close();
      fs.rmSync(home, { recursive: true, force: true });
    }
  } finally {
    try { process.kill(parseInt(pid, 10)); } catch (_e) {}
  }
}

async function testKeyboardInteractive() {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-mfahome-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  // password auth disabled (password: null); only keyboard-interactive with the right code is accepted
  const srv = await startSftpServer({
    password: null,
    keyboard: { prompts: [{ prompt: 'Verification code: ', echo: false }], verify: (a) => a[0] === '123456' },
    files: { 'ok.txt': 'authenticated via keyboard-interactive' },
  });
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true,
    promptSecret: async (req) => (req.kind === 'keyboard-interactive' ? '123456' : null),
    onError: () => {},
  });
  try {
    eq(await transport.remoteReadText(srv.url('/ok.txt')), 'authenticated via keyboard-interactive',
       'keyboard-interactive (MFA) auth succeeds with the prompted response');
    transport.closeAll();
  } finally {
    await srv.close();
  }
}

async function testProxyJump() {
  const jump = await startSftpServer({ password: 'jump-pw', files: { placeholder: 'x' } });
  const target = await startSftpServer({ password: 'target-pw', files: { 'secret.txt': 'reached through the jump' } });
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-pjhome-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  fs.writeFileSync(path.join(home, '.ssh', 'config'), [
    'Host jump-alias', '  HostName 127.0.0.1', `  Port ${jump.port}`, '  User tester',
    'Host target-alias', '  HostName 127.0.0.1', `  Port ${target.port}`, '  User tester', '  ProxyJump jump-alias',
    '',
  ].join('\n'));
  const pwByPort = { [String(jump.port)]: 'jump-pw', [String(target.port)]: 'target-pw' };
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true,
    promptSecret: async (req) => pwByPort[req.connKey.split(':').pop()],
    onError: () => {},
  });
  eq(await transport.remoteReadText('ssh://target-alias/secret.txt'), 'reached through the jump', 'ProxyJump reaches the target through the jump host');
  eq(transport.connectionCount(), 2, 'ProxyJump pools both the jump and the target');
  transport.closeAll();
  await jump.close();
  await target.close();
}

async function testAgentAdd(home) {
  let agent;
  try { agent = cp.execSync('ssh-agent -s', { encoding: 'utf8' }); }
  catch (_e) { console.log('[skip] ssh-agent unavailable — AddKeysToAgent add path not exercised'); return; }
  const sock = /SSH_AUTH_SOCK=([^;]+);/.exec(agent)[1];
  const pid = /SSH_AGENT_PID=(\d+);/.exec(agent)[1];
  try {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-agentkeys-'));
    const types = [['ed25519', []], ['rsa', ['-b', '2048']], ['ecdsa', ['-b', '256']]];
    for (const [t, extra] of types) {
      const kp = path.join(dir, 'id_' + t);
      cp.execFileSync('ssh-keygen', ['-q', '-t', t, ...extra, '-N', '', '-C', t + '@vv', '-f', kp]);
      const pem = require('ssh2').utils.parseKey(fs.readFileSync(kp)).getPrivatePEM();
      await sshAgent.addKey(sock, pem, t + '@vv', {});
    }
    const listed = cp.execFileSync('ssh-add', ['-l'], { env: Object.assign({}, process.env, { SSH_AUTH_SOCK: sock }), encoding: 'utf8' });
    ok(/ED25519|ed25519/.test(listed) && /RSA/.test(listed) && /ECDSA/.test(listed), 'ssh_agent.addKey added ed25519 + rsa + ecdsa keys to the agent');
  } finally {
    try { process.kill(parseInt(pid, 10)); } catch (_e) {}
  }
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
