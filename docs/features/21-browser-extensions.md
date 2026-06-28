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
- A few MV3 background APIs (`offscreen`, `nativeMessaging`, `sidePanel`, `webNavigation`) are absent.

## 4 · Internals

| Piece | Where |
|---|---|
| Web-Store install/load/reconcile + manifest→toolbar model + state push | `vinary.main.extensions` |
| Popup `WebContentsView` host (origin-locked, content-sized) | `vinary.main.ext-popup` |
| Pure helpers (id parse, reconcile, action model, popup geometry) | `vinary.main.ext-util` (unit-tested) |
| Persisted prefs | `vinary.main.ext-config` ↔ `extensions.edn` |
| Toolbar (manifest icons as data-URLs) | `vinary.ui.ext-toolbar` |
| Management dialog | `vinary.ui.extensions` |
| Runtime smoke (loadExtension + content-script + popup) | `test/extensions-smoke.js` |

Security: extensions execute on the isolated `persist:vinary-web` session and **cannot reach `window.vv`
or Node** — see [the threat model §6.5](../security/threat-model.md).
