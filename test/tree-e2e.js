'use strict';

// End-to-end proof of fallback project roots (ADR-0030) against the REAL application.
//
// Why this harness exists alongside the others: test/electron-smoke.js and scripts/screenshots.cjs both
// MOCK the vv:open IPC seam (they inject content via state.contentByPath / vv:open-files), so main's real
// open! → send-tree! → repo-tree / dir-walk chain never runs in them. test/git-tree-smoke.js exercises the
// git command directly but cannot boot the app. This file requires the real compiled main
// (dist/main/main.js) from INSIDE an Electron process — which is the only way it can be required, since it
// auto-invokes vinary.main.core/main on load — so the whole production chain executes:
//
//   __vvopen(path) → [:doc/open] → load-fx → vv:open → main open! → send-tree!
//     → repo-tree (git) OR dir-walk/dir-tree (synthetic) → vv:tree → [:tree/received]
//     → projects/merge-project → [:ui :projects] → ui.tree renders <details.vv-project>
//
// Assertions read BOTH the renderer's DEV inspect hook window.__vvdb() (app-db verbatim) and the real
// sidebar DOM, and drive the project-header context menu with actual mouse events.
//
// Run: npm run test:tree-e2e          (wraps it in xvfb-run; needs a DISPLAY)
//      xvfb-run -a electron --no-sandbox test/tree-e2e.js
//
// Not part of `npm test`: like test:electron, it needs a display and boots a real window.

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow } = require('electron');

const ROOT = path.resolve(__dirname, '..');

// The fixture lives in a throwaway temp dir OUTSIDE any git repository — that is the whole point — and is
// realpath'd because dir-tree realpaths the root it adopts (macOS hands out /var/… symlinked to
// /private/var/…, which would otherwise make every root comparison a false negative).
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-tree-e2e-')));

function writeFixture() {
  const mk = (rel, body) => {
    const abs = path.join(SCRATCH, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, body);
  };
  mk('sub/a.md', '# A\nnested note\n');
  mk('b.md', '# B\ntop note\n');
  mk('c.md', '# C\nsecond top\n');
  mk('deep/a/b/d.md', '# deep\n');
  mk('.dotfile.md', 'a hidden FILE — git ls-files lists these, so must we\n');
  mk('node_modules/dep/index.js', 'heavy dir — must be excluded\n');
  mk('.hidden/x.md', 'hidden dir — must be excluded\n');
}

require(path.join(ROOT, 'dist', 'main', 'main.js'));   // boots the real app

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PROJECTS = '(function(){ var d = window.__vvdb(); return (d && d.ui && d.ui.projects) || []; })()';
const HEADERS =
  "Array.from(document.querySelectorAll('.vv-project-name')).map(function(e){ return e.textContent; })";

async function waitForWindow() {
  for (let i = 0; i < 300; i++) {
    const [w] = BrowserWindow.getAllWindows();
    if (w && !w.webContents.isLoading()) return w;
    await sleep(100);
  }
  throw new Error('no renderer window appeared');
}

// Poll `expr` until `pred` accepts its value. Every UI assertion goes through this: Reagent re-renders on
// an animation frame and the IPC round-trip is async, so a single read races both.
async function until(wc, expr, pred, label) {
  let last = null;
  for (let i = 0; i < 120; i++) {
    last = await wc.executeJavaScript(expr);
    if (pred(last)) return last;
    await sleep(100);
  }
  throw new Error(`timed out waiting for: ${label}\n  last value = ${JSON.stringify(last, null, 2)}`);
}

const rootsOf = (ps) => ps.map((p) => p.root);
const byRoot = (ps, r) => ps.find((p) => p.root === r);
const open = (wc, p) => wc.executeJavaScript(`window.__vvopen(${JSON.stringify(p)})`);

