'use strict';

// Slide-flip latency in the real app — the instrument for docs/scientific/12 §Flip latency (ADR-0045).
//
// QUESTION. In the Slides layout, how long does a key take to put the next slide on screen? Does a flip to a slide
// the engine has already rendered (its neighbours are: the slides observer keeps ±1 slot) cost only the frame that
// shows it? What does a jump to a slide nobody rendered yet cost? And does the engine hold the number of live page
// canvases at three however far the presenter walks?
//
// METHOD. The real app (dist/main/main.js) opens a generated 50-slide Beamer deck, which is detected and laid out
// as slides. Keys are real key events, sent from this (main) process through webContents.sendInputEvent.
//   1. 49 → flips, one at a time, each from rest: the next starts 150 ms after the previous slide showed.
//   2. 10 jumps, Home and End alternating: each target is 49 slots away and has no canvas when the key is sent.
// A renderer probe times each key against the frame that shows its target — the target slot's top at the
// scroller's top (within 1 px) and the slot's page canvas present:
//   • t0    the key's dispatch in the renderer: event.timeStamp. For an injected key Electron stamps the moment the
//           renderer dispatches it — before any listener, the app's own included — so it is exactly the start of the
//           app's work. (It is NOT the time the key was sent: that is tSend, below.)
//   • pre   the start of the first animation frame in which the target shows (the rAF timestamp). The browser
//           renders that frame next: style, layout, paint, composite.
//   • post  the end of that frame's main-thread rendering work: a task posted from the rAF callback runs after the
//           frame's rendering steps, and checks the target again.
//   The two brackets disagree only when the target changes in between (a change made during the frame's own rAF
//   callbacks, or a task after its rendering): post then sees it one frame before pre does. Such samples are
//   counted and reported as frame-ambiguous; the error is at most one frame either way.
//   • tSend the key's send from this process (performance.timeOrigin + performance.now()), compared with the
//           renderer's clock through an NTP-style offset: the tightest of 25 round trips, its half-width the bound.
// Two attribution points split the latency: the probe's own capture listener on window (registered after the app's,
// so it runs once the app's capture listeners have; it checks that event.timeStamp marks the dispatch), and the
// moment the engine writes the scroller's scrollTop (an accessor installed on that one element, recording the first
// write after the probe is armed — just before the key is sent — and passing every write through).
// A key handled INSIDE the frame that renders it (Chromium dispatches input at the start of a frame) has
// key → frame slightly below zero: that frame's rAF timestamp is its start, a little before the dispatch.
// The live page canvases (`.vv-pdf-doc canvas.vv-pdf-canvas`) are counted in every frame, and after every move.
//
// Hermetic: an isolated $XDG_CONFIG_HOME, a private $XDG_RUNTIME_DIR and the password bridge off
// (test/fixtures/hermetic-env.js); no warm-pool window. GPU off, as the input benchmark: reproducible software
// compositing (VV_BENCH_GPU=1 keeps it on).
//
//   npm run compile
//   taskset -c 2-9 npm run bench:flip | tee flip.txt        (VV_BENCH_JSON=<path> also writes every sample)

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

const ROOT = path.resolve(__dirname, '..');
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-bench-flip-')));
const CONFIG_HOME = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-bench-flip-config-')));
process.env.XDG_CONFIG_HOME = CONFIG_HOME;     // never the developer's real ~/.config/vinary-viewer
// …and a private $XDG_RUNTIME_DIR with the password bridge off (test/fixtures/hermetic-env.js)
const RUNTIME = require('../test/fixtures/hermetic-env').hermeticRuntime(CONFIG_HOME);
process.env.VV_POOL = '0';                     // no hidden warm window: the recorded runs (docs/scientific/12) had none
const GPU = process.env.VV_BENCH_GPU === '1';
if (!GPU) app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
if (OZONE) app.commandLine.appendSwitch('ozone-platform', OZONE);

