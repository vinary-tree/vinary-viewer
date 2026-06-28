// Popup main-world script — records which chrome.* APIs it can reach (the §0.5 visibility question).
window.__vvPopup = {
  hasChrome: typeof chrome !== 'undefined',
  hasRuntime: typeof chrome !== 'undefined' && !!chrome.runtime,
  hasAction: typeof chrome !== 'undefined' && !!chrome.action,
  hasStorage: typeof chrome !== 'undefined' && !!(chrome.storage && chrome.storage.local),
  hasI18n: typeof chrome !== 'undefined' && !!chrome.i18n,
  // the frame preload's chrome.* polyfill (ADR-0015) defines the APIs Electron lacks (e.g. chrome.windows)
  // in extension-page main worlds, so extension popups/pages that touch them don't crash:
  frameWindows: typeof chrome !== 'undefined' &&
    !!(chrome.windows && chrome.windows.onFocusChanged && chrome.windows.onFocusChanged.addListener),
  // the background SW stores its own chrome.windows probe (ADR-0015 SW path) in chrome.storage; poll until
  // it lands so the smoke can read whether the SW preload reached the background worker
  bg: null,
};
(function pollBg() {
  if (typeof chrome === 'undefined' || !(chrome.storage && chrome.storage.local)) { return; }
  chrome.storage.local.get('vvBg', function (r) {
    if (r && r.vvBg) { window.__vvPopup.bg = r.vvBg; }
    else { setTimeout(pollBg, 100); }
  });
})();
document.getElementById('p').textContent = 'popup-ready';
