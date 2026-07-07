'use strict';

// Automated, headless screenshot harness for vinary-viewer.
//
// Boots the real renderer (resources/public/index.html) in an 800x600 Electron window, mocks the
// main-process IPC seam (like test/electron-smoke.js), drives each feature into view, and writes a
// deterministic 800x600 PNG per scene into docs/screenshots/. Parsed content kinds (office, table, log,
// archive) are produced by the REAL content service (src/vinary/main/content_service.js) so the previews
// are authentic; the trivial kinds (markdown/source/image/pdf/mermaid/directory) are fed directly with the
// same payload shapes main/service.cljs sends.
//
// Everything renders in the renderer DOM and is captured from the main window EXCEPT the in-app web view,
// which is a native WebContentsView overlay: for that one scene we create the overlay ourselves, capture
// its own webContents, and composite it onto the chrome with ImageMagick.
//
// Run: electron --no-sandbox scripts/screenshots.cjs            (needs a DISPLAY / xvfb)
//      npm run screenshots                                      (wraps it in xvfb-run + the asset syncs)
//      electron --no-sandbox scripts/screenshots.cjs --only=native-pdf,web-preview   (subset, for iteration)

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
process.env.ELECTRON_OZONE_PLATFORM_HINT = 'x11';
process.env.GDK_BACKEND = 'x11';
process.env.XDG_SESSION_TYPE = 'x11';
delete process.env.WAYLAND_DISPLAY;

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { app, BrowserWindow, ipcMain, WebContentsView } = require('electron');
const contentService = require('../src/vinary/main/content_service.js');

const ROOT = path.resolve(__dirname, '..');
const INDEX = path.join(ROOT, 'resources', 'public', 'index.html');
const PRELOAD = path.join(ROOT, 'resources', 'preload.js');
const WEB_PRELOAD = path.join(ROOT, 'resources', 'web-preload.js');
const OUT = path.join(ROOT, 'docs', 'screenshots');
const SAMPLES = path.join(OUT, 'samples');
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'vv-shots-'));

const W = 800;
const H = 600;
const GITHUB_URL = 'https://github.com/vinary-tree/vinary-viewer';

app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
app.commandLine.appendSwitch('ozone-platform', 'x11');
app.commandLine.appendSwitch('force-device-scale-factor', '1'); // 1 CSS px == 1 device px -> exact 800x600

// sample files
const SHOWCASE = path.join(SAMPLES, 'showcase.md');
const MMD = path.join(SAMPLES, 'live-refresh.mmd');
const MATH = path.join(SAMPLES, 'math.md');
const CSV = path.join(SAMPLES, 'data.csv');
const LOGF = path.join(SAMPLES, 'app.log');
const DOCX = path.join(SAMPLES, 'report.docx');
const ZIPF = path.join(SAMPLES, 'bundle.zip');
const PDF = path.join(SAMPLES, 'fuzzy-rho.pdf');
// real repo files
const LOGO = path.join(ROOT, 'resources', 'public', 'assets', 'vinary-tree-logo.svg');
const VIEWS = path.join(ROOT, 'src', 'vinary', 'ui', 'views.cljs');
const PKG = path.join(ROOT, 'package.json');
const README = path.join(ROOT, 'README.md');
const REACTIVE = path.join(ROOT, 'docs', 'theory', '01-reactive-architecture.md');
const KBDOC = path.join(ROOT, 'docs', 'features', '15-custom-keybindings.md');
const FEATURES_DIR = path.join(ROOT, 'docs', 'features');

const delay = (ms) => new Promise((r) => setTimeout(r, ms));
const read = (p) => fs.readFileSync(p, 'utf8');
const now = () => Date.now();

// ---- filesystem payload helpers (mirror main/service.cljs send-content! + main/service.cljs list-dir) ----
function dirEntries(dir) {
  let ds;
  try { ds = fs.readdirSync(dir, { withFileTypes: true }); } catch (_) { return []; }
  return ds.map((d) => {
    const name = d.name;
    const abs = path.join(dir, name);
    let st = null;
    try { st = fs.lstatSync(abs); } catch (_) { st = null; }
    const link = !!(st && st.isSymbolicLink());
    let st2 = st;
    if (link) { try { st2 = fs.statSync(abs); } catch (_) { /* dangling symlink: keep lstat */ } }
    return {
      name,
      path: abs,
      'dir?': !!(st2 && st2.isDirectory()),
      size: st2 ? st2.size : null,
      mtime: st2 ? st2.mtimeMs : null,
      symlink: link
    };
  });
}

