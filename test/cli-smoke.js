'use strict';

// vv-cli node smoke — proves the headless terminal renderer end-to-end WITHOUT Electron:
//   • each format fixture (markdown / log / source / CSV table) lowers to STRUCTURED ANSI
//     (box-drawing tables, gutters, SGR colour, OSC-8-less link degradation when piped);
//   • NO_COLOR emits ZERO escape bytes (the isatty/NO_COLOR degradation contract);
//   • --toc prints the document outline;
//   • a >5 MiB log STREAMS through the WPDA log-stream parser (content_service.streamOpen/
//     streamPull) with BOUNDED peak RSS — peak memory does NOT scale with file size, so
//     `vv-cli huge.log | less` never holds the whole file. This is the streaming core, proven headless.
//
// Why a Node harness (not a cljs -test): the CLI's value is the wiring — argv → content_service (real
// fs/readline/papaparse, no Electron) → IR front-ends → ir.backend.ansi → stdout, plus the streaming
// pull-loop and the terminal-capability degradation — none of which a pure unit test exercises. The
// pure pieces (ir.backend.ansi golden tests, log-stream bounded-memory property) are unit-tested separately.
//
// Run: node test/cli-smoke.js   (wired into `npm test` via `test:cli`, after `compile:cli` builds vv-cli.js).

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync, spawn } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const CLI = path.join(ROOT, 'dist', 'cli', 'vv-cli.js');
assert.ok(fs.existsSync(CLI), 'vv-cli.js must be built (npm run compile:cli) before the smoke');

const ESC = '\u001b';                                    // the byte that begins every ANSI escape
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-cli-smoke-'));
let passed = 0;
function ok(cond, msg) { assert.ok(cond, msg); console.log('  ✓ ' + msg); passed++; }

// run the CLI capturing stdout (small fixtures only; large logs go through the /proc RSS path below)
function run(args, env) {
  return execFileSync('node', [CLI, ...args], {
    encoding: 'utf8', env: { ...process.env, ...(env || {}) }, maxBuffer: 128 * 1024 * 1024,
  });
}

console.log('cli-smoke: headless terminal renderer');

// ── 1. markdown → structured ANSI ────────────────────────────────────────────────────────────────
const md = path.join(tmp, 'doc.md');
fs.writeFileSync(md, [
  '# Title', '', 'A [link](https://example.com) and `code`.', '',
  '## Section', '', '- one', '- two', '', '> quote line', '',
  '```python', 'def f():', '    return 1', '```', '',
  '| A | B |', '|---|---|', '| 1 | 2 |', '',
].join('\n'));

const mdColor = run(['--color', '--width', '72', md]);
ok(mdColor.includes(ESC + '['), 'markdown --color emits SGR escapes');
ok(mdColor.includes('Title') && mdColor.includes('Section'), 'markdown heading text present');
ok(mdColor.includes('┌') && mdColor.includes('│') && mdColor.includes('└'),
   'markdown pipe table renders with box-drawing (┌ │ └)');
ok(mdColor.includes('│ quote line') || mdColor.includes('quote line'), 'blockquote content present');
ok(mdColor.includes('▏'), 'fenced code block has the ▏ gutter');
ok(mdColor.includes('• one') || mdColor.includes('one'), 'list item present');

// NO_COLOR → ZERO escape bytes (the piped/degraded contract)
const mdPlain = run([md], { NO_COLOR: '1' });
ok(!mdPlain.includes(ESC), 'NO_COLOR markdown output contains ZERO escape bytes');
ok(mdPlain.includes('Title') && mdPlain.includes('one') && mdPlain.includes('return 1'),
   'plain markdown keeps all text (heading, list, code)');
ok(mdPlain.includes('┌'), 'plain markdown still draws the table (box-drawing is content, not colour)');

// --plain is also escape-free even with a TTY forced off
ok(!run(['--plain', md]).includes(ESC), '--plain output contains ZERO escape bytes');

// --toc → outline
const toc = run(['--no-color', '--toc', md]);
ok(/Contents/.test(toc) && /Title/.test(toc) && /Section/.test(toc), '--toc prints the document outline');

// ── 2. CSV → box-drawing table ───────────────────────────────────────────────────────────────────
const csv = path.join(tmp, 'data.csv');
fs.writeFileSync(csv, 'name,age\nAlice,30\nBob,25\n');
const csvOut = run(['--no-color', csv]);
ok(csvOut.includes('┌') && csvOut.includes('name') && csvOut.includes('Alice') && csvOut.includes('30'),
   'CSV renders as a box-drawing table with the parsed cells');

// ── 3. log → severity-coloured records ───────────────────────────────────────────────────────────
const smalllog = path.join(tmp, 'app.log');
fs.writeFileSync(smalllog, '2026-01-01 ERROR boom\n    at Foo.bar\n2026-01-01 INFO ok\n');
const logColor = run(['--color', smalllog]);
ok(logColor.includes('boom') && logColor.includes('Foo.bar') && logColor.includes('ok'),
   'log records carry their line text (header + continuation)');
