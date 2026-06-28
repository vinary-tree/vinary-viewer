'use strict';
// Service-worker preload registered on persist:vinary-web. It WOULD inject the chrome.* polyfill (ADR-0015)
// into an extension's background service worker (the same idea as ext-frame-preload does for extension pages).
//
// NOTE (Electron 42.5.0, verified): session service-worker preloads do NOT execute inside extension
// BACKGROUND service workers — the script registers but never runs there. So today this is a
// forward-compatible NO-OP: it stays registered so that a future Electron which runs SW preloads in
// extension workers will fix LastPass-class extensions (whose background worker reads e.g.
// chrome.windows.onFocusChanged) automatically, with no further change. The popup / extension-page half is
// handled by ext-frame-preload, which DOES work.
const electron = require('electron');
const { installPolyfill } = require('./ext-chrome-polyfill.js');

try {
  if (electron.contextBridge && electron.contextBridge.executeInMainWorld) {
    electron.contextBridge.executeInMainWorld({ func: installPolyfill });
  }
} catch (e) { /* ignore */ }
try { installPolyfill(); } catch (e) { /* ignore */ }