const { makeDeckPdf } = require('../test/fixtures/pdf-gen');
const { isLiveWindow, assertPainting } = require('../test/headless-window');
// the pre-registered run is 50 slides and 10 jumps (docs/scientific/data/12/flip-prediction.txt); smaller values only
// check the instrument
const SLIDES = Number(process.env.VV_FLIP_SLIDES) || 50;
const JUMPS = Number(process.env.VV_FLIP_JUMPS) || 10;
const REST_MS = 150;
const DECK = path.join(SCRATCH, 'flip-deck.pdf');
fs.writeFileSync(DECK, makeDeckPdf({ pages: SLIDES }));   // Beamer Creator, 16:9 paper, coloured pages with text

process.argv.splice(1, process.argv.length - 1, __filename);   // the app opens no document from our argv
require(path.join(ROOT, 'dist', 'main', 'main.js'));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => performance.timeOrigin + performance.now();   // epoch ms, sub-millisecond
async function waitFor(probe, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await probe();
    if (last) return last;
    await sleep(20);
  }
  throw new Error(`timed out: ${label} (last=${JSON.stringify(last)})`);
}
// nearest-rank quantiles: q(p) = the ⌈p·n⌉-th smallest sample
const stats = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const q = (p) => s[Math.min(s.length - 1, Math.max(0, Math.ceil(p * s.length) - 1))];
  return { n: s.length, min: s[0], median: q(0.5), p95: q(0.95), max: s[s.length - 1] };
};
const fmt = (st) => `n=${st.n}  median ${st.median.toFixed(1)}  p95 ${st.p95.toFixed(1)}  max ${st.max.toFixed(1)}  min ${st.min.toFixed(1)} ms`;

const PROBE = `(() => {
  const p = window.__vvFlip = { target: null, t0: null, tl: null, ts: null, pre: null, preFrame: null, post: null,
                                postFrame: null, frame: 0, maxCanvases: 0 };
  const scroller0 = document.querySelector('.vv-content');
  const top = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
  Object.defineProperty(scroller0, 'scrollTop', { configurable: true,
    get() { return top.get.call(this); },
    // armed is enough: a key handled synchronously writes scrollTop inside the app's own capture listener, BEFORE the
    // probe's listener has stamped the key (ts − t0 is then still ≥ 0: t0 is the dispatch, before every listener)
    set(v) { if (p.target !== null && p.ts === null) p.ts = performance.now(); top.set.call(this, v); } });
  const shown = () => {
    const slot = document.getElementById('vv-pdf-page-' + (p.target + 1));
    const scroller = document.querySelector('.vv-content');
    if (!slot || !scroller || !slot.querySelector('canvas.vv-pdf-canvas')) return false;
    const top = scroller.getBoundingClientRect().top + scroller.clientTop;
    return Math.abs(slot.getBoundingClientRect().top - top) <= 1;
  };
  // the scheduling probe: a task posted from the key's own dispatch, the way goog.async.nextTick (re-frame's
  // router) posts one — does it run before the next animation frame, or after it?
  const sched = new MessageChannel();
  sched.port1.onmessage = () => { if (p.tTask === null) { p.tTask = performance.now(); p.taskFrame = p.frame; } };
  window.addEventListener('keydown', (e) => {
    if (p.target !== null && p.t0 === null) {
      p.t0 = e.timeStamp; p.tl = performance.now(); p.keyFrame = p.frame; p.tTask = null; p.taskFrame = null;
      sched.port2.postMessage(0);
    }
  }, true);
  const after = new MessageChannel();
  after.port1.onmessage = () => {
    if (p.target !== null && p.t0 !== null && p.post === null && shown()) { p.post = performance.now(); p.postFrame = p.frame; }
  };
  const tick = (ts) => {
    p.frame += 1;
    const n = document.querySelectorAll('.vv-pdf-doc canvas.vv-pdf-canvas').length;
    if (n > p.maxCanvases) p.maxCanvases = n;
    if (p.target !== null && p.t0 !== null && p.pre === null && shown()) { p.pre = ts; p.preFrame = p.frame; }
    after.port2.postMessage(0);
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
  window.__vvFlipArm = (target) => {
    Object.assign(p, { target, t0: null, tl: null, ts: null, pre: null, preFrame: null, post: null, postFrame: null });
    const slot = document.getElementById('vv-pdf-page-' + (target + 1));
    return { rendered: Boolean(slot && slot.querySelector('canvas.vv-pdf-canvas')) };
  };
  window.__vvFlipRead = () => ({ t0: p.t0, tl: p.tl, ts: p.ts, pre: p.pre, preFrame: p.preFrame, post: p.post,
                                 postFrame: p.postFrame, keyFrame: p.keyFrame, tTask: p.tTask, taskFrame: p.taskFrame,
                                 origin: performance.timeOrigin });
  window.__vvFlipCanvases = () => ({ now: document.querySelectorAll('.vv-pdf-doc canvas.vv-pdf-canvas').length,
                                     max: p.maxCanvases });
  return true; })()`;

