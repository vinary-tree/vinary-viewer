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

// Regression guard for docs/scientific/11 H0/E0 (ADR-0046). With Nagle's algorithm on, every SFTP request/response
// turn waits out the peer's delayed-ACK timer (~40 ms on Linux), and a whole-file read issues one request per
// 64 KiB (split into 3 against this fixture's ssh2 ident). Against a server that — like OpenSSH's sshd — disables
// Nagle on ITS side, a 1 MiB read is ~50 turns: ~2 s with the client's Nagle on (measured 2059 ms), ~20 ms with
// it off (measured 21 ms). 800 ms separates the two by a wide margin either way.
async function testNoDelay() {
  const big = crypto.randomBytes(1024 * 1024);
  const srv = await startSftpServer({ password: 'nd-pw', files: { 'big.bin': big }, noDelay: true });
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-nd-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => 'nd-pw', onError: () => {},
  });
  await transport.remoteStat(srv.url('/big.bin'));   // connect + open the SFTP channel outside the timing
  const t0 = process.hrtime.bigint();
  const got = await transport.remoteReadFile(srv.url('/big.bin'));
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  ok(got.equals(big), 'the 1 MiB TCP_NODELAY read is byte-exact');
  ok(ms < 800, `a 1 MiB read over loopback took ${ms.toFixed(1)} ms — must be < 800 ms: the client sets TCP_NODELAY, so no request waits out a delayed ACK`);
  transport.closeAll();
  await srv.close();
}

