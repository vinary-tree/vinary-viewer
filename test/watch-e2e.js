'use strict';

// Real-main-process regression for multi-window live refresh. The lightweight Electron smoke owns a mocked
// vv:open handler, so it cannot prove chokidar ownership or delivery. This harness boots dist/main/main.js,
// opens one path in two real renderer windows, changes it on disk, closes the most-recent owner, and changes it
// again. Both fan-out and destroyed-window cleanup are load-bearing: the old global path -> WebContents map sent
// only to the last opener, then retained that destroyed destination forever.

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

const ROOT = path.resolve(__dirname, '..');
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-watch-e2e-')));
const DOC = path.join(SCRATCH, 'shared.md');
process.env.VV_DAEMON_EVENTS_DESCRIPTOR = path.join(SCRATCH, 'daemon-events.json');
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
  return BrowserWindow.getAllWindows().filter(isLiveWindow);
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

function projectFiles(win) {
  return evalIn(win, `(window.__vvdb()?.ui?.projects || [])
    .find((project) => project.root === ${JSON.stringify(SCRATCH)})?.files || []`);
}

async function waitForTreeFile(win, relativePath, present, label) {
  return until(
    () => projectFiles(win),
    (files) => files.includes(relativePath) === present,
    label
  );
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

  // Tree watchers have separate ownership: one shared depth-0 watcher per expanded directory, with the
  // owner set reconciled from each mounted Files view. A structural add must fan out to both windows.
  const treeBoth = path.join(SCRATCH, 'tree-both.md');
  fs.writeFileSync(treeBoth, '# both expanded\n');
  await Promise.all([
    waitForTreeFile(first, 'tree-both.md', true, 'tree add fan-out to the first window'),
    waitForTreeFile(second, 'tree-both.md', true, 'tree add fan-out to the second window')
  ]);
  console.log('[ok] one structural change refreshes every window with that directory expanded');

  // Collapse only the first root. Its renderer must leave the shared watch's owner set without tearing down
  // the second owner's subscription or receiving the second owner's future scoped updates.
  await evalIn(first, `document.querySelector(
    ${JSON.stringify(`details.vv-project[data-root=${JSON.stringify(SCRATCH)}] > summary`)})?.click(); true`);
  await until(
    () => evalIn(first, `(() => { const d=document.querySelector(
      ${JSON.stringify(`details.vv-project[data-root=${JSON.stringify(SCRATCH)}]`)});
      return d ? Boolean(d.open) : null; })()`),
    (open) => open === false,
    'the first window project root to collapse'
  );
  await sleep(350);
  const secondOnly = path.join(SCRATCH, 'tree-second-only.md');
  fs.writeFileSync(secondOnly, '# only second expanded\n');
  await waitForTreeFile(second, 'tree-second-only.md', true,
    'the remaining expanded owner to receive a structural update');
  await sleep(500);
  assert.ok(!(await projectFiles(first)).includes('tree-second-only.md'),
    'a collapsed window must not receive another owner\'s automatic tree refresh');
  console.log('[ok] collapsing one window releases only its tree-watcher ownership');

  // The old implementation's single destination was the most recent opener. Destroy it without renderer-side
  // cleanup IPC, then prove the surviving owner still receives the next edit and therefore still owns the watch.
  second.close();
  await until(async () => second.isDestroyed(), Boolean, 'second window destruction');
  await sleep(200);
  fs.writeFileSync(DOC, '# Shared watch\n\nversion-three\n');
  await waitForText(first, 'version-three', 'surviving owner refresh after the other window closes');
  console.log('[ok] destroying one owner preserves the shared watcher and routes refresh to the survivor');

  // Re-expansion first reconciles everything missed while collapsed, then installs a new sole-owner watcher.
  await evalIn(first, `document.querySelector(
    ${JSON.stringify(`details.vv-project[data-root=${JSON.stringify(SCRATCH)}] > summary`)})?.click(); true`);
  await waitForTreeFile(first, 'tree-second-only.md', true,
    'the collapsed survivor to reconcile before reopening');
  await sleep(350);
  fs.writeFileSync(path.join(SCRATCH, 'tree-first-reacquired.md'), '# sole owner\n');
  await waitForTreeFile(first, 'tree-first-reacquired.md', true,
    'the surviving window to reacquire automatic tree refresh');
  console.log('[ok] re-expansion after owner destruction reacquires a clean sole-owner tree watcher');
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
