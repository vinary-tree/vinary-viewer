'use strict';

// End-to-end proof for the in-page-find / scroll-ownership defects (ADR-0032,
// docs/scientific/09-in-page-find-and-scroll-experiments.md).
//
// Why this harness exists alongside test/electron-smoke.js: the smoke MOCKS the vv:open IPC seam, and it
// drives keys with synthetic KeyboardEvents. Neither can reproduce the two bugs under investigation:
//
//   • The rubber band is a COMPOSITOR-path effect. A synthetic `new WheelEvent(...)` is untrusted, so
//     Chromium never runs its default action — nothing scrolls and the test would pass vacuously. Only
//     webContents.sendInputEvent({type:'mouseWheel'}) enters at the browser-process input pipeline and
//     takes the same path a physical wheel does, including scroll-offset snapping.
//   • The dead find shortcut needs the REAL Escape path: React's on-key-down on .vv-find-input. A
//     window-level dispatch would not reach it, so the leak would not be reproduced.
//
// This file requires the real compiled main (dist/main/main.js) from INSIDE an Electron process, exactly
// as test/tree-e2e.js does, so the whole production chain runs against real files on disk.
//
// Run: npm run test:find-e2e       (wraps it in xvfb-run; needs a DISPLAY)
//      xvfb-run -a electron --no-sandbox test/find-e2e.js
//
// Not part of `npm test`: like test:electron and test:tree-e2e, it needs a display and boots a real window.

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
process.env.ELECTRON_OZONE_PLATFORM_HINT = 'x11';
process.env.GDK_BACKEND = 'x11';
process.env.XDG_SESSION_TYPE = 'x11';
delete process.env.WAYLAND_DISPLAY;

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow } = require('electron');

const ROOT = path.resolve(__dirname, '..');
const SCRATCH = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-find-e2e-')));

// VV_FIND_E2E_GPU=1 re-runs WITH hardware acceleration. app.disableHardwareAcceleration() changes the
// scroller's compositing and therefore possibly the scroll-offset quantum the runaway-animator bug depends
// on, so the confound is switchable and REPORTED rather than hidden (docs/scientific/07 §1.2).
const GPU = process.env.VV_FIND_E2E_GPU === '1';
if (!GPU) app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
app.commandLine.appendSwitch('ozone-platform', 'x11');

// ---- fixtures ---------------------------------------------------------------------------------------
// Well over a viewport tall, far under the 256 KiB markdown streaming threshold (vinary.stream.flag), so
// this exercises the BATCH path — the user's actual repro. The multi-word phrase below deliberately
// straddles an inline <code> boundary, which the single-text-node matcher cannot see.
const MD = path.join(SCRATCH, 'scroll-anchor.md');
const PDF = path.join(SCRATCH, 'find.pdf');

// A multi-page PDF with REAL extractable text. test/fixtures/smoke.pdf is a single short page, so it can
// never be taller than the viewport and cannot exercise scrolling at all. Each page emits the phrase
// "quick brown" as TWO separate Tj operators at the same baseline: pdf.js renders one <span> per text run,
// so that phrase is the PDF analogue of a Markdown match straddling an inline element.
function makeTextPdf(pageCount) {
  const content = (i) => [
    'BT', '/F1 14 Tf', '72 720 Td',
    `(Page ${i} paragraph text for the in-page find harness) Tj`,
    '0 -28 Td', '(quick ) Tj', '(brown fox jumps over the lazy dog) Tj',
    '0 -28 Td', `(needle phrase ${i}) Tj`,
    'ET'
  ].join('\n');

  const objs = [];
  const kids = [];
  const pageObjFirst = 3;                                   // 1 = Catalog, 2 = Pages
  for (let i = 0; i < pageCount; i++) kids.push(`${pageObjFirst + i * 2} 0 R`);
  objs.push('<< /Type /Catalog /Pages 2 0 R >>');
  objs.push(`<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pageCount} >>`);
  for (let i = 0; i < pageCount; i++) {
    const streamObj = pageObjFirst + i * 2 + 1;
    objs.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${streamObj} 0 R ` +
              `/Resources << /Font << /F1 ${pageObjFirst + pageCount * 2} 0 R >> >> >>`);
    const body = content(i);
    objs.push(`<< /Length ${body.length} >>\nstream\n${body}\nendstream`);
  }
  objs.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');

  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objs.forEach((body, i) => {
    offsets[i] = Buffer.byteLength(pdf, 'latin1');
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xrefStart = Buffer.byteLength(pdf, 'latin1');
  pdf += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((off) => { pdf += `${String(off).padStart(10, '0')} 00000 n \n`; });
  pdf += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

function writeFixtures() {
  const paras = [];
  paras.push('# Scroll anchor fixture\n');
  for (let i = 0; i < 140; i++) {
    paras.push(`## Section ${i}\n`);
    paras.push(`Paragraph ${i} — the quick brown fox jumps over the lazy dog, repeatedly and at length ` +
               `so that this document is comfortably taller than any plausible viewport.\n`);
  }
  // a match that spans a markup boundary: rendered as "the <code>needle</code> phrase"
  paras.push('A sentence containing the `needle` phrase for cross-node matching.\n');
  // a match that exists ONLY inside math — MathJax emits a hidden assistive-MathML text copy of this
  paras.push('An equation with a unique token: $\\alpha_{zzqqx}$\n');
  fs.writeFileSync(MD, paras.join('\n'));
  fs.writeFileSync(PDF, makeTextPdf(6));
}