function isDir(p) {
  try { return fs.statSync(p).isDirectory(); } catch (_) { return false; }
}

// URI-bar path completion, replicating main/service.cljs `complete`.
function completePath(raw) {
  let s = String(raw);
  if (s.startsWith('file://')) s = s.slice(7);
  if (s === '~') s = os.homedir();
  else if (s.startsWith('~/')) s = path.join(os.homedir(), s.slice(2));
  const sepI = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
  const dirPart = sepI < 0 ? '.' : s.slice(0, sepI + 1);
  let parent;
  try { parent = path.resolve(dirPart); } catch (_) { parent = dirPart; }
  const entries = isDir(parent) ? dirEntries(parent) : [];
  let target;
  try { target = path.resolve(s); } catch (_) { target = s; }
  let st = null;
  try { st = fs.statSync(target); } catch (_) { st = null; }
  return { input: String(raw), dir: parent, target, entries, 'exists?': !!st, 'dir?': !!(st && st.isDirectory()) };
}

// vv:content payload for a scene path. Parsed kinds run the real content service; the rest mirror service.cljs.
async function payloadFor(kind, p) {
  const stamp = now();
  switch (kind) {
    case 'markdown':
    case 'source':
    case 'mermaid':
    case 'text':
      return { path: p, kind, text: read(p), stamp };
    case 'image':
      return { path: p, kind: 'image', stamp };
    case 'pdf':
      return { path: p, kind: 'pdf', bytes: new Uint8Array(fs.readFileSync(p)), stamp };
    case 'directory':
      return { path: p, kind: 'directory', entries: dirEntries(p), stamp };
    case 'parsed': {
      const payload = await contentService.openUri(p); // office / table / log / archive
      payload.stamp = stamp;
      payload.path = p; // archive listings come back keyed by a vv-archive:// URI; re-key to the opened tab path
      return payload;
    }
    default:
      throw new Error('unknown kind ' + kind);
  }
}

// ---- renderer driving ----
async function evalIn(win, src) {
  return win.webContents.executeJavaScript(src, true);
}

async function waitFor(fn, label, timeoutMs = 8000) {
  const deadline = now() + timeoutMs;
  let last = null;
  while (now() < deadline) {
    try { const v = await fn(); if (v) return v; } catch (e) { last = e; }
    await delay(50);
  }
  throw new Error('timeout waiting for ' + label + (last ? ': ' + last.message : ''));
}

// gentler poller for eventually-consistent state that settles over seconds (pdf.js, web load, reagent re-renders)
async function waitCalm(win, expr, label, timeoutMs = 20000) {
  const deadline = now() + timeoutMs;
  let last = null;
  while (now() < deadline) {
    try { last = await evalIn(win, expr); if (last) return last; } catch (e) { last = 'ERR ' + e.message; }
    await delay(400);
  }
  throw new Error('timeout waiting for ' + label + ' (last=' + JSON.stringify(last) + ')');
}

async function dispatchWindowKey(win, key, opts = {}) {
  return evalIn(win, `(() => {
    const e = new KeyboardEvent('keydown', { key: ${JSON.stringify(key)},
      altKey: ${!!opts.altKey}, ctrlKey: ${!!opts.ctrlKey}, shiftKey: ${!!opts.shiftKey}, metaKey: ${!!opts.metaKey},
      bubbles: true, cancelable: true });
    window.dispatchEvent(e); return e.defaultPrevented; })()`);
}

async function sendChord(win, keyCode, modifiers = []) {
  win.focus();
  win.webContents.focus();
  win.webContents.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
  win.webContents.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
}

// set a controlled <input>/<textarea> value the way React/reagent sees it, then fire input+change.
async function setInput(win, selector, value) {
  const ok = await evalIn(win, `(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return false;
    el.focus();
    const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`);
  if (!ok) throw new Error('input not found: ' + selector);
}

async function clickSel(win, selector) {
  const ok = await evalIn(win, `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (el) { el.click(); return true; } return false; })()`);
  if (!ok) throw new Error('element not found: ' + selector);
}

async function clickByText(win, selector, text) {
  const ok = await evalIn(win, `(() => {
    const el = [...document.querySelectorAll(${JSON.stringify(selector)})].find(n => n.textContent.includes(${JSON.stringify(text)}));
    if (el) { el.click(); return true; } return false; })()`);
  if (!ok) throw new Error('no ' + selector + ' matching text: ' + text);
}

