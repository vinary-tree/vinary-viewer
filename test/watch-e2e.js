'use strict';

// Real-main-process regression for multi-window live refresh. The lightweight Electron smoke owns a mocked
// vv:open handler, so it cannot prove chokidar ownership or delivery. This harness boots dist/main/main.js,
// opens one path in two real renderer windows, changes it on disk, closes the most-recent owner, and changes it
// again. Both fan-out and destroyed-window cleanup are load-bearing: the old global path -> WebContents map sent
// only to the last opener, then retained that destroyed destination forever.

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
process.env.ELECTRON_OZONE_PLATFORM_HINT = 'x11';
process.env.GDK_BACKEND = 'x11';
process.env.XDG_SESSION_TYPE = 'x11';
process.env.VV_OZONE = 'x11';
delete process.env.WAYLAND_DISPLAY;

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow } = require('electron');

const ROOT = path.resolve(__dirname, '..');
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-watch-e2e-')));
const DOC = path.join(SCRATCH, 'shared.md');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

fs.writeFileSync(DOC, '# Shared watch\n\nversion-one\n');
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

function visibleWindows() {
  return BrowserWindow.getAllWindows().filter((win) => !win.isDestroyed() && win.isVisible());
}

async function readyVisibleWindows(count) {
  return until(
    async () => visibleWindows().filter((win) => !win.webContents.isLoading()),
    (wins) => wins.length === count,
    `${count} loaded visible renderer window(s)`,
    20000
  );
}

function evalIn(win, source) {
  return win.webContents.executeJavaScript(source, true);
}

function openIn(win, filePath) {
  return evalIn(win, `window.__vvopen(${JSON.stringify(filePath)})`);
}

function renderedText(win) {
  return evalIn(win, `document.querySelector('.vv-content .markdown-body')?.textContent || ''`);
}

async function waitForText(win, token, label) {
  return until(() => renderedText(win), (text) => text.includes(token), label);
}

async function run() {
  const [first] = await readyVisibleWindows(1);
  await until(() => evalIn(first, `typeof window.__vvopen`), (v) => v === 'function', 'first window DEV bridge');
  await openIn(first, DOC);
  await waitForText(first, 'version-one', 'first window initial content');

  // Exercise the same authoritative path as a second `vv <file>` invocation. A unique launch id prevents the
  // main process's duplicate-signal coalescer from treating this as the initial window.
  app.emit('second-instance', {},
    [process.execPath, ROOT, '--vv-instance-id=watch-e2e-second', DOC], ROOT);
  const two = await readyVisibleWindows(2);
  const second = two.find((win) => win.id !== first.id);
  assert.ok(second, 'a distinct second BrowserWindow must be visible');
  await waitForText(second, 'version-one', 'second window initial content');

  // Give chokidar time to report ready before the first write. Initial content delivery proves vv:open, not that
  // the async native watcher has installed its backend subscription yet.
  await sleep(300);
  fs.writeFileSync(DOC, '# Shared watch\n\nversion-two\n');
  await Promise.all([
    waitForText(first, 'version-two', 'watch change fan-out to the first window'),
    waitForText(second, 'version-two', 'watch change fan-out to the second window')
  ]);
  console.log('[ok] one file change refreshes every retaining renderer window');

  // The old implementation's single destination was the most recent opener. Destroy it without renderer-side
  // cleanup IPC, then prove the surviving owner still receives the next edit and therefore still owns the watch.
  second.close();
  await until(async () => second.isDestroyed(), Boolean, 'second window destruction');
  await sleep(200);
  fs.writeFileSync(DOC, '# Shared watch\n\nversion-three\n');
  await waitForText(first, 'version-three', 'surviving owner refresh after the other window closes');
  console.log('[ok] destroying one owner preserves the shared watcher and routes refresh to the survivor');
}

app.whenReady().then(() =>
  run()
    .then(() => {
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      app.exit(0);
    })
    .catch((error) => {
      console.error('\nwatch-e2e FAILED:\n', error && error.stack ? error.stack : error);
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      app.exit(1);
    }));
