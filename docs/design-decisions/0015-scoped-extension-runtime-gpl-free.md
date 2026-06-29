# 0015 — A GPL-free, scoped Chrome-extension runtime (password managers + ad-blocker-class)

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** vinary-viewer maintainers

## Context

Users wanted **targeted** Chrome-extension support — password managers and ad blockers — in the web view,
with Chrome **Web Store install and periodic auto-update**, *not* the full extension spectrum. Three
constraints shaped the design:

1. **License.** Vinary is **Apache-2.0** and "fully open source." The obvious library,
   `electron-chrome-extensions`, is **GPL-3.0** (dual-licensed with a paid key) and would make the
   *distributed binary* GPLv3 — unacceptable.
2. **Electron is not Chrome.** Native-messaging password managers (1Password, KeePassXC) talk to a
   companion desktop app over a bridge Electron's extension host does not provide; they cannot fully work
   regardless of approach.
3. **Electron renders no browser-action toolbar.** Even where the `chrome.action` *API* exists, there is
   no toolbar UI to click or popup to show.

A **compatibility spike on Electron 42** (`scratchpad/probe-ext-spike.js`, committed as
`test/extensions-smoke.js`) returned a decisive, plan-changing result.

## Decision

**Build a GPL-free runtime from permissive parts, and — per the spike — add NO chrome.* shim.**

The verified Electron-42 facts:

- `session.extensions.loadExtension` loads an unpacked MV3 extension; its **content scripts inject** and
  its **background service worker runs**.
- Extension popups have **native `chrome.runtime`, `chrome.storage`, `chrome.action`, `chrome.tabs`
  (+`query`), and `chrome.i18n`** — so popups don't crash and the popup's own logic works.

Therefore the original plan's hardest third — a custom `chrome.action`/tabs/message **shim**, two session
**preloads**, and a message **bus** — is **unnecessary** and was dropped. The stack is:

| Concern | Mechanism | License |
|---|---|---|
| Load extensions, content scripts, storage, MV3 SW, `chrome-extension://` | Electron built-in `session.extensions.loadExtension` | MIT (in Electron) |
| Web Store install + periodic auto-update | `electron-chrome-web-store` (standalone, no GPL dep) | MIT |
| Ad/tracker blocking | `@ghostery/adblocker-electron` (ADR-0014) | MPL-2.0 |
| Browser-action **toolbar** + **popup** (the only gap) | **our first-party code** (`vinary.ui.ext-toolbar`, `vinary.main.ext-popup`) | Apache-2.0 |

`vinary.main.extensions` runs the Web-Store install/load/reconcile, reads each extension's **manifest**
to build a toolbar model (`ext-util/action-model`), and ships the action icon as a **data-URL** to the
sandboxed renderer (which is on a different session and cannot load `chrome-extension://` icons).
`vinary.ui.ext-toolbar` renders the icon buttons in the address-bar row for `http(s)` tabs; a click asks
main to open the extension's popup in a `WebContentsView` (`vinary.main.ext-popup`) anchored below the
icon and **content-sized** by measuring the popup via `executeJavaScript` (no preload needed). Popup
navigation is locked to the extension's own origin; external links open in the OS browser.

**Provenance:** `allowUnpackedExtensions: false` (Web-Store only) and `beforeInstall → deny` so in-page
"Add to Chrome" is blocked — installs go only through our Settings ▸ Extensions UI (`installExtension`).
Prefs (master toggle, per-extension disabled-ids, ad-block toggle/lists) persist in `extensions.edn`
(`vinary.main.ext-config`, mirroring `recent.cljs`) with a **synchronous main-side `load-config`** so the
runtime boots with the user's saved prefs, not defaults.

## Consequences

- **Apache-2.0 stays clean** — no GPL dependency in the shipped binary.
- The implementation is **far simpler** than planned (no GPL bus / no SW message-routing): five small main
  namespaces (`ext-util`, `ext-config`, `adblock`, `extensions`, `ext-popup`) + two UI namespaces — plus a
  **self-contained chrome.* polyfill preload** (one file, registered for both the frame and service-worker
  types) for the **page/popup** APIs Electron lacks. (It does NOT reach a real extension's background
  service worker — an Electron-42 realm limitation; see Documented limitations.)
- Verified end-to-end by `test/extensions-smoke.js`: loadExtension → content-script injection → popup
  with native `chrome.runtime/storage/action`, plus the ad-blocker dropping a known ad host.
- **The `vvext:*` channels and shim files in the design plan were never built** — superseded by the spike.

## Documented limitations (honest scope)