async function openMenu(win, key) {
  await dispatchWindowKey(win, key, { altKey: true });
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-menu-dropdown'))`), 'menu ' + key);
}

async function hoverItem(win, text) {
  return evalIn(win, `(() => {
    const it = [...document.querySelectorAll('.vv-menu-dropdown .vv-menu-item')].find(n => n.textContent.includes(${JSON.stringify(text)}));
    if (!it) return false;
    const r = it.getBoundingClientRect();
    it.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true, clientX: r.left + 8, clientY: r.top + 8 }));
    return true; })()`);
}

async function ensureSidebar(win) {
  // expand the sidebar if collapsed to a rail
  await evalIn(win, `(() => { const r = document.querySelector('.vv-sidebar-rail'); if (r) r.click(); return true; })()`);
  await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-sidebar'))`), 'sidebar visible');
}

async function clickSidebarTab(win, label) {
  await ensureSidebar(win);
  await clickByText(win, '.vv-sidebar-tab', label);
  await waitFor(() => evalIn(win, `document.querySelector('.vv-sidebar-tab-active')?.textContent.includes(${JSON.stringify(label)})`), 'sidebar tab ' + label);
}

// open a doc through the normal flow: seed the mocked content, then send vv:open-files.
async function openDoc(win, state, p, payload, waitExpr, timeoutMs = 15000) {
  state.contentByPath.set(p, payload);
  win.webContents.send('vv:open-files', { paths: [p] });
  if (waitExpr) await waitFor(() => evalIn(win, waitExpr), 'content ' + path.basename(p), timeoutMs);
}

async function settle(win) {
  try { await evalIn(win, `document.fonts.ready.then(() => true)`); } catch (_) { /* fonts API unavailable */ }
  await evalIn(win, `new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))`);
}

async function shoot(win, name) {
  await settle(win);
  const img = await win.webContents.capturePage({ x: 0, y: 0, width: W, height: H });
  const file = path.join(OUT, name + '.png');
  fs.writeFileSync(file, img.toPNG());
  const sz = img.getSize();
  if (sz.width !== W || sz.height !== H) throw new Error(`${name}: captured ${sz.width}x${sz.height}, expected ${W}x${H}`);
  console.log('  [shot]', name);
}

// reset to a clean renderer between scenes (fresh app-db, dark theme, 0 tabs = watermark).
async function resetRenderer(win, state) {
  if (state.webView) state.webView.setVisible(false);
  win.webContents.reload();
  await waitFor(
    () => evalIn(win, `Boolean(window.__vvdb && document.querySelector('.vv-menubar') && document.querySelector('.vv-tab-new'))`),
    'renderer boot');
}

