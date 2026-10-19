'use strict';

// Real-main-process regression for a linked slide show (ADR-0048 §8): "Present in New Window" (Shift+S) opens the
// deck's audience in a SECOND real app window, the two follow each other's transitions from either side, and the
// speaker notes stay in the first. The Electron smoke's stub main only RECORDS the vv:presenter-* sends; this
// harness boots dist/main/main.js, so the real vinary.main.presenter claims a real window, the real
// vinary.main.slides-link orders the moves, and each window's real slide engine shows them. It proves:
//   1. Shift+S opens a second live, painting window that presents the deck, while the first shows the presenter
//      console with the slide's notes — notes the audience window does not show: the notes over the whole content
//      area, a small next slide beside them, and no current slide; the audience window hides its chrome, and the
//      presenter window keeps its menus, tabs, URI bar and bottom bar, windowed;
//   2. a flip in either window moves the other (Space in the presenter; Space and Shift+Space in the audience), and a
//      click on the console — its notes scrolling or not — leaves the keyboard with the show;
//   3. a blank (b) in either window blanks the other, the console's strip says so (no blank screen covers the notes),
//      and Escape's first step — clearing the blank — clears it in both;
//   4. Escape in the audience window ends the show in BOTH windows, and main closes the audience window;
//   5. closing the audience window by hand leaves the presenter presenting, with the notice that says so, in the
//      console, and a blank there is still shown in the strip, not over the notes;
//   6. Shift+S while linked raises the audience window instead of opening another, and Escape in the presenter
//      window ends both and closes the audience window;
//   7. a show blanked before Shift+S stays blanked in both windows: the audience window's show starts blanked before
//      its engine's first report of the deck, and that report keeps the blank (R-blank, docs/theory/11 §7); and the
//      one-window show's fullscreen is left as the window becomes the presenter, which stays windowed;
//   8. the presenter's chrome is its own: Ctrl+L focuses its URI bar without ending the show, and Escape there leaves
//      the show and the audience's blank alone; Escape on the document then clears the blank, and again ends both.
// Run twice by `npm run test:presenter-e2e`: with the warm pool (the default, VV_POOL=1 — the audience window is
// the claimed pool window, joined at once) and with the runner's --env=VV_POOL=0 (every audience window is cold,
// joined only once it has loaded — vinary.main.core claim-window!). Xvfb gives Chromium ONE display (RandR monitors set with
// `xrandr --setmonitor` are not reported as displays), so the audience window opens beside the presenter ("same");
// placement on a second display and Swap screens are covered by test/vinary/main/displays_test.cljs and
// test/vinary/app/slides_linked_test.cljs.
// Hermetic: XDG_CONFIG_HOME and XDG_RUNTIME_DIR point into a scratch directory (test/fixtures/hermetic-env.js).
// VV_PRESENTER_SHOTS=<dir> also saves PNGs of the presenter console and the audience window after step 1, for a look.

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
const OZONE = process.env.VV_OZONE || (process.platform === 'linux' ? 'x11' : null);
if (OZONE) {
  process.env.ELECTRON_OZONE_PLATFORM_HINT = OZONE;
  process.env.GDK_BACKEND = OZONE;
  process.env.XDG_SESSION_TYPE = OZONE === 'wayland' ? 'wayland' : 'x11';
  process.env.VV_OZONE = OZONE;
  if (OZONE === 'x11') delete process.env.WAYLAND_DISPLAY;
}

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow } = require('electron');
const { isLiveWindow, assertPainting } = require('./headless-window.js');
const { makeDeckPdf } = require('./fixtures/pdf-gen');

