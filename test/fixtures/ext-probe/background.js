// MV3 background service worker — proves the SW runs + which chrome.* it can reach.
self.__vvBgRan = true;
self.__vvBgApis = {
  action: typeof chrome !== 'undefined' && !!chrome.action,
  runtime: typeof chrome !== 'undefined' && !!chrome.runtime,
  storage: typeof chrome !== 'undefined' && !!(chrome.storage && chrome.storage.local),
};
try { chrome.action.setBadgeText({ text: 'OK' }); self.__vvBadgeOk = true; } catch (e) { self.__vvBadgeErr = String(e); }

// LastPass-pattern probe (ADR-0015 SW path): read the polyfilled chrome.windows the way a real extension's
// background worker does at startup. Without the service-worker preload's polyfill, chrome.windows is
// undefined and onFocusChanged.addListener throws — the exact crash we fix. Wrapped so the probe RECORDS the
// failure instead of killing the worker, then stored so the popup (and the smoke) can read the result.
var vvWindows = typeof chrome !== 'undefined' && !!chrome.windows;
var vvWindowsOk = false;
try { chrome.windows.onFocusChanged.addListener(function () {}); vvWindowsOk = true; } catch (e) {}
self.__vvBgApis.windows = vvWindows;
try { chrome.storage.local.set({ vvBg: { ran: true, windows: vvWindows, windowsOk: vvWindowsOk } }); } catch (e) {}

if (chrome.runtime && chrome.runtime.onMessage) {
  chrome.runtime.onMessage.addListener((msg, _sender, send) => { if (msg === 'ping') { send('pong'); } return true; });
}
