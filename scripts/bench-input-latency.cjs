'use strict';

// Input-latency benchmark — the measuring instrument for docs/scientific/10.
//
// It types into each of the app's text fields with REAL key events delivered through the browser-process
// input pipeline (webContents.sendInputEvent), at a fixed cadence, and reports what the renderer-side
// tracer (vinary.renderer.input-trace) recorded.
//
// Why real key events and not `el.value = …` + a synthetic `input`: a synthetic event is untrusted and a
// direct `.value` write bypasses the browser's own edit path entirely, so neither exercises the code that
// is actually slow. `sendInputEvent` enters at the browser-process input pipeline and takes the same path
// a physical key does.
//
// Why the cadence is honest: this file runs in the MAIN process. A renderer that is blocked for 450 ms
// does not delay our setTimeout, so keys really are delivered on schedule and really do queue up — the
// same way a physical keyboard's do.
//
// MEASURED LIMITATION, and why `lagMs` is computed out here rather than read from the tracer: Electron
// stamps an INJECTED event's `timeStamp` when the renderer dispatches it, not when the browser process
// sent it. So the tracer's `queueMs` — the correct metric for a physical keyboard — reads ~0 for a
// synthetic key however blocked the renderer is (confirmed: find-large saturates the main thread with
// four ~450 ms searches and still reports a 4 ms queue). `probeResponsiveness` measures the same quantity
// from the outside instead: main-process round trips that queue behind the block exactly as keys do.
//
// Run (pinned, per the standing benchmarking rule):
//   npm run compile
//   taskset -c 2-9 xvfb-run -a -s "-screen 0 1400x1000x24" \
//     electron --no-sandbox scripts/bench-input-latency.cjs | tee /tmp/vv-input-baseline.txt
//
// Environment:
//   VV_BENCH_GPU=1        keep hardware acceleration on (default: off, like test/find-e2e.js)
//   VV_BENCH_CADENCE=150  ms between keystrokes; overrides the default 150 / 40 / 25 ladder
//   VV_BENCH_JSON=<path>  additionally write the full report as JSON
//   VV_BENCH_ONLY=<name>  run one scenario only (find-small, find-large, find-pdf, tree, palette, uri, prefs)

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
process.env.ELECTRON_OZONE_PLATFORM_HINT = 'x11';
process.env.GDK_BACKEND = 'x11';
process.env.XDG_SESSION_TYPE = 'x11';
delete process.env.WAYLAND_DISPLAY;

const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow } = require('electron');

const ROOT = path.resolve(__dirname, '..');
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-bench-input-')));

const GPU = process.env.VV_BENCH_GPU === '1';
if (!GPU) app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
app.commandLine.appendSwitch('ozone-platform', 'x11');

const ONLY = process.env.VV_BENCH_ONLY || null;

// Three cadences, and the FIRST one is the one that matters.
//
// 150 ms/char (~80 WPM) is an ordinary fast typist — and it is slower than find's 100 ms debounce, so
// EVERY keystroke lands a full search. That is the user's actual complaint: each character pays for a
// whole document walk, and the next characters queue behind it.
//
// 40 ms and 25 ms look faster but are, for this subsystem, EASIER: every keystroke supersedes the
// previous debounce timer, so the whole burst collapses into a single search after typing stops. Reported
// anyway, because the collapse is worth being able to see — and because they are the cadences at which a
// controlled input's stale-value commit has the least slack to hide in.
const CADENCES = process.env.VV_BENCH_CADENCE
  ? [Number(process.env.VV_BENCH_CADENCE)]
  : [150, 40, 25];

// ---- fixtures ----------------------------------------------------------------------------------------

const SMALL_MD = path.join(SCRATCH, 'small.md');
const LARGE_MD = path.join(SCRATCH, 'large.md');
const BIG_PDF = path.join(SCRATCH, 'big.pdf');