const ROOT = path.resolve(__dirname, '..');
const COLD = process.env.VV_POOL === '0';
const MODE = COLD ? 'cold (VV_POOL=0)' : 'warm pool';
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-presenter-e2e-')));
const CONFIG_HOME = path.join(SCRATCH, 'config');
const DECK = path.join(SCRATCH, 'talk.pdf');
const NOTE_1 = 'Welcome everyone and introduce the topic';
const NOTE_2 = 'Point at the second build of this slide';
fs.mkdirSync(CONFIG_HOME, { recursive: true });
process.env.XDG_CONFIG_HOME = CONFIG_HOME;
// …and a private $XDG_RUNTIME_DIR with the password bridge off (test/fixtures/hermetic-env.js)
const RUNTIME = require('./fixtures/hermetic-env').hermeticRuntime(CONFIG_HOME);
process.env.VV_DAEMON_EVENTS_DESCRIPTOR = path.join(SCRATCH, 'daemon-events.json');
// 5 pages, a Beamer deck, with a \pdfpcnote-style text note on the first two pages
fs.writeFileSync(DECK, Buffer.from(makeDeckPdf({
  frames: [1, 2, 1, 1],
  textNotes: [{ page: 0, contents: NOTE_1 }, { page: 1, contents: NOTE_2 }],
})));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

require(path.join(ROOT, 'dist', 'main', 'main.js'));

async function until(probe, predicate, label, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      last = await probe();
      if (predicate(last)) return last;
    } catch (error) {
      lastError = error;
    }
    await sleep(75);
  }
  throw new Error(`timed out waiting for ${label}; last=${JSON.stringify(last)}` +
    (lastError ? `; error=${lastError.message}` : ''));
}

const evalIn = (win, source) => win.webContents.executeJavaScript(source, true);

function key(win, keyCode, modifiers = []) {
  win.focus();
  win.webContents.focus();
  win.webContents.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
  win.webContents.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
}

async function focusDocument(win) {
  await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
}

async function press(win, keyCode, modifiers = []) {
  await focusDocument(win);
  key(win, keyCode, modifiers);
}

// One window's side of the show, as the test reads it: app-db's presentation, position mirror and link
// ([:ui :slides-link]), and what the presenter view and the blank badge put on screen.
const state = (win) => evalIn(win, `(() => {
  const ui = window.__vvdb().ui || {};
  const slides = ui.slides || {};
  const link = ui['slides-link'] || null;
  const text = (sel) => { const n = document.querySelector(sel); return n ? n.textContent.trim() : null; };
  return {
    presenting: Boolean((ui.presentation || {})['active?']),
    enginePresenting: slides['presenting?'] === true,
    page: slides.page == null ? null : slides.page,
    blank: slides.blank == null ? null : slides.blank,
    presenterView: ui['presenter-view?'] === true,
    role: link && link.role, phase: link && link.phase, peer: link && link.peer, display: link && link.display,
    pane: Boolean(document.querySelector('.vv-presenter-pane')),
    notes: [...document.querySelectorAll('.vv-presenter-note')].map((n) => n.textContent.trim()),
    notice: text('.vv-presenter-link-notice'),
    swap: Boolean(document.querySelector('.vv-presenter-swap')),
    badge: text('.vv-presenter-blank'),
    // the presenter console (ADR-0048 §6.2): the wrap's mode; what a click at the content area's centre hits; the next
    // slide's and the notes' shares of its width; whether the next slide is drawn; and no blank screen or old badge
    console: Boolean(document.querySelector('.vv-content-wrap.vv-presenter-console')),
    split: Boolean(document.querySelector('.vv-content-wrap.vv-presenter-view')),
    covered: (() => { const w = document.querySelector('.vv-content-wrap').getBoundingClientRect();
      const hit = document.elementFromPoint(w.left + w.width / 2, w.top + w.height / 2);
      return Boolean(hit && hit.closest('.vv-presenter-pane')); })(),
    nextShare: (() => { const n = document.querySelector('.vv-presenter-next-box');
      const w = document.querySelector('.vv-content-wrap').getBoundingClientRect().width;
      return n ? n.getBoundingClientRect().width / w : null; })(),
    notesShare: (() => { const n = document.querySelector('.vv-presenter-notes');
      const w = document.querySelector('.vv-content-wrap').getBoundingClientRect().width;
      return n ? n.getBoundingClientRect().width / w : null; })(),
    nextDrawn: Boolean(document.querySelector('.vv-presenter-next-box canvas')),
    blankScreen: Boolean(document.querySelector('.vv-slides-blank')),
    oldBadge: Boolean(document.querySelector('.vv-slides-blank-badge')),
    focusOnDocument: (() => { const a = document.activeElement;
      return !a || a === document.body || Boolean(a.closest('.vv-content')); })(),
    noteOnScreen: document.body.innerText.includes(${JSON.stringify(NOTE_1)}),
    // the window hides its chrome for the show (.vv-root.vv-presenting), and whether its chrome is on screen
    chromeless: document.querySelector('.vv-root').classList.contains('vv-presenting'),
    chrome: ['.vv-menubar', '.vv-tabs', '.vv-uribar', '.vv-bottombar']
      .every((sel) => { const n = document.querySelector(sel); return Boolean(n) && n.getClientRects().length > 0; }),
    focusInUri: Boolean(document.activeElement && document.activeElement.closest('.vv-uribar')),
  };
})()`);

