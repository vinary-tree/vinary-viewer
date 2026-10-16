'use strict';

// Real-main-process regression for slide-show fullscreen (ADR-0045 §Presenting). The Electron smoke's stub main
// only RECORDS vv:set-fullscreen; this harness boots dist/main/main.js so the real vinary.main.fullscreen decides,
// the real BrowserWindow enters and leaves fullscreen, and the real window.cljs persists (or refuses to persist)
// bounds. It proves the three rules main owns:
//   1. F5 makes the window fullscreen and Escape leaves it (main entered it, so main leaves it);
//   2. a window the USER made fullscreen stays fullscreen when a show ends (main never leaves what it did not enter);
//   3. leaving the show's fullscreen from outside (F11 / the window manager) ends the show;
// and that window.edn is never written with fullscreen geometry (window/persistable-bounds).
// Isolated: XDG_CONFIG_HOME points into a scratch directory, so the user's real window.edn / recent.edn are untouched.

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
const { isLiveWindow } = require('./headless-window.js');
const { makeDeckPdf } = require('./fixtures/pdf-gen');

const ROOT = path.resolve(__dirname, '..');
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-fullscreen-e2e-')));
const CONFIG_HOME = path.join(SCRATCH, 'config');
const WINDOW_EDN = path.join(CONFIG_HOME, 'vinary-viewer', 'window.edn');
const DECK = path.join(SCRATCH, 'talk.pdf');
fs.mkdirSync(CONFIG_HOME, { recursive: true });
process.env.XDG_CONFIG_HOME = CONFIG_HOME;
process.env.VV_DAEMON_EVENTS_DESCRIPTOR = path.join(SCRATCH, 'daemon-events.json');
fs.writeFileSync(DECK, Buffer.from(makeDeckPdf({ frames: [1, 2, 1, 1] })));   // 5 pages, a Beamer deck
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

require(path.join(ROOT, 'dist', 'main', 'main.js'));

async function until(probe, predicate, label, timeoutMs = 12000) {
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

const presenting = (win) => evalIn(win, `Boolean((window.__vvdb().ui.presentation || {})['active?'])`);
const rendererFullscreen = (win) => evalIn(win, `window.__vvdb().ui['fullscreen?'] === true`);
// what is on disk, and WHEN it was written: the platforms tested here report the normal bounds correctly even
// while fullscreen, so comparing contents alone could not tell "saved the same bounds" from "did not save"
const savedBounds = () => (fs.existsSync(WINDOW_EDN)
  ? { mtimeMs: fs.statSync(WINDOW_EDN).mtimeMs, text: fs.readFileSync(WINDOW_EDN, 'utf8') }
  : null);

async function focusDocument(win) {
  await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
}

async function run() {
  const win = await until(
    async () => BrowserWindow.getAllWindows().find((w) => isLiveWindow(w) && !w.webContents.isLoading()),
    Boolean, 'a loaded visible renderer window', 20000);
  await until(() => evalIn(win, `typeof window.__vvopen`), (v) => v === 'function', 'the DEV bridge');
  await evalIn(win, `window.__vvopen(${JSON.stringify(DECK)})`);
  await until(() => evalIn(win, `Boolean(document.querySelector('.vv-pdf-doc.vv-pdf-slides #vv-pdf-page-1 canvas'))`),
    Boolean, 'the deck opens in the slides layout', 20000);
  assert.strictEqual(win.isFullScreen(), false, 'the window starts windowed');
  const normal = win.getNormalBounds();
  await sleep(600);                                       // let any launch-time debounced save land first
  const before = savedBounds();

  // 1. F5 → main makes THIS window fullscreen; Escape → main leaves it again
  await focusDocument(win);
  key(win, 'F5');
  await until(async () => win.isFullScreen() && (await presenting(win)) && (await rendererFullscreen(win)),
    Boolean, 'F5: a show in a real fullscreen window, reported to the renderer');
  await sleep(700);                                       // past window.cljs's 400 ms debounced save
  const during = savedBounds();
  assert.deepStrictEqual(during, before,
    `window.edn is not written at all while fullscreen (before=${JSON.stringify(before)} during=${JSON.stringify(during)})`);
  key(win, 'Escape');
  await until(async () => !win.isFullScreen() && !(await presenting(win)) && !(await rendererFullscreen(win)),
    Boolean, 'Escape: the show ends and main leaves the fullscreen it entered');
  const after = win.getNormalBounds();
  assert.deepStrictEqual([after.width, after.height], [normal.width, normal.height], 'the window returns to its size');
  console.log('[ok] F5 enters real fullscreen for the show; Escape leaves it; window.edn is not written while fullscreen');

  // 2. the USER's own fullscreen survives a show: main did not enter it, so main never leaves it
  win.setFullScreen(true);
  await until(async () => win.isFullScreen() && (await rendererFullscreen(win)), Boolean, "the user's own fullscreen");
  await focusDocument(win);
  key(win, 'F5');
  await until(() => presenting(win), Boolean, 'a show inside the user\'s fullscreen');
  key(win, 'Escape');
  await until(async () => !(await presenting(win)), Boolean, 'the show ends');
  await sleep(500);
  assert.strictEqual(win.isFullScreen(), true, 'ending the show did NOT leave the user\'s fullscreen');
  win.setFullScreen(false);
  await until(async () => !win.isFullScreen() && !(await rendererFullscreen(win)), Boolean, 'the user leaves fullscreen');
  console.log('[ok] a fullscreen the user made stays fullscreen when a show ends (main leaves only what it entered)');

  // 3. leaving the show's fullscreen from outside (F11 / the window manager) ends the show
  await focusDocument(win);
  key(win, 'F5');
  await until(async () => win.isFullScreen() && (await presenting(win)), Boolean, 'a second show in fullscreen');
  win.setFullScreen(false);                               // what the window manager / F11 does
  await until(async () => !(await presenting(win)), Boolean, 'leaving fullscreen from outside ends the show');
  await sleep(500);
  assert.strictEqual(win.isFullScreen(), false, 'nothing re-enters fullscreen afterwards');
  console.log('[ok] leaving the show\'s fullscreen from outside ends the show');
}

app.whenReady().then(() =>
  run()
    .then(() => {
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      app.exit(0);
    })
    .catch((error) => {
      console.error('\nfullscreen-e2e FAILED:\n', error && error.stack ? error.stack : error);
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      app.exit(1);
    }));
