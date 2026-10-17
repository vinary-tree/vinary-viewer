'use strict';
// Remote whole-file read benchmark — the instrument behind docs/scientific/11 (ADR-0046).
//
// QUESTION. How long does vinary-viewer take to fetch a whole remote file (a slide deck, a PDF) over SFTP, as a
// function of file size S, network round-trip time R and the server's SSH identity string — and how much of
// that is protocol latency a pipelined reader could remove?
//
// HYPOTHESIS H1 (today's reader, ssh2's createReadStream): one ≤64 KiB request in flight at a time, each split
// into k = ⌈64 KiB / L⌉ sequential protocol reads where L is ssh2's _maxReadLen (260 096 B for an OpenSSH or
// dropbear ident, 31 952 B otherwise), so  t ≈ R·(1 open + ⌈S/64 KiB⌉·k + 1 EOF probe) + S/B_cpu.
//
// INSTRUMENT.
//   • The real transport (src/vinary/main/ssh_transport.js) against the hermetic SFTP fixture
//     (test/fixtures/ssh-server.js), with network distance injected by test/fixtures/latency-proxy.js — a TCP
//     proxy delaying every chunk RTT/2 per direction (a server-side READ delay would NOT model window adjusts,
//     OPEN, or STAT; see that file's header).
//   • The server runs in a CHILD process (one per ident) pinned with `taskset -c 6-9` when taskset exists, so
//     its crypto never competes with the measured client; pin the client yourself:
//         taskset -c 2-5 node scripts/bench-remote-read.cjs | tee bench-remote.txt
//   • Each cell = (size, rtt, ident, reader): a warm connection, 2 warm-up reads, then up to --runs timed reads
//     or --budget-s seconds (min 3 runs). Every read is sha256-verified against the served bytes.
//   • Per cell: n, median, p95 (nearest rank), mean ± sd, min, max, MiB/s, client CPU ms per read, peak
//     process arrayBuffers during a read (2 ms sampling), event-loop-delay p99, and H1's predicted time.
//
// READERS.  stream         transport.remoteReadFile — what the app does today (or, after ADR-0046, its default)
//           stream-legacy  ssh2 createReadStream + concat — today's algorithm, kept as the baseline once the
//                          transport changes (identical to `stream` before E1)
//           ssh2-readFile  ssh2's own sftp.readFile (sequential, fstat-sized)
//           fastGet        ssh2 fastGet to /dev/shm then readFile (64 × 32 KiB in flight) — a pipelining reference
//           pipelined:D:C  transport._readAllPipelined with depth D and chunk C (only once E1 lands)
//           open-pdf       the WHOLE remote open the app performs — content_service.openRemoteUri(…, 'pdf') on a
//                          document GROUP: group-<size>.pdf (a valid PDF padded to ≈ size; the read is verified,
//                          ADR-0046 §Torn reads) beside group-<size>.md and a small group-<size>.tex. It times the
//                          stat, the read AND the sibling discovery (H3), checks the group found, and sha256-verifies
//                          the delivered bytes
//           open-md        the same for the group's markdown member (openRemoteUri(…, 'markdown'), ASCII text)
//
// FLAGS. --sizes=64K,1M,8M,32M  --rtts=0,10,50,100  --idents=ssh2,openssh  --readers=stream,ssh2-readFile,fastGet
//        --runs=15 --budget-s=45 --warmup=2 --json=PATH (also VV_BENCH_JSON)   VV_BENCH_QUICK=1 → a small subset.
//        --server-nodelay / --client-nodelay  set TCP_NODELAY (Nagle off) on that side's socket (H0 controls)

const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');
const crypto = require('crypto');
const { monitorEventLoopDelay } = require('perf_hooks');

const ROOT = path.resolve(__dirname, '..');