// A multi-page PDF with real extractable text (the same construction test/find-e2e.js uses, at a size
// where the text-layer materialization cost is visible).
function makeTextPdf(pageCount) {
  const content = (i) => [
    'BT', '/F1 14 Tf', '72 720 Td',
    `(Page ${i} paragraph text for the input-latency benchmark) Tj`,
    '0 -28 Td', '(quick ) Tj', '(brown fox jumps over the lazy dog) Tj',
    '0 -28 Td', `(needle phrase ${i}) Tj`,
    'ET'
  ].join('\n');

  const objs = [];
  const kids = [];
  const pageObjFirst = 3;
  for (let i = 0; i < pageCount; i++) kids.push(`${pageObjFirst + i * 2} 0 R`);
  objs.push('<< /Type /Catalog /Pages 2 0 R >>');
  objs.push(`<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pageCount} >>`);
  for (let i = 0; i < pageCount; i++) {
    const streamObj = pageObjFirst + i * 2 + 1;
    objs.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${streamObj} 0 R ` +
              `/Resources << /Font << /F1 ${pageObjFirst + pageCount * 2} 0 R >> >> >>`);
    const body = content(i);
    objs.push(`<< /Length ${body.length} >>\nstream\n${body}\nendstream`);
  }
  objs.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');

  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objs.forEach((body, i) => {
    offsets[i] = Buffer.byteLength(pdf, 'latin1');
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xrefStart = Buffer.byteLength(pdf, 'latin1');
  pdf += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((off) => { pdf += `${String(off).padStart(10, '0')} 00000 n \n`; });
  pdf += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

function writeFixtures() {
  const small = ['# Small fixture\n'];
  for (let i = 0; i < 60; i++) {
    small.push(`## Section ${i}\n`);
    small.push(`Paragraph ${i} — the quick brown fox jumps over the lazy dog.\n`);
  }
  fs.writeFileSync(SMALL_MD, small.join('\n'));

  // Comfortably past the 256 KiB markdown streaming threshold (vinary.stream.flag), so this exercises the
  // STREAMING path — the one where find's ensure-active! latches the scheduler's rush? flag.
  const large = ['# Large fixture\n'];
  for (let i = 0; i < 4000; i++) {
    large.push(`## Section ${i}\n`);
    large.push(`Paragraph ${i} — the quick brown fox jumps over the lazy dog, repeatedly and at length so ` +
               `that this document is far past the streaming threshold and the walk cost is measurable. ` +
               `Filler token ${i} alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu.\n`);
  }
  fs.writeFileSync(LARGE_MD, large.join('\n'));

  fs.writeFileSync(BIG_PDF, makeTextPdf(40));
}

require(path.join(ROOT, 'dist', 'main', 'main.js'));   // boots the real app

// ---- harness -----------------------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForWindow() {
  for (let i = 0; i < 300; i++) {
    const [w] = BrowserWindow.getAllWindows();
    if (w && !w.webContents.isLoading()) return w;
    await sleep(100);
  }
  throw new Error('no renderer window appeared');
}

const evalIn = (wc, src) => wc.executeJavaScript(src, true);

async function until(wc, expr, pred, label, tries = 300) {
  let last = null;
  for (let i = 0; i < tries; i++) {
    try { last = await evalIn(wc, expr); } catch (e) { last = 'ERR ' + e.message; }
    if (pred(last)) return last;
    await sleep(100);
  }
  throw new Error(`timed out waiting for: ${label}\n  last = ${JSON.stringify(last)}`);
}

// Deliver one character exactly as a keyboard does: keyDown (what the tracer stamps), char (what inserts
// the text), keyUp. Nothing here awaits the renderer, so the cadence is real even while it is blocked.
function typeChar(win, ch) {
  const wc = win.webContents;
  wc.sendInputEvent({ type: 'keyDown', keyCode: ch });
  wc.sendInputEvent({ type: 'char', keyCode: ch });
  wc.sendInputEvent({ type: 'keyUp', keyCode: ch });
}

async function typeString(win, s, cadenceMs) {
  for (const ch of s) {
    typeChar(win, ch);
    await sleep(cadenceMs);
  }
}

