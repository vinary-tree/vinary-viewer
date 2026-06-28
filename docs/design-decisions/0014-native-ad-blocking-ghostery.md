# 0014 — Native ad/tracker blocking with @ghostery/adblocker-electron

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** vinary-viewer maintainers

## Context

The in-app web view (`vinary.main.web`) browses documentation/citation pages. Users asked for ad and
tracker blocking, both for a cleaner reading experience and for **security** (malvertising and trackers
are a real attack surface for a desktop app embedding a browser). Two ways exist to deliver it in
Electron: load an ad-block **extension** (e.g. uBlock Origin), or block **natively** in the main process.

## Decision

**Block natively with [`@ghostery/adblocker-electron`](https://github.com/ghostery/adblocker) (MPL-2.0)
on the web view's persistent session (`persist:vinary-web`) — not via an extension.**

`vinary.main.adblock` builds an `ElectronBlocker` from the prebuilt EasyList/uBO lists
(`fromPrebuiltAdsAndTracking` or `fromPrebuiltAdsOnly`), serializes the engine to a cache under
`userData` (`adblock-engine.bin`), and calls `enableBlockingInSession` so requests are dropped at
`webRequest` **before the page sees them**. A scheduler refreshes the lists on a cadence
(`:update-every-hours`, default 24); offline/first-run **degrades** gracefully — it falls back to the
cache, then to an empty (no-op) engine, never crashing. Enable/disable and the list-set are persisted in
`extensions.edn` and toggled live from **Settings ▸ Extensions** (`vinary.ui.extensions`).

The blocker is scoped to `persist:vinary-web` **only** — it never touches the app renderer session, the
PDF view, or `file://`/`data:` tabs.

## Consequences

- Ad/tracker blocking is **reliable and immune to Chrome's MV3 limitations** (which neutered
  `webRequest`-based blocking for extensions): we use the native `webRequest` hook directly.
- **No third-party extension code** runs to deliver it — a smaller attack surface than loading uBO as an
  extension. MPL-2.0 is file-level copyleft and imposes no obligations on Vinary's Apache-2.0 source.
- Filter lists auto-update on a schedule and survive restarts (cached under `userData`).
- Verified by `test/extensions-smoke.js`: with network available, a request to a known ad host
  (`doubleclick.net`) is blocked on the session.

## Alternatives considered

- **Load uBlock Origin as an extension.** Rejected: subject to MV3's reduced blocking and Electron's
  partial extension APIs — strictly worse, and it adds third-party code to the trust boundary.
- **A blocklist hand-rolled over `webRequest`.** Rejected: maintaining + updating EasyList-quality rules
  is a project in itself; `@ghostery/adblocker` is the maintained, battle-tested engine.

## Trade-offs

- A dependency + a periodic network fetch for filter lists (cached, degradable offline).
- Cosmetic filtering uses the library's session preload; it coexists additively with the web view's own
  preload and any extension content scripts (verified).

Pairs with **ADR-0015** (the scoped extension runtime shares the same `persist:vinary-web` session).