// ADR-0046 S7 — the pipelined Readable behind remote streaming and paging (remoteCreateReadStream), against BOTH
// server identities: byte-exact at every chunk boundary, at most D requests in flight (and actually pipelined), a
// capping server, a start offset, backpressure (a paused consumer stops new requests: what is requested stays within
// one buffer and one pipeline of what was consumed), destroy mid-stream (handle closed, no error), and a dropped
// connection erroring the stream promptly.
async function testPipelinedStream() {
  const rand = (len) => crypto.randomBytes(len);
  // read a stream to its end — boundedly: a stream that stops issuing requests (or never ends) fails the arm with a
  // message after 15 s instead of hanging the smoke
  const consume = (stream) => new Promise((resolve, reject) => {
    const parts = [];
    const guard = setTimeout(() => reject(new Error('the stream neither ended nor errored within 15 s')), 15000);
    stream.on('data', (c) => parts.push(c));
    stream.on('end', () => { clearTimeout(guard); resolve(Buffer.concat(parts)); });
    stream.on('error', (e) => { clearTimeout(guard); reject(e); });
  });
  const handlesClosed = async (srv) => {
    const deadline = Date.now() + 2000;
    while (srv.openHandles() !== 0 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 10));
    return srv.openHandles();
  };
  for (const ident of [undefined, 'OpenSSH_9.6']) {
    const label = ident || 'ssh2-ident';
    const L = ident ? 260096 : 31952;
    const sizes = [0, 1, L - 1, L, L + 1, 3 * L + 17, 8 * 1024 * 1024];
    const files = {};
    for (const size of sizes) files[`s-${size}.bin`] = rand(size);
    const srv = await startSftpServer({ password: 'ps-pw', ident, files });
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-ps-'));
    fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
    transport.configure({
      homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
      promptHostKey: async () => true, promptSecret: async () => 'ps-pw', onError: () => {},
    });
    const url = (name) => srv.url(`/${name}`);
    try {
      await transport.remoteStat(url('s-1.bin'));
      const { sftp } = await transport._resolvePath(url('s-1.bin'));
      const shape = transport._pipelineShape(sftp);
      for (const size of sizes) {
        srv.resetStats();
        const got = await consume(await transport.remoteCreateReadStream(url(`s-${size}.bin`)));
        ok(got.equals(files[`s-${size}.bin`]), `${label}: a ${size}-byte file streams byte-exact`);
        ok(srv.stats.maxInFlightReads <= shape.depth,
          `${label}: never more than D = ${shape.depth} READs in flight while streaming ${size} bytes (${srv.stats.maxInFlightReads})`);
        // every request is a chunk, a short read's remainder, or one of at most D issued past the end
        ok((srv.stats.byPath[`/s-${size}.bin`] || { reads: 0 }).reads <= Math.ceil(size / L) + shape.depth + 1,
          `${label}: streaming ${size} bytes costs at most ⌈S/C⌉ + D + 1 requests`);
        eq(await handlesClosed(srv), 0, `${label}: the handle is closed once a ${size}-byte stream ends`);
      }
      ok(srv.stats.maxInFlightReads >= 1, '(stats were collected)');
      srv.resetStats();
      await consume(await transport.remoteCreateReadStream(url('s-8388608.bin')));
      ok(srv.stats.maxInFlightReads > 1, `${label}: an 8 MiB stream is actually pipelined (${srv.stats.maxInFlightReads} READs in flight at once)`);

      // a start offset
      const big = files['s-8388608.bin'];
      const from = 3 * L + 5;
      ok((await consume(await transport.remoteCreateReadStream(url('s-8388608.bin'), { start: from }))).equals(big.subarray(from)),
        `${label}: { start } streams from that offset`);

      // the size hint: a small file costs its own requests (no D-wide burst past its end), and a file larger than the
      // hint — it grew after the stat — is still read to its real end
      srv.resetStats();
      ok((await consume(await transport.remoteCreateReadStream(url('s-1.bin'), { size: 1 }))).equals(files['s-1.bin']),
        `${label}: a size-hinted 1-byte stream is byte-exact`);
      ok(srv.stats.byPath['/s-1.bin'].reads <= 2,
        `${label}: …in at most 2 requests (${srv.stats.byPath['/s-1.bin'].reads}), not D = ${shape.depth}`);
      ok((await consume(await transport.remoteCreateReadStream(url('s-8388608.bin'), { size: 1024 * 1024 }))).equals(big),
        `${label}: a stream whose file outgrew its size hint reads on to the real end`);

      // backpressure: pause after the first chunk; what is requested must stay bounded by what was consumed
      srv.resetStats();
      const paused = await transport.remoteCreateReadStream(url('s-8388608.bin'));
      let consumed = 0;
      const parts = [];
      await new Promise((resolve) => {
        paused.once('data', (c) => { consumed += c.length; parts.push(c); paused.pause(); resolve(); });
      });
      await new Promise((r) => setTimeout(r, 300));               // replies to what was in flight land meanwhile
      const requested = srv.stats.byPath['/s-8388608.bin'].readBytes;
      const bound = consumed + paused.readableHighWaterMark + (shape.depth + 1) * shape.chunk;
      ok(requested <= bound,
        `${label}: a paused stream stops requesting — ${requested} bytes read for ${consumed} consumed (≤ ${bound}, of ${big.length})`);
      paused.on('data', (c) => { consumed += c.length; parts.push(c); });
      await new Promise((resolve, reject) => { paused.on('end', resolve); paused.on('error', reject); paused.resume(); });
      ok(Buffer.concat(parts).equals(big), `${label}: …and resumed, it finishes byte-exact`);

      // destroy mid-stream: no error, the handle closes
      const early = await transport.remoteCreateReadStream(url('s-8388608.bin'));
      let earlyError = null;
      early.on('error', (e) => { earlyError = e; });
      await new Promise((resolve) => early.once('data', () => { early.destroy(); resolve(); }));
      await new Promise((r) => setTimeout(r, 50));
      ok(earlyError === null && early.destroyed, `${label}: destroy() mid-stream ends it without an error`);
      eq(await handlesClosed(srv), 0, `${label}: …and closes the handle`);
    } finally {
      transport.closeAll();
      srv.destroyConnections();
      await srv.close();
    }
  }

  // a server that caps reads (answers at most 10 000 bytes per READ): still byte-exact, in order
  {
    const data = rand(300000);
    const srv = await startSftpServer({ password: 'pc-pw', files: { 'c.bin': data }, maxReadLen: 10000 });
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-pc-'));
    fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
    transport.configure({
      homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
      promptHostKey: async () => true, promptSecret: async () => 'pc-pw', onError: () => {},
    });
    try {
      ok((await consume(await transport.remoteCreateReadStream(srv.url('/c.bin')))).equals(data),
        'a server that caps reads at 10 000 bytes: the stream is byte-exact and in order');
    } finally {
      transport.closeAll();
      srv.destroyConnections();
      await srv.close();
    }
  }

  // a dropped connection errors the stream promptly (never hangs)
  {
    const data = rand(8 * 1024 * 1024);
    const srv = await startSftpServer({ password: 'pd-pw', files: { 'd.bin': data } });
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-pd-'));
    fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
    transport.configure({
      homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
      promptHostKey: async () => true, promptSecret: async () => 'pd-pw', onError: () => {},
    });
    try {
      srv.setDelay((req) => (req.op === 'READ' && req.offset > 0 ? 200 : 0));   // keep later reads in flight
      const s = await transport.remoteCreateReadStream(srv.url('/d.bin'));
      const t0 = Date.now();
      const outcome = await new Promise((resolve) => {
        s.on('error', (e) => resolve(e));
        s.on('end', () => resolve(null));
        s.once('data', () => srv.destroyConnections());
      });
      ok(outcome instanceof Error && Date.now() - t0 < 1000,
        `a dropped connection errors the stream within 1 s (${outcome && outcome.message}, ${Date.now() - t0} ms)`);
    } finally {
      srv.setDelay(null);
      transport.closeAll();
      srv.destroyConnections();
      await srv.close();
    }
  }
}

