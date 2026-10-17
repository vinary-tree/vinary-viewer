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
// WHICH WINDOW: the app's LIVE window (test/headless-window isLiveWindow), never the first one listed — the real
// main keeps a hidden warm-pool window too, getAllWindows() lists it first, and a hidden window draws only about one frame a second.
// Every run first asserts the measured window is painting (assertPainting) and fails otherwise. Until 2026-10-17
// this benchmark measured that hidden window (docs/scientific/10 §12).
//
// Run (pinned, per the standing benchmarking rule):
//   npm run compile
//   taskset -c 2-9 npm run bench:input
// Set VV_HEADLESS_BACKEND=wayland to benchmark the private Weston path instead of the default Xvfb path.
//
// Environment:
//   VV_BENCH_GPU=1        keep hardware acceleration on (default: off, like test/find-e2e.js)
//   VV_BENCH_CADENCE=150  ms between keystrokes; overrides the default 150 / 40 / 25 ladder. A comma list
//                          (150,40) runs those rounds in order in ONE app — a later round then starts from the state
//                          the earlier one left (e.g. the font name the prefs round saved), as in the full ladder
//   VV_BENCH_JSON=<path>  additionally write the full report as JSON
//   VV_BENCH_ONLY=<name>  run one scenario only (find-small, find-large, find-pdf, tree, palette, uri, prefs) — or a comma
//                          list, run in the ladder's order in one app (a scenario can depend on what ran before it)
//   VV_BENCH_JS_FLAGS=<f> V8 flags for the app (Chromium's --js-flags), e.g. --trace-gc to log every collection
//   VV_BENCH_PERF=<dir>   sample the measured window's RENDERER process (every thread) with Linux perf over each measure
//                          window — from outside, so it moves nothing it measures — into <dir>/<name>-<cadence>.perf.data
//                          (CLOCK_MONOTONIC timestamps; the window's marks are printed on the same clock)
//   VV_BENCH_PROFILE=<dir> also record a CPU profile of the RENDERER over each scenario's typing + settle window
//                          (DevTools protocol Profiler, 1 ms sampling), written to <dir>/<name>-<cadence>.cpuprofile —
//                          what a long task was doing; read it with scripts/analyze-cpuprofile.cjs (--busy=N)
//   VV_BENCH_DIAG=1       print each scenario's IPC + phase timeline (vv:* sends to the window, vv:open/vv:close
//                          from it, the harness's phase marks, the worst probe round trip) and the warm-cache deltas
//                          over the window — the check that nothing but typing re-rendered the document
//   VV_BENCH_DIAG_SWALLOW=<channel>  (with DIAG) a causal control: DROP main's sends on <channel> to the measured window
//                          while a scenario is measured — does the row's defect go away without that message?
//   VV_BENCH_DIAG_STATE=1 (with DIAG) also probe the renderer's state — visibility, focus, animation frames in 300 ms,
//                          what the pane holds — after the open, at the warm-up and after the measure's focus. Each probe
//                          takes ~0.4 s, so this SHIFTS the measure prologue; it is off unless asked for

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
const OZONE = process.env.VV_OZONE || (process.platform === 'linux' ? 'x11' : null);
if (OZONE) {
  process.env.ELECTRON_OZONE_PLATFORM_HINT = OZONE;
  process.env.GDK_BACKEND = OZONE;
  process.env.XDG_SESSION_TYPE = OZONE === 'wayland' ? 'wayland' : 'x11';
  if (OZONE === 'x11') delete process.env.WAYLAND_DISPLAY;
}

const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow } = require('electron');
// isLiveWindow picks the app's window, assertPainting is every row's precondition (it fails the run rather than
// report latencies from a window no user could be typing into), COUNT_FRAMES is the frame counter both use
const { isLiveWindow, assertPainting, COUNT_FRAMES } = require('../test/headless-window');

const ROOT = path.resolve(__dirname, '..');
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-bench-input-')));

