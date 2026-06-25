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
| `:content/received` | fx | `{:path :kind (:text)}` | DataScript snapshot (`eid-for-path`, `doc-attr`, `order-for-path`, `next-order`) | `:ui :active-path`, `:ui :history` (via `nav-to`) | `[:ds/transact tx]` (upsert `:doc/*`, retract stale `:doc/error`); markdown→`[:markdown/render …]`; text→`[:ds/transact {:doc/html (plain-html)}]` |
| `:content/rendered` | fx | `path html` | — | — | `[:ds/transact [{:doc/path path :doc/html html}]]` |
| `:content/error` | fx | `{:path :message}` | — | — | when `path`: `[:ds/transact [{:doc/path path :doc/error message}]]`; else `{}` |

### 1.3 Documents & tabs

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:doc/open` | fx | `path` | — | — | `[:vv/open path]` |
| `:doc/open-in-tab` **[input]** | fx | `path _new?` | — | — | `[:vv/open path]` (per-path-tab model: new vs replace both reduce to open) |
| `:tab/activate` | db | `path` | `:ui :history` | `:ui :active-path`, `:ui :history` (`nav-to`) | — |
| `:tab/close` | fx | `path` | snapshot (`eid-for-path`, `open-docs`), `:ui :active-path` | `:ui :active-path` ← `new-active` | when eid: `[:ds/transact [[:db/retractEntity eid]]]`; always `[:vv/close path]` |
| `:tab/next` **[input]** | db | — | snapshot `open-docs`, `:ui :active-path` | `:ui :active-path`, `:ui :history` (`nav-to` to next tab, wrapping) | — |
| `:tab/prev` **[input]** | db | — | snapshot `open-docs`, `:ui :active-path` | `:ui :active-path`, `:ui :history` (`nav-to` to previous tab, wrapping) | — |

### 1.4 Navigation history

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:history/back` | db | — | `:ui :history` | `:ui :history :idx` ← dec, `:ui :active-path` ← `stack[idx-1]` (when `idx>0`) | — |
| `:history/forward` | db | — | `:ui :history` | `:ui :history :idx` ← inc, `:ui :active-path` ← `stack[idx+1]` (when `idx<count-1`) | — |

### 1.5 Theme

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:theme/set` | fx | `theme` | — | `:ui :theme` ← theme | `[:theme/apply theme]` |
| `:theme/cycle` **[input]** | fx | — | `:ui :theme` | — | `[:dispatch [:theme/set <next-in-cycle>]]` (cycles `["spacemacs-dark" "spacemacs-light"]`) |

### 1.6 Git file-tree

| Event | Kind | Payload | Reads | Writes | Effects |
| --- | --- | --- | --- | --- | --- |
| `:tree/received` | db | `{:root :files}` | — | `:ui :tree` ← `{:root :files(vec)}` | — |
| `:tree/filter` | db | `q` | — | `:ui :tree-filter` ← q | — |
| `:tree/move` **[input]** | db | `dir` | `:ui :tree`, `:ui :tree-filter`, `:ui :tree-selected` | `:ui :tree-selected` ← next visible path (wrapping over the **filtered** list) | — |
| `:tree/activate` **[input]** | fx | — | `:ui :tree-selected` | — | when selected: `[:vv/open sel]` |

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
| `:nav/scroll` | fx | `opts` (`{:dy …}` / `{:to …}`) | — | — | `[:dom/scroll opts]` |

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
| `:keymap/config-received` | db | `cfg` | — | installs the keymap (`keymap/install-user!` if `cfg` non-empty, else bundled `:default`); `:ui :input :mode` ← `keymap/initial-mode` | — |

> Triggered by the renderer's `onKeymap` IPC handler and by the `window.__vvkeymap "vim"` dev hook.
> See [§4](#4-the-input--command-layer-available--in-progress) and
> [ipc-channels.md `vv:keymap`](./ipc-channels.md#2-channel-catalog).

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

---

## 2. Effects

`reg-fx` handlers receive the effect's argument and perform IO/async, often re-dispatching back into
the loop. They are the **only** place side effects happen (effects-at-the-edge).

### 2.1 `vinary.app.fx`

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:ds/transact` | `tx` (tx-data vector) | `d/transact! ds/conn tx` (the sole DataScript write path) | — (the conn listener dispatches `[:ds/changed]`) |
| `:markdown/render` | `{:text :path :on-done}` | `md/render text` (unified pipeline → `Promise`) | `.then` → `(conj on-done html)`; `.catch` → `[:content/error {:path :message "render error: …"}]` |
| `:theme/apply` | `theme` (string) | `set! (.-href #vv-theme-link) "css/themes/<theme>.css"` | — |
| `:find/run` | `q` | `finder/search! q` | `[:find/count <count>]` |
| `:find/cycle` | `dir` (+1/-1) | `finder/cycle! dir` | `[:find/idx <new-1-based-idx>]` |
| `:find/clear` | `_` | `finder/clear!` (delete both highlights, reset state) | — |
| `:toc/scroll` | `id` | `getElementById id` → `scrollIntoView {block:"start" behavior:"smooth"}` | — |
| `:vv/open` | `path` | `window.vv.open(path)` → `vv:open` IPC (guarded on `window.vv`) | — |
| `:vv/close` | `path` | `window.vv.close(path)` → `vv:close` IPC (guarded) | — |