// A closed connection's LATE 'close' event must not evict its successor from the pool. closeAll() unpools a
// connection at once, but ssh2 delivers its 'close' only when the socket is gone; a reconnect pools its successor
// BEFORE the successor's handshake, so the old 'close' landed mid-handshake and deleted the successor by key: the
// successor stayed open but unpooled — leaked, nothing could ever close it — and the next request dialled a third
// connection (with password auth, a second prompt). Deterministic here: the old FIN lands within the new handshake.
async function testStaleCloseKeepsSuccessor() {
  const srv = await startSftpServer({ password: 'sc-pw', files: { 'a.txt': 'alpha' } });
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-sc-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => 'sc-pw', onError: () => {},
  });
  // the server's live-connection count settles asynchronously (sockets close after the FIN exchange): wait, boundedly
  const settled = async (want) => {
    const deadline = Date.now() + 2000;
    while (srv.liveConnections() !== want && Date.now() < deadline) await new Promise((r) => setTimeout(r, 10));
    return srv.liveConnections();
  };
  try {
    await transport.remoteStat(srv.url('/a.txt'));                 // connection A
    eq(await settled(1), 1, 'the server holds one connection');
    transport.closeAll();                                          // A is unpooled at once; its 'close' comes later
    await transport.remoteStat(srv.url('/a.txt'));                 // B — pooled before its handshake, during which A's 'close' lands
    eq((await transport.remoteReadFile(srv.url('/a.txt'))).toString(), 'alpha', 'a read after the reconnect succeeds');
    eq(transport.connectionCount(), 1, 'the transport pools exactly one connection');
    eq(await settled(1), 1, "the server holds exactly ONE live connection: A is gone and B is reused — no third dial, nothing leaked");
    transport.closeAll();
    eq(await settled(0), 0, 'closeAll leaves no connection open on the server');
  } finally {
    transport.closeAll();
    srv.destroyConnections();                                      // so a regression fails its assertion, never hangs close()
    await srv.close();
  }
}

