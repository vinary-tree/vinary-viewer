'use strict';

// Real-main-process regression for a linked slide show (ADR-0048 §8): "Present in New Window" (Shift+S) opens the
// deck's audience in a SECOND real app window, the two follow each other's transitions from either side, and the
// speaker notes stay in the first. The Electron smoke's stub main only RECORDS the vv:presenter-* sends; this
// harness boots dist/main/main.js, so the real vinary.main.presenter claims a real window, the real
// vinary.main.slides-link orders the moves, and each window's real slide engine shows them. It proves:
//   1. Shift+S opens a second live, painting window that presents the deck, while the first shows the presenter
//      console with the slide's notes — notes the audience window does not show: centred notes on the left and a
//      scrollable right Preview Strip with the active step outlined; the audience window hides its chrome, and the
//      presenter window keeps its menus, tabs, URI bar and bottom bar, windowed; neither shows the speaker-notes hint,
//      which is the single-window show's (no linked show spends it, checked again after step 6);
//   2. a flip in either window moves the other (Space in the presenter; Space and Shift+Space in the audience), and a
//      click on the console — its notes scrolling or not — leaves the keyboard with the show;
//   3. a blank (b) in either window blanks the other, the console's strip says so (no blank screen covers the notes),
//      and Escape's first step — clearing the blank — clears it in both;
//   4. Escape in the audience window ends the show in BOTH windows, and main closes the audience window;
//   5. closing the audience window by hand ends the show and returns to its current slide beside the notes;
//   6. Shift+S while linked raises the audience window instead of opening another, and Escape in the presenter
//      window ends both and closes the audience window;
//   7. a show blanked before Shift+S stays blanked in both windows: the audience window's show starts blanked before
//      its engine's first report of the deck, and that report keeps the blank (R-blank, docs/theory/11 §7); and the
//      one-window show's fullscreen is left as the window becomes the presenter, which stays windowed;
//   8. the presenter's chrome is its own: Ctrl+L focuses its URI bar without ending the show, and Escape there leaves
//      the show and the audience's blank alone; Escape on the document then clears the blank, and again ends both;
//   9. the presenter leaves its deck and comes back (ADR-0048 §8.10, docs/theory/11 §3.5): a new tab in front makes it
//      AWAY — the show runs on in the audience window, whose keys still drive it, and nothing of the show acts on
//      the other deck in front (no console, no presenter view, b and Escape leave the show alone); a real click on
//      the deck's tab resumes the console at the show's slide and blank, sending nothing, and Space there unblanks
//      both, then flips both; a navigation in the deck's tab, to another deck, is away too — that deck does not
//      present — and Back resumes; closing the deck's tab from another tab ends both shows.
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
const PAPER = path.join(SCRATCH, 'paper.pdf');           // step 9's other deck
const ASIDE = path.join(SCRATCH, 'aside.pdf');           // step 9: a deck no tab holds, opened in the deck's own tab
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
fs.writeFileSync(PAPER, Buffer.from(makeDeckPdf({ frames: [1, 1, 1] })));
fs.writeFileSync(ASIDE, Buffer.from(makeDeckPdf({ frames: [1, 1] })));
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

