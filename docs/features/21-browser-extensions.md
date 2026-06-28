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

**chrome.* polyfill for popups, pages, and background workers.** Electron 42 ships many extension APIs but
not all — it lacks `chrome.windows`, `webNavigation`, `cookies`, `notifications`, `contextMenus`, and
`privacy`. So one **self-contained** session preload (`resources/ext-chrome-polyfill.js`), registered for
**both** the `frame` and `service-worker` types, injects inert, correctly-shaped **stubs** for those into
each extension's **main world** — its popup/options pages **and** its background **service worker**. That is
just enough that an extension which reads them (e.g. **LastPass**, whose background worker calls
`chrome.windows.onFocusChanged` at startup) registers and its login popup loads — **without modifying the
extension's source**.

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
- **Cloud password managers (LastPass-class) work** as of round 3 — the chrome.* polyfill now reaches the
  **background service worker** too (see §1), so an extension whose worker reads `chrome.windows`/etc. at
  startup registers cleanly and its popup loads. (Service-worker preloads require Electron's sandbox to be
  enabled, which it is.) Patching the extension's files on disk was still **rejected** (modifying a
  third-party password manager's code is a security/integrity and auto-update hazard) — the fix lives in
  *our* preload.
- A few MV3 background surfaces are still absent (`offscreen`, `nativeMessaging`, `sidePanel`); the polyfill
  stubs are **inert** (correct shape, no real behavior) — enough to prevent startup crashes, but they do not
  *implement* those APIs.

## 4 · Internals

| Piece | Where |
|---|---|
| Web-Store install/load/reconcile + manifest→toolbar model + state push | `vinary.main.extensions` |
| chrome.* polyfill preload (one self-contained file, registered for frame **and** service-worker) | `resources/ext-chrome-polyfill.js` — inlines its polyfill (no relative require) so it loads in the sandboxed SW realm, and self-installs via `contextBridge.executeInMainWorld`; registered via `session.registerPreloadScript` in `vinary.main.extensions` |
| Popup `WebContentsView` host (origin-locked, content-sized) | `vinary.main.ext-popup` |
| Pure helpers (id parse, reconcile, action model, popup geometry) | `vinary.main.ext-util` (unit-tested) |
| Persisted prefs | `vinary.main.ext-config` ↔ `extensions.edn` |
| Toolbar (manifest icons as data-URLs) | `vinary.ui.ext-toolbar` |
| Management dialog | `vinary.ui.extensions` |
| Runtime smoke (loadExtension + content-script + popup + frame **and** background-worker `chrome.windows`) | `test/extensions-smoke.js` — run sandbox-on via `npm run test:extensions:sandbox` to exercise the SW path |

Security: extensions execute on the isolated `persist:vinary-web` session and **cannot reach `window.vv`
or Node** — see [the threat model §6.5](../security/threat-model.md).
