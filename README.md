# vinary-viewer (`vv`)

**A reactive desktop previewer for Markdown, diagrams, PDFs, images, and source code — with live
refresh.** A standalone ClojureScript / re-frame / Electron application, *inspired by*
[vmd](https://github.com/yoshuawuyts/vmd) (no vmd source is vendored).

> Status: `0.2.0-dev`. This is the v0.2 rewrite — a self-contained app, not the v0.1.0 vmd-patching
> tool. The previous tool is preserved at git tag **`v0.1.0`**.

## Features

- **Live refresh** — Markdown, diagrams, PDFs, images, and source files re-render the instant the file
  changes on disk, preserving your scroll position and UI state.
- **Browser-like navigation** — tabs are independent views with **per-tab history**: left-click a tree
  entry or link to navigate the active tab, **Ctrl+click** to open a new tab. A **URI bar** with
  back/forward (plus `Alt+←/→` and the **mouse thumb buttons**) shows the current document's address;
  back/forward restore the document **and its scroll position**. Hovering a link shows its URL bottom-left.
  You can **follow HTTP links** (e.g. citations) in an in-app web view.
- **Tab management** — **drag to reorder** tabs, **right-click** for *Close / Close Others / Close to the
  Right / View Source / Copy path*, a per-tab **View Source** toggle (raw Markdown in the pane), and a
  vertical **Tabs** sidebar panel that mirrors the strip's order (reordering either reorders both).
- **Markdown** — GitHub-flavored (remark/unified) with slugged headings and syntax-highlighted code,
  **full-width** layout, centered images + tables, and **figure font-matching** (embedded SVGs are sized
  so their text matches the document font). Relative image/link paths resolve against the document.
- **Native PDF** — Chromium's built-in PDF viewer in an embedded native view, with live reload.
- **Diagrams** — `.d2`, PlantUML (`.puml`), Mermaid (`.mmd`), Graphviz (`.dot`) → SVG, live.
- **Source code** — read-only CodeMirror 6 with **tree-sitter** highlighting; **Rholang** bundled, more
  via a user grammar registry (`~/.config/vinary-viewer/grammars/<lang>/{grammar.wasm,highlights.scm}`).
- **Tabbed multi-project sidebar** — **Files** (one collapsible tree per open project, rooted at its
  directory name, with a filter) and **Contents** (a scroll-spy outline that follows Markdown *and* HTML
  sections); **collapsible** and **resizable**.
- **Menu bar + context menus** — a themed `File / View / Settings / Help` menu bar; right-click files,
  directories, and document links for **Open / Open in new tab / Copy path / Copy name**. `File ▸ Open`
  is a multi-file dialog (one file → current tab, many → one tab each).
- **Preferences** — change the variable- and fixed-width fonts and sizes (persisted to
  `settings.edn`); **named themes** (Spacemacs dark/light) with live switching.
- **In-page find** (`Ctrl+F`), **image view**, **command palette**, and an empty-tab **Vinary Tree
  logo** watermark.
- **Custom keybindings** — `default` / **vim** / **emacs** keymaps, switchable live from **Settings ▸ Key
  Bindings**, plus a two-pane visual **editor** (`Customize…`) with key capture, emacs-style modifier
  chips, clone / rename / drag-reorder, and **undo/redo**. Vim mirrors **Vimium**: `h`/`l` scroll, **`f`**
  link hints, `/` find. Sets persist to `~/.config/vinary-viewer/keybindings.edn` (live-reloaded).

## Install

```bash
./install.sh            # npm install + shadow-cljs release + installs vv / vinary-viewer launchers
vv README.md            # open a file
```

The launchers go to `~/.local/bin` (override with `BIN=/dir ./install.sh`). `./uninstall.sh` removes
them. Requires Node.js (with `npx`) and a JDK (for shadow-cljs). Development: `npm run dev`.

## Configuration

Under `~/.config/vinary-viewer/`:

- `keybindings.edn` — the keymap-set registry (`{:active … :order … :sets …}`), usually managed by the
  **Settings ▸ Key Bindings** menu + editor; a legacy single delta (`{:extends :vim}` + overrides) is still
  accepted and auto-wrapped.
- `grammars/<lang>/` — drop a tree-sitter `grammar.wasm` + `highlights.scm` to highlight `<lang>`.
- the in-app theme selector switches Spacemacs dark/light live.

## Documentation

A full documentation suite lives under [`docs/`](docs/README.md) — theory, architecture, design
decisions, usage, per-feature guides, a reference catalog, a threat model, and ~30 PlantUML diagrams.

## Architecture (in brief)

Two shadow-cljs builds: an Electron **main** process (file-watch IO + native PDF + an HTTP web view +
diagram rendering + dialogs / clipboard / config / settings) and a **renderer** (the re-frame reactive
UI), talking only through a `contextBridge` **Mediator** seam (plain JSON / EDN text). **Tabs are
browser-like views** — each with a current URI and its own back/forward history — held in app-db;
**DataScript** caches loaded document content (bridged to re-frame subscriptions by a
transaction-revision counter). See `docs/architecture/`.

## Attribution & license

vinary-viewer © Vinary Tree, **Apache-2.0** (its own code). *Inspired by* **vmd** (Yoshua Wuyts, MIT)
— credited, no source vendored. Architecture/patterns credited to **LightningBug** (f1r3fly) and the
**pgmcp `webui`** design. Built on remark/unified, web-tree-sitter, CodeMirror 6, Electron, chokidar,
reagent/re-frame/re-com/DataScript, and the d2/PlantUML/Mermaid/Graphviz CLIs — under their own
licenses. The Spacemacs color palette is used by value. See [`NOTICE`](NOTICE).
