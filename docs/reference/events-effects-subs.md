# Reference · Events, Effects & Subscriptions

> **What this is.** Three exhaustive lookup tables for the re-frame loop — every **event**
> (`reg-event-db`/`reg-event-fx`), every **effect** (`reg-fx`, plus the one **coeffect**,
> `reg-cofx :view-pos`), and every **subscription** (`reg-sub`) registered in the renderer — plus the
> **command registry** that keybindings and the command palette dispatch through. Narrative traces are in
> [architecture/05-data-flows.md](../architecture/05-data-flows.md); the IPC side is in
> [ipc-channels.md](./ipc-channels.md).
>
> Sources: `vinary.app.events`, `vinary.app.fx`, `vinary.app.subs`, `vinary.app.commands`,
> `vinary.input.events`, `vinary.input.fx`, and `vinary.renderer.pdf` (the `:pdf/reflow` effect). Items from
> the **input / command layer** are tagged **[input]**. "ADR-NNNN" refers to the architecture decision
> records in [`docs/design-decisions/`](../design-decisions/README.md).
>
> **Exhaustiveness is linted.** `npm run lint` (`node test/lint.js`, check **4a**; `npm test` runs it first) requires every
> `(rf/reg-<kind> <id>` under `src/vinary/` to have a **first-column** table row in its kind's section —
> events in §1, effects and coeffects in §2, subscriptions in §3 — and every first-column row there to name a
> live registration of that kind, so a retired id cannot linger. Ids are written exactly as in the source,
> including the auto-namespaced subscriptions `::keymaps-slice`, `::palette-ctx` and `::facet-inputs` (that is,
> `:vinary.app.subs/…`). Each id has its **own** row (a row naming two ids only documents the first), and an
> id registered both as an event and as an effect or subscription — `:tree/reveal-active` and `:find/cycle`
> (event + effect), `:ui/content-width` and `:ui/hover-link` (event + subscription) — has a row in each
> section. See [engineering/04-lint-and-conventions.md](../engineering/04-lint-and-conventions.md).

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
| `:content/received` | fx | `{:path :kind :stamp (:text) (:html) (:bytes) (:entries) (:sheets) (:page) (:meta) (:dataUrl) (:sourceable) (:paged) (:siblings) (:language) (:stdin) (:baseDir) (:git)}` (the `vv:content` payload) | DataScript snapshot (`eid-for-path`, `doc-attr`), current tabs, `[:ui :settings :stream?]`, `[:ui :settings :collocated-default]` | the document entity (DataScript, never app-db): `:doc/kind`, `:doc/stamp`, `:doc/streaming?` (`stream-flag/enabled?` — a large doc of a streaming kind skips its batch render fx, §1.28) and the payload's other attrs; a kind-`diff` payload's `git {root from to range}` → `:doc/git {:root :from :to :range?}` and a stale `:doc/git` / `:doc/language` / `:doc/error` is retracted (ADR-0042, ADR-0036). In app-db: the first content creates the first tab; the **active** path records the recent dir→child trail and the most-recently-used (MRU) file list (never a piped-stdin doc or a Commit Graph); a group doc (`:siblings`) writes its default facet (`facet/default-facet`, PDF-first) onto every owning tab that has none yet (`nav/set-facet`, in place — §1.16) | `[:render-cache/invalidate {:path :stamp}]`, `[:ds/transact tx]`; then by kind: `markdown`/`org`/`latex` (not streaming) → `[:markdown/render …]` / `[:org/render …]` / `[:latex/render …]`, `office` → `[:office/render …]`, `diff` → `[:diff/render …]` (+ `[:diff/build-split …]` when its Split was already built), `pdf` → `[:pdf/cache-bytes {:path :bytes}]`, `git-graph` → `[:dispatch [:git-graph/data-ensure root]]` — each render fx reports back on `[:content/rendered path stamp]`; a default facet naming a not-yet-cached sibling → `[:facet/ensure-loaded {:path}]`; active → `[:vv/save-recent edn]`; always the retention fx (`[:vv/sync-retained-files …]`, `[:pdf/evict …]`, `[:render-cache/retain-only …]`, eviction `[:ds/transact …]`, conditional `[:dispatch [:tree/prune-extras …]]`); a newly created first tab → `[:dom/focus …]` |
| `:content/rendered` | fx | `path stamp {:html :toc :assets}` | DataScript snapshot (`eid-for-path`, `doc-attr :doc/stamp`) | — | when stamp still matches: add `:doc/html`, `:doc/toc`, `:doc/assets`; then `[:vv/watch-assets {:doc-path path :paths assets}]` |
| `:content/error` | fx | `{:path :message :stamp}` | DataScript snapshot, current tabs | first path error may create the first tab in `app-db` | when `path`: transact `:doc/error`; then sync retained files and evict unretained cache |