// ---------------------------------------------------------------------------------------------------------
// server child: `node bench-remote-read.cjs --serve --ident=<ssh2|openssh> --rtts=… --sizes=…`
// ---------------------------------------------------------------------------------------------------------
async function serve(argv) {
  const { startSftpServer } = require(path.join(ROOT, 'test/fixtures/ssh-server.js'));
  const { startLatencyProxy } = require(path.join(ROOT, 'test/fixtures/latency-proxy.js'));
  const ident = argv.ident === 'openssh' ? 'OpenSSH_9.6' : undefined;   // undefined → ssh2's default ident
  const files = {};
  for (const label of argv.sizes) {
    const n = parseSize(label);
    files[`bench-${label}.bin`] = seededBytes(n, n ^ 0x5eed);
    if (argv.group) Object.assign(files, groupFiles(label, n));
  }
  // digests + sizes by file name — every timed read is sha256-verified against the file it targets
  const digests = {};
  const sizes = {};
  for (const [name, buf] of Object.entries(files)) {
    digests[name] = crypto.createHash('sha256').update(buf).digest('hex');
    sizes[name] = buf.length;
  }
  const srv = await startSftpServer({ password: 'bench-pw', files, ident, noDelay: argv.serverNoDelay });
  const proxies = {};
  for (const rtt of argv.rtts) {
    proxies[rtt] = rtt === 0 ? { port: srv.port, close: async () => {} }
      : await startLatencyProxy({ targetHost: '127.0.0.1', targetPort: srv.port, rttMs: rtt });
  }
  process.stdout.write(JSON.stringify({
    ready: true, ident: argv.ident, user: srv.user, password: srv.password, digests, sizes,
    ports: Object.fromEntries(Object.entries(proxies).map(([rtt, p]) => [rtt, p.port])),
  }) + '\n');
  process.stdin.on('data', async () => {
    for (const p of Object.values(proxies)) await p.close();
    await srv.close();
    process.exit(0);
  });
}

// ---------------------------------------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------------------------------------
function parseSize(label) {
  const m = /^(\d+)([KMG]?)$/i.exec(String(label));
  if (!m) throw new Error(`bad size ${label}`);
  return Number(m[1]) * ({ '': 1, K: 1024, M: 1024 ** 2, G: 1024 ** 3 })[m[2].toUpperCase()];
}

function seededBytes(n, seed) {
  // xorshift32 — incompressible enough that SSH's (absent) compression can't help, and reproducible
  const buf = Buffer.allocUnsafe(n);
  let x = (seed >>> 0) || 1;
  for (let i = 0; i < n; i++) { x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0; buf[i] = x & 0xff; }
  return buf;
}

// A document GROUP for the open-* readers (H3): a PDF, a markdown file and a LaTeX source sharing one stem, the
// shape a Beamer talk's directory has (talk.tex beside talk.pdf). The PDF is valid — the remote PDF read is
// VERIFIED (ADR-0046), so random bytes would time the settle loop instead — and padded to ≈ n bytes; the markdown
// member is n bytes of ASCII text (lossless through the UTF-8 decode, so its sha256 still verifies).
function groupFiles(label, n) {
  const { makeDeckPdf } = require(path.join(ROOT, 'test/fixtures/pdf-gen.js'));
  const base = makeDeckPdf({ pages: 3 }).length;
  const pdf = makeDeckPdf({ pages: 3, padBytes: Math.max(0, n - base - 64), seed: n ^ 0x6d });
  const md = Buffer.allocUnsafe(n);
  let x = (n ^ 0x3d) >>> 0 || 1;
  for (let i = 0; i < n; i++) {
    x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0;
    md[i] = i % 80 === 79 ? 0x0a : 0x20 + (x % 95);          // printable ASCII, 80-column lines
  }
  const tex = Buffer.from('\\documentclass{beamer}\n\\begin{document}\n\\begin{frame}{Bench}x\\end{frame}\n\\end{document}\n');
  return { [`group-${label}.pdf`]: pdf, [`group-${label}.md`]: md, [`group-${label}.tex`]: tex };
}

function parseArgs(raw) {
  const quick = process.env.VV_BENCH_QUICK === '1';
  const o = {
    sizes: quick ? ['1M', '8M'] : ['64K', '1M', '8M', '32M'],
    rtts: quick ? [0, 50] : [0, 10, 50, 100],
    idents: ['ssh2', 'openssh'],
    readers: ['stream', 'ssh2-readFile', 'fastGet'],
    runs: quick ? 5 : 15, budgetS: 45, warmup: 2, json: process.env.VV_BENCH_JSON || null,
    serve: false, ident: 'ssh2', group: false,
    serverNoDelay: false, clientNoDelay: false,   // H0 controls: TCP_NODELAY on the server / client socket
  };
  for (const a of raw) {
    const [k, v] = a.replace(/^--/, '').split('=');
    if (k === 'serve') o.serve = true;
    else if (k === 'group') o.group = true;
    else if (k === 'ident') o.ident = v;
    else if (k === 'sizes') o.sizes = v.split(',');
    else if (k === 'rtts') o.rtts = v.split(',').map(Number);
    else if (k === 'idents') o.idents = v.split(',');
    else if (k === 'readers') o.readers = v.split(',');
    else if (k === 'runs') o.runs = Number(v);
    else if (k === 'budget-s') o.budgetS = Number(v);
    else if (k === 'warmup') o.warmup = Number(v);
    else if (k === 'json') o.json = v;
    else if (k === 'server-nodelay') o.serverNoDelay = true;
    else if (k === 'client-nodelay') o.clientNoDelay = true;
  }
  return o;
}

