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
| `:tree/received` | db | `{:root :files}` | — | `:ui :tree` ← `{:root :files(vec)}` | — |
| `:tree/filter` | db | `q` | — | `:ui :tree-filter` ← q | — |
| `:tree/move` **[input]** | db | `dir` | `:ui :tree`, `:ui :tree-filter`, `:ui :tree-selected` | `:ui :tree-selected` ← next visible path (wrapping over the **filtered** list) | — |
| `:tree/activate` **[input]** | fx | — | `:ui :tree-selected` | — | when selected: dispatch `[:doc/open sel]` |

### 1.7 In-page find

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:find/toggle` | fx | — | `:ui :find :visible?` | `:ui :find :visible?` ← not | when hiding: `[:find/clear]` |
| `:find/set-query` | fx | `q` | — | `:ui :find :query` ← q | `[:find/run q]` |
| `:find/count` | db | `n` | — | `:ui :find :count` ← n; `:ui :find :idx` ← `(if (pos? n) 1 0)` | — |
| `:find/idx` | db | `i` | — | `:ui :find :idx` ← i | — |
| `:find/cycle` | fx | `dir` | — | — | `[:find/cycle dir]` |
| `:find/close` | fx | — | — | `:ui :find :visible?` ← false | `[:find/clear]` |

### 1.8 Table of contents

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:toc/goto` | fx | `id` | — | — | `[:toc/scroll id]` |
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
| `:palette/set-query` | db | `q` | — | `:ui :palette :query` ← q, `:selected` ← 0 | — |
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
| `:tab/set-diff-view` | `:unified` \| `:split` | Set a diff tab's `:diff-view`. |
| `:tab/toggle-diff-view` | — | Flip Unified ↔ Split; entering Split requests the on-disk pre/post sources over the `vv:load-diff-sources` IPC **invoke** (there is no `reg-fx` for it), whose result returns as `[:diff/split-ready …]`. |
| `:diff/split-ready` | `{:path :html}` | The enriched side-by-side HTML arrived → stored as `:doc/diff-split-html`. |
| `:pdf/sibling-ready` | `{:path :bytes}` | The sibling PDF's bytes arrived (over `vv:load-pdf-bytes`) → cached for the pdf view. |
| `:tab/reload` | — | Re-open the active tab's URI (re-read + re-render). |

See [ADR-0025](../design-decisions/0025-latex-rendering-via-unified-latex.md) / [ADR-0026](../design-decisions/0026-diff-rendering-side-by-side-and-repo-filetypes.md).

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

---

## 2. Effects

`reg-fx` handlers receive the effect's argument and perform IO/async, often re-dispatching back into
the loop. They are the **only** place side effects happen (effects-at-the-edge).

### 2.1 `vinary.app.fx`

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:ds/transact` | `tx` (tx-data vector) | `d/transact! ds/conn tx` (the sole DataScript write path) | — (the conn listener dispatches `[:ds/changed]`) |
| `:scroll/restore` | `n` | remember a pending content scrollTop for the next render | — |
| `:markdown/render` | `{:text :path :stamp :on-done}` | `md/render text` (unified pipeline → `Promise<{:html :toc :assets}>`) | `.then` → `(conj on-done result)`; `.catch` → `[:content/error {:path :message "render error: …"}]` |
| `:theme/apply` | `theme` (string) | `set! (.-href #vv-theme-link) "css/themes/<theme>.css"` | — |
| `:find/run` | `q` | `finder/search! q` | `[:find/count <count>]` |
| `:find/cycle` | `dir` (+1/-1) | `finder/cycle! dir` | `[:find/idx <new-1-based-idx>]` |
| `:find/clear` | `_` | `finder/clear!` (delete both highlights, reset state) | — |
| `:toc/scroll` | `id` | `getElementById id` → `scrollIntoView {block:"start" behavior:"smooth"}` | — |
| `:vv/open` | `path` | `window.vv.open(path)` → `vv:open` IPC (guarded on `window.vv`) | — |
| `:vv/close` | `path` | `window.vv.close(path)` → `vv:close` IPC (guarded) | — |
| `:vv/watch-assets` | `{:doc-path :paths}` | `window.vv.watchAssets(docPath, paths)` → `vv:watch-assets` IPC | — |
| `:vv/sync-retained-files` | `paths` | `window.vv.syncRetainedFiles(paths)` → `vv:retained-files` IPC | — |
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
| `:hints/collect` | — | scan the visible surface for link targets and assign Vimium-style hint labels | — |
| `:hints/follow` | target | activate the hinted link | — |
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
| `:ui/find` | `app-db` | `{:visible? :query :count :idx}` |
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
| `:input/in-input?` **[input]** | `app-db` | bool (focus is in a text input) |
| `:palette/state` **[input]** | `app-db` | `{:open? :source :prefix :query :items :selected}` |
| `:history/can-back?` | `app-db` | `(and idx (pos? idx))` → bool |
| `:history/can-forward?` | `app-db` | `(and idx (< idx (dec (count stack))))` → bool |
| `:ui/tabs` | `app-db` | the raw tab vector (`[:ui :tabs]`) |
| `:ui/active-tab` / `:ui/active-tab-id` | `app-db` | the active tab map / its id |
| `:ui/active-uri` | `app-db` | the active tab's current URI — a local path **or** a virtual `ssh://` / `sftp://` / `vv-archive://` URI |
| `:ui/active-view-source?` | `app-db` | bool — the active tab shows **Source** rather than **Preview** |
| `:ui/active-diff-view` | `app-db` | `:unified` \| `:split` for the active diff tab |
| `:ui/collocated-default` | `app-db` | the `collocated-default` preference (`:pdf` \| `:document`) — which face a doc with a sibling PDF opens as |
| `:ui/settings` | `app-db` | the persisted settings map (`settings.edn`) |
| `:ui/projects` | `app-db` | the git-rooted file trees |
| `:ui/sidebar-tab` / `:ui/sidebar-width` | `app-db` | the active sidebar panel (`:files` / `:contents`) and its width |
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
