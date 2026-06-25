# 0001 — Electron 42 supersedes the v0.1.0 Electron 13 pin

- **Status:** Accepted — supersedes the v0.1.0 platform choice
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

The superseded **v0.1.0** tool was a set of *patches applied to `vmd`*. `vmd` pinned **Electron 13**,
and v0.1.0 inherited that pin because it had to coexist inside `vmd`'s process — notably to keep its
native X11 *mouse forward/back* addon and thumbnail tweaks binary-compatible with `vmd`'s old Electron
ABI. The pin was therefore a **constraint of patching someone else's app**, not a considered platform
choice.

The current product is a **ground-up ClojureScript rewrite** (`src/vinary/**`, `resources/**`) that runs
as its **own** Electron application. It no longer patches `vmd`, no longer ships a native addon, and no
longer needs to match any external ABI. That removes every reason the old pin existed.

## Decision

Target **modern Electron**. `package.json` declares `"electron": "^42.5.0"` as a dev dependency, and the
window is created by our own `vinary.main.core/create-window!` with current `webPreferences`
(`contextIsolation:true`, `nodeIntegration:false`, a `preload`). The build is shadow-cljs (`:main` →
`dist/main/main.js`, `:renderer` → `resources/public/js/main.js`).

## Consequences

- **Modern security defaults are available and used.** Electron 42 supports `contextIsolation` and the
  `contextBridge` cleanly (see [ADR-0009](0009-mediator-ipc-over-point-to-point.md) and
  [security/threat-model.md](../security/threat-model.md)); the old Electron-13 era predated some of
  these being defaults.
- **Native browser capabilities we want are built in.** Modern Chromium provides native **PDF**
  rendering, **WebAssembly**, and the **CSS Custom Highlight API** that the in-page find feature relies
  on ([ADR-0003](0003-ref-innerHTML-no-vdom-body.md) / `vinary.renderer.find`). These are available
  without bespoke native code.
- **The native mouse forward/back X11 addon is very likely unnecessary.** History navigation is now
  implemented in-app (`Alt+←/→`, toolbar buttons; `vinary.app.events` history). If literal mouse-button
  back/forward is ever wanted, modern Electron/Chromium expose it through standard input events rather
  than a compiled X11 addon. *(Confirming the exact mouse-button mapping is Forthcoming, but no native
  addon is anticipated.)*
- **No `vmd` coupling.** We control the Electron version, upgrade cadence, and `webPreferences`
  outright.

## Alternatives considered

- **Keep Electron 13 / keep patching `vmd`.** Rejected: the rewrite's entire premise is to *not* be a
  `vmd` patch. The pin only ever served ABI compatibility with `vmd`, which no longer applies, and
  Electron 13 lacks the modern isolation defaults and native features above.
- **A non-Electron shell (e.g. Tauri).** Out of scope for this decision; the project is committed to the
  ClojureScript + React/reagent + Electron stack. Revisiting the shell would be a separate ADR.

## Trade-offs

- We accept the usual **Electron footprint** (a bundled Chromium + Node) in exchange for native PDF/WASM,
  modern security primitives, and a single cross-platform runtime. For a developer-facing local
  previewer this is an acceptable cost, and it removes the far larger cost of maintaining native addons
  against a frozen Electron ABI.