require(path.join(ROOT, 'dist', 'main', 'main.js'));   // boots the real app

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForWindow() {
  for (let i = 0; i < 300; i++) {
    const [w] = BrowserWindow.getAllWindows();
    if (w && !w.webContents.isLoading()) return w;
    await sleep(100);
  }
  throw new Error('no renderer window appeared');
}

async function until(wc, expr, pred, label, tries = 150) {
  let last = null;
  for (let i = 0; i < tries; i++) {
    try { last = await wc.executeJavaScript(expr, true); } catch (e) { last = 'ERR ' + e.message; }
    if (pred(last)) return last;
    await sleep(100);
  }
  throw new Error(`timed out waiting for: ${label}\n  last value = ${JSON.stringify(last, null, 2)}`);
}

const evalIn = (wc, src) => wc.executeJavaScript(src, true);
const open = (wc, p) => evalIn(wc, `window.__vvopen(${JSON.stringify(p)})`);

async function sendChord(win, keyCode, modifiers = []) {
  win.focus();
  win.webContents.focus();
  win.webContents.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
  win.webContents.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
  await sleep(60);
}

// A REAL wheel, through the browser-process input pipeline. Negative deltaY scrolls DOWN in Electron's
// convention (matches the proven recipe in test/electron-smoke.js).
async function wheel(win, x, y, deltaY) {
  win.webContents.sendInputEvent({ type: 'mouseMove', x, y });
  win.webContents.sendInputEvent({
    type: 'mouseWheel', x, y, deltaY,
    wheelTicksY: Math.sign(deltaY) * 4,
    hasPreciseScrollingDeltas: true,
    canScroll: true
  });
}

// ---- probes -----------------------------------------------------------------------------------------

// E3 — the crux probe. Distinguishes the two candidate non-termination mechanisms with numbers:
//   overshoot > 0.5  ⇒ max-top (built from ROUNDED scrollHeight/clientHeight) is unreachable  [H1-A]
//   quantum === 0    ⇒ a sub-pixel scrollTop write produces NO movement                       [H1-B]
const E3 = `(() => {
  const c = document.querySelector('.vv-content');
  if (!c) return null;
  const keep = c.scrollTop;
  const max = c.scrollHeight - c.clientHeight;
  c.scrollTop = max;        const reachedMax = c.scrollTop;
  c.scrollTop = 100;        const base = c.scrollTop;
  c.scrollTop = base + 0.4; const q = c.scrollTop - base;
  c.scrollTop = keep;
  return { max, reachedMax, overshoot: max - reachedMax, base, quantum: q,
           dpr: window.devicePixelRatio, clientH: c.clientHeight, scrollH: c.scrollHeight };
})()`;

// Sample the scroller across N consecutive animation frames. If anything is still writing scrollTop, the
// spread exposes it — this is the direct measurement of "moves a few pixels and then snaps back".
const sampleFrames = (n) => `(() => new Promise((resolve) => {
  const c = document.querySelector('.vv-content');
  const xs = [];
  let i = 0;
  const tick = () => {
    xs.push(c.scrollTop);
    if (++i >= ${n}) resolve(xs); else requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}))()`;

const SET_QUERY = (q) => `(() => {
  const i = document.querySelector('.vv-find-input');
  if (!i) return false;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(i, ${JSON.stringify(q)});
  i.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`;

