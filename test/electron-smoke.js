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

// standard PNG CRC-32 (poly 0xEDB88320), for building valid chunks below
function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = ((c >>> 1) ^ (0xEDB88320 & -(c & 1))) >>> 0;
  }
  return (c ^ 0xFFFFFFFF) >>> 0;
}

// a VALID (transparent RGBA) PNG of the given pixel dimensions — large enough for the renderer to load it AND
// for figures.cljs's IHDR header-parse to read its intrinsic width/height (used by the raster scroll test).
function makePng(width, height) {
  const zlib = require('zlib');
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // color type: RGBA
  const raw = Buffer.alloc(height * (1 + width * 4)); // per-scanline filter byte (0) + transparent RGBA pixels
  const idat = zlib.deflateSync(raw);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const typed = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(typed), 0);
    return Buffer.concat([len, typed, crc]);
  };
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

function createLocalRasterScrollFixture() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-raster-scroll-'));
  const imagesDir = path.join(dir, 'images');
  tempDirs.push(dir);
  fs.mkdirSync(imagesDir);

  const images = [
    { file: 'wide.png', width: 800, height: 200 },   // 4.00
    { file: 'tall.png', width: 200, height: 600 },   // 0.33
    { file: 'box.png',  width: 400, height: 400 }    // 1.00
  ];
  for (const im of images) {
    fs.writeFileSync(path.join(imagesDir, im.file), makePng(im.width, im.height));
  }
  const filler = Array.from({ length: 16 }, (_value, index) =>
    `Paragraph ${index + 1}. This gives the raster image scroll test enough height.`
  ).join('\n\n');
  const text = `# Local Raster Scroll\n\n${filler}\n\n` +
    `![Wide](images/wide.png)\n\n## Middle\n\n${filler}\n\n` +
    `![Tall](images/tall.png)\n\n## More\n\n${filler}\n\n` +
    `![Box](images/box.png)\n\n## End\n\n${filler}`;
  const docPath = path.join(dir, 'local-raster-scroll.md');
  fs.writeFileSync(docPath, text);
  return { docPath, text };
}