function stats(xs) {
  const s = [...xs].sort((a, b) => a - b);
  const n = s.length;
  const mean = s.reduce((a, b) => a + b, 0) / n;
  const sd = n > 1 ? Math.sqrt(s.reduce((a, b) => a + (b - mean) ** 2, 0) / (n - 1)) : 0;
  const rank = (p) => s[Math.min(n - 1, Math.max(0, Math.ceil(p * n) - 1))];   // nearest-rank percentile
  return { n, median: rank(0.5), p95: rank(0.95), mean, sd, min: s[0], max: s[n - 1] };
}

// H1's prediction for the sequential stream reader (ms): (1 OPEN + ⌈S/64 KiB⌉·k READs + 1 EOF READ) round trips.
function h1PredictMs(size, rtt, ident) {
  const L = ident === 'openssh' ? 260096 : 31952;
  const k = Math.ceil(65536 / L);
  return rtt * (1 + Math.ceil(size / 65536) * k + 1);
}

function environment() {
  const read = (p) => { try { return fs.readFileSync(p, 'utf8').trim(); } catch (_e) { return null; } };
  let affinity = null;
  try { affinity = cp.execFileSync('taskset', ['-cp', String(process.pid)], { encoding: 'utf8' }).trim(); } catch (_e) { /* no taskset */ }
  return {
    date: new Date().toISOString(), node: process.version,
    ssh2: require(path.join(ROOT, 'node_modules/ssh2/package.json')).version,
    cpu: os.cpus()[0] && os.cpus()[0].model, cpus: os.cpus().length,
    governor: read('/sys/devices/system/cpu/cpu2/cpufreq/scaling_governor'),
    curFreqKHz: read('/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq'),
    maxFreqKHz: read('/sys/devices/system/cpu/cpu2/cpufreq/cpuinfo_max_freq'),
    affinity,
  };
}

function startServer(ident, o) {
  const args = [__filename, '--serve', `--ident=${ident}`, `--rtts=${o.rtts.join(',')}`, `--sizes=${o.sizes.join(',')}`];
  if (o.serverNoDelay) args.push('--server-nodelay');
  if (o.readers.some((r) => r.startsWith('open-'))) args.push('--group');   // serve the group trio (open-* readers)
  let hasTaskset = false;
  try { cp.execFileSync('taskset', ['-V'], { stdio: 'ignore' }); hasTaskset = true; } catch (_e) { /* absent */ }
  const child = hasTaskset
    ? cp.spawn('taskset', ['-c', process.env.VV_BENCH_SERVER_CPUS || '6-9', process.execPath, ...args], { stdio: ['pipe', 'pipe', 'inherit'] })
    : cp.spawn(process.execPath, args, { stdio: ['pipe', 'pipe', 'inherit'] });
  return new Promise((resolve, reject) => {
    let buf = '';
    child.stdout.on('data', (d) => {
      buf += d;
      const nl = buf.indexOf('\n');
      if (nl >= 0) resolve({ child, info: JSON.parse(buf.slice(0, nl)) });
    });
    child.on('exit', (code) => reject(new Error(`server child exited ${code}`)));
  });
}