// The REAL close path: React's on-key-down on the input. A window-level dispatch would not reach it.
const ESCAPE_ON_INPUT = `(() => {
  const i = document.querySelector('.vv-find-input');
  if (!i) return false;
  i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  return true;
})()`;

const IN_INPUT = `(() => { const d = window.__vvdb(); return d.ui.input['in-input?']; })()`;
const FIND = `(() => { const d = window.__vvdb(); return d.ui.find; })()`;

const FIND_OPEN = `Boolean(document.querySelector('.vv-find-input'))`;

// Wait for the find bar; if the shortcut is broken, force it open through the DEV toggle so the remaining
// measurements in this scenario still run. Returns whether the shortcut alone would have sufficed.
async function openFindOrForce(win, label) {
  const wc = win.webContents;
  try { await until(wc, FIND_OPEN, (v) => v === true, label, 25); return true; } catch (_) { /* below */ }
  await evalIn(wc, `window.__vvfindtoggle()`);
  await until(wc, FIND_OPEN, (v) => v === true, `${label} (forced via __vvfindtoggle)`, 30);
  return false;
}

async function dumpDiagnostics(wc, label) {
  try {
    const anim = await evalIn(wc, `window.__vvscrollanim ? window.__vvscrollanim() : 'no-hook'`);
    const writers = await evalIn(wc, `window.__vvscrolltrace ? window.__vvscrolltrace().writers() : 'no-hook'`);
    const unmoved = await evalIn(wc,
      `window.__vvscrolltrace ? window.__vvscrolltrace().unmoved().slice(-6) : 'no-hook'`);
    const clamped = await evalIn(wc,
      `window.__vvscrolltrace ? window.__vvscrolltrace().clamped().slice(-6) : 'no-hook'`);
    const frames = await evalIn(wc,
      `window.__vvscrollanimlog ? window.__vvscrollanimlog().entries.slice(-12) : 'no-hook'`);
    console.error(`\n--- diagnostics (${label}) ---`);
    console.error('live animator :', JSON.stringify(anim));
    console.error('writers       :', JSON.stringify(writers, null, 2));
    console.error('unmoved writes:', JSON.stringify(unmoved, null, 2));
    console.error('clamped writes:', JSON.stringify(clamped, null, 2));
    console.error('animator log  :', JSON.stringify(frames, null, 2));
  } catch (e) {
    console.error('diagnostics unavailable:', e.message);
  }
}

// ---- the scenario -----------------------------------------------------------------------------------

// Each keymap opens find with a DIFFERENT key, and the difference is the point: vim's `/` is a bare
// printable character (the class of key the focus-flag leak swallows), while default's Ctrl+F and emacs's
// C-s are chords and survive it. Note vim binds C-f to :nav/page-down (resources/keymaps/vim.edn:16), so
// using Ctrl+F under vim would silently page the document instead of opening find.
const KEYMAPS = {
  vim:     { open: ['/', []], next: ['n', []], prev: ['N', ['shift']] },
  default: { open: ['F', ['control']], next: ['F3', []], prev: ['F3', ['shift']] }
};

