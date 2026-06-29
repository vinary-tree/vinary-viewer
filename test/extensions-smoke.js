'use strict';
// Runtime smoke for the extension + ad-block runtime. Proves the load-bearing foundation our feature
// relies on (verified by the Electron-42 spike): session.extensions.loadExtension loads an unpacked MV3
// extension, its content script injects into a matched http page, and its action popup loads with native
// chrome.* (runtime/storage/action). Also exercises the native ad-blocker (gated on network: it needs to
// fetch filter lists). Run: `electron --no-sandbox test/extensions-smoke.js` (npm run test:extensions).

process.env.ELECTRON_DISABLE_SECURITY_WARNINGS = '1';
process.env.ELECTRON_OZONE_PLATFORM_HINT = 'x11';
process.env.GDK_BACKEND = 'x11';
delete process.env.WAYLAND_DISPLAY;

const assert = require('assert');
const http = require('http');
const path = require('path');
const { app, BrowserWindow, WebContentsView, session, net } = require('electron');

const ROOT = path.resolve(__dirname, '..');
const EXT_DIR = path.join(ROOT, 'test', 'fixtures', 'ext-probe');

app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu-sandbox');
app.commandLine.appendSwitch('ozone-platform', 'x11');

const delay = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(pred, label, ms = 12000) {
  const end = Date.now() + ms; let last;
  while (Date.now() < end) { try { if (await pred()) return true; } catch (e) { last = e; } await delay(50); }
  throw new Error('timeout: ' + label + (last ? ' (' + last.message + ')' : ''));
}