// Never read or write the developer's real ~/.config/vinary-viewer: the real main persists the keymap selection
// recent files and window geometry there, and a resident `vv --daemon` from the
// same checkout watches those files — an unisolated run switched the LIVE app's keymap. Same idiom as tree-e2e;
// default settings also make the measurement reproducible across machines.
// A SEPARATE temp dir, not inside SCRATCH: test documents there may be adopted as a watched Files-tree project.
const CONFIG_HOME = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-bench-input-config-')));
process.env.XDG_CONFIG_HOME = CONFIG_HOME;
// …and a private $XDG_RUNTIME_DIR with the password bridge off (test/fixtures/hermetic-env.js)
const RUNTIME = require('../test/fixtures/hermetic-env').hermeticRuntime(CONFIG_HOME);

const GPU = process.env.VV_BENCH_GPU === '1';
if (!GPU) app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
if (OZONE) app.commandLine.appendSwitch('ozone-platform', OZONE);
if (process.env.VV_BENCH_JS_FLAGS) app.commandLine.appendSwitch('js-flags', process.env.VV_BENCH_JS_FLAGS);

const ONLY = process.env.VV_BENCH_ONLY ? process.env.VV_BENCH_ONLY.split(',') : null;
const PROFILE_DIR = process.env.VV_BENCH_PROFILE || null;
const PERF_DIR = process.env.VV_BENCH_PERF || null;
// CLOCK_MONOTONIC in ms — the clock `perf record -k CLOCK_MONOTONIC` stamps its samples with
const monoMs = () => Number(process.hrtime.bigint()) / 1e6;
const DIAG = process.env.VV_BENCH_DIAG === '1';
const SWALLOW = (DIAG && process.env.VV_BENCH_DIAG_SWALLOW) || null;
// the renderer state probes (visibility, focus, frames, pane) each hold the renderer ~0.4 s, which shifts the measure
// prologue — so they are opt-in on top of DIAG: the timeline itself must not move what it times
const DIAG_STATE = DIAG && process.env.VV_BENCH_DIAG_STATE === '1';
let measuring = false;   // inside a measure window (its 'measure' mark → 'settled')

// ---- VV_BENCH_DIAG: the IPC + phase timeline ------------------------------------------------------------
//
// A row is only a measurement of TYPING if nothing else re-rendered the document inside its window. This
// timeline is how that is checked: every vv:* message main sends this window (metadata only — channel, path,
// stamp, seq, kind, text length; never contents), every vv:open / vv:close the renderer sends main, and the
// harness's own phase marks, all on main's clock. Printed per scenario, relative to the measure's first mark.
const diagLog = [];
function diagMark(label) { if (DIAG) diagLog.push({ t: Date.now(), mono: monoMs(), mark: label }); }
function installDiag(win) {
  if (!DIAG) return;
  const { ipcMain } = require('electron');
  const wc = win.webContents;
  const send = wc.send.bind(wc);
  wc.send = (channel, msg, ...rest) => {
    if (SWALLOW && measuring && channel === SWALLOW) {
      diagLog.push({ t: Date.now(), out: `${channel} (SWALLOWED)` });
      return undefined;
    }
    if (typeof channel === 'string' && channel.startsWith('vv:')) {
      const m = msg && typeof msg === 'object' ? msg : {};
      diagLog.push({ t: Date.now(), out: channel, path: m.path, stamp: m.stamp, seq: m.seq, kind: m.kind,
                     len: typeof m.text === 'string' ? m.text.length : undefined, refresh: m.refresh });
    }
    return send(channel, msg, ...rest);
  };
  ipcMain.on('vv:open', (_e, p) => diagLog.push({ t: Date.now(), in: 'vv:open', path: p }));
  ipcMain.on('vv:close', (_e, p) => diagLog.push({ t: Date.now(), in: 'vv:close', path: p }));
}
// What the renderer can paint right now: page visibility, focus, frames in 300 ms, and what the pane holds.
const STATE_PROBE = `(async () => {
  const frames = await (${COUNT_FRAMES})();
  const empty = document.querySelector('.vv-content .vv-empty');
  const pane = empty ? 'placeholder ' + JSON.stringify(empty.textContent)
             : document.querySelector('.vv-content .markdown-body.vv-streamed') ? 'streamed body'
             : document.querySelector('.vv-content .markdown-body') ? 'batch body' : 'other';
  return 'visibility=' + document.visibilityState + ' focus=' + document.hasFocus() + ' rAF/300ms=' + frames + ' pane=' + pane;
})()`;
// DIAG: long animation frames over the window. The Long Tasks API measures TASKS — the rendering that follows a task
// (style, layout, paint) is not in it — so a frame that stalls in rendering shows as a frame gap with no long task.
// The Long Animation Frames API reports the whole frame and splits it: script time (its scripts' durations) against
// rendering time (from renderStart to the frame's end). Frames of at least 100 ms are kept.
const LOAF_START = `(() => {
  window.__vvBenchLoaf = [];
  try {
    const o = new PerformanceObserver((list) => { for (const e of list.getEntries()) if (e.duration >= 100) window.__vvBenchLoaf.push(e); });
    o.observe({ type: 'long-animation-frame', buffered: false });
    window.__vvBenchLoafObs = o;
    return true;
  } catch (e) { return 'unsupported: ' + e.message; }
})()`;
const LOAF_REPORT = `(() => {
  if (window.__vvBenchLoafObs) window.__vvBenchLoafObs.disconnect();
  return (window.__vvBenchLoaf || []).map((e) => {
    const script = (e.scripts || []).reduce((a, s) => a + s.duration, 0);
    const top = (e.scripts || []).slice().sort((a, b) => b.duration - a.duration)[0];
    return { at: Math.round(e.startTime), ms: Math.round(e.duration), script: Math.round(script),
             render: e.renderStart ? Math.round(e.startTime + e.duration - e.renderStart) : 0,
             styleLayout: e.styleAndLayoutStart ? Math.round(e.startTime + e.duration - e.styleAndLayoutStart) : 0,
             blocking: Math.round(e.blockingDuration || 0),
             topScript: top ? (top.invoker || '') + ' ' + (top.sourceFunctionName || '') + ' ' + (top.sourceURL || '').split('/').pop() : '' };
  });
})()`;

