// MV3 background service worker — proves the SW runs + which chrome.* it can reach.
self.__vvBgRan = true;
self.__vvBgApis = {
  action: typeof chrome !== 'undefined' && !!chrome.action,
  runtime: typeof chrome !== 'undefined' && !!chrome.runtime,
  storage: typeof chrome !== 'undefined' && !!(chrome.storage && chrome.storage.local),
};
try { chrome.action.setBadgeText({ text: 'OK' }); self.__vvBadgeOk = true; } catch (e) { self.__vvBadgeErr = String(e); }
if (chrome.runtime && chrome.runtime.onMessage) {
  chrome.runtime.onMessage.addListener((msg, _sender, send) => { if (msg === 'ping') { send('pong'); } return true; });
}