// docs/scientific/11 §9.4 — the SFTP channel window is granted EAGERLY, as OpenSSH's client does. ssh2 alone tops it
// up only once HALF of it (1 MiB) is used, so a pipelined read moved ≈ 1 MiB per round trip whatever its depth.
// Deterministic, not timed: every WINDOW_ADJUST the client sends on the SFTP channel during an 8 MiB read is
// recorded. With the policy (the default) the grants are many and small — a little over three maximum packets each;
// with ssh2's own policy (eagerWindow: false — the control arm, which shows the assertions discriminate) they are
// ≈ 1 MiB each. The private ssh2 surface the policy uses is pinned, and its constants against ssh2's own.
async function testEagerWindow() {
  const size = 8 * 1024 * 1024;
  const big = crypto.randomBytes(size);
  const ssh2Channel = require('ssh2/lib/Channel.js');
  eq(transport._WINDOW_MAX, ssh2Channel.MAX_WINDOW, "WINDOW_MAX is ssh2's MAX_WINDOW, the receive window it advertises");
  eq(transport._WINDOW_STEP, 3 * ssh2Channel.PACKET_SIZE, "WINDOW_STEP is three of ssh2's maximum packets (OpenSSH's threshold)");
  const srv = await startSftpServer({ password: 'ew-pw', files: { 'big.bin': big }, noDelay: true, ident: 'OpenSSH_9.6' });
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-ew-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  const grantsFor = async (eager) => {
    transport.closeAll();               // a fresh connection → a fresh SFTP channel, set up under this policy
    transport.configure({
      homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
      promptHostKey: async () => true, promptSecret: async () => 'ew-pw', onError: () => {}, eagerWindow: eager,
    });
    await transport.remoteStat(srv.url('/big.bin'));
    const { sftp } = await transport._resolvePath(srv.url('/big.bin'));
    ok(typeof sftp._protocol.channelWindowAdjust === 'function' && typeof sftp.incoming.window === 'number'
       && typeof sftp.outgoing.id === 'number' && typeof sftp.outgoing.state === 'string' && typeof sftp.push === 'function',
       "ssh2 still exposes the SFTP channel's push / incoming.window / outgoing.{id,state} / _protocol.channelWindowAdjust");
    eq(sftp.push.name === 'pushThenGrant', eager, `eagerWindow ${eager}: the policy is ${eager ? '' : 'NOT '}installed`);
    const grants = [];
    const proto = sftp._protocol;
    const own = Object.prototype.hasOwnProperty.call(proto, 'channelWindowAdjust');
    const original = proto.channelWindowAdjust;
    proto.channelWindowAdjust = function recordGrant(id, amount) {
      if (id === sftp.outgoing.id) grants.push(amount);
      return original.call(this, id, amount);
    };
    try {
      const got = await transport.remoteReadFile(srv.url('/big.bin'), { size });
      ok(got.equals(big), `eagerWindow ${eager}: the 8 MiB read is byte-exact`);
    } finally {
      if (own) proto.channelWindowAdjust = original; else delete proto.channelWindowAdjust;
    }
    return grants;
  };
  try {
    const eager = await grantsFor(true);
    const lazy = await grantsFor(false);
    const kib = (xs) => `${xs.length} grants, largest ${(Math.max(...xs) / 1024).toFixed(0)} KiB`;
    // a grant tops up what is owed the moment it passes WINDOW_STEP: at most WINDOW_STEP + one packet
    ok(eager.length >= 48 && Math.max(...eager) <= transport._WINDOW_STEP + ssh2Channel.PACKET_SIZE,
      `with the policy, window credit is granted in small steps (${kib(eager)})`);
    ok(lazy.length <= 9 && Math.max(...lazy) >= 900 * 1024,
      `with ssh2's own policy, in ≈ 1 MiB steps (${kib(lazy)}) — the control arm`);
  } finally {
    transport.closeAll();
    transport.configure({ eagerWindow: true });
    await srv.close();
  }
}