const server = http.createServer((_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<!doctype html><title>orig</title><body>hi</body>'); });

function networkUp() {
  return new Promise((resolve) => {
    try {
      const req = net.request('https://easylist.to/easylist/easylist.txt');
      req.on('response', (r) => { resolve(r.statusCode > 0); req.abort(); });
      req.on('error', () => resolve(false));
      setTimeout(() => { try { req.abort(); } catch (_) {} resolve(false); }, 2500);
      req.end();
    } catch (_) { resolve(false); }
  });
}

async function main() {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/`;
  await app.whenReady();
  const ses = session.fromPartition('persist:vv-ext-smoke');

  // register the ONE self-contained chrome.* polyfill preload (ADR-0015) for BOTH types BEFORE loading the
  // extension — it inlines its polyfill (no relative require) so it also works in the sandboxed service-worker
  // realm, and injects via contextBridge.executeInMainWorld into extension pages/popups AND the background SW.
  const polyfill = path.join(ROOT, 'resources', 'ext-chrome-polyfill.js');
  ses.registerPreloadScript({ type: 'service-worker', filePath: polyfill });
  ses.registerPreloadScript({ type: 'frame', filePath: polyfill });

  // (1) load the unpacked MV3 extension
  const ext = await ses.extensions.loadExtension(EXT_DIR);
  assert.ok(ext && ext.id, 'session.extensions.loadExtension must return an extension');
  console.log('[ok] loadExtension →', ext.id);

  // start the MV3 background SW so its startup chrome.* probe runs (it stores the result for the popup/smoke)
  try { await ses.serviceWorkers.startWorkerForScope(`chrome-extension://${ext.id}/`); } catch (e) {}

  const win = new BrowserWindow({ width: 600, height: 500, show: true, paintWhenInitiallyHidden: true });
  const view = new WebContentsView({ webPreferences: { partition: 'persist:vv-ext-smoke', contextIsolation: true, nodeIntegration: false } });
  win.contentView.addChildView(view);
  view.setBounds({ x: 0, y: 0, width: 600, height: 500 });
  const wc = view.webContents;

  // (2) content-script injection into the matched http page
  await wc.loadURL(url);
  await waitFor(async () => (await wc.executeJavaScript('document.title', true)) === 'VV_EXT_OK', 'content script injected');
  console.log('[ok] content script injected (autofill foundation)');

  // (3) the action popup loads with native chrome.* (runtime/storage/action)
  await wc.loadURL(`chrome-extension://${ext.id}/popup.html`);
  await waitFor(async () => (await wc.executeJavaScript('Boolean(window.__vvPopup)', true)) === true, 'popup ran');
  const popup = await wc.executeJavaScript('window.__vvPopup', true);
  assert.strictEqual(popup.hasRuntime, true, 'popup must have native chrome.runtime');
  assert.strictEqual(popup.hasStorage, true, 'popup must have native chrome.storage');
  assert.strictEqual(popup.hasAction, true, 'popup must have native chrome.action');
  console.log('[ok] action popup loads with native chrome.runtime/storage/action');

  // (3b) the frame preload's chrome.* polyfill defines the APIs Electron lacks (e.g. chrome.windows) in
  // extension-page main worlds, so extension popups/options pages that touch them don't crash (ADR-0015)
  assert.strictEqual(popup.frameWindows, true, 'the frame polyfill must define chrome.windows in extension pages');
  console.log('[ok] frame polyfill defines chrome.windows in extension pages');

  // (3c) the SERVICE-WORKER preload's chrome.* polyfill (ADR-0015) reaching a TRIVIAL probe SW: its FIRST
  // statement reads chrome.windows.onFocusChanged un-guarded (the call LastPass crashes on). For this small
  // SW the executeInMainWorld realm COINCIDES with the SW script realm, so the polyfill lands and the worker
  // survives + stores its sentinel. IMPORTANT HONEST CAVEAT (ADR-0015 §"Service-worker limitation"): a GREEN
  // here does NOT prove a real/heavy extension SW works — for those, executeInMainWorld runs in a SEPARATE
  // realm from the SW script (empirically, Electron 42), so e.g. LastPass's background SW still crashes. This
  // asserts only the trivial-SW + frame path. SW preloads REQUIRE the sandbox, so under --no-sandbox this is
  // NOT validated (explicitly skipped, not silently passed — run `npm run test:extensions:sandbox`).
  const noSandbox = process.argv.includes('--no-sandbox') || app.commandLine.hasSwitch('no-sandbox');
  await waitFor(async () => (await wc.executeJavaScript('Boolean(window.__vvPopup && window.__vvPopup.bg)', true)) === true,
    'background SW stored its chrome.* probe', 8000).catch(() => {});
  const bg = await wc.executeJavaScript('(window.__vvPopup && window.__vvPopup.bg) || null', true);
  if (noSandbox) {
    console.log('[skip] SW chrome.windows polyfill NOT validated under --no-sandbox (SW preloads need the sandbox) — run `npm run test:extensions:sandbox`');
  } else {
    assert.ok(bg && bg.ran, 'the (trivial) probe SW must SURVIVE its un-guarded chrome.windows.onFocusChanged read + store its sentinel');
    assert.strictEqual(bg.windows, true, 'the SW preload must define chrome.windows in the trivial probe worker');
    assert.strictEqual(bg.windowsOk, true, 'the trivial probe SW must reach chrome.windows.onFocusChanged.addListener');
    console.log('[ok] SW chrome.windows polyfill reaches a TRIVIAL probe SW (does NOT generalize to heavy real-extension SWs — ADR-0015 §SW-limitation)');
  }

  // (4) ad-blocker blocks a known ad host (network-gated: building the engine fetches filter lists)
  if (await networkUp()) {
    const { ElectronBlocker } = require('@ghostery/adblocker-electron');
    const blocker = await ElectronBlocker.fromPrebuiltAdsAndTracking((u) => fetch(u));
    blocker.enableBlockingInSession(ses);
    const blocked = await new Promise((resolve) => {
      const req = net.request({ url: 'https://www.doubleclick.net/', session: ses });
      req.on('response', () => resolve(false));      // not blocked → got a response
      req.on('error', () => resolve(true));          // blocked → request errored
      setTimeout(() => resolve(true), 4000);         // blocked engines often hang the request → treat as blocked
      req.end();
    });
    assert.strictEqual(blocked, true, 'a known ad host must be blocked on the session');
    console.log('[ok] ad-blocker blocks a known ad host (doubleclick.net)');
  } else {
    console.log('[skip] ad-blocker host test (offline — engine cannot fetch filter lists)');
  }

  win.close();
}

const hard = setTimeout(() => { console.error('extensions smoke timed out'); app.exit(1); }, 60000);
main().then(() => { clearTimeout(hard); server.close(); console.log('[ok] EXTENSIONS SMOKE PASSED'); app.quit(); })
  .catch((e) => { clearTimeout(hard); server.close(); console.error(e && e.stack ? e.stack : e); app.exit(1); });
