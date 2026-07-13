# Archived documentation — the v0.1.0 `vmd`-patch generation

This directory preserves the **first-generation documentation** of vinary-viewer. It
describes the **superseded v0.1.0 product**, which was *not* a standalone application
but a set of marker-guarded patches injected into an installed
[`vmd`](https://github.com/yoshuawuyts/vmd) package (Electron 3), plus an X11 mouse
addon and a transparent `vmd()` shell wrapper that re-applied the patches on launch.

> **These pages are retained for history and are not maintained.** They document a
> codebase that no longer ships. The v0.1.0 source is preserved at git tag
> **`v0.1.0`**; the running application is the standalone ClojureScript / re-frame /
> Electron rewrite documented in the live suite at [`../README.md`](../README.md).

Everything still true of the current app — the git file tree, the table-of-contents
scroll-spy, in-page find, embedded-figure font-matching, the `--vv-*` theme palette,
navigation history, live theme switching, and the security principles — was carried
forward and *expanded* in the second-generation suite. Nothing here needs to be read
to understand the current application; it is kept only so the project's early design
reasoning is reconstructable.

## Why these were archived

The v0.1.0 docs contradict the current architecture in load-bearing ways. For
example, `03-architecture.md` and `08-security-and-internals.md` describe a trust
model with `nodeIntegration` **on** and the Electron `remote` module in use — the
exact opposite of today's `contextIsolation: true`, `nodeIntegration: false`,
`window.vv`-mediated posture (see [`../security/threat-model.md`](../security/threat-model.md)).
`06-native-addon.md` documents a compiled X11 `XGrabButton` addon for "Electron 3",
and `02-installation.md` documents patching `vmd` in place rather than an
`npm install` + `shadow-cljs release` build. Rewriting them in place would have
erased that history; archiving keeps it intact and clearly labelled.

## Supersession map

Each archived page and the current document(s) that replace it.

| Archived (v0.1.0) | Superseded by (current suite) |
|-------------------|-------------------------------|
| [`01-introduction.md`](01-introduction.md) | [`../README.md`](../README.md), [`../theory/01-reactive-architecture.md`](../theory/01-reactive-architecture.md) |
| [`02-installation.md`](02-installation.md) | [`../usage/01-getting-started.md`](../usage/01-getting-started.md), [`../usage/02-installation-and-build.md`](../usage/02-installation-and-build.md) |
| [`03-architecture.md`](03-architecture.md) | [`../architecture/01-overview.md`](../architecture/01-overview.md), [`../architecture/02-process-and-build-topology.md`](../architecture/02-process-and-build-topology.md), [`../theory/04-hexagonal-and-ipc-mediator.md`](../theory/04-hexagonal-and-ipc-mediator.md) |
| [`04-features.md`](04-features.md) | [`../features/`](../features/README.md) (git tree, find, themes, history, image, Markdown, TOC) |
| [`05-theming.md`](05-theming.md) | [`../reference/css-variables.md`](../reference/css-variables.md), [`../features/06-themes-and-live-switching.md`](../features/06-themes-and-live-switching.md), [`../design-decisions/0007-css-mask-themed-watermark.md`](../design-decisions/0007-css-mask-themed-watermark.md) |
| [`06-native-addon.md`](06-native-addon.md) | *(none — the `mouse-forward-back` X11 addon is retired; history nav is now toolbar/`Alt+←/→`/web-view thumb buttons, see [`../features/07-navigation-history.md`](../features/07-navigation-history.md))* |
| [`07-history-navigation.md`](07-history-navigation.md) | [`../features/07-navigation-history.md`](../features/07-navigation-history.md), [`../theory/07-command-history-model.md`](../theory/07-command-history-model.md) |
| [`08-security-and-internals.md`](08-security-and-internals.md) | [`../security/threat-model.md`](../security/threat-model.md) |
| [`09-troubleshooting-and-uninstall.md`](09-troubleshooting-and-uninstall.md) | [`../usage/06-troubleshooting.md`](../usage/06-troubleshooting.md) |

## The `figures/` D2 diagrams

[`figures/`](figures/) holds four **D2** diagrams (`architecture`, `bootstrap-sequence`,
`history-flow`, `theming`) that illustrate the v0.1.0 patch topology — the `vmd()`
wrapper, `apply.sh` re-patching, inline `require(sidebar.js)`, the X11 thumb-button
path, and `VV_THEME`/`~/.config/.../theme` selection precedence. None of that exists in
the current app, so there is no PlantUML port. The current suite renders **all** its
figures as themed PlantUML under [`../diagrams/`](../diagrams/README.md); see that
catalog for the current system-context, container/build, deployment, and flow diagrams.
