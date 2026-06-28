'use strict';

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
process.env.ELECTRON_OZONE_PLATFORM_HINT = 'x11';
process.env.GDK_BACKEND = 'x11';
process.env.XDG_SESSION_TYPE = 'x11';
delete process.env.WAYLAND_DISPLAY;

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { app, BrowserWindow, ipcMain } = require('electron');

const ROOT = path.resolve(__dirname, '..');
const INDEX = path.join(ROOT, 'resources', 'public', 'index.html');
const PRELOAD = path.join(ROOT, 'resources', 'preload.js');
const tempDirs = [];

app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
app.commandLine.appendSwitch('ozone-platform', 'x11');

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function cleanupTempDirs() {
  for (const dir of tempDirs.splice(0)) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

function svgDiagram({ width, height, fontSize, label, fill }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" height="${height}px" preserveAspectRatio="none" ` +
    `style="width:${width}px;height:${height}px;background:#FFFFFF;" viewBox="0 0 ${width} ${height}" ` +
    `width="${width}px"><rect width="${width}" height="${height}" fill="${fill}"/>` +
    `<text x="${width / 2}" y="${height / 2}" font-size="${fontSize}" fill="#111827" ` +
    `text-anchor="middle">${label}</text></svg>`;
}

function createLocalSvgScrollFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-svg-scroll-'));
  const diagramsDir = path.join(dir, 'diagrams');
  tempDirs.push(dir);
  fs.mkdirSync(diagramsDir);

  const diagrams = [
    { file: 'wide.svg', width: 1461, height: 242, fontSize: 14, label: 'wide local svg', fill: '#DBEAFE' },
    { file: 'tall.svg', width: 563, height: 731, fontSize: 12, label: 'tall local svg', fill: '#DCFCE7' },
    { file: 'very-wide.svg', width: 3269, height: 415, fontSize: 14, label: 'very wide local svg', fill: '#FEF3C7' }
  ];
  for (const diagram of diagrams) {
    fs.writeFileSync(path.join(diagramsDir, diagram.file), svgDiagram(diagram));
  }

  const filler = Array.from({ length: 16 }, (_value, index) =>
    `Paragraph ${index + 1}. This gives the local SVG scroll test enough height.`
  ).join('\n\n');
  const text = `# Local SVG Scroll\n\n${filler}\n\n` +
    `![Wide](diagrams/wide.svg)\n\n## Middle\n\n${filler}\n\n` +
    `![Tall](diagrams/tall.svg)\n\n## More\n\n${filler}\n\n` +
    `![Very wide](diagrams/very-wide.svg)\n\n## End\n\n${filler}`;
  const docPath = path.join(dir, 'local-svg-scroll.md');
  fs.writeFileSync(docPath, text);
  return { docPath, text };
}

async function waitFor(predicate, label, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;

  while (Date.now() < deadline) {
    try {
      const value = await predicate();
      if (value) {
        return value;
      }
    } catch (err) {
      lastError = err;
    }
    await delay(50);
  }

  const suffix = lastError ? `: ${lastError.message}` : '';
  throw new Error(`Timed out waiting for ${label}${suffix}`);
}

async function evalIn(win, source) {
  return win.webContents.executeJavaScript(source, true);
}

async function dispatchWindowKey(win, key, opts = {}) {
  return evalIn(win, `(() => {
    const event = new KeyboardEvent('keydown', {
      key: ${JSON.stringify(key)},
      altKey: ${Boolean(opts.altKey)},
      ctrlKey: ${Boolean(opts.ctrlKey)},
      shiftKey: ${Boolean(opts.shiftKey)},
      metaKey: ${Boolean(opts.metaKey)},
      bubbles: true,
      cancelable: true
    });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  })()`);
}

async function dispatchMenuKey(win, key) {
  return evalIn(win, `(() => {
    const menu = document.querySelector('.vv-ctx-menu');
    if (!menu) {
      return { handled: false, missing: true };
    }
    menu.focus();
    const event = new KeyboardEvent('keydown', {
      key: ${JSON.stringify(key)},
      bubbles: true,
      cancelable: true
    });
    menu.dispatchEvent(event);
    return {
      handled: event.defaultPrevented,
      focusedLabel: document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() || null
    };
  })()`);
}

async function hoverMenuItem(win, label) {
  return evalIn(win, `(() => {
    const label = ${JSON.stringify(label)};
    const item = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item'))
      .find((node) => node.textContent.includes(label));
    if (!item) {
      return false;
    }
    const rect = item.getBoundingClientRect();
    item.dispatchEvent(new MouseEvent('mouseover', {
      bubbles: true,
      cancelable: true,
      clientX: rect.left + 8,
      clientY: rect.top + 8
    }));
    return true;
  })()`);
}

async function sendChord(win, keyCode, modifiers = []) {
  win.focus();
  win.webContents.focus();
  win.webContents.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
  win.webContents.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
}