### 2.2 `vinary.input.fx` **[input]**

| Effect | Arg | Side effect | Re-dispatch |
| --- | --- | --- | --- |
| `:input/arm-timeout` | `ms` | `setTimeout #(dispatch [:input/timeout]) ms` | `[:input/set-timeout-id id]` |
| `:input/cancel-timeout` | `id` | `clearTimeout id` (when id) | — |
| `:dom/scroll` | `{:dy :to}` | scroll `.vv-content`: `:to :top`/`:bottom` → `scrollTo`; `:dy :page`/`:-page` (±0.9·clientH), `:half`/`:-half` (±0.5·clientH), or a number → `scrollBy` | — |
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
| `:ui/active-path` | `app-db` | active tab path \| nil |
| `:ui/theme` | `app-db` | theme name string |
| `:ui/tree` | `app-db` | `{:root :files}` \| nil |
| `:ui/tree-filter` | `app-db` | filter query string \| nil |
| `:ui/find` | `app-db` | `{:visible? :query :count :idx}` |
| `:ui/active-heading` | `app-db` | active heading id \| nil |
| `:ui/sidebar-visible?` **[input]** | `app-db` | bool |
| `:ui/tree-selected` **[input]** | `app-db` | selected tree path \| nil |
| `:input/mode` **[input]** | `app-db` | `:normal`/`:insert`/`:visual` |
| `:input/pending` **[input]** | `app-db` | the pending key-sequence vector (`:ui :input :sequence`) |
| `:input/in-input?` **[input]** | `app-db` | bool (focus is in a text input) |
| `:palette/state` **[input]** | `app-db` | `{:open? :source :prefix :query :items :selected}` |
| `:history/can-back?` | `app-db` | `(and idx (pos? idx))` → bool |
| `:history/can-forward?` | `app-db` | `(and idx (< idx (dec (count stack))))` → bool |

### 3.2 DataScript (layer-3) subscriptions

| Sub | Inputs | Output |
| --- | --- | --- |
| `:tabs` | `:<- [:ds/rev]` | `ds/open-docs` → `[{:path :order :kind}]` ordered |
| `:doc/active` | `:<- [:ds/rev]` `:<- [:ui/active-path]` | `ds/active-doc` → `{:doc/path :doc/kind :doc/html :doc/error}` \| nil |
| `:doc/toc` | `:<- [:doc/active]` | `toc/extract (:doc/html)` → `[{:level :text :id}]` |

> `:doc/toc` depends on `:doc/active` (not `app-db`), so the `DOMParser` heading extraction runs only
> when the active document's HTML changes — re-frame de-duplicates the rest.

---

## 4. The input / command layer (Available + in-progress)

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
(forthcoming) palette. Predicates: `:always`, `:has-tabs`, `:can-back?`, `:can-forward?`,
`:find-visible?`, `:palette-open?`, `:not-in-input?`.

| Category | Command ids |
| --- | --- |
| Tabs | `:tab/next`, `:tab/prev`, `:tab/close` |
| File | `:file/open`, `:file/open-in-new-tab`, `:file/reveal-in-tree` |
| Navigation | `:history/back`, `:history/forward`, `:nav/scroll-down`, `:nav/scroll-up`, `:nav/page-down`, `:nav/page-up`, `:nav/half-page-down`, `:nav/half-page-up`, `:nav/scroll-top`, `:nav/scroll-bottom`, `:focus/sidebar`, `:focus/content`, `:focus/toggle`, `:tree/down`, `:tree/up`, `:tree/open` |
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

### 4.4 What is wired vs pending

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
