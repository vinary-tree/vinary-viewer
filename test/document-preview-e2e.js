'use strict';

// Real-main regression: the continuous Preview Strip renders the document and only navigates it.
process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow } = require('electron');
const { isLiveWindow, assertPainting } = require('./headless-window');
const scratch = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'vv-document-preview-')));
process.env.XDG_CONFIG_HOME = path.join(scratch, 'config');
const runtime = require('./fixtures/hermetic-env').hermeticRuntime(process.env.XDG_CONFIG_HOME);
process.env.VV_DAEMON_EVENTS_DESCRIPTOR = path.join(scratch, 'daemon-events.json');
const doc = path.join(scratch, 'overview.md');
const paragraph = 'A faithful preview keeps exactly the same wrapping and spacing as the document. '.repeat(8);
const markdown = '# Document overview\n\n[Navigation stays here](https://example.com/)\n\n' +
  '![A local figure](figure.svg)\n\n$$x^2 + y^2 = z^2$$\n\n' +
  '```mermaid\ngraph LR\n  A[Start] --> B[Finish]\n```\n\n' +
  '<details><summary>Inert disclosure</summary>Hidden content</details>\n\n' +
  Array.from({ length: 65 }, (_, i) => `## Section ${i}\n\n${paragraph}\n\n`).join('');
fs.writeFileSync(doc, markdown);
fs.writeFileSync(path.join(scratch, 'figure.svg'), '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="80"><rect width="240" height="80" fill="#689"/></svg>');
require('../dist/main/main.js');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const evaluate = (win, js) => win.webContents.executeJavaScript(js, true);
let activeWindow;
async function until(probe, label, timeout = 20000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const value = await probe();
    if (value) return value;
    await sleep(75);
  }
  throw new Error(`Timed out: ${label}`);
}
// Evaluated afresh so route switches cannot leave stale test references behind.
const elements = `const pane = document.querySelector('.vv-document-preview');
  const host = pane?.querySelector('.vv-document-preview-host');
  const shadow = host?.shadowRoot;
  const main = document.querySelector('.vv-content');
  const body = main?.querySelector('.markdown-body');
  const mirror = shadow?.querySelector('.markdown-body');`;
async function click(win, x, y) {
  win.webContents.sendInputEvent({ type: 'mouseDown', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 });
  win.webContents.sendInputEvent({ type: 'mouseUp', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 });
  await sleep(200);
}
const menuRow = label => `Array.from(document.querySelectorAll('.vv-menu-dropdown > .vv-menu-item'))
  .find(row => row.querySelector('.vv-menu-item-label')?.textContent === ${JSON.stringify(label)})`;
const key = (win, value) => evaluate(win, `window.dispatchEvent(new KeyboardEvent('keydown',
  { key: ${JSON.stringify(value)}, bubbles: true, cancelable: true })); true`);
