'use strict';

// Real-app, real-SFTP end-to-end proof that the client/server path works for slide decks (plan G1; ADR-0045,
// ADR-0046), plus the remote re-open and position arms (plan S6, S2). One real Electron main process, real renderer
// windows, and two in-process ssh2 SFTP servers:
//
//   • `deck` host — main is also the target daemon (ADR-0035, as in remote-daemon-events-e2e), so a change to the deck
//     reaches the windows through the authenticated event channel: a Beamer deck open in THREE windows is read once
//     per change and fanned out (H4), never moves backwards under a delayed read, keeps its last complete version
//     through a torn write and a refused read, and keeps its slide through a connection dropped mid-read while
//     presenting. Presented in two linked windows (Shift+S, ADR-0048 §8.8), the audience window's open costs at most
//     one more read, either window drives, and a change on the server is still one read, shown in both on one slide;
//   • `quiet` host — no daemon and no poller, so NOTHING announces a change there: a Back/Forward step is the only
//     thing that can notice one. That isolates the conditional re-open (S6): an unchanged document costs one stat and
//     no read; a changed one is read again. And the view position survives Back/Forward, a tab switch and a tab close
//     for a remote document (S2).
//
// The runner (remote-pdf-e2e-runner.js) owns the temporary HOME, as remote-daemon-events-e2e-runner does.

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { app, BrowserWindow } = require('electron');
const { startSftpServer } = require('./fixtures/ssh-server.js');
const { makeDeckPdf, truncatePdf } = require('./fixtures/pdf-gen.js');
const { isLiveWindow } = require('./headless-window.js');

const ROOT = path.resolve(__dirname, '..');
const requestedHome = process.env.VV_REMOTE_PDF_E2E_HOME;
if (!requestedHome) throw new Error('VV_REMOTE_PDF_E2E_HOME must be supplied by the integration-test runner');
const HOME_DIR = fs.realpathSync(requestedHome);
const DECK = path.join(HOME_DIR, 'talk', 'deck.pdf');
const DESCRIPTOR = path.join(HOME_DIR, '.vinary-viewer', 'runtime', 'daemon-events.json');
const QUIET = path.join(HOME_DIR, 'quiet');          // the daemon-less host's root AND home: no descriptor there
const NOTES = path.join(QUIET, 'notes.md');
const OTHER = path.join(QUIET, 'other.md');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const PAD = 400 * 1024;                               // each deck version ≈ 400 KB: one change = one real download
const version = (frames) => makeDeckPdf({ frames, padBytes: PAD, seed: frames.length });
const notesText = (extra) => '# Remote notes\n\n' + Array.from({ length: 600 }, (_, i) => `Line ${i + 1} of the notes.\n\n`).join('') + (extra || '');

function fixture() {
  fs.mkdirSync(path.dirname(DECK), { recursive: true });
  fs.writeFileSync(DECK, version([1, 2, 1, 1]));                  // v1: 5 pages, 4 frames
  fs.mkdirSync(QUIET, { recursive: true });
  fs.writeFileSync(NOTES, notesText());
  fs.writeFileSync(OTHER, '# OTHER DOC\n\nNothing to see.\n');
}

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

const liveWindows = () => BrowserWindow.getAllWindows().filter(isLiveWindow);
const readyWindows = (count) => until(
  async () => liveWindows().filter((win) => !win.webContents.isLoading()),
  (wins) => wins.length === count, `${count} loaded renderer window(s)`, 30000);
const evalIn = (win, source) => win.webContents.executeJavaScript(source, true);
const openIn = (win, uri) => evalIn(win, `window.__vvopen(${JSON.stringify(uri)}); true`);
function key(win, keyCode, modifiers = []) {
  win.focus();
  win.webContents.focus();
  win.webContents.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
  win.webContents.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
}
// a key at the document, as a user's: the content area holds the focus first (an audience window opens unfocused)
async function press(win, keyCode, modifiers = []) {
  await evalIn(win, `document.querySelector('.vv-content').focus({ preventScroll: true }); true`);
  key(win, keyCode, modifiers);
}
const slides = (win) => evalIn(win, `(() => { const s = (window.__vvdb().ui || {}).slides || {};
  return { count: s.pages, page: s.page, layout: s.layout }; })()`);