async function scenario(win, { file, kind, ready, check, keymap }) {
  const wc = win.webContents;
  const keys = KEYMAPS[keymap];
  console.log(`\n=== ${kind} : ${path.basename(file)} [keymap=${keymap}] ===`);

  await open(wc, file);
  await until(wc, ready, (v) => v === true, `${kind} content rendered`);
  await until(wc, `(() => { const c = document.querySelector('.vv-content');
                            return !!c && c.scrollHeight > c.clientHeight + 200; })()`,
              (v) => v === true, `${kind} taller than the viewport`);

  // this harness must cover the BATCH path — the streaming path is a different mechanism entirely
  const streamed = await evalIn(wc, `Boolean(document.querySelector('.vv-stream-progress'))`);
  check(`${kind}: renders on the batch path (not streamed)`, () => {
    assert.strictEqual(streamed, false, 'fixture must be under the streaming threshold');
  });

  const probe = await evalIn(wc, E3);
  console.log(`  probe E3 (gpu=${GPU}):`, JSON.stringify(probe));

  await evalIn(wc, `window.__vvkeymap(${JSON.stringify(keymap)})`);
  const wantMode = keymap === 'vim' ? 'normal' : 'insert';
  await until(wc, `(() => window.__vvdb().ui.input.mode)()`, (v) => v === wantMode,
              `${keymap} initial input mode`);

  // ---- open find, type, close with the REAL Escape path ---------------------------------------------
  // The first open can already be broken by a leak inherited from an EARLIER scenario in this same run, so
  // fall back to the DEV toggle rather than aborting: a cascade of timeouts would hide every measurement
  // after this point. ASSERT E below is where "does the shortcut work?" is actually judged.
  await sendChord(win, keys.open[0], keys.open[1]);
  await openFindOrForce(win, `${kind} find bar opens`);
  await evalIn(wc, SET_QUERY('the'));
  await until(wc, `${FIND}.count`, (v) => typeof v === 'number', `${kind} find reports a count`);

  await evalIn(wc, ESCAPE_ON_INPUT);
  await until(wc, `Boolean(document.querySelector('.vv-find-input'))`, (v) => v === false,
              `${kind} find bar closes`);

  // ASSERT A — the focus flag must not survive the unmount
  const inInput = await until(wc, IN_INPUT, (v) => v === false || v === true, 'in-input? readable');
  check(`${kind}: ASSERT A — :in-input? is false after Esc-closing find`, () => {
    assert.strictEqual(inInput, false,
      'the find input unmounted while focused; Chromium fires no blur, so a cached flag leaks');
  });

  // ---- drive the animator's worst case, then require it to STOP -------------------------------------
  await evalIn(wc, `window.__vvscrollanimlog && window.__vvscrollanimlog().clear()`);
  await evalIn(wc, `window.__vvscrolltrace && window.__vvscrolltrace().clear()`);
  await sendChord(win, 'End');                     // :nav/scroll-bottom → target == max-top
  await sleep(1500);

  const liveAnim = await evalIn(wc, `window.__vvscrollanim ? window.__vvscrollanim() : 'no-hook'`);
  check(`${kind}: ASSERT B — the scroll animator terminates`, () => {
    assert.strictEqual(liveAnim, null,
      `the easing loop was still chasing a target 1.5s after End: ${JSON.stringify(liveAnim)}`);
  });

  // ---- THE RUBBER BAND: a real wheel must hold its ground ------------------------------------------
  const geom = await evalIn(wc, `(() => {
    const c = document.querySelector('.vv-content');
    const r = c.getBoundingClientRect();
    c.scrollTop = Math.round((c.scrollHeight - c.clientHeight) * 0.6);
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2),
             before: c.scrollTop };
  })()`);
  await sleep(120);

  await wheel(win, geom.x, geom.y, 480);           // positive deltaY scrolls UP (toward the top)
  await sleep(150);
  const afterWheel = await evalIn(wc, `document.querySelector('.vv-content').scrollTop`);

  // ASSERT D first — keeps ASSERT C from passing vacuously on a wheel that did nothing
  check(`${kind}: ASSERT D — the synthesized wheel actually scrolls`, () => {
    assert.ok(Math.abs(afterWheel - geom.before) > 8,
      `wheel moved the view by ${afterWheel - geom.before}px (from ${geom.before}); ` +
      'the input event never reached the compositor, so ASSERT C would be vacuous');
  });

  const samples = await evalIn(wc, sampleFrames(20));
  const spread = Math.max(...samples) - Math.min(...samples);
  const drift = Math.abs(samples[samples.length - 1] - samples[0]);
  check(`${kind}: ASSERT C — the view stays where the user scrolled it`, () => {
    assert.ok(spread < 2 && drift < 2,
      `scroll position was still being rewritten after the wheel: spread=${spread.toFixed(2)}px ` +
      `drift=${drift.toFixed(2)}px over 20 frames; samples=${JSON.stringify(samples)}`);
  });

  // ---- the find shortcut must still work after a close ---------------------------------------------
  // Under vim this is `/`, a BARE PRINTABLE key — precisely what the leaked focus flag swallows.
  await sendChord(win, keys.open[0], keys.open[1]);
  const reopened = await openFindOrForce(win, `${kind} find re-opens`);
  check(`${kind}: ASSERT E — "${keys.open[1].concat(keys.open[0]).join('+')}" re-opens find after an Esc close`,
        () => assert.ok(reopened, 'the find shortcut stopped working after the bar was closed once'));

  await evalIn(wc, SET_QUERY('paragraph'));
  const counted = await until(wc, `${FIND}.count`, (v) => typeof v === 'number' && v > 1,
                              `${kind} more than one match to cycle`);
  // COMMIT the search so the cycle keys are reachable. Under a modal (vim) keymap Enter must return the
  // keyboard to normal mode with the bar still open — otherwise `n`/`N` are bare printable keys typed
  // into the focused query box and can never resolve to :search/next.
  await evalIn(wc, `(() => { const i = document.querySelector('.vv-find-input');
    i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    return true; })()`);
  await sleep(150);
  const committed = await evalIn(wc,
    `!document.activeElement || !document.activeElement.classList.contains('vv-find-input')`);
  if (keymap === 'vim') {
    check(`${kind}: ASSERT F1 — Enter commits the search and releases the keyboard (modal keymap)`, () => {
      assert.ok(committed, 'the query box still holds focus, so vim n/N can never resolve');
    });
  }

  // capture the cursor AFTER the commit — Enter itself may legitimately advance it, and comparing against
  // a pre-commit value would let ASSERT F2 pass on Enter's move rather than on the cycle key's
  const firstIdx = await evalIn(wc, `${FIND}.idx`);
  await sendChord(win, keys.next[0], keys.next[1]);
  let nextIdx = firstIdx;
  try {
    nextIdx = await until(wc, `${FIND}.idx`, (v) => v !== firstIdx, 'cycle advances', 25);
  } catch (_) { /* recorded below */ }
  check(`${kind}: ASSERT F2 — "${keys.next[0]}" cycles matches (${firstIdx} → ${nextIdx} of ${counted})`,
        () => assert.notStrictEqual(nextIdx, firstIdx, 'the find-next binding never reached the resolver'));

  await evalIn(wc, ESCAPE_ON_INPUT);
  await sendChord(win, 'Escape');
  await until(wc, `Boolean(document.querySelector('.vv-find-input'))`, (v) => v === false,
              `${kind} find closes again`);
  const inInput2 = await evalIn(wc, IN_INPUT);
  check(`${kind}: ASSERT G — :in-input? still false after the second close`, () => {
    assert.strictEqual(inInput2, false);
  });

  return probe;
}

