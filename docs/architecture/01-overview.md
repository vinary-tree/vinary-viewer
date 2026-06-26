# 01. Architecture Overview

This page is the entry point for the architecture pillar. It describes the
process model, IPC seam, state ownership, renderer runtime, and current feature
status.

---

## 1. System purpose

vinary-viewer is a local-first Electron previewer for repository resources. It
opens Markdown, images, PDFs, source files, text files, and HTTP/HTTPS links. It
live-refreshes local files while preserving tabs, per-tab history, scroll
positions, settings, and keybinding state.

Key terms:

| Term | Meaning |
|------|---------|
| Main process | Electron/Node process that owns OS APIs: filesystem, dialogs, clipboard, config files, watchers, native PDF/web views, and git tree queries. |
| Renderer process | Chromium process that owns the re-frame/Reagent UI, Markdown rendering, source preview, tabs, search, TOC, and keybindings. |
| Preload mediator | `resources/preload.js`; exposes `window.vv` through `contextBridge`. |
| DataScript content cache | In-memory document cache keyed by `:doc/path`. |
| Retained file | A local file reachable from any open tab history entry. |

---

## 2. System context

vinary-viewer is read-only with respect to user documents. It reads and watches
files, reads git metadata for the sidebar, and writes only its own user config.

![System context](../diagrams/system-context.svg)

*Diagram source: [`../diagrams/system-context.puml`](../diagrams/system-context.puml).*

Primary loops:

| Loop | Behavior |
|------|----------|
| Open | Main reads/classifies a file and sends `vv:content`; renderer caches/renders and points a tab at it. |
| Live refresh | Main watcher re-sends content; renderer updates cached content and rendered metadata without resetting UI state. |
| Navigation | Renderer tab/history events change app-db, load retained local files when needed, and restore scroll. |
| Configuration | Main watches settings/keybinding/grammar files and pushes plain EDN/data over IPC. |

---

## 3. Two builds, one seam

| Build | Target | Output | Entry | Process |
|-------|--------|--------|-------|---------|
| `main` | `:node-script` | `dist/main/main.js` | `vinary.main.core/main` | Electron main |
| `renderer` | `:browser` | `resources/public/js/main.js` | `vinary.renderer.core/init` | Chromium renderer |

![Containers](../diagrams/container-two-build.svg)

*Diagram source: [`../diagrams/container-two-build.puml`](../diagrams/container-two-build.puml).*

The renderer never imports `electron`, `fs`, or `ipcRenderer`. The only
renderer-to-main API is `window.vv`, exposed by the preload mediator.

---

## 4. State ownership

| State | Owner | Examples |
|-------|-------|----------|
| UI and navigation | re-frame `app-db` | Tabs, active tab id, tab histories, saved scroll entries, sidebar state, find state, settings UI, keybinding UI. |
| Loaded content | DataScript | `:doc/path`, `:doc/kind`, `:doc/text`, `:doc/html`, `:doc/toc`, `:doc/assets`, `:doc/error`, `:doc/stamp`. |
| OS/native resources | Main process | Chokidar watchers, asset watchers, PDF `WebContentsView`, HTTP web view, config file watchers. |

The `:ds/rev` bridge connects DataScript to re-frame subscriptions. Each
DataScript transaction dispatches `[:ds/changed]`, increments `:ds/rev`, and
causes content-reading subscriptions to re-read the current DataScript snapshot.

---

## 5. Main process responsibilities

Main owns privileged operations:

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.main.core` | App/window lifecycle and initial file handling. |
| `vinary.main.service` | File reading, kind classification, retained watcher reconciliation, git tree, embedded asset watchers. |
| `vinary.main.pdf` | Native PDF view and PDF reloads. |
| `vinary.main.web` | In-app HTTP/HTTPS web view. |
| `vinary.main.config` | `keybindings.edn` load/save/watch. |
| `vinary.main.settings` | `settings.edn` load/save/watch. |
| `vinary.main.grammars` | Bundled and user grammar registry. |

Main does not parse Markdown, own tabs, or render ordinary UI.

---

## 6. Renderer responsibilities

Renderer owns reactive application behavior:

| Namespace family | Responsibility |
|------------------|----------------|
| `vinary.app.*` | app-db defaults, events, effects, subscriptions, navigation helpers, DataScript helpers, command registry. |
| `vinary.renderer.*` | Markdown rendering, source highlighting, TOC offset cache, figure sizing, scroll restore, media helpers, find. |
| `vinary.ui.*` | Reagent/Re-frame views, tabs, tree, menu bar, settings, keybinding editor. |
| `vinary.input.*` | Key tokenization, keymap presets, keymap registry, modal/chord resolver, input effects. |

The renderer uses React 19 through Reagent and renders the shell declaratively.
Rendered Markdown bodies are inserted through an imperative lifecycle component
because the HTML comes from the Markdown pipeline rather than React children.

---

## 7. Current status

Available now:

| Area | Status |
|------|--------|
| Launchers | `./install.sh` installs `vinary-viewer` and `vv`. |
| Markdown | GFM render, slugged headings, code highlighting, TOC metadata, asset tracking. |
| Live refresh | Retained-path watcher reconciliation and bounded DataScript cache eviction. |
| Tabs/history | Browser-like tabs, per-tab history, scroll restore, tab reorder, tab context menus, View Source. |
| PDF | Native Chromium PDF viewer in a main-owned `WebContentsView`. |
| Source preview | Read-only CodeMirror 6 with web-tree-sitter highlighting when a grammar or filetype mapping is available. |
| Grammar registry | Bundled grammars plus user grammars under `~/.config/vinary-viewer/grammars/` and filename/pattern mappings from `filetypes.edn`. |
| Mermaid rendering | Mermaid fences in Markdown and direct `.mmd` / `.mermaid` files render to SVG in the renderer. |
| Keybindings | Standard/Vim/Emacs presets, command registry, resolver, visual editor, persisted `keybindings.edn`. |
| Settings | Persisted theme and font settings in `settings.edn`. |
| HTTP links | In-app web view with heading outline integration. |

Still planned:

| Area | Current behavior |
|------|------------------|
| External diagram engines | `.d2`, `.puml`, `.dot`, and related non-Mermaid diagram sources open as source code; generated SVGs from those tools can be embedded in Markdown. |
| Additional security hardening | Renderer sandboxing and a strict CSP are tracked in the threat model. |

---

## 8. Architecture map

| Topic | Document |
|-------|----------|
| Build topology | [02-process-and-build-topology.md](02-process-and-build-topology.md) |
| IPC protocol | [03-ipc-protocol.md](03-ipc-protocol.md) |
| State schema | [04-state-schema-reference.md](04-state-schema-reference.md) |
| Data flows | [05-data-flows.md](05-data-flows.md) |
| Renderer runtime | [06-renderer-runtime.md](06-renderer-runtime.md) |
| Security model | [../security/threat-model.md](../security/threat-model.md) |