const liveWindows = () => BrowserWindow.getAllWindows().filter(isLiveWindow);

// Shift+S in `presenter`: the audience window it opens, once both windows are live members of one show. In the warm
// mode the audience window must be the pool window that was ready before the key; in the cold mode, a new window.
async function openLinkedShow(presenter) {
  const before = new Set(BrowserWindow.getAllWindows().map((w) => w.id));
  let pool = null;
  if (!COLD) {
    pool = await until(
      async () => BrowserWindow.getAllWindows().find((w) => w !== presenter && !w.isDestroyed() && !w.isVisible()
                                                        && !w.webContents.isLoading()),
      Boolean, 'a loaded warm-pool window', 20000);
  }
  await press(presenter, 'S', ['shift']);
  const audience = await until(
    async () => liveWindows().find((w) => w !== presenter && !w.webContents.isLoading()),
    Boolean, 'Shift+S: a second live window', 20000);
  if (COLD) assert.ok(!before.has(audience.id), 'cold: the audience window is a new window');
  else assert.strictEqual(audience.id, pool.id, 'warm: the audience window is the pool window that was ready');
  await until(() => evalIn(audience, `typeof window.__vvdb`), (v) => v === 'function', 'the audience window\'s DEV bridge');
  await until(() => state(audience), (s) => s.presenting && s.role === 'audience' && s.phase === 'live',
    'the audience window presents as a live member', 20000);
  await until(() => state(presenter), (s) => s.presenting && s.role === 'presenter' && s.phase === 'live'
                                             && s.peer === 'live' && s.presenterView,
    'the presenter window presents, with the presenter view, and its peer is live', 20000);
  return audience;
}

async function samePage(a, b, page, label) {
  await until(async () => [(await state(a)).page, (await state(b)).page], ([pa, pb]) => pa === page && pb === page,
    `${label}: both windows on page ${page}`);
}