// ADR-0046 E1 — the pipelined, preallocated whole-file reader (remoteOpenFile → readAll), against BOTH server
// identities (ssh2's own, whose reads are capped at 31 952 bytes, and OpenSSH's, at 260 096): byte-exact results at
// every chunk boundary, at most D requests in flight, short reads from a capping server, out-of-order replies, a file
// growing or shrinking mid-read, the size cap enforced before any READ, a drop rejecting promptly, the reaper sparing
// an open handle, the depth-1 retry, and no leaked server handles.
async function testPipelinedReader() {
  const rand = (len) => crypto.randomBytes(len);
  // the read helpers close their handle in the BACKGROUND (a read resolves a round trip sooner), so a leak check
  // waits — boundedly — for those closes to land
  const handlesClosed = async (srv) => {
    const deadline = Date.now() + 2000;
    while (srv.openHandles() !== 0 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 10));
    return srv.openHandles();
  };
  const configure = (home, pw, extra) => transport.configure(Object.assign({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => pw, onError: () => {},
  }, extra || {}));
  for (const ident of [undefined, 'OpenSSH_9.6']) {
    const label = ident || 'ssh2-ident';
    const L = ident ? 260096 : 31952;
    const sizes = [0, 1, L - 1, L, L + 1, 3 * L + 17, 8 * 1024 * 1024];
    const files = {};
    for (const size of sizes) files[`f-${size}.bin`] = rand(size);
    const srv = await startSftpServer({ password: 'pr-pw', ident, files });
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-pr-'));
    fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
    configure(home, 'pr-pw');
    const url = (name) => srv.url(`/${name}`);
    await transport.remoteStat(url('f-1.bin'));            // connect + open the SFTP channel
    // white-box guard: the reader sizes its requests from ssh2's PRIVATE _maxReadLen — fail loudly if it moves
    const { sftp } = await transport._resolvePath(url('f-1.bin'));
    eq(sftp._maxReadLen, L, `${label}: ssh2 still exposes _maxReadLen (= ${L}) — the reader's chunk size depends on it`);
    eq(transport._maxReadLenOf(sftp), L, `${label}: the reader reads that cap`);
    const shape = transport._pipelineShape(sftp);
    eq(shape, { chunk: L, depth: Math.min(64, Math.max(4, Math.ceil(2 * 1024 * 1024 / L))) },
      `${label}: chunk = L, depth = clamp(ceil(2 MiB / L), 4, 64)`);

    for (const size of sizes) {
      const want = files[`f-${size}.bin`];
      srv.resetStats();
      const got = await transport.remoteReadFile(url(`f-${size}.bin`));   // no size hint → fstat
      ok(got.equals(want), `${label}: a ${size}-byte file reads byte-exact (fstat-sized)`);
      const hinted = await transport.remoteReadFile(url(`f-${size}.bin`), { size });
      ok(hinted.equals(want), `${label}: a ${size}-byte file reads byte-exact (size hint)`);
      ok(srv.stats.maxInFlightReads <= shape.depth,
        `${label}: never more than D = ${shape.depth} READs in flight (${srv.stats.maxInFlightReads}) for ${size} bytes`);
      eq(await handlesClosed(srv), 0, `${label}: every handle is closed after a ${size}-byte read`);
      // with its size known, an exact file costs its body's requests plus ONE end-of-file probe — no re-request
      srv.resetStats();
      await transport.remoteReadFile(url(`f-${size}.bin`), { size });
      eq((srv.stats.byPath[`/f-${size}.bin`] || { reads: 0 }).reads, Math.ceil(size / L) + 1,
        `${label}: a ${size}-byte file reads in ⌈S/C⌉ + 1 requests (body + probe)`);
    }
    // a file 10 bytes SHORTER than its size hint: its last body request comes back short at end-of-file, one
    // re-request confirms the end — and that short read must not shrink the pipeline into a burst of tiny requests
    srv.resetStats();
    const shortish = `f-${3 * L + 17}.bin`;
    const hinted10 = await transport.remoteReadFile(url(shortish), { size: 3 * L + 27 });
    ok(hinted10.equals(files[shortish]), `${label}: a stale (too large) size hint still reads exactly the file`);
    ok(srv.stats.byPath[`/${shortish}`].reads <= Math.ceil((3 * L + 27) / L) + 2,
      `${label}: end-of-file inside the body costs one re-request, not a shrunken pipeline (${srv.stats.byPath[`/${shortish}`].reads} READs)`);
    srv.resetStats();
    await transport.remoteReadFile(url(`f-${8 * 1024 * 1024}.bin`));
    ok(srv.stats.maxInFlightReads > 1, `${label}: an 8 MiB read is actually pipelined (${srv.stats.maxInFlightReads} in flight)`);

    // replies out of order: every third chunk's reply is held back 25 ms
    srv.setDelay((req) => (req.op === 'READ' && Math.floor(req.offset / L) % 3 === 0 ? 25 : 0));
    ok((await transport.remoteReadFile(url(`f-${3 * L + 17}.bin`))).equals(files[`f-${3 * L + 17}.bin`]),
      `${label}: replies arriving out of order still assemble byte-exact`);
    srv.setDelay(null);

    // a file that GROWS between the stat and the read (by more than the probe chunk) is read to its real end
    const growName = 'grow.bin';
    const grow0 = rand(L + 5);
    const growMore = rand(3 * L + 11);
    fs.writeFileSync(path.join(srv.dir, growName), grow0);
    let grown = false;
    srv.onRequest(async (req) => {
      if (!grown && req.op === 'READ' && req.path.endsWith(growName)) { grown = true; fs.appendFileSync(path.join(srv.dir, growName), growMore); }
    });
    const afterGrowth = await transport.remoteReadFile(url(growName), { size: grow0.length });   // a stale size hint
    srv.onRequest(null);
    ok(afterGrowth.equals(Buffer.concat([grow0, growMore])), `${label}: a file that grew mid-read is read to its new end`);

    // …and one that SHRINKS returns exactly its new content — no byte past its end, none uninitialised
    const shrinkName = 'shrink.bin';
    const big = rand(4 * L + 3);
    fs.writeFileSync(path.join(srv.dir, shrinkName), big);
    let shrunk = false;
    srv.onRequest(async (req) => {
      if (!shrunk && req.op === 'READ' && req.path.endsWith(shrinkName)) { shrunk = true; fs.truncateSync(path.join(srv.dir, shrinkName), L + 7); }
    });
    const afterShrink = await transport.remoteReadFile(url(shrinkName), { size: big.length });
    srv.onRequest(null);
    ok(afterShrink.equals(big.subarray(0, L + 7)), `${label}: a file that shrank mid-read returns exactly its new content`);

    // the size cap is enforced BEFORE any READ
    srv.resetStats();
    await assert.rejects(transport.remoteReadFile(url(`f-${3 * L + 17}.bin`), { maxBytes: 1000 }), /exceeds the 1000-byte read limit/);
    eq((srv.stats.byPath[`/f-${3 * L + 17}.bin`] || { reads: 0 }).reads, 0, `${label}: an over-cap file is refused with 0 READs`);

    // a server that fails when it is asked for several reads at once: the reader retries once at depth 1
    let refused = 0;
    srv.setFault((req) => (req.op === 'READ' && srv.stats.inFlightReads > 1 ? (refused++, srv.STATUS_CODE.FAILURE) : null));
    ok((await transport.remoteReadFile(url(`f-${3 * L + 17}.bin`))).equals(files[`f-${3 * L + 17}.bin`]),
      `${label}: a server that refuses concurrent reads still delivers, at depth 1`);
    ok(refused > 0, `${label}: (the fault really fired: ${refused} refused)`);
    srv.setFault(null);

    // the idle reaper never closes a connection under an open handle
    configure(home, 'pr-pw', { idleMs: 1 });
    srv.setDelay((req) => (req.op === 'READ' ? 150 : 0));
    const slow = transport.remoteReadFile(url(`f-${3 * L + 17}.bin`));
    await new Promise((r) => setTimeout(r, 60));
    transport._reapIdle(Date.now() + 60000);
    // guarded like the drop test below: a reaper that closed the connection under the read would leave it pending
    const stuck = new Promise((_, reject) => setTimeout(() => reject(new Error('the read never settled after the reaper ran')), 5000).unref());
    ok((await Promise.race([slow, stuck])).equals(files[`f-${3 * L + 17}.bin`]),
      `${label}: the reaper running mid-read leaves the read alone`);
    srv.setDelay(null);
    // the read resolved before its handle's CLOSE (that lands in the background, the handle registered until then):
    // the reaper spares the connection until the close lands, then reaps it
    const reapBy = Date.now() + 2000;
    do transport._reapIdle(Date.now() + 60000);
    while (transport.connectionCount() !== 0 && Date.now() < reapBy && await new Promise((r) => setTimeout(r, 10)) === undefined);
    eq(transport.connectionCount(), 0, `${label}: once the handle is closed, the idle connection is reaped`);
    configure(home, 'pr-pw', { idleMs: 5 * 60 * 1000 });

    // a connection DROP mid-read rejects promptly (ssh2 alone would leave requests pending on the dead channel)
    srv.setDelay((req) => (req.op === 'READ' ? 400 : 0));
    const doomed = transport.remoteReadFile(url(`f-${8 * 1024 * 1024}.bin`));
    await new Promise((r) => setTimeout(r, 80));
    const t0 = Date.now();
    srv.destroyConnections();
    // (whichever surfaces first: the transport's own drop error, or the socket's — EPIPE / ECONNRESET). Guarded:
    // a regression that left requests pending on the dead channel must FAIL here, not hang the suite.
    const hung = new Promise((_, reject) => setTimeout(() => reject(new Error('the read never settled after the drop')), 5000).unref());
    await assert.rejects(Promise.race([doomed, hung]), /SSH connection lost|EPIPE|ECONNRESET|closed|socket/i);
    const rejectMs = Date.now() - t0;
    ok(rejectMs < 1000, `${label}: a drop mid-read rejects within 1 s (${rejectMs} ms)`);
    srv.setDelay(null);

    transport.closeAll();
    await srv.close();
  }

  // a server that CAPS its reads below ours (some servers answer at most N bytes per READ): every short read is
  // re-requested from where it stopped, later requests shrink to the cap, and the result is still byte-exact
  const capped = rand(3 * 260096 + 17);
  const csrv = await startSftpServer({ password: 'cap-pw', ident: 'OpenSSH_9.6', maxReadLen: 10000, files: { 'capped.bin': capped } });
  const chome = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sshhome-cap-'));
  fs.mkdirSync(path.join(chome, '.ssh'), { recursive: true });
  configure(chome, 'cap-pw');
  csrv.resetStats();
  ok((await transport.remoteReadFile(csrv.url('/capped.bin'))).equals(capped),
    'a server capping READs at 10 000 bytes (below our 260 096): short reads are re-requested, byte-exact');
  const { sftp: csftp } = await transport._resolvePath(csrv.url('/capped.bin'));
  ok(csrv.stats.maxInFlightReads <= transport._pipelineShape(csftp).depth, 'and never more than D in flight');
  ok(csrv.stats.byPath['/capped.bin'].reads >= Math.ceil(capped.length / 10000),
    `it took at least ⌈S / 10 000⌉ READs (${csrv.stats.byPath['/capped.bin'].reads})`);
  eq(await handlesClosed(csrv), 0, 'and closed its handle');
  transport.closeAll();
  await csrv.close();
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

  // 9e. a throwing status/error sink must not wedge connect/read (guarded sink) — see testThrowingSink.
  await testThrowingSink();

  // 9f. TCP_NODELAY on the client (docs/scientific/11 H0/E0) — a timing guard against the Nagle/delayed-ACK stall.
  await testNoDelay();

  // 9g. the pipelined, preallocated whole-file reader (ADR-0046 E1)
  await testPipelinedReader();

  // 9h. a closed connection's late 'close' event never evicts its successor from the pool
  await testStaleCloseKeepsSuccessor();

  // 9i. the SFTP channel window granted eagerly, as OpenSSH does (docs/scientific/11 §9.4)
  await testEagerWindow();

  // 9j. the pipelined Readable behind remote streaming and paging (ADR-0046 S7)
  await testPipelinedStream();

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