const pdfPages = (win) => evalIn(win, `document.querySelectorAll('.vv-content .vv-pdf-doc .vv-pdf-page').length`);
const note = (win) => evalIn(win, `(() => { const n = document.querySelector('.vv-refresh-note');
  return n ? n.dataset.kind : null; })()`);
const presenting = (win) => evalIn(win, `Boolean((window.__vvdb().ui.presentation || {})['active?'])`);
const bodyText = (win) => evalIn(win, `document.querySelector('.vv-content .markdown-body')?.textContent || ''`);
const scrollTop = (win) => evalIn(win, `document.querySelector('.vv-content')?.scrollTop || 0`);
const setScroll = (win, y) => evalIn(win, `(() => { const c = document.querySelector('.vv-content'); c.scrollTop = ${y};
  return c.scrollTop; })()`);
const tabCount = (win) => evalIn(win, `((window.__vvdb().ui || {}).tabs || []).length`);
// a window's side of a linked show ([:ui :slides-link]), or null when it is in none
const linkOf = (win) => evalIn(win, `(() => { const l = (window.__vvdb().ui || {})['slides-link'] || null;
  return l ? { role: l.role, phase: l.phase, peer: l.peer } : null; })()`);
const bothOn = (a, b, page, label) => until(async () => [(await slides(a)).page, (await slides(b)).page],
  ([pa, pb]) => pa === page && pb === page, `${label}: both windows on slide ${page}`);

// every vv:content each window receives for a path: its read number and, for a PDF, its byte length
async function recordContent(win, p) {
  await evalIn(win, `(() => { window.__vvRecv = window.__vvRecv || {}; window.__vvRecv[${JSON.stringify(p)}] = [];
    window.vv.onContent((m) => { if (m.path === ${JSON.stringify(p)})
      window.__vvRecv[${JSON.stringify(p)}].push({ seq: m.seq, bytes: m.bytes ? m.bytes.length : null,
                                                   incomplete: Boolean(m.incomplete) }); });
    return true; })()`);
}
const received = (win, p) => evalIn(win, `(window.__vvRecv[${JSON.stringify(p)}] || []).slice()`);