// ---------------------------------------------------------------------------------------------------------
// readers
// ---------------------------------------------------------------------------------------------------------
function makeReaders(transport, content) {
  const viaSftp = async (uri, fn) => { const { sftp, path: p } = await transport._resolvePath(uri); return fn(sftp, p); };
  // the pre-ADR-0046 reader, as the transport keeps it (ssh2 createReadStream + concat); an inline copy of the same
  // algorithm when measuring a transport that predates _readFileStream
  const legacyStream = typeof transport._readFileStream === 'function'
    ? (uri) => transport._readFileStream(uri)
    : (uri) => viaSftp(uri, (sftp, p) => new Promise((resolve, reject) => {
      const chunks = [];
      const s = sftp.createReadStream(p);
      s.on('data', (c) => chunks.push(c)); s.on('error', reject); s.on('end', () => resolve(Buffer.concat(chunks)));
    }));
  const readers = {
    stream: (uri) => transport.remoteReadFile(uri),
    'stream-legacy': legacyStream,
    'ssh2-readFile': (uri) => viaSftp(uri, (sftp, p) => new Promise((res, rej) => sftp.readFile(p, (e, b) => (e ? rej(e) : res(b))))),
    fastGet: (uri) => viaSftp(uri, (sftp, p) => new Promise((res, rej) => {
      const tmp = path.join(fs.existsSync('/dev/shm') ? '/dev/shm' : os.tmpdir(), `vv-bench-${process.pid}.bin`);
      sftp.fastGet(p, tmp, {}, (e) => {
        if (e) return rej(e);
        try { const b = fs.readFileSync(tmp); fs.unlinkSync(tmp); res(b); } catch (err) { rej(err); }
      });
    })),
  };
  // the whole remote open (H3): the payload must carry the group found — the opened file first, then its same-stem
  // members in GROUP_EXTS order — and its bytes (PDF) or text (markdown) are returned for the sha256 check
  const ext = (u) => u.slice(u.lastIndexOf('.'));
  const checkGroup = (payload, expectExts) => {
    const got = (payload.siblings || []).map((sib) => ext(sib.path));
    if (got.join(' ') !== expectExts.join(' ')) throw new Error(`group mismatch: ${got.join(' ')} (expected ${expectExts.join(' ')})`);
    if (payload.incomplete || payload.pending) throw new Error(`open delivered ${payload.pending ? 'pending' : 'incomplete'}`);
  };
  const opens = {
    'open-pdf': { ext: '.pdf', read: async (uri) => {
      const payload = await content.openRemoteUri(uri, 'pdf', { notifies: false });
      checkGroup(payload, ['.pdf', '.md', '.tex']);
      return payload.bytes;
    } },
    'open-md': { ext: '.md', read: async (uri) => {
      const payload = await content.openRemoteUri(uri, 'markdown');
      checkGroup(payload, ['.md', '.pdf', '.tex']);
      return Buffer.from(payload.text, 'utf8');
    } },
  };
  return (name) => {
    if (readers[name]) return { file: (label) => `bench-${label}.bin`, read: readers[name] };
    if (opens[name]) return { file: (label) => `group-${label}${opens[name].ext}`, read: opens[name].read };
    const m = /^pipelined:(\d+):(\d+)$/.exec(name);
    if (m && typeof transport._readAllPipelined === 'function') {
      const depth = Number(m[1]), chunk = Number(m[2]);
      return { file: (label) => `bench-${label}.bin`, read: (uri) => transport._readAllPipelined(uri, { depth, chunk }) };
    }
    throw new Error(`unknown reader ${name}${m ? ' (pipelined reader not available in this transport)' : ''}`);
  };
}

