'use strict';
// chrome.* polyfill for the scoped extension runtime (ADR-0015). Electron 42 implements
// action/tabs/runtime/storage/i18n/scripting natively but NOT windows/webNavigation/cookies/notifications/
// contextMenus/privacy — so real extensions (e.g. LastPass, whose background service worker reads
// `chrome.windows.onFocusChanged` at startup) crash and fail to register. This defines correctly-SHAPED but
// inert stubs for the missing surfaces so the SW registers and runs; the native APIs are never overwritten.
//
// `installPolyfill` is run in the extension's MAIN world (where `chrome` lives) via
// contextBridge.executeInMainWorld from the SW + frame preloads, so it MUST be self-contained — it may
// reference only globals (no closures over preload scope).

function installPolyfill() {
  var c = globalThis.chrome;
  if (!c) { return; }

  // an event stub: { addListener, removeListener, hasListener } — extensions read .onX then .addListener()
  function evt() {
    return { addListener: function () {}, removeListener: function () {}, hasListener: function () { return false; } };
  }
  // an async method stub: invokes a trailing callback (MV2 style) with `v` and resolves a Promise (MV3 style)
  function async(v) {
    return function () {
      var cb = arguments.length ? arguments[arguments.length - 1] : null;
      if (typeof cb === 'function') { try { cb(v); } catch (e) { /* ignore */ } }
      return Promise.resolve(v);
    };
  }
  function def(name, obj) { if (typeof c[name] === 'undefined') { c[name] = obj; } }

  def('windows', {
    WINDOW_ID_NONE: -1, WINDOW_ID_CURRENT: -2,
    onCreated: evt(), onRemoved: evt(), onFocusChanged: evt(), onBoundsChanged: evt(),
    get: async({}), getCurrent: async({}), getLastFocused: async({}), getAll: async([]),
    create: async({}), update: async({}), remove: async()
  });

  def('webNavigation', {
    onBeforeNavigate: evt(), onCommitted: evt(), onDOMContentLoaded: evt(), onCompleted: evt(),
    onErrorOccurred: evt(), onCreatedNavigationTarget: evt(), onReferenceFragmentUpdated: evt(),
    onTabReplaced: evt(), onHistoryStateUpdated: evt(),
    getFrame: async(null), getAllFrames: async([])
  });

  def('cookies', {
    onChanged: evt(),
    get: async(null), getAll: async([]), getAllCookieStores: async([]), set: async(null), remove: async(null)
  });

  def('notifications', {
    onClicked: evt(), onClosed: evt(), onButtonClicked: evt(), onPermissionLevelChanged: evt(), onShowSettings: evt(),
    create: async('vv'), update: async(true), clear: async(true), getAll: async({}), getPermissionLevel: async('granted')
  });

  def('contextMenus', {
    ACTION_MENU_TOP_LEVEL_LIMIT: 6, onClicked: evt(),
    create: function () { var a = arguments; var cb = a.length ? a[a.length - 1] : null; if (typeof cb === 'function') { try { cb(); } catch (e) {} } return 'vv'; },
    update: async(), remove: async(), removeAll: async()
  });

  // each chrome.privacy.* leaf is a ChromeSetting: { get, set, clear, onChange }
  function setting() { return { get: async({ value: false, levelOfControl: 'not_controllable' }), set: async(), clear: async(), onChange: evt() }; }
  def('privacy', {
    network: { networkPredictionEnabled: setting(), webRTCIPHandlingPolicy: setting() },
    services: { autofillAddressEnabled: setting(), autofillCreditCardEnabled: setting(), passwordSavingEnabled: setting(), searchSuggestEnabled: setting() },
    websites: { thirdPartyCookiesAllowed: setting(), hyperlinkAuditingEnabled: setting(), referrersEnabled: setting(), doNotTrackEnabled: setting() }
  });
}

module.exports = { installPolyfill };
