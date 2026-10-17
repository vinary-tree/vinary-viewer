'use strict';
// Torn PDF reads during REAL LaTeX compile loops — the instrument behind docs/scientific/11 H5 (ADR-0046 §Torn reads).
//
// QUESTION. pdflatex / lualatex / xelatex rewrite their output PDF in place. How often does a read racing a compile
// return bytes that are no complete version of the file (a TORN read)? What would a viewer make of such bytes? And does
// the verified reader (content_service readPdfLocal / readPdfRemote) ever hand one over as complete?
//
// METHOD. For each engine, one temporary directory holds a generated Beamer deck (talk.tex). The engine recompiles it
// in place, back to back, for the run's duration. GROUND TRUTH is the sha256 of the PDF each compile leaves behind once
// the engine has exited: those are the file's complete versions. While the loop runs, three readers sample it:
//   • naive    fs.readFileSync, as the viewer did before ADR-0046. Each read is hashed; afterwards, a read is
//              CONSISTENT if its hash is a complete version (or a byte-identical prefix of none — see below) and TORN
//              otherwise. Every torn read is also handed to pdf.js (the headless legacy build): does it open, and with
//              how many pages, or does it fail?
//   • verified readPdfLocal(…, {notifies: false}) — the settle loop with no change notification to wait for, so it
//              returns bytes flagged complete, or bytes flagged incomplete. A complete-flagged delivery whose hash is
//              not a complete version would be a TORN DELIVERY: the failure H5 says never happens.
//   • remote   readPdfRemote through the in-process SFTP fixture serving the same directory, with the fixture's byte
//              counter. Its incomplete probes are measured: every attempt that stopped at the tail.
// Readings are classified only AFTER the run, against the full set of complete versions, so no reader is judged by the
// integrity check under test.
//
//   node scripts/exp-torn-pdf-reads.cjs --engines=pdflatex,lualatex,xelatex --seconds=60 --json=out.json | tee out.txt
//
// Needs the engines and Beamer installed (TeX Live). Flags: --engines (default all three), --seconds per engine
// (default 60), --frames (default 40), --naive-ms (the naive sampling gap, default 3), --json PATH.

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const content = require(path.join(ROOT, 'src/vinary/main/content_service.js'));
const transport = require(path.join(ROOT, 'src/vinary/main/ssh_transport.js'));
const { startSftpServer } = require(path.join(ROOT, 'test/fixtures/ssh-server.js'));

function parseArgs(raw) {
  const o = { engines: ['pdflatex', 'lualatex', 'xelatex'], seconds: 60, frames: 40, naiveMs: 3, json: null };
  for (const a of raw) {
    const [k, v] = a.replace(/^--/, '').split('=');
    if (k === 'engines') o.engines = v.split(',');
    else if (k === 'seconds') o.seconds = Number(v);
    else if (k === 'frames') o.frames = Number(v);
    else if (k === 'naive-ms') o.naiveMs = Number(v);
    else if (k === 'json') o.json = v;
  }
  return o;
}

const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// A Beamer deck with real typesetting work per frame (overlays, a list, some math), so a compile takes long enough
// to be raced, and its PDF is written while it runs.
function deckSource(frames) {
  const body = [];
  for (let f = 1; f <= frames; f++) {
    body.push(`\\begin{frame}{Frame ${f}}
  \\begin{itemize}
    \\item<1-> Point one of frame ${f}: $\\sum_{k=1}^{${f}} k = \\frac{${f}(${f}+1)}{2}$
    \\item<2-> Point two, revealed on the second overlay
    \\item<3-> Point three: $\\int_0^{${f}} x^2\\,dx = \\frac{${f}^3}{3}$
  \\end{itemize}
\\end{frame}`);
  }
  return `\\documentclass[aspectratio=169]{beamer}
\\usetheme{Madrid}
\\title{Torn-read experiment}
\\begin{document}
\\begin{frame}\\titlepage\\end{frame}
${body.join('\n')}
\\end{document}
`;
}

// pdf.js (headless, legacy build) on a torn copy: does it open, and with how many pages?
async function pdfjsVerdict(lib, bytes) {
  try {
    const doc = await lib.getDocument({ data: new Uint8Array(bytes), useSystemFonts: true, isEvalSupported: false, verbosity: 0 }).promise;
    const pages = doc.numPages;
    await doc.destroy();
    return { opened: true, pages };
  } catch (e) {
    return { opened: false, error: String(e && e.message || e).slice(0, 80) };
  }
}

