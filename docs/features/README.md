# vinary-viewer — Features

This pillar documents every user-facing capability of **vinary-viewer** (`vv`): a reactive
ClojureScript / [re-frame](https://day8.github.io/re-frame/) / [Electron](https://www.electronjs.org/)
desktop previewer for Markdown, images, and source text, with **live refresh** as files are edited.

Each feature has its own page following a fixed template:

1. **What it is** — the intuition.
2. **How to use it** — concrete steps and an example.
3. **How it works internally** — a literate walk of the exact code that implements it.
4. **Design notes / trade-offs** — the relevant Architecture Decision Record (ADR).
5. **Forthcoming** — for partially-built features, what is planned next.
6. **Diagram(s)** — fully-colored PlantUML illustrations.

> **Status legend.** **Available now** means the capability is implemented in the live
> ClojureScript app under `src/vinary/**` and `resources/**`. **«planned»** (Forthcoming)
> means it is designed but wired. One feature — **custom keybindings** — is
> **now available**: its IO effects (`vinary.input.fx`) and `app-db` state slots are
> landed, but the command registry, keymaps, and resolver are wired, so the
> baseline `Ctrl+F` / `Alt+←/→` bindings remain active.

---

## Feature matrix

| # | Feature | Status | What it does | How you use it | Internals |
|---|---------|--------|--------------|----------------|-----------|
| 01 | **Live refresh** | Available now | Re-renders the open document the instant the file changes on disk, preserving your scroll/UI position. | Just edit and save the file in any editor. | [01-live-refresh.md](01-live-refresh.md) |
| 02 | **Multi-tab previews** | Available now | One tab per open document; a tab strip to switch and close. | Open files (CLI arg or file-tree click); click a tab to activate, `×` to close. | [02-multi-tab-previews.md](02-multi-tab-previews.md) |
| 03 | **Watermark on empty tabs** | Available now | A faint, theme-tinted Vinary Tree shield fills the content area when nothing is open. | Close all tabs (or launch with no file) to see it. | [03-watermark-empty-tabs.md](03-watermark-empty-tabs.md) |
| 04 | **Git file-tree + filter** | Available now | A sidebar showing the git repository of the open file as a collapsible tree, with a live text filter. | Open a file inside a git repo; click entries to open; type in the filter box to narrow. | [04-git-file-tree-and-filter.md](04-git-file-tree-and-filter.md) |
| 05 | **In-page find** | Available now | Highlights all matches of a query within the rendered document and cycles between them. | `Ctrl+F`, type a query, `Enter` / `Shift+Enter` to cycle, `Esc` to close. | [05-in-page-find.md](05-in-page-find.md) |
| 06 | **Themes + live switching** | Available now | Spacemacs-dark / -light palettes, swapped instantly at runtime. | Pick a theme from the toolbar `<select>`. | [06-themes-and-live-switching.md](06-themes-and-live-switching.md) |
| 07 | **Navigation history** | Available now | A browser-style back/forward stack over the documents you visit. | Toolbar `←` / `→` buttons, or `Alt+←` / `Alt+→`. | [07-navigation-history.md](07-navigation-history.md) |
| 08 | **Image view** | Available now | Renders image files directly (no text read) by `file://` URL. | Open a `.png` / `.jpg` / `.svg` / … file. | [08-image-view.md](08-image-view.md) |
| 09 | **Markdown rendering** | Available now | GitHub-flavored Markdown → HTML with heading slugs and syntax highlighting, themed via CSS variables. | Open a `.md` / `.markdown` / `.mdx` file. | [09-markdown-rendering.md](09-markdown-rendering.md) |
| 10 | **Scroll-spy TOC** | Available now | A right-hand table of contents that tracks which heading is at the top of the viewport. | Open a Markdown file with headings; scroll, or click a TOC entry. | [10-scroll-spy-toc.md](10-scroll-spy-toc.md) |
| 11 | **Native PDF** | «planned» | A Chromium `BrowserView` positioned over the active tab to render PDFs, with live-reload. | (Forthcoming) open a `.pdf` file. | [11-native-pdf.md](11-native-pdf.md) |
| 12 | **Diagram rendering** | «planned» | `d2` / PlantUML / Mermaid sources rendered to SVG as a new Strategy entry, live-refreshed. | (Forthcoming) open a diagram source file. | [12-diagram-rendering.md](12-diagram-rendering.md) |
| 13 | **Source preview (tree-sitter)** | «planned» | Read-only CodeMirror 6 + web-tree-sitter syntax highlighting with incremental parsing. | (Forthcoming) open a source file in a registered language. | [13-source-preview-tree-sitter.md](13-source-preview-tree-sitter.md) |
| 14 | **Grammar registry** | «planned» | Bundled + user-supplied tree-sitter grammars, validated by `clojure.spec`. | (Forthcoming) drop a `.wasm` + `highlights.scm` + config into `~/.config/vinary-viewer/grammars/`. | [14-grammar-registry.md](14-grammar-registry.md) |
| 15 | **Custom keybindings** | Available now | A command registry + vim/emacs keymaps + modal/chord resolver + user EDN config + command palette. | Today: `Ctrl+F`, `Alt+←/→`. Soon: configurable keymaps. | [15-custom-keybindings.md](15-custom-keybindings.md) |

---

## How the features fit together

vinary-viewer is **one reactive loop**, not a bag of independent widgets. Most features are
**derived views over a single source of truth** — the
[DataScript](https://github.com/tonsky/datascript) database that holds one `:doc` entity per
open document — plus a small amount of **ephemeral UI state** in the re-frame `app-db`
(active tab, theme, find query, scroll-spy highlight, history). This is why, for example,
**live refresh** never disturbs your **scroll position** or **find** state: content lives in
DataScript; where-you-are lives in `app-db`; a content update touches only the former.

```
                    ┌─────────────────────────── Electron MAIN (Node) ───────────────────────────┐
   file on disk ──▶ │  chokidar watcher (per open path) ──▶ read + classify ──▶ vv:content / vv:tree │
                    └───────────────────────────────────┬──────────────────────────────────────────┘
                                                         │  IPC seam (preload contextBridge, JSON only)
                    ┌────────────────────────────────────▼─────────────── Electron RENDERER (Chromium) ──┐
                    │  re-frame events ─▶ DataScript (:doc/*) ─▶ subscriptions ─▶ reagent UI            │
                    │      │                    ▲                                                        │
                    │      │   markdown render fx (unified pipeline) ──┘                                 │
                    │      └─▶ app-db (active tab · theme · find · history · scroll-spy)                 │
                    └────────────────────────────────────────────────────────────────────────────────────┘
```

The shared mechanisms are documented once in the **theory** pillar and cross-referenced from
the feature pages:

- The **live-refresh spine** — [theory/03-live-refresh-spine.md](../theory/03-live-refresh-spine.md)
  (the reactive heart used by features 01, 02, 08, 09, 10, and — when built — 11/12/13).
- The **`:ds/rev` reactivity bridge** and state model —
  [theory/02-state-model-datascript-app-db.md](../theory/02-state-model-datascript-app-db.md).
- The **Strategy renderer registry** (content-view by `:doc/kind`) —
  [theory/05-strategy-renderer-registry.md](../theory/05-strategy-renderer-registry.md).
- The **CSS Custom Highlight API** find model —
  [theory/06-find-css-custom-highlight.md](../theory/06-find-css-custom-highlight.md).
- The **Command-pattern navigation history** —
  [theory/07-command-history-model.md](../theory/07-command-history-model.md).

For the per-feature ADRs that record *why* each design was chosen, see
[../design-decisions/README.md](../design-decisions/README.md).

---

## Diagrams in this pillar

Every diagram is PlantUML, located under [../diagrams/](../diagrams/), and reuses the shared
palette in [`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml) so a color always means the
same concept (slate = MAIN/Node-IO, teal = Renderer, amber = IPC seam, purple = DataScript,
blue-violet = ephemeral `app-db`, green = Markdown/unified, red = errors, dashed grey = «planned»).

Owned by this pillar:

- [`object-watermark.puml`](../diagrams/object-watermark.puml) — the watermark emblem pipeline.
- [`seq-toc.puml`](../diagrams/seq-toc.puml) — scroll-spy TOC extract / spy / scroll.
- [`object-history-stack.puml`](../diagrams/object-history-stack.puml) — the history stack evolving.
- [`component-native-pdf-planned.puml`](../diagrams/component-native-pdf-planned.puml) — «planned».
- [`component-diagram-rendering-planned.puml`](../diagrams/component-diagram-rendering-planned.puml) — «planned».
- [`component-tree-sitter-planned.puml`](../diagrams/component-tree-sitter-planned.puml) — «planned».
- [`component-grammar-registry-planned.puml`](../diagrams/component-grammar-registry-planned.puml) — «planned».
- [`component-keybindings-inprogress.puml`](../diagrams/component-keybindings-inprogress.puml) — now available.