async function diagState(win, label) {
  if (DIAG_STATE) diagLog.push({ t: Date.now(), mark: `${label}: ${await evalIn(win.webContents, STATE_PROBE)}` });
}
function diagFlush(label) {
  if (!DIAG || diagLog.length === 0) return;
  const t0 = (diagLog.find((e) => e.mark === 'measure') || diagLog[0]).t;
  console.log(`[diag] ── ${label} (ms relative to the measure mark) ──`);
  for (const e of diagLog) {
    const at = String(e.t - t0).padStart(7);
    if (e.mark) console.log(`[diag] ${at}  mark ${e.mark}${e.mono != null ? ` [mono ${e.mono.toFixed(1)}]` : ''}`);
    else if (e.in) console.log(`[diag] ${at}  ← ${e.in} ${e.path}`);
    else {
      const fields = ['stamp', 'seq', 'kind', 'len', 'refresh'].filter((k) => e[k] != null).map((k) => `${k}=${e[k]}`);
      console.log(`[diag] ${at}  → ${e.out}${e.path ? ' ' + e.path : ''}${fields.length ? ' ' + fields.join(' ') : ''}`);
    }
  }
  diagLog.length = 0;
}

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
  ? process.env.VV_BENCH_CADENCE.split(',').map(Number)
  : [150, 40, 25];

// ---- fixtures ----------------------------------------------------------------------------------------

const SMALL_MD = path.join(SCRATCH, 'small.md');
const LARGE_MD = path.join(SCRATCH, 'large.md');
const BIG_PDF = path.join(SCRATCH, 'big.pdf');

// A multi-page PDF with real extractable text (the same construction test/find-e2e.js uses, at a size
// where the text-layer materialization cost is visible).
// The benchmark's PDF fixture comes from the shared builder with its own first-line wording, so the bytes — and
// therefore the measurements recorded in docs/scientific/10 — stay identical to the historical inline copy.
const { makeTextPdf: makeSharedTextPdf } = require('../test/fixtures/pdf-gen');
const makeTextPdf = (pageCount) => makeSharedTextPdf(pageCount, 'input-latency benchmark');

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