/**
 * Measure perceived lag FROM OUTSIDE the renderer.
 *
 * The tracer's own `queueMs` (`performance.now() - event.timeStamp`) is the right metric for a physical
 * keyboard, but Electron stamps a `sendInputEvent`-injected event when the RENDERER dispatches it, not
 * when the browser process sent it — so for a synthetic key it reads ~0 no matter how blocked the
 * renderer is. Measured and confirmed: `find-large` saturates the main thread with four ~450 ms searches
 * and still reports a 4 ms queue.
 *
 * So the harness measures the same quantity the only way it honestly can: from the main process, fire a
 * trivial `executeJavaScript` every `intervalMs` and time the round trip. These queue behind a blocked
 * renderer exactly as keystrokes do, so the worst round trip is the worst time a character would wait
 * before appearing. Returns the round trips in ms.
 */
function probeResponsiveness(win, durationMs, intervalMs = 25) {
  const wc = win.webContents;
  const trips = [];
  let stopped = false;
  const done = (async () => {
    const deadline = Date.now() + durationMs;
    const inflight = [];
    while (!stopped && Date.now() < deadline) {
      const t = Date.now();
      inflight.push(wc.executeJavaScript('1', true)
        .then(() => trips.push(Date.now() - t))
        .catch(() => { /* window torn down mid-probe */ }));
      await sleep(intervalMs);
    }
    await Promise.all(inflight);
    return trips;
  })();
  return { trips, done, stop: () => { stopped = true; } };
}

const pct = (xs, p) => {
  if (!xs.length) return NaN;
  const s = xs.slice().sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * s.length))];
};

const focusSel = (sel) => `(() => { const e = document.querySelector(${JSON.stringify(sel)});
  if (!e) return false; e.focus(); return document.activeElement === e; })()`;

const valueOf = (sel) => `(() => { const e = document.querySelector(${JSON.stringify(sel)});
  return e ? e.value : null; })()`;

// Empty the field the way a paste would, through the native setter + a bubbling `input`, so the widget's
// own state follows. Without this the "did every character survive?" test compares the typed phrase
// against whatever the field already held — find deliberately KEEPS its query across documents, and a
// Preferences font field is pre-populated, so several scenarios start non-empty.
const clearField = (sel) => `(() => {
  const e = document.querySelector(${JSON.stringify(sel)});
  if (!e) return false;
  const proto = e instanceof window.HTMLTextAreaElement ? window.HTMLTextAreaElement : window.HTMLInputElement;
  Object.getOwnPropertyDescriptor(proto.prototype, 'value').set.call(e, '');
  e.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`;

const TRACE_CLEAR = `(() => { const t = window.__vvinputtrace; if (!t) return false; t().clear(); return true; })()`;
const TRACE_REPORT = `(() => {
  const t = window.__vvinputtrace; if (!t) return null;
  const api = t();
  return { summary: api.summary(),
           lost: api.lost().map((e) => ({ key: e.key, field: e.field, queueMs: e.queueMs })),
           clobbers: api.clobbers().map((w) => ({ field: w.field, from: w.from, to: w.to, stack: w.stack })) };
})()`;

// Set the find query WITHOUT typing, so a warm-up does not pollute the keystroke records.
const setFindQuery = (q) => `(() => {
  const i = document.querySelector('.vv-find-input');
  if (!i) return false;
  Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(i, ${JSON.stringify(q)});
  i.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`;

const FIND_CHARS = `(() => (window.__vvfind ? window.__vvfind().chars : -1))()`;

/**
 * Drive one search and wait for the searchable buffer to stop growing.
 *
 * Without this, a streamed document is measured in the wrong regime: find's first search awaits full
 * materialization, and every keystroke typed during that await flows through an IDLE renderer — so the
 * benchmark reports a near-zero queue delay for the very document that is slowest. A user types into a
 * document that is already on screen; this reproduces that steady state.
 */