// NTP-style: offset = renderer clock − main clock, from the round trip with the smallest width (its half-width bounds
// the offset's error)
async function clockOffset(js) {
  let best = null;
  for (let i = 0; i < 25; i++) {
    const a = now();
    const r = await js('performance.timeOrigin + performance.now()');
    const b = now();
    if (!best || b - a < best.rtt) best = { rtt: b - a, offset: r - (a + b) / 2 };
  }
  return best;
}

async function run() {
  let failed = null;
  const report = { date: new Date().toISOString(), slides: SLIDES, gpu: GPU, restMs: REST_MS, flips: [], jumps: [] };
  try {
    const win = await waitFor(async () => {
      // the app's live window, never a warm-pool slot (getAllWindows() lists a pool slot first)
      return BrowserWindow.getAllWindows().find((w) => isLiveWindow(w) && !w.webContents.isLoading()) || null;
    }, 'the renderer window', 30000);
    // flip latency is measured in frames: refuse a window that is not painting (docs/scientific/10 §12)
    report.paintingFrames = await assertPainting(win);
    const js = (src) => win.webContents.executeJavaScript(src, true);
    await waitFor(() => js('typeof window.__vvopen === "function"'), 'renderer hooks');
    await js(`window.__vvopen(${JSON.stringify(DECK)}); true`);
    await waitFor(() => js(`(() => { const s = (window.__vvdb().ui || {}).slides || {};
      return s.pages === ${SLIDES} && s.layout === 'slides' && s.page === 0; })()`), 'the deck opens as slides');
    await waitFor(() => js(`Boolean(document.querySelector('#vv-pdf-page-2 canvas.vv-pdf-canvas'))`), 'slide 2 pre-rendered');
    await sleep(500);
    await js(PROBE);
    report.window = await js('[window.innerWidth, window.innerHeight, window.devicePixelRatio]');
    report.clock = await clockOffset(js);
    const key = (keyCode) => {
      win.focus();
      win.webContents.focus();
      const tSend = now();
      win.webContents.sendInputEvent({ type: 'keyDown', keyCode });
      win.webContents.sendInputEvent({ type: 'keyUp', keyCode });
      return tSend;
    };
    const move = async (keyCode, target, list) => {
      const { rendered } = await js(`window.__vvFlipArm(${target})`);
      const tSend = key(keyCode);
      const r = await waitFor(async () => {
        const s = await js('window.__vvFlipRead()');
        return s.pre !== null && s.post !== null ? s : null;
      }, `slide ${target + 1} shown after ${keyCode}`, 10000);
      const toMain = (t) => r.origin + t - report.clock.offset;      // a renderer stamp on this process's clock
      list.push({ key: keyCode, target, preRendered: rendered,
                  dispatchMs: toMain(r.t0) - tSend,                     // send → the renderer dispatches the key
                  toListenerMs: r.tl - r.t0,                            // dispatch → the probe's capture listener
                  toScrollMs: r.ts === null ? null : r.ts - r.t0,       // dispatch → the engine writes scrollTop
                  scrollToFrameMs: r.ts === null ? null : r.pre - r.ts, // that write → the frame that shows it
                  toTaskMs: r.tTask === null ? null : r.tTask - r.t0,   // dispatch → a task posted at the key runs
                  taskAfterFrame: r.taskFrame !== null && r.taskFrame > r.keyFrame,   // …after a frame began
                  keyToFrameMs: r.pre - r.t0,                           // dispatch → the frame that shows the target
                  keyToRenderedMs: r.post - r.t0,                       // dispatch → that frame's rendering work done
                  sendToRenderedMs: toMain(r.post) - tSend,             // end to end
                  frameAmbiguous: r.postFrame !== r.preFrame });
      await sleep(REST_MS);
      list[list.length - 1].canvasesAfter = (await js('window.__vvFlipCanvases()')).now;
    };
    for (let i = 1; i < SLIDES; i++) await move('Right', i, report.flips);
    for (let j = 0; j < JUMPS; j++) await move(j % 2 === 0 ? 'Home' : 'End', j % 2 === 0 ? 0 : SLIDES - 1, report.jumps);
    report.maxCanvases = (await js('window.__vvFlipCanvases()')).max;

    const show = (title, list) => {
      console.log(`${title} (a canvas already in the target's slot: ${list.filter((m) => m.preRendered).length} of ${list.length}; frame-ambiguous: ${list.filter((m) => m.frameAmbiguous).length})`);
      console.log(`  key → frame that shows it    ${fmt(stats(list.map((m) => m.keyToFrameMs)))}`);
      console.log(`  key → that frame rendered    ${fmt(stats(list.map((m) => m.keyToRenderedMs)))}`);
      console.log(`  send → rendered (end to end) ${fmt(stats(list.map((m) => m.sendToRenderedMs)))}`);
      console.log(`  send → key dispatched        ${fmt(stats(list.map((m) => m.dispatchMs)))}`);
      console.log(`  dispatch → probe listener    ${fmt(stats(list.map((m) => m.toListenerMs)))}`);
      const scrolled = list.filter((m) => m.toScrollMs !== null);
      if (scrolled.length) {
        console.log(`  dispatch → scrollTop written ${fmt(stats(scrolled.map((m) => m.toScrollMs)))}`);
        console.log(`  scrollTop written → frame    ${fmt(stats(scrolled.map((m) => m.scrollToFrameMs)))}`);
      }
      if (scrolled.length < list.length) console.log(`  (no scrollTop write observed for ${list.length - scrolled.length})`);
      const tasked = list.filter((m) => m.toTaskMs !== null);
      console.log(`  dispatch → a posted task runs ${fmt(stats(tasked.map((m) => m.toTaskMs)))}; after the next frame began: ${tasked.filter((m) => m.taskAfterFrame).length} of ${tasked.length}`);
    };
    const [w, h, dpr] = report.window;
    console.log(`# bench-slide-flip ${report.date}  ${SLIDES} slides  window ${w}×${h} @${dpr}x  GPU ${GPU ? 'on' : 'off'}  rest ${REST_MS} ms`);
    console.log(`# clock offset renderer−main ${report.clock.offset.toFixed(3)} ms ± ${(report.clock.rtt / 2).toFixed(3)} ms (tightest of 25 round trips)`);
    show('neighbour flips (→)', report.flips);
    show('jumps (Home / End)', report.jumps);
    const after = report.flips.concat(report.jumps).map((m) => m.canvasesAfter);
    console.log(`live page canvases: max over every frame ${report.maxCanvases}; after each move max ${Math.max(...after)} (seen: ${[...new Set(after)].sort((a, b) => a - b).join(', ')})`);
    if (process.env.VV_BENCH_JSON) fs.writeFileSync(process.env.VV_BENCH_JSON, JSON.stringify(report, null, 1));
  } catch (error) {
    failed = error;
    console.error(error && error.stack || error);
  } finally {
    for (const d of [SCRATCH, CONFIG_HOME]) { try { fs.rmSync(d, { recursive: true, force: true }); } catch (_e) { /* gone */ } }
    RUNTIME.cleanup();
    app.exit(failed ? 1 : 0);
  }
}

run();