async function runEngine(engine, o, lib) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `vv-torn-${engine}-`));
  const tex = path.join(dir, 'talk.tex');
  const pdf = path.join(dir, 'talk.pdf');
  fs.writeFileSync(tex, deckSource(o.frames));
  const compile = () => new Promise((resolve) => {
    const t0 = Date.now();
    const child = cp.spawn(engine, ['-interaction=nonstopmode', '-halt-on-error', 'talk.tex'], { cwd: dir, stdio: 'ignore' });
    child.on('exit', (code) => resolve({ code, ms: Date.now() - t0 }));
  });
  // one warm-up compile: the PDF exists before any reader starts, as in an authoring session
  const warm = await compile();
  if (warm.code !== 0) throw new Error(`${engine}: the warm-up compile failed (exit ${warm.code})`);
  const versions = new Set([sha(fs.readFileSync(pdf))]);
  const versionSizes = new Map([[sha(fs.readFileSync(pdf)), fs.statSync(pdf).size]]);
  const compiles = [];
  const naive = [];             // { hash, size }; bytes kept only for a bounded sample of not-yet-matched reads
  const KEEP_TORN_BYTES = 200;  // pdf.js judges at most this many torn copies (memory: a read is ~0.2 MB)
  let keptBytes = 0;
  const verified = [];          // { hash, size, incomplete }
  const remote = [];            // { hash, size, incomplete, bytesMoved }
  let running = true;

  // the SFTP fixture serves the compile directory itself (its own absolute path), so the remote reader races the
  // same compiles
  const srv = await startSftpServer({ dir, exposeAbsoluteRoot: dir, realHome: dir, allowNone: true, noDelay: true });
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-torn-home-'));
  fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
  transport.configure({ homeDir: home, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
    promptHostKey: async () => true, promptSecret: async () => null, onError: () => {}, idleMs: 3600e3 });
  const uri = srv.url(pdf);
  await transport.remoteStat(uri);
  // the probe sampler gets a server of its own, so its byte counts are its alone (the remote reader resets its own)
  const probeSrv = await startSftpServer({ dir, exposeAbsoluteRoot: dir, realHome: dir, allowNone: true, noDelay: true });
  const probeUri = probeSrv.url(pdf);
  await transport.remoteStat(probeUri);

  const loop = (async () => {
    const deadline = Date.now() + o.seconds * 1000;
    while (Date.now() < deadline) {
      const c = await compile();
      compiles.push(c);
      if (c.code === 0) {
        const b = fs.readFileSync(pdf);
        const h = sha(b);
        versions.add(h);
        versionSizes.set(h, b.length);
      }
    }
    running = false;
  })();
  const naiveReader = (async () => {
    while (running) {
      try {
        const b = fs.readFileSync(pdf);
        const hash = sha(b);
        // a read matching a version already recorded is consistent for sure; any other read may yet match the version
        // the running compile is about to leave behind — classified at the end, its bytes kept for pdf.js (bounded)
        const keep = !versions.has(hash) && keptBytes < KEEP_TORN_BYTES;
        if (keep) keptBytes += 1;
        naive.push({ hash, size: b.length, bytes: keep ? b : null });
      } catch (_e) { /* the file is being replaced */ }
      await sleep(o.naiveMs);
    }
  })();
  const verifiedReader = (async () => {
    while (running) {
      try {
        const r = await content.readPdfLocal(pdf, { notifies: false });
        verified.push({ hash: sha(r.bytes), size: r.bytes.length, incomplete: Boolean(r.incomplete) });
      } catch (_e) { /* the file vanished between stat and open */ }
    }
  })();
  const remoteReader = (async () => {
    while (running) {
      try {
        srv.resetStats();
        const st = await transport.remoteStat(uri);
        const r = await content.readPdfRemote(uri, st, { notifies: false });
        const moved = (srv.stats.byPath[pdf] || { readBytes: 0 }).readBytes;
        remote.push({ hash: sha(r.bytes), size: r.bytes.length, incomplete: Boolean(r.incomplete), bytesMoved: moved });
      } catch (_e) { /* a stat or read that raced the rewrite */ }
    }
  })();
  // a separate PROBE sampler: one verified remote ATTEMPT at a time (no settle), so every attempt that found the file
  // unfinished is visible with the bytes it cost (H5's ≤ 1 KiB claim)
  const probes = [];
  const probeReader = (async () => {
    while (running) {
      try {
        const st = await transport.remoteStat(probeUri);
        probeSrv.resetStats();
        const a = await content._readPdfAttemptRemote(probeUri, st);
        const moved = (probeSrv.stats.byPath[pdf] || { readBytes: 0 }).readBytes;
        probes.push({ outcome: a.bytes ? 'complete' : a.incomplete ? `incomplete:${a.incomplete}` : 'changing', bytesMoved: moved, size: st.size });
      } catch (_e) { /* raced */ }
      await sleep(20);
    }
  })();
  await Promise.all([loop, naiveReader, verifiedReader, remoteReader, probeReader]);
  transport.closeAll();
  for (const s of [srv, probeSrv]) { s.destroyConnections(); await s.close(); }

  // ---- classify, against the full set of complete versions ----
  const tornNaive = naive.filter((r) => !versions.has(r.hash));
  const opened = [];
  for (const r of tornNaive) if (r.bytes) opened.push(await pdfjsVerdict(lib, r.bytes));
  const result = {
    engine,
    compiles: compiles.length,
    failedCompiles: compiles.filter((c) => c.code !== 0).length,
    compileMs: compiles.map((c) => c.ms),
    versions: versions.size,
    versionBytes: [...versionSizes.values()],
    naive: { reads: naive.length, consistent: naive.length - tornNaive.length, torn: tornNaive.length,
             tornSizes: tornNaive.map((r) => r.size),
             tornJudgedByPdfjs: opened.length,
             tornOpenedByPdfjs: opened.filter((v) => v.opened).length,
             tornOpenedPages: opened.filter((v) => v.opened).map((v) => v.pages),
             tornRejectedByPdfjs: opened.filter((v) => !v.opened).length },
    verified: { deliveries: verified.length,
                complete: verified.filter((r) => !r.incomplete).length,
                tornComplete: verified.filter((r) => !r.incomplete && !versions.has(r.hash)).length,
                flaggedIncomplete: verified.filter((r) => r.incomplete).length },
    remote: { deliveries: remote.length,
              complete: remote.filter((r) => !r.incomplete).length,
              tornComplete: remote.filter((r) => !r.incomplete && !versions.has(r.hash)).length,
              flaggedIncomplete: remote.filter((r) => r.incomplete).length },
    probes: { attempts: probes.length,
              byOutcome: probes.reduce((m, p) => { m[p.outcome] = (m[p.outcome] || 0) + 1; return m; }, {}),
              incompleteMaxBytes: Math.max(0, ...probes.filter((p) => p.outcome.startsWith('incomplete')).map((p) => p.bytesMoved)) },
  };
  fs.rmSync(dir, { recursive: true, force: true });
  fs.rmSync(home, { recursive: true, force: true });
  return result;
}

