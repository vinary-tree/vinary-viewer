'use strict';
// Hermetic protocol test for the target side of daemon_events.js.  It exercises the real loopback socket,
// rotating descriptor secret, mutual HMAC challenge/response, request framing, descriptor permissions, and
// POSIX/Windows SFTP path codecs without needing an external SSH server.

const assert = require('assert');
const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');
const events = require('../src/vinary/main/daemon_events.js');
const transport = require('../src/vinary/main/ssh_transport.js');
const { startSftpServer } = require('./fixtures/ssh-server.js');

let n = 0;
const ok = (value, message) => { assert.ok(value, message); n++; };
const eq = (actual, expected, message) => { assert.deepStrictEqual(actual, expected, message); n++; };

function frames(socket) {
  let buf = '';
  const values = [];
  const waiters = [];
  socket.setEncoding('utf8');
  socket.on('data', (chunk) => {
    buf += chunk;
    for (;;) {
      const i = buf.indexOf('\n');
      if (i < 0) break;
      const raw = buf.slice(0, i); buf = buf.slice(i + 1);
      if (!raw.trim()) continue;
      const value = JSON.parse(raw);
      if (waiters.length) waiters.shift()(value); else values.push(value);
    }
  });
  return () => values.length ? Promise.resolve(values.shift()) : new Promise((resolve) => waiters.push(resolve));
}

async function main() {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-daemon-events-'));
  const descriptorPath = path.join(temp, 'runtime', 'daemon-events.json');
  events.configure({ descriptorPath, handlers: { releaseSession: () => {} } });

  try {
    let cancelled = null;
    const cancelledStart = events.startServer({ version: 'cancelled-start' });
    events.stopServer();
    await cancelledStart.catch((error) => { cancelled = error; });
    ok(cancelled && /cancelled/.test(cancelled.message), 'shutdown cancels an endpoint start still in flight');
    ok(!fs.existsSync(descriptorPath), 'a cancelled endpoint start never publishes a descriptor');

    const starts = await Promise.all([
      events.startServer({ version: 'test', bundleMtimeMs: 42 }),
      events.startServer({ version: 'test', bundleMtimeMs: 42 }),
    ]);
    const desc = starts[0];
    eq(starts[1].daemonId, desc.daemonId, 'concurrent starts share one endpoint and descriptor');
    eq(desc.protocol, events.PROTOCOL, 'descriptor advertises the expected protocol');
    ok(desc.port > 0 && desc.host === '127.0.0.1', 'event server binds an ephemeral loopback endpoint');
    ok(typeof desc.secret === 'string' && desc.secret.length >= 40, 'descriptor carries a strong rotating secret');
    eq(JSON.parse(fs.readFileSync(descriptorPath, 'utf8')).daemonId, desc.daemonId,
      'the published descriptor belongs to the running endpoint');
    if (process.platform !== 'win32') {
      eq(fs.statSync(descriptorPath).mode & 0o777, 0o600, 'descriptor is owner-readable only');
    }

    const socket = net.connect(desc.port, '127.0.0.1');
    await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
    const next = frames(socket);
    const clientId = 'client-test'; const clientNonce = 'client-nonce';
    socket.write(JSON.stringify({ type: 'hello', protocol: events.PROTOCOL, clientId, nonce: clientNonce,
      descriptorPath }) + '\n');
    const challenge = await next();
    eq(challenge.type, 'challenge', 'target challenges the source before accepting requests');
    socket.write(JSON.stringify({ type: 'authenticate', proof: events._hmac(desc.secret,
      ['client', events.PROTOCOL, clientId, clientNonce, desc.daemonId, challenge.nonce, descriptorPath]) }) + '\n');
    const ready = await next();
    ok(events._safeEqual(ready.proof, events._hmac(desc.secret,
      ['server', events.PROTOCOL, clientId, clientNonce, desc.daemonId, challenge.nonce, ready.sessionId,
        descriptorPath])),
    'source verifies a target proof bound to both nonces and the session');
    socket.write(JSON.stringify({ type: 'request', id: '1', op: 'ping', payload: {} }) + '\n');
    const pong = await next();
    ok(pong.ok && pong.id === '1' && typeof pong.result.now === 'number',
      'authenticated requests receive framed responses');
    socket.destroy();

    const impostor = net.connect(desc.port, '127.0.0.1');
    await new Promise((resolve, reject) => { impostor.once('connect', resolve); impostor.once('error', reject); });
    const nextImpostor = frames(impostor);
    impostor.write(JSON.stringify({ type: 'hello', protocol: events.PROTOCOL,
      clientId: 'impostor', nonce: 'wrong', descriptorPath }) + '\n');
    eq((await nextImpostor()).type, 'challenge', 'unauthenticated connections receive only a challenge');
    const rejected = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('bad daemon-event proof was not rejected')), 2000);
      impostor.once('close', () => { clearTimeout(timer); resolve(true); });
    });
    impostor.write(JSON.stringify({ type: 'authenticate', proof: 'not-the-proof' }) + '\n');
    ok(await rejected, 'a source without the descriptor secret is disconnected');

    const chrooted = net.connect(desc.port, '127.0.0.1');
    await new Promise((resolve, reject) => { chrooted.once('connect', resolve); chrooted.once('error', reject); });
    const namespaceRejected = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('mismatched SFTP namespace was not rejected')), 2000);
      chrooted.once('close', () => { clearTimeout(timer); resolve(true); });
    });
    chrooted.write(JSON.stringify({ type: 'hello', protocol: events.PROTOCOL,
      clientId: 'chrooted', nonce: 'namespace', descriptorPath: '/virtual/home/daemon-events.json' }) + '\n');
    ok(await namespaceRejected, 'the handshake rejects an SFTP namespace that differs from the target daemon');

    eq(events._sftpToLocal('/srv/repo/a.md', 'posix-v1'), '/srv/repo/a.md', 'POSIX SFTP path maps locally');
    eq(events._localToSftp('C:\\Users\\Alice\\repo', 'windows-openssh-v1'), '/C:/Users/Alice/repo',
      'Windows OpenSSH path maps into the SFTP namespace');
    eq(events._sftpToLocal('/C:/Users/Alice/repo', 'windows-openssh-v1'), 'C:\\Users\\Alice\\repo',
      'Windows OpenSSH SFTP path maps back locally');
    eq(events._uriWithPath('ssh://alice@example.test/old', '/srv/a b/100%'),
      'ssh://alice@example.test/srv/a%20b/100%25', 'target paths become encoded remote URIs');
    eq(events._descriptorUri('sftp://alice@example.test/srv/x'),
      `sftp://alice@example.test/~/${events.DESCRIPTOR_REL}`, 'descriptor discovery is target-home relative');
  } finally {
    events.shutdown();
    ok(!fs.existsSync(descriptorPath), 'shutdown removes only the descriptor owned by this endpoint');
    fs.rmSync(temp, { recursive: true, force: true });
  }
  await sshBridge();
  await rejectImpostorTarget();
  await cancelInFlightDiscovery();
  await noTargetFallback();
  console.log(`[ok] daemon-events-smoke: ${n} assertions passed`);
}