// ---------------------------------------------------------------------------------------------------------
// client
// ---------------------------------------------------------------------------------------------------------
async function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.serve) return serve(o);

  const transport = require(path.join(ROOT, 'src/vinary/main/ssh_transport.js'));
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-bench-home-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  transport.configure({
    homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true,
    promptSecret: async () => 'bench-pw',
    onError: () => {},
    idleMs: 24 * 3600 * 1000,                 // never reap mid-benchmark
  });
  const content = require(path.join(ROOT, 'src/vinary/main/content_service.js'));   // shares `transport`'s pool
  const readerFor = makeReaders(transport, content);
  const env = environment();
  console.log(`# bench-remote-read ${env.date}  node ${env.node}  ssh2 ${env.ssh2}  ${env.cpu} (${env.cpus} cpus)`);
  console.log(`# governor=${env.governor} cur=${env.curFreqKHz}kHz max=${env.maxFreqKHz}kHz  ${env.affinity || 'affinity: (taskset unavailable)'}`);
  console.log(`# sizes=${o.sizes} rtts=${o.rtts} idents=${o.idents} readers=${o.readers} runs≤${o.runs} budget=${o.budgetS}s warmup=${o.warmup}` +
              ` TCP_NODELAY overrides: server=${o.serverNoDelay} client=${o.clientNoDelay}` +
              ' (since E0 the transport itself sets client TCP_NODELAY on every connection)');
  console.log('ident    rtt  size  reader                n   median_ms    p95_ms   mean±sd_ms        MiB/s  cpu_ms  peakAB_MiB  eld_p99_ms  h1_ms');

  const results = [];
  const servers = [];
  try {
    for (const ident of o.idents) {
      const s = await startServer(ident, o);
      servers.push(s);
      for (const rtt of o.rtts) {
        const port = s.info.ports[rtt];
        for (const label of o.sizes) {
          const base = `ssh://${s.info.user}@127.0.0.1:${port}/`;
          await transport.remoteStat(`${base}bench-${label}.bin`);   // warm the connection + SFTP channel + home resolution
          if (o.clientNoDelay) (await transport._resolvePath(`${base}bench-${label}.bin`)).conn.client.setNoDelay(true);
          for (const reader of o.readers) {
            const { file, read } = readerFor(reader);
            const name = file(label);
            const uri = base + name;
            const size = s.info.sizes[name];
            const verify = (buf) => {
              const d = crypto.createHash('sha256').update(buf).digest('hex');
              if (d !== s.info.digests[name]) throw new Error(`sha256 mismatch: ${ident} rtt=${rtt} ${name} ${reader} (${buf.length} bytes)`);
            };
            for (let w = 0; w < o.warmup; w++) verify(await read(uri));
            const walls = [], cpus = [], peaks = [];
            const eld = monitorEventLoopDelay({ resolution: 1 });
            eld.enable();
            const deadline = Date.now() + o.budgetS * 1000;
            while (walls.length < o.runs && (walls.length < 3 || Date.now() < deadline)) {
              if (global.gc) global.gc();
              let peak = process.memoryUsage().arrayBuffers;
              const sampler = setInterval(() => { peak = Math.max(peak, process.memoryUsage().arrayBuffers); }, 2);
              const c0 = process.cpuUsage();
              const t0 = process.hrtime.bigint();
              const buf = await read(uri);
              const t1 = process.hrtime.bigint();
              const c1 = process.cpuUsage(c0);
              clearInterval(sampler);
              peak = Math.max(peak, process.memoryUsage().arrayBuffers);
              verify(buf);
              walls.push(Number(t1 - t0) / 1e6);
              cpus.push((c1.user + c1.system) / 1000);
              peaks.push(peak / (1024 * 1024));
            }
            eld.disable();
            const w = stats(walls);
            const row = {
              ident, rtt, size: label, bytes: size, reader, wall: w, cpuMs: stats(cpus), peakArrayBuffersMiB: stats(peaks),
              eldP99Ms: eld.percentile(99) / 1e6, mibPerS: (size / (1024 * 1024)) / (w.median / 1000),
              h1PredictMs: name.endsWith('.bin') ? h1PredictMs(size, rtt, ident) : null, file: name, samplesMs: walls,
            };
            results.push(row);
            console.log(
              `${ident.padEnd(8)} ${String(rtt).padStart(3)} ${label.padStart(5)}  ${reader.padEnd(20)} ${String(w.n).padStart(2)}` +
              ` ${w.median.toFixed(1).padStart(11)} ${w.p95.toFixed(1).padStart(9)} ${(w.mean.toFixed(1) + '±' + w.sd.toFixed(1)).padStart(12)}` +
              ` ${row.mibPerS.toFixed(2).padStart(12)} ${stats(cpus).median.toFixed(1).padStart(7)} ${stats(peaks).max.toFixed(1).padStart(11)}` +
              ` ${row.eldP99Ms.toFixed(1).padStart(11)} ${(row.h1PredictMs == null ? '—' : row.h1PredictMs.toFixed(0)).padStart(6)}`);
          }
        }
      }
    }
  } finally {
    transport.closeAll();
    for (const s of servers) { try { s.child.stdin.write('quit\n'); } catch (_e) { /* gone */ } }
  }
  if (o.json) {
    fs.writeFileSync(o.json, JSON.stringify({ env, options: o, results }, null, 1));
    console.log(`# wrote ${o.json}`);
  }
}

main().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