async function warmDocument(win) {
  const wc = win.webContents;
  let prev = -1;
  let stable = 0;
  for (let i = 0; i < 300; i++) {
    // re-issue the query each round: `chars` only updates when a search COMPLETES, so polling it after a
    // single search would read the same stale number forever and declare the document warm immediately
    await evalIn(wc, setFindQuery(i % 2 ? 'warmup' : 'warm-up'));
    await sleep(250);
    const chars = await evalIn(wc, FIND_CHARS);
    if (chars > 0 && chars === prev) { if (++stable >= 2) break; } else { stable = 0; }
    prev = chars;
  }
  await evalIn(wc, setFindQuery(''));
  await sleep(400);
  return prev;
}

async function openMenuItem(win, menuKey, itemText) {
  const wc = win.webContents;
  await evalIn(wc, `(() => { window.dispatchEvent(new KeyboardEvent('keydown',
    { key: ${JSON.stringify(menuKey)}, altKey: true, bubbles: true, cancelable: true })); return true; })()`);
  await until(wc, `Boolean(document.querySelector('.vv-menu-dropdown'))`, (v) => v === true, `Alt+${menuKey}`);
  await evalIn(wc, `(() => {
    const it = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item, .vv-menu-subdropdown .vv-menu-item'))
      .find((n) => n.textContent.includes(${JSON.stringify(itemText)}));
    if (it) it.click();
    return Boolean(it);
  })()`);
}

// ---- one measurement ---------------------------------------------------------------------------------

/**
 * Type `phrase` into `selector` at `cadenceMs` and report what the tracer saw.
 * `settleMs` is how long to wait after the last key before reading — long enough for the widget's own
 * debounce plus its work, so `settledMs` in the record is meaningful.
 */
async function measure(win, { name, selector, phrase, cadenceMs, settleMs = 2500, findStats = false }) {
  const wc = win.webContents;
  win.focus();
  wc.focus();
  const focused = await evalIn(wc, focusSel(selector));
  if (!focused) throw new Error(`${name}: could not focus ${selector}`);
  // clear AFTER focusing: the URI bar's on-focus repopulates and select-alls the field
  await evalIn(wc, clearField(selector));
  await sleep(300);
  await evalIn(wc, focusSel(selector));
  await evalIn(wc, TRACE_CLEAR);
  await sleep(120);

  // the probe runs for the typing window plus the settle, so a search that lands after the last key is
  // still counted against responsiveness — that is time the user is waiting too
  const typingMs = phrase.length * cadenceMs;
  const probe = probeResponsiveness(win, typingMs + settleMs);

  const t0 = Date.now();
  await typeString(win, phrase, cadenceMs);
  await sleep(settleMs);
  const wall = Date.now() - t0;
  probe.stop();
  const trips = await probe.done;

  const report = await evalIn(wc, TRACE_REPORT);
  const finalValue = await evalIn(wc, valueOf(selector));
  const find = findStats ? await evalIn(wc, `(() => (window.__vvfind ? window.__vvfind() : null))()`) : null;
  const summary = report && report.summary;

  return {
    name, cadenceMs, phrase, finalValue,
    // the RC1 gate, stated as a boolean: did every character survive?
    intact: finalValue === phrase,
    wallMs: wall,
    // perceived lag, measured from the main process (see probeResponsiveness)
    lagMs: { p50: pct(trips, 0.50), p95: pct(trips, 0.95), max: pct(trips, 1.0), n: trips.length },
    // total main-thread time spent in long tasks across the window — reported as milliseconds rather
    // than a percentage, because the window deliberately includes the post-typing settle and a ratio over
    // it would understate a block that lands entirely inside the typing burst
    blockTotalMs: summary && summary.blockTotalMs,
    summary,
    lost: report && report.lost,
    clobbers: report && report.clobbers,
    find: find && { count: find.count, ms: find.ms, cpuMs: find.cpuMs, cached: find.cached,
                    chars: find.chars, nodes: find.nodes }
  };
}

// ---- scenarios ---------------------------------------------------------------------------------------

async function openDoc(win, p, label) {
  const wc = win.webContents;
  await evalIn(wc, `window.__vvopen(${JSON.stringify(p)})`);
  await until(wc, `Boolean(document.querySelector('.vv-content'))`, (v) => v === true, `${label}: content pane`);
  await sleep(1200);   // let the initial render / stream settle before the field is exercised
}