function makeOutlinePdf() {
  const content = 'q 0 0 1 rg 20 20 120 120 re f Q';
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>',
    '<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 800] /Contents 5 0 R /Resources << >> >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 800] /Contents 5 0 R /Resources << >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Outlines /First 7 0 R /Last 8 0 R /Count 2 >>',
    '<< /Title (Section One) /Parent 6 0 R /Next 8 0 R /First 9 0 R /Last 9 0 R /Count 1 /Dest [3 0 R /XYZ 0 800 0] >>',
    '<< /Title (Section Two) /Parent 6 0 R /Prev 7 0 R /Dest [4 0 R /XYZ 0 800 0] >>',
    '<< /Title (Subsection A) /Parent 7 0 R /Dest [3 0 R /XYZ 0 400 0] >>'   // nested under Section One → 1.1
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objs.forEach((body, i) => { offsets[i] = Buffer.byteLength(pdf, 'latin1'); pdf += `${i + 1} 0 obj\n${body}\nendobj\n`; });
  const xrefStart = Buffer.byteLength(pdf, 'latin1');
  pdf += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((off) => { pdf += `${String(off).padStart(10, '0')} 00000 n \n`; });
  pdf += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
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

// A gentle poller (500ms cadence) for eventually-consistent state that settles over seconds — e.g. a PDF
// outline resolving via async worker round-trips, or a reagent re-render deferred behind main-thread page
// rasterization. Deliberately slower than waitFor's 50ms loop: tight executeJavaScript polling during a
// pdf.js remount can transiently destabilize the headless IPC channel, so we probe calmly instead.
async function waitCalm(win, expr, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    try { last = await evalIn(win, expr); if (last) return last; } catch (err) { last = 'ERR ' + err.message; }
    await delay(500);
  }
  throw new Error(`Timed out waiting for ${label} (last=${JSON.stringify(last)})`);
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
  // native password-manager bridge mocks (so the Passwords dialog populates with a provider row)
  ipcMain.on('vv:password-state-request', (event) => {
    event.sender.send('vv:password-state', {
      providers: [{ id: 'op', label: '1Password', status: 'ready', 'save-supported?': true, message: '' }],
      forms: { count: 0 }
    });
  });
  ipcMain.on('vv:password-search', (_event, url) => { state.pwSearch = url; });
  ipcMain.on('vv:password-fill', (_event, item) => { state.pwFill = item; });
  ipcMain.on('vv:password-save', (_event, payload) => { state.pwSave = payload; });
  ipcMain.on('vv:password-dismiss-save', (_event, token) => { state.pwDismiss = token; });
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
    pwSearch: null, pwFill: null, pwSave: null, pwDismiss: null,
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
  await waitFor(() => evalIn(win, `document.querySelector('.vv-menu-dropdown')?.textContent.includes('Zoom In')`), 'View dropdown rendered');
  // B6 — the PDF-only items (Fit submenu, Invert PDF) appear ONLY when a PDF is the active view; none is yet.
  // A4 — the re-frame-10x item exists only in a dev build (its runtime is stripped from :release); the gate
  // runs this smoke against a release build with VV_RELEASE=1 to assert it is then absent.
  const releaseBuild = process.env.VV_RELEASE === '1';
  const viewNoPdf = await evalIn(win, `(() => { const t = document.querySelector('.vv-menu-dropdown')?.textContent || '';
    return { fit: t.includes('Fit'), invert: t.includes('Invert PDF'), tenx: t.includes('re-frame-10x') }; })()`);
  assert.strictEqual(viewNoPdf.fit, false, 'View ▸ Fit must be hidden when no PDF is active');
  assert.strictEqual(viewNoPdf.invert, false, 'View ▸ Invert PDF must be hidden when no PDF is active');
  assert.strictEqual(viewNoPdf.tenx, !releaseBuild,
    releaseBuild ? 'View ▸ re-frame-10x must be absent in a release build' : 'View ▸ re-frame-10x present in a dev build');
  await dispatchWindowKey(win, 'Escape');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu == null`), 'View menu closed');
  console.log('[ok] zoom bar present + View PDF-only items hidden without a PDF');

  // B4 / B5 — the mode-line indicator is removed, and #app clips overflow so a tall view adds no second
  // window-level scrollbar beside the content scroller
  const layout = await evalIn(win, `(() => ({
    noModeline: !document.querySelector('.vv-modeline'),
    appClip: getComputedStyle(document.getElementById('app')).overflowY,
    statusInWrap: !document.querySelector('.vv-statusbar') || Boolean(document.querySelector('.vv-content-wrap > .vv-statusbar'))
  }))()`);
  assert.strictEqual(layout.noModeline, true, 'the mode-line indicator must be removed');
  assert.strictEqual(layout.appClip, 'hidden', '#app must clip overflow so a tall view adds no window scrollbar');
  assert.strictEqual(layout.statusInWrap, true, 'the hover-URL status bar must live inside .vv-content-wrap (above the zoom bar)');
  console.log('[ok] mode-line removed + app root clipped + status bar repositioned');

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
  // the canvas must carry REAL painted pixels, not a blank/white surface (guards the white-page class —
  // the render completes before this point, gated by the text-layer wait above)
  const pdfNonWhite = await evalIn(win, `(() => {
    const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas');
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    let n = 0; for (let p = 0; p < d.length; p += 4) { if (d[p] < 235 || d[p+1] < 235 || d[p+2] < 235) n++; }
    return n;
  })()`);
  assert.ok(pdfNonWhite > 0, 'PDF canvas must contain rendered pixels (not a blank/white canvas)');
  console.log('[ok] PDF renders in-DOM: canvas + aligned text layer + real pixels (no native view)');

  // Bug C (round 5): pdf.js 5.x sizes/positions every text span via calc(var(--total-scale-factor) * …).
  // build-text-layer! must set --total-scale-factor (it formerly set only the renamed --scale-factor), else
  // the span geometry is invalid-at-computed-value and Ctrl+F highlights mis-position. Assert the var is set
  // AND a span's font-size resolves to a positive px (not the inherited body size that an undefined var gives).
  const pdfScale = await evalIn(win, `(() => {
    const layer = document.querySelector('.vv-pdf-text');
    const span = document.querySelector('.vv-pdf-text span');
    return {
      totalScaleVar: layer ? layer.style.getPropertyValue('--total-scale-factor').trim() : '',
      fontPx: span ? parseFloat(getComputedStyle(span).fontSize) : 0
    };
  })()`);
  assert.ok(pdfScale.totalScaleVar && parseFloat(pdfScale.totalScaleVar) > 0,
    'the PDF text layer must set --total-scale-factor (pdf.js 5.x reads it) so span geometry / find highlights resolve');
  assert.ok(pdfScale.fontPx > 0, 'PDF text-layer spans must have a resolved (non-zero) font-size from the scale var');
  console.log('[ok] PDF text layer sets --total-scale-factor → find highlights align (Bug C)');

  // B6 — now a PDF IS the active view → its Fit submenu + Invert PDF appear, and the Fit submenu works
  await dispatchWindowKey(win, 'v', { altKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu === 'View'`), 'View menu open (pdf active)');
  await waitFor(() => evalIn(win, `document.querySelector('.vv-menu-dropdown')?.textContent.includes('Fit')`), 'View ▸ Fit present with a PDF active');
  assert.strictEqual(await evalIn(win, `(document.querySelector('.vv-menu-dropdown')?.textContent || '').includes('Invert PDF')`), true, 'View ▸ Invert PDF present with a PDF active');
  assert.strictEqual(await hoverMenuItem(win, 'Fit'), true, 'View menu must have a Fit submenu with a PDF active');
  await waitFor(() => evalIn(win, `(() => { const d = document.querySelector('.vv-menu-subdropdown');
    return Boolean(d) && d.textContent.includes('Fit Width') && d.textContent.includes('Fit Page'); })()`),
    'Fit submenu lists Fit Width / Fit Page');
  await dispatchWindowKey(win, 'Escape');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.menu == null`), 'View menu closed (pdf)');
  console.log('[ok] View ▸ Fit + Invert PDF appear only with a PDF active');

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

  // Hit-test: mouse-drag selection requires the TEXT layer to be the topmost element at a text span's
  // position. The link overlay (.vv-pdf-anno, z-index 2) covers the whole page ABOVE the text layer, so it
  // must be pointer-transparent — else it swallows the drag and text can't be selected (the copy test above
  // uses a PROGRAMMATIC range, which needs no pointer events, so it never caught this). elementFromPoint at a
  // span's center must resolve into .vv-pdf-text, NOT .vv-pdf-anno / the canvas.
  // close any menu left open by an earlier step — its full-screen .vv-menu-overlay (z-index 49) would sit
  // over the page and defeat the hit-test (the find/copy tests above don't care: no pointer events needed).
  await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("menu","close"))); true`);
  await waitFor(() => evalIn(win, `!document.querySelector('.vv-menu-overlay')`), 'no menu overlay before the PDF hit-test');
  const hitTest = await evalIn(win, `(() => {
    // a real, laid-out glyph span: non-empty, not the zero-height marked-content marker, real box
    const span = Array.from(document.querySelectorAll('.vv-pdf-text span'))
      .find(s => s.textContent.trim().length > 0 && !s.classList.contains('markedContent')
                 && s.getBoundingClientRect().width > 1 && s.getBoundingClientRect().height > 1);
    if (!span) return { ok: false, reason: 'no laid-out text span' };
    const r = span.getBoundingClientRect();
    const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return {
      ok: Boolean(hit && hit.closest('.vv-pdf-text')),
      inAnno: Boolean(hit && hit.closest('.vv-pdf-anno')),
      hitClass: hit ? (hit.className || hit.tagName.toLowerCase()) : null,
      annoPE: getComputedStyle(document.querySelector('.vv-pdf-anno')).pointerEvents
    };
  })()`);
  assert.strictEqual(hitTest.inAnno, false, 'link overlay must not intercept pointer events over text (pointer-events:none)');
  assert.strictEqual(hitTest.annoPE, 'none', '.vv-pdf-anno container must be pointer-transparent');
  assert.strictEqual(hitTest.ok, true, `a text span must be the topmost hit target for drag-selection (got .${hitTest.hitClass})`);
  console.log('[ok] PDF text layer is the topmost hit target — drag-selection reaches it (link overlay is click-through)');

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

  // --- opt-in PDF reflow (ADR-0017): "Reflow Text" swaps the fixed-layout canvas for the extracted text as
  // reflowable prose (an additive facet — the canvas render is untouched). smoke.pdf is the active PDF here
  // and carries text. Dev-only: it dispatches through the re-frame global, which the release :simple build
  // encapsulates (release still covers the reflow-ir transform via the DOM-free unit tests).
  if (!releaseBuild) {
    const isReflowOn = () => evalIn(win, `(() => { const p = (((window.__vvdb()||{}).ui||{}).pdf)||{}; return Boolean(p["reflow?"]); })()`);
    if (await isReflowOn())   // a prior run may have persisted it on — normalize to OFF first
      await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("pdf", "reflow-toggle"))); true`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), 'canvas active before reflow');
    await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("pdf", "reflow-toggle"))); true`);
    const reflowedLen = await waitFor(
      () => evalIn(win, `(() => { const b = document.querySelector('.vv-content .markdown-body');
                                  return b && b.textContent.trim().length > 0 ? b.textContent.trim().length : null; })()`),
      'PDF reflow renders the extracted text as prose');
    assert.ok(reflowedLen > 0, 'reflow view shows the extracted PDF text');
    assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), false,
      'reflow replaces the canvas while active (additive: the canvas returns when toggled off)');
    await evalIn(win, `re_frame.core.dispatch_sync(cljs.core.vector(cljs.core.keyword("pdf", "reflow-toggle"))); true`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-content .vv-pdf-doc canvas'))`), 'canvas PDF view returns after reflow off');
    console.log('[ok] PDF reflow — extracted text renders as reflowable prose; canvas returns when off');
  }

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

  // Bug A (round 4) — a zoom rescale clears + re-renders; the visible page must not be left blank (A2
  // re-asserts visible renders deterministically rather than relying on an IntersectionObserver re-fire)
  await sendChord(win, '=', ['control']);   // Ctrl+= zoom in → :view/zoom → pdf rescale
  await waitFor(() => evalIn(win, `(() => { const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas'); return Boolean(c) && c.getBoundingClientRect().width > 0 && c.getBoundingClientRect().height > 0; })()`),
    'PDF page canvas survives a zoom rescale (re-rendered, not left blank)', 8000);
  await sendChord(win, '0', ['control']);   // reset zoom for the following tests
  await delay(120);
  console.log('[ok] PDF page stays rendered through a zoom rescale');

  // ── PDF Contents scroll-spy — the generalized follow must TRACK a bookmarked PDF's sections on scroll ──
  // (the reported bug: PDF sections were listed but the reading position was never followed). Load a 2-page
  // OUTLINED PDF, confirm its outline fills the Contents, then scroll to the bottom / top and assert the
  // active section FOLLOWS (page-2 then page-1 bookmarks) and the active row is highlighted. Exercises
  // pdf.cljs's outline → :toc-ids → toc/refresh! wiring and the content-agnostic spy (toc.cljs) end-to-end.
  // The PDF is the active view here (before the Bug A/B hint tests, which inject their own link and don't
  // depend on smoke.pdf's content). Uses waitCalm because the outline resolves asynchronously and a scrolled
  // page rasterizes on the main thread, briefly deferring the sidebar re-render.
  const outlinePdfPath = path.join(ROOT, 'test', 'fixtures', 'outline-smoke.pdf'); // virtual path; bytes below
  state.contentByPath.set(outlinePdfPath, {
    path: outlinePdfPath, kind: 'pdf', bytes: new Uint8Array(makeOutlinePdf()), stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [outlinePdfPath] });
  await evalIn(win, `(() => { const t = Array.from(document.querySelectorAll('.vv-sidebar-tab')).find((n) => n.textContent.trim() === 'Contents'); if (t) t.click(); return true; })()`);
  await delay(1500);   // let the pdf-view remount + first render settle before probing (avoids tight polling mid-remount)
  await waitCalm(win, `document.querySelectorAll('.vv-toc-item').length === 3`,
    'the PDF outline fills the Contents (Section One, Subsection A, Section Two)');
  // scroll to the bottom → the page-2 section becomes active AND its Contents row is highlighted
  await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); c.scrollTop = Math.max(0, c.scrollHeight - c.clientHeight); c.dispatchEvent(new Event('scroll', { bubbles: true })); return true; })()`);
  await waitCalm(win, `String(window.__vvdb().ui['active-heading']) === 'vv-pdf-page-2' && Boolean(document.querySelector('.vv-toc-item.vv-toc-active'))`,
    'scrolling to the bottom follows to the page-2 bookmark and highlights its row', 15000);
  // scroll back to the top → the page-1 section becomes active (follows both directions)
  await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); c.scrollTop = 0; c.dispatchEvent(new Event('scroll', { bubbles: true })); return true; })()`);
  await waitCalm(win, `String(window.__vvdb().ui['active-heading']) === 'vv-pdf-page-1'`,
    'scrolling back to the top follows to the page-1 bookmark');
  console.log('[ok] PDF Contents scroll-spy follows both scroll directions + highlights the active section');

  // ── PDF Contents auto-numbering — unnumbered outline bookmarks must show derived hierarchical numbers ──
  // (pdf-layout/number-outline): Section One / Subsection A / Section Two → 1, 1.1, 2.
  assert.strictEqual(
    await evalIn(win, `JSON.stringify(Array.from(document.querySelectorAll('.vv-toc-item .vv-toc-num')).map((s) => s.textContent))`),
    JSON.stringify(['1', '1.1', '2']),
    'the PDF Contents must show derived section numbers (1, 1.1, 2) from the unnumbered outline');
  console.log('[ok] PDF Contents auto-numbers the unnumbered outline (1, 1.1, 2)');

  // ── Menu bar must NOT move when clicking a PDF Contents section ──
  // Root cause: el.scrollIntoView({block:"start"}) aligns the target to the top of EVERY scrollable ancestor,
  // so when #app has scroll-range (a tall document can create it) it scrolls #app, pushing the menu bar out of
  // #app's clipped viewport. The confined .scrollTo (the fix) scrolls only .vv-content and can never touch
  // #app. To make this a real regression test — one the OLD scrollIntoView would FAIL — we deterministically
  // give #app scroll-range with a temporary spacer (the bug's precondition), click the last section, and
  // assert the content pane scrolled while #app + the menu bar did NOT.
  const appRange = await evalIn(win, `(() => {
    const app = document.getElementById('app');
    const sp = document.createElement('div'); sp.id = '__vv_menubar_spacer'; sp.style.height = '3000px';
    app.appendChild(sp); app.scrollTop = 0;
    return app.scrollHeight - app.clientHeight;   // #app now has real scroll-range (the bug's precondition)
  })()`);
  assert.ok(appRange > 0, 'test precondition: the spacer gives #app scroll-range');
  const menubarTop0 = await evalIn(win, `document.querySelector('.vv-menubar').getBoundingClientRect().top`);
  await evalIn(win, `(() => { const rows = document.querySelectorAll('.vv-toc-item'); rows[rows.length - 1].click(); return true; })()`);
  await waitCalm(win, `document.querySelector('.vv-content').scrollTop > 50`,
    'clicking a PDF Contents section scrolls the content pane', 10000);
  const chrome = JSON.parse(await evalIn(win, `(() => { const a = document.getElementById('app');
    return JSON.stringify({ menubarTop: document.querySelector('.vv-menubar').getBoundingClientRect().top,
                            appScroll: a.scrollTop, rootScroll: document.scrollingElement.scrollTop }); })()`));
  await evalIn(win, `(() => { const s = document.getElementById('__vv_menubar_spacer'); if (s) s.remove();
    document.getElementById('app').scrollTop = 0; return true; })()`);   // restore layout for later tests
  assert.strictEqual(chrome.appScroll, 0, 'clicking a PDF Contents section must NOT scroll #app (the old scrollIntoView did → menu bar vanished)');
  assert.strictEqual(chrome.menubarTop, menubarTop0, 'the menu bar must not move when clicking a PDF Contents section');
  assert.strictEqual(chrome.rootScroll, 0, 'the document root must not scroll either');
  console.log('[ok] PDF Contents click scrolls the pane, not the chrome — menu bar stays put even with #app scroll-range');

  // ===== Bugs A + B1 + B2 (round 5) — run with the PDF active (the content pane); default keymap restored after.
  // Bug A: a persisted Vim set must be LIVE immediately (active set AND its modal :normal mode set in :db), not
  // only after a manual Standard→Vim→… switch. Push a Vim config the way main does and assert both at once.
  win.webContents.send('vv:keymap', '{:active "vim" :order [] :sets {}}');
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'vim'`), 'persisted Vim set becomes active at config-received');
  assert.strictEqual(await evalIn(win, `window.__vvdb().ui.input.mode`), 'normal',
    'Bug A: Vim is modal → input mode is :normal immediately (set synchronously in :db — no manual switch needed)');
  console.log('[ok] persisted Vim keymap is live immediately — active set + :normal mode (Bug A)');

  // inject a content link (PDF-intra-doc style: href="#", destination only in a click listener) + a sidebar
  // tree file row (the leak candidate), with focus in the content pane.
  await evalIn(win, `(() => {
    const content = document.querySelector('.vv-content');
    const a = document.createElement('a');
    a.className = 'vv-pdf-link'; a.href = '#'; a.textContent = 'HINTLINK';
    a.setAttribute('style', 'position:fixed;left:6px;top:6px;width:48px;height:16px;z-index:99999;background:#fff');
    a.addEventListener('click', (e) => { e.preventDefault(); window.__vvHintClicked = true; });
    content.appendChild(a);
    let tree = document.querySelector('.vv-tree');
    if (!tree) { tree = document.createElement('div'); tree.className = 'vv-tree'; tree.setAttribute('data-vv-test-tree','1'); document.body.appendChild(tree); }
    const t = document.createElement('a'); t.className = 'vv-file'; t.setAttribute('data-path', '/leak/should-not-hint.txt');
    t.textContent = 'leakrow'; t.setAttribute('style', 'position:fixed;left:6px;top:60px;width:48px;height:16px;z-index:99999');
    tree.appendChild(t);
    window.__vvHintClicked = false;
    // the hidden find bar auto-focuses at boot (sets :in-input? true). Cycle a REAL input (the URI bar) through
    // focus→blur so its on-blur dispatches :input/set-in-input false, then move focus into the content pane.
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    const uri = document.querySelector('.vv-uri-input'); if (uri) { uri.focus(); uri.blur(); }
    const c = document.querySelector('.vv-content'); if (c) { c.setAttribute('tabindex', '-1'); c.focus(); }
  })()`);
  // a bare 'f' only resolves to :hint/start when NOT typing into an input; wait for :in-input? to clear first.
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.input['in-input?']`), ':in-input? cleared before f');

  // Bug B1: 'f' (Vim) collects link hints scoped to the focused CONTENT pane — the sidebar tree must NOT leak in.
  await dispatchWindowKey(win, 'f');
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.hints && window.__vvdb().ui.hints['active?'])`), 'link hints activate on f');
  const hintInfo = await evalIn(win, `(() => {
    const ts = (window.__vvdb().ui.hints && window.__vvdb().ui.hints.targets) || [];
    return { leak: ts.some(t => t.path === '/leak/should-not-hint.txt'),
             label: (ts.find(t => (t.text || '').indexOf('HINTLINK') >= 0) || {}).label,
             count: ts.length };
  })()`);
  assert.strictEqual(hintInfo.leak, false, 'Bug B1: the sidebar tree file row must NOT be hinted while the content pane is focused');
  assert.ok(hintInfo.label, 'the injected content link must be hinted (collected from .vv-content)');
  console.log('[ok] f-hints scoped to the content pane — sidebar tree not hinted (Bug B1)');

  // Bug B2: typing the label fires the element's REAL click. A PDF intra-doc link carries its destination only
  // in a click listener (href="#"), so deriving nav from href was a no-op; the follow now re-finds the element
  // at its stamped position and .click()s it.
  for (const ch of hintInfo.label) { await dispatchWindowKey(win, ch); await delay(20); }
  await waitFor(() => evalIn(win, `window.__vvHintClicked === true`), 'typing the hint label fires the link real click');
  console.log('[ok] activating an f-hint fires the link real click listener (Bug B2)');

  // restore the default keymap + clean up the injected nodes so the following tests run unchanged
  await evalIn(win, `(() => {
    document.querySelectorAll('.vv-content .vv-pdf-link').forEach(e => { if (/HINTLINK/.test(e.textContent)) e.remove(); });
    document.querySelectorAll('.vv-file').forEach(e => { if (e.getAttribute('data-path') === '/leak/should-not-hint.txt') e.remove(); });
    const tn = document.querySelector('.vv-tree[data-vv-test-tree]'); if (tn) tn.remove();
  })()`);
  win.webContents.send('vv:keymap', null);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.keymaps.active === 'default'`), 'default keymap restored after the hint tests');
  await delay(60);

  const dialogsBefore = state.openDialogs;
  await sendChord(win, 'O', ['control', 'shift']);
  await waitFor(() => state.openDialogs > dialogsBefore, 'Ctrl+Shift+O dialog request');
  const openMode = await evalIn(win, `String(window.__vvdb().ui['open-dialog-mode'])`);
  assert.strictEqual(openMode, 'new-tab', 'Ctrl+Shift+O must request the new-tab open mode');
  console.log('[ok] Ctrl+Shift+O works from preview content');

  // B3 — app-global Ctrl/Cmd chords forwarded from the (separate-context) web view are replayed through the
  // resolver. Simulate the forward by delivering vv:web-key to the renderer and assert the same command runs.
  const dialogsBeforeWeb = state.openDialogs;
  win.webContents.send('vv:web-key', { key: 'O', ctrl: true, shift: true, alt: false, meta: false });
  await waitFor(() => state.openDialogs > dialogsBeforeWeb, 'web-view Ctrl+Shift+O forwarded → new-tab dialog request');
  assert.strictEqual(await evalIn(win, `String(window.__vvdb().ui['open-dialog-mode'])`), 'new-tab', 'forwarded Ctrl+Shift+O requests the new-tab open mode');
  console.log('[ok] web-view Ctrl/Cmd shortcuts forwarded to the app keymap');

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
      'A code span `$x^2$` stays literal.\n\n' +
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
  // Each equation must stash its raw LaTeX so the copy paths can recover the source the SVG can't carry.
  const mathTex = await evalIn(win, `(() => ({
    inline: document.querySelector('.markdown-body .vv-math-inline')?.getAttribute('data-tex') ?? null,
    display: document.querySelector('.markdown-body .vv-math-display')?.getAttribute('data-tex') ?? null
  }))()`);
  assert.strictEqual(mathTex.inline, 'x^2', 'inline math must carry its raw LaTeX in data-tex');
  assert.strictEqual(mathTex.display, '\\frac{a}{b}', 'display math must carry its raw LaTeX in data-tex');
  // GFM precedence: `$x^2$` (backticks OUTSIDE) is a CODE SPAN → literal `$x^2$`, NOT math (whereas bare
  // $x^2$ and $`x^2`$ ARE math). Regression guard for the pre-parse-regex bug that leaked math into code spans.
  const codeSpanMath = await evalIn(win, `(() => {
    const lit = Array.from(document.querySelectorAll('.markdown-body code')).find(c => c.textContent.trim() === '$x^2$');
    return {
      found: Boolean(lit),
      isMath: lit ? (lit.classList.contains('language-math') || lit.classList.contains('math-inline')) : null,
      hasMjx: lit ? Boolean(lit.querySelector('mjx-container')) : null
    };
  })()`);
  assert.strictEqual(codeSpanMath.found, true, '`$x^2$` renders as a literal <code> span (code outranks $…$ math in GFM)');
  assert.strictEqual(codeSpanMath.isMath, false, 'the code span must NOT be a math node');
  assert.strictEqual(codeSpanMath.hasMjx, false, 'the code span must contain no MathJax rendering');
  console.log('[ok] `$x^2$` inline code stays literal (no math leak); $`x^2`$ + bare $…$ still render as math');
  // Regression guard for the double-render bug: MathJax's <mjx-assistive-mml> must stay in the DOM (a11y)
  // but be clipped + unselectable by our re-added rule, so Chromium can't paint it as a text-size duplicate.
  const assistive = await evalIn(win, `(() => {
    const m = document.querySelector('.markdown-body .vv-math-inline mjx-assistive-mml');
    if (!m) return { present: false };
    const cs = getComputedStyle(m);
    return { present: true, position: cs.position, userSelect: cs.userSelect, clip: cs.clip };
  })()`);
  assert.strictEqual(assistive.present, true, 'assistive MathML must remain in the DOM for screen readers');
  assert.strictEqual(assistive.position, 'absolute', 'assistive MathML must be positioned out of flow');
  assert.strictEqual(assistive.userSelect, 'none', 'assistive MathML must be excluded from text selection');
  assert.ok(assistive.clip.startsWith('rect') && assistive.clip.includes('1px'),
    `assistive MathML must be clipped to ~1px (no visible duplicate): ${assistive.clip}`);
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

  // The common IR is the UNCONDITIONAL render path (ADR-0017): the Markdown assertions above (H1, MathJax SVG,
  // Mermaid SVG, badge layout, preview copy) already flow through render-ir, so they ARE the end-to-end
  // IR-render verification. Byte-parity of the HAST round-trip is proven separately by ir.parity-test. (The
  // retired :vv/ir flag's off<->on A/B toggle check was removed together with the flag.)

  // --- office via the common IR: a synthetic office document renders through render-office-ir — its headings
  // gain slug ids (the Contents TOC office lacked) and the shared GitHub-allowlist sanitizer strips dangerous
  // markup (script / on* / javascript:). IR is the default, so simply opening the office doc exercises it.
  const officeDocPath = path.join(ROOT, 'test', 'fixtures', 'smoke-report.docx');
  state.contentByPath.set(officeDocPath, {
    path: officeDocPath,
    kind: 'office',
    html: '<h1>Quarterly Report</h1><p onclick="evil()">Body text.</p><h2>Summary</h2>'
        + '<script>bad()</script><a href="javascript:hack()">link</a>'
  });
  win.webContents.send('vv:open-files', { paths: [officeDocPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Quarterly Report'`),
    'office document renders via the common IR'
  );
  const office = await evalIn(win, `(() => {
    const b = document.querySelector('.markdown-body');
    return {
      h1: b.querySelector('h1') ? b.querySelector('h1').textContent.trim() : null,
      ids: Array.from(b.querySelectorAll('h1,h2')).map((h) => h.id),
      hasScript: /<script/i.test(b.innerHTML),
      hasOnclick: /onclick/i.test(b.innerHTML),
      hasJsUrl: /javascript:/i.test(b.innerHTML)
    };
  })()`);
  assert.strictEqual(office.h1, 'Quarterly Report', 'office renders its heading via the common IR');
  assert.deepStrictEqual(office.ids, ['quarterly-report', 'summary'], 'office headings gain slug ids (a Contents TOC) via IR');
  assert.strictEqual(office.hasScript, false, 'office IR sanitizer strips <script>');
  assert.strictEqual(office.hasOnclick, false, 'office IR sanitizer strips on* handlers');
  assert.strictEqual(office.hasJsUrl, false, 'office IR sanitizer strips javascript: URLs');
  console.log('[ok] office renders via the common IR — heading TOC + shared sanitizer (script/on*/javascript: stripped)');
  // re-focus the markdown feature doc so the Copy-LaTeX tests below operate on it
  win.webContents.send('vv:open-files', { paths: [featureDocPath] });
  await waitFor(() => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Markdown Features'`),
    'return to the markdown feature document');

  // Copy LaTeX (path 1): Ctrl+C over a paragraph containing inline math must substitute the rendered
  // equation with its raw $…$ source — prose preserved, no glyphs, no leaked assistive MathML ("x2").
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const p = document.querySelector('.markdown-body .vv-math-inline').closest('p');
    const selection = window.getSelection();
    selection.removeAllRanges();
    const range = document.createRange();
    range.selectNodeContents(p);
    selection.addRange(range);
    return true;
  })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C must be handled for a math selection');
  const mathCopied = await waitFor(
    () => (state.lastCopiedText && state.lastCopiedText.includes('$x^2$')) ? state.lastCopiedText : null,
    'inline math Ctrl+C copies LaTeX ($x^2$)'
  );
  assert.strictEqual(mathCopied.trim(), 'Inline math $x^2$ and display math:',
    'math selection copies prose with the equation substituted by inline LaTeX (no glyphs, no MathML leak)');

  // Copy LaTeX (path 2): right-click a rendered equation → "Copy LaTeX" is offered and copies the $$…$$
  // source. Drive it by keyboard (Home focuses the first item, Enter activates) like the other menu tests.
  state.lastCopiedText = null;
  await evalIn(win, `(() => {
    const el = document.querySelector('.markdown-body .vv-math-display');
    const r = el.getBoundingClientRect();
    el.dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true, cancelable: true, button: 2, clientX: r.left + 4, clientY: r.top + 4
    }));
    return true;
  })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-ctx-menu'))`), 'math context menu');
  const mathMenuHome = await dispatchMenuKey(win, 'Home');
  assert.strictEqual(mathMenuHome.handled, true, 'Home must be handled by the math context menu');
  // The .vv-menu-item-focused class lands on the next reagent render, so wait for it (matches the tab-menu test).
  await waitFor(
    () => evalIn(win, `document.querySelector('.vv-ctx-menu .vv-menu-item-focused .vv-menu-item-label')?.textContent.trim() === 'Copy LaTeX'`),
    'right-clicking an equation offers "Copy LaTeX" as the first item'
  );
  await dispatchMenuKey(win, 'Enter');
  await waitFor(() => state.lastCopiedText === '$$\\frac{a}{b}$$', 'Copy LaTeX menu item copies display LaTeX');
  console.log('[ok] math copies as LaTeX via Ctrl+C selection and the Copy LaTeX menu item');

  // ---- Raw HTML support (rehype-raw + rehype-sanitize, GitHub allowlist) + sanitization ----
  // GFM docs (e.g. libdictenstein) embed diagrams as raw <img> tags; these must render, with relative src
  // rewritten to file://. Dangerous raw HTML (script/on*-handlers/javascript:) must be stripped so a
  // malicious .md cannot script the privileged renderer.
  const rawHtmlDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-rawhtml-'));
  fs.mkdirSync(path.join(rawHtmlDir, 'diagrams'), { recursive: true });
  fs.writeFileSync(path.join(rawHtmlDir, 'diagrams', 'fig.svg'),
    '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20" fill="#3b82f6"/></svg>');
  const rawHtmlDoc = path.join(rawHtmlDir, 'raw-html.md');
  state.contentByPath.set(rawHtmlDoc, {
    path: rawHtmlDoc, kind: 'markdown',
    text: [
      '# Raw HTML', '',
      '<img src="diagrams/fig.svg" alt="a figure" width="100%"/>', '',
      '<details><summary>more</summary>hidden body</details>', '',
      '<table><thead><tr><th>H</th></tr></thead><tbody><tr><td>cell</td></tr></tbody></table>', '',
      'E = mc<sup>2</sup> and H<sub>2</sub>O', '',
      '<script>window.__vvxss = true;</script>',
      '<img src="does-not-exist" onerror="window.__vvxss = true;">',
      '<a href="javascript:window.__vvxss = true;">click</a>', ''
    ].join('\n'),
    stamp: Date.now()
  });
  await evalIn(win, `window.__vvxss = false`);
  win.webContents.send('vv:open-files', { paths: [rawHtmlDoc] });
  await waitFor(() => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Raw HTML'`), 'raw-html fixture');
  // Renders: raw <img> relative src rewritten to file://, width preserved; other GitHub-allowed tags render.
  const rawImg = await evalIn(win, `(() => {
    const img = document.querySelector('.markdown-body img[alt="a figure"]');
    return img ? { src: img.getAttribute('src') || '', width: img.getAttribute('width') } : null;
  })()`);
  assert.ok(rawImg, 'raw <img> tag renders as a real element');
  assert.ok(rawImg.src.startsWith('file://') && rawImg.src.includes('diagrams/fig.svg'),
    'raw <img> relative src rewritten to file://: ' + rawImg.src);
  assert.strictEqual(rawImg.width, '100%', 'raw <img> width="100%" preserved through the sanitizer');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.markdown-body details > summary'))`), true, 'raw <details><summary> renders');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.markdown-body table td'))`), true, 'raw <table> renders');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.markdown-body sup') && document.querySelector('.markdown-body sub'))`), true, 'raw <sup>/<sub> render');
  // Sanitized: no script/onerror/javascript:, no XSS fired, window.vv bridge intact.
  const xss = await evalIn(win, `(() => ({
    script: Boolean(document.querySelector('.markdown-body script')),
    onerror: Boolean(Array.from(document.querySelectorAll('.markdown-body img')).find(i => i.hasAttribute('onerror'))),
    jsHref: Boolean(Array.from(document.querySelectorAll('.markdown-body a')).find(a => (a.getAttribute('href')||'').toLowerCase().startsWith('javascript:'))),
    fired: window.__vvxss === true,
    vvIntact: typeof window.vv === 'object' && window.vv !== null
  }))()`);
  assert.strictEqual(xss.script, false, '<script> stripped by the sanitizer');
  assert.strictEqual(xss.onerror, false, 'onerror handler stripped by the sanitizer');
  assert.strictEqual(xss.jsHref, false, 'javascript: href stripped by the sanitizer');
  assert.strictEqual(xss.fired, false, 'no injected script executed (window.__vvxss stayed false)');
  assert.strictEqual(xss.vvIntact, true, 'window.vv bridge not clobbered by author id/name');
  console.log('[ok] raw HTML renders (img/table/details/sub/sup); script/onerror/javascript: sanitized; no XSS');

  // Standalone raw block <img> figures must be CENTERED like markdown ![]() images. A raw block <img> is
  // wrapped as <a.vv-figure-link><img></a> that is a DIRECT child of .markdown-body (never inside a <p>), so
  // the new `.markdown-body > a.vv-figure-link` rule centers it. figures.cljs sizes the tiny fixture SVG
  // narrow, so real, equal side gaps prove centering.
  await waitFor(() => evalIn(win, `(() => {
    const img = document.querySelector('.markdown-body > a.vv-figure-link > img[alt="a figure"]');
    const w = img && img.getBoundingClientRect().width;
    return Boolean(w) && w < 200;
  })()`), 'raw <img> figure sized narrow by figures.cljs');
  const fig = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const img  = document.querySelector('.markdown-body > a.vv-figure-link > img[alt="a figure"]');
    if (!body || !img) return null;
    const wrap = img.parentElement;
    const bs = getComputedStyle(body);
    const b = body.getBoundingClientRect(), r = img.getBoundingClientRect();
    const leftGap  = r.left - (b.left + (parseFloat(bs.paddingLeft) || 0));
    const rightGap = (b.right - (parseFloat(bs.paddingRight) || 0)) - r.right;
    return { directChild: wrap.parentElement === body, imgWidth: Math.round(r.width),
             leftGap: Math.round(leftGap), rightGap: Math.round(rightGap) };
  })()`);
  assert.ok(fig, 'raw <img> figure + its .vv-figure-link wrapper exist');
  assert.strictEqual(fig.directChild, true, 'raw block <img> wrapper is a direct child of .markdown-body (not inside a <p>)');
  assert.ok(fig.leftGap > 4 && fig.rightGap > 4, `figure has real side gaps (centered, not full-width): L=${fig.leftGap} R=${fig.rightGap}`);
  assert.ok(Math.abs(fig.leftGap - fig.rightGap) <= 2, `raw <img> figure is centered (leftGap≈rightGap): ${fig.leftGap} vs ${fig.rightGap}`);
  console.log('[ok] raw <img> block figures are centered');

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

  // RASTER images "jumpy when scrolling" fix: PNG/JPG/etc. now reserve their layout box from intrinsic dims
  // (width/height attributes, header-parsed) so they don't reflow (pop from ~0 → full height) as they decode
  // while scrolling. Previously raster images were left dimensionless (clear-size!) → the jump.
  const rasterFixture = createLocalRasterScrollFixture();
  state.contentByPath.set(rasterFixture.docPath, {
    path: rasterFixture.docPath,
    kind: 'markdown',
    text: rasterFixture.text,
    stamp: Date.now()
  });
  win.webContents.send('vv:open-files', { paths: [rasterFixture.docPath] });
  await waitFor(
    () => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local Raster Scroll'`),
    'local raster scroll markdown document'
  );
  // the box must be reserved from intrinsic dims (width/height ATTRIBUTES), independent of byte decode
  await waitFor(
    () => evalIn(win, `Array.from(document.querySelectorAll('.markdown-body img'))
      .every((img) => img.hasAttribute('width') && img.hasAttribute('height')
                      && Number(img.getAttribute('width')) > 0 && Number(img.getAttribute('height')) > 0)`),
    'raster images get intrinsic width/height attributes (layout box reserved)'
  );
  const rasterAttrs = await evalIn(win, `Array.from(document.querySelectorAll('.markdown-body img'))
    .map((img) => img.getAttribute('width') + 'x' + img.getAttribute('height'))`);
  assert.deepStrictEqual(rasterAttrs, ['800x200', '200x600', '400x400'],
    'raster images must carry their intrinsic dimensions (header-parsed PNG IHDR) as width/height attributes');
  await waitFor(
    () => evalIn(win, `(() => {
      const imgs = Array.from(document.querySelectorAll('.markdown-body img'));
      return imgs.length === 3 && imgs.every((img) => img.complete && img.naturalWidth > 0);
    })()`),
    'local raster images load'
  );
  const rasterStart = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    window.__vvRasterImages = imgs;
    window.__vvRasterMutations = 0;
    window.__vvRasterObserver?.disconnect?.();
    window.__vvRasterObserver = new MutationObserver((records) => {
      window.__vvRasterMutations += records.filter((record) => record.type === 'childList').length;
    });
    window.__vvRasterObserver.observe(body, { childList: true });
    return {
      imageCount: imgs.length,
      dims: imgs.map((img) => {
        const rect = img.getBoundingClientRect();
        return { width: Math.round(rect.width), height: Math.round(rect.height),
                 ratio: Math.round((rect.width / rect.height) * 100) };
      })
    };
  })()`);
  assert.strictEqual(rasterStart.imageCount, 3, 'local raster fixture must render three images');
  // reserved boxes must match the intrinsic aspect ratios (800/200=4.00, 200/600=0.33, 400/400=1.00),
  // responsively (width clamped to the column, height from the ratio) — this is what stops the jump
  assert.deepStrictEqual(rasterStart.dims.map((d) => d.ratio), [400, 33, 100],
    'reserved raster boxes must match their intrinsic aspect ratios');
  win.show();
  await delay(100);
  win.focus();
  win.webContents.focus();
  for (let i = 0; i < rasterStart.imageCount; i++) {
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
      `wheel scroll over local raster ${i + 1}`,
      2000
    );
  }
  const rasterResult = await evalIn(win, `(() => {
    const body = document.querySelector('.markdown-body');
    const imgs = Array.from(body.querySelectorAll('img'));
    const dims = imgs.map((img) => {
      const rect = img.getBoundingClientRect();
      return { width: Math.round(rect.width), height: Math.round(rect.height),
               ratio: Math.round((rect.width / rect.height) * 100) };
    });
    const result = {
      childListMutations: window.__vvRasterMutations,
      sameImages: Array.isArray(window.__vvRasterImages) &&
        window.__vvRasterImages.length === imgs.length &&
        imgs.every((img, index) => img === window.__vvRasterImages[index]),
      dims,
      docStillOpen: document.querySelector('.markdown-body h1')?.textContent.trim() === 'Local Raster Scroll'
    };
    window.__vvRasterObserver?.disconnect?.();
    window.__vvRasterObserver = null;
    window.__vvRasterImages = null;
    return result;
  })()`);
  assert.strictEqual(rasterResult.childListMutations, 0, 'wheel scrolling raster images must not rebuild markdown body children');
  assert.strictEqual(rasterResult.sameImages, true, 'wheel scrolling raster images must not recreate image nodes');
  assert.strictEqual(rasterResult.docStillOpen, true, 'wheel scrolling raster images must keep the markdown document open');
  assert.deepStrictEqual(rasterResult.dims, rasterStart.dims, 'raster image dimensions must stay stable while scrolling over them (no layout jump)');
  console.log('[ok] local raster markdown images reserve a box + scroll without layout jump');

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
    return { src: (img && img.getAttribute('src')) || '', insideHost: Boolean(img && img.closest('.vv-web-host')),
             complete: Boolean(img && img.complete && img.naturalWidth > 0) };
  })()`);
  assert.ok(frozen.src.startsWith('data:image'), 'snapshot img must show the captured page raster');
  assert.strictEqual(frozen.insideHost, true, 'snapshot img must render inside the web host');
  assert.strictEqual(state.httpVisible, false, 'native view must hide while the frozen snapshot is shown');
  // Bug D (round 5): the native view is hidden only AFTER the snapshot <img> has decoded (img.decode() before
  // hide!), so the frozen raster is on-screen the instant the view goes away — no one-frame blank "blink".
  assert.strictEqual(frozen.complete, true, 'Bug D: the frozen snapshot img must be DECODED (complete) once the native view is hidden');
  console.log('[ok] opening a menu freezes a DECODED page snapshot before hiding the view (Bug D: no blink)');

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

  // ── Bug B (round 4): a web page's late did-navigate updates its OWNER (http) tab, not the active tab ──
  await sendChord(win, 'T', ['control']);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-empty')?.textContent.trim() === 'New Tab'`), 'fresh tab for owner-routing test');
  state.httpShow = null;
  await evalIn(win, `(() => { const i = document.querySelector('.vv-uri-input'); i.focus();
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'http://127.0.0.1:9/owner'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await delay(60);
  await evalIn(win, `document.querySelector('.vv-uri-input').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))`);
  await waitFor(() => state.httpShow && Number.isInteger(state.httpShow.tabId), 'httpShow carries the owner tab id');
  const ownerTab = state.httpShow.tabId;
  await sendChord(win, 'T', ['control']);   // a different active tab; the http tab's late nav must not hijack it
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] !== ${ownerTab}`), 'a non-owner tab is active');
  const activeTab = await evalIn(win, `window.__vvdb().ui['active-tab']`);
  win.webContents.send('vv:http-navigated', { url: 'http://127.0.0.1:9/loaded-late', tab: ownerTab });
  await waitFor(() => evalIn(win, `(window.__vvdb().ui.tabs.find(t => t.id === ${ownerTab}) || {}).uri === 'http://127.0.0.1:9/loaded-late'`),
    'the owner http tab receives the late navigation');
  const activeUri = await evalIn(win, `(window.__vvdb().ui.tabs.find(t => t.id === ${activeTab}) || {}).uri`);
  assert.ok(activeUri == null || !String(activeUri).startsWith('http'),
    'the active (non-owner) tab must NOT be hijacked by the web view navigation');
  // a relay for a closed/unknown tab id is a harmless no-op
  const tabsBeforeGhost = await evalIn(win, `JSON.stringify(window.__vvdb().ui.tabs.map(t => [t.id, t.uri || null]))`);
  win.webContents.send('vv:http-navigated', { url: 'http://127.0.0.1:9/ghost', tab: 987654 });
  await delay(80);
  const tabsAfterGhost = await evalIn(win, `JSON.stringify(window.__vvdb().ui.tabs.map(t => [t.id, t.uri || null]))`);
  assert.strictEqual(tabsAfterGhost, tabsBeforeGhost, 'a navigation for a closed/unknown tab id must be a no-op');
  console.log('[ok] web-view navigation updates its owner tab, never the active tab');

  // ════════ Dialog UIX — the shared modal shell + each dialog's features ════════
  // open a top-level menu (Alt+<key>) and click the dropdown/sub-dropdown item whose text matches
  const openMenuItem = async (menuKey, itemText) => {
    await dispatchWindowKey(win, menuKey, { altKey: true });
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-menu-dropdown'))`), `Alt+${menuKey} menu`);
    await evalIn(win, `(() => {
      const it = Array.from(document.querySelectorAll('.vv-menu-dropdown .vv-menu-item, .vv-menu-subdropdown .vv-menu-item'))
        .find((n) => n.textContent.includes(${JSON.stringify(itemText)}));
      if (it) it.click();
      return Boolean(it);
    })()`);
  };
  const modalShown = () => evalIn(win, `Boolean(document.querySelector('.vv-modal'))`);
  // dispatch a bubbling Escape on the dialog panel (the shared modal handles Esc on the panel, not the window)
  const escModal = () => evalIn(win, `(() => { const p = document.querySelector('.vv-modal');
    if (p) p.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    return Boolean(p); })()`);

  // ── Settings/Preferences — exercises the SHARED modal behaviors (autofocus, modality, focus-trap, Esc) ──
  await openMenuItem('s', 'Preferences');
  await waitFor(() => evalIn(win, `(() => { const m = document.querySelector('.vv-modal');
    return Boolean(m) && /Preferences/.test(m.querySelector('.vv-modal-title')?.textContent || '')
           && Boolean(m.querySelector('.vv-modal-x')); })()`), 'Settings dialog: title + ✕ render');
  assert.strictEqual(await evalIn(win, `(() => { const m = document.querySelector('.vv-modal');
    return m === document.activeElement || m.contains(document.activeElement); })()`), true,
    'opening a modal moves focus into the dialog (autofocus)');
  // true modality: a background command chord (Ctrl+T → new tab) must NOT fire while a modal owns the
  // keyboard. A window-dispatched chord reaches the keymap resolver directly, so this validates the
  // resolver's :modal-open? guard (no false-pass from a key that simply wasn't delivered).
  const tabsDuringModal = await evalIn(win, `document.querySelectorAll('.vv-tab').length`);
  await dispatchWindowKey(win, 't', { ctrlKey: true });
  await delay(140);
  assert.strictEqual(await evalIn(win, `document.querySelectorAll('.vv-tab').length`), tabsDuringModal,
    'a modal suppresses background command chords (Ctrl+T added no tab while Settings was open)');
  assert.strictEqual(await modalShown(), true, 'the Settings modal stayed open');
  // focus trap: Tab from the last focusable wraps to the first
  assert.strictEqual(await evalIn(win, `(() => {
    const m = document.querySelector('.vv-modal');
    const sel = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),[tabindex]:not([tabindex="-1"])';
    const f = Array.from(m.querySelectorAll(sel)).filter((e) => e.offsetWidth > 0);
    if (f.length < 2) return false;
    f[f.length - 1].focus();
    f[f.length - 1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
    return document.activeElement === f[0];
  })()`), true, 'Tab wraps focus within the dialog (focus trap)');
  // editing a font field live-applies + persists (:settings/set)
  await evalIn(win, `(() => { const i = document.querySelector('.vv-pref-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'Iosevka'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await waitFor(() => evalIn(win, `window.__vvdb().ui.settings['font-variable'] === 'Iosevka'`),
    'editing a font field updates settings live');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['settings-open?']`), 'Esc closes the Settings dialog');
  console.log('[ok] Settings: autofocus + modality + focus-trap + live font edit + Esc-close');

  // ✕ and backdrop-click also close (consistency)
  await openMenuItem('s', 'Preferences');
  await waitFor(modalShown, 'Settings reopened (✕)');
  await evalIn(win, `document.querySelector('.vv-modal-x').click()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['settings-open?']`), '✕ closes the dialog');
  await openMenuItem('s', 'Preferences');
  await waitFor(modalShown, 'Settings reopened (backdrop)');
  await evalIn(win, `document.querySelector('.vv-modal-overlay').click()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['settings-open?']`), 'backdrop click closes the dialog');
  console.log('[ok] Settings: ✕ and backdrop-click both close');

  // ── About ──
  await openMenuItem('h', 'About');
  await waitFor(() => evalIn(win, `(() => { const m = document.querySelector('.vv-modal.vv-about');
    return Boolean(m) && m.textContent.includes('smoke-test'); })()`), 'About shows the app version');
  assert.strictEqual(await evalIn(win, `Boolean(document.querySelector('.vv-about .vv-about-link'))`), true,
    'About has the repository link');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['about-open?']`), 'Esc closes About');
  console.log('[ok] About: name/version/link render + Esc-close');

  // ── Command palette: open → fuzzy filter → arrow-select → Esc close ──
  await openMenuItem('h', 'Command Palette');
  // wait for the input DOM (not just the open? state) before driving it — else the native value setter below
  // can fire with a null element (Illegal invocation) if reagent hasn't committed the input yet.
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.palette['open?'] && document.querySelector('.vv-palette-input'))`), 'palette opens');
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(i, 'tab'); i.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
  await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-palette-item').length > 0`),
    'palette fuzzy-filters to tab commands');
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    i.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true })); return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-palette-selected'))`), 'ArrowDown selects a row');
  await evalIn(win, `(() => { const i = document.querySelector('.vv-palette-input');
    i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); return true; })()`);
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.palette['open?']`), 'Escape closes the palette');
  console.log('[ok] Command palette: open → fuzzy filter → arrow-select → Esc-close');

  // ── Extensions: opens, ad-block toggle flips state, Esc closes ──
  await openMenuItem('s', 'Extensions');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-modal')) && Boolean(document.querySelector('.vv-ext-sect'))`),
    'Extensions dialog open');
  const ab0 = await evalIn(win, `Boolean(window.__vvdb().ui.adblock && window.__vvdb().ui.adblock['enabled?'])`);
  await evalIn(win, `(() => { const cb = document.querySelector('.vv-ext-sect input[type="checkbox"]'); cb.click(); return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.adblock && window.__vvdb().ui.adblock['enabled?']) !== ${ab0}`),
    'ad-block toggle flips its state');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui['extensions-open?']`), 'Esc closes Extensions');
  console.log('[ok] Extensions: opens + ad-block toggle + Esc-close');

  // ── Passwords panel (populated from the mocked bridge state) ──
  await openMenuItem('s', 'Passwords');
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-modal.vv-pw-dialog'))`), 'Passwords dialog open');
  await waitFor(() => evalIn(win, `(document.querySelector('.vv-pw-providers')?.textContent || '').includes('1Password')`),
    'a provider row renders from mocked state');
  await escModal();
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.passwords['open?']`), 'Esc closes Passwords');
  console.log('[ok] Passwords panel: opens with provider rows + Esc-close');

  // ── Passwords save-prompt (push-driven) → renders, Save enabled, Dismiss notifies main ──
  state.pwDismiss = null;
  win.webContents.send('vv:password-save-prompt', { token: 'tok-1', origin: 'https://example.com', username: 'alice',
    providers: [{ id: 'op', label: '1Password', status: 'ready', 'save-supported?': true }] });
  await waitFor(() => evalIn(win, `(() => { const m = document.querySelector('.vv-modal.vv-pw-save');
    return Boolean(m) && m.textContent.includes('Save Login'); })()`), 'save-prompt renders');
  assert.strictEqual(await evalIn(win, `!document.querySelector('.vv-pw-save .vv-btn[disabled]')`), true,
    'Save is enabled once a provider auto-selects');
  await evalIn(win, `(() => { const b = Array.from(document.querySelectorAll('.vv-pw-save .vv-btn'))
    .find((x) => x.textContent.includes('Dismiss')); if (b) b.click(); return Boolean(b); })()`);
  await waitFor(() => state.pwDismiss === 'tok-1', 'Dismiss notifies main with the token');
  await waitFor(() => evalIn(win, `!window.__vvdb().ui.passwords['save-prompt']`), 'Dismiss clears the save-prompt');
  console.log('[ok] Passwords save-prompt: renders + Save enabled + Dismiss');

  // ── Error view text is selectable AND copyable (Ctrl+C) — selectable-root now includes .vv-error ──
  const errPath = path.join(ROOT, 'test', 'fixtures', 'copy-error.md');
  win.webContents.send('vv:open-files', { paths: [errPath] });
  await waitFor(() => evalIn(win, `window.__vvdb().ui.tabs.some((t) => t.uri === ${JSON.stringify(errPath)})`),
    'a tab exists for the error path');
  win.webContents.send('vv:error', { path: errPath, message: 'EISDIR: illegal operation on a directory, read' });
  await waitFor(() => evalIn(win, `(document.querySelector('.vv-error')?.textContent || '').includes('EISDIR')`),
    'the error view renders');
  state.lastCopiedText = null;
  await evalIn(win, `(() => { const el = document.querySelector('.vv-error');
    const r = document.createRange(); r.selectNodeContents(el);
    const s = window.getSelection(); s.removeAllRanges(); s.addRange(r); return s.toString(); })()`);
  assert.strictEqual(await dispatchWindowKey(win, 'c', { ctrlKey: true }), true, 'Ctrl+C is handled for an error selection');
  await waitFor(() => state.lastCopiedText && state.lastCopiedText.includes('EISDIR'),
    'selected error text copies to the clipboard');
  console.log('[ok] error view text is selectable and copyable (Ctrl+C)');

  // ── Multi-argument launch — `vv a.md b.md https://x` opens each argument in its own tab (first focused) ──
  // The main process parses every non-flag argument (startup/doc-uris, unit-tested) and pushes them over THIS
  // channel with focus-first; here we drive the renderer pipeline directly with one real IPC to prove the live
  // outcome: every argument gets a tab, the extra-argument tabs preserve command-line order, and the FIRST
  // argument's tab ends active (the Open dialog, by contrast, leaves the last-opened tab active).
  const argA = path.join(ROOT, 'test', 'fixtures', 'multi-arg-a.md');
  const argB = path.join(ROOT, 'test', 'fixtures', 'multi-arg-b.md');
  const argC = 'http://127.0.0.1:9/multi-arg-c';
  for (const p of [argA, argB]) {
    state.contentByPath.set(p, { path: p, kind: 'markdown', text: `# ${path.basename(p)}\n\nbody`, stamp: Date.now() });
  }
  const idsBeforeMulti = await evalIn(win, `window.__vvdb().ui.tabs.map((t) => t.id)`);
  win.webContents.send('vv:open-files', { paths: [argA, argB, argC], 'focus-first': true });
  await waitFor(() => evalIn(win, `(() => {
    const uris = window.__vvdb().ui.tabs.map((t) => t.uri);
    return [${JSON.stringify(argA)}, ${JSON.stringify(argB)}, ${JSON.stringify(argC)}].every((u) => uris.includes(u));
  })()`), 'multi-arg launch opens a tab for every argument');
  const multi = await evalIn(win, `(() => {
    const ui = window.__vvdb().ui;
    const idx = (u) => ui.tabs.findIndex((t) => t.uri === u);
    const tabFor = (u) => ui.tabs.find((t) => t.uri === u) || {};
    const active = ui.tabs.find((t) => t.id === ui['active-tab']);
    const before = ${JSON.stringify(idsBeforeMulti)};
    return {
      activeUri: active ? active.uri : null,
      bNew: !before.includes(tabFor(${JSON.stringify(argB)}).id),
      cNew: !before.includes(tabFor(${JSON.stringify(argC)}).id),
      bBeforeC: idx(${JSON.stringify(argB)}) < idx(${JSON.stringify(argC)})
    };
  })()`);
  assert.strictEqual(multi.activeUri, argA, 'multi-arg launch must focus the FIRST argument\'s tab');
  assert.ok(multi.bNew && multi.cNew, 'every non-first argument must open in its own NEW tab');
  assert.ok(multi.bBeforeC, 'the additional argument tabs must preserve command-line order');
  console.log('[ok] multi-arg launch: a tab per argument, command-line order, first focused');

  // ── Ctrl+PageDown / Ctrl+PageUp cycle tabs right / left (browser-style, wrapping) ──
  // A real window keydown: the capture-phase scroll handler ignores it (its `no-cam?` guard requires NO Ctrl),
  // so the bubble-phase resolver maps C-next/C-prior → :tab/next/:tab/prev — the same wrapping commands as the
  // Vim g t / g T sequence. (The default keymap is active at this point.)
  const cyc = await evalIn(win, `(() => { const ui = window.__vvdb().ui;
    return { active: ui['active-tab'], order: ui.tabs.map((t) => t.id) }; })()`);
  assert.ok(cyc.order.length >= 2, 'the multi-arg block must leave >= 2 tabs open for the cycle test');
  const cycNext = cyc.order[(cyc.order.indexOf(cyc.active) + 1) % cyc.order.length];
  await dispatchWindowKey(win, 'PageDown', { ctrlKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${cycNext}`),
    'Ctrl+PageDown activates the next (right) tab');
  await dispatchWindowKey(win, 'PageUp', { ctrlKey: true });
  await waitFor(() => evalIn(win, `window.__vvdb().ui['active-tab'] === ${cyc.active}`),
    'Ctrl+PageUp returns to the previous (left) tab');
  console.log('[ok] Ctrl+PageDown / Ctrl+PageUp cycle tabs right / left');

  win.close();
}

const hardTimeout = setTimeout(() => {
  cleanupTempDirs();
  console.error('Electron smoke test timed out');
  app.exit(1);
}, 75000);

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