// ---- match correctness ------------------------------------------------------------------------------
// The scroll/focus scenario above proves find can be REACHED. This one proves it finds the right things:
// the counter alone could never distinguish "7 matches" from "7 matches highlighted in the wrong place",
// which is why these defects survived the existing smoke suite. window.__vvfind() reports the matched
// text, its geometry and whether it is on screen.

const VVFIND = `window.__vvfind()`;

async function correctnessScenario(win, { file, kind, ready, check, phrase, other }) {
  const wc = win.webContents;
  console.log(`\n=== ${kind} match correctness ===`);

  await evalIn(wc, `window.__vvkeymap('default')`);
  await open(wc, file);
  await until(wc, ready, (v) => v === true, `${kind} content rendered`);
  await sendChord(win, 'F', ['control']);
  await openFindOrForce(win, `${kind} find bar opens`);

  // ---- a phrase that spans a markup boundary ------------------------------------------------------
  await evalIn(wc, SET_QUERY(phrase));
  await sleep(400);                                   // past the debounce
  const spanning = await evalIn(wc, VVFIND);
  check(`${kind}: ASSERT H1 — a phrase spanning a markup boundary matches ("${phrase}")`, () => {
    assert.ok(spanning.count >= 1,
      `no match for a phrase that is split across text nodes (${kind} splits text at every inline ` +
      'element / text run, so a single-text-node matcher finds nothing)');
  });
  check(`${kind}: ASSERT H2 — the highlighted text IS the query`, () => {
    const got = (spanning.matches[0] || {}).text || '';
    assert.strictEqual(got.toLowerCase().replace(/\s+/g, ' ').trim(), phrase.toLowerCase(),
      `highlighted ${JSON.stringify(got)} instead of ${JSON.stringify(phrase)}`);
  });
  check(`${kind}: ASSERT H3 — every collected match is actually painted`, () => {
    assert.strictEqual(spanning.painted, spanning.count,
      `${spanning.painted} Ranges painted for ${spanning.count} matches`);
  });

  // ---- cycling visits every match, brings it on screen, and moves nothing but the content pane ------
  await evalIn(wc, SET_QUERY(other));
  await sleep(400);
  const many = await evalIn(wc, VVFIND);
  check(`${kind}: ASSERT H4 — "${other}" has several matches to cycle`, () => {
    assert.ok(many.count > 2, `only ${many.count} matches`);
  });
  const walk = [];
  for (let i = 0; i < Math.min(many.count, 6); i++) {
    await evalIn(wc, `window.__vvdb() && null`);       // settle a frame
    await sendChord(win, 'F3');
    await sleep(220);
    walk.push(await evalIn(wc, `(() => { const f = ${VVFIND};
      return { idx: f.idx, visible: (f.matches[f.idx - 1] || {}).visible,
               app: document.getElementById('app').scrollTop,
               doc: document.documentElement.scrollTop,
               body: document.body.scrollTop }; })()`));
  }
  check(`${kind}: ASSERT H5 — each cycled match is scrolled on screen`, () => {
    const off = walk.filter((w) => w.visible !== true);
    assert.strictEqual(off.length, 0,
      `${off.length} of ${walk.length} focused matches were off screen: ${JSON.stringify(walk)}`);
  });
  check(`${kind}: ASSERT H6 — cycling scrolls ONLY the content pane`, () => {
    // the exact failure the codebase's own :toc/scroll comment warns about: el.scrollIntoView walks up
    // and scrolls every scrollable ancestor, pushing the app chrome out of the clipped viewport
    const moved = walk.filter((w) => w.app !== 0 || w.doc !== 0 || w.body !== 0);
    assert.strictEqual(moved.length, 0,
      `find scrolled an ancestor of .vv-content: ${JSON.stringify(moved)}`);
  });
  check(`${kind}: ASSERT H7 — the cursor advances on every cycle`, () => {
    const idxs = walk.map((w) => w.idx);
    assert.strictEqual(new Set(idxs).size, idxs.length, `cursor stalled: ${JSON.stringify(idxs)}`);
  });

  // ---- re-opening re-runs the stored query ---------------------------------------------------------
  await sendChord(win, 'F', ['control']);             // close
  await until(wc, FIND_OPEN, (v) => v === false, `${kind} find closes`);
  const cleared = await evalIn(wc, `(() => ${VVFIND}.painted || 0)()`);
  check(`${kind}: ASSERT H8 — closing find un-paints the highlights`, () => {
    assert.ok(!cleared, `${cleared} highlight Ranges survived the close`);
  });
  await sendChord(win, 'F', ['control']);             // re-open
  await openFindOrForce(win, `${kind} find re-opens`);
  await sleep(400);
  const reran = await evalIn(wc, `(() => { const d = window.__vvdb().ui.find; const f = ${VVFIND};
    return { count: d.count, painted: f.painted, query: f.query }; })()`);
  check(`${kind}: ASSERT H9 — re-opening re-runs the query instead of showing a stale count`, () => {
    assert.ok(reran.count > 0, 'the counter came back empty');
    assert.strictEqual(reran.painted, reran.count,
      `counter says ${reran.count} but ${reran.painted} Ranges are painted — a stale number over an ` +
      'unpainted document');
  });

  // ---- a fast typist must not be overtaken by an earlier, slower query ------------------------------
  await evalIn(wc, `(() => {
    const i = document.querySelector('.vv-find-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    for (const q of ['t', 'th', 'the', ${JSON.stringify(other)}]) {
      setter.call(i, q); i.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return true; })()`);
  await sleep(700);
  const raced = await evalIn(wc, `(() => { const d = window.__vvdb().ui.find; const f = ${VVFIND};
    return { dbCount: d.count, query: f.query, count: f.count }; })()`);
  check(`${kind}: ASSERT H10 — the counter reflects the LAST query typed, not the first to resolve`, () => {
    assert.strictEqual(raced.query, other.toLowerCase(),
      `the finder settled on ${JSON.stringify(raced.query)}`);
    assert.strictEqual(raced.dbCount, raced.count,
      `app-db says ${raced.dbCount} matches, the finder holds ${raced.count}`);
  });

  await sendChord(win, 'F', ['control']);
  await until(wc, FIND_OPEN, (v) => v === false, `${kind} find closes at the end`);
}

