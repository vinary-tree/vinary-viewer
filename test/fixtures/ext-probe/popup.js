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
};
document.getElementById('p').textContent = 'popup-ready';
