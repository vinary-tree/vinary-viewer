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
//   CLI argv → startup/doc-uris → vv:open-files → [:files/opened] → [:doc/open]
//   __vvopen(path) ───────────────────────────────────────────────→ [:doc/open]
//     → load-fx → vv:open → main open! → send-tree!
//     → repo-tree (git) OR dir-walk/dir-tree (synthetic) → vv:tree → [:tree/received]
//     → projects/merge-project → [:ui :projects] → ui.tree renders <details.vv-project>
//
// Assertions read BOTH the renderer's DEV inspect hook window.__vvdb() (app-db verbatim) and the real
// sidebar DOM, and drive the project-header context menu with actual mouse events.
//
// Run: npm run test:tree-e2e          (uses the cross-platform headless runner)
//      VV_HEADLESS_BACKEND=wayland npm run test:tree-e2e   (Linux Weston variant)
//
// Not part of `npm test`: like test:electron, it needs a display and boots a real window.

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { app, BrowserWindow } = require('electron');
const { isLiveWindow } = require('./headless-window.js');

const ROOT = path.resolve(__dirname, '..');

// The fixture lives in a throwaway temp dir OUTSIDE any git repository — that is the whole point — and is
// realpath'd because dir-tree realpaths the root it adopts (macOS hands out /var/… symlinked to
// /private/var/…, which would otherwise make every root comparison a false negative).
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-tree-e2e-')));
const GIT_SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-tree-git-e2e-')));
const DIFF_BASE = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-tree-adopt-e2e-')));
const CONFIG_HOME = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-tree-e2e-config-')));
process.env.VV_DAEMON_EVENTS_DESCRIPTOR = path.join(CONFIG_HOME, 'daemon-events.json');
const CLI_DOC = path.join(ROOT, 'src', 'vinary', 'ui', 'tree.cljs');

// Keep persisted user settings (especially :sidebar-visible?) out of the harness: the CLI reveal assertion
// requires the default Files panel to be mounted before startup activation, and tests must never read/write the
// developer's real config. The nested repository file supplies three directory ancestors to expand.
process.env.XDG_CONFIG_HOME = CONFIG_HOME;

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

  execFileSync('git', ['-c', 'init.defaultBranch=main', 'init', '-q'], {
    cwd: GIT_SCRATCH, stdio: ['ignore', 'ignore', 'ignore']
  });
  fs.writeFileSync(path.join(GIT_SCRATCH, 'open.md'), '# git fixture\n');
  fs.writeFileSync(path.join(GIT_SCRATCH, 'tracked-rename.md'), '# rename me\n');
  fs.writeFileSync(path.join(GIT_SCRATCH, 'tracked-delete.md'), '# delete me\n');
  execFileSync('git', ['add', 'open.md', 'tracked-rename.md', 'tracked-delete.md'], {
    cwd: GIT_SCRATCH, stdio: ['ignore', 'ignore', 'ignore']
  });

  // ADR-0038 fixture: a repo at DIFF_BASE/repo, and a patch stored OUTSIDE it at DIFF_BASE/patches/
  // whose recorded paths ("repo/src/lib.rs") resolve through diff-source's ancestor walk from the
  // patch's own directory — the on-disk shape that makes seam 2 adopt the described repository.
  const diffRepo = path.join(DIFF_BASE, 'repo');
  fs.mkdirSync(path.join(diffRepo, 'src'), { recursive: true });
  execFileSync('git', ['-c', 'init.defaultBranch=main', 'init', '-q'], {
    cwd: diffRepo, stdio: ['ignore', 'ignore', 'ignore']
  });
  fs.writeFileSync(path.join(diffRepo, 'src', 'lib.rs'), 'pub fn answer() -> i32 {\n    42\n}\n');
  execFileSync('git', ['add', 'src/lib.rs'], { cwd: diffRepo, stdio: ['ignore', 'ignore', 'ignore'] });
  fs.mkdirSync(path.join(DIFF_BASE, 'patches'), { recursive: true });
  fs.writeFileSync(path.join(DIFF_BASE, 'patches', 'fix.patch'),
    ['diff --git a/repo/src/lib.rs b/repo/src/lib.rs',
     '--- a/repo/src/lib.rs',
     '+++ b/repo/src/lib.rs',
     '@@ -1,3 +1,3 @@',
     ' pub fn answer() -> i32 {',
     '-    42',
     '+    43',
     ' }',
     ''].join('\n'));
  fs.writeFileSync(path.join(diffRepo, 'local.patch'),
    ['diff --git a/src/lib.rs b/src/lib.rs',
     '--- a/src/lib.rs',
     '+++ b/src/lib.rs',
     '@@ -1,3 +1,3 @@',
     ' pub fn answer() -> i32 {',
     '-    42',
     '+    44',
     ' }',
     ''].join('\n'));
}

