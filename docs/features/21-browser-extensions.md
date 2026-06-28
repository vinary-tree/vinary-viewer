# Browser extensions

**Status: Available now.** *(See [ADR-0015](../design-decisions/0015-scoped-extension-runtime-gpl-free.md).)*

---

## 1 · What it is

You can install a **scoped set of Chrome extensions** — **password managers** and **ad-blocker-class**
extensions — from the Chrome Web Store, install runs on the in-app web view's session. The runtime is
**fully GPL-free** (Apache-2.0 + MIT + MPL): it uses Electron's *built-in* extension support (which on
Electron 42 already provides `chrome.runtime`/`storage`/`action`/`tabs`/`i18n`, MV3 service workers, and
content scripts natively), `electron-chrome-web-store` (MIT) for install + periodic auto-update, and a
small first-party **toolbar + popup** surface (Electron renders none of its own).

Each installed extension's action icon appears in a toolbar at the end of the address bar (for `http(s)`
tabs); clicking it opens the extension's popup just below.

**chrome.* polyfill for popups & pages.** Electron 42 ships many extension APIs but not all — it lacks
`chrome.windows`, `webNavigation`, `cookies`, `notifications`, `contextMenus`, and `privacy`. So a small
**frame** session preload (`resources/ext-frame-preload.js` + `ext-chrome-polyfill.js`) injects inert,
correctly-shaped **stubs** for those into each extension's **popup** and **options/page** main world —
just enough that a popup or options page which touches them doesn't throw and crash. This covers the
*page/popup* worlds only, not background service workers (see **Honest limitations** below).

## 2 · How you use it

Open **Settings ▸ Extensions**:

- **Enable browser extensions** — the master toggle.
- **Install** — paste a Chrome Web Store URL (or a 32-char extension ID) and click **Install**.
- For each installed extension: an on/off toggle and a **Remove** button.
- **Check for updates** — extensions also auto-update in the background (startup + ~5 h).

Prefs persist in `~/.config/vinary-viewer/extensions.edn`; extensions install under
`<userData>/Extensions/`. Provenance is **Web-Store-only** — in-page "Add to Chrome" is denied; install
goes through this dialog.

## 3 · Honest limitations

Electron is not Chrome. Documented in [ADR-0015](../design-decisions/0015-scoped-extension-runtime-gpl-free.md):

- **Native-messaging password managers** (1Password, KeePassXC, Bitwarden-desktop) — **not supported**.
  Cloud-login managers' **autofill** (content script) and **vault popup** do work.
- **Dynamic action badges/icons** are not displayed (Electron has no toolbar to render them; the toolbar
  shows the static manifest icon).
- **Background service workers don't get the polyfill (the LastPass-class gap).** The chrome.* polyfill
  above reaches popup/options **pages**, but **Electron 42 does not run session preloads inside extension
  _background_ service workers**. So an extension whose background worker touches one of those missing APIs
  **at startup** — e.g. **LastPass**, which calls `chrome.windows.onFocusChanged` — still fails to
  register its worker, and its login popup (which talks to that worker) **may stay unfilled**. The SW
  preload (`resources/ext-sw-preload.js`) is registered anyway as a **forward-compatible no-op**: a future
  Electron that runs preloads in extension workers would fix this automatically. Patching the extension's
  files on disk was **rejected** (modifying a third-party password manager's code is a security/integrity
  and auto-update hazard).
- A few other MV3 background surfaces (`offscreen`, `nativeMessaging`, `sidePanel`) are also absent.

## 4 · Internals

| Piece | Where |
|---|---|
| Web-Store install/load/reconcile + manifest→toolbar model + state push | `vinary.main.extensions` |
| chrome.* polyfill preloads (frame = popup/page worlds; service-worker = no-op) | `resources/ext-frame-preload.js` + `ext-chrome-polyfill.js`, `ext-sw-preload.js`; registered via `session.registerPreloadScript` in `vinary.main.extensions` |
| Popup `WebContentsView` host (origin-locked, content-sized) | `vinary.main.ext-popup` |
| Pure helpers (id parse, reconcile, action model, popup geometry) | `vinary.main.ext-util` (unit-tested) |
| Persisted prefs | `vinary.main.ext-config` ↔ `extensions.edn` |
| Toolbar (manifest icons as data-URLs) | `vinary.ui.ext-toolbar` |
| Management dialog | `vinary.ui.extensions` |
| Runtime smoke (loadExtension + content-script + popup) | `test/extensions-smoke.js` |

Security: extensions execute on the isolated `persist:vinary-web` session and **cannot reach `window.vv`
or Node** — see [the threat model §6.5](../security/threat-model.md).
