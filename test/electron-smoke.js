'use strict';

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
process.env.ELECTRON_OZONE_PLATFORM_HINT = 'x11';
process.env.GDK_BACKEND = 'x11';
process.env.XDG_SESSION_TYPE = 'x11';
delete process.env.WAYLAND_DISPLAY;

const assert = require('assert');
const path = require('path');
const { app, BrowserWindow, ipcMain } = require('electron');

const ROOT = path.resolve(__dirname, '..');
const INDEX = path.join(ROOT, 'resources', 'public', 'index.html');
const PRELOAD = path.join(ROOT, 'resources', 'preload.js');

app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
app.commandLine.appendSwitch('ozone-platform', 'x11');

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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
  ipcMain.on('vv:http-hide', () => {
    state.httpHidden = true;
  });
  ipcMain.on('vv:watch-assets', () => {});
  ipcMain.on('vv:context-open-link', () => {});
  ipcMain.on('vv:context-open-link-new-tab', () => {});
  ipcMain.on('vv:copy-text', (_event, text) => {
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

  win.webContents.send('vv:content', {
    path: path.join(ROOT, 'test', 'fixtures', 'smoke.pdf'),
    kind: 'pdf',
    stamp: Date.now()
  });
  await waitFor(
    () => evalIn(win, `Boolean(document.querySelector('.vv-pdf-host'))`),
    'PDF host'
  );
  await waitFor(() => state.pdfShow && state.pdfShow.bounds, 'PDF show payload');
  const pdfLayout = await evalIn(win, `(() => {
    const content = document.querySelector('.vv-content');
    const host = document.querySelector('.vv-pdf-host');
    const contentStyle = getComputedStyle(content);
    const hostRect = host.getBoundingClientRect();
    return {
      hasPdfClass: content.classList.contains('vv-content-pdf'),
      paddingTop: contentStyle.paddingTop,
      paddingRight: contentStyle.paddingRight,
      paddingBottom: contentStyle.paddingBottom,
      paddingLeft: contentStyle.paddingLeft,
      hostWidth: hostRect.width,
      hostHeight: hostRect.height
    };
  })()`);
  assert.strictEqual(pdfLayout.hasPdfClass, true, 'PDF content must use the PDF layout class');
  assert.strictEqual(pdfLayout.paddingTop, '0px', 'PDF content top padding must be zero');
  assert.strictEqual(pdfLayout.paddingRight, '0px', 'PDF content right padding must be zero');
  assert.strictEqual(pdfLayout.paddingBottom, '0px', 'PDF content bottom padding must be zero');
  assert.strictEqual(pdfLayout.paddingLeft, '0px', 'PDF content left padding must be zero');
  assert.ok(pdfLayout.hostWidth > 0 && pdfLayout.hostHeight > 0, 'PDF host must have visible bounds');
  assert.ok(
    state.pdfShow.bounds.width > 0 && state.pdfShow.bounds.height > 0,
    'PDF IPC bounds must be positive'
  );
  console.log('[ok] PDF preview has zero margins and visible bounds');

  const dialogsBefore = state.openDialogs;
  await sendChord(win, 'O', ['control', 'shift']);
  await waitFor(() => state.openDialogs > dialogsBefore, 'Ctrl+Shift+O dialog request');
  const openMode = await evalIn(win, `String(window.__vvdb().ui['open-dialog-mode'])`);
  assert.strictEqual(openMode, 'new-tab', 'Ctrl+Shift+O must request the new-tab open mode');
  console.log('[ok] Ctrl+Shift+O works from preview content');

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

  win.close();
}

const hardTimeout = setTimeout(() => {
  console.error('Electron smoke test timed out');
  app.exit(1);
}, 25000);

main()
  .then(() => {
    clearTimeout(hardTimeout);
    console.log('[ok] Electron smoke test passed');
    app.quit();
  })
  .catch((err) => {
    clearTimeout(hardTimeout);
    console.error(err && err.stack ? err.stack : err);
    app.exit(1);
  });