function installIpc(state) {
  ipcMain.on('vv:settings-request', (event) => {
    event.sender.send('vv:settings', '');
  });
  ipcMain.on('vv:keymap-request', (event) => {
    event.sender.send('vv:keymap', null);
  });
  ipcMain.on('vv:grammars-request', (event) => {
    event.sender.send('vv:grammars', '');
  });
  ipcMain.on('vv:app-info-request', (event) => {
    event.sender.send('vv:app-info', {
      name: 'vinary-viewer',
      version: 'smoke-test',
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      node: process.versions.node
    });
  });
  ipcMain.on('vv:open-dialog', () => {
    state.openDialogs += 1;
  });
  ipcMain.on('vv:open', (event, filePath) => {
    const payload = state.contentByPath.get(filePath);
    if (payload) {
      event.sender.send('vv:content', { ...payload, stamp: Date.now() });
    } else {
      state.openedPaths.push(filePath);
    }
  });
  ipcMain.on('vv:pdf-show', (_event, payload) => {
    state.pdfShow = payload;
  });
  ipcMain.on('vv:pdf-bounds', (_event, payload) => {
    state.pdfBounds = payload;
  });
  ipcMain.on('vv:pdf-hide', () => {
    state.pdfHidden = true;
  });
  ipcMain.on('vv:http-show', (_event, payload) => {
    state.httpShow = payload;
    state.httpVisible = true;
  });
  ipcMain.on('vv:http-bounds', (_event, payload) => {
    state.httpBounds = payload;
  });
  ipcMain.on('vv:http-hide', () => {
    state.httpHidden = true;
    state.httpVisible = false;
  });
  ipcMain.handle('vv:http-snapshot', () => state.snapshotDataUrl);
  ipcMain.on('vv:watch-assets', () => {});
  ipcMain.handle('vv:complete-path', (_event, input) => ({
    input, dir: null, target: null, 'exists?': false, 'dir?': false, entries: []
  }));
  ipcMain.on('vv:context-open-link', () => {});
  ipcMain.on('vv:context-open-link-new-tab', () => {});
  ipcMain.on('vv:clipboard-write', (_event, text) => {
    state.lastCopiedText = text;
  });
}