// A real mouse click at the centre of the first element `selector` matches in `win`, once it is on screen.
async function clickAt(win, selector) {
  const at = await until(() => evalIn(win, `(() => { const n = document.querySelector(${JSON.stringify(selector)});
    if (!n || n.getClientRects().length === 0) return null; const r = n.getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }; })()`), Boolean,
  `${selector} on screen`);
  win.focus();
  win.webContents.sendInputEvent({ type: 'mouseDown', x: at.x, y: at.y, button: 'left', clickCount: 1 });
  win.webContents.sendInputEvent({ type: 'mouseUp', x: at.x, y: at.y, button: 'left', clickCount: 1 });
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
    // away from its deck (ADR-0048 §8.10), the position the link last exchanged with main, and the newest move seen
    away: link ? link['away?'] === true : null,
    knownPage: link && link.known ? link.known.page : null, knownBlank: link && link.known ? link.known.blank : null,
    seq: link ? link.seq : null,
    // the deck the slides engine in front reports (app-db's position mirror, [:ui :slides])
    slidesPath: slides.path == null ? null : slides.path,
    currentNotes: slides['current-notes?'] === true,
    pane: Boolean(document.querySelector('.vv-presenter-pane')),
    notes: [...document.querySelectorAll('.vv-presenter-note')].map((n) => n.textContent.trim()),
    notice: text('.vv-presenter-link-notice'),
    swap: Boolean(document.querySelector('.vv-presenter-swap')),
    badge: text('.vv-presenter-blank'),
    // the presenter console: a left strip of slide steps around the current preview, and centred notes on the right
    console: Boolean(document.querySelector('.vv-content-wrap.vv-presenter-console')),
    split: Boolean(document.querySelector('.vv-content-wrap.vv-presenter-view')),
    covered: (() => { const w = document.querySelector('.vv-content-wrap').getBoundingClientRect();
      const hit = document.elementFromPoint(w.left + w.width / 2, w.top + w.height / 2);
      return Boolean(hit && hit.closest('.vv-presenter-pane')); })(),
    previewShare: (() => { const n = document.querySelector('.vv-presenter-previews');
      const w = document.querySelector('.vv-content-wrap').getBoundingClientRect().width;
      return n ? n.getBoundingClientRect().width / w : null; })(),
    previewLeft: document.querySelector('.vv-presenter-previews')?.getBoundingClientRect().left ?? null,
    notesRight: document.querySelector('.vv-presenter-notes')?.getBoundingClientRect().right ?? null,
    notesShare: (() => { const n = document.querySelector('.vv-presenter-notes');
      const w = document.querySelector('.vv-content-wrap').getBoundingClientRect().width;
      return n ? n.getBoundingClientRect().width / w : null; })(),
    previews: [...document.querySelectorAll('.vv-presenter-preview')].map((n) => ({
      page: Number(n.dataset.page), current: n.getAttribute('aria-current') === 'step',
      peek: n.classList.contains('vv-presenter-preview-peek'),
      drawn: Boolean(n.querySelector('canvas')),
      top: n.getBoundingClientRect().top,
      center: n.getBoundingClientRect().top + n.getBoundingClientRect().height / 2,
    })),
    previewCanvasCount: document.querySelectorAll('.vv-presenter-preview canvas').length,
    previewCenter: (() => { const n = document.querySelector('.vv-presenter-previews');
      return n ? n.getBoundingClientRect().top + n.getBoundingClientRect().height / 2 : null; })(),
    previewTop: (() => { const n = document.querySelector('.vv-presenter-previews');
      return n ? n.getBoundingClientRect().top : null; })(),
    nextPreview: Boolean(document.querySelector('.vv-presenter-next')),
    joined: (() => { const a = document.querySelector('.vv-content-wrap.vv-presenter-view .vv-content');
      const b = document.querySelector('.vv-content-wrap.vv-presenter-view .vv-presenter-pane');
      if (!a || !b) return null;
      const x = a.getBoundingClientRect(), y = b.getBoundingClientRect();
      return { equal: Math.abs(x.width - y.width) < 2 && Math.abs(x.height - y.height) < 2,
        touching: Math.abs(x.right - y.left) < 2 || Math.abs(y.right - x.left) < 2
          || Math.abs(x.bottom - y.top) < 2 || Math.abs(y.bottom - x.top) < 2,
        centered: Math.abs((x.top + x.bottom) / 2 - (document.querySelector('.vv-content-wrap').getBoundingClientRect().top
          + document.querySelector('.vv-content-wrap').getBoundingClientRect().bottom) / 2) < 2 }; })(),
    notesCentered: (() => { const box = document.querySelector('.vv-presenter-notes');
      const body = document.querySelector('.vv-presenter-notes-body');
      if (!box || !body || body.scrollHeight > box.clientHeight) return null;
      const a = box.getBoundingClientRect(), b = body.getBoundingClientRect();
      return Math.abs((a.top + a.bottom - b.top - b.bottom) / 2) < 3; })(),
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
    // the speaker-notes hint: showing in this window (app-db's flag, or its chip), and spent on this installation —
    // the setting every window receives once one has shown it (ADR-0047), so a chip gone after its 6 s still counts
    hint: Boolean((ui.presentation || {})['hint?']) || Boolean(document.querySelector('.vv-slides-notes-hint')),
    hinted: (ui.settings || {})['slides-notes-hinted?'] === true,
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
  const ordinaryStrip = await until(() => evalIn(presenter, `(() => {
    const rail = document.querySelector('.vv-preview-rail'), content = document.querySelector('.vv-content-wrap');
    return rail && content && document.querySelector('.vv-preview-page[aria-current="step"]')
      ? { right: rail.getBoundingClientRect().left >= content.getBoundingClientRect().right - 2,
          count: document.querySelectorAll('.vv-preview-page').length } : null; })()`),
  (v) => v && v.right && v.count >= 2, 'the normal slide view has a right-side Preview Strip');
  assert.ok(ordinaryStrip.count >= 2);
  await clickAt(presenter, '.vv-preview-page[data-page="1"]');
  await until(() => state(presenter), (s) => s.page === 1, 'selecting a normal Preview Strip slide opens it');
  await clickAt(presenter, '.vv-preview-page[data-page="0"]');
  await until(() => state(presenter), (s) => s.page === 0, 'the first Preview Strip slide can be selected again');
  await clickAt(presenter, '.vv-preview-collapse');
  await until(() => evalIn(presenter, `Boolean(document.querySelector('.vv-preview-reopen'))
    && !document.querySelector('.vv-preview-rail')`), Boolean, 'collapse restores the normal scrollbar');
  await clickAt(presenter, '.vv-preview-reopen');
  await until(() => evalIn(presenter, `Boolean(document.querySelector('.vv-preview-rail'))`),
    Boolean, 'the Preview Strip reopens');
  await evalIn(presenter, `document.querySelector('.vv-content').focus()`);
  await press(presenter, 'n');
  await until(() => state(presenter), (s) => s.page === 1, 'n advances a slide');
  await press(presenter, 'p');
  await until(() => state(presenter), (s) => s.page === 0, 'p returns to the previous slide');
  await evalIn(presenter, `window.dispatchEvent(new MouseEvent('mousedown', { button: 4, bubbles: true, cancelable: true }))`);
  await until(() => state(presenter), (s) => s.page === 1, 'mouse Forward advances a slide');
  await evalIn(presenter, `window.dispatchEvent(new MouseEvent('mousedown', { button: 3, bubbles: true, cancelable: true }))`);
  await until(() => state(presenter), (s) => s.page === 0, 'mouse Back returns to the previous slide');

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
  const consoled = await until(() => state(presenter),
    (s) => s.console && s.previews.some((p) => p.current && p.drawn), 'the console draws the current slide');
  assert.strictEqual(consoled.split, false, 'the console, not the presenter view\'s split');
  assert.strictEqual(consoled.covered, true, 'the console covers the content area');
  assert.ok(consoled.previewShare > 0 && consoled.previewShare < 0.4, `the preview strip is narrow (${consoled.previewShare})`);
  assert.ok(consoled.notesShare > 0.55, `the notes fill the rest (${consoled.notesShare})`);
  assert.ok(consoled.previewLeft >= consoled.notesRight, 'the Preview Strip is to the right of the notes');
  assert.ok(consoled.previews.length >= 2 && consoled.previews.every((p, i) => p.page === i),
    'the strip begins with consecutive overlays that fit');
  assert.ok(Math.abs(consoled.previews.find((p) => p.current).top - consoled.previewTop) < 2,
    'the first current preview starts at the top');
  assert.ok(consoled.previewCanvasCount <= consoled.previews.length,
    'only visible slide previews hold canvases');
  assert.strictEqual(consoled.notesCentered, true, 'short notes are vertically centred');
  const browsed = await evalIn(presenter, `(() => { const n = document.querySelector('.vv-presenter-previews');
    n.scrollTop = n.scrollHeight; return { top: n.scrollTop, page: window.__vvdb().ui.slides.page }; })()`);
  assert.ok(browsed.top > 0, 'the Preview Strip scrolls independently');
  assert.strictEqual(browsed.page, p0, 'browsing the strip leaves the current slide unchanged');
  await evalIn(presenter, `document.querySelector('.vv-presenter-previews').scrollTop = 0`);
  const originalSize = presenter.getBounds();
  presenter.setSize(originalSize.width, Math.max(500, originalSize.height - 180));
  await until(() => state(presenter), (s) => s.console && s.previewCenter !== consoled.previewCenter
    && s.previews.some((p) => p.current && Math.abs(p.top - s.previewTop) < 2),
  'resizing keeps the first preview top aligned');
  presenter.setSize(originalSize.width, originalSize.height);
  await until(() => state(presenter), (s) => s.console
    && Math.abs(s.previewCenter - consoled.previewCenter) < 2
    && s.previews.some((p) => p.current && Math.abs(p.top - s.previewTop) < 2),
  'restoring the window keeps the first preview top aligned');
  console.log(`[info] ${MODE}: the console's slide strip takes ${consoled.previewShare.toFixed(3)} of the content width, `
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
  //    the speaker-notes hint is the single-window show's (ADR-0048 §6.1): never on the audience's screen — the
  //    projector's — though its engine's report of a page with a text note asks for it again (H-HINT); nor in the
  //    console, which shows the notes
  assert.strictEqual(seen.hint, false, 'the audience window shows no speaker-notes hint');
  assert.strictEqual(seen.hinted, false, '…and spent none: no window has shown it');
  assert.strictEqual((await state(presenter)).hint, false, 'nor does the presenter console');
  console.log(`[ok] ${MODE}: Shift+S opens a second live window presenting the deck; the notes stay in the first`);

  // 2. a flip in either window moves the other
  await press(presenter, 'Space');
  await samePage(presenter, audience, p0 + 1, 'Space in the presenter');
  await until(() => state(presenter), (s) => s.notes.includes(NOTE_2), 'the presenter view follows to the next note');
  await press(audience, 'Space');
  await samePage(presenter, audience, p0 + 2, 'Space in the audience');
  await press(audience, 'Space', ['shift']);
  await samePage(presenter, audience, p0 + 1, 'Shift+Space in the audience');
  await clickAt(presenter, '.vv-presenter-preview[data-page="0"]');
  await samePage(presenter, audience, p0, 'clicking an earlier preview jumps both windows');
  await clickAt(presenter, '.vv-presenter-preview[data-page="1"]');
  await samePage(presenter, audience, p0 + 1, 'clicking the next overlay preview jumps both windows');
  await evalIn(presenter, `document.querySelector('.vv-presenter-preview[data-page="0"]').focus(); true`);
  key(presenter, 'Enter');
  await samePage(presenter, audience, p0, 'Enter on a focused preview jumps both windows');
  await clickAt(presenter, '.vv-presenter-preview[data-page="1"]');
  await samePage(presenter, audience, p0 + 1, 'a click restores the next overlay after keyboard selection');
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
  assert.strictEqual(ended.presenterView, true, 'and automatic notes remain enabled outside the show');
  console.log(`[ok] ${MODE}: Escape in the audience window ends both shows and closes it`);

  // 5. closing the audience window by hand ends presentation on the last slide with its notes beside it
  const second = await openLinkedShow(presenter);
  await evalIn(second, `window.__vvslides('first')`);
  await samePage(presenter, second, 0, 'the second show returns to its first slide');
  const beforeClose = await until(() => state(second),
    (s) => s.enginePresenting && s.page != null, 'the second audience window is showing its starting slide');
  await press(second, 'Space');
  const afterClosePage = await until(() => state(second),
    (s) => s.page > beforeClose.page, 'the audience advances before its window closes');
  await samePage(presenter, second, afterClosePage.page, 'the presenter follows that advance');
  second.close();
  const left = await until(() => state(presenter),
    (s) => !s.presenting && s.role == null && !s.console && s.page === afterClosePage.page,
    'closing the audience window returns to the current slide and notes');
  assert.strictEqual(left.role, null, 'the link is forgotten');
  assert.strictEqual(left.presenterView, true, 'the main window shows its slide beside its notes');
  assert.strictEqual(left.console, false, 'the notes-only console closes');
  const joined = await until(() => state(presenter),
    (s) => s.currentNotes && s.split && s.joined && s.joined.equal && s.notes.includes(NOTE_2),
    'the current slide and its note become one joined page');
  assert.strictEqual(joined.joined.touching, true, 'the slide and notes are connected');
  assert.strictEqual(joined.joined.centered, true, 'the joined page is vertically centred');
  assert.strictEqual(left.nextPreview, false, 'the normal view has no next-slide preview');
  if (process.env.VV_PRESENTER_SHOTS) {
    const dir = process.env.VV_PRESENTER_SHOTS;
    fs.writeFileSync(path.join(dir, `joined-${COLD ? 'cold' : 'warm'}.png`),
      (await presenter.webContents.capturePage()).toPNG());
  }
  await evalIn(presenter, `window.__vvslides('next')`);
  await until(() => state(presenter), (s) => s.page === 2 && !s.currentNotes && !s.split && !s.pane,
    'a following slide without notes appears by itself');
  assert.strictEqual(left.blank, null, 'the linked show blank is cleared');
  console.log(`[ok] ${MODE}: closing the audience window returns to the current slide and notes`);

  // 6. Shift+S while linked raises the audience window; Escape in the presenter ends both
  const third = await openLinkedShow(presenter);
  await press(presenter, 'S', ['shift']);
  await sleep(1000);
  assert.strictEqual(liveWindows().length, 2, 'Shift+S while linked opens no third window');
  assert.ok(!third.isDestroyed() && (await state(third)).phase === 'live', 'and the show goes on');
  await press(presenter, 'Escape');
  await until(async () => third.isDestroyed() && !(await state(presenter)).presenting,
    Boolean, 'Escape in the presenter: the show ends in both and the audience window closes');
  //    three linked shows, three audience windows, flips and blanks: none spent the once-only speaker-notes hint, which
  //    is the single-window show's — step 7's F5 is the first to show it (H-HINT)
  assert.strictEqual((await state(presenter)).hinted, false, 'no linked show has shown the speaker-notes hint');
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

  // 9. away and back (ADR-0048 §8.10, theory/11 §3.5): the show keeps running while the presenter is elsewhere
  const sixth = await openLinkedShow(presenter);
  await press(presenter, 'Home');
  await samePage(presenter, sixth, 0, 'Home: both on the first slide');
  await clickAt(presenter, '.vv-tab-new');                        // a new tab in front: the deck's view unmounts
  await until(() => state(presenter), (s) => s.away === true && s.presenting && s.phase === 'live' && !s.console,
    'a new tab in front: the presenter is away, its show running, no console');
  await evalIn(presenter, `window.__vvopen(${JSON.stringify(PAPER)})`);
  const otherShown = await until(() => state(presenter), (s) => s.slidesPath === PAPER && s.page != null,
    'the other deck opens in the new tab', 20000);
  assert.strictEqual(otherShown.enginePresenting, false, 'the other deck does not present');
  assert.strictEqual(otherShown.pane, false, 'no console or presenter view over it');
  assert.strictEqual(otherShown.chromeless, false, 'and the presenter window keeps its chrome');
  await press(sixth, 'Space');
  await until(() => state(sixth), (s) => s.page === 1, 'Space in the audience while the presenter is away: page 1');
  await press(sixth, 'Space');
  await until(() => state(sixth), (s) => s.page === 2, 'Space again in the audience: page 2');
  await press(sixth, 'b');
  const recorded = await until(() => state(presenter), (s) => s.knownPage === 2 && s.knownBlank === 'black',
    'the away presenter records where the show is: the audience\'s page and blank');
  assert.strictEqual(recorded.slidesPath, PAPER, 'the other deck stays in front');
  assert.strictEqual(recorded.page, otherShown.page, 'it did not move');
  assert.strictEqual(recorded.blank, null, 'nor blank');
  await press(presenter, 'b');                                    // on the other deck: not the show's
  await press(presenter, 'Escape');
  await sleep(500);
  const [keptP, keptA] = [await state(presenter), await state(sixth)];
  assert.ok(keptP.presenting && keptP.away && keptP.phase === 'live' && !sixth.isDestroyed(),
    'b and Escape on the other deck leave the show running, the presenter away');
  assert.strictEqual(keptA.blank, 'black', 'the audience stays black');
  assert.strictEqual(keptP.blank, null, 'and the other deck is not blanked');
  const seqAway = keptA.seq;
  await clickAt(presenter, '.vv-tab[title$="talk.pdf"]');         // back to the deck's tab, by a real click
  await until(() => state(presenter),
    (s) => !s.away && s.console && s.enginePresenting && s.slidesPath === DECK && s.page === 2 && s.blank === 'black'
      && s.badge === 'Audience: black',
    'back on the deck\'s tab: the console resumes at the show\'s slide, with its blank');
  await sleep(500);
  const resumed = await state(sixth);
  assert.strictEqual(resumed.seq, seqAway, 'the resume sent nothing: the audience saw no move');
  assert.strictEqual(resumed.page, 2, 'it stays where it was');
  assert.strictEqual(resumed.blank, 'black', 'and black');
  //    the show resumed blanked, and in a blanked show a slide step only ends the blanking (slides-command-fx, ADR-0045
  //    §5): the audience sees the slide it left — the first Space unblanks both windows, the next one flips both
  await press(presenter, 'Space');
  await until(async () => [await state(presenter), await state(sixth)],
    ([p, a]) => p.blank == null && a.blank == null && p.badge == null && p.page === 2 && a.page === 2,
    'Space in the resumed presenter unblanks both windows, on the slide the audience left');
  await press(presenter, 'Space');
  await samePage(presenter, sixth, 3, 'Space again in the resumed presenter moves both');
  //    a navigation in the deck's own tab, to a deck no tab holds: __vvopen is :doc/open, which FOCUSES a tab that
  //    already shows its document — PAPER's would be a tab switch, and Back would then walk the paper's tab
  await evalIn(presenter, `window.__vvopen(${JSON.stringify(ASIDE)})`);
  const aside = await until(() => state(presenter), (s) => s.away === true && s.slidesPath === ASIDE && s.page != null,
    'the deck\'s tab navigated to another deck: away again', 20000);
  assert.strictEqual(aside.enginePresenting, false, 'another deck in the show\'s own tab does not present');
  await clickAt(presenter, '.vv-uribar .vv-nav-btn[title^="Back"]');
  await until(() => state(presenter), (s) => !s.away && s.console && s.enginePresenting && s.page === 3,
    'Back: the deck again, and the show resumes at its slide');
  await clickAt(presenter, '.vv-tab[title$="paper.pdf"]');        // the other tab in front: away
  await until(() => state(presenter), (s) => s.away === true, 'the other tab in front: away');
  await press(sixth, 'Space');
  await until(() => state(presenter), (s) => s.knownPage === 4, 'the audience advances while the presenter is away');
  sixth.close();
  await until(() => state(presenter),
    (s) => !s.presenting && s.slidesPath === PAPER,
    'closing the audience keeps the other tab in front and ends presentation');
  await clickAt(presenter, '.vv-tab[title$="talk.pdf"]');
  await until(() => state(presenter),
    (s) => !s.split && s.page === 4 && s.presenterView,
    'returning to a notes-free step shows the audience\'s last slide by itself');
  const seventh = await openLinkedShow(presenter);
  await clickAt(presenter, '.vv-tab[title$="paper.pdf"]');
  await until(() => state(presenter), (s) => s.away === true, 'the presenter leaves the deck again');
  await clickAt(presenter, '.vv-tab[title$="talk.pdf"] .vv-tab-close');
  await until(async () => seventh.isDestroyed() && !(await state(presenter)).presenting,
    Boolean, 'closing the deck\'s tab from another tab ends both shows, and the audience window closes');
  assert.strictEqual((await state(presenter)).role, null, 'the presenter window leaves the link');
  console.log(`[ok] ${MODE}: away from the deck the show runs on; back, the console resumes at the show's slide`);
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
