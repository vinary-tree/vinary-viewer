# Ad & tracker blocking

**Status: Available now.** *(See [ADR-0014](../design-decisions/0014-native-ad-blocking-ghostery.md).)*

---

## 1 · What it is

When you follow an `http(s)` link, the page opens in Vinary's in-app web view. **Ad and tracker requests
are blocked natively** — at the network layer, before the page sees them — using
[`@ghostery/adblocker-electron`](https://github.com/ghostery/adblocker) (MPL-2.0) with the standard
EasyList / uBO filter lists. This is *not* an extension: it runs in the main process on the web view's
session, so it is immune to Chrome's MV3 limitations and adds no third-party code to the page.

It also **increases security** — malvertising and tracking requests are dropped before they reach the
renderer.

## 2 · How you use it

Open **Settings ▸ Extensions ▸ Ad blocking**:

- **Block ads & trackers** — the master on/off toggle.
- **Lists** — *Ads + tracking* (default) or *Ads only*.
- **Update filter lists** — refetch the lists now (they also auto-update daily).

Settings persist in `~/.config/vinary-viewer/extensions.edn` and apply live to the web view. Offline, the
last cached filter engine is used; on a first run with no network, blocking is simply inert (no errors).

## 3 · Internals

| Piece | Where |
|---|---|
| Engine build/enable/refresh/schedule on `persist:vinary-web` | `vinary.main.adblock` |
| Engine cache | `<userData>/adblock-engine.bin` |
| Persisted prefs (`:adblock {…}`) | `vinary.main.ext-config` ↔ `extensions.edn` |
| UI (toggle / lists / refresh) | `vinary.ui.extensions` (Settings ▸ Extensions) |
| Block test (network-gated) | `test/extensions-smoke.js` |

The blocker is scoped to the web-browsing session only — never the app renderer, the PDF view, or
`file://` tabs.