// A THROWING status/error sink must NOT break connect or reads. In the app these sinks are backed by an Electron
// webContents; after the window they were captured against closes, a connect-status or keepalive-error callback can
// throw "Object has been destroyed". `connect()` calls onStatus('connecting') SYNCHRONOUSLY before the connect
// promise, so an unguarded throw there rejected the read directly — which is exactly why, after a suspend dropped
// the SSH connection and the window went away, EVERY subsequent remote open surfaced "Object has been destroyed" as
// its preview. The transport now funnels every sink call through a guard (safeStatus/safeError), so a broken sink is
// swallowed and the read still completes. (Regression; own server so it cannot perturb the main sequence.)
async function testThrowingSink() {
  const srv = await startSftpServer({ password: 'sink-pw', files: { 'notes.md': 'content here' } });
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-sinkhome-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true,
    promptSecret: async () => 'sink-pw',
    onStatus: () => { throw new Error('Object has been destroyed'); },
    onError:  () => { throw new Error('Object has been destroyed'); },
  });
  try {
    eq(await transport.remoteReadText(srv.url('/notes.md')), 'content here',
       'a throwing onStatus/onError sink does not break connect or read (guarded sink)');
    transport.closeAll();
  } finally {
    await srv.close();
    fs.rmSync(home, { recursive: true, force: true });
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
