# 1 — Introduction

## What `vinary-viewer` is

`vinary-viewer` (short name `vv`) is a set of enhancements layered onto
[`vmd`](https://github.com/yoshuawuyts/vmd), an Electron-based GitHub-flavored-Markdown previewer. It
turns `vmd` from a single-file previewer into an environment for *reading a documentation tree*: a
repository file-tree, a scroll-spy table of contents, a working in-page find, correctly sized figures,
complete history navigation, and switchable color themes.

It is **not a fork**. `vinary-viewer` installs by patching the `vmd` package already on your machine —
every change is guarded by a unique marker and backed up — and adds one renderer script and one
stylesheet. A transparent `vmd()` shell wrapper re-applies the patches each time you launch `vmd`, so
they survive `npm` upgrades of `vmd`.

## The problem it solves

Stock `vmd` is intentionally minimal. For sustained documentation reading that minimalism costs you:

| Pain point in stock `vmd` | What `vinary-viewer` does |
| --- | --- |
| One file at a time; no way to see the rest of the project. | A git **file-tree sidebar**; click to open any file in the viewer. |
| No document outline. | A **Contents** tab — a scroll-spy table of contents. |
| The bundled find (an `electron-in-page-search` webview) is broken. | A **custom find** highlighter scoped to the document body. |
| Content sits in a narrow 888 px centered column. | **Full-width** layout with the standard gutter preserved. |
| Embedded SVG diagrams are stretched to the column, magnifying their text. | **Figure font-matching** sizes each SVG so its text equals the body font. |
| History back/forward only grows on link clicks, and only via the menu. | **Unified history** across every open: keyboard, app-command, and mouse thumb buttons. |
| One look, forever. | **Named themes** (`spacemacs-dark`/`spacemacs-light`, plus your own). |

## Scope and non-goals

**In scope.** Anything that improves *reading and navigating* a local Markdown/asset tree in `vmd` on
Linux: layout, navigation, search, figures, and theming, plus the install/upgrade/uninstall machinery
that keeps those changes non-destructive.

**Out of scope.**

- *Replacing `vmd` or its Markdown renderer.* `vinary-viewer` styles and augments `vmd`'s output; it
  does not parse Markdown itself.
- *Editing.* This is a viewer.
- *Cross-platform parity.* The thumb-button addon is X11-specific (`XGrabButton`); the rest assumes
  `vmd`'s Electron renderer. macOS/Windows are not targets for `0.x`.

## Design philosophy

1. **Non-destructive.** Patches are marker-guarded and back up the file they touch; `uninstall.sh`
   restores the originals. You can always get stock `vmd` back.
2. **Upgrade-surviving.** Because `vmd` lives in an `npm` package that upgrades overwrite, the `vmd()`
   wrapper re-applies the patches on every launch. Re-applying is idempotent (markers prevent double
   patching).
3. **Portable.** No path is hard-coded. Scripts locate themselves (`apply.sh`), detect `vmd`
   (`command -v vmd`), and read the install dir from the environment. The repository can live anywhere
   and install for any user.
4. **Transparent.** You keep typing `vmd`. The enhancements are additive; outside a git repository the
   layout is byte-for-byte stock.
5. **Separation of structure and color.** The stylesheet is purely structural and references CSS
   variables; *color* lives entirely in small theme files, so adding a theme never touches layout.

## How to read this documentation

- New here? Continue to [02 — Installation](02-installation.md).
- Want the mental model? [03 — Architecture](03-architecture.md) explains the two Electron processes,
  the bootstrap, the four patches, and the `data-filepath` spine, with diagrams.
- Looking for one feature? [04 — Features](04-features.md) covers each in depth.
- The remaining chapters drill into [theming](05-theming.md), the [native addon](06-native-addon.md),
  [history navigation](07-history-navigation.md), [security and internals](08-security-and-internals.md),
  and [troubleshooting](09-troubleshooting-and-uninstall.md).