// The LIVE window — never the first one listed. The real main keeps a hidden warm-pool window beside the live one
// (VV_POOL, default 1), and getAllWindows() lists that newest slot FIRST: a hidden window is not drawn, so
// requestAnimationFrame runs only about once a second there, every Reagent render waits on it, and a large document's
// first paint — gated on two frames — lands in whatever is measured next. Every number this benchmark recorded
// before 2026-10-17 came from that hidden window (docs/scientific/10 §12).
async function waitForWindow() {
  for (let i = 0; i < 300; i++) {
    const w = BrowserWindow.getAllWindows().find((x) => isLiveWindow(x) && !x.webContents.isLoading());
    if (w) return w;
    await sleep(100);
  }
  throw new Error('no live renderer window appeared');
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
  const detail = [];   // VV_BENCH_DIAG: each trip's start on main's clock, to place the worst one on the timeline
  let stopped = false;
  const done = (async () => {
    const deadline = Date.now() + durationMs;
    const inflight = [];
    while (!stopped && Date.now() < deadline) {
      const t = Date.now();
      const mono = monoMs();
      inflight.push(wc.executeJavaScript('1', true)
        .then(() => { trips.push(Date.now() - t); if (DIAG) detail.push({ t, mono, ms: Date.now() - t }); })
        .catch(() => { /* window torn down mid-probe */ }));
      await sleep(intervalMs);
    }
    await Promise.all(inflight);
    return trips;
  })();
  return { trips, detail, done, stop: () => { stopped = true; } };
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

// Is the content pane showing `docPath`'s BODY? 'ready', or what it shows instead. The pane's data-doc-key names a
// document as soon as it is routed — before its body mounts — so the key alone accepts a "Loading…"/"Rendering…"
// placeholder (.vv-empty) or a stream that is still committing blocks.
const paneReady = (docPath) => `(() => {
  const c = document.querySelector('.vv-content');
  if (!c) return 'no content pane';
  if (c.dataset.docKey !== ${JSON.stringify(docPath)}) return 'the pane shows ' + c.dataset.docKey;
  const empty = c.querySelector('.vv-empty');
  if (empty) return 'a placeholder ' + JSON.stringify(empty.textContent);
  if (c.querySelector('.vv-stream-progress:not(.vv-stream-progress-done)')) return 'a stream still committing';
  return 'ready';
})()`;

/**
 * Drive one search and wait for the searchable buffer to stop growing.
 *
 * Without this, a streamed document is measured in the wrong regime: find's first search awaits full
 * materialization, and every keystroke typed during that await flows through an IDLE renderer — so the
 * benchmark reports a near-zero queue delay for the very document that is slowest. A user types into a
 * document that is already on screen; this reproduces that steady state.
 */
async function warmDocument(win, docPath) {
  const wc = win.webContents;
  // First, the RIGHT document's body: until it is on screen, find searches whatever the pane holds — and a
  // placeholder's few characters are just as "stable" as a whole document. Declaring that warm let the typing start
  // before the 1.1 MB fixture had rendered, so the fast-cadence rows measured its first render, not typing
  // (docs/scientific/10 §12).
  await diagState(win, 'state at warm-up');
  if (docPath) {
    await until(wc, paneReady(docPath), (v) => v === 'ready', `the pane shows ${path.basename(docPath)}'s body`);
  }
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
  diagMark(`warm (chars ${prev})`);
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
  const warmBefore = DIAG ? await evalIn(wc, `(() => (window.__vvwarmcache ? window.__vvwarmcache() : null))()`) : null;
  let perf = null;
  if (PERF_DIR) {
    fs.mkdirSync(PERF_DIR, { recursive: true });
    const file = path.join(PERF_DIR, `${name}-${cadenceMs}.perf.data`);
    perf = require('child_process').spawn('perf', ['record', '-q', '-k', 'CLOCK_MONOTONIC', '-F', '999', '-g',
      '-p', String(wc.getOSProcessId()), '-o', file], { stdio: 'ignore' });
    await sleep(500);   // attached before the window opens
    console.log(`[bench] ${name} @ ${cadenceMs} ms: perf → ${file} (renderer pid ${wc.getOSProcessId()})`);
  }
  diagMark('measure');
  measuring = true;
  win.focus();
  wc.focus();
  await diagState(win, 'state after win.focus()');
  const focused = await evalIn(wc, focusSel(selector));
  if (!focused) throw new Error(`${name}: could not focus ${selector}`);
  // clear AFTER focusing: the URI bar's on-focus repopulates and select-alls the field
  await evalIn(wc, clearField(selector));
  diagMark('cleared');
  await sleep(300);
  await evalIn(wc, focusSel(selector));
  await evalIn(wc, TRACE_CLEAR);
  await sleep(120);

  // the probe runs for the typing window plus the settle, so a search that lands after the last key is
  // still counted against responsiveness — that is time the user is waiting too
  const typingMs = phrase.length * cadenceMs;
  const profiling = Boolean(PROFILE_DIR);
  if (profiling) {
    wc.debugger.attach('1.3');
    await wc.debugger.sendCommand('Profiler.enable');
    await wc.debugger.sendCommand('Profiler.setSamplingInterval', { interval: 1000 });
    await wc.debugger.sendCommand('Profiler.start');
    diagMark('profiler-started');
  }
  const probe = probeResponsiveness(win, typingMs + settleMs);
  // DIAG: this MAIN process's own event-loop delay over the window — the bench runs in main, so a stall here is main
  // being blocked (a round trip and the renderer's frame signals both pass through it), not the renderer
  const eld = DIAG ? require('perf_hooks').monitorEventLoopDelay({ resolution: 10 }) : null;
  if (eld) eld.enable();
  if (DIAG) {
    const ok = await evalIn(wc, LOAF_START);
    if (ok !== true) diagLog.push({ t: Date.now(), mark: `long-animation-frame observer ${ok}` });
  }

  const t0 = Date.now();
  diagMark('typing');
  await typeString(win, phrase, cadenceMs);
  diagMark('typed');
  await sleep(settleMs);
  const wall = Date.now() - t0;
  probe.stop();
  const trips = await probe.done;
  diagMark('settled');
  measuring = false;
  if (DIAG) {
    const loaf = await evalIn(wc, LOAF_REPORT);
    diagLog.push({ t: Date.now(), mark: `long animation frames ≥ 100 ms over the window: ${loaf.length ? '' : 'none'}` +
      loaf.map((f) => `\n[diag]           frame of ${f.ms} ms — script ${f.script} ms, rendering ${f.render} ms ` +
                       `(style+layout onward ${f.styleLayout} ms), blocking ${f.blocking} ms` +
                       `${f.topScript.trim() ? `, top script: ${f.topScript.trim()}` : ''}`).join('') });
  }
  if (eld) {
    eld.disable();
    diagLog.push({ t: Date.now(), mark: `main event-loop delay over the window: max ${(eld.max / 1e6).toFixed(1)} ms, ` +
                                        `p99 ${(eld.percentile(99) / 1e6).toFixed(1)} ms` });
  }
  if (DIAG && probe.detail.length) {
    const worst = probe.detail.reduce((a, b) => (b.ms > a.ms ? b : a));
    diagLog.push({ t: worst.t, mono: worst.mono, mark: `worst round trip starts (${worst.ms} ms)` });
    diagLog.sort((a, b) => a.t - b.t);
  }
  if (perf) {
    await new Promise((resolve) => { perf.once('exit', resolve); perf.kill('SIGINT'); });
  }
  if (profiling) {
    const { profile } = await wc.debugger.sendCommand('Profiler.stop');
    wc.debugger.detach();
    fs.mkdirSync(PROFILE_DIR, { recursive: true });
    const file = path.join(PROFILE_DIR, `${name}-${cadenceMs}.cpuprofile`);
    fs.writeFileSync(file, JSON.stringify(profile));
    console.log(`[bench] ${name} @ ${cadenceMs} ms: renderer CPU profile ${file}`);
  }

  const report = await evalIn(wc, TRACE_REPORT);
  const finalValue = await evalIn(wc, valueOf(selector));
  const find = findStats ? await evalIn(wc, `(() => (window.__vvfind ? window.__vvfind() : null))()`) : null;
  const summary = report && report.summary;
  if (DIAG) {
    const warmAfter = await evalIn(wc, `(() => (window.__vvwarmcache ? window.__vvwarmcache() : null))()`);
    const delta = (k) => (warmBefore && warmAfter ? warmAfter[k] - warmBefore[k] : '?');
    console.log(`[diag] ${name} @ ${cadenceMs} ms warm-cache over the window: misses +${delta('misses')} ` +
                `hits +${delta('hits')} evictions +${delta('evictions')} invalidations +${delta('invalidations')}`);
    diagFlush(`${name} @ ${cadenceMs} ms`);
  }

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
    // `dirty`: the content DOM changed after the buffer was built — what separates "something keeps defeating the
    // reuse" from "there was no buffer yet" when `cached` is false
    find: find && { count: find.count, ms: find.ms, cpuMs: find.cpuMs, cached: find.cached, dirty: find.dirty,
                    chars: find.chars, nodes: find.nodes }
  };
}

