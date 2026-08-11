# Reference · Events, Effects & Subscriptions

> **What this is.** Three exhaustive lookup tables for the re-frame loop — every **event**
> (`reg-event-db`/`reg-event-fx`), every **effect** (`reg-fx`), and every **subscription**
> (`reg-sub`) registered in the renderer — plus the **command registry** that keybindings and the
> command palette dispatch through. Narrative traces are in
> [architecture/05-data-flows.md](../architecture/05-data-flows.md); the IPC side is in
> [ipc-channels.md](./ipc-channels.md).
>
> Sources: `vinary.app.events`, `vinary.app.fx`, `vinary.app.subs`, `vinary.app.commands`,
> `vinary.input.events`, `vinary.input.fx`. Items from the **input / command layer** are tagged
> **[input]**.

---

## 1. Events

`reg-event-db` handlers return a new `app-db`; `reg-event-fx` handlers return a map of `:db` and/or
`:fx`. "Reads" = state consulted; "Writes" = `app-db` paths set; "Effects" = the `:fx` emitted.

### 1.1 Core / lifecycle

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:db/init` | db | — | — | replaces app-db with `default-db` | — |
| `:ds/changed` | db | — | `:ds/rev` | `:ds/rev` ← inc | — |

### 1.2 Content (from main, on every file change)

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:content/received` | fx | `{:path :kind (:text) (:html) :stamp}` | DataScript snapshot (`eid-for-path`, `doc-attr`), current tabs | first content may create the first tab in `app-db` | `[:ds/transact tx]`; markdown → `[:markdown/render …]`; after tab changes → `[:vv/sync-retained-files paths]` plus cache eviction txs |
| `:content/rendered` | fx | `path stamp {:html :toc :assets}` | DataScript snapshot (`eid-for-path`, `doc-attr :doc/stamp`) | — | when stamp still matches: add `:doc/html`, `:doc/toc`, `:doc/assets`; then `[:vv/watch-assets {:doc-path path :paths assets}]` |
| `:content/error` | fx | `{:path :message :stamp}` | DataScript snapshot, current tabs | first path error may create the first tab in `app-db` | when `path`: transact `:doc/error`; then sync retained files and evict unretained cache |

### 1.3 Documents & tabs

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:doc/open` | fx | `uri/path` | current tab, content scroll | active tab/history | focus existing tab or navigate active tab; local files → `[:vv/open path]`, `[:scroll/restore n]`, retained sync |
| `:doc/open-new` | fx | `uri/path` | current tab, content scroll | tabs/history | focus existing tab or open a new tab; local files → `[:vv/open path]`, `[:scroll/restore n]`, retained sync |
| `:doc/open-in-tab` **[input]** | fx | `path new?` | — | — | dispatches `:doc/open-new` when `new?`, else `:doc/open` |
| `:tab/navigate` | fx | `uri` | active tab, content scroll | active tab/history | local files → `[:vv/open path]`; also scroll restore and retained sync |
| `:tab/open` | fx | `uri` | active tab, content scroll | tabs/history | add a new tab, load local files, restore top scroll, sync retained |
| `:tab/activate` | fx | `id` | content scroll | `:ui :active-tab`, saved leaving scroll | restore target scroll for local files and sync retained |
| `:tab/close` | fx | `id` | tab list/history | tabs, active tab | sync retained files and evict no-longer-retained cached docs |
| `:tab/next` **[input]** | db | — | `app-db` tabs | `:ui :active-tab` | activate next tab id |
| `:tab/prev` **[input]** | db | — | `app-db` tabs | `:ui :active-tab` | activate previous tab id |

### 1.4 Navigation history

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:history/back` | fx | — | active tab history, content scroll | active tab history idx and saved leaving scroll | load target URI, restore saved scroll, sync retained files |
| `:history/forward` | fx | — | active tab history, content scroll | active tab history idx and saved leaving scroll | load target URI, restore saved scroll, sync retained files |
| `:nav/parent` **[input]** | fx | — | active tab uri, content scroll | active tab/history (→ parent dir via `uri/dirname`), `[:ui :dir-selected]` ← came-from path | navigate active tab to parent, scroll restore, sync retained; **no-op** for `http(s)` / at filesystem root |
| `:nav/open-target` **[input]** | fx | — | active path, DataScript `active-doc`, `[:ui :dir-selected]`, `[:ui :recent :trail]` | — | when `:doc/kind` = `"directory"`: `[:dispatch [:doc/open <effective-selected>]]`; else inert |

### 1.5 Theme

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:theme/set` | fx | `theme` | current settings | `:ui :theme` ← theme, `:ui :settings :theme` ← theme | `[:theme/apply theme]`, `[:vv/save-settings edn]` |
| `:theme/cycle` **[input]** | fx | — | `:ui :theme` | — | `[:dispatch [:theme/set <next-in-cycle>]]` (cycles `["spacemacs-dark" "spacemacs-light"]`) |

### 1.6 Git file-tree

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:tree/received` | fx | `{:root :files :synthetic? :scope? :extras?}` | `:ui :projects`, tree-open scopes | full project merge or exact scoped-subtree replacement; prune stale scopes; an in-place full refresh **unions** `:extras` (`projects/merge-extras` — an extras-less re-send never wipes attached diffs, ADR-0038); scoped payloads never touch extras | sync visible roots, dispatch declarative active reveal |
| `:tree/prune-extras` | db | `retained` (the retained document-identity set) | `:ui :projects` | drop `:transient?` extras whose `:path` is not in the retained set (`projects/prune-extras`; emptied `:extras` keys removed — persistent extras stay) | — (dispatched conditionally from `retention-fx`, only when some project carries extras — the same retention edge at which main unlinks a stdin spill) |
| `:tree/expand` → `:tree/expand-ready` / `-failed` | fx | `root directory` / scope + reply | projects, open/pending scopes | mark pending; on success commit subtree + open scope atomically; failure leaves closed | `:vv/refresh-tree` |
| `:tree/collapse` | db | `root directory` | tree-open scopes | remove the persistent open scope (effective descendants close too) | Files lifecycle later syncs the reduced watcher set |
| `:tree/refresh` / `:tree/refresh-all` | fx | `{root path}` / — | visible projects | apply one scoped/full reply or every visible-root reply | `:vv/refresh-tree` / `:vv/refresh-all-trees` |
| `:tree/sync-expanded` | fx | effective scope set | — | — | `:vv/sync-tree-expanded` |
| `:tree/reveal-active` | fx | — | active path, projects | add its known ancestor scopes declaratively | post-render scroll-only `:tree/reveal-active` effect |
| `:tree/restore-ready` | fx | per-root results | projects, remembered root scopes | apply successful listings, close failed roots, clear restoring gate | sync roots and reveal active row |
| `:tree/filter` | fx | `q` | — | — | `[:async/debounce {:key :tree/filter :ms 90 :dispatch [:tree/filter-commit q]}]` |
| `:tree/filter-commit` | db | `q` | — | `:ui :tree-filter` ← q | — |
| `:tree/move` **[input]** | db | `dir` | `:ui :projects`, `:ui :tree-filter`, `:ui :tree-selected` | `:ui :tree-selected` ← next visible path (wrapping over the **filtered** list) | — |
| `:tree/activate` **[input]** | fx | — | `:ui :tree-selected` | — | when selected: dispatch `[:doc/open sel]` |

### 1.7 In-page find

