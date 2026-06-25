# Reference · Namespaces

> **What this is.** A per-namespace map of the vinary-viewer source: the **responsibility**, the
> **key vars/fns**, and what each **depends on**. Grouped by process. The architecture pillar
> ([architecture/01-overview.md](../architecture/01-overview.md)) explains how they fit together; this
> page is the per-file reference. Component diagrams:
> [`component-renderer.puml`](../diagrams/component-renderer.puml) and
> [`component-main-service.puml`](../diagrams/component-main-service.puml).

Layout:

```text
src/vinary/
  main/      core.cljs        service.cljs                 ← MAIN (Node)
  app/       db.cljs  ds.cljs  events.cljs  subs.cljs  fx.cljs  commands.cljs
  input/     events.cljs  fx.cljs  keymap.cljs  keys.cljs  resolver.cljs  presets.clj
  renderer/  core.cljs  markdown.cljs  find.cljs  toc.cljs
  ui/        views.cljs  tabs.cljs  tree.cljs              ← RENDERER (Chromium)
resources/   preload.js       keymaps/{default,vim,emacs}.edn
```

---

## MAIN process

![Main components](../diagrams/component-main-service.svg)

*Source: [`../diagrams/component-main-service.puml`](../diagrams/component-main-service.puml).*

### `vinary.main.core`
- **Responsibility.** Electron app + window lifecycle; parse the initial file from argv; create the
  sandbox-leaning `BrowserWindow` with the preload.
- **Key vars/fns.** `main` (`^:export`; the entry), `create-window!` (BrowserWindow + `did-finish-load`
  → `service/open!`), `initial-file` (argv → path), `renderer-index`, `preload-path`, `main-window`
  (atom).
- **Depends on.** `electron`, `path`, `clojure.string`, `vinary.main.service`.

### `vinary.main.service`
- **Responsibility.** The IO service: read files, classify them, push content/errors to the renderer,
  watch every open path (chokidar), and compute the git file-tree. The only place that touches the
  filesystem and git.
- **Key vars/fns.** `init!` (register `ipcMain` `vv:open`/`vv:close`), `open!` (send content + tree +
  start a watcher), `close!` (stop a watcher), `send-content!` (read + `vv:content`, or `vv:error`),
  `send-tree!` / `repo-tree` (`git rev-parse` + `git ls-files`), `kind-of` (md/image/text),
  `watchers` (atom path→watcher).
- **Depends on.** `electron` (`ipcMain`), `fs`, `path`, `child_process`, `chokidar`, `clojure.string`.

---

## preload (IPC seam)

### `resources/preload.js`
- **Responsibility.** The Mediator `contextBridge` seam — expose a minimal JSON-only `window.vv` to
  the renderer; all cross-process traffic goes through here.
- **Key API.** `open`, `close`, `requestKeymap` (renderer → main); `onContent`, `onError`, `onTree`,
  `onKeymap` (main → renderer; each returns an unsubscribe fn). See
  [ipc-channels.md](./ipc-channels.md).
- **Depends on.** `electron` (`contextBridge`, `ipcRenderer`) via CommonJS `require`.

---

## RENDERER · re-frame core (`vinary.app.*`)

![Renderer components](../diagrams/component-renderer.svg)

*Source: [`../diagrams/component-renderer.puml`](../diagrams/component-renderer.puml).*

### `vinary.app.db`
- **Responsibility.** The `app-db` default value — ephemeral UI only (documents live in DataScript).
- **Key vars/fns.** `default-db` (`:ds/rev`; `:ui {…active-path, theme, history, find, tree*, input,
  palette…}`).
- **Depends on.** *(none)*.

### `vinary.app.ds`
- **Responsibility.** DataScript — the documents/tabs single-source-of-truth; the `:ds/rev`
  reactivity bridge; the read-helper API.
- **Key vars/fns.** `schema` (`{:doc/path {:db/unique :db.unique/identity}}`), `conn`,
  `install-bridge!`, `snapshot`, `eid-for-path`, `order-for-path`, `next-order`, `open-docs`,
  `doc-attr`, `active-doc`. See [architecture/04-state-schema-reference.md](../architecture/04-state-schema-reference.md).