async function run() {
  writeFixture();
  const win = await waitForWindow();
  const wc = win.webContents;
  await until(wc, 'typeof window.__vvdb === "function"', (v) => v === true, 'the __vvdb DEV hook');

  const passed = [];
  const check = (name, fn) => { fn(); passed.push(name); console.log(`  ✓ ${name}`); };

  // ---- a file in NO repo adopts its containing directory -----------------------------------------
  await open(wc, path.join(SCRATCH, 'sub', 'a.md'));
  let ps = await until(wc, PROJECTS, (p) => p.some((x) => x.root === path.join(SCRATCH, 'sub')),
                       'a synthetic root at <scratch>/sub');
  check('a file outside any git repo gets a project root (its parent directory)', () => {
    assert.deepStrictEqual(byRoot(ps, path.join(SCRATCH, 'sub')).files, ['a.md'],
                           'the root lists the opened file, root-relative');
  });
  check('the root is marked synthetic (an inference, not a git fact)', () => {
    assert.strictEqual(byRoot(ps, path.join(SCRATCH, 'sub'))['synthetic?'], true);
  });

  // ---- a broader synthetic root ABSORBS the narrower one ------------------------------------------
  await open(wc, path.join(SCRATCH, 'b.md'));
  ps = await until(wc, PROJECTS, (p) => p.some((x) => x.root === SCRATCH), 'a synthetic root at <scratch>');
  check('the broader directory absorbs the nested synthetic root (one tree, not two)', () => {
    assert.ok(!byRoot(ps, path.join(SCRATCH, 'sub')),
              `nested synthetic root should have been absorbed; roots = ${rootsOf(ps)}`);
  });

  // ---- the walk's recursion and exclusions --------------------------------------------------------
  check('the walk recurses, keeps hidden FILES, and excludes heavy + hidden DIRECTORIES', () => {
    const files = byRoot(ps, SCRATCH).files;
    assert.ok(files.includes('b.md'), 'top-level file listed');
    assert.ok(files.includes('sub/a.md'), 'nested file listed, root-relative');
    assert.ok(files.includes('deep/a/b/d.md'), 'deeply nested file listed');
    assert.ok(files.includes('.dotfile.md'), 'a hidden FILE is kept (parity with git ls-files)');
    assert.ok(!files.some((f) => f.startsWith('node_modules/')), 'node_modules excluded');
    assert.ok(!files.some((f) => f.startsWith('.hidden/')), 'a hidden DIRECTORY is excluded');
  });

  // ---- re-opening under a known root adds NOTHING --------------------------------------------------
  const before = rootsOf(ps).slice();
  await open(wc, path.join(SCRATCH, 'c.md'));
  await sleep(1500);
  ps = await wc.executeJavaScript(PROJECTS);
  check('opening another file under a known root adds no new project and does not reorder', () => {
    assert.deepStrictEqual(rootsOf(ps), before);
  });

  // ---- a file created AFTER the root was walked still appears --------------------------------------
  // The invariant `git ls-files --others` buys for repositories (and that git-tree-smoke.js exists to
  // protect): the file you just opened is in the tree. A synthetic root must not be staler than that.
  fs.writeFileSync(path.join(SCRATCH, 'sub', 'brand-new.md'), '# created after the walk\n');
  await open(wc, path.join(SCRATCH, 'sub', 'brand-new.md'));
  ps = await until(wc, PROJECTS,
                   (p) => { const r = byRoot(p, SCRATCH); return r && r.files.includes('sub/brand-new.md'); },
                   'a file created after the root was walked to appear in the covering root');
  check('a file created after its root was walked still appears in the sidebar', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('sub/brand-new.md'));
  });

  // ---- a real git repository is unchanged (and NOT synthetic) --------------------------------------
  await open(wc, path.join(ROOT, 'README.md'));
  ps = await until(wc, PROJECTS, (p) => p.some((x) => x.root === ROOT), 'the git root for this repo');
  check('a file inside a git repository still yields its repo root, not a synthetic one', () => {
    const p = byRoot(ps, ROOT);
    assert.ok(!p['synthetic?'], 'a git root must not be marked synthetic');
    assert.ok(p.files.includes('package.json'), 'the git listing is present');
  });
  check('the synthetic root and the git root coexist', () => {
    assert.ok(byRoot(ps, SCRATCH), 'the scratch project survives alongside the repo');
  });

  // ---- opening a DIRECTORY adopts that directory, never its parent ---------------------------------
  await open(wc, path.join(SCRATCH, 'deep'));
  await sleep(1800);
  ps = await wc.executeJavaScript(PROJECTS);
  check('opening a directory never adopts its parent (no root at the tmp dir or /)', () => {
    assert.ok(!byRoot(ps, os.tmpdir()), `${os.tmpdir()} must never become a project; roots = ${rootsOf(ps)}`);
    assert.ok(!byRoot(ps, '/'), '/ must never become a project');
  });

  // ---- the sidebar actually RENDERS the synthetic project ------------------------------------------
  await wc.executeJavaScript(
    "(function(){ var r = document.querySelector('.vv-sidebar-rail'); if (r) r.click(); return true; })()");
  await sleep(400);
  await wc.executeJavaScript(
    "(function(){ var t = document.querySelectorAll('.vv-sidebar-tab'); if (t.length) t[0].click(); return true; })()");
  const scratchName = path.basename(SCRATCH);
  const headers = await until(wc, HEADERS, (hs) => Array.isArray(hs) && hs.some((h) => h.includes(scratchName)),
                              'the sidebar to render the synthetic project header');
  check('the sidebar renders the synthetic project by its directory name', () => {
    assert.ok(headers.some((h) => h.includes(scratchName)));
  });
  const filePaths = await wc.executeJavaScript(
    "Array.from(document.querySelectorAll('.vv-file')).map(function(e){ return e.getAttribute('data-path'); })");
  check('the rendered file anchors resolve to real absolute paths under the synthetic root', () => {
    assert.ok(filePaths.includes(path.join(SCRATCH, 'sub', 'a.md')),
              `expected <scratch>/sub/a.md among ${filePaths.length} anchors`);
  });

  // ---- Remove from Files prunes the project (real context menu, real click) -------------------------
  const idx = headers.findIndex((h) => h.includes(scratchName));
  await wc.executeJavaScript(`(function(){
    document.querySelectorAll('.vv-project-name')[${idx}].dispatchEvent(
      new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 60, clientY: 60 }));
    return true; })()`);
  const labels = await until(
    wc,
    "Array.from(document.querySelectorAll('.vv-ctx-menu .vv-menu-item-label')).map(function(e){ return e.textContent; })",
    (ls) => Array.isArray(ls) && ls.length > 0,
    'the project-header context menu to open');
  check('the project header offers "Remove from Files"', () => {
    assert.ok(labels.includes('Remove from Files'), `menu was ${JSON.stringify(labels)}`);
  });
  check('the project menu is still directory-shaped (it is :project, a superset of :dir)', () => {
    assert.ok(labels.includes('Copy directory path'));
  });
  await wc.executeJavaScript(`(function(){
    Array.from(document.querySelectorAll('.vv-ctx-menu .vv-menu-item'))
      .find(function(e){ return e.textContent.trim() === 'Remove from Files'; }).click();
    return true; })()`);
  ps = await until(wc, PROJECTS, (p) => !p.some((x) => x.root === SCRATCH),
                   'the scratch project to leave app-db');
  check('Remove from Files prunes the project from app-db', () => {
    assert.ok(byRoot(ps, ROOT), 'the git project must be untouched');
  });
  const headersAfter = await until(
    wc, HEADERS, (hs) => Array.isArray(hs) && !hs.some((h) => h.includes(scratchName)),
    'the sidebar DOM to drop the removed project');
  check('Remove from Files removes the tree from the sidebar DOM', () => {
    assert.ok(!headersAfter.some((h) => h.includes(scratchName)),
              `sidebar still shows ${JSON.stringify(headersAfter)}`);
    assert.ok(headersAfter.length > 0, 'the other project(s) must remain rendered');
  });

  // ---- the removed root comes BACK when a file under it is opened again -----------------------------
  await open(wc, path.join(SCRATCH, 'b.md'));
  const returned = await until(wc, PROJECTS, (p) => p.some((x) => x.root === SCRATCH),
                               'the scratch project to return after re-opening');
  check('removal is a sidebar decision, not a persisted exclusion (the root returns)', () => {
    assert.ok(byRoot(returned, SCRATCH), 'the synthetic root is re-adopted on the next open');
  });

  console.log(`\ntree-e2e: ${passed.length} checks passed`);
}

app.whenReady().then(() =>
  run()
    .then(() => { fs.rmSync(SCRATCH, { recursive: true, force: true }); app.exit(0); })
    .catch((err) => {
      console.error('\ntree-e2e FAILED:\n', err && err.stack ? err.stack : err);
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      app.exit(1);
    }));