async function main() {
  const o = parseArgs(process.argv.slice(2));
  const lib = await import(path.join(ROOT, 'node_modules/pdfjs-dist/legacy/build/pdf.mjs'));
  const versions = {};
  for (const e of o.engines) {
    try { versions[e] = cp.execFileSync(e, ['--version'], { encoding: 'utf8' }).split('\n')[0]; } catch (_e) { versions[e] = null; }
  }
  console.log(`# exp-torn-pdf-reads ${new Date().toISOString()}  node ${process.version}  frames=${o.frames} seconds/engine=${o.seconds} naive-gap=${o.naiveMs} ms  VV_PDF_SETTLE_MS=${process.env.VV_PDF_SETTLE_MS || '(default 250)'}`);
  for (const e of o.engines) console.log(`# ${e}: ${versions[e] || 'NOT INSTALLED'}`);
  const results = [];
  for (const engine of o.engines) {
    if (!versions[engine]) { console.log(`${engine}: skipped (not installed)`); continue; }
    const r = await runEngine(engine, o, lib);
    results.push(r);
    const ms = [...r.compileMs].sort((a, b) => a - b);
    console.log(`\n${engine}: ${r.compiles} compiles (${r.failedCompiles} failed), median ${ms[Math.floor(ms.length / 2)]} ms; ${r.versions} complete versions (${Math.min(...r.versionBytes)}–${Math.max(...r.versionBytes)} bytes)`);
    console.log(`  naive     ${r.naive.reads} reads: ${r.naive.consistent} consistent, ${r.naive.torn} TORN` +
                (r.naive.torn ? ` — of ${r.naive.tornJudgedByPdfjs} judged, pdf.js opened ${r.naive.tornOpenedByPdfjs} (pages ${[...new Set(r.naive.tornOpenedPages)].sort((a, b) => a - b).join('/') || '—'}), rejected ${r.naive.tornRejectedByPdfjs}` : ''));
    console.log(`  verified  ${r.verified.deliveries} deliveries: ${r.verified.complete} complete, ${r.verified.flaggedIncomplete} flagged incomplete, ${r.verified.tornComplete} TORN-AS-COMPLETE`);
    console.log(`  remote    ${r.remote.deliveries} deliveries: ${r.remote.complete} complete, ${r.remote.flaggedIncomplete} flagged incomplete, ${r.remote.tornComplete} TORN-AS-COMPLETE`);
    console.log(`  probes    ${r.probes.attempts} remote attempts: ${JSON.stringify(r.probes.byOutcome)}; an incomplete probe moved at most ${r.probes.incompleteMaxBytes} bytes`);
  }
  if (o.json) {
    fs.writeFileSync(o.json, JSON.stringify({ date: new Date().toISOString(), node: process.version, engines: versions, options: o, results }, null, 1));
    console.log(`# wrote ${path.relative(ROOT, path.resolve(o.json))}`);
  }
}

main().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