async function rejectImpostorTarget() {
  const sshHome = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-daemon-events-impostor-'));
  fs.mkdirSync(path.join(sshHome, '.ssh'), { recursive: true });
  const srv = await startSftpServer({ allowNone: true, exposeAbsoluteRoot: sshHome,
    realHome: sshHome, dir: sshHome });
  const descriptorPath = path.join(sshHome, ...events.DESCRIPTOR_REL.split('/'));
  const secret = Buffer.alloc(32, 0x5a).toString('base64url');
  const daemonId = 'impostor-daemon-id';
  const serverNonce = 'impostor-server-nonce';
  let clientProofValid = false;
  const fake = net.createServer((socket) => {
    let buf = '';
    let hello = null;
    socket.setEncoding('utf8');
    socket.on('data', (chunk) => {
      buf += chunk;
      for (;;) {
        const i = buf.indexOf('\n');
        if (i < 0) break;
        const raw = buf.slice(0, i); buf = buf.slice(i + 1);
        if (!raw.trim()) continue;
        const msg = JSON.parse(raw);
        if (msg.type === 'hello') {
          hello = msg;
          socket.write(JSON.stringify({ type: 'challenge', protocol: events.PROTOCOL,
            daemonId, nonce: serverNonce, capabilities: [] }) + '\n');
        } else if (msg.type === 'authenticate' && hello) {
          clientProofValid = events._safeEqual(msg.proof, events._hmac(secret,
            ['client', events.PROTOCOL, hello.clientId, hello.nonce, daemonId, serverNonce,
              descriptorPath]));
          socket.write(JSON.stringify({ type: 'ready', protocol: events.PROTOCOL,
            sessionId: 'forged-session', proof: 'forged-target-proof' }) + '\n');
        }
      }
    });
  });
  await new Promise((resolve, reject) => {
    fake.once('error', reject);
    fake.listen(0, '127.0.0.1', resolve);
  });
  fs.mkdirSync(path.dirname(descriptorPath), { recursive: true, mode: 0o700 });
  fs.writeFileSync(descriptorPath, JSON.stringify({
    protocol: events.PROTOCOL, host: '127.0.0.1', port: fake.address().port, secret, daemonId,
    pathCodec: 'posix-v1', capabilities: [],
  }) + '\n', { mode: 0o600 });

  transport.configure({
    homeDir: sshHome, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => '',
  });
  events.configure({ descriptorPath, onContentState: () => {}, onConnectionState: () => {} });
  try {
    const uri = srv.url(path.join(sshHome, 'doc.md'));
    let rejected = null;
    await events.subscribeContent(uri, 'impostor-target', () => {}).catch((error) => { rejected = error; });
    ok(clientProofValid, 'source proof is bound to the negotiated target nonce and descriptor path');
    ok(rejected && /authentication failed/.test(rejected.message),
      'the source rejects a target that cannot prove possession of the descriptor secret');
  } finally {
    events.shutdown();
    transport.closeAll();
    srv.destroyConnections();
    await srv.close();
    await new Promise((resolve) => fake.close(resolve));
    fs.rmSync(sshHome, { recursive: true, force: true });
  }
}