async function closeMenu(win) {
  await key(win, 'Escape');
  await until(() => evaluate(win, `!document.querySelector('.vv-menu-dropdown, .vv-menu-overlay')`), 'menu overlay removed');
}
async function viewMenu(win) {
  await evaluate(win, `Array.from(document.querySelectorAll('.vv-menu-label')).find(n => n.textContent === 'View').click(); true`);
  await until(() => evaluate(win, `Boolean(document.querySelector('.vv-menu-dropdown'))`), 'View menu');
}
async function menuLayout(win, labels) {
  await viewMenu(win);
  const rows = await evaluate(win, `Array.from(document.querySelectorAll('.vv-menu-dropdown > .vv-menu-item')).map(row => {
    const label = row.querySelector('.vv-menu-item-label'), box = row.querySelector('.vv-menu-check');
    const accel = row.querySelector('.vv-menu-item-accel'), rect = row.getBoundingClientRect();
    return { label: label.textContent, left: label.getBoundingClientRect().left, right: rect.right,
      shortcutRight: accel?.getBoundingClientRect().right,
      box: box && { left: box.getBoundingClientRect().left, right: box.getBoundingClientRect().right,
        width: box.offsetWidth, height: box.offsetHeight, border: getComputedStyle(box).borderTopWidth,
        tick: getComputedStyle(box, '::after').content, hidden: box.getAttribute('aria-hidden') },
      role: row.getAttribute('role'), checked: row.getAttribute('aria-checked') };
  })`);
  for (const label of labels) {
    const row = rows.find(r => r.label === label);
    assert.ok(row?.box, `${label} has a checkbox outline in either state`);
    assert.strictEqual(row.role, 'menuitemcheckbox');
    assert.strictEqual(row.box.hidden, 'true', 'the graphic does not duplicate the accessible state');
    assert.strictEqual(row.box.width, 14);
    assert.strictEqual(row.box.height, 14);
    assert.strictEqual(row.box.border, '1px');
    assert.ok(Math.abs(row.box.right - (row.right - 14)) < 1, 'checkbox at the right padding edge');
    assert.strictEqual(row.box.tick !== 'none', row.checked === 'true', 'tick matches accessible state');
  }
  assert.ok(rows.every(r => Math.abs(r.left - rows[0].left) < 1), 'all labels share the left edge');
  const shortcuts = rows.filter(r => r.shortcutRight != null);
  assert.ok(shortcuts.every(r => Math.abs(r.shortcutRight - shortcuts[0].shortcutRight) < 1), 'all shortcuts share a column');
  assert.ok(shortcuts.every(r => !r.box || Math.abs(r.box.left - r.shortcutRight - 8) < 1), '8px between shortcut and box');
  if (process.env.VV_MENU_SHOT) fs.writeFileSync(process.env.VV_MENU_SHOT, (await win.webContents.capturePage()).toPNG());
  await closeMenu(win);
}
async function toggleMenuSetting(win, label) {
  await viewMenu(win);
  const before = await evaluate(win, `(() => { const row = ${menuRow(label)}, b = row.querySelector('.vv-menu-check').getBoundingClientRect();
    return { checked: row.getAttribute('aria-checked'), x: b.left + b.width / 2, y: b.top + b.height / 2 }; })()`);
  await click(win, before.x, before.y);
  await until(() => evaluate(win, `!document.querySelector('.vv-menu-dropdown')`), 'toggle closes menu once');
  await viewMenu(win);
  assert.notStrictEqual(await evaluate(win, `${menuRow(label)}.getAttribute('aria-checked')`), before.checked, `${label} changed once`);
  // Restore by keyboard, navigating only enabled rows as a user would.
  const index = await evaluate(win, `Array.from(document.querySelectorAll('.vv-menu-dropdown > .vv-menu-item')).indexOf(${menuRow(label)})`);
  await key(win, 'Home');
  for (let i = 0; i < index; i++) await key(win, 'ArrowDown');
  await key(win, ' ');
  await until(() => evaluate(win, `!document.querySelector('.vv-menu-dropdown')`), 'keyboard toggle closes menu');
  await viewMenu(win);
  assert.strictEqual(await evaluate(win, `${menuRow(label)}.getAttribute('aria-checked')`), before.checked, `${label} restored by keyboard`);
  await closeMenu(win);
}
async function browserAvailability(win) {
  for (const expanded of [true, false]) {
    if (!expanded) await evaluate(win, `document.querySelector('.vv-preview-collapse').click(); true`);
    const preference = await evaluate(win, `window.__vvdb().ui['preview-strip-visible?']`);
    await evaluate(win, `window.__vvopen(${JSON.stringify(scratch)})`);
    await until(() => evaluate(win, `Boolean(document.querySelector('.vv-fb'))`), 'folder browser');
    assert.ok(await evaluate(win, `!document.querySelector('.vv-preview-rail, .vv-preview-reopen, .vv-document-preview-host')`), 'file browser has no strip or handle');
    await viewMenu(win);
    assert.ok(await evaluate(win, `!(${menuRow('Show Preview Strip')})`), 'preview toggle absent from file-browser menu');
    await closeMenu(win);
    const guarded = await evaluate(win, `(() => {
      const id = cljs.core.keyword('view', 'preview-strip-toggle');
      const ctx = vinary.app.commands.palette_ctx();
      const keyCtx = vinary.input.resolver.build_ctx();
      const offered = cljs.core.clj__GT_js(vinary.app.commands.all_visible(ctx)).some(c => c.id === 'view/preview-strip-toggle');
      const consumed = vinary.app.commands.run(id, keyCtx);
      re_frame.core.dispatch_sync(cljs.core.vector(id));
      return { offered, consumed, preference: window.__vvdb().ui['preview-strip-visible?'] };
    })()`);
    assert.deepStrictEqual(guarded, { offered: false, consumed: false, preference });
    await evaluate(win, `window.__vvopen(${JSON.stringify(doc)})`);
    await until(() => evaluate(win, `document.querySelector('.vv-content .markdown-body h1')?.textContent === 'Document overview'`), 'return to document');
    await until(() => evaluate(win, `Boolean(document.querySelector(${JSON.stringify(expanded ? '.vv-document-preview-host' : '.vv-preview-reopen')}))`), 'document preference restored');
  }
  await evaluate(win, `document.querySelector('.vv-preview-reopen').click(); true`);
  const archive = path.join(scratch, 'browse.tar');
  const packed = await new Promise((resolve, reject) => {
    const pack = require('tar-stream').pack(), chunks = [];
    pack.on('data', chunk => chunks.push(chunk));
    pack.on('error', reject);
    pack.on('end', () => resolve(Buffer.concat(chunks)));
    pack.entry({ name: 'note.md' }, '# Archived document\n\nA previewable file inside an archive.\n');
    pack.finalize();
  });
  fs.writeFileSync(archive, packed);
  const { archiveUri } = require('../src/vinary/main/content_service');
  await evaluate(win, `window.__vvopen(${JSON.stringify(archiveUri(archive, []))})`);
  await until(() => evaluate(win, `Boolean(document.querySelector('.vv-fb'))`), 'archive browser');
  assert.ok(await evaluate(win, `!document.querySelector('.vv-preview-rail, .vv-preview-reopen')`), 'archive listing has no strip or handle');
  await viewMenu(win);
  assert.ok(await evaluate(win, `!(${menuRow('Show Preview Strip')})`), 'archive listing hides the toggle');
  await closeMenu(win);
  const entry = archiveUri(archive, ['note.md']);
  await evaluate(win, `window.__vvopen(${JSON.stringify(entry)})`);
  await until(() => evaluate(win, `document.querySelector('.vv-document-preview-host')?.shadowRoot?.querySelector('h1')?.textContent === 'Archived document'`), 'archive member supports previews');
  console.log('[ok] File-browser exclusion across menu, palette, keyboard dispatch and direct events; preferences survive navigation');
}
async function clickPreview(win, selector, childSelector = null) {
  await evaluate(win, `(() => { ${elements}
    const target = mirror.querySelector(${JSON.stringify(selector)});
    pane.scrollTop += target.getBoundingClientRect().top - pane.getBoundingClientRect().top - 80;
    pane.dispatchEvent(new WheelEvent('wheel')); return true;
  })()`);
  // Both scrolling and content-visibility can change this band over several paints. Click the
  // heading a user can actually see, not the previous frame's estimated placeholder coordinates.
  let settled = 0, previousOffset = null;
  await until(async () => {
    await evaluate(win, `(() => { ${elements}
      const box = mirror.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();
      pane.scrollTop += box.top - pane.getBoundingClientRect().top - 80; return true;
    })()`);
    await sleep(80);
    const position = await evaluate(win, `(() => { ${elements}
      const box = mirror.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();
      return { offset: box.top - pane.getBoundingClientRect().top, height: box.height, viewport: pane.clientHeight,
        ready: !mirror.querySelector(${JSON.stringify(selector)}).hasAttribute('data-vv-preview-placeholder') };
    })()`);
    const { offset, height, viewport } = position;
    settled = position.ready && offset >= 0 && offset + height <= viewport && previousOffset != null && Math.abs(offset - previousOffset) < 1 ? settled + 1 : 0;
    previousOffset = offset;
    return settled >= 2;
  }, `preview target settles: ${selector}`);
  const at = await evaluate(win, `(() => { ${elements}
    const block = mirror.querySelector(${JSON.stringify(selector)});
    const target = ${childSelector ? `block.querySelector(${JSON.stringify(childSelector)})` : 'block'};
    const b = target.getBoundingClientRect();
    return { x: b.left + b.width / 2, y: b.top + b.height / 2,
      paneTop: pane.getBoundingClientRect().top, paneBottom: pane.getBoundingClientRect().bottom };
  })()`);
  assert.ok(at.y >= at.paneTop && at.y <= at.paneBottom, `click target visible: ${selector} ${JSON.stringify(at)}`);
  await click(win, at.x, at.y);
}
async function run() {
  const win = await until(async () => BrowserWindow.getAllWindows().find(w => isLiveWindow(w) && !w.webContents.isLoading()), 'live window');
  activeWindow = win;
  await until(() => evaluate(win, 'typeof window.__vvopen === "function"'), 'DEV bridge');
  await assertPainting(win);
  await evaluate(win, `window.__vvopen(${JSON.stringify(doc)})`);
  await until(() => evaluate(win, `document.querySelector('.vv-content .markdown-body h1')?.textContent === 'Document overview'`), 'main document');
  const ready = `(() => { ${elements} return Boolean(mirror?.querySelector('h2[id$="section-64"]') && mirror.querySelector('h1')?.textContent); })()`;
  await until(() => evaluate(win, ready), 'faithful preview contains rendered content and document placeholders');
  await until(() => evaluate(win, `(() => { ${elements}
    return Boolean(mirror?.querySelector('img')?.complete && mirror.querySelector('mjx-container svg') && mirror.querySelector('.vv-mermaid svg'));
  })()`), 'image, math and diagram in preview');
  const basic = await evaluate(win, `(() => { ${elements}
    return { text: mirror.querySelector('h1').textContent === body.querySelector('h1').textContent,
      children: mirror.children.length === body.children.length, inert: host.inert, hidden: host.getAttribute('aria-hidden'),
      scrollbar: getComputedStyle(pane).scrollbarWidth, gutter: pane.offsetWidth - pane.clientWidth,
      bodies: document.querySelectorAll('.markdown-body').length,
      width: mirror.offsetWidth, mainWidth: body.offsetWidth,
      scale: mirror.getBoundingClientRect().width / body.getBoundingClientRect().width,
      color: getComputedStyle(mirror.querySelector('h1')).color, mainColor: getComputedStyle(body.querySelector('h1')).color };
  })()`);
  assert.ok(basic.text && basic.children && basic.inert && basic.hidden === 'true');
  assert.strictEqual(basic.bodies, 1, 'document selectors, TOC and find see one body');
  assert.strictEqual(basic.scrollbar, 'none');
  assert.strictEqual(basic.gutter, 0, 'no second scrollbar or gutter');
  assert.strictEqual(basic.width, basic.mainWidth, 'same layout width before scaling');
  assert.strictEqual(basic.color, basic.mainColor, 'same theme');
  assert.ok(basic.scale > 0 && basic.scale < 1);
  console.log('[ok] Actual document, figures, math and diagrams; isolated content and no scrollbar');
  await menuLayout(win, ['Show Preview Strip', 'Show Sidebar']);

  // The real wheel scrolls just the strip. Mutations/resizing must not undo manual browsing.
  const before = await evaluate(win, `(() => { ${elements}
    const box = pane.getBoundingClientRect(); return { top: main.scrollTop, x: box.left + box.width / 2, y: box.top + 100 };
  })()`);
  win.webContents.sendInputEvent({ type: 'mouseWheel', x: Math.round(before.x), y: Math.round(before.y), deltaY: -400, deltaX: 0 });
  await sleep(400);
  const browsed = await evaluate(win, `(() => { ${elements} return { main: main.scrollTop, preview: pane.scrollTop }; })()`);
  assert.strictEqual(browsed.main, before.top, 'wheel over strip leaves main scroll alone');
  assert.ok(browsed.preview > 0, 'strip remains scrollable');

  const drag = await evaluate(win, `(() => { ${elements}
    const box = pane.getBoundingClientRect();
    return { x: Math.round(box.left + box.width / 2), y: Math.round(box.top + 100), scroll: pane.scrollTop };
  })()`);
  win.webContents.sendInputEvent({ type: 'mouseDown', x: drag.x, y: drag.y, button: 'left', clickCount: 1 });
  await sleep(100);
  const dragStart = await evaluate(win, `document.querySelector('.vv-content').scrollTop`);
  win.webContents.sendInputEvent({ type: 'mouseMove', x: drag.x, y: drag.y + 150, modifiers: ['leftButtonDown'] });
  await sleep(150);
  assert.ok(await evaluate(win, `document.querySelector('.vv-content').scrollTop > ${dragStart + 50}`), 'drag advances the document');
  assert.ok(await evaluate(win, `Math.abs(document.querySelector('.vv-document-preview').scrollTop - ${drag.scroll}) < 2`), 'automatic reveal is suspended during drag');
  win.webContents.sendInputEvent({ type: 'mouseUp', x: drag.x, y: drag.y + 150, button: 'left', clickCount: 1 });

  for (const section of [30, 4, 60]) {
    const selector = `h2[id$="section-${section}"]`;
    await clickPreview(win, selector);
    await until(() => evaluate(win, `(() => { ${elements}
      const target = body.querySelector(${JSON.stringify(selector)}).getBoundingClientRect(), box = main.getBoundingClientRect();
      return Math.abs(target.top + target.height / 2 - box.top - main.clientHeight / 2) < 10;
    })()`), `click maps to section ${section}`);
  }
  console.log('[ok] Independent wheel browsing and accurate navigation in both directions');
  const wrapping = await evaluate(win, `(() => { ${elements}
    const a = body.querySelector('h2[id$="section-60"]').nextElementSibling;
    const b = mirror.querySelector('h2[id$="section-60"]').nextElementSibling;
    const lines = n => { const range = document.createRange(); range.selectNodeContents(n); return range.getClientRects().length; };
    return { main: lines(a), preview: lines(b) };
  })()`);
  assert.strictEqual(wrapping.preview, wrapping.main, 'paragraphs keep the same line wrapping');

  const tabsBefore = await evaluate(win, 'document.querySelectorAll(".vv-tab").length');
  await clickPreview(win, 'p:first-of-type', 'a');
  assert.strictEqual(await evaluate(win, 'document.querySelectorAll(".vv-tab").length'), tabsBefore, 'preview link does not open a tab');
  await clickPreview(win, 'details', 'summary');
  assert.ok(await evaluate(win, `(() => { ${elements} return !mirror.querySelector('details').open && !body.querySelector('details').open; })()`), 'preview disclosure cannot toggle');
  await evaluate(win, `(() => { ${elements} body.querySelector('details').open = true; return true; })()`);
  await until(() => evaluate(win, `(() => { ${elements} return mirror.querySelector('details').open; })()`), 'main disclosure change mirrored');

  // Main scrolling follows its matching block, even though offscreen intrinsic heights differ.
  await evaluate(win, `(() => { ${elements} main.scrollTop = main.scrollHeight; return true; })()`);
  await until(() => evaluate(win, `(() => { ${elements}
    const a = pane.querySelector('.vv-preview-viewport').getBoundingClientRect(), b = pane.getBoundingClientRect();
    return a.top >= b.top - 2 && a.bottom <= b.bottom + 2 && pane.scrollTop > 0;
  })()`), 'main navigation reveals viewport outline');
  win.setSize(1150, 800);
  await sleep(400);
  assert.ok(await evaluate(win, `(() => { ${elements} return mirror.offsetWidth === body.offsetWidth; })()`), 'resize keeps matching layout');
  await evaluate(win, `document.documentElement.style.setProperty('--vv-font-size', '18px'); true`);
  await sleep(300);
  assert.ok(await evaluate(win, `(() => { ${elements} return getComputedStyle(mirror).fontSize === getComputedStyle(body).fontSize; })()`), 'font preferences inherit');

  // Switching facets tears down the mirror and keeps the original source map.
  await evaluate(win, 'window.__vvsource()');
  await until(() => evaluate(win, `Boolean(document.querySelector('.cm-editor') && document.querySelector('.vv-preview-minimap-canvas') && !document.querySelector('.vv-document-preview'))`), 'source map');
  await evaluate(win, 'window.__vvsource()');
  await until(() => evaluate(win, `(() => { ${elements} return Boolean(mirror?.querySelector('h2[id$="section-64"]')); })()`), 'preview restored');
  await evaluate(win, `document.querySelector('.vv-preview-collapse').click(); true`);
  await until(() => evaluate(win, `!document.querySelector('.vv-document-preview') && Boolean(document.querySelector('.vv-preview-reopen'))`), 'collapsed mirror disposed');
  await evaluate(win, `document.querySelector('.vv-preview-reopen').click(); true`);
  await until(() => evaluate(win, `(() => { ${elements} return Boolean(mirror?.querySelector('h2[id$="section-64"]')); })()`), 'reopened mirror');
  console.log('[ok] Inert links/controls, viewport following, resize/fonts, source switch and collapse');

  // Same-path live refresh must replace the mirror, then the progressive pipeline must also feed it.
  fs.appendFileSync(doc, '\n\n## Live refresh marker\n');
  await until(() => evaluate(win, `(() => { ${elements} return Boolean(mirror?.querySelector('h2[id$="live-refresh-marker"]')); })()`), 'live refresh');
  const streamed = path.join(scratch, 'streamed.md');
  fs.writeFileSync(streamed, '# Streamed overview\n\n' + Array.from({ length: 750 }, (_, i) => `## Stream ${i}\n\n${paragraph}\n\n`).join(''));
  await evaluate(win, `window.__vvopen(${JSON.stringify(streamed)})`);
  await until(() => evaluate(win, `(() => { ${elements} return body?.classList.contains('vv-streamed') && Boolean(mirror?.querySelector('h2[id$="stream-749"]')); })()`), 'streamed preview indexed', 45000);
  await clickPreview(win, 'h2[id$="stream-500"]');
  await until(() => evaluate(win, `(() => { ${elements}
    const a = body.querySelector('h2[id$="stream-500"]').getBoundingClientRect(), b = main.getBoundingClientRect();
    return a.top >= b.top && a.bottom <= b.bottom;
  })()`), 'streamed preview target');
  const chunks = await evaluate(win, `(() => { ${elements}
    return { total: mirror.children.length, rendered: [...mirror.children].filter(n => !n.hasAttribute('data-vv-preview-placeholder')).length,
      evicted: mirror.querySelector('h1').textContent === '' };
  })()`);
  assert.ok(chunks.total > 1000 && chunks.rendered < 150 && chunks.evicted, `bounded preview band: ${JSON.stringify(chunks)}`);
  if (process.env.VV_PREVIEW_SHOT) fs.writeFileSync(process.env.VV_PREVIEW_SHOT, (await win.webContents.capturePage()).toPNG());
  console.log('[ok] Live refresh and streamed document navigation');

  const huge = path.join(scratch, 'huge.md');
  fs.writeFileSync(huge, '<!--' + ' '.repeat(5 * 1024 * 1024) + '-->\n\n# Large document\n\n' + markdown);
  await evaluate(win, `window.__vvopen(${JSON.stringify(huge)})`);
  await until(() => evaluate(win, `Boolean(document.querySelector('.vv-content .markdown-body h1')) && document.querySelector('.vv-content')?.dataset.docKey === ${JSON.stringify(huge)}`), 'large document loaded', 45000);
  assert.ok(await evaluate(win, `!document.querySelector('.vv-document-preview-host') && Boolean(document.querySelector('.vv-preview-reopen'))`), 'large document has no mounted preview');
  await evaluate(win, `document.querySelector('.vv-preview-reopen').click(); true`);
  await until(() => evaluate(win, `(() => { ${elements} return mirror?.textContent.includes('Large document'); })()`), 'large document opt-in');
  await evaluate(win, `document.querySelector('.vv-preview-collapse').click(); true`);
  await evaluate(win, `window.__vvopen(${JSON.stringify(doc)})`);
  await until(() => evaluate(win, `Boolean(document.querySelector('.vv-document-preview-host'))`), 'small document default is preserved');
  console.log('[ok] Very large documents start unmounted; opt-in leaves small-document defaults alone');
  await browserAvailability(win);
  win.setSize(1400, 1000);
  await toggleMenuSetting(win, 'Show Preview Strip');
  await toggleMenuSetting(win, 'Show Sidebar');
  const pdf = path.join(scratch, 'menu.pdf');
  fs.writeFileSync(pdf, require('./fixtures/pdf-gen').makeTextPdf(2));
  await evaluate(win, `window.__vvopen(${JSON.stringify(pdf)})`);
  await until(() => evaluate(win, `Boolean(document.querySelector('.vv-pdf-doc canvas'))`), 'PDF menu fixture');
  for (const label of ['Invert PDF', 'Reflow Text']) await toggleMenuSetting(win, label);
  await evaluate(win, `window.__vvslides('layout', 'slides')`);
  await until(() => evaluate(win, `Boolean(document.querySelector('.vv-pdf-slides'))`), 'slides menu fixture');
  await toggleMenuSetting(win, 'Show Slide Notes');
  for (const theme of ['spacemacs-dark', 'spacemacs-light']) {
    await evaluate(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword('theme', 'set'), ${JSON.stringify(theme)})); true`);
    await menuLayout(win, ['Show Sidebar', 'Show Preview Strip', 'Show Slide Notes', 'Invert PDF', 'Reflow Text']);
  }
  console.log('[ok] All five boxed toggles activate with pointer/keyboard; labels, shortcuts and states align in both themes');
}
const timeout = setTimeout(() => { console.error('document-preview-e2e timed out'); app.exit(1); }, 120000);
app.whenReady().then(run).then(() => {
  clearTimeout(timeout);
  runtime.cleanup();
  fs.rmSync(scratch, { recursive: true, force: true });
  app.exit(0);
}).catch(async error => {
  console.error(error.stack || error);
  if (activeWindow && !activeWindow.isDestroyed()) {
    console.error(await evaluate(activeWindow, `(() => { ${elements}
      const rect = n => n ? { top: n.getBoundingClientRect().top, bottom: n.getBoundingClientRect().bottom, height: n.getBoundingClientRect().height } : null;
      return { main: rect(main), scroll: main?.scrollTop, total: main?.scrollHeight,
        source: rect(body?.querySelector('h2[id$="stream-500"]')), preview: rect(mirror?.querySelector('h2[id$="stream-500"]')),
        pane: rect(pane), previewScroll: pane?.scrollTop, marker: rect(pane?.querySelector('.vv-preview-viewport')) };
    })()`));
    fs.writeFileSync('/tmp/vv-document-preview-failure.png', (await activeWindow.webContents.capturePage()).toPNG());
  }
  runtime.cleanup();
  fs.rmSync(scratch, { recursive: true, force: true });
  app.exit(1);
});