async function run() {
  fixture();
  const deckSrv = await startSftpServer({ dir: HOME_DIR, exposeAbsoluteRoot: HOME_DIR, realHome: HOME_DIR,
    allowNone: true, noDelay: true });
  const quietSrv = await startSftpServer({ dir: QUIET, exposeAbsoluteRoot: QUIET, realHome: QUIET,
    allowNone: true, noDelay: true });
  const sshDir = path.join(HOME_DIR, '.ssh');
  fs.mkdirSync(sshDir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(sshDir, 'known_hosts'),
    [deckSrv, quietSrv].map((s) => `[127.0.0.1]:${s.port} ${s.hostKeyType} ${s.hostKeyB64}\n`).join(''), { mode: 0o600 });

  process.env.HOME = HOME_DIR;
  process.env.XDG_CONFIG_HOME = path.join(HOME_DIR, '.config');
  // …and a private $XDG_RUNTIME_DIR with the password bridge off (test/fixtures/hermetic-env.js)
  const runtime = require('./fixtures/hermetic-env').hermeticRuntime(process.env.XDG_CONFIG_HOME);
  process.env.VV_DAEMON_EVENTS_DESCRIPTOR = DESCRIPTOR;
  process.env.VV_POOL = '0';
  process.env.VV_PDF_SETTLE_MS = '100';                // a torn write settles on a 100 ms unit (ADR-0046 §Settling)
  process.argv.splice(1, process.argv.length - 1, __filename);
  require(path.join(ROOT, 'dist', 'main', 'main.js'));

  const deckUri = deckSrv.url(DECK);
  const notesUri = quietSrv.url(NOTES);
  const otherUri = quietSrv.url(OTHER);
  const deckKey = DECK;                                // the fixture keys its stats by the served absolute path
  let failed = null;
  try {
    const [first] = await readyWindows(1);
    await until(() => evalIn(first, 'typeof window.__vvopen'), (v) => v === 'function', 'renderer test hooks');
    for (let i = 0; i < 100 && !fs.existsSync(DESCRIPTOR); i++) await sleep(50);
    assert.ok(fs.existsSync(DESCRIPTOR), 'the target daemon descriptor is published');

    // ── A. a remote Beamer deck in three windows: detected, laid out as slides, and read ONCE per change (H4) ──
    await openIn(first, deckUri);
    await until(() => slides(first), (s) => s.count === 5 && s.layout === 'slides', 'window 1: the remote deck opens as 5 slides');
    app.emit('second-instance', {}, [process.execPath, ROOT, '--vv-instance-id=remote-pdf-e2e-2', deckUri], ROOT);
    app.emit('second-instance', {}, [process.execPath, ROOT, '--vv-instance-id=remote-pdf-e2e-3', deckUri], ROOT);
    const wins = await readyWindows(3);
    const [, second, third] = [first, ...wins.filter((w) => w.id !== first.id)];
    for (const win of [second, third]) {
      await until(() => slides(win), (s) => s.count === 5, `window ${win.id}: the deck as 5 slides`);
    }
    for (const win of [first, second, third]) await recordContent(win, deckUri);
    await sleep(1000);                                 // the target-daemon content subscription becomes active
    const v2 = version([1, 1, 1]);
    deckSrv.resetStats();
    fs.writeFileSync(DECK, v2);
    for (const win of [first, second, third]) {
      await until(() => slides(win), (s) => s.count === 3, `window ${win.id}: v2 (3 slides) arrives`);
    }
    await sleep(500);
    const moved = deckSrv.stats.byPath[deckKey];
    assert.ok(moved && moved.readBytes <= 2 * v2.length + 2 * 1088,
      `one change cost ${moved && moved.readBytes} bytes for a ${v2.length}-byte deck in 3 windows (≤ 2·S + 2 tails — H4)`);
    assert.ok(moved.opens <= 2, `…and ${moved.opens} OPEN(s) (≤ 2)`);
    const seqs = await Promise.all([first, second, third].map((w) => received(w, deckUri)));
    const last = seqs.map((list) => list[list.length - 1] && list[list.length - 1].seq);
    assert.ok(last.every((n) => Number.isInteger(n) && n === last[0]), `all three windows applied the same read (${last})`);
    console.log(`[ok] A: a remote Beamer deck opens as slides in 3 windows; one change = ${moved.readBytes} bytes, ${moved.opens} open(s) (S = ${v2.length})`);

    // ── H. the remote deck presented in two linked windows (Shift+S, ADR-0048 §8.8) ──────────────────────────────
    // Run here, while the deck host's change events are known to flow (arm A), and ended before arm B, so B–G see the
    // same three windows. The audience window is a cold window (VV_POOL=0), which joins the show once it has loaded.
    await evalIn(first, `window.__vvslides('goto', 0); true`);
    await until(() => slides(first), (s) => s.page === 0, 'window 1 on slide 0');
    const before = new Set(liveWindows().map((w) => w.id));
    deckSrv.resetStats();
    await press(first, 'S', ['shift']);                 // Present in New Window
    const audience = await until(async () => liveWindows().find((w) => !before.has(w.id) && !w.webContents.isLoading()),
      Boolean, 'Shift+S: the audience window', 30000);
    await until(() => evalIn(audience, 'typeof window.__vvdb'), (v) => v === 'function', "the audience window's test hooks");
    await until(async () => [await presenting(audience), await linkOf(audience)],
      ([p, l]) => p && l && l.role === 'audience' && l.phase === 'live', 'the audience window presents the remote deck', 30000);
    await until(() => linkOf(first), (l) => l && l.role === 'presenter' && l.phase === 'live' && l.peer === 'live',
      'window 1 presents, linked to the audience window', 30000);
    assert.strictEqual((await slides(audience)).count, 3, 'the audience window shows v2');
    await bothOn(first, audience, 0, "the show starts on window 1's slide");
    await sleep(500);                                  // a second read, if one were coming, would have landed
    const joined = deckSrv.stats.byPath[deckKey] || { readBytes: 0, opens: 0 };
    assert.ok(joined.readBytes <= v2.length + 1088 && joined.opens <= 1,
      `the audience window's open cost ${joined.readBytes} bytes, ${joined.opens} OPEN(s) (≤ one read of S = ${v2.length} and its tail)`);

    await press(first, 'Space');
    await bothOn(first, audience, 1, 'Space in window 1');
    await press(audience, 'Space');
    await bothOn(first, audience, 2, 'Space in the audience window');
    await press(audience, 'Space', ['shift']);
    await bothOn(first, audience, 1, 'Shift+Space in the audience window');

    await recordContent(audience, deckUri);
    const vH = version([1, 1, 1, 1, 1, 1, 1, 1]);      // 8 slides
    deckSrv.resetStats();
    fs.writeFileSync(DECK, vH);
    for (const win of [first, second, third, audience]) {
      await until(() => slides(win), (s) => s.count === 8, `window ${win.id}: the 8-slide version arrives`, 20000);
    }
    await bothOn(first, audience, 1, 'after the change on the server');
    await sleep(500);
    const linkedChange = deckSrv.stats.byPath[deckKey];
    assert.ok(linkedChange && linkedChange.readBytes <= 2 * vH.length + 2 * 1088 && linkedChange.opens <= 2,
      `one change cost ${linkedChange && linkedChange.readBytes} bytes, ${linkedChange && linkedChange.opens} OPEN(s), for a ${vH.length}-byte deck in 4 windows (≤ 2·S + 2 tails, ≤ 2 OPENs — H4)`);
    const [readFirst, readAudience] = await Promise.all([first, audience].map(async (w) => {
      const list = await received(w, deckUri);
      return list.length ? list[list.length - 1].seq : null;
    }));
    assert.ok(Number.isInteger(readFirst) && readFirst === readAudience,
      `both windows of the show applied the same read (${readFirst}, ${readAudience})`);
    const live = await Promise.all([first, audience].map(linkOf));
    assert.deepStrictEqual(live.map((l) => l && l.phase), ['live', 'live'], 'the show goes on through the change');

    await press(first, 'Escape');
    await until(async () => audience.isDestroyed(), Boolean, 'Escape in window 1 closes the audience window');
    await until(() => presenting(first), (p) => p === false, 'Escape in window 1 ends its show');
    const left = await linkOf(first);
    assert.ok(!left || left.role == null, `window 1 leaves the link (${JSON.stringify(left)})`);
    console.log(`[ok] H: a linked show of the remote deck — the audience window's open cost ${joined.readBytes} bytes; ` +
      `flips from either window; a change cost ${linkedChange.readBytes} bytes (S = ${vH.length}) and showed in both on slide 1; Escape ended both`);

    // ── B. a delayed read never moves a window backwards ─────────────────────────────────────────────────────
    deckSrv.setDelay((req) => (req.op === 'READ' && req.path === deckKey ? 1200 : 0));
    fs.writeFileSync(DECK, version([1, 1, 1, 1]));    // v3: 4 slides …
    await sleep(300);
    const v4 = version([1, 1, 2, 1, 1]);              // … and v4: 6 slides, written while v3's read is held
    fs.writeFileSync(DECK, v4);
    for (const win of [first, second, third]) {
      await until(() => slides(win), (s) => s.count === 6, `window ${win.id}: v4 (6 slides) arrives`, 20000);
    }
    deckSrv.setDelay(null);
    for (const win of [first, second, third]) {
      const list = await received(win, deckUri);
      for (let i = 1; i < list.length; i++) {
        assert.ok(list[i].seq > list[i - 1].seq, `window ${win.id}: read numbers only increase (${list.map((x) => x.seq)})`);
      }
    }
    console.log('[ok] B: under a delayed read, every window applies reads in order and ends on the last version');

    // ── C. a torn write keeps the last complete version behind the "incomplete" note ─────────────────────────
    const v5 = version([2, 1, 1, 1, 1, 1]);            // 7 slides
    fs.writeFileSync(DECK, truncatePdf(v5, 0.5));     // pdflatex died half-way
    for (const win of [first, second, third]) {
      await until(() => note(win), (k) => k === 'incomplete', `window ${win.id}: the incomplete note`, 20000);
      assert.strictEqual((await slides(win)).count, 6, `window ${win.id}: v4 stays on screen`);
    }
    fs.writeFileSync(DECK, v5);                        // the next run finishes
    for (const win of [first, second, third]) {
      await until(() => slides(win), (s) => s.count === 7, `window ${win.id}: v5 replaces v4`, 20000);
      await until(() => note(win), (k) => k === null, `window ${win.id}: the note clears`);
    }
    console.log('[ok] C: a half-written deck keeps the last complete version behind the incomplete note; the finished one replaces it');

    // ── D. a refused read keeps the deck behind the "refresh-failed" note ────────────────────────────────────
    deckSrv.setFault((req) => (req.op === 'OPEN' && req.path === deckKey ? deckSrv.STATUS_CODE.PERMISSION_DENIED : null));
    fs.writeFileSync(DECK, version([1, 1]));          // v6: 2 slides, which the server now refuses to open
    for (const win of [first, second, third]) {
      await until(() => note(win), (k) => k === 'refresh-failed', `window ${win.id}: the refresh-failed note`, 20000);
      assert.strictEqual((await slides(win)).count, 7, `window ${win.id}: v5 stays on screen`);
    }
    deckSrv.setFault(null);
    const v7 = version([1, 1, 1, 1]);                  // 4 slides
    fs.writeFileSync(DECK, v7);
    for (const win of [first, second, third]) {
      await until(() => slides(win), (s) => s.count === 4, `window ${win.id}: v7 once the server serves it again`, 20000);
      await until(() => note(win), (k) => k === null, `window ${win.id}: the note clears`);
    }
    console.log('[ok] D: a permission-denied read keeps the deck behind the refresh-failed note; the next good read clears it');

    // ── E. a connection dropped mid-read while presenting keeps the slide; Retry shows the new version there ───
    await evalIn(first, `window.__vvslides('goto', 2); true`);
    // the exact page asked for: arm H leaves window 1 on slide 1, so "past slide 0" would hold before the goto lands
    const atSlide = await until(() => slides(first), (s) => s.page === 2, 'window 1 on slide 2');
    key(first, 'F5', ['shift']);                      // present from the current slide
    await until(() => presenting(first), Boolean, 'window 1 presents');
    const reads = [];
    deckSrv.onRequest((req) => { if (req.op === 'READ' && req.path === deckKey) reads.push(req.offset); });
    deckSrv.setDelay((req) => (req.op === 'READ' && req.path === deckKey ? 1500 : 0));
    const v8 = version([1, 1, 1, 1, 1]);              // 5 slides
    fs.writeFileSync(DECK, v8);
    await until(async () => reads.length, (n) => n > 0, 'the v8 read reaches the server');
    deckSrv.destroyConnections();                      // the network goes away mid-read
    await until(() => note(first), (k) => k === 'refresh-failed', 'window 1: the dropped read is noted', 20000);
    assert.strictEqual(await presenting(first), true, 'window 1 is still presenting');
    assert.strictEqual((await slides(first)).page, atSlide.page, 'on the same slide');
    assert.strictEqual((await slides(first)).count, 4, 'showing v7');
    deckSrv.setDelay(null);
    deckSrv.onRequest(null);
    await evalIn(first, `document.querySelector('.vv-refresh-note-retry').click(); true`);
    await until(() => slides(first), (s) => s.count === 5, 'window 1: Retry reconnects and shows v8', 20000);
    assert.strictEqual(await presenting(first), true, 'still presenting after the reconnect');
    assert.strictEqual((await slides(first)).page, atSlide.page, `still on slide ${atSlide.page}`);
    key(first, 'Escape');
    await until(() => presenting(first), (p) => p === false, 'Escape ends the show');
    console.log(`[ok] E: a connection dropped mid-read while presenting keeps slide ${atSlide.page}; Retry reconnects and shows v8 there`);

    // ── F. the quiet host: Back/Forward to a remote document re-opens CONDITIONALLY (S6) and keeps its position (S2) ─
    await openIn(second, notesUri);
    await until(() => bodyText(second), (t) => t.includes('Line 600 of the notes.'), 'window 2: the remote notes');
    await sleep(300);
    const y = await setScroll(second, 3000);
    assert.ok(y >= 2990, `window 2 scrolls the notes (${y})`);
    await sleep(200);
    await openIn(second, otherUri);
    await until(() => bodyText(second), (t) => t.includes('OTHER DOC'), 'window 2: the other remote document');
    quietSrv.resetStats();
    key(second, 'Left', ['alt']);                      // Back to the notes — unchanged on disk
    await until(() => bodyText(second), (t) => t.includes('Line 600 of the notes.'), 'window 2: Back to the notes');
    await sleep(1200);                                 // a read, if one were coming, would have landed
    const unchanged = quietSrv.stats.byPath[NOTES] || { opens: 0, reads: 0, stats: 0 };
    assert.ok(unchanged.stats >= 1, `Back revalidated the notes with a stat (${unchanged.stats})`);
    assert.strictEqual(unchanged.opens + unchanged.reads, 0,
      `…and, unchanged, read nothing (${unchanged.opens} OPEN, ${unchanged.reads} READ) — no second download`);
    const back = await scrollTop(second);
    assert.ok(Math.abs(back - y) <= 2, `Back restores the remote document's position (${back} ≈ ${y})`);
    console.log(`[ok] F1: Back to an unchanged remote document costs one stat and no read, at its position (${back})`);

    fs.writeFileSync(NOTES, notesText('APPENDED AFTERWARDS\n'));    // the quiet host announces nothing
    key(second, 'Right', ['alt']);
    await until(() => bodyText(second), (t) => t.includes('OTHER DOC'), 'window 2: Forward to the other document');
    quietSrv.resetStats();
    key(second, 'Left', ['alt']);                      // Back to the notes — CHANGED on disk
    await until(() => bodyText(second), (t) => t.includes('APPENDED AFTERWARDS'), 'window 2: Back reads the changed notes', 20000);
    const changed = quietSrv.stats.byPath[NOTES] || { opens: 0, reads: 0 };
    assert.ok(changed.reads >= 1, `a changed remote document is read again (${changed.reads} READ)`);
    await sleep(600);
    const backChanged = await scrollTop(second);
    assert.ok(Math.abs(backChanged - y) <= 2, `…and comes back at its position (${backChanged} ≈ ${y})`);
    console.log(`[ok] F2: Back to a changed remote document reads it again and keeps its position (${backChanged})`);

    // ── G. a remote document's position survives a tab switch and the closing of the tab in front of it (S2) ──
    await openIn(third, notesUri);
    await until(() => bodyText(third), (t) => t.includes('APPENDED AFTERWARDS'), 'window 3: the remote notes');
    await sleep(300);
    const y3 = await setScroll(third, 2500);
    await sleep(200);
    const tabs0 = await tabCount(third);
    key(third, 'T', ['control']);
    await until(() => tabCount(third), (n) => n === tabs0 + 1, 'window 3: a new tab');
    await openIn(third, otherUri);
    await until(() => bodyText(third), (t) => t.includes('OTHER DOC'), 'window 3: the other document in the new tab');
    key(third, 'Tab', ['control', 'shift']);
    await until(() => bodyText(third), (t) => t.includes('APPENDED AFTERWARDS'), 'window 3: back on the notes tab');
    await sleep(600);
    const switched = await scrollTop(third);
    assert.ok(Math.abs(switched - y3) <= 2, `a tab switch restores the remote document's position (${switched} ≈ ${y3})`);
    key(third, 'Tab', ['control']);
    await until(() => bodyText(third), (t) => t.includes('OTHER DOC'), 'window 3: the other tab again');
    key(third, 'W', ['control']);
    await until(() => tabCount(third), (n) => n === tabs0, 'window 3: the other tab closes');
    await until(() => bodyText(third), (t) => t.includes('APPENDED AFTERWARDS'), 'window 3: the notes tab is revealed');
    await sleep(600);
    const revealed = await scrollTop(third);
    assert.ok(Math.abs(revealed - y3) <= 2, `closing the tab in front restores the remote document's position (${revealed} ≈ ${y3})`);
    console.log(`[ok] G: a remote document's position survives a tab switch (${switched}) and a tab close (${revealed})`);

    console.log('[ok] remote-pdf-e2e: slide decks over SFTP — fan-out, a linked show, ordering, torn and refused reads, a drop while presenting, conditional re-open, positions');
  } catch (error) {
    failed = error;
    console.error(error && error.stack || error);
  } finally {
    for (const s of [deckSrv, quietSrv]) {
      try { s.setDelay(null); s.setFault(null); s.onRequest(null); s.destroyConnections(); } catch (_e) { /* closing */ }
      await s.close();
    }
    runtime.cleanup();
    // app.exit, not process.exitCode + app.quit(): Electron does not carry exitCode out through quit(), so a failed run
    // exited 0 (as watch-e2e / fullscreen-e2e already do)
    app.exit(failed ? 1 : 0);
  }
}

run().catch((error) => { console.error(error && error.stack || error); app.exit(1); });