### 1.3 Documents & tabs

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:doc/open` | fx | `uri/path` | current tab, content scroll | active tab/history | focus existing tab or navigate active tab; local files → `[:vv/open path]`, `[:scroll/restore n]`, retained sync; foreground result focuses the document |
| `:doc/open-new` | fx | `uri/path` | current tab, content scroll | tabs/history | focus existing tab or open a new tab; local files → `[:vv/open path]`, `[:scroll/restore n]`, retained sync; foreground result focuses the document. Since ADR-0044 this is the **Ctrl+Shift+click** / context-menu semantics — plain Ctrl+click and middle-click use `:tab/open-background` (always-new) instead |
| `:doc/open-in-tab` **[input]** | fx | `path new?` | — | — | dispatches `:doc/open-new` when `new?`, else `:doc/open` |
| `:tab/navigate` | fx | `uri` | active tab, content scroll | active tab/history | local files → `[:vv/open path]`; also scroll restore, retained sync, and focus the document |
| `:tab/new-blank` | fx | — | active tab, content scroll | tabs, active tab | save the leaving position, append a nil-URI tab, focus and select its URI bar, sync retained files |
| `:tab/open` | fx | `uri` | active tab, content scroll | tabs/history | add a new **focused** tab (Ctrl+Shift+click / Shift+middle-click / context menu), load local files, restore top scroll, sync retained |
| `:tab/open-background` | fx | `uri` | active tab | tabs (appended, **active tab unchanged**) | the ADR-0044 background open (Ctrl+click / middle-click): `nav/add-tab-background` + `load-fx` only — no `:view-pos` cofx, no `[:scroll/restore]` (nothing is being left); records web history for http(s); with no active tab it degenerates to `[:tab/open uri]` |
| `:tab/activate` | fx | `id` | content scroll | `:ui :active-tab`, saved leaving scroll | restore the target tab's position for local files (facet-aware: `[:scroll/restore n]`, or `[:source/want-line n]` / `[:preview/want-line n]` for an entry that captured a line), sync retained, then focus URI for a nil-URI tab or content for a document tab |
| `:tab/close` | fx | `id` | tab list/history | tabs, active tab | sync retained files and evict no-longer-retained cached docs; closing the active tab applies the blank/document focus rule to its revealed neighbor. Dispatched by the × button **and** by middle-clicking a tab in either tab list — the horizontal strip or the sidebar's vertical Tabs panel, which share `tabs/tab-item` (ADR-0044) |
| `:tab/close-active` | fx | — | `[:ui :active-tab]` | — | `[:dispatch [:tab/close <active id>]]` when a tab is active, else nothing — File ▸ Close Tab (`Ctrl+W`) and the `:tab/close` command |
| `:tab/close-others` | fx | `id` | tab list | — | `[:dispatch [:tab/close id']]` for every other tab — tab context menu **Close Others** |
| `:tab/close-right` | fx | `id` | tab list (strip order) | — | `[:dispatch [:tab/close id']]` for every tab after `id` (none for an unknown `id`) — tab context menu **Close to the Right**, labelled **Close Below** in the vertical sidebar tab list |
| `:tab/duplicate` | fx | `id` | `:view-pos` cofx, tab list | a copy of tab `id` — same URI, history, facet, facet MRU and diff layout — inserted right after it with a fresh id (`:next-tab-id`) and made active (`nav/duplicate-tab`); the leaving position is saved first when `id` is the active tab; an unknown `id` changes nothing | retention sync; when a copy was made: the facet-aware restore of its current entry (`[:scroll/restore n]` / `[:source/want-line n]` / `[:preview/want-line n]`, local files only) and the blank/document focus rule — tab context menu **Duplicate tab** |
| `:tab/next` **[input]** | fx | — | `app-db` tabs, content scroll | `:ui :active-tab` | activate next tab id and apply the blank/document focus rule |
| `:tab/prev` **[input]** | fx | — | `app-db` tabs, content scroll | `:ui :active-tab` | activate previous tab id and apply the blank/document focus rule |

### 1.4 Navigation history

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:history/back` | fx | — | active tab history, `:view-pos` cofx | active tab history idx, saved leaving position, and the target entry's **facet** restored onto the tab (`nav/step` — Back returns to the previous *view*, not just the URI) | load the target URI, `[:facet/ensure-loaded {:path}]` for the entry's facet file, the facet-aware position restore (`[:scroll/restore n]` / `[:source/want-line n]` / `[:preview/want-line n]`), retained sync, and the blank/document focus rule |
| `:history/forward` | fx | — | active tab history, `:view-pos` cofx | as `:history/back`, one entry forward | as `:history/back` |
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
| `:tree/received` | fx | `{:root :files :synthetic? :scope? :extras?}` | `:ui :projects`, tree-open scopes | full project merge or exact scoped-subtree replacement; a newly delivered project opens at its root; prune stale scopes; an in-place full refresh **unions** `:extras` (`projects/merge-extras` — an extras-less re-send never wipes attached diffs, ADR-0038); scoped payloads never touch extras | `[:vv/sync-tree-roots roots]`; an unscoped (initial/full) entry also `[:dispatch [:tree/reveal-active]]` — scoped watcher updates never tug the sidebar scroll |
| `:tree/remove-project` | fx | `root` | `:ui :projects` | drop the project (`projects/remove-project`) and prune its `:tree-open` / `:tree-expanding` scopes | `[:vv/sync-tree-roots roots]` — project-header context menu **Remove from Files**; the project returns when a file under it is opened again (main's `open!` re-sends the tree, a watcher refresh does not) |
| `:tree/prune-extras` | db | `retained` (the retained document-identity set) | `:ui :projects` | drop `:transient?` extras whose `:path` is not in the retained set (`projects/prune-extras`; emptied `:extras` keys removed — persistent extras stay) | — (dispatched conditionally from `retention-fx`, only when some project carries extras — the same retention edge at which main unlinks a stdin spill) |
| `:tree/expand` | fx | `root directory` | `:ui :tree-open`, `:ui :tree-expanding` | already open or already pending → no-op; else mark the scope `[root directory]` **pending** in `:ui :tree-expanding` (refresh-before-open) | `[:vv/refresh-tree {:root :path directory :on-success [:tree/expand-ready scope] :on-failure [:tree/expand-failed scope]}]` |
| `:tree/expand-ready` | fx | `scope entry` | `:ui :tree-expanding`, `:ui :projects` | only while the scope is still pending (a project removed mid-invoke is dropped): apply the listing, un-pend the scope, and open it when it names a known directory — the subtree and its open scope commit atomically | — |
| `:tree/expand-failed` | db | `scope message` | — | un-pend the scope; it stays closed | — |
| `:tree/collapse` | db | `root directory` | tree-open scopes | remove the persistent open scope (effective descendants close too) | Files lifecycle later syncs the reduced watcher set |
| `:tree/refresh` | fx | `{:root :path}` | — | — | `[:vv/refresh-tree {:root :path :on-success [:tree/refresh-ready root] :on-failure [:tree/refresh-failed]}]` |
| `:tree/refresh-ready` | fx | `requested-root entry` | `:ui :projects` | only when `requested-root` is still a project: apply the scoped or full listing (a reply already in flight can never resurrect a removed project) | `[:vv/sync-tree-roots roots]` |
| `:tree/refresh-failed` | db | error message | — | — (the failure landing point; app-db unchanged) | — |
| `:tree/refresh-all` | fx | — | — | — | `[:vv/refresh-all-trees {:on-success [:tree/refresh-all-ready] :on-failure [:tree/refresh-all-failed]}]` — Files-tab context menu **Refresh All** |
| `:tree/refresh-all-ready` | fx | `entries` | `:ui :projects` | apply every listing whose root is still present when the reply commits | `[:vv/sync-tree-roots roots]` |
| `:tree/refresh-all-failed` | db | error message | — | — (app-db unchanged) | — |
| `:tree/sync-expanded` | fx | effective scope set `[[root directory] …]` | — | — | `[:vv/sync-tree-expanded [{:root :path} …]]` |
| `:tree/reveal-active` | fx | — | active path, projects | add its known ancestor scopes declaratively | post-render scroll-only `:tree/reveal-active` effect |
| `:tree/restore-ready` | fx | per-root results `[{:scope :entry} \| {:scope :error} …]` | projects, remembered root scopes | apply successful listings, close failed roots, clear the `:tree-restoring?` gate | `[:vv/sync-tree-roots roots]`, `[:dispatch [:tree/reveal-active]]` |
| `:tree/filter` | fx | `q` | — | — | `[:async/debounce {:key :tree/filter :ms 90 :dispatch [:tree/filter-commit q]}]` |
| `:tree/filter-commit` | db | `q` | — | `:ui :tree-filter` ← q | — |
| `:tree/move` **[input]** | db | `dir` | `:ui :projects`, `:ui :tree-filter`, `:ui :tree-selected` | `:ui :tree-selected` ← next visible path (wrapping over the **filtered** list) | — |
| `:tree/activate` **[input]** | fx | — | `:ui :tree-selected` | — | when selected: dispatch `[:doc/open sel]` |

### 1.7 In-page find

Every `:find/*` request carries a **generation** (`:ui :find :gen`, bumped by `bump-gen`). Searching is
asynchronous — it first materializes a PDF's text layers or drains a streamed document — so an earlier,
shorter query's reply can land last; `:find/result` drops any reply whose generation is stale. The input
debounce is a separate, genuinely cancellable timer (`:async/debounce` under the key `:find/search`), which
closing or resetting the bar cancels outright. See
[ADR-0032](../design-decisions/0032-scroll-ownership-and-derived-input-focus.md) and
[ADR-0033](../design-decisions/0033-asynchronous-text-input.md).

Every search request is the map `find-request` builds: `{:q :gen :mode :phonetic?}` — the query, the current
generation, the bar's match mode (`[:ui :find :mode]`: `:exact`, `:approximate` or `:fzf`; see
[usage/04-keyboard-shortcuts.md](../usage/04-keyboard-shortcuts.md)) and the `:find-phonetic?` preference
(English sound-alike matching for prose).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:find/toggle` | fx | — | `:ui :find :visible?`, `:ui :find :query`, `[:ui :settings :find-default-mode]` | `:ui :find :visible?` ← not; opening resets `:mode` ← the `:find-default-mode` preference (default `:exact`); `:gen`++ | opening with a remembered query: `[:find/search {:q :gen :mode :phonetic?}]`; closing: `[:async/cancel :find/search]` (a search armed by the last keystroke must not repaint a closed bar) then `[:find/clear]` |
| `:find/set-query` | fx | `q` | `:ui :find :gen` | `:ui :find :query` ← q; `:gen`++ | `[:async/debounce {:key :find/search :ms 40 :dispatch [:find/run gen]}]` |
| `:find/set-mode` | fx | `mode` (`:exact` / `:approximate` / `:fzf`; any other value is a no-op) | `:ui :find :query` | `:ui :find :mode` ← mode; `:gen`++ | blank query → `[:find/clear]`; else re-search immediately with the new mode (`[:find/search {:q :gen :mode :phonetic?}]`) — the find bar's mode selector |
| `:find/run` | fx | `gen` | `:ui :find :gen`, `:query`, `:mode`, `[:ui :settings :find-phonetic?]` | — | `[:find/search {:q :gen :mode :phonetic?}]` — **only if `gen` is still current**. The generation no longer doubles as the debounce (ADR-0033): `:async/debounce` cancels a superseded request outright, and the generation now guards only against an already-started search replying late. |
| `:find/retry-active` | fx | — | `:ui :find :visible?`, `:query` | — (no generation bump) | when the bar is open with a non-blank query: `[:find/search {:q :gen :mode :phonetic?}]` — re-run against what is mounted now; dispatched on the next frame after every CodeMirror source-view mount (`create-source-view`) and by `:settings/set :find-phonetic?` |
| `:find/result` | db | `{:gen :count :idx :error :match-kind}` | `:ui :find :gen` | `:ui :find :count`, `:idx`, `:error` (a search failure's message) and `:match-kind` — **only if `gen` is current** (a reply without `:gen` is accepted) | — |
| `:find/cycle` | fx | `dir` | `:ui :find :gen` | — | `[:find/cycle {:dir :gen}]` |
| `:find/reset` | fx | — | — | `:ui :find :count` ← 0; `:idx` ← 0; `:gen`++ (**query kept**) | `[:async/cancel :find/search]`, `[:find/clear]` — dispatched by the find bar whenever `:ui/find-context` (the shown path × facet) changes |
| `:find/close` | fx | — | — | `:ui :find :visible?` ← false; `:gen`++ | `[:async/cancel :find/search]`, `[:find/clear]` |

### 1.8 Table of contents

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:toc/goto` | fx | `id` | active URI, active tab (diff collapsed set) | tab `:diff-collapsed` (auto-expand) | an http(s) tab → `[:vv/http-toc-goto id]` (the web view's preload scrolls); else `[:toc/scroll id]`; a Contents click on a COLLAPSED diff file first auto-expands it — `[:diff/apply-collapsed …]` is ordered before the scroll so the offset measures the expanded layout (ADR-0037) |
| `:toc/set` | fx | `path toc` | DataScript `eid-for-path` | — | `[:ds/transact [[:db/add eid :doc/source-toc toc]]]` — the **Source** view's tree-sitter outline (`L<line>` ids for CodeMirror navigation), dispatched by the source view on every mount. Kept apart from the preview's `:doc/toc` (rehype slug ids, written by `:content/rendered`) so neither clobbers the other; the `:doc/toc` subscription picks the outline of the active view. No cached entity → no-op |
| `:toc/active-heading` | db | `id` | — | `:ui :active-heading` ← id | — |

### 1.9 Sidebar & focus / scroll commands **[input]**

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:sidebar/toggle` | fx | — | `:ui :sidebar-visible?`, `:ui :sidebar-tab`, `[:ui :settings]` | `:ui :sidebar-visible?` ← not — the PANEL column's visibility (the icon rail, ADR-0041, always renders, and clicking the active rail icon dispatches this) — mirrored to `[:ui :settings :sidebar-visible?]`; showing the **Files** panel also runs the Files restore (`[:ui :tree-restoring?]` gate while its remembered open scopes refresh) | `[:vv/save-settings edn]`; Files shown with open scopes → `[:vv/refresh-trees {:scopes … :on-complete [:tree/restore-ready]}]` |
| `:nav/focus` | fx | `target` (`:uri`/`:tree`/`:content`/`:toggle`) | `[:ui :presentation]` | a running slide show ends first when `target` is chrome the show hides (`:uri`/`:tree`/`:toggle`) | `[:dom/focus target]` (after the show's `[:vv/set-fullscreen {:on false}]`) — `events/nav-focus-fx` (ADR-0045) |
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
| `:input/escape` | fx | — | `:ui :palette :open?`, `:ui :find :visible?`, `:ui :slides :digits`, `:ui :input :mode` | mode→`:normal` or clear sequence/count (see precedence) | palette open→`[:dispatch [:palette/close]]`; else find→`[:dispatch [:find/close]]`; else a typed slide number→`[:dispatch [:slides/digits-clear]]` — the pure `input-events/escape-fx` |

> **`:input/escape` precedence** (first match wins): close palette → close find → the slide show's own
> cancel steps (ADR-0045: the overview grid, a half-typed slide number, a blanked screen, then the running show itself) →
> leave non-normal mode (→ `:normal`) → clear the pending sequence + count. This is the "universal cancel". The slide steps come before the mode switch so
> Escape has no mode side effect while it is cancelling something on a slide.

### 1.11 Keymap config **[input]**

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:keymap/config-received` | fx | EDN text (from `vv:keymap`) or an already-parsed map | — | `:ui :keymaps` ← `registry/normalize-config` envelope `{:active :order :sets}` (a legacy single `{:extends :keymaps}` delta becomes one custom set named `"Custom"`); `:ui :input :mode` ← the active set's initial mode (`registry/initial-mode-for`) in the **same** `:db`, so a modal (vim) set is live in `:normal` at boot with no one-tick `:insert` window | `[:keymap/install-active <active id>]` |
| `:keymap/select` | fx | set `id` (`"default"` / `"vim"` / `"emacs"` / a custom set name) | `[:ui :keymaps]` | `[:ui :keymaps :active]` ← id (`registry/select`); `:ui :input :mode` ← that set's initial mode | `[:keymap/install-active id]`, `[:vv/save-keymap <registry EDN>]` — Settings ▸ Key Bindings radio rows and the `window.__vvkeymap("vim")` dev hook |

> `:keymap/config-received` is triggered by the renderer's `onKeymap` IPC handler (main pushes the raw
> `keybindings.edn` text on `vv:keymap`; the renderer also pulls once via `requestKeymap`). Switching sets at
> runtime goes through `:keymap/select`; editing them goes through the key-binding editor (§1.27). See
> [§4](#4-the-input--command-layer) and [ipc-channels.md `vv:keymap`](./ipc-channels.md#2-main--renderer).

### 1.12 Command palette **[input]**

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:palette/open` | db | `{:keys [source prefix]}` | — | `:ui :palette` ← `{:open? true :source (or source :command) :prefix (or prefix "") :query "" :selected 0}` | — |
| `:palette/close` | fx | — | — | `:ui :palette :open?` ← false | `[:async/cancel :palette/query]` — a query commit armed by the last keystroke cannot land after the overlay is gone and leave a stale query for the next open |
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
| `:recent/received` | db | EDN text (`vv:recent`) | — | `:ui :recent` ← the parsed `{:trail {…} :recent-files [...] :web-history [...]}` merged over `{:trail {} :recent-files [] :web-history []}` (a non-map parse changes nothing) | — |
| `:recent/clear` | fx | — | `:ui :recent` | `:ui :recent :recent-files` ← `[]` (the dir→child `:trail` is kept) | `[:vv/save-recent edn]` |

> The trail + MRU are **also** updated as a side effect of `:content/received`: a pure `record-recent`
> helper records a `dir → child` entry for every ancestor of the active path (and, for a **file**,
> unshifts it onto `:recent-files`, capped at 10), then `:content/received` emits `[:vv/save-recent …]`.
> This only runs for the **active** tab's path (a real forward navigation), never a background
> live-refresh. See [features/17-breadcrumb-and-up-down-navigation.md](../features/17-breadcrumb-and-up-down-navigation.md).

### 1.15 Tab drag-and-drop & breadcrumb modifier

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:tab/reorder` | db | `from-id to-id after?` | tab list | `[:ui :tabs]` — tab `from-id` moved to the insertion gap before `to-id`, or after it when `after?` (the pointer is past the target's midpoint), via `nav/reorder`; an unknown `to-id` leaves the strip unchanged | — (the tab strip's drop handler) |
| `:tab/drop-set` | db | `over` (tab id), `after?` | — | `:ui :tab-drop` ← `{:over over :after? (boolean after?)}` | — |
| `:tab/drop-clear` | db | — | — | `:ui :tab-drop` ← nil | — |
| `:ui/set-ctrl-held` | db | `held?` | — | `:ui :ctrl-held?` ← `(boolean held?)` (drives the Ctrl-hover breadcrumb) | — |

> `:ui/set-ctrl-held` is dispatched by capture-phase `keydown`/`keyup` listeners in
> `vinary.renderer.core` (each reads its own `ctrlKey`, so a missed `keyup` self-heals). `:tab/drop-set`/
> `:tab/drop-clear` drive the CSS insertion line (`.vv-tab-drop-before` / `.vv-tab-drop-after`) shown
> while dragging a tab; `:tab/reorder` commits the drop.

> **The 0.3 families below** (§1.16–§1.22) use a `Event | Payload | Purpose` table; the
> `reg-event-db` / `reg-event-fx` split for each is read directly from `vinary.app.events`.

### 1.16 View facet — collocated group × Preview↔Source, and Unified↔Split

**The facet model** (`vinary.app.facet`). A document may belong to a **collocated group**: the same-stem
files of a group kind (`pdf`, `markdown`, `org`, `latex`, `mermaid`, `diff`) that main discovers and attaches
to every member's `vv:content` payload as `:siblings` → `:doc/siblings`; a lone group-kind document
synthesizes a one-member group (`facet/group-of`). A tab shows exactly one **facet** of its group,
`{:path :type}` — *which file*, and whether it is rendered (`:preview`) or shown as highlighted text
(`:source`) — stored on the tab as `:facet` (mirrored onto the current history entry) together with a per-type
MRU, `:facet-mru {:preview path :source path}`. The **Preview** button offers every member with the compiled
PDF first; the **Source** button offers every member except the PDF; the `[Preview ▾ | Source ▾]` combo is
shown only when there is a source to view or at least two previews. A missing or stale facet resolves to
`facet/default-facet`: the group's PDF preview when the group has a PDF, the opened file is not that PDF, and
the `:collocated-default` setting is not `:document`; otherwise the opened file's own preview. The pane,
Contents, in-page find and zoom follow the shown file — the **content path**, `:ui/active-content-path` —
while the address bar and the Files tree stay on the tab's primary URI.

A user-initiated switch is a **history event on the same tab** (`nav/push-facet`: same URI, a new history
entry, the leaving view's position captured, so Back returns to the previous view *and* place); only the
fresh-open default is written in place (`nav/set-facet`, from `:content/received`). A facet whose file is not
cached loads **without opening a tab**, through the ordinary open seam — `[:facet/ensure-loaded {:path}]` →
`window.vv.open` → `vv:open` → `vv:content` → `:content/received` (which also caches a PDF's bytes) — and
retention keeps every facet path alive (`nav/retained-file-paths`).

This model replaced the binary **Document↔PDF representation** switch (commit 7015251): the
`:tab/set-representation`, `:tab/toggle-representation`, `:tab/open-representation-source` and
`:pdf/sibling-ready` events, the `:doc/pdf-sibling` / `:doc/source-sibling` / `:pdf/sibling-loaded`
subscriptions, the per-tab `:representation` and `:view-source?` keys, and the `vv:load-pdf-bytes` invoke
seam no longer exist.

```text
  combo MAIN region            combo caret-menu pick         C-S-s · View ▸ View Source ·
  (views/view-switch)                    │                   tab and document context menus
          │                              │                                 │
          ▼                              │                                 ▼
  :tab/activate-facet-type               │                        :tab/toggle-source
  facet/main-target: the type's          │                        facet/toggle-target: the other
  MRU file, else its first option        │                        type's main target (if any)
          │                              ▼                                 │
          └──────────────────▶ :tab/set-facet id path type ◀───────────────┘
                                         │
                   ┌─────────────────────┴──────────────────────┐
                   ▼ {:path :type} is already shown             ▼ otherwise
       [:facet/ensure-loaded {:path}]             nav/push-facet: same URI, new history entry
                   │                              (leaving position captured; tab :facet and
                   │                              :facet-mru set); retention fx;
                   │                              [:facet/ensure-loaded {:path}]
                   │                                            │
                   └─────────────────────┬──────────────────────┘
                                         ▼
               doc entity cached (and, for a PDF, its bytes)? ── yes ──▶ nothing to load
                                         │ no
                                         ▼
        window.vv.open(path) ─▶ vv:open ─▶ main reads + routes ─▶ vv:content ─▶ :content/received
        (no tab is created; a PDF's bytes enter the renderer byte cache via [:pdf/cache-bytes …])
```

| Event | Payload | Purpose |
| --- | --- | --- |
| `:tab/set-facet` | `id path type` (`id` nil → the active tab; `type` `:preview` \| `:source`) | The one facet-switch event (a combo caret-menu pick, and the target of the two events below). Re-selecting the shown facet only re-ensures its file (`[:facet/ensure-loaded {:path}]`, no history entry); otherwise it injects `:view-pos` and records the switch as a history entry on the same tab (`nav/push-facet` — the tab's `:facet` and `:facet-mru` are set) with the retention fx and `[:facet/ensure-loaded {:path}]`. |
| `:tab/activate-facet-type` | `id type` | The combo's MAIN region (and a diff layout pick made from the Source view, which switches to the Preview showing it): takes the primary document's group (`facet/group-of`), that type's options (`preview-options` / `source-options`) and `facet/main-target` — the type's MRU file while it is still an option, else the first — and dispatches `[:tab/set-facet id path type]`; a type with no options is a no-op. |
| `:tab/toggle-source` | `id?` | Preview ↔ Source of the shown file — `C-S-s` in the default keymap, View ▸ View Source, the tab and document context menus, palette **Toggle preview / source**, `window.__vvsource()`. `facet/toggle-target` names the OTHER type's main target, resolved against the **active** tab's facet, group and MRU, then `[:tab/set-facet (or id <active>) path type]`; no-op when the other type has no options (a lone PDF has no Source). |
| `:settings/set-collocated-default` | `:pdf` \| `:document` | Persist which facet a group opens as by default: `[:ui :settings :collocated-default]` ← mode (anything but `:document` coerces to `:pdf`) → `[:vv/save-settings …]`; read by `::facet-inputs` → `facet/default-facet`. No in-tree dispatcher at HEAD — the key can also be set by hand in `settings.edn`, which `:settings/received` merges. |
| `:tab/set-diff-view` | `id view` (`id` nil → the active tab; `view` `:unified` \| `:split`) | Set a diff tab's **explicit, sticky** `:diff-view` (picked from the Preview combo's caret layout rows — ADR-0037; an unset tab follows the width-adaptive default instead, [ADR-0043](../design-decisions/0043-width-adaptive-split-default.md)). Choosing `:split` for a shown diff whose text is cached and whose split is not built yet emits `[:diff/build-split {:path :text :stamp}]`. |
| `:tab/toggle-diff-view` | — | Flip Unified ↔ Split off the **effective** (width-aware when unchosen) view of the shown diff — palette **Toggle unified / split diff**, `C-S-b` in the default keymap — by dispatching `[:tab/set-diff-view nil <other>]`, so every toggle writes an explicit, sticky choice (ADR-0043) and builds the split on demand; a non-diff document is a no-op. |
| `:diff/ensure-split` | — | The ADR-0043 idempotent gate: shown facet is a diff ∧ the effective view is `:split` ∧ text present ∧ the side-by-side HTML not built → `[:diff/build-split …]`. Dispatched from the unified diff surface's mount (`views/diff-unified-body` — every navigation path) and from `:ui/content-width` (a live width crossing). |
| `:ui/content-width` | px \| nil | The `.vv-content` ResizeObserver mirror (`views/content-width-ref`, ADR-0043): value-guarded write to `[:ui :content-width]`, then `[:diff/ensure-split]` — crossing into "wide" with an unchosen diff builds its split right then. |
| `:diff/split-ready` | `path stamp html` | A side-by-side build finished — the patch-only baseline first, then (when main found the pre/post files) the on-disk-enriched version, both from `:diff/build-split`. **Stamp-gated** against the entity's `:doc/stamp`, so an older async result never overwrites a live-refreshed diff → `[:ds/transact …]` `:doc/diff-split-html`. |
| `:diff/targets-ready` | `path stamp targets` | Main resolved which diff-relative file names exist (`{rel → resolved local or remote path}`) — first path-only from `:diff/render`, then authoritatively (even when empty) from the Split build. Stamp-gated → `[:ds/transact …]` `:doc/diff-targets` (the clickable file headers); `:content/received` clears it on every refresh so a vanished path is never left clickable. |
| `:diff/toggle-file` | `file-id` | Flip one diff file's collapsed state (its `vv-diff-file-N` summary id) in the tab's `:diff-collapsed` set — the banner-click / hint-activation event; self-gated on the shown diff preview; fires `[:diff/apply-collapsed …]` (ADR-0037). |
| `:diff/collapse-all` | — | Collapse every file — the ids come from the diff's `:doc/toc` outline (`facet/diff-file-ids`), never the DOM → `[:diff/apply-collapsed ids]`; self-gated on the shown diff preview; palette **Collapse all diff files**, vim `z M`. |
| `:diff/expand-all` | — | Expand every file: the tab's `:diff-collapsed` ← `#{}` → `[:diff/apply-collapsed #{}]`; self-gated; palette **Expand all diff files**, vim `z R`. |
| `:diff/toggle-collapse-all` | — | The View-menu item's single static event: dispatches expand-all iff everything is collapsed (the same predicate flips the item's dynamic label). |
| `:tab/reload` | — | Re-open the active tab's URI (`[:vv/open path]` for a local path — main re-reads and re-sends; an http tab is untouched) — File ▸ Reload, `Ctrl+R`, the toolbar button. |
| `:doc/set-file-type` | `{:kind :language?}` | Settings ▸ File Type: re-interpret the SHOWN document (the active facet's file) under an explicit type. Fires `[:vv/set-file-type {:path :kind :language}]` — main registers the override in its doc-overrides registry and re-sends through the full open pipeline (the `:tab/reload` shape), so the override survives watcher refreshes. `language` is a grammar-catalog id for a "source with THIS grammar" pick. No optimistic DataScript write (`:content/received` stays the single ingestion point). |

See [features/02-multi-tab-previews.md](../features/02-multi-tab-previews.md) and
[architecture/04-state-schema-reference.md §5](../architecture/04-state-schema-reference.md#5-tab-shape) (the tab
shape), and [ADR-0025](../design-decisions/0025-latex-rendering-via-unified-latex.md) /
[ADR-0026](../design-decisions/0026-diff-rendering-side-by-side-and-repo-filetypes.md) /
[ADR-0036](../design-decisions/0036-stdin-documents-and-explicit-file-types.md) /
[ADR-0037](../design-decisions/0037-collapsible-diff-file-previews.md) /
[ADR-0043](../design-decisions/0043-width-adaptive-split-default.md).

### 1.17 Remote files over SSH

| Event | Payload | Purpose |
| --- | --- | --- |
| `:ssh/prompt` | `{:promptId :kind :host :user :connKey …}` | A **non-secret** auth-prompt request from main (`vv:ssh-prompt`) → `[:ui :ssh-prompt]`, which opens the SSH prompt modal. |
| `:ssh/prompt-reply` | `prompt-id secret` | The user's answer: `[:ui :ssh-prompt]` ← nil and `[:ssh/reply {:promptId :secret}]`, which sends `vv:ssh-prompt-reply`. The secret is held in the modal's local state and passes straight into the effect — it is **never** written to app-db. |
| `:ssh/status` | `{:connKey :host :state}` | Connection status (connecting / ready / closed) → `[:ui :ssh-status]` (kept for a future indicator). |
| `:ssh/error` | `{:connKey :host :kind :message}` | A connection/transport error (host-key rejected, SFTP error, …) → `[:ui :ssh-error]`. |
| `:ssh/dismiss-error` | — | Dismiss the surfaced SSH error banner (`[:ui :ssh-error]` ← nil). |
| `:connections/received` | EDN text | Persisted, **non-secret** SSH connection metadata (`connections.edn`, `vv:connections`), parsed into `[:ui :connections]` for host/URI hints (unparsable or blank → nil). |

See [ADR-0027](../design-decisions/0027-remote-files-over-ssh.md) and [features/29](../features/29-remote-files-over-ssh.md).

### 1.18 Native password-manager bridge

| Event | Payload | Purpose |
| --- | --- | --- |
| `:passwords/open` | — | Open the passwords dialog (the web toolbar's key icon, Settings ▸ Passwords…, palette **Passwords**): `[:ui :passwords :open?]` ← true, `:result` / `:error` ← nil; `[:vv/password-state nil]` (provider status) and, on an http(s) tab, `[:vv/password-search <url>]`. |
| `:passwords/close` | — | Close the dialog (`[:ui :passwords :open?]` ← false). |
| `:passwords/retry` | — | The dialog's **Retry** button: `:result` ← nil, then the same status + search requests as `:passwords/open`. |
| `:passwords/fill` | item metadata | `[:vv/password-fill item]` — main reveals the selected login and injects it **into the web view**; the secret never enters app-db. |
| `:passwords/save` | `token provider` | The save-login prompt's **Save**: `[:vv/password-save {:token :provider}]` — main stores its short-lived, main-memory login candidate with that provider. |
| `:passwords/dismiss-save` | `token` | Dismiss the save-login prompt: `[:ui :passwords :save-prompt]` ← nil and `[:vv/password-dismiss-save token]` (main drops the candidate). |
| `:passwords/state-received` | provider status (`vv:password-state`) | Merge `{:providers :forms :busy? :error}` into `[:ui :passwords]` — sanitized provider metadata plus the page's detected login-form count. |
| `:passwords/items-received` | `{:url :origin :items}` (`vv:password-items`) | `[:ui :passwords :items]` ← the matching logins' **metadata** (no secrets); `:busy?` ← false. |
| `:passwords/result-received` | `{:ok :action …}` (`vv:password-result`) | `[:ui :passwords :result]` ← the outcome; a successful `"fill"` closes the dialog, a successful `"save"` clears the save prompt. |
| `:passwords/save-prompt` | `{:token :origin :username :providers}` \| nil (`vv:password-save-prompt`) | A login was submitted in the web view → `[:ui :passwords :save-prompt]` (the **Save Login** modal); nil clears it. |

The pushes from main (`vv:password-state` / `-items` / `-result` / `-save-prompt`) land as the four events
above; see [ipc-channels §2.5](./ipc-channels.md) and [features/23](../features/23-password-manager-bridge.md).

### 1.19 Extensions & ad-blocking

| Event | Payload | Purpose |
| --- | --- | --- |
| `:extensions/open` | — | Open the Settings ▸ Extensions… dialog (`[:ui :extensions-open?]` ← true). |
| `:extensions/close` | — | Close it (`[:ui :extensions-open?]` ← false). |
| `:extensions/install` | Web-Store id or URL | `[:vv/ext-install s]` — install into the scoped extension session; the outcome returns as `:ext/install-result`. |
| `:extensions/remove` | `id` | `[:vv/ext-remove id]` — uninstall (main re-pushes `vv:ext-state`). |
| `:extensions/set-enabled` | `id on?` | Flip one installed extension: its entry in `[:ui :extensions :installed]` gets `:enabled?` ← `on?`, then `[:vv/ext-set-enabled {:id :on}]` and `[:vv/save-ext-config edn]` (the disabled ids persist in `extensions.edn`). |
| `:extensions/toggle` | — | Flip the extension runtime's master switch `[:ui :extensions :enabled?]` and persist it (`[:vv/save-ext-config edn]` — no other IPC). |
| `:extensions/check-updates` | — | `[:vv/ext-check-updates]` — a Web-Store update check; progress and outcome return as `:ext/update-result`. |
| `:extensions/action-clicked` | `id popup bounds` | A toolbar browser-action click (`vinary.ui.ext-toolbar`) → `[:vv/ext-action-clicked {:id :popup :bounds}]` — main opens the action's popup at `bounds`. |
| `:extensions/popup-close` | — | `[:vv/ext-popup-close]` — close the open browser-action popup (no in-tree dispatcher at HEAD). |
| `:ext-config/received` | EDN text (`vv:ext-config`) | The persisted prefs (`extensions.edn`, pushed on change and pulled once at boot): its `:adblock` map is merged into `[:ui :adblock]`, its `[:extensions :enabled?]` copied to `[:ui :extensions :enabled?]`. |
| `:ext/state-received` | EDN text (`vv:ext-state`) | The runtime state from main: `:enabled?` and `:installed` merged into `[:ui :extensions]`. |
| `:ext/install-result` | result object (`vv:ext-install-result`) | `[:ui :extensions :install-status]` ← `{:ok :id :name :error}`. |
| `:ext/update-result` | result object (`vv:ext-update-result`) | `[:ui :extensions :update-status]` ← `{:checking? :ok :error}`. |
| `:adblock/toggle` | — | Flip `[:ui :adblock :enabled?]` → `[:vv/adblock-set-enabled on]` and `[:vv/save-ext-config edn]`. |
| `:adblock/set-lists` | `:ads-and-tracking` \| `:ads-only` | `[:ui :adblock :lists]` ← the choice → `[:vv/adblock-set-lists kw]` and `[:vv/save-ext-config edn]`. |
| `:adblock/refresh` | — | `[:vv/adblock-refresh]` — re-download the filter lists (Settings ▸ Extensions ▸ **Update filter lists**); status returns on `vv:adblock-status`. |
| `:adblock/status-received` | `{:status :last-updated?}` (`vv:adblock-status`) | `[:ui :adblock :status]` ← the status keyword; a present `:last-updated` is stored **and** persisted (`[:vv/save-ext-config edn]`) — `ext-config-edn` writes only `:enabled? :lists :last-updated :update-every-hours`, so a transient `:status` such as "updating" never reaches `extensions.edn`. |

### 1.20 Menus, dialogs, context menu & access keys

| Event | Payload | Purpose |
| --- | --- | --- |
| `:menu/open` | menu label | Open a top-level DOM menu: `[:ui :menu]` ← label; the open submenu and both keyboard-focus indices are cleared. |
| `:menu/close` | — | Close the menu bar: `:menu`, `:menu-submenu`, `:menu-focus`, `:menu-submenu-focus` ← nil and the Alt access-key underlines off (`:access-keys-active?` ← false). Also the event of the inert "No recent files" / "No document" placeholder rows. |
| `:menu/toggle` | menu label | A menu-title click: the open menu closes (exactly as `:menu/close`), any other menu opens (as `:menu/open`). |
| `:menu/focus` | row index | Keyboard focus within the open menu (`[:ui :menu-focus]`). |
| `:menu/submenu` | submenu id \| nil | Open a flyout submenu (Open Recent, Fit, File Type, Theme, Key Bindings), or close it with nil: `[:ui :menu-submenu]`, with its focus reset. |
| `:menu/submenu-focus` | row index | Keyboard focus within the open submenu (`[:ui :menu-submenu-focus]`). |
| `:access-keys/set` | bool | Show/hide the Alt-held access-key underlines in the menu bar. |
| `:context-menu/show` | `{:x :y :target {:kind …}}` | Open the themed right-click context menu at `x`,`y` (`[:ui :context-menu]`); its items derive from the target kind (a tab, a tree/link file, a directory, an http link, a commit, a text input, the document — Copy, Go to source/preview, …). |
| `:context-menu/close` | — | Close it (`[:ui :context-menu]` ← nil) — before any item's event runs, on Escape, and on a click-away. |
| `:settings/open` | — | Open the Preferences dialog (`[:ui :settings-open?]` ← true). |
| `:settings/close` | — | Close it (← false). |
| `:settings/received` | EDN text (`vv:settings`) | The persisted `settings.edn` (pushed by main on change, pulled once at boot via `requestSettings`): parsed and **merged** over `[:ui :settings]`; mirrors `:theme` → `[:ui :theme]`, `:sidebar-visible?`, `:sidebar-width`, and `:pdf-fit` / `:pdf-invert?` / `:pdf-reflow?` → `[:ui :pdf …]`; fires `[:fonts/apply settings]` (plus `[:theme/apply theme]` when the file names one). |
| `:settings/set` | `k v` | A Preferences field edit: `[:ui :settings k]` ← `v` immediately, then `[:async/debounce {:key :settings/apply :ms 150 :dispatch [:settings/apply]}]` — live preview lands once per pause, because a per-keystroke `:fonts/apply` (which refits every figure and diagram) made the font fields drop keys ([scientific/10](../scientific/10-input-latency-experiments.md)); `k` = `:find-phonetic?` also re-runs an open search (`[:dispatch [:find/retry-active]]`). |
| `:settings/apply` | — | The debounce landing point: `[:fonts/apply settings]` and `[:vv/save-settings (pr-str settings)]`. |
| `:about/open` | — | Open the About dialog (`[:ui :about-open?]` ← true). |
| `:about/close` | — | Close it (← false). |
| `:app-info/received` | `{:name :version :repo}` (`vv:app-info`) | App metadata for the About dialog → `[:ui :app-info]`. |
| `:file/open-dialog` | `mode?` (`:new-tab`; anything else → the current tab) | File ▸ Open… / Open in New Tab… (and their palette commands): `[:ui :open-dialog-mode]` ← `:new-tab` \| `:current`; `[:vv/open-dialog seeds]` with the ordered folder-seed chain — the active local file or directory (`nav/dialog-seed-path`), then the local recent-files MRU; main opens the dialog in the first candidate that still resolves to a directory, else the home directory. |
| `:files/opened` | `{:paths :focus-first}` (`vv:open-files`) | Paths chosen in the native Open dialog **or** named on the command line: reads, then resets `[:ui :open-dialog-mode]` to `:current`, and dispatches `files-opened-fx` — `:new-tab` → `[:doc/open-new p]` for each; otherwise one path → `[:doc/open p]`, several → `[:doc/open first]` plus `[:doc/open-new p]` for the rest; `focus-first` (the multi-argument CLI launch) re-activates the first path's tab at the end. |

### 1.21 Link hints, URI-bar completion & sidebar

| Event | Payload | Purpose |
| --- | --- | --- |
| `:hint/start` | — | Begin Vimium-style link-hint mode (`f` in the vim keymap, palette **Link hints (Vimium f)**); emits `[:hints/collect]`. |
| `:hints/activate` | target vector (from `:hints/collect`) | Enter hint mode with the labelled targets the collect effect found: `[:ui :hints]` ← `{:active? true :targets … :typed ""}`; no targets → stays inactive. |
| `:hints/type` | one letter | A label character typed while hints are active (capture-phase keydown): appends its upper-case form to `:typed`; exactly one label still prefix-matches → hints reset and `[:hints/follow target]`; none → hints reset (cancelled); several → keep typing. |
| `:hints/backspace` | — | Drop the last typed label character. |
| `:hints/cancel` | — | Abort hint mode (Escape): `[:ui :hints]` ← `{:active? false :targets [] :typed ""}`. |
| `:uri/navigate` | `tab-id text` | Resolve Enter for the originating active tab; a late reply cannot navigate a different tab, and failure keeps the blank URI draft editable. |
| `:uri-complete/typed` | `tab-id text` | Start tab-owned path completion (or reset local/history completion state). |
| `:uri-complete/result` | `tag result` | Accept a result only when its tab id and exact input still own the active completion context. |
| `:uri-complete/decide-enter` | `tab-id {:input :entries :exists? :target}` | Resolve URI-bar Enter once completion data covers the exact typed input (from `:uri/navigate`, or an `:enter`-kind `:uri-complete/result`), guarded on the tab still being active and owning the completion: an existing target → `[:tab/navigate target]`; else the first prefix match (directories first) → `[:tab/navigate <its path>]` — each followed by `[:uri-complete/clear]`; no match → `[:ui :uri-complete :error?]` ← true and `[:uri-complete/error-timeout]`. |
| `:uri-complete/set` | completion data | Merge into the address-bar completion state `[:ui :uri-complete]` (ghost + dropdown) — the view's dismiss / hover / list-only updates. |
| `:uri-complete/move` | `dir n` | ↑/↓ in the dropdown: `:selected` moves with wrap-around over the `n` candidates (from "none", -1, ↓ lands on the first and ↑ on the last) and the dropdown is un-dismissed; computed in the event off the live `:selected`, so rapid keys never read a stale view closure; `n` = 0 → no-op. |
| `:uri-complete/clear` | — | Reset `[:ui :uri-complete]` to its empty state (popup dismissed). |
| `:uri-complete/clear-error` | — | `[:ui :uri-complete :error?]` ← false — fired 2.5 s after a failed Enter by the `:uri-complete/error-timeout` effect. |
| `:sidebar/show` | tab keyword (`:files`/`:contents`/`:tabs`/`:commits`) | Show the panel column opened to that panel (`:files` routes through the tree-restore flow); a collapsed rail icon dispatches this to expand straight into its panel. |
| `:sidebar/tab` | tab keyword (`:files`/`:contents`/`:tabs`/`:commits`) | Select the sidebar panel; switching **to** `:files` runs the Files restore (refresh the remembered open scopes behind the `[:ui :tree-restoring?]` gate → `:tree/restore-ready`), any other panel clears that gate. |
| `:sidebar/reveal` | — | Show the Files panel through the same restore flow — the `:file/reveal-in-tree` command (palette **Reveal in tree**); the active file's ancestors then open via the tree's reveal-active. |
| `:sidebar/width` | `w` (px — the splitter's pointer `clientX`) | The sidebar splitter drag: clamped to 180–720 px — the TOTAL width including the 36 px icon rail ([ADR-0041](../design-decisions/0041-sidebar-vertical-icon-rail.md)) — into `[:ui :sidebar-width]` and `[:ui :settings :sidebar-width]`, then `[:vv/save-settings edn]` (debounced inside the effect). |

### 1.22 Web view, shell & app

| Event | Payload | Purpose |
| --- | --- | --- |
| `:web/open-tab` | `{:url :mode}` | A link in the web view asked for a popup; main denied the native window and relayed it ([ADR-0044](../design-decisions/0044-browser-link-gesture-family.md)). Dispatches `:tab/open-background` when `mode` is `"background"` (Chromium's `background-tab` disposition — a middle-/Ctrl-click), else `:tab/open`. Fail-closed: non-http(s) urls are ignored. |
| `:http/navigated` | `{:url :tab}` (`vv:http-navigated`) | The in-app web view navigated. Recorded onto the tab that **owns** the view (`tab`), not the active one — the http tab may have been left while the page loaded. A new http(s) URL different from the owner's current URI pushes a history entry on that tab (`nav/nav-tab`, which clears its facet state) and onto the web-history MRU, then the retention fx and `[:vv/save-recent …]`; an echo of the tab's own `loadURL`, a closed owner, or a non-http URL changes nothing. |
| `:web/toc` | heading vector (`vv:web-toc`) | The in-app web view's heading outline → `[:ui :web-toc]` (read by `:doc/toc` for an http tab — the same Contents panel model). |
| `:web/active-heading` | heading id \| nil | The web view's scroll-spy active heading → `[:ui :active-heading]`. |
| `:ui/hover-link` | href \| nil | The hovered link → `[:ui :hover-link]`, shown in the status strip. |
| `:shell/open-path` | path | `[:vv/open-path path]` — ask the OS to reveal a local path. |
| `:shell/open-external` | URL | `[:vv/open-external url]` — open in the system browser (About's repository link, the "Open in system browser" context-menu item, external links inside a PDF). |
| `:clipboard/copy` | text | `[:vv/copy text]` — copy text to the OS clipboard. |
| `:view/devtools` | — | `[:vv/devtools]` — toggle renderer devtools (View ▸ Developer Tools, `Ctrl+Shift+I`). |
| `:view/re-frame-10x` | — | Dev builds: flip `[:ui :re-frame-10x-open?]` → `[:devtools/re-frame-10x open?]` (View ▸ re-frame-10x, palette). |
| `:view/re-frame-10x-hide` | — | `[:ui :re-frame-10x-open?]` ← false → `[:devtools/re-frame-10x false]` — dispatched once at renderer boot, so the panel starts hidden. |
| `:app/quit` | — | `[:vv/quit]` — quit the application (File ▸ Quit, `Ctrl+Q`). |

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
| `:commits/select` | db | `root hash mode` | the loaded hash order | `:selection` ← `commits/select` (R3 **multi-select marking only** since [ADR-0042](../design-decisions/0042-derived-open-commit-highlight.md): `:toggle` (Ctrl) / `:range` (Shift, anchor-to-hash; falls back to a self-anchored single-row range when an end left the window); the `:single` mode is removed — plain clicks never write the selection, the opened-commit highlight derives from `:commits/open-set`) | — |
| `:commits/cursor-to` | fx | `root idx {:extend?}` | the loaded hash order | `:selection` — `extend?` (Shift) applies `commits/select … :range`; else writes `:cursor` only (plain movement never touches the marking — ADR-0042; the old `:move-only?` option is gone) — by the hash at `idx` (out-of-window idx → no-op) | `[:git-graph/reveal-row idx]` — the graph's keyboard model funnels here (ADR-0040) so both surfaces share one cursor |
| `:commits/cursor-set` | db | `root hash` | — | `:selection :cursor` ← hash — the graph's plain-click target (ADR-0042: cursor move without touching the stored marking; opening stays on double-click/Enter) | — |
| `:commits/clear-selection` | db | `root` | — | `:selection` — `:anchor` ← nil, `:selected` ← `#{}`, **`:cursor` kept** (clearing marks must not teleport keyboard focus). Dispatched by Escape in the Commit Graph while marks exist (ADR-0042) | — |
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
| `:git-graph/shown` | fx | `root` | — | `[:ui :commits :watch-owners :graph]` ← root, `[:ui :commits :last-root]` ← root (ADR-0042: `derive-root` falls back to `last-root` while a spill is the active doc, so a diff activated from a graph-only flow still highlights in a Commits panel opened afterwards) | `[:dispatch [:commits/sync-watch]]`, `[:dispatch [:commits/ensure root]]` — the view's mount hook |
| `:git-graph/hidden` | fx | — | — | `[:ui :commits :watch-owners :graph]` ← nil | `[:dispatch [:commits/sync-watch]]` — releases only the **graph slot** of the watch-owner union |
| `:git-graph/near-end` | fx | `root approx-hi` (the visible band's end row, from the scroll listener) | `:commits`, `:loading?`, `:exhausted?` | — | `[:commits/load-more root]` — **only** when the band is within 30 rows of the loaded end, not loading, not exhausted; the guard lives here so paging is event-decided, never a render-time dispatch |
| `:git-graph/cursor-move` | fx | `root key {:extend? :vis-rows}` | the loaded hash order, `:selection :cursor` | — | `[:commits/cursor-to root nidx {…}]` — `ggeo/next-cursor` maps `:up`/`:down`/`:pgup`/`:pgdn`/`:home`/`:end` over the loaded indices (clamped); no cursor yet → the newest commit (index 0). The `:move-only?` option is gone (ADR-0042: plain movement is always cursor-only) |
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

### 1.27 Key-binding editor **[input]**

The visual editor (`vinary.ui.keybindings-editor`, opened from Settings ▸ Key Bindings ▸ **Customize…**)
edits the keymap-set registry `[:ui :keymaps] {:active :order :sets}` described in
[§4.3](#43-keymaps-vinaryinputkeymap--vinaryinputkeymaps-registry). Every mutation is a **command map** —
`:put`, `:insert-set`, `:remove-set`, `:rename-set` or `:reorder` — applied by
`vinary.input.kbedit-history/apply-cmd` and pushed onto `[:ui :kbedit :undo]` (clearing `:redo`), and
`hist/invert` derives each inverse (`:put` swaps `:value`/`:prev`, `:insert-set` ↔ `:remove-set`,
`:rename-set` swaps the names, `:reorder` swaps the indices), so every edit is undoable. After each committed
change the **commit fx** re-installs the active set live and persists the registry:
`[:keymap/install-active <active id>]` + `[:keymap/persist <registry EDN>]` (400 ms debounce). The three
built-in sets (`"default"`, `"vim"`, `"emacs"`) are read-only — the binding, rename and delete events
self-gate on `registry/custom?`. Editor state lives at `[:ui :kbedit]`
(`:open? :sel :editing :capture :ctx :undo :redo`). See
[features/15-custom-keybindings.md](../features/15-custom-keybindings.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:kbedit/open` **[input]** | db | — | `registry/order`, `registry/active-id` | `[:ui :kbedit]` — `:open?` ← true; `:sel` ← the first custom set, else the active set; `:ctx`, `:editing`, `:capture` ← nil | — |
| `:kbedit/close` **[input]** | db | — | — | `:open?` ← false; `:capture`, `:ctx`, `:editing` ← nil | — |
| `:kbedit/select` **[input]** | db | set `id` | — | `:sel` ← id (the set being edited — not the active set); `:ctx` ← nil | — |
| `:kbedit/ctx-show` **[input]** | db | `{:x :y :id}` | — | `:ctx` ← the map (the editor's own right-click menu: Clone, and for a custom set Rename / Delete) | — |
| `:kbedit/ctx-close` **[input]** | db | — | — | `:ctx` ← nil | — |
| `:kbedit/do` **[input]** | fx | a command map | `[:ui :keymaps]` | the registry ← `hist/apply-cmd`; the command is conj'd onto `:undo` and `:redo` ← `[]` | the commit fx |
| `:kbedit/undo` **[input]** | fx | — | `[:ui :kbedit :undo]` | pop the last command, apply its `hist/invert`, push it onto `:redo`; an empty stack is a no-op | the commit fx (palette **Undo (key-binding editor)**) |
| `:kbedit/redo` **[input]** | fx | — | `[:ui :kbedit :redo]` | pop, re-apply, push back onto `:undo`; empty → no-op | the commit fx (palette **Redo (key-binding editor)**) |
| `:kbedit/add` **[input]** | fx | — | `registry/fresh-name`, `registry/order` | pushes `{:op :insert-set :name "Custom N" :cfg {:extends :default :keymaps {}} :idx <end>}`; `:sel` and `:editing` ← the new name (its in-place rename opens) | the commit fx |
| `:kbedit/delete` **[input]** | fx | set `id` | `registry/custom?`, `registry/order`, `:sel` | a custom set: pushes `{:op :remove-set :name id :cfg <its cfg> :idx <its index>}` (the captured cfg and index make it reversible); a deleted `:sel` moves to the first remaining custom set, else the active set; `:ctx` ← nil. A built-in: only `:ctx` ← nil | the commit fx (custom sets only) |
| `:kbedit/clone` **[input]** | fx | set `id` | `registry/clone-name`, `registry/builtin?` | pushes an `:insert-set` named `"<name> Copy"` (`"… Copy 2"`, … when taken): a built-in clones as `{:extends <its keyword> :keymaps {}}`, a custom set copies its cfg; `:sel` ← the clone; `:ctx` ← nil | the commit fx |
| `:kbedit/reorder` **[input]** | fx | `id to-idx` | `registry/order` | when the position changes: pushes `{:op :reorder :name id :from-idx :to-idx}` (drag-reorder of custom sets); else no-op | the commit fx |
| `:kbedit/begin-rename` **[input]** | db | set `id` | `registry/custom?` | a custom set: `:editing` ← id | — |
| `:kbedit/cancel-rename` **[input]** | db | — | — | `:editing` ← nil | — |
| `:kbedit/commit-rename` **[input]** | fx | `old raw` | `registry/custom?`, `registry/sets`, `registry/builtin?` | a trimmed, non-blank, changed name that is neither taken nor a built-in id: pushes `{:op :rename-set :old :new}` (an active or selected set follows the rename); `:editing` ← nil either way | the commit fx (valid renames only) |
| `:kbedit/set-binding` **[input]** | fx | `set-id mode chords action` | `registry/custom?`, `hist/current-binding` | — | a custom set and a non-empty `chords`: `[:dispatch [:kbedit/do {:op :put :set-id :mode :chords :value action :prev <current delta>}]]`; otherwise nothing |
| `:kbedit/unbind` **[input]** | fx | `set-id mode chords` | `registry/custom?`, `hist/current-binding` | — | a custom set: `[:dispatch [:kbedit/do {:op :put … :value :unbind :prev …}]]` — an `:unbind` leaf removes the inherited binding when the set is merged (§4.3) |
| `:kbedit/capture-start` **[input]** | db | `{:set-id :mode :action}` | — | `:capture` ← the map with `:chords []` (key capture for that action begins) | — |
| `:kbedit/capture-chord` **[input]** | db | `chord` | — | conj `chord` onto `[:ui :kbedit :capture :chords]` — multi-chord sequences such as `C-x C-f` or `SPC f f` | — |
| `:kbedit/capture-pop` **[input]** | db | — | — | drop the last captured chord | — |
| `:kbedit/capture-cancel` **[input]** | db | — | — | `:capture` ← nil | — |
| `:kbedit/capture-commit` **[input]** | fx | — | `[:ui :kbedit :capture]` | `:capture` ← nil | non-empty chords: `[:dispatch [:kbedit/set-binding set-id mode chords action]]` |

### 1.28 Streaming documents (ADR-0018 / ADR-0023)

A large document of a streaming kind renders as a **bounded-memory incremental stream** instead of a batch
render: `:content/received` asks `stream-flag/enabled?` (the persisted `:stream?` preference over the
compile-time default; log/text at or above 5 MiB, Markdown/Org/LaTeX at or above 256 KiB), stores
`:doc/streaming? true`, and skips the batch render effect. The view's `ir-stream-body` then starts a
`vinary.stream.scheduler` controller with one of two engines: the **transport engine** (log/text — bounded
line batches pulled from a main-process session and fed to the stream parser, a weighted pushdown automaton
(WPDA) that holds only the still-open block — see the [glossary](../GLOSSARY.md)) or the **progressive
engine** (Markdown/Org/LaTeX, and the PDF **reflow** view — the batch pipeline runs once and its top-level
blocks are committed across idle frames, byte-identical to the batch output). The events below are the
scheduler's only writes back into the loop: each targets the **document entity** (`[:ds/transact …]`, keyed
by path — never app-db) and is a no-op when the path has no cached entity. A transport or block-provider
failure is reported as `[:content/error {:path :message "stream error: …"}]`. See
[ADR-0018](../design-decisions/0018-document-streaming-pipeline.md) and
[ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:stream/progress` | fx | `path progress` (a fraction in $`[0,1]`$) | DataScript `eid-for-path` | — | `[:ds/transact [[:db/add eid :doc/stream-progress progress]]]` after every committed batch (transport: the batch's own `:progress`; progressive: committed ÷ total blocks) — drives the progress strip through `:doc/stream-progress` |
| `:stream/toc-append` | fx | `path entries` | the entity's current `:doc/toc` | — | `[:ds/transact …]` `:doc/toc` ← the outline so far plus `entries` — the bounded log outline: records whose first line names an `ERROR` / `WARN` / `WARNING` / `FATAL` / `CRITICAL` level, at most 1000 per stream (only log records qualify, so in practice the transport engine) |
| `:stream/md-ready` | fx | `path toc assets` | DataScript `eid-for-path` | — | the progressive engine knows the whole outline up front: `[:ds/transact …]` `:doc/toc` and `:doc/assets` at once, then `[:vv/watch-assets {:doc-path path :paths assets}]` (image live-refresh parity with the batch path) |
| `:stream/done` | fx | `path` | DataScript `eid-for-path` | — | `[:ds/transact …]` `:doc/stream-progress` ← 1 — the whole stream has been fed (also the transport's pull-failure exit and an empty progressive document) |
| `:stream/interrupted` | fx | `path msg` (`msg` unused) | DataScript `eid-for-path` | — | `[:ds/transact …]` `:doc/stream-note` ← "Connection lost — showing partial content. Reopen to retry." — a remote (SSH) stream dropped mid-way. **Non-fatal**: the committed blocks stay; never `:doc/error`, which would blank the streamed DOM |

### 1.29 Bidirectional source↔preview jump (ADR-0021)

**Go to source** / **Go to preview** jump between a document's rendered preview and its highlighted source,
landing on the corresponding line. Entry points: the preview and source context menus (which carry the
clicked node's `data-vv-source-*` line or the CodeMirror line) and the palette commands
`:jump/to-source` / `:jump/to-preview`, whose events derive the line themselves. The **event** decides
whether the pane must switch facet; the **effect** either scrolls the already-mounted view now
(`*/scroll-line`) or stashes the line for the view about to mount (`*/want-line`, consumed on mount — the
`renderer.scroll` `want!` → `apply!` pattern). A switch is recorded as a history entry that carries the target
line (`nav/push-facet … {:line n}`), so Back returns to the previous view and position. See
[ADR-0021](../design-decisions/0021-bidirectional-source-preview-jump.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:source/goto-line` | fx | `line` (1-based source line; a non-number is a no-op) | `:view-pos` cofx, `facet/active-type`, `facet/active-content-path` | already in Source: —; else the active tab's history ← a pushed `{:path <content path> :type :source}` facet entry with `:line line` | already in Source: `[:source/scroll-line line]`; else `[:source/want-line line]` (scrolls once the source view mounts) |
| `:preview/goto-line` | fx | `line` | `:view-pos` cofx, `facet/active-type`, `facet/active-content-path` | in Source: the active tab's history ← a pushed `:preview` facet entry with `:line line`; else — | in Source: `[:preview/want-line line]`; else `[:preview/scroll-line line]` |
| `:jump/goto-source` | fx | — | `facet/active-type`, the shown document's `:doc/kind` | — | only from the **Preview** of a previewable document (kind `markdown` or `org` — the kinds that stamp `data-vv-source-*`): `[:jump/to-source-current nil]`, which derives the line from the preview viewport and dispatches `:source/goto-line` |
| `:jump/goto-preview` | fx | — | `facet/active-type`, the shown document's `:doc/kind` | — | only from the **Source** view of a `markdown` / `org` document: `[:jump/to-preview-current nil]`, which reads the CodeMirror cursor line and dispatches `:preview/goto-line` |

### 1.30 In-renderer PDF view-state & zoom (ADR-0013)

PDFs render inside the renderer with pdf.js ([ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md)), so
their view-state is plain app-db data: `[:ui :pdf] {:scale :fit :invert? :reflow?}` (default
`{:scale 1.0 :fit :width :invert? false}`), with the fit mode, inversion and reflow persisted in
`settings.edn` as `:pdf-fit`, `:pdf-invert?` and `:pdf-reflow?` (restored by `:settings/received`). Zoom is
**context-aware** — `vinary.app.zoom/context`, keyed off the kind of the *shown facet* — and targets one of
three surfaces: `:pdf` (the pdf.js scale), `:web` (an http(s) page or a local `.html`, zoomed inside the
native web view) or `:window` (the app renderer's DOM views, zoomed through the window's webFrame). The zoom
bar shows `:view/zoom-percent`. See [features/22-zoom-and-fit.md](../features/22-zoom-and-fit.md) and
[features/11-native-pdf.md](../features/11-native-pdf.md).

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:view/zoom` | fx | `dir` (`1` in, `-1` out, `0` reset) | `zoom/context` | — | `:pdf` → `[:dispatch [:pdf/zoom …]]` with `:in` / `:out` / `:reset`; `:web` → `[:vv/http-zoom dir]`; `:window` → `[:vv/zoom dir]` — View ▸ Zoom In / Out / Reset (`Ctrl++` / `Ctrl+-` / `Ctrl+0`), the zoom bar's − / + buttons, the palette |
| `:view/zoom-set` | fx | `pct` (the zoom bar's typed value or a preset) | `zoom/context` | `:pdf` → `[:ui :pdf :scale]` ← `pdf-layout/clamp-zoom` of the factor, `[:ui :pdf :fit]` ← nil | the factor $`f = \max(10, \min(800, p)) / 100`$ for $`p`$ = `pct` → `:web` → `[:vv/http-zoom-set f]`; `:window` → `[:vv/zoom-set f]` (main clamps both to 0.4–3.0) |
| `:view/zoom-changed` | db | JS `{context, factor}` (`vv:zoom-changed`) | — | `[:ui :web-zoom]` (context `"web"`) or `[:ui :window-zoom]` ← `factor` (default 1.0) | — — main's report after every window or web-view zoom, and the boot pull (`requestZoom`), so the bar shows the live percentage |
| `:pdf/zoom` | fx | `:in` / `:out` / `:reset` | `[:ui :pdf :scale]` | `:scale` ← `pdf-layout/zoom-step` (× or ÷ the zoom factor, clamped; `:reset` → 1.0); `:fit` ← nil (an explicit zoom leaves fit mode) | — |
| `:pdf/scale-resolved` | db | `scale` | — | `[:ui :pdf :scale]` ← scale; `:fit` is kept so View ▸ Fit stays marked | — — the pdf engine reports a fit-resolved scale so the zoom bar shows the live percentage while fitting |
| `:pdf/fit` | fx | `:width` / `:page` / `:actual` | `[:ui :settings]` | `[:ui :pdf :fit]` ← mode; `[:ui :settings :pdf-fit]` ← mode | `[:vv/save-settings edn]` — the View ▸ Fit radio |
| `:pdf/invert-toggle` | fx | — | `[:ui :pdf :invert?]` | `:invert?` flipped and mirrored to `[:ui :settings :pdf-invert?]` | `[:vv/save-settings edn]` — View ▸ Invert PDF |
| `:pdf/reflow-toggle` | fx | — | `[:ui :pdf :reflow?]` | `:reflow?` flipped and mirrored to `[:ui :settings :pdf-reflow?]` | `[:vv/save-settings edn]`; switching on also `[:pdf/reflow {}]` (re-extract the mounted PDF's text, §2.3) — View ▸ Reflow Text, [ADR-0017](../design-decisions/0017-common-document-ir.md) |
| `:pdf/reflowed` | fx | `path html` | DataScript `eid-for-path` | — | `[:ds/transact …]` `:doc/reflow-html` ← html (the batch reflow view) — dispatched by the `:pdf/reflow` effect |
| `:pdf/outline` | fx | `path toc` | DataScript `eid-for-path` | — | `[:ds/transact …]` `:doc/toc` ← the numbered outline (`1`, `2`, `2.1`, …) with `vv-pdf-page-N` ids — from pdf.js `getOutline`, else the font-size heading heuristic |

---

### 1.31 Live-refresh notes (ADR-0045 / ADR-0046)

A refresh of a document that already rendered never blanks it: when the new version cannot be **shown**, the
view keeps the last good version and the doc entity carries a non-fatal `:doc/refresh-note`
`{:kind :message :stamp :at}` — `"render-failed"` (the new PDF bytes did not open in pdf.js, typically read while
`pdflatex` was still writing; raised by the pdf-view's in-place refresh), `"refresh-failed"` (main could not
re-read the file) or `"incomplete"` (main delivered a PDF whose trailer is missing). `ui/refresh-note` floats a
banner over the content with **Retry** and **×**; a successful refresh clears it. PDF bytes are cached as a
**candidate** (`pdf-cache/put-bytes!`) and promoted to the good version only once pdf.js opens them, so a torn
read cannot displace the last version that rendered.

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:pdf/deck-detected` | fx | `path stamp summary` | DataScript `eid-for-path`, `doc-attr :doc/deck` | — | `[:ds/transact …]` `:doc/deck` ← `summary` `{:deck? :tier :source :pages :frame-count}` (`vinary.slides.deck/summary`) — only when the value changed; dispatched by `pdf-view` after every mount and in-place refresh, from the classification `pdf/prepare!` computes (ADR-0045) |
| `:pdf/refresh-failed` | fx | `path stamp message` | DataScript `eid-for-path` | — | `[:ds/transact …]` `:doc/refresh-note` ← `{:kind "render-failed" :message :stamp :at}` — dispatched by `pdf-view` when a refreshed (or first) PDF candidate fails to open; the last good document stays mounted |
| `:pdf/refreshed` | fx | `path stamp` | DataScript `doc-attr :doc/refresh-note` | — | retracts `:doc/refresh-note` when present — a PDF version opened successfully (first mount or in-place refresh) |
| `:doc/refresh-retry` | fx | `path` (the shown file) | — | — | `[:vv/open path]` — the banner's **Retry**: main re-reads and re-sends the file through the ordinary open flow |
| `:doc/refresh-note-dismiss` | fx | `path` | DataScript `doc-attr :doc/refresh-note` | — | retracts `:doc/refresh-note` — the banner's **×** |

### 1.32 Slides layout (ADR-0045)

The PDF engine (`vinary.renderer.pdf`) owns the slide show: in the **slides layout** every page is a
full-viewport slot showing one page at a time at its own fit, navigated by slide (`command!` — any
`vinary.slides.deck/step` op), with the wheel flipping slides and a snap at scroll end. app-db holds only the
document view's explicit layout choice (`nav :pdf-layout`, on the tab **and** its current history entry, cleared
for a new document — like `:facet`) and a mirror of the mounted slide show's position, `[:ui :slides]`. The
**effective** layout is `vinary.slides.deck/effective-layout` — presenting → slides; else the explicit choice;
else a detected deck (`:doc/deck`) opens as slides unless `:slides-open-as` is `:document` — applied identically
by the engine and by `vinary.app.slides` (menus, subscriptions), so the two never disagree.

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:pdf/layout-toggle` | fx | — | `slides/pdf-shown?`, `slides/effective-layout` | — | `[:dispatch [:pdf/set-layout :document\|:slides]]` — the OPPOSITE of the effective layout (View ▸ View as Slide Show ↔ View as Document); no-op unless a PDF is shown |
| `:pdf/set-layout` | fx | `:slides` \| `:document` \| `nil` | `slides/pdf-shown?` | active tab `:pdf-layout` + its current history entry's (`nav/set-pdf-layout`; `nil` clears → follow detection again) | — ; the pdf-view's `:pdf/layout-request` sub re-drives the engine (`pdf/update!` → `set-layout!`, keeping the page) |
| `:slides/changed` | db | `{:path :layout :page :pages :frame :frames :overlay :overlays :label :doc-gen}` | `[:ui :slides]` | `[:ui :slides]` ← `slides/merge-report`: the map, keeping the UI-owned keys (`slides/ui-keys` — the typed `:digits`) only for the same `:path` still in the `:slides` layout | — — the engine's position report (`deck/position` + layout), at most one per animation frame; feeds the slide counter, menus and tests (`__vvdb().ui.slides`) |
| `:slides/unmounted` | fx | `path` | `[:ui :slides :path]`, `[:ui :presentation :path]` | dissoc `[:ui :slides]` when it belongs to `path`; ends a slide show of `path` (`slides-stop-fx`) | the show's `[:vv/set-fullscreen {:on false}]` when it asked for fullscreen — `events/slides-unmounted-fx`; dispatched by the pdf-view's unmount (tab switch, navigation, Reflow) |
| `:slides/command` | fx | `op` — a `vinary.slides.deck/step` op (`:next :prev :next-frame :prev-frame :first :last [:goto i] [:goto-label s] [:jump i] :back :forward`) | `[:ui :slides :digits]`, `[:ui :slides :blank]` | blanked (presenting) → ONLY clears the blank (the audience sees the slide they left — pdfpc's rule); else clears a half-typed slide number | `[:slides/command op]` (+ `[:async/cancel :slides/digits-timeout]` when a number was typed); nothing while blanked — `events/slides-command-fx`. Dispatched by the slides layout's scroll delegate (every keymap's scroll keys → `deck/scroll->op`), the `:slides` key layer, the palette's Slides commands, menus, a presenting click (`:slides/click`) and `window.__vvslides` |
| `:slides/digit` | fx | `d` — 0–9 | `[:ui :slides :layout]` (no-op unless `:slides`) | `[:ui :slides :digits]` ← `deck/digits-step` (at most 6 digits) | `[:async/debounce {:key :slides/digits-timeout :ms 2500 :dispatch [:slides/digits-clear]}]` — `events/slides-digit-fx`; bound to the digit keys in the `:slides` key layer |
| `:slides/enter` | fx | — | `[:ui :slides :digits]` | clears the typed number (and a blanked screen — an explicit go-to) | typed → `[:async/cancel :slides/digits-timeout]` `[:slides/command [:goto-label typed]]` (label first — Beamer's frame number — then the 1-based page); none → `[:dispatch [:slides/command :next]]` — `events/slides-enter-fx` |
| `:slides/backspace` | fx | — | `[:ui :slides :digits]` | drops the last typed digit | shorter number → re-arms the timeout; last digit → `[:async/cancel …]`; none typed → `[:dispatch [:slides/command :prev]]` — `events/slides-backspace-fx` |
| `:slides/digits-clear` | fx | — | — | dissoc `[:ui :slides :digits]` | `[:async/cancel :slides/digits-timeout]` — the 2.5 s timeout firing, or Escape's first slide step (`input-events/escape-fx`) |
| `:slides/present` | fx | `{:from :first\|:current\|<page index>}` (default `:first`) | `slides/pdf-shown?`, `[:ui :presentation]`, `[:ui :settings :slides-fullscreen?]` | not presenting → `[:ui :presentation]` ← `{:active? true :tab-id :path :from :fullscreen?}`, clears digits + blank | not presenting → `[:dom/focus :content]` (+ `[:vv/set-fullscreen {:on true}]` unless the preference is off); presenting → `:first` → `[:dispatch [:slides/command :first]]` (F5 again), an index → `[:goto i]`, `:current` → nothing; no PDF shown → nothing — `events/slides-present-fx`. The engine sees `:presenting?`/`:present-from` through `:pdf/layout-request`: slides layout, no margin, the start slide, click-advance and the idle cursor (ADR-0045) |
| `:slides/stop` | fx | — | `[:ui :presentation]` | dissoc `[:ui :presentation]`; clears the blank | `[:vv/set-fullscreen {:on false}]` when the show asked for fullscreen (main leaves only a fullscreen it entered) — `events/slides-stop-fx`; also Escape's last slide step, `:slides/unmounted`, `:nav/focus` on hidden chrome, and the window leaving the show's fullscreen |
| `:slides/black` | fx | — | `[:ui :presentation]`, `[:ui :slides :blank]` | presenting → `[:ui :slides :blank]` ← `:black`, or cleared when already black | — — `events/slides-blank-fx`; a no-op outside a show. Keys `b` `.` in the `:slides` layer |
| `:slides/white` | fx | — | `[:ui :presentation]`, `[:ui :slides :blank]` | presenting → `[:ui :slides :blank]` ← `:white`, or cleared when already white | — — `events/slides-blank-fx`; a no-op outside a show. Keys `w` `,` in the `:slides` layer |
| `:slides/unblank` | db | — | — | dissoc `[:ui :slides :blank]` | — — a click on the blank overlay, Escape's blank step |
| `:slides/click` | fx | `{:shift? bool}` | `[:ui :presentation]`, `[:ui :settings :slides-click-advance?]` | — | presenting and the preference on → `[:dispatch [:slides/command (if shift? :prev :next)]]` — `events/slides-click-fx`; reported by the engine for a plain primary click on the slide (no link, no selection, under 4 px of drag) |
| `:view/fullscreen-changed` | fx | `#js {fullscreen owned}` (main's `vv:fullscreen-changed`) | `[:ui :fullscreen?]`, `[:ui :presentation]` | `[:ui :fullscreen?]` ← fullscreen; the window LEAVING the fullscreen a show asked for ends the show (no `:vv/set-fullscreen` — it is already gone) | — — `events/fullscreen-changed-fx` |
| `:slides/overview` | fx | `{:open? bool}` \| nil | `[:ui :slides :overview?]`, `[:ui :slides :layout]` | open → `[:ui :slides :overview?]` ← true (clears a typed number); open + not `:open?` → closes (`slides-overview-close-fx`) | — — `events/slides-overview-fx`; a no-op without a PDF in the slides layout. Keys Tab / `o` in the `:slides` layer; the palette's Slide overview / Go to slide… (`{:open? true}` — only opens) |
| `:slides/overview-close` | fx | — | `[:ui :slides :overview?]` | dissoc `[:ui :slides :overview?]` | `[:dom/focus :content]` (the keyboard returns to the slides) — `events/slides-overview-close-fx`; the grid's own Escape / Tab / `o`, and Escape's FIRST slide step |
| `:slides/overview-pick` | fx | `page` (0-based) | — | dissoc `:overview?`, clears the blank and a typed number | `[:slides/command [:jump page]]` (a jump — Back returns) `[:dom/focus :content]` — `events/slides-overview-pick-fx`; a tile's Enter / click (Shift → the frame's other end) |