- **Native-messaging password managers** (1Password, KeePassXC, Bitwarden-desktop) are **not supported**
  in Electron. Cloud-login managers' **autofill** (content script) + **vault popup** work.
- **Dynamic action badge/icon** (`chrome.action.setBadgeText/setIcon` at runtime) is a no-op for display
  — Electron has no toolbar to render it; our toolbar shows the **static manifest icon**.
- **Missing chrome.* surfaces — polyfilled for extension PAGES; NOT reachable in a real background worker.**
  Electron does not implement `chrome.windows`, `webNavigation`, `cookies`, `notifications`, `contextMenus`,
  `privacy` (nor `offscreen`/`nativeMessaging`/`sidePanel`). We inject an inert chrome.* polyfill into the
  extension's MAIN world via `contextBridge.executeInMainWorld`, registered on `persist:vinary-web` for both
  the `frame` and `service-worker` preload types (`vinary.main.extensions`).
  - **Frame path (popups/options pages): WORKS.** `executeInMainWorld` runs in the page's own main world
    (chrome present, same realm), so a popup that reads e.g. `chrome.windows` does not crash. Verified by
    `test/extensions-smoke.js` (`popup.frameWindows`).
  - **Service-worker path: does NOT reach a real/heavy extension's SW (Electron-42 limitation).** See below.

  > **Correction (2026-06-28, round 5) — supersedes the round-3 "LastPass now works" claim, which was wrong.**
  > A real extension's background service worker still crashes. Reproduced and root-caused empirically (full
  > ledger: session scratchpad `lastpass-findings.md`):
  > LastPass's `background-redux-new.js` throws `Cannot read properties of undefined (reading 'onFocusChanged')`
  > → `Service worker registration failed. Status code: 15`, so its login UI never loads. Diagnostics in the
  > SW main world show the preload **does** run (`process.type=service-worker`), `installPolyfill` **does**
  > execute (`href=background-redux-new.js`, `chromePresent=true`), and it **does** set `chrome.windows`
  > (`typeof=object`) — yet the bundle reads `chrome.windows` as `undefined` ~0.24s later. An always-installed
  > accessor on `globalThis.chrome` (configurable **and** non-configurable) **never has its setter fire** and
  > has no effect. Conclusion: **`contextBridge.executeInMainWorld` from a `service-worker` preload executes in
  > a SEPARATE V8 realm from the extension's own SW script** for a non-trivial SW; whatever it defines is
  > invisible to the realm where the bundle runs. A trivial probe SW survives only because its realm coincides
  > with the executeInMainWorld realm — which is why `extensions-smoke.js` passes yet LastPass fails (the smoke
  > cannot reproduce the real failure; it is now annotated to say so). It is **not** module-vs-classic
  > (LastPass SW is classic), **not** the load path (`loadExtension`+`startWorkerForScope` — the smoke's exact
  > path — crashes identically on the real bundle, fresh and persisted sessions), and **not** a chrome
  > re-assignment.
  >
  > **In-bounds options exhausted:** executeInMainWorld (data property + configurable/non-configurable
  > accessor), `loadExtension`+`startWorkerForScope`, and `ServiceWorkerMain` (post-start IPC only, no
  > pre-evaluation hook). None bridge the realms. Providing `chrome.windows` natively would require patching
  > Electron. Patching the extension's on-disk SW file remains **rejected** (third-party password-manager
  > integrity / auto-update fragility / security). **Net: an extension whose background SW reads an
  > unimplemented chrome.* API at startup (e.g. LastPass) is currently UNSUPPORTED on Electron 42; its popup
  > path would work if the SW registered.** Tracking upstream: electron/electron#34178 (MV3 SW chrome.*).
- Auto-update polls Google's **unofficial** update endpoint (via `electron-chrome-web-store`, startup +
  ~5h) with CRX3 verification — see the threat model.

## Alternatives considered

- **`electron-chrome-extensions` (GPL-3.0 / paid).** Rejected on license grounds, and — post-spike —
  unnecessary for the popup/chrome.* surface Electron 42 already provides.
- **Build the chrome.* shim + preloads anyway** (the pre-spike plan). Rejected: the spike proved Electron
  provides those natively; the shim would be dead complexity.
- **Skip extensions, ship only the native ad-blocker.** Rejected: the user specifically wanted password
  managers.

## Trade-offs

- We own a small browser-action toolbar/popup surface (Electron renders none) — bounded, first-party,
  themeable.
- A new, opt-in trust boundary: extensions execute on `persist:vinary-web` — see
  `docs/security/threat-model.md`.

Cites: ADR-0009 (Mediator IPC seam — the app-renderer seam is unchanged), ADR-0014 (native ad-blocking on
the shared session).