async function openFind(win) {
  const wc = win.webContents;
  if (!(await evalIn(wc, `Boolean(document.querySelector('.vv-find-input'))`))) {
    await evalIn(wc, `window.__vvfindtoggle()`);
  }
  await until(wc, `Boolean(document.querySelector('.vv-find-input'))`, (v) => v === true, 'find bar');
}

async function closeFind(win) {
  const wc = win.webContents;
  if (await evalIn(wc, `Boolean(document.querySelector('.vv-find-input'))`)) {
    await evalIn(wc, `window.__vvfindtoggle()`);
    await sleep(200);
  }
}

async function scenarios(win, cadenceMs) {
  const wc = win.webContents;
  const out = [];
  const want = (n) => !ONLY || ONLY === n;

  if (want('find-small')) {
    await openDoc(win, SMALL_MD, 'small.md');
    await openFind(win);
    await warmDocument(win);
    out.push(await measure(win, { name: 'find-small', selector: '.vv-find-input', phrase: 'quick brown', cadenceMs, findStats: true }));
    await closeFind(win);
  }

  if (want('find-large')) {
    await openDoc(win, LARGE_MD, 'large.md');
    await openFind(win);
    await warmDocument(win);
    out.push(await measure(win, { name: 'find-large', selector: '.vv-find-input', phrase: 'quick brown', cadenceMs, settleMs: 6000, findStats: true }));
    await closeFind(win);
  }

  if (want('find-pdf')) {
    await openDoc(win, BIG_PDF, 'big.pdf');
    await openFind(win);
    await warmDocument(win);
    out.push(await measure(win, { name: 'find-pdf', selector: '.vv-find-input', phrase: 'quick brown', cadenceMs, settleMs: 6000, findStats: true }));
    await closeFind(win);
  }

  if (want('tree') || want('palette') || want('uri') || want('prefs')) {
    // open a file from THIS repository so the sidebar tree is the real git listing (thousands of files),
    // which is the case the tree filter and the file palette actually have to survive
    await openDoc(win, path.join(ROOT, 'README.md'), 'repo README');
    await until(wc, `(() => { const p = window.__vvdb().ui.projects; return p && p.length > 0; })()`,
      (v) => v === true, 'project tree loaded');
  }

  if (want('tree')) {
    await until(wc, `Boolean(document.querySelector('.vv-tree-filter'))`, (v) => v === true, 'tree filter');
    out.push(await measure(win, { name: 'tree', selector: '.vv-tree-filter', phrase: 'views', cadenceMs }));
    await evalIn(wc, `(() => { const i = document.querySelector('.vv-tree-filter');
      const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      s.call(i, ''); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
    await sleep(400);
  }

  if (want('uri')) {
    await until(wc, `Boolean(document.querySelector('.vv-uri-input'))`, (v) => v === true, 'uri input');
    out.push(await measure(win, { name: 'uri', selector: '.vv-uri-input', phrase: 'READ', cadenceMs }));
    await evalIn(wc, `(() => { const i = document.querySelector('.vv-uri-input'); i.blur(); return true; })()`);
    await sleep(300);
  }

  if (want('palette')) {
    await openMenuItem(win, 'h', 'Command Palette');
    await until(wc, `Boolean(document.querySelector('.vv-palette-input'))`, (v) => v === true, 'palette');
    out.push(await measure(win, { name: 'palette', selector: '.vv-palette-input', phrase: 'views', cadenceMs }));
    await evalIn(wc, `(() => { const i = document.querySelector('.vv-palette-input');
      i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
    await sleep(400);
  }

  if (want('prefs')) {
    await openMenuItem(win, 's', 'Preferences');
    await until(wc, `Boolean(document.querySelector('.vv-pref-input'))`, (v) => v === true, 'preferences');
    // the FIRST .vv-pref-input is the variable-font name field; typing a font name is the realistic case
    out.push(await measure(win, { name: 'prefs', selector: '.vv-pref-input', phrase: 'Noto Sans', cadenceMs }));
    await evalIn(wc, `(() => { const p = document.querySelector('.vv-modal');
      if (p) p.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      return true; })()`);
    await sleep(400);
  }

  return out;
}

// ---- reporting ---------------------------------------------------------------------------------------

const n2 = (x) => (typeof x === 'number' && isFinite(x) ? x.toFixed(1) : '—');

function printTable(rows) {
  const head = ['scenario', 'cad', 'intact', 'lost', 'clob', 'lag p50', 'lag p95', 'lag max',
                'task max', 'task ms', 'settle p95', 'find ms', 'find cpu', 'cached', 'chars'];
  const body = rows.map((r) => {
    const s = r.summary || {};
    const b = s.blockMs || {};
    const st = s.settledMs || {};
    const lg = r.lagMs || {};
    return [
      r.name, String(r.cadenceMs), r.intact ? 'yes' : 'NO',
      String(s.lostKeys != null ? s.lostKeys : '—'),
      String(s.clobbers != null ? s.clobbers : '—'),
      n2(lg.p50), n2(lg.p95), n2(lg.max), n2(b.max), n2(s.blockTotalMs),
      n2(st.p95),
      r.find ? n2(r.find.ms) : '—',
      r.find ? n2(r.find.cpuMs) : '—',
      r.find ? (r.find.cached ? 'yes' : 'no') : '—',
      r.find ? String(r.find.chars) : '—'
    ];
  });
  const widths = head.map((h, i) => Math.max(h.length, ...body.map((row) => row[i].length)));
  const line = (cells) => cells.map((c, i) => c.padEnd(widths[i])).join('  ');
  console.log('');
  console.log(line(head));
  console.log(widths.map((w) => '─'.repeat(w)).join('  '));
  body.forEach((row) => console.log(line(row)));
  console.log('');
}

// ---- main --------------------------------------------------------------------------------------------

app.whenReady().then(async () => {
  let code = 0;
  try {
    writeFixtures();
    const win = await waitForWindow();
    const wc = win.webContents;

    const traced = await evalIn(wc, `Boolean(window.__vvinputtrace)`);
    if (!traced) {
      throw new Error('window.__vvinputtrace is missing — this benchmark needs a DEV build ' +
                      '(npm run compile), not a :release one');
    }

    console.log(`[bench] hardware acceleration: ${GPU ? 'ON' : 'OFF'}`);
    console.log(`[bench] scratch: ${SCRATCH}`);
    console.log(`[bench] cadences: ${CADENCES.join(', ')} ms`);

    const rows = [];
    for (const cadence of CADENCES) {
      console.log(`\n[bench] ─── cadence ${cadence} ms ───`);
      const got = await scenarios(win, cadence);
      got.forEach((r) => {
        rows.push(r);
        console.log(`[bench] ${r.name}: intact=${r.intact} ` +
                    `lost=${r.summary ? r.summary.lostKeys : '?'} ` +
                    `clobbers=${r.summary ? r.summary.clobbers : '?'} ` +
                    `lag-max=${n2(r.lagMs && r.lagMs.max)}ms ` +
                    `task-total=${n2(r.summary && r.summary.blockTotalMs)}ms`);
        if (!r.intact) console.log(`         typed ${JSON.stringify(r.phrase)} → field holds ${JSON.stringify(r.finalValue)}`);
      });
    }

    printTable(rows);

    if (process.env.VV_BENCH_JSON) {
      fs.writeFileSync(process.env.VV_BENCH_JSON, JSON.stringify({ gpu: GPU, rows }, null, 2));
      console.log(`[bench] JSON written to ${process.env.VV_BENCH_JSON}`);
    }
  } catch (e) {
    console.error('[bench] FAILED:', e && e.stack || e);
    code = 1;
  } finally {
    try { fs.rmSync(SCRATCH, { recursive: true, force: true }); } catch (_) { /* best effort */ }
    app.exit(code);
  }
});
