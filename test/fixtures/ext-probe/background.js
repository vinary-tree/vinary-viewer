// MV3 background service worker — proves the SW runs + which chrome.* it can reach.
//
// LastPass-FAITHFUL crash probe (ADR-0015 SW path): the FIRST executable statement reads
// chrome.windows.onFocusChanged UN-GUARDED — exactly as LastPass's background-redux-new.js does during its
// synchronous bundle evaluation. If the service-worker preload's polyfill has NOT defined chrome.windows by
// this instant, this THROWS ("Cannot read properties of undefined (reading 'onFocusChanged')") and the worker
// FAILS to register (status 15) — the precise LastPass crash. The success sentinel below then never lands, so
// the smoke's SW assertion fails. With the polyfill in place the read survives and the sentinel is stored.
// (Deliberately no try/catch here: a survivable, guarded read could not reproduce a registration-killing crash
// — that gap is why the earlier smoke was a false positive.)
chrome.windows.onFocusChanged.addListener(function () {});
self.__vvReachedAfterWindows = true;

self.__vvBgRan = true;
self.__vvBgApis = {
  action: typeof chrome !== 'undefined' && !!chrome.action,
  runtime: typeof chrome !== 'undefined' && !!chrome.runtime,
  storage: typeof chrome !== 'undefined' && !!(chrome.storage && chrome.storage.local),
  windows: typeof chrome !== 'undefined' && !!chrome.windows,
};
try { chrome.action.setBadgeText({ text: 'OK' }); self.__vvBadgeOk = true; } catch (e) { self.__vvBadgeErr = String(e); }

// success sentinel — only reached because the un-guarded chrome.windows read above did NOT throw. Stored so
// the popup (and the smoke) can read that the SW preload reached the background worker before it ran.
try { chrome.storage.local.set({ vvBg: { ran: true, windows: !!chrome.windows, windowsOk: true } }); } catch (e) {}

if (chrome.runtime && chrome.runtime.onMessage) {
  chrome.runtime.onMessage.addListener((msg, _sender, send) => { if (msg === 'ping') { send('pong'); } return true; });
}