// ---- scenarios ---------------------------------------------------------------------------------------

async function openDoc(win, p, label) {
  const wc = win.webContents;
  diagMark(`open ${path.basename(p)}`);
  await evalIn(wc, `window.__vvopen(${JSON.stringify(p)})`);
  await until(wc, `Boolean(document.querySelector('.vv-content'))`, (v) => v === true, `${label}: content pane`);
  await sleep(1200);   // let the initial render / stream settle before the field is exercised
  await diagState(win, 'state after open + 1.2 s');
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
  const want = (n) => !ONLY || ONLY.includes(n);

  if (want('find-small')) {
    await openDoc(win, SMALL_MD, 'small.md');
    await openFind(win);
    await warmDocument(win, SMALL_MD);
    out.push(await measure(win, { name: 'find-small', selector: '.vv-find-input', phrase: 'quick brown', cadenceMs, findStats: true }));
    await closeFind(win);
  }

  if (want('find-large')) {
    await openDoc(win, LARGE_MD, 'large.md');
    await openFind(win);
    await warmDocument(win, LARGE_MD);
    out.push(await measure(win, { name: 'find-large', selector: '.vv-find-input', phrase: 'quick brown', cadenceMs, settleMs: 6000, findStats: true }));
    await closeFind(win);
  }

  if (want('find-pdf')) {
    await openDoc(win, BIG_PDF, 'big.pdf');
    await openFind(win);
    await warmDocument(win, BIG_PDF);
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
  // `gap max` — the longest interval between animation frames. Meaningful only in a window that paints, which
  // assertPainting now guarantees; in the hidden window every row read ~1017 ms (docs/scientific/10 §12).
  const head = ['scenario', 'cad', 'intact', 'lost', 'clob', 'lag p50', 'lag p95', 'lag max',
                'task max', 'task ms', 'gap max', 'settle p95', 'find ms', 'find cpu', 'cached', 'chars'];
  const body = rows.map((r) => {
    const s = r.summary || {};
    const b = s.blockMs || {};
    const g = s.frameGapMs || {};
    const st = s.settledMs || {};
    const lg = r.lagMs || {};
    return [
      r.name, String(r.cadenceMs), r.intact ? 'yes' : 'NO',
      String(s.lostKeys != null ? s.lostKeys : '—'),
      String(s.clobbers != null ? s.clobbers : '—'),
      n2(lg.p50), n2(lg.p95), n2(lg.max), n2(b.max), n2(s.blockTotalMs), n2(g.max),
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
    installDiag(win);
    console.log(`[bench] measuring window #${win.id}: visible, ${await assertPainting(win)} animation frames in 300 ms ` +
                `(main pid ${process.pid}, its renderer pid ${wc.getOSProcessId()})`);

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
    try { fs.rmSync(SCRATCH, { recursive: true, force: true }); fs.rmSync(CONFIG_HOME, { recursive: true, force: true }); RUNTIME.cleanup(); } catch (_) { /* best effort */ }
    app.exit(code);
  }
});
