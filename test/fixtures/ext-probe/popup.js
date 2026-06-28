// Popup main-world script — records which chrome.* APIs it can reach (the §0.5 visibility question).
window.__vvPopup = {
  hasChrome: typeof chrome !== 'undefined',
  hasRuntime: typeof chrome !== 'undefined' && !!chrome.runtime,
  hasAction: typeof chrome !== 'undefined' && !!chrome.action,
  hasStorage: typeof chrome !== 'undefined' && !!(chrome.storage && chrome.storage.local),
  hasI18n: typeof chrome !== 'undefined' && !!chrome.i18n,
  // a shim injected by our frame preload (main-world) would set this:
  shim: typeof window.__vvActionShim !== 'undefined',
};
document.getElementById('p').textContent = 'popup-ready';