// ---- run --------------------------------------------------------------------------------------------

async function run() {
  writeFixtures();
  const win = await waitForWindow();
  const wc = win.webContents;
  await until(wc, 'typeof window.__vvdb === "function"', (v) => v === true, 'the __vvdb DEV hook');

  win.show();
  await sleep(200);
  win.focus();
  wc.focus();

  const passed = [];
  const failed = [];
  const check = (name, fn) => {
    try { fn(); passed.push(name); console.log(`  ✓ ${name}`); }
    catch (err) { failed.push({ name, err }); console.log(`  ✗ ${name}\n      ${err.message}`); }
  };

  const MD_READY = `Boolean(document.querySelector('.markdown-body h1'))`;
  const PDF_READY = `Boolean(document.querySelector('.vv-pdf-doc canvas.vv-pdf-canvas'))`;

  // vim first: it is the user's configuration, and `/` `n` `N` are the bare printable keys the focus-flag
  // leak swallows. default second: it proves the fixes are keymap-independent.
  const mdProbe = await scenario(win, { file: MD, kind: 'markdown', check, keymap: 'vim', ready: MD_READY });
  let seen = failed.length;
  if (seen) await dumpDiagnostics(wc, 'markdown/vim');

  const pdfProbe = await scenario(win, { file: PDF, kind: 'pdf', check, keymap: 'vim', ready: PDF_READY });
  if (failed.length > seen) await dumpDiagnostics(wc, 'pdf/vim');
  seen = failed.length;

  await scenario(win, { file: MD, kind: 'markdown', check, keymap: 'default', ready: MD_READY });
  if (failed.length > seen) await dumpDiagnostics(wc, 'markdown/default');
  seen = failed.length;

  // "the needle phrase" renders as `the <code>needle</code> phrase` — three text nodes
  await correctnessScenario(win, { file: MD, kind: 'markdown', check, ready: MD_READY,
                                   phrase: 'the needle phrase', other: 'paragraph' });
  // "quick brown" is emitted as two separate Tj operators, so pdf.js renders it as two <span>s
  await correctnessScenario(win, { file: PDF, kind: 'pdf', check, ready: PDF_READY,
                                   phrase: 'quick brown', other: 'page' });

  // ---- text that exists ONLY in MathJax's hidden screen-reader duplicate must not be found ----------
  await open(wc, MD);
  await until(wc, MD_READY, (v) => v === true, 'markdown back for the math check');
  const mathProbe = await evalIn(wc, `(() => {
    const a = document.querySelector('.vv-content mjx-assistive-mml');
    return { present: Boolean(a), inAssistive: Boolean(a && /zzqqx/.test(a.textContent || '')),
             rawVisible: /\\$\\\\alpha/.test(document.querySelector('.markdown-body').textContent || '') };
  })()`);
  if (mathProbe.inAssistive && !mathProbe.rawVisible) {
    await sendChord(win, 'F', ['control']);
    await openFindOrForce(win, 'find bar for the math check');
    await evalIn(wc, SET_QUERY('zzqqx'));
    await sleep(400);
    const mathHits = await evalIn(wc, `${VVFIND}.count`);
    check('markdown: ASSERT H11 — MathJax\'s hidden assistive-MathML duplicate is not searched', () => {
      assert.strictEqual(mathHits, 0,
        `found ${mathHits} match(es) for text that exists only in the screen-reader copy — the counter ` +
        'would be inflated and cycling would land on an invisible node');
    });
    await sendChord(win, 'F', ['control']);
    await until(wc, FIND_OPEN, (v) => v === false, 'find closes after the math check');
  } else {
    console.log(`  · skipped ASSERT H11 (no assistive MathML to exclude: ${JSON.stringify(mathProbe)})`);
  }
  if (failed.length > seen) await dumpDiagnostics(wc, 'correctness');

  console.log('\n--- environment ---');
  console.log(`hardware acceleration : ${GPU ? 'ON (VV_FIND_E2E_GPU=1)' : 'OFF (disableHardwareAcceleration)'}`);
  console.log(`markdown probe        : ${JSON.stringify(mdProbe)}`);
  console.log(`pdf probe             : ${JSON.stringify(pdfProbe)}`);

  console.log(`\nfind-e2e: ${passed.length} passed, ${failed.length} failed`);
  if (failed.length) {
    const err = new Error(`${failed.length} check(s) failed:\n` +
                          failed.map((f) => `  • ${f.name}\n      ${f.err.message}`).join('\n'));
    throw err;
  }
}

app.whenReady().then(() =>
  run()
    .then(() => { fs.rmSync(SCRATCH, { recursive: true, force: true }); app.exit(0); })
    .catch((err) => {
      console.error('\nfind-e2e FAILED:\n', err && err.stack ? err.stack : err);
      fs.rmSync(SCRATCH, { recursive: true, force: true });
      app.exit(1);
    }));