async function cancelInFlightDiscovery() {
  const sshHome = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-daemon-events-cancel-'));
  fs.mkdirSync(path.join(sshHome, '.ssh'), { recursive: true });
  const srv = await startSftpServer({ allowNone: true, exposeAbsoluteRoot: sshHome,
    realHome: sshHome, dir: sshHome });
  transport.configure({
    homeDir: sshHome, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => '',
  });
  events.configure({ descriptorPath: path.join(sshHome, ...events.DESCRIPTOR_REL.split('/')),
    onContentState: () => {}, onConnectionState: () => {} });

  const originalResolvePath = transport.remoteResolvePath;
  let releaseResolve;
  let discoveryStarted;
  const started = new Promise((resolve) => { discoveryStarted = resolve; });
  const release = new Promise((resolve) => { releaseResolve = resolve; });
  transport.remoteResolvePath = async (uri) => {
    discoveryStarted();
    await release;
    return originalResolvePath(uri);
  };
  try {
    const uri = srv.url(path.join(sshHome, 'doc.md'));
    let cancelled = null;
    const opening = events.treeOpen(uri, 'cancel-in-flight', uri)
      .catch((error) => { cancelled = error; return null; });
    await started;
    await events.cancelTreeOpen(uri, 'cancel-in-flight', uri);
    releaseResolve();
    await opening;
    ok(cancelled && /cancelled/.test(cancelled.message),
      'releasing the last desired tree aborts discovery still in flight');
    eq(events._clientCount(), 0, 'in-flight cancellation leaves no orphan daemon-event client');
  } finally {
    releaseResolve();
    transport.remoteResolvePath = originalResolvePath;
    events.shutdown();
    transport.closeAll();
    srv.destroyConnections();
    await srv.close();
    fs.rmSync(sshHome, { recursive: true, force: true });
  }
}