ok(logColor.includes(ESC + '['), 'log records are severity-coloured under --color');
// coalescing: a single-severity line is ONE SGR run, not one escape per word
const firstLogLine = logColor.split('\n')[0];
ok((firstLogLine.match(/\[[0-9;]*m/g) || []).length <= 2,
   'a coloured log line emits a single SGR run (open+reset), not one per word');

// ── 3b. error path: a missing file degrades cleanly and does NOT abort sibling files ───────────────
{
  const r = require('child_process').spawnSync('node', [CLI, '--no-color', path.join(tmp, 'does-not-exist.md'), csv],
                                                { encoding: 'utf8' });
  ok(r.status === 1, 'a missing file yields a non-zero exit');
  ok(/^vv-cli: .*does-not-exist\.md: /m.test(r.stderr), 'missing file prints a clean "vv-cli: <file>: <msg>" (no raw stack trace)');
  ok(!/at Object\.|processTicksAndRejections/.test(r.stderr), 'no uncaught Node stack trace leaks to stderr');
  ok(r.stdout.includes('┌') && r.stdout.includes('Alice'), 'the sibling CSV after the missing file STILL renders');
}

// ── 4. source → tree-sitter ANSI highlight ───────────────────────────────────────────────────────
// use a real bundled-grammar source file (the CLI's own caps.cljs); grammars synced by compile:cli
const src = path.join(ROOT, 'src', 'vinary', 'terminal', 'caps.cljs');
const srcOut = run(['--color', '--width', '100', src]);
ok(srcOut.includes('▏'), 'source render has the ▏ code gutter');
ok(srcOut.includes('ns vinary.terminal.caps'), 'source render contains the actual code text');
ok(srcOut.includes(ESC + '['), 'source is syntax-highlighted (tree-sitter → SGR)');
// plain source: gutter + text, no escapes
ok(!run(['--plain', src]).includes(ESC), '--plain source contains ZERO escape bytes');

// ── 5. bounded-memory streaming: peak RSS must NOT scale with file size ───────────────────────────
// Distinct event numbers per line maximise a would-be per-record accumulation (each line a unique slug),
// so a regression of the O(1) record-counter back to a growing seen-map is maximally exposed.
function makeLog(file, lines) {
  const fd = fs.openSync(file, 'w');
  let buf = [];
  for (let i = 0; i < lines; i++) {
    buf.push('2026-01-01 ' + (i % 50 === 0 ? 'ERROR' : 'INFO') + ' event ' + i + ' padding padding padding padding\n');
    if (buf.length >= 8192) { fs.writeSync(fd, buf.join('')); buf = []; }
  }
  if (buf.length) fs.writeSync(fd, buf.join(''));
  fs.closeSync(fd);
}

// peak RSS (VmHWM, monotonic) of a run whose stdout is discarded, via /proc polling (Linux)
function peakRssKb(args) {
  return new Promise((resolve, reject) => {
    const child = spawn('node', [CLI, ...args], { stdio: ['ignore', 'ignore', 'inherit'] });
    let peak = 0;
    const statusPath = '/proc/' + child.pid + '/status';
    const timer = setInterval(() => {
      try {
        const m = fs.readFileSync(statusPath, 'utf8').match(/VmHWM:\s+(\d+)\s+kB/);
        if (m) peak = Math.max(peak, parseInt(m[1], 10));
      } catch (_) { /* process already reaped */ }
    }, 25);
    child.on('exit', (code) => { clearInterval(timer); code === 0 ? resolve(peak) : reject(new Error('vv-cli exited ' + code)); });
    child.on('error', reject);
  });
}

(async () => {
  const small = path.join(tmp, 'small.log');   // ~6 MiB — crosses the 5 MiB stream threshold
  const large = path.join(tmp, 'large.log');   // ~30 MiB — 5× the small file
  makeLog(small, 90000);
  makeLog(large, 450000);
  const smallMiB = fs.statSync(small).size / 1048576, largeMiB = fs.statSync(large).size / 1048576;
  ok(smallMiB > 5, 'small log (' + smallMiB.toFixed(1) + ' MiB) crosses the 5 MiB streaming threshold');

  // correctness: the first and last records stream through the bounded pull-loop
  const streamed = run(['--no-color', small]);
  ok(streamed.startsWith('2026-01-01 ERROR event 0'), 'streamed log: first record is present and first');
  ok(streamed.includes('event 89999'), 'streamed log: the final record streamed through to the end');

  // boundedness: a 5× larger file must NOT grow peak RSS anywhere near 5× — the working set is the open
  // record + WPDA config + one int, not the document. (The pre-fix seen-map leak grew RSS ~2.3× at 5×.)
  const pSmall = await peakRssKb(['--no-color', small]);
  const pLarge = await peakRssKb(['--no-color', large]);
  const ratio = pLarge / pSmall;
  console.log('  peak RSS: small=' + (pSmall / 1024).toFixed(0) + 'MB  large=' + (pLarge / 1024).toFixed(0)
              + 'MB  (5× file → ' + ratio.toFixed(2) + '× RSS)');
  ok(ratio < 1.8, 'streaming peak RSS is bounded: a 5× file grew peak RSS only ' + ratio.toFixed(2) + '× (< 1.8×)');

  console.log('\ncli-smoke: ' + passed + ' checks passed');
  fs.rmSync(tmp, { recursive: true, force: true });
})().catch((e) => { console.error(e); try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (_) {} process.exit(1); });