async function main() {
  const state = {
    openDialogs: 0,
    pdfShow: null,
    pdfBounds: null,
    pdfHidden: false,
    httpHidden: false,
    httpVisible: false,
    httpShow: null,
    httpBounds: null,
    // a minimal valid 1x1 PNG, returned by the mocked vv:http-snapshot so web-host can freeze the page
    snapshotDataUrl: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
    lastCopiedText: null,
    openedPaths: [],
    contentByPath: new Map()
  };

  installIpc(state);

  await app.whenReady();

  const win = new BrowserWindow({
    width: 1000,
    height: 700,
    show: false,
    paintWhenInitiallyHidden: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: PRELOAD
    }
  });

  win.webContents.on('render-process-gone', (_event, details) => {
    throw new Error(`Renderer process gone: ${details.reason}`);
  });
  win.webContents.on('console-message', (details) => {
    const level = details.level;
    if (level >= 2 || level === 'warning' || level === 'error') {
      console.error(
        `[renderer:${level}] ${details.message} (${details.sourceId}:${details.lineNumber || details.line})`
      );
    }
  });
  win.webContents.on('did-fail-load', (_event, code, description, url) => {
    throw new Error(`Renderer failed to load ${url}: ${code} ${description}`);
  });

  await win.loadFile(INDEX);

  await waitFor(
    () => evalIn(win, `Boolean(window.__vvdb && document.querySelector('.vv-menubar') && document.querySelector('.vv-tab-new'))`),
    'renderer boot'
  );
  console.log('[ok] renderer booted');

  const tenxVisible = await evalIn(win, `Boolean(window.__vvdb().ui['re-frame-10x-open?'])`);
  assert.strictEqual(tenxVisible, false, 're-frame-10x must be hidden by default');
  console.log('[ok] re-frame-10x hidden by default');

  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(
    () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      return ui.menu === 'Settings' && ui['menu-focus'] === 0;
    })()`),
    'Alt+S Settings menu focus'
  );
  await dispatchWindowKey(win, 'ArrowDown');
  await waitFor(
    () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      return ui.menu === 'Settings' && ui['menu-focus'] === 1;
    })()`),
    'Settings menu ArrowDown focus'
  );
  await dispatchWindowKey(win, 'ArrowRight');
  await waitFor(
    () => evalIn(win, `(() => {
      const ui = window.__vvdb().ui;
      return ui['menu-submenu'] === 'Key Bindings' && ui['menu-submenu-focus'] === 0;
    })()`),
    'Settings submenu ArrowRight focus'
  );
  await dispatchWindowKey(win, 'Escape');
  await waitFor(
    () => evalIn(win, `window.__vvdb().ui.menu == null`),
    'menu Escape close'
  );
  console.log('[ok] Alt access keys and menu arrows work');

  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(
    () => evalIn(win, `window.__vvdb().ui.menu === 'Settings'`),
    'Settings menu reopen for pointer submenu checks'
  );
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-menu-dropdown')?.textContent.includes('Theme')`),
    'Settings menu dropdown rendered'
  );
  assert.strictEqual(await hoverMenuItem(win, 'Theme'), true, 'Theme submenu row must exist');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-menu-subdropdown')?.textContent.includes('Spacemacs Dark')`),
    'Theme submenu pointer open'
  );
  assert.strictEqual(await hoverMenuItem(win, 'Key Bindings'), true, 'Key Bindings submenu row must exist');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-menu-subdropdown')?.textContent.includes('Customize')`),
    'Key Bindings submenu pointer open'
  );
  await dispatchWindowKey(win, 'Escape');
  await waitFor(
    () => evalIn(win, `window.__vvdb().ui.menu == null`),
    'Settings pointer submenu Escape close'
  );
  console.log('[ok] Settings pointer submenus open');

  // Fix 5 — the always-visible zoom bar + the View ▸ Fit submenu
  const zoomBar = await evalIn(win, `(() => {
    const i = document.querySelector('.vv-bottombar .vv-zoom-input');
    return { present: Boolean(i), value: i ? i.value : null,
             buttons: document.querySelectorAll('.vv-bottombar .vv-zoom-btn').length };
  })()`);
  assert.strictEqual(zoomBar.present, true, 'the zoom bar must be present in every view');
  assert.strictEqual(zoomBar.buttons, 2, 'the zoom bar must have − and + buttons');
  assert.ok(/^\d+$/.test(zoomBar.value || ''), 'the zoom field must show a numeric percentage');
  await dispatchWindowKey(win, 'v', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'View'`), 'View menu open');
  await waitFor(() => evalIn(win, `document.querySelector('.vv-menu-dropdown')?.textContent.includes('Fit')`), 'View dropdown rendered');
  assert.strictEqual(await hoverMenuItem(win, 'Fit'), true, 'View menu must have a Fit submenu');
  await waitFor(() => evalIn(win, `(() => { const d = document.querySelector('.vv-menu-subdropdown');
    return Boolean(d) && d.textContent.includes('Fit Width') && d.textContent.includes('Fit Page'); })()`),
    'Fit submenu lists Fit Width / Fit Page');
  await dispatchWindowKey(win, 'Escape');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu == null`), 'View menu closed');
  console.log('[ok] zoom bar present + View ▸ Fit submenu works');

  // PDF — rendered IN-DOM via pdf.js (parity with markdown: canvas + selectable text layer + find +
  // copy), NOT the retired native WebContentsView. Bytes are streamed on vv:content.
  const pdfFixture = path.join(ROOT, 'test', 'fixtures', 'smoke.pdf');
  win.webContents.send('vv:content', {
    path: pdfFixture,
    kind: 'pdf',
    bytes: new Uint8Array(fs.readFileSync(pdfFixture)),
    stamp: Date.now()
  });
  await waitFor(
    () => evalIn(win, `(() => {
      const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas');
      return Boolean(c) && c.getBoundingClientRect().width > 0 && c.getBoundingClientRect().height > 0;
    })()`),
    'PDF canvas rendered in the DOM', 15000
  );
  await waitFor(
    () => evalIn(win, `document.querySelectorAll('.vv-pdf-text span').length > 0`),
    'PDF text layer spans', 15000
  );
  const pdfLayout = await evalIn(win, `(() => {
    const canvas = document.querySelector('.vv-pdf-canvas');
    const span = Array.from(document.querySelectorAll('.vv-pdf-text span')).find(s => /Smoke|Vinary/.test(s.textContent))
                 || document.querySelector('.vv-pdf-text span');
    const cr = canvas.getBoundingClientRect();
    const sr = span.getBoundingClientRect();
    return {
      inRenderer: Boolean(document.querySelector('.vv-pdf-doc')),
      noNativeHost: !document.querySelector('.vv-pdf-host'),
      canvasW: Math.round(cr.width),
      textOverlapsCanvas: sr.left < cr.right && sr.right > cr.left && sr.top < cr.bottom && sr.bottom > cr.top
    };
  })()`);
  assert.strictEqual(pdfLayout.inRenderer, true, 'PDF must render in the in-renderer pdf-view (.vv-pdf-doc)');
  assert.strictEqual(pdfLayout.noNativeHost, true, 'the native PDF host (.vv-pdf-host) must be retired');
  assert.ok(pdfLayout.canvasW > 0, 'PDF canvas must have a visible width');
  assert.strictEqual(pdfLayout.textOverlapsCanvas, true, 'PDF text layer must align over the canvas');
  console.log('[ok] PDF renders in-DOM: canvas + aligned text layer (no native view)');

  // selection → Ctrl+C copies the PDF text (copy parity with markdown/source)
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const layer = document.querySelector('.vv-pdf-text');
    const range = document.createRange();
    range.selectNodeContents(layer);
    const sel = window.getSelection();
    sel.removeAllRanges(); sel.addRange(range);
    return sel.toString();
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C must be handled for a PDF selection');
  await waitFor(() => state.lastCopiedText && state.lastCopiedText.includes('Vinary'), 'PDF selection Ctrl+C clipboard write');
  console.log('[ok] PDF text selection copies via Ctrl+C');

  // in-page find covers the PDF text layer (materialized on find)
  await evalIn(win, `window.getSelection().removeAllRanges()`);
  await dispatchWindowKey(win, 'f', { ctrlKey: true });
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'find bar opens over PDF');
  await evalIn(win, `(() => {
    const i = document.querySelector('.vv-find-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'Smoke'); i.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.find.count > 0`), 'PDF find matches', 8000);
  await evalIn(win, `(() => { const i = document.querySelector('.vv-find-input');
    i && i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
  console.log('[ok] in-page find matches PDF text');

  // Fix 2 — the PDF pane is edge-to-edge (drops the 45px document reading gutter)
  const pdfEdge = await evalIn(win, `(() => {
    const c = document.querySelector('.vv-content');
    return { flush: c.classList.contains('vv-content-pdf-flush'), pad: getComputedStyle(c).paddingLeft };
  })()`);
  assert.strictEqual(pdfEdge.flush, true, 'PDF content pane must be edge-to-edge (.vv-content-pdf-flush)');
  assert.strictEqual(pdfEdge.pad, '0px', 'edge-to-edge PDF pane must drop the reading gutter');
  console.log('[ok] PDF pane is edge-to-edge');

  // Fix 7 — switching away from the PDF tab and back must re-render. pdf.js detaches the byte buffer on
  // load; we now clone it, so the cached bytes survive a remount. Open a blank tab (PDF unmounts), close
  // it (PDF tab reactivates → remount from the cached bytes), and assert the canvas re-renders.
  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `!document.querySelector('.vv-pdf-canvas')`), 'PDF unmounts when switched away');
  await sendChord(win, 'W', ['control']);
  await waitFor(
    () => evalIn(win, `(() => { const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas'); return Boolean(c) && c.getBoundingClientRect().width > 0; })()`),
    'PDF re-renders after returning to its tab', 15000);
  console.log('[ok] PDF re-renders after switching tabs away and back');

  const dialogsBefore = state.openDialogs;
  await sendChord(win, 'O', ['control', 'shift']);
  await waitFor(() => state.openDialogs > dialogsBefore, 'Ctrl+Shift+O dialog request');
  const openMode = await evalIn(win, `String(window.__vvdb().ui['open-dialog-mode'])`);
  assert.strictEqual(openMode, 'new-tab', 'Ctrl+Shift+O must request the new-tab open mode');
  console.log('[ok] Ctrl+Shift+O works from preview content');

  await sendChord(win, 'L', ['control']);
  await waitFor(
    () => evalIn(win, `document.activeElement === document.querySelector('.vv-uri-input')`),
    'Ctrl+L URI input focus'
  );
  const uriFocus = await evalIn(win, `(() => {
    const input = document.querySelector('.vv-uri-input');
    return {
      active: document.activeElement === input,
      valueLength: input?.value.length ?? -1,
      selectionStart: input?.selectionStart ?? -1,
      selectionEnd: input?.selectionEnd ?? -1
    };
  })()`);
  assert.strictEqual(uriFocus.active, true, 'Ctrl+L must focus the URI input');
  assert.strictEqual(uriFocus.selectionStart, 0, 'Ctrl+L must select from the start of the URI');
  assert.strictEqual(uriFocus.selectionEnd, uriFocus.valueLength, 'Ctrl+L must select the full URI');
  console.log('[ok] Ctrl+L focuses and selects the URI input');

  const tabCountBefore = await evalIn(win, `document.querySelectorAll('.vv-tab').length`);
  await sendChord(win, 'T', ['control']);
  await waitFor(
    () => evalIn(win, `document.querySelectorAll('.vv-tab').length > ${tabCountBefore}`),
    'Ctrl+T new tab'
  );
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`),
    'blank tab view'
  );
  console.log('[ok] Ctrl+T opens a blank tab');

  const badgeSvg = encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="92" height="20" role="img">' +
      '<rect width="92" height="20" fill="#2563eb"/>' +
      '<text x="46" y="14" font-size="11" fill="#fff" text-anchor="middle">badge</text>' +
    '</svg>'
  );
  const featureDocPath = path.join(ROOT, 'test', 'fixtures', 'markdown-features.md');
  state.contentByPath.set(featureDocPath, {
    path: featureDocPath,
    kind: 'markdown',
    text: '# Markdown Features\n\n' +
      `![One](data:image/svg+xml,${badgeSvg}) ![Two](data:image/svg+xml,${badgeSvg})\n\n` +
      'Copyable preview text lives here.\n\n' +
      'Inline math $`x^2`$ and display math:\n\n$$\n\\frac{a}{b}\n$$\n\n' +
      '```mermaid\nflowchart LR\n  A[Start] --> B[Done]\n```\n',
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [featureDocPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Markdown Features'`),
    'markdown feature fixture'
  );
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-math-inline mjx-container svg'))`),
    'inline MathJax SVG'
  );
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-math-display mjx-container svg'))`),
    'display MathJax SVG'
  );
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-mermaid svg'))`),
    'Mermaid SVG'
  );
  const markdownFeatureLayout = await evalIn(win, `(() => {
    const badges = Array.from(document.querySelectorAll('.markdown-body p:first-of-type img'));
    const rects = badges.map((img) => img.getBoundingClientRect());
    return {
      badgeCount: badges.length,
      sameRow: rects.length === 2 && Math.abs(rects[0].top - rects[1].top) < 2,
      secondAfterFirst: rects.length === 2 && rects[1].left > rects[0].left,
      mermaidText: document.querySelector('.vv-mermaid')?.textContent || ''
    };
  })()`);
  assert.strictEqual(markdownFeatureLayout.badgeCount, 2, 'badge fixture must render two images');
  assert.strictEqual(markdownFeatureLayout.sameRow, true, 'badge images must stay on one row');
  assert.strictEqual(markdownFeatureLayout.secondAfterFirst, true, 'badge images must flow horizontally');
  assert.ok(markdownFeatureLayout.mermaidText.includes('Start'), 'Mermaid output should contain diagram labels');
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const walker = document.createTreeWalker(document.querySelector('.markdown-body'), NodeFilter.SHOW_TEXT);
    let node = null;
    while ((node = walker.nextNode())) {
      const index = node.nodeValue.indexOf('Copyable preview text');
      if (index >= 0) {
        const range = document.createRange();
        range.setStart(node, index);
        range.setEnd(node, index + 'Copyable preview text'.length);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        return true;
      }
    }
    return false;
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C must be handled for preview selections');
  await waitFor(() => state.lastCopiedText === 'Copyable preview text', 'preview Ctrl+C clipboard write');
  console.log('[ok] Markdown badges, MathJax, Mermaid, and preview copy work');

  const sourceDocPath = path.join(ROOT, 'test', 'fixtures', 'source-copy.toml');
  state.contentByPath.set(sourceDocPath, {
    path: sourceDocPath,
    kind: 'source',
    text: '[package]\nname = "demo"\n',
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [sourceDocPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-source .cm-line')?.textContent.includes('[package]')`),
    'source copy fixture'
  );
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const line = Array.from(document.querySelectorAll('.vv-source .cm-line'))
      .find((node) => node.textContent.includes('name = "demo"'));
    if (!line) {
      return false;
    }
    const range = document.createRange();
    range.selectNodeContents(line);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    return true;
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'C', { ctrlKey: true, shiftKey: true }), true,
    'Ctrl+Shift+C must be handled for source selections');
  await waitFor(() => state.lastCopiedText === 'name = "demo"', 'source Ctrl+Shift+C clipboard write');
  console.log('[ok] source Ctrl+Shift+C copies selected text');

  const scrollDocPath = path.join(ROOT, 'test', 'fixtures', 'scroll-jank.md');
  const scrollSvg = encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="280" viewBox="0 0 640 280">' +
      '<rect width="640" height="280" fill="#1c1f24"/>' +
      '<text x="320" y="150" font-size="40" fill="#ffffff" text-anchor="middle">diagram</text>' +
    '</svg>'
  );
  const filler = Array.from({ length: 18 }, (_value, index) =>
    `Paragraph ${index + 1}. This line gives the preview enough height for scroll-spy updates.`
  ).join('\n\n');
  state.contentByPath.set(scrollDocPath, {
    path: scrollDocPath,
    kind: 'markdown',
    text: `# Top\n\n${filler}\n\n![Diagram](data:image/svg+xml,${scrollSvg})\n\n## After Image\n\n${filler}\n\n## End\n\nDone.`,
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [scrollDocPath] });
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.markdown-body img'))`),
    'scroll-jank markdown document'
  );
  await evalIn(win, `(() => {
    const tab = Array.from(document.querySelectorAll('.vv-sidebar-tab'))
      .find((node) => node.textContent.trim() === 'Contents');
    tab?.click();
    return true;
  })()`);
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-sidebar-tab-active')?.textContent.trim() === 'Contents'`),
    'Contents sidebar selected'
  );
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-toc-item'))`),
    'scroll-jank table of contents'
  );
  const scrollStability = await evalIn(win, `(() => new Promise((resolve) => {
    const content = document.querySelector('.vv-content');
    const body = document.querySelector('.markdown-body');
    const image = body?.querySelector('img');
    const menubarTop = document.querySelector('.vv-menubar')?.getBoundingClientRect().top ?? null;
    const tabsTop = document.querySelector('.vv-tabs')?.getBoundingClientRect().top ?? null;
    const uriTop = document.querySelector('.vv-uribar')?.getBoundingClientRect().top ?? null;
    let childListMutations = 0;
    const observer = new MutationObserver((records) => {
      childListMutations += records.filter((record) => record.type === 'childList').length;
    });
    observer.observe(body, { childList: true });
    content.scrollTop = Math.max(0, content.scrollHeight - content.clientHeight);
    content.dispatchEvent(new Event('scroll', { bubbles: true }));
    requestAnimationFrame(() => {
      setTimeout(() => {
        observer.disconnect();
        resolve({
          childListMutations,
          sameImage: image === body.querySelector('img'),
          menubarTop,
          tabsStillBelowMenu: document.querySelector('.vv-tabs').getBoundingClientRect().top > menubarTop,
          uriStillBelowTabs: document.querySelector('.vv-uribar').getBoundingClientRect().top > tabsTop,
          contentStillBelowUri: document.querySelector('.vv-content').getBoundingClientRect().top > uriTop,
          activeHeading: window.__vvdb().ui['active-heading'] || null
        });
      }, 250);
    });
  }))()`);
  assert.strictEqual(scrollStability.childListMutations, 0, 'scrolling must not rebuild markdown body children');
  assert.strictEqual(scrollStability.sameImage, true, 'scrolling must not recreate preview image nodes');
  assert.strictEqual(scrollStability.tabsStillBelowMenu, true, 'tab bar must stay below menu bar while scrolling images');
  assert.strictEqual(scrollStability.uriStillBelowTabs, true, 'URI bar must stay below tab bar while scrolling images');
  assert.strictEqual(scrollStability.contentStillBelowUri, true, 'content pane must stay below URI bar while scrolling images');
  assert.ok(scrollStability.activeHeading, 'scrolling must still update the active TOC heading');
  console.log('[ok] markdown image scrolling keeps preview DOM and chrome stable');

  const localSvgFixture = createLocalSvgScrollFixture();
  state.contentByPath.set(localSvgFixture.docPath, {
    path: localSvgFixture.docPath,
    kind: 'markdown',
    text: localSvgFixture.text,
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [localSvgFixture.docPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local SVG Scroll'`),
    'local SVG scroll markdown document'
  );
  await waitFor(
    () => evalIn(win, `(() => {
      const imgs = Array.from(document.querySelectorAll('.markdown-body img'));
      return imgs.length === 3 && imgs.every((img) => img.complete && img.naturalWidth > 0);
    })()`),
    'local SVG image loads'
  );
  await waitFor(
    () => evalIn(win, `Array.from(document.querySelectorAll('.markdown-body img'))
      .every((img) => img.style.width && img.style.height && img.style.aspectRatio && img.getAttribute('draggable') === 'false')`),
    'local SVG figure sizing'
  );
  const localSvgStart = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    window.__vvSvgScrollImages = imgs;
    window.__vvSvgScrollChildListMutations = 0;
    window.__vvSvgScrollObserver?.disconnect?.();
    window.__vvSvgScrollObserver = new MutationObserver((records) => {
      window.__vvSvgScrollChildListMutations += records.filter((record) => record.type === 'childList').length;
    });
    window.__vvSvgScrollObserver.observe(body, { childList: true });
    return {
      imageCount: imgs.length,
      dims: imgs.map((img) => {
        const rect = img.getBoundingClientRect();
        return {
          width: Math.round(rect.width),
          height: Math.round(rect.height),
          styleWidth: img.style.width,
          styleHeight: img.style.height,
          aspectRatio: img.style.aspectRatio
        };
      })
    };
  })()`);
  assert.strictEqual(localSvgStart.imageCount, 3, 'local SVG fixture must render three images');
  win.show();
  await delay(100);
  win.focus();
  win.webContents.focus();
  for (let i = 0; i < localSvgStart.imageCount; i++) {
    const wheelTarget = await evalIn(win, `(() => {
      const content = document.querySelector('.vv-content');
      const img = document.querySelectorAll('.markdown-body img')[${i}];
      const maxTop = Math.max(0, content.scrollHeight - content.clientHeight);
      const targetTop = Math.max(0, Math.min(maxTop - 1, img.offsetTop - (content.clientHeight / 2) + (img.offsetHeight / 2)));
      content.scrollTop = targetTop;
      const rect = img.getBoundingClientRect();
      return {
        before: content.scrollTop,
        x: Math.round(rect.left + Math.max(1, Math.min(rect.width - 1, rect.width / 2))),
        y: Math.round(rect.top + Math.max(1, Math.min(rect.height - 1, rect.height / 2)))
      };
    })()`);
    win.webContents.sendInputEvent({ type: 'mouseMove', x: wheelTarget.x, y: wheelTarget.y });
    win.webContents.sendInputEvent({
      type: 'mouseWheel',
      x: wheelTarget.x,
      y: wheelTarget.y,
      deltaY: -480,
      wheelTicksY: -4,
      hasPreciseScrollingDeltas: true,
      canScroll: true
    });
    await waitFor(
      () => evalIn(win, `document.querySelector('.vv-content').scrollTop > ${wheelTarget.before + 8}`),
      `wheel scroll over local SVG ${i + 1}`,
      2000
    );
  }
  const localSvgResult = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    const dims = imgs.map((img) => {
      const rect = img.getBoundingClientRect();
      return {
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        styleWidth: img.style.width,
        styleHeight: img.style.height,
        aspectRatio: img.style.aspectRatio
      };
    });
    const result = {
      childListMutations: window.__vvSvgScrollChildListMutations,
      sameImages: Array.isArray(window.__vvSvgScrollImages) &&
        window.__vvSvgScrollImages.length === imgs.length &&
        imgs.every((img, index) => img === window.__vvSvgScrollImages[index]),
      dims,
      docStillOpen: document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local SVG Scroll',
      dedicatedImageView: Boolean(document.querySelector('.vmd-image-view'))
    };
    window.__vvSvgScrollObserver?.disconnect?.();
    window.__vvSvgScrollObserver = null;
    window.__vvSvgScrollImages = null;
    return result;
  })()`);
  assert.strictEqual(localSvgResult.childListMutations, 0, 'wheel scrolling local SVGs must not rebuild markdown body children');
  assert.strictEqual(localSvgResult.sameImages, true, 'wheel scrolling local SVGs must not recreate image nodes');
  assert.strictEqual(localSvgResult.docStillOpen, true, 'wheel scrolling local SVGs must keep the markdown document open');
  assert.strictEqual(localSvgResult.dedicatedImageView, false, 'wheel scrolling local SVGs must not open the dedicated image view');
  assert.deepStrictEqual(localSvgResult.dims, localSvgStart.dims, 'local SVG dimensions must stay stable while scrolling over images');
  console.log('[ok] local SVG markdown images scroll without layout churn');

  await evalIn(win, `(() => {
    const tab = document.querySelector('.vv-tab-active') || document.querySelector('.vv-tab');
    const rect = tab.getBoundingClientRect();
    tab.dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true,
      cancelable: true,
      button: 2,
      clientX: rect.left + 8,
      clientY: rect.top + 8
    }));
    return true;
  })()`);
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-ctx-menu'))`),
    'tab context menu'
  );
  await waitFor(
    () => evalIn(win, `document.activeElement?.classList.contains('vv-ctx-menu')`),
    'context menu focus'
  );
  assert.strictEqual(
    await evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused') == null`),
    true,
    'context menu must not start with a hover-focused item'
  );
  const homeResult = await dispatchMenuKey(win, 'Home');
  assert.strictEqual(homeResult.handled, true, 'Home must be handled by the context menu');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() === 'Close'`),
    'context menu Home focus'
  );
  const downResult = await dispatchMenuKey(win, 'ArrowDown');
  assert.strictEqual(downResult.handled, true, 'ArrowDown must be handled by the context menu');
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() === 'Duplicate tab'`),
    'context menu second focused item'
  );
  console.log('[ok] tab context menu arrow navigation works');

  // ---- Web view: edge-to-edge bounds (bug 1) + menu-over-page snapshot freeze vs. modal hide (bug 2) ----
  // The native WebContentsView is mocked here (installIpc), so web-host is driven purely by the http tab URI
  // and the stubbed vv:http-* channels. First clear the context menu left open by the previous test.
  await evalIn(win, `(() => {
    const m = document.querySelector('.vv-ctx-menu');
    if (m) m.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui['context-menu'] == null`), 'context menu cleared');

  // bug 4 — the extensions install input must accept keystrokes. Open Settings ▸ Extensions…, focus the
  // field: focus must set :in-input? (so the keymap resolver, which consumes bare keys in vim
  // normal/visual, lets text through), typing must update the controlled value, and blur must clear it.
  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'Settings'`), 'Settings menu for Extensions…');
  await waitFor(
    () => evalIn(win, `Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item')).some((n) => n.textContent.includes('Extensions'))`),
    'Extensions… menu item present'
  );
  await evalIn(win, `(() => {
    const item = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item')).find((n) => n.textContent.includes('Extensions'));
    item.click();
    return true;
  })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-ext-input'))`), 'extensions dialog open');
  await evalIn(win, `(() => { document.querySelector('.vv-ext-input').focus(); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.input['in-input?'] === true`), 'extensions input sets in-input on focus');
  await evalIn(win, `(() => {
    const i = document.querySelector('.vv-ext-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'abcdefghijklmnopabcdefghijklmnop');
    i.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-ext-input').value === 'abcdefghijklmnopabcdefghijklmnop'`),
    'extensions input accepts typed text');
  await evalIn(win, `(() => { document.querySelector('.vv-ext-input').blur(); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.input['in-input?'] === false`), 'extensions input clears in-input on blur');
  await evalIn(win, `(() => {
    const btn = Array.from(document.querySelectorAll('.vv-modal .vv-btn')).find((b) => b.textContent.trim() === 'Close');
    if (btn) { btn.click(); }
    return true;
  })()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['extensions-open?']`), 'extensions dialog closed');
  console.log('[ok] extensions install input accepts keyboard input');

  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`),
    'fresh tab for the web view');
  state.httpShow = null;
  state.httpVisible = false;
  // type an http URL into the URI bar and press Enter (→ :uri/navigate → :tab/navigate). It need not load —
  // the native view is mocked; web-host renders from the http(s) scheme alone.
  await evalIn(win, `(() => {
    const i = document.querySelector('.vv-uri-input');
    i.focus();
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'http://127.0.0.1:9/smoke');
    i.dispatchEvent(new Event('input', { bubbles: true }));
    return i.value;
  })()`);
  await delay(60);
  await evalIn(win, `(() => {
    document.querySelector('.vv-uri-input')
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-web-host'))`), 'web host renders for an http URI');
  await waitFor(() => state.httpShow && state.httpShow.bounds && state.httpShow.bounds.width > 100,
    'web view show + bounds sent');
  await waitFor(() => state.httpVisible === true, 'web view initially shown');
  // simulate main's proactive snapshot push so web-host has a pre-cached image (the instant-freeze path)
  win.webContents.send('vv:http-snapshot-ready', state.snapshotDataUrl);
  await delay(60);

  // bug 1 — edge-to-edge: the content pane drops its document reading gutter and the host fills it exactly,
  // so the bounds handed to the native view carry no 45/32px inset.
  const webEdge = await evalIn(win, `(() => {
    const content = document.querySelector('.vv-content');
    const host = document.querySelector('.vv-web-host');
    const cs = getComputedStyle(content);
    const cr = content.getBoundingClientRect();
    const hr = host.getBoundingClientRect();
    return {
      hasWebClass: content.classList.contains('vv-content-web'),
      paddingLeft: cs.paddingLeft, paddingTop: cs.paddingTop,
      flush: Math.abs(hr.left - cr.left) < 1 && Math.abs(hr.top - cr.top) < 1 &&
             Math.abs(hr.width - cr.width) < 1 && Math.abs(hr.height - cr.height) < 1,
      contentWidth: Math.round(cr.width)
    };
  })()`);
  assert.strictEqual(webEdge.hasWebClass, true, 'http content pane must use the edge-to-edge .vv-content-web class');
  assert.strictEqual(webEdge.paddingLeft, '0px', 'edge-to-edge web pane must drop the L/R reading gutter');
  assert.strictEqual(webEdge.paddingTop, '0px', 'edge-to-edge web pane must drop the T/B reading gutter');
  assert.strictEqual(webEdge.flush, true, 'web host must fill the content pane exactly (no margin around the native view)');
  assert.ok(Math.abs(Math.round(state.httpShow.bounds.width) - webEdge.contentWidth) < 2,
    'native view width must equal the full pane (not inset by the 90px gutter)');
  console.log('[ok] web view is edge-to-edge (no margin)');

  // bug 2a — opening a MENU over the page freezes a snapshot and hides the native view (page does not vanish)
  await dispatchWindowKey(win, 's', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'Settings'`), 'Settings menu open over the web view');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('img.vv-web-snap'))`), 'page snapshot frozen under the menu');
  const frozen = await evalIn(win, `(() => {
    const img = document.querySelector('img.vv-web-snap');
    return { src: (img && img.getAttribute('src')) || '', insideHost: Boolean(img && img.closest('.vv-web-host')) };
  })()`);
  assert.ok(frozen.src.startsWith('data:image'), 'snapshot img must show the captured page raster');
  assert.strictEqual(frozen.insideHost, true, 'snapshot img must render inside the web host');
  assert.strictEqual(state.httpVisible, false, 'native view must hide while the frozen snapshot is shown');
  console.log('[ok] opening a menu freezes a page snapshot (the page does not disappear)');

  // bug 2b — closing the menu drops the snapshot and restores the live view
  await dispatchWindowKey(win, 'Escape');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu == null`), 'Settings menu closed');
  await waitFor(() => evalIn(win, `!document.querySelector('img.vv-web-snap')`), 'snapshot dropped on menu close');
  await waitFor(() => state.httpVisible === true, 'live web view restored on menu close');
  console.log('[ok] closing the menu restores the live web view');

  // bug 2c — a full-window MODAL (command palette) ALSO freezes the page (shows the snapshot behind its
  // dimmed backdrop), not blank — unified overlay handling.
  await sendChord(win, 'P', ['control', 'shift']);
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.palette && window.__vvdb().ui.palette['open?'])`),
    'command palette (modal) open');
  await waitFor(() => state.httpVisible === false, 'native view hidden under a modal');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('img.vv-web-snap'))`),
    'a modal freezes the page snapshot (not blank)');
  // close the palette via its own input (a window-dispatched Escape doesn't reach it while it's open)
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    if (i) i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
  await waitFor(() => evalIn(win, `!(window.__vvdb().ui.palette && window.__vvdb().ui.palette['open?'])`), 'palette closed');
  await waitFor(() => state.httpVisible === true, 'live web view restored after the modal closes');
  console.log('[ok] a modal dialog freezes the page snapshot (unified overlay handling)');

  // Fix 6 — a local .html file opens in the (edge-to-edge) web view via its file:// URL, not as source
  const htmlPath = path.join(ROOT, 'test', 'fixtures', 'local-page.html');
  state.contentByPath.set(htmlPath, { path: htmlPath, kind: 'html' });
  state.httpShow = null;
  win.webContents.send('vv:open-files', { paths: [htmlPath] });
  await waitFor(() => evalIn(win, `(() => {
    const c = document.querySelector('.vv-content');
    return Boolean(document.querySelector('.vv-web-host')) && Boolean(c) &&
           c.classList.contains('vv-content-web') &&
           window.__vvdb().ui.tabs.some((t) => (t.uri || '').includes('local-page.html'));
  })()`), 'local HTML renders in the edge-to-edge web view');
  await waitFor(() => state.httpShow && typeof state.httpShow.url === 'string' &&
    state.httpShow.url.startsWith('file://') && state.httpShow.url.includes('local-page.html'),
    'web view loads the local HTML via its file:// URL');
  console.log('[ok] local HTML opens in the web view (file:// URL, edge-to-edge)');

  win.close();
}

const hardTimeout = setTimeout(() => {
  cleanupTempDirs();
  console.error('Electron smoke test timed out');
  app.exit(1);
}, 45000);

main()
  .then(() => {
    clearTimeout(hardTimeout);
    cleanupTempDirs();
    console.log('[ok] Electron smoke test passed');
    app.quit();
  })
  .catch((err) => {
    clearTimeout(hardTimeout);
    cleanupTempDirs();
    console.error(err && err.stack ? err.stack : err);
    app.exit(1);
  });
