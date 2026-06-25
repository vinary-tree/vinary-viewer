# 01 · Architecture Overview

> **Scope.** This page is the entry point to the *architecture* pillar of the vinary-viewer
> documentation. It gives the **system context** (who and what the app talks to), the **container
> view** (the two builds and the seam between them), and the central design thesis — **a thin main
> process and a smart renderer**. Subsequent pages drill into the build topology (02), the IPC
> protocol (03), the state schema (04), the data flows (05), and the renderer runtime (06).

---

## 1. What vinary-viewer is

**vinary-viewer** is a reactive desktop **document previewer** built with **ClojureScript**,
**re-frame**, **re-com**, and **Electron**. It opens a file named on the command line
(`vv README.md`), renders it, and — crucially — **re-renders it live** whenever the file changes on
disk, so you edit in your own editor and watch the preview update without lifting a finger. It
previews Markdown (with GitHub-Flavoured extensions, heading anchors, and syntax-highlighted code),
images, and plain text today; PDF, diagram, and source-code views are
[Forthcoming](#7-status-current-vs-forthcoming).

> **Terminology, defined before use.**
> - **Main process** — the Electron/Node process that owns OS resources (windows, the filesystem, child
>   processes). One per app.
> - **Renderer process** — the Electron/Chromium process that runs the UI (a web page). One per window.
> - **IPC** (*Inter-Process Communication*) — the message channel between main and renderer.
> - **Preload** — a small script that runs in the renderer's process *with* Node privileges, before the
>   page loads, used to expose a **narrow, safe API** to the otherwise-sandboxed page.
> - **re-frame** — a ClojureScript framework implementing a unidirectional **event → effect → state →
>   view** loop (events, effects (`fx`), subscriptions (`subs`), and a single app database, `app-db`).
> - **DataScript** — an immutable, in-memory **Datalog** database; here it is the single source of truth
>   (SSOT) for the open documents and tabs.
> - **reagent** — a ClojureScript wrapper over React that renders UI from plain data (*hiccup*).

The package is `vinary-viewer` at version `0.2.0-dev`; it is *inspired by* [vmd](https://github.com/yoshuawuyts/vmd)
but is a new application with original code under **Apache-2.0**.

---

## 2. System context

vinary-viewer sits between the **user**, the **filesystem** (where the documents live), the **git
repository** that contains them (for the file-tree sidebar), and the user's **external editor** (the
thing that actually changes files). vinary-viewer is **read-only** with respect to user content: it
never writes the documents it previews.

![System context](../diagrams/system-context.svg)

*Source: [`../diagrams/system-context.puml`](../diagrams/system-context.puml).*

```text
        edits in                       saves                       reads + watches
 User ───────────▶ External Editor ───────────▶ Filesystem ◀───────────────────── vinary-viewer
   │                                                  │                                  ▲
   │ clicks tabs / tree / TOC,                        │  change / add events             │ git rev-parse
   │ finds, switches theme                            └──────────────────────────────────┤ + git ls-files
   └──────────────────────────────────────────────────────────── live refresh ──────────┘
```

The essential loops:

1. **Open** — the user launches `vv <path>`; main reads the file and pushes its content to the
   renderer (see [05 · Data Flows](./05-data-flows.md#1-open-a-file) and
   [`seq-open-file`](../diagrams/seq-open-file.puml)).
2. **Live refresh** — main watches every open path; on each save it re-pushes content, and the
   renderer re-renders **without disturbing scroll position or which tab is active**.
3. **Navigate** — tabs, the git file-tree, in-page find, the table-of-contents scroll-spy, and a
   back/forward history are all driven from the renderer.

---

## 3. The container view: one source tree, two builds, one seam

A single ClojureScript source tree (`src/vinary/**`) is compiled by **shadow-cljs** into **two**
independent JavaScript artifacts that run in **two** Electron processes, joined by **one** IPC seam
(the preload `contextBridge`).

![Containers](../diagrams/container-two-build.svg)

*Source: [`../diagrams/container-two-build.puml`](../diagrams/container-two-build.puml).*

| Build (`shadow-cljs.edn`) | Target | Output | Entry | Runs in |
| --- | --- | --- | --- | --- |
| `:main` | `:node-script` | `dist/main/main.js` | `vinary.main.core/main` | MAIN (Node) |
| `:renderer` | `:browser` | `resources/public/js/main.js` | `vinary.renderer.core/init` | RENDERER (Chromium) |

The **only** path between the two processes is the amber seam: `resources/preload.js` exposes a
minimal JSON-only object, `window.vv`, via Electron's `contextBridge`. The renderer never touches the
filesystem or `ipcRenderer` directly. (Full catalog in [03 · IPC Protocol](./03-ipc-protocol.md).)

---

## 4. The thesis: thin main, smart renderer

vinary-viewer deliberately concentrates **almost all logic in the renderer** and keeps **main as a
thin IO service at the edge**. This is the inverse of the "fat backend, dumb view" split common to
desktop apps, and it is a load-bearing decision, so it deserves a clear rationale.

### What main does (and only this)

`vinary.main.core` + `vinary.main.service` together:

- create the `BrowserWindow` with a sandbox-leaning posture (`contextIsolation: true`,
  `nodeIntegration: false`, the preload script);
- parse the initial file from `argv`;
- **read** a file's bytes (`fs.readFileSync`) and classify it (`kind-of` → `markdown` / `image` /
  `text`);
- **push** content/errors/tree to the renderer (`webContents.send`);
- **watch** each open path with one chokidar watcher and re-push on change;
- compute the **git file-tree** (`git rev-parse --show-toplevel` + `git ls-files`).

Notably, main does **not** parse Markdown, manage tabs, hold UI state, or know anything about
rendering. Rendering happens in the renderer because the **all-ESM remark/rehype stack bundles
cleanly for the browser** and runs against the live DOM.

### What the renderer does

`vinary.renderer.*` + `vinary.app.*` + `vinary.ui.*` own:

- the **re-frame loop** (events, effects, subscriptions);
- the **DataScript** document/tab store and the reactivity bridge that connects it to re-frame;
- **Markdown rendering** (the unified pipeline);
- the **multi-tab** model, **git file-tree**, **in-page find**, **theme** switching,
  **table-of-contents** scroll-spy, and navigation **history**;
- the **reagent** view tree (React 19).

### Why this split

| Force | Consequence of "thin main, smart renderer" |
| --- | --- |
| **Security** | The renderer is sandbox-leaning and reaches the filesystem **only** through the audited preload seam; the app's large surface (rendering, UI) runs with no Node privileges. |
| **Reactivity** | Live refresh, tabs, find, and history are naturally expressed as a unidirectional re-frame loop over an immutable store — keeping them in one process avoids cross-process state synchronisation. |
| **Toolchain fit** | remark/rehype is browser-friendly ESM; the renderer is exactly where it belongs. Main stays Node-only (`fs`, `chokidar`, `child_process`) with no bundling of UI deps. |
| **Testability / time-travel** | re-frame's `fx`-at-the-edge discipline plus DataScript immutability make the renderer replayable and inspectable (re-frame-10x, re-frisk). |

---

## 5. Concern → process → namespace map

The table below is the canonical "where does X live?" index. Namespaces are detailed in
[reference/namespaces.md](../reference/namespaces.md).

| Concern | Process | Namespace(s) / file | Key entry points |
| --- | --- | --- | --- |
| App + window lifecycle | MAIN | `vinary.main.core` | `main`, `create-window!`, `initial-file` |
| File IO, watch, git tree | MAIN | `vinary.main.service` | `open!`, `close!`, `send-content!`, `repo-tree`, `init!` |
| IPC seam (contextBridge) | preload | `resources/preload.js` | `window.vv.{open,close,onContent,onError,onTree}` |
| Renderer bootstrap | RENDERER | `vinary.renderer.core` | `init`, `bridge!`, `keybindings!`, `mount!` |
| Ephemeral UI state (default) | RENDERER | `vinary.app.db` | `default-db` |
| Document/tab SSOT + reactivity bridge | RENDERER | `vinary.app.ds` | `schema`, `conn`, `install-bridge!`, query helpers |
| Events (the loop's verbs) | RENDERER | `vinary.app.events` | `:content/received`, `:tab/*`, `:theme/set`, `:find/*`, … |
| Effects (IO/async at the edge) | RENDERER | `vinary.app.fx` | `:ds/transact`, `:markdown/render`, `:theme/apply`, `:vv/*`, … |
| Subscriptions (the Observer graph) | RENDERER | `vinary.app.subs` | `:tabs`, `:doc/active`, `:doc/toc`, `:history/*`, … |
| Markdown rendering | RENDERER | `vinary.renderer.markdown` | `render` (unified pipeline) |
| In-page find | RENDERER | `vinary.renderer.find` | `search!`, `cycle!`, `clear!` |
| Table-of-contents + scroll-spy | RENDERER | `vinary.renderer.toc` | `extract`, `spy!` |
| Views (shell, content, toolbar, find, TOC) | RENDERER | `vinary.ui.views` | `root`, `content-view`, `markdown-body`, … |
| Tab strip | RENDERER | `vinary.ui.tabs` | `tab-strip` |
| Git file-tree sidebar | RENDERER | `vinary.ui.tree` | `file-tree`, `build-tree` |
| Command registry | RENDERER | `vinary.app.commands` | `registry`, `predicates`, `run`, `all-visible` |
| Keymap resolver (keydown → command) | RENDERER | `vinary.input.resolver` | `install!`, `step`, `handle` |
| Keymap presets + merge | RENDERER | `vinary.input.keymap` | `bundled`, `install!`, `install-user!`, `modes` |
| Key normalization | RENDERER | `vinary.input.keys` | `event->chord`, `normalize-token` |
| Input/palette events + effects | RENDERER | `vinary.input.events`, `vinary.input.fx` | `:input/*`, `:palette/*`; `:dom/scroll`, `:dom/focus` |

---

## 6. The reactive spine (one paragraph you should remember)

Content arrives from main on **every file change** and is **transacted into DataScript**. Each
DataScript transaction fires a listener that dispatches `[:ds/changed]`, which bumps a counter
`:ds/rev` in `app-db`. The conn-reading subscriptions (`:tabs`, `:doc/active`) declare `:<- [:ds/rev]`
as an input, so they recompute exactly when the store changes — and the reagent views that subscribe
to them re-render. A content update **never** writes scroll or active-tab state (those live in
`app-db`), which is precisely why live-refresh preserves *where you are* in the document. This
"`:ds/rev` bridge" is the explicit, hand-rolled reactivity glue; see
[04 · State Schema](./04-state-schema-reference.md) and
[06 · Renderer Runtime](./06-renderer-runtime.md).

---

## 7. Status: current vs Forthcoming

**Available now:** live refresh; multi-tab Markdown/text/image preview; git file-tree with filter;
in-page find (CSS Custom Highlight API); themes with live switching (Spacemacs dark/light);
back/forward history; table-of-contents scroll-spy; and a **data-driven keybinding system** — a
command registry (`vinary.app.commands`), a keymap resolver (`vinary.input.resolver`), and three
bundled presets (`:default` non-modal, `:vim` modal, `:emacs`) embedded at compile time. The default
preset generalizes the original `Ctrl+F` (find) and `Alt+←/→` (history) bindings; switch preset at
runtime with `window.__vvkeymap("vim")`.

**Wired but pending:** (a) loading a **user** `~/.config/vinary-viewer/keybindings.edn` over the
`vv:keymap` IPC channel — the renderer subscribes/pulls but main has no sender yet (falls back to the
bundled `:default`); (b) the **command-palette view** — its events + `:palette/state` subscription
exist, but no palette component is rendered yet. See
[reference/events-effects-subs.md §4.4](../reference/events-effects-subs.md#44-what-is-wired-vs-pending).

**Forthcoming (planned, not built):** `vv` / `vinary-viewer` binary launchers; a
`~/.config/vinary-viewer/` grammar registry; native PDF view (BrowserView); diagram rendering
(d2/plantuml/mermaid → SVG); a tree-sitter source view.

---

## 8. Where to go next

- [02 · Process & Build Topology](./02-process-and-build-topology.md) — the shadow-cljs builds, the
  dependency stack, and the dev hot-reload loop.
- [03 · IPC Protocol](./03-ipc-protocol.md) — the authoritative channel catalog and the seam's
  security rationale.
- [04 · State Schema Reference](./04-state-schema-reference.md) — the DataScript schema, the
  schema-less attribute catalog, and `app-db`.
- [05 · Data Flows](./05-data-flows.md) — a literate trace + sequence diagram per user action.
- [06 · Renderer Runtime](./06-renderer-runtime.md) — `init`, the bridges, the subscription graph,
  and the imperative `innerHTML` body.