// The headless runner invokes `electron --no-sandbox <app>`, leaving the Chromium switch in argv[1]. Production is
// `electron <app> <document>`, the shape startup/doc-uris intentionally parses (user args begin at index 2).
// Chromium has already consumed its switch, so normalize only the argv presented to the required real main.
process.argv.splice(1, process.argv.length - 1, __filename, CLI_DOC);
require(path.join(ROOT, 'dist', 'main', 'main.js'));   // boots the real app with one genuine CLI document arg

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PROJECTS = '(function(){ var d = window.__vvdb(); return (d && d.ui && d.ui.projects) || []; })()';
const HEADERS =
  "Array.from(document.querySelectorAll('.vv-project-name')).map(function(e){ return e.textContent; })";

async function waitForWindow() {
  for (let i = 0; i < 300; i++) {
    // main refills its warm pool as soon as it opens the visible startup window. BrowserWindow's global list
    // contains that hidden, empty pool window too; the command-line document belongs to the visible claim.
    const w = BrowserWindow.getAllWindows().find(isLiveWindow);
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

  // ---- the REAL command-line startup reveals its active file --------------------------------------
  await until(wc,
              `(() => { const ui=window.__vvdb().ui; const t=ui.tabs.find(x=>x.id===ui['active-tab']);
                return t && t.uri; })()`,
              (uri) => uri === CLI_DOC,
              'the command-line document to become the active startup tab');
  await until(wc, `Boolean(document.querySelector('.vv-tree'))`, (v) => v === true,
              'the default Files panel to remain mounted during startup');
  const cliReveal = await until(
    wc,
    `(() => {
      const target=${JSON.stringify(CLI_DOC)};
      const a=[...document.querySelectorAll('.vv-file')].find(x=>x.getAttribute('data-path')===target);
      if(!a) return {active:false, opens:[], visible:false};
      const opens=[];
      for(let el=a.parentElement; el && !el.classList.contains('vv-tree'); el=el.parentElement) {
        if(el.matches('details.vv-dir')) opens.push(Boolean(el.open));
      }
      const body=a.closest('.vv-sidebar-body');
      const ar=a.getBoundingClientRect(), br=body && body.getBoundingClientRect();
      return {active:a.classList.contains('vv-file-active'), opens,
              visible:Boolean(br && ar.top>=br.top && ar.bottom<=br.bottom)};
    })()`,
    (state) => state && state.active && state.opens.length === 3
      && state.opens.every(Boolean) && state.visible,
    'the command-line document row to render, expand, and become visible in its repository tree');
  check('the real command-line launch selects its corresponding file-tree row', () => {
    assert.strictEqual(cliReveal.active, true);
  });
  check('the real command-line launch expands every ancestor and scrolls the row into view', () => {
    assert.ok(cliReveal.opens.every(Boolean), `ancestor open states = ${JSON.stringify(cliReveal.opens)}`);
    assert.strictEqual(cliReveal.visible, true, 'the active command-line row must be visible in the sidebar');
  });

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

  // ---- expanded-directory watchers are shallow and structural -------------------------------------
  // Let renderer :tree/received → syncTreeRoots and the mounted controlled tree → syncTreeExpanded settle,
  // then mutate a direct child WITHOUT opening it. The expanded project root owns a depth-0 watcher.
  await sleep(900);
  const autoAdded = path.join(SCRATCH, 'auto-added.md');
  fs.writeFileSync(autoAdded, '# automatic root refresh\n');
  ps = await until(wc, PROJECTS,
                   (p) => byRoot(p, SCRATCH)?.files.includes('auto-added.md'),
                   'an added direct child to arrive through the expanded-root watcher');
  check('an expanded project root automatically refreshes when a direct child is added', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('auto-added.md'));
  });

  const autoRenamed = path.join(SCRATCH, 'auto-renamed.md');
  fs.renameSync(autoAdded, autoRenamed);
  ps = await until(wc, PROJECTS,
                   (p) => { const files = byRoot(p, SCRATCH)?.files || [];
                     return files.includes('auto-renamed.md') && !files.includes('auto-added.md'); },
                   'a renamed direct child to replace its old path');
  check('rename events replace the old tree path with the new one', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('auto-renamed.md'));
  });

  fs.unlinkSync(autoRenamed);
  ps = await until(wc, PROJECTS,
                   (p) => !(byRoot(p, SCRATCH)?.files || []).includes('auto-renamed.md'),
                   'a deleted direct child to leave the tree');
  check('unlink events remove deleted paths from an expanded directory', () => {
    assert.ok(!byRoot(ps, SCRATCH).files.includes('auto-renamed.md'));
  });

  const addedDir = path.join(SCRATCH, 'auto-dir');
  fs.mkdirSync(addedDir);
  fs.writeFileSync(path.join(addedDir, 'inside.md'), '# directory add\n');
  ps = await until(wc, PROJECTS,
                   (p) => byRoot(p, SCRATCH)?.files.includes('auto-dir/inside.md'),
                   'an added directory and its file to arrive through the root watcher');
  check('addDir events refresh an expanded directory with the new subtree', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('auto-dir/inside.md'));
  });

  const renamedDir = path.join(SCRATCH, 'auto-dir-renamed');
  fs.renameSync(addedDir, renamedDir);
  ps = await until(wc, PROJECTS,
                   (p) => { const files=byRoot(p,SCRATCH)?.files||[];
                     return files.includes('auto-dir-renamed/inside.md')
                       && !files.includes('auto-dir/inside.md'); },
                   'a renamed directory subtree to replace its old path');
  check('directory rename events replace every old subtree path', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('auto-dir-renamed/inside.md'));
  });

  fs.rmSync(renamedDir, { recursive: true });
  ps = await until(wc, PROJECTS,
                   (p) => !(byRoot(p, SCRATCH)?.files || []).some((f) => f.startsWith('auto-dir-renamed/')),
                   'a deleted directory subtree to leave the tree');
  check('unlinkDir events remove a deleted subtree', () => {
    assert.ok(!(byRoot(ps, SCRATCH)?.files || []).some((f) => f.startsWith('auto-dir-renamed/')));
  });

  // Ordinary saves are handled by the retained-document watcher, not the Files-tree watcher. Observe the
  // public onTree channel directly so an identical re-list cannot hide behind app-db value equality.
  await wc.executeJavaScript(`window.__vvTreePushes=0; window.vv.onTree(()=>window.__vvTreePushes++); true`);
  fs.writeFileSync(path.join(SCRATCH, 'b.md'), '# B\ncontent-only edit\n');
  await sleep(650);
  const contentOnlyPushes = await wc.executeJavaScript('window.__vvTreePushes');
  check('ordinary file-content saves do not rebuild the Files tree', () => {
    assert.strictEqual(contentOnlyPushes, 0);
  });

  // Exercise the distinct git-backed listing through the same real watcher path. Unstaged tracked
  // deletion/rename is especially important: --cached still reports the old index path, so repo-files must
  // subtract `git ls-files --deleted` while retaining the untracked rename destination.
  await open(wc, path.join(GIT_SCRATCH, 'open.md'));
  ps = await until(wc, PROJECTS, (p) => p.some((x) => x.root === GIT_SCRATCH),
                   'the throwaway git project to join the Files tree');
  check('a throwaway git repository uses a non-synthetic project root', () => {
    assert.strictEqual(Boolean(byRoot(ps, GIT_SCRATCH)['synthetic?']), false);
  });
  await sleep(500);
  fs.writeFileSync(path.join(GIT_SCRATCH, 'untracked-added.md'), '# git add event\n');
  ps = await until(wc, PROJECTS,
                   (p) => byRoot(p, GIT_SCRATCH)?.files.includes('untracked-added.md'),
                   'an untracked git file to arrive automatically');
  check('expanded git roots automatically include new untracked files', () => {
    assert.ok(byRoot(ps, GIT_SCRATCH).files.includes('untracked-added.md'));
  });

  fs.renameSync(path.join(GIT_SCRATCH, 'tracked-rename.md'),
                path.join(GIT_SCRATCH, 'tracked-renamed.md'));
  ps = await until(wc, PROJECTS,
                   (p) => { const files=byRoot(p,GIT_SCRATCH)?.files||[];
                     return files.includes('tracked-renamed.md') && !files.includes('tracked-rename.md'); },
                   'an unstaged tracked-file rename to replace the cached path');
  check('git refresh removes the deleted side of an unstaged tracked rename', () => {
    assert.ok(byRoot(ps, GIT_SCRATCH).files.includes('tracked-renamed.md'));
  });

  fs.unlinkSync(path.join(GIT_SCRATCH, 'tracked-delete.md'));
  ps = await until(wc, PROJECTS,
                   (p) => !(byRoot(p,GIT_SCRATCH)?.files||[]).includes('tracked-delete.md'),
                   'an unstaged tracked-file deletion to leave the git tree');
  check('git refresh removes unstaged tracked deletions', () => {
    assert.ok(!byRoot(ps, GIT_SCRATCH).files.includes('tracked-delete.md'));
  });

  // ---- refresh-before-open: collapsed descendants stay stale, then open atomically with fresh data --------
  const subPath = path.join(SCRATCH, 'sub');
  const subSelector = `details.vv-dir[data-path=${JSON.stringify(subPath)}]`;
  const subOpen = await wc.executeJavaScript(`Boolean(document.querySelector(${JSON.stringify(subSelector)})?.open)`);
  if (subOpen) {
    await wc.executeJavaScript(`document.querySelector(${JSON.stringify(subSelector)}+' > summary').click(); true`);
    await until(wc, `Boolean(document.querySelector(${JSON.stringify(subSelector)})) &&
                     !document.querySelector(${JSON.stringify(subSelector)}).open`, (v) => v === true,
                'the nested sub directory to collapse');
  }
  await sleep(350); // let syncTreeExpanded release the nested watcher
  fs.writeFileSync(path.join(subPath, 'appears-on-expand.md'), '# created while collapsed\n');
  await sleep(650);
  ps = await wc.executeJavaScript(PROJECTS);
  check('a collapsed child does not refresh for mutations below it', () => {
    assert.ok(!byRoot(ps, SCRATCH).files.includes('sub/appears-on-expand.md'));
  });

  // Capture the first open-attribute transition. At that exact DOM commit the refreshed row must already exist;
  // this catches a stale flash that a later steady-state assertion could miss.
  await wc.executeJavaScript(`(() => {
    const d=document.querySelector(${JSON.stringify(subSelector)});
    window.__vvExpandSnapshots=[];
    const o=new MutationObserver(() => {
      if(d.open) window.__vvExpandSnapshots.push({
        has:Boolean([...d.querySelectorAll('.vv-file')].find(x=>x.dataset.path===${JSON.stringify(path.join(subPath, 'appears-on-expand.md'))}))
      });
    });
    o.observe(d,{attributes:true,attributeFilter:['open']}); window.__vvExpandObserver=o;
    d.querySelector(':scope > summary').click();
    return {open:d.open,pending:d.querySelector(':scope > summary').getAttribute('aria-busy')};
  })()`);
  const expandedFresh = await until(
    wc,
    `(() => ({open:Boolean(document.querySelector(${JSON.stringify(subSelector)})?.open),
              snaps:window.__vvExpandSnapshots || [],
              files:((${PROJECTS}).find(x=>x.root===${JSON.stringify(SCRATCH)})||{files:[]}).files}))()`,
    (s) => s.open && s.files.includes('sub/appears-on-expand.md') && s.snaps.length > 0,
    'the collapsed directory to refresh and then open');
  check('expansion renders open only after its refreshed child is present', () => {
    assert.strictEqual(expandedFresh.snaps[0].has, true,
                       `first open snapshot was ${JSON.stringify(expandedFresh.snaps)}`);
  });
  await wc.executeJavaScript('window.__vvExpandObserver?.disconnect(); true');

  // Once open, its own depth-0 watcher sees direct structural changes.
  fs.writeFileSync(path.join(subPath, 'while-expanded.md'), '# watched child\n');
  ps = await until(wc, PROJECTS,
                   (p) => byRoot(p, SCRATCH)?.files.includes('sub/while-expanded.md'),
                   'an expanded nested directory watcher to refresh');
  check('an expanded nested directory automatically refreshes its direct children', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('sub/while-expanded.md'));
  });

  // Collapsing an already-watched child releases its subscription. Re-expanding performs the gated refresh,
  // so the change becomes visible as part of the same commit that opens it again.
  await wc.executeJavaScript(`document.querySelector(${JSON.stringify(`${subSelector} > summary`)}).click(); true`);
  await until(wc, `!document.querySelector(${JSON.stringify(subSelector)}).open`, (v) => v === true,
              'the watched child directory to collapse');
  await sleep(350);
  fs.writeFileSync(path.join(subPath, 'after-child-collapse.md'), '# watcher released\n');
  await sleep(650);
  ps = await wc.executeJavaScript(PROJECTS);
  check('collapsing an expanded child disables its automatic refresh', () => {
    assert.ok(!byRoot(ps, SCRATCH).files.includes('sub/after-child-collapse.md'));
  });
  await wc.executeJavaScript(`document.querySelector(${JSON.stringify(subSelector)}+' > summary').click(); true`);
  ps = await until(wc, PROJECTS,
                   (p) => byRoot(p, SCRATCH)?.files.includes('sub/after-child-collapse.md'),
                   'the collapsed child to refresh on re-expansion');
  check('re-expanding a child refreshes changes missed while collapsed', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('sub/after-child-collapse.md'));
  });

  // An open descendant remains remembered when its project ancestor closes, but it is no longer effective:
  // both the root and child watchers must disappear until the ancestor is refreshed and rendered open again.
  const scratchProjectSelector = `details.vv-project[data-root=${JSON.stringify(SCRATCH)}]`;
  await wc.executeJavaScript(`document.querySelector(${JSON.stringify(scratchProjectSelector)}+' > summary').click(); true`);
  await until(wc, `!document.querySelector(${JSON.stringify(scratchProjectSelector)}).open`, (v) => v === true,
              'the scratch project ancestor to collapse');
  await sleep(350);
  fs.writeFileSync(path.join(SCRATCH, 'after-parent-collapse.md'), '# root watcher released\n');
  fs.writeFileSync(path.join(subPath, 'after-parent-collapse.md'), '# descendant watcher released\n');
  await sleep(650);
  ps = await wc.executeJavaScript(PROJECTS);
  check('collapsing an ancestor disables its descendant watchers', () => {
    assert.ok(!byRoot(ps, SCRATCH).files.includes('after-parent-collapse.md'));
    assert.ok(!byRoot(ps, SCRATCH).files.includes('sub/after-parent-collapse.md'));
  });
  await wc.executeJavaScript(`document.querySelector(${JSON.stringify(scratchProjectSelector)}+' > summary').click(); true`);
  ps = await until(wc, PROJECTS,
                   (p) => { const files=byRoot(p,SCRATCH)?.files||[];
                     return files.includes('after-parent-collapse.md')
                       && files.includes('sub/after-parent-collapse.md'); },
                   'the collapsed project to refresh before reopening');
  check('re-expanding an ancestor refreshes its complete project tree', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('after-parent-collapse.md'));
  });

  // The Files component unmounts on another sidebar tab, which sends an empty effective-expansion set. Returning
  // to Files refreshes remembered open project roots while the body shows its small restoration placeholder.
  await wc.executeJavaScript(`([...document.querySelectorAll('.vv-sidebar-tab')]
    .find(x=>x.textContent.trim()==='Contents')).click(); true`);
  await until(wc, "!document.querySelector('.vv-tree')", (v) => v === true,
              'the Files tree to unmount on the Contents tab');
  await sleep(350);
  fs.writeFileSync(path.join(SCRATCH, 'while-files-hidden.md'), '# hidden Files tab\n');
  await sleep(650);
  ps = await wc.executeJavaScript(PROJECTS);
  check('another sidebar tab suspends automatic Files-tree refresh', () => {
    assert.ok(!byRoot(ps, SCRATCH).files.includes('while-files-hidden.md'));
  });
  await wc.executeJavaScript(`([...document.querySelectorAll('.vv-sidebar-tab')]
    .find(x=>x.textContent.trim()==='Files')).click(); true`);
  ps = await until(wc, PROJECTS,
                   (p) => byRoot(p, SCRATCH)?.files.includes('while-files-hidden.md'),
                   'remembered project roots to refresh when Files returns');
  check('returning to Files refreshes remembered roots before showing their trees', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('while-files-hidden.md'));
  });
  await sleep(500); // let the restored watchers' one ready-time race-closing reconciliation settle

  // ---- manual refresh scopes -----------------------------------------------------------------------
  // Inject a deliberately stale project model without touching disk. A nested Refresh must replace only
  // `sub/`, retaining the unrelated sentinel; a project Refresh must then replace the entire listing.
  wc.send('vv:tree', { root: SCRATCH,
                       files: ['b.md', 'sub/fake.md', 'sibling/stale.md'],
                       synthetic: true, 'synthetic?': true });
  await until(wc, PROJECTS, (p) => byRoot(p, SCRATCH)?.files.includes('sub/fake.md'),
              'the deliberately stale subtree fixture');
  const subSummarySelector = `${subSelector} > summary`;
  await wc.executeJavaScript(`document.querySelector(${JSON.stringify(subSummarySelector)}).dispatchEvent(
    new MouseEvent('contextmenu',{bubbles:true,cancelable:true,clientX:70,clientY:70})); true`);
  await until(wc,
              "[...document.querySelectorAll('.vv-ctx-menu .vv-menu-item-label')].map(x=>x.textContent)",
              (ls) => ls.includes('Refresh'), 'the nested directory Refresh menu item');
  await wc.executeJavaScript(`([...document.querySelectorAll('.vv-ctx-menu .vv-menu-item')]
    .find(x=>x.textContent.trim()==='Refresh')).click(); true`);
  ps = await until(wc, PROJECTS,
                   (p) => { const files=byRoot(p,SCRATCH)?.files||[];
                     return files.includes('sub/a.md') && !files.includes('sub/fake.md'); },
                   'the manual subtree refresh');
  check('directory Refresh replaces only its subtree', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('sibling/stale.md'),
              'an unrelated sibling must survive a scoped refresh');
  });

  const scratchHeaderIndex = (await wc.executeJavaScript(HEADERS)).findIndex((h) => h.includes(scratchName));
  await wc.executeJavaScript(`document.querySelectorAll('.vv-project-name')[${scratchHeaderIndex}].dispatchEvent(
    new MouseEvent('contextmenu',{bubbles:true,cancelable:true,clientX:75,clientY:75})); true`);
  const projectRefreshLabels = await until(
    wc, "[...document.querySelectorAll('.vv-ctx-menu .vv-menu-item-label')].map(x=>x.textContent)",
    (ls) => ls.includes('Refresh'), 'the project Refresh menu item');
  check('project headers offer Refresh', () => assert.ok(projectRefreshLabels.includes('Refresh')));
  await wc.executeJavaScript(`([...document.querySelectorAll('.vv-ctx-menu .vv-menu-item')]
    .find(x=>x.textContent.trim()==='Refresh')).click(); true`);
  ps = await until(wc, PROJECTS,
                   (p) => !(byRoot(p, SCRATCH)?.files || []).includes('sibling/stale.md'),
                   'the full project refresh to discard the stale sibling');
  check('project Refresh replaces the complete root listing', () => {
    assert.ok(!byRoot(ps, SCRATCH).files.includes('sibling/stale.md'));
  });

  const filesTab = "[...document.querySelectorAll('.vv-sidebar-tab')].find(x=>x.textContent.trim()==='Files')";
  await wc.executeJavaScript(`(${filesTab}).dispatchEvent(
    new MouseEvent('contextmenu',{bubbles:true,cancelable:true,clientX:80,clientY:40})); true`);
  const filesLabels = await until(
    wc, "[...document.querySelectorAll('.vv-ctx-menu .vv-menu-item-label')].map(x=>x.textContent)",
    (ls) => ls.includes('Refresh All'), 'the Files-tab Refresh All menu item');
  check('the Files tab itself offers Refresh All', () => assert.ok(filesLabels.includes('Refresh All')));
  wc.send('vv:tree', { root: SCRATCH, files: ['b.md', 'fake-all.md'], 'synthetic?': true });
  wc.send('vv:tree', { root: ROOT, files: ['fake-root-all.md'], 'synthetic?': false });
  wc.send('vv:tree', { root: GIT_SCRATCH, files: ['fake-git-all.md'], 'synthetic?': false });
  await until(wc, PROJECTS,
              (p) => byRoot(p, SCRATCH)?.files.includes('fake-all.md')
                && byRoot(p, ROOT)?.files.includes('fake-root-all.md')
                && byRoot(p, GIT_SCRATCH)?.files.includes('fake-git-all.md'),
              'the stale fixtures for every visible tree');
  await wc.executeJavaScript(`([...document.querySelectorAll('.vv-ctx-menu .vv-menu-item')]
    .find(x=>x.textContent.trim()==='Refresh All')).click(); true`);
  ps = await until(wc, PROJECTS,
                   (p) => !(byRoot(p, SCRATCH)?.files || []).includes('fake-all.md')
                     && !(byRoot(p, ROOT)?.files || []).includes('fake-root-all.md')
                     && !(byRoot(p, GIT_SCRATCH)?.files || []).includes('fake-git-all.md'),
                   'Refresh All to replace every visible project listing');
  check('Files-tab Refresh All refreshes every visible tree', () => {
    assert.ok(byRoot(ps, SCRATCH).files.includes('sub/a.md'));
    assert.ok(byRoot(ps, ROOT).files.includes('README.md'));
    assert.ok(byRoot(ps, GIT_SCRATCH).files.includes('open.md'));
  });

  // ---- ADR-0038: a diff adopts the project it describes --------------------------------------------
  const DIFF_REPO = path.join(DIFF_BASE, 'repo');
  const FIX_PATCH = path.join(DIFF_BASE, 'patches', 'fix.patch');
  await open(wc, FIX_PATCH);
  ps = await until(wc, PROJECTS,
                   (p) => { const r = byRoot(p, DIFF_REPO);
                     return Boolean(r && (r.extras || []).some((x) => x.path === FIX_PATCH)); },
                   'the described repository to join Files with the patch attached');
  check('an on-disk patch stored outside its repo adopts that repo with an attached extra', () => {
    const r = byRoot(ps, DIFF_REPO);
    assert.strictEqual(Boolean(r['synthetic?']), false, 'the adopted root is a git fact');
    const extra = r.extras.find((x) => x.path === FIX_PATCH);
    assert.strictEqual(extra.name, 'fix.patch');
    assert.strictEqual(extra.kind, 'diff');
    assert.strictEqual(Boolean(extra['transient?']), false, 'an on-disk patch is a persistent extra');
    assert.ok(r.files.includes('src/lib.rs'), 'the adopted repo lists its own files');
  });

  const adoptedProjSelector = `details.vv-project[data-root=${JSON.stringify(DIFF_REPO)}]`;
  const extraDom = await until(
    wc,
    `(() => {
       const proj = document.querySelector(${JSON.stringify(adoptedProjSelector)});
       if (!proj) return null;
       const a = proj.querySelector('a.vv-file-extra');
       return a && { path: a.getAttribute('data-path'), text: a.textContent,
                     active: a.classList.contains('vv-file-active'), open: proj.open };
     })()`,
    (v) => Boolean(v && v.path === FIX_PATCH),
    'the sidebar to render the attached patch row inside the adopted project');
  check('the extra renders inside the (revealed) adopted project as the active row', () => {
    assert.ok(extraDom.text.includes('fix.patch'));
    assert.strictEqual(extraDom.active, true, 'the open patch row carries vv-file-active');
    assert.strictEqual(extraDom.open, true, 'adopting reveals the project (active-scopes extras arm)');
  });

  // A full extras-less refresh must UNION, not replace: re-opening a repo file re-sends the complete
  // entry with no extras — the branch that would silently wipe attachments without merge-extras.
  await open(wc, path.join(DIFF_REPO, 'src', 'lib.rs'));
  await sleep(1200);
  ps = await wc.executeJavaScript(PROJECTS);
  check('a full extras-less refresh (re-open) keeps the attached patch (union, not replace)', () => {
    const r = byRoot(ps, DIFF_REPO);
    assert.ok((r.extras || []).some((x) => x.path === FIX_PATCH),
              `extras after refresh = ${JSON.stringify(r.extras)}`);
  });

  // The inside-root guard: a patch stored IN the repo is already an ls-files row; a second (extra)
  // listing would be a double.
  const LOCAL_PATCH = path.join(DIFF_REPO, 'local.patch');
  await open(wc, LOCAL_PATCH);
  ps = await until(wc, PROJECTS,
                   (p) => (byRoot(p, DIFF_REPO)?.files || []).includes('local.patch'),
                   'the in-repo patch to appear as an ordinary untracked row');
  await sleep(900);   // give seam 2 time to (wrongly) attach an extra if the guard ever broke
  ps = await wc.executeJavaScript(PROJECTS);
  check('a patch INSIDE the repo is a single ordinary row — never a second (extra) listing', () => {
    const r = byRoot(ps, DIFF_REPO);
    assert.ok(!(r.extras || []).some((x) => x.path === LOCAL_PATCH),
              `extras = ${JSON.stringify(r.extras)}`);
    assert.ok((r.extras || []).some((x) => x.path === FIX_PATCH),
              'the earlier outside-repo extra still survives these refreshes');
  });

  console.log(`\ntree-e2e: ${passed.length} checks passed`);
}

app.whenReady().then(() =>
  run()
    .then(() => {
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      fs.rmSync(GIT_SCRATCH, { recursive: true, force: true });
      fs.rmSync(DIFF_BASE, { recursive: true, force: true });
      fs.rmSync(CONFIG_HOME, { recursive: true, force: true });
      app.exit(0);
    })
    .catch((err) => {
      console.error('\ntree-e2e FAILED:\n', err && err.stack ? err.stack : err);
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      fs.rmSync(GIT_SCRATCH, { recursive: true, force: true });
      fs.rmSync(DIFF_BASE, { recursive: true, force: true });
      fs.rmSync(CONFIG_HOME, { recursive: true, force: true });
      app.exit(1);
    }));
