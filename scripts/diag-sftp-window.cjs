'use strict';
// SFTP channel-window trace: the instrument behind docs/scientific/11 §9.4.
//
// QUESTION. A pipelined whole-file read keeps more than the 2 MiB channel window of requests in flight, so H2
// predicts one window per round trip. At 32 MiB and 50 ms it moved only about 1 MiB per round trip. Where does the
// other half go?
//
// METHOD. One whole-file read through the real transport, against the in-process SFTP fixture behind the latency
// proxy. The protocol is logged on both sides:
//   • every READ request as the server receives it (the fixture's onRequest hook);
//   • every CHANNEL_DATA the client's SFTP channel consumes, with the client's receive window after it (a wrapper
//     around the channel-emulating SFTP object's push — outermost, so it sees the window after any grant);
//   • every WINDOW_ADJUST the client sends on that channel (a wrapper around _protocol.channelWindowAdjust).
// The events are then counted per round-trip bucket: bytes received, grants sent, READs arriving at the server, and
// the lowest receive window seen.
//
// POLICIES. --policy=eager is the transport's default: credit is granted the moment more than three packets are owed,
// as OpenSSH does (ssh_transport.js eagerWindow). --policy=ssh2 configures eagerWindow: false, leaving ssh2's own
// policy: grant only once half the window is used.
//
//   node scripts/diag-sftp-window.cjs --rtt=50 --size-mib=32 --ident=openssh --policy=ssh2 | tee trace.txt
//
// The script measures one read with no warm-up and no repetition. It is a trace, not a benchmark; the timed
// comparison is bench-remote-read.

const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const { startSftpServer } = require(path.join(ROOT, 'test/fixtures/ssh-server.js'));
const { startLatencyProxy } = require(path.join(ROOT, 'test/fixtures/latency-proxy.js'));
const transport = require(path.join(ROOT, 'src/vinary/main/ssh_transport.js'));

function parseArgs(raw) {
  const o = { rtt: 50, sizeMiB: 32, ident: 'openssh', policy: 'eager', events: 0 };
  for (const a of raw) {
    const [k, v] = a.replace(/^--/, '').split('=');
    if (k === 'rtt') o.rtt = Number(v);
    else if (k === 'size-mib') o.sizeMiB = Number(v);
    else if (k === 'ident') o.ident = v;
    else if (k === 'policy') o.policy = v;
    else if (k === 'events') o.events = Number(v);          // also print the first N raw events
  }
  if (o.policy !== 'eager' && o.policy !== 'ssh2') throw new Error(`--policy must be eager or ssh2, not ${o.policy}`);
  return o;
}

async function main() {
  const o = parseArgs(process.argv.slice(2));
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-window-diag-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  const size = o.sizeMiB * 1024 * 1024;
  const content = Buffer.alloc(size, 0x5a);
  const ident = o.ident === 'openssh' ? 'OpenSSH_9.6' : undefined;
  const srv = await startSftpServer({ password: 'diag-pw', files: { 'big.bin': content }, ident, noDelay: true });
  const proxy = await startLatencyProxy({ targetHost: '127.0.0.1', targetPort: srv.port, rttMs: o.rtt });
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => 'diag-pw', onError: () => {},
    idleMs: 3600 * 1000, eagerWindow: o.policy === 'eager',
  });
  const uri = `ssh://${srv.user}@127.0.0.1:${proxy.port}/big.bin`;
  try {
    await transport.remoteStat(uri);                      // connect + open the SFTP channel outside the trace
    const { conn, sftp } = await transport._resolvePath(uri);
    const shape = transport._pipelineShape(sftp, {});
    const events = [];
    let t0 = 0;
    const now = () => Number(process.hrtime.bigint()) / 1e6 - t0;
    const proto = conn.client._protocol;
    const grant = proto.channelWindowAdjust;
    proto.channelWindowAdjust = function traceGrant(id, amount) {
      if (id === sftp.outgoing.id) events.push({ kind: 'grant', t: now(), bytes: amount });
      return grant.call(this, id, amount);
    };
    const push = sftp.push;
    sftp.push = function tracePush(data) {
      const ret = push.call(this, data);
      if (data) events.push({ kind: 'data', t: now(), bytes: data.length, window: sftp.incoming.window });
      return ret;
    };
    srv.onRequest((req) => { if (req.op === 'READ') events.push({ kind: 'read', t: now(), offset: req.offset, bytes: req.length }); });
    t0 = Number(process.hrtime.bigint()) / 1e6;
    const got = await transport.remoteReadFile(uri, { size });
    const total = now();
    if (!got.equals(content)) throw new Error('the read is not byte-exact');

    const bucketMs = Math.max(1, o.rtt);
    const buckets = new Map();
    const bucket = (t) => {
      const k = Math.floor(t / bucketMs);
      if (!buckets.has(k)) buckets.set(k, { rx: 0, grants: 0, granted: 0, reads: 0, minWindow: Infinity });
      return buckets.get(k);
    };
    for (const e of events) {
      const b = bucket(e.t);
      if (e.kind === 'data') { b.rx += e.bytes; b.minWindow = Math.min(b.minWindow, e.window); }
      else if (e.kind === 'grant') { b.grants += 1; b.granted += e.bytes; }
      else b.reads += 1;
    }
    console.log(`# diag-sftp-window ${new Date().toISOString()}  node ${process.version}  policy=${o.policy}` +
                ` ident=${o.ident} rtt=${o.rtt} ms size=${o.sizeMiB} MiB  shape=${JSON.stringify(shape)}`);
    console.log(`# total ${total.toFixed(1)} ms = ${(total / Math.max(1, o.rtt)).toFixed(1)} R;` +
                ` server max READs in flight ${srv.stats.maxInFlightReads}; grants ${events.filter((e) => e.kind === 'grant').length}`);
    console.log('bucket(t/R)   rx_KiB  grants  granted_KiB  server_READs  min_window_KiB');
    for (const [k, b] of [...buckets.entries()].sort((x, y) => x[0] - y[0])) {
      console.log(`${String(k).padStart(11)} ${(b.rx / 1024).toFixed(0).padStart(8)} ${String(b.grants).padStart(7)}` +
                  ` ${(b.granted / 1024).toFixed(0).padStart(12)} ${String(b.reads).padStart(13)}` +
                  ` ${(b.minWindow === Infinity ? '—' : (b.minWindow / 1024).toFixed(0)).padStart(15)}`);
    }
    if (o.events > 0) {
      console.log(`\n# first ${o.events} events (t in ms)`);
      for (const e of events.slice(0, o.events)) console.log(JSON.stringify(Object.assign({}, e, { t: Number(e.t.toFixed(2)) })));
    }
  } finally {
    transport.closeAll();
    srv.destroyConnections();
    await proxy.close();
    await srv.close();
    fs.rmSync(home, { recursive: true, force: true });
  }
}

main().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
