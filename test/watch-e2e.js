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

// Never read or write the developer's real ~/.config/vinary-viewer: the real main persists the keymap selection
// recent files and window geometry there, and a resident `vv --daemon` from the
// same checkout watches those files — an unisolated run switched the LIVE app's keymap. Same idiom as tree-e2e.
// A SEPARATE temp dir, not inside SCRATCH: test documents there may be adopted as a watched Files-tree project.
const CONFIG_HOME = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-watch-e2e-config-')));
process.env.XDG_CONFIG_HOME = CONFIG_HOME;
// …and a private $XDG_RUNTIME_DIR with the password bridge off (test/fixtures/hermetic-env.js)
const RUNTIME = require('./fixtures/hermetic-env').hermeticRuntime(CONFIG_HOME);
const DOC = path.join(SCRATCH, 'shared.md');
process.env.VV_DAEMON_EVENTS_DESCRIPTOR = path.join(SCRATCH, 'daemon-events.json');
// a PDF caught mid-write settles on a unit-u backoff (ADR-0046 §Settling); 100 ms keeps the torn-write arm quick
process.env.VV_PDF_SETTLE_MS = '100';
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

  // Record the read number (`seq`, ADR-0046) of every payload each window receives for DOC: one read per change,
  // fanned out, gives both windows the SAME number; the old per-window reads gave each its own (and no number).
  const recordSeqs = (win) => evalIn(win, `(() => { window.__vvSeqs = [];
    window.vv.onContent((p) => { if (p.path === ${JSON.stringify(DOC)}) window.__vvSeqs.push(p.seq); });
    return true; })()`);
  const seqsOf = (win) => evalIn(win, `window.__vvSeqs.slice()`);
  await recordSeqs(first);
  await recordSeqs(second);

  // Give chokidar time to report ready before the first write. Initial content delivery proves vv:open, not that
  // the async native watcher has installed its backend subscription yet.
  await sleep(300);
  fs.writeFileSync(DOC, '# Shared watch\n\nversion-two\n');
  await Promise.all([
    waitForText(first, 'version-two', 'watch change fan-out to the first window'),
    waitForText(second, 'version-two', 'watch change fan-out to the second window')
  ]);
  const [s1, s2] = await Promise.all([seqsOf(first), seqsOf(second)]);
  assert.ok(s1.length >= 1 && s1.every((n) => Number.isInteger(n)), `the first window received numbered reads: ${s1}`);
  assert.strictEqual(s1[s1.length - 1], s2[s2.length - 1],
    `one read, delivered to both windows: the same read number (${s1} / ${s2})`);
  console.log('[ok] one file change is read once and delivered to every retaining renderer window');

  // A burst of writes: every window ends on the LAST version, from the same read, and never sees the read
  // numbers go backwards (an older read can no longer overwrite a newer one)
  for (let i = 1; i <= 6; i += 1) {
    fs.writeFileSync(DOC, `# Shared watch\n\nburst-${i}${i === 6 ? ' burst-final' : ''}\n`);
    await sleep(15);
  }
  await Promise.all([
    waitForText(first, 'burst-final', 'the burst ends on its last version in the first window'),
    waitForText(second, 'burst-final', 'the burst ends on its last version in the second window')
  ]);
  await sleep(400);   // let any trailing read land before reading the record
  const [b1, b2] = await Promise.all([seqsOf(first), seqsOf(second)]);
  const increasing = (xs) => xs.every((n, i) => i === 0 || n > xs[i - 1]);
  assert.ok(increasing(b1) && increasing(b2), `read numbers only increase: ${b1} / ${b2}`);
  assert.strictEqual(b1[b1.length - 1], b2[b2.length - 1], `both windows end on the same read (${b1} / ${b2})`);
  assert.ok((await renderedText(first)).includes('burst-final') && (await renderedText(second)).includes('burst-final'),
    'and that read is the last version');
  console.log(`[ok] a burst of ${6} writes ends on the last version in both windows (reads ${b1.slice(s1.length).join(' ')})`);

  // Deleting the file is a failed READ, not a reason to blank the document (ADR-0046): both windows keep the last
  // version behind the non-fatal refresh note; re-creating it refreshes both and clears the note
  const note = (win) => evalIn(win, `(() => { const n = document.querySelector('.vv-refresh-note');
    return n ? n.dataset.kind : null; })()`);
  const errorPage = (win) => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-error'))`);
  fs.rmSync(DOC);
  await Promise.all([
    until(() => note(first), (k) => k === 'refresh-failed', 'the first window notes the failed read'),
    until(() => note(second), (k) => k === 'refresh-failed', 'the second window notes the failed read')
  ]);
  for (const win of [first, second]) {
    assert.ok((await renderedText(win)).includes('burst-final'), 'the last version stays on screen');
    assert.strictEqual(await errorPage(win), false, 'no error page replaces it');
  }
  fs.writeFileSync(DOC, '# Shared watch\n\nversion-restored\n');
  await Promise.all([
    waitForText(first, 'version-restored', 'the re-created file refreshes the first window'),
    waitForText(second, 'version-restored', 'the re-created file refreshes the second window')
  ]);
  await Promise.all([
    until(() => note(first), (k) => k === null, 'the good read clears the first window\'s note'),
    until(() => note(second), (k) => k === null, 'the good read clears the second window\'s note')
  ]);
  console.log('[ok] a deleted file keeps its last version behind a refresh note in every window; re-creating it refreshes and clears the note');

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

  // A PDF REWRITTEN IN PLACE, the way pdflatex writes it (ADR-0046 §Torn reads): main verifies every read and settles
  // a torn one, so the window goes from v1 straight to v2 — never a torn version, a failed render or an error page in
  // between; a write that stops half-way (a LaTeX run that died) keeps v2 on screen behind the "incomplete" note, and
  // the finished file replaces it.
  const { makeDeckPdf, truncatePdf } = require('./fixtures/pdf-gen.js');
  const portrait = (pages) => makeDeckPdf({ pages, sizes: Array.from({ length: pages }, () => [300, 800]),
    labels: null, creator: 'watch-e2e', producer: 'pdflatex-like' });
  const DECK = path.join(SCRATCH, 'deck.pdf');
  const pages = (win) => evalIn(win, `document.querySelectorAll('.vv-content .vv-pdf-doc .vv-pdf-page').length`);
  fs.writeFileSync(DECK, portrait(3));
  await openIn(first, DECK);
  await until(() => pages(first), (n) => n === 3, 'the PDF opens (3 pages)');
  // record, every 10 ms from now on, anything a torn read would have shown
  await evalIn(first, `(() => { const seen = window.__vvTornSeen = [];
    window.__vvTornProbe = setInterval(() => {
      const n = document.querySelector('.vv-refresh-note');
      if (n && n.dataset.kind === 'render-failed') seen.push('render-failed note');
      if (document.querySelector('.vv-content .vv-error')) seen.push('error page');
    }, 10);
    return true; })()`);
  await sleep(300);   // the content watcher for DECK is installed
  const v2 = portrait(5);
  fs.writeFileSync(DECK, truncatePdf(v2, 0.5));           // pdflatex is half-way through…
  await sleep(150);
  fs.writeFileSync(DECK, v2);                             // …and finishes
  await until(() => pages(first), (n) => n === 5, 'the finished v2 replaces v1');
  await sleep(500);
  const seenTorn = await evalIn(first, `(clearInterval(window.__vvTornProbe), window.__vvTornSeen.slice())`);
  assert.deepStrictEqual(seenTorn, [], 'no torn version, failed render or error page was ever shown');
  assert.strictEqual(await note(first), null, 'and no note is left behind');
  console.log('[ok] a PDF rewritten in place goes from v1 straight to v2 — the torn read is settled, never shown');

  fs.writeFileSync(DECK, truncatePdf(portrait(7), 0.5)); // a run that dies half-way: the file stays torn
  await until(() => note(first), (k) => k === 'incomplete', 'the never-finished PDF raises the incomplete note', 15000);
  assert.strictEqual(await pages(first), 5, 'while v2 stays on screen');
  assert.strictEqual(await errorPage(first), false, 'with no error page');
  fs.writeFileSync(DECK, portrait(7));                    // the fixed run finishes
  await until(() => pages(first), (n) => n === 7, 'the finished file replaces v2');
  await until(() => note(first), (k) => k === null, 'and clears the note');
  console.log('[ok] a PDF left half-written keeps the last complete version behind the incomplete note; the finished file replaces it');
}

app.whenReady().then(() =>
  run()
    .then(() => {
      fs.rmSync(SCRATCH, { recursive: true, force: true }); fs.rmSync(CONFIG_HOME, { recursive: true, force: true }); RUNTIME.cleanup();
      app.exit(0);
    })
    .catch((error) => {
      console.error('\nwatch-e2e FAILED:\n', error && error.stack ? error.stack : error);
      fs.rmSync(SCRATCH, { recursive: true, force: true }); fs.rmSync(CONFIG_HOME, { recursive: true, force: true }); RUNTIME.cleanup();
      app.exit(1);
    }));