async function run() {
  const presenter = await until(
    async () => BrowserWindow.getAllWindows().find((w) => isLiveWindow(w) && !w.webContents.isLoading()),
    Boolean, 'a loaded visible renderer window', 20000);
  await until(() => evalIn(presenter, `typeof window.__vvopen`), (v) => v === 'function', 'the DEV bridge');
  await evalIn(presenter, `window.__vvopen(${JSON.stringify(DECK)})`);
  await until(() => evalIn(presenter, `Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides #vv-pdf-page-1 canvas'))`),
    Boolean, 'the deck opens in the slides layout', 20000);
  const start = await until(() => state(presenter), (s) => s.page != null, 'the engine reports its page');

  // 1. Shift+S: a second window presents the deck; the notes stay in the first
  const audience = await openLinkedShow(presenter);
  await assertPainting(audience);
  await assertPainting(presenter);
  const p0 = start.page;
  await samePage(presenter, audience, p0, 'the show starts where the presenter was');
  const shown = await until(() => state(presenter), (s) => s.notes.includes(NOTE_1), 'the presenter view shows the slide\'s note');
  assert.strictEqual(shown.display, 'same', 'one display: the audience window opens beside the presenter');
  assert.strictEqual(shown.notice, 'One display: share the audience window in a call.', 'the presenter is told how');
  assert.strictEqual(shown.swap, false, 'Swap screens is not offered on one display');
  const consoled = await until(() => state(presenter), (s) => s.console && s.nextDrawn, 'the console draws the next slide');
  assert.strictEqual(consoled.split, false, 'the console, not the presenter view\'s split');
  assert.strictEqual(consoled.covered, true, 'the console covers the content area: no current slide is shown');
  assert.ok(consoled.nextShare > 0 && consoled.nextShare < 0.4, `the next slide is small (${consoled.nextShare})`);
  assert.ok(consoled.notesShare > 0.55, `the notes fill the rest (${consoled.notesShare})`);
  console.log(`[info] ${MODE}: the console's next slide takes ${consoled.nextShare.toFixed(3)} of the content width, `
    + `its notes ${consoled.notesShare.toFixed(3)}`);
  if (process.env.VV_PRESENTER_SHOTS) {
    const dir = process.env.VV_PRESENTER_SHOTS;
    fs.mkdirSync(dir, { recursive: true });
    const tag = COLD ? 'cold' : 'warm';
    fs.writeFileSync(path.join(dir, `console-${tag}.png`), (await presenter.webContents.capturePage()).toPNG());
    fs.writeFileSync(path.join(dir, `audience-${tag}.png`), (await audience.webContents.capturePage()).toPNG());
  }
  assert.strictEqual(shown.chromeless, false, 'the presenter window keeps its chrome');
  assert.strictEqual(shown.chrome, true, 'its menu bar, tabs, URI bar and bottom bar are on screen');
  assert.strictEqual(presenter.isFullScreen(), false, 'and it stays windowed');
  const seen = await state(audience);
  assert.strictEqual(seen.pane, false, 'the audience window has no presenter view');
  assert.strictEqual(seen.noteOnScreen, false, 'the audience window does not show the notes');
  assert.strictEqual(seen.presenterView, false, 'nor is its presenter view on');
  assert.strictEqual(seen.chromeless, true, 'the audience window hides its chrome');
  assert.strictEqual(seen.chrome, false, 'none of it is on screen');
  console.log(`[ok] ${MODE}: Shift+S opens a second live window presenting the deck; the notes stay in the first`);

  // 2. a flip in either window moves the other
  await press(presenter, 'Space');
  await samePage(presenter, audience, p0 + 1, 'Space in the presenter');
  await until(() => state(presenter), (s) => s.notes.includes(NOTE_2), 'the presenter view follows to the next note');
  await press(audience, 'Space');
  await samePage(presenter, audience, p0 + 2, 'Space in the audience');
  await press(audience, 'Space', ['shift']);
  await samePage(presenter, audience, p0 + 1, 'Shift+Space in the audience');
  //    a click on the console leaves the keyboard with the show — and so does one on its notes when they scroll (a
  //    long note): Chromium can focus a scroller that has no focusable children
  const clickNotes = async () => {
    const at = await evalIn(presenter, `(() => { const r = document.querySelector('.vv-presenter-notes').getBoundingClientRect();
      return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + Math.min(r.height / 2, 14)) }; })()`);
    presenter.focus();
    presenter.webContents.sendInputEvent({ type: 'mouseDown', x: at.x, y: at.y, button: 'left', clickCount: 1 });
    presenter.webContents.sendInputEvent({ type: 'mouseUp', x: at.x, y: at.y, button: 'left', clickCount: 1 });
    await sleep(300);
  };
  await clickNotes();
  assert.strictEqual((await state(presenter)).focusOnDocument, true, 'a click on the console leaves the focus on the document');
  key(presenter, 'Space');                                // no focusDocument: the keyboard is where the click left it
  await samePage(presenter, audience, p0 + 2, 'Space after a click on the console');
  await evalIn(presenter, `(() => { const n = document.querySelector('.vv-presenter-notes'); n.style.maxHeight = '24px';
    return n.scrollHeight > n.clientHeight; })()`).then((scrolls) => assert.ok(scrolls, 'the notes scroll'));
  await clickNotes();
  assert.strictEqual((await state(presenter)).focusOnDocument, true, 'a click on scrolling notes leaves the focus on the document');
  key(presenter, 'Space', ['shift']);
  await samePage(presenter, audience, p0 + 1, 'Shift+Space after a click on the scrolling notes');
  await evalIn(presenter, `(() => { document.querySelector('.vv-presenter-notes').style.maxHeight = ''; return true; })()`);
  console.log(`[ok] ${MODE}: a flip in either window moves the other; a click on the console keeps the keyboard`);

  // 3. a blank in either window is the other's; Escape's first step clears it in both
  await press(audience, 'b');
  await until(async () => [await state(presenter), await state(audience)],
    ([p, a]) => p.blank === 'black' && a.blank === 'black' && p.badge === 'Audience: black',
    'b in the audience: both black, and the presenter\'s badge says so');
  const blanked = await state(presenter);
  assert.ok(!blanked.blankScreen && !blanked.oldBadge && blanked.console, 'the console stays in view: no blank screen covers it');
  await press(presenter, 'b');
  await until(async () => [await state(presenter), await state(audience)],
    ([p, a]) => p.blank == null && a.blank == null && p.badge == null, 'b again in the presenter: both back');
  await press(presenter, 'w');
  await until(async () => (await state(audience)).blank, (b) => b === 'white', 'w in the presenter: the audience white');
  await press(presenter, 'Escape');
  await until(async () => [await state(presenter), await state(audience)],
    ([p, a]) => p.blank == null && a.blank == null && p.presenting && a.presenting,
    'Escape clears the blank in both, and both still present');
  console.log(`[ok] ${MODE}: a blank in either window blanks the other; Escape clears it in both`);

  // 4. Escape in the audience window ends the show in both; main closes the audience window
  await press(audience, 'Escape');
  await until(async () => audience.isDestroyed() && !(await state(presenter)).presenting,
    Boolean, 'Escape in the audience: the show ends in both and the audience window closes');
  const ended = await state(presenter);
  assert.strictEqual(ended.role, null, 'the presenter window leaves the link');
  assert.strictEqual(ended.presenterView, false, 'and its presenter view is closed with the show');
  console.log(`[ok] ${MODE}: Escape in the audience window ends both shows and closes it`);

  // 5. closing the audience window by hand: the presenter keeps presenting, and is told
  const second = await openLinkedShow(presenter);
  second.close();
  //    (the phase is app-db's and the notice the DOM's: the wait takes both, for the render that shows the ended link —
  //    waiting on the phase alone raced that render, and the M1 control run of 2026-10-19 read the live link's notice)
  const left = await until(() => state(presenter),
    (s) => s.phase === 'ended' && s.notice === 'The audience window closed. Shift+S opens it again.',
    'closing the audience window by hand ends the link, and the presenter is told why');
  assert.strictEqual(left.presenting, true, 'the presenter window keeps presenting');
  assert.strictEqual(left.notice, 'The audience window closed. Shift+S opens it again.', 'and says why');
  assert.strictEqual(left.console, true, 'in the console');
  await press(presenter, 'b');
  await until(() => state(presenter), (s) => s.blank === 'black' && s.badge === 'Audience: black' && !s.blankScreen,
    'b after the audience window closed: the strip shows the blank Shift+S would open it with, not a blank screen');
  await press(presenter, 'Escape');
  await until(() => state(presenter), (s) => s.blank == null && s.badge == null && s.presenting, 'Escape lifts that blank');
  await press(presenter, 'Escape');
  await until(async () => !(await state(presenter)).presenting, Boolean, 'Escape then ends the presenter\'s show');
  console.log(`[ok] ${MODE}: closing the audience window leaves the presenter presenting, told why`);

  // 6. Shift+S while linked raises the audience window; Escape in the presenter ends both
  const third = await openLinkedShow(presenter);
  await press(presenter, 'S', ['shift']);
  await sleep(1000);
  assert.strictEqual(liveWindows().length, 2, 'Shift+S while linked opens no third window');
  assert.ok(!third.isDestroyed() && (await state(third)).phase === 'live', 'and the show goes on');
  await press(presenter, 'Escape');
  await until(async () => third.isDestroyed() && !(await state(presenter)).presenting,
    Boolean, 'Escape in the presenter: the show ends in both and the audience window closes');
  console.log(`[ok] ${MODE}: Shift+S while linked raises the audience window; Escape in the presenter ends both`);

  // 7. a show blanked before Shift+S stays blanked in both windows (R-blank): the audience window joins at the
  //    session's position, black; its show starts blanked before its engine's first report of the deck, and the
  //    first presenting report must send nothing — before the fix it sent the page unblanked, and both windows
  //    unblanked
  await press(presenter, 'F5');
  await until(async () => [await state(presenter), presenter.isFullScreen()],
    ([s, full]) => s.presenting && s.enginePresenting && s.chromeless && full,
    'F5: a one-window show, chromeless and fullscreen');
  await press(presenter, 'b');
  await until(() => state(presenter), (s) => s.blank === 'black', 'b: the one-window show goes black');
  const fourth = await openLinkedShow(presenter);
  await until(async () => [await state(presenter), presenter.isFullScreen()],
    ([s, full]) => !s.chromeless && s.chrome && !full,
    'Shift+S: the window becomes the presenter, with its chrome back, and leaves the show\'s fullscreen');
  await until(() => state(fourth), (s) => s.enginePresenting, 'the audience window\'s engine presents');
  await sleep(500);
  const [blankP, blankA] = [await state(presenter), await state(fourth)];
  assert.strictEqual(blankA.blank, 'black', 'the audience window starts black and stays black once its engine presents');
  assert.strictEqual(blankP.blank, 'black', 'and the presenter stays black: no move unblanked it');
  assert.strictEqual(blankP.badge, 'Audience: black', 'the presenter\'s badge says so');
  await press(presenter, 'Escape');
  await until(async () => [await state(presenter), await state(fourth)],
    ([p, a]) => p.blank == null && a.blank == null && p.presenting && a.presenting,
    'Escape\'s first step clears the blank in both');
  await press(presenter, 'Escape');
  await until(async () => fourth.isDestroyed() && !(await state(presenter)).presenting,
    Boolean, 'Escape again ends both, and the audience window closes');
  console.log(`[ok] ${MODE}: a show blanked before Shift+S stays blanked in both windows; the presenter stays windowed`);

  // 8. the presenter's chrome is its own: focusing the URI bar keeps the show, and Escape there cancels the URI bar,
  //    not the show and not the audience's blank
  const fifth = await openLinkedShow(presenter);
  await press(presenter, 'b');
  await until(async () => [await state(presenter), await state(fifth)], ([p, a]) => p.blank === 'black' && a.blank === 'black',
    'b in the presenter: both black');
  await press(presenter, 'L', ['control']);
  await until(() => state(presenter), (s) => s.focusInUri, 'Ctrl+L focuses the presenter\'s URI bar');
  key(presenter, 'Escape');                               // in the URI bar: not on the document
  await sleep(500);
  assert.ok(!fifth.isDestroyed(), 'Ctrl+L and Escape in the URI bar leave the audience window open');
  const [uriP, uriA] = [await state(presenter), await state(fifth)];
  assert.ok(uriP.presenting && uriA.presenting && uriP.phase === 'live', 'Ctrl+L and Escape in the URI bar leave the show running');
  assert.strictEqual(uriA.blank, 'black', 'the audience stays black');
  assert.strictEqual(uriP.chromeless, false, 'and the presenter keeps its chrome');
  await press(presenter, 'Escape');
  await until(async () => [await state(presenter), await state(fifth)],
    ([p, a]) => p.blank == null && a.blank == null && p.presenting && a.presenting,
    'Escape on the document clears the blank in both');
  await press(presenter, 'Escape');
  await until(async () => fifth.isDestroyed() && !(await state(presenter)).presenting,
    Boolean, 'Escape again ends both, and the audience window closes');
  console.log(`[ok] ${MODE}: the presenter's URI bar and its Escape leave the show and the audience's blank alone`);
}

app.whenReady().then(() =>
  run()
    .then(() => {
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      RUNTIME.cleanup();
      app.exit(0);
    })
    .catch((error) => {
      console.error(`\npresenter-e2e (${MODE}) FAILED:\n`, error && error.stack ? error.stack : error);
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      RUNTIME.cleanup();
      app.exit(1);
    }));
