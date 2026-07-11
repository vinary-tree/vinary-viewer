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

// Async runner — required when the CLI subprocess must talk to an in-process server (a synchronous execFileSync
// would block the event loop the server runs on).
function runAsync(args, env) {
  return new Promise((resolve, reject) => {
    const p = spawn('node', [CLI, ...args], { env: { ...process.env, ...(env || {}) } });
    let out = '', err = '';
    p.stdout.on('data', (d) => (out += d));
    p.stderr.on('data', (d) => (err += d));
    p.on('close', (code) => resolve({ code, out, err }));
    p.on('error', reject);
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

// GFM task lists reach the terminal with their state (the same <input type=checkbox> shape Org emits)
const mdTasks = run(['--no-color', (() => {
  const p = path.join(tmp, 'tasks.md');
  fs.writeFileSync(p, '- [ ] unchecked\n- [x] checked\n- plain\n');
  return p;
})()]);
ok(mdTasks.includes('☐ unchecked') && mdTasks.includes('☑ checked') && mdTasks.includes('• plain'),
   'markdown task lists render ☐ / ☑ and leave plain items as bullets');

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

// ── 1b. Org (.org) → the SAME uniorg/common-IR render as the GUI, lowered to ANSI ────────────────
// Regression: content_service.js's classifyName (the JS twin of file-kind/kind-of) once had no `org` arm and
// kept .org in textExts, so the CLI's "text + a bundled tree-sitter grammar → source" upgrade printed
// highlighted Org MARKUP instead of rendering it. It also let an Org table trip the delimited-CSV sniff.
const org = path.join(tmp, 'doc.org');
fs.writeFileSync(org, [
  '#+TITLE: Org Title',
  '#+AUTHOR: Ada Lovelace',
  '#+LATEX_HEADER: \\usepackage{booktabs}',
  '',
  '* Section',
  'Some *bold* text.',
  '- [ ] todo',
  '- [X] done',
  '',
  '#+begin_src python',
  'def f():',
  '    return 1',
  '#+end_src',
  '',
  '| A | B |',
  '|---+---|',
  '| 1 | 2 |',
  '',
  '#+BEGIN_EXPORT latex',
  '\\begin{center}Billing Period\\end{center}',
  '#+END_EXPORT',
  '',
].join('\n'));

const orgOut = run(['--no-color', org]);
ok(orgOut.includes('☐ todo') && orgOut.includes('☑ done'),
   'org task lists carry their checkbox STATE into the terminal (☐ / ☑, not a bare bullet)');
ok(orgOut.includes('Org Title'), 'org #+TITLE renders as document front matter');
ok(orgOut.includes('Ada Lovelace'), 'org #+AUTHOR renders as document front matter');
ok(!orgOut.includes('#+TITLE'), 'org is RENDERED, not printed as raw source markup');
ok(!orgOut.includes('usepackage'), 'org #+LATEX_HEADER keywords stay dropped');
ok(orgOut.includes('Section') && orgOut.includes('bold'), 'org heading and inline markup render');
ok(orgOut.includes('┌') && orgOut.includes('│'), 'org table renders with box-drawing (NOT sniffed as a CSV)');
ok(orgOut.includes('▏') && orgOut.includes('return 1'), 'org #+begin_src block renders with the ▏ code gutter');
// the terminal has no DOM, so the MathJax attempt cannot run: a latex export block ALWAYS takes the
// code-block fallback — and its body must never be silently swallowed
ok(orgOut.includes('Billing Period'), 'org #+BEGIN_EXPORT latex body is preserved (code-block fallback)');
ok(!orgOut.includes(ESC), 'NO_COLOR org output contains ZERO escape bytes');

// an org document that renders to nothing must not crash the CLI
const orgEmpty = path.join(tmp, 'empty.org');
fs.writeFileSync(orgEmpty, '#+OPTIONS: toc:nil\n# just a comment\n');
ok(typeof run(['--no-color', orgEmpty]) === 'string', 'an org document that renders nothing exits cleanly');

// --toc works for org (heading outline, incl. the front-matter title)
const orgToc = run(['--no-color', '--toc', org]);
ok(/Contents/.test(orgToc) && /Section/.test(orgToc), '--toc prints the org document outline');

// ── 1c. diff (.diff/.patch) → the unified colored IR, lowered to ANSI (the terminal has no split view) ──
// content_service.js classifies .diff as its own kind and openLocal short-circuits the delimited sniff; cli/render
// then lowers it through ir.frontend.diff → ir.backend.ansi with per-line colour.
const diffFile = path.join(tmp, 'change.diff');
fs.writeFileSync(diffFile, [
  'diff --git a/src/app.js b/src/app.js',
  '--- a/src/app.js',
  '+++ b/src/app.js',
  '@@ -1,3 +1,3 @@',
  ' const a = 1;',
  '-const b = 2;',
  '+const b = 3;',
  ' const c = 4;',
  '',
].join('\n'));
const diffPlain = run(['--no-color', diffFile]);
ok(diffPlain.includes('src/app.js'), 'diff renders the file banner');
ok(diffPlain.includes('@@ -1 +1 @@'), 'diff renders the hunk header');
ok(diffPlain.includes('-const b = 2;') && diffPlain.includes('+const b = 3;'), 'diff renders ±-marked lines');
const diffColor = run(['--color', diffFile]);
// robust to 16-colour AND truecolor terminals (the smoke inherits the ambient COLORTERM)
const green = /\[32m/.test(diffColor) || /\[38;2;152;195;121m/.test(diffColor);
const red = /\[31m/.test(diffColor) || /\[38;2;224;108;117m/.test(diffColor);
ok(green, 'a diff insertion is coloured green in the terminal');
ok(red, 'a diff deletion is coloured red in the terminal');
// --toc lists the changed files (one Contents entry per file banner)
ok(/Contents/.test(run(['--no-color', '--toc', diffFile])), '--toc lists the diff\'s changed files');

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

// ── 4c. PDF → headless pdf.js text extraction → reflowed prose (not raw bytes / a delimited table) ────────
const pdf = path.join(ROOT, 'test', 'fixtures', 'smoke.pdf');
if (fs.existsSync(pdf)) {
  const pout = run(['--no-color', '--width', '60', pdf]);
  ok(pout.includes('Vinary PDF Smoke'), 'PDF renders its extracted text (pdf.js reflow)');
  ok(!pout.startsWith('%PDF') && !pout.includes('┌'), 'PDF is NOT dumped as raw bytes or a box-drawing table');
}

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
  // ── remote (ssh://) file over the CLI — a hermetic in-process SFTP fixture, passwordless (allowNone) so no
  //    TTY prompt is needed; a pre-seeded known_hosts skips the TOFU prompt. Proves the CLI routes a remote URI
  //    through openRemoteUri and renders it. ──
  {
    const { startSftpServer } = require('./fixtures/ssh-server.js');
    const sshcfg = require('../src/vinary/main/ssh_config.js');
    const srv = await startSftpServer({ allowNone: true, files: { 'notes.md': '# Remote CLI\nhello from ssh' } });
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-cli-sshhome-'));
    fs.mkdirSync(path.join(home, '.ssh'), { recursive: true });
    fs.writeFileSync(path.join(home, '.ssh', 'known_hosts'),
      sshcfg.knownHostsLine('127.0.0.1', srv.port, srv.hostKeyType, srv.hostKeyB64) + '\n');
    try {
      const { out, code } = await runAsync(['--no-color', srv.url('/notes.md')], { HOME: home, SSH_AUTH_SOCK: '' });
      ok(code === 0 && /hello from ssh/.test(out), 'vv-cli renders a remote ssh:// markdown file');
    } finally {
      await srv.close();
      fs.rmSync(home, { recursive: true, force: true });
    }
  }

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
