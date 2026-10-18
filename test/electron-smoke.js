'use strict';

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
const OZONE = process.env.VV_OZONE || (process.platform === 'linux' ? 'x11' : null);
if (OZONE) {
  process.env.ELECTRON_OZONE_PLATFORM_HINT = OZONE;
  process.env.GDK_BACKEND = OZONE;
  process.env.XDG_SESSION_TYPE = OZONE === 'wayland' ? 'wayland' : 'x11';
  if (OZONE === 'x11') delete process.env.WAYLAND_DISPLAY;
}

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow, ipcMain } = require('electron');
const { browserWindowOptions, showForTest } = require('./headless-window.js');
// the REAL main-process content service — the streaming smoke wires vv:stream-* to it (exactly as service.cljs
// does) so the test drives genuine createReadStream/readline batching + the session registry, and can assert
// streamCount() returns to 0 (no fd/session leak) after teardown.
const contentService = require(path.join(__dirname, '..', 'src', 'vinary', 'main', 'content_service.js'));
const sshTransport = require(path.join(__dirname, '..', 'src', 'vinary', 'main', 'ssh_transport.js'));
const { startSftpServer } = require('./fixtures/ssh-server.js');

const ROOT = path.resolve(__dirname, '..');
const INDEX = path.join(ROOT, 'resources', 'public', 'index.html');
const PRELOAD = path.join(ROOT, 'resources', 'preload.js');
const tempDirs = [];

app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
if (OZONE) app.commandLine.appendSwitch('ozone-platform', OZONE);

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function cleanupTempDirs() {
  for (const dir of tempDirs.splice(0)) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

function svgDiagram({ width, height, fontSize, label, fill }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" height="${height}px" preserveAspectRatio="none" ` +
    `style="width:${width}px;height:${height}px;background:#FFFFFF;" viewBox="0 0 ${width} ${height}" ` +
    `width="${width}px"><rect width="${width}" height="${height}" fill="${fill}"/>` +
    `<text x="${width / 2}" y="${height / 2}" font-size="${fontSize}" fill="#111827" ` +
    `text-anchor="middle">${label}</text></svg>`;
}

function createLocalSvgScrollFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-svg-scroll-'));
  const diagramsDir = path.join(dir, 'diagrams');
  tempDirs.push(dir);
  fs.mkdirSync(diagramsDir);

  const diagrams = [
    { file: 'wide.svg', width: 1461, height: 242, fontSize: 14, label: 'wide local svg', fill: '#DBEAFE' },
    { file: 'tall.svg', width: 563, height: 731, fontSize: 12, label: 'tall local svg', fill: '#DCFCE7' },
    { file: 'very-wide.svg', width: 3269, height: 415, fontSize: 14, label: 'very wide local svg', fill: '#FEF3C7' }
  ];
  for (const diagram of diagrams) {
    fs.writeFileSync(path.join(diagramsDir, diagram.file), svgDiagram(diagram));
  }

  const filler = Array.from({ length: 16 }, (_value, index) =>
    `Paragraph ${index + 1}. This gives the local SVG scroll test enough height.`
  ).join('\n\n');
  const text = `# Local SVG Scroll\n\n${filler}\n\n` +
    `![Wide](diagrams/wide.svg)\n\n## Middle\n\n${filler}\n\n` +
    `![Tall](diagrams/tall.svg)\n\n## More\n\n${filler}\n\n` +
    `![Very wide](diagrams/very-wide.svg)\n\n## End\n\n${filler}`;
  const docPath = path.join(dir, 'local-svg-scroll.md');
  fs.writeFileSync(docPath, text);
  return { docPath, text };
}

// A d2-shaped figure whose embedded markdown-label stylesheet carries RELATIVE font sizes. d2 emits this `.md`
// block for any shape with a markdown label; its 1em / 1.25em / 0.875em / 0.85em declarations (six of them)
// used to outvote the five real 16px <text> labels in figures.cljs's dominant-font count, because the count was
// unit-blind and every one of them rounds to 1. The font-matched width then became docFont × 671 / 1 ≈ 10065px,
// which CSS max-width:100% silently clamped to the full column — the figure rendered enormous with magnified
// text. Only ABSOLUTE sizes may vote, so the dominant font must be 16 and the width docFont × 671 / 16.
// Mirrors libdictenstein's docs/diagrams/kernel-substrate.svg (viewBox 671×723), incl. its `width="80%"` author
// attribute, which font-matching deliberately overrides (ADR-0022). The viewBox is scaled to the same 0.928
// aspect so the font-matched width (docFont × 400/16 = 375px at the default 15px font) sits well inside this
// window's ~614px column: if the figure were clamped by max-width:100% the assertion could not tell a correct
// width from the 6000px blow-up, since both render as "full column".
const RELATIVE_FONT_VIEWBOX = { width: 400, height: 431, textFontPx: 16 };

function createRelativeFontSvgFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-relfont-svg-'));
  tempDirs.push(dir);
  fs.mkdirSync(path.join(dir, 'diagrams'));

  const { width, height, textFontPx } = RELATIVE_FONT_VIEWBOX;
  const relativeCss = [
    '.md { font-size: 1em; }',
    '.md p { font-size: 1em; }',
    '.md li { font-size: 1em; }',
    '.md h1 { font-size: 1.25em; }',
    '.md code { font-size: 0.875em; }',
    '.md small { font-size: 0.85em; }'
  ].join('');
  const labels = ['alpha', 'beta', 'gamma', 'delta', 'epsilon'].map((label, i) =>
    `<text x="40" y="${40 + i * 40}" class="text-bold" ` +
    `style="text-anchor:middle;font-size:${textFontPx}px" fill="#0D47A1">${label}</text>`
  ).join('');
  const svg =
    '<?xml version="1.0" encoding="utf-8"?>' +
    `<svg xmlns="http://www.w3.org/2000/svg" data-d2-version="0.7.1" viewBox="0 0 ${width} ${height}">` +
    `<svg class="d2-svg" width="${width}" height="${height}" viewBox="-6 -126 ${width} ${height}">` +
    `<rect width="${width}" height="${height}" fill="#FFFFFF"/>` +
    `<style type="text/css"><![CDATA[${relativeCss}]]></style>${labels}</svg></svg>`;
  fs.writeFileSync(path.join(dir, 'diagrams', 'relative-font.svg'), svg);

  const text = [
    '# Relative Font SVG', '',
    'The figure below embeds a d2 markdown-label stylesheet with relative font sizes.', '',
    '<img src="diagrams/relative-font.svg" alt="relative-font d2 figure" width="80%"/>', ''
  ].join('\n');
  const docPath = path.join(dir, 'relative-font.md');
  fs.writeFileSync(docPath, text);
  return { docPath, text };
}

// standard PNG CRC-32 (poly 0xEDB88320), for building valid chunks below
function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = ((c >>> 1) ^ (0xEDB88320 & -(c & 1))) >>> 0;
  }
  return (c ^ 0xFFFFFFFF) >>> 0;
}

// a VALID (transparent RGBA) PNG of the given pixel dimensions — large enough for the renderer to load it AND
// for figures.cljs's IHDR header-parse to read its intrinsic width/height (used by the raster scroll test).
function makePng(width, height) {
  const zlib = require('zlib');
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // color type: RGBA
  const raw = Buffer.alloc(height * (1 + width * 4)); // per-scanline filter byte (0) + transparent RGBA pixels
  const idat = zlib.deflateSync(raw);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const typed = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(typed), 0);
    return Buffer.concat([len, typed, crc]);
  };
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

function createLocalRasterScrollFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-raster-scroll-'));
  const imagesDir = path.join(dir, 'images');
  tempDirs.push(dir);
  fs.mkdirSync(imagesDir);

  const images = [
    { file: 'wide.png', width: 800, height: 200 },   // 4.00
    { file: 'tall.png', width: 200, height: 600 },   // 0.33
    { file: 'box.png',  width: 400, height: 400 }    // 1.00
  ];
  for (const im of images) {
    fs.writeFileSync(path.join(imagesDir, im.file), makePng(im.width, im.height));
  }
  const filler = Array.from({ length: 16 }, (_value, index) =>
    `Paragraph ${index + 1}. This gives the raster image scroll test enough height.`
  ).join('\n\n');
  const text = `# Local Raster Scroll\n\n${filler}\n\n` +
    `![Wide](images/wide.png)\n\n## Middle\n\n${filler}\n\n` +
    `![Tall](images/tall.png)\n\n## More\n\n${filler}\n\n` +
    `![Box](images/box.png)\n\n## End\n\n${filler}`;
  const docPath = path.join(dir, 'local-raster-scroll.md');
  fs.writeFileSync(docPath, text);
  return { docPath, text };
}

// A log comfortably larger than SMALL_LOG_BYTES (5 MiB) — the streaming threshold — with a KNOWN record count.
// Mostly single-line INFO/ERROR records (ERROR ones become Contents entries), plus a few multi-line records:
// stack traces (continuation lines must NOT split the record) and JSON objects whose inner line LOOKS like a
// header (the WPDA brace pushdown must keep each object whole). Exact counts let the smoke assert progressive
// growth converges to the whole file with braced/continuation records intact.
function createLargeLogFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-log-stream-'));
  tempDirs.push(dir);
  const pad = 'x'.repeat(800);               // widen each line so ~7k records clear 5 MiB (few batches → fast)
  const N = 7000;
  const lines = [];
  let records = 0;
  let errorHeaders = 0;
  for (let i = 0; i < N; i++) {
    if (i % 200 === 0) { lines.push(`2026-07-07T00:00:00Z ERROR event ${i} failed ${pad}`); errorHeaders++; }
    else               { lines.push(`2026-07-07T00:00:00Z INFO  event ${i} ok ${pad}`); }
    records++;
  }
  for (let k = 0; k < 3; k++) {              // multi-line ERROR-with-stack records (header + 2 continuations)
    lines.push(`2026-07-07T01:00:0${k}Z ERROR boom ${k}`);
    lines.push(`    at com.example.Foo.bar(Foo.java:${k})`);
    lines.push(`    at com.example.Baz.qux(Baz.java:${k})`);
    records++; errorHeaders++;
  }
  for (let k = 0; k < 2; k++) {              // JSON braced records with a header-LOOKING inner line (pushdown)
    lines.push('{');
    lines.push(`2026-07-07T02:00:0${k}Z ERROR inner-looks-like-a-header ${k}`);   // header first line is '{' → NOT a Contents entry
    lines.push('}');
    records++;
  }
  const docPath = path.join(dir, 'huge.log');
  fs.writeFileSync(docPath, lines.join('\n') + '\n');
  return { docPath, records, errorHeaders, size: fs.statSync(docPath).size };
}

// A log well under the streaming threshold — must stay on the batch path (the paged .vv-log-doc view), never
// streamed. Proves the size gate: small docs render exactly as before (byte-identical), streaming is opt-in.
function createSmallLogFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-log-small-'));
  tempDirs.push(dir);
  const lines = [];
  for (let i = 0; i < 40; i++) lines.push(`2026-07-07T00:00:00Z INFO small event ${i}`);
  lines.push('2026-07-07T00:00:01Z ERROR small boom UNIQUEMARKER');
  const docPath = path.join(dir, 'small.log');
  fs.writeFileSync(docPath, lines.join('\n') + '\n');
  return { docPath, size: fs.statSync(docPath).size };
}

// Two byte-identical Markdown files (same directory → identical base-dir URL rewriting) larger than the 256 KiB
// streaming threshold, exercising the parity-critical base-pipeline features: heading-slug DEDUP across repeated
// headings, a FORWARD reference definition (used in the intro, defined at the very end — proves whole-document
// context), tight + loose lists, a highlighted fenced code block, a table, a blockquote, inline raw HTML, and a
// thematic break. Deliberately NO math/images: MathJax uses a shared monotonic ID counter AND local image URLs
// carry a per-render cache-buster (?vv-cache=<timestamp>, regenerated each render) — both differ between two
// independent renders, so they are covered by separate feature assertions, not byte-comparison. (Figure SIZING
// itself is deterministic + pre-DOM now — ADR-0022 — proven by the local-SVG/raster sizing + no-flash
// assertions.) `batch.md` renders whole (flag off), `stream.md` streams (flag on); their
// .markdown-body innerHTML must be byte-identical.
function createLargeMarkdownFixtures() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-md-stream-'));
  tempDirs.push(dir);
  // a ~1.2 KB filler sentence so each section is a realistically-sized block group (few hundred sections for
  // 280 KiB, not thousands of tiny blocks) — keeps the streamed render fast enough for the smoke budget.
  const filler = 'The quick brown fox jumps over the lazy dog while pangrams exercise every glyph. '.repeat(15);
  const section = (i) => `## Section ${i}

Paragraph ${i} with **bold**, _italic_, \`inline code\`, and a [back reference][ref${i}] plus raw <abbr title="x">HTML</abbr>. ${filler}

- tight one
- tight two

1. ordered a
2. ordered b

- loose one

- loose two

> a blockquote
> across two lines

\`\`\`clojure
(defn add-${i} [a b] (+ a b ${i}))
\`\`\`

| Col A | Col B |
|-------|-------|
| ${i}  | ${i + 1} |

### Repeated Heading

Body under the repeated heading (slug dedup must number these across the whole document).

---

[ref${i}]: https://example.com/back/${i}
`;
  let body = '# Big Markdown Document\n\nIntro paragraph referencing a [forward definition][fwd] resolved at EOF.\n\n';
  let i = 0;
  while (Buffer.byteLength(body, 'utf8') < 300 * 1024) { body += section(i); i += 1; }
  body += '\n[fwd]: https://example.com/forward-defined-at-end\n';
  const batchPath = path.join(dir, 'batch.md');
  const streamPath = path.join(dir, 'stream.md');
  fs.writeFileSync(batchPath, body);
  fs.writeFileSync(streamPath, body);
  return { dir, batchPath, streamPath, text: body, size: Buffer.byteLength(body, 'utf8'), sections: i };
}

// Org's counterpart. Org rides the SAME progressive engine (it commits IR children, not markdown nodes), so the
// byte-parity contract is identical: streamed innerHTML must equal the batch render exactly.
function createLargeOrgFixtures() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-org-stream-'));
  tempDirs.push(dir);
  const filler = 'The quick brown fox jumps over the lazy dog while pangrams exercise every glyph. '.repeat(15);
  // Every parity-critical Org construct appears in EVERY section, so the streamed-vs-batch comparison covers
  // them per-block: inline math (normalized span.math → code.math-inline, typeset by the MathJax pass) and a
  // non-math #+BEGIN_EXPORT latex block (ADR-0025: RENDERED by unified-latex into a raw node that rehype-raw
  // parses → tex-normalize → IR children, identically whether the whole doc renders at once or streams block by
  // block). If that were not deterministic per block, streamed and batch HTML would diverge exactly here.
  const section = (i) => `** Section ${i}

Paragraph ${i} with *bold*, /italic/, ~inline code~, inline math $x_{${i}}^2$, and a [[https://example.com/${i}][link]]. ${filler}

- tight one
- tight two
- [ ] a task
- [X] a done task

#+begin_src clojure
(defn add-${i} [a b] (+ a b ${i}))
#+end_src

#+BEGIN_EXPORT latex
\\begin{center}Not math ${i} -- falls back to a highlighted code block\\end{center}
#+END_EXPORT

| Col A | Col B |
|-------+-------|
| ${i}  | ${i + 1} |

*** Repeated Heading

Body under the repeated heading (slug dedup must number these across the whole document).

`;
  let body = '#+TITLE: Big Org Document\n#+AUTHOR: Ada Lovelace\n\n* Intro\n\nIntro paragraph.\n\n';
  let i = 0;
  while (Buffer.byteLength(body, 'utf8') < 300 * 1024) { body += section(i); i += 1; }
  const batchPath = path.join(dir, 'batch.org');
  const streamPath = path.join(dir, 'stream.org');
  fs.writeFileSync(batchPath, body);
  fs.writeFileSync(streamPath, body);
  return { dir, batchPath, streamPath, text: body, size: Buffer.byteLength(body, 'utf8'), sections: i };
}

// A large standalone .tex whose per-section constructs (headings, styling, inline math, lists, a tabular) exercise
// the per-block post-passes, so a >256 KiB .tex must stream byte-identically to its batch render — the same
// whole-parse + progressive-paint engine as markdown/org, driven by markdown/latex-stream-blocks (ADR-0025).
function createLargeLatexFixtures() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-tex-stream-'));
  tempDirs.push(dir);
  const filler = 'The quick brown fox jumps over the lazy dog while pangrams exercise every glyph. '.repeat(12);
  const section = (i) => `\\section{Section ${i}}
Paragraph ${i} with \\textbf{bold ${i}}, \\emph{italic}, and inline math $x_{${i}}^2$. ${filler}

\\begin{itemize}
\\item First point ${i}
\\item Second point ${i}
\\end{itemize}

\\begin{center}
\\begin{tabular}{@{}ll@{}}
Item ${i} & Value ${i} \\\\
\\end{tabular}
\\end{center}

`;
  // no leading intro section: every <h3> comes from section(i), so the h3 count equals the number of sections
  // that carry math + a tabular + \textbf, keeping the per-section coverage assertions exact.
  let body = '\\documentclass{article}\n\\usepackage{amsmath}\n\\begin{document}\n';
  let i = 0;
  while (Buffer.byteLength(body, 'utf8') < 300 * 1024) { body += section(i); i += 1; }
  body += '\\end{document}\n';
  const batchPath = path.join(dir, 'batch.tex');
  const streamPath = path.join(dir, 'stream.tex');
  fs.writeFileSync(batchPath, body);
  fs.writeFileSync(streamPath, body);
  return { dir, batchPath, streamPath, text: body, size: Buffer.byteLength(body, 'utf8'), sections: i };
}

function firstDiff(a, b) {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return i;
  return a.length === b.length ? -1 : n;
}

// makeOutlinePdf() (two portrait pages + a nested outline) and the slide-deck builder live in the shared,
// deterministic fixture module.
const { makeOutlinePdf } = require('./fixtures/pdf-gen');

async function waitFor(predicate, label, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;

  while (Date.now() < deadline) {
    try {
      const value = await predicate();
      if (value) {
        return value;
      }
    } catch (err) {
      lastError = err;
    }
    await delay(50);
  }

  const suffix = lastError ? `: ${lastError.message}` : '';
  throw new Error(`Timed out waiting for ${label}${suffix}`);
}

async function evalIn(win, source) {
  return win.webContents.executeJavaScript(source, true);
}

// A gentle poller (500ms cadence) for eventually-consistent state that settles over seconds — e.g. a PDF
// outline resolving via async worker round-trips, or a reagent re-render deferred behind main-thread page
// rasterization. Deliberately slower than waitFor's 50ms loop: tight executeJavaScript polling during a
// pdf.js remount can transiently destabilize the headless IPC channel, so we probe calmly instead.
async function waitCalm(win, expr, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    try { last = await evalIn(win, expr); if (last) return last; } catch (err) { last = 'ERR ' + err.message; }
    await delay(500);
  }
  throw new Error(`Timed out waiting for ${label} (last=${JSON.stringify(last)})`);
}

// Middle-click the tab (strip entry, or vertical Tabs-panel row when `selector` says so) whose title
// contains `uriPart` — the ADR-0044 close gesture. Waits for the element first: a db-state waitFor
// returns a frame before Reagent has rendered the strip, so a bare querySelector would race it.
async function middleClickTab(win, uriPart, selector = '.vv-tab') {
  await waitFor(() => evalIn(win, `Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
    .some((el) => el.title.includes(${JSON.stringify(uriPart)}))`),
    `a ${selector} entry for ${uriPart} to render`, 8000);
  return evalIn(win, `(() => {
    const tab = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
      .find((el) => el.title.includes(${JSON.stringify(uriPart)}));
    tab.dispatchEvent(new MouseEvent('auxclick', { bubbles: true, cancelable: true, button: 1 }));
    return true; })()`);
}

async function dispatchWindowKey(win, key, opts = {}) {
  return evalIn(win, `(() => {
    const event = new KeyboardEvent('keydown', {
      key: ${JSON.stringify(key)},
      altKey: ${Boolean(opts.altKey)},
      ctrlKey: ${Boolean(opts.ctrlKey)},
      shiftKey: ${Boolean(opts.shiftKey)},
      metaKey: ${Boolean(opts.metaKey)},
      bubbles: true,
      cancelable: true
    });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  })()`);
}

async function dispatchMenuKey(win, key) {
  return evalIn(win, `(() => {
    const menu = document.querySelector('.vv-ctx-menu');
    if (!menu) {
      return { handled: false, missing: true };
    }
    menu.focus();
    const event = new KeyboardEvent('keydown', {
      key: ${JSON.stringify(key)},
      bubbles: true,
      cancelable: true
    });
    menu.dispatchEvent(event);
    return {
      handled: event.defaultPrevented,
      focusedLabel: document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() || null
    };
  })()`);
}

async function hoverMenuItem(win, label) {
  return evalIn(win, `(() => {
    const label = ${JSON.stringify(label)};
    const item = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item'))
      .find((node) => node.textContent.includes(label));
    if (!item) {
      return false;
    }
    const rect = item.getBoundingClientRect();
    item.dispatchEvent(new MouseEvent('mouseover', {
      bubbles: true,
      cancelable: true,
      clientX: rect.left + 8,
      clientY: rect.top + 8
    }));
    return true;
  })()`);
}

async function sendChord(win, keyCode, modifiers = []) {
  win.focus();
  win.webContents.focus();
  win.webContents.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
  win.webContents.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
}

function installIpc(state) {
  ipcMain.on('vv:settings-request', (event) => {
    event.sender.send('vv:settings', '');
  });
  ipcMain.on('vv:keymap-request', (event) => {
    event.sender.send('vv:keymap', null);
  });
  ipcMain.on('vv:grammars-request', (event) => {
    event.sender.send('vv:grammars', '');
  });
  ipcMain.on('vv:app-info-request', (event) => {
    event.sender.send('vv:app-info', {
      name: 'vinary-viewer',
      version: 'smoke-test',
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      node: process.versions.node
    });
  });
  // the zoom bar's boot-pull (fix for the "% resets to 100 after restart" bug): reply with the live factor,
  // exactly as src/vinary/main/shell.cljs does. Count calls so the regression test can assert it auto-fires.
  // slide-show fullscreen (ADR-0045): the stub main records every request and answers like the real
  // vinary.main.fullscreen does for a window that was not fullscreen before (owned = it entered it)
  ipcMain.on('vv:set-fullscreen', (event, payload) => {
    const on = Boolean(payload && payload.on);
    state.fullscreenRequests = (state.fullscreenRequests || []).concat([on]);
    event.sender.send('vv:fullscreen-changed', { fullscreen: on, owned: on });
  });
  ipcMain.on('vv:fullscreen-request', (event) => {
    state.fullscreenStatePulls = (state.fullscreenStatePulls || 0) + 1;
    event.sender.send('vv:fullscreen-changed', { fullscreen: false, owned: false });
  });
  ipcMain.on('vv:zoom-request', (event) => {
    state.zoomRequests = (state.zoomRequests || 0) + 1;
    event.sender.send('vv:zoom-changed', { context: 'window', factor: event.sender.getZoomFactor() });
  });
  ipcMain.on('vv:open-dialog', (_event, seed) => {
    state.openDialogs += 1;
    state.lastOpenSeed = seed === undefined ? null : seed;   // ordered candidate folders (active path, then recent-files MRU)
  });
  ipcMain.on('vv:open', (event, filePath) => {
    // remote (ssh://) paths route through the REAL remote reader exactly as service.cljs/send-remote-content! does
    if (sshTransport.isRemoteUri(filePath)) {
      contentService.openRemoteUri(filePath, contentService.classifyName(filePath))
        .then((p) => event.sender.send('vv:content', p))
        .catch((e) => event.sender.send('vv:error', { path: filePath, message: e.message }));
      return;
    }
    const payload = state.contentByPath.get(filePath);
    if (payload) {
      // record the stamp actually DELIVERED: a test that then sends a "newer" refresh must stamp it after THIS one
      // (a stamp taken before the open can equal it — the round trip can take 1 ms — and an equal stamp is no
      // refresh at all to the view)
      const sentStamp = Date.now();
      state.sentStamps.set(filePath, sentStamp);
      event.sender.send('vv:content', { ...payload, stamp: sentStamp });
    } else {
      state.openedPaths.push(filePath);
    }
  });
  // Settings ▸ File Type (ADR-0036) — mirror the REAL main handler's observable behavior: re-read the
  // file through the real content service with the EXPLICIT kind (authoritative — no classifyName, no
  // sniffing) and re-send, decorating :language exactly as service.cljs decorate-js-payload! does. The
  // main-side registry/persistence half is unit-tested (doc_overrides_test) and daemon-smoked; this seam
  // proves the renderer half: menu → :doc/set-file-type → vv:set-file-type → re-sent kind re-renders.
  ipcMain.on('vv:set-file-type', (event, req) => {
    state.fileTypeRequests = (state.fileTypeRequests || []).concat([req]);
    contentService.openUri(req.path, req.kind)
      .then((p) => {
        if (req.language) p.language = req.language;
        event.sender.send('vv:content', p);
      })
      .catch((e) => event.sender.send('vv:error', { path: req.path, message: e.message }));
  });
  ipcMain.on('vv:pdf-show', (_event, payload) => {
    state.pdfShow = payload;
  });
  ipcMain.on('vv:pdf-bounds', (_event, payload) => {
    state.pdfBounds = payload;
  });
  ipcMain.on('vv:pdf-hide', () => {
    state.pdfHidden = true;
  });
  ipcMain.on('vv:http-show', (_event, payload) => {
    state.httpShow = payload;
    state.httpVisible = true;
  });
  ipcMain.on('vv:http-bounds', (_event, payload) => {
    state.httpBounds = payload;
  });
  ipcMain.on('vv:http-hide', () => {
    state.httpHidden = true;
    state.httpVisible = false;
  });
  ipcMain.handle('vv:http-snapshot', () => state.snapshotDataUrl);
  // document streaming — wired to the REAL content service exactly as service.cljs does (createReadStream +
  // readline session registry); streamCount() then tells us whether a session leaked after teardown.
  ipcMain.handle('vv:stream-open',  (_event, req) => contentService.streamOpen(req));
  ipcMain.handle('vv:stream-pull',  (_event, req) => contentService.streamPull(req));
  ipcMain.handle('vv:stream-close', (_event, req) => contentService.streamClose(req));
  ipcMain.on('vv:watch-assets', () => {});
  // Diff headers ask for path-only structured entries; Split later asks for the same entries with content.
  // Requests without includePaths keep the legacy {rel: content} contract.
  ipcMain.handle('vv:load-diff-sources', (_event, req) => {
    const sources = state.diffSources || {};
    if (!req || !req.includePaths) return sources;
    const targets = state.diffTargets || {};
    const out = {};
    for (const rel of req.files || []) {
      if (!targets[rel]) continue;
      out[rel] = { path: targets[rel] };
      if (req.includeContent && typeof sources[rel] === 'string') out[rel].content = sources[rel];
    }
    return out;
  });
  // SSH remote files: capture the (secret) prompt reply for assertions; serve remote assets from the fixture.
  ipcMain.on('vv:ssh-prompt-reply', (_event, payload) => { state.sshReply = payload; });
  ipcMain.handle('vv:load-remote-asset', (_event, req) => contentService.loadRemoteAsset(req.uri, req.relativeTo));
  // ADR-0040: fixture hunks for the blame-gutter arm (the real main is exercised by git-blame-smoke.js;
  // here the seam is mocked exactly like vv:open, so the arm proves the renderer's gutter pipeline)
  // ADR-0040: a three-commit fixture page for the Commit Graph arm (kebab string keys — exactly the
  // clj->js wire shape vinary.main.git's parser produces). The real data layer is pinned by
  // git-log-smoke.js; this proves the renderer's store → fold → windowed view → keyboard pipeline.
  const GG_HASHES = ['1'.repeat(40), '2'.repeat(40), '3'.repeat(40)];
  state.ggHashes = GG_HASHES;
  ipcMain.handle('vv:git-log', () => ({
    commits: [
      { hash: GG_HASHES[0], parents: [GG_HASHES[1]], 'author-name': 'Ada', 'author-email': 'ada@x',
        'author-date': '2026-08-11T10:00:00+02:00', 'committer-date': '2026-08-11T10:00:00+02:00',
        refs: ['HEAD -> main', 'tag: v1.0'], subject: 'feat: newest', body: '' },
      { hash: GG_HASHES[1], parents: [GG_HASHES[2]], 'author-name': 'Ada', 'author-email': 'ada@x',
        'author-date': '2026-08-11T09:00:00+02:00', 'committer-date': '2026-08-11T09:00:00+02:00',
        refs: [], subject: 'feat: middle', body: 'a body' },
      { hash: GG_HASHES[2], parents: [], 'author-name': 'Bob', 'author-email': 'bob@x',
        'author-date': '2026-08-11T08:00:00+02:00', 'committer-date': '2026-08-11T08:00:00+02:00',
        refs: [], subject: 'root', body: '' },
    ],
    exhausted: true,
  }));
  ipcMain.handle('vv:git-branches', () => ({
    head: 'main', 'detached?': false,
    branches: [{ name: 'main', kind: 'local', 'current?': true }],
  }));
  ipcMain.handle('vv:git-open-diff', (_event, req) => {
    state.gitOpenDiffRequests.push(req);
    // ADR-0042: when a section registers spill fixtures, answer like the real main does — {path} —
    // so the renderer's :tab/navigate runs the REAL vv:open → vv:content pipeline and the derived
    // open-highlight arms exercise the whole chain. Sections that never set gitDiffSpills keep the
    // inert error reply (the Commit Graph keyboard arm asserts the request shape and must stay on
    // the graph document).
    const spills = state.gitDiffSpills;
    if (spills) {
      if (req && req['parent?'] && spills.parent) return { path: spills.parent, title: 'parent.diff' };
      if (req && req.from && spills.range) return { path: spills.range, title: 'range.diff' };
    }
    return { error: 'mocked: this harness opens no diff documents' };
  });
  ipcMain.handle('vv:git-blame', (_event, req) => {
    state.gitBlameRequests.push(req);
    return {
      root: '/fixture/repo',
      hunks: [
        { hash: 'a'.repeat(40), 'orig-line': 1, 'final-line': 1, count: 3,
          'author-name': 'Ada', 'author-email': 'ada@x.io', 'author-date': 1700000000000,
          summary: 'feat: first', uncommitted: false },
        { hash: '0'.repeat(40), 'orig-line': 4, 'final-line': 4, count: 1, uncommitted: true },
      ],
    };
  });
  ipcMain.handle('vv:complete-path', async (_event, input) => {
    state.completePathInputs.push(input);
    // One deliberately slow live-completion reply lets the URI-tab regression prove that an answer owned by
    // tab A cannot overwrite tab B after the user switches. Ordinary requests stay immediate.
    if (String(input).includes('__vv_slow_complete__')) await delay(300);
    return { input, dir: null, target: null, 'exists?': false, 'dir?': false, entries: [] };
  });
  ipcMain.on('vv:context-open-link', () => {});
  ipcMain.on('vv:context-open-link-new-tab', () => {});
  ipcMain.on('vv:clipboard-write', (_event, text) => {
    state.lastCopiedText = text;
  });
  // native password-manager bridge mocks (so the Passwords dialog populates with a provider row)
  ipcMain.on('vv:password-state-request', (event) => {
    event.sender.send('vv:password-state', {
      providers: [{ id: 'op', label: '1Password', status: 'ready', 'save-supported?': true, message: '' }],
      forms: { count: 0 }
    });
  });
  ipcMain.on('vv:password-search', (_event, url) => { state.pwSearch = url; });
  ipcMain.on('vv:password-fill', (_event, item) => { state.pwFill = item; });
  ipcMain.on('vv:password-save', (_event, payload) => { state.pwSave = payload; });
  ipcMain.on('vv:password-dismiss-save', (_event, token) => { state.pwDismiss = token; });
}

async function main() {
  const state = {
    sentStamps: new Map(),   // path → the stamp the stub delivered on its last vv:open reply (see installIpc)
    openDialogs: 0,
    lastOpenSeed: null,   // ordered candidate folder paths the renderer passed with the last vv:open-dialog
    zoomRequests: 0,   // vv:zoom-request boot-pull calls (Bug-1 restart-% regression)
    gitBlameRequests: [],   // ADR-0040: vv:git-blame invocations (the blame-gutter arm asserts the seam fired)
    gitOpenDiffRequests: [],   // ADR-0040: vv:git-open-diff invocations (the graph arm asserts Enter's request shape)
    pdfShow: null,
    pdfBounds: null,
    pdfHidden: false,
    httpHidden: false,
    httpVisible: false,
    httpShow: null,
    httpBounds: null,
    // a minimal valid 1x1 PNG, returned by the mocked vv:http-snapshot so web-host can freeze the page
    snapshotDataUrl: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
    lastCopiedText: null,
    pwSearch: null, pwFill: null, pwSave: null, pwDismiss: null,
    completePathInputs: [],
    openedPaths: [],
    contentByPath: new Map(),
    diffSources: null,
    diffTargets: null,
    // Every renderer console error whose text mentions the CSP. Injecting MathJax's stylesheet (which carries an
    // @font-face with a data: URL) silently tripped `font-src` on every launch, printed only as noise among the
    // re-frame warnings. Collect them so a CSP regression fails the run instead of scrolling past.
    cspViolations: []
  };

  installIpc(state);

  await app.whenReady();

  const win = new BrowserWindow(browserWindowOptions({
    width: 1000,
    height: 700,
    show: false,
    paintWhenInitiallyHidden: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: PRELOAD
    }
  }));

  win.webContents.on('render-process-gone', (_event, details) => {
    throw new Error(`Renderer process gone: ${details.reason}`);
  });
  win.webContents.on('console-message', (details) => {
    const level = details.level;
    if (/Content Security Policy/i.test(details.message || '')) {
      state.cspViolations.push(details.message.slice(0, 200));
    }
    if (level >= 2 || level === 'warning' || level === 'error') {
      console.error(
        `[renderer:${level}] ${details.message} (${details.sourceId}:${details.lineNumber || details.line})`
      );
    }
  });
  win.webContents.on('did-fail-load', (_event, code, description, url) => {
    throw new Error(`Renderer failed to load ${url}: ${code} ${description}`);
  });

  await win.loadFile(INDEX);
  // A Weston headless surface needs to be mapped before it receives ongoing frame callbacks. This is still
  // invisible to the user under Xvfb/Weston, and showForTest suppresses mapping for native macOS/Windows mode.
  showForTest(win);

  await waitFor(
    () => evalIn(win, `Boolean(window.__vvdb && document.querySelector('.vv-menubar') && document.querySelector('.vv-tab-new'))`),
    'renderer boot'
  );
  console.log('[ok] renderer booted');

  const tenxVisible = await evalIn(win, `Boolean(window.__vvdb().ui['re-frame-10x-open?'])`);
  assert.strictEqual(tenxVisible, false, 're-frame-10x must be hidden by default');
  console.log('[ok] re-frame-10x hidden by default');

  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(
    () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      return ui.menu === 'Settings' && ui['menu-focus'] === 0;
    })()`),
    'Alt+S Settings menu focus'
  );
  await dispatchWindowKey(win, 'ArrowDown');
  await waitFor(
    () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      return ui.menu === 'Settings' && ui['menu-focus'] === 1;
    })()`),
    'Settings menu ArrowDown focus'
  );
  await dispatchWindowKey(win, 'ArrowRight');
  await waitFor(
    () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      // focus 1 is Theme now — File Type (ADR-0036) sits first in Settings
      return ui['menu-submenu'] === 'Theme' && ui['menu-submenu-focus'] === 0;
    })()`),
    'Settings submenu ArrowRight focus'
  );
  await dispatchWindowKey(win, 'Escape');
  await waitFor(
    () => evalIn(win, `window.__vvdb().ui.menu == null`),
    'menu Escape close'
  );
  console.log('[ok] Alt access keys and menu arrows work');

  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(
    () => evalIn(win, `window.__vvdb().ui.menu === 'Settings'`),
    'Settings menu reopen for pointer submenu checks'
  );
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-menu-dropdown')?.textContent.includes('Theme')`),
    'Settings menu dropdown rendered'
  );
  assert.strictEqual(await hoverMenuItem(win, 'Theme'), true, 'Theme submenu row must exist');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-menu-subdropdown')?.textContent.includes('Spacemacs Dark')`),
    'Theme submenu pointer open'
  );
  assert.strictEqual(await hoverMenuItem(win, 'Key Bindings'), true, 'Key Bindings submenu row must exist');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-menu-subdropdown')?.textContent.includes('Customize')`),
    'Key Bindings submenu pointer open'
  );
  await dispatchWindowKey(win, 'Escape');
  await waitFor(
    () => evalIn(win, `window.__vvdb().ui.menu == null`),
    'Settings pointer submenu Escape close'
  );
  console.log('[ok] Settings pointer submenus open');

  // Fix 5 — the always-visible zoom bar + the View ▸ Fit submenu
  const zoomBar = await evalIn(win, `(() => {
    const i = document.querySelector('.vv-bottombar .vv-zoom-input');
    return { present: Boolean(i), value: i ? i.value : null,
             buttons: document.querySelectorAll('.vv-bottombar .vv-zoom-btn').length };
  })()`);
  assert.strictEqual(zoomBar.present, true, 'the zoom bar must be present in every view');
  assert.strictEqual(zoomBar.buttons, 2, 'the zoom bar must have − and + buttons');
  assert.ok(/^\d+$/.test(zoomBar.value || ''), 'the zoom field must show a numeric percentage');
  await dispatchWindowKey(win, 'v', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'View'`), 'View menu open');
  await waitFor(() => evalIn(win, `document.querySelector('.vv-menu-dropdown')?.textContent.includes('Zoom In')`), 'View dropdown rendered');
  // B6 — the PDF-only items (Fit submenu, Invert PDF) appear ONLY when a PDF is the active view; none is yet.
  // A4 — the re-frame-10x item exists only in a dev build (its runtime is stripped from :release); the gate
  // runs this smoke against a release build with VV_RELEASE=1 to assert it is then absent.
  const releaseBuild = process.env.VV_RELEASE === '1';
  const viewNoPdf = await evalIn(win, `(() => { const t = document.querySelector('.vv-menu-dropdown')?.textContent || '';
    return { fit: t.includes('Fit'), invert: t.includes('Invert PDF'), tenx: t.includes('re-frame-10x'),
             allFiles: t.includes('All Files') }; })()`);
  assert.strictEqual(viewNoPdf.fit, false, 'View ▸ Fit must be hidden when no PDF is active');
  assert.strictEqual(viewNoPdf.invert, false, 'View ▸ Invert PDF must be hidden when no PDF is active');
  assert.strictEqual(viewNoPdf.allFiles, false, 'View ▸ Collapse All Files must be hidden when no diff is active (ADR-0037)');
  assert.strictEqual(viewNoPdf.tenx, !releaseBuild,
    releaseBuild ? 'View ▸ re-frame-10x must be absent in a release build' : 'View ▸ re-frame-10x present in a dev build');
  await dispatchWindowKey(win, 'Escape');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu == null`), 'View menu closed');
  console.log('[ok] zoom bar present + View PDF-only items hidden without a PDF');

  // The scroll tracer PATCHES Element.prototype's scroll accessors, so it must never reach a shipped build.
  // It is gated on goog.DEBUG (renderer.core/init); this asserts the gate BEHAVIOURALLY rather than trusting
  // the optimizer. Same shape as the re-frame-10x check above. See ADR-0032 / docs/scientific/09.
  const tracerPresent = await evalIn(win, `typeof window.__vvscrolltrace === 'function'`);
  assert.strictEqual(tracerPresent, !releaseBuild,
    releaseBuild
      ? 'the DEV scroll tracer must be absent from a release build (it patches Element.prototype)'
      : 'the DEV scroll tracer must be installed in a dev build');
  console.log(`[ok] DEV scroll tracer ${releaseBuild ? 'absent from the release build' : 'installed in the dev build'}`);

  // The input tracer patches HTMLInputElement.prototype's `value` accessor for the same class of reason,
  // and carries the same obligation: it must never reach a shipped build. Gated in the same goog.DEBUG
  // block in renderer.core/init, and asserted behaviourally here rather than by trusting the optimizer.
  // See ADR-0033 / docs/scientific/10.
  const inputTracerPresent = await evalIn(win, `typeof window.__vvinputtrace === 'function'`);
  assert.strictEqual(inputTracerPresent, !releaseBuild,
    releaseBuild
      ? 'the DEV input tracer must be absent from a release build (it patches HTMLInputElement.prototype)'
      : 'the DEV input tracer must be installed in a dev build');
  console.log(`[ok] DEV input tracer ${releaseBuild ? 'absent from the release build' : 'installed in the dev build'}`);

  // …whereas find's own cost counters are DELIBERATELY not gated: a release build is the one whose latency
  // actually matters, and they are three numbers already sitting in the finder's state atom (ADR-0033).
  const findStats = await evalIn(win, `(() => { const f = window.__vvfind && window.__vvfind();
    return f ? { ms: typeof f.ms, cpuMs: typeof f.cpuMs, chars: typeof f.chars, cached: typeof f.cached } : null; })()`);
  assert.deepStrictEqual(findStats, { ms: 'number', cpuMs: 'number', chars: 'number', cached: 'boolean' },
    'window.__vvfind() must report its cost counters in every build, release included');
  console.log('[ok] find cost counters available in this build (release-safe by design)');

  // Regression (Bug 1) — the zoom bar (:window-zoom) must seed from the REAL zoom factor at boot. On relaunch
  // Chromium restores the actual per-host zoom, but :window-zoom is ephemeral (default 1.0) and was only ever
  // written in reaction to a zoom action — so the bar wrongly read 100% until the first zoom. The renderer now
  // issues window.vv.requestZoom() right after wiring onZoomChanged (mirrors requestSettings); main replies on
  // vv:zoom-changed with getZoomFactor(). (preload.js + src/vinary/main/shell.cljs + renderer/core.cljs)
  assert.strictEqual(await evalIn(win, `typeof window.vv.requestZoom`), 'function',
    'preload must expose window.vv.requestZoom for the boot-pull');
  assert.ok(state.zoomRequests >= 1, `renderer must auto-issue the zoom boot-pull at startup (got ${state.zoomRequests})`);
  // reproduce the exact bug: the factor is restored to 1.5 while app-db shows the 1.0 default → bar reads 100.
  win.webContents.setZoomFactor(1.5);
  win.webContents.send('vv:zoom-changed', { context: 'window', factor: 1.0 });
  await waitFor(() => evalIn(win, `document.querySelector('.vv-zoom-input')?.value === '100'`), 'zoom bar shows the stale 100% (bug state)');
  const zoomReqBefore = state.zoomRequests;
  await evalIn(win, `window.vv.requestZoom()`);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-zoom-input')?.value === '150'`), 'boot-pull recovers the true 150% scale');
  assert.strictEqual(state.zoomRequests, zoomReqBefore + 1, 'requestZoom() must reach the main handler');
  win.webContents.setZoomFactor(1.0);                                   // restore 1.0 so later layout tests measure unzoomed
  win.webContents.send('vv:zoom-changed', { context: 'window', factor: 1.0 });
  await waitFor(() => evalIn(win, `document.querySelector('.vv-zoom-input')?.value === '100'`), 'zoom reset to 100 for subsequent tests');
  console.log('[ok] zoom bar seeds :window-zoom from the real factor at boot (restart-% regression)');

  // B4 / B5 — the mode-line indicator is removed, and #app clips overflow so a tall view adds no second
  // window-level scrollbar beside the content scroller
  const layout = await evalIn(win, `(() => ({
    noModeline: !document.querySelector('.vv-modeline'),
    appClip: getComputedStyle(document.getElementById('app')).overflowY,
    statusInWrap: !document.querySelector('.vv-statusbar') || Boolean(document.querySelector('.vv-content-wrap > .vv-statusbar'))
  }))()`);
  assert.strictEqual(layout.noModeline, true, 'the mode-line indicator must be removed');
  assert.strictEqual(layout.appClip, 'hidden', '#app must clip overflow so a tall view adds no window scrollbar');
  assert.strictEqual(layout.statusInWrap, true, 'the hover-URL status bar must live inside .vv-content-wrap (above the zoom bar)');
  console.log('[ok] mode-line removed + app root clipped + status bar repositioned');

  // PDF — rendered IN-DOM via pdf.js (parity with markdown: canvas + selectable text layer + find +
  // copy), NOT the retired native WebContentsView. Bytes are streamed on vv:content.
  const pdfFixture = path.join(ROOT, 'test', 'fixtures', 'smoke.pdf');
  win.webContents.send('vv:content', {
    path: pdfFixture,
    kind: 'pdf',
    bytes: new Uint8Array(fs.readFileSync(pdfFixture)),
    stamp: Date.now()
  });
  await waitFor(
    () => evalIn(win, `(() => {
      const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas');
      return Boolean(c) && c.getBoundingClientRect().width > 0 && c.getBoundingClientRect().height > 0;
    })()`),
    'PDF canvas rendered in the DOM', 15000
  );
  await waitFor(
    () => evalIn(win, `document.querySelectorAll('.vv-pdf-text span').length > 0`),
    'PDF text layer spans', 15000
  );
  const pdfLayout = await evalIn(win, `(() => {
    const canvas = document.querySelector('.vv-pdf-canvas');
    const span = Array.from(document.querySelectorAll('.vv-pdf-text span')).find(s => /Smoke|Vinary/.test(s.textContent))
                 || document.querySelector('.vv-pdf-text span');
    const cr = canvas.getBoundingClientRect();
    const sr = span.getBoundingClientRect();
    return {
      inRenderer: Boolean(document.querySelector('.vv-pdf-doc')),
      noNativeHost: !document.querySelector('.vv-pdf-host'),
      canvasW: Math.round(cr.width),
      textOverlapsCanvas: sr.left < cr.right && sr.right > cr.left && sr.top < cr.bottom && sr.bottom > cr.top
    };
  })()`);
  assert.strictEqual(pdfLayout.inRenderer, true, 'PDF must render in the in-renderer pdf-view (.vv-pdf-doc)');
  assert.strictEqual(pdfLayout.noNativeHost, true, 'the native PDF host (.vv-pdf-host) must be retired');
  assert.ok(pdfLayout.canvasW > 0, 'PDF canvas must have a visible width');
  assert.strictEqual(pdfLayout.textOverlapsCanvas, true, 'PDF text layer must align over the canvas');
  // the canvas must carry REAL painted pixels, not a blank/white surface (guards the white-page class —
  // the render completes before this point, gated by the text-layer wait above)
  const pdfNonWhite = await evalIn(win, `(() => {
    const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas');
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    let n = 0; for (let p = 0; p < d.length; p += 4) { if (d[p] < 235 || d[p+1] < 235 || d[p+2] < 235) n++; }
    return n;
  })()`);
  assert.ok(pdfNonWhite > 0, 'PDF canvas must contain rendered pixels (not a blank/white canvas)');
  console.log('[ok] PDF renders in-DOM: canvas + aligned text layer + real pixels (no native view)');

  // Bug C (round 5): pdf.js 5.x sizes/positions every text span via calc(var(--total-scale-factor) * …).
  // build-text-layer! must set --total-scale-factor (it formerly set only the renamed --scale-factor), else
  // the span geometry is invalid-at-computed-value and Ctrl+F highlights mis-position. Assert the var is set
  // AND a span's font-size resolves to a positive px (not the inherited body size that an undefined var gives).
  const pdfScale = await evalIn(win, `(() => {
    const layer = document.querySelector('.vv-pdf-text');
    const span = document.querySelector('.vv-pdf-text span');
    return {
      totalScaleVar: layer ? layer.style.getPropertyValue('--total-scale-factor').trim() : '',
      fontPx: span ? parseFloat(getComputedStyle(span).fontSize) : 0
    };
  })()`);
  assert.ok(pdfScale.totalScaleVar && parseFloat(pdfScale.totalScaleVar) > 0,
    'the PDF text layer must set --total-scale-factor (pdf.js 5.x reads it) so span geometry / find highlights resolve');
  assert.ok(pdfScale.fontPx > 0, 'PDF text-layer spans must have a resolved (non-zero) font-size from the scale var');
  console.log('[ok] PDF text layer sets --total-scale-factor → find highlights align (Bug C)');

  // B6 — now a PDF IS the active view → its Fit submenu + Invert PDF appear, and the Fit submenu works
  await dispatchWindowKey(win, 'v', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'View'`), 'View menu open (pdf active)');
  await waitFor(() => evalIn(win, `document.querySelector('.vv-menu-dropdown')?.textContent.includes('Fit')`), 'View ▸ Fit present with a PDF active');
  assert.strictEqual(await evalIn(win, `(document.querySelector('.vv-menu-dropdown')?.textContent || '').includes('Invert PDF')`), true, 'View ▸ Invert PDF present with a PDF active');
  assert.strictEqual(await evalIn(win, `(document.querySelector('.vv-menu-dropdown')?.textContent || '').includes('All Files')`), false,
    'View ▸ Collapse All Files stays hidden with a PDF active (the diff gate is not the pdf gate; ADR-0037)');
  assert.strictEqual(await hoverMenuItem(win, 'Fit'), true, 'View menu must have a Fit submenu with a PDF active');
  await waitFor(() => evalIn(win, `(() => { const d = document.querySelector('.vv-menu-subdropdown');
    return Boolean(d) && d.textContent.includes('Fit Width') && d.textContent.includes('Fit Page'); })()`),
    'Fit submenu lists Fit Width / Fit Page');
  await dispatchWindowKey(win, 'Escape');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu == null`), 'View menu closed (pdf)');
  console.log('[ok] View ▸ Fit + Invert PDF appear only with a PDF active');

  // ── Diff (.diff/.patch) — colored multi-file unified view + the side-by-side split toggle + on-disk
  //    enrichment (ADR-0026). __vvopen navigates the active tab to the diff path (so content-view shows it);
  //    the content is then pushed directly (the unified HTML is computed client-side by :diff/render).
  const diffPath = path.join(ROOT, 'test', 'greet.diff');
  const diffText = [
    'diff --git a/src/greet.js b/src/greet.js',
    '--- a/src/greet.js',
    '+++ b/src/greet.js',
    '@@ -1,3 +1,3 @@',
    ' function greet(name) {',
    '-  return "hi " + name;',
    '+  return "hello " + name;',
    ' }',
    'diff --git a/README.md b/README.md',
    'new file mode 100644',
    '--- /dev/null',
    '+++ b/README.md',
    '@@ -0,0 +1 @@',
    '+# Project',
    ''
  ].join('\n');
  // Stage one resolved NEW file. Initial render receives only its path (navigable header, no eager bytes);
  // selecting Split receives the same path + content for full-file enrichment. README.md stays unresolved.
  const diffTargetDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-diff-target-'));
  tempDirs.push(diffTargetDir);
  const greetTarget = path.join(diffTargetDir, 'src', 'greet.js');
  fs.mkdirSync(path.dirname(greetTarget), { recursive: true });
  const greetSource = 'function greet(name) {\n  return "hello " + name;\n}\nconst VERSION = "1.0";\nmodule.exports = greet;\n';
  fs.writeFileSync(greetTarget, greetSource);
  state.contentByPath.set(greetTarget, await contentService.openUri(greetTarget));
  state.diffSources = { 'src/greet.js': greetSource };
  state.diffTargets = { 'src/greet.js': greetTarget };
  await evalIn(win, `window.__vvopen(${JSON.stringify(diffPath)})`);
  win.webContents.send('vv:content', { path: diffPath, kind: 'diff', text: diffText, stamp: Date.now(), meta: { size: diffText.length } });
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body .vv-diff-insert') && document.querySelector('.vv-content .markdown-body .vv-diff-delete'))`),
    'diff unified view renders colored insert/delete lines', 8000
  );
  const diffUnified = await evalIn(win, `(() => {
    const insert = document.querySelector('.vv-diff-insert');
    return {
      banners: document.querySelectorAll('.vv-diff-file-head').length,
      hasHunk: Boolean(document.querySelector('.vv-diff-hunk')),
      insertText: (document.querySelector('.vv-diff-insert .vv-diff-code') || {}).textContent || '',
      deleteText: (document.querySelector('.vv-diff-delete .vv-diff-code') || {}).textContent || '',
      dataNew: insert ? (insert.getAttribute('data-new') || '') : '',
      syntaxTokens: document.querySelectorAll('.vv-diff-code [class^="cm-"]').length,
      insertBg: insert ? getComputedStyle(insert).backgroundColor : '',
      jsString: (() => {
        const token = document.querySelectorAll('details.vv-diff-file')[0]?.querySelector('.cm-string');
        const line = token?.closest('.vv-diff-line');
        return token && line ? { color: getComputedStyle(token).color, lineColor: getComputedStyle(line).color } : null;
      })(),
      mdHeading: (() => {
        const file = document.querySelectorAll('details.vv-diff-file')[1];
        const token = file?.querySelector('.cm-md-heading, .cm-md-heading-marker');
        const line = token?.closest('.vv-diff-line');
        return token && line ? { color: getComputedStyle(token).color, lineColor: getComputedStyle(line).color } : null;
      })(),
      greetHref: document.querySelector('.vv-diff-file-path[data-vv-diff-path="src/greet.js"]')?.getAttribute('href') || '',
      readmeHref: document.querySelector('.vv-diff-file-path[data-vv-diff-path="README.md"]')?.getAttribute('href') || ''
    };
  })()`);
  assert.strictEqual(diffUnified.banners, 2, 'a multi-file diff renders one banner per file (also the Contents anchors)');
  assert.ok(diffUnified.hasHunk, 'the unified view renders the @@ hunk header');
  assert.ok(/hello/.test(diffUnified.insertText) && diffUnified.insertText.startsWith('+'), 'the inserted line renders with its + marker');
  assert.ok(/hi /.test(diffUnified.deleteText) && diffUnified.deleteText.startsWith('-'), 'the deleted line renders with its - marker');
  assert.ok(diffUnified.dataNew && Number(diffUnified.dataNew) > 0, 'the insert line carries a data-new gutter number');
  assert.ok(diffUnified.syntaxTokens > 0, 'deducible .js/.md files receive tree-sitter token spans in Unified');
  assert.ok(diffUnified.jsString && diffUnified.jsString.color !== diffUnified.jsString.lineColor,
    'the .js section applies its language highlighter foreground, not merely token markup');
  assert.ok(diffUnified.mdHeading && diffUnified.mdHeading.color !== diffUnified.mdHeading.lineColor,
    'the .md section applies its Markdown-specific language foreground');
  assert.ok(diffUnified.insertBg && diffUnified.insertBg !== 'rgba(0, 0, 0, 0)',
    'the insert tint remains on the row behind syntax-token foreground colors');
  assert.ok(diffUnified.greetHref.startsWith('file://') && decodeURIComponent(diffUnified.greetHref).endsWith('/src/greet.js'),
    'a main-resolved diff header path is activated as an encoded file link');
  assert.strictEqual(diffUnified.readmeHref, '', 'an unresolved diff header path remains inert (no href)');
  console.log('[ok] diff renders syntax colors under diff tints, gutters, and resolved-only header links');

  // ── collapsible per-file previews (ADR-0037): each file is <details class="vv-diff-file" open> whose
  //    <summary> banner toggles it; state is per-tab and re-applied after every innerHTML rebuild ──
  const detailsState = () => evalIn(win, `(() =>
    Array.from(document.querySelectorAll('.vv-content details.vv-diff-file')).map(d => ({
      id: (d.querySelector('summary.vv-diff-file-head') || {}).id, open: d.open })))()`);
  {
    const ds0 = await detailsState();
    assert.strictEqual(ds0.length, 2, 'each diff file renders as a details.vv-diff-file wrapper');
    assert.ok(ds0.every(d => d.open), 'files render expanded by default');
    assert.deepStrictEqual(ds0.map(d => d.id), ['vv-diff-file-0', 'vv-diff-file-1'],
      'the summaries carry the canonical vv-diff-file-N ids');
  }
  // clicking the banner collapses that ONE file (the delegated branch preventDefaults the native toggle)
  await evalIn(win, `document.getElementById('vv-diff-file-0').click()`);
  await waitFor(async () => {
    const ds = await detailsState();
    return ds.length === 2 && ds[0].open === false && ds[1].open === true;
  }, 'a banner click collapses its own file only', 8000);
  await evalIn(win, `document.getElementById('vv-diff-file-0').click()`);
  await waitFor(async () => (await detailsState()).every(d => d.open), 'clicking again expands it', 8000);
  console.log('[ok] a diff file banner click toggles that file preview (ADR-0037)');

  // Platform-regime pin (ADR-0037 — the find-over-collapsed doc wording rests on it): this Chromium hides
  // a closed <details>' body via content-visibility on an internal slot, so the CHILDREN's computed style
  // stays display:block/visible — the find walker (which prunes only display:none/visibility:hidden
  // subtrees) therefore still MATCHES text inside a collapsed file, with the highlight invisible until it
  // expands (docs/features/28-diff-rendering.md states exactly that). If a Chromium upgrade flips back to
  // the legacy UA `display:none` rule, this assertion fails on purpose: update the doc wording with it.
  const closedRegime = await evalIn(win, `(() => {
    const d = document.getElementById('vv-diff-file-0').closest('details');
    const wasOpen = d.open; d.open = false;
    const line = d.querySelector('.vv-diff-line');
    const cs = getComputedStyle(line);
    const out = { display: cs.display, visibility: cs.visibility };
    d.open = wasOpen;
    return out;
  })()`);
  assert.notStrictEqual(closedRegime.display, 'none',
    'closed-details children are hidden via content-visibility (NOT display:none) — the find-over-collapsed doc wording depends on this');
  assert.strictEqual(closedRegime.visibility, 'visible',
    'closed-details children keep visibility:visible — same reason');
  console.log('[ok] closed-details child regime pinned (content-visibility hiding — find still matches inside collapsed files)');

  // View ▸ Collapse All Files — the diff-only single toggling item: label flips to "Expand All Files"
  // once every file is collapsed, and back
  const clickViewItem = (label) => evalIn(win, `(() => {
    const it = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item'))
      .find(el => el.textContent.includes(${JSON.stringify(label)}));
    if (it) it.click();
    return Boolean(it);
  })()`);
  await dispatchWindowKey(win, 'v', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'View'`), 'View menu opens with a diff active');
  await waitFor(() => evalIn(win, `(document.querySelector('.vv-menu-dropdown')?.textContent || '').includes('Collapse All Files')`),
    'the diff-only Collapse All Files item is present with a diff active');
  assert.strictEqual(await clickViewItem('Collapse All Files'), true, 'Collapse All Files is clickable');
  await waitFor(async () => (await detailsState()).every(d => d.open === false), 'Collapse All collapses every file', 8000);
  await dispatchWindowKey(win, 'v', { altKey: true });
  await waitFor(() => evalIn(win, `(document.querySelector('.vv-menu-dropdown')?.textContent || '').includes('Expand All Files')`),
    'the item label flips to Expand All Files once everything is collapsed');
  assert.strictEqual(await clickViewItem('Expand All Files'), true, 'Expand All Files is clickable');
  await waitFor(async () => (await detailsState()).every(d => d.open === true), 'Expand All expands every file', 8000);
  console.log('[ok] View ▸ Collapse/Expand All Files toggles every diff file (dynamic label, diff-only)');

  // ── the diff layout choice lives in the Preview combo's caret menu now (ADR-0037; the [Unified | Split]
  //    seg control is retired). A lone diff's menu is exactly [Unified, Split]; picks fire on MOUSE-DOWN. ──
  // collapse file 0 first, so the collapsed state can be shown to survive the layout switch
  await evalIn(win, `document.getElementById('vv-diff-file-0').click()`);
  await waitFor(async () => (await detailsState())[0].open === false, 'file 0 collapsed before the layout switch', 8000);
  const openComboMenu = async () => {
    await evalIn(win, `document.querySelector('.vv-combo-caret').click()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-combo-menu'))`), 'the Preview caret menu opens');
    return evalIn(win, `Array.from(document.querySelectorAll('.vv-combo-menu .vv-combo-opt')).map(li => li.textContent.trim())`);
  };
  const pickCombo = (label) => evalIn(win, `(() => {
    const li = Array.from(document.querySelectorAll('.vv-combo-menu .vv-combo-opt'))
      .find(el => el.textContent.trim() === ${JSON.stringify(label)});
    if (li) li.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    return Boolean(li);
  })()`);
  {
    const rows = await openComboMenu();
    assert.deepStrictEqual(rows, ['Unified', 'Split'],
      'a lone diff\'s Preview caret menu is exactly the two layout rows (no redundant file row, no divider)');
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-combo-menu .vv-combo-sep').length`), 0,
      'no divider for a lone diff');
    assert.strictEqual(await pickCombo('Split'), true, 'the Split row picks on mouse-down');
  }
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-diff-splitview .vv-diff-row'))`),
    'the split (side-by-side) view renders two-column rows', 8000
  );
  const split = await evalIn(win, `(() => ({
    hasOld: Boolean(document.querySelector('.vv-diff-side-old')),
    hasNew: Boolean(document.querySelector('.vv-diff-side-new')),
    text: document.querySelector('.vv-diff-splitview').textContent,
    syntaxTokens: document.querySelectorAll('.vv-diff-splitview .vv-diff-side [class^="cm-"]').length,
    jsString: Boolean(document.querySelectorAll('.vv-diff-splitview details.vv-diff-file')[0]?.querySelector('.cm-string')),
    mdHeading: Boolean(document.querySelectorAll('.vv-diff-splitview details.vv-diff-file')[1]?.querySelector('.cm-md-heading, .cm-md-heading-marker')),
    greetHref: document.querySelector('.vv-diff-splitview .vv-diff-file-path[data-vv-diff-path="src/greet.js"]')?.getAttribute('href') || '',
    readmeHref: document.querySelector('.vv-diff-splitview .vv-diff-file-path[data-vv-diff-path="README.md"]')?.getAttribute('href') || '',
    changedBg: getComputedStyle(document.querySelector('.vv-diff-row-change .vv-diff-side-new')).backgroundColor
  }))()`);
  assert.ok(split.hasOld && split.hasNew, 'the split view has old and new side columns');
  assert.ok(split.syntaxTokens > 0, 'Split carries tree-sitter token spans on both hunk/full-source rows');
  assert.ok(split.jsString && split.mdHeading,
    'Split independently highlights the deducible JavaScript and Markdown file types');
  assert.ok(split.greetHref.startsWith('file://') && !split.readmeHref,
    'resolved-only header navigation is re-projected after the Unified-to-Split innerHTML remount');
  assert.ok(split.changedBg && split.changedBg !== 'rgba(0, 0, 0, 0)',
    'Split keeps its changed-row tint behind token foreground colors');
  // enrichment: the staged on-disk file contributes context lines BEYOND the hunk window (textContent
  // includes a collapsed details' hidden children, so file 0's enrichment is still assertable)
  assert.ok(/VERSION/.test(split.text) && /module\.exports/.test(split.text),
    'the split view is enriched with full-file context from the on-disk source (beyond the diff hunk)');
  // the per-tab collapsed set survives the unified→split remount (both views share the id space)
  {
    const ds = await detailsState();
    assert.strictEqual(ds.length, 2, 'the split view renders the same details.vv-diff-file wrappers');
    assert.strictEqual(ds[0].open, false, 'file 0 stays collapsed across the layout switch');
    assert.strictEqual(ds[1].open, true, 'file 1 stays expanded across the layout switch');
  }
  console.log('[ok] Preview▾ Split renders side-by-side, enriches from disk, and keeps the collapsed set');

  // back to unified via the combo; the collapsed set survives again, then expand file 0 for the tests below
  {
    const rows = await openComboMenu();
    assert.ok(rows.includes('Unified'), 'the caret menu offers Unified in the split view too');
    assert.strictEqual(await pickCombo('Unified'), true);
  }
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body .vv-diff-line')) && !document.querySelector('.vv-diff-splitview')`),
    'picking Unified shows the unified view', 8000
  );
  await waitFor(async () => (await detailsState())[0].open === false, 'file 0 still collapsed back in unified', 8000);
  await evalIn(win, `document.getElementById('vv-diff-file-0').click()`);
  await waitFor(async () => (await detailsState()).every(d => d.open), 'file 0 re-expanded', 8000);
  console.log('[ok] the Preview combo caret switches diff layouts (seg control retired) and collapse state survives');

  // ── the Preview combo KEEPS its caret in the diff's SOURCE view (ADR-0037): the layout rows stay
  //    offered there, and picking one switches back to the preview in that layout ──
  await evalIn(win, `window.__vvsource()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-source'))`),
    'the diff source view mounts', 12000);
  assert.ok(await evalIn(win, `Boolean(document.querySelector('.vv-combo-caret'))`),
    'the Preview combo keeps its caret while viewing the diff source');
  {
    const rows = await openComboMenu();
    assert.ok(rows.includes('Unified') && rows.includes('Split'),
      `the source-view Preview menu still offers the layout rows (got ${JSON.stringify(rows)})`);
    assert.strictEqual(await pickCombo('Split'), true, 'Split is pickable from the source view');
  }
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-diff-splitview .vv-diff-row'))`),
    'picking Split from the source view switches to the split PREVIEW', 8000
  );
  // back to the unified preview for the drives below
  {
    const rows = await openComboMenu();
    assert.ok(rows.includes('Unified'));
    assert.strictEqual(await pickCombo('Unified'), true);
  }
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body .vv-diff-line')) && !document.querySelector('.vv-diff-splitview')`),
    'back to the unified preview after the source-view drive', 8000
  );
  console.log('[ok] the Preview combo keeps Unified/Split in the diff SOURCE view; a pick returns to that preview');

  // ── Vim f-hints treat the banners as :toggle targets: typing a banner's label clicks it (ADR-0037) ──
  win.webContents.send('vv:keymap', '{:active "vim" :order [] :sets {}}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'vim'`), 'vim keymap active for the diff hint drive');
  await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); if (c) c.focus(); })()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.input['in-input?']`), ':in-input? cleared before the diff f');
  await dispatchWindowKey(win, 'f');
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.hints && window.__vvdb().ui.hints['active?'])`),
    'f activates hint mode over a diff (the banners are targets)');
  const diffHint = await evalIn(win, `(() => {
    const ts = (window.__vvdb().ui.hints && window.__vvdb().ui.hints.targets) || [];
    const t = ts.find(t => t.kind === 'toggle' && (t.text || '').indexOf('greet.js') >= 0);
    return t ? { label: t.label, path: t.path } : null;
  })()`);
  assert.ok(diffHint && diffHint.path === 'vv-diff-file-0', 'the greet.js banner is a :toggle hint target keyed by its id');
  for (const ch of diffHint.label) { await dispatchWindowKey(win, ch); await delay(20); }
  await waitFor(async () => (await detailsState())[0].open === false,
    'typing the banner hint label toggles its file (rides the same click branch)', 8000);
  await evalIn(win, `document.getElementById('vv-diff-file-0').click()`);   // restore expanded
  win.webContents.send('vv:keymap', null);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'default'`), 'default keymap restored after the diff hint drive');
  await delay(60);
  console.log('[ok] Vim f-hints toggle diff file previews via their banners (ADR-0037)');

  // ── vim z M / z R fold chords collapse/expand every file (self-gated on the diff preview) ──
  win.webContents.send('vv:keymap', '{:active "vim" :order [] :sets {}}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'vim'`), 'vim keymap active for the fold chords');
  await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); if (c) c.focus(); })()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.input['in-input?']`), ':in-input? cleared before z M');
  await dispatchWindowKey(win, 'z'); await delay(30); await dispatchWindowKey(win, 'M');
  await waitFor(async () => (await detailsState()).every(d => d.open === false), 'z M collapses every file', 8000);
  await dispatchWindowKey(win, 'z'); await delay(30); await dispatchWindowKey(win, 'R');
  await waitFor(async () => (await detailsState()).every(d => d.open === true), 'z R expands every file', 8000);
  win.webContents.send('vv:keymap', null);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'default'`), 'default keymap restored after the fold chords');
  await delay(60);
  console.log('[ok] vim z M / z R collapse and expand all diff files');

  // A target can disappear after the first existence check. Live refresh must clear the prior href immediately,
  // and a later successful resolution must reactivate it on the newly mounted HTML.
  state.diffTargets = {};
  state.diffSources = {};
  win.webContents.send('vv:content', {
    path: diffPath, kind: 'diff', text: diffText, stamp: Date.now(), meta: { size: diffText.length }
  });
  await waitFor(() => evalIn(win,
    `!document.querySelector('.vv-diff-file-path[data-vv-diff-path="src/greet.js"][href]')`),
  'live refresh removes a href whose target no longer resolves', 8000);
  state.diffTargets = { 'src/greet.js': greetTarget };
  state.diffSources = { 'src/greet.js': greetSource };
  win.webContents.send('vv:content', {
    path: diffPath, kind: 'diff', text: diffText, stamp: Date.now() + 1, meta: { size: diffText.length }
  });
  await waitFor(() => evalIn(win,
    `Boolean(document.querySelector('.vv-diff-file-path[data-vv-diff-path="src/greet.js"][href]'))`),
  'a later refresh reapplies a newly resolved href', 8000);
  assert.ok((await detailsState()).every(d => d.open),
    'target disappearance/reappearance does not disturb controlled file-collapse state');
  console.log('[ok] live refresh removes stale diff links and reapplies newly resolved targets');

  // Resolved filename navigation shares the preview-link contract but must outrank the enclosing summary's
  // collapse toggle: Ctrl+left-click opens a BACKGROUND tab (ADR-0044 — the reader stays on the diff);
  // plain left-click replaces the current view.
  const diffNavBefore = await evalIn(win, `(() => {
    const ui = window.__vvdb().ui;
    return { tabCount: ui.tabs.length, active: ui['active-tab'] };
  })()`);
  await evalIn(win, `(() => {
    const a = document.querySelector('.vv-diff-file-path[data-vv-diff-path="src/greet.js"][href]');
    a.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true, button: 0 }));
  })()`);
  await waitFor(() => evalIn(win, `(() => {
    const ui = window.__vvdb().ui;
    const t = ui.tabs.find(x => x.id === ui['active-tab']);
    return ui.tabs.length === ${diffNavBefore.tabCount + 1}
      && ui['active-tab'] === ${diffNavBefore.active}
      && t && t.uri === ${JSON.stringify(diffPath)}
      && ui.tabs.some(x => x.uri === ${JSON.stringify(greetTarget)});
  })()`), 'Ctrl+click on a resolved diff filename opens it in a BACKGROUND tab (active unchanged)', 8000);
  // close that background tab by MIDDLE-CLICKING its strip entry (ADR-0044's tab-close gesture), which
  // must leave the still-active diff tab exactly where it was
  // NB: a tab's title is uri/display — "file://<path>" for a local file — so match by containment, and
  // WAIT for the strip to catch up: the db-state waitFor above returns a frame before Reagent renders.
  await middleClickTab(win, greetTarget);
  await waitFor(() => evalIn(win, `(() => {
    const ui = window.__vvdb().ui;
    const t = ui.tabs.find(x => x.id === ui['active-tab']);
    return ui.tabs.length === ${diffNavBefore.tabCount}
      && ui['active-tab'] === ${diffNavBefore.active}
      && t && t.uri === ${JSON.stringify(diffPath)}
      && Boolean(document.querySelector('.vv-diff-file-path[data-vv-diff-path="src/greet.js"][href]'));
  })()`), 'middle-clicking the background tab closes it; the active diff tab is undisturbed', 8000);
  assert.ok((await detailsState()).every(d => d.open), 'following the nested filename link did not toggle collapse');
  await evalIn(win, `document.querySelector('.vv-diff-file-path[data-vv-diff-path="src/greet.js"][href]').click()`);
  await waitFor(() => evalIn(win, `(() => {
    const ui = window.__vvdb().ui;
    const t = ui.tabs.find(x => x.id === ui['active-tab']);
    return t && t.uri === ${JSON.stringify(greetTarget)};
  })()`), 'left-click on a resolved diff filename replaces the current view', 8000);
  console.log('[ok] diff filename links navigate current/background tabs without collapsing their file');

  // ── Settings ▸ File Type (ADR-0036): the menu re-types the SHOWN document in place ──
  // Drives the REAL menu DOM (Settings → File Type flyout → row click) so the whole renderer half is
  // exercised: menus → :doc/set-file-type → :vv/set-file-type → vv:set-file-type → the re-sent payload's
  // kind re-renders the pane. The fixture is diff-shaped text in a .txt, the exact re-type use case.
  {
    const ftDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-filetype-'));
    const ftPath = path.join(ftDir, 'pasted-diff.txt');
    const ftText = 'diff --git a/y.js b/y.js\n--- a/y.js\n+++ b/y.js\n@@ -1 +1 @@\n-const OLD = 1;\n+const NEW = 2;\n';
    fs.writeFileSync(ftPath, ftText);
    state.contentByPath.set(ftPath, {
      path: ftPath, kind: 'text', text: ftText, sourceable: true, meta: { size: ftText.length }
    });
    await evalIn(win, `window.__vvopen(${JSON.stringify(ftPath)})`);
    await waitFor(
      () => evalIn(win, `(document.querySelector('.vv-content pre.vv-plain')?.textContent || '').includes('diff --git')`),
      'the .txt fixture to open as LITERAL plain text (never delimiter-aligned)', 8000
    );

    // open Settings → hover File Type → its flyout lists the kinds, a separator, and the grammar languages
    const openFileTypeMenu = async () => {
      await evalIn(win, `(() => {
        const label = Array.from(document.querySelectorAll('.vv-menubar .vv-menu-label'))
          .find((el) => el.textContent.trim().startsWith('Settings'));
        label.click();
        return true;
      })()`);
      await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-menu-dropdown'))`),
        'the Settings dropdown to open');
      assert.strictEqual(await hoverMenuItem(win, 'File Type'), true, 'File Type submenu row must exist');
      await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-menu-subdropdown'))`),
        'the File Type flyout to open');
    };
    await openFileTypeMenu();
    const rows = await evalIn(win, `(() => {
      const els = Array.from(document.querySelectorAll('.vv-menu-subdropdown .vv-menu-item-radio'));
      return {
        labels: els.map((el) => el.textContent.trim()),
        selected: els.filter((el) => el.getAttribute('aria-checked') === 'true').map((el) => el.textContent.trim()),
        seps: document.querySelectorAll('.vv-menu-subdropdown .vv-menu-sep').length
      };
    })()`);
    assert.ok(rows.labels.length > 40, `the File Type flyout lists kinds + every grammar language (got ${rows.labels.length})`);
    for (const want of ['Plain Text', 'Markdown', 'Diff / Patch', 'Delimited Table', 'Source (auto)', 'Python', 'Rust', 'C++']) {
      assert.ok(rows.labels.some((l) => l.includes(want)), `the flyout offers "${want}"`);
    }
    assert.ok(rows.seps >= 1, 'a separator divides the kinds from the languages');
    assert.ok(rows.selected.some((l) => l.includes('Plain Text')), 'the current kind (Plain Text) is radio-marked');

    // pick "Diff / Patch" → the same file re-renders through the diff pipeline
    await evalIn(win, `(() => {
      Array.from(document.querySelectorAll('.vv-menu-subdropdown .vv-menu-item-radio'))
        .find((el) => el.textContent.includes('Diff / Patch')).click();
      return true;
    })()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body .vv-diff-line'))`),
      'the re-typed document to render through the diff pipeline', 8000);
    assert.deepStrictEqual(state.fileTypeRequests[state.fileTypeRequests.length - 1],
      { path: ftPath, kind: 'diff' },
      'the menu pick names the shown file and the chosen kind over vv:set-file-type');
    console.log('[ok] Settings ▸ File Type re-types a .txt as a diff in place (menu → IPC → re-render)');

    // pick a LANGUAGE ("Python") → source kind + the explicit grammar (the extensionless/piped-doc case)
    await openFileTypeMenu();
    await evalIn(win, `(() => {
      Array.from(document.querySelectorAll('.vv-menu-subdropdown .vv-menu-item-radio'))
        .find((el) => el.textContent.trim() === 'Python').click();
      return true;
    })()`);
    await waitCalm(win, `Boolean(document.querySelector('.vv-content .vv-source'))`,
      'the source view to mount for the language-typed doc');
    assert.deepStrictEqual(state.fileTypeRequests[state.fileTypeRequests.length - 1],
      { path: ftPath, kind: 'source', language: 'python' },
      'a language pick sends kind source + the resolved grammar id');
    // …and the radio now marks the explicit language, not Source (auto)
    await openFileTypeMenu();
    const marked = await evalIn(win, `(() => {
      return Array.from(document.querySelectorAll('.vv-menu-subdropdown .vv-menu-item-radio'))
        .filter((el) => el.getAttribute('aria-checked') === 'true').map((el) => el.textContent.trim());
    })()`);
    assert.ok(marked.some((l) => l === 'Python'), `the explicit language is radio-marked (got ${JSON.stringify(marked)})`);
    assert.ok(!marked.some((l) => l.includes('Source (auto)')), 'Source (auto) is NOT marked when a language override is set');
    await evalIn(win, `(() => { document.querySelector('.vv-menu-overlay')?.click(); return true; })()`);
    console.log('[ok] Settings ▸ File Type language pick renders highlighted source with the chosen grammar');
    fs.rmSync(ftDir, { recursive: true, force: true });
  }

  // ── Remote SSH (ADR-0027): open a remote file + browse a remote directory against a hermetic in-process SFTP
  //    fixture, and exercise the SSH auth-prompt modal. Configures the REAL transport (agent off, temp home,
  //    auto-accept host key, fixed password) so vv:open → openRemoteUri authenticates with no live host. ──
  {
    const sshHome = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-esmoke-sshhome-'));
    fs.mkdirSync(path.join(sshHome, '.ssh'), { recursive: true });
    sshTransport.configure({ homeDir: sshHome, agentSock: '', systemConfigPath: '', systemKnownHostsPath: '',
      promptHostKey: async () => true, promptSecret: async () => 'pw', onError: () => {} });
    const sshSrv = await startSftpServer({ password: 'pw', files: {
      'notes.md': '# Remote Doc\nhello over ssh',
      'sub/one.txt': 'one', 'sub/two.txt': 'two',
    } });

    await evalIn(win, `window.__vvopen(${JSON.stringify(sshSrv.url('/notes.md'))})`);
    await waitFor(() => evalIn(win, `(() => { const b = document.querySelector('.vv-content .markdown-body'); return Boolean(b) && /hello over ssh/.test(b.textContent); })()`),
      'a remote ssh:// markdown file renders in the GUI', 12000);
    console.log('[ok] a remote ssh:// markdown file renders in the GUI');

    await evalIn(win, `window.__vvopen(${JSON.stringify(sshSrv.url('/sub'))})`);
    await waitFor(() => evalIn(win, `(() => { const t = document.querySelector('.vv-content').textContent; return /one\\.txt/.test(t) && /two\\.txt/.test(t); })()`),
      'a remote ssh:// directory lists its entries in-pane', 12000);
    console.log('[ok] a remote ssh:// directory browses in the in-pane file list');

    // The header must FLOAT and the pane must be edge-to-edge: a top gutter on the scroller would make the
    // top:0 sticky header float below it, showing scrolled rows in the gap above. Assert the gutter is dropped
    // for a directory and the header block is sticky (deterministic — no flaky scroll geometry).
    const dirChrome = await evalIn(win, `(() => {
      const cs = getComputedStyle(document.querySelector('.vv-content'));
      const hd = document.querySelector('.vv-fb-header');
      return { padTop: cs.paddingTop, padLeft: cs.paddingLeft, headerPos: hd ? getComputedStyle(hd).position : null };
    })()`);
    assert.strictEqual(dirChrome.padTop, '0px', 'the directory pane drops the top gutter so the sticky header sits flush');
    assert.strictEqual(dirChrome.padLeft, '0px', 'the directory pane is edge-to-edge (rows carry their own inset)');
    assert.strictEqual(dirChrome.headerPos, 'sticky', 'the Name/Size/Modified header block floats (position: sticky)');
    console.log('[ok] the directory listing header floats flush (edge-to-edge, sticky header block)');

    // The pinned ".." parent row: present for a subdirectory (with the parent authority-path kept), navigates up
    // one directory when activated, and is absent at the remote root (uri/dirname → nil, so nothing to go up to).
    const upAtSub = await evalIn(win, `(() => {
      const up = document.querySelector('.vv-fb-up');
      return up ? { present: true, name: (up.querySelector('.vv-fb-name') || {}).textContent, dataPath: up.getAttribute('data-path') } : { present: false };
    })()`);
    assert.ok(upAtSub.present && upAtSub.name === '..', 'a subdirectory listing shows a pinned ".." parent row');
    assert.strictEqual(upAtSub.dataPath, sshSrv.url('/'), 'the ".." row targets the parent directory (remote root, authority kept)');
    // activate the ".." row with the platform's open gesture (single-click on Linux, double-click elsewhere)
    const upGesture = (process.platform === 'linux') ? 'click' : 'dblclick';
    await evalIn(win, `(() => { const up = document.querySelector('.vv-fb-up'); up.dispatchEvent(new MouseEvent(${JSON.stringify(upGesture)}, { bubbles: true, cancelable: true })); })()`);
    await waitFor(() => evalIn(win, `(() => { const t = document.querySelector('.vv-content').textContent;
      return /notes\\.md/.test(t) && /sub/.test(t) && document.querySelector('.vv-fb-up') === null; })()`),
      'activating ".." navigates up to the parent listing, where no ".." remains (the remote root has no parent)', 12000);
    console.log('[ok] the ".." parent row lists, navigates up one directory, and hides at the root');

    // SSH auth-prompt modal: main pushes a (non-secret) prompt request → the modal appears → OK sends the secret
    state.sshReply = null;
    win.webContents.send('vv:ssh-prompt', { promptId: 'smoke1', kind: 'password', host: 'demo.example', user: 'alice', attempt: 1 });
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-ssh-prompt .vv-ssh-input'))`), 'the SSH auth-prompt modal appears', 8000);
    // the prompt is a modal dialog that owns the keyboard (vinary.app.overlay): keymap chords, menubar access keys and
    // document scrolling must not act behind it — before the predicates were unified it was in none of their lists
    const tabsBefore = await evalIn(win, `window.__vvdb().ui.tabs.length`);
    // make the pane scrollable for the duration, so "PageDown scrolls nothing behind it" can actually fail
    await evalIn(win, `(() => { const sp = document.createElement('div'); sp.id = 'vv-smoke-scroll-spacer';
      sp.style.height = '6000px'; document.querySelector('.vv-content').appendChild(sp); return true; })()`);
    const scrollBefore = await evalIn(win, `document.querySelector('.vv-content').scrollTop`);
    // focus INSIDE the prompt: the menubar listens in the capture phase, ahead of the modal's own key handling
    await sendChord(win, 'F', ['alt']);
    // focus ESCAPED from the panel: now only the shared predicates stand between a key and the document
    await evalIn(win, `document.activeElement && document.activeElement.blur(); true`);
    await sendChord(win, 'T', ['control']);
    await sendChord(win, 'PageDown');
    await delay(300);
    const behind = await evalIn(win, `({ tabs: window.__vvdb().ui.tabs.length, menu: window.__vvdb().ui.menu,
      scroll: document.querySelector('.vv-content').scrollTop, prompt: Boolean(document.querySelector('.vv-ssh-prompt')),
      webHidden: window.__vvdb().ui['ssh-prompt'] != null })`);
    assert.deepStrictEqual({ tabs: behind.tabs, menu: behind.menu, scroll: behind.scroll, prompt: behind.prompt },
      { tabs: tabsBefore, menu: null, scroll: scrollBefore, prompt: true },
      'with the SSH prompt open, Ctrl+T opens no tab, Alt+F opens no menu, PageDown scrolls nothing behind it');
    await evalIn(win, `(() => { document.getElementById('vv-smoke-scroll-spacer').remove();
      document.querySelector('.vv-ssh-input').focus(); return true; })()`);
    await evalIn(win, `(() => {
      const i = document.querySelector('.vv-ssh-input');
      // React controlled input: set via the native value setter so React/reagent's onChange fires
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(i, 'hunter2'); i.dispatchEvent(new Event('input', { bubbles: true }));
      const ok = Array.from(document.querySelectorAll('.vv-ssh-prompt button')).find((b) => b.textContent.trim() === 'OK');
      ok.click();
    })()`);
    await waitFor(() => state.sshReply && state.sshReply.promptId === 'smoke1', 'the SSH prompt reply reaches main', 8000);
    assert.strictEqual(state.sshReply.secret, 'hunter2', 'the typed secret is sent back over vv:ssh-prompt-reply');
    await waitFor(() => evalIn(win, `!document.querySelector('.vv-ssh-prompt')`), 'the SSH prompt modal closes after OK', 8000);
    console.log('[ok] the SSH auth-prompt modal collects a secret and replies to main');

    sshTransport.closeAll();
    await sshSrv.close();
    fs.rmSync(sshHome, { recursive: true, force: true });
  }

  // restore the PDF as the active view — this diff test navigated the tab away from it, and the PDF
  // selection / hit-test / find steps below operate on the pdf.js view.
  state.diffSources = null;
  state.diffTargets = null;
  await evalIn(win, `window.__vvopen(${JSON.stringify(pdfFixture)})`);
  win.webContents.send('vv:content', { path: pdfFixture, kind: 'pdf', bytes: new Uint8Array(fs.readFileSync(pdfFixture)), stamp: Date.now() });
  await waitFor(
    () => evalIn(win, `(() => { const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas'); return Boolean(c) && c.getBoundingClientRect().width > 0; })()`),
    'PDF view restored after the diff test', 15000
  );
  // the text layer builds asynchronously AFTER the canvas — the PDF selection/hit-test steps below need it
  await waitFor(
    () => evalIn(win, `document.querySelectorAll('.vv-pdf-text span').length > 0`),
    'PDF text layer restored after the diff test', 15000
  );

  // selection → Ctrl+C copies the PDF text (copy parity with markdown/source)
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const layer = document.querySelector('.vv-pdf-text');
    const range = document.createRange();
    range.selectNodeContents(layer);
    const sel = window.getSelection();
    sel.removeAllRanges(); sel.addRange(range);
    return sel.toString();
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C must be handled for a PDF selection');
  await waitFor(() => state.lastCopiedText && state.lastCopiedText.includes('Vinary'), 'PDF selection Ctrl+C clipboard write');
  console.log('[ok] PDF text selection copies via Ctrl+C');

  // Hit-test: mouse-drag selection requires the TEXT layer to be the topmost element at a text span's
  // position. The link overlay (.vv-pdf-anno, z-index 2) covers the whole page ABOVE the text layer, so it
  // must be pointer-transparent — else it swallows the drag and text can't be selected (the copy test above
  // uses a PROGRAMMATIC range, which needs no pointer events, so it never caught this). elementFromPoint at a
  // span's center must resolve into .vv-pdf-text, NOT .vv-pdf-anno / the canvas.
  // close any menu left open by an earlier step — its full-screen .vv-menu-overlay (z-index 49) would sit
  // over the page and defeat the hit-test (the find/copy tests above don't care: no pointer events needed).
  // Click the overlay (its own on-click closes the menu) — a DOM action that works in the dev AND release
  // (:simple) builds, unlike a re-frame-global dispatch which only the dev build exposes.
  await evalIn(win, `(() => { const o = document.querySelector('.vv-menu-overlay'); if (o) o.click(); return true; })()`);
  await waitFor(() => evalIn(win, `!document.querySelector('.vv-menu-overlay')`), 'no menu overlay before the PDF hit-test');
  const hitTest = await evalIn(win, `(() => {
    // a real, laid-out glyph span: non-empty, not the zero-height marked-content marker, real box
    const span = Array.from(document.querySelectorAll('.vv-pdf-text span'))
      .find(s => s.textContent.trim().length > 0 && !s.classList.contains('markedContent')
                 && s.getBoundingClientRect().width > 1 && s.getBoundingClientRect().height > 1);
    if (!span) return { ok: false, reason: 'no laid-out text span' };
    const r = span.getBoundingClientRect();
    const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return {
      ok: Boolean(hit && hit.closest('.vv-pdf-text')),
      inAnno: Boolean(hit && hit.closest('.vv-pdf-anno')),
      hitClass: hit ? (hit.className || hit.tagName.toLowerCase()) : null,
      annoPE: getComputedStyle(document.querySelector('.vv-pdf-anno')).pointerEvents
    };
  })()`);
  assert.strictEqual(hitTest.inAnno, false, 'link overlay must not intercept pointer events over text (pointer-events:none)');
  assert.strictEqual(hitTest.annoPE, 'none', '.vv-pdf-anno container must be pointer-transparent');
  assert.strictEqual(hitTest.ok, true, `a text span must be the topmost hit target for drag-selection (got .${hitTest.hitClass})`);
  console.log('[ok] PDF text layer is the topmost hit target — drag-selection reaches it (link overlay is click-through)');

  // in-page find covers the PDF text layer (materialized on find)
  await evalIn(win, `window.getSelection().removeAllRanges()`);
  await dispatchWindowKey(win, 'f', { ctrlKey: true });
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'find bar opens over PDF');
  await evalIn(win, `(() => {
    const i = document.querySelector('.vv-find-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'Smoke'); i.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.find.count > 0`), 'PDF find matches', 8000);
  await evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
    i && i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
  console.log('[ok] in-page find matches PDF text');

  // --- opt-in PDF reflow (ADR-0017): "Reflow Text" swaps the fixed-layout canvas for the extracted text as
  // reflowable prose (an additive facet — the canvas render is untouched). smoke.pdf is the active PDF here
  // and carries text. Dev-only: it dispatches through the re-frame global, which the release :simple build
  // encapsulates (release still covers the reflow-ir transform via the DOM-free unit tests).
  if (!releaseBuild) {
    const isReflowOn = () => evalIn(win, `(() => { const p = (((window.__vvdb()||{}).ui||{}).pdf)||{}; return Boolean(p["reflow?"]); })()`);
    if (await isReflowOn())   // a prior run may have persisted it on — normalize to OFF first
      await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("pdf", "reflow-toggle"))); true`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), 'canvas active before reflow');
    await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("pdf", "reflow-toggle"))); true`);
    const reflowedLen = await waitFor(
      () => evalIn(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
                                  return b && b.textContent.trim().length > 0 ? b.textContent.trim().length : null; })()`),
      'PDF reflow renders the extracted text as prose');
    assert.ok(reflowedLen > 0, 'reflow view shows the extracted PDF text');
    assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), false,
      'reflow replaces the canvas while active (additive: the canvas returns when toggled off)');
    await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("pdf", "reflow-toggle"))); true`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), 'canvas PDF view returns after reflow off');
    console.log('[ok] PDF reflow — extracted text renders as reflowable prose; canvas returns when off');
  }

  // Fix 2 — the PDF pane is edge-to-edge (drops the 45px document reading gutter)
  const pdfEdge = await evalIn(win, `(() => {
    const c = document.querySelector('.vv-content');
    return { flush: c.classList.contains('vv-content-pdf-flush'), pad: getComputedStyle(c).paddingLeft };
  })()`);
  assert.strictEqual(pdfEdge.flush, true, 'PDF content pane must be edge-to-edge (.vv-content-pdf-flush)');
  assert.strictEqual(pdfEdge.pad, '0px', 'edge-to-edge PDF pane must drop the reading gutter');
  console.log('[ok] PDF pane is edge-to-edge');

  // Fix 7 — switching away from the PDF tab and back must re-render. pdf.js detaches the byte buffer on
  // load; we now clone it, so the cached bytes survive a remount. Open a blank tab (PDF unmounts), close
  // it (PDF tab reactivates → remount from the cached bytes), and assert the canvas re-renders.
  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `!document.querySelector('.vv-pdf-canvas')`), 'PDF unmounts when switched away');
  await sendChord(win, 'W', ['control']);
  await waitFor(
    () => evalIn(win, `(() => { const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas'); return Boolean(c) && c.getBoundingClientRect().width > 0; })()`),
    'PDF re-renders after returning to its tab', 15000);
  console.log('[ok] PDF re-renders after switching tabs away and back');

  // Bug A (round 4) — a zoom rescale clears + re-renders; the visible page must not be left blank (A2
  // re-asserts visible renders deterministically rather than relying on an IntersectionObserver re-fire)
  await sendChord(win, '=', ['control']);   // Ctrl+= zoom in → :view/zoom → pdf rescale
  await waitFor(() => evalIn(win, `(() => { const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas'); return Boolean(c) && c.getBoundingClientRect().width > 0 && c.getBoundingClientRect().height > 0; })()`),
    'PDF page canvas survives a zoom rescale (re-rendered, not left blank)', 8000);
  await sendChord(win, '0', ['control']);   // reset zoom for the following tests
  await delay(120);
  console.log('[ok] PDF page stays rendered through a zoom rescale');

  // ── PDF Contents scroll-spy — the generalized follow must TRACK a bookmarked PDF's sections on scroll ──
  // (the reported bug: PDF sections were listed but the reading position was never followed). Load a 2-page
  // OUTLINED PDF, confirm its outline fills the Contents, then scroll to the bottom / top and assert the
  // active section FOLLOWS (page-2 then page-1 bookmarks) and the active row is highlighted. Exercises
  // pdf.cljs's outline → :toc-ids → toc/refresh! wiring and the content-agnostic spy (toc.cljs) end-to-end.
  // The PDF is the active view here (before the Bug A/B hint tests, which inject their own link and don't
  // depend on smoke.pdf's content). Uses waitCalm because the outline resolves asynchronously and a scrolled
  // page rasterizes on the main thread, briefly deferring the sidebar re-render.
  const outlinePdfPath = path.join(ROOT, 'test', 'fixtures', 'outline-smoke.pdf'); // virtual path; bytes below
  state.contentByPath.set(outlinePdfPath, {
    path: outlinePdfPath, kind: 'pdf', bytes: new Uint8Array(makeOutlinePdf()), stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [outlinePdfPath] });
  await evalIn(win, `(() => { const ui = window.__vvdb().ui;
    if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'contents')
      document.querySelector('[data-vv-rail="contents"]').click();
    return true; })()`);
  await delay(1500);   // let the pdf-view remount + first render settle before probing (avoids tight polling mid-remount)
  await waitCalm(win, `document.querySelectorAll('.vv-toc-item').length === 3`,
    'the PDF outline fills the Contents (Section One, Subsection A, Section Two)');
  // scroll to the bottom → the page-2 section becomes active AND its Contents row is highlighted
  await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); c.scrollTop = Math.max(0, c.scrollHeight - c.clientHeight); c.dispatchEvent(new Event('scroll', { bubbles: true })); return true; })()`);
  await waitCalm(win, `String(window.__vvdb().ui['active-heading']) === 'vv-pdf-page-2' && Boolean(document.querySelector('.vv-toc-item.vv-toc-active'))`,
    'scrolling to the bottom follows to the page-2 bookmark and highlights its row', 15000);
  // scroll back to the top → the page-1 section becomes active (follows both directions)
  await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); c.scrollTop = 0; c.dispatchEvent(new Event('scroll', { bubbles: true })); return true; })()`);
  await waitCalm(win, `String(window.__vvdb().ui['active-heading']) === 'vv-pdf-page-1'`,
    'scrolling back to the top follows to the page-1 bookmark');
  console.log('[ok] PDF Contents scroll-spy follows both scroll directions + highlights the active section');

  // ── PDF Contents auto-numbering — unnumbered outline bookmarks must show derived hierarchical numbers ──
  // (pdf-layout/number-outline): Section One / Subsection A / Section Two → 1, 1.1, 2.
  assert.strictEqual(
    await evalIn(win, `JSON.stringify(Array.from(document.querySelectorAll('.vv-toc-item .vv-toc-num')).map((s) => s.textContent))`),
    JSON.stringify(['1', '1.1', '2']),
    'the PDF Contents must show derived section numbers (1, 1.1, 2) from the unnumbered outline');
  console.log('[ok] PDF Contents auto-numbers the unnumbered outline (1, 1.1, 2)');

  // ── Menu bar must NOT move when clicking a PDF Contents section ──
  // Root cause: el.scrollIntoView({block:"start"}) aligns the target to the top of EVERY scrollable ancestor,
  // so when #app has scroll-range (a tall document can create it) it scrolls #app, pushing the menu bar out of
  // #app's clipped viewport. The confined .scrollTo (the fix) scrolls only .vv-content and can never touch
  // #app. To make this a real regression test — one the OLD scrollIntoView would FAIL — we deterministically
  // give #app scroll-range with a temporary spacer (the bug's precondition), click the last section, and
  // assert the content pane scrolled while #app + the menu bar did NOT.
  const appRange = await evalIn(win, `(() => {
    const app = document.getElementById('app');
    const sp = document.createElement('div'); sp.id = '__vv_menubar_spacer'; sp.style.height = '3000px';
    app.appendChild(sp); app.scrollTop = 0;
    return app.scrollHeight - app.clientHeight;   // #app now has real scroll-range (the bug's precondition)
  })()`);
  assert.ok(appRange > 0, 'test precondition: the spacer gives #app scroll-range');
  const menubarTop0 = await evalIn(win, `document.querySelector('.vv-menubar').getBoundingClientRect().top`);
  await evalIn(win, `(() => { const rows = document.querySelectorAll('.vv-toc-item'); rows[rows.length - 1].click(); return true; })()`);
  await waitCalm(win, `document.querySelector('.vv-content').scrollTop > 50`,
    'clicking a PDF Contents section scrolls the content pane', 10000);
  const chrome = JSON.parse(await evalIn(win, `(() => { const a = document.getElementById('app');
    return JSON.stringify({ menubarTop: document.querySelector('.vv-menubar').getBoundingClientRect().top,
                            appScroll: a.scrollTop, rootScroll: document.scrollingElement.scrollTop }); })()`));
  await evalIn(win, `(() => { const s = document.getElementById('__vv_menubar_spacer'); if (s) s.remove();
    document.getElementById('app').scrollTop = 0; return true; })()`);   // restore layout for later tests
  assert.strictEqual(chrome.appScroll, 0, 'clicking a PDF Contents section must NOT scroll #app (the old scrollIntoView did → menu bar vanished)');
  assert.strictEqual(chrome.menubarTop, menubarTop0, 'the menu bar must not move when clicking a PDF Contents section');
  assert.strictEqual(chrome.rootScroll, 0, 'the document root must not scroll either');
  console.log('[ok] PDF Contents click scrolls the pane, not the chrome — menu bar stays put even with #app scroll-range');

  // ── PDF live refresh IN PLACE (ADR-0045 §Live refresh) ──
  // content-view keys the pdf-view by tab + path, so a re-sent PDF (a watcher / daemon refresh) swaps the
  // document INSIDE the mounted view: the .vv-pdf-doc element survives (no remount → no blank flash), the
  // reading position is kept, and the placeholders grow with the new page count. A refresh whose bytes do not
  // open (read while pdflatex was still writing) keeps the last good render behind a non-fatal note; the next
  // good refresh replaces it and clears the note.
  {
    const { makeDeckPdf, truncatePdf } = require('./fixtures/pdf-gen');
    const livePath = path.join(ROOT, 'test', 'fixtures', 'live-refresh.pdf');   // virtual path; bytes below
    // tall portrait pages, a non-Beamer producer → an ordinary document (never the slides layout)
    const version = (pages) => makeDeckPdf({ pages, sizes: Array.from({ length: pages }, () => [300, 800]),
      labels: null, creator: 'live-refresh smoke', producer: 'smoke' });
    const t0 = Date.now();
    state.contentByPath.set(livePath, { path: livePath, kind: 'pdf', bytes: new Uint8Array(version(6)), stamp: t0 });
    win.webContents.send('vv:open-files', { paths: [livePath] });
    await waitCalm(win, `document.querySelectorAll('.vv-pdf-doc .vv-pdf-page').length === 6
      && Boolean(document.querySelector('.vv-pdf-doc .vv-pdf-sheet canvas.vv-pdf-canvas'))`,
      'the 6-page live-refresh fixture renders', 15000);
    await evalIn(win, `(() => { const c = document.querySelector('.vv-content');
      document.querySelector('.vv-pdf-doc').__vvLiveMark = 'mounted-once';
      c.scrollTop = 1200; c.dispatchEvent(new Event('scroll', { bubbles: true })); return true; })()`);
    await delay(300);
    const before = await evalIn(win, `document.querySelector('.vv-content').scrollTop`);

    // every refresh is stamped AFTER the version the stub delivered on open (its stamp is taken when the renderer's
    // vv:open arrives, which can be the very millisecond after t0 — an equal stamp would not be a refresh)
    const delivered = state.sentStamps.get(livePath);
    assert.ok(Number.isFinite(delivered), 'the stub recorded the delivered stamp');

    // 1 — a good refresh with one more page swaps in place
    win.webContents.send('vv:content', { path: livePath, kind: 'pdf', bytes: new Uint8Array(version(7)), stamp: delivered + 1 });
    await waitCalm(win, `document.querySelectorAll('.vv-pdf-doc .vv-pdf-page').length === 7`,
      'the refreshed document (7 pages) is swapped in', 15000);
    const afterGood = await evalIn(win, `(() => ({
      same: document.querySelector('.vv-pdf-doc').__vvLiveMark === 'mounted-once',
      top: document.querySelector('.vv-content').scrollTop,
      note: Boolean(document.querySelector('.vv-refresh-note')) }))()`);
    assert.strictEqual(afterGood.same, true, 'a live refresh must update the mounted PDF view in place (no remount)');
    assert.ok(Math.abs(afterGood.top - before) <= 2, `the reading position survives the refresh (${before} → ${afterGood.top})`);
    assert.strictEqual(afterGood.note, false, 'a good refresh shows no note');

    // 2 — a torn refresh (half a file) keeps the last good render + raises the non-fatal note
    win.webContents.send('vv:content', { path: livePath, kind: 'pdf', bytes: new Uint8Array(truncatePdf(version(8), 0.5)), stamp: delivered + 2 });
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-refresh-note[data-kind="render-failed"]'))`),
      'a refresh that cannot be opened raises the render-failed note', 10000);
    const afterTorn = await evalIn(win, `(() => ({
      pages: document.querySelectorAll('.vv-pdf-doc .vv-pdf-page').length,
      canvas: Boolean(document.querySelector('.vv-pdf-doc canvas.vv-pdf-canvas')),
      same: document.querySelector('.vv-pdf-doc').__vvLiveMark === 'mounted-once',
      error: Boolean(document.querySelector('.vv-content .vv-error')) }))()`);
    assert.deepStrictEqual(afterTorn, { pages: 7, canvas: true, same: true, error: false },
      'a torn refresh keeps the last good document on screen (no error page, no remount)');

    // 3 — the next good refresh replaces it and clears the note
    win.webContents.send('vv:content', { path: livePath, kind: 'pdf', bytes: new Uint8Array(version(8)), stamp: delivered + 3 });
    await waitCalm(win, `document.querySelectorAll('.vv-pdf-doc .vv-pdf-page').length === 8
      && !document.querySelector('.vv-refresh-note')`,
      'the next good refresh is shown and clears the note', 15000);

    // 4 — a refresh that LOSES pages, then one that gains them back: both swap in place. The shrink must leave the
    // placeholder vectors growable: it kept cljs Subvec views, which cannot go transient, so every growth after a
    // shrink threw inside replace-doc! and fell back to the last good version (found by remote-pdf-e2e)
    win.webContents.send('vv:content', { path: livePath, kind: 'pdf', bytes: new Uint8Array(version(4)), stamp: delivered + 4 });
    await waitCalm(win, `document.querySelectorAll('.vv-pdf-doc .vv-pdf-page').length === 4`,
      'a refresh that loses pages shrinks in place', 15000);
    win.webContents.send('vv:content', { path: livePath, kind: 'pdf', bytes: new Uint8Array(version(6)), stamp: delivered + 5 });
    await waitCalm(win, `document.querySelectorAll('.vv-pdf-doc .vv-pdf-page').length === 6
      && !document.querySelector('.vv-refresh-note')`,
      'a refresh that regains pages after a shrink grows in place, with no note', 15000);
    assert.strictEqual(await evalIn(win, `document.querySelector('.vv-pdf-doc').__vvLiveMark === 'mounted-once'`), true,
      'shrink-then-grow refreshes still update the same mounted view');
    // back to the 8 pages the following arms start from (a second growth after the shrink)
    win.webContents.send('vv:content', { path: livePath, kind: 'pdf', bytes: new Uint8Array(version(8)), stamp: delivered + 6 });
    await waitCalm(win, `document.querySelectorAll('.vv-pdf-doc .vv-pdf-page').length === 8
      && !document.querySelector('.vv-refresh-note')`, 'the document grows back to 8 pages in place', 15000);
    console.log('[ok] PDF live refresh swaps in place (position kept); a torn refresh keeps the last good render behind a note; shrink then grow refreshes in place');
  }

  // ── a pending refresh never holds the VIEW STATE hostage (ADR-0045 §Live refresh; vinary.ui.pdf-view-lifecycle) ──
  // A refresh is prepared beside the live document and lands WITHOUT a re-render. The old pdf-view chose EITHER the
  // refresh OR the view-state update on each render, so (A) a view-state change rendered while a refresh was pending
  // was dropped until some unrelated change re-rendered the view, and (B) a version that arrived while the FIRST mount
  // was being prepared was never shown. A 1200-page version keeps pdf.js busy for many frames (calibrated: ≈330 ms to
  // prepare in Node alone, more with the renderer's worker), so both windows are wide — and each part ASSERTS that it
  // hit its window, so neither can pass by timing luck.
  {
    const { makeDeckPdf } = require('./fixtures/pdf-gen');
    const portrait = (pages, creator) => makeDeckPdf({ pages, sizes: Array.from({ length: pages }, () => [300, 800]),
      labels: null, creator, producer: 'smoke' });
    const SLOW = 1200;
    const pageCount = `document.querySelectorAll('.vv-content .vv-pdf-doc .vv-pdf-page').length`;
    const inverted = `Boolean(document.querySelector('.vv-content .vv-pdf-doc.vv-pdf-invert'))`;

    // A — dark-invert turned on while a refresh is being prepared
    const livePath = path.join(ROOT, 'test', 'fixtures', 'live-refresh.pdf');
    assert.strictEqual(await evalIn(win, pageCount), 8, 'the live-refresh document is shown (8 pages)');
    assert.strictEqual(await evalIn(win, `Boolean(window.__vvdb().ui.pdf['invert?'])`), false, 'dark-invert starts off');
    // when the setting ARRIVES (the app's own listener has only queued its event), note the page count; three frames
    // later its render has run — Reagent flushes on the first animation frame after app-db changes — so a refresh
    // still pending then proves the change rendered while the refresh was pending
    await evalIn(win, `(() => { const probe = window.__vvInvertProbe = {};
      window.__vvInvertOff = window.vv.onSettings(() => {
        if (probe.atArrival) return;
        probe.atArrival = { pages: ${pageCount} };
        let frames = 0;
        const tick = () => { frames += 1;
          if (frames < 3) { requestAnimationFrame(tick); return; }
          probe.afterRender = { pages: ${pageCount}, inverted: ${inverted} }; };
        requestAnimationFrame(tick); });
      return true; })()`);
    // the slow version and the setting go out back to back: the setting lands with (or just after) the new stamp,
    // long before pdf.js has prepared 1200 pages
    const stampA = state.sentStamps.get(livePath) + 4;
    win.webContents.send('vv:content', { path: livePath, kind: 'pdf', bytes: new Uint8Array(portrait(SLOW, 'slow refresh')), stamp: stampA });
    win.webContents.send('vv:settings', '{:pdf-invert? true}');
    const probeA = await waitFor(() => evalIn(win, `window.__vvInvertProbe.afterRender ? window.__vvInvertProbe : null`),
      'the invert probe records its render', 8000);
    await evalIn(win, `(window.__vvInvertOff(), delete window.__vvInvertOff, delete window.__vvInvertProbe, true)`);
    assert.deepStrictEqual(probeA, { atArrival: { pages: 8 }, afterRender: { pages: 8, inverted: true } },
      'dark-invert applies on its own render while the refresh is still being prepared (a pending refresh must not defer it)');
    await waitCalm(win, `${pageCount} === ${SLOW}`, 'the slow refresh lands in place', 30000);
    assert.strictEqual(await evalIn(win, inverted), true, 'the refreshed document is still inverted');
    win.webContents.send('vv:settings', '{:pdf-invert? false}');
    await waitCalm(win, `!${inverted}`, 'dark-invert off again', 4000);

    // B — a newer version that arrives while the FIRST mount is still being prepared
    const racePath = path.join(ROOT, 'test', 'fixtures', 'first-mount-race.pdf');   // virtual path; bytes below
    state.contentByPath.set(racePath, { path: racePath, kind: 'pdf', bytes: new Uint8Array(portrait(SLOW, 'slow first mount')), stamp: Date.now() });
    // for each version of this path that arrives, what the NEW view showed at that moment; for the second, how many
    // frames later the first version's pages appeared. The previous tab's view stays in the DOM for a frame after the
    // new tab activates, so every view present now is marked and the probes only ever look at an unmarked one
    await evalIn(win, `(() => { const seen = window.__vvRace = [];
      document.querySelectorAll('.vv-pdf-doc').forEach((el) => { el.__vvBeforeRace = true; });
      const fresh = () => { const el = document.querySelector('.vv-content .vv-pdf-doc');
        return el && !el.__vvBeforeRace ? el : null; };
      window.__vvRaceFresh = fresh;
      window.__vvRaceOff = window.vv.onContent((p) => {
        if (p.path !== ${JSON.stringify(racePath)}) return;
        const el = fresh();
        const rec = { doc: Boolean(el), pages: el ? el.querySelectorAll('.vv-pdf-page').length : 0 };
        seen.push(rec);
        if (seen.length !== 2) return;
        let frames = 0;
        const tick = () => { frames += 1; const now = fresh();
          if ((now && now.querySelectorAll('.vv-pdf-page').length > 0) || frames >= 900) { rec.framesUntilPages = frames; return; }
          requestAnimationFrame(tick); };
        requestAnimationFrame(tick); });
      return true; })()`);
    win.webContents.send('vv:open-files', { paths: [racePath] });
    // the NEW view exists — so the first version has arrived and its mount is preparing 1200 pages…
    await evalIn(win, `new Promise((done, fail) => { const t0 = performance.now();
      const look = () => {
        if (window.__vvRaceFresh() && window.__vvRace.length === 1) return done(true);
        if (performance.now() - t0 > 15000) return fail(new Error('the race document never started mounting'));
        requestAnimationFrame(look); };
      look(); })`);
    // …when a newer, 3-page version arrives
    const stampB = state.sentStamps.get(racePath) + 1;
    win.webContents.send('vv:content', { path: racePath, kind: 'pdf', bytes: new Uint8Array(portrait(3, 'raced first mount')), stamp: stampB });
    const race = await waitFor(() => evalIn(win, `window.__vvRace.length === 2 && window.__vvRace[1].framesUntilPages !== undefined
      ? window.__vvRace : null`), 'the race probe sees the first version mount', 30000);
    await evalIn(win, `(window.__vvRaceOff(), delete window.__vvRaceOff, delete window.__vvRace, delete window.__vvRaceFresh, true)`);
    assert.strictEqual(race[1].doc, true, 'the newer version arrived after the view had started its first mount');
    assert.strictEqual(race[1].pages, 0, 'the newer version arrived before the first mount landed');
    assert.ok(race[1].framesUntilPages >= 4 && race[1].framesUntilPages < 900,
      `the first mount landed only after the newer version had rendered (${race[1].framesUntilPages} frames later)`);
    await waitCalm(win, `${pageCount} === 3`, 'the version that raced the first mount is shown, in place', 20000);
    console.log(`[ok] PDF view state is never deferred by a pending refresh (invert applied while one was pending); a version that raced the first mount is shown (mount landed ${race[1].framesUntilPages} frames after it)`);
  }

  // ── the ORDERING GUARD (ADR-0046; vinary.app.content-guard): main numbers every read of a path (`seq`), and a
  // window drops a payload whose number is not newer than the one its document applied — an older read (or an
  // older read's failure) can never cover a newer version. Payloads main did not number (a web-linked PDF, this
  // stub's own replies) always apply.
  {
    const guardPath = path.join(ROOT, 'test', 'fixtures', 'ordering-guard.txt');   // virtual path; text below
    state.contentByPath.set(guardPath, { path: guardPath, kind: 'text', text: 'guard-unnumbered', stamp: Date.now() });
    const shown = `(document.querySelector('.vv-content .markdown-body')?.textContent || '')`;
    await evalIn(win, `window.__vvopen(${JSON.stringify(guardPath)})`);
    await waitCalm(win, `${shown}.includes('guard-unnumbered')`, 'the guard document opens (unnumbered)', 8000);
    // a deterministic barrier: main → renderer IPC is one ordered pipe and re-frame runs events FIFO, so once a
    // settings marker sent AFTER a payload is in app-db, that payload's event has been handled; two frames later
    // any render it caused has painted
    let marker = 0;
    const barrier = async () => {
      marker += 1;
      win.webContents.send('vv:settings', `{:smoke-barrier ${marker}}`);
      await waitCalm(win, `window.__vvdb().ui.settings['smoke-barrier'] === ${marker}`, `barrier ${marker}`, 4000);
      await evalIn(win, `new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))`);
    };
    const send = (seq, text) => win.webContents.send('vv:content', { path: guardPath, kind: 'text', text, seq, stamp: Date.now() });
    send(7, 'guard-seven');
    await waitCalm(win, `${shown}.includes('guard-seven')`, 'read 7 applies', 8000);
    send(6, 'guard-six');
    await barrier();
    assert.strictEqual(await evalIn(win, `${shown}.includes('guard-seven') && !${shown}.includes('guard-six')`), true,
      'an OLDER read (6 after 7) is dropped');
    win.webContents.send('vv:error', { path: guardPath, message: 'an older read failed', seq: 5 });
    await barrier();
    assert.deepStrictEqual(await evalIn(win, `({ seven: ${shown}.includes('guard-seven'),
      error: Boolean(document.querySelector('.vv-content .vv-error')) })`), { seven: true, error: false },
      'an older read\'s FAILURE (5) is dropped too — no error page over newer content');
    send(7, 'guard-seven-again');
    await barrier();
    assert.strictEqual(await evalIn(win, `${shown}.includes('guard-seven') && !${shown}.includes('again')`), true,
      'the same read delivered twice applies once');
    send(8, 'guard-eight');
    await waitCalm(win, `${shown}.includes('guard-eight')`, 'a newer read (8) applies', 8000);
    win.webContents.send('vv:content', { path: guardPath, kind: 'text', text: 'guard-unnumbered-again', stamp: Date.now() });
    await waitCalm(win, `${shown}.includes('guard-unnumbered-again')`, 'an unnumbered payload always applies', 8000);
    send(9, 'guard-nine');
    await waitCalm(win, `${shown}.includes('guard-nine')`, 'and numbering resumes after it', 8000);
    console.log('[ok] ordering guard: an older read, an older failure and a duplicate are dropped; newer and unnumbered payloads apply');

    // a FAILED READ keeps the last good document (ADR-0046): main's numbered vv:error for a document the window
    // already shows raises the non-fatal note over it — no error page, nothing remounted; Retry re-reads it through
    // the ordinary open flow and the next good read clears the note
    win.webContents.send('vv:error', { path: guardPath, message: 'ENOENT: no such file or directory', seq: 10, refresh: true });
    await waitCalm(win, `Boolean(document.querySelector('.vv-refresh-note[data-kind="refresh-failed"]'))`,
      'a failed read of a shown document raises the refresh note', 8000);
    assert.deepStrictEqual(await evalIn(win, `({ nine: ${shown}.includes('guard-nine'),
      error: Boolean(document.querySelector('.vv-content .vv-error')) })`), { nine: true, error: false },
      'the last good version stays on screen — no error page');
    state.contentByPath.set(guardPath, { path: guardPath, kind: 'text', text: 'guard-restored', stamp: Date.now() });
    await evalIn(win, `document.querySelector('.vv-refresh-note-retry').click(); true`);
    await waitCalm(win, `${shown}.includes('guard-restored') && !document.querySelector('.vv-refresh-note')`,
      'Retry re-reads the file and the good read clears the note', 8000);
    // an UNNUMBERED error is renderer-side (a render that failed), and shows the error page as before
    win.webContents.send('vv:error', { path: guardPath, message: 'a renderer-side failure' });
    await waitCalm(win, `Boolean(document.querySelector('.vv-content .vv-error'))`, 'an unnumbered error shows the error page', 8000);
    // …as does a numbered failure of a document the window never showed (nothing to keep)
    const neverPath = path.join(ROOT, 'test', 'fixtures', 'never-loaded.txt');   // the stub has no content for it
    await evalIn(win, `window.__vvopen(${JSON.stringify(neverPath)})`);
    await waitCalm(win, `(window.__vvdb().ui.tabs.find((t) => t.id === window.__vvdb().ui['active-tab']) || {}).uri === ${JSON.stringify(neverPath)}`,
      'the never-loaded document is the active tab', 8000);
    win.webContents.send('vv:error', { path: neverPath, message: 'EACCES: permission denied', seq: 11 });
    await waitCalm(win, `(document.querySelector('.vv-content .vv-error')?.textContent || '').includes('EACCES')`,
      'a failed first read shows the error page', 8000);
    console.log('[ok] a failed read keeps a shown document behind a refresh note (Retry clears it); unnumbered or first-read failures show the error page');
  }

  // ── INCOMPLETE PDFs (ADR-0046 §Torn reads): main delivers a PDF it caught mid-write — and could not wait out —
  // FLAGGED `incomplete`. A window that shows a complete version keeps it, untouched, behind a note; a window that
  // shows nothing yet lets pdf.js recover what it can (a torn incremental update opens as its last finished revision),
  // flagged likewise; and a first open that pdf.js cannot open at all is the error page — never an empty view under a
  // banner promising a last good version that does not exist.
  {
    const { makeDeckPdf, truncatePdf } = require('./fixtures/pdf-gen');
    const portrait = (pages, extra) => makeDeckPdf(Object.assign({ pages, sizes: Array.from({ length: pages }, () => [300, 800]),
      labels: null, creator: 'incomplete smoke', producer: 'smoke' }, extra || {}));
    const pageCount = `document.querySelectorAll('.vv-content .vv-pdf-doc .vv-pdf-page').length`;
    const note = `(() => { const n = document.querySelector('.vv-refresh-note');
      return n ? { kind: n.dataset.kind, text: n.textContent, title: n.title } : null; })()`;

    // 1 — a shown PDF: an incomplete delivery changes nothing but the note
    const keptPath = path.join(ROOT, 'test', 'fixtures', 'incomplete-kept.pdf');   // virtual path; bytes below
    state.contentByPath.set(keptPath, { path: keptPath, kind: 'pdf', bytes: new Uint8Array(portrait(4)), stamp: Date.now() });
    await evalIn(win, `window.__vvopen(${JSON.stringify(keptPath)})`);
    await waitCalm(win, `${pageCount} === 4 && Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas.vv-pdf-canvas'))`,
      'the complete 4-page version is shown', 15000);
    await evalIn(win, `(document.querySelector('.vv-content .vv-pdf-doc').__vvIncMark = 'kept', true)`);
    win.webContents.send('vv:content', { path: keptPath, kind: 'pdf', bytes: new Uint8Array(truncatePdf(portrait(6), 0.5)),
      stamp: Date.now(), seq: 70, incomplete: true, incompleteReason: 'no-eof' });
    await waitCalm(win, `(${note} || {}).kind === 'incomplete'`, 'an incomplete delivery raises the incomplete note', 8000);
    const kept = await evalIn(win, `({ pages: ${pageCount}, same: document.querySelector('.vv-content .vv-pdf-doc').__vvIncMark === 'kept',
      note: ${note}, error: Boolean(document.querySelector('.vv-content .vv-error')) })`);
    assert.deepStrictEqual([kept.pages, kept.same, kept.error], [4, true, false],
      'the last complete version stays exactly as it was (same view, 4 pages, no error page)');
    assert.ok(/last complete version/.test(kept.note.text) && /end-of-file marker/.test(kept.note.title),
      `the banner says what is shown and why: ${JSON.stringify(kept.note)}`);
    win.webContents.send('vv:content', { path: keptPath, kind: 'pdf', bytes: new Uint8Array(portrait(6)), stamp: Date.now(), seq: 71 });
    await waitCalm(win, `${pageCount} === 6 && !document.querySelector('.vv-refresh-note')`,
      'the finished file replaces it in place and clears the note', 15000);

    // 2 — a FIRST open of an incomplete PDF that pdf.js can still open (a torn incremental update reads as its last
    // finished revision): shown, with the banner saying it is what could be read
    const inc = portrait(3, { incremental: true });
    const firstEof = inc.indexOf('%%EOF') + 6;
    const tornIncremental = Buffer.concat([inc.subarray(0, firstEof),
      Buffer.from('9 0 obj\n<< /Length 5000 >>\nstream\n' + 'x'.repeat(2000), 'latin1')]);
    const partialPath = path.join(ROOT, 'test', 'fixtures', 'incomplete-partial.pdf');
    state.contentByPath.set(partialPath, { path: partialPath, kind: 'pdf', bytes: new Uint8Array(tornIncremental), stamp: Date.now(),
      seq: 72, incomplete: true, incompleteReason: 'no-eof' });
    await evalIn(win, `window.__vvopen(${JSON.stringify(partialPath)})`);
    await waitCalm(win, `${pageCount} === 3 && (${note} || {}).kind === 'incomplete'`,
      'a first open of a torn incremental update shows its finished revision, flagged', 15000);
    assert.ok(/what could be read/.test((await evalIn(win, note)).text), 'the banner says it shows what could be read');

    // 3 — a first open pdf.js cannot open at all, with nothing to fall back to: the error page, no banner — in main's
    // words when main said the file is incomplete, in pdf.js's otherwise
    const tornPath = path.join(ROOT, 'test', 'fixtures', 'incomplete-unopenable.pdf');
    state.contentByPath.set(tornPath, { path: tornPath, kind: 'pdf', bytes: new Uint8Array(truncatePdf(portrait(5), 0.5)),
      stamp: Date.now(), seq: 73, incomplete: true, incompleteReason: 'no-eof' });
    await evalIn(win, `window.__vvopen(${JSON.stringify(tornPath)})`);
    await waitCalm(win, `(document.querySelector('.vv-content .vv-error')?.textContent || '').includes('incomplete')`,
      'an unopenable incomplete first open is the error page, in main\'s words', 15000);
    assert.ok(/once the file is finished/.test(await evalIn(win, `document.querySelector('.vv-content .vv-error').textContent`)),
      'it says the PDF will appear once the file is finished');
    assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-refresh-note'))`), false, 'and no banner');
    const junkPath = path.join(ROOT, 'test', 'fixtures', 'unopenable.pdf');
    state.contentByPath.set(junkPath, { path: junkPath, kind: 'pdf', bytes: new Uint8Array(Buffer.from('%PDF-1.5\nnot really a pdf\n')), stamp: Date.now() });
    await evalIn(win, `window.__vvopen(${JSON.stringify(junkPath)})`);
    await waitCalm(win, `(document.querySelector('.vv-content .vv-error')?.textContent || '').includes('could not be opened')`,
      'an unopenable PDF is the error page, in pdf.js\'s words', 15000);
    assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-refresh-note'))`), false,
      'never an empty view under a "last good version" banner');
    console.log('[ok] incomplete PDFs: a shown version is kept behind a note; a recoverable first open is shown flagged; an unopenable one is the error page');
  }

  // ── slide-deck DETECTION on prepare (ADR-0045): every PDF is classified from its own evidence, in the
  //    renderer, before the first mount — exposed as .vv-pdf-doc[data-deck] (DOM-level, release-safe) ──
  {
    const { makeDeckPdf } = require('./fixtures/pdf-gen');
    const fixtures = [
      ['deck-beamer.pdf', makeDeckPdf({ frames: [1, 3, 1, 2] }), 'beamer'],                               // Beamer Creator
      ['deck-pptx.pdf', makeDeckPdf({ pages: 4, size: 'powerpoint-169', labels: null, creator: null,
                                      producer: null, text: false }), 'slide-export'],                    // exact export canvas
      ['deck-fullscreen.pdf', makeDeckPdf({ pages: 3, sizes: [[612, 792], [612, 792], [612, 792]], labels: null,
                                            creator: 'Some authoring tool', producer: 'x', pageMode: 'FullScreen' }),
       'fullscreen-mode'],
      ['not-a-deck.pdf', makeDeckPdf({ pages: 3, sizes: [[792, 612], [792, 612], [792, 612]], labels: null,
                                       creator: 'Mozilla/5.0 Chrome', producer: 'Skia/PDF m142' }), ''],   // landscape Letter
    ];
    for (const [name, bytes, expected] of fixtures) {
      const p = path.join(ROOT, 'test', 'fixtures', name);   // virtual path; bytes served by the stub main
      state.contentByPath.set(p, { path: p, kind: 'pdf', bytes: new Uint8Array(bytes), stamp: Date.now() });
      await evalIn(win, `window.__vvopen(${JSON.stringify(p)})`);
      await waitCalm(win, `(() => { const d = document.querySelector('.vv-pdf-doc');
        return Boolean(d) && d.dataset.deck === ${JSON.stringify(expected)}
          && Boolean(d.querySelector('canvas.vv-pdf-canvas')); })()`,
        `${name} is classified as ${JSON.stringify(expected || 'not a deck')}`, 15000);
    }
    console.log('[ok] PDF slide-deck detection: Beamer / slide export / FullScreen page mode / landscape non-deck');
  }

  // ── SLIDES LAYOUT (ADR-0045): a detected deck opens one slide per viewport slot; navigation is by slide ──
  {
    const { makeDeckPdf, pageColour } = require('./fixtures/pdf-gen');
    // the last fixture of the detection arm is the landscape non-deck: it must stay in the document layout
    assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides'))`), false,
      'a PDF that is not a deck stays in the continuous document layout');
    const deckPath = path.join(ROOT, 'test', 'fixtures', 'slides-deck.pdf');   // virtual path; bytes below
    const frames = [1, 3, 1, 2];                                                // 7 pages, 4 frames (beamer labels)
    state.contentByPath.set(deckPath, { path: deckPath, kind: 'pdf', bytes: new Uint8Array(makeDeckPdf({ frames })), stamp: Date.now() });
    await evalIn(win, `window.__vvopen(${JSON.stringify(deckPath)})`);
    await waitCalm(win, `(() => { const d = document.querySelector('.vv-pdf-doc.vv-pdf-slides');
      return Boolean(d && d.dataset.deck === 'beamer' && d.querySelector('#vv-pdf-page-1 canvas.vv-pdf-canvas'))
        && (window.__vvdb().ui.slides || {}).page === 0; })()`,
      'a Beamer deck opens in the slides layout on its first slide', 15000);
    const geom = await evalIn(win, `(() => { const c = document.querySelector('.vv-content');
      const slots = [...document.querySelectorAll('.vv-pdf-doc .vv-pdf-page')];
      return { cw: c.clientWidth, ch: c.clientHeight, overflow: getComputedStyle(c).overflowY,
               sizes: slots.map((s) => [s.offsetWidth, s.offsetHeight]),
               sheet: (() => { const r = slots[0].querySelector('.vv-pdf-sheet').getBoundingClientRect(),
                                     q = slots[0].getBoundingClientRect();
                               return { dx: Math.abs((r.left - q.left) - (q.right - r.right)),
                                        dy: Math.abs((r.top - q.top) - (q.bottom - r.bottom)) }; })(),
               slides: window.__vvdb().ui.slides }; })()`);
    assert.strictEqual(geom.overflow, 'hidden', 'the slides layout hides the scroller\'s scrollbar');
    assert.ok(geom.sizes.length === 7 && geom.sizes.every(([w, h]) => w === geom.cw && h === geom.ch),
      `every slot is exactly the pane (${geom.cw}×${geom.ch}): ${JSON.stringify(geom.sizes)}`);
    assert.ok(geom.sheet.dx <= 1 && geom.sheet.dy <= 1, `each slide is centred in its slot (${JSON.stringify(geom.sheet)})`);
    assert.deepStrictEqual([geom.slides.pages, geom.slides.frames, String(geom.slides.layout)], [7, 4, 'slides']);

    const at = async (label) => {
      await waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {}; const c = document.querySelector('.vv-content');
        const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
      return evalIn(win, `window.__vvdb().ui.slides`);
    };
    await evalIn(win, `window.__vvslides('next')`);
    let s = await at('next → slide 2');
    assert.deepStrictEqual([s.page, s.frame, s.overlay, s.overlays, s.label], [1, 1, 0, 3, '2'],
      'the second page is overlay 1/3 of frame 2');
    await evalIn(win, `window.__vvslides('next-frame')`);
    s = await at('next-frame skips the remaining overlays');
    assert.strictEqual(s.page, 4, 'next-frame lands on frame 3 (page 5)');
    await evalIn(win, `window.__vvslides('prev-frame')`);
    s = await at('prev-frame shows the previous frame fully built');
    assert.strictEqual(s.page, 3, 'prev-frame lands on the LAST overlay of frame 2 (page 4)');
    // the wheel: ONE notch flips exactly one slide (pdf.js presentation-mode accumulation) — 120 px, the notch Chromium
    // delivers on X11 at 100 % zoom (docs/scientific/12 §8.7)
    await evalIn(win, `document.querySelector('.vv-content').dispatchEvent(new WheelEvent('wheel', { deltaY: 120, deltaMode: 0, bubbles: true })); true`);
    s = await at('a wheel notch flips one slide');
    assert.strictEqual(s.page, 4, 'the wheel advanced one slide');
    await evalIn(win, `window.__vvslides('last')`);
    s = await at('last');
    assert.strictEqual(s.page, 6);
    await evalIn(win, `window.__vvslides('back')`);
    s = await at('back returns to where the jump came from');
    assert.strictEqual(s.page, 4, 'GoBack history after a jump');
    // the SHOWN slide is really that page: its canvas centre has page 5's fill colour
    await waitCalm(win, `Boolean(document.querySelector('#vv-pdf-page-5 canvas.vv-pdf-canvas'))`, 'slide 5 rendered', 8000);
    const px = await evalIn(win, `(() => { const cv = document.querySelector('#vv-pdf-page-5 canvas.vv-pdf-canvas');
      const d = cv.getContext('2d', { willReadFrequently: true }).getImageData(cv.width >> 1, cv.height >> 1, 1, 1).data;
      return [d[0], d[1], d[2]]; })()`);
    const want = pageColour(4);
    assert.ok(px.every((v, i) => Math.abs(v - want[i]) <= 8), `slide 5's centre pixel ${px} ≈ its fill ${want}`);

    // View as Document keeps the page: the current slide's page sits at the top of the continuous layout
    await evalIn(win, `window.__vvslides('layout', 'document')`);
    // (the continuous layout keeps its 16 px gutter above a page — the same alignment scroll-to-page! gives links)
    await waitCalm(win, `(() => { const c = document.querySelector('.vv-content'); const p = document.getElementById('vv-pdf-page-5');
      const dy = p.getBoundingClientRect().top - c.getBoundingClientRect().top;
      return !document.querySelector('.vv-pdf-slides') && getComputedStyle(c).overflowY !== 'hidden'
        && dy >= -1 && dy <= 24; })()`, 'View as Document keeps page 5 at the top', 8000);
    await evalIn(win, `window.__vvslides('layout', null)`);
    s = await (async () => { await waitCalm(win, `Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides'))`, 'back to slides', 8000); return at('back on slide 5'); })();
    assert.strictEqual(s.page, 4, 'returning to the slides layout keeps the page');

    // a live refresh (one more frame appended) keeps the slide
    win.webContents.send('vv:content', { path: deckPath, kind: 'pdf', bytes: new Uint8Array(makeDeckPdf({ frames: [...frames, 1] })), stamp: Date.now() + 1 });
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).pages === 8`, 'the refreshed deck (8 pages) is swapped in', 15000);
    s = await at('the refresh keeps the current slide');
    assert.strictEqual(s.page, 4, 'a live refresh keeps the slide being shown');
    console.log('[ok] PDF slides layout: slots fill the pane, page/frame/wheel/jump navigation, layout toggle + refresh keep the slide');
  }

  // ── SLIDES KEYS (ADR-0045 §Keys): REAL key events flip slides — bare arrows / PgUp/PgDn / Home/End through
  // key-scroll! → :dom/scroll → the slides layout's scroll delegate; Space / Shift+arrows / digits / Enter /
  // Backspace through the :slides keymap layer — and the layer stands aside when focus leaves the document.
  // The deck is the refreshed 8-page one from the arm above: frames [1, 3, 1, 2, 1], Beamer labels 1 2 2 2 3 4 4 5.
  {
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
    const page = () => evalIn(win, `(window.__vvdb().ui.slides || {}).page`);
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    assert.strictEqual(await evalIn(win, `window.__vvdb().ui.keymaps.active`), 'default', 'the Standard set is active here');
    await sendChord(win, 'Home');
    await slidesAt(0, 'Home → the first slide');
    await sendChord(win, 'Right');
    await slidesAt(1, '→ (a bare arrow, through the scroll delegate) → the next slide');
    await sendChord(win, 'PageDown');
    await slidesAt(2, 'PgDn → the next slide');
    await sendChord(win, 'Right', ['shift']);
    await slidesAt(4, 'Shift+→ (the :slides layer) skips frame 2\'s last overlay → frame 3');
    await sendChord(win, 'PageUp', ['shift']);
    await slidesAt(3, 'Shift+PgUp (:shift? through the delegate) → frame 2, fully built');
    await sendChord(win, 'Space');
    await slidesAt(4, 'Space → the next slide');
    await sendChord(win, 'Space', ['shift']);
    await slidesAt(3, 'Shift+Space → the previous slide');
    await sendChord(win, 'End');
    await slidesAt(7, 'End → the last slide');
    // a typed slide number names the LABEL first: "2" is frame 2, whose first overlay is page index 1
    await sendChord(win, '2');
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).digits === '2'
      && (document.querySelector('.vv-slides-goto b') || {}).textContent === '2'`,
      'typing 2 shows the "Go to 2" chip', 4000);
    await sendChord(win, 'Enter');
    await slidesAt(1, '2 Enter → frame 2 (label "2")');
    assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-slides-goto'))`), false, 'Enter consumes the chip');
    await sendChord(win, '5');
    await sendChord(win, 'Backspace');
    await waitCalm(win, `!(window.__vvdb().ui.slides || {}).digits && !document.querySelector('.vv-slides-goto')`,
      'Backspace deletes the typed digit', 4000);
    assert.strictEqual(await page(), 1, 'Backspace with a typed digit does not move the slide');
    const modeBefore = await evalIn(win, `window.__vvdb().ui.input.mode`);
    await sendChord(win, '4');
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).digits === '4'`, 'typing 4', 4000);
    await sendChord(win, 'Escape');
    await waitCalm(win, `!(window.__vvdb().ui.slides || {}).digits`, 'Escape forgets the typed number', 4000);
    assert.strictEqual(await page(), 1, 'Escape does not move the slide');
    assert.strictEqual(await evalIn(win, `window.__vvdb().ui.input.mode`), modeBefore, 'Escape had no mode side effect');
    // focus OFF the document (the URI bar): the slides layer stands aside — Space reaches the field, not the deck
    await evalIn(win, `(() => { const u = document.querySelector('.vv-uri-input'); u.focus(); u.select(); return true; })()`);
    await sendChord(win, 'Space');
    await sendChord(win, 'Right', ['shift']);
    await new Promise((r) => setTimeout(r, 250));
    assert.strictEqual(await page(), 1, 'with focus in the URI bar, Space and Shift+→ do not flip slides');
    await evalIn(win, `(() => { const u = document.querySelector('.vv-uri-input'); u.blur();
      document.querySelector('.vv-content').focus({ preventScroll: true }); return true; })()`);
    await sendChord(win, 'Space');
    await slidesAt(2, 'focus back on the document → Space flips again');
    console.log('[ok] PDF slides keys: arrows/PgUp/PgDn/Home/End via the scroll delegate, Space/Shift+arrows/digits+Enter/Backspace/Escape via the :slides layer, inactive off the document');
  }

  // ── PRESENTING (ADR-0045 §Presenting): F5 runs a fullscreen slide show of the shown PDF in THIS window — the
  // chrome hides (CSS, nothing remounts), main is asked for fullscreen, the slide fills the window with no
  // margin, b/w blank the screen (a navigation key only un-blanks), a click advances, an SSH prompt still
  // appears over the show (and owns the keyboard), Escape unwinds blank → show, Shift+F5 starts from the
  // current slide, and the window leaving the show's fullscreen ends it. (Stub main: it records every
  // vv:set-fullscreen and answers like the real vinary.main.fullscreen.)
  {
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
    const page = () => evalIn(win, `(window.__vvdb().ui.slides || {}).page`);
    const showing = `(() => { const p = window.__vvdb().ui.presentation;
      return Boolean(p && p['active?']) && document.querySelector('.vv-root').classList.contains('vv-presenting'); })()`;
    const fsBefore = (state.fullscreenRequests || []).length;
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    await sendChord(win, 'F5');
    await waitCalm(win, showing, 'F5 starts the show', 8000);
    await slidesAt(0, 'F5 starts from the first slide');
    assert.deepStrictEqual((state.fullscreenRequests || []).slice(fsBefore), [true], 'the show asked main for fullscreen');
    await waitCalm(win, `window.__vvdb().ui['fullscreen?'] === true`, "main's fullscreen report is recorded", 4000);
    const chrome = await evalIn(win, `['.vv-menubar', '.vv-tabs', '.vv-uribar', '.vv-bottombar', '.vv-sidebar']
      .map((sel) => { const el = document.querySelector(sel); return el ? getComputedStyle(el).display : 'absent'; })`);
    assert.ok(chrome.every((d) => d === 'none' || d === 'absent'), `the app chrome is hidden during the show: ${chrome}`);
    await waitCalm(win, `(() => { const c = document.querySelector('.vv-content').getBoundingClientRect();
        const slot = document.getElementById('vv-pdf-page-1').getBoundingClientRect();
        const sh = document.querySelector('#vv-pdf-page-1 .vv-pdf-sheet').getBoundingClientRect();
        return Math.abs(c.width - innerWidth) <= 1 && Math.abs(c.height - innerHeight) <= 1
          && Math.abs(slot.height - c.height) <= 1
          && (Math.abs(sh.width - slot.width) <= 1 || Math.abs(sh.height - slot.height) <= 1); })()`,
      'the slide fills the window: the pane is the viewport and the sheet spans the slot (no margin)', 8000);
    // keys still flip; b blanks; a navigation key only un-blanks (the audience sees the slide they left)
    await sendChord(win, 'Right');
    await slidesAt(1, '→ during the show');
    await sendChord(win, 'b');
    await waitCalm(win, `getComputedStyle(document.querySelector('.vv-slides-blank') || document.body).backgroundColor === 'rgb(0, 0, 0)'
      && document.querySelector('.vv-slides-blank').dataset.blank === 'black'`, 'b blanks the screen to black', 4000);
    await sendChord(win, 'Right');
    await waitCalm(win, `!document.querySelector('.vv-slides-blank')`, 'a navigation key ends the blanking', 4000);
    assert.strictEqual(await page(), 1, '…without moving the slide');
    await sendChord(win, ',');
    await waitCalm(win, `(document.querySelector('.vv-slides-blank') || {}).dataset?.blank === 'white'`, ', blanks to white', 4000);
    await sendChord(win, ',');
    await waitCalm(win, `!document.querySelector('.vv-slides-blank')`, 'the same key again un-blanks', 4000);
    // a plain click on the slide advances; Shift+click goes back
    const centre = await evalIn(win, `(() => { const r = document.querySelector('.vv-content').getBoundingClientRect();
      return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }; })()`);
    const click = (modifiers) => {
      win.webContents.sendInputEvent({ type: 'mouseDown', x: centre.x, y: centre.y, button: 'left', clickCount: 1, modifiers });
      win.webContents.sendInputEvent({ type: 'mouseUp', x: centre.x, y: centre.y, button: 'left', clickCount: 1, modifiers });
    };
    click([]);
    await slidesAt(2, 'a click advances the show');
    click(['shift']);
    await slidesAt(1, 'Shift+click goes back');
    // an SSH prompt appears OVER the show and owns the keyboard (the slides layer stands aside)
    win.webContents.send('vv:ssh-prompt', { promptId: 'present1', kind: 'password', host: 'demo.example', user: 'alice', attempt: 1 });
    await waitCalm(win, `(() => { const m = document.querySelector('.vv-ssh-prompt'); if (!m) return false;
        const r = m.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(m).visibility !== 'hidden'; })()`,
      'the SSH prompt is visible during the show', 8000);
    assert.strictEqual(await evalIn(win, showing), true, 'the prompt does not end the show');
    await sendChord(win, 'Space');
    await new Promise((r) => setTimeout(r, 250));
    assert.strictEqual(await page(), 1, 'Space goes to the prompt, not the slides');
    await evalIn(win, `Array.from(document.querySelectorAll('.vv-ssh-prompt button')).find((b) => b.textContent.trim() === 'Cancel').click(); true`);
    await waitCalm(win, `!document.querySelector('.vv-ssh-prompt')`, 'the prompt closes', 8000);
    // Escape unwinds: the blank first, then the show itself (which leaves the fullscreen it asked for)
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    await sendChord(win, 'b');
    await waitCalm(win, `Boolean(document.querySelector('.vv-slides-blank'))`, 'blanked again', 4000);
    await sendChord(win, 'Escape');
    await waitCalm(win, `!document.querySelector('.vv-slides-blank')`, 'Escape ends the blanking first', 4000);
    assert.strictEqual(await evalIn(win, showing), true, '…and the show is still running');
    await sendChord(win, 'Escape');
    await waitCalm(win, `!window.__vvdb().ui.presentation && !document.querySelector('.vv-root').classList.contains('vv-presenting')
      && getComputedStyle(document.querySelector('.vv-tabs')).display !== 'none'`, 'Escape ends the show and the chrome returns', 8000);
    assert.deepStrictEqual((state.fullscreenRequests || []).slice(fsBefore), [true, false], 'ending the show left its fullscreen');
    assert.strictEqual(await page(), 1, 'the slides layout keeps the slide the show ended on');
    // Shift+F5 starts from the CURRENT slide; the window leaving the show's fullscreen (F11 / the WM) ends it
    await evalIn(win, `window.__vvslides('goto', 3)`);
    await slidesAt(3, 'on slide 4 before Shift+F5');
    await sendChord(win, 'F5', ['shift']);
    await waitCalm(win, showing, 'Shift+F5 starts the show', 8000);
    await waitCalm(win, `window.__vvdb().ui['fullscreen?'] === true`, 'fullscreen again', 4000);
    assert.strictEqual(await page(), 3, 'Shift+F5 starts from the current slide');
    win.webContents.send('vv:fullscreen-changed', { fullscreen: false, owned: false });
    await waitCalm(win, `!window.__vvdb().ui.presentation`, 'leaving the show\'s fullscreen ends the show', 8000);
    assert.deepStrictEqual((state.fullscreenRequests || []).slice(fsBefore), [true, false, true],
      'no extra exit request when the window already left fullscreen');
    // two Escapes that arrive together take two steps (H-ESC, docs/scientific/12 §8.8). Sent back to back, both keys
    // are queued before re-frame runs the first; each Escape applies its step in its own event, so the second sees the
    // typed number gone and ends the show. (Before the fix the first queued its step BEHIND the second: both forgot
    // the number, and the show ran on.)
    await sendChord(win, 'F5', ['shift']);
    await waitCalm(win, showing, 'Shift+F5 starts a show again', 8000);
    await sendChord(win, '2');
    await waitCalm(win, `(document.querySelector('.vv-slides-goto b') || {}).textContent === '2'`,
      'a slide number typed during the show', 4000);
    await sendChord(win, 'Escape');
    await sendChord(win, 'Escape');
    await waitCalm(win, `!(window.__vvdb().ui.slides || {}).digits`,
      'two Escapes sent together: the first forgets the typed number', 4000);
    await waitCalm(win, `!window.__vvdb().ui.presentation`, '…and the second ends the show', 4000);
    assert.strictEqual(await page(), 3, '…on the slide it was on');
    console.log('[ok] PDF presenting: F5 fullscreen show (chrome hidden, no margin), keys + blanking + click-advance, SSH prompt over the show, Escape unwinds (two at once take two steps), Shift+F5 from current, leaving fullscreen ends it');
  }

  // ── OVERVIEW GRID (ADR-0045 §Overview): o / Tab open a focused grid of FRAMES over the slides layout; arrows move,
  // digits select by label, Enter opens a frame fully built (its LAST overlay), Shift+Enter its first, a click
  // opens a tile, Escape closes without moving; a pick is a jump (Back returns); thumbnails are lazy and bounded.
  // Deck (from the arms above): 8 pages, frames [1, 3, 1, 2, 1] — frame index f covers pages
  // f0 [0], f1 [1-3], f2 [4], f3 [5-6], f4 [7]; Beamer labels 1 2 2 2 3 4 4 5; the show above ended on page 3.
  {
    const { makeDeckPdf, pageColour } = require('./fixtures/pdf-gen');
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
    const selFrame = () => evalIn(win, `(document.querySelector('.vv-slides-tile-sel') || {}).dataset?.frame`);
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    await slidesAt(3, 'on page 4 (frame 2) before the overview');
    await sendChord(win, 'o');
    await waitCalm(win, `(() => { const o = document.querySelector('.vv-slides-overview');
        return Boolean(o) && document.activeElement === o && o.querySelectorAll('.vv-slides-tile').length === 5
          && (o.querySelector('.vv-slides-tile-sel') || {}).dataset?.frame === '1'; })()`,
      'o opens the overview: 5 frame tiles, the current frame selected, the grid focused', 8000);
    await waitCalm(win, `document.querySelectorAll('.vv-slides-overview canvas.vv-slides-tile-canvas').length === 5`,
      'every visible tile gets its thumbnail', 15000);
    const tilePx = await evalIn(win, `(() => { const cv = document.querySelector('.vv-slides-tile[data-frame="1"] canvas');
      const d = cv.getContext('2d', { willReadFrequently: true }).getImageData(cv.width >> 1, cv.height >> 1, 1, 1).data;
      return [d[0], d[1], d[2]]; })()`);
    const want = pageColour(3);
    assert.ok(tilePx.every((v, i) => Math.abs(v - want[i]) <= 12),
      `frame 2's tile shows its LAST overlay (page 4) by default: ${tilePx} ≈ ${want}`);
    await sendChord(win, 'Right');
    await waitCalm(win, `(document.querySelector('.vv-slides-tile-sel') || {}).dataset?.frame === '2'`, '→ moves the selection', 4000);
    await sendChord(win, 'Right');
    await sendChord(win, 'Enter');
    await waitCalm(win, `!document.querySelector('.vv-slides-overview')`, 'Enter closes the overview', 4000);
    await slidesAt(6, 'Enter opens frame 4 fully built (its last page)');
    assert.strictEqual(await evalIn(win, `document.activeElement === document.querySelector('.vv-content')`), true,
      'the keyboard is back on the slides');
    await sendChord(win, 'Tab');
    await waitCalm(win, `Boolean(document.querySelector('.vv-slides-overview'))`, 'Tab opens the overview too', 4000);
    await sendChord(win, '2');
    await waitCalm(win, `(document.querySelector('.vv-slides-tile-sel') || {}).dataset?.frame === '1'`,
      'typing 2 selects frame 2 by its label', 4000);
    await sendChord(win, 'Enter', ['shift']);
    await slidesAt(1, "Shift+Enter opens the frame's FIRST overlay");
    await evalIn(win, `window.__vvslides('back')`);
    await slidesAt(6, 'a pick is a jump: Back returns');
    await sendChord(win, 'o');
    await waitCalm(win, `Boolean(document.querySelector('.vv-slides-tile[data-frame="0"]'))`, 'reopened', 4000);
    await evalIn(win, `document.querySelector('.vv-slides-tile[data-frame="0"]').click(); true`);
    await slidesAt(0, 'a click opens a tile');
    await sendChord(win, 'o');
    await waitCalm(win, `Boolean(document.querySelector('.vv-slides-overview'))`, 'reopened again', 4000);
    await sendChord(win, 'Right');
    await sendChord(win, 'Escape');
    await waitCalm(win, `!document.querySelector('.vv-slides-overview')
      && document.activeElement === document.querySelector('.vv-content')`, 'Escape closes the overview', 4000);
    assert.strictEqual(await evalIn(win, `window.__vvdb().ui.slides.page`), 0, 'Escape does not move the slide');
    assert.strictEqual(await selFrame(), undefined);

    // bounded thumbnails: a 150-page deck keeps canvases only for tiles within the observer band (±½ viewport)
    const bigPath = path.join(ROOT, 'test', 'fixtures', 'slides-150.pdf');   // virtual path; bytes below
    state.contentByPath.set(bigPath, { path: bigPath, kind: 'pdf', bytes: new Uint8Array(makeDeckPdf({ pages: 150 })), stamp: Date.now() });
    await evalIn(win, `window.__vvopen(${JSON.stringify(bigPath)})`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).pages === 150 && Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides'))`,
      'the 150-page deck opens in the slides layout', 15000);
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    await sendChord(win, 'o');
    await waitCalm(win, `document.querySelectorAll('.vv-slides-overview .vv-slides-tile').length === 150`, '150 tiles', 8000);
    const band = `(() => { const o = document.querySelector('.vv-slides-overview'); const r = o.getBoundingClientRect();
      const lo = r.top - r.height / 2, hi = r.bottom + r.height / 2;
      const tiles = [...o.querySelectorAll('.vv-slides-tile')];
      const near = tiles.filter((t) => { const q = t.getBoundingClientRect(); return q.bottom >= lo && q.top <= hi; }).length;
      return { near, canvases: o.querySelectorAll('canvas.vv-slides-tile-canvas').length,
               firstHas: Boolean(tiles[0].querySelector('canvas')), lastHas: Boolean(tiles[tiles.length - 1].querySelector('canvas')) }; })()`;
    // (within one grid row — at most 8 columns — of the tiles in the band: the observer's edge semantics differ
    // from a rect test by a row at most)
    const top = await waitCalm(win, `(() => { const b = ${band}; return b.firstHas && b.canvases >= b.near - 8 ? b : false; })()`,
      'the thumbnails near the top render', 20000);
    assert.ok(top.canvases <= top.near + 8 && top.canvases < 150 && !top.lastHas,
      `only the band is rendered at the top: ${JSON.stringify(top)}`);
    await sendChord(win, 'End');
    const bottom = await waitCalm(win, `(() => { const b = ${band}; return b.lastHas && !b.firstHas && b.canvases >= b.near - 8 ? b : false; })()`,
      'End scrolls to the last frame, its band renders, and the top band is released', 20000);
    assert.ok(bottom.canvases <= bottom.near + 8 && bottom.canvases < 150,
      `far thumbnails are released (the count stays bounded): ${JSON.stringify(bottom)}`);
    await sendChord(win, 'Escape');
    await waitCalm(win, `!document.querySelector('.vv-slides-overview')`, 'closed', 4000);
    console.log('[ok] PDF overview grid: o/Tab open, thumbnails show each frame fully built, arrows/digits/Enter/Shift+Enter/click/Escape, picks are jumps, thumbnails bounded on a 150-page deck'
      + ` (live canvases ${top.canvases} at the top, ${bottom.canvases} at the end, of 150 tiles; band ${top.near}/${bottom.near})`);
  }

  // ── ENTRY POINTS (ADR-0045 §Entry points): any PDF can become a slide show from the View menu, the address bar's
  // toggle, the slide bar and the content-pane context menu; Preferences can opt out of fullscreen.
  {
    const { makeDeckPdf } = require('./fixtures/pdf-gen');
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && s.layout === 'slides' && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
    const openMenu = async (menu) => {
      await evalIn(win, `(() => { const l = Array.from(document.querySelectorAll('.vv-menubar .vv-menu-label'))
        .find((el) => el.textContent.trim().startsWith(${JSON.stringify(menu)})); l.click(); return true; })()`);
      await waitCalm(win, `Boolean(document.querySelector('.vv-menu-dropdown'))`, `the ${menu} menu opens`, 4000);
    };
    const menuLabels = () => evalIn(win, `Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item'))
      .map((n) => (n.querySelector('.vv-menu-item-label') || n).textContent.trim())`);
    const clickMenu = (label) => evalIn(win, `(() => { const it = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item'))
      .find((n) => (n.querySelector('.vv-menu-item-label') || n).textContent.trim() === ${JSON.stringify(label)});
      if (!it) return false; it.click(); return true; })()`);
    // an ordinary (non-deck) PDF: it opens as a document
    const docPath = path.join(ROOT, 'test', 'fixtures', 'entry-doc.pdf');   // virtual path; bytes below
    state.contentByPath.set(docPath, { path: docPath, kind: 'pdf', stamp: Date.now(),
      bytes: new Uint8Array(makeDeckPdf({ pages: 4, sizes: [[612, 792], [612, 792], [612, 792], [612, 792]], labels: null,
                                          creator: 'Mozilla/5.0 Chrome', producer: 'Skia/PDF m142' })) });
    await evalIn(win, `window.__vvopen(${JSON.stringify(docPath)})`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).pages === 4 && (window.__vvdb().ui.slides || {}).layout === 'document'
      && !document.querySelector('.vv-pdf-slides')`, 'the non-deck PDF opens as a document', 15000);
    // View menu: Fit (document layout only), the layout toggle, the show items; no overview yet
    await openMenu('View');
    let ls = await menuLabels();
    for (const want of ['Fit', 'Invert PDF', 'Reflow Text', 'View as Slide Show', 'Start Slide Show', 'Start from Current Slide']) {
      assert.ok(ls.some((l) => l.startsWith(want)), `View offers "${want}" for a PDF: ${JSON.stringify(ls)}`);
    }
    assert.ok(!ls.some((l) => l.startsWith('Slide Overview')), 'no overview outside the slides layout');
    assert.strictEqual(await clickMenu('View as Slide Show'), true);
    await slidesAt(0, 'View ▸ View as Slide Show switches any PDF to the slides layout');
    await openMenu('View');
    ls = await menuLabels();
    assert.ok(ls.includes('View as Document') && !ls.some((l) => l.startsWith('Fit')) && ls.some((l) => l.startsWith('Slide Overview')),
      `in the slides layout the toggle flips, Fit hides and the overview shows: ${JSON.stringify(ls)}`);
    await evalIn(win, `document.querySelector('.vv-menu-overlay').click(); true`);
    // the slide bar replaces the zoom control: ▶ advances, the page field goes to a physical page
    await waitCalm(win, `Boolean(document.querySelector('.vv-bottombar .vv-slides-bar')) && !document.querySelector('.vv-bottombar .vv-zoom')`,
      'the slide bar replaces the zoom control in the slides layout', 4000);
    await evalIn(win, `document.querySelector('.vv-slides-bar-btn[aria-label="Next slide"]').click(); true`);
    await slidesAt(1, '▶ advances');
    assert.strictEqual(await evalIn(win, `document.activeElement === document.querySelector('.vv-content')`), true,
      'the bar hands the keyboard back to the slides');
    await evalIn(win, `(() => { const i = document.querySelector('.vv-slides-bar-page'); i.focus();
      const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      set.call(i, '4'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
    await sendChord(win, 'Enter');
    await slidesAt(3, 'typing 4 + Enter in the page field goes to page 4');
    // the address bar's toggle flips back to the document layout (and focus returns to the content)
    await evalIn(win, `document.querySelector('.vv-slides-toggle').click(); true`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).layout === 'document' && !document.querySelector('.vv-pdf-slides')
      && Boolean(document.querySelector('.vv-bottombar .vv-zoom'))`, 'the toolbar toggle returns to the document layout', 8000);
    assert.strictEqual(await evalIn(win, `document.activeElement === document.querySelector('.vv-content')`), true,
      'a focused toolbar button would eat the next Space — focus is back on the content');
    // the context menu: Start From This Slide on page 3 → a show from page 3 (Preferences: fullscreen OFF)
    await evalIn(win, `window.__vvdb && true`);
    win.webContents.send('vv:settings', '{:slides-fullscreen? false}');
    await waitCalm(win, `window.__vvdb().ui.settings['slides-fullscreen?'] === false`, 'the fullscreen preference is off', 4000);
    const fsBefore = (state.fullscreenRequests || []).length;
    await evalIn(win, `(() => { const p = document.getElementById('vv-pdf-page-3'); p.scrollIntoView(); const r = p.getBoundingClientRect();
      p.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2,
        clientX: r.left + r.width / 2, clientY: r.top + Math.min(40, r.height / 2) })); return true; })()`);
    await waitCalm(win, `Array.from(document.querySelectorAll('.vv-ctx-menu .vv-menu-item')).some((n) => n.textContent.includes('Start From This Slide'))`,
      'the PDF context menu offers Start From This Slide', 4000);
    await evalIn(win, `Array.from(document.querySelectorAll('.vv-ctx-menu .vv-menu-item')).find((n) => n.textContent.includes('Start From This Slide')).click(); true`);
    await waitCalm(win, `Boolean((window.__vvdb().ui.presentation || {})['active?'])`, 'Start From This Slide starts a show', 8000);
    await slidesAt(2, 'the show starts on the right-clicked page');
    assert.strictEqual((state.fullscreenRequests || []).length, fsBefore, 'with "Present full screen" off, no fullscreen request');
    await sendChord(win, 'Escape');
    await waitCalm(win, `!window.__vvdb().ui.presentation`, 'Escape ends it', 4000);
    // the slide bar's Present button is gone outside the slides layout; the document layout is restored
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).layout === 'document'`, 'back to the document layout after the show', 8000);
    win.webContents.send('vv:settings', '{:slides-fullscreen? true}');
    console.log('[ok] PDF slide entry points: View menu (Fit/flip/show/overview gating), slide bar (▶, page field), toolbar toggle (focus back), context menu Start From This Slide, fullscreen preference');
  }

  // ── NAMED ACTIONS (ADR-0045 F8): a deck's links page it — NextPage, a GoTo destination (a JUMP), GoBack walks the
  // jump history, Find opens find — and only allow-listed actions get a link at all (Print stays inert).
  {
    const { makeDeckPdf } = require('./fixtures/pdf-gen');
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
    const linksPath = path.join(ROOT, 'test', 'fixtures', 'links-deck.pdf');   // virtual path; bytes below
    state.contentByPath.set(linksPath, { path: linksPath, kind: 'pdf', stamp: Date.now(),
      bytes: new Uint8Array(makeDeckPdf({ frames: [1, 1, 1, 1], links: [
        { page: 0, rect: [10, 10, 80, 40], action: 'NextPage' },
        { page: 0, rect: [100, 10, 170, 40], dest: 3 },
        { page: 3, rect: [10, 10, 80, 40], action: 'GoBack' },
        { page: 3, rect: [100, 10, 170, 40], action: 'Print' },
        { page: 3, rect: [190, 10, 260, 40], action: 'Find' }] })) });
    await evalIn(win, `window.__vvopen(${JSON.stringify(linksPath)})`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).pages === 4 && Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides'))`,
      'the links deck opens in the slides layout', 15000);
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    await slidesAt(0, 'on slide 1');
    const link = (page, sel) => `document.querySelector('#vv-pdf-page-${page} .vv-pdf-anno ${sel}')`;
    await waitCalm(win, `Boolean(${link(1, '.vv-pdf-link[title="NextPage"]')})`, 'the NextPage link is built', 8000);
    await evalIn(win, `${link(1, '.vv-pdf-link[title="NextPage"]')}.click(); true`);
    await slidesAt(1, 'a NextPage link advances');
    await evalIn(win, `window.__vvslides('first')`);
    await slidesAt(0, 'back on slide 1');
    await evalIn(win, `${link(1, '.vv-pdf-link:not([title])')}.click(); true`);
    await slidesAt(3, 'a GoTo link jumps to its destination');
    await waitCalm(win, `Boolean(${link(4, '.vv-pdf-link[title="GoBack"]')})`, 'the GoBack link is built', 8000);
    assert.strictEqual(await evalIn(win, `Boolean(${link(4, '.vv-pdf-link[title="Print"]')})`), false,
      'a Print named action gets no link (not on the allow-list)');
    await evalIn(win, `${link(4, '.vv-pdf-link[title="GoBack"]')}.click(); true`);
    await slidesAt(0, 'GoBack returns to where the jump came from');
    await evalIn(win, `window.__vvslides('last')`);
    await slidesAt(3, 'on the last slide again');
    await waitCalm(win, `Boolean(${link(4, '.vv-pdf-link[title="Find"]')})`, 'the Find link is built', 8000);
    await evalIn(win, `${link(4, '.vv-pdf-link[title="Find"]')}.click(); true`);
    await waitCalm(win, `window.__vvdb().ui.find['visible?'] === true`, 'a Find link opens the find bar', 4000);
    await evalIn(win, `${link(4, '.vv-pdf-link[title="Find"]')}.click(); true`);
    await new Promise((r) => setTimeout(r, 200));
    assert.strictEqual(await evalIn(win, `window.__vvdb().ui.find['visible?']`), true, 'Find never closes the bar');
    await evalIn(win, `window.__vvfindtoggle()`);
    await waitCalm(win, `window.__vvdb().ui.find['visible?'] === false`, 'find closed', 4000);
    console.log('[ok] PDF named actions: NextPage advances, GoTo jumps, GoBack returns, Find opens find, Print is inert');
  }

  // ── REMEMBERING THE SLIDE (ADR-0045 F9): leaving a deck saves its SLIDE in the history entry (a pixel offset
  // depends on the window size in the slides layout), and Back/Forward or a tab switch reopens that slide.
  {
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && s.layout === 'slides' && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 10000);
    const entry = (offset) => evalIn(win, `(() => { const ui = window.__vvdb().ui;
      const t = ui.tabs.find((x) => x.id === ui['active-tab']); const h = t.hist; return h.stack[h.idx + (${offset})] || null; })()`);
    await evalIn(win, `window.__vvslides('goto', 2)`);
    await slidesAt(2, 'on slide 3 of the links deck');
    const otherPath = path.join(ROOT, 'test', 'fixtures', 'entry-doc.pdf');   // still in the stub's content map
    await evalIn(win, `window.__vvopen(${JSON.stringify(otherPath)})`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).layout === 'document' && (window.__vvdb().ui.slides || {}).pages === 4
      && !document.querySelector('.vv-pdf-slides')`, 'navigated to another (document-layout) PDF', 15000);
    const left = await entry(-1);
    assert.strictEqual(left && left.page, 2, `the left entry remembers the slide: ${JSON.stringify(left)}`);
    await sendChord(win, 'Left', ['alt']);
    await slidesAt(2, 'Back reopens the deck on the remembered slide');
    // a tab switch away and back restores it too — and a NEW position replaces the old one
    await evalIn(win, `window.__vvslides('goto', 1)`);
    await slidesAt(1, 'on slide 2 now');
    // sendInputEvent returns BEFORE the renderer handles the key: wait for each key's effect on the TAB state (and for
    // the deck's view to actually unmount), never on the slide mirror alone — it still describes the deck until its
    // view unmounts, so a check against it can pass on a stale value before the key has even landed
    const deckTab = await evalIn(win, `window.__vvdb().ui['active-tab']`);
    const tabCount = await evalIn(win, `window.__vvdb().ui.tabs.length`);
    await sendChord(win, 'T', ['control']);
    await waitCalm(win, `window.__vvdb().ui.tabs.length === ${tabCount + 1} && window.__vvdb().ui['active-tab'] !== ${deckTab}
      && !document.querySelector('.vv-pdf-doc') && document.activeElement && document.activeElement.classList.contains('vv-uri-input')`,
      'a new blank tab (the deck view unmounted)', 8000);
    await sendChord(win, 'PageUp', ['control']);
    await waitCalm(win, `window.__vvdb().ui['active-tab'] === ${deckTab}`, 'Ctrl+PageUp is back on the deck tab', 8000);
    await slidesAt(1, 'switching back to the deck tab reopens its slide (a remount)');
    await sendChord(win, 'PageDown', ['control']);
    await waitCalm(win, `window.__vvdb().ui['active-tab'] !== ${deckTab}`, 'Ctrl+PageDown moved to the blank tab', 8000);
    await sendChord(win, 'W', ['control']);   // close the blank tab again
    await waitCalm(win, `window.__vvdb().ui.tabs.length === ${tabCount} && window.__vvdb().ui['active-tab'] === ${deckTab}`,
      'the blank tab closed; the deck tab is active', 8000);
    await slidesAt(1, 'the deck is still on its slide');
    console.log('[ok] PDF remembered slide: Back and a tab switch reopen the slide (not a pixel offset); a new position replaces the old');
  }

  // ── DOCUMENT-LAYOUT JUMP HISTORY: the continuous layout runs the same deck model — a followed link is a jump that
  // GoBack returns from, NextPage pages without recording one (the links deck from the named-actions arm, as a document).
  {
    await evalIn(win, `window.__vvslides('layout', 'document')`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).layout === 'document' && !document.querySelector('.vv-pdf-slides')`,
      'the links deck in the document layout', 8000);
    // start at the very top, and measure the continuous layout's gutter there: page 1's top sits that far below it
    await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); c.scrollTop = 0; c.dispatchEvent(new Event('scroll')); return true; })()`);
    await waitCalm(win, `document.querySelector('.vv-content').scrollTop === 0`, 'the pane is at the top', 8000);
    const gutter = await evalIn(win, `document.getElementById('vv-pdf-page-1').getBoundingClientRect().top
      - document.querySelector('.vv-content').getBoundingClientRect().top`);
    assert.ok(gutter > 0 && gutter < 40, `the continuous layout keeps a gutter above page 1 (${gutter} px)`);
    // "page n is shown": the pane scrolled so page n's top sits one gutter below the viewport top — the alignment
    // every jump uses — or as far as it can, when the document is too short to bring that top up (the deck shows at
    // 100 % here, so its last page cannot reach the top)
    const scrolledToPage = (n, label) => waitCalm(win, `(() => { const c = document.querySelector('.vv-content');
        const p = document.getElementById('vv-pdf-page-${n}'); if (!p) return false;
        const pageTop = p.getBoundingClientRect().top - c.getBoundingClientRect().top + c.scrollTop;
        const target = Math.min(Math.max(0, pageTop - ${gutter}), Math.max(0, c.scrollHeight - c.clientHeight));
        return Math.abs(c.scrollTop - target) <= 1; })()`, label, 8000);
    const link = (page, sel) => `document.querySelector('#vv-pdf-page-${page} .vv-pdf-anno ${sel}')`;
    await waitCalm(win, `Boolean(${link(1, '.vv-pdf-link:not([title])')})`, 'the GoTo link is built in the document layout', 8000);
    await evalIn(win, `${link(1, '.vv-pdf-link:not([title])')}.click(); true`);
    await scrolledToPage(4, 'a followed GoTo link shows page 4');
    await waitCalm(win, `Boolean(${link(4, '.vv-pdf-link[title="GoBack"]')})`, 'the GoBack link is built', 8000);
    await evalIn(win, `${link(4, '.vv-pdf-link[title="GoBack"]')}.click(); true`);
    await scrolledToPage(1, 'GoBack returns to page 1 in the document layout too');
    await evalIn(win, `${link(1, '.vv-pdf-link[title="NextPage"]')}.click(); true`);
    await scrolledToPage(2, 'NextPage pages to page 2');
    // NextPage recorded no jump: GoBack from page 2 returns nowhere new (the history holds only the GoTo, already undone)
    await evalIn(win, `window.__vvslides('layout', null)`);
    await waitCalm(win, `Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides'))`, 'back to the slides layout', 8000);
    console.log('[ok] PDF document-layout jump history: a GoTo link is a jump, GoBack returns, NextPage pages');
  }

  // ── SWIPE (S10): a quick horizontal touch / pen swipe flips a slide; a slow drag, a vertical drag and a MOUSE drag
  // (text selection) do not (vinary.slides.deck/swipe-op)
  {
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && s.layout === 'slides' && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
    const gesture = (type, dx, dy, ms) => evalIn(win, `new Promise((done) => {
      const c = document.querySelector('.vv-content'); const r = c.getBoundingClientRect();
      const x0 = r.left + r.width / 2, y0 = r.top + r.height / 2;
      const ev = (name, x, y) => c.dispatchEvent(new PointerEvent(name, { pointerId: 11, pointerType: ${JSON.stringify(type)},
        isPrimary: true, clientX: x, clientY: y, bubbles: true, cancelable: true }));
      ev('pointerdown', x0, y0);
      setTimeout(() => { ev('pointerup', x0 + (${dx}), y0 + (${dy})); done(true); }, ${ms});
    })`);
    await evalIn(win, `window.__vvslides('first')`);
    await slidesAt(0, 'on the first slide');
    await gesture('touch', -160, 12, 120);
    await slidesAt(1, 'a leftward touch swipe → the next slide');
    await gesture('pen', 150, -8, 120);
    await slidesAt(0, 'a rightward pen swipe → the previous slide');
    await gesture('touch', -160, 0, 1100);
    await gesture('touch', -60, 200, 120);
    await gesture('mouse', -200, 0, 100);
    await delay(300);
    assert.strictEqual(await evalIn(win, `window.__vvdb().ui.slides.page`), 0,
      'a slow drag, a mostly vertical drag and a mouse drag (text selection) flip nothing');
    console.log('[ok] PDF swipe: touch/pen swipes flip slides; slow, vertical and mouse drags do not');
  }

  // ── TRANSITIONS (S8): with the :slides-transition preference a command-driven flip animates — a snapshot of the
  // outgoing slide fades (or is pushed off while the incoming sheet slides in) and is removed when it ends; the flip
  // itself is instant as always (vinary.slides.deck/transition-plan)
  {
    const slidesAt = (want, label) => waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {};
        const c = document.querySelector('.vv-content'); const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${want} && s.layout === 'slides' && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
    // flip, then sample every 10 ms for 1 s: did a snapshot overlay appear, did the incoming sheet animate, is it gone?
    const flipAndWatch = (op) => evalIn(win, `new Promise((done) => {
      const seen = { overlay: false, sheetAnim: false };
      window.__vvslides(${JSON.stringify(op)});
      const t0 = performance.now();
      const id = setInterval(() => {
        if (document.querySelector('.vv-slides-transition')) seen.overlay = true;
        if (Array.from(document.querySelectorAll('.vv-pdf-sheet')).some((sh) => sh.getAnimations().length > 0)) seen.sheetAnim = true;
        if (performance.now() - t0 > 1000) { clearInterval(id);
          done({ ...seen, leftover: Boolean(document.querySelector('.vv-slides-transition')) }); }
      }, 10);
    })`);
    // app-db holds a new preference a frame BEFORE the engine does: the view state reaches pdf/update! through the
    // pdf-view's re-render, which Reagent batches onto the next animation frame. Its flush was queued when app-db
    // changed — before these callbacks — so two frames later the engine has the preference (an ordering, not a
    // sleep; a flip dispatched sooner sees the previous transition, as it did when this arm first failed)
    const engineHasSettings = () => evalIn(win, `new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))`);
    await evalIn(win, `window.__vvslides('first')`);
    await slidesAt(0, 'transitions: on slide 1');
    await waitCalm(win, `Boolean(document.querySelector('#vv-pdf-page-1 canvas.vv-pdf-canvas'))`, 'slide 1 rendered', 8000);
    win.webContents.send('vv:settings', '{:slides-transition :fade}');
    await waitCalm(win, `window.__vvdb().ui.settings['slides-transition'] === 'fade'`, 'fade selected', 4000);
    await engineHasSettings();
    const fade = await flipAndWatch('next');
    await slidesAt(1, 'the faded flip still lands on slide 2');
    assert.deepStrictEqual(fade, { overlay: true, sheetAnim: false, leftover: false },
      'fade: a snapshot overlay fades out and is removed; the incoming sheet does not move');
    win.webContents.send('vv:settings', '{:slides-transition :push}');
    await waitCalm(win, `window.__vvdb().ui.settings['slides-transition'] === 'push'`, 'push selected', 4000);
    await engineHasSettings();
    await waitCalm(win, `Boolean(document.querySelector('#vv-pdf-page-2 canvas.vv-pdf-canvas'))`, 'slide 2 rendered', 8000);
    const push = await flipAndWatch('prev');
    await slidesAt(0, 'the pushed flip lands on slide 1');
    assert.deepStrictEqual(push, { overlay: true, sheetAnim: true, leftover: false },
      'push: the snapshot is pushed off while the incoming sheet slides in, and both end');
    win.webContents.send('vv:settings', '{:slides-transition :none}');
    await waitCalm(win, `window.__vvdb().ui.settings['slides-transition'] === 'none'`, 'transitions off again', 4000);
    await engineHasSettings();
    const none = await flipAndWatch('next');
    await slidesAt(1, 'an instant flip');
    assert.deepStrictEqual(none, { overlay: false, sheetAnim: false, leftover: false }, 'none: no animation at all');
    console.log('[ok] PDF slide transitions: fade (snapshot fades), push (snapshot pushed + incoming slides in), none (instant)');
  }

  // ── SECOND-SCREEN NOTES (S9): Beamer's `show notes on second screen=<side>` builds every page twice the paper — the
  // slide and its notes side by side (pgfpages). The slides layout shows the SLIDE half — fitted, rasterized alone,
  // the notes' text hidden from find — per the :slides-notes preference (named, as in Beamer, after where the NOTES
  // are); the document layout keeps whole pages, notes readable and searchable; the page labels pgfpages corrupts are
  // ignored, one frame per page (vinary.slides.deck/second-screen-axis, slide-side; renderer.pdf/split-side).
  {
    const { makeDeckPdf, pageColour, NOTES_COLOUR } = require('./fixtures/pdf-gen');
    const notesPath = path.join(ROOT, 'test', 'fixtures', 'second-screen-deck.pdf');   // virtual path; bytes below
    // 4 pages (frames 2 + 1 + 1) labelled the way pgfpages corrupts them, 1 1 1 2 — a plain build says 1 1 2 3
    state.contentByPath.set(notesPath, { path: notesPath, kind: 'pdf', stamp: Date.now(),
      bytes: new Uint8Array(makeDeckPdf({ secondScreen: 'right', size: 43, frames: [2, 1, 1], labels: ['1', '1', '1', '2'] })) });
    await evalIn(win, `window.__vvopen(${JSON.stringify(notesPath)})`);
    // (the slides mirror's :path names the view that reported it — the previous deck's view, also a Beamer deck in
    // the slides layout, would satisfy every DOM condition until the new one mounts)
    await waitCalm(win, `(() => { const d = document.querySelector('.vv-pdf-doc.vv-pdf-slides');
      const s = window.__vvdb().ui.slides || {};
      return Boolean(d && d.dataset.deck === 'beamer' && d.querySelector('#vv-pdf-page-1 canvas.vv-pdf-canvas'))
        && s.path === ${JSON.stringify(notesPath)} && s.page === 0; })()`, 'the second-screen deck opens in the slides layout', 15000);
    // a preference reaches the engine a frame after app-db (see the transitions arm)
    const engineHasSettings = () => evalIn(win, `new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))`);
    const setNotes = async (v) => {
      win.webContents.send('vv:settings', `{:slides-notes :${v}}`);
      await waitCalm(win, `window.__vvdb().ui.settings['slides-notes'] === '${v}'`, `notes = ${v}`, 4000);
      await engineHasSettings();
    };
    const slideAspect = 362.835 / 272.126, pageAspect = 2 * slideAspect;
    // the first slot as shown — the sheet's aspect, its canvas raster's aspect, the colour at the raster's centre — once
    // the sheet's split state and aspect are `split` / `aspect` and its centre is `colour` (re-renders are
    // double-buffered: a stale raster stays until the new one swaps in, so wait for the final one)
    const shownAs = (split, aspect, colour, label) => waitCalm(win, `(() => {
      const sheet = document.querySelector('#vv-pdf-page-1 .vv-pdf-sheet');
      const cv = sheet && sheet.querySelector('canvas.vv-pdf-canvas');
      if (!cv) return false;
      const r = sheet.getBoundingClientRect();
      const d = cv.getContext('2d', { willReadFrequently: true }).getImageData(cv.width >> 1, cv.height >> 1, 1, 1).data;
      const want = ${JSON.stringify(colour)};
      return sheet.classList.contains('vv-pdf-split') === ${split}
        && Math.abs(r.width / r.height - ${aspect}) < 0.03 && Math.abs(cv.width / cv.height - ${aspect}) < 0.03
        && (want === null || [0, 1, 2].every((i) => Math.abs(d[i] - want[i]) <= 10)); })()`, label, 8000);
    await shownAs(true, slideAspect, pageColour(0),
      'auto shows the LEFT half — Beamer\'s default notes=right — fitted and rasterized alone');

    let s = await evalIn(win, `window.__vvdb().ui.slides`);
    assert.deepStrictEqual([s.pages, s.frames, s.label], [4, 4, '1'],
      'one frame per page: the labels pgfpages corrupted are not trusted');
    await evalIn(win, `window.__vvslides('next')`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).page === 1`, 'next', 8000);
    s = await evalIn(win, `window.__vvdb().ui.slides`);
    assert.strictEqual(s.label, '2', 'page 2 is called 2 — its corrupt label said 1');
    await evalIn(win, `window.__vvslides('first')`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).page === 0`, 'back on the first slide', 8000);

    // find sees what the eye sees: the slide text on every page, none of the notes
    const findFor = async (q) => {
      const setQuery = (v) => evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(i, ${JSON.stringify(v)}); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
      const ranFor = (v) => waitCalm(win, `(() => { const f = window.__vvfind();
        return String(f.query || '').trim().toLowerCase() === ${JSON.stringify(v.toLowerCase())}
          && !window.__vvpending('find/search'); })()`, `find ran for ${v}`, 8000);
      // the renderer records a query only when its search COMPLETES (with its matches): searching for a sentinel
      // first makes "the last completed search was q" mean THIS search — never an earlier one for the same words
      await setQuery('qzxjv sentinel');
      await ranFor('qzxjv sentinel');
      await setQuery(q);
      await ranFor(q);
      return evalIn(win, `window.__vvfind().count`);
    };
    const closeFind = () => evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
      i && i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
    await evalIn(win, `window.__vvfindtoggle()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'the find bar opens', 8000);
    assert.strictEqual(await findFor('Frame'), 4, 'find matches the slide text of every page');
    assert.strictEqual(await findFor('Speakernotes'), 0, 'the notes half is hidden from find in the slides layout');
    await closeFind();

    // the preference names where the NOTES are: notes=left shows the RIGHT half — on this deck, its notes
    await setNotes('left');
    await shownAs(true, slideAspect, NOTES_COLOUR, 'notes=left shows the right half (here, the notes)');
    await setNotes('off');
    await shownAs(false, pageAspect, null, 'off shows whole pages');
    await setNotes('auto');
    await shownAs(true, slideAspect, pageColour(0), 'auto again shows the slide half');

    // the document layout keeps whole pages: the notes are there to read, and to find
    await evalIn(win, `window.__vvslides('layout', 'document')`);
    await waitCalm(win, `(() => { const sheet = document.querySelector('#vv-pdf-page-1 .vv-pdf-sheet'); const r = sheet.getBoundingClientRect();
      return !document.querySelector('.vv-pdf-slides') && !sheet.classList.contains('vv-pdf-split')
        && Math.abs(r.width / r.height - ${pageAspect}) < 0.03; })()`, 'the document layout shows whole pages', 8000);
    await evalIn(win, `window.__vvfindtoggle()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'the find bar opens again', 8000);
    assert.strictEqual(await findFor('Speakernotes'), 4, 'the document layout finds the notes of every page');
    await closeFind();
    await evalIn(win, `window.__vvslides('layout', null)`);
    await shownAs(true, slideAspect, pageColour(0), 'back in the slides layout, the slide half again');

    // the overview's tiles are slide halves too
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    await sendChord(win, 'o');
    await waitCalm(win, `document.querySelectorAll('.vv-slides-overview canvas.vv-slides-tile-canvas').length === 4`,
      'the overview shows 4 frame tiles, each with its thumbnail', 15000);
    const tile = await evalIn(win, `(() => { const t = document.querySelector('.vv-slides-tile[data-frame="0"]');
      const box = t.querySelector('.vv-slides-tile-thumb').getBoundingClientRect(); const cv = t.querySelector('canvas');
      const d = cv.getContext('2d', { willReadFrequently: true }).getImageData(cv.width >> 1, cv.height >> 1, 1, 1).data;
      return { box: box.width / box.height, canvas: cv.width / cv.height, px: [d[0], d[1], d[2]] }; })()`);
    assert.ok(Math.abs(tile.box - slideAspect) < 0.03 && Math.abs(tile.canvas - slideAspect) < 0.03,
      `an overview tile is the slide half (box ${tile.box.toFixed(3)}, canvas ${tile.canvas.toFixed(3)})`);
    const want0 = pageColour(0);
    assert.ok(tile.px.every((v, i) => Math.abs(v - want0[i]) <= 12), `the tile shows the slide: ${tile.px} ≈ ${want0}`);
    await sendChord(win, 'Escape');
    await waitCalm(win, `!document.querySelector('.vv-slides-overview')`, 'the overview closes', 4000);

    // a REAL deck (test/fixtures/beamer-notes-right.tex, compiled once by LuaLaTeX and committed as produced): white
    // halves, embedded fonts, and a notes half whose default template repeats the slide's own words in a header and a
    // thumbnail. Whole pages hold FrameOneTitle 6×, SlideOneBody 4×, NotesForFrameOne 2× (pdftotext); the slides
    // layout must find only the slide half's — the title and body of frame 1's two overlay pages
    const realPath = path.join(ROOT, 'test', 'fixtures', 'beamer-notes-right.pdf');
    state.contentByPath.set(realPath, { path: realPath, kind: 'pdf', stamp: Date.now(),
      bytes: new Uint8Array(fs.readFileSync(realPath)) });
    await evalIn(win, `window.__vvopen(${JSON.stringify(realPath)})`);
    await waitCalm(win, `(() => { const d = document.querySelector('.vv-pdf-doc.vv-pdf-slides');
      const s = window.__vvdb().ui.slides || {};
      return Boolean(d && d.dataset.deck === 'beamer'
        && d.querySelector('#vv-pdf-page-1 .vv-pdf-sheet.vv-pdf-split canvas.vv-pdf-canvas'))
        && s.path === ${JSON.stringify(realPath)} && s.page === 0; })()`, 'the real LuaLaTeX notes deck opens on its slide half', 15000);
    s = await evalIn(win, `window.__vvdb().ui.slides`);
    assert.deepStrictEqual([s.pages, s.frames], [3, 3], 'the real deck steps page by page (its labels read 1 1 1)');
    await evalIn(win, `window.__vvfindtoggle()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'find over the real deck', 8000);
    const inSlides = [await findFor('FrameOneTitle'), await findFor('SlideOneBody'), await findFor('NotesForFrameOne')];
    await closeFind();
    assert.deepStrictEqual(inSlides, [2, 2, 0],
      'the slides layout finds the slide half only — not the notes header, the thumbnail or the notes');
    await evalIn(win, `window.__vvslides('layout', 'document')`);
    await waitCalm(win, `!document.querySelector('.vv-pdf-slides')
      && Boolean(document.querySelector('#vv-pdf-page-1 .vv-pdf-sheet canvas.vv-pdf-canvas'))`, 'the real deck as a document', 8000);
    await evalIn(win, `window.__vvfindtoggle()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'find over the real document', 8000);
    const inDocument = [await findFor('FrameOneTitle'), await findFor('SlideOneBody'), await findFor('NotesForFrameOne')];
    await closeFind();
    assert.deepStrictEqual(inDocument, [6, 4, 2], 'the document layout finds every word on the page');
    await evalIn(win, `window.__vvslides('layout', null)`);
    await waitCalm(win, `Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides'))`, 'the real deck back in the slides layout', 8000);
    console.log('[ok] PDF second-screen notes: the slides show the slide half (auto / notes=left / off), find skips the notes (a real LuaLaTeX deck too), the document layout keeps whole pages, tiles are halves, corrupt labels ignored');
  }

  // ── NOTE PAGES (ADR-0048 §3, N2, N7): Beamer's `show notes` writes a note page after the slides it annotates. They
  // leave the audience's sequence but stay in the DOM — marked .vv-pdf-note-page, never rasterized in the slides layout,
  // their text hidden from find there — and next/prev step over them, the slide a flip shows next already rendered.
  // LuaTeX's labels prove them at first paint; a deck without labels (one XeLaTeX pass) is settled by the background
  // scan; :slides-notes :off shows every page; the document layout shows them all. The pdfpc sidecar a payload carries
  // reaches the deck: its hidden page is skipped. Real TeX output throughout (test/fixtures/beamer-shownotes*.pdf,
  // beamer-pdfpc.pdf + .pdfpc). ──
  {
    const fx = (f) => path.join(ROOT, 'test', 'fixtures', f);
    // test/pdf-gen-smoke.mjs NOTE_PAGES — the note pages, written down before the fixtures were compiled
    const NOTE_PAGES = [1, 3, 4, 8];
    const engineHasSettings = () => evalIn(win, `new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(true))))`);
    const setNotes = async (v) => {
      win.webContents.send('vv:settings', `{:slides-notes :${v}}`);
      await waitCalm(win, `window.__vvdb().ui.settings['slides-notes'] === '${v}'`, `notes = ${v}`, 4000);
      await engineHasSettings();
    };
    const marksAre = (want, label) => waitCalm(win, `JSON.stringify([...document.querySelectorAll(
      '.vv-pdf-doc .vv-pdf-page.vv-pdf-note-page')].map((p) => +p.dataset.pdfIdx)) === ${JSON.stringify(JSON.stringify(want))}`,
      label, 15000);
    const slidesAt = async (page, label) => {
      await waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {}; const c = document.querySelector('.vv-content');
        const slot = document.querySelector('.vv-pdf-doc .vv-pdf-page').offsetHeight;
        return s.page === ${page} && Math.abs(c.scrollTop - s.page * slot) <= 1; })()`, label, 8000);
      return evalIn(win, `window.__vvdb().ui.slides`);
    };
    const step = async (op, page, label) => { await evalIn(win, `window.__vvslides(${JSON.stringify(op)})`); return slidesAt(page, label); };
    const deckOf = (p) => evalIn(win, `window.__vvdeck(${JSON.stringify(p)})`);
    const findFor = async (q) => {
      const setQuery = (v) => evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(i, ${JSON.stringify(v)}); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
      const ranFor = (v) => waitCalm(win, `(() => { const f = window.__vvfind();
        return String(f.query || '').trim().toLowerCase() === ${JSON.stringify(v.toLowerCase())}
          && !window.__vvpending('find/search'); })()`, `find ran for ${v}`, 8000);
      await setQuery('qzxjv sentinel');
      await ranFor('qzxjv sentinel');
      await setQuery(q);
      await ranFor(q);
      return evalIn(win, `window.__vvfind().count`);
    };
    const closeFind = () => evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
      i && i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);

    // 1 — LuaLaTeX: the labels prove the note pages before the first paint
    const luaPath = path.join(ROOT, 'test', 'fixtures', 'notes-lua-deck.pdf');   // virtual path; the real bytes
    state.contentByPath.set(luaPath, { path: luaPath, kind: 'pdf', stamp: Date.now(),
      bytes: new Uint8Array(fs.readFileSync(fx('beamer-shownotes.pdf'))) });
    await evalIn(win, `window.__vvopen(${JSON.stringify(luaPath)})`);
    await waitCalm(win, `(() => { const d = document.querySelector('.vv-pdf-doc.vv-pdf-slides'); const s = window.__vvdb().ui.slides || {};
      return Boolean(d && d.dataset.deck === 'beamer') && s.path === ${JSON.stringify(luaPath)} && s.page === 0; })()`,
      'the LuaLaTeX `show notes` deck opens in the slides layout', 15000);
    await marksAre(NOTE_PAGES, 'its four note pages are marked');
    let s = await evalIn(win, `window.__vvdb().ui.slides`);
    assert.deepStrictEqual([s.pages, s.frames], [10, 4], '10 pages, and 4 frames over the 6 pages the audience sees');
    let dk = await deckOf(luaPath);
    assert.ok(dk && dk.notes && dk.notes.style === 'interleaved' && dk.notes['note-pages'] === 4
      && JSON.stringify(dk.notes.proof) === '["labels"]' && dk.notes['has-notes?'] === true,
      `:doc/deck carries the notes model: ${JSON.stringify(dk && dk.notes)}`);
    // the slide a flip shows next is already rendered — two slots away, past the note page — and no note page is
    await waitCalm(win, `Boolean(document.querySelector('#vv-pdf-page-3 canvas.vv-pdf-canvas'))`,
      'the next slide (page 2, beyond note page 1) is rendered before the flip', 8000);
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-pdf-note-page canvas.vv-pdf-canvas').length`), 0,
      'no note page is rasterized in the slides layout');
    // next and prev step over the note pages: 0 → 2 → 5 → 6 → 7 → 9, and back
    s = await step('next', 2, 'next skips note page 1');
    assert.deepStrictEqual([s.frame, s.overlay, s.overlays], [0, 1, 2], 'page 2 is the Intro\'s second overlay (of 2)');
    await step('next', 5, 'next skips note pages 3 and 4 — Middle');
    await step('next', 6, 'Outro, overlay 1');
    await step('next', 7, 'Outro, overlay 2');
    s = await step('next', 9, 'next skips note page 8 — the \\againframe of Middle');
    assert.strictEqual(s.frame, 3, 'the last frame');
    await step('prev', 7, 'prev skips note page 8');
    // a typed frame number counts the audience's frames: 2 is Middle
    await evalIn(win, `window.__vvslides('goto-label', '2')`);
    await slidesAt(5, 'goto 2 is frame 2 (Middle, page 5) — not physical page 2');
    await evalIn(win, `window.__vvslides('first')`);
    await slidesAt(0, 'back on the first slide');
    // the overview is the audience's frames: four tiles, none of them a note page
    await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
    await sendChord(win, 'o');
    await waitCalm(win, `document.querySelectorAll('.vv-slides-overview .vv-slides-tile').length === 4`,
      'the overview shows the 4 frames the audience sees', 8000);
    await sendChord(win, 'Escape');
    await waitCalm(win, `!document.querySelector('.vv-slides-overview')`, 'the overview closes', 4000);

    // find never takes the audience to a note page; the document layout finds the notes like any text
    await evalIn(win, `window.__vvfindtoggle()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'the find bar opens', 8000);
    assert.strictEqual(await findFor('Notes for the introduction'), 0, 'the slides layout finds no note-page text');
    assert.ok(await findFor('Intro') >= 2, 'it still finds the slides\' own text');
    await closeFind();
    await evalIn(win, `window.__vvslides('layout', 'document')`);
    await waitCalm(win, `!document.querySelector('.vv-pdf-slides')`, 'the document layout', 8000);
    await evalIn(win, `window.__vvfindtoggle()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'the find bar opens again', 8000);
    assert.strictEqual(await findFor('Notes for the introduction'), 2, 'the document layout finds both note pages of the Intro');
    await closeFind();
    // a show started from a note page — the document layout's Start From This Slide — starts on that note's slide:
    // the audience never sees a note page, however the show is asked to start. The slot on screen is recorded at the
    // END of every frame, from before the show starts, and slot 3 — note page 3, never drawn, so a blank slot — must
    // not appear in any (checked before the end state, so a view stuck on it fails here, by this name). The end of a
    // frame is a ResizeObserver callback: a frame runs it after every animation-frame callback and after layout, just
    // before it paints, and a 1-px probe whose width each frame toggles makes it run every frame. A record taken IN an
    // animation-frame callback would miss the frame that matters: the show starts inside Reagent's own
    // animation-frame callback (its flushSync commit runs pdf/update!), which runs after the record's, so that frame
    // is painted before such a record looks again. go-to-page! snaps every target it is given; without that snap the
    // show's first frame is painted on the blank slot, and snap!, at the end of the scroll, repairs it one frame later
    // (ADR-0048 Verification).
    win.webContents.send('vv:settings', '{:slides-fullscreen? false}');
    await waitCalm(win, `window.__vvdb().ui.settings['slides-fullscreen?'] === false`, 'the fullscreen preference is off', 4000);
    await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); const seen = window.__vvSlotsSeen = [];
      const probe = document.createElement('div');
      probe.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;pointer-events:none';
      document.body.appendChild(probe);
      const ro = new ResizeObserver(() => { if (window.__vvSlotsSeen !== seen) return;
        const p = document.querySelector('.vv-pdf-doc.vv-pdf-slides .vv-pdf-page');
        if (p) seen.push(Math.round(c.scrollTop / p.offsetHeight)); });
      ro.observe(probe);
      window.__vvSlotsStop = () => { ro.disconnect(); probe.remove(); };
      const tick = () => { if (window.__vvSlotsSeen !== seen) return;
        probe.style.width = probe.style.width === '1px' ? '2px' : '1px';
        requestAnimationFrame(tick); };
      requestAnimationFrame(tick); return true; })()`);
    await evalIn(win, `(() => { const p = document.getElementById('vv-pdf-page-4'); p.scrollIntoView(); const r = p.getBoundingClientRect();
      p.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2,
        clientX: r.left + r.width / 2, clientY: r.top + Math.min(40, r.height / 2) })); return true; })()`);
    await waitCalm(win, `Array.from(document.querySelectorAll('.vv-ctx-menu .vv-menu-item')).some((n) => n.textContent.includes('Start From This Slide'))`,
      'the context menu over note page 3 offers Start From This Slide', 4000);
    await evalIn(win, `Array.from(document.querySelectorAll('.vv-ctx-menu .vv-menu-item')).find((n) => n.textContent.includes('Start From This Slide')).click(); true`);
    await waitCalm(win, `Boolean((window.__vvdb().ui.presentation || {})['active?'])`, 'the show starts', 8000);
    await waitCalm(win, `(window.__vvSlotsSeen || []).length >= 20`, 'twenty frames of the show recorded', 8000);
    const slotsSeen = await evalIn(win, `(() => { const s = window.__vvSlotsSeen; window.__vvSlotsSeen = null; window.__vvSlotsStop(); return s; })()`);
    assert.ok(!slotsSeen.includes(3),
      `no frame showed note page 3's slot (slots seen, in order: ${JSON.stringify(slotsSeen.filter((v, i) => i === 0 || v !== slotsSeen[i - 1]))})`);
    await slidesAt(2, 'a show from note page 3 starts on its slide, page 2');
    await sendChord(win, 'Escape');
    await waitCalm(win, `!window.__vvdb().ui.presentation`, 'Escape ends the show', 4000);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).layout === 'document'`, 'back in the document layout', 8000);
    win.webContents.send('vv:settings', '{:slides-fullscreen? true}');
    await waitCalm(win, `window.__vvdb().ui.settings['slides-fullscreen?'] === true`, 'the fullscreen preference is back on', 4000);
    await evalIn(win, `window.__vvslides('layout', null)`);
    await waitCalm(win, `Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides'))`, 'back in the slides layout', 8000);
    await evalIn(win, `window.__vvslides('first')`);
    await slidesAt(0, 'on the first slide again');

    // :slides-notes :off shows every page as it is — the escape hatch; any other value pairs them again
    await setNotes('off');
    await marksAre([], ':off: no page is marked');
    s = await step('next', 1, ':off: next goes to physical page 1 (the note page is shown)');
    // its labels, 1 2 1 4 5 2 3 3 9 4, group into 9 frames when every page is shown: a note page is labelled with its
    // physical page number, so each one is a frame of its own
    assert.strictEqual(s.frames, 9, ':off: 9 frames — each note page, labelled with its page number, is a frame of its own');
    await setNotes('auto');
    await marksAre(NOTE_PAGES, 'auto: the note pages are marked again');
    s = await slidesAt(0, 'auto: the note page the view was on shows as its slide (page 0)');

    // 2 — one XeLaTeX pass: no labels, so the note pages are found by the background scan after the first paint
    const xePath = path.join(ROOT, 'test', 'fixtures', 'notes-xe-deck.pdf');
    state.contentByPath.set(xePath, { path: xePath, kind: 'pdf', stamp: Date.now(),
      bytes: new Uint8Array(fs.readFileSync(fx('beamer-shownotes-xelatex.pdf'))) });
    await evalIn(win, `window.__vvopen(${JSON.stringify(xePath)})`);
    await waitCalm(win, `(window.__vvdb().ui.slides || {}).path === ${JSON.stringify(xePath)}`, 'the XeLaTeX deck mounts', 15000);
    await marksAre(NOTE_PAGES, 'the scan finds the same four note pages');
    dk = await deckOf(xePath);
    assert.ok(dk.notes.style === 'interleaved' && JSON.stringify(dk.notes.proof) === '["scan"]' && !dk.notes.pending,
      `…proven by the scan, nothing left pending: ${JSON.stringify(dk.notes)}`);
    await step('next', 2, 'the XeLaTeX deck: next skips note page 1');
    s = await evalIn(win, `window.__vvdb().ui.slides`);
    assert.strictEqual(s.frames, 6, 'without labels, one frame per shown page');

    // 3 — pdfpc: the sidecar on the payload (main reads it beside the PDF — content-service smoke) reaches the deck
    const pcText = fs.readFileSync(fx('beamer-pdfpc.pdfpc'), 'utf8');
    const pcPath = path.join(ROOT, 'test', 'fixtures', 'notes-pdfpc-deck.pdf');
    const pcBytes = fs.readFileSync(fx('beamer-pdfpc.pdf'));
    state.contentByPath.set(pcPath, { path: pcPath, kind: 'pdf', stamp: Date.now(), bytes: new Uint8Array(pcBytes),
      sidecar: { path: pcPath.replace(/\.pdf$/, '.pdfpc'), text: pcText } });
    await evalIn(win, `window.__vvopen(${JSON.stringify(pcPath)})`);
    await waitCalm(win, `(() => { const s = window.__vvdb().ui.slides || {}; const d = window.__vvdeck(${JSON.stringify(pcPath)});
      return s.path === ${JSON.stringify(pcPath)} && Boolean(d && d.notes); })()`, 'the pdfpc deck mounts', 15000);
    dk = await deckOf(pcPath);
    assert.ok(dk.notes.sidecar === 'read' && dk.notes['pdfpc?'] === true && dk.notes['markdown?'] === true,
      `the sidecar was read and the deck carries pdfpc metadata (Markdown notes): ${JSON.stringify(dk.notes)}`);
    await evalIn(win, `window.__vvslides('goto', 3)`);
    await slidesAt(3, 'goto page 3 (frame Three)');
    await evalIn(win, `window.__vvslides('next')`);
    await engineHasSettings();
    s = await slidesAt(3, 'next stays: the only page ahead, 4, is hidden in the sidecar (pdfpc skips it)');
    await step('last', 4, 'End still reaches the skipped page');
    // the control: the same PDF without its sidecar steps onto page 4
    win.webContents.send('vv:content', { path: pcPath, kind: 'pdf', bytes: new Uint8Array(pcBytes), stamp: Date.now() + 1, sidecar: null });
    await waitCalm(win, `(window.__vvdeck(${JSON.stringify(pcPath)}) || { notes: {} }).notes.sidecar == null`,
      'the refresh without a sidecar is swapped in', 15000);
    await evalIn(win, `window.__vvslides('goto', 3)`);
    await slidesAt(3, 'goto page 3 again');
    await step('next', 4, 'without the sidecar, next steps onto page 4');
    console.log('[ok] PDF note pages: marked and never rasterized in the slides layout; next/prev/goto over the audience\'s pages; the next slide pre-rendered; find skips them (the document layout finds them); :off shows every page; one XeLaTeX pass settled by the scan; the pdfpc sidecar\'s hidden page is skipped');
  }

  // ===== Bugs A + B1 + B2 (round 5) — run with the PDF active (the content pane); default keymap restored after.
  // Bug A: a persisted Vim set must be LIVE immediately (active set AND its modal :normal mode set in :db), not
  // only after a manual Standard→Vim→… switch. Push a Vim config the way main does and assert both at once.
  win.webContents.send('vv:keymap', '{:active "vim" :order [] :sets {}}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'vim'`), 'persisted Vim set becomes active at config-received');
  assert.strictEqual(await evalIn(win, `window.__vvdb().ui.input.mode`), 'normal',
    'Bug A: Vim is modal → input mode is :normal immediately (set synchronously in :db — no manual switch needed)');
  console.log('[ok] persisted Vim keymap is live immediately — active set + :normal mode (Bug A)');

  // inject a content link (PDF-intra-doc style: href="#", destination only in a click listener) + a sidebar
  // tree file row (the leak candidate), with focus in the content pane.
  await evalIn(win, `(() => {
    const content = document.querySelector('.vv-content');
    const a = document.createElement('a');
    a.className = 'vv-pdf-link'; a.href = '#'; a.textContent = 'HINTLINK';
    a.setAttribute('style', 'position:fixed;left:6px;top:6px;width:48px;height:16px;z-index:99999;background:#fff');
    a.addEventListener('click', (e) => { e.preventDefault(); window.__vvHintClicked = true; });
    content.appendChild(a);
    let tree = document.querySelector('.vv-tree');
    if (!tree) { tree = document.createElement('div'); tree.className = 'vv-tree'; tree.setAttribute('data-vv-test-tree','1'); document.body.appendChild(tree); }
    const t = document.createElement('a'); t.className = 'vv-file'; t.setAttribute('data-path', '/leak/should-not-hint.txt');
    t.textContent = 'leakrow'; t.setAttribute('style', 'position:fixed;left:6px;top:60px;width:48px;height:16px;z-index:99999');
    tree.appendChild(t);
    window.__vvHintClicked = false;
    // RETIRED WORKAROUND (ADR-0032) — kept commented, per the repo's comment-don't-delete rule, because it
    // records a real bug and re-adding it would mask the regression the fix now guards against:
    //
    //   // the hidden find bar auto-focuses at boot (sets :in-input? true). Cycle a REAL input (the URI bar)
    //   // through focus→blur so its on-blur dispatches :input/set-in-input false, then move focus into the
    //   // content pane.
    //   if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    //   const uri = document.querySelector('.vv-uri-input'); if (uri) { uri.focus(); uri.blur(); }
    //   const c = document.querySelector('.vv-content'); if (c) { c.setAttribute('tabindex','-1'); c.focus(); }
    //
    // The diagnosis in that comment was wrong: :ui/find/visible? defaults false (app/db.cljs), so nothing
    // auto-focuses at boot. The stuck flag came from the Escape close a few hundred lines above — closing
    // the find bar unmounted a FOCUSED input, and Chromium fires no blur for a removed element, so the
    // hand-mirrored :in-input? stayed true and swallowed every bare printable key for the rest of the
    // session. :in-input? is now derived from document.activeElement (input/resolver) and .vv-content
    // carries tabindex="-1" in the app itself, so neither half of the workaround is needed.
    const c = document.querySelector('.vv-content'); if (c) c.focus();
  })()`);
  // a bare 'f' only resolves to :hint/start when NOT typing into an input. This must now be true WITHOUT
  // any coaxing — it is the regression guard for the leak described above.
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.input['in-input?']`), ':in-input? cleared before f');

  // Bug B1: 'f' (Vim) collects link hints scoped to the focused CONTENT pane — the sidebar tree must NOT leak in.
  await dispatchWindowKey(win, 'f');
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.hints && window.__vvdb().ui.hints['active?'])`), 'link hints activate on f');
  const hintInfo = await evalIn(win, `(() => {
    const ts = (window.__vvdb().ui.hints && window.__vvdb().ui.hints.targets) || [];
    return { leak: ts.some(t => t.path === '/leak/should-not-hint.txt'),
             label: (ts.find(t => (t.text || '').indexOf('HINTLINK') >= 0) || {}).label,
             count: ts.length };
  })()`);
  assert.strictEqual(hintInfo.leak, false, 'Bug B1: the sidebar tree file row must NOT be hinted while the content pane is focused');
  assert.ok(hintInfo.label, 'the injected content link must be hinted (collected from .vv-content)');
  console.log('[ok] f-hints scoped to the content pane — sidebar tree not hinted (Bug B1)');

  // Bug B2: typing the label fires the element's REAL click. A PDF intra-doc link carries its destination only
  // in a click listener (href="#"), so deriving nav from href was a no-op; the follow now re-finds the element
  // at its stamped position and .click()s it.
  for (const ch of hintInfo.label) { await dispatchWindowKey(win, ch); await delay(20); }
  await waitFor(() => evalIn(win, `window.__vvHintClicked === true`), 'typing the hint label fires the link real click');
  console.log('[ok] activating an f-hint fires the link real click listener (Bug B2)');

  // restore the default keymap + clean up the injected nodes so the following tests run unchanged
  await evalIn(win, `(() => {
    document.querySelectorAll('.vv-content .vv-pdf-link').forEach(e => { if (/HINTLINK/.test(e.textContent)) e.remove(); });
    document.querySelectorAll('.vv-file').forEach(e => { if (e.getAttribute('data-path') === '/leak/should-not-hint.txt') e.remove(); });
    const tn = document.querySelector('.vv-tree[data-vv-test-tree]'); if (tn) tn.remove();
  })()`);
  win.webContents.send('vv:keymap', null);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'default'`), 'default keymap restored after the hint tests');
  await delay(60);

  const dialogsBefore = state.openDialogs;
  await sendChord(win, 'O', ['control', 'shift']);
  await waitFor(() => state.openDialogs > dialogsBefore, 'Ctrl+Shift+O dialog request');
  const openMode = await evalIn(win, `String(window.__vvdb().ui['open-dialog-mode'])`);
  assert.strictEqual(openMode, 'new-tab', 'Ctrl+Shift+O must request the new-tab open mode');
  console.log('[ok] Ctrl+Shift+O works from preview content');

  // The Open dialog is seeded with an ORDERED candidate chain of folders: the active tab's local path (when
  // it is a browsable local file/dir), then the recent-files MRU. Reproduce the renderer's seed selection
  // (nav/dialog-seed-path + the recent-files fallback, deduped) here as an INDEPENDENT oracle over the raw
  // app-db, and assert the candidate array that crossed the IPC seam matches exactly.
  const expectedSeeds = await evalIn(win, `(() => {
    const ui = (window.__vvdb() || {}).ui || {};
    const tabs = ui.tabs || [];
    const tab = tabs.find((t) => t.id === ui['active-tab']);
    const isLocal = (u) => {
      if (!u) return false;
      const lo = u.toLowerCase();
      return !(lo.startsWith('http://') || lo.startsWith('https://')
        || u.startsWith('vv-archive://') || lo.startsWith('ssh://') || lo.startsWith('sftp://'));
    };
    const uri = tab && tab.uri;
    const active = isLocal(uri) ? (uri.startsWith('file://') ? uri.slice(7) : uri) : null;
    const recent = ((ui.recent && ui.recent['recent-files']) || []).filter(isLocal);
    const seen = new Set();
    return [active, ...recent].filter((p) => p && !seen.has(p) && seen.add(p));
  })()`);
  assert.deepStrictEqual(state.lastOpenSeed, expectedSeeds,
    'the Open dialog is seeded with the active-tab local path then the recent-files MRU, matching nav/dialog-seed-path');
  console.log('[ok] Open dialog seeded with the active/recent directory chain:', JSON.stringify(state.lastOpenSeed));

  // B3 — app-global Ctrl/Cmd chords forwarded from the (separate-context) web view are replayed through the
  // resolver. Simulate the forward by delivering vv:web-key to the renderer and assert the same command runs.
  const dialogsBeforeWeb = state.openDialogs;
  win.webContents.send('vv:web-key', { key: 'O', ctrl: true, shift: true, alt: false, meta: false });
  await waitFor(() => state.openDialogs > dialogsBeforeWeb, 'web-view Ctrl+Shift+O forwarded → new-tab dialog request');
  assert.strictEqual(await evalIn(win, `String(window.__vvdb().ui['open-dialog-mode'])`), 'new-tab', 'forwarded Ctrl+Shift+O requests the new-tab open mode');
  console.log('[ok] web-view Ctrl/Cmd shortcuts forwarded to the app keymap');

  await sendChord(win, 'L', ['control']);
  await waitFor(
    () => evalIn(win, `document.activeElement === document.querySelector('.vv-uri-input')`),
    'Ctrl+L URI input focus'
  );
  const uriFocus = await evalIn(win, `(() => {
    const input = document.querySelector('.vv-uri-input');
    return {
      active: document.activeElement === input,
      valueLength: input?.value.length ?? -1,
      selectionStart: input?.selectionStart ?? -1,
      selectionEnd: input?.selectionEnd ?? -1
    };
  })()`);
  assert.strictEqual(uriFocus.active, true, 'Ctrl+L must focus the URI input');
  assert.strictEqual(uriFocus.selectionStart, 0, 'Ctrl+L must select from the start of the URI');
  assert.strictEqual(uriFocus.selectionEnd, uriFocus.valueLength, 'Ctrl+L must select the full URI');
  console.log('[ok] Ctrl+L focuses and selects the URI input');

  const documentTabBeforeBlank = await evalIn(win, `window.__vvdb().ui['active-tab']`);
  await evalIn(win, `(() => { document.querySelector('.vv-content').focus(); return true; })()`);
  assert.strictEqual(await evalIn(win, `document.activeElement === document.querySelector('.vv-content')`), true,
    'the tab-focus regression starts from the document body, not a pre-focused URI field');

  const setUriValue = (value) => evalIn(win, `(() => {
    const i = document.querySelector('.vv-uri-input');
    const v = ${JSON.stringify(value)};
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, v);
    i.dispatchEvent(new Event('input', { bubbles: true }));
    i.setSelectionRange(v.length, v.length);
    return i.value;
  })()`);
  const clickTabId = async (id, label) => {
    const clicked = await evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      const idx = ui.tabs.findIndex((t) => t.id === ${id});
      const tab = document.querySelectorAll('.vv-tab')[idx];
      if (!tab) return false;
      tab.click(); return true;
    })()`);
    assert.strictEqual(clicked, true, `${label}: tab exists in the strip`);
    await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${id}`), `${label}: tab activates`);
  };

  const tabCountBefore = await evalIn(win, `document.querySelectorAll('.vv-tab').length`);
  await sendChord(win, 'T', ['control']);
  await waitFor(
    () => evalIn(win, `document.querySelectorAll('.vv-tab').length > ${tabCountBefore}`),
    'Ctrl+T new tab'
  );
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`),
    'blank tab view'
  );
  const uriAfterFocusedNavigation = await evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return { value:i?.value ?? null, active:document.activeElement===i,
             start:i?.selectionStart ?? -1, end:i?.selectionEnd ?? -1 }; })()`);
  assert.strictEqual(uriAfterFocusedNavigation.active, true,
    'a new blank tab focuses the URI field even when the document held focus');
  assert.strictEqual(uriAfterFocusedNavigation.value, '',
    'a fresh blank tab starts with an empty URI draft');
  assert.strictEqual(uriAfterFocusedNavigation.start, 0, 'the replacement URI remains selected from its start');
  assert.strictEqual(uriAfterFocusedNavigation.end, 0, 'the empty replacement URI has an empty full selection');
  const blankTabA = await evalIn(win, `window.__vvdb().ui['active-tab']`);
  const draftA = '/tmp/unfinished-alpha';
  assert.strictEqual(await setUriValue(draftA), draftA, 'blank tab A accepts an unfinished URI');

  // A second blank tab gets an independent address task. This switch happens while the SAME controlled input
  // remains focused, the edge that used to leak the previous tab's component-local draft.
  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] !== ${blankTabA}`),
    'second blank tab activates');
  const blankTabB = await evalIn(win, `window.__vvdb().ui['active-tab']`);
  await waitFor(() => evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return document.activeElement===i && i.value==='' && i.selectionStart===0 && i.selectionEnd===0; })()`),
    'second blank tab owns an empty focused URI field');
  const draftB = '/tmp/unfinished-beta';
  assert.strictEqual(await setUriValue(draftB), draftB, 'blank tab B accepts its own unfinished URI');

  await clickTabId(documentTabBeforeBlank, 'document tab focus');
  await waitFor(() => evalIn(win, `document.activeElement === document.querySelector('.vv-content')`),
    'a document tab focuses the document body');

  await clickTabId(blankTabA, 'blank tab A restore');
  await waitFor(() => evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return document.activeElement===i && i.value===${JSON.stringify(draftA)}
      && i.selectionStart===0 && i.selectionEnd===i.value.length; })()`),
    'blank tab A restores and selects its unfinished URI');

  const amendedA = `${draftA}/amended`;
  assert.strictEqual(await setUriValue(amendedA), amendedA,
    'collapsing the selection permits immediate amendment');
  await clickTabId(blankTabB, 'blank tab B restore');
  await waitFor(() => evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return document.activeElement===i && i.value===${JSON.stringify(draftB)}
      && i.selectionStart===0 && i.selectionEnd===i.value.length; })()`),
    'blank tab B retains its independent selected draft');
  await clickTabId(blankTabA, 'amended blank tab A restore');
  await waitFor(() => evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return document.activeElement===i && i.value===${JSON.stringify(amendedA)}
      && i.selectionStart===0 && i.selectionEnd===i.value.length; })()`),
    'blank tab A retains the amended draft independently');

  // A stale completion reply from A must not overwrite B's completion owner after a switch.
  const slowDraft = '/tmp/__vv_slow_complete__/alpha';
  await setUriValue(slowDraft);
  await waitFor(() => state.completePathInputs.includes(slowDraft), 'slow completion request leaves tab A');
  await clickTabId(blankTabB, 'switch during tab A completion');
  await waitFor(() => state.completePathInputs.includes(draftB), 'tab B refreshes completion from its restored draft');
  await delay(350);
  const completionOwner = await evalIn(win, `(() => { const u=window.__vvdb().ui['uri-complete'];
    return { tab:u['tab-id'], typed:u['typed-input'], input:u.input }; })()`);
  assert.strictEqual(completionOwner.tab, blankTabB, 'a late completion reply remains owned by tab B');
  assert.strictEqual(completionOwner.typed, draftB, 'tab B completion keeps its restored draft as the latest input');

  // Invalid Enter is corrective, not destructive: keep the blank tab, text, focus, and caret.
  await clickTabId(blankTabA, 'blank tab A invalid navigation');
  await waitFor(() => evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return document.activeElement===i && i.value===${JSON.stringify(slowDraft)}
      && i.selectionStart===0 && i.selectionEnd===i.value.length; })()`),
    'blank tab A finishes restoring before its draft is replaced');
  const invalidDraft = '/__vv_definitely_missing__/unfinished-location';
  await setUriValue(invalidDraft);
  await evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    i.dispatchEvent(new KeyboardEvent('keydown', { key:'Enter', bubbles:true, cancelable:true })); return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-uri-error'))`),
    'invalid URI shows an inline error');
  const failedUri = await evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return { active:document.activeElement===i, value:i.value, start:i.selectionStart, end:i.selectionEnd,
             tab:window.__vvdb().ui['active-tab'], uri:(window.__vvdb().ui.tabs.find(t=>t.id===window.__vvdb().ui['active-tab'])||{}).uri }; })()`);
  assert.strictEqual(failedUri.tab, blankTabA, 'failed navigation stays on its originating blank tab');
  assert.strictEqual(failedUri.uri == null, true, 'failed navigation does not turn the draft into a real tab URI');
  assert.strictEqual(failedUri.active, true, 'failed navigation keeps URI focus for correction');
  assert.strictEqual(failedUri.value, invalidDraft, 'failed navigation preserves the unfinished text');
  assert.strictEqual(failedUri.start, invalidDraft.length, 'failed navigation preserves the caret start');
  assert.strictEqual(failedUri.end, invalidDraft.length, 'failed navigation preserves the caret end');

  // Closing active B reveals blank A: the same focus/restore rule must apply to close-driven activation.
  await clickTabId(blankTabB, 'blank tab B before close');
  await evalIn(win, `(() => { const ui=window.__vvdb().ui;
    const idx=ui.tabs.findIndex(t=>t.id===${blankTabB});
    document.querySelectorAll('.vv-tab')[idx].querySelector('.vv-tab-close').click(); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${blankTabA}`),
    'closing active blank tab reveals its neighbor');
  await waitFor(() => evalIn(win, `(() => { const i=document.querySelector('.vv-uri-input');
    return document.activeElement===i && i.value===${JSON.stringify(invalidDraft)}
      && i.selectionStart===0 && i.selectionEnd===i.value.length; })()`),
    'close-revealed blank tab restores, focuses, and selects its draft');
  console.log('[ok] blank-tab URI drafts persist independently; focus follows blank/document state; failures stay editable');

  const badgeSvg = encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="92" height="20" role="img">' +
      '<rect width="92" height="20" fill="#2563eb"/>' +
      '<text x="46" y="14" font-size="11" fill="#fff" text-anchor="middle">badge</text>' +
    '</svg>'
  );
  const featureDocPath = path.join(ROOT, 'test', 'fixtures', 'markdown-features.md');
  state.contentByPath.set(featureDocPath, {
    path: featureDocPath,
    kind: 'markdown',
    text: '# Markdown Features\n\n' +
      `![One](data:image/svg+xml,${badgeSvg}) ![Two](data:image/svg+xml,${badgeSvg})\n\n` +
      'Copyable preview text lives here.\n\n' +
      'Inline math $`x^2`$ and display math:\n\n$$\n\\frac{a}{b}\n$$\n\n' +
      'A code span `$x^2$` stays literal.\n\n' +
      // Operators (`mo`) and spacing (`mspace`) are MathJax 4's inline line-break opportunities. `x^2` has
      // neither, which is why it rendered fine while $`\implies`$ was an invisible blank gap. Geometry is
      // asserted on these below; keep at least one `mo` and one `mspace` expression here.
      'Operators and spacing: $`\\implies`$ then $`x \\le y`$ and $`a\\,b`$.\n\n' +
      'AMS family: bold $\\boldsymbol{v}$ and a commutative diagram:\n\n$$\n\\begin{CD} A @>f>> B \\end{CD}\n$$\n\n' +
      'Denotation brackets + monospace (MathJax 4 dynamic-font glyphs):\n\n' +
      '$$\n\\llbracket L \\Rightarrow R \\rrbracket \\;=\\; \\mathtt{for}\\;\\mathbb{R}\n$$\n\n' +
      '```mermaid\nflowchart LR\n  A[Start] --> B[Done]\n```\n',
    stamp: Date.now()
  });
  // Open through the active-tab path so this also proves that a successful navigation consumes blank A's
  // draft in place and hands focus from the URI field to the loaded document.
  await evalIn(win, `window.__vvopen(${JSON.stringify(featureDocPath)})`);
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Markdown Features'`),
    'markdown feature fixture'
  );
  const loadedFocus = await evalIn(win, `(() => { const ui=window.__vvdb().ui;
    const t=ui.tabs.find(x=>x.id===ui['active-tab']); const i=document.querySelector('.vv-uri-input');
    return { id:ui['active-tab'], uri:t?.uri, input:i?.value,
             content:document.activeElement===document.querySelector('.vv-content') }; })()`);
  assert.strictEqual(loadedFocus.id, blankTabA, 'successful navigation reuses the originating blank tab');
  assert.strictEqual(loadedFocus.uri, featureDocPath, 'the document path becomes the tab URI only after success');
  assert.strictEqual(loadedFocus.content, true, 'successful navigation transfers focus to the document body');
  assert.ok(loadedFocus.input.includes('markdown-features.md') && loadedFocus.input !== invalidDraft,
    'the loaded URI replaces the unfinished blank-tab draft');

  // A Ctrl+L edit over a LOADED document is transient by design. Leaving and returning resumes reading the
  // document, not an abandoned address edit.
  await sendChord(win, 'L', ['control']);
  const transientDocDraft = '/tmp/transient-loaded-document-edit';
  await setUriValue(transientDocDraft);
  await clickTabId(documentTabBeforeBlank, 'leave loaded document URI edit');
  await clickTabId(blankTabA, 'return to loaded document after URI edit');
  await waitFor(() => evalIn(win, `document.activeElement === document.querySelector('.vv-content')`),
    'returning to a loaded document focuses its body');
  // an EVENTUAL property: the bar re-renders the returned tab on the next frame (the clicks above can all land
  // inside one frame, before any render). A retained draft would never go away, so this still fails on the bug.
  await waitFor(() => evalIn(win, `document.querySelector('.vv-uri-input').value !== ${JSON.stringify(transientDocDraft)}
    && document.querySelector('.vv-uri-input').value.includes('markdown-features.md')`),
    'a loaded document does not retain an abandoned URI edit', 4000);
  assert.strictEqual(await evalIn(win, `document.activeElement === document.querySelector('.vv-content')`), true,
    'the stale Ctrl+L focus request does not steal focus after the tab switches (it belonged to an earlier activation)');
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-math-inline mjx-container svg'))`),
    'inline MathJax SVG'
  );
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-math-display mjx-container svg'))`),
    'display MathJax SVG'
  );
  // ── Inline math must PAINT, not merely occupy space ────────────────────────────────────────────────────────
  // Two composing defects made 15% of a real corpus render as blank gaps, and every structural assertion above
  // stayed green throughout. Only a real browser can see either one, so assert on geometry here.
  //
  //  A. MathJax's own stylesheet was never injected (we typeset off-DOM through liteAdaptor), so the UA rule
  //     `svg:not(:root){overflow:hidden}` clipped any ink outside the viewBox.
  //  B. MathJax 4 defaults to automatic INLINE line-breaking, whose break opportunities are `mo` and `mspace`.
  //     With no measurable container it broke at every one, emitting several sibling <svg>s — the first holding
  //     ALL the ink inside a 16-unit-wide box. `\implies` (= \;\Longrightarrow\;) lost 99% of its ink.
  // NB: look the spans up by data-tex CONTENT, never with a CSS attribute selector — the CSS parser would
  // unescape `[data-tex="\implies"]` to the literal `implies` and silently match nothing.
  const inlineMathProbe = (tex) => `(() => {
    const span = Array.from(document.querySelectorAll('.markdown-body .vv-math-inline'))
      .find(n => n.getAttribute('data-tex') === ${JSON.stringify(tex)});
    if (!span) return null;
    const svgs = span.querySelectorAll('mjx-container > svg');
    const svg = svgs[0];
    if (!svg) return null;
    const bb = svg.getBBox();          // ink extents, in the svg's own user units (fill geometry, no stroke)
    const vb = svg.viewBox.baseVal;    // the layout box the browser clips to
    const glyph = svg.querySelector('path[data-c]');
    // Fraction of the expression's ink falling outside the box, on the worst of the four edges. MathJax derives
    // the viewBox from the font's advance/height/depth, so fill ink TOUCHES the top and bottom edges exactly;
    // it is the 3-unit glyph stroke, and accents/stretchy delimiters, that need overflow:visible.
    const span_ = Math.max(bb.width, bb.height, 1);
    const lost = Math.max(
      (bb.x + bb.width) - (vb.x + vb.width),   // right
      (bb.y + bb.height) - (vb.y + vb.height), // bottom
      vb.x - bb.x,                             // left
      vb.y - bb.y,                             // top
      0) / span_;
    return {
      svgCount: svgs.length,
      overflow: getComputedStyle(svg).overflow,
      strokeWidth: glyph ? parseFloat(getComputedStyle(glyph).strokeWidth) : null,
      widthPx: span.getBoundingClientRect().width,
      lost
    };
  })()`;
  await waitFor(() => evalIn(win, inlineMathProbe('\\implies')), 'the \\implies inline equation renders');
  const mathGeometry = {};
  for (const tex of ['\\implies', 'x \\le y', 'a\\,b', 'x^2']) {
    mathGeometry[tex] = await evalIn(win, inlineMathProbe(tex));
    assert.ok(mathGeometry[tex], `inline math "${tex}" must render an <svg>`);
  }
  for (const [tex, m] of Object.entries(mathGeometry)) {
    // Defect A — MathJax ships `overflow: visible` precisely so that legitimate ink spill paints.
    assert.strictEqual(m.overflow, 'visible',
      `${tex}: MathJax's stylesheet is not injected — <svg> overflow is "${m.overflow}", so ink outside the viewBox is clipped`);
    assert.ok(m.strokeWidth > 0,
      `${tex}: MathJax's glyph stroke-width rule is missing (got ${m.strokeWidth}); the root <g>'s stroke-width="0" presentation attribute is winning`);
    // Defect B — one <svg> per expression; several means MathJax line-broke it.
    assert.strictEqual(m.svgCount, 1,
      `${tex}: inline math line-broke into ${m.svgCount} <svg> elements (MathJax linebreaks.inline must be off)`);
    assert.ok(m.widthPx > 2, `${tex}: inline math collapsed to a ${m.widthPx}px box`);
    // A few percent of spill is CORRECT MathJax output (italic correction, accents) — that is what
    // `overflow: visible` is for. Defect B lost 50-99%, so 10% separates them by two orders of magnitude.
    assert.ok(m.lost < 0.10,
      `${tex}: ${(m.lost * 100).toFixed(0)}% of the glyph ink lies outside the viewBox the browser clips to`);
  }
  console.log(`[ok] inline math paints: overflow:visible, stroke-width applied, one <svg>, ink inside the viewBox`
    + ` (\\implies ${mathGeometry['\\implies'].widthPx.toFixed(1)}px, x^2 spill ${(mathGeometry['x^2'].lost * 100).toFixed(1)}%)`);
  // The injected stylesheet is now the ONLY thing hiding the screen-reader MathML (app.css no longer hand-copies
  // that rule), and it must be MathJax's, not ours.
  assert.ok(await evalIn(win, `Boolean(document.getElementById('vv-mathjax-style'))`),
    "MathJax's stylesheet must be injected into <head> as #vv-mathjax-style");
  // Negative control: detach the injected sheet and the UA rule `svg:not(:root){overflow:hidden}` must take over.
  // Without this, `overflow === 'visible'` above could be vacuously true and we would not know.
  const withoutSheet = await evalIn(win, `(() => {
    const style = document.getElementById('vv-mathjax-style');
    const svg = document.querySelector('.markdown-body .vv-math-inline mjx-container > svg');
    style.remove();
    const overflow = getComputedStyle(svg).overflow;
    const strokeWidth = parseFloat(getComputedStyle(svg.querySelector('path[data-c]')).strokeWidth);
    document.head.appendChild(style);                       // restore
    return { overflow, strokeWidth, restored: getComputedStyle(svg).overflow };
  })()`);
  assert.strictEqual(withoutSheet.overflow, 'hidden',
    'sanity: without the injected sheet the UA clips the <svg> — so the overflow assertion above is not vacuous');
  assert.strictEqual(withoutSheet.strokeWidth, 0,
    'sanity: without the injected sheet glyph paths inherit the root <g>\'s stroke-width="0"');
  assert.strictEqual(withoutSheet.restored, 'visible', 'the stylesheet was restored after the negative control');

  // MathJax 4 loads font glyph data in dynamic chunks; \mathtt/\mathbb live in chunks and \llbracket/\rrbracket are
  // stmaryrd macros we bind to ⟦/⟧. Under the SYNCHRONOUS render this crashed ("retry -- an asynchronous action is
  // required") until the dynamic-font preload; the brackets were also undefined red text. Guard both here.
  const extendedMath = await evalIn(win, `(() => {
    const nodes = Array.from(document.querySelectorAll('.markdown-body .vv-math-display'));
    const brk = nodes.find(n => (n.getAttribute('data-tex') || '').includes('llbracket'));
    return {
      found: Boolean(brk),
      hasSvg: brk ? Boolean(brk.querySelector('mjx-container svg')) : null,
      isError: brk ? brk.classList.contains('vv-math-error') : null,
      anyError: document.querySelectorAll('.markdown-body .vv-math-error').length
    };
  })()`);
  assert.strictEqual(extendedMath.found, true, 'the \\llbracket…\\mathtt display equation is present');
  assert.strictEqual(extendedMath.hasSvg, true, 'dynamic-font glyphs + \\llbracket render to a MathJax SVG (no async retry crash)');
  assert.strictEqual(extendedMath.isError, false, 'the equation is not a MathJax error node');
  assert.strictEqual(extendedMath.anyError, 0, 'no .vv-math-error anywhere (no retry throw, no undefined-macro brackets)');
  // Each equation must stash its raw LaTeX so the copy paths can recover the source the SVG can't carry.
  const mathTex = await evalIn(win, `(() => ({
    inline: document.querySelector('.markdown-body .vv-math-inline')?.getAttribute('data-tex') ?? null,
    display: document.querySelector('.markdown-body .vv-math-display')?.getAttribute('data-tex') ?? null
  }))()`);
  assert.strictEqual(mathTex.inline, 'x^2', 'inline math must carry its raw LaTeX in data-tex');
  assert.strictEqual(mathTex.display, '\\frac{a}{b}', 'display math must carry its raw LaTeX in data-tex');
  // GFM precedence: `$x^2$` (backticks OUTSIDE) is a CODE SPAN → literal `$x^2$`, NOT math (whereas bare
  // $x^2$ and $`x^2`$ ARE math). Regression guard for the pre-parse-regex bug that leaked math into code spans.
  const codeSpanMath = await evalIn(win, `(() => {
    const lit = Array.from(document.querySelectorAll('.markdown-body code')).find(c => c.textContent.trim() === '$x^2$');
    return {
      found: Boolean(lit),
      isMath: lit ? (lit.classList.contains('language-math') || lit.classList.contains('math-inline')) : null,
      hasMjx: lit ? Boolean(lit.querySelector('mjx-container')) : null
    };
  })()`);
  assert.strictEqual(codeSpanMath.found, true, '`$x^2$` renders as a literal <code> span (code outranks $…$ math in GFM)');
  assert.strictEqual(codeSpanMath.isMath, false, 'the code span must NOT be a math node');
  assert.strictEqual(codeSpanMath.hasMjx, false, 'the code span must contain no MathJax rendering');
  console.log('[ok] `$x^2$` inline code stays literal (no math leak); $`x^2`$ + bare $…$ still render as math');

  // AMS family via the shadow-bundled engine: boldsymbol (amsbsy) + amscd commutative diagrams render. The
  // authoritative "package is active" signal is the assistive MathML structure (SVG glyphs aren't inspectable).
  const amsMath = await evalIn(win, `(() => {
    const bs = Array.from(document.querySelectorAll('.markdown-body .vv-math-inline'))
      .find(e => (e.getAttribute('data-tex') || '').includes('boldsymbol'));
    const cd = Array.from(document.querySelectorAll('.markdown-body .vv-math-display'))
      .find(e => (e.getAttribute('data-tex') || '').includes('begin{CD}'));
    const mml = (el) => el ? ((el.querySelector('mjx-assistive-mml') || {}).innerHTML || '') : '';
    return {
      bsSvg: Boolean(bs && bs.querySelector('mjx-container svg')),
      bsBoldItalic: /bold-italic/.test(mml(bs)),
      cdSvg: Boolean(cd && cd.querySelector('mjx-container svg')),
      cdMtable: /<mtable/.test(mml(cd))
    };
  })()`);
  assert.strictEqual(amsMath.bsSvg, true, 'boldsymbol inline math renders an SVG');
  assert.strictEqual(amsMath.bsBoldItalic, true, 'boldsymbol → bold-italic MathML (AMS boldsymbol package active)');
  assert.strictEqual(amsMath.cdSvg, true, 'amscd commutative diagram renders an SVG');
  assert.strictEqual(amsMath.cdMtable, true, 'amscd \\begin{CD} builds an mtable (AMS amscd package active)');
  console.log('[ok] AMS family active — boldsymbol → bold-italic, amscd \\begin{CD} → mtable (shadow-bundled engine)');

  // MathJax renditions are SVG (glyph paths, not text), so native ::selection can't paint them; selecting
  // across an equation must give it a .vv-math-selected background so math highlights with the surrounding
  // prose. The listener is selectionchange + rAF-throttled, so wait a frame after selecting.
  await evalIn(win, `(() => {
    const p = document.querySelector('.markdown-body .vv-math-inline').closest('p');
    const sel = window.getSelection(); sel.removeAllRanges();
    const range = document.createRange(); range.selectNodeContents(p); sel.addRange(range);
    return true;
  })()`);
  const mathSel = await waitFor(
    () => evalIn(win, `(() => {
      const el = document.querySelector('.markdown-body .vv-math-inline');
      if (!el || !el.classList.contains('vv-math-selected')) return null;
      return { bg: getComputedStyle(el).backgroundColor };
    })()`),
    'inline math gets .vv-math-selected while selected');
  assert.ok(mathSel.bg && mathSel.bg !== 'rgba(0, 0, 0, 0)' && mathSel.bg !== 'transparent',
    `selected math must have a non-transparent highlight background (got ${mathSel.bg})`);
  await evalIn(win, `window.getSelection().removeAllRanges(); true`);
  await waitFor(() => evalIn(win, `!document.querySelector('.markdown-body .vv-math-inline.vv-math-selected')`),
    'math highlight clears when the selection is collapsed');
  console.log('[ok] MathJax renditions highlight together with the prose during selection (.vv-math-selected)');
  // Regression guard for the double-render bug: MathJax's <mjx-assistive-mml> must stay in the DOM (a11y) but
  // be clipped + unselectable, so Chromium can't paint it as a text-size duplicate. app.css used to hand-copy
  // that rule; it now comes from MathJax's own injected stylesheet, so these assertions guard the injection.
  const assistive = await evalIn(win, `(() => {
    const m = document.querySelector('.markdown-body .vv-math-inline mjx-assistive-mml');
    if (!m) return { present: false };
    const cs = getComputedStyle(m);
    return { present: true, position: cs.position, userSelect: cs.userSelect, clip: cs.clip };
  })()`);
  assert.strictEqual(assistive.present, true, 'assistive MathML must remain in the DOM for screen readers');
  assert.strictEqual(assistive.position, 'absolute', 'assistive MathML must be positioned out of flow');
  assert.strictEqual(assistive.userSelect, 'none', 'assistive MathML must be excluded from text selection');
  assert.ok(assistive.clip.startsWith('rect') && assistive.clip.includes('1px'),
    `assistive MathML must be clipped to ~1px (no visible duplicate): ${assistive.clip}`);
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-mermaid svg'))`),
    'Mermaid SVG'
  );
  const markdownFeatureLayout = await evalIn(win, `(() => {
    const badges = Array.from(document.querySelectorAll('.markdown-body p:first-of-type img'));
    const rects = badges.map((img) => img.getBoundingClientRect());
    return {
      badgeCount: badges.length,
      sameRow: rects.length === 2 && Math.abs(rects[0].top - rects[1].top) < 2,
      secondAfterFirst: rects.length === 2 && rects[1].left > rects[0].left,
      mermaidText: document.querySelector('.vv-mermaid')?.textContent || '',
      mermaidSvg: (() => {
        const svg = document.querySelector('.markdown-body .vv-mermaid svg');
        if (!svg) return null;
        return { width: svg.style.width, aspectRatio: svg.style.aspectRatio, maxWidth: svg.style.maxWidth };
      })()
    };
  })()`);
  assert.strictEqual(markdownFeatureLayout.badgeCount, 2, 'badge fixture must render two images');
  assert.strictEqual(markdownFeatureLayout.sameRow, true, 'badge images must stay on one row');
  assert.strictEqual(markdownFeatureLayout.secondAfterFirst, true, 'badge images must flow horizontally');
  assert.ok(markdownFeatureLayout.mermaidText.includes('Start'), 'Mermaid output should contain diagram labels');
  // inline Mermaid is font-matched PRE-DOM (ADR-0022): its own inline max-width is stripped and a font-matched
  // width + aspect-ratio are baked into the SVG string before insertion (so it auto-scales like the figures).
  assert.ok(markdownFeatureLayout.mermaidSvg, 'inline Mermaid must render an <svg>');
  assert.strictEqual(markdownFeatureLayout.mermaidSvg.maxWidth, '', 'inline Mermaid max-width must be stripped so font-match can up-scale');
  assert.ok(/px$/.test(markdownFeatureLayout.mermaidSvg.width), `inline Mermaid must carry a font-matched px width (got: ${markdownFeatureLayout.mermaidSvg.width})`);
  assert.ok(markdownFeatureLayout.mermaidSvg.aspectRatio, 'inline Mermaid must carry an aspect-ratio');
  // Regression (Bug 2) — rendered diagrams get a #fafafa backdrop (--vv-figure-bg) so transparent-SVG ink stays
  // legible on any theme; the embedded mermaid rides the shared figure/mermaid frame rule.
  const mermaidBackdrop = await evalIn(win, `getComputedStyle(document.querySelector('.markdown-body .vv-mermaid')).backgroundColor`);
  assert.strictEqual(mermaidBackdrop, 'rgb(250, 250, 250)', `embedded mermaid must carry the #fafafa diagram backdrop (got ${mermaidBackdrop})`);
  console.log('[ok] embedded mermaid carries the #fafafa diagram backdrop');
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const walker = document.createTreeWalker(document.querySelector('.markdown-body'), NodeFilter.SHOW_TEXT);
    let node = null;
    while ((node = walker.nextNode())) {
      const index = node.nodeValue.indexOf('Copyable preview text');
      if (index >= 0) {
        const range = document.createRange();
        range.setStart(node, index);
        range.setEnd(node, index + 'Copyable preview text'.length);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        return true;
      }
    }
    return false;
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C must be handled for preview selections');
  await waitFor(() => state.lastCopiedText === 'Copyable preview text', 'preview Ctrl+C clipboard write');
  console.log('[ok] Markdown badges, MathJax, Mermaid, and preview copy work');

  // The common IR is the UNCONDITIONAL render path (ADR-0017): the Markdown assertions above (H1, MathJax SVG,
  // Mermaid SVG, badge layout, preview copy) already flow through render-ir, so they ARE the end-to-end
  // IR-render verification. Byte-parity of the HAST round-trip is proven separately by ir.parity-test. (The
  // retired :vv/ir flag's off<->on A/B toggle check was removed together with the flag.)

  // --- office via the common IR: a synthetic office document renders through render-office-ir — its headings
  // gain slug ids (the Contents TOC office lacked) and the shared GitHub-allowlist sanitizer strips dangerous
  // markup (script / on* / javascript:). IR is the default, so simply opening the office doc exercises it.
  const officeDocPath = path.join(ROOT, 'test', 'fixtures', 'smoke-report.docx');
  state.contentByPath.set(officeDocPath, {
    path: officeDocPath,
    kind: 'office',
    html: '<h1>Quarterly Report</h1><p onclick="evil()">Body text.</p><h2>Summary</h2>'
        + '<script>bad()</script><a href="javascript:hack()">link</a>'
  });
  win.webContents.send('vv:open-files', { paths: [officeDocPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Quarterly Report'`),
    'office document renders via the common IR'
  );
  const office = await evalIn(win, `(() => {
    const b = document.querySelector('.markdown-body');
    return {
      h1: b.querySelector('h1') ? b.querySelector('h1').textContent.trim() : null,
      ids: Array.from(b.querySelectorAll('h1,h2')).map((h) => h.id),
      hasScript: /<script/i.test(b.innerHTML),
      hasOnclick: /onclick/i.test(b.innerHTML),
      hasJsUrl: /javascript:/i.test(b.innerHTML)
    };
  })()`);
  assert.strictEqual(office.h1, 'Quarterly Report', 'office renders its heading via the common IR');
  assert.deepStrictEqual(office.ids, ['quarterly-report', 'summary'], 'office headings gain slug ids (a Contents TOC) via IR');
  assert.strictEqual(office.hasScript, false, 'office IR sanitizer strips <script>');
  assert.strictEqual(office.hasOnclick, false, 'office IR sanitizer strips on* handlers');
  assert.strictEqual(office.hasJsUrl, false, 'office IR sanitizer strips javascript: URLs');
  console.log('[ok] office renders via the common IR — heading TOC + shared sanitizer (script/on*/javascript: stripped)');
  // re-focus the markdown feature doc so the Copy-LaTeX tests below operate on it
  win.webContents.send('vv:open-files', { paths: [featureDocPath] });
  await waitFor(() => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Markdown Features'`),
    'return to the markdown feature document');

  // Copy LaTeX (path 1): Ctrl+C over a paragraph containing inline math must substitute the rendered
  // equation with its raw $…$ source — prose preserved, no glyphs, no leaked assistive MathML ("x2").
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const p = document.querySelector('.markdown-body .vv-math-inline').closest('p');
    const selection = window.getSelection();
    selection.removeAllRanges();
    const range = document.createRange();
    range.selectNodeContents(p);
    selection.addRange(range);
    return true;
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C must be handled for a math selection');
  const mathCopied = await waitFor(
    () => (state.lastCopiedText && state.lastCopiedText.includes('$x^2$')) ? state.lastCopiedText : null,
    'inline math Ctrl+C copies LaTeX ($x^2$)'
  );
  assert.strictEqual(mathCopied.trim(), 'Inline math $x^2$ and display math:',
    'math selection copies prose with the equation substituted by inline LaTeX (no glyphs, no MathML leak)');

  // Copy LaTeX (path 2): right-click a rendered equation → "Copy LaTeX" is offered and copies the $$…$$
  // source. Drive it by keyboard (Home focuses the first item, Enter activates) like the other menu tests.
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const el = document.querySelector('.markdown-body .vv-math-display');
    const r = el.getBoundingClientRect();
    el.dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true, cancelable: true, button: 2, clientX: r.left + 4, clientY: r.top + 4
    }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-ctx-menu'))`), 'math context menu');
  const mathMenuHome = await dispatchMenuKey(win, 'Home');
  assert.strictEqual(mathMenuHome.handled, true, 'Home must be handled by the math context menu');
  // The .vv-menu-item-focused class lands on the next reagent render, so wait for it (matches the tab-menu test).
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() === 'Copy LaTeX'`),
    'right-clicking an equation offers "Copy LaTeX" as the first item'
  );
  await dispatchMenuKey(win, 'Enter');
  await waitFor(() => state.lastCopiedText === '$$\\frac{a}{b}$$', 'Copy LaTeX menu item copies display LaTeX');
  console.log('[ok] math copies as LaTeX via Ctrl+C selection and the Copy LaTeX menu item');

  // ---- Raw HTML support (rehype-raw + rehype-sanitize, GitHub allowlist) + sanitization ----
  // GFM docs (e.g. libdictenstein) embed diagrams as raw <img> tags; these must render, with relative src
  // rewritten to file://. Dangerous raw HTML (script/on*-handlers/javascript:) must be stripped so a
  // malicious .md cannot script the privileged renderer.
  const rawHtmlDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-rawhtml-'));
  fs.mkdirSync(path.join(rawHtmlDir, 'diagrams'), { recursive: true });
  fs.writeFileSync(path.join(rawHtmlDir, 'diagrams', 'fig.svg'),
    '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20" fill="#3b82f6"/></svg>');
  const rawHtmlDoc = path.join(rawHtmlDir, 'raw-html.md');
  state.contentByPath.set(rawHtmlDoc, {
    path: rawHtmlDoc, kind: 'markdown',
    text: [
      '# Raw HTML', '',
      '<img src="diagrams/fig.svg" alt="a figure" width="100%"/>', '',
      '<details><summary>more</summary>hidden body</details>', '',
      '<table><thead><tr><th>H</th></tr></thead><tbody><tr><td>cell</td></tr></tbody></table>', '',
      'E = mc<sup>2</sup> and H<sub>2</sub>O', '',
      '<script>window.__vvxss = true;</script>',
      '<img src="does-not-exist" onerror="window.__vvxss = true;">',
      '<a href="javascript:window.__vvxss = true;">click</a>', ''
    ].join('\n'),
    stamp: Date.now()
  });
  await evalIn(win, `window.__vvxss = false`);
  win.webContents.send('vv:open-files', { paths: [rawHtmlDoc] });
  await waitFor(() => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Raw HTML'`), 'raw-html fixture');
  // Renders: raw <img> relative src rewritten to file://, width preserved; other GitHub-allowed tags render.
  const rawImg = await evalIn(win, `(() => {
    const img = document.querySelector('.markdown-body img[alt="a figure"]');
    return img ? { src: img.getAttribute('src') || '', width: img.getAttribute('width') } : null;
  })()`);
  assert.ok(rawImg, 'raw <img> tag renders as a real element');
  assert.ok(rawImg.src.startsWith('file://') && rawImg.src.includes('diagrams/fig.svg'),
    'raw <img> relative src rewritten to file://: ' + rawImg.src);
  assert.strictEqual(rawImg.width, '100%', 'raw <img> width="100%" preserved through the sanitizer');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.markdown-body details > summary'))`), true, 'raw <details><summary> renders');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.markdown-body table td'))`), true, 'raw <table> renders');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.markdown-body sup') && document.querySelector('.markdown-body sub'))`), true, 'raw <sup>/<sub> render');
  // Sanitized: no script/onerror/javascript:, no XSS fired, window.vv bridge intact.
  const xss = await evalIn(win, `(() => ({
    script: Boolean(document.querySelector('.markdown-body script')),
    onerror: Boolean(Array.from(document.querySelectorAll('.markdown-body img')).find(i => i.hasAttribute('onerror'))),
    jsHref: Boolean(Array.from(document.querySelectorAll('.markdown-body a')).find(a => (a.getAttribute('href')||'').toLowerCase().startsWith('javascript:'))),
    fired: window.__vvxss === true,
    vvIntact: typeof window.vv === 'object' && window.vv !== null
  }))()`);
  assert.strictEqual(xss.script, false, '<script> stripped by the sanitizer');
  assert.strictEqual(xss.onerror, false, 'onerror handler stripped by the sanitizer');
  assert.strictEqual(xss.jsHref, false, 'javascript: href stripped by the sanitizer');
  assert.strictEqual(xss.fired, false, 'no injected script executed (window.__vvxss stayed false)');
  assert.strictEqual(xss.vvIntact, true, 'window.vv bridge not clobbered by author id/name');
  console.log('[ok] raw HTML renders (img/table/details/sub/sup); script/onerror/javascript: sanitized; no XSS');

  // Standalone raw block <img> figures must be CENTERED like markdown ![]() images. A raw block <img> is
  // wrapped as <a.vv-figure-link><img></a> that is a DIRECT child of .markdown-body (never inside a <p>), so
  // the new `.markdown-body > a.vv-figure-link` rule centers it. figures.cljs sizes the tiny fixture SVG
  // narrow, so real, equal side gaps prove centering.
  await waitFor(() => evalIn(win, `(() => {
    const img = document.querySelector('.markdown-body > a.vv-figure-link > img[alt="a figure"]');
    const w = img && img.getBoundingClientRect().width;
    return Boolean(w) && w < 200;
  })()`), 'raw <img> figure sized narrow by figures.cljs');
  const fig = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const img  = document.querySelector('.markdown-body > a.vv-figure-link > img[alt="a figure"]');
    if (!body || !img) return null;
    const wrap = img.parentElement;
    const bs = getComputedStyle(body);
    const b = body.getBoundingClientRect(), r = img.getBoundingClientRect();
    const leftGap  = r.left - (b.left + (parseFloat(bs.paddingLeft) || 0));
    const rightGap = (b.right - (parseFloat(bs.paddingRight) || 0)) - r.right;
    return { directChild: wrap.parentElement === body, imgWidth: Math.round(r.width),
             leftGap: Math.round(leftGap), rightGap: Math.round(rightGap) };
  })()`);
  assert.ok(fig, 'raw <img> figure + its .vv-figure-link wrapper exist');
  assert.strictEqual(fig.directChild, true, 'raw block <img> wrapper is a direct child of .markdown-body (not inside a <p>)');
  assert.ok(fig.leftGap > 4 && fig.rightGap > 4, `figure has real side gaps (centered, not full-width): L=${fig.leftGap} R=${fig.rightGap}`);
  assert.ok(Math.abs(fig.leftGap - fig.rightGap) <= 2, `raw <img> figure is centered (leftGap≈rightGap): ${fig.leftGap} vs ${fig.rightGap}`);
  console.log('[ok] raw <img> block figures are centered');

  // Regression (Bug 2) — the block-figure frame carries the #fafafa backdrop (--vv-figure-bg) so a transparent
  // embedded SVG diagram reads on any theme (the backdrop only shows through the transparent areas).
  const figureBackdrop = await evalIn(win, `getComputedStyle(document.querySelector('.markdown-body > a.vv-figure-link')).backgroundColor`);
  assert.strictEqual(figureBackdrop, 'rgb(250, 250, 250)', `block figure frame must carry the #fafafa backdrop (got ${figureBackdrop})`);
  console.log('[ok] block figures carry the #fafafa diagram backdrop (transparent-SVG legibility)');

  const sourceDocPath = path.join(ROOT, 'test', 'fixtures', 'source-copy.toml');
  state.contentByPath.set(sourceDocPath, {
    path: sourceDocPath,
    kind: 'source',
    text: '[package]\nname = "demo"\n',
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [sourceDocPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-source .cm-line')?.textContent.includes('[package]')`),
    'source copy fixture'
  );
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const line = Array.from(document.querySelectorAll('.vv-source .cm-line'))
      .find((node) => node.textContent.includes('name = "demo"'));
    if (!line) {
      return false;
    }
    const range = document.createRange();
    range.selectNodeContents(line);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    return true;
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'C', { ctrlKey: true, shiftKey: true }), true,
    'Ctrl+Shift+C must be handled for source selections');
  await waitFor(() => state.lastCopiedText === 'name = "demo"', 'source Ctrl+Shift+C clipboard write');
  console.log('[ok] source Ctrl+Shift+C copies selected text');

  const scrollDocPath = path.join(ROOT, 'test', 'fixtures', 'scroll-jank.md');
  const scrollSvg = encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="280" viewBox="0 0 640 280">' +
      '<rect width="640" height="280" fill="#1c1f24"/>' +
      '<text x="320" y="150" font-size="40" fill="#ffffff" text-anchor="middle">diagram</text>' +
    '</svg>'
  );
  const filler = Array.from({ length: 18 }, (_value, index) =>
    `Paragraph ${index + 1}. This line gives the preview enough height for scroll-spy updates.`
  ).join('\n\n');
  state.contentByPath.set(scrollDocPath, {
    path: scrollDocPath,
    kind: 'markdown',
    text: `# Top\n\n${filler}\n\n![Diagram](data:image/svg+xml,${scrollSvg})\n\n## After Image\n\n${filler}\n\n## End\n\nDone.`,
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [scrollDocPath] });
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body img'))`),
    'scroll-jank markdown document'
  );
  await evalIn(win, `(() => { const ui = window.__vvdb().ui;
    if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'contents')
      document.querySelector('[data-vv-rail="contents"]').click();
    return true; })()`);
  await waitFor(
    () => evalIn(win, `document.querySelector('[data-vv-rail="contents"]').classList.contains('vv-rail-btn-active')`),
    'Contents sidebar selected'
  );
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-toc-item'))`),
    'scroll-jank table of contents'
  );
  const scrollStability = await evalIn(win, `(() => new Promise((resolve) => {
    const content = document.querySelector('.vv-content');
    const body = document.querySelector('.markdown-body');
    const image = body?.querySelector('img');
    const menubarTop = document.querySelector('.vv-menubar')?.getBoundingClientRect().top ?? null;
    const tabsTop = document.querySelector('.vv-tabs')?.getBoundingClientRect().top ?? null;
    const uriTop = document.querySelector('.vv-uribar')?.getBoundingClientRect().top ?? null;
    let childListMutations = 0;
    const observer = new MutationObserver((records) => {
      childListMutations += records.filter((record) => record.type === 'childList').length;
    });
    observer.observe(body, { childList: true });
    content.scrollTop = Math.max(0, content.scrollHeight - content.clientHeight);
    content.dispatchEvent(new Event('scroll', { bubbles: true }));
    requestAnimationFrame(() => {
      setTimeout(() => {
        observer.disconnect();
        resolve({
          childListMutations,
          sameImage: image === body.querySelector('img'),
          menubarTop,
          tabsStillBelowMenu: document.querySelector('.vv-tabs').getBoundingClientRect().top > menubarTop,
          uriStillBelowTabs: document.querySelector('.vv-uribar').getBoundingClientRect().top > tabsTop,
          contentStillBelowUri: document.querySelector('.vv-content').getBoundingClientRect().top > uriTop,
          activeHeading: window.__vvdb().ui['active-heading'] || null
        });
      }, 250);
    });
  }))()`);
  assert.strictEqual(scrollStability.childListMutations, 0, 'scrolling must not rebuild markdown body children');
  assert.strictEqual(scrollStability.sameImage, true, 'scrolling must not recreate preview image nodes');
  assert.strictEqual(scrollStability.tabsStillBelowMenu, true, 'tab bar must stay below menu bar while scrolling images');
  assert.strictEqual(scrollStability.uriStillBelowTabs, true, 'URI bar must stay below tab bar while scrolling images');
  assert.strictEqual(scrollStability.contentStillBelowUri, true, 'content pane must stay below URI bar while scrolling images');
  assert.ok(scrollStability.activeHeading, 'scrolling must still update the active TOC heading');
  console.log('[ok] markdown image scrolling keeps preview DOM and chrome stable');

  const localSvgFixture = createLocalSvgScrollFixture();
  state.contentByPath.set(localSvgFixture.docPath, {
    path: localSvgFixture.docPath,
    kind: 'markdown',
    text: localSvgFixture.text,
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [localSvgFixture.docPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local SVG Scroll'`),
    'local SVG scroll markdown document'
  );
  await waitFor(
    () => evalIn(win, `(() => {
      const imgs = Array.from(document.querySelectorAll('.markdown-body img'));
      return imgs.length === 3 && imgs.every((img) => img.complete && img.naturalWidth > 0);
    })()`),
    'local SVG image loads'
  );
  await waitFor(
    () => evalIn(win, `Array.from(document.querySelectorAll('.markdown-body img'))
      .every((img) => img.style.width && img.style.aspectRatio && !img.style.height && img.getAttribute('draggable') === 'false')`),
    'local SVG figure sizing (width + aspect-ratio, no explicit px height)'
  );
  // NO-FLASH proof (ADR-0022): figures are sized in the HTML string (pre-DOM, apply-posts → scale-figures-html),
  // so nothing mutates <img> geometry after first paint. Observe style/dimension attributes across the body
  // subtree and assert zero mutations settle in — i.e. no visible post-insert re-scale.
  const noFlash = await evalIn(win, `(() => new Promise((resolve) => {
    const body = document.querySelector('.markdown-body');
    let attrMutations = 0;
    const obs = new MutationObserver((records) => {
      attrMutations += records.filter((r) => r.type === 'attributes').length;
    });
    obs.observe(body, { attributes: true, subtree: true, attributeFilter: ['style', 'width', 'height', 'draggable'] });
    setTimeout(() => { obs.disconnect(); resolve({ attrMutations }); }, 400);
  }))()`);
  assert.strictEqual(noFlash.attrMutations, 0, 'figures must be pre-sized in the HTML — no post-insert style/dimension mutation (no flash)');
  console.log('[ok] local SVG figures are pre-sized — no post-insert re-scale (no flash)');
  const localSvgStart = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    window.__vvSvgScrollImages = imgs;
    window.__vvSvgScrollChildListMutations = 0;
    window.__vvSvgScrollObserver?.disconnect?.();
    window.__vvSvgScrollObserver = new MutationObserver((records) => {
      window.__vvSvgScrollChildListMutations += records.filter((record) => record.type === 'childList').length;
    });
    window.__vvSvgScrollObserver.observe(body, { childList: true });
    return {
      imageCount: imgs.length,
      dims: imgs.map((img) => {
        const rect = img.getBoundingClientRect();
        return {
          width: Math.round(rect.width),
          height: Math.round(rect.height),
          styleWidth: img.style.width,
          styleHeight: img.style.height,
          aspectRatio: img.style.aspectRatio
        };
      })
    };
  })()`);
  assert.strictEqual(localSvgStart.imageCount, 3, 'local SVG fixture must render three images');
  showForTest(win);
  await delay(100);
  win.focus();
  win.webContents.focus();
  for (let i = 0; i < localSvgStart.imageCount; i++) {
    const wheelTarget = await evalIn(win, `(() => {
      const content = document.querySelector('.vv-content');
      const img = document.querySelectorAll('.markdown-body img')[${i}];
      const maxTop = Math.max(0, content.scrollHeight - content.clientHeight);
      const targetTop = Math.max(0, Math.min(maxTop - 1, img.offsetTop - (content.clientHeight / 2) + (img.offsetHeight / 2)));
      content.scrollTop = targetTop;
      const rect = img.getBoundingClientRect();
      return {
        before: content.scrollTop,
        x: Math.round(rect.left + Math.max(1, Math.min(rect.width - 1, rect.width / 2))),
        y: Math.round(rect.top + Math.max(1, Math.min(rect.height - 1, rect.height / 2)))
      };
    })()`);
    win.webContents.sendInputEvent({ type: 'mouseMove', x: wheelTarget.x, y: wheelTarget.y });
    win.webContents.sendInputEvent({
      type: 'mouseWheel',
      x: wheelTarget.x,
      y: wheelTarget.y,
      deltaY: -480,
      wheelTicksY: -4,
      hasPreciseScrollingDeltas: true,
      canScroll: true
    });
    await waitFor(
      () => evalIn(win, `document.querySelector('.vv-content').scrollTop > ${wheelTarget.before + 8}`),
      `wheel scroll over local SVG ${i + 1}`,
      2000
    );
  }
  const localSvgResult = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    const dims = imgs.map((img) => {
      const rect = img.getBoundingClientRect();
      return {
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        styleWidth: img.style.width,
        styleHeight: img.style.height,
        aspectRatio: img.style.aspectRatio
      };
    });
    const result = {
      childListMutations: window.__vvSvgScrollChildListMutations,
      sameImages: Array.isArray(window.__vvSvgScrollImages) &&
        window.__vvSvgScrollImages.length === imgs.length &&
        imgs.every((img, index) => img === window.__vvSvgScrollImages[index]),
      dims,
      docStillOpen: document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local SVG Scroll',
      dedicatedImageView: Boolean(document.querySelector('.vmd-image-view'))
    };
    window.__vvSvgScrollObserver?.disconnect?.();
    window.__vvSvgScrollObserver = null;
    window.__vvSvgScrollImages = null;
    return result;
  })()`);
  assert.strictEqual(localSvgResult.childListMutations, 0, 'wheel scrolling local SVGs must not rebuild markdown body children');
  assert.strictEqual(localSvgResult.sameImages, true, 'wheel scrolling local SVGs must not recreate image nodes');
  assert.strictEqual(localSvgResult.docStillOpen, true, 'wheel scrolling local SVGs must keep the markdown document open');
  assert.strictEqual(localSvgResult.dedicatedImageView, false, 'wheel scrolling local SVGs must not open the dedicated image view');
  assert.deepStrictEqual(localSvgResult.dims, localSvgStart.dims, 'local SVG dimensions must stay stable while scrolling over images');
  console.log('[ok] local SVG markdown images scroll without layout churn');

  // A figure whose SVG carries RELATIVE font sizes must still be font-matched, not blown up to the column.
  // The column cap (max-width:100%) HIDES this class of bug — an over-wide figure renders as "full column with
  // magnified text", which reads as intentional — so the load-bearing assertion is on the PRE-CLAMP inline
  // width that figures.cljs stamps, not on the rendered rect.
  const relFontFixture = createRelativeFontSvgFixture();
  state.contentByPath.set(relFontFixture.docPath, {
    path: relFontFixture.docPath, kind: 'markdown', text: relFontFixture.text, stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [relFontFixture.docPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Relative Font SVG'`),
    'relative-font SVG markdown document'
  );
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body img[alt="relative-font d2 figure"]')?.style.width)`),
    'relative-font SVG figure sized'
  );
  const relFont = await evalIn(win, `(() => {
    const img = document.querySelector('.markdown-body img[alt="relative-font d2 figure"]');
    const body = document.querySelector('.markdown-body');
    const docFont = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--vv-font-size')) || 15;
    const bs = getComputedStyle(body);
    const column = body.getBoundingClientRect().width
      - (parseFloat(bs.paddingLeft) || 0) - (parseFloat(bs.paddingRight) || 0);
    return {
      docFont,
      styleWidth: img.style.width,
      aspectRatio: img.style.aspectRatio,
      authorWidthAttr: img.getAttribute('width'),
      renderedWidth: Math.round(img.getBoundingClientRect().width),
      column: Math.round(column),
      fontMatched: Math.round(docFont * ${RELATIVE_FONT_VIEWBOX.width} / ${RELATIVE_FONT_VIEWBOX.textFontPx}) + 'px',
      unitBlind: Math.round(docFont * ${RELATIVE_FONT_VIEWBOX.width} / 1) + 'px'
    };
  })()`);
  assert.strictEqual(relFont.styleWidth, relFont.fontMatched,
    `dominant font must be the 16px text labels, not the rounded 1em CSS: width ${relFont.styleWidth} (expected ${relFont.fontMatched})`);
  assert.notStrictEqual(relFont.styleWidth, relFont.unitBlind,
    'relative CSS font sizes must never vote for the dominant font (the unit-blind blow-up)');
  assert.strictEqual(relFont.aspectRatio, `${RELATIVE_FONT_VIEWBOX.width} / ${RELATIVE_FONT_VIEWBOX.height}`,
    'font-matched figure keeps its viewBox aspect-ratio');
  assert.strictEqual(relFont.authorWidthAttr, '80%', 'author width attribute survives the sanitizer');
  assert.ok(relFont.column > parseFloat(relFont.fontMatched) + 8,
    `the test column (${relFont.column}px) must be wider than the font-matched figure, else clamping masks the bug`);
  assert.ok(relFont.renderedWidth < relFont.column,
    `font-matched figure must not fill the column: ${relFont.renderedWidth}px of ${relFont.column}px`);
  assert.strictEqual(relFont.renderedWidth, parseFloat(relFont.fontMatched),
    'font-matched width wins over the author width="80%" attribute (ADR-0022)');
  console.log(`[ok] SVG with relative CSS font sizes is font-matched (${relFont.styleWidth}, not ${relFont.unitBlind})`);

  // RASTER images "jumpy when scrolling" fix: PNG/JPG/etc. now reserve their layout box from intrinsic dims
  // (width/height attributes, header-parsed) so they don't reflow (pop from ~0 → full height) as they decode
  // while scrolling. Previously raster images were left dimensionless (clear-size!) → the jump.
  const rasterFixture = createLocalRasterScrollFixture();
  state.contentByPath.set(rasterFixture.docPath, {
    path: rasterFixture.docPath,
    kind: 'markdown',
    text: rasterFixture.text,
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [rasterFixture.docPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local Raster Scroll'`),
    'local raster scroll markdown document'
  );
  // the box must be reserved from intrinsic dims (width/height ATTRIBUTES), independent of byte decode
  await waitFor(
    () => evalIn(win, `Array.from(document.querySelectorAll('.markdown-body img'))
      .every((img) => img.hasAttribute('width') && img.hasAttribute('height')
                      && Number(img.getAttribute('width')) > 0 && Number(img.getAttribute('height')) > 0)`),
    'raster images get intrinsic width/height attributes (layout box reserved)'
  );
  const rasterAttrs = await evalIn(win, `Array.from(document.querySelectorAll('.markdown-body img'))
    .map((img) => img.getAttribute('width') + 'x' + img.getAttribute('height'))`);
  assert.deepStrictEqual(rasterAttrs, ['800x200', '200x600', '400x400'],
    'raster images must carry their intrinsic dimensions (header-parsed PNG IHDR) as width/height attributes');
  await waitFor(
    () => evalIn(win, `(() => {
      const imgs = Array.from(document.querySelectorAll('.markdown-body img'));
      return imgs.length === 3 && imgs.every((img) => img.complete && img.naturalWidth > 0);
    })()`),
    'local raster images load'
  );
  const rasterStart = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    window.__vvRasterImages = imgs;
    window.__vvRasterMutations = 0;
    window.__vvRasterObserver?.disconnect?.();
    window.__vvRasterObserver = new MutationObserver((records) => {
      window.__vvRasterMutations += records.filter((record) => record.type === 'childList').length;
    });
    window.__vvRasterObserver.observe(body, { childList: true });
    return {
      imageCount: imgs.length,
      dims: imgs.map((img) => {
        const rect = img.getBoundingClientRect();
        return { width: Math.round(rect.width), height: Math.round(rect.height),
                 ratio: Math.round((rect.width / rect.height) * 100) };
      })
    };
  })()`);
  assert.strictEqual(rasterStart.imageCount, 3, 'local raster fixture must render three images');
  // reserved boxes must match the intrinsic aspect ratios (800/200=4.00, 200/600=0.33, 400/400=1.00),
  // responsively (width clamped to the column, height from the ratio) — this is what stops the jump
  assert.deepStrictEqual(rasterStart.dims.map((d) => d.ratio), [400, 33, 100],
    'reserved raster boxes must match their intrinsic aspect ratios');
  showForTest(win);
  await delay(100);
  win.focus();
  win.webContents.focus();
  for (let i = 0; i < rasterStart.imageCount; i++) {
    const wheelTarget = await evalIn(win, `(() => {
      const content = document.querySelector('.vv-content');
      const img = document.querySelectorAll('.markdown-body img')[${i}];
      const maxTop = Math.max(0, content.scrollHeight - content.clientHeight);
      const targetTop = Math.max(0, Math.min(maxTop - 1, img.offsetTop - (content.clientHeight / 2) + (img.offsetHeight / 2)));
      content.scrollTop = targetTop;
      const rect = img.getBoundingClientRect();
      return {
        before: content.scrollTop,
        x: Math.round(rect.left + Math.max(1, Math.min(rect.width - 1, rect.width / 2))),
        y: Math.round(rect.top + Math.max(1, Math.min(rect.height - 1, rect.height / 2)))
      };
    })()`);
    win.webContents.sendInputEvent({ type: 'mouseMove', x: wheelTarget.x, y: wheelTarget.y });
    win.webContents.sendInputEvent({
      type: 'mouseWheel',
      x: wheelTarget.x,
      y: wheelTarget.y,
      deltaY: -480,
      wheelTicksY: -4,
      hasPreciseScrollingDeltas: true,
      canScroll: true
    });
    await waitFor(
      () => evalIn(win, `document.querySelector('.vv-content').scrollTop > ${wheelTarget.before + 8}`),
      `wheel scroll over local raster ${i + 1}`,
      2000
    );
  }
  const rasterResult = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    const dims = imgs.map((img) => {
      const rect = img.getBoundingClientRect();
      return { width: Math.round(rect.width), height: Math.round(rect.height),
               ratio: Math.round((rect.width / rect.height) * 100) };
    });
    const result = {
      childListMutations: window.__vvRasterMutations,
      sameImages: Array.isArray(window.__vvRasterImages) &&
        window.__vvRasterImages.length === imgs.length &&
        imgs.every((img, index) => img === window.__vvRasterImages[index]),
      dims,
      docStillOpen: document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local Raster Scroll'
    };
    window.__vvRasterObserver?.disconnect?.();
    window.__vvRasterObserver = null;
    window.__vvRasterImages = null;
    return result;
  })()`);
  assert.strictEqual(rasterResult.childListMutations, 0, 'wheel scrolling raster images must not rebuild markdown body children');
  assert.strictEqual(rasterResult.sameImages, true, 'wheel scrolling raster images must not recreate image nodes');
  assert.strictEqual(rasterResult.docStillOpen, true, 'wheel scrolling raster images must keep the markdown document open');
  assert.deepStrictEqual(rasterResult.dims, rasterStart.dims, 'raster image dimensions must stay stable while scrolling over them (no layout jump)');
  console.log('[ok] local raster markdown images reserve a box + scroll without layout jump');

  // ── Feature: bidirectional "Go to source" / "Go to preview" jumps ──────────────────────────────────────
  // The "Local Raster Scroll" markdown doc is still open (in preview). The preview/source right-click menus
  // dispatch these events with a source LINE, and no menu/keyboard affordance carries one, so the only way to
  // drive them is the re-frame global. Dev-only for the same reason as the PDF-reflow block above: the release
  // `:simple` build encapsulates `re_frame`, so an internal dispatch throws ReferenceError there. Release still
  // covers the pure jump math (line → nearest anchored element, and back) via the DOM-free
  // vinary.renderer.source-nav-test unit tests, which run in every build.
  if (!releaseBuild) {
    const dispatchJump = (nsName, evName, line) =>
      evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword(${JSON.stringify(nsName)}, ${JSON.stringify(evName)}), ${line})); true`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body')) && !document.querySelector('.vv-source')`),
      'jump precondition: the raster markdown doc is showing its preview');
    // preview → source: switches the pane to the source view (deferred scroll consumed on create-source-view mount)
    await dispatchJump('source', 'goto-line', 12);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-source .cm-editor')) && !document.querySelector('.vv-content .markdown-body')`),
      '"Go to source" switches the pane to the source view', 8000);
    console.log('[ok] Go to source: preview → source view (deferred line scroll)');
    // source → preview: switches back and lands near the TOP for line 1
    await dispatchJump('preview', 'goto-line', 1);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body')) && !document.querySelector('.vv-source')`),
      '"Go to preview" switches the pane back to the preview', 8000);
    await delay(250);
    const jumpTop = await evalIn(win, `document.querySelector('.vv-content').scrollTop`);
    // "near the top" — the first heading region (allowing for .vv-content padding + the h1 margin); the deep-jump
    // check below (deepTop > jumpTop) is what proves the target line actually steers the scroll position.
    assert.ok(jumpTop < 200, `jumping to line 1 lands near the top of the preview (scrollTop=${jumpTop})`);
    // preview → a deep line: confine-scrolls the preview down to the nearest element (no toggle, already preview)
    await dispatchJump('preview', 'goto-line', 60);
    await delay(300);
    const deepTop = await evalIn(win, `document.querySelector('.vv-content').scrollTop`);
    assert.ok(deepTop > jumpTop, `jumping to a deep line scrolls the preview down (scrollTop ${jumpTop} → ${deepTop})`);
    console.log('[ok] Go to preview: pane follows the target source line (top vs deep)');
    // keyboard / command-palette commands self-gate and jump in the meaningful direction (no click target)
    await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("jump","goto-source"))); true`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-source .cm-editor'))`),
      'keyboard "Go to source" command switches to source', 8000);
    await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("jump","goto-preview"))); true`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body')) && !document.querySelector('.vv-source')`),
      'keyboard "Go to preview" command switches to preview', 8000);
    console.log('[ok] keyboard Go to source / Go to preview commands self-gate and jump');
  }

  // ---- ADR-0040: the blame gutter (window.__vvblame DEV seam; vv:git-blame is mocked above) ----------
  {
    const inSource = await evalIn(win, `Boolean(document.querySelector('.vv-source .cm-editor'))`);
    if (!inSource) {
      await evalIn(win, `window.__vvsource()`);
      await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-source .cm-editor'))`),
        'blame precondition: the source view mounts', 8000);
    }
    await evalIn(win, `window.__vvblame()`);
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-blame-gutter .vv-blame-chip').length > 0`),
      'toggling blame renders gutter chips from the (mocked) hunks', 8000);
    const chip = await evalIn(win, `(() => {
      const chips = Array.from(document.querySelectorAll('.vv-blame-gutter .vv-blame-chip'));
      const head = chips.find((c) => c.textContent.includes('Ada'));
      const dirty = chips.find((c) => c.textContent === 'Uncommitted');
      return { count: chips.length,
               headTitle: head ? head.title : null,
               hasDirty: Boolean(dirty) };
    })()`);
    assert.ok(chip.count > 0, 'blame chips rendered');
    assert.ok(chip.headTitle && chip.headTitle.includes('aaaaaaaa'),
      `a hunk-head chip's tooltip carries the short hash (got ${JSON.stringify(chip.headTitle)})`);
    assert.ok(chip.hasDirty, 'the zero-hash hunk renders as an "Uncommitted" chip');
    assert.ok(state.gitBlameRequests.length > 0, 'the renderer actually asked main over vv:git-blame');
    await evalIn(win, `window.__vvblame()`);
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-blame-gutter .vv-blame-chip').length === 0`),
      'toggling blame off clears the gutter', 8000);
    console.log('[ok] blame gutter: toggle on renders author/uncommitted chips, toggle off clears (ADR-0040)');
    if (!inSource) {
      await evalIn(win, `window.__vvsource()`);
      await waitFor(() => evalIn(win, `!document.querySelector('.vv-source .cm-editor')`),
        'blame arm restores the preview facet', 8000);
    }
  }

  // ---- ADR-0040: the Commit Graph document (mocked vv:git-log/branches; real store, fold, view) ------
  {
    const GG_ROOT = '/fixture/gg-repo';
    const ggUri = `vv-git-graph://${GG_ROOT}`;
    const H = state.ggHashes;
    state.contentByPath.set(ggUri, { path: ggUri, kind: 'git-graph', git: { root: GG_ROOT } });
    await evalIn(win, `window.__vvopen(${JSON.stringify(ggUri)})`);
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-gg-row').length >= 3`),
      'the graph document renders its fixture rows', 8000);
    const gg = await evalIn(win, `(() => {
      const rows = Array.from(document.querySelectorAll('.vv-gg-row'));
      return { count: rows.length,
               firstHash: rows[0] && rows[0].getAttribute('data-hash'),
               rails: document.querySelectorAll('.vv-gg-rail path').length,
               dots: document.querySelectorAll('.vv-gg-dot').length,
               refs: Array.from(document.querySelectorAll('.vv-gg-ref')).map((e) => e.textContent) };
    })()`);
    assert.strictEqual(gg.count, 3, 'three fixture commits render');
    assert.strictEqual(gg.firstHash, H[0], 'newest first');
    assert.strictEqual(gg.dots, 3, 'every row carries its lane dot');
    assert.ok(gg.rails >= 2, 'the linear chain draws connecting rail ink');
    assert.ok(gg.refs.includes('main') && gg.refs.includes('v1.0'),
      `HEAD and tag decorations render as badges (got ${JSON.stringify(gg.refs)})`);
    const key = (k) => evalIn(win, `(() => {
      const gg = document.querySelector('.vv-gg'); gg.focus();
      gg.dispatchEvent(new KeyboardEvent('keydown', { key: ${JSON.stringify(k)}, bubbles: true, cancelable: true }));
      return true; })()`);
    await key('ArrowDown');
    await waitFor(() => evalIn(win,
      `(window.__vvdb().ui.commits.repos[${JSON.stringify(GG_ROOT)}].selection || {}).cursor === ${JSON.stringify(H[0])}`),
      'the first ArrowDown places the cursor on the newest commit', 8000);
    await key('ArrowDown');
    await waitFor(() => evalIn(win,
      `(window.__vvdb().ui.commits.repos[${JSON.stringify(GG_ROOT)}].selection || {}).cursor === ${JSON.stringify(H[1])}`),
      'a second ArrowDown moves the cursor down one row', 8000);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-gg-row-cur[data-hash=${JSON.stringify(H[1])}]'))`),
      'the cursor row carries the vv-gg-row-cur marker in the DOM (next Reagent frame)', 8000);
    await key('Enter');
    await waitFor(() => Promise.resolve(state.gitOpenDiffRequests.length > 0),
      'Enter asks main for the cursor commit\'s diff', 8000);
    const req = state.gitOpenDiffRequests[state.gitOpenDiffRequests.length - 1];
    assert.strictEqual(req.to, H[1], 'the request targets the cursor commit');
    assert.strictEqual(Boolean(req['parent?']), true, 'diff-vs-parent asks main to resolve to^ (R4)');
    console.log('[ok] Commit Graph: rows + badges render, keyboard cursor + Enter drive the diff seam (ADR-0040)');
  }

  // ---- Commits sidebar tab: regression arms for the shadowed-empty? blank-window crash -------------
  // A destructured `empty?` shadowed cljs.core/empty? and was INVOKED — nil on the panel's first
  // render, false once the store was primed — and with no error boundary the React root unmounted
  // (a blank window). No harness ever CLICKED this tab before; these arms close that gap. Selectors
  // use [data-vv-rail=…], the stable anchors that survive the icon-rail migration.
  {
    await evalIn(win, `window.__vvWinErrors = window.__vvWinErrors || [];
      if (!window.__vvWinErrorsWired) { window.__vvWinErrorsWired = true;
        window.addEventListener('error', (e) => window.__vvWinErrors.push(String(e.message))); }
      true`);
    const winErrors = () => evalIn(win, `window.__vvWinErrors`);
    const clearErrors = () => evalIn(win, `window.__vvWinErrors.length = 0; true`);
    const appAlive = async (tag) => {
      assert.deepStrictEqual(await winErrors(), [], `${tag}: no window-level errors recorded`);
      assert.ok(await evalIn(win, `document.getElementById('app').children.length > 0`),
        `${tag}: the React root is still mounted`);
    };
    const railClick = (name) =>
      evalIn(win, `document.querySelector('[data-vv-rail=${JSON.stringify(name)}]').click(); true`);

    // Arm C — no git project open: the empty message, no crash
    await clearErrors();
    assert.strictEqual(await evalIn(win, `(window.__vvdb().ui.projects || []).length`), 0,
      'precondition: no projects are seeded at this point in the run');
    await railClick('commits');
    await waitFor(() => evalIn(win,
      `(document.querySelector('.vv-sidebar-empty') || {}).textContent === 'No git repository open'`),
      'the Commits panel shows its empty state with no git project', 8000);
    await appAlive('arm C');

    // Arm A — a git project appears (the real vv:tree seam) while Commits is the active tab: the
    // root-keyed panel (re)mounts — the exact first render that used to invoke nil
    const CP_ROOT = '/fixture/commits-panel-repo';
    win.webContents.send('vv:tree', { root: CP_ROOT, files: ['README.md'], 'synthetic?': false });
    await waitFor(() => evalIn(win,
      `(window.__vvdb().ui.projects || []).some((p) => p.root === ${JSON.stringify(CP_ROOT)})`),
      'the fixture project lands in app-db', 8000);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-commits'))`),
      'the Commits panel mounts for the seeded repo', 8000);
    await waitFor(() => evalIn(win,
      `document.querySelectorAll('.vv-commits-row').length >= 3
        && document.querySelector('.vv-commits-row').dataset.hash === ${JSON.stringify(state.ggHashes[0])}`),
      'the seeded repo lists the mocked page, newest first', 8000);
    await appAlive('arm A');
    console.log('[ok] Commits tab: empty state + first-mount both render (shadowed-empty? regression)');

    // Arm A′ — leave and return: the primed-store first render (the false-invoking variant)
    await railClick('files');
    await waitFor(() => evalIn(win, `!document.querySelector('.vv-commits')`),
      'switching to Files unmounts the Commits panel', 8000);
    await railClick('commits');
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-commits-row').length >= 3`),
      'returning to Commits re-renders the primed rows', 8000);
    await appAlive('arm A′');
    console.log('[ok] Commits tab: primed-store remount renders (false-variant regression)');

    // Arm B (dev-only, like every dispatch_sync block) — a store primed BEFORE the panel ever
    // mounts: page-0 injected directly, then the repo pinned, then the first mount happens primed
    if (!releaseBuild) {
      const CP_ROOT_B = '/fixture/commits-panel-repo-b';
      const BH = ['a'.repeat(40), 'b'.repeat(40)];
      win.webContents.send('vv:tree', { root: CP_ROOT_B, files: ['README.md'], 'synthetic?': false });
      await waitFor(() => evalIn(win,
        `(window.__vvdb().ui.projects || []).some((p) => p.root === ${JSON.stringify(CP_ROOT_B)})`),
        'the second fixture project lands', 8000);
      const payloadJson = JSON.stringify({ commits: [
        { hash: BH[0], parents: [BH[1]], 'author-name': 'Pri', 'author-email': 'p@x',
          'author-date': '2026-08-11T12:00:00+02:00', 'committer-date': '2026-08-11T12:00:00+02:00',
          refs: [], subject: 'primed newest', body: '' },
        { hash: BH[1], parents: [], 'author-name': 'Pri', 'author-email': 'p@x',
          'author-date': '2026-08-11T11:00:00+02:00', 'committer-date': '2026-08-11T11:00:00+02:00',
          refs: [], subject: 'primed root', body: '' }], exhausted: true });
      await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(
        cljs.core.keyword("commits","log-received"), ${JSON.stringify(CP_ROOT_B)}, null, true,
        cljs.core.js__GT_clj(JSON.parse(${JSON.stringify(payloadJson)}),
                             cljs.core.keyword("keywordize-keys"), true))); true`);
      await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(
        cljs.core.keyword("commits","set-root"), ${JSON.stringify(CP_ROOT_B)})); true`);
      await railClick('files');
      await waitFor(() => evalIn(win, `!document.querySelector('.vv-commits')`),
        'arm B: the panel unmounts before the primed first mount', 8000);
      await railClick('commits');
      await waitFor(() => evalIn(win,
        `(document.querySelector('.vv-commits-row') || { dataset: {} }).dataset.hash === ${JSON.stringify(BH[0])}`),
        'the pre-primed store renders its own newest hash on first mount', 8000);
      await appAlive('arm B');
      await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(
        cljs.core.keyword("commits","set-root"), null)); true`);
      console.log('[ok] Commits tab: pre-primed store first-mount renders (arm B, dev)');
    }
    // restore the pre-section sidebar state for the later sections
    await railClick('files');
    await waitFor(() => evalIn(win, `!document.querySelector('.vv-commits')`),
      'the regression section restores the Files panel', 8000);
  }

  // ---- ADR-0042: the opened-commit highlight DERIVES from the active document ----------------------
  // The strongly highlighted row must be the commit whose diff IS the active tab's document —
  // history Back, tab switches, and pair diffs retarget it live, and plain clicks no longer write
  // the stored (now Ctrl/Shift-only) multi-select. Release-safe: DOM clicks, real chords, and the
  // unconditional __vvopen/__vvdb hooks only.
  {
    const railClick = (name) =>
      evalIn(win, `document.querySelector('[data-vv-rail=${JSON.stringify(name)}]').click(); true`);
    const F1_ROOT = '/fixture/f1-open-repo';
    const H = state.ggHashes;   // the vv:git-log mock serves this 3-commit page for ANY root
    // a real project member: while it is ACTIVE, derive-root picks F1_ROOT (no pin, release-safe)
    const memberPath = `${F1_ROOT}/README.md`;
    state.contentByPath.set(memberPath, { path: memberPath, kind: 'text', text: 'f1 member file' });
    win.webContents.send('vv:tree', { root: F1_ROOT, files: ['README.md'], 'synthetic?': false });
    await waitFor(() => evalIn(win,
      `(window.__vvdb().ui.projects || []).some((p) => p.root === ${JSON.stringify(F1_ROOT)})`),
      'the ADR-0042 fixture project lands in app-db', 8000);
    await evalIn(win, `window.__vvopen(${JSON.stringify(memberPath)})`);
    await waitFor(() => evalIn(win, `document.body.textContent.includes('f1 member file')`),
      'the member document is active (the panel derives F1_ROOT from it)', 8000);

    // two spill fixtures carrying the {root from to range} facts the real main registers
    const spillDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-git-spill-'));
    tempDirs.push(spillDir);
    const DIFF_TEXT = 'diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n';
    const parentSpill = path.join(spillDir, `${H[0].slice(0, 7)}.diff`);
    const rangeSpill = path.join(spillDir, `${H[2].slice(0, 7)}..${H[1].slice(0, 7)}.diff`);
    fs.writeFileSync(parentSpill, DIFF_TEXT);
    fs.writeFileSync(rangeSpill, DIFF_TEXT);
    state.contentByPath.set(parentSpill, { path: parentSpill, kind: 'diff', text: DIFF_TEXT,
      stdin: true, git: { root: F1_ROOT, from: H[1], to: H[0], range: false } });
    state.contentByPath.set(rangeSpill, { path: rangeSpill, kind: 'diff', text: DIFF_TEXT,
      stdin: true, git: { root: F1_ROOT, from: H[2], to: H[1], range: true } });
    state.gitDiffSpills = { parent: parentSpill, range: rangeSpill };

    await railClick('commits');
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-commits-row').length >= 3`),
      'the Commits panel lists the fixture page for the derived root', 8000);

    // 1 — a plain click opens the parent diff; -open DERIVES from the now-active doc; NO stored
    //     selection is written
    await evalIn(win,
      `document.querySelector('.vv-commits-row[data-hash=${JSON.stringify(H[0])}]').click(); true`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-commits-row-open[data-hash=${JSON.stringify(H[0])}]'))`),
      'the clicked commit row carries -open once its diff is the active document', 8000);
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-commits-row-sel').length`), 0,
      'a plain click writes no stored selection (ADR-0042)');
    const f1sel = await evalIn(win,
      `(((window.__vvdb().ui.commits.repos || {})[${JSON.stringify(F1_ROOT)}] || {}).selection || {})`);
    assert.ok(!f1sel.selected || f1sel.selected.length === 0,
      `:selected stays empty on a plain click (got ${JSON.stringify(f1sel.selected)})`);

    // 2 — the highlight minds the active TAB: a blank tab clears it, returning restores it.
    //     Closing the blank tab reveals its POSITIONAL neighbour (right, else left — nav/close), and by this point
    //     in the suite earlier arms have left other tabs open (the blank tab is appended after the Office
    //     document's tab, not next to the diff's), so the arm returns to the diff tab explicitly: what it proves
    //     is that -open follows the active tab, not which neighbour close-reveal picks (that is pinned by the
    //     blank-tab arms above).
    const diffTabId = await evalIn(win, `window.__vvdb().ui['active-tab']`);
    await sendChord(win, 'T', ['control']);
    await waitFor(() => evalIn(win, `!document.querySelector('.vv-commits-row-open')`),
      'a blank tab holds no commit diff → no row is -open', 8000);
    await sendChord(win, 'W', ['control']);
    await waitFor(() => evalIn(win, `!window.__vvdb().ui.tabs.some((t) => t.uri == null)`),
      'Ctrl+W closes the blank tab', 8000);
    await clickTabId(diffTabId, 'return to the commit diff tab');
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-commits-row-open[data-hash=${JSON.stringify(H[0])}]'))`),
      'returning to the diff tab → its row is -open again', 8000);

    // 3 — Ctrl-click marking is STORED state; "Diff selected" opens the PAIR diff whose -open
    //     claims BOTH endpoint rows (range true)
    const ctrlClickRow = (h) => evalIn(win, `(() => {
      const row = document.querySelector('.vv-commits-row[data-hash=${JSON.stringify(h)}]');
      row.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }));
      return true; })()`);
    await ctrlClickRow(H[1]);
    await ctrlClickRow(H[2]);
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-commits-row-sel').length === 2`),
      'Ctrl-clicks mark two rows with the multi-select ring', 8000);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-commits-pill:not(.vv-commits-graph-btn)'))`),
      'exactly two marks surface the "Diff selected" pill', 8000);
    await evalIn(win,
      `document.querySelector('.vv-commits-pill:not(.vv-commits-graph-btn)').click(); true`);
    await waitFor(() => evalIn(win, `(() => {
      const open = Array.from(document.querySelectorAll('.vv-commits-row-open'))
        .map((r) => r.dataset.hash).sort();
      return JSON.stringify(open) ===
        JSON.stringify([${JSON.stringify(H[1])}, ${JSON.stringify(H[2])}].sort());
    })()`), 'the pair diff -open claims BOTH endpoint rows (range? true)', 8000);
    assert.ok(await evalIn(win,
      `!document.querySelector('.vv-commits-row-open[data-hash=${JSON.stringify(H[0])}]')`),
      'the previously opened parent commit no longer carries -open');
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-commits-row-sel').length`), 2,
      'the Ctrl/Shift marking is stored state and survives the pair-diff navigation');

    // 4 — history Back retargets the highlight step by step (the reported stale-highlight bug)
    await sendChord(win, 'Left', ['alt']);   // rangeSpill → parentSpill
    await waitFor(() => evalIn(win, `(() => {
      const open = Array.from(document.querySelectorAll('.vv-commits-row-open')).map((r) => r.dataset.hash);
      return open.length === 1 && open[0] === ${JSON.stringify(H[0])}; })()`),
      'Back to the parent diff retargets -open to its single commit', 8000);
    await sendChord(win, 'Left', ['alt']);   // parentSpill → memberPath
    await waitFor(() => evalIn(win, `!document.querySelector('.vv-commits-row-open')`),
      'Back to the member document clears every -open (the highlight minds the URI)', 8000);

    // 5 — the Commit Graph document: plain click = cursor only; Escape clears the marks (cursor
    //     kept); double-click opens the diff (the sidebar panel derives its -open); Back leaves
    //     NO sticky highlight on the graph rows
    const ggUri = `vv-git-graph://${F1_ROOT}`;
    state.contentByPath.set(ggUri, { path: ggUri, kind: 'git-graph', git: { root: F1_ROOT } });
    await evalIn(win, `window.__vvopen(${JSON.stringify(ggUri)})`);
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-gg-row').length >= 3`),
      'the graph document renders for the fixture root', 8000);
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-gg-row-open').length`), 0,
      'the graph document itself is not a commit diff → no graph row is -open');
    // the PANEL-made Ctrl-marks (arm 3) render here too: one stored selection per repo, both
    // surfaces (R3) — and a plain click must neither extend nor clear it
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-gg-row-sel').length`), 2,
      'the panel-made marks render in the graph too (one stored selection per repo, R3)');
    await evalIn(win,
      `document.querySelector('.vv-gg-row[data-hash=${JSON.stringify(H[0])}]').click(); true`);
    await waitFor(() => evalIn(win,
      `(((window.__vvdb().ui.commits.repos || {})[${JSON.stringify(F1_ROOT)}] || {}).selection || {}).cursor === ${JSON.stringify(H[0])}`),
      'a plain graph click moves the keyboard cursor', 8000);
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-gg-row-sel').length`), 2,
      'a plain graph click neither writes nor clears the stored marking (ADR-0042)');
    const escapeGg = () => evalIn(win, `(() => { const gg = document.querySelector('.vv-gg'); gg.focus();
      gg.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      return true; })()`);
    await escapeGg();
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-gg-row-sel').length === 0`),
      'Escape clears the multi-select marks', 8000);
    assert.strictEqual(await evalIn(win,
      `(((window.__vvdb().ui.commits.repos || {})[${JSON.stringify(F1_ROOT)}] || {}).selection || {}).cursor`),
      H[0], 'Escape keeps the keyboard cursor (clearing marks must not teleport focus)');
    const ctrlClickGg = (h) => evalIn(win, `(() => {
      const row = document.querySelector('.vv-gg-row[data-hash=${JSON.stringify(h)}]');
      row.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }));
      return true; })()`);
    await ctrlClickGg(H[0]);
    await ctrlClickGg(H[1]);
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-gg-row-sel').length === 2`),
      'the graph’s own Ctrl-clicks mark rows', 8000);
    await escapeGg();
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-gg-row-sel').length === 0`),
      'Escape clears the graph-made marks too', 8000);
    await evalIn(win, `(() => {
      const row = document.querySelector('.vv-gg-row[data-hash=${JSON.stringify(H[0])}]');
      row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
      return true; })()`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-commits-row-open[data-hash=${JSON.stringify(H[0])}]'))`),
      'a graph double-click opens the diff and the sidebar panel derives its -open row', 8000);
    await sendChord(win, 'Left', ['alt']);   // parentSpill → the graph document
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-gg-row').length >= 3`),
      'Back returns to the graph document', 8000);
    await waitFor(() => evalIn(win, `!document.querySelector('.vv-commits-row-open')`),
      'with the graph active no commit diff is open → no panel row keeps a stale highlight', 8000);
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-gg-row-sel').length`), 0,
      'no sticky selection lingers on the activated graph row (the old :single write is gone)');
    assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-gg-row-open').length`), 0,
      'no graph row claims -open while the graph itself is the active document');

    // cleanup: release the spill fixtures and restore the Files panel for the later sections
    state.gitDiffSpills = null;
    await railClick('files');
    await waitFor(() => evalIn(win, `!document.querySelector('.vv-commits')`),
      'the ADR-0042 section restores the Files panel', 8000);
    console.log('[ok] derived open-commit highlight: click/open, tab-away, pair endpoints, Back retargeting, graph semantics (ADR-0042)');
  }

  // ---- ADR-0043: the width-adaptive Split default for diffs ----------------------------------------
  // With no explicit per-tab choice, the diff layout follows the content-pane width LIVE (Split at
  // ≥1000 CSS px of .vv-content clientWidth, Unified below); an explicit combo pick stays sticky.
  // Window math: content width = window width − 280 (sidebar visible; total incl. the 36px rail) or
  // − 36 (collapsed) — the suite's 1000px window keeps every other diff arm below the threshold.
  // Release-safe: real chords, real resizes, DOM clicks.
  {
    const f2Dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-autosplit-'));
    tempDirs.push(f2Dir);
    const f2Diff = path.join(f2Dir, 'auto.diff');
    const F2_TEXT = 'diff --git a/x.txt b/x.txt\n--- a/x.txt\n+++ b/x.txt\n@@ -1 +1 @@\n-alpha\n+beta\n';
    fs.writeFileSync(f2Diff, F2_TEXT);
    state.contentByPath.set(f2Diff, { path: f2Diff, kind: 'diff', text: F2_TEXT });
    const activeDiffView = () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      const t = (ui.tabs || []).find((tb) => tb.id === ui['active-tab']);
      return (t && t['diff-view']) || null; })()`);
    const unifiedShowing = `Boolean(document.querySelector('.vv-content .markdown-body .vv-diff-line')) && !document.querySelector('.vv-diff-splitview')`;
    const splitShowing = `Boolean(document.querySelector('.vv-content .vv-diff-splitview .vv-diff-row'))`;
    const pickLayout = async (label) => {
      await evalIn(win, `document.querySelector('.vv-combo-caret').click()`);
      await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-combo-menu'))`),
        `the Preview caret menu opens to pick ${label}`, 8000);
      // the combo rows act on mouse-down (the pickCombo idiom above), not click
      const picked = await evalIn(win, `(() => {
        const li = Array.from(document.querySelectorAll('.vv-combo-menu .vv-combo-opt'))
          .find((el) => el.textContent.trim() === ${JSON.stringify(label)});
        if (li) li.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
        return Boolean(li); })()`);
      assert.strictEqual(picked, true, `the ${label} layout row exists and picks on mouse-down`);
    };

    // 1 — auto default NARROW: a fresh tab (no explicit choice) at the 1000px window (content ≈720)
    await sendChord(win, 'T', ['control']);
    // sendInputEvent returns before the renderer has handled the chord: open the diff only once the fresh tab is
    // active, or __vvopen can win the race and open it in the PREVIOUS tab, leaving the new tab blank
    await waitFor(() => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`),
      'a fresh tab for the auto-layout diff', 8000);
    await evalIn(win, `window.__vvopen(${JSON.stringify(f2Diff)})`);
    await waitFor(() => evalIn(win, unifiedShowing),
      'an unchosen diff in a narrow pane renders Unified', 8000);
    assert.strictEqual(await activeDiffView(), null, 'the tab holds NO explicit :diff-view (auto)');

    // 2 — REACTIVE flip wide: grow the window; the .vv-content ResizeObserver mirror re-checks and
    //     the auto Split builds the side-by-side HTML by itself
    win.setContentSize(1600, 700);
    await waitFor(() => evalIn(win, splitShowing),
      'crossing the threshold auto-builds and shows Split', 10000);
    assert.strictEqual(await activeDiffView(), null, 'the auto Split wrote no explicit choice');

    // 3 — REACTIVE fall back: shrink again; the built split HTML stays cached but the route returns
    //     to Unified (no rebuild, no stickiness)
    win.setContentSize(1000, 700);
    await waitFor(() => evalIn(win, unifiedShowing),
      'narrowing returns the unchosen diff to Unified', 10000);

    // 4 — the sidebar straddle: at 1120px the sidebar toggle alone crosses the threshold
    win.setContentSize(1120, 700);
    await waitFor(() => evalIn(win, unifiedShowing),
      'sidebar visible at 1120px (content ≈840) → Unified', 10000);
    await sendChord(win, 'B', ['control']);   // collapse to the rail → content ≈1084 → wide
    await waitFor(() => evalIn(win, splitShowing),
      'collapsing the sidebar crosses into wide → Split', 10000);
    await sendChord(win, 'B', ['control']);   // expand again → ≈840 → narrow
    await waitFor(() => evalIn(win, unifiedShowing),
      'restoring the sidebar returns Unified', 10000);

    // 5 — explicit picks are STICKY across width changes (both directions)
    await pickLayout('Split');
    await waitFor(() => evalIn(win, splitShowing),
      'an explicit Split pick shows Split even in the narrow pane', 10000);
    assert.strictEqual(await activeDiffView(), 'split', 'the Split pick is stored on the tab');
    win.setContentSize(1600, 700);
    await delay(400);
    win.setContentSize(1120, 700);
    await waitFor(() => evalIn(win, splitShowing),
      'the explicit Split survives a wide→narrow round trip', 10000);
    assert.strictEqual(await activeDiffView(), 'split', 'still the explicit choice after resizes');
    win.setContentSize(1600, 700);
    await pickLayout('Unified');
    await waitFor(() => evalIn(win, unifiedShowing),
      'an explicit Unified pick shows Unified even in the wide pane', 10000);
    assert.strictEqual(await activeDiffView(), 'unified', 'the Unified pick is stored on the tab');
    win.setContentSize(1120, 700);
    await delay(400);
    win.setContentSize(1600, 700);
    await waitFor(() => evalIn(win, unifiedShowing),
      'the explicit Unified survives a narrow→wide round trip', 10000);

    // 6 — restore the suite window and close the arm's tab
    win.setContentSize(1000, 700);
    await sendChord(win, 'W', ['control']);
    await waitFor(() => evalIn(win,
      `!document.querySelector('.vv-diff-splitview') && !document.querySelector('.vv-diff-line')`),
      'the ADR-0043 arm closes its tab', 8000);
    console.log('[ok] width-adaptive Split default: auto narrow/wide, live sidebar straddle, sticky explicit picks (ADR-0043)');
  }


  await evalIn(win, `(() => {
    const tab = document.querySelector('.vv-tab-active') || document.querySelector('.vv-tab');
    const rect = tab.getBoundingClientRect();
    tab.dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true,
      cancelable: true,
      button: 2,
      clientX: rect.left + 8,
      clientY: rect.top + 8
    }));
    return true;
  })()`);
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-ctx-menu'))`),
    'tab context menu'
  );
  await waitFor(
    () => evalIn(win, `document.activeElement?.classList.contains('vv-ctx-menu')`),
    'context menu focus'
  );
  assert.strictEqual(
    await evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused') == null`),
    true,
    'context menu must not start with a hover-focused item'
  );
  const homeResult = await dispatchMenuKey(win, 'Home');
  assert.strictEqual(homeResult.handled, true, 'Home must be handled by the context menu');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() === 'Close'`),
    'context menu Home focus'
  );
  const downResult = await dispatchMenuKey(win, 'ArrowDown');
  assert.strictEqual(downResult.handled, true, 'ArrowDown must be handled by the context menu');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() === 'Duplicate tab'`),
    'context menu second focused item'
  );
  console.log('[ok] tab context menu arrow navigation works');

  // ---- Web view: edge-to-edge bounds (bug 1) + menu-over-page snapshot freeze vs. modal hide (bug 2) ----
  // The native WebContentsView is mocked here (installIpc), so web-host is driven purely by the http tab URI
  // and the stubbed vv:http-* channels. First clear the context menu left open by the previous test.
  await evalIn(win, `(() => {
    const m = document.querySelector('.vv-ctx-menu');
    if (m) m.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui['context-menu'] == null`), 'context menu cleared');

  // bug 4 — the extensions install input must accept keystrokes. Open Settings ▸ Extensions…, focus the
  // field: focus must set :in-input? (so the keymap resolver, which consumes bare keys in vim
  // normal/visual, lets text through), typing must update the controlled value, and blur must clear it.
  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'Settings'`), 'Settings menu for Extensions…');
  await waitFor(
    () => evalIn(win, `Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item')).some((n) => n.textContent.includes('Extensions'))`),
    'Extensions… menu item present'
  );
  await evalIn(win, `(() => {
    const item = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item')).find((n) => n.textContent.includes('Extensions'));
    item.click();
    return true;
  })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-ext-input'))`), 'extensions dialog open');
  await evalIn(win, `(() => { document.querySelector('.vv-ext-input').focus(); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.input['in-input?'] === true`), 'extensions input sets in-input on focus');
  await evalIn(win, `(() => {
    const i = document.querySelector('.vv-ext-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'abcdefghijklmnopabcdefghijklmnop');
    i.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-ext-input').value === 'abcdefghijklmnopabcdefghijklmnop'`),
    'extensions input accepts typed text');
  await evalIn(win, `(() => { document.querySelector('.vv-ext-input').blur(); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.input['in-input?'] === false`), 'extensions input clears in-input on blur');
  await evalIn(win, `(() => {
    const btn = Array.from(document.querySelectorAll('.vv-modal .vv-btn')).find((b) => b.textContent.trim() === 'Close');
    if (btn) { btn.click(); }
    return true;
  })()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['extensions-open?']`), 'extensions dialog closed');
  console.log('[ok] extensions install input accepts keyboard input');

  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`),
    'fresh tab for the web view');
  state.httpShow = null;
  state.httpVisible = false;
  // type an http URL into the URI bar and press Enter (→ :uri/navigate → :tab/navigate). It need not load —
  // the native view is mocked; web-host renders from the http(s) scheme alone.
  await evalIn(win, `(() => {
    const i = document.querySelector('.vv-uri-input');
    i.focus();
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'http://127.0.0.1:9/smoke');
    i.dispatchEvent(new Event('input', { bubbles: true }));
    return i.value;
  })()`);
  await delay(60);
  await evalIn(win, `(() => {
    document.querySelector('.vv-uri-input')
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-web-host'))`), 'web host renders for an http URI');
  await waitFor(() => state.httpShow && state.httpShow.bounds && state.httpShow.bounds.width > 100,
    'web view show + bounds sent');
  await waitFor(() => state.httpVisible === true, 'web view initially shown');
  // simulate main's proactive snapshot push so web-host has a pre-cached image (the instant-freeze path)
  win.webContents.send('vv:http-snapshot-ready', state.snapshotDataUrl);
  await delay(60);

  // bug 1 — edge-to-edge: the content pane drops its document reading gutter and the host fills it exactly,
  // so the bounds handed to the native view carry no 45/32px inset.
  const webEdge = await evalIn(win, `(() => {
    const content = document.querySelector('.vv-content');
    const host = document.querySelector('.vv-web-host');
    const cs = getComputedStyle(content);
    const cr = content.getBoundingClientRect();
    const hr = host.getBoundingClientRect();
    return {
      hasWebClass: content.classList.contains('vv-content-web'),
      paddingLeft: cs.paddingLeft, paddingTop: cs.paddingTop,
      flush: Math.abs(hr.left - cr.left) < 1 && Math.abs(hr.top - cr.top) < 1 &&
             Math.abs(hr.width - cr.width) < 1 && Math.abs(hr.height - cr.height) < 1,
      contentWidth: Math.round(cr.width)
    };
  })()`);
  assert.strictEqual(webEdge.hasWebClass, true, 'http content pane must use the edge-to-edge .vv-content-web class');
  assert.strictEqual(webEdge.paddingLeft, '0px', 'edge-to-edge web pane must drop the L/R reading gutter');
  assert.strictEqual(webEdge.paddingTop, '0px', 'edge-to-edge web pane must drop the T/B reading gutter');
  assert.strictEqual(webEdge.flush, true, 'web host must fill the content pane exactly (no margin around the native view)');
  assert.ok(Math.abs(Math.round(state.httpShow.bounds.width) - webEdge.contentWidth) < 2,
    'native view width must equal the full pane (not inset by the 90px gutter)');
  console.log('[ok] web view is edge-to-edge (no margin)');

  // bug 2a — opening a MENU over the page freezes a snapshot and hides the native view (page does not vanish)
  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'Settings'`), 'Settings menu open over the web view');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('img.vv-web-snap'))`), 'page snapshot frozen under the menu');
  // the native view is hidden ASYNCHRONOUSLY, only after the snapshot <img> has decoded (hide-after-paint! →
  // img.decode() → rAF → httpHide). Wait for that rather than racing it — under CPU load the hide can lag the
  // img appearing above, which intermittently tripped the `httpVisible === false` assertion below.
  await waitFor(() => state.httpVisible === false, 'native view hidden once the frozen snapshot is shown');
  const frozen = await evalIn(win, `(() => {
    const img = document.querySelector('img.vv-web-snap');
    return { src: (img && img.getAttribute('src')) || '', insideHost: Boolean(img && img.closest('.vv-web-host')),
             complete: Boolean(img && img.complete && img.naturalWidth > 0) };
  })()`);
  assert.ok(frozen.src.startsWith('data:image'), 'snapshot img must show the captured page raster');
  assert.strictEqual(frozen.insideHost, true, 'snapshot img must render inside the web host');
  assert.strictEqual(state.httpVisible, false, 'native view must hide while the frozen snapshot is shown');
  // Bug D (round 5): the native view is hidden only AFTER the snapshot <img> has decoded (img.decode() before
  // hide!), so the frozen raster is on-screen the instant the view goes away — no one-frame blank "blink".
  assert.strictEqual(frozen.complete, true, 'Bug D: the frozen snapshot img must be DECODED (complete) once the native view is hidden');
  console.log('[ok] opening a menu freezes a DECODED page snapshot before hiding the view (Bug D: no blink)');

  // bug 2b — closing the menu drops the snapshot and restores the live view
  await dispatchWindowKey(win, 'Escape');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu == null`), 'Settings menu closed');
  await waitFor(() => evalIn(win, `!document.querySelector('img.vv-web-snap')`), 'snapshot dropped on menu close');
  await waitFor(() => state.httpVisible === true, 'live web view restored on menu close');
  console.log('[ok] closing the menu restores the live web view');

  // bug 2c — a full-window MODAL (command palette) ALSO freezes the page (shows the snapshot behind its
  // dimmed backdrop), not blank — unified overlay handling.
  await sendChord(win, 'P', ['control', 'shift']);
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.palette && window.__vvdb().ui.palette['open?'])`),
    'command palette (modal) open');
  await waitFor(() => state.httpVisible === false, 'native view hidden under a modal');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('img.vv-web-snap'))`),
    'a modal freezes the page snapshot (not blank)');
  // close the palette via its own input (a window-dispatched Escape doesn't reach it while it's open)
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    if (i) i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
  await waitFor(() => evalIn(win, `!(window.__vvdb().ui.palette && window.__vvdb().ui.palette['open?'])`), 'palette closed');
  await waitFor(() => state.httpVisible === true, 'live web view restored after the modal closes');
  console.log('[ok] a modal dialog freezes the page snapshot (unified overlay handling)');

  // Fix 6 — a local .html file opens in the (edge-to-edge) web view via its file:// URL, not as source
  const htmlPath = path.join(ROOT, 'test', 'fixtures', 'local-page.html');
  state.contentByPath.set(htmlPath, { path: htmlPath, kind: 'html' });
  state.httpShow = null;
  win.webContents.send('vv:open-files', { paths: [htmlPath] });
  await waitFor(() => evalIn(win, `(() => {
    const c = document.querySelector('.vv-content');
    return Boolean(document.querySelector('.vv-web-host')) && Boolean(c) &&
           c.classList.contains('vv-content-web') &&
           window.__vvdb().ui.tabs.some((t) => (t.uri || '').includes('local-page.html'));
  })()`), 'local HTML renders in the edge-to-edge web view');
  await waitFor(() => state.httpShow && typeof state.httpShow.url === 'string' &&
    state.httpShow.url.startsWith('file://') && state.httpShow.url.includes('local-page.html'),
    'web view loads the local HTML via its file:// URL');
  console.log('[ok] local HTML opens in the web view (file:// URL, edge-to-edge)');

  // ── Bug B (round 4): a web page's late did-navigate updates its OWNER (http) tab, not the active tab ──
  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`), 'fresh tab for owner-routing test');
  state.httpShow = null;
  await evalIn(win, `(() => { const i = document.querySelector('.vv-uri-input'); i.focus();
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'http://127.0.0.1:9/owner'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await delay(60);
  await evalIn(win, `document.querySelector('.vv-uri-input').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))`);
  await waitFor(() => state.httpShow && Number.isInteger(state.httpShow.tabId), 'httpShow carries the owner tab id');
  const ownerTab = state.httpShow.tabId;
  await sendChord(win, 'T', ['control']);   // a different active tab; the http tab's late nav must not hijack it
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] !== ${ownerTab}`), 'a non-owner tab is active');
  const activeTab = await evalIn(win, `window.__vvdb().ui['active-tab']`);
  win.webContents.send('vv:http-navigated', { url: 'http://127.0.0.1:9/loaded-late', tab: ownerTab });
  await waitFor(() => evalIn(win, `(window.__vvdb().ui.tabs.find(t => t.id === ${ownerTab}) || {}).uri === 'http://127.0.0.1:9/loaded-late'`),
    'the owner http tab receives the late navigation');
  const activeUri = await evalIn(win, `(window.__vvdb().ui.tabs.find(t => t.id === ${activeTab}) || {}).uri`);
  assert.ok(activeUri == null || !String(activeUri).startsWith('http'),
    'the active (non-owner) tab must NOT be hijacked by the web view navigation');
  // a relay for a closed/unknown tab id is a harmless no-op
  const tabsBeforeGhost = await evalIn(win, `JSON.stringify(window.__vvdb().ui.tabs.map(t => [t.id, t.uri || null]))`);
  win.webContents.send('vv:http-navigated', { url: 'http://127.0.0.1:9/ghost', tab: 987654 });
  await delay(80);
  const tabsAfterGhost = await evalIn(win, `JSON.stringify(window.__vvdb().ui.tabs.map(t => [t.id, t.uri || null]))`);
  assert.strictEqual(tabsAfterGhost, tabsBeforeGhost, 'a navigation for a closed/unknown tab id must be a no-op');
  console.log('[ok] web-view navigation updates its owner tab, never the active tab');

  // ── Part 1: a .pdf link clicked in the web view opens the app's pdf.js, NOT Chromium's pdfium ──
  // main intercepts the PDF navigation (will-navigate / setWindowOpenHandler / will-download), downloads the
  // bytes over the page's own session, proactively sends vv:content{kind pdf, keyed by the URL}, then relays
  // vv:http-open-pdf{url}. The renderer's onHttpOpenPdf → [:doc/open-new url] opens a tab whose uri IS the http
  // URL; because that doc is kind "pdf", content-view's http+pdf guard routes it to the in-DOM pdf.js
  // (.vv-pdf-doc), never the web host — the web host would re-trigger Chromium's inline pdfium plugin (the bug).
  // The main-side interception is standard Electron wiring; this drives the renderer-visible decision (the
  // content-view guard + the onHttpOpenPdf → :doc/open-new wiring), which is the part that actually chose wrong.
  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`),
    'fresh tab before the web-view PDF-link test');
  const webPdfUrl = 'http://127.0.0.1:9/papers/paper.pdf';
  const webPdfBytes = new Uint8Array(fs.readFileSync(pdfFixture));
  // load-fx never re-requests an http uri (it has no local file path), so the bytes come solely from main's
  // proactive post-download vv:content — register it in contentByPath too, belt-and-suspenders.
  state.contentByPath.set(webPdfUrl, { path: webPdfUrl, kind: 'pdf', bytes: webPdfBytes });
  win.webContents.send('vv:content', { path: webPdfUrl, kind: 'pdf', bytes: webPdfBytes, stamp: Date.now() });
  win.webContents.send('vv:http-open-pdf', { url: webPdfUrl, tab: null });
  await waitFor(() => evalIn(win, `(() => { const ui = window.__vvdb().ui;
    const t = ui.tabs.find((x) => x.id === ui['active-tab']); return Boolean(t && (t.uri || '').includes('paper.pdf')); })()`),
    'a tab opens for the http PDF url');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc'))`),
    'the http PDF renders in the app pdf.js (.vv-pdf-doc)');
  const webPdfDom = await evalIn(win, `({
    pdf: Boolean(document.querySelector('.vv-content .vv-pdf-doc')),
    web: Boolean(document.querySelector('.vv-web-host')),
    flush: Boolean(document.querySelector('.vv-content')?.classList.contains('vv-content-pdf-flush')),
    webClass: Boolean(document.querySelector('.vv-content')?.classList.contains('vv-content-web'))
  })`);
  assert.strictEqual(webPdfDom.pdf, true, 'a web-view PDF link must open in the app pdf.js (.vv-pdf-doc)');
  assert.strictEqual(webPdfDom.web, false, 'a web-view PDF link must NOT fall through to the web host (Chromium pdfium)');
  assert.strictEqual(webPdfDom.webClass, false, 'the http-PDF pane must not use the edge-to-edge web (.vv-content-web) class');
  assert.strictEqual(webPdfDom.flush, true, 'the http-PDF pane uses the flush (PDF) gutter (.vv-content-pdf-flush)');
  console.log('[ok] a web-view PDF link opens in the app pdf.js, not Chromium pdfium');

  // ════════ Dialog UIX — the shared modal shell + each dialog's features ════════
  // open a top-level menu (Alt+<key>) and click the dropdown/sub-dropdown item whose text matches
  const openMenuItem = async (menuKey, itemText) => {
    await dispatchWindowKey(win, menuKey, { altKey: true });
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-menu-dropdown'))`), `Alt+${menuKey} menu`);
    await evalIn(win, `(() => {
      const it = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item, .vv-menu-subdropdown .vv-menu-item'))
        .find((n) => n.textContent.includes(${JSON.stringify(itemText)}));
      if (it) it.click();
      return Boolean(it);
    })()`);
  };
  const modalShown = () => evalIn(win, `Boolean(document.querySelector('.vv-modal'))`);
  // dispatch a bubbling Escape on the dialog panel (the shared modal handles Esc on the panel, not the window)
  const escModal = () => evalIn(win, `(() => { const p = document.querySelector('.vv-modal');
    if (p) p.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    return Boolean(p); })()`);

  // ── Settings/Preferences — exercises the SHARED modal behaviors (autofocus, modality, focus-trap, Esc) ──
  await openMenuItem('s', 'Preferences');
  await waitFor(() => evalIn(win, `(() => { const m = document.querySelector('.vv-modal');
    return Boolean(m) && /Preferences/.test(m.querySelector('.vv-modal-title')?.textContent || '')
           && Boolean(m.querySelector('.vv-modal-x')); })()`), 'Settings dialog: title + ✕ render');
  assert.strictEqual(await evalIn(win, `(() => { const m = document.querySelector('.vv-modal');
    return m === document.activeElement || m.contains(document.activeElement); })()`), true,
    'opening a modal moves focus into the dialog (autofocus)');
  // true modality: a background command chord (Ctrl+T → new tab) must NOT fire while a modal owns the
  // keyboard. A window-dispatched chord reaches the keymap resolver directly, so this validates the
  // resolver's :modal-open? guard (no false-pass from a key that simply wasn't delivered).
  const tabsDuringModal = await evalIn(win, `document.querySelectorAll('.vv-tab').length`);
  await dispatchWindowKey(win, 't', { ctrlKey: true });
  await delay(140);
  assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-tab').length`), tabsDuringModal,
    'a modal suppresses background command chords (Ctrl+T added no tab while Settings was open)');
  assert.strictEqual(await modalShown(), true, 'the Settings modal stayed open');
  // focus trap: Tab from the last focusable wraps to the first
  assert.strictEqual(await evalIn(win, `(() => {
    const m = document.querySelector('.vv-modal');
    const sel = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),[tabindex]:not([tabindex="-1"])';
    const f = Array.from(m.querySelectorAll(sel)).filter((e) => e.offsetWidth > 0);
    if (f.length < 2) return false;
    f[f.length - 1].focus();
    f[f.length - 1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    return document.activeElement === f[0];
  })()`), true, 'Tab wraps focus within the dialog (focus trap)');
  // editing a font field live-applies + persists (:settings/set)
  await evalIn(win, `(() => { const i = document.querySelector('.vv-pref-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'Iosevka'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['font-variable'] === 'Iosevka'`),
    'editing a font field updates settings live');
  // the document-streaming toggle: present, checked by default (Phase 4 default-on), and persists on toggle
  const streamCheck = await evalIn(win, `(() => {
    const box = document.querySelector(".vv-pref-checkbox[data-vv-access-key='t']");
    return box ? { present: true, checked: box.checked } : { present: false };
  })()`);
  assert.strictEqual(streamCheck.present, true, 'the "Stream large documents" checkbox is present in Preferences');
  assert.strictEqual(streamCheck.checked, true, 'streaming is checked by default (Phase 4 default-on)');
  await evalIn(win, `(() => { document.querySelector(".vv-pref-checkbox[data-vv-access-key='t']").click(); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['stream?'] === false`), 'toggling the checkbox persists :stream? false');
  console.log('[ok] Settings: document-streaming toggle present, default-on, persists on toggle');
  win.webContents.send('vv:settings', '{:stream? true}');   // restore default-on for the rest of the run
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['stream?'] === true`), 'streaming restored after the toggle test');
  // the Slides section (ADR-0045): two dropdowns + two default-ON checkboxes, each with its own Alt key
  const slidesPrefs = await evalIn(win, `(() => {
    const q = (sel) => document.querySelector(sel);
    const sec = Array.from(document.querySelectorAll('.vv-pref-section')).some((n) => n.textContent.trim() === 'Slides');
    return { sec, openAs: q(".vv-pref-input[data-vv-access-key='o']")?.value, overview: q(".vv-pref-input[data-vv-access-key='w']")?.value,
             fullscreen: q(".vv-pref-checkbox[data-vv-access-key='u']")?.checked, click: q(".vv-pref-checkbox[data-vv-access-key='k']")?.checked };
  })()`);
  assert.deepStrictEqual(slidesPrefs, { sec: true, openAs: 'slides', overview: 'last', fullscreen: true, click: true },
    'the Slides preferences show their defaults (decks open as slides, overview fully built, fullscreen + click on)');
  await evalIn(win, `(() => { document.querySelector(".vv-pref-checkbox[data-vv-access-key='k']").click();
    const sel = document.querySelector(".vv-pref-input[data-vv-access-key='w']");
    const set = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value').set;
    set.call(sel, 'first'); sel.dispatchEvent(new Event('change', { bubbles: true })); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['slides-click-advance?'] === false
    && window.__vvdb().ui.settings['slides-overview-page'] === 'first'`), 'the Slides preferences persist');
  win.webContents.send('vv:settings', '{:slides-click-advance? true :slides-overview-page :last}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['slides-click-advance?'] === true`), 'slides preferences restored');
  console.log('[ok] Settings: the Slides section shows its defaults and persists changes');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['settings-open?']`), 'Esc closes the Settings dialog');
  console.log('[ok] Settings: autofocus + modality + focus-trap + live font edit + Esc-close');

  // ✕ and backdrop-click also close (consistency)
  await openMenuItem('s', 'Preferences');
  await waitFor(modalShown, 'Settings reopened (✕)');
  await evalIn(win, `document.querySelector('.vv-modal-x').click()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['settings-open?']`), '✕ closes the dialog');
  await openMenuItem('s', 'Preferences');
  await waitFor(modalShown, 'Settings reopened (backdrop)');
  await evalIn(win, `document.querySelector('.vv-modal-overlay').click()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['settings-open?']`), 'backdrop click closes the dialog');
  console.log('[ok] Settings: ✕ and backdrop-click both close');

  // ── About ──
  await openMenuItem('h', 'About');
  await waitFor(() => evalIn(win, `(() => { const m = document.querySelector('.vv-modal.vv-about');
    return Boolean(m) && m.textContent.includes('smoke-test'); })()`), 'About shows the app version');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-about .vv-about-link'))`), true,
    'About has the repository link');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['about-open?']`), 'Esc closes About');
  console.log('[ok] About: name/version/link render + Esc-close');

  // ── Command palette: open → fuzzy filter → arrow-select → Esc close ──
  await openMenuItem('h', 'Command Palette');
  // wait for the input DOM (not just the open? state) before driving it — else the native value setter below
  // can fire with a null element (Illegal invocation) if reagent hasn't committed the input yet.
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.palette['open?'] && document.querySelector('.vv-palette-input'))`), 'palette opens');
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'tab'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-palette-item').length > 0`),
    'palette fuzzy-filters to tab commands');
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    i.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true })); return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-palette-selected'))`), 'ArrowDown selects a row');
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.palette['open?']`), 'Escape closes the palette');
  console.log('[ok] Command palette: open → fuzzy filter → arrow-select → Esc-close');

  // ── Extensions: opens, ad-block toggle flips state, Esc closes ──
  await openMenuItem('s', 'Extensions');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-modal')) && Boolean(document.querySelector('.vv-ext-sect'))`),
    'Extensions dialog open');
  const ab0 = await evalIn(win, `Boolean(window.__vvdb().ui.adblock && window.__vvdb().ui.adblock['enabled?'])`);
  await evalIn(win, `(() => { const cb = document.querySelector('.vv-ext-sect input[type="checkbox"]'); cb.click(); return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.adblock && window.__vvdb().ui.adblock['enabled?']) !== ${ab0}`),
    'ad-block toggle flips its state');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['extensions-open?']`), 'Esc closes Extensions');
  console.log('[ok] Extensions: opens + ad-block toggle + Esc-close');

  // ── Passwords panel (populated from the mocked bridge state) ──
  await openMenuItem('s', 'Passwords');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-modal.vv-pw-dialog'))`), 'Passwords dialog open');
  await waitFor(() => evalIn(win, `(document.querySelector('.vv-pw-providers')?.textContent || '').includes('1Password')`),
    'a provider row renders from mocked state');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.passwords['open?']`), 'Esc closes Passwords');
  console.log('[ok] Passwords panel: opens with provider rows + Esc-close');

  // ── Passwords save-prompt (push-driven) → renders, Save enabled, Dismiss notifies main ──
  state.pwDismiss = null;
  win.webContents.send('vv:password-save-prompt', { token: 'tok-1', origin: 'https://example.com', username: 'alice',
    providers: [{ id: 'op', label: '1Password', status: 'ready', 'save-supported?': true }] });
  await waitFor(() => evalIn(win, `(() => { const m = document.querySelector('.vv-modal.vv-pw-save');
    return Boolean(m) && m.textContent.includes('Save Login'); })()`), 'save-prompt renders');
  assert.strictEqual(await evalIn(win, `!document.querySelector('.vv-pw-save .vv-btn[disabled]')`), true,
    'Save is enabled once a provider auto-selects');
  await evalIn(win, `(() => { const b = Array.from(document.querySelectorAll('.vv-pw-save .vv-btn'))
    .find((x) => x.textContent.includes('Dismiss')); if (b) b.click(); return Boolean(b); })()`);
  await waitFor(() => state.pwDismiss === 'tok-1', 'Dismiss notifies main with the token');
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.passwords['save-prompt']`), 'Dismiss clears the save-prompt');
  console.log('[ok] Passwords save-prompt: renders + Save enabled + Dismiss');

  // ── Error view text is selectable AND copyable (Ctrl+C) — selectable-root now includes .vv-error ──
  const errPath = path.join(ROOT, 'test', 'fixtures', 'copy-error.md');
  win.webContents.send('vv:open-files', { paths: [errPath] });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.tabs.some((t) => t.uri === ${JSON.stringify(errPath)})`),
    'a tab exists for the error path');
  win.webContents.send('vv:error', { path: errPath, message: 'EISDIR: illegal operation on a directory, read' });
  await waitFor(() => evalIn(win, `(document.querySelector('.vv-error')?.textContent || '').includes('EISDIR')`),
    'the error view renders');
  state.lastCopiedText = null;
  await evalIn(win, `(() => { const el = document.querySelector('.vv-error');
    const r = document.createRange(); r.selectNodeContents(el);
    const s = window.getSelection(); s.removeAllRanges(); s.addRange(r); return s.toString(); })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C is handled for an error selection');
  await waitFor(() => state.lastCopiedText && state.lastCopiedText.includes('EISDIR'),
    'selected error text copies to the clipboard');
  console.log('[ok] error view text is selectable and copyable (Ctrl+C)');

  // ── Multi-argument launch — `vv a.md b.md https://x` opens each argument in its own tab (first focused) ──
  // The main process parses every non-flag argument (startup/doc-uris, unit-tested) and pushes them over THIS
  // channel with focus-first; here we drive the renderer pipeline directly with one real IPC to prove the live
  // outcome: every argument gets a tab, the extra-argument tabs preserve command-line order, and the FIRST
  // argument's tab ends active (the Open dialog, by contrast, leaves the last-opened tab active).
  const argA = path.join(ROOT, 'test', 'fixtures', 'multi-arg-a.md');
  const argB = path.join(ROOT, 'test', 'fixtures', 'multi-arg-b.md');
  const argC = 'http://127.0.0.1:9/multi-arg-c';
  for (const p of [argA, argB]) {
    state.contentByPath.set(p, { path: p, kind: 'markdown', text: `# ${path.basename(p)}\n\nbody`, stamp: Date.now() });
  }
  const idsBeforeMulti = await evalIn(win, `window.__vvdb().ui.tabs.map((t) => t.id)`);
  win.webContents.send('vv:open-files', { paths: [argA, argB, argC], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => {
    const uris = window.__vvdb().ui.tabs.map((t) => t.uri);
    return [${JSON.stringify(argA)}, ${JSON.stringify(argB)}, ${JSON.stringify(argC)}].every((u) => uris.includes(u));
  })()`), 'multi-arg launch opens a tab for every argument');
  const multi = await evalIn(win, `(() => {
    const ui = window.__vvdb().ui;
    const idx = (u) => ui.tabs.findIndex((t) => t.uri === u);
    const tabFor = (u) => ui.tabs.find((t) => t.uri === u) || {};
    const active = ui.tabs.find((t) => t.id === ui['active-tab']);
    const before = ${JSON.stringify(idsBeforeMulti)};
    return {
      activeUri: active ? active.uri : null,
      bNew: !before.includes(tabFor(${JSON.stringify(argB)}).id),
      cNew: !before.includes(tabFor(${JSON.stringify(argC)}).id),
      bBeforeC: idx(${JSON.stringify(argB)}) < idx(${JSON.stringify(argC)})
    };
  })()`);
  assert.strictEqual(multi.activeUri, argA, 'multi-arg launch must focus the FIRST argument\'s tab');
  assert.ok(multi.bNew && multi.cNew, 'every non-first argument must open in its own NEW tab');
  assert.ok(multi.bBeforeC, 'the additional argument tabs must preserve command-line order');
  console.log('[ok] multi-arg launch: a tab per argument, command-line order, first focused');

  // ── Ctrl+PageDown / Ctrl+PageUp cycle tabs right / left (browser-style, wrapping) ──
  // A real window keydown: the capture-phase scroll handler ignores it (its `no-cam?` guard requires NO Ctrl),
  // so the bubble-phase resolver maps C-next/C-prior → :tab/next/:tab/prev — the same wrapping commands as the
  // Vim g t / g T sequence. (The default keymap is active at this point.)
  const cyc = await evalIn(win, `(() => { const ui = window.__vvdb().ui;
    return { active: ui['active-tab'], order: ui.tabs.map((t) => t.id) }; })()`);
  assert.ok(cyc.order.length >= 2, 'the multi-arg block must leave >= 2 tabs open for the cycle test');
  const cycNext = cyc.order[(cyc.order.indexOf(cyc.active) + 1) % cyc.order.length];
  await dispatchWindowKey(win, 'PageDown', { ctrlKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${cycNext}`),
    'Ctrl+PageDown activates the next (right) tab');
  await dispatchWindowKey(win, 'PageUp', { ctrlKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${cyc.active}`),
    'Ctrl+PageUp returns to the previous (left) tab');
  console.log('[ok] Ctrl+PageDown / Ctrl+PageUp cycle tabs right / left');

  // ========================================================================================================
  // ── Document streaming (vinary.stream.*) — a large log renders as a BOUNDED-MEMORY incremental stream ──
  // The WPDA log grammar segments records (continuation lines + JSON braces stay whole); the scheduler appends
  // them across idle frames; Contents + find work on the growing document; a small log stays on the batch
  // path; and the main-process session registry drains to 0 on teardown (no fd/session leak). vv:stream-* is
  // wired to the REAL content service, so this drives genuine createReadStream/readline batching end-to-end.
  // ========================================================================================================
  win.webContents.send('vv:settings', '{:stream? true}');
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.settings['stream?'])`), 'streaming flag enabled via settings');

  const bigLog = createLargeLogFixture();
  assert.ok(bigLog.size > 5 * 1024 * 1024, `large log fixture (${bigLog.size} B) must exceed the 5 MiB stream threshold`);
  state.contentByPath.set(bigLog.docPath, {
    path: bigLog.docPath, kind: 'log', paged: true,
    page: { index: 0, lines: [], hasPrev: false, hasNext: true },
    stamp: Date.now(), sourceable: true, meta: { size: bigLog.size, pageSize: 2000 }
  });
  const streamCount0 = contentService.streamCount();
  win.webContents.send('vv:open-files', { paths: [bigLog.docPath], 'focus-first': true });

  // streaming engages: the stream body (its progress strip) mounts and the FIRST records paint incrementally —
  // div.vv-log-record under .markdown-body, a shape the batch .vv-log-doc view never produces.
  await waitFor(() => evalIn(win, `(() => {
    const streaming = Boolean(document.querySelector('.vv-content .vv-stream-progress'));
    const recs = document.querySelectorAll('.vv-content .markdown-body .vv-log-record').length;
    return streaming && recs > 0;
  })()`), 'large log streams — progress strip mounts + first records paint', 20000);
  const earlyRecords = await evalIn(win, `document.querySelectorAll('.vv-content .markdown-body .vv-log-record').length`);
  assert.ok(earlyRecords > 0, 'at least one record paints before the whole file is parsed (incremental first paint)');
  assert.strictEqual(contentService.streamCount(), streamCount0 + 1, 'exactly one main-process stream session is open while streaming');

  // the stream converges to the WHOLE file: every record present, with continuation/braced records kept whole
  // (an exact count — a split JSON/stack record would inflate it).
  await waitCalm(win, `document.querySelectorAll('.vv-content .markdown-body .vv-log-record').length === ${bigLog.records}`,
    `streamed record count converges to the whole file (${bigLog.records})`, 30000);
  const finalRecords = await evalIn(win, `document.querySelectorAll('.vv-content .markdown-body .vv-log-record').length`);
  assert.strictEqual(finalRecords, bigLog.records, 'the full document streamed in, records intact (no split braces/continuations)');
  if (earlyRecords < finalRecords) console.log(`[ok] progressive growth observed (${earlyRecords} → ${finalRecords} records)`);
  await waitFor(() => evalIn(win, `(() => { const p = document.querySelector('.vv-content .vv-stream-progress');
    return p && p.classList.contains('vv-stream-progress-done'); })()`), 'progress strip hides once complete');
  console.log(`[ok] large log streams to completion (${finalRecords} records, ${(bigLog.size / 1048576).toFixed(1)} MiB, bounded memory)`);

  // Contents populates from the ERROR records (grows via :stream/toc-append) AND is navigable — clicking an
  // entry scrolls to that record (its stable anchor id is a real element id in the streamed DOM).
  await evalIn(win, `(() => { const ui = window.__vvdb().ui;
    if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'contents')
      document.querySelector('[data-vv-rail="contents"]').click();
    return true; })()`);
  await waitCalm(win, `document.querySelectorAll('.vv-toc-item').length >= 35`, 'the streamed log Contents lists the error records', 10000);
  const tocInfo = await evalIn(win, `(() => { const items = Array.from(document.querySelectorAll('.vv-toc-item'));
    return { n: items.length, anyError: items.some((i) => /ERROR/.test(i.textContent)) }; })()`);
  assert.ok(tocInfo.n >= 35, `the streamed Contents must list the error records (got ${tocInfo.n})`);
  assert.ok(tocInfo.anyError, 'a Contents entry names an ERROR record');
  await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); if (c) c.scrollTop = 0;
    const rows = document.querySelectorAll('.vv-toc-item'); rows[rows.length - 1].click(); return true; })()`);
  await waitCalm(win, `document.querySelector('.vv-content').scrollTop > 50`, 'clicking a Contents entry scrolls to its streamed record', 10000);
  console.log(`[ok] streamed log Contents lists ${tocInfo.n} error records + navigates to them`);

  // find works over the streamed document — the JSON braced records' inner line is present AND findable, which
  // proves those records streamed in WHOLE (the WPDA pushdown never split them).
  await dispatchWindowKey(win, 'f', { ctrlKey: true });
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'find bar opens over the streamed log');
  await evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
    const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    set.call(i, 'inner-looks-like-a-header'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.find.count > 0`), 'find matches text inside the streamed JSON records', 8000);
  const findCount = await evalIn(win, `window.__vvdb().ui.find.count`);
  assert.ok(findCount >= 1, "find locates the braced records' inner header-looking line (records streamed whole)");
  await evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
    i && i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
  console.log(`[ok] find works on the streamed document (${findCount} match(es) inside braced records)`);

  // a small log stays on the BATCH path (below the threshold) — the paged .vv-log-doc view, never streamed
  const smallLog = createSmallLogFixture();
  assert.ok(smallLog.size < 5 * 1024 * 1024, 'small log fixture must be below the streaming threshold');
  state.contentByPath.set(smallLog.docPath, {
    path: smallLog.docPath, kind: 'log', text: fs.readFileSync(smallLog.docPath, 'utf8'),
    stamp: Date.now(), sourceable: true, meta: { size: smallLog.size }
  });
  win.webContents.send('vv:open-files', { paths: [smallLog.docPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => {
    const batch = Boolean(document.querySelector('.vv-content .vv-log-doc'));
    const streaming = Boolean(document.querySelector('.vv-content .vv-stream-progress'));
    return batch && !streaming;
  })()`), 'small log renders on the batch path (paged .vv-log-doc, not streamed)', 10000);
  console.log('[ok] a small log stays on the batch path — streaming is opt-in + size-gated');

  // switching away from the large-log tab unmounted its stream body → the main-process session closed. No leak.
  await waitFor(() => contentService.streamCount() === 0, 'the streamed log session closed on teardown', 10000);
  assert.strictEqual(contentService.streamCount(), 0, 'no main-process stream session may leak after teardown');
  console.log('[ok] stream session drains to 0 on teardown — no fd/session leak');

  // ── Feature: Org-mode (.org) — GitHub-style render via uniorg through the common IR, nested-language src
  //    highlighting, and a tree-sitter-org-highlighted View Source ──────────────────────────────────────────
  const orgDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-org-'));
  tempDirs.push(orgDir);
  const orgText = [
    '#+TITLE: Org Demo',
    '* First Heading',
    'Some *bold* text and a [[https://example.com][link]].',
    '** Nested Heading',
    '#+begin_src python',
    'def add(a, b):',
    '    return a + b',
    '#+end_src',
    '#+begin_src emacs-lisp',
    '(message "hi")',
    '#+end_src',
    '| a | b |',
    '|---+---|',
    '| 1 | 2 |',
    ''
  ].join('\n');
  const orgPath = path.join(orgDir, 'demo.org');
  fs.writeFileSync(orgPath, orgText);
  // Drive Org through the REAL content_service classifier, not a hand-written {kind:'org'} stub. Every other
  // fixture here stubs the payload, which is exactly why the classifier divergence (.org ∈ textExts, no org arm
  // in classifyName) survived CI while breaking vv-cli/vv-tui and archive-nested .org files.
  state.contentByPath.set(orgPath, await contentService.openUri(orgPath));
  win.webContents.send('vv:open-files', { paths: [orgPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `[...document.querySelectorAll('.vv-content .markdown-body h1')].some(h => h.textContent.includes('First Heading'))`),
    'org preview renders headings', 10000);
  assert.ok(await evalIn(win, `document.querySelector('.vv-content .markdown-body h1')?.textContent.includes('Org Demo')`),
    'org #+TITLE renders as document front matter ahead of the body');
  const orgPreview = await evalIn(win, `(() => {
    const body = document.querySelector('.vv-content .markdown-body');
    return {
      h1: body.querySelectorAll('h1').length,
      h2: body.querySelectorAll('h2').length,
      python: Boolean(body.querySelector('pre code[class*="language-python"]')),
      elisp: Boolean(body.querySelector('pre code[class*="language-emacs-lisp"]')),
      table: Boolean(body.querySelector('table')),
      bold: Boolean(body.querySelector('strong'))
    };
  })()`);
  assert.ok(orgPreview.h1 >= 1, 'org first heading renders as h1');
  assert.ok(orgPreview.h2 >= 1, 'org nested heading renders as h2');
  assert.ok(orgPreview.python, 'org #+begin_src python → a language-python code block (nested-language highlighting)');
  assert.ok(orgPreview.elisp, 'org #+begin_src emacs-lisp → a language-emacs-lisp code block');
  assert.ok(orgPreview.table, 'org table renders');
  assert.ok(orgPreview.bold, 'org *bold* renders as <strong>');
  console.log('[ok] org (.org) preview renders GitHub-style with nested-language code blocks');
  // View Source on org → a CodeMirror source view highlighted by the bundled tree-sitter-org grammar.
  // Driven through the REAL View ▸ View Source menu item rather than the re-frame global, so this runs in the
  // release build too (:simple encapsulates `re_frame`, so an internal dispatch throws ReferenceError there).
  await openMenuItem('v', 'View Source');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-source .cm-editor'))`),
    'org View Source mounts the CodeMirror source view', 8000);
  await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-content .vv-source .cm-keyword, .vv-content .vv-source .cm-md-heading, .vv-content .vv-source .cm-comment').length > 0`),
    'org source view is highlighted by the tree-sitter-org grammar', 12000);
  console.log('[ok] org View Source is highlighted by the bundled tree-sitter-org grammar');
  await openMenuItem('v', 'View Source');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body h1'))`), 'org toggles back to preview', 8000);

  // ── Regression: an Org document whose whole body is a LaTeX export block must not render a BLANK pane ──────
  // uniorg drops every #+KEYWORD and drops any #+BEGIN_EXPORT whose backend is not `html`, so an invoice-shaped
  // document lowered to "" — and "" is TRUTHY in ClojureScript, so the view mounted an empty .markdown-body and
  // showed a silent blank pane with no error. The LaTeX here is document markup (\begin{center}); ADR-0025 now
  // RENDERS it via unified-latex (center → a block <div>), not a highlighted `language-latex` code block.
  const invoicePath = path.join(orgDir, 'invoice.org');
  fs.writeFileSync(invoicePath, [
    '#+TITLE: Invoice #42',
    '#+AUTHOR: Ada Lovelace',
    '#+DATE: 2026-06-30',
    '#+LATEX_HEADER: \\usepackage{booktabs}',
    '',
    '#+BEGIN_EXPORT latex',
    '\\begin{center}',
    '  Billing Period: June 2026',
    '\\end{center}',
    '#+END_EXPORT',
    ''
  ].join('\n'));
  state.contentByPath.set(invoicePath, await contentService.openUri(invoicePath));
  win.webContents.send('vv:open-files', { paths: [invoicePath], 'focus-first': true });
  await waitFor(() => evalIn(win, `document.querySelector('.vv-content .markdown-body h1')?.textContent.includes('Invoice #42')`),
    'latex-only org renders its #+TITLE (was a silent blank pane)', 10000);
  const invoice = await evalIn(win, `(() => {
    const body = document.querySelector('.vv-content .markdown-body');
    return {
      blank: !body || body.innerHTML.trim() === '',
      author: body.textContent.includes('Ada Lovelace'),
      latexBlock: Boolean(body.querySelector('pre code[class*="language-latex"]')),
      bodyKept: body.textContent.includes('Billing Period'),
      leakedHeader: body.textContent.includes('usepackage'),
      marker: Boolean(body.querySelector('.vv-tex-attempt')),
      leakedHtmlTag: /html-tag|html-attr/.test(body.textContent),
      emptyNotice: Boolean(document.querySelector('.vv-content .vv-empty'))
    };
  })()`);
  assert.ok(!invoice.blank, 'latex-only org preview is NOT blank');
  assert.ok(!invoice.emptyNotice, 'latex-only org shows real content, not the empty-document notice');
  assert.ok(invoice.author, 'org #+AUTHOR renders as front matter');
  // ADR-0025: the non-math #+BEGIN_EXPORT latex block is RENDERED by unified-latex, not shown as source
  assert.ok(!invoice.latexBlock, 'a non-math #+BEGIN_EXPORT latex is rendered, not a language-latex code block');
  assert.ok(!invoice.marker, 'no vv-tex-attempt marker is left in the DOM (the block is rendered, not attempted)');
  assert.ok(invoice.bodyKept, 'the export block body is preserved and rendered, never silently swallowed');
  assert.ok(!invoice.leakedHeader, '#+LATEX_HEADER keywords stay dropped (harvested for macros, not rendered)');
  assert.ok(!invoice.leakedHtmlTag, 'no unified-latex \\html-tag: internal syntax leaks into the rendered output');
  console.log('[ok] org latex-export-only document RENDERS the invoice body (title + author + rendered layout)');

  // ── A document that genuinely renders NOTHING shows an explicit notice, never a silent blank pane ──────────
  // Positive counterpart to the invoice assertion above: :doc/html is set to "" (a SUCCESSFUL render of nothing),
  // and because "" is truthy in ClojureScript the view must test ir.backend.html/blank? rather than truthiness.
  const orgEmptyPath = path.join(orgDir, 'empty.org');
  fs.writeFileSync(orgEmptyPath, '#+OPTIONS: toc:nil num:nil\n# just a comment\n');
  state.contentByPath.set(orgEmptyPath, await contentService.openUri(orgEmptyPath));
  win.webContents.send('vv:open-files', { paths: [orgEmptyPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-empty'))`),
    'a document that renders nothing shows the empty-preview notice', 10000);
  const emptyDoc = await evalIn(win, `(() => ({
    notice: document.querySelector('.vv-content .vv-empty').textContent,
    noBody: !document.querySelector('.vv-content .markdown-body')
  }))()`);
  assert.ok(/Nothing to preview/i.test(emptyDoc.notice), `the notice explains the empty render (got: ${emptyDoc.notice})`);
  assert.ok(/View Source/i.test(emptyDoc.notice), 'the notice points at View Source');
  assert.ok(!/Rendering/i.test(emptyDoc.notice), 'it is the empty notice, not the still-rendering placeholder');
  assert.ok(emptyDoc.noBody, 'no empty .markdown-body is mounted (the silent-blank-pane failure mode)');
  console.log('[ok] a document that renders nothing shows an explicit notice, not a blank pane');

  // ── An Org #+BEGIN_EXPORT latex block that IS math typesets via MathJax ─────────────────────────────────────
  const orgMathPath = path.join(orgDir, 'math.org');
  fs.writeFileSync(orgMathPath, [
    '* Equations',
    'Inline $E = mc^2$ math.',
    '',
    '#+BEGIN_EXPORT latex',
    '\\begin{align} a &= b \\end{align}',
    '#+END_EXPORT',
    ''
  ].join('\n'));
  state.contentByPath.set(orgMathPath, await contentService.openUri(orgMathPath));
  win.webContents.send('vv:open-files', { paths: [orgMathPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body .vv-math-display svg'))`),
    'org latex export block that is real math typesets to MathJax SVG', 12000);
  assert.ok(await evalIn(win, `Boolean(document.querySelector('.vv-content .markdown-body .vv-math-inline svg'))`),
    'org inline $…$ math renders as MathJax SVG (uniorg emits span.math, normalized to code.math-inline)');
  assert.ok(await evalIn(win, `!document.querySelector('.vv-content .markdown-body pre code[class*="language-latex"]')`),
    'a math export block typesets instead of falling back to a code block');
  console.log('[ok] org math renders via MathJax (inline + a math #+BEGIN_EXPORT latex block)');

  // ── Org task lists reuse GFM's shape (and therefore GFM's sanitize allowlist + CSS) ────────────────────────
  const orgTaskPath = path.join(orgDir, 'tasks.org');
  fs.writeFileSync(orgTaskPath, '* Tasks\n- [ ] todo\n- [X] done\n');
  state.contentByPath.set(orgTaskPath, await contentService.openUri(orgTaskPath));
  win.webContents.send('vv:open-files', { paths: [orgTaskPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-content .markdown-body li.task-list-item input[type=checkbox]').length === 2`),
    'org checkboxes render as GFM task-list items', 10000);
  assert.ok(await evalIn(win, `document.querySelectorAll('.vv-content .markdown-body input[type=checkbox]:checked').length === 1`),
    'org [X] renders a checked box');
  console.log('[ok] org task lists render with GFM checkbox parity');

  // ========================================================================================================
  // ── Phase 2 — MARKDOWN streaming parity: streamed HTML == batch HTML (the load-bearing gate) ──
  // A >256 KiB Markdown doc rendered whole (flag off) vs streamed as progressive block-commits (flag on) must
  // produce BYTE-IDENTICAL .markdown-body innerHTML — proving heading-slug dedup, forward reference resolution,
  // loose lists, code highlighting, tables, and source positions all survive per-block progressive rendering.
  // ========================================================================================================
  const md = createLargeMarkdownFixtures();
  assert.ok(md.size > 256 * 1024, `markdown fixture (${md.size} B) must exceed the 256 KiB stream threshold`);
  const mdStamp = Date.now();

  // batch render (flag OFF) → capture the reference innerHTML
  win.webContents.send('vv:settings', '{:stream? false}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['stream?'] === false`), 'streaming disabled for the batch reference');
  state.contentByPath.set(md.batchPath, { path: md.batchPath, kind: 'markdown', text: md.text, stamp: mdStamp, sourceable: true, meta: { size: md.size } });
  win.webContents.send('vv:open-files', { paths: [md.batchPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
    return b && b.querySelectorAll('h2').length >= ${md.sections} && !document.querySelector('.vv-content .vv-stream-progress'); })()`),
    'batch markdown renders whole (no stream progress strip)', 25000);
  await delay(400);   // let the post-passes (syntax highlight) + figure pass settle
  const batchHtml = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
  const batchH2 = await evalIn(win, `document.querySelectorAll('.vv-content .markdown-body h2').length`);
  assert.ok(batchH2 >= md.sections, 'batch reference rendered every section');
  console.log(`[ok] batch markdown reference rendered (${batchH2} sections, ${(md.size / 1024).toFixed(0)} KiB)`);

  // Noto Sans prose font: the self-hosted @font-face loads, and the app.css DEFAULT --vv-font-variable is
  // Noto Sans. (An earlier settings test set a runtime font-variable override on :root via fx.cljs; remove
  // it to expose the app.css default, read the computed prose font, then restore it so the smoke state is intact.)
  await evalIn(win, `document.fonts.load('16px "Noto Sans"').then(() => document.fonts.load('italic 16px "Noto Sans"'))`);
  const notoLoaded = await evalIn(win, `document.fonts.check('16px "Noto Sans"')`);
  const bodyFont = await evalIn(win, `(() => {
    const s = document.documentElement.style, prior = s.getPropertyValue('--vv-font-variable');
    s.removeProperty('--vv-font-variable');
    const f = getComputedStyle(document.querySelector('.vv-content .markdown-body')).fontFamily;
    if (prior) s.setProperty('--vv-font-variable', prior);
    return f;
  })()`);
  assert.ok(notoLoaded, 'Noto Sans @font-face loaded from resources/public/assets/fonts/noto-sans-otf/');
  assert.ok(/Noto Sans/i.test(bodyFont), `default prose font is Noto Sans (got: ${bodyFont})`);
  console.log('[ok] prose renders in the self-hosted Noto Sans web font (app.css default)');

  // ── Three-font scheme: LaTeX-scoped prose font + Fira Code ligature toggle ─────────────────────────────
  // (a) .vv-content exposes data-doc-kind (views.cljs) so app.css can pick the prose font by document format.
  const contentKind = await evalIn(win, `document.querySelector('.vv-content') && document.querySelector('.vv-content').getAttribute('data-doc-kind')`);
  assert.ok(contentKind, `.vv-content exposes data-doc-kind for CSS font scoping (got: ${contentKind})`);
  // (b) LaTeX documents render prose in Latin Modern (matching the Computer-Modern math). Verify the app.css
  //     scope rule + the --vv-font-latex token (and a user :font-latex override) by flipping the kind on the
  //     live .vv-content and reading the computed prose font — no LaTeX-engine round-trip needed.
  await evalIn(win, `document.fonts.load('16px "Latin Modern Roman"').then(() => true)`);
  const latexFonts = await evalIn(win, `(() => {
    const c = document.querySelector('.vv-content'), body = c.querySelector('.markdown-body');
    const prior = c.getAttribute('data-doc-kind');
    const base = getComputedStyle(body).fontFamily;                 // markdown/org → the variable-width font
    c.setAttribute('data-doc-kind', 'latex');
    const lm = getComputedStyle(body).fontFamily;                   // latex → Latin Modern (--vv-font-latex)
    const st = document.documentElement.style, had = st.getPropertyValue('--vv-font-latex');
    st.setProperty('--vv-font-latex', '"VV Latex Probe", serif');   // a user :font-latex override flows through
    const overridden = getComputedStyle(body).fontFamily;
    if (had) st.setProperty('--vv-font-latex', had); else st.removeProperty('--vv-font-latex');
    if (prior) c.setAttribute('data-doc-kind', prior); else c.removeAttribute('data-doc-kind');
    return { base, lm, overridden };
  })()`);
  assert.ok(!/Latin Modern Roman/i.test(latexFonts.base), `non-LaTeX prose is not Latin Modern (base: ${latexFonts.base})`);
  assert.ok(/Latin Modern Roman/i.test(latexFonts.lm), `LaTeX-kind prose switches to Latin Modern Roman (got: ${latexFonts.lm})`);
  assert.ok(/VV Latex Probe/i.test(latexFonts.overridden), `a :font-latex override (--vv-font-latex) flows to LaTeX prose (got: ${latexFonts.overridden})`);
  console.log('[ok] LaTeX previews render prose in Latin Modern (format-scoped via data-doc-kind, --vv-font-latex overridable)');
  // (c) Fira Code programming ligatures default OFF on mono surfaces; the :code-ligatures? setting flips them on
  //     everywhere (fx.cljs → --vv-code-liga → font-variant-ligatures). Probe a .markdown-body code node.
  const ligProbe = `(() => {
    const d = document.createElement('div'); d.className = 'markdown-body';
    d.style.cssText = 'position:absolute;left:-9999px;top:-9999px';
    const code = document.createElement('code'); code.textContent = 'x=y';   // a mono/code node
    d.appendChild(code); document.body.appendChild(d);
    const v = getComputedStyle(code).fontVariantLigatures;
    const rootVar = getComputedStyle(document.documentElement).getPropertyValue('--vv-code-liga').trim();
    d.remove(); return { v, rootVar };
  })()`;
  const ligDefault = await evalIn(win, ligProbe);
  assert.strictEqual(ligDefault.v, 'none', `code ligatures are OFF by default (font-variant-ligatures=${ligDefault.v})`);
  assert.strictEqual(ligDefault.rootVar, 'none', `--vv-code-liga defaults to none (got ${ligDefault.rootVar})`);
  win.webContents.send('vv:settings', '{:code-ligatures? true}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['code-ligatures?'] === true`), ':code-ligatures? true persists');
  const ligOn = await evalIn(win, ligProbe);
  assert.strictEqual(ligOn.rootVar, 'normal', `:code-ligatures? true → --vv-code-liga: normal on :root (fx.cljs)`);
  assert.notStrictEqual(ligOn.v, 'none', `:code-ligatures? true → mono ligatures enabled (font-variant-ligatures=${ligOn.v})`);
  win.webContents.send('vv:settings', '{:code-ligatures? false}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['code-ligatures?'] === false`), ':code-ligatures? false persists');
  const ligOff = await evalIn(win, ligProbe);
  assert.strictEqual(ligOff.v, 'none', `:code-ligatures? false → font-variant-ligatures: none (got ${ligOff.v})`);
  console.log('[ok] Fira Code ligatures default off across mono surfaces; :code-ligatures? toggles them live');

  // streamed render (flag ON) → progressive block-commit
  win.webContents.send('vv:settings', '{:stream? true}');
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.settings['stream?'])`), 'streaming re-enabled for the streamed render');
  state.contentByPath.set(md.streamPath, { path: md.streamPath, kind: 'markdown', text: md.text, stamp: mdStamp, sourceable: true, meta: { size: md.size } });
  win.webContents.send('vv:open-files', { paths: [md.streamPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => {
    const streaming = Boolean(document.querySelector('.vv-content .vv-stream-progress'));
    const h2 = document.querySelectorAll('.vv-content .markdown-body h2').length;
    return streaming && h2 > 0;
  })()`), 'markdown streams — progress strip + first blocks paint', 20000);
  const midH2 = await evalIn(win, `document.querySelectorAll('.vv-content .markdown-body h2').length`);
  // Phase 1: while still streaming, the pre-estimated spacer already pads the scroller toward the whole-document
  // height, so the scrollbar reflects the whole doc (not just the streamed-so-far prefix). Best-effort — a fast
  // drain may finish before we sample, in which case the spacer has correctly collapsed to ~0.
  await delay(60);   // let one refresh-view! (rAF-coalesced) size the spacer
  const midScroll = await evalIn(win, `(() => {
    const c = document.querySelector('.vv-content');
    const body = c.querySelector('.markdown-body.vv-streamed');
    const spacer = c.querySelector('.vv-stream-spacer');
    const streaming = Boolean(document.querySelector('.vv-content .vv-stream-progress:not(.vv-stream-progress-done)'));
    return { streaming, scrollHeight: c.scrollHeight, bodyHeight: body ? body.offsetHeight : 0,
             spacerHeight: spacer ? spacer.offsetHeight : 0 };
  })()`);
  if (midScroll.streaming) {
    assert.ok(midScroll.spacerHeight > 0, `mid-stream the height spacer pads the scroller (spacer=${midScroll.spacerHeight}px)`);
    assert.ok(midScroll.scrollHeight > midScroll.bodyHeight, `mid-stream scrollHeight (${midScroll.scrollHeight}) exceeds the rendered body (${midScroll.bodyHeight}) → scrollbar reflects the whole doc`);
    console.log(`[ok] mid-stream scrollbar reflects the whole document (spacer ${midScroll.spacerHeight}px pads body ${midScroll.bodyHeight}px)`);
  } else {
    console.log('[ok] streamed markdown drained before mid-stream sampling (spacer height model applied + collapsed)');
  }
  // converge to the whole document, then let the async append queue + post-passes settle
  await waitCalm(win, `document.querySelectorAll('.vv-content .markdown-body h2').length >= ${batchH2}
    && (() => { const p = document.querySelector('.vv-content .vv-stream-progress'); return p && p.classList.contains('vv-stream-progress-done'); })()`,
    'streamed markdown converges to the whole document', 60000);
  await delay(600);   // final refresh-view (figure scale + spy) + trailing appends
  const streamHtml = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
  if (midH2 < batchH2) console.log(`[ok] progressive markdown paint observed (${midH2} → ${batchH2} sections)`);

  if (streamHtml !== batchHtml) {
    const at = firstDiff(streamHtml, batchHtml);
    console.error(`markdown parity MISMATCH at char ${at} / stream ${streamHtml.length} vs batch ${batchHtml.length}`);
    console.error('stream …', JSON.stringify(streamHtml.slice(Math.max(0, at - 80), at + 120)));
    console.error('batch  …', JSON.stringify(batchHtml.slice(Math.max(0, at - 80), at + 120)));
  }
  assert.strictEqual(streamHtml, batchHtml, 'streamed markdown innerHTML must be byte-identical to the batch render');
  console.log('[ok] streamed markdown is BYTE-IDENTICAL to the batch render (slug dedup, forward refs, lists, code, tables, positions)');

  // Regression: Source is explicit user intent, so the streaming Preview policy must not
  // intercept it for a document over the 256 KiB threshold (the reported 396 KiB Markdown case).
  const warmBeforeSource = await evalIn(win, `window.__vvwarmcache ? window.__vvwarmcache() : null`);
  await evalIn(win, `(() => { const b=[...document.querySelectorAll('.vv-combo-group .vv-combo-main, .vv-combo-group .vv-combo-plain')]
    .find(x => x.textContent.trim() === 'Source'); if (b) b.click(); return Boolean(b); })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-source .cm-line'))
    && !document.querySelector('.vv-content .vv-stream-progress')`),
    'large streamed Markdown switches to its source editor', 20000);
  assert.strictEqual(await evalIn(win, `document.querySelector('.vv-content .cm-line')?.textContent || ''`), '# Big Markdown Document',
    'Source displays the Markdown text rather than leaving the streamed Preview mounted');
  await evalIn(win, `(() => { const b=[...document.querySelectorAll('.vv-combo-group .vv-combo-main, .vv-combo-group .vv-combo-plain')]
    .find(x => x.textContent.trim() === 'Preview'); if (b) b.click(); return Boolean(b); })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-stream-progress'))`),
    'large Markdown returns to streamed Preview', 12000);
  const warmAfterPreview = await evalIn(win, `window.__vvwarmcache ? window.__vvwarmcache() : null`);
  if (!releaseBuild) {
    // the hit-counter probe is goog.DEBUG-only (window.__vvwarmcache lives in the dev-hook block);
    // the FLIP BEHAVIOR above ran in both builds — only the cache-counter assertion is dev-scoped
    assert.ok(warmBeforeSource && warmAfterPreview && warmAfterPreview.hits > warmBeforeSource.hits,
      `returning to Preview reuses its prepared artifact (${JSON.stringify(warmBeforeSource)} → ${JSON.stringify(warmAfterPreview)})`);
  }
  console.log(`[ok] large Markdown Source overrides streaming; returning to Preview ${releaseBuild ? 'renders (warm-cache counters are a dev-only probe)' : 'hits the bounded warm cache'}`);

  // Regression: activating a tab used to synchronously rebuild its large document before Chromium could paint
  // the newly active chrome, making Ctrl+PageUp/PageDown appear frozen for ~2 seconds. Add a tiny tab to guarantee
  // another target exists, select the large tab's actual right-hand neighbor, then measure the REAL key path in
  // both directions. The tab state, URI bar, and target surface must all settle within a human-immediate budget;
  // the large return must
  // also hit the prepared-artifact cache, or this could pass only because the fixture happened to parse quickly.
  const tabLatencyPath = path.join(md.dir, 'tab-latency.md');
  const tabLatencyText = '# Tab latency neighbor\n\nSmall adjacent document.\n';
  fs.writeFileSync(tabLatencyPath, tabLatencyText);
  state.contentByPath.set(tabLatencyPath, {
    path: tabLatencyPath, kind: 'markdown', text: tabLatencyText,
    stamp: Date.now(), sourceable: true, meta: { size: Buffer.byteLength(tabLatencyText) }
  });
  // One selected file intentionally navigates the current tab. Supplying the current large path followed by
  // the new file exercises the multi-file contract instead: reactivate the existing first path, then open the
  // second in a NEW tab. Its presence guarantees a distinct neighbor even if the large tab was previously last.
  win.webContents.send('vv:open-files', { paths: [md.streamPath, tabLatencyPath] });
  await waitFor(() => evalIn(win, `document.querySelector('.vv-content')?.dataset.docKey === ${JSON.stringify(tabLatencyPath)}
    && Boolean(document.querySelector('.vv-content .markdown-body h1'))`),
    'small latency-fixture tab becomes active before the measurement', 10000);
  const tabPair = await evalIn(win, `(() => {
    const ui = window.__vvdb().ui;
    const byUri = (uri) => ui.tabs.find((t) => t.uri === uri);
    const large = byUri(${JSON.stringify(md.streamPath)});
    const largeIndex = ui.tabs.findIndex((t) => t.id === large?.id);
    const rightIndex = (largeIndex + 1) % ui.tabs.length;
    const right = ui.tabs[rightIndex];
    const rightDisplay = document.querySelectorAll('.vv-tab')[rightIndex]?.title;
    return { active: ui['active-tab'],
             large: large?.id, small: byUri(${JSON.stringify(tabLatencyPath)})?.id,
             right: right?.id, rightIndex, rightDisplay };
  })()`);
  assert.strictEqual(tabPair.active, tabPair.small, 'latency precondition: the small neighbor is active');
  assert.ok(tabPair.large != null && tabPair.right != null && tabPair.right !== tabPair.large,
    `latency precondition: the large tab has a distinct right neighbor (${JSON.stringify(tabPair)})`);
  await evalIn(win, `(() => {
    const tab = document.querySelectorAll('.vv-tab')[${tabPair.rightIndex}];
    if (tab) tab.click();
    return Boolean(tab);
  })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${tabPair.right}
    && document.querySelector('.vv-uri-input')?.value === ${JSON.stringify(tabPair.rightDisplay)}`),
    'the large tab actual right neighbor becomes active before timing', 10000);
  const maxTabSwitchMs = 1000;
  // the hit counters (window.__vvwarmcache) are goog.DEBUG-only; the PAINT-BUDGET assert below is
  // the behavioral guarantee and runs in both builds
  const cacheBeforeTabReturn = await evalIn(win, `window.__vvwarmcache ? window.__vvwarmcache() : null`);
  const largeSwitchStarted = Date.now();
  await dispatchWindowKey(win, 'PageUp', { ctrlKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${tabPair.large}
    && document.querySelector('.vv-uri-input')?.value === ${JSON.stringify(`file://${md.streamPath}`)}
    && document.querySelector('.vv-content')?.dataset.docKey === ${JSON.stringify(md.streamPath)}
    && Boolean(document.querySelector('.vv-content .markdown-body h1'))`),
    'Ctrl+PageUp paints the warmed large streamed tab', maxTabSwitchMs);
  const largeSwitchMs = Date.now() - largeSwitchStarted;
  const cacheAfterTabReturn = await evalIn(win, `window.__vvwarmcache ? window.__vvwarmcache() : null`);
  if (!releaseBuild) {
    assert.ok(cacheAfterTabReturn.hits > cacheBeforeTabReturn.hits,
      `large tab return must hit the warm cache (${JSON.stringify(cacheBeforeTabReturn)} → ${JSON.stringify(cacheAfterTabReturn)})`);
  }
  assert.ok(largeSwitchMs < maxTabSwitchMs,
    `Ctrl+PageUp took ${largeSwitchMs} ms to paint the warmed large tab (budget ${maxTabSwitchMs} ms)`);

  const neighborSwitchStarted = Date.now();
  await dispatchWindowKey(win, 'PageDown', { ctrlKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${tabPair.right}
    && document.querySelector('.vv-uri-input')?.value === ${JSON.stringify(tabPair.rightDisplay)}`),
    'Ctrl+PageDown paints the actual right-hand tab', maxTabSwitchMs);
  const neighborSwitchMs = Date.now() - neighborSwitchStarted;
  assert.ok(neighborSwitchMs < maxTabSwitchMs,
    `Ctrl+PageDown took ${neighborSwitchMs} ms to paint the right-hand tab (budget ${maxTabSwitchMs} ms)`);
  console.log(`[ok] Ctrl+Page tab switching paints within ${maxTabSwitchMs} ms (large ${largeSwitchMs} ms, neighbor ${neighborSwitchMs} ms; warm-cache hit)`);

  // Return to the large fixture for its remaining Contents/windowing/live-refresh assertions.
  await dispatchWindowKey(win, 'PageUp', { ctrlKey: true });
  await waitFor(() => evalIn(win, `document.querySelector('.vv-content')?.dataset.docKey === ${JSON.stringify(md.streamPath)}
    && Boolean(document.querySelector('.vv-content .markdown-body h1'))`),
    'large streamed tab is restored after the latency measurement', maxTabSwitchMs);

  // Contents populated from the streamed markdown headings (set upfront from the one base-pipeline pass)
  await waitCalm(win, `document.querySelectorAll('.vv-toc-item').length >= ${md.sections}`, 'streamed markdown Contents lists its headings', 10000);
  console.log('[ok] streamed markdown Contents is populated');

  // ── windowed DOM: content-visibility skips layout/paint of off-screen streamed blocks (bounded render),
  //    and the trailing spacer (Phase 1) is a SIBLING of .markdown-body so it never pollutes the parity capture ──
  const windowing = await evalIn(win, `(() => {
    const body = document.querySelector('.vv-content .markdown-body.vv-streamed');
    if (!body) return { streamed: false };
    const blocks = Array.from(body.children).filter((e) => e.nodeType === 1);
    const first = blocks[0], last = blocks[blocks.length - 1];
    // at scrollTop 0 a far-down block should be SKIPPED by content-visibility:auto (not rendered)
    const skipped = last && last.checkVisibility ? !last.checkVisibility({ contentVisibilityAuto: true }) : null;
    const spacer = document.querySelector('.vv-content .vv-stream-spacer');
    return { streamed: true, count: blocks.length, cv: getComputedStyle(first).contentVisibility, skipped,
             spacerPresent: Boolean(spacer),
             spacerIsSibling: Boolean(spacer) && spacer.parentElement === body.parentElement && !body.contains(spacer) };
  })()`);
  assert.strictEqual(windowing.streamed, true, 'the streamed body carries .vv-streamed (windowed render)');
  assert.strictEqual(windowing.cv, 'auto', 'streamed top-level blocks use content-visibility:auto (off-screen layout/paint skipped per the CSS spec)');
  assert.strictEqual(windowing.spacerPresent, true, 'the streamed body has a height spacer (Phase 1 whole-document scrollbar)');
  assert.strictEqual(windowing.spacerIsSibling, true, 'the spacer is a SIBLING of .markdown-body (never a child → byte-parity capture stays clean)');
  // checkVisibility({contentVisibilityAuto}) is informational — its reporting of the skipped state is not yet
  // reliable across Chromium builds, but the applied content-visibility:auto is the load-bearing guarantee.
  console.log(`[ok] streamed DOM is render-windowed — content-visibility:auto on ${windowing.count} blocks (off-screen skipped=${windowing.skipped}); sibling height spacer present`);

  // ── live-refresh scroll re-anchor: a streamed doc that re-renders keeps the reader's scroll position ──
  await evalIn(win, `(() => { document.querySelector('.vv-content').scrollTop = 3000; return true; })()`);
  await delay(120);
  const beforeScroll = await evalIn(win, `document.querySelector('.vv-content').scrollTop`);
  assert.ok(beforeScroll > 1000, `precondition: scrolled the streamed markdown down (got ${beforeScroll})`);
  win.webContents.send('vv:content', { path: md.streamPath, kind: 'markdown', text: md.text, stamp: Date.now(), sourceable: true, meta: { size: md.size } });
  // wait for the remount to CLEAR + restart the stream (h2 drops as the new body begins empty) so we don't
  // match the still-present old completed render, THEN wait for the re-stream to finish
  await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-content .markdown-body h2').length < ${batchH2}`),
    're-stream restarts (old content cleared)', 15000);
  await waitCalm(win, `(() => { const p = document.querySelector('.vv-content .vv-stream-progress');
    return p && p.classList.contains('vv-stream-progress-done')
      && document.querySelectorAll('.vv-content .markdown-body h2').length >= ${batchH2}; })()`,
    'streamed markdown re-streams after a live-refresh', 60000);
  await delay(500);
  const geo = JSON.parse(await evalIn(win, `(() => { const c = document.querySelector('.vv-content');
    return JSON.stringify({ top: c.scrollTop, sh: c.scrollHeight, ch: c.clientHeight,
      h2: document.querySelectorAll('.vv-content .markdown-body h2').length }); })()`));
  const afterScroll = geo.top;
  assert.ok(afterScroll > beforeScroll * 0.7, `scroll re-anchored after live-refresh (was ${beforeScroll}, now ${afterScroll}; geo=${JSON.stringify(geo)})`);
  console.log(`[ok] streamed doc re-anchors scroll across a live-refresh (${beforeScroll} → ${afterScroll})`);

  // ── ORG streaming parity: the progressive engine is format-agnostic (it commits IR children, not markdown
  //    nodes), so a >256 KiB .org must stream byte-identically to its batch render, exactly as markdown does.
  //    Runs AFTER the markdown windowing / re-anchor assertions, which read the ACTIVE .vv-content. ─────────
  const orgBig = createLargeOrgFixtures();
  assert.ok(orgBig.size > 256 * 1024, `org fixture (${orgBig.size} B) must exceed the 256 KiB stream threshold`);
  const orgStamp = Date.now();

  win.webContents.send('vv:settings', '{:stream? false}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['stream?'] === false`), 'streaming disabled for the org batch reference');
  state.contentByPath.set(orgBig.batchPath, { ...(await contentService.openUri(orgBig.batchPath)), stamp: orgStamp });
  win.webContents.send('vv:open-files', { paths: [orgBig.batchPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
    return b && b.querySelectorAll('h2').length >= ${orgBig.sections} && !document.querySelector('.vv-content .vv-stream-progress'); })()`),
    'batch org renders whole (no stream progress strip)', 30000);
  await delay(400);
  const orgBatchHtml = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
  const orgBatchH2 = await evalIn(win, `document.querySelectorAll('.vv-content .markdown-body h2').length`);
  // the parity comparison is only meaningful if the batch render actually contains the constructs whose
  // per-block handling could diverge — MathJax SVG, the RENDERED embedded-latex (ADR-0025), and task checkboxes
  const orgBatchHas = await evalIn(win, `(() => {
    const b = document.querySelector('.vv-content .markdown-body');
    return { math: b.querySelectorAll('.vv-math-inline svg').length,
             latexBlocks: b.querySelectorAll('pre code[class*="language-latex"]').length,
             rendered: (b.textContent.match(/Not math/g) || []).length,
             tasks: b.querySelectorAll('li.task-list-item input[type=checkbox]').length };
  })()`);
  assert.ok(orgBatchHas.math >= orgBatchH2, `batch org rendered inline MathJax in every section (${orgBatchHas.math})`);
  assert.strictEqual(orgBatchHas.latexBlocks, 0, 'the non-math export blocks are RENDERED, never a language-latex code block');
  assert.ok(orgBatchHas.rendered >= orgBatchH2, `the embedded latex export block is rendered in every section (${orgBatchHas.rendered})`);
  assert.ok(orgBatchHas.tasks >= 2 * orgBatchH2, `batch org rendered task-list checkboxes (${orgBatchHas.tasks})`);
  console.log(`[ok] batch org reference rendered (${orgBatchH2} sections, ${(orgBig.size / 1024).toFixed(0)} KiB; ${orgBatchHas.math} math, ${orgBatchHas.rendered} rendered latex blocks)`);

  win.webContents.send('vv:settings', '{:stream? true}');
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.settings['stream?'])`), 'streaming re-enabled for the streamed org render');
  state.contentByPath.set(orgBig.streamPath, { ...(await contentService.openUri(orgBig.streamPath)), stamp: orgStamp });
  win.webContents.send('vv:open-files', { paths: [orgBig.streamPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => {
    const streaming = Boolean(document.querySelector('.vv-content .vv-stream-progress'));
    return streaming && document.querySelectorAll('.vv-content .markdown-body h2').length > 0;
  })()`), 'org streams — progress strip + first blocks paint', 25000);
  await waitCalm(win, `document.querySelectorAll('.vv-content .markdown-body h2').length >= ${orgBatchH2}
    && (() => { const p = document.querySelector('.vv-content .vv-stream-progress'); return p && p.classList.contains('vv-stream-progress-done'); })()`,
    'streamed org converges to the whole document', 60000);
  await delay(600);
  const orgStreamHtml = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
  if (orgStreamHtml !== orgBatchHtml) {
    const at = firstDiff(orgStreamHtml, orgBatchHtml);
    console.error(`org parity MISMATCH at char ${at} / stream ${orgStreamHtml.length} vs batch ${orgBatchHtml.length}`);
    console.error('stream …', JSON.stringify(orgStreamHtml.slice(Math.max(0, at - 80), at + 120)));
    console.error('batch  …', JSON.stringify(orgBatchHtml.slice(Math.max(0, at - 80), at + 120)));
  }
  assert.strictEqual(orgStreamHtml, orgBatchHtml, 'streamed org innerHTML must be byte-identical to the batch render');
  console.log('[ok] streamed org is BYTE-IDENTICAL to the batch render (same progressive engine as markdown)');

  // ── LaTeX streaming parity (ADR-0025): a >256 KiB .tex must stream byte-identically to its batch render. Same
  //    whole-parse + progressive-paint engine; only the block provider (latex-stream-blocks) differs. For a full
  //    `\begin{document}`, renderer.latex-structure numbers each \section into an <h2> (an explicit slug id +
  //    prepended number) — the stream runs the SAME whole-document restructure, so parity holds. ──────────────
  const texBig = createLargeLatexFixtures();
  assert.ok(texBig.size > 256 * 1024, `latex fixture (${texBig.size} B) must exceed the 256 KiB stream threshold`);
  const texStamp = Date.now();

  win.webContents.send('vv:settings', '{:stream? false}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['stream?'] === false`), 'streaming disabled for the latex batch reference');
  state.contentByPath.set(texBig.batchPath, { ...(await contentService.openUri(texBig.batchPath)), stamp: texStamp });
  win.webContents.send('vv:open-files', { paths: [texBig.batchPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
    return b && b.querySelectorAll('h2').length >= ${texBig.sections} && !document.querySelector('.vv-content .vv-stream-progress'); })()`),
    'batch latex renders whole (no stream progress strip)', 40000);
  await delay(400);
  const texBatchHtml = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
  const texBatchSections = await evalIn(win, `document.querySelectorAll('.vv-content .markdown-body h2').length`);
  const texBatchHas = await evalIn(win, `(() => {
    const b = document.querySelector('.vv-content .markdown-body');
    return { math: b.querySelectorAll('.vv-math-inline svg').length,
             tables: b.querySelectorAll('table').length,
             bold: b.querySelectorAll('b, strong').length };
  })()`);
  assert.ok(texBatchHas.math >= texBatchSections, `batch latex typeset inline MathJax in every section (${texBatchHas.math})`);
  assert.ok(texBatchHas.tables >= texBatchSections, `batch latex rendered a tabular in every section (${texBatchHas.tables})`);
  assert.ok(texBatchHas.bold >= texBatchSections, `batch latex rendered \\textbf in every section (${texBatchHas.bold})`);
  const firstTexH2 = (texBatchHtml.match(/<h2[^>]*>[\s\S]*?<\/h2>/) || ['<no h2>'])[0];
  assert.ok(/id="user-content-1-section-0"/.test(firstTexH2) && /1 Section 0/.test(firstTexH2),
    `restructure numbered the first \\section (1 Section 0) with a clobber-prefixed slug id — got: ${firstTexH2.slice(0, 140)}`);
  console.log(`[ok] batch latex reference rendered (${texBatchSections} numbered sections, ${(texBig.size / 1024).toFixed(0)} KiB; ${texBatchHas.math} math, ${texBatchHas.tables} tables)`);

  win.webContents.send('vv:settings', '{:stream? true}');
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.settings['stream?'])`), 'streaming re-enabled for the streamed latex render');
  state.contentByPath.set(texBig.streamPath, { ...(await contentService.openUri(texBig.streamPath)), stamp: texStamp });
  win.webContents.send('vv:open-files', { paths: [texBig.streamPath], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => {
    const streaming = Boolean(document.querySelector('.vv-content .vv-stream-progress'));
    return streaming && document.querySelectorAll('.vv-content .markdown-body h2').length > 0;
  })()`), 'latex streams — progress strip + first blocks paint', 30000);
  await waitCalm(win, `document.querySelectorAll('.vv-content .markdown-body h2').length >= ${texBatchSections}
    && (() => { const p = document.querySelector('.vv-content .vv-stream-progress'); return p && p.classList.contains('vv-stream-progress-done'); })()`,
    'streamed latex converges to the whole document', 60000);
  await delay(600);
  const texStreamHtml = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
  if (texStreamHtml !== texBatchHtml) {
    const at = firstDiff(texStreamHtml, texBatchHtml);
    console.error(`latex parity MISMATCH at char ${at} / stream ${texStreamHtml.length} vs batch ${texBatchHtml.length}`);
    console.error('stream …', JSON.stringify(texStreamHtml.slice(Math.max(0, at - 80), at + 120)));
    console.error('batch  …', JSON.stringify(texBatchHtml.slice(Math.max(0, at - 80), at + 120)));
  }
  assert.strictEqual(texStreamHtml, texBatchHtml, 'streamed latex innerHTML must be byte-identical to the batch render');
  console.log('[ok] streamed latex is BYTE-IDENTICAL to the batch render (same progressive engine, latex-stream-blocks)');

  // ── Phase 3 — PDF reflow streaming parity (dev-only: the reflow toggle drives the re-frame global) ──
  // The opt-in "Reflow Text" view renders the extracted PDF text as prose. With streaming on it commits that
  // text progressively (ir-stream-body "pdf-reflow") and must be BYTE-IDENTICAL to the batch reflow HTML.
  if (!releaseBuild) {
    const toggleReflow = () => evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("pdf","reflow-toggle"))); true`);
    const reflowPdf = path.join(ROOT, 'test', 'fixtures', 'reflow-stream.pdf');   // virtual path; smoke.pdf bytes carry real text
    state.contentByPath.set(reflowPdf, { path: reflowPdf, kind: 'pdf', bytes: new Uint8Array(fs.readFileSync(path.join(ROOT, 'test', 'fixtures', 'smoke.pdf'))), stamp: Date.now() });

    // batch reflow (stream OFF) → reference innerHTML
    win.webContents.send('vv:settings', '{:stream? false}');
    await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['stream?'] === false`), 'stream off for the batch reflow reference');
    win.webContents.send('vv:open-files', { paths: [reflowPdf], 'focus-first': true });
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), 'reflow PDF canvas mounts', 15000);
    await toggleReflow();
    await waitCalm(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
      return b && b.textContent.includes('Smoke') && !document.querySelector('.vv-content .vv-stream-progress'); })()`,
      'batch reflow renders the extracted text', 15000);
    const batchReflow = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
    await toggleReflow();   // back to canvas
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), 'canvas returns after reflow off', 10000);

    // streamed reflow (stream ON) → progressive block-commit of the same extracted text
    win.webContents.send('vv:settings', '{:stream? true}');
    await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.settings['stream?'])`), 'stream on for reflow streaming');
    await toggleReflow();
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-stream-progress'))`), 'reflow streams (progress strip present)', 10000);
    await waitCalm(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
      const p = document.querySelector('.vv-content .vv-stream-progress');
      return b && b.textContent.includes('Smoke') && p && p.classList.contains('vv-stream-progress-done'); })()`,
      'streamed reflow completes', 15000);
    await delay(300);
    const streamReflow = await evalIn(win, `document.querySelector('.vv-content .markdown-body').innerHTML`);
    if (streamReflow !== batchReflow) {
      const at = firstDiff(streamReflow, batchReflow);
      console.error(`reflow parity MISMATCH at ${at}`, JSON.stringify(streamReflow.slice(Math.max(0, at - 60), at + 80)),
        'vs', JSON.stringify(batchReflow.slice(Math.max(0, at - 60), at + 80)));
    }
    assert.strictEqual(streamReflow, batchReflow, 'streamed PDF reflow must be byte-identical to the batch reflow');
    console.log('[ok] streamed PDF reflow is byte-identical to the batch reflow (progressive extracted-text commit)');
  }

  // ── Part 3: a standalone .tex renders its STRUCTURE in the LaTeX preview (numbering, refs, cites, bib, front
  // matter) — the full DOM pipeline (renderer.latex-structure → tex-normalize → MathJax → the vv-* CSS). No PDF
  // sibling, so the doc opens showing its own LaTeX preview (the restructured HTML in .markdown-body).
  const standaloneTex = path.join(ROOT, 'test', 'standalone.tex');
  const standaloneSrc = [
    '\\documentclass{article}', '\\usepackage{amsthm,amsmath,xcolor}',
    '\\newtheorem{theorem}{Theorem}', '\\newtheorem{definition}[theorem]{Definition}',
    '\\definecolor{redcopy}{rgb}{0.78,0.08,0.08}', '\\newcommand{\\rc}[1]{\\textcolor{redcopy}{#1}}',
    '\\newcommand{\\rSet}{\\rc{\\mathsf{Set}}}', '\\newcommand{\\out}[2]{#1!(#2)}',
    '\\title{Standalone Paper}', '\\author{Test Author}', '\\date{2026}',
    '\\begin{document}', '\\maketitle',
    '\\begin{abstract}', 'An abstract citing \\cite{knuth}.', '\\end{abstract}', '\\tableofcontents',
    '\\section{First}\\label{sec:first}',
    // $\out{a}{0}\mid\out{a}{0}$: after expansion \mid sits before the expanded a — must NOT glue into \mida
    'Body with math $\\rSet$, a process $\\out{a}{0}\\mid\\out{a}{0}$, ref \\S\\ref{sec:second}, eq \\eqref{eq:e}, and cite \\cite{knuth}.',
    '\\begin{equation}\\label{eq:e}', 'x = y', '\\end{equation}',
    '\\begin{definition}[A widget]\\label{def:w}', 'See Definition~\\ref{def:w}.', '\\end{definition}',
    '\\section{Second}\\label{sec:second}', 'Done.',
    '\\begin{thebibliography}{9}', '\\bibitem{knuth} D. Knuth. The Art. 1968.', '\\end{thebibliography}',
    '\\end{document}',
  ].join('\n');
  state.contentByPath.set(standaloneTex, { path: standaloneTex, kind: 'latex', text: standaloneSrc, meta: { size: standaloneSrc.length } });
  await evalIn(win, `window.__vvopen(${JSON.stringify(standaloneTex)})`);
  win.webContents.send('vv:content', { path: standaloneTex, kind: 'latex', text: standaloneSrc, stamp: Date.now(), meta: { size: standaloneSrc.length } });
  await waitFor(() => evalIn(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
    return Boolean(b) && Boolean(b.querySelector('h2') && b.querySelector('[id="user-content-vv-bibliography"] li') && b.querySelector('svg')); })()`),
    'the standalone .tex renders its restructured LaTeX preview (with math typeset)', 15000);
  const tex = await evalIn(win, `(() => {
    const b = document.querySelector('.vv-content .markdown-body');
    const h2s = Array.from(b.querySelectorAll('h2')).map(h => ({ id: h.id, t: h.textContent.trim() }));
    const bibLi = b.querySelector('[id="user-content-vv-bibliography"] li');
    const citeLink = b.querySelector('a[href="#user-content-vv-bib-knuth"]');
    const tocLink = b.querySelector('[id="user-content-vv-toc"] a');
    const thm = Array.from(b.querySelectorAll('strong')).map(s => s.textContent.trim()).find(t => /^Definition 1\\b/.test(t));
    return {
      title: b.querySelector('[id="user-content-vv-doc-header"] h1')?.textContent.trim(),
      abstract: h2s.some(h => h.t === 'Abstract') && Boolean(b.querySelector('[id="user-content-vv-abstract"]')),
      sec1: h2s.find(h => /^1\\s+First/.test(h.t)) || null,
      sec2: h2s.find(h => /^2\\s+Second/.test(h.t)) || null,
      bibMark: bibLi?.textContent.trim().slice(0, 3), bibId: bibLi?.id,
      cite: Boolean(citeLink), citeText: citeLink?.textContent.trim(),
      toc: tocLink?.getAttribute('href'),
      thm: thm || null,
      refResolved: /Definition\\s*1/.test(b.textContent),
      mathRendered: Boolean(b.querySelector('.vv-math-inline svg, .vv-math-display svg, svg')),
      leaks: /\\\\cite|\\\\ref\\{|\\\\label|texorpdfstring|\\\\maketitle/.test(b.textContent),
      mathSources: Array.from(b.querySelectorAll('[data-tex]')).map(e => e.getAttribute('data-tex') || ''),
    };
  })()`);
  assert.strictEqual(tex.title, 'Standalone Paper', 'the \\title renders inside the <section id=vv-doc-header> block');
  assert.strictEqual(tex.abstract, true, 'the abstract renders under an Abstract heading, in a <section id=vv-abstract>');
  assert.ok(tex.sec1 && tex.sec1.id === 'user-content-1-first', 'section 1 is a numbered heading with a (clobber-prefixed) slug id');
  assert.ok(tex.sec2 && tex.sec2.id === 'user-content-2-second', 'section 2 is numbered 2 with its own id');
  assert.strictEqual(tex.bibMark, '[1]', 'the bibliography entry is enumerated [1]');
  assert.strictEqual(tex.bibId, 'user-content-vv-bib-knuth', 'the bib entry carries the clobber-prefixed anchor id (a <li>)');
  assert.ok(tex.cite && tex.citeText === '1', 'the \\cite renders as a link whose text is the bib number');
  assert.strictEqual(tex.toc, '#user-content-1-first', 'the table of contents links to the numbered section anchor');
  assert.ok(/^Definition 1 \(A widget\)/.test(tex.thm || ''), 'the definition env gets a numbered <strong> header');
  assert.strictEqual(tex.refResolved, true, '\\ref{def:w} resolved to the definition number (Definition 1)');
  assert.strictEqual(tex.mathRendered, true, 'the inline math (a red \\rSet) typeset via MathJax');
  assert.strictEqual(tex.leaks, false, 'no structural LaTeX macro leaks into the rendered preview');
  // deglue: \out{a}{0}\mid\out{a}{0} — the \mid must survive as a relation, never glued into the undefined \mida
  assert.ok(tex.mathSources.length > 0, 'the typeset math stashes its LaTeX source in data-tex');
  assert.ok(!tex.mathSources.some((t) => /\\mida/.test(t)),
    `no control word glued to a letter into the undefined \\mida — sources: ${JSON.stringify(tex.mathSources)}`);
  assert.ok(tex.mathSources.some((t) => /\\mid(?:\{\}|[^A-Za-z]|$)/.test(t)),
    'the \\mid relation survives, separated from the following letter');
  console.log('[ok] Part 3: a standalone .tex renders numbered sections, refs, cites, a bibliography + front matter (no \\mida glue)');

  // ── Collocated document GROUP: the [Preview ▾ | Source ▾] combo (replaces the old [Doc | PDF] switch) ──
  // A .tex collocated with an exported .pdf. Its group = {latex, pdf}. By the collocated-default :pdf preference it
  // opens showing the sibling PDF IN PLACE (a facet load — no navigation), and the PDF surface drops the reading
  // gutter (vv-content-pdf-flush). The toolbar shows Preview (a combo: PDF + LaTeX) and Source (LaTeX). Clicking
  // Source shows the .tex source in place; the tab URI never changes.
  const texPath = path.join(ROOT, 'test', 'paper.tex');
  const texSiblingPdf = path.join(ROOT, 'test', 'paper.pdf');
  // three sections spread over many lines so the source Contents scroll-spy has distinct sections to track
  const texFiller = (tag) => Array.from({ length: 30 }, (_, i) => `${tag} filler line ${i}.`).join('\n');
  const texContent = '\\documentclass{article}\n\\begin{document}\n\\section{Intro}\n' + texFiller('Intro')
    + '\n\\section{Methods}\n' + texFiller('Methods') + '\n\\section{Results}\n' + texFiller('Results')
    + '\n\\end{document}\n';
  const texGroup = [{ path: texPath, kind: 'latex' }, { path: texSiblingPdf, kind: 'pdf' }];
  // register BOTH representations (each carries the full :siblings group); the default-facet load fetches the PDF
  // in place via the harness's vv:open auto-responder.
  state.contentByPath.set(texPath, { path: texPath, kind: 'latex', text: texContent, siblings: texGroup, meta: { size: texContent.length } });
  state.contentByPath.set(texSiblingPdf, { path: texSiblingPdf, kind: 'pdf', bytes: new Uint8Array(fs.readFileSync(pdfFixture)), siblings: texGroup });
  await evalIn(win, `window.__vvopen(${JSON.stringify(texPath)})`);
  win.webContents.send('vv:content', { path: texPath, kind: 'latex', text: texContent, siblings: texGroup, stamp: Date.now(), meta: { size: texContent.length } });
  // the default facet (collocated-default :pdf) loads + shows the sibling PDF in place → the flush class appears
  // once the PDF facet's doc entity resolves (its bytes arrive via the facet vv:open round-trip).
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-content')?.classList.contains('vv-content-pdf-flush') && Boolean(document.querySelector('.vv-content .vv-pdf-doc'))`),
    'a .tex with a collocated PDF opens showing the PDF in place (flush gutter)', 15000
  );
  const combo = await evalIn(win, `(() => ({
    group: Boolean(document.querySelector('.vv-combo-group')),
    labels: Array.from(document.querySelectorAll('.vv-combo-group .vv-combo-main, .vv-combo-group .vv-combo-plain')).map(b => b.textContent.trim()),
    uri: (() => { const ui = window.__vvdb().ui; const t = ui.tabs.find(x => x.id === ui['active-tab']); return t && t.uri; })()
  }))()`);
  assert.ok(combo.group, 'the [Preview | Source] combo is shown for a doc with a collocated group');
  assert.deepStrictEqual(combo.labels, ['Preview', 'Source'], 'both the Preview and Source buttons are present');
  assert.ok((combo.uri || '').includes('paper.tex'), 'the tab stays on the primary .tex — the facet switch is IN-PLACE (no navigation)');
  console.log('[ok] a collocated .tex+PDF group shows the [Preview | Source] combo and opens the PDF in place (flush)');
  // the Preview combo's menu must open DIRECTLY ADJACENT to the button (no dead-zone gap) — a gap fires the
  // .vv-combo mouseleave and closes the menu before the cursor reaches an option (the reported bug).
  await evalIn(win, `(() => { const c=[...document.querySelectorAll('.vv-combo')].find(x=>x.querySelector('.vv-combo-main')?.textContent.trim()==='Preview'); c.querySelector('.vv-combo-caret').click(); return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-combo-menu'))`), 'the Preview combo menu opens', 4000);
  const menuGeom = await evalIn(win, `(() => {
    const c=[...document.querySelectorAll('.vv-combo')].find(x=>x.querySelector('.vv-combo-menu'));
    const mr=c.querySelector('.vv-combo-menu').getBoundingClientRect(), cr=c.getBoundingClientRect();
    return { gap: Math.round(mr.top - cr.bottom), opts: [...c.querySelectorAll('.vv-combo-opt')].map(o=>o.textContent.trim()) };
  })()`);
  assert.ok(menuGeom.gap <= 0, 'the combo menu opens flush against the button (no dead-zone gap that would close it on hover-to-option); menu.top - combo.bottom = ' + menuGeom.gap + 'px');
  assert.deepStrictEqual(menuGeom.opts, ['PDF', 'LaTeX'], 'the Preview menu lists both representations (PDF, LaTeX)');
  console.log('[ok] the combo menu opens flush against the button (survives hover-to-option) + lists its files');
  await evalIn(win, `(() => { const c=[...document.querySelectorAll('.vv-combo')].find(x=>x.querySelector('.vv-combo-menu')); if(c) c.querySelector('.vv-combo-caret').click(); return true; })()`); // close the menu
  // click Source (its main region) → the .tex source shows in place; the flush gutter is dropped
  await evalIn(win, `(() => { const b = Array.from(document.querySelectorAll('.vv-combo-group .vv-combo-main, .vv-combo-group .vv-combo-plain')).find(x => x.textContent.trim() === 'Source'); if (b) b.click(); })()`);
  await waitFor(
    () => evalIn(win, `(() => { const s = document.querySelector('.vv-content .vv-source .cm-line'); return Boolean(s) && !document.querySelector('.vv-content').classList.contains('vv-content-pdf-flush'); })()`),
    'clicking Source shows the .tex source in place (gutter restored)', 8000
  );
  const stillTex = await evalIn(win, `(() => { const ui = window.__vvdb().ui; const t = ui.tabs.find(x => x.id === ui['active-tab']); return t && t.uri; })()`);
  assert.ok((stillTex || '').includes('paper.tex'), 'Source is shown IN PLACE — the tab URI is unchanged (a facet switch pushes a same-uri history entry, not a cross-document navigation)');
  console.log('[ok] the [Preview | Source] combo switches facets in place (tab URI unchanged)');

  // ── Part 2: a view (facet) switch is a HISTORY event → Back/Forward restore the previous VIEW + location ──
  // The Source click above switched PDF-preview → .tex-source, pushing a same-uri history entry. Back must return
  // to the PDF preview (the prior view); Forward must return to the .tex source. Driven through the REAL toolbar
  // Back/Forward buttons (release-safe), not a re-frame dispatch.
  const clickNavBtn = (kind) => evalIn(win, `(() => { const b = [...document.querySelectorAll('.vv-nav-btn')].find(x => (x.title||'').startsWith(${JSON.stringify(kind)})); if (b && !b.disabled) { b.click(); return true; } return false; })()`);
  await waitFor(() => evalIn(win, `[...document.querySelectorAll('.vv-nav-btn')].some(x => (x.title||'').startsWith('Back') && !x.disabled)`),
    'the facet switch enabled Back (a history entry was pushed for the same uri)');
  await clickNavBtn('Back');
  await waitFor(
    () => evalIn(win, `(() => { const c = document.querySelector('.vv-content'); return c.classList.contains('vv-content-pdf-flush') && Boolean(c.querySelector('.vv-pdf-doc')) && !c.querySelector('.vv-source'); })()`),
    'Back returns to the previous VIEW — the PDF preview (source gone, flush gutter restored)', 12000
  );
  const backUri = await evalIn(win, `(() => { const ui = window.__vvdb().ui; const t = ui.tabs.find(x => x.id === ui['active-tab']); return t && t.uri; })()`);
  assert.ok((backUri || '').includes('paper.tex'), 'Back keeps the same tab URI (the facet switch was a same-uri history entry)');
  console.log('[ok] Part 2: Back returns to the previous view (PDF preview), same uri');
  await waitFor(() => evalIn(win, `[...document.querySelectorAll('.vv-nav-btn')].some(x => (x.title||'').startsWith('Forward') && !x.disabled)`),
    'Forward is enabled after Back');
  await clickNavBtn('Forward');
  await waitFor(
    () => evalIn(win, `(() => { const c = document.querySelector('.vv-content'); return Boolean(c.querySelector('.vv-source .cm-line')) && !c.classList.contains('vv-content-pdf-flush'); })()`),
    'Forward returns to the .tex source view (gutter dropped again)', 12000
  );
  console.log('[ok] Part 2: Forward returns to the source view — a facet switch is a history event');

  // the Contents panel FOLLOWS the source view's active section on scroll (line-based scroll-spy). Reveal Contents,
  // then click a deep section: the editor scrolls there AND the section is highlighted — previously navigation
  // worked but nothing highlighted (the DOM/pixel preview spy can't see the CodeMirror scroller or L<line> ids).
  await evalIn(win, `(() => { const ui = window.__vvdb().ui;
    if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'contents')
      document.querySelector('[data-vv-rail="contents"]').click();
    return true; })()`);
  // wait until the SOURCE Contents (L<line> ids) has replaced the preview toc AND the spy has highlighted its
  // first section — i.e. parse-outline finished and the initial spy ran (otherwise a click hits a preview slug id)
  await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-toc-item').length >= 3 && Boolean(document.querySelector('.vv-toc-item.vv-toc-active'))`), 'the .tex Source Contents + active section (spy running)', 10000);
  const tocActiveIndex = `(() => { const its=[...document.querySelectorAll('.vv-toc-item')]; const a=document.querySelector('.vv-toc-item.vv-toc-active'); return a ? its.indexOf(a) : -1; })()`;
  // click the MIDDLE section (reachable to the viewport top; the LAST section can't reach the top in a short doc,
  // exactly like the preview spy) → it scrolls the editor there AND the Contents highlights it
  await evalIn(win, `document.querySelectorAll('.vv-toc-item')[1].click()`);
  await waitFor(() => evalIn(win, `${tocActiveIndex} === 1`), 'clicking a source section scrolls the editor there AND highlights it in Contents', 8000);
  // scroll back to the first section → the highlight follows
  await evalIn(win, `document.querySelectorAll('.vv-toc-item')[0].click()`);
  await waitFor(() => evalIn(win, `${tocActiveIndex} === 0`), 'the source Contents highlight follows to another section', 8000);
  console.log('[ok] the Contents panel highlights + follows the source view active section on scroll');

  // a lone PDF (no collocated source / alternate) shows NO view-switch buttons — nothing meaningful to toggle
  const lonePdf = path.join(ROOT, 'test', 'lonely.pdf');
  const loneGroup = [{ path: lonePdf, kind: 'pdf' }];
  state.contentByPath.set(lonePdf, { path: lonePdf, kind: 'pdf', bytes: new Uint8Array(fs.readFileSync(pdfFixture)), siblings: loneGroup });
  await evalIn(win, `window.__vvopen(${JSON.stringify(lonePdf)})`);
  win.webContents.send('vv:content', { path: lonePdf, kind: 'pdf', bytes: new Uint8Array(fs.readFileSync(pdfFixture)), siblings: loneGroup, stamp: Date.now() });
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas.vv-pdf-canvas'))`),
    'the lone PDF renders', 15000
  );
  const loneHasCombo = await evalIn(win, `Boolean(document.querySelector('.vv-combo-group')) || document.querySelectorAll('.vv-combo-main, .vv-combo-plain').length > 0`);
  assert.strictEqual(loneHasCombo, false, 'a lone PDF (no collocated source) shows NO Preview/Source buttons');
  console.log('[ok] a lone PDF shows no view-switch buttons (nothing to toggle)');

  // Command-line opens arrive over vv:open-files before main's asynchronous vv:tree reply. The active-path
  // render therefore has no matching row; :tree/received must schedule one post-render reveal when the row
  // appears instead of leaving every ancestor <details> closed. Keep the Files panel mounted across both
  // deliveries so this exercises that exact lifecycle ordering.
  await evalIn(win, `(() => { const ui = window.__vvdb().ui;
    if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'files')
      document.querySelector('[data-vv-rail="files"]').click();
    return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-sidebar-body'))`),
    'sidebar is visible for the delayed command-line tree regression');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-tree'))`),
    'Files tree is mounted before the command-line document activates');

  const revealRoot = path.join(ROOT, 'test', 'fixtures', 'delayed-tree-project');
  const revealRelA = 'src/features/deep/opened.md';
  const revealRelB = 'next/branch/other.md';
  const revealPathA = path.join(revealRoot, ...revealRelA.split('/'));
  const revealPathB = path.join(revealRoot, ...revealRelB.split('/'));
  for (const [p, title] of [[revealPathA, 'Delayed tree'], [revealPathB, 'Loaded tree']]) {
    state.contentByPath.set(p, { path: p, kind: 'markdown', text: `# ${title}\n`, stamp: Date.now() });
  }
  const revealSnapshot = (target) => `(() => {
    const target=${JSON.stringify(target)};
    const a=[...document.querySelectorAll('.vv-file')]
      .find(x=>x.getAttribute('data-path')===target);
    if(!a) return {path:null, active:false, opens:[]};
    const opens=[];
    for(let el=a.parentElement; el && !el.classList.contains('vv-tree'); el=el.parentElement) {
      if(el.matches('details.vv-dir')) opens.push(Boolean(el.open));
    }
    return {path:a.getAttribute('data-path'), active:a.classList.contains('vv-file-active'), opens};
  })()`;

  win.webContents.send('vv:open-files', { paths: [revealPathA], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => { const ui=window.__vvdb().ui;
    const t=ui.tabs.find(x=>x.id===ui['active-tab']);
    return t?.uri===${JSON.stringify(revealPathA)} && !document.querySelector('.vv-file-active'); })()`),
    'command-line path is active before its project tree exists');
  win.webContents.send('vv:tree', { root: revealRoot, files: [revealRelA, revealRelB] });
  await waitFor(() => evalIn(win, `(${revealSnapshot(revealPathA)}).active`),
    'the delayed tree renders the active command-line row');
  const delayedReveal = await evalIn(win, revealSnapshot(revealPathA));
  assert.strictEqual(delayedReveal.opens.length, 3, 'the delayed active row has three directory ancestors');
  assert.ok(delayedReveal.opens.every(Boolean),
    `every delayed active-row ancestor must expand, got ${JSON.stringify(delayedReveal.opens)}`);
  console.log('[ok] a delayed command-line tree payload expands to + reveals the already-active document');

  // The original, tree-already-loaded activation path remains intact, and reveal is additive: activating B
  // opens its branch without collapsing the branch that the delayed reveal opened for A.
  win.webContents.send('vv:open-files', { paths: [revealPathB], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => { const s=${revealSnapshot(revealPathB)};
    return s.active && s.opens.length===2 && s.opens.every(Boolean); })()`),
    'an active-path change expands a row in an already-loaded tree');
  const retainedReveal = await evalIn(win, revealSnapshot(revealPathA));
  assert.ok(retainedReveal.opens.every(Boolean),
    `revealing another file must not collapse existing folders, got ${JSON.stringify(retainedReveal.opens)}`);
  console.log('[ok] tree reveal still follows later activations and remains additive');

  // ---- ADR-0044: the browser link-gesture family ---------------------------------------------------
  // Ctrl+click and middle-click open a BACKGROUND tab (the reader stays put), Ctrl+Shift+click and
  // Shift+middle-click open a focused one, and middle-click on a tab closes it. Every surface that
  // behaves like a link participates: preview content links, file-tree rows, directory-browser rows
  // (incl. ".."), and the address-bar breadcrumbs. Release-safe: DOM events + __vvdb/__vvopen only.
  {
    const g4 = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-gestures-'));
    tempDirs.push(g4);
    const hubPath = path.join(g4, 'hub.md');
    const leafPath = path.join(g4, 'leaf.md');
    const otherPath = path.join(g4, 'other.md');
    const subDir = path.join(g4, 'sub');
    const subChild = path.join(subDir, 'child.md');
    fs.mkdirSync(subDir);
    fs.writeFileSync(leafPath, '# Leaf\n\nleaf body\n');
    fs.writeFileSync(otherPath, '# Other\n\nother body\n');
    fs.writeFileSync(subChild, '# Child\n\nchild body\n');
    fs.writeFileSync(hubPath, `# Hub\n\n[leaf](leaf.md) and [other](other.md)\n`);
    // markdown is rendered by the RENDERER, so main sends it as kind 'markdown' + text (the content
    // service's generic tail would call a .md file plain text) — mirror that, like the other md fixtures
    for (const p of [hubPath, leafPath, otherPath, subChild]) {
      state.contentByPath.set(p, {
        path: p, kind: 'markdown', text: fs.readFileSync(p, 'utf8'), stamp: Date.now(), sourceable: true
      });
    }
    // directories are listed by main (service.cljs list-dir), not by the content service — mirror the
    // payload shape it sends so the in-pane directory browser renders these fixture folders
    const dirPayload = (dir) => ({
      path: dir, kind: 'directory', stamp: Date.now(),
      entries: fs.readdirSync(dir, { withFileTypes: true }).map((d) => {
        const abs = path.join(dir, d.name);
        const st = fs.statSync(abs);
        return { name: d.name, path: abs, 'dir?': st.isDirectory(),
                 size: st.size, mtime: st.mtimeMs, symlink: false };
      })
    });
    state.contentByPath.set(subDir, dirPayload(subDir));
    state.contentByPath.set(g4, dirPayload(g4));

    const tabsNow = () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      return { count: ui.tabs.length, active: ui['active-tab'],
               activeUri: (ui.tabs.find(t => t.id === ui['active-tab']) || {}).uri,
               uris: ui.tabs.map(t => t.uri) };
    })()`);
    // Dispatch a mouse gesture on the first element matching `sel`, after letting the surface settle:
    // an event dispatched into a node React is mid-way through replacing lands on a detached element and
    // never reaches the delegated root listener, so wait for the node and give the pending render a beat.
    const gesture = async (sel, type, init) => {
      await waitFor(() => evalIn(win, `Boolean(document.querySelector(${JSON.stringify(sel)}))`),
        `${sel} to be present for a ${type} gesture`, 8000);
      await delay(150);
      return evalIn(win, `(() => {
        const el = document.querySelector(${JSON.stringify(sel)});
        if (!el) return false;
        el.dispatchEvent(new MouseEvent(${JSON.stringify(type)},
          Object.assign({ bubbles: true, cancelable: true }, ${JSON.stringify(init)})));
        return true; })()`);
    };
    // Close this section's OWN fixture tabs (everything under the fixture dir) except the one showing
    // `uri`, using the middle-click close gesture itself. Tab titles are uri/display ("file://<path>"
    // locally), so match by containment. Pre-existing tabs from earlier sections are left alone — later
    // sections measure paint budgets against them. The keep-tab must be present BEFORE anything closes:
    // the strip renders a frame after the db settles, and closing while it lags could take it out.
    // return to the hub document: closing the ACTIVE fixture tab hands focus to whatever neighbor is
    // next, which may be an earlier section's tab — the gestures below need the hub's links on screen
    const backToHub = async () => {
      await evalIn(win, `window.__vvopen(${JSON.stringify(hubPath)})`);
      await waitFor(() => evalIn(win,
        `Boolean(document.querySelector('.markdown-body a[href$="leaf.md"]'))`),
        'the hub document is showing again', 8000);
    };

    const closeFixtureTabsBut = async (uri) => {
      await waitFor(() => evalIn(win, `Array.from(document.querySelectorAll('.vv-tab'))
        .some((el) => el.title.includes(${JSON.stringify(uri)}))`),
        `the keep-tab for ${uri} to render before closing the rest`, 8000);
      for (let i = 0; i < 16; i += 1) {
        const done = await evalIn(win, `(() => {
          const tabs = Array.from(document.querySelectorAll('.vv-tab'));
          const keep = tabs.find((el) => el.title.includes(${JSON.stringify(uri)}));
          if (!keep) return true;                       // never close blind
          const victim = tabs.find((el) => el !== keep && el.title.includes(${JSON.stringify(g4)}));
          if (!victim) return true;
          victim.dispatchEvent(new MouseEvent('auxclick', { bubbles: true, cancelable: true, button: 1 }));
          return false; })()`);
        if (done) {
          const t = await tabsNow();
          assert.ok(t.uris.some((u) => u && u.includes(uri)),
            `closeFixtureTabsBut kept the ${uri} tab (tabs: ${JSON.stringify(t.uris)})`);
          return;
        }
        await delay(80);
      }
      throw new Error(`closeFixtureTabsBut(${uri}) did not converge`);
    };

    // remember the pre-section state so the section restores it (later sections measure paint budgets
    // against a warmed cache — leftover tabs would perturb them)
    const sectionStart = await tabsNow();
    await evalIn(win, `window.__vvopen(${JSON.stringify(hubPath)})`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.markdown-body a[href$="leaf.md"]'))`),
      'the gesture-fixture hub document renders its relative links', 8000);
    await closeFixtureTabsBut(hubPath);
    const base = await tabsNow();
    assert.strictEqual(base.uris.filter((u) => u && u.includes(g4)).length, 1,
      'the gesture section starts from exactly one fixture tab (the hub); earlier sections keep theirs');
    await evalIn(win, `(() => { document.querySelector('.vv-content').focus(); return true; })()`);

    // 1 — preview link, middle-click → BACKGROUND tab (active stays on the hub)
    assert.strictEqual(await gesture('.markdown-body a[href$="leaf.md"]', 'auxclick', { button: 1 }), true,
      'the hub renders a resolvable leaf link');
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === base.count + 1 && t.active === base.active
        && t.activeUri === base.activeUri && t.uris.some((u) => u && u.includes('leaf.md'));
    }, 'middle-click on a preview link opens a background tab', 8000);
    assert.strictEqual(await evalIn(win, `document.activeElement === document.querySelector('.vv-content')`), true,
      'a background tab open preserves the document keyboard owner');
    // …and the background tab really loaded: activating it shows the content
    await waitFor(() => evalIn(win,
      `Array.from(document.querySelectorAll('.vv-tab')).some((el) => el.title.includes('leaf.md'))`),
      'the background tab renders in the strip', 8000);
    await evalIn(win, `(() => {
      const tab = Array.from(document.querySelectorAll('.vv-tab'))
        .find((el) => el.title.includes('leaf.md'));
      tab.click(); return true; })()`);
    await waitFor(() => evalIn(win, `document.body.textContent.includes('leaf body')`),
      'the background tab loaded its content while unfocused', 8000);
    await closeFixtureTabsBut(hubPath);
    await backToHub();

    // 2 — preview link, Ctrl+click → BACKGROUND; Ctrl+Shift+click → FOCUSED
    const beforeCtrl = await tabsNow();
    await gesture('.markdown-body a[href$="leaf.md"]', 'click', { button: 0, ctrlKey: true });
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === beforeCtrl.count + 1 && t.activeUri === beforeCtrl.activeUri;
    }, 'Ctrl+click on a preview link opens a background tab', 8000);
    const beforeCtrlShift = await tabsNow();
    await gesture('.markdown-body a[href$="other.md"]', 'click', { button: 0, ctrlKey: true, shiftKey: true });
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === beforeCtrlShift.count + 1 && t.activeUri && t.activeUri.includes('other.md');
    }, 'Ctrl+Shift+click on a preview link opens a FOCUSED tab', 8000);
    await evalIn(win, `window.__vvopen(${JSON.stringify(hubPath)})`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.markdown-body a[href$="leaf.md"]'))`),
      'back on the hub document', 8000);
    await closeFixtureTabsBut(hubPath);
    await backToHub();

    // 3 — file-tree rows: Ctrl+click and middle-click always open a NEW background tab (even when a tab
    //     for that file already exists), while Ctrl+Shift+click reuses/focuses one
    win.webContents.send('vv:tree', {
      root: g4, files: ['hub.md', 'leaf.md', 'other.md', 'sub/child.md'], 'synthetic?': false
    });
    // state-guarded rail click: clicking the ALREADY-active icon collapses the sidebar (ADR-0041)
    await evalIn(win, `(() => { const ui = window.__vvdb().ui;
      if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'files')
        document.querySelector('[data-vv-rail="files"]').click();
      return true; })()`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-file[data-path=${JSON.stringify(leafPath)}]'))`),
      'the gesture-fixture project lists its files', 8000);
    const treeSel = `.vv-file[data-path=${JSON.stringify(leafPath)}]`;
    const beforeTreeAux = await tabsNow();
    assert.strictEqual(await gesture(treeSel, 'auxclick', { button: 1 }), true, 'the tree row is present');
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === beforeTreeAux.count + 1 && t.activeUri === beforeTreeAux.activeUri
        && t.uris.some((u) => u && u.includes('leaf.md'));
    }, 'middle-click on a tree row opens a background tab', 8000);
    const beforeTreeCtrl = await tabsNow();
    await gesture(treeSel, 'click', { button: 0, ctrlKey: true });
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === beforeTreeCtrl.count + 1
        && t.activeUri === beforeTreeCtrl.activeUri
        && t.uris.filter((u) => u && u.includes('leaf.md')).length === 2;
    }, 'Ctrl+click on a tree row opens ANOTHER new tab (always-new, ADR-0044)', 8000);
    const beforeReuse = await tabsNow();
    await gesture(treeSel, 'click', { button: 0, ctrlKey: true, shiftKey: true });
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === beforeReuse.count && t.activeUri && t.activeUri.includes('leaf.md');
    }, 'Ctrl+Shift+click on a tree row focuses an existing tab instead of duplicating it', 8000);
    await evalIn(win, `window.__vvopen(${JSON.stringify(hubPath)})`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.markdown-body a[href$="leaf.md"]'))`), 'back on the hub', 8000);
    await closeFixtureTabsBut(hubPath);
    await backToHub();

    // 4 — directory-browser rows (and the ".." parent row)
    await evalIn(win, `window.__vvopen(${JSON.stringify(subDir)})`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-fb-row[data-path=${JSON.stringify(subChild)}]'))`),
      'the directory browser lists the fixture child', 8000);
    const dirBase = await tabsNow();
    await gesture(`.vv-fb-row[data-path=${JSON.stringify(subChild)}]`, 'auxclick', { button: 1 });
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === dirBase.count + 1 && t.activeUri === dirBase.activeUri
        && t.uris.some((u) => u && u.includes('child.md'));
    }, 'middle-click on a directory row opens a background tab', 8000);
    await gesture(`.vv-fb-row[data-path=${JSON.stringify(subChild)}]`,
      'click', { button: 0, ctrlKey: true, shiftKey: true });
    await waitFor(async () => {
      const t = await tabsNow();
      return t.activeUri && t.activeUri.includes('child.md');
    }, 'Ctrl+Shift+click on a directory row focuses the tab', 8000);
    await evalIn(win, `window.__vvopen(${JSON.stringify(subDir)})`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.vv-fb-row.vv-fb-up'))`), 'the ".." parent row is shown', 8000);
    const upBase = await tabsNow();
    await gesture('.vv-fb-row.vv-fb-up', 'auxclick', { button: 1 });
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === upBase.count + 1 && t.activeUri === upBase.activeUri
        && t.uris.some((u) => u === g4);
    }, 'middle-click on ".." opens the parent directory in a background tab', 8000);
    await evalIn(win, `window.__vvopen(${JSON.stringify(hubPath)})`);
    await waitFor(() => evalIn(win,
      `Boolean(document.querySelector('.markdown-body a[href$="leaf.md"]'))`), 'back on the hub', 8000);
    await closeFixtureTabsBut(hubPath);
    await backToHub();

    // 5 — breadcrumbs (they replace the address input while Ctrl is held over the URI bar, so Ctrl is
    //     already down for every crumb click: middle-click backgrounds, Shift+click focuses)
    await dispatchWindowKey(win, 'Control', { ctrlKey: true });
    // React synthesizes onMouseEnter from delegated mouseover/mouseout (a raw 'mouseenter' never reaches
    // it), so hover the URI bar with a bubbling mouseover whose relatedTarget is outside it
    await evalIn(win, `(() => {
      const bar = document.querySelector('.vv-uribar');
      bar.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, relatedTarget: document.body }));
      return true; })()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-crumb'))`),
      'the breadcrumb bar replaces the address input while Ctrl is held over it', 8000);
    const crumbBase = await tabsNow();
    // the LAST crumb of a file uri is its containing folder chain's leaf — a directory either way
    await evalIn(win, `(() => {
      const crumbs = Array.from(document.querySelectorAll('.vv-crumb'));
      const c = crumbs[crumbs.length - 1];
      c.dispatchEvent(new MouseEvent('auxclick', { bubbles: true, cancelable: true, button: 1 }));
      return true; })()`);
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === crumbBase.count + 1 && t.activeUri === crumbBase.activeUri;
    }, 'middle-click on a breadcrumb opens that folder in a background tab', 8000);
    await evalIn(win, `(() => {
      const ev = new KeyboardEvent('keyup', { key: 'Control', ctrlKey: false, bubbles: true });
      window.dispatchEvent(ev); return true; })()`);
    await closeFixtureTabsBut(hubPath);
    await backToHub();

    // 6 — tab middle-close on BOTH representations, and on the ACTIVE tab
    const beforeClose = await tabsNow();
    await gesture(treeSel, 'auxclick', { button: 1 });   // a background tab to close
    await waitFor(async () => (await tabsNow()).count === beforeClose.count + 1,
      'a background tab exists to close', 8000);
    await evalIn(win, `(() => { const ui = window.__vvdb().ui;
      if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'tabs')
        document.querySelector('[data-vv-rail="tabs"]').click();
      return true; })()`);
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-vtab').length >= 2`),
      'the vertical Tabs panel lists both tabs', 8000);
    await middleClickTab(win, 'leaf.md', '.vv-vtab');
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === beforeClose.count && t.activeUri === beforeClose.activeUri;
    }, 'middle-click in the vertical Tabs panel closes that tab (shared tab-item)', 8000);
    await evalIn(win, `(() => { const ui = window.__vvdb().ui;
      if (!ui['sidebar-visible?'] || String(ui['sidebar-tab']) !== 'files')
        document.querySelector('[data-vv-rail="files"]').click();
      return true; })()`);
    // middle-clicking the ACTIVE tab closes it and a neighbor takes over
    await waitFor(() => evalIn(win, `Boolean(document.querySelector(${JSON.stringify(treeSel)}))`),
      'the Files tree is back before the active-tab close arm', 8000);
    assert.strictEqual(await gesture(treeSel, 'click', { button: 0, ctrlKey: true, shiftKey: true }), true,
      'the tree row is clickable for the focused open');
    await waitFor(async () => (await tabsNow()).activeUri.includes('leaf.md'),
      'a focused second tab is active before the active-tab close', 8000);
    await waitFor(() => evalIn(win,
      `(document.querySelector('.vv-tab.vv-tab-active') || {}).title?.includes('leaf.md') === true`),
      'the focused leaf tab is the active strip entry', 8000);
    const beforeActiveClose = await tabsNow();
    await evalIn(win, `(() => {
      const t = document.querySelector('.vv-tab.vv-tab-active');
      t.dispatchEvent(new MouseEvent('auxclick', { bubbles: true, cancelable: true, button: 1 }));
      return true; })()`);
    // a neighbor takes over — WHICH neighbor depends on the surrounding tab order, so assert the
    // invariants: the tab is gone, one fewer remains, and something is active
    await waitFor(async () => {
      const t = await tabsNow();
      return t.count === beforeActiveClose.count - 1
        && t.activeUri !== beforeActiveClose.activeUri
        && t.active != null;
    }, 'middle-clicking the ACTIVE tab closes it and activates a neighbor', 8000);
    await waitFor(() => evalIn(win, `document.activeElement === document.querySelector('.vv-content')`),
      'the document revealed by active-tab close owns keyboard focus', 8000);

    // restore the pre-section tab set: close every fixture tab (middle-click again) and re-activate the
    // document that was showing when the section began
    for (let i = 0; i < 16; i += 1) {
      const done = await evalIn(win, `(() => {
        const victim = Array.from(document.querySelectorAll('.vv-tab'))
          .find((el) => el.title.includes(${JSON.stringify(g4)}));
        if (!victim) return true;
        victim.dispatchEvent(new MouseEvent('auxclick', { bubbles: true, cancelable: true, button: 1 }));
        return false; })()`);
      if (done) break;
      await delay(80);
    }
    await waitFor(async () => {
      const t = await tabsNow();
      return !t.uris.some((u) => u && u.includes(g4));
    }, 'the gesture section closes its fixture tabs', 8000);
    if (sectionStart.activeUri) {
      const restored = await evalIn(win, `(() => {
        const tab = Array.from(document.querySelectorAll('.vv-tab'))
          .find((el) => el.title.includes(${JSON.stringify(sectionStart.activeUri)}));
        if (!tab) return false;
        tab.click(); return true; })()`);
      if (restored) {
        await waitFor(async () => (await tabsNow()).activeUri === sectionStart.activeUri,
          'the pre-section document is active again', 8000);
      }
    }
    console.log('[ok] link gestures: Ctrl/middle → background, Ctrl+Shift → focused, across preview/tree/dir/breadcrumbs; middle-click closes tabs (ADR-0044)');
  }

  // The CSP is a security control, not advice: a blocked resource is a bug, and it only ever surfaced as one
  // more red line among the re-frame warnings. Injecting MathJax's stylesheet tripped `font-src` this way.
  assert.deepStrictEqual(state.cspViolations, [],
    `the renderer must trigger no Content-Security-Policy violations:\n  ${state.cspViolations.join('\n  ')}`);
  console.log('[ok] no Content-Security-Policy violations during the run');

  win.close();
}

const hardTimeout = setTimeout(() => {
  cleanupTempDirs();
  console.error('Electron smoke test timed out');
  app.exit(1);
}, 240000);   // headroom for the document-streaming sections (a >5 MiB log + a >256 KiB markdown parity render)

main()
  .then(() => {
    clearTimeout(hardTimeout);
    cleanupTempDirs();
    console.log('[ok] Electron smoke test passed');
    app.quit();
  })
  .catch((err) => {
    clearTimeout(hardTimeout);
    cleanupTempDirs();
    console.error(err && err.stack ? err.stack : err);
    app.exit(1);
  });