Every `:find/*` request carries a **generation** (`:ui :find :gen`, bumped by `bump-gen`). Searching is
asynchronous — it first materializes a PDF's text layers or drains a streamed document — so an earlier,
shorter query's reply can land last; `:find/result` drops any reply whose generation is stale. The same
counter collapses the input debounce. See [ADR-0032](../design-decisions/0032-scroll-ownership-and-derived-input-focus.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:find/toggle` | fx | — | `:ui :find :visible?`, `:ui :find :query` | `:ui :find :visible?` ← not; `:gen`++ | opening with a remembered query: `[:find/search {:q :gen}]`; closing: `[:find/clear]` |
| `:find/set-query` | fx | `q` | `:ui :find :gen` | `:ui :find :query` ← q; `:gen`++ | `[:async/debounce {:key :find/search :ms 40 :dispatch [:find/run gen]}]` |
| `:find/run` | fx | `gen` | `:ui :find :gen`, `:query` | — | `[:find/search {:q :gen}]` — **only if `gen` is still current**. The generation no longer doubles as the debounce (ADR-0033): `:async/debounce` cancels a superseded request outright, and the generation now guards only against an already-started search replying late. |
| `:find/result` | db | `{:gen :count :idx}` | `:ui :find :gen` | `:ui :find :count` ← count; `:ui :find :idx` ← idx — **only if `gen` is current** | — |
| `:find/cycle` | fx | `dir` | `:ui :find :gen` | — | `[:find/cycle {:dir :gen}]` |
| `:find/reset` | fx | — | — | `:ui :find :count` ← 0; `:idx` ← 0; `:gen`++ (**query kept**) | `[:find/clear]` |
| `:find/close` | fx | — | — | `:ui :find :visible?` ← false; `:gen`++ | `[:find/clear]` |

### 1.8 Table of contents

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:toc/goto` | fx | `id` | active tab (diff collapsed set) | tab `:diff-collapsed` (auto-expand) | `[:toc/scroll id]`; a Contents click on a COLLAPSED diff file first auto-expands it — `[:diff/apply-collapsed …]` is ordered before the scroll so the offset measures the expanded layout (ADR-0037) |
| `:toc/active-heading` | db | `id` | — | `:ui :active-heading` ← id | — |

### 1.9 Sidebar & focus / scroll commands **[input]**

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:sidebar/toggle` | db | — | `:ui :sidebar-visible?` | `:ui :sidebar-visible?` ← not | — |
| `:nav/focus` | fx | `target` (`:tree`/`:content`/`:toggle`) | — | — | `[:dom/focus target]` |
| `:nav/scroll` | fx | `opts` (`{:dy …}` / `{:dx …}` / `{:to …}`) | — | — | `[:dom/scroll opts]` |

### 1.10 Modal input state **[input]**

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:input/set-mode` | db | `mode` | — | `:ui :input :mode` ← mode | — |
| `:input/set-sequence` | db | `s` | — | `:ui :input :sequence` ← `(vec s)` (mode-line mirror of the resolver's pending chord) | — |
| `:input/set-in-input` | db | `v` | — | `:ui :input :in-input?` ← `(boolean v)` | — |
| `:input/set-timeout-id` | db | `id` | — | `:ui :input :timeout-id` ← id | — |
| `:input/push-sequence` | fx | `token timeout-ms` | `:ui :input :timeout-id` | `:ui :input :sequence` ← conj token | `[:input/cancel-timeout id]`, `[:input/arm-timeout ms]` |
| `:input/reset-sequence` | fx | — | `:ui :input :timeout-id` | `:ui :input :sequence` ← `[]`, `:count` ← nil | `[:input/cancel-timeout id]` |
| `:input/timeout` | db | — | — | `:ui :input :sequence` ← `[]`, `:count` ← nil | — |
| `:input/escape` | fx | — | `:ui :palette :open?`, `:ui :find :visible?`, `:ui :input :mode` | mode→`:normal` or clear sequence/count (see precedence) | palette open→`[:dispatch [:palette/close]]`; else find→`[:dispatch [:find/close]]` |

> **`:input/escape` precedence** (first match wins): close palette → close find → leave non-normal
> mode (→ `:normal`) → clear the pending sequence + count. This is the "universal cancel".

### 1.11 Keymap config **[input]**

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:keymap/config-received` | fx | EDN text or map | — | `:ui :keymaps` ← normalized registry envelope | `[:keymap/install-active]` |

> Triggered by the renderer's `onKeymap` IPC handler and by the `window.__vvkeymap "vim"` dev hook.
> See [§4](#4-the-input--command-layer) and
> [ipc-channels.md `vv:keymap`](./ipc-channels.md#2-main--renderer).

### 1.12 Command palette **[input]**

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:palette/open` | db | `{:keys [source prefix]}` | — | `:ui :palette` ← `{:open? true :source (or source :command) :prefix (or prefix "") :query "" :selected 0}` | — |
| `:palette/close` | db | — | — | `:ui :palette :open?` ← false | — |
| `:palette/set-query` | fx | `q` | — | — | `[:async/debounce {:key :palette/query :ms 90 :dispatch [:palette/set-query-commit q]}]` |
| `:palette/set-query-commit` | db | `q` | — | `:ui :palette :query` ← q, `:selected` ← 0 | — |
| `:palette/move` | db | `dir n` | `:ui :palette :selected` | `:ui :palette :selected` ← `(mod (+ sel dir) (max 1 n))` | — |

> **Palette UI.** The palette **events + state + `:palette/state` sub** are backed by the rendered
> view component `vinary.ui.palette/command-palette` (mounted in `vinary.ui.views/root`). Commands that
> `:prompt` (e.g. `:file/open`, `:theme/pick`) dispatch `[:palette/open …]`, which opens the overlay
> with the `:command`, `:file`, or `:theme` source; typing fuzzy-filters, `Enter` runs the selection.

### 1.13 In-pane directory browser

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:dir/select` | db | `path` | — | `:ui :dir-selected` ← path (the highlighted `Enter` / `Alt+Down` target) | — |

> The directory browser is a **detailed list** (name · size · modified) — there is no grid layout, no
> layout toggle, and no `:dir-view-mode` state. Its only key handler is `Enter` → `[:nav/open-target]`;
> bare arrow keys are *not* consumed — they fall through to the global smooth pane-scroll (see
> `:dom/scroll`). A click dispatches `[:dir/select path]` to highlight, then opens **OS-dependently** — a
> single click on Linux, a double click on Windows/macOS (`vinary.ui.platform/single-click-open?`) — and
> `Ctrl+click` opens in a new tab. See
> [features/16-directory-browser.md](../features/16-directory-browser.md).

### 1.14 Recent navigation memory (`recent.edn`)

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:recent/received` | db | EDN text | — | `:ui :recent` ← parsed `{:trail {…} :recent-files [...]}` merged over `{:trail {} :recent-files []}` | — |
| `:recent/clear` | fx | — | `:ui :recent` | `:ui :recent :recent-files` ← `[]` (the dir→child `:trail` is kept) | `[:vv/save-recent edn]` |

> The trail + MRU are **also** updated as a side effect of `:content/received`: a pure `record-recent`
> helper records a `dir → child` entry for every ancestor of the active path (and, for a **file**,
> unshifts it onto `:recent-files`, capped at 10), then `:content/received` emits `[:vv/save-recent …]`.
> This only runs for the **active** tab's path (a real forward navigation), never a background
> live-refresh. See [features/17-breadcrumb-and-up-down-navigation.md](../features/17-breadcrumb-and-up-down-navigation.md).

### 1.15 Tab drag-drop indicator & breadcrumb modifier

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:tab/drop-set` | db | `over` (tab id), `after?` | — | `:ui :tab-drop` ← `{:over over :after? (boolean after?)}` | — |
| `:tab/drop-clear` | db | — | — | `:ui :tab-drop` ← nil | — |
| `:ui/set-ctrl-held` | db | `held?` | — | `:ui :ctrl-held?` ← `(boolean held?)` (drives the Ctrl-hover breadcrumb) | — |

> `:ui/set-ctrl-held` is dispatched by capture-phase `keydown`/`keyup` listeners in
> `vinary.renderer.core` (each reads its own `ctrlKey`, so a missed `keyup` self-heals). `:tab/drop-set`/
> `:tab/drop-clear` drive the CSS insertion line (`.vv-tab-drop-before` / `.vv-tab-drop-after`) shown
> while dragging a tab.

> **The 0.3 families below** (§1.16–§1.22) use a `Event | Payload | Purpose` table; the
> `reg-event-db` / `reg-event-fx` split for each is read directly from `vinary.app.events`.

### 1.16 View representation — Document↔PDF, Preview↔Source, Unified↔Split

| Event | Payload | Purpose |
| --- | --- | --- |
| `:tab/set-representation` | `:document` \| `:pdf` | Set the active tab's `:representation` — show the rendered document, or its collocated same-stem sibling PDF (no new tab). |
| `:tab/toggle-representation` | — | Flip `:document` ↔ `:pdf` (toolbar segmented control / command palette). |
| `:tab/open-representation-source` | — | From a PDF, navigate to its collocated **source** document, forcing `:representation :document`. |
| `:tab/toggle-source` | — | Flip the tab's `:view-source?` — Preview ↔ Source (`Ctrl+Shift+D` / `Ctrl+Shift+S`). |
| `:tab/set-diff-view` | `:unified` \| `:split` | Set a diff tab's `:diff-view` (picked from the Preview combo's caret layout rows — ADR-0037). |
| `:diff/toggle-file` | `file-id` | Flip one diff file's collapsed state (its `vv-diff-file-N` summary id) in the tab's `:diff-collapsed` set — the banner-click / hint-activation event; self-gated on the shown diff preview; fires `[:diff/apply-collapsed …]` (ADR-0037). |
| `:diff/collapse-all` / `:diff/expand-all` | — | Collapse (ids from the diff's `:doc/toc` outline — never the DOM) / expand every file; self-gated; palette **Collapse/Expand all diff files**, vim `z M` / `z R`. |
| `:diff/toggle-collapse-all` | — | The View-menu item's single static event: dispatches expand-all iff everything is collapsed (the same predicate flips the item's dynamic label). |
| `:tab/toggle-diff-view` | — | Flip Unified ↔ Split; entering Split requests the on-disk pre/post sources over the `vv:load-diff-sources` IPC **invoke** (there is no `reg-fx` for it), whose result returns as `[:diff/split-ready …]`. |
| `:diff/split-ready` | `{:path :html}` | The enriched side-by-side HTML arrived → stored as `:doc/diff-split-html`. |
| `:pdf/sibling-ready` | `{:path :bytes}` | The sibling PDF's bytes arrived (over `vv:load-pdf-bytes`) → cached for the pdf view. |
| `:tab/reload` | — | Re-open the active tab's URI (re-read + re-render). |
| `:doc/set-file-type` | `{:kind :language?}` | Settings ▸ File Type: re-interpret the SHOWN document (the active facet's file) under an explicit type. Fires `[:vv/set-file-type {:path :kind :language}]` — main registers the override in its doc-overrides registry and re-sends through the full open pipeline (the `:tab/reload` shape), so the override survives watcher refreshes. `language` is a grammar-catalog id for a "source with THIS grammar" pick. No optimistic DataScript write (`:content/received` stays the single ingestion point). |

See [ADR-0025](../design-decisions/0025-latex-rendering-via-unified-latex.md) / [ADR-0026](../design-decisions/0026-diff-rendering-side-by-side-and-repo-filetypes.md) / [ADR-0036](../design-decisions/0036-stdin-documents-and-explicit-file-types.md).

### 1.17 Remote files over SSH

| Event | Payload | Purpose |
| --- | --- | --- |
| `:ssh/prompt` | `{:promptId :kind :host :user :connKey …}` | A **non-secret** auth-prompt request from main → open the SSH prompt modal. |
| `:ssh/prompt-reply` | `{:promptId :secret}` | The user's answer; emits `[:ssh/reply …]`, which sends `vv:ssh-prompt-reply`. The secret is held in the modal's local state, **never** in app-db. |
| `:ssh/status` | `{:connKey :host :state}` | Connection status (connecting / ready / closed). |
| `:ssh/error` | `{:connKey :host :kind :message}` | A connection/transport error (host-key rejected, SFTP error, …). |
| `:ssh/dismiss-error` | — | Dismiss the surfaced SSH error banner. |
| `:connections/received` | EDN text | Persisted, **non-secret** SSH connection metadata (`connections.edn`). |

See [ADR-0027](../design-decisions/0027-remote-files-over-ssh.md) and [features/29](../features/29-remote-files-over-ssh.md).

### 1.18 Native password-manager bridge

| Event | Payload | Purpose |
| --- | --- | --- |
| `:passwords/open` / `:passwords/close` | — | Open / close the passwords dialog. (`:passwords/open` is dispatched from the key-icon; `:passwords/close` closes it.) |
| `:passwords/fill` | item metadata | Ask main to reveal the selected login and inject it **into the web view** — the secret never enters app-db. |

The state/items/save-prompt/result pushes from main land as `[:passwords/*-received …]`; see
[ipc-channels §2.5](./ipc-channels.md) and [features/23](../features/23-password-manager-bridge.md).

### 1.19 Extensions & ad-blocking

| Event | Payload | Purpose |
| --- | --- | --- |
| `:extensions/open` / `:extensions/close` | — | Open / close the Settings ▸ Extensions dialog. |
| `:extensions/install` / `:extensions/remove` | Web-Store id or URL / id | Install / uninstall a scoped extension. |
| `:extensions/check-updates` | — | Trigger a Web-Store update check. |
| `:extensions/action-clicked` / `:extensions/popup-close` | `{:id :popup :bounds}` / — | Open / close a browser-action popup. |
| `:ext/install-result` / `:ext/update-result` | result object | Install / update outcome pushed from main. |
| `:adblock/refresh` | — | Refresh the ad-block filter lists (status returns on `vv:adblock-status`). |

### 1.20 Menus, dialogs, context menu & access keys

| Event | Payload | Purpose |
| --- | --- | --- |
| `:menu/open` / `:menu/close` / `:menu/toggle` | menu id | Open / close / toggle a top-level DOM menu. |
| `:menu/focus` / `:menu/submenu` / `:menu/submenu-focus` | id | Keyboard focus + submenu traversal within the menu bar. |
| `:context-menu/show` / `:context-menu/close` | `{:x :y :items}` / — | The themed right-click context menu (Copy, Go to source/preview, …). |
| `:settings/open` / `:settings/close` | — | Open / close the Preferences dialog. |
| `:about/open` / `:about/close` | — | Open / close the About dialog. |
| `:access-keys/set` | bool | Show/hide the Alt-held access-key underlines in the menu bar. |
| `:app-info/received` | app metadata map | App metadata for the About dialog. |

### 1.21 Link hints, URI-bar completion & sidebar

| Event | Payload | Purpose |
| --- | --- | --- |
| `:hint/start` | — | Begin Vimium-style link-hint mode (`f`); emits `[:hints/collect]`. |
| `:hints/activate` | typed label | Follow the hinted link whose label was typed. |
| `:hints/backspace` / `:hints/cancel` | — | Edit / abort the typed hint label. |
| `:uri-complete/set` | completion data | Address-bar path/history completion state (ghost + dropdown). |
| `:uri-complete/clear` / `:uri-complete/clear-error` | — | Dismiss the completion popup / clear its error flag. |
| `:sidebar/show` | bool | Show/hide the sidebar. |
| `:sidebar/tab` | `:files` \| `:contents` | Select the sidebar panel. |
| `:sidebar/reveal` | path | Reveal (and select) a path in the Files tree. |

### 1.22 Web view, shell & app

| Event | Payload | Purpose |
| --- | --- | --- |
| `:web/toc` | heading vector | The in-app web view's heading outline (feeds the same Contents panel model). |
| `:web/active-heading` | heading id \| nil | The web view's scroll-spy active heading. |
| `:ui/hover-link` | href \| nil | The hovered link, shown in the status strip. |
| `:shell/open-path` / `:shell/open-external` | path / URL | Ask the OS to reveal a local path / open an external URL. |
| `:clipboard/copy` | text | Copy text to the OS clipboard. |
| `:view/devtools` | — | Toggle renderer devtools. |
| `:app/quit` | — | Quit the application. |

### 1.23 Commits surfaces (ADR-0039/0040)

Per-repo state lives at `[:ui :commits :repos <root>]` (the
[state-schema reference](../architecture/04-state-schema-reference.md) documents the shape). The
lane fold (`:graph`) is stored **incrementally** on every received page, so the sidebar rail and
the Commit Graph document read one source of truth; stale async replies are dropped by
a per-repo generation (`:gen`), the `:find` pattern. Hash-keyed UI state (selection, expanded
rows, rendered bodies) survives a page-0 refresh via `commits/keep-surviving`. See
[ADR-0039](../design-decisions/0039-commits-sidebar-and-git-data-layer.md). Since the Commit
Graph document ([ADR-0040](../design-decisions/0040-commit-graph-blame-and-history.md)), the
window's `.git`-watch set is **derived**: each surface owns a slot under
`[:ui :commits :watch-owners {:panel :graph}]` and `:commits/sync-watch` sends the slots' union —
`vv:git-watch` *replaces* the window's set, so neither surface's unmount may release the other's
watcher.

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:commits/sync-watch` | fx | — | `[:ui :commits :watch-owners]` | — | `[:vv/git-watch <the distinct non-nil slot values>]` — the ONE place the window's watched-repo set is computed (ADR-0040's union; dispatched by every slot write below and by the `:git-graph/shown`/`hidden` pair) |
| `:commits/shown` | fx | `root` | — | `[:ui :commits :last-root]` ← root; `[:ui :commits :watch-owners :panel]` ← root | `[:dispatch [:commits/sync-watch]]`, `[:dispatch [:commits/ensure root]]` — the panel's mount hook (nil root → no-op) |
| `:commits/hidden` | fx | — | — | `[:ui :commits :watch-owners :panel]` ← nil | `[:dispatch [:commits/sync-watch]]` — panel unmount releases only the **panel slot**; main closes the repo's watcher when the union no longer names it |
| `:commits/set-root` | fx | `root` | — | `[:ui :commits :root]` ← root (the explicit **pin** — a non-nil pin IS "stop following the active doc"); `[:ui :commits :watch-owners :panel]` ← root | `[:dispatch [:commits/sync-watch]]`, `[:dispatch [:commits/ensure root]]` — the header repo switcher |
| `:commits/ensure` | fx | `root` | the repo's `:branches` / `:commits` / `:loading?` | ensure `[:repos root]` exists | **idempotent**: `:vv/git-branches` only when branches are missing; `[:commits/load root {:skip 0}]` only when no commits are loaded and no load is in flight |
| `:commits/load` | fx | `root {:skip}` | the repo's `:ref`, `:gen`, `:mode`, `:history-target` | `:loading?` ← true; `:gen`++ | **mode-aware** (ADR-0040): merges `commits/history-args` into the request — `:file-history` → `{:file … :follow true}`, `:line-history` → `{:lineRange {file start end}}`, `:log` → nothing — then `:vv/git-log {root skip limit 250 …}`; the viewed `:ref` is added **only for the plain branch log** (history follows HEAD) → on-done `[:commits/log-received root gen page0?]` / on-error `[:commits/log-error root]` |
| `:commits/load-more` | fx | `root` | `:loading?`, `:exhausted?`, `:commits` | — | `[:commits/load root {:skip (count commits)}]` — unless already loading or exhausted (the footer button; also the graph's `:git-graph/near-end` target) |
| `:commits/log-received` | db | `root gen page0? {:commits :exhausted :empty :error}` | the repo's `:gen`, `:graph`, `:mode` | stale `gen` → **reply dropped**; error → `:error`; else page 0 replaces / later pages append `:commits`, and the page folds through `graph/assign` into the **stored** `:graph {:rows :state :max-lane}` (R2 — the one fold site); in a **history mode** the fold is stored **empty** (`{:rows [] :state init}` — a non-contiguous listing must not fabricate lanes; both rails degrade to lane-0 dots, ADR-0040); page 0 additionally prunes `:selection`/`:expanded`/`:bodies` to surviving hashes (`commits/keep-surviving`) | — |
| `:commits/log-error` | db | `root msg` | — | `:loading?` ← false; `:error` ← msg (e.g. "git is not available on PATH", "git output too large (>64 MiB)") | — |
| `:commits/branches-received` | db | `root payload` | the repo's `:ref` | error → `:error`; else `:branches` ← `{:head :detached? :branches}`; a viewed `:ref` no longer among the branch names → nil (branch deleted — fall back to HEAD, D8) | — |
| `:commits/set-ref` | fx | `root ref` | — | `:ref` ← ref | `[:commits/load root {:skip 0}]` — the branch combo's pick |
| `:commits/activate` | fx | `root hash` | — | — | `:vv/git-open-diff {root :to hash :parent? true}` — diff vs the FIRST parent; main resolves `hash^`, empty tree for a root commit (R4) |
| `:commits/select` | db | `root hash mode` | the loaded hash order | `:selection` ← `commits/select` (R3: `:single` / `:toggle` (Ctrl) / `:range` (Shift, anchor-to-hash; falls back to `:single` when an end left the window)) | — |
| `:commits/cursor-to` | fx | `root idx {:extend? :move-only?}` | the loaded hash order | `:selection` — `move-only?` (Ctrl) writes `:cursor` only (the selection stands); `extend?` (Shift) applies `commits/select … :range`; else `:single` — all by the hash at `idx` (out-of-window idx → no-op) | `[:git-graph/reveal-row idx]` — the graph's keyboard model funnels here (ADR-0040) so both surfaces share one cursor |
| `:commits/clear-selection` | db | `root` | — | `:selection` ← `{:cursor nil :anchor nil :selected #{}}` | — |
| `:commits/diff-selected` | fx | `root` | `:selection`, the loaded hash order | — | when **exactly two** commits are selected: `:vv/git-open-diff {root :from older :to newer}` (`commits/diff-pair` orders by log index — the "Diff selected" pill) |
| `:commits/toggle-expand` | fx | `root hash` | `:expanded`, `:bodies`, the commit's `:body` | toggle hash in `:expanded` | first expansion of a non-blank, not-yet-cached body: `[:commits/render-body {root hash body}]` — lazy GFM (D3) |
| `:commits/body-rendered` | db | `root hash html` | — | `[:bodies hash]` ← sanitized html string, or `false` on render failure (the view shows a plain `<pre>`) | — |
| `:commits/range-input` | db | `root s` | — | `:range-input` ← s; `:range-error` ← nil | — |
| `:commits/range-submit` | fx | `root` | `:range-input` | unparsable non-blank input → `:range-error` "unrecognized range" | parsed (`commits/parse-range`: `A..B` / `A...B` / single-rev-vs-parent): `:vv/git-open-diff` with `{:from? :to :dots? :parent?}` + `:root` — main rev-parse-verifies every side |
| `:commits/open-diff-error` | db | `root msg` | — | `:range-error` ← msg (e.g. main's "unknown revision: …") — surfaced inline under the range input | — |
| `:commits/git-changed` | fx | `{:root}` | is `[:repos root]` loaded? | — | loaded repos only (D8's **conservative refresh**): `:vv/git-branches` + `[:dispatch [:commits/load root {:skip 0}]]` — the `:gen` bump strands in-flight page replies; `keep-surviving` preserves selection |

### 1.24 History modes (ADR-0040)

`--follow` file history and `-L` line-range history are **modes of the shared Commits store**
(`:mode` / `:history-target` per repo), so the sidebar panel and the Commit Graph both show a
history the moment it is requested. Entering a history **pins the panel to the file's repo and
reveals the Commits tab** — the results must be looked at. See
[ADR-0040](../design-decisions/0040-commit-graph-blame-and-history.md) and
[feature 34](../features/34-git-blame-and-file-history.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:git/file-history` | fx | `{:file?}` (default: the active path) | `[:ui :projects]` (root derivation) | `enter-history`: `[:ui :commits :root]` ← the file's repo (pin); the repo's `:mode` ← `:file-history`, `:history-target` ← `{:file}`; commits/fold/selection/expanded/bodies reset | `[:commits/load root {:skip 0}]` (mode-aware → `--follow`), `[:sidebar/show]`, `[:sidebar/tab :commits]` — no derivable git root → silent no-op (the palette pattern) |
| `:git/line-history` | fx | `{:file :start :end}` | `[:ui :projects]` | as `:git/file-history` with `:mode` ← `:line-history`, `:history-target` ← `{:file :start :end}` (swapped bounds normalize) | same three effects; the load carries `{:lineRange …}` — main runs the single-shot `-L` walk (`-n 500`, reply `exhausted`), repo-relative with an outside-repo guard |
| `:git/line-history-from-selection` | fx | — | the active path | — | `[:git/selection-line-history file]` — the palette/menu entry with no explicit range; the mounted source view's selection (cursor line twice when empty) names the lines, read through the fx (the DOM's business) |
| `:git/history-exit` | fx | `root` | — | the repo's `:mode` ← `:log`, `:history-target` ← nil; commits/fold/selection/expanded/bodies reset | `[:commits/load root {:skip 0}]` — the × on the history chip in either header |

### 1.25 The Commit Graph document (ADR-0040)

The graph is a **virtual document** (`vv-git-graph://<root>`, kind `"git-graph"`) rendering a
second view over `[:ui :commits]`; it registers no new subscriptions and drives the shared
`:commits/*` selection events. See [feature 33](../features/33-commit-graph.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:git-graph/open` | fx | `root?` | `[:ui :projects]`, the pin/last-root, the active path | — | `[:doc/open "vv-git-graph://<root>"]` — an explicit root (project-header menu, panel Graph pill) must be a **non-synthetic** (git) project; the no-arg palette form derives one exactly like the panel (`commits/derive-root`); underivable → silent no-op |
| `:git-graph/data-ensure` | fx | `root` | — | — | `[:commits/ensure root]` — dispatched by `:content/received` for kind `"git-graph"` (R9: the document ingests through the same idempotent path the panel mounts through) |
| `:git-graph/shown` | fx | `root` | — | `[:ui :commits :watch-owners :graph]` ← root | `[:dispatch [:commits/sync-watch]]`, `[:dispatch [:commits/ensure root]]` — the view's mount hook |
| `:git-graph/hidden` | fx | — | — | `[:ui :commits :watch-owners :graph]` ← nil | `[:dispatch [:commits/sync-watch]]` — releases only the **graph slot** of the watch-owner union |
| `:git-graph/near-end` | fx | `root approx-hi` (the visible band's end row, from the scroll listener) | `:commits`, `:loading?`, `:exhausted?` | — | `[:commits/load-more root]` — **only** when the band is within 30 rows of the loaded end, not loading, not exhausted; the guard lives here so paging is event-decided, never a render-time dispatch |
| `:git-graph/cursor-move` | fx | `root key {:extend? :move-only? :vis-rows}` | the loaded hash order, `:selection :cursor` | — | `[:commits/cursor-to root nidx {…}]` — `ggeo/next-cursor` maps `:up`/`:down`/`:pgup`/`:pgdn`/`:home`/`:end` over the loaded indices (clamped); no cursor yet → the newest commit (index 0) |
| `:git-graph/toggle-at-cursor` | fx | `root` | `:selection :cursor` | — | `[:commits/select root cursor :toggle]` — Space |
| `:git-graph/activate-cursor` | fx | `root` | `:selection :cursor` | — | `[:commits/activate root cursor]` — Enter, diff vs first parent (R4) |

### 1.26 Git blame (ADR-0040)

Blame is **one global mode** (`[:ui :blame]`): every source-view mount reports itself, so facet
flips, tab switches, and live refreshes all re-ensure the gutter through a single hook; one
`git blame` runs per `(file, stamp)` and replies are **stamp-gated** so a refresh race can never
paint a stale gutter. See [feature 34](../features/34-git-blame-and-file-history.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:blame/source-mounted` | fx | `{:file :stamp}` | `[:ui :blame :on?]` | `[:ui :blame :file]` ← file; `[:ui :blame :stamp]` ← stamp | when the mode is on: `[:dispatch [:blame/ensure]]` — the ONE hook (dispatched from `mount-editor!`) covering toggle-while-shown, facet flips, tab switches, and live refresh (a new stamp re-blames) |
| `:blame/toggle` | fx | — | `[:ui :blame]` (`:on?`, `:file`) | on → `:on?` ← false; off→on (only for a **local, plain-path** mounted source file — `blameable?`; else a silent no-op) → `:on?` ← true | on→off: `[:blame/clear-view]`; off→on: `[:dispatch [:blame/ensure]]` — palette "Toggle git blame", `C-S-g`, `window.__vvblame` |
| `:blame/ensure` | fx | — | `[:ui :blame]` (`:on?`, `:file`/`:stamp`, the `:hunks-file`/`:hunks-stamp` cache keys) | cache miss: `:loading?` ← true, `:error` ← nil | cache hit for the mounted `(file, stamp)`: `[:blame/apply-view hunks]`; miss: `[:vv/git-blame {:file :stamp}]` — inert unless the mode is on and the file is blameable |
| `:blame/received` | fx | `file stamp {:root :hunks :error}` | `[:ui :blame :file]`/`:stamp` (the **stamp gate** — a reply for a no-longer-mounted `(file, stamp)` is dropped) | error → `:loading?` ← false, `:error`; else `:root`/`:hunks` + the cache keys `:hunks-file`/`:hunks-stamp`, `:loading?` ← false | still on → `[:blame/apply-view hunks]` |
| `:blame/error` | db | `msg` | — | `[:ui :blame :loading?]` ← false; `[:ui :blame :error]` ← msg (also the `:git/open-commit-diff` on-error target) | — |
| `:blame/line-click` | fx | `hunk` (resolved by the gutter via `blame/hunk-for-line`) | `[:ui :blame :root]` | — | committed hunk → `[:git/open-commit-diff root {:to (:hash hunk)}]`; an **uncommitted** (zero-hash) line is a no-op — there is no commit to open |
| `:git/open-commit-diff` | fx | `root {:from? :to}` | — | — | the shared open-a-commit-diff entry (blame click; the graph funnels through `:commits/activate`): `:vv/git-open-diff {root :to …}` with `:from` when given, else `:parent? true` — main resolves `<to>^`, empty tree for a root commit (R4); errors → `[:blame/error]` |

---

## 2. Effects

`reg-fx` handlers receive the effect's argument and perform IO/async, often re-dispatching back into
the loop. They are the **only** place side effects happen (effects-at-the-edge).

### 2.1 `vinary.app.fx`

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:async/debounce` | `{:key :ms :dispatch}` | `vinary.async.scheduler/debounce!` — **one live timer per `:key`**, replaced on each arming and genuinely cancellable (ADR-0033) | the given `:dispatch`, once `:ms` have passed with no re-arming |
| `:async/cancel` | `key` | `vinary.async.scheduler/cancel!` — clears the pending timer and invalidates any running sliced job for that key | — |
| `:ds/transact` | `tx` (tx-data vector) | `d/transact! ds/conn tx` (the sole DataScript write path) | — (the conn listener dispatches `[:ds/changed]`) |
| `:scroll/restore` | `n` | remember a pending content scrollTop for the next render | — |
| `:tree/reveal-active` | `_` | coalesce through Reagent's post-render queue, then scroll the already-declaratively-expanded active Files row into view | — |
| `:markdown/render` | `{:text :path :stamp :on-done}` | `md/render text` (unified pipeline → `Promise<{:html :toc :assets}>`) | `.then` → `(conj on-done result)`; `.catch` → `[:content/error {:path :message "render error: …"}]` |
| `:theme/apply` | `theme` (string) | `set! (.-href #vv-theme-link) "css/themes/<theme>.css"` | — |
| `:find/search` | `{:q :gen}` | `await pdf-cache/ensure-active!` (materialize PDF text layers / drain a stream) → `finder/search! q on-result` — **sliced and cancellable**; a superseded run never calls back (ADR-0033) | `[:find/result {:count :idx :gen}]` |
| `:find/cycle` | `{:dir :gen}` | `finder/cycle! dir` (reconciles stale Ranges first — see `ensure-fresh!`) | `[:find/result {:count :idx :gen}]` — the **count** too, because a re-collect can change it |
| `:find/clear` | `_` | `finder/clear!` (delete both highlights, disconnect the MutationObserver, reset state) | — |
| `:toc/scroll` | `id` | `getElementById id` → `scroll/scroll-el-to! {:block :start :behavior "smooth"}` — a **confined** `.vv-content` scrollTo, never `el.scrollIntoView` (ADR-0032) | — |
| `:vv/open` | `path` | `window.vv.open(path)` → `vv:open` IPC (guarded on `window.vv`) | — |
| `:vv/set-file-type` | `{:path :kind :language?}` | `window.vv.setFileType(req)` → `vv:set-file-type` IPC (guarded on `.-setFileType` — a stale preload lacks it): main registers the explicit type override and re-sends the doc to every retaining window (ADR-0036) | — |
| `:vv/close` | `path` | `window.vv.close(path)` → `vv:close` IPC (guarded) | — |
| `:vv/watch-assets` | `{:doc-path :paths}` | `window.vv.watchAssets(docPath, paths)` → `vv:watch-assets` IPC | — |
| `:vv/sync-retained-files` | `paths` | `window.vv.syncRetainedFiles(paths)` → `vv:retained-files` IPC | — |
| `:vv/sync-tree-roots` | root vector | `window.vv.syncTreeRoots(roots)` → `vv:tree-roots` IPC | — |
| `:vv/git-log` | `{:root :ref? :skip :limit :file? :follow? :lineRange? :on-done :on-error}` | `window.vv.gitLog(req)` ⮐ → `vv:git-log` — one async, main-parsed page of history (ADR-0039). `:file`+`:follow` = file history; `:lineRange {file start end}` = the single-shot `-L` walk (ADR-0040). A missing preload fn (stale daemon) degrades to the on-error path — the panel shows a sentence instead of hanging | `(conj on-done reply)` / `(conj on-error msg)` |
| `:vv/git-branches` | `{:root :on-done :on-error}` | `window.vv.gitBranches(req)` ⮐ → `vv:git-branches` — refs for the branch combo (same stale-preload degradation) | `(conj on-done reply)` / `(conj on-error msg)` |
| `:vv/git-open-diff` | `{:root :from? :to :parent? :dots? :on-error}` | `window.vv.gitOpenDiff(req)` ⮐ → `vv:git-open-diff` — main verifies both revs, runs `git diff`, spills + registers the document (ADR-0039 D4) | reply `{path}` → **`[:tab/navigate path]`** (navigation is renderer-owned — history, retention, facets; main only returns the path); `{error}` / reject → `(conj on-error msg)` |
| `:vv/git-watch` | root vector | `window.vv.gitWatch(roots)` → `vv:git-watch` — replace this window's watched-repo set (`[]` releases). Fed exclusively by `:commits/sync-watch`'s **union of the panel/graph watch-owner slots** (ADR-0040) — the surfaces write slots, never this effect directly | — (main pushes `vv:git-changed` → `[:commits/git-changed]`) |
| `:vv/git-blame` | `{:file :stamp}` | `window.vv.gitBlame({file})` ⮐ → `vv:git-blame` — main re-derives the repo from the file's own directory, blames the **working tree** (`--line-porcelain`), and replies with coalesced hunks (~100× smaller than the porcelain; ADR-0040). The `:stamp` never crosses the seam — it rides the reply event so `:blame/received` can stamp-gate | `[:blame/received file stamp reply]` (also on reject / missing preload fn, with `{:error …}`) |
| `:blame/apply-view` | hunks | `cm/set-blame! hunks on-line-click` — reconfigure the mounted source view's blame `Compartment` with the gutter extension (guarded: no mounted view, or a facet-flip-destroyed view whose DOM is disconnected → no-op); the click callback resolves the 1-based line through `blame/hunk-for-line` | `[:blame/line-click hunk]` on a gutter click |
| `:blame/clear-view` | — | `cm/clear-blame!` — reconfigure the blame `Compartment` empty (same destroyed-view guard) | — |
| `:git/selection-line-history` | `file` | `cm/selection-lines` — the mounted source view's primary selection as 1-based `[start end]` (the cursor line twice when empty); silently nothing without a mounted source view (the palette pattern) | `[:git/line-history {:file :start :end}]` |
| `:git-graph/reveal-row` | `idx` | clamp the Commit Graph's enclosing `.vv-content` scroller so the fixed-height row at `idx` sits inside the viewport — native `scrollTop` writes only (the single scroll owner, ADR-0032) | — |
| `:commits/render-body` | `{:root :hash :body}` | `md/render-ir body root` — ONE commit message body through the **single** sanitizing markdown pipeline, base-dir = the repo root so relative links resolve like a README's (lazy, on first row expand — never eagerly for a page; ADR-0039 D3) | `[:commits/body-rendered root hash html]`; render failure → `[:commits/body-rendered root hash false]` (plain-text fallback) |
| `:vv/sync-tree-expanded` | `[{root, path}]` | `window.vv.syncTreeExpanded(scopes)` → `vv:tree-expanded` IPC | — |
| `:vv/refresh-tree` | `{root path on-success on-failure}` | invoke `window.vv.refreshTree`; convert the listing/error at the edge | configured success/failure event |
| `:vv/refresh-all-trees` | `{on-success on-failure}` | invoke `window.vv.refreshAllTrees` | configured success/failure event |
| `:vv/refresh-trees` | `{scopes on-complete}` | invoke per-scope refreshes with `Promise.all`, retaining individual failures | configured completion event with result vector |
| `:vv/save-recent` | `edn` (EDN string) | **debounced 300 ms**, then `window.vv.saveRecent(edn)` → `vv:recent-save` IPC (persists the dir→child trail + recent-files MRU to `recent.edn`) | — |
| `:vv/http-toc-goto` | `id` | `window.vv.httpTocGoto(id)` → `vv:http-toc-goto` IPC | — |
| `:vv/complete-path` | `input` | `window.vv.completePath(input)` ⮐ → URI-bar completion data (SFTP-aware) | `[:uri-complete/set …]` |
| `:uri-complete/error-timeout` | `ms` | arm a timer that clears the completion error flag | `[:uri-complete/clear-error]` |
| `:vv/save-settings` | EDN string | `window.vv.saveSettings(edn)` → `vv:settings-save` IPC | — |
| `:vv/save-keymap` | EDN string | `window.vv.saveKeymap(edn)` → `vv:keymap-save` IPC | — |
| `:vv/save-ext-config` | EDN string | `window.vv.saveExtConfig(edn)` → `vv:ext-config-save` (ad-block + extension prefs) | — |
| `:pdf/cache-bytes` | `{:path :bytes}` | store a PDF's bytes in the renderer-side **pdf-cache** — the Document↔PDF switch renders a sibling PDF with no new tab | — |
| `:pdf/evict` | `path` | drop a PDF's cached bytes once no tab history reaches it (bounded retention) | — |
| `:jump/to-source-current` | — | jump preview → source using the IR's per-node source positions ([ADR-0021](../design-decisions/0021-bidirectional-source-preview-jump.md)) | `[:source/want-line n]` |
| `:jump/to-preview-current` | — | jump source → preview (the reverse map) | `[:preview/want-line n]` |
| `:source/scroll-line` / `:source/want-line` | `line` | scroll the CodeMirror source view to a line / **defer** it until the source view mounts | — |
| `:preview/scroll-line` / `:preview/want-line` | `line` | scroll the preview to the node for a source line / defer until the preview mounts | — |
| `:ssh/reply` | `{:promptId :secret}` | `window.vv.sshPromptReply(...)` → `vv:ssh-prompt-reply`. The **only** secret-bearing effect: one-shot, resolved into a main-side promise, never persisted or stored in app-db | — |
| `:vv/password-state` / `:vv/password-search` | — / `url` | request provider status / search logins matching the current web origin | — |
| `:vv/password-fill` | item metadata | reveal the item **main-side** and inject it straight into the web view — the password never enters app-db | — |
| `:vv/password-save` / `:vv/password-dismiss-save` | `{:token :provider}` / token | save / drop a short-lived main-memory login candidate | — |
| `:vv/ext-install` / `:vv/ext-remove` / `:vv/ext-set-enabled` | id-or-URL / id / `{:id :on}` | install / uninstall / enable-disable a scoped extension | — |
| `:vv/ext-check-updates` | — | trigger a Web-Store update check | `[:ext/update-result …]` |
| `:vv/ext-action-clicked` / `:vv/ext-popup-close` | `{:id :popup :bounds}` / — | open / close a browser-action popup | — |
| `:vv/adblock-set-enabled` / `:vv/adblock-set-lists` / `:vv/adblock-refresh` | bool / keyword / — | toggle / configure / refresh the ad-blocker | — (status returns on `vv:adblock-status`) |
| `:hints/collect` | — | scan the visible surface for hint targets (`a[href]`, `[data-path]` rows, and diff file banners `.vv-diff-file-head` — ADR-0037) and assign Vimium-style labels | — |
| `:hints/follow` | target | activate the hinted target: an anchor's real `.click()`; `:file`/`:dir` → `[:doc/open]`; a diff banner's `:toggle` → `getElementById` + `.click()` (rides the same delegated collapse branch as a mouse click) | — |
| `:diff/apply-collapsed` | collapsed-id set | project the per-tab `:diff-collapsed` set onto the mounted `details.vv-diff-file` wrappers (the state-change half; `markdown-body` re-applies the same helper synchronously after every innerHTML rebuild — ADR-0037) | — |
| `:vv/zoom` / `:vv/zoom-set` | direction / factor | app-window zoom (DOM views) → `vv:zoom` / `vv:zoom-set` | — (main reports `vv:zoom-changed`) |
| `:vv/http-zoom` / `:vv/http-zoom-set` | direction / factor | zoom the **web page** inside the native web view (not the app chrome) | — |
| `:vv/open-dialog` | candidate paths (vector) | open the native multi-file Open dialog, seeded to the folder of the active file/dir then the recent-files MRU (`nav/dialog-seed-path` + fallback) | — |
| `:vv/open-path` / `:vv/open-external` | path / URL | ask the OS to reveal a local path / open an external URL | — |
| `:vv/copy` | text | copy text to the OS clipboard | — |
| `:vv/quit` / `:vv/devtools` | — | quit the app / toggle renderer devtools | — |
| `:devtools/re-frame-10x` | — | toggle the re-frame-10x debug panel (dev builds only) | — |

### 2.2 `vinary.input.fx` **[input]**

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:input/arm-timeout` | `ms` | `setTimeout #(dispatch [:input/timeout]) ms` | `[:input/set-timeout-id id]` |
| `:input/cancel-timeout` | `id` | `clearTimeout id` (when id) | — |
| `:keymap/install-active` | `_` | install the active app-db keymap registry entry into the live keymap atom and dispatch its initial mode | `[:input/set-mode mode]` |
| `:keymap/persist` | EDN string | debounce and save keymap registry through `window.vv.saveKeymap` | — |
| `:dom/scroll` | `{:dy :dx :to}` | smoothly scroll the **focused pane** (the focused element's scrollable ancestor, else `.vv-content`) by easing toward an **accumulating target** through a single `requestAnimationFrame` animator — so a held key scrolls continuously and smoothly (this replaced the old per-press `behavior:"smooth"` jumps and also smooths Vim `j`/`k`, page/half scroll). Supports `:to :top`/`:bottom`; vertical `:dy` (`:page`/`:-page` ±0.9·clientH, `:half`/`:-half` ±0.5·clientH, or a number); and horizontal `:dx` (`:left`/`:right` or a number). | — |
| `:dom/focus` | `target` | `:tree` → focus `.vv-tree-filter`; `:content` → focus `.vv-content`; `:toggle` → swap focus between them | — |

> **Two timeout mechanisms exist.** The **resolver** holds an authoritative synchronous chord timer
> in a local atom (`vinary.input.resolver`); `:input/arm-timeout`/`:input/cancel-timeout` are the
> re-frame-side equivalents used by `:input/push-sequence`/`:input/reset-sequence`. The resolver's
> local timer is what actually drives live chord resolution (re-frame dispatch is async); the app-db
> sequence is mirrored only for the mode-line display. See
> [§4.2](#42-the-resolver-interpreter).

---

## 3. Subscriptions

`reg-sub` defines the Observer graph. UI subs read `app-db`; document subs read the DataScript conn
and list `:<- [:ds/rev]` so they recompute per transaction.

### 3.1 `app-db` (layer-2) subscriptions

| Sub | Inputs | Output |
| --- | --- | --- |
| `:ds/rev` | `app-db` | the DataScript revision int |
| `:ui/active-path` | `app-db` | active URI when it is a local file path, else nil |
| `:ui/theme` | `app-db` | theme name string |
| `:ui/tree` | `app-db` | `{:root :files}` \| nil |
| `:ui/tree-filter` | `app-db` | filter query string \| nil |
| `:ui/find` | `app-db` | `{:visible? :query :count :idx :gen}` — `:gen` is the request generation (ADR-0032) |
| `:ui/find-context` | `:<- [:ui/active-content-path] :<- [:facet/type]` | `[path facet]` — the identity in-page find watches; a change dispatches `[:find/reset]` |
| `:ui/active-heading` | `app-db` | active heading id \| nil |
| `:ui/sidebar-visible?` **[input]** | `app-db` | bool |
| `:ui/tree-selected` **[input]** | `app-db` | selected tree path \| nil |
| `:ui/dir-selected` | `app-db` | highlighted directory-entry path \| nil (the *explicit* selection; the rendered highlight also consults the trail — see `nav/effective-selected`) |
| `:ui/ctrl-held?` | `app-db` | bool — Control currently held (drives the Ctrl-hover breadcrumb URI bar) |
| `:ui/tab-drop` | `app-db` | `{:over <tab-id> :after? bool}` \| nil — the tab-drag drop-line indicator |
| `:ui/recent` | `app-db` | `{:trail {dir→child} :recent-files [...]}` (persisted recent-navigation state) |
| `:ui/recent-files` | `app-db` | the recent-files MRU vector (`[:ui :recent :recent-files]`, capped at 10) — surfaced in File ▸ Open Recent |
| `:ui/overlay-open?` | `app-db` | bool — OR of `:ui/menu`, `:ui/context-menu`, `:ui/settings-open?`, `:ui/about-open?`, `[:ui :kbedit :open?]`, `[:ui :palette :open?]`; true hides the native **web view** so a dropdown/modal isn't painted beneath it (PDFs render in the DOM since [ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md), so they need no such hiding) |
| `:pdf/view-state` | `app-db` | `{:scale :fit :invert?}` for the active PDF (`[:ui :pdf]`; drives `pdf/update!`) |
| `:view/zoom-percent` | `app-db` | live zoom % for the active surface (PDF scale / web-view / app-window) — shown in the zoom bar |
| `:view/pdf-active?` | `app-db` | bool — the active view is a PDF (`= :pdf (zoom/context …)`); gates the PDF-only View-menu items (Fit, Invert PDF) |
| `:input/mode` **[input]** | `app-db` | `:normal`/`:insert`/`:visual` |
| `:input/pending` **[input]** | `app-db` | the pending key-sequence vector (`:ui :input :sequence`) |
| `:input/in-input?` **[input]** | `app-db` | bool (focus is in a text input). **Display/derived only** — the keymap resolver computes its own from `document.activeElement` at keydown time; a cached flag leaks when a focused element unmounts (ADR-0032) |
| `:input/modal-keymap?` **[input]** | `:<- [::keymaps-slice]` | bool — is the active keymap set modal (Vim-like)? Layered on the slice because answering it runs `merge-user`, which walks the whole keymap |
| `:palette/state` **[input]** | `app-db` | `{:open? :source :prefix :query :items :selected}` |
| `:history/can-back?` | `app-db` | `(and idx (pos? idx))` → bool |
| `:history/can-forward?` | `app-db` | `(and idx (< idx (dec (count stack))))` → bool |
| `:ui/tabs` | `app-db` | the raw tab vector (`[:ui :tabs]`) |
| `:ui/active-tab` / `:ui/active-tab-id` | `app-db` | the active tab map / its id |
| `:ui/active-uri` | `app-db` | the active tab's current URI — a local path **or** a virtual `ssh://` / `sftp://` / `vv-archive://` URI |
| `:ui/active-view-source?` | `app-db` | bool — the active tab shows **Source** rather than **Preview** |
| `:ui/active-diff-view` | `app-db` | `:unified` \| `:split` for the active diff tab |
| `:ui/active-diff-collapsed` | `app-db` | the active tab's collapsed diff-file id set (`#{}` = all expanded) — re-projected onto the DOM after every rebuild (ADR-0037) |
| `:view/diff-active?` | `app-db` + DataScript | is the shown facet a diff PREVIEW (kind `"diff"` ∧ not source)? Gates the diff-only View-menu item + the Preview combo's layout rows |
| `:diff/all-collapsed?` | `app-db` + DataScript | are ALL of the shown diff's files collapsed? Flips the "Collapse All Files" ↔ "Expand All Files" menu label |
| `:ui/collocated-default` | `app-db` | the `collocated-default` preference (`:pdf` \| `:document`) — which face a doc with a sibling PDF opens as |
| `:ui/settings` | `app-db` | the persisted settings map (`settings.edn`) |
| `:ui/projects` | `app-db` | the git-rooted file trees |
| `:ui/tree-open` / `:ui/tree-expanding` / `:ui/tree-restoring?` | `app-db` | persistent disclosure scopes / refresh-before-open pending scopes / Files-remount refresh gate |
| `:ui/sidebar-tab` / `:ui/sidebar-width` | `app-db` | the active sidebar panel (`:files` / `:contents` / `:tabs` / `:commits`) and its width |
| `:commits/state` | `app-db` | the whole `[:ui :commits]` slice — panel targeting (`:root` pin, `:last-root`) + the per-repo cache (`:repos`), ADR-0039 |
| `:commits/panel-root` | `:<- [:commits/state]` `:<- [:ui/projects]` `:<- [:ui/active-path]` | the repo the Commits panel shows: pin > deepest git project containing the active doc > last shown > first (`commits/derive-root`); nil ⇔ no git project open |
| `:commits/for-root` | `:<- [:commits/state]` | one repo's state map (`[:repos root]` — commits, graph fold, selection, expansion, bodies, range input, flags) |
| `:commits/repos` | `:<- [:ui/projects]` | the non-synthetic (git) projects the header's repo switcher can offer — a synthetic root can never serve git data |
| `:ui/menu-focus` / `:ui/menu-submenu` / `:ui/menu-submenu-focus` | `app-db` | menu-bar keyboard traversal state |
| `:ui/access-keys-active?` | `app-db` | bool — the Alt-held access-key underlines are showing |
| `:ui/hints` | `app-db` | link-hint state `{:active? :targets :typed}` |
| `:ui/hover-link` | `app-db` | the hovered link href (status strip) |
| `:ui/uri-complete` | `app-db` | address-bar completion state (inline ghost + ambiguous-only dropdown) |
| `:ui/web-history` | `app-db` | the web-URL history backing address-bar completion |
| `:ui/app-info` | `app-db` | app metadata (About dialog) |
| `:ui/re-frame-10x-open?` | `app-db` | bool — the dev debug panel is open |
| `:ui/ssh-prompt` | `app-db` | the pending SSH auth prompt — **non-secret**; the typed secret lives only in the modal's local state, never here |
| `:ui/ssh-error` | `app-db` | the surfaced SSH connection / transport error |
| `:ui/passwords` | `app-db` | password-bridge UI state (provider status, sanitized item metadata, save prompt) — **never** a revealed password |
| `:ui/extensions` / `:ui/extensions-open?` | `app-db` | extension runtime state / whether the Extensions dialog is open |
| `:ui/adblock` | `app-db` | ad-block prefs + status `{:enabled? :lists :last-updated}` |
| `:pdf/reflow?` | `app-db` | bool — **View ▸ Reflow Text** is on for the active PDF |
| `:pdf/sibling-loaded` | `app-db` | bool — the collocated sibling PDF's bytes are cached and ready to show |
| `:keymaps/active-id` / `:keymaps/set-rows` **[input]** | `app-db` | the active keymap id / the rows rendered by the keybinding editor |
| `:kbedit/open?` · `:kbedit/sel` · `:kbedit/editing` · `:kbedit/capture` · `:kbedit/ctx` · `:kbedit/sets` · `:kbedit/focused` · `:kbedit/action-index` · `:kbedit/can-undo?` · `:kbedit/can-redo?` **[input]** | `app-db` | the visual keybinding editor's selection / capture / context state and its undo–redo stacks |

### 3.2 Tab/document derived subscriptions

| Sub | Inputs | Output |
| --- | --- | --- |
| `:tabs` | `:<- [:ui/tabs]` | app-db tab vector |
| `:tree/filtered` | `:<- [:ui/projects]` `:<- [:ui/tree-filter]` `:<- [:ui/settings]` | `[{:root :files :nodes :filtered? :extras?}]` — each project narrowed by the filter and already folded into its nested shape; ADR-0038 `:extras` (attached diffs) filter by their display `:name` — they have no root-relative path — and keep the project alive on their own. **Layered**: folding every path of every project inside the view's render function is what let a re-render land between two keystrokes (ADR-0033) |
| `:palette/candidates` | `:<- [:palette/state]` `:<- [:ui/projects]` `:<- [:ui/settings]` `:<- [::palette-ctx]` | `[{:item :score :spans}]`, empty while the palette is closed. Ranks raw strings and materialises an item only for survivors, so the cap bounds allocation rather than only the display |
| `:facet/active` | `:<- [::facet-inputs]` `:<- [:doc/group]` | the active tab's effective facet `{:path :type}`. **Layered** — as a plain db-sub it ran a `d/q` plus a 22-attribute `d/pull` on every app-db write, and it is permanently subscribed |
| `:view/switch` | `:<- [::facet-inputs]` `:<- [:doc/group]` | the `[Preview ▾ \| Source ▾]` toolbar model. Layered for the same reason as `:facet/active` |
| `:doc/active` | `:<- [:ds/rev]` `:<- [:ui/active-path]` | `ds/active-doc` → the pulled doc entity \| nil. **The `d/pull` vector in `vinary.app.ds/active-doc` is authoritative:** `:doc/path :doc/kind :doc/text :doc/html :doc/toc :doc/assets :doc/entries :doc/error :doc/stamp :doc/sheets :doc/page :doc/paged? :doc/meta :doc/sourceable? :doc/data-url :doc/reflow-html :doc/pdf-sibling :doc/source-sibling :doc/diff-split-html :doc/streaming? :doc/stream-progress :doc/stream-note`. A **new `:doc/*` attribute is invisible to views until it is added there.** |
| `:doc/kind` | `:<- [:doc/active]` | the active document's kind — selects the `content-view` Strategy |
| `:doc/toc` | `:<- [:ui/active-uri]` `:<- [:doc/active]` `:<- [:ui/web-toc]` | HTTP page headings from `:ui/web-toc`, else the stored `:doc/toc` outline (Markdown/Org/LaTeX/office headings, a PDF font-size outline, or a source-code outline) |
| `:doc/streaming?` / `:doc/stream-progress` / `:doc/stream-note` | `:<- [:doc/active]` | whether the active doc renders **incrementally**, its progress in `$`[0,1]`$`, and a user-facing status note |
| `:doc/pdf-sibling` / `:doc/source-sibling` | `:<- [:doc/active]` | the collocated same-stem sibling PDF / source path — present iff the Document↔PDF switch is available |

> Markdown TOC metadata is captured during rendering and stored on the document entity. Scroll-spy
> active-heading detection uses a measured offset cache rather than reparsing the HTML during scroll.

---

## 4. The input / command layer

The keybinding system replaces the original hand-rolled `Ctrl+F` / `Alt+←→` listener with a
data-driven **command registry** + **keymap resolver**. The pieces:

```text
keydown ─▶ resolver/handle ─▶ keys/event->chord ─▶ step(modes,mode,seq,token,ctx)
                                                      │
              ┌───────────────────────────────────────┴────────────────┐
              ▼               ▼              ▼                ▼          ▼
           :prefix        :dispatch       :consume         :pass     :retry
       (extend chord)   commands/run     (swallow)     (to input)  (re-step len-0)
                              │
                              ▼  command spec {:dispatch|:handler|:prompt :when}
                          rf/dispatch  ──▶  the events in §1
```

### 4.1 The command registry (`vinary.app.commands`)

A command is reified data `{:id :title :category :dispatch|:handler|:prompt :when :arg}`. `run` checks
the `:when` predicate against a *resolution context* and dispatches. `all-visible` populates the
command palette. Predicates: `:always`, `:has-tabs`, `:can-back?`, `:can-forward?`,
`:find-visible?`, `:palette-open?`, `:not-in-input?`.

| Category | Command ids |
| --- | --- |
| Tabs | `:tab/next`, `:tab/prev`, `:tab/close` |
| File | `:file/open`, `:file/open-in-new-tab`, `:file/reveal-in-tree` |
| Navigation | `:history/back`, `:history/forward`, `:nav/parent`, `:nav/open-target`, `:nav/scroll-down`, `:nav/scroll-up`, `:nav/page-down`, `:nav/page-up`, `:nav/half-page-down`, `:nav/half-page-up`, `:nav/scroll-top`, `:nav/scroll-bottom`, `:focus/sidebar`, `:focus/content`, `:focus/toggle`, `:tree/down`, `:tree/up`, `:tree/open` |
| Search | `:search/start`, `:search/next`, `:search/prev`, `:search/close`, `:palette/open`, `:palette/files` |
| View | `:sidebar/toggle`, `:theme/cycle`, `:theme/pick` |
| Mode (vim) | `:mode/normal`, `:mode/insert`, `:mode/visual`, `:mode/ex`, `:input/escape` |

> A command's `:dispatch` may carry an `:arg` (appended) or be called with resolver `:args`; a
> `:handler` returns the event to dispatch (used by `:tab/close`, which needs the active path); a
> `:prompt` opens the palette in a given source mode.

### 4.2 The resolver (Interpreter)

`vinary.input.resolver/step` is the pure core: given the active keymap's `modes`, the current `mode`,
the pending `sequence`, the incoming `token`, and the `ctx`, it returns a decision —
`:dispatch` (leaf command), `:prefix` (a longer chord exists), `:consume` (vim swallows a stray
normal-mode key), `:pass` (let it reach inputs/browser), or `:retry` (restart resolution at length 0).
`handle` applies the decision and `preventDefault`s as appropriate; it never hijacks a bare printable
key while a text input is focused in non-modal/insert mode. The pending sequence + chord timer are
held in resolver-**local atoms** (synchronous, authoritative) and mirrored into `app-db` only for the
mode-line display.

### 4.3 Keymaps (`vinary.input.keymap` + presets)

Three presets — **`:default`** (non-modal; generalizes the original `C-f`/`M-←→`), **`:vim`** (modal
`normal`/`insert`/`visual`, leader `SPC`), **`:emacs`** (non-modal, `C-x` prefix maps, `M-x` palette)
— are authored as EDN under `resources/keymaps/*.edn` and **embedded at compile time** by the
`vinary.input.presets/bundled` macro (the renderer build stubs `fs`, so the EDN is read on the
classpath during compilation, not at runtime). A user config can `:extends` a preset and deep-merge
deltas (with `:unbind` to remove an inherited binding). The active keymap lives in an atom; modal
state lives in `app-db`.

### 4.4 Current status

| Piece | Status |
| --- | --- |
| Command registry, resolver, `step`, `install!` | **Available** — installed by `renderer.core/keybindings!` → `resolver/install!` |
| Bundled `:default`/`:vim`/`:emacs` presets | **Available** — compiled in; switch at runtime via `window.__vvkeymap("vim")` |
| Modal FSM, pending-sequence, chord timeout | **Available** |
| Scroll / focus effects (`:dom/scroll`, `:dom/focus`) | **Available** |
| `vv:keymap` / `vv:keymap-request` IPC (user config from main) | **Available** — `vinary.main.config` reads `~/.config/vinary-viewer/keybindings.edn` (honoring `XDG_CONFIG_HOME`), watches it with chokidar, and pushes raw EDN **text** over `vv:keymap` (clj→js would flatten keyword command-ids — the renderer parses with `cljs.reader`); the renderer pulls once via `requestKeymap`. |
| Command-palette **view** | **Available** — `vinary.ui.palette/command-palette`, mounted in `vinary.ui.views/root`. |

See [usage/04-keyboard-shortcuts.md](../usage/04-keyboard-shortcuts.md) for the user-facing key tables
and [features/15-custom-keybindings.md](../features/15-custom-keybindings.md) for the design.

---

## 5. See also

- [architecture/05-data-flows.md](../architecture/05-data-flows.md) — these events/effects/subs in motion.
- [architecture/04-state-schema-reference.md](../architecture/04-state-schema-reference.md) — the state they read/write.
- [ipc-channels.md](./ipc-channels.md) — the IPC effects' wire side.
- [namespaces.md](./namespaces.md) — where each registration lives.