## 2. Effects

`reg-fx` handlers receive the effect's argument and perform IO/async, often re-dispatching back into
the loop. They are the **only** place side effects happen (effects-at-the-edge). The one `reg-cofx`,
`:view-pos`, runs the other way: it *reads* the world into an event handler's context before the handler
runs, so navigation handlers stay pure. Effects named `:vv/*` cross the `contextBridge` seam through
`window.vv` (`resources/preload.js`); they guard on `window.vv` and, for most of them, on the specific
preload function, so a missing bridge or a stale preload degrades to a no-op — or, for the git
request/reply effects, to their on-error path — rather than throwing.

### 2.1 `vinary.app.fx`

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:async/debounce` | `{:key :ms :dispatch}` | `vinary.async.scheduler/debounce!` — **one live timer per `:key`**, replaced on each arming and genuinely cancellable (ADR-0033) | the given `:dispatch`, once `:ms` have passed with no re-arming |
| `:async/cancel` | `key` | `vinary.async.scheduler/cancel!` — clears the pending timer and invalidates any running sliced job for that key | — |
| `:ds/transact` | `tx` (tx-data vector) | `d/transact! ds/conn tx` (the sole DataScript write path) | — (the conn listener dispatches `[:ds/changed]`) |
| `:view-pos` (cofx) | — (injected with `(rf/inject-cofx :view-pos)`) | the one **coeffect**: adds `:view-pos {:scroll (scroll/current) :line (cm/current-viewport-line)}` — the leaving view's preview pixel `scrollTop` and Source-view top line, both read eagerly at navigation time — to the handler's context; `nav/capture-pos` then keeps whichever the history entry's facet makes authoritative (`:line` for a `:source` entry, `:scroll` otherwise) | — (injected by `:tab/navigate`, `:tab/open`, `:tab/new-blank`, `:tab/activate`, `:tab/duplicate`, `:tab/next`, `:tab/prev`, `:doc/open`, `:doc/open-new`, `:history/back`, `:history/forward`, `:nav/parent`, `:tab/set-facet`, `:source/goto-line`, `:preview/goto-line`) |
| `:scroll/restore` | `n` | `scroll/want! n` — remember a pending content scrollTop for the next render | — |
| `:tree/reveal-active` | `_` | coalesce through Reagent's post-render queue, then scroll the already-declaratively-expanded active Files row into view | — |
| `:markdown/render` | `{:text :path :stamp :base-dir :on-done}` | `md/render-ir text (or base-dir (md/dir-of path)) stamp` — the pipeline through the common document IR (intermediate representation; [ADR-0017](../design-decisions/0017-common-document-ir.md), [ADR-0029](../design-decisions/0029-mature-parsers-shared-ir-features.md)) — remark-parse + remark-gfm → mdast → hast → IR → the shared sanitizing suffix + `apply-posts` → `Promise<{:html :toc :assets}>`; an explicit `:base-dir` (a piped document's cwd, ADR-0036) overrides the path-derived asset base | `.then` → `(conj on-done result)`, i.e. `[:content/rendered path stamp result]`; `.catch` → `[:content/error {:path :message "render error: …"}]` |
| `:office/render` | `{:html :path :on-done}` | `md/render-office-ir html` — office (docx / ODF) HTML from main through the common IR and the GitHub-allowlist sanitizer → HTML + a heading TOC (ADR-0017) | `(conj on-done result)`; failure → `[:content/error {:path :message "office render error: …"}]` |
| `:org/render` | `{:text :path :stamp :base-dir :on-done}` | `md/render-org-ir` — Org through uniorg into the common IR (HTML + heading TOC + assets; nested `#+begin_src` blocks highlight via `apply-posts`; [ADR-0020](../design-decisions/0020-org-mode-via-uniorg.md)) | `(conj on-done result)`; failure → `[:content/error {… "org render error: …"}]` |
| `:latex/render` | `{:text :path :stamp :base-dir :on-done}` | `md/render-latex-ir` — LaTeX through unified-latex into the common IR (HTML + heading TOC + assets; [ADR-0025](../design-decisions/0025-latex-rendering-via-unified-latex.md)) | `(conj on-done result)`; failure → `[:content/error {… "latex render error: …"}]` |
| `:diff/render` | `{:text :path :stamp :on-done}` | the **Unified** diff: `diff/parse` → tree-sitter highlighting (`diff-view/highlight-model`) → `ir.frontend.diff` → `ir.backend.html` — colored HTML plus a per-file Contents outline, with no runtime sanitizer (fixed structure + escaped text; ADR-0026). In parallel a path-only `window.vv.loadDiffSources` invoke (`vv:load-diff-sources`, `includeContent: false`) asks main which diff-relative names exist | `(conj on-done {:html :toc :assets []})`; the existence reply → `[:diff/targets-ready path stamp targets]`; failure → `[:content/error {… "diff render error: …"}]` |
| `:diff/build-split` | `{:path :text :stamp}` | the side-by-side (**Split**) build: a syntax-highlighted **baseline** from the patch alone, then a lazy `loadDiffSources` with `includeContent: true` for the full pre/post files and, when any are found, an **enriched** split with full-file context (`diff/split-html`) | `[:diff/split-ready path stamp html]` for the baseline, then for the enriched build; the content reply's existence map (authoritative even when empty) → `[:diff/targets-ready path stamp targets]` |
| `:theme/apply` | `theme` (string) | `set! (.-href #vv-theme-link) "css/themes/<theme>.css"` | — |
| `:find/search` | `{:q :gen :mode :phonetic?}` | `await pdf-cache/ensure-active!` (materialize every PDF text layer / drain a stream, so find covers the whole document) → `finder/search! q {:mode :phonetic?} on-result` — **sliced and cancellable**; a superseded run never calls back (ADR-0033). `:mode` is the bar's `:exact` / `:approximate` / `:fzf`; a mounted Source view is searched over its whole CodeMirror document, not just the rendered viewport | `[:find/result {:count :idx :error :match-kind :gen}]`; then `input-trace/mark! "find/search"` (the dev latency tracer; a no-op in release) |
| `:find/cycle` | `{:dir :gen}` | `finder/cycle! dir` — move the focused match, wrapping at both ends (reconciles stale Ranges first — see `ensure-fresh!`; a mounted Source view cycles its own full-document matches) | `[:find/result {:count :idx :match-kind :gen}]` — the **count** too, because a re-collect can change it |
| `:find/clear` | `_` | `finder/clear!` (delete both highlights, disconnect the MutationObserver, reset state) | — |
| `:toc/scroll` | `id` | `getElementById id` inside `.vv-content` → `scroll/scroll-el-to! {:block :start :behavior "smooth"}` — a **confined** `.vv-content` scrollTo, never `el.scrollIntoView` (ADR-0032); an element outside any scroller falls back to `scrollIntoView`; no element and an `L<line>` id (a Source-outline entry) → `cm/scroll-source-to-line!` | — |
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
| `:vv/complete-path` | `{:input :tag}` | `window.vv.completePath(input)` ⮐ (invoke `vv:complete-path`) → URI-bar completion data (SFTP-aware); `tag` carries the live/Enter kind, the tab id and the exact input for stale-reply rejection. Live typing is **debounced 90 ms** (it protects main, whose completion does a synchronous readdir + stat walk); an `:enter` request runs at once and cancels any pending live one, so a late reply cannot reopen the dropdown over a navigation | `[:uri-complete/result tag reply]` (a rejected invoke is dropped) |
| `:uri-complete/error-timeout` | — | arm a 2.5 s timer that clears the completion error flag | `[:uri-complete/clear-error]` |
| `:vv/save-settings` | EDN string | **debounced 300 ms** (the shared scheduler — the sidebar splitter writes per mousemove, a font field per keystroke), then `window.vv.saveSettings(edn)` → `vv:settings-save` IPC | — |
| `:vv/save-keymap` | EDN string | `window.vv.saveKeymap(edn)` → `vv:keymap-save` IPC (immediate; the editor's bulk writes use the debounced `:keymap/persist`, §2.2) | — |
| `:vv/save-ext-config` | EDN string | **debounced 300 ms** (toggles can fire rapidly), then `window.vv.saveExtConfig(edn)` → `vv:ext-config-save` (ad-block + extension prefs → `extensions.edn`) | — |
| `:pdf/cache-bytes` | `{:path :bytes :stamp}` | `pdf-cache/put-bytes!` — caches the bytes as the path's **candidate** version (promoted to the good version by `pdf-view` once pdf.js opens it, rejected if it does not — keep-last-good, §1.31); the renderer-side PDF byte cache keyed by `:doc/path`, never DataScript ([ADR-0010](../design-decisions/0010-bounded-content-retention-and-render-metadata.md)); emitted by `:content/received` for every kind-`pdf` payload, including a PDF facet loaded by `:facet/ensure-loaded` | — |
| `:slides/command` | `op` | `pdf-cache/slides-command!` — runs the op on the mounted slide show through the pdf.js-free hook the engine registers (`set-slides!`); a no-op when no PDF is in the slides layout (ADR-0045) | — (the engine reports the new position via `:slides/changed`) |
| `:pdf/evict` | `retained` (the retained path vector) | `pdf-cache/evict-keep!` — drop the cached bytes of every PDF no tab retains any more (emitted by each retention pass) | — |
| `:render-cache/invalidate` | `{:path :stamp}` | `warm-cache/invalidate-path!` — retire `path`'s renderer preparation artifacts for any OTHER stamp (a live refresh superseded them; a leased artifact is disposed when its view releases it) — emitted by every `:content/received` | — |
| `:render-cache/retain-only` | `retained` (path vector) | `warm-cache/retain-only!` — invalidate every warm-cache artifact whose path is no longer retained (the bounded cache of DOM-free parse/load artifacts that a tab revisit reuses) — emitted by each retention pass | — |
| `:facet/ensure-loaded` | `{:path}` | load a collocated **facet's** file without a tab: when `path` has no cached document entity, or is a PDF whose bytes are not cached, `window.vv.open(path)` (guarded) → `vv:open`; otherwise nothing (idempotent) — §1.16 | — (main replies on `vv:content` → `[:content/received …]`, which also caches a PDF's bytes) |
| `:jump/to-source-current` | — | `source-nav/current-preview-line` — the source line of the preview viewport's anchor node, from the IR's per-node source positions ([ADR-0021](../design-decisions/0021-bidirectional-source-preview-jump.md)); nothing when no anchor resolves | `[:source/goto-line line]` |
| `:jump/to-preview-current` | — | `cm/current-source-line` — the mounted CodeMirror view's cursor line; nothing without a mounted source view | `[:preview/goto-line line]` |
| `:source/scroll-line` | `line` | `cm/scroll-source-to-line!` — scroll the mounted CodeMirror source view to `line` now | — |
| `:source/want-line` | `line` | `cm/want-source-line!` — **defer** the line until the source view (re)mounts (`create-source-view` consumes it); also the facet-aware history restore of a `:source` entry | — |
| `:preview/scroll-line` | `line` | `source-nav/scroll-preview-to-line!` — scroll the preview to the node stamped with that source line now | — |
| `:preview/want-line` | `line` | `source-nav/want-preview-line!` — defer the line until the preview remounts; also the history restore of a preview entry that captured a `:line` | — |
| `:ssh/reply` | `{:promptId :secret}` | `window.vv.sshPromptReply(...)` → `vv:ssh-prompt-reply`. The **only** secret-bearing effect: one-shot, resolved into a main-side promise, never persisted or stored in app-db | — |
| `:vv/password-state` | — | `window.vv.passwordState()` → `vv:password-state-request` — ask main for the provider status | — (main pushes `vv:password-state` → `[:passwords/state-received …]`) |
| `:vv/password-search` | `url` | `window.vv.passwordSearch(url)` → `vv:password-search` — search the providers for logins matching the page's origin | — (main pushes `vv:password-items` → `[:passwords/items-received …]`) |
| `:vv/password-fill` | item metadata | `window.vv.passwordFill(item)` → `vv:password-fill` — reveal the item **main-side** and inject it straight into the web view; the password never enters app-db | — (outcome on `vv:password-result`) |
| `:vv/password-save` | `{:token :provider}` | `window.vv.passwordSave(payload)` → `vv:password-save` — save main's short-lived, main-memory login candidate with `provider` | — (outcome on `vv:password-result`) |
| `:vv/password-dismiss-save` | `token` | `window.vv.passwordDismissSave(token)` → `vv:password-dismiss-save` — drop the candidate | — |
| `:vv/ext-install` | Web-Store id or URL | `window.vv.extInstall(s)` → `vv:ext-install` — install into the scoped extension session | — (main pushes `vv:ext-install-result` → `[:ext/install-result …]`) |
| `:vv/ext-remove` | `id` | `window.vv.extRemove(id)` → `vv:ext-remove` — uninstall | — (main re-pushes `vv:ext-state` → `[:ext/state-received …]`) |
| `:vv/ext-set-enabled` | `{:id :on}` | `window.vv.extSetEnabled(id, on)` → `vv:ext-set-enabled` — enable or disable one extension | — |
| `:vv/ext-check-updates` | — | `window.vv.extCheckUpdates()` → `vv:ext-check-updates` — a Web-Store update check | — (main pushes `vv:ext-update-result` → `[:ext/update-result …]`, first `{:checking? true}`, then the outcome) |
| `:vv/ext-action-clicked` | `{:id :popup :bounds}` | `window.vv.extActionClicked(id, popup, bounds)` → `vv:ext-action-clicked` — open the browser-action popup | — |
| `:vv/ext-popup-close` | — | `window.vv.extPopupClose()` → `vv:ext-popup-close` — close the browser-action popup | — |
| `:vv/adblock-set-enabled` | bool | `window.vv.adblockSetEnabled(on)` → `vv:adblock-set-enabled` — switch the ad-blocker on or off | — |
| `:vv/adblock-set-lists` | `:ads-and-tracking` \| `:ads-only` | `window.vv.adblockSetLists(name)` → `vv:adblock-set-lists` (the keyword's *name* crosses the seam) — choose the filter-list set | — |
| `:vv/adblock-refresh` | — | `window.vv.adblockRefresh()` → `vv:adblock-refresh` — re-download the filter lists | — (status returns on `vv:adblock-status` → `[:adblock/status-received …]`) |
| `:hints/collect` | — | scan `.vv-content` — plus the sidebar `.vv-tree`, but only while it holds focus (otherwise file-row labels leaked over the preview) — for hint targets (`a[href]`, `[data-path]` rows, and diff file banners `.vv-diff-file-head` — ADR-0037) and assign Vimium-style labels (`hints/with-labels`) | `[:hints/activate targets]` |
| `:hints/follow` | target `{:kind :path :x :y}` | activate the hinted target: re-find the live element at its stamped viewport position and fire an anchor's own `.click()` (a PDF intra-document link navigates only through its click listener); a diff banner's `:toggle` → `getElementById` + `.click()` (rides the same delegated collapse branch as a mouse click); `:http` / `:file` / `:dir` rows → `[:doc/open path]` (their own click handlers are platform-gated); an `:anchor` fallback → `scrollIntoView` | `[:doc/open path]` for row targets |
| `:diff/apply-collapsed` | collapsed-id set | project the per-tab `:diff-collapsed` set onto the mounted `details.vv-diff-file` wrappers (the state-change half; `markdown-body` re-applies the same helper synchronously after every innerHTML rebuild — ADR-0037) | — |
| `:vv/zoom` | `dir` (`1` / `-1` / `0`) | `window.vv.zoom(dir)` → `vv:zoom` — step the app window's zoom (the DOM views) by ±0.1, or reset it to 1.0 | — (main reports `vv:zoom-changed` → `[:view/zoom-changed …]`) |
| `:vv/zoom-set` | factor | `window.vv.zoomSet(f)` → `vv:zoom-set` — absolute app-window zoom (main clamps to 0.4–3.0) | — (same report) |
| `:vv/http-zoom` | `dir` | `window.vv.httpZoom(dir)` → `vv:http-zoom` — step the **web page's** zoom inside the native web view (not the app chrome) | — (main reports `vv:zoom-changed` with context `"web"`) |
| `:vv/http-zoom-set` | factor | `window.vv.httpZoomSet(f)` → `vv:http-zoom-set` — absolute web-page zoom (clamped to 0.4–3.0) | — (same report) |
| `:vv/open-dialog` | seed paths (vector, best first) | `window.vv.openDialog(seeds)` → `vv:open-dialog` — main opens the native multi-file Open dialog in the first seed that still resolves to a directory (a file → its parent), else the home directory; the seeds come from `:file/open-dialog` (the active file or directory, then the recent-files MRU) | — (chosen paths return on `vv:open-files` → `[:files/opened …]`) |
| `:vv/open-path` | path | `window.vv.openPath(p)` → `vv:open-path` — ask the OS to reveal a local path | — |
| `:vv/open-external` | URL | `window.vv.openExternal(url)` → `vv:open-external` — open the URL in the system browser | — |
| `:vv/copy` | text | `window.vv.copyText(str text)` → `vv:clipboard-write` — copy text to the OS clipboard | — |
| `:vv/quit` | — | `window.vv.quit()` → `vv:quit` — quit the app | — |
| `:vv/devtools` | — | `window.vv.toggleDevtools()` → `vv:devtools` — toggle the renderer devtools | — |
| `:vv/set-fullscreen` | `{:on bool}` | `window.vv.setFullscreen(on)` → `vv:set-fullscreen` — window-level fullscreen of THIS window for a slide show; main enters only on request and leaves only a fullscreen it entered (`vinary.main.fullscreen/decide`, ADR-0045) | `[:view/fullscreen-changed …]` via `vv:fullscreen-changed` |
| `:devtools/re-frame-10x` | `visible?` | show or hide the day8 re-frame-10x debug panel through its `day8.re_frame_10x.show_panel_BANG_` global — present only in dev builds, so a release build ignores it | — |
| `:fonts/apply` | the settings map | write the font preferences as CSS custom properties on `:root` ([ADR-0031](../design-decisions/0031-per-content-type-fonts-and-ligatures.md)): `--vv-font-variable`, `--vv-font-latex`, `--vv-font-fixed` (each only when non-blank), `--vv-code-liga` (`:code-ligatures?` → `normal` / `none`; nil keeps `app.css`'s ligatures-off default), `--vv-code-font-size` (px) and `--vv-font-size` (px) — a document font-size change also re-measures the figures and Mermaid diagrams already on screen (`figures/refit-all!`, `mermaid/refit-all!`), the one post-DOM sizing pass | — |

### 2.2 `vinary.input.fx` **[input]**

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:input/arm-timeout` | `ms` | `setTimeout #(dispatch [:input/timeout]) ms` | `[:input/set-timeout-id id]` |
| `:input/cancel-timeout` | `id` | `clearTimeout id` (when id) | — |
| `:keymap/install-active` | set `id` | `registry/install-for! @app-db id` — install that set into the live keymap atom the resolver reads (a built-in via `keymap/install!`, a custom set via `keymap/install-user!`). It no longer dispatches the input mode: the triggering event (`:keymap/config-received`, `:keymap/select`) writes `[:ui :input :mode]` in its own `:db`, which closes the one-tick window in which the atom held the new set while the mode was still `:insert` | — |
| `:keymap/persist` | EDN string | **debounced 400 ms** (the shared scheduler — editor edits stream fast), then `window.vv.saveKeymap(edn)` → `vv:keymap-save` | — |
| `:dom/scroll` | `{:dy :dx :to}` | smoothly scroll the **focused pane** (the focused element's scrollable ancestor, else `.vv-content` — or, when the pane itself does not scroll, as in the Source view, its CodeMirror `.cm-scroller`) by easing toward an **accumulating target** through a single `requestAnimationFrame` animator — so a held key scrolls continuously and smoothly (this replaced the old per-press `behavior:"smooth"` jumps and also smooths Vim `j`/`k`, page/half scroll). Supports `:to :top`/`:bottom`; vertical `:dy` (`:page`/`:-page` ±0.9·clientH, `:half`/`:-half` ±0.5·clientH, or a number); and horizontal `:dx` (`:left`/`:right` or a number). | — |
| `:dom/focus` | `target` | `:uri` → next-frame focus + select `.vv-uri-input`; `:tree` → focus `.vv-tree-filter`; `:content` → focus `.vv-content`; `:toggle` → swap focus between tree and content | — |

> **Two timeout mechanisms exist.** The **resolver** holds an authoritative synchronous chord timer
> in a local atom (`vinary.input.resolver`); `:input/arm-timeout`/`:input/cancel-timeout` are the
> re-frame-side equivalents used by `:input/push-sequence`/`:input/reset-sequence`. The resolver's
> local timer is what actually drives live chord resolution (re-frame dispatch is async); the app-db
> sequence is mirrored only for the mode-line display. See
> [§4.2](#42-the-resolver-interpreter).

### 2.3 `vinary.renderer.pdf`

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:pdf/reflow` | `_` (`{}`) | opt-in PDF reflow ([ADR-0017](../design-decisions/0017-common-document-ir.md)): extract the **currently mounted** PDF's text (a bounded page scan → PDF IR → reflow IR plus a page-anchored outline), stash the reflow IR's top-level blocks and outline for the streaming reflow view (`reflow-blocks!`), and lower the IR to HTML for the batch reflow view; no mounted PDF, or an extraction failure → nothing. Registered here rather than in `vinary.app.fx` so the DOM-free `:node-test` build never loads pdf.js (which touches `document` at load) | `[:pdf/reflowed path html]` (non-empty HTML only) |

---

## 3. Subscriptions

`reg-sub` defines the Observer graph. UI subs read `app-db`; document subs read the DataScript conn
and list `:<- [:ds/rev]` so they recompute per transaction. Several subscriptions are deliberately
**layered** (`:<-` inputs) over a cheap extraction sub, because re-frame compares each layer's *result*: a
keystroke that changes app-db but not the extracted value stops at that layer instead of re-running an
expensive derivation (a DataScript query, a keymap merge, a tree fold) — ADR-0033. Three of those
extraction subs are private to `vinary.app.subs` and auto-namespaced: `::keymaps-slice`, `::palette-ctx`,
`::facet-inputs`.

### 3.1 `app-db` (layer-2) subscriptions

| Sub | Inputs | Output |
| --- | --- | --- |
| `:ds/rev` | `app-db` | the DataScript revision int |
| `:ui/active-path` | `app-db` | the active tab's URI when it is a local file path (`nav/active-path` → `uri/file-path`), else nil — the tab's **primary** path, not the shown facet (that is `:ui/active-content-path`) |
| `:ui/theme` | `app-db` | theme name string |
| `:ui/tree-filter` | `app-db` | filter query string \| nil |
| `:ui/find` | `app-db` | `{:visible? :query :mode :count :idx :gen :error :match-kind}` — `:mode` is `:exact` / `:approximate` / `:fzf`, `:gen` the request generation (ADR-0032), `:error` a failed search's message |
| `:ui/find-context` | `:<- [:ui/active-content-path] :<- [:facet/type]` | `[path facet]` — the identity in-page find watches; a change dispatches `[:find/reset]` |
| `:ui/active-heading` | `app-db` | active heading id \| nil |
| `:ui/sidebar-visible?` **[input]** | `app-db` | bool |
| `:ui/tree-selected` **[input]** | `app-db` | selected tree path \| nil |
| `:ui/dir-selected` | `app-db` | highlighted directory-entry path \| nil (the *explicit* selection; the rendered highlight also consults the trail — see `nav/effective-selected`) |
| `:ui/ctrl-held?` | `app-db` | bool — Control currently held (drives the Ctrl-hover breadcrumb URI bar) |
| `:ui/tab-drop` | `app-db` | `{:over <tab-id> :after? bool}` \| nil — the tab-drag drop-line indicator |
| `:ui/recent` | `app-db` | `{:trail {dir→child} :recent-files [...] :web-history [...]}` (persisted recent-navigation state, `recent.edn`) |
| `:ui/recent-files` | `app-db` | the recent-files MRU vector (`[:ui :recent :recent-files]`, capped at 10) — surfaced in File ▸ Open Recent |
| `:ui/overlay-open?` | `app-db` | bool — OR of `[:ui :menu]`, `[:ui :context-menu]`, `[:ui :settings-open?]`, `[:ui :about-open?]`, `[:ui :kbedit :open?]`, `[:ui :extensions-open?]`, `[:ui :passwords :open?]`, `[:ui :passwords :save-prompt]`, `[:ui :palette :open?]`; true hides the native **web view** so a dropdown/modal isn't painted beneath it (PDFs render in the DOM since [ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md), so they need no such hiding) |
| `:pdf/view-state` | `app-db` | `[:ui :pdf]` — `{:scale :fit :invert?}` (plus `:reflow?` once set) for the active PDF; drives `pdf/update!` |
| `:view/zoom-percent` | `app-db` | live zoom % for the active surface (PDF scale / web-view / app-window) — shown in the zoom bar |
| `:view/pdf-active?` | `app-db` | bool — the active view is a PDF (`= :pdf (zoom/context …)`); gates the PDF-only View-menu items (Fit, Invert PDF, Reflow Text) |
| `:input/mode` **[input]** | `app-db` | `:normal`/`:insert`/`:visual` |
| `:input/pending` **[input]** | `app-db` | the pending key-sequence vector (`:ui :input :sequence`) |
| `:input/in-input?` **[input]** | `app-db` | bool (focus is in a text input). **Display/derived only** — the keymap resolver computes its own from `document.activeElement` at keydown time; a cached flag leaks when a focused element unmounts (ADR-0032) |
| `::keymaps-slice` **[input]** | `app-db` | the `[:ui :keymaps]` registry slice `{:active :order :sets}` — the extraction layer under `:input/modal-keymap?` |
| `:input/modal-keymap?` **[input]** | `:<- [::keymaps-slice]` | bool — is the active keymap set modal (Vim-like)? Layered on the slice because answering it runs `merge-user`, which walks the whole keymap |
| `:palette/state` **[input]** | `app-db` | `[:ui :palette]` — `{:open? :source :prefix :query :selected}` (plus the default db's unused `:items` seed; the rows come from `:palette/candidates`) |
| `:history/can-back?` | `app-db` | `(and idx (pos? idx))` → bool |
| `:history/can-forward?` | `app-db` | `(and idx (< idx (dec (count stack))))` → bool |
| `:ui/tabs` | `app-db` | the raw tab vector (`[:ui :tabs]`) |
| `:ui/active-tab` | `app-db` | the active tab map (`nav/active-tab`) |
| `:ui/active-tab-id` | `app-db` | the active tab's id (`[:ui :active-tab]`) — the tab strip, sidebar tab list and combo buttons address their events with it |
| `:ui/active-uri` | `app-db` | the active tab's current URI — a local path **or** a virtual `ssh://` / `sftp://` / `vv-archive://` URI |
| `:ui/active-view-source?` | `:<- [:facet/active]` | bool — the shown facet's `:type` is `:source` (the active tab shows **Source** rather than **Preview**) |
| `:ui/active-diff-view` | `:<- [:ui/active-tab]` `:<- [:ui/split-wide?]` | the EFFECTIVE `:unified` \| `:split` for the active diff tab: the explicit per-tab choice, else the width-adaptive default ([ADR-0043](../design-decisions/0043-width-adaptive-split-default.md)); the combo caret's layout checkmarks read this |
| `:ui/content-width` | `app-db` | the mirrored `.vv-content` clientWidth (`[:ui :content-width]`, CSS px; nil before the ResizeObserver's first report — ADR-0043) |
| `:ui/split-wide?` | `:<- [:ui/content-width]` | its boolean `nav/split-wide?` projection (width ≥ 1000 CSS px; nil → false) — the boolean dedups per-pixel resize churn before it reaches the diff views (ADR-0043) |
| `:ui/active-diff-collapsed` | `:<- [:ui/active-tab]` | the active tab's collapsed diff-file id set (`#{}` = all expanded) — re-projected onto the DOM after every rebuild (ADR-0037) |
| `:view/diff-active?` | `app-db` + DataScript | is the shown facet a diff PREVIEW (kind `"diff"` ∧ not source)? Gates the diff-only View-menu item + the Preview combo's layout rows |
| `:diff/all-collapsed?` | `app-db` + DataScript | are ALL of the shown diff's files collapsed? Flips the "Collapse All Files" ↔ "Expand All Files" menu label |
| `:ui/collocated-default` | `:<- [:ui/settings]` | the `:collocated-default` preference (`:pdf` \| `:document`, default `:pdf`) — which facet a document with a collocated PDF opens as (§1.16); no in-tree subscriber at HEAD (`::facet-inputs` reads the same key) |
| `:ui/settings` | `app-db` | the persisted settings map (`settings.edn`) |
| `:ui/settings-open?` | `app-db` | bool — the Preferences dialog is open |
| `:ui/projects` | `app-db` | the git-rooted file trees |
| `:ui/tree-open` | `app-db` | the persistent disclosure scopes `#{[root directory] …}` (default `#{}`) |
| `:ui/tree-expanding` | `app-db` | the refresh-before-open **pending** scopes (default `#{}`) — a pending directory row renders `aria-busy` with the `vv-dir-refreshing` class while its listing is in flight (`:tree/expand`) |
| `:ui/tree-restoring?` | `app-db` | bool (default false) — the Files-remount refresh gate, true while the remembered open scopes refresh (`:tree/restore-ready` clears it) |
| `:ui/sidebar-tab` | `app-db` | the active sidebar panel (`:files` / `:contents` / `:tabs` / `:commits`) |
| `:ui/sidebar-width` | `app-db` | the sidebar's TOTAL width in px, including the 36 px icon rail (clamped 180–720 by `:sidebar/width`, ADR-0041) |
| `:commits/state` | `app-db` | the whole `[:ui :commits]` slice — panel targeting (`:root` pin, `:last-root`) + the per-repo cache (`:repos`), ADR-0039 |
| `:commits/panel-root` | `:<- [:commits/state]` `:<- [:ui/projects]` `:<- [:ui/active-path]` | the repo the Commits panel shows: pin > deepest git project containing the active doc > last shown > first (`commits/derive-root`); nil ⇔ no git project open |
| `:commits/for-root` | `:<- [:commits/state]` | one repo's state map (`[:repos root]` — commits, graph fold, selection, expansion, bodies, range input, flags) |
| `:commits/open-set` | `:<- [:doc/git]` | the repo-`root` commits whose diff IS the active document (`commits/open-set` — `#{to}` for a parent diff, `#{from to}` for an explicit range, `#{}` otherwise): the **derived** `-open` row highlight on both surfaces, never stored ([ADR-0042](../design-decisions/0042-derived-open-commit-highlight.md)) |
| `:commits/repos` | `:<- [:ui/projects]` | the non-synthetic (git) projects the header's repo switcher can offer — a synthetic root can never serve git data |
| `:ui/menu` | `app-db` | the open top-level menu's label \| nil |
| `:ui/menu-focus` | `app-db` | the keyboard-focused row index in the open menu \| nil |
| `:ui/menu-submenu` | `app-db` | the open flyout submenu's id \| nil |
| `:ui/menu-submenu-focus` | `app-db` | the keyboard-focused row index in the open submenu \| nil |
| `:ui/access-keys-active?` | `app-db` | bool — the Alt-held access-key underlines are showing |
| `:ui/context-menu` | `app-db` | the open right-click menu `{:x :y :target {:kind …}}` \| nil |
| `:ui/about-open?` | `app-db` | bool — the About dialog is open |
| `:ui/hints` | `app-db` | link-hint state `{:active? :targets :typed}` |
| `:ui/hover-link` | `app-db` | the hovered link href (status strip) |
| `:ui/uri-complete` | `app-db` | address-bar completion state (inline ghost + ambiguous-only dropdown) |
| `:ui/web-history` | `app-db` | the web-URL history backing address-bar completion (`[:ui :recent :web-history]`) |
| `:ui/web-toc` | `app-db` | the in-app web view's heading outline (`[:ui :web-toc]`, from `:web/toc`) — an input of `:doc/toc` |
| `:ui/app-info` | `app-db` | app metadata (About dialog) |
| `:ui/re-frame-10x-open?` | `app-db` | bool — the dev debug panel is open |
| `:ui/ssh-prompt` | `app-db` | the pending SSH auth prompt — **non-secret**; the typed secret lives only in the modal's local state, never here |
| `:ui/ssh-error` | `app-db` | the surfaced SSH connection / transport error |
| `:ui/passwords` | `app-db` | password-bridge UI state (provider status, sanitized item metadata, save prompt) — **never** a revealed password |
| `:ui/extensions` | `app-db` | extension runtime state `{:enabled? :installed :install-status :update-status}` |
| `:ui/extensions-open?` | `app-db` | bool — the Settings ▸ Extensions… dialog is open |
| `:ui/adblock` | `app-db` | ad-block prefs + status `{:enabled? :lists :last-updated :status}` |
| `:pdf/reflow?` | `app-db` | bool — **View ▸ Reflow Text** is on for the active PDF |
| `:keymaps/active-id` **[input]** | `app-db` | the active keymap set id (`registry/active-id`; `"default"` when unset) |
| `:keymaps/set-rows` **[input]** | `app-db` | `[{:id :name :selected?}]` — every set (built-ins first, then custom sets in order) with the active one marked; no in-tree subscriber at HEAD (the Settings ▸ Key Bindings submenu builds its rows from app-db directly) |
| `:kbedit/open?` **[input]** | `app-db` | bool — the key-binding editor dialog is open (§1.27) |
| `:kbedit/sel` **[input]** | `app-db` | the id of the set being edited (the editor's focus — not the active set) |
| `:kbedit/editing` **[input]** | `app-db` | the id of the custom set being renamed in place \| nil |
| `:kbedit/capture` **[input]** | `app-db` | the in-progress key capture `{:set-id :mode :action :chords}` \| nil |
| `:kbedit/ctx` **[input]** | `app-db` | the editor's right-click menu `{:x :y :id}` \| nil |
| `:kbedit/can-undo?` **[input]** | `app-db` | bool — `[:ui :kbedit :undo]` is non-empty |
| `:kbedit/can-redo?` **[input]** | `app-db` | bool — `[:ui :kbedit :redo]` is non-empty |
| `:kbedit/sets` **[input]** | `app-db` | the editor's set list — the three built-ins, then the custom sets in order — each `{:id :name :builtin? :custom? :active? :focused?}` |
| `:kbedit/focused` **[input]** | `app-db` | the selected set's descriptor `{:id :name :builtin? :custom? :modal? :default-mode}` — `:default-mode` is `:normal` for a modal set, else `:insert` (where new bindings land) |
| `:kbedit/action-index` **[input]** | `app-db` | the selected set's **effective** bindings reverse-indexed by action, `{action-id [[mode [chord …]] …]}` (`registry/action-index` over `keymap/merge-user`) — the binding chips; nil with no selection |

### 3.2 Tab/document derived subscriptions

| Sub | Inputs | Output |
| --- | --- | --- |
| `:tabs` | `:<- [:ui/tabs]` | app-db tab vector |
| `:tree/filtered` | `:<- [:ui/projects]` `:<- [:ui/tree-filter]` `:<- [:ui/settings]` | `[{:root :files :nodes :filtered? :extras?}]` — each project narrowed by the filter (the match mode `vinary.search.config/mode-for :tree` reads from the settings; projects with nothing left are omitted) and already folded into its nested shape; ADR-0038 `:extras` (attached diffs) filter by their display `:name` — they have no root-relative path — and keep the project alive on their own. **Layered**: folding every path of every project inside the view's render function is what let a re-render land between two keystrokes (ADR-0033) |
| `::palette-ctx` **[input]** | `:<- [:ui/tabs]` `:<- [:ui/active-tab-id]` `:<- [:ui/active-path]` `:<- [:history/can-back?]` `:<- [:history/can-forward?]` `:<- [:ui/find]` | the command-resolution context `{:tabs :active-tab :active-path :can-back? :can-forward? :find-visible? :in-input? true}` the palette's `:when` predicates run against (`:in-input?` is forced true — the query box holds focus). It reads `:ui/find`, which changes per find keystroke, but its own value changes only when `:visible?` flips, so `:palette/candidates` is not disturbed |
| `:palette/candidates` | `:<- [:palette/state]` `:<- [:ui/projects]` `:<- [:ui/settings]` `:<- [::palette-ctx]` | `[{:item :score :spans}]`, empty while the palette is closed. By `:source`: `:file` ranks every open project's files, `:theme` the themes, anything else the commands `commands/all-visible` allows in `::palette-ctx`; match mode from `vinary.search.config/mode-for :palette`. Ranks raw strings and materialises an item only for survivors, so the cap bounds allocation rather than only the display |
| `::facet-inputs` | `app-db` | `{:facet :primary :mru :pref}` — the active tab's stored `:facet`, its primary local path, its `:facet-mru`, and `[:ui :settings :collocated-default]` (default `:pdf`): four cheap map lookups whose *result* is what re-frame compares, so app-db churn stops here before reaching the DataScript-backed facet derivations |
| `:doc/group` | `:<- [:ds/rev]` `:<- [:ui/active-path]` | the active document's collocated group `[{:path :kind} …]` (`facet/group-of`): the PRIMARY (opened) doc's `:doc/siblings`, else a synthesized one-member group for a group-kind doc, else nil — read off the primary so the toolbar stays stable whichever facet is shown |
| `:facet/active` | `:<- [::facet-inputs]` `:<- [:doc/group]` | the active tab's effective facet `{:path :type}`. **Layered** — as a plain db-sub it ran a `d/q` plus a 22-attribute `d/pull` on every app-db write, and it is permanently subscribed |
| `:ui/active-content-path` | `:<- [:facet/active]` `:<- [:ui/active-path]` `:<- [:ui/active-uri]` | the path of the file the active tab is SHOWING — the facet's `:path`, else the primary path, else the raw active URI (so a PDF keyed by an http(s) URL, opened from a web-view link, still resolves). The pane, Contents, find and zoom operate on it |
| `:facet/type` | `:<- [:facet/active]` | the shown facet's type, `:preview` \| `:source` |
| `:view/switch` | `:<- [::facet-inputs]` `:<- [:doc/group]` | the `[Preview ▾ \| Source ▾]` toolbar model (`facet/view-model`: `:show?`, `:active-type`, `:active-path`, and per button its `:mode` — `:hidden` / `:plain` / `:combo` — `:main-path` and `:options`). Layered for the same reason as `:facet/active` |
| `:doc/active` | `:<- [:ds/rev]` `:<- [:ui/active-content-path]` | `ds/active-doc` → the pulled doc entity of the **shown** file \| nil. **The `d/pull` vector in `vinary.app.ds/active-doc` is authoritative:** `:doc/path :doc/kind :doc/language :doc/base-dir :doc/text :doc/html :doc/toc :doc/source-toc :doc/assets :doc/entries :doc/error :doc/stamp :doc/sheets :doc/page :doc/paged? :doc/meta :doc/sourceable? :doc/data-url :doc/reflow-html :doc/siblings :doc/diff-split-html :doc/diff-targets :doc/streaming? :doc/stream-progress :doc/stream-note :doc/git-root :doc/git :doc/refresh-note :doc/deck`. A **new `:doc/*` attribute is invisible to views until it is added there.** |
| `:doc/kind` | `:<- [:doc/active]` | the active document's kind — selects the `content-view` Strategy |
| `:doc/git` | `:<- [:doc/active]` | the active document's commit-diff facts `{:root :from :to :range?}` — nil unless the shown doc is a git-produced diff (kind-guarded so a stale attr can never leak). Feeds `:commits/open-set` ([ADR-0042](../design-decisions/0042-derived-open-commit-highlight.md)) |
| `:doc/toc` | `:<- [:ui/active-uri]` `:<- [:doc/active]` `:<- [:ui/web-toc]` `:<- [:ui/active-view-source?]` | the Contents outline for the active view (`subs/active-toc`): an http(s) page → the web view's headings (`:ui/web-toc`); the **Source** view with a source outline present → `:doc/source-toc` (`L<line>` ids for CodeMirror navigation, from `:toc/set`); otherwise the preview outline `:doc/toc` (slug ids — Markdown/Org/LaTeX/office headings, a diff's per-file list, a PDF outline, a streamed log's error/warning records). Keeping the two id-spaces in separate attrs means neither view's Contents clobbers the other's |
| `:doc/streaming?` | `:<- [:doc/active]` | bool — the active document renders **incrementally** as a stream (§1.28) rather than whole |
| `:doc/stream-progress` | `:<- [:doc/active]` | the stream's completion, a fraction in $`[0,1]`$ (nil until the first batch) — the progress strip |
| `:doc/stream-note` | `:<- [:doc/active]` | a non-fatal note for the progress strip (e.g. a remote SSH connection dropped mid-stream — `:stream/interrupted`) \| nil |
| `::slides-inputs` | `app-db` | `{:explicit :open-as :presenting?}` — the active document view's explicit PDF layout (`nav/pdf-layout`), the `:slides-open-as` preference (default `:slides`), and whether a slide show is presenting (ADR-0045) |
| `:pdf/layout-request` | `:<- [::slides-inputs]` | the layout request the pdf-view merges into the engine's view-state (the engine combines it with the document's own deck classification) |
| `:slides/view?` | `:<- [:view/pdf-active?]` `:<- [::slides-inputs]` `:<- [:doc/deck]` | bool — the shown PDF is in the slides layout (`vinary.slides.deck/effective-layout`, the engine's own rule) |
| `:slides/state` | `app-db` | `[:ui :slides]` — the mounted slide show's position mirror (`:slides/changed`) \| nil |
| `:slides/digits` | `app-db` | `[:ui :slides :digits]` — the slide number being typed (`"12"`) \| nil; the "Go to 12" chip (`ui/slides-hud`) |
| `:slides/presentation` | `app-db` | `[:ui :presentation]` — the running slide show `{:active? :tab-id :path :from :fullscreen?}` \| nil |
| `:slides/presenting?` | `:<- [:slides/presentation]` | bool — a show is running in this window (`.vv-root.vv-presenting`; the palette's `:presenting?`) |
| `:slides/blank` | `app-db` | `[:ui :slides :blank]` — `:black` / `:white` while the running show is blanked \| nil (the blank overlay, `ui/slides-hud`) |
| `:slides/overview?` | `app-db` | bool — `[:ui :slides :overview?]`, the overview grid is open (`ui/slides-overview`) |
| `:slides/doc-gen` | `app-db` | `[:ui :slides :doc-gen]` — the mounted slides view's document generation; a live refresh bumps it and the open overview re-borrows the document (`pdf-cache/slides-snapshot`) |
| `:slides/overview-page` | `app-db` | `slides/overview-page` — which page of a frame its overview tile shows and opens: `:last` (default — fully built) \| `:first` (the `:slides-overview-page` preference) |
| `:pdf/invert?` | `app-db` | bool — `[:ui :pdf :invert?]`, the dark-invert of PDF pages; the overview grid mirrors it on its thumbnails |
| `:doc/deck` | `:<- [:doc/active]` | the shown PDF's slide-deck classification `{:deck? :tier :source :pages :frame-count}` \| nil (ADR-0045) |
| `:doc/refresh-note` | `:<- [:doc/active]` | the shown document's non-fatal live-refresh note `{:kind :message :stamp :at}` \| nil — rendered by `ui/refresh-note` over the last good version (§1.31) |

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
| Tabs | `:tab/next`, `:tab/prev`, `:tab/close` (→ `[:tab/close-active]`), `:tab/new-blank` |
| File | `:file/open` (prompt), `:file/open-in-new-tab` (prompt), `:file/open-dialog`, `:file/open-dialog-new-tab`, `:file/reveal-in-tree` (→ `[:sidebar/reveal]`), `:doc/reload` (→ `[:tab/reload]`) |
| Navigation | `:history/back`, `:history/forward`, `:nav/parent`, `:nav/open-target`, `:nav/scroll-down`, `:nav/scroll-up`, `:nav/scroll-left`, `:nav/scroll-right`, `:nav/page-down`, `:nav/page-up`, `:nav/half-page-down`, `:nav/half-page-up`, `:nav/scroll-top`, `:nav/scroll-bottom`, `:hint/start`, `:focus/uri`, `:focus/sidebar`, `:focus/content`, `:focus/toggle`, `:tree/down`, `:tree/up`, `:tree/open`, `:jump/to-source` (→ `[:jump/goto-source]`), `:jump/to-preview` (→ `[:jump/goto-preview]`) |
| Search | `:search/start`, `:search/next`, `:search/prev`, `:search/close`, `:palette/open`, `:palette/files` |
| View | `:sidebar/toggle`, `:view/toggle-source` (→ `[:tab/toggle-source]`), `:view/toggle-diff-split` (→ `[:tab/toggle-diff-view]`), `:view/diff-collapse-all`, `:view/diff-expand-all`, `:theme/cycle`, `:theme/pick` (prompt), `:view/zoom-in`, `:view/zoom-out`, `:view/zoom-reset`, `:view/devtools`, `:view/re-frame-10x`, `:view/toggle-slides` (→ `[:pdf/layout-toggle]`, `:when :pdf-view?`) |
| Slides (ADR-0045) | `:slides/next`, `:slides/prev`, `:slides/next-frame`, `:slides/prev-frame`, `:slides/first`, `:slides/last`, `:slides/back`, `:slides/forward` (each → `[:slides/command op]`, `:when :slides-view?` — palette-visible only with a PDF in the slides layout); `:slides/digit` (→ `[:slides/digit d]` via the binding's `:args [d]`), `:slides/enter`, `:slides/backspace` (`:when :slides-active?` — keyboard-only: only the resolver's context sets it, so the palette never offers them) |
| Git | `:git/blame-toggle` (→ `[:blame/toggle]`), `:git/open-graph` (→ `[:git-graph/open]`), `:git/file-history`, `:git/line-history` (→ `[:git/line-history-from-selection]`) |
| Settings · Web · Help | `:settings/open`, `:passwords/open`, `:about/open` |
| Editor | `:kbedit/undo`, `:kbedit/redo` (the key-binding editor's undo/redo, §1.27) |
| Mode (vim) | `:mode/normal`, `:mode/insert`, `:mode/visual`, `:mode/ex`, `:input/escape` |

> A command's `:dispatch` may carry an `:arg` (appended) or be called with resolver `:args`; a
> `:prompt` opens the palette in a given source mode (`:file`, `:theme`). `run` still supports a
> `:handler` (a function of the context that returns the event to dispatch), but no bundled command uses
> one any more — `:tab/close` dispatches `[:tab/close-active]`, which resolves the active tab itself.

### 4.2 The resolver (Interpreter)

`vinary.input.resolver/step` is the pure core: given the active keymap's `modes`, the current `mode`,
the pending `sequence`, the incoming `token`, and the `ctx`, it returns a decision —
`:dispatch` (leaf command), `:prefix` (a longer chord exists), `:consume` (vim swallows a stray
normal-mode key), `:pass` (let it reach inputs/browser), or `:retry` (restart resolution at length 0).
`handle` applies the decision and `preventDefault`s as appropriate; it never hijacks a bare printable
key while a text input is focused in non-modal/insert mode. The pending sequence + chord timer are
held in resolver-**local atoms** (synchronous, authoritative) and mirrored into `app-db` only for the
mode-line display.

**Layers (ADR-0045).** The root trie `step` walks is a shallow merge, lowest first, of `:all`, then the
**`:slides` layer** — only while the context says `:slides-active?` — then the current mode. The slides layer
therefore sits *below* the mode: a mode's own binding of the same key wins (Vim's `SPC` leader, `n`), and the
shallow merge never replaces a mode's prefix map wholesale. `build-ctx` derives `:slides-active?` from the live
DOM on every keydown (ADR-0032 — never cached): the PDF engine reports a mounted slides layout
(`pdf-cache/slides-active?`), focus is on `<body>` or inside `.vv-content` (not the sidebar, the URI bar, a
button or an overlay's own focused surface), no text field has focus, and `vinary.app.overlay/blocking?` is
false (menubar menu, context menu, link hints, palette, SSH prompt, the modal dialogs). It also adds
`:pdf-view?` and `:slides-view?` (the palette's contexts carry those two as well).

**Scroll commands in the slides layout.** Arrows, PgUp/PgDn (with `:shift?` from `key-scroll!` for a frame
step), Home/End, Vim `j/k/h/l/C-d/C-u/C-f/C-b/gg/G`, Emacs `C-v/M-v/C-n/C-p` and the palette's scroll commands
all reach `:dom/scroll` as the same opts map. `:dom/scroll` first offers it to the **scroll delegate**
(`vinary.renderer.scroll/delegate!`) when the content pane is the target; the slides layout registers one that
maps the opts through `deck/scroll->op` and dispatches `[:slides/command op]`, and always claims the command, so
the smooth-scroll animator never starts a chase that the slide snap would fight.

### 4.3 Keymaps (`vinary.input.keymap` + `vinary.input.keymaps-registry`)

**Bundled presets.** Three presets — **`:default`** (non-modal; generalizes the original `C-f`/`M-←→`),
**`:vim`** (modal `normal`/`insert`/`visual`, leader `SPC`), **`:emacs`** (non-modal, `C-x` prefix maps,
`M-x` palette) — are authored as EDN in `resources/keymaps/default.edn`, `vim.edn` and `emacs.edn`. They are
**inlined at compile time** by `vinary.input.keymap/bundled` with `shadow.resource/inline` (`rc/inline`), and
the inlined strings are parsed with `cljs.reader/read-string` when the namespace loads. `rc/inline` also
registers each EDN file as a compile dependency, so editing a preset triggers a recompile. (This superseded the
`vinary.input.presets/bundled` compile-time slurp macro, whose plain slurp did not track the files and left
the bundled keymaps stale until a cache-clearing rebuild; `src/vinary/input/presets.clj` remains only for
reference and is no longer required.) Each preset is `{:name :initial-mode :timeout-ms :leader :modes}`,
where `:modes` maps a mode (`:all`, `:normal`, `:insert`, …) to a trie of chord tokens — a nested map is a
key sequence (prefix) — whose leaves are command ids from §4.1, either a bare keyword or a gated
`{:id … :when …}`.

**User sets and the merge.** A custom set is a *delta* over the preset it extends:
`{:extends :vim :initial-mode … :timeout-ms … :leader … :keymaps {<mode> {<chord> <command-or-subtrie>}}}`.
`keymap/merge-user` (pure, nothing installed) resolves it: take the `:extends` preset (default `:default`),
override `:initial-mode` / `:timeout-ms` / `:leader` when the delta sets them, **deep-merge the user's
`:keymaps` into the preset's `:modes`, per mode** (a nil delta value keeps the preset's binding), strip every
`:unbind` leaf — so `:unbind` removes an inherited binding — and normalize the authored key tokens (`SPC` →
the space token, …) so they compare equal to runtime tokens.

**The registry of sets** lives in `vinary.input.keymaps-registry` over the app-db slice
`[:ui :keymaps] {:active :order :sets}`: the three read-only built-ins (`"default"` = Standard Mode, `"vim"`,
`"emacs"`) plus the user's named custom sets (`:sets` name → delta, `:order` their display order), persisted
to `~/.config/vinary-viewer/keybindings.edn` as that envelope (`registry/normalize-config` wraps a legacy
single `{:extends :keymaps}` delta as one custom set named `"Custom"`). `registry/install-for!` installs a
built-in through `keymap/install!` and a custom set through `keymap/install-user!` into the **one live keymap
atom** the resolver reads; modal and pending-sequence state lives in `app-db`. Selecting a set is
`:keymap/select` (§1.11); editing sets is the key-binding editor (§1.27).

### 4.4 Current status

| Piece | Status |
| --- | --- |
| Command registry, resolver, `step`, `install!` | **Available** — installed by `renderer.core/keybindings!` → `resolver/install!` |
| Bundled `:default`/`:vim`/`:emacs` presets | **Available** — inlined at compile time (`shadow.resource/inline`); switch at runtime with Settings ▸ Key Bindings or `window.__vvkeymap("vim")` (both `:keymap/select`) |
| Named custom keymap sets + the visual key-binding editor | **Available** — `vinary.input.keymaps-registry` + `vinary.ui.keybindings-editor` (Settings ▸ Key Bindings ▸ Customize…, §1.27), undoable through `vinary.input.kbedit-history` |
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