// ---- IPC mocks (superset of test/electron-smoke.js installIpc) ----
function installIpc(state) {
  const reply = (event, ch, payload) => event.sender.send(ch, payload);
  ipcMain.on('vv:settings-request', (e) => reply(e, 'vv:settings', ''));
  ipcMain.on('vv:keymap-request', (e) => reply(e, 'vv:keymap', null));
  ipcMain.on('vv:grammars-request', (e) => reply(e, 'vv:grammars', ''));
  ipcMain.on('vv:recent-request', (e) => reply(e, 'vv:recent', ''));
  ipcMain.on('vv:ext-config-request', (e) => reply(e, 'vv:ext-config', ''));
  ipcMain.on('vv:ext-state-request', (e) => reply(e, 'vv:ext-state', { extensions: [], adblock: { 'enabled?': false } }));
  ipcMain.on('vv:app-info-request', (e) => reply(e, 'vv:app-info', {
    name: 'vinary-viewer', version: '0.2.0-dev',
    electron: process.versions.electron, chrome: process.versions.chrome, node: process.versions.node
  }));
  ipcMain.on('vv:open', (e, filePath) => {
    const payload = state.contentByPath.get(filePath);
    if (payload) e.sender.send('vv:content', Object.assign({}, payload, { stamp: now() }));
    else state.openedPaths.push(filePath);
  });
  ipcMain.on('vv:open-dialog', () => { state.openDialogs += 1; });
  ipcMain.on('vv:clipboard-write', (_e, text) => { state.lastCopiedText = text; });
  ipcMain.on('vv:watch-assets', () => {});
  ipcMain.on('vv:retained-files', () => {});
  ipcMain.on('vv:close', () => {});
  ipcMain.handle('vv:complete-path', (_e, raw) => completePath(raw));
  ipcMain.handle('vv:content-page', (_e, req) => contentService.contentPage(req));
  ipcMain.handle('vv:http-snapshot', () => state.snapshotDataUrl);
  // password bridge — a ready provider row (no real secrets)
  ipcMain.on('vv:password-state-request', (e) => reply(e, 'vv:password-state', {
    providers: [{ id: 'op', label: '1Password', status: 'ready', 'save-supported?': true, message: '' }],
    forms: { count: 0 }
  }));
  ipcMain.on('vv:password-search', (_e, url) => { state.pwSearch = url; });
  ipcMain.on('vv:password-fill', (_e, item) => { state.pwFill = item; });
  ipcMain.on('vv:password-save', (_e, p) => { state.pwSave = p; });
  ipcMain.on('vv:password-dismiss-save', (_e, tok) => { state.pwDismiss = tok; });
  // web view overlay — really create it so we can capture the page (child-view capture).
  ipcMain.on('vv:http-show', (_e, payload) => {
    state.httpShow = payload;
    state.httpVisible = true;
    const win = state.win;
    if (!state.webView) {
      const v = new WebContentsView({
        webPreferences: {
          contextIsolation: true, nodeIntegration: false,
          preload: WEB_PRELOAD, partition: 'persist:vinary-web'
        }
      });
      win.contentView.addChildView(v);
      state.webView = v;
    }
    const v = state.webView;
    v.setVisible(true);
    const b = payload && payload.bounds;
    if (b) v.setBounds({ x: Math.round(b.x), y: Math.round(b.y), width: Math.round(b.width), height: Math.round(b.height) });
    const url = payload && payload.url;
    if (url && url !== state.webUrl) {
      state.webUrl = url;
      state.webLoaded = new Promise((resolve) => {
        const wc = v.webContents;
        const to = setTimeout(() => resolve(false), 25000);
        wc.once('did-finish-load', () => { clearTimeout(to); resolve(true); });
        wc.once('did-fail-load', (_ev, code, desc, u, isMainFrame) => { if (isMainFrame) { clearTimeout(to); resolve(false); } });
      });
      v.webContents.loadURL(url);
    }
  });
  ipcMain.on('vv:http-hide', () => { state.httpVisible = false; if (state.webView) state.webView.setVisible(false); });
  ipcMain.on('vv:http-bounds', (_e, payload) => {
    const b = payload && payload.bounds;
    if (state.webView && b) state.webView.setBounds({ x: Math.round(b.x), y: Math.round(b.y), width: Math.round(b.width), height: Math.round(b.height) });
  });
  ipcMain.on('vv:http-zoom', () => {});
  ipcMain.on('vv:http-zoom-set', () => {});
  ipcMain.on('vv:http-scroll', () => {});
  ipcMain.on('vv:http-toc-goto', () => {});
  ipcMain.on('vv:context-open-link', () => {});
  ipcMain.on('vv:context-open-link-new-tab', () => {});
  ipcMain.on('vv:open-path', () => {});
  ipcMain.on('vv:open-external', () => {});
  ipcMain.on('vv:zoom', () => {});
  ipcMain.on('vv:zoom-set', () => {});
  ipcMain.on('vv:devtools', () => {});
  ipcMain.on('vv:quit', () => {});
  ipcMain.on('vv:settings-save', () => {});
  ipcMain.on('vv:keymap-save', () => {});
  ipcMain.on('vv:recent-save', () => {});
  ipcMain.on('vv:ext-config-save', () => {});
  ipcMain.on('vv:ext-install', () => {});
  ipcMain.on('vv:ext-remove', () => {});
  ipcMain.on('vv:ext-set-enabled', () => {});
  ipcMain.on('vv:ext-check-updates', () => {});
  ipcMain.on('vv:ext-action-clicked', () => {});
  ipcMain.on('vv:ext-popup-close', () => {});
  ipcMain.on('vv:adblock-set-enabled', () => {});
  ipcMain.on('vv:adblock-set-lists', () => {});
  ipcMain.on('vv:adblock-refresh', () => {});
}