async function sshBridge() {
  const sshHome = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-daemon-events-ssh-'));
  fs.mkdirSync(path.join(sshHome, '.ssh'), { recursive: true });
  const srv = await startSftpServer({ password: 'event-test', files: { 'doc.md': '# remote\n' },
    exposeAbsoluteRoot: sshHome, realHome: sshHome, dir: sshHome });
  const descriptorPath = path.join(srv.dir, ...events.DESCRIPTOR_REL.split('/'));
  const calls = [];
  let pushInvalidate;
  let invalidated;
  let invalidation = new Promise((resolve) => { invalidated = resolve; });
  const states = [];

  transport.configure({
    homeDir: sshHome, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => srv.password,
  });
  events.configure({
    descriptorPath,
    onTreeUpdate: () => {}, onContentState: (state) => states.push(state),
    onConnectionState: (state) => states.push(state),
    handlers: {
      contentSubscribe: (_session, subId, p, push) => {
        calls.push(['content-subscribe', subId, p]); pushInvalidate = push;
      },
      contentUnsubscribe: (_session, subId) => calls.push(['content-unsubscribe', subId]),
      treeOpen: (_session, ownerId, p) => {
        calls.push(['tree-open', ownerId, p]);
        return { root: path.join(sshHome, 'repo'), files: ['a b.md', 'src/100%.clj'], synthetic: false };
      },
      treeRoots: (_session, ownerId, roots) => calls.push(['tree-roots', ownerId, roots]),
      treeExpanded: (_session, ownerId, scopes) => calls.push(['tree-expanded', ownerId, scopes]),
      treeRefresh: (_session, ownerId, request) => {
        calls.push(['tree-refresh', ownerId, request]);
        return { root: request.root, scope: 'src', files: ['src/new file.clj'], synthetic: false };
      },
      treeRefreshAll: (_session, ownerId) => {
        calls.push(['tree-refresh-all', ownerId]);
        return [{ root: path.join(sshHome, 'repo'), files: ['all.md'], synthetic: false }];
      },
      releaseTreeOwner: (_session, ownerId) => calls.push(['release-owner', ownerId]),
      releaseSession: () => {},
    },
  });

  try {
    await events.startServer({ version: 'ssh-test' });
    const docPath = path.join(sshHome, 'doc.md');
    const uri = srv.url(docPath);
    await events.subscribeContent(uri, 'doc-sub', () => invalidated('changed'));
    eq(calls[0], ['content-subscribe', 'doc-sub', docPath],
      'source discovers the descriptor over SFTP and subscribes through SSH forwarding');
    pushInvalidate('change');
    eq(await invalidation, 'changed', 'target invalidation crosses the authenticated SSH event channel');

    const tree = await events.treeOpen(uri, 'window-7', uri);
    eq(tree.root, srv.url(path.join(sshHome, 'repo')),
      'target-local project root is converted to a source remote URI');
    eq(tree.files, ['a%20b.md', 'src/100%25.clj'], 'remote tree paths are URI encoded by segment');
    await events.treeRoots(uri, 'window-7', [tree.root]);
    await events.treeExpanded(uri, 'window-7', [{ root: tree.root, path: `${tree.root}/src` }]);
    ok(calls.some((c) => c[0] === 'tree-roots' && c[2][0] === path.join(sshHome, 'repo')),
      'visible remote roots are translated back into target-local paths');
    ok(calls.some((c) => c[0] === 'tree-expanded'
      && c[2][0].path === path.join(sshHome, 'repo', 'src')),
      'expanded remote scopes are translated back into target-local paths');
    const refreshed = await events.treeRefresh(uri, 'window-7', tree.root, `${tree.root}/src`);
    eq(refreshed.root, tree.root, 'manual tree refresh maps the target root back to its remote URI');
    eq(refreshed.files, ['src/new%20file.clj'], 'manual tree refresh URI-encodes returned file segments');
    ok(calls.some((c) => c[0] === 'tree-refresh'
      && c[2].root === path.join(sshHome, 'repo') && c[2].path === path.join(sshHome, 'repo', 'src')),
    'manual tree refresh maps the source request into target-local paths');
    const refreshedAll = await events.treeRefreshAll(uri, 'window-7');
    eq(refreshedAll[0].root, tree.root, 'Refresh All maps every target entry back to the source URI namespace');

    events.stopServer();
    await events.startServer({ version: 'ssh-test-restarted' });
    const restored = () => ['content-subscribe', 'tree-open', 'tree-roots', 'tree-expanded']
      .every((op) => calls.filter((c) => c[0] === op).length >= 2);
    for (let i = 0; i < 80 && !restored(); i++) {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    ok(calls.filter((c) => c[0] === 'content-subscribe').length >= 2,
      'content subscriptions are restored after the target daemon restarts');
    ok(calls.filter((c) => c[0] === 'tree-open').length >= 2,
      'target-side project knowledge is restored after reconnect');
    ok(calls.filter((c) => c[0] === 'tree-roots').length >= 2,
      'visible remote roots are restored after reconnect');
    ok(calls.filter((c) => c[0] === 'tree-expanded').length >= 2,
      'expanded remote watcher scopes are restored after reconnect');
    ok(states.some((s) => s.active === false) && states.some((s) => s.active === true),
      'source state reports fallback during disconnect and active events after reconnect');
    invalidation = new Promise((resolve) => { invalidated = resolve; });
    pushInvalidate('change');
    eq(await invalidation, 'changed', 'content invalidation still crosses the channel after reconnect');

    await events.releaseOwner(uri, 'window-7');
    await events.unsubscribeContent(uri, 'doc-sub');
    ok(calls.some((c) => c[0] === 'release-owner'), 'remote tree ownership is explicitly released');
    ok(calls.some((c) => c[0] === 'content-unsubscribe'), 'remote content ownership is explicitly released');
  } finally {
    events.shutdown();
    transport.closeAll();
    await srv.close();
    fs.rmSync(sshHome, { recursive: true, force: true });
  }
}

async function noTargetFallback() {
  const sshHome = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-daemon-events-none-'));
  fs.mkdirSync(path.join(sshHome, '.ssh'), { recursive: true });
  const srv = await startSftpServer({ password: 'fallback-test', files: { 'doc.md': '# fallback still opens\n' },
    exposeAbsoluteRoot: sshHome, realHome: sshHome, dir: sshHome });
  const descriptorPath = path.join(sshHome, ...events.DESCRIPTOR_REL.split('/'));
  const calls = [];
  const lateTrees = [];
  const contentStates = [];
  transport.configure({
    homeDir: sshHome, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => srv.password,
  });
  events.configure({
    descriptorPath,
    onTreeUpdate: (update) => lateTrees.push(update),
    onContentState: (state) => contentStates.push(state), onConnectionState: () => {},
    handlers: {
      contentSubscribe: (_session, subId, p) => calls.push(['content-subscribe', subId, p]),
      contentUnsubscribe: () => {},
      treeOpen: (_session, ownerId, p) => {
        calls.push(['tree-open', ownerId, p]);
        return { root: sshHome, files: ['doc.md'], synthetic: true };
      },
      releaseTreeOwner: () => {}, releaseSession: () => {},
    },
  });
  try {
    const docPath = path.join(sshHome, 'doc.md');
    const uri = srv.url(docPath);
    let unavailable = null;
    await events.subscribeContent(uri, 'fallback-doc', () => {}).catch((error) => { unavailable = error; });
    ok(unavailable && /descriptor|No such file|not found/i.test(unavailable.message),
      'absence of a target daemon is reported as event-channel unavailability');
    eq(await transport.remoteReadText(uri), '# fallback still opens\n',
      'event discovery failure does not interfere with ordinary SFTP content reads');
    await events.treeOpen(uri, 'late-window', uri).catch(() => {});
    await events.treeOpen(uri, 'cancelled-window', uri).catch(() => {});
    await events.cancelTreeOpen(uri, 'cancelled-window', uri);
    await events.startServer({ version: 'late-target' });
    const lateReady = () => calls.length >= 2 && lateTrees.length >= 1
      && contentStates.some((state) => state.subId === 'fallback-doc' && state.active === true);
    for (let i = 0; i < 80 && !lateReady(); i++) await new Promise((resolve) => setTimeout(resolve, 100));
    ok(calls.some((call) => call[0] === 'content-subscribe' && call[2] === docPath),
      'a target appearing after initial discovery restores the desired content subscription');
    ok(calls.some((call) => call[0] === 'tree-open' && call[2] === docPath),
      'a target appearing after initial discovery offers the desired remote tree');
    ok(!calls.some((call) => call[0] === 'tree-open' && call[1] === 'cancelled-window'),
      'an undelivered tree open can be cancelled before late-target restoration');
    ok(lateTrees.some((update) => update.entry.root === srv.url(sshHome)),
      'the late target tree is delivered to the source renderer callback');
    ok(contentStates.some((state) => state.subId === 'fallback-doc' && state.active === true),
      'late target restoration reports the content event channel active');
  } finally {
    events.shutdown();
    transport.closeAll();
    await srv.close();
    fs.rmSync(sshHome, { recursive: true, force: true });
  }
}

main().catch((e) => { events.shutdown(); console.error(e); process.exit(1); });
