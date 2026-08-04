'use strict';

// vv-tui smoke — the interactive TUI proven WITHOUT a pseudo-tty via the `--drive <keyfile>` seam: keys are
// replayed through the SAME keys→state→frame pipeline the live terminal uses, and the final frame is dumped
// deterministically (forced --width). Asserts scroll, find (jump + reverse-video highlight), TOC overlay + jump,
// and that a log LARGER than the viewport ring stays bounded (older lines drop, counted). A small, skippable
// pseudo-tty check (Python `pty`, Linux) covers the one thing --drive can't: terminal teardown — enter/leave the
// alternate screen + restore the cursor on `q`.  Run: node test/tui-smoke.js  (wired into `npm test` via test:tui).

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync, spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const TUI = path.join(ROOT, 'dist', 'tui', 'vv-tui.js');
assert.ok(fs.existsSync(TUI), 'vv-tui.js must be built (npm run compile:tui) before the smoke');

const ESC = '\u001b';
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-tui-smoke-'));
let passed = 0;
function ok(cond, msg) { assert.ok(cond, msg); console.log('  ✓ ' + msg); passed++; }

// drive vv-tui with a byte sequence of keys; returns the final frame (utf8; escapes preserved).
// stdin is EXPLICITLY 'ignore' (→ /dev/null → instant EOF): vv-tui drains a non-TTY stdin to EOF
// (ADR-0036 piped documents), so inheriting a harness stdin that never closes would hang every run.
function drive(keys, file, extra) {
  const kf = path.join(tmp, 'keys-' + Math.abs(hash(keys + (extra || ''))) + '.bin');
  fs.writeFileSync(kf, Buffer.from(keys, 'binary'));
  return execFileSync('node', [TUI, '--drive', kf, ...(extra || []), file],
                      { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
}

// drive vv-tui with the DOCUMENT piped on stdin (no file arg) — the ADR-0036 stdin seam: --drive reads
// keys from a file, so the pipe is free to carry the document; no pseudo-tty needed.
function drivePiped(keys, input, extra) {
  const kf = path.join(tmp, 'keys-p-' + Math.abs(hash(keys + (extra || []).join(''))) + '.bin');
  fs.writeFileSync(kf, Buffer.from(keys, 'binary'));
  const r = spawnSync('node', [TUI, '--drive', kf, ...(extra || [])],
                      { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, input,
                        stdio: ['pipe', 'pipe', 'pipe'] });
  return { code: r.status, out: r.stdout || '', err: r.stderr || '' };
}
function hash(s) { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return h; }
function strip(s) { return s.replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').replace(/\x1b\][^\x07\x1b]*(\x07|\x1b\\)/g, ''); }

console.log('tui-smoke: interactive terminal viewer');

// ── fixtures ─────────────────────────────────────────────────────────────────
const doc = path.join(tmp, 'doc.md');
{ let s = '# TUI Doc\n\n## Section Two\n\n'; for (let i = 1; i <= 30; i++) s += 'Line ' + i + ' content\n\n'; fs.writeFileSync(doc, s); }

// ── 1. --version / --help ──────────────────────────────────────────────────────
ok(execFileSync('node', [TUI, '--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).includes('vv --tui'), '--version prints the version');
ok(execFileSync('node', [TUI, '--help'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).includes('scroll'), '--help documents the keys');

// ── 2. scroll: j/k and g/G move the window ─────────────────────────────────────
const home = strip(drive('', doc, ['--no-color', '--width', '40']));
ok(home.includes('TUI Doc'), 'initial frame shows the top of the document');
const down = strip(drive('jjjj', doc, ['--no-color', '--width', '40']));
ok(!down.includes('TUI Doc') && down.includes('Line'), 'j scrolls down (heading scrolled off, body lines shown)');
const bottom = strip(drive('G', doc, ['--no-color', '--width', '40']));
ok(bottom.includes('Line 30 content'), 'G jumps to the bottom (last line visible)');
ok(bottom.includes('/61') || /\d+\/\d+/.test(bottom), 'status row shows a position indicator');

// ── 3. find: /query Enter jumps to + highlights the match ──────────────────────
const found = drive('/Line 15\r', doc, ['--width', '40']);       // colour on → reverse-video highlight
ok(strip(found).includes('Line 15 content'), 'find jumps so the match line is visible');
ok(found.includes(ESC + '[7m'), 'the match is highlighted in reverse-video');
const foundStrip = strip(drive('/nope-xyz\r', doc, ['--width', '40']));
ok(foundStrip.includes('TUI Doc'), 'a query with no matches leaves the view at the top (no crash)');

// ── 4. TOC overlay + jump ──────────────────────────────────────────────────────
const tocFrame = strip(drive('t', doc, ['--no-color', '--width', '40']));
ok(tocFrame.includes('TUI Doc') && tocFrame.includes('Section Two'), 'TOC overlay lists both headings');
const tocJump = strip(drive('t' + '\x1b[B' + '\r', doc, ['--no-color', '--width', '40']));  // t, down, Enter
ok(tocJump.includes('Section Two'), 'selecting the 2nd heading and Enter jumps to it');

// ── 4b. Org (.org) renders through uniorg + the common IR, not as highlighted source markup ────────
// Same regression as the CLI: content_service.js's classifyName had no `org` arm, so the TUI's
// "text + a bundled tree-sitter grammar → source" upgrade paged raw Org markup instead of a rendered doc.
const orgDoc = path.join(tmp, 'doc.org');
fs.writeFileSync(orgDoc, '#+TITLE: Org TUI\n\n* Section One\nSome *bold* text.\n\n** Section Two\nMore text.\n');
const orgFrame = strip(drive('', orgDoc, ['--no-color', '--width', '40']));
ok(orgFrame.includes('Org TUI'), 'org #+TITLE renders as document front matter in the TUI');
ok(!orgFrame.includes('#+TITLE'), 'org is RENDERED in the TUI, not paged as raw source markup');
const orgToc = strip(drive('t', orgDoc, ['--no-color', '--width', '40']));
ok(orgToc.includes('Section One') && orgToc.includes('Section Two'), 'the TUI TOC lists the org headings');

// ── 5. streaming log stays bounded (ring): a log larger than the cap drops older lines ─────────────
// 130k lines, padded so the file exceeds BOTH the 5 MiB streaming threshold AND the 100k viewport-ring cap
const biglog = path.join(tmp, 'big.log');
{ const fd = fs.openSync(biglog, 'w'); let buf = [];
  for (let i = 0; i < 130000; i++) { buf.push('2026-01-01 INFO event ' + i + ' padding padding padding\n'); if (buf.length >= 8192) { fs.writeSync(fd, buf.join('')); buf = []; } }
  if (buf.length) fs.writeSync(fd, buf.join('')); fs.closeSync(fd); }
ok(fs.statSync(biglog).size > 5 * 1024 * 1024, 'the streaming fixture exceeds the 5 MiB threshold');
const streamed = strip(drive('G', biglog, ['--no-color', '--width', '50']));
ok(streamed.includes('event 129999'), 'streamed log: G reaches the final record');
ok(/\(\+\d+ earlier\)/.test(streamed), 'the bounded ring dropped older lines (a "+N earlier" indicator is shown)');

// ── 5b. PDF: headless pdf.js reflow renders in the TUI ─────────────────────────
const pdf = path.join(ROOT, 'test', 'fixtures', 'smoke.pdf');
if (fs.existsSync(pdf)) {
  ok(strip(drive('', pdf, ['--no-color', '--width', '50'])).includes('Vinary PDF Smoke'),
     'a PDF opens in the TUI (pdf.js text extraction + reflow)');
}

// ── 6. pseudo-tty teardown check (skippable: needs python3 + a Linux pty) ───────
(function ptyTeardown() {
  const py = `
import pty,os,sys,select,time
pid,fd=pty.fork()
if pid==0:
    os.environ['TERM']='xterm'; os.execvp('node',['node',${JSON.stringify(TUI)},'--width','40',${JSON.stringify(doc)}])
else:
    time.sleep(0.6); os.write(fd,b'q'); out=b''
    while True:
        r,_,_=select.select([fd],[],[],1.5)
        if not r: break
        try: d=os.read(fd,65536)
        except OSError: break
        if not d: break
        out+=d
    sys.stdout.buffer.write(out)
`;
  const r = spawnSync('python3', ['-c', py], { encoding: 'latin1', timeout: 15000 });
  if (r.error || r.status !== 0 || !r.stdout) { console.log('  ⓘ pty teardown check skipped (no python3/pty available)'); return; }
  const out = r.stdout;
  ok(out.includes('\x1b[?1049h'), 'pty: vv-tui enters the alternate screen on start');
  ok(out.includes('\x1b[?1049l'), 'pty: vv-tui LEAVES the alternate screen on q (clean teardown)');
  ok(out.includes('\x1b[?25h'), 'pty: the cursor is restored on q');
})();

// ── 7. ADR-0036: piped stdin documents + -t/--type ────────────────────────────────────────────────
{
  // a piped document renders with the status-bar name `stdin`; untyped = literal plain text
  const raw = drivePiped('', '# piped-heading\nplain body line\n', ['--no-color', '--width', '60']);
  ok(raw.code === 0 && strip(raw.out).includes('# piped-heading') && strip(raw.out).includes(' stdin '),
     'an untyped piped document pages literally with the status-bar name "stdin"');

  // -t markdown renders it through the markdown pipeline (the heading marker is consumed)
  const md36 = drivePiped('', '# piped-heading\n\nbody\n', ['--no-color', '--width', '60', '-t', 'markdown']);
  ok(md36.code === 0 && strip(md36.out).includes('piped-heading') && !strip(md36.out).includes('# piped-heading'),
     '-t markdown renders the piped document (not literal text)');

  // the acceptance shape: a piped git diff with -t diff pages through the diff IR
  const diffText = 'diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n';
  const df = drivePiped('', diffText, ['--no-color', '--width', '60', '-t', 'diff']);
  ok(df.code === 0 && strip(df.out).includes('modified x') && strip(df.out).includes('+new'),
     'git diff | vv --tui -t diff pages the diff IR (file banner + ± lines)');

  // an unknown type fails fast (before any stdin drain), exit ≠ 0 with the hint
  const bad = drivePiped('', 'x\n', ['-t', 'diph']);
  ok(bad.code === 1 && /unknown type 'diph'/.test(bad.err) && /valid types/.test(bad.err),
     'an unknown type errors with the valid-token hint (exit 1)');

  // -t python on an extensionless FILE forces highlighted source in the frame
  const noExt = path.join(tmp, 'script-no-ext');
  fs.writeFileSync(noExt, 'def f():\n    return 1\n');
  const py = drive('', noExt, ['--width', '60', '-t', 'py']);
  ok(py.includes(ESC + '[38;2') && strip(py).includes('return 1'),
     '-t py highlights an extensionless file as python source in the frame');
}

console.log('\ntui-smoke: ' + passed + ' checks passed');
fs.rmSync(tmp, { recursive: true, force: true });
