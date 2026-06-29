'use strict';
// Self-contained chrome.* polyfill PRELOAD for the scoped extension runtime (ADR-0015). Registered on
// persist:vinary-web for BOTH the 'service-worker' and 'frame' preload types (vinary.main.extensions).
//
// Electron 42 implements action/tabs/runtime/storage/i18n/scripting natively but NOT windows/webNavigation/
// cookies/notifications/contextMenus/privacy. This defines correctly-SHAPED but inert stubs for the missing
// surfaces; the native APIs are never overwritten (def only assigns where undefined).
//
// SCOPE OF EFFECT — load-bearing, do not over-claim (see ADR-0015 §"Service-worker limitation"):
//   • FRAME path (extension popup/options pages): WORKS. executeInMainWorld runs installPolyfill in the page's
//     own main world (chrome already present, SAME realm), so a popup that reads e.g. chrome.windows does not
//     crash. Verified by test/extensions-smoke.js (popup.frameWindows).
//   • SERVICE-WORKER path: does NOT reach a real/heavy extension's SW. EMPIRICALLY (2026-06-28, Electron 42),
//     contextBridge.executeInMainWorld from a service-worker preload runs in a SEPARATE V8 realm from the
//     extension's own SW script: installPolyfill sets chrome.windows on realm A, but background-redux-new.js
//     executes in realm B whose native chrome lacks windows → it still throws
//     "Cannot read properties of undefined (reading 'onFocusChanged')" and SW registration fails (status 15).
//     A trivial probe SW survives only because its realm coincides with the executeInMainWorld realm. No
//     in-bounds mechanism (data property, configurable/non-configurable accessor, loadExtension+
//     startWorkerForScope) bridges the two; the augment below is a harmless no-op there. So a background SW
//     that reads an unimplemented chrome.* API at startup (e.g. LastPass) currently cannot load its UI.
//
// MUST be fully self-contained: a sandboxed preload realm (a service worker especially) has NO `fs` and
// CANNOT `require` a relative file — so this requires only 'electron' and INLINES installPolyfill. It is
// injected into the extension's MAIN world via contextBridge.executeInMainWorld, so it is serialized and must
// reference only globals (no closures over preload scope).

function installPolyfill() {
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
  // each chrome.privacy.* leaf is a ChromeSetting: { get, set, clear, onChange }
  function setting() { return { get: async({ value: false, levelOfControl: 'not_controllable' }), set: async(), clear: async(), onChange: evt() }; }

  // Gap-fill the surfaces Electron lacks onto `c`, never overwriting a native API. A plain assignment can
  // silently fail to stick on some bindings objects, so fall back to defineProperty.
  function augment(c) {
    if (!c) { return; }
    function def(name, obj) {
      if (typeof c[name] !== 'undefined') { return; }
      try { c[name] = obj; } catch (e) { /* getter-only / sealed → defineProperty below */ }
      if (typeof c[name] === 'undefined') {
        try { Object.defineProperty(c, name, { value: obj, writable: true, enumerable: true, configurable: true }); } catch (e) { /* cannot define; nothing more we can do */ }
      }
    }

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

    def('privacy', {
      network: { networkPredictionEnabled: setting(), webRTCIPHandlingPolicy: setting() },
      services: { autofillAddressEnabled: setting(), autofillCreditCardEnabled: setting(), passwordSavingEnabled: setting(), searchSuggestEnabled: setting() },
      websites: { thirdPartyCookiesAllowed: setting(), hyperlinkAuditingEnabled: setting(), referrersEnabled: setting(), doNotTrackEnabled: setting() }
    });
  }

  // Augment the extension world's chrome where it is present (frame/popup pages — the path that works). For a
  // service worker this runs in a realm the SW script does not share (see header), so it is a harmless no-op.
  augment(globalThis.chrome);
}

// ---- self-install -------------------------------------------------------------------------------------
// Inject into the extension's MAIN world. The SW + the contextIsolation:true popup run context-isolated, so
// the preload-realm `chrome` is NOT the extension's — executeInMainWorld compiles installPolyfill in the
// extension's own world (before its script runs). Guard on the extension context so ordinary web pages and
// Ghostery's own frame preload (this session serves them too) are left untouched.
try {
  var isExtensionContext =
    (typeof process !== 'undefined' && process.type === 'service-worker') ||
    (typeof location !== 'undefined' && location.protocol === 'chrome-extension:');
  if (isExtensionContext) {
    var electron = require('electron');
    if (electron.contextBridge && electron.contextBridge.executeInMainWorld) {
      electron.contextBridge.executeInMainWorld({ func: installPolyfill });
    }
    // non-context-isolated fallback; a no-op in the (sandboxed) preload realm, where globalThis.chrome is undefined
    try { installPolyfill(); } catch (e) { /* ignore */ }
  }
} catch (e) { /* ignore */ }
