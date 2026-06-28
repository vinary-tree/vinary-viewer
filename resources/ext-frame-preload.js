'use strict';
// Frame preload registered on persist:vinary-web. This session also serves ordinary web pages (and the
// Ghostery ad-blocker's own frame preload), so we GUARD on the chrome-extension:// origin and only inject
// the chrome.* polyfill (ADR-0015) into extension pages — the popup / options pages. We inject into the MAIN
// world (via executeInMainWorld) because the popup host runs with contextIsolation:true, so an isolated-world
// definition would not reach the extension's own code. Defensive parity with the SW preload.
const { contextBridge } = require('electron');
const { installPolyfill } = require('./ext-chrome-polyfill.js');

try {
  if (typeof location !== 'undefined' && location.protocol === 'chrome-extension:') {
    contextBridge.executeInMainWorld({ func: installPolyfill });
  }
} catch (e) { /* ignore */ }