// ---- scenes ----
// Each scene receives {win, state} and writes exactly one PNG (via shoot or the web composite).
const scenes = {
  async 'watermark'(win, state) {
    // boot state is 0 tabs -> watermark; close any tabs to be safe
    const n = await evalIn(win, `window.__vvdb().ui.tabs.length`);
    for (let i = 0; i < n; i++) { await sendChord(win, 'W', ['control']); await delay(120); }
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-watermark'))`), 'watermark');
    await shoot(win, 'watermark');
  },

  async 'markdown-rendering'(win, state) {
    await openDoc(win, state, SHOWCASE, await payloadFor('markdown', SHOWCASE),
      `document.querySelector('.markdown-body h1')?.textContent.includes('Feature Showcase')`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-math-display mjx-container svg'))`), 'display math', 15000);
    await waitFor(() => evalIn(win, `(() => { const i = document.querySelector('.markdown-body img'); return i && i.complete && i.naturalWidth > 0; })()`), 'figure image', 8000);
    await shoot(win, 'markdown-rendering');
  },

  async 'markdown-rendering-light'(win, state) {
    await openDoc(win, state, SHOWCASE, await payloadFor('markdown', SHOWCASE),
      `document.querySelector('.markdown-body h1')?.textContent.includes('Feature Showcase')`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.markdown-body .vv-math-display mjx-container svg'))`), 'display math', 15000);
    await evalIn(win, `(() => { const l = document.getElementById('vv-theme-link'); l.href = 'css/themes/spacemacs-light.css'; return l.href; })()`);
    await delay(400);
    await shoot(win, 'markdown-rendering-light');
  },

  async 'source-tree-sitter'(win, state) {
    await openDoc(win, state, VIEWS, await payloadFor('source', VIEWS), `Boolean(document.querySelector('.vv-source .cm-line'))`);
    await waitCalm(win, `document.querySelectorAll('.vv-source .cm-line').length > 10`, 'source lines');
    await delay(1200); // let tree-sitter decorations paint
    await shoot(win, 'source-tree-sitter');
  },

  async 'git-file-tree'(win, state) {
    const files = execFileSync('git', ['ls-files'], { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
      .split('\n').filter(Boolean);
    win.webContents.send('vv:tree', { root: ROOT, files });
    await openDoc(win, state, VIEWS, await payloadFor('source', VIEWS), `Boolean(document.querySelector('.vv-source .cm-line'))`);
    await clickSidebarTab(win, 'Files');
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-tree .vv-tree-filter'))`), 'tree');
    await setInput(win, '.vv-tree-filter', 'view');
    await waitFor(() => evalIn(win, `window.__vvdb().ui['tree-filter'] === 'view' && document.querySelectorAll('.vv-file').length > 0`), 'filtered tree');
    await shoot(win, 'git-file-tree');
  },

  async 'in-page-find'(win, state) {
    await openDoc(win, state, REACTIVE, await payloadFor('markdown', REACTIVE), `Boolean(document.querySelector('.markdown-body'))`);
    await sendChord(win, 'F', ['control']);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-find-input'))`), 'find bar');
    await setInput(win, '.vv-find-input', 'reactive');
    await waitFor(() => evalIn(win, `window.__vvdb().ui.find.count > 0`), 'find matches', 8000);
    await shoot(win, 'in-page-find');
  },

  async 'scroll-spy-toc'(win, state) {
    await openDoc(win, state, KBDOC, await payloadFor('markdown', KBDOC), `Boolean(document.querySelector('.markdown-body h1'))`);
    await clickSidebarTab(win, 'Contents');
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-toc-item'))`), 'toc');
    await evalIn(win, `(() => { const c = document.querySelector('.vv-content'); c.scrollTop = Math.round(c.scrollHeight * 0.4); c.dispatchEvent(new Event('scroll', { bubbles: true })); return true; })()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-toc-item.vv-toc-active'))`), 'active toc', 8000);
    await shoot(win, 'scroll-spy-toc');
  },

  async 'image-view'(win, state) {
    await openDoc(win, state, LOGO, await payloadFor('image', LOGO),
      `(() => { const i = document.querySelector('.vv-image-view img'); return i && i.complete && i.naturalWidth > 0; })()`);
    await shoot(win, 'image-view');
  },

  async 'command-palette'(win, state) {
    await openDoc(win, state, SHOWCASE, await payloadFor('markdown', SHOWCASE), `Boolean(document.querySelector('.markdown-body h1'))`);
    await sendChord(win, 'P', ['control', 'shift']);
    await waitFor(() => evalIn(win, `Boolean(window.__vvdb().ui.palette && window.__vvdb().ui.palette['open?'] && document.querySelector('.vv-palette-input'))`), 'palette');
    await setInput(win, '.vv-palette-input', 'tab');
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-palette-item').length > 0`), 'palette items');
    await shoot(win, 'command-palette');
  },

  async 'multi-tab'(win, state) {
    state.contentByPath.set(SHOWCASE, await payloadFor('markdown', SHOWCASE));
    state.contentByPath.set(LOGO, await payloadFor('image', LOGO));
    state.contentByPath.set(VIEWS, await payloadFor('source', VIEWS));
    state.contentByPath.set(PKG, await payloadFor('source', PKG));
    win.webContents.send('vv:open-files', { paths: [SHOWCASE, LOGO, VIEWS, PKG], 'focus-first': true });
    await waitFor(() => evalIn(win, `document.querySelectorAll('.vv-tab').length >= 4`), '4 tabs');
    await waitFor(() => evalIn(win, `document.querySelector('.markdown-body h1')?.textContent.includes('Feature Showcase')`), 'first tab content', 15000);
    await shoot(win, 'multi-tab');
  },

  async 'keybinding-editor'(win, state) {
    await openMenu(win, 's');
    await hoverItem(win, 'Key Bindings');
    await waitFor(() => evalIn(win, `document.querySelector('.vv-menu-subdropdown')?.textContent.includes('Customize')`), 'kb submenu');
    await clickByText(win, '.vv-menu-subdropdown .vv-menu-item', 'Customize');
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-kb-modal'))`), 'kb editor', 8000);
    await shoot(win, 'keybinding-editor');
  },

  async 'uri-bar-nav'(win, state) {
    await openDoc(win, state, README, await payloadFor('markdown', README), `Boolean(document.querySelector('.markdown-body h1'))`);
    // navigate the active tab a second time so Back is enabled (per-tab history depth >= 2)
    const second = path.join(ROOT, 'docs', 'README.md');
    state.contentByPath.set(second, await payloadFor('markdown', second));
    await sendChord(win, 'L', ['control']);
    await waitFor(() => evalIn(win, `document.activeElement === document.querySelector('.vv-uri-input')`), 'uri focus');
    await setInput(win, '.vv-uri-input', second);
    await evalIn(win, `document.querySelector('.vv-uri-input').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))`);
    await waitFor(() => evalIn(win, `window.__vvdb().ui.tabs.find(t => t.id === window.__vvdb().ui['active-tab'])?.uri?.includes('docs/README.md')`), 'navigated', 10000);
    await waitFor(() => evalIn(win, `window.__vvdb().ui.tabs.length >= 1`), 'tab');
    await delay(200);
    await shoot(win, 'uri-bar-nav');
  },

  async 'diagram-mermaid'(win, state) {
    await openDoc(win, state, MMD, await payloadFor('mermaid', MMD), `Boolean(document.querySelector('.vv-mermaid-view .vv-mermaid svg'))`);
    await shoot(win, 'diagram-mermaid');
  },

  async 'math'(win, state) {
    await openDoc(win, state, MATH, await payloadFor('markdown', MATH),
      `Boolean(document.querySelector('.markdown-body .vv-math-display mjx-container svg'))`, 20000);
    await shoot(win, 'math');
  },

  async 'native-pdf'(win, state) {
    win.webContents.send('vv:content', { path: PDF, kind: 'pdf', bytes: new Uint8Array(fs.readFileSync(PDF)), stamp: now() });
    await waitCalm(win,
      `(() => { const c = document.querySelector('.vv-pdf-doc .vv-pdf-page canvas.vv-pdf-canvas'); return Boolean(c) && c.getBoundingClientRect().width > 0 && c.getBoundingClientRect().height > 0; })()`,
      'pdf canvas', 25000);
    await waitCalm(win, `document.querySelectorAll('.vv-pdf-text span').length > 0`, 'pdf text layer', 15000);
    await clickSidebarTab(win, 'Contents');
    await delay(1500);
    await shoot(win, 'native-pdf');
  },

  async 'directory-browser'(win, state) {
    await openDoc(win, state, FEATURES_DIR, await payloadFor('directory', FEATURES_DIR),
      `Boolean(document.querySelector('.vv-fb .vv-fb-body .vv-fb-row'))`, 10000);
    await shoot(win, 'directory-browser');
  },

  async 'preview-table'(win, state) {
    await openDoc(win, state, CSV, await payloadFor('parsed', CSV), `Boolean(document.querySelector('.vv-table-doc .vv-table tbody tr'))`, 10000);
    await shoot(win, 'preview-table');
  },

  async 'preview-log'(win, state) {
    await openDoc(win, state, LOGF, await payloadFor('parsed', LOGF), `Boolean(document.querySelector('.vv-log-doc .vv-log-line'))`, 10000);
    await shoot(win, 'preview-log');
  },

  async 'preview-office'(win, state) {
    await openDoc(win, state, DOCX, await payloadFor('parsed', DOCX),
      `document.querySelector('.markdown-body')?.textContent.includes('Release Notes')`, 15000);
    await shoot(win, 'preview-office');
  },

  async 'preview-archive'(win, state) {
    await openDoc(win, state, ZIPF, await payloadFor('parsed', ZIPF), `Boolean(document.querySelector('.vv-fb .vv-fb-row'))`, 10000);
    await shoot(win, 'preview-archive');
  },

  async 'dialog-preferences'(win, state) {
    await openMenu(win, 's');
    await clickByText(win, '.vv-menu-dropdown .vv-menu-item', 'Preferences');
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-modal .vv-pref-input'))`), 'preferences dialog', 8000);
    await shoot(win, 'dialog-preferences');
  },

  async 'dialog-extensions'(win, state) {
    await openMenu(win, 's');
    await clickByText(win, '.vv-menu-dropdown .vv-menu-item', 'Extensions');
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-modal .vv-ext-sect'))`), 'extensions dialog', 8000);
    await shoot(win, 'dialog-extensions');
  },

  async 'dialog-passwords'(win, state) {
    await openMenu(win, 's');
    await clickByText(win, '.vv-menu-dropdown .vv-menu-item', 'Passwords');
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-modal.vv-pw-dialog'))`), 'passwords dialog', 8000);
    await waitFor(() => evalIn(win, `(document.querySelector('.vv-pw-providers')?.textContent || '').includes('1Password')`), 'provider row', 8000);
    await shoot(win, 'dialog-passwords');
  },

  async 'zoom-fit'(win, state) {
    await openDoc(win, state, SHOWCASE, await payloadFor('markdown', SHOWCASE), `Boolean(document.querySelector('.markdown-body h1'))`);
    await clickSel(win, '.vv-zoom-caret');
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-zoom-menu .vv-zoom-opt'))`), 'zoom menu');
    await shoot(win, 'zoom-fit');
  },

  async 'address-completion'(win, state) {
    await openDoc(win, state, README, await payloadFor('markdown', README), `Boolean(document.querySelector('.markdown-body'))`);
    await sendChord(win, 'L', ['control']);
    await waitFor(() => evalIn(win, `document.activeElement === document.querySelector('.vv-uri-input')`), 'uri focus');
    await setInput(win, '.vv-uri-input', path.join(ROOT, 're')); // matches README.md + resources/ -> ambiguous -> dropdown
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-uri-complete .vv-uri-opt'))`), 'completion dropdown', 8000);
    await shoot(win, 'address-completion');
  },

  async 'breadcrumb'(win, state) {
    await openDoc(win, state, README, await payloadFor('markdown', README), `Boolean(document.querySelector('.markdown-body'))`);
    // Ctrl-held + hovering the URI bar swaps the address input for a clickable breadcrumb. ctrl-tracker! reads
    // ctrlKey off a capture-phase window keydown; hover? is a React onMouseEnter (synthesized from mouseover).
    await evalIn(win, `window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Control', ctrlKey: true, bubbles: true, cancelable: true }))`);
    await waitFor(() => evalIn(win, `window.__vvdb().ui['ctrl-held?'] === true`), 'ctrl-held');
    await evalIn(win, `(() => { const b = document.querySelector('.vv-uribar'); if (b) b.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true })); return true; })()`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-breadcrumb .vv-crumb'))`), 'breadcrumb', 6000);
    await shoot(win, 'breadcrumb');
    await evalIn(win, `window.dispatchEvent(new KeyboardEvent('keyup', { key: 'Control', ctrlKey: false, bubbles: true }))`);
  },

  async 'web-preview'(win, state) {
    // navigate the active tab to the GitHub repo; the renderer draws .vv-web-host and asks main to show the
    // native web view via vv:http-show (handled in installIpc, which really creates + loads the overlay).
    await sendChord(win, 'L', ['control']);
    await waitFor(() => evalIn(win, `document.activeElement === document.querySelector('.vv-uri-input')`), 'uri focus');
    await setInput(win, '.vv-uri-input', GITHUB_URL);
    await evalIn(win, `document.querySelector('.vv-uri-input').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))`);
    await waitFor(() => evalIn(win, `Boolean(document.querySelector('.vv-web-host'))`), 'web host', 8000);
    await waitFor(() => state.httpShow && state.httpShow.bounds && state.httpShow.bounds.width > 100, 'http-show bounds', 8000);
    const loaded = await (state.webLoaded || Promise.resolve(false));
    if (!loaded) throw new Error('web page failed to load (offline / rate-limited)');
    await delay(2500); // let the page paint
    const b = state.httpShow.bounds;
    const chromeImg = await win.webContents.capturePage({ x: 0, y: 0, width: W, height: H });
    const pageImg = await state.webView.webContents.capturePage();
    const chromeP = path.join(TMP, 'chrome.png');
    const pageP = path.join(TMP, 'page.png');
    fs.writeFileSync(chromeP, chromeImg.toPNG());
    fs.writeFileSync(pageP, pageImg.toPNG());
    const outP = path.join(OUT, 'web-preview.png');
    // composite the real page over the app chrome at the web-host offset (ImageMagick; scale factor 1 => px-exact)
    execFileSync('convert', [chromeP, pageP, '-geometry', `+${Math.round(b.x)}+${Math.round(b.y)}`, '-composite', outP]);
    const dims = execFileSync('identify', ['-format', '%wx%h', outP], { encoding: 'utf8' });
    if (dims.trim() !== `${W}x${H}`) throw new Error('web-preview composite is ' + dims + ', expected ' + W + 'x' + H);
    state.webView.setVisible(false);
    console.log('  [shot] web-preview (composite)');
  }
};

async function main() {
  const onlyArg = process.argv.find((a) => a.startsWith('--only='));
  const only = onlyArg ? onlyArg.slice('--only='.length).split(',').map((s) => s.trim()).filter(Boolean) : null;
  const names = Object.keys(scenes).filter((n) => !only || only.includes(n));

  fs.mkdirSync(OUT, { recursive: true });

  const state = {
    win: null, contentByPath: new Map(), openedPaths: [], openDialogs: 0,
    httpShow: null, httpVisible: false, webView: null, webUrl: null, webLoaded: null,
    lastCopiedText: null, pwSearch: null, pwFill: null, pwSave: null, pwDismiss: null,
    snapshotDataUrl: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='
  };
  installIpc(state);

  await app.whenReady();
  const win = new BrowserWindow({
    useContentSize: true, width: W, height: H, show: false, paintWhenInitiallyHidden: true,
    backgroundColor: '#292b2e',
    webPreferences: { contextIsolation: true, nodeIntegration: false, preload: PRELOAD }
  });
  state.win = win;
  win.webContents.on('render-process-gone', (_e, d) => { throw new Error('renderer gone: ' + d.reason); });

  await win.loadFile(INDEX);
  await waitFor(() => evalIn(win, `Boolean(window.__vvdb && document.querySelector('.vv-menubar') && document.querySelector('.vv-tab-new'))`), 'initial boot', 20000);
  win.show(); // invisible under xvfb; guarantees the compositor paints so captures aren't blank

  const results = [];
  for (const name of names) {
    process.stdout.write('[scene] ' + name + '\n');
    try {
      await resetRenderer(win, state);
      state.contentByPath.clear();
      await scenes[name](win, state);
      results.push([name, 'ok']);
    } catch (e) {
      results.push([name, 'FAIL: ' + (e && e.message ? e.message : String(e))]);
      console.error('  [FAIL]', name, '-', e && e.message ? e.message : e);
    }
  }

  console.log('\n=== screenshot summary ===');
  let failed = 0;
  for (const [name, status] of results) {
    console.log((status === 'ok' ? '  OK   ' : '  FAIL ') + name + (status === 'ok' ? '' : '  (' + status + ')'));
    if (status !== 'ok') failed += 1;
  }
  console.log(`${results.length - failed}/${results.length} scenes captured`);
  return failed;
}

const hardTimeout = setTimeout(() => {
  console.error('screenshot harness timed out');
  try { fs.rmSync(TMP, { recursive: true, force: true }); } catch (_) {}
  app.exit(2);
}, 8 * 60 * 1000);

main()
  .then((failed) => {
    clearTimeout(hardTimeout);
    try { fs.rmSync(TMP, { recursive: true, force: true }); } catch (_) {}
    app.quit();
    process.exitCode = failed > 0 ? 1 : 0;
  })
  .catch((err) => {
    clearTimeout(hardTimeout);
    try { fs.rmSync(TMP, { recursive: true, force: true }); } catch (_) {}
    console.error(err && err.stack ? err.stack : err);
    app.exit(1);
  });