- **Depends on.** `datascript.core`, `re-frame.core`.

### `vinary.app.events`
- **Responsibility.** The loop's verbs: content ingestion (→ DataScript), tabs, history, theme, tree,
  find, TOC, plus the command-target events the keybinding registry dispatches.
- **Key vars/fns.** `:db/init`, `:ds/changed`, `:content/received`/`:content/rendered`/`:content/error`,
  `:doc/open`, `:tab/activate`/`:tab/close`/`:tab/next`/`:tab/prev`, `:history/back`/`:history/forward`,
  `:theme/set`/`:theme/cycle`, `:tree/received`/`:tree/filter`/`:tree/move`/`:tree/activate`,
  `:find/*`, `:toc/goto`/`:toc/active-heading`, `:sidebar/toggle`, `:nav/focus`/`:nav/scroll`,
  `:doc/open-in-tab`; private helpers `record-nav`, `nav-to`, `plain-html`, `nth-tab`,
  `visible-tree-paths`. See [reference/events-effects-subs.md §1](./events-effects-subs.md#1-events).
- **Depends on.** `re-frame.core`, `clojure.string`, `goog.string`, `vinary.app.{db,ds,fx}`,
  `vinary.input.fx`.

### `vinary.app.subs`
- **Responsibility.** The Observer graph — UI subs over `app-db`; document subs over DataScript
  (gated on `:ds/rev`).
- **Key vars/fns.** `:ds/rev`, `:ui/*` (active-path, theme, tree, tree-filter, find, active-heading,
  sidebar-visible?, tree-selected), `:input/*` (mode, pending, in-input?), `:palette/state`,
  `:history/can-back?`/`:history/can-forward?`, `:tabs`, `:doc/active`, `:doc/toc`. See
  [reference/events-effects-subs.md §3](./events-effects-subs.md#3-subscriptions).
- **Depends on.** `re-frame.core`, `vinary.app.ds`, `vinary.renderer.toc`.

### `vinary.app.fx`
- **Responsibility.** Effects at the edge — the only place IO/async/DataScript-mutation happens.
- **Key vars/fns.** `:ds/transact`, `:markdown/render`, `:theme/apply`, `:find/run`/`:find/cycle`/
  `:find/clear`, `:toc/scroll`, `:vv/open`/`:vv/close`. See
  [reference/events-effects-subs.md §2.1](./events-effects-subs.md#21-vinaryappfx).
- **Depends on.** `re-frame.core`, `datascript.core`, `vinary.app.ds`, `vinary.renderer.markdown`,
  `vinary.renderer.find`.

### `vinary.app.commands`
- **Responsibility.** The command registry — reified `{:id :title :category :dispatch|:handler|:prompt
  :when}` specs that keybindings and the palette dispatch through (Command pattern).
- **Key vars/fns.** `registry`, `predicates`, `run` (resolve + dispatch a command vs ctx), `allowed?`,
  `all-visible` (populate the palette). See
  [reference/events-effects-subs.md §4.1](./events-effects-subs.md#41-the-command-registry-vinaryappcommands).
- **Depends on.** `re-frame.core`.

---

## RENDERER · input / keybindings (`vinary.input.*`)

### `vinary.input.resolver`
- **Responsibility.** The keymap resolver (Interpreter): on each `keydown`, normalize the chord, build
  a resolution context from `app-db`, walk the active keymap's trie for the current mode, and act
  (dispatch / extend prefix / consume / pass / retry). Holds the authoritative pending-sequence and
  chord timer in **local atoms** (synchronous).
- **Key vars/fns.** `install!` (global `keydown` listener; `installed` guard), `step` (pure
  resolution), `handle`, `build-ctx`, `reset-seq!`; private `push!`, `arm!`, `mirror!`.
- **Depends on.** `re-frame.core`, `re-frame.db`, `vinary.app.ds`, `vinary.app.commands`,
  `vinary.input.keys`, `vinary.input.keymap`.

### `vinary.input.keymap`
- **Responsibility.** Keymap registry: the bundled presets (embedded at compile time), key
  normalization, and the preset⊕user deep-merge (Strategy: the active keymap + mode select bindings).
- **Key vars/fns.** `bundled` (from the `presets/bundled` macro), `install!` (a named preset),
  `install-user!` (merge a user delta, honour `:unbind`), `modes`, `initial-mode`, `timeout-ms`,
  `keymap-name`; `active` (atom).
- **Depends on.** `vinary.input.presets` (macros), `clojure.walk`, `vinary.input.keys`.

### `vinary.input.keys`
- **Responsibility.** Key normalization: a JS `KeyboardEvent` → a canonical chord token, folding
  modifiers cross-platform (Ctrl/⌘ → `C-`, Alt → `M-`, Shift → `S-` only for named keys).
- **Key vars/fns.** `event->chord`, `bare-printable?`, `normalize-token` (SPC→space, RET→enter, …),
  `mac?`; tables `named`, `modifier-keys`.
- **Depends on.** `clojure.string`.

### `vinary.input.events`
- **Responsibility.** re-frame events for input: modal state, the pending sequence, the command
  palette, and receiving the user keymap config (State pattern; all ephemeral → `app-db`).
- **Key vars/fns.** `:input/set-mode`/`:input/set-sequence`/`:input/set-in-input`/`:input/set-timeout-id`,
  `:input/push-sequence`/`:input/reset-sequence`/`:input/timeout`, `:input/escape`,
  `:keymap/config-received`, `:palette/open`/`:palette/close`/`:palette/set-query`/`:palette/move`. See
  [reference/events-effects-subs.md §1.10–1.12](./events-effects-subs.md#110-modal-input-state-input).
- **Depends on.** `re-frame.core`, `vinary.input.keymap`, `vinary.input.fx`.

### `vinary.input.fx`
- **Responsibility.** Edge effects for keybinding-driven navigation: scroll the content viewport, move
  DOM focus, and the chord-timeout timers.
- **Key vars/fns.** `:input/arm-timeout`/`:input/cancel-timeout`, `:dom/scroll` (page/half/top/bottom/
  delta), `:dom/focus` (`:tree`/`:content`/`:toggle`). See
  [reference/events-effects-subs.md §2.2](./events-effects-subs.md#22-vinaryinputfx-input).
- **Depends on.** `re-frame.core`.

### `vinary.input.presets`
- **Responsibility.** Compile-time embedding of the bundled preset keymaps. The renderer build stubs
  `fs`, so the EDN under `resources/keymaps/*.edn` is read at **compile** time (classpath) and inlined.
- **Key vars/fns.** the `bundled` **macro** (`:default`/`:vim`/`:emacs` → EDN data).
- **Depends on.** `clojure.edn`, `clojure.java.io`. *(A `.clj` macro namespace, not `.cljs`.)*
- **Data.** `resources/keymaps/{default,vim,emacs}.edn` — the authored keymaps.

---

## RENDERER · services (`vinary.renderer.*`)

### `vinary.renderer.core`
- **Responsibility.** The renderer entry: boot the re-frame loop, install the DataScript bridge, wire
  the IPC seam, install the keymap resolver, mount the reagent UI.
- **Key vars/fns.** `init` (`^:export`), `reload` (`^:export`; hot-reload re-mount), `bridge!` (wire
  `window.vv.on*`), `keybindings!` (`resolver/install!`), `mount!`, `root` (atom). See
  [architecture/06-renderer-runtime.md](../architecture/06-renderer-runtime.md).
- **Depends on.** `reagent.dom.client`, `re-frame.core`, `re-frame.db`, `vinary.app.{ds,events,subs,
  commands}`, `vinary.input.{events,resolver}`, `vinary.ui.views`.

### `vinary.renderer.markdown`
- **Responsibility.** Markdown → HTML via the unified/remark/rehype pipeline (GFM + slugs +
  highlight). Pure transform; returns a `Promise<string>`; runs in the renderer.
- **Key vars/fns.** `render` (the six-stage pipeline). See
  [architecture/05-data-flows.md (Markdown render)](../architecture/05-data-flows.md#markdown-render-shared-sub-flow).
- **Depends on.** `unified`, `remark-parse`, `remark-gfm`, `remark-rehype`, `rehype-slug`,
  `rehype-highlight`, `rehype-stringify`.

### `vinary.renderer.find`
- **Responsibility.** In-page find via the **CSS Custom Highlight API** (paints `Range`s without
  mutating the DOM).
- **Key vars/fns.** `search!` (collect ranges, paint, focus first, return count), `cycle!` (move the
  focused match, wrapping), `clear!`; private `collect-ranges`, `paint!`, `supported?`, `scroll-to!`;
  `state` (atom). See [theory/06-find-css-custom-highlight.md](../theory/06-find-css-custom-highlight.md).
- **Depends on.** `clojure.string`.

### `vinary.renderer.toc`
- **Responsibility.** Table of contents: extract headings from rendered HTML (rehype-slug ids) and a
  rAF-throttled scroll-spy.
- **Key vars/fns.** `extract` (HTML → `[{:level :text :id}]` via `DOMParser`), `spy!` (mark the
  heading at the viewport top); `spy-pending` (atom).
- **Depends on.** `re-frame.core`.

---

## RENDERER · views (`vinary.ui.*`)

### `vinary.ui.views`
- **Responsibility.** The app shell and its panels; the imperative `innerHTML` content body; the
  content-view Strategy (watermark/error/image/html/loading).
- **Key vars/fns.** `root` (shell layout), `content-view`, `markdown-body` (form-3 `innerHTML` sink),
  `watermark`, `toolbar` (history buttons + theme `<select>`), `find-bar`, `toc-panel`; private
  `set-inner!`, `themes`. See [architecture/06-renderer-runtime.md §3](../architecture/06-renderer-runtime.md#3-the-imperative-innerhtml-body).
- **Depends on.** `reagent.core`, `re-frame.core`, `vinary.ui.{tabs,tree}`, `vinary.renderer.toc`.

### `vinary.ui.tabs`
- **Responsibility.** The multi-tab strip (from `:tabs`); click activates, `×` closes.
- **Key vars/fns.** `tab-strip`; private `basename`.
- **Depends on.** `re-frame.core`, `clojure.string`.

### `vinary.ui.tree`
- **Responsibility.** The git file-tree sidebar — fold flat repo paths into a nested tree, render
  collapsible `<details>`, filter, open a file on click.
- **Key vars/fns.** `file-tree`; private `build-tree`, `nodes->hiccup`.
- **Depends on.** `re-frame.core`, `clojure.string`.

---

## Dependency direction (at a glance)

```text
ui.{views,tabs,tree} ─▶ app.subs ─▶ app.ds (conn)         input.resolver ─▶ app.commands ─▶ (rf/dispatch) ─▶ app.events
        │                  │            ▲                       │  │
        └▶ app.events ─▶ app.fx ─▶ app.ds / renderer.{markdown,find}   └▶ input.keymap ─▶ input.presets (macro) ─▶ keymaps/*.edn
                                                                          input.keys (chord normalization)
renderer.core ─▶ (requires) app.{events,subs,commands}, input.{events,resolver}, ui.views
main.core ─▶ main.service ─▶ {fs, chokidar, git}        preload.js ⟷ (IPC) ⟷ app.fx (:vv/*) + renderer.core/bridge!
```

No renderer namespace imports a `main.*` namespace (and vice versa) — the **only** link between the
two processes is the preload IPC seam.

---

## See also

- [architecture/01-overview.md §5](../architecture/01-overview.md#5-concern--process--namespace-map) — the concern→namespace map.
- [architecture/06-renderer-runtime.md](../architecture/06-renderer-runtime.md) — the renderer boot + graph.
- [reference/events-effects-subs.md](./events-effects-subs.md) — the registrations these namespaces define.
