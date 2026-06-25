# 06 · Renderer Runtime

> **Scope.** What actually happens *inside* the renderer process: the `vinary.renderer.core/init`
> boot sequence, the reagent root, the **imperative `innerHTML`** content-body lifecycle (a reagent
> form-3 component), the **subscription graph**, and the **devtools**. Read
> [04 · State Schema](./04-state-schema-reference.md) for the stores and
> [05 · Data Flows](./05-data-flows.md) for the per-action traces.

---

## 1. The boot sequence (`init`)

The renderer entry point is `vinary.renderer.core/init` (the `:renderer` build's `:init-fn`). It runs
exactly these steps, in order:

```clojure
;; vinary.renderer.core
(defn ^:export init []
  (rf/dispatch-sync [:db/init])                                  ; 1
  (ds/install-bridge!)                                           ; 2
  (set! (.-__vvdb js/window) (fn [] (clj->js @rfdb/app-db)))     ; 3  DEV inspect hooks
  (set! (.-__vvds js/window) (fn [] (clj->js (ds/open-docs (ds/snapshot)))))
  (set! (.-__vvkeymap js/window)                                 ;    DEV: switch preset at runtime
        (fn [nm] (rf/dispatch [:keymap/config-received {:extends (keyword nm)}])))
  (bridge!)                                                      ; 4
  (keybindings!)                                                 ; 5
  (mount!))                                                      ; 6
```

The namespace requires pull in the loop's registrations plus the input layer:
`vinary.app.events`, `vinary.app.subs`, `vinary.app.commands`, `vinary.input.events`,
`vinary.input.resolver`, and `vinary.ui.views`.

| Step | Call | Why it is here / why this order |
| --- | --- | --- |
| 1 | `dispatch-sync [:db/init]` | Install `default-db` **synchronously** so `app-db` is populated *before* anything subscribes or any content arrives. (`dispatch-sync`, not `dispatch`, to avoid a race with step 4.) |
| 2 | `ds/install-bridge!` | Attach the `d/listen!` on the DataScript conn so future transactions dispatch `[:ds/changed]` (the `:ds/rev` bridge). Done before `bridge!` so the very first `:content/received` transaction is observed. |
| 3 | `__vvdb` / `__vvds` / `__vvkeymap` window hooks | DEV inspection: `window.__vvdb()` returns `app-db`, `window.__vvds()` returns the open docs; `window.__vvkeymap("vim")` swaps the active keymap preset (dispatches `[:keymap/config-received {:extends :vim}]`). |
| 4 | `bridge!` | Wire `window.vv.onContent/onError/onTree` → re-frame dispatch; also subscribe `onKeymap` and `requestKeymap` **if** the preload exposes them (guarded). After this, pushed content flows into the loop. |
| 5 | `keybindings!` | `resolver/install!` — install the keymap **resolver** (the global `keydown` handler that walks the active keymap's trie; replaces the old hand-rolled Ctrl+F/Alt listener). |
| 6 | `mount!` | Create the reagent root (once) and render `[views/root]`. |

`reload` (the `:devtools :after-load` hook) is just `(mount!)` — a re-render that reuses the existing
root, `app-db`, and conn, so hot reload preserves state (see
[02 §6](./02-process-and-build-topology.md#6-the-development-hot-reload-loop)). The resolver guards
its own install (`installed` atom), so a hot reload does not double-register the `keydown` listener.

```text
init
 ├─1 dispatch-sync [:db/init] ─────────▶ app-db = default-db
 ├─2 ds/install-bridge! ───────────────▶ conn listener → [:ds/changed] → :ds/rev++
 ├─3 window.__vvdb / __vvds / __vvkeymap ─▶ dev console hooks
 ├─4 bridge! ──────────────────────────▶ window.vv.on{Content,Error,Tree[,Keymap]} → rf/dispatch
 ├─5 keybindings! = resolver/install! ──▶ window keydown → keymap trie-walk → command registry
 └─6 mount! ───────────────────────────▶ rdomc/create-root(#app) → render [views/root]
```

---

## 2. The reagent root

```clojure
;; vinary.renderer.core
(defonce root (atom nil))

(defn mount! []
  (when (nil? @root)
    (reset! root (rdomc/create-root (.getElementById js/document "app"))))
  (rdomc/render @root [views/root]))
```

- Uses **`reagent.dom.client`** (`rdomc`) — the React 18/19 `createRoot` API.
- The root is created **once** (`defonce` atom + `nil?` guard), so repeated `mount!` calls (hot
  reload) re-render into the same root instead of leaking roots.
- `[views/root]` is the app shell. `ui.views/root` lays out: the **file-tree** (left), a **pane**
  (toolbar + tab-strip + content-view + find-bar), and the **toc-panel** (right):

```clojure
;; vinary.ui.views/root
[:div.vv-app
 [tree/file-tree]
 [:div.vv-pane [toolbar] [tabs/tab-strip] [content-view] [find-bar]]
 [toc-panel]]
```

---

## 3. The imperative `innerHTML` body (form-3 lifecycle)

The rendered Markdown body is **not** VDOM-diffed. It is a reagent **form-3** component
(`r/create-class`) that writes the HTML string straight into a DOM node via a ref atom, on mount and
on every update.

```clojure
;; vinary.ui.views
(defn- set-inner! [^js node html]
  (when node (set! (.-innerHTML node) (or html ""))))

(defn markdown-body
  [_html]
  (let [node (atom nil)]
    (r/create-class
     {:display-name "vv-markdown-body"
      :component-did-mount  (fn [this] (set-inner! @node (second (r/argv this))))
      :component-did-update (fn [this] (set-inner! @node (second (r/argv this))))
      :reagent-render       (fn [_html] [:div.markdown-body {:ref (fn [el] (reset! node el))}])})))
```

| Aspect | Behaviour | Rationale |
| --- | --- | --- |
| Render | `:reagent-render` emits an empty `.markdown-body` with a `:ref` that captures the DOM node into `node`. | The component owns a *container*; React never sees the rendered children. |
| Mount | `component-did-mount` → `set-inner!` with the current HTML (`(second (r/argv this))` = the `html` arg). | First paint. |
| Update | `component-did-update` → `set-inner!` again. | Live-refresh: new HTML replaces the body wholesale. |
| Diffing | **None** — `innerHTML` is set directly. | The HTML comes from the trusted unified pipeline (no `rehype-raw`); diffing a large rendered document through React would be wasteful, and direct `innerHTML` composes with the CSS Custom Highlight find (which paints `Range`s over these nodes without altering them). |

> **Who holds the subscription?** `content-view` subscribes to `:doc/active` and passes
> `(:doc/html doc)` *into* `markdown-body` as an argument. So the subscription/observer lives in
> `content-view`; `markdown-body` is a dumb sink that mirrors its argument into `innerHTML`. The
> `content-view` strategy (precedence: watermark → error → image → html → "Rendering…") is detailed in
> [05 §9](./05-data-flows.md#9-error-arrival--retraction).

---

## 4. The subscription graph

Subscriptions form the **Observer** layer. Two families: **UI subs** read `app-db` directly;
**document subs** read the DataScript conn and are gated on `:<- [:ds/rev]` so they recompute per
transaction.

```text
                      app-db                                  DataScript conn
                        │                                            │
   ┌────────────────────┼─────────────────────────┐                 │
   │ :ds/rev            :ui/active-path  :ui/theme │                 │
   │ :ui/tree  :ui/tree-filter  :ui/find           │                 │
   │ :ui/active-heading                            │                 │
   │ :history/can-back?  :history/can-forward?     │                 │
   └───────┬──────────────────┬────────────────────┘                 │
           │                  │                                       │
           │ :<- [:ds/rev]    │ :<- [:ds/rev] :<- [:ui/active-path]   │
           ▼                  ▼                                       ▼
        ┌──────┐         ┌────────────┐  reads snapshot   ┌────────────────────┐
        │:tabs │◀────────│:doc/active │◀──────────────────│ ds/open-docs /     │
        └──┬───┘         └─────┬──────┘                    │ ds/active-doc      │
           │                   │ :<- [:doc/active]         └────────────────────┘
           │                   ▼
           │              ┌──────────┐
           │              │ :doc/toc │ → toc/extract(:doc/html)
           │              └──────────┘
           ▼                   ▼
        tab-strip          content-view / toc-panel
```

| Subscription | Inputs | Output | Consumed by |
| --- | --- | --- | --- |
| `:ds/rev` | `app-db` | the revision int | the gated subs below |
| `:ui/active-path` | `app-db` | active tab path | `tab-strip`, `file-tree`, `:doc/active` |
| `:ui/theme` | `app-db` | theme name | `toolbar` `<select>` |
| `:ui/tree` | `app-db` | `{:root :files}` | `file-tree` |
| `:ui/tree-filter` | `app-db` | filter string | `file-tree` |
| `:ui/find` | `app-db` | `{:visible? :query :count :idx}` | `find-bar` |
| `:ui/active-heading` | `app-db` | active heading id | `toc-panel` |
| `:history/can-back?` | `app-db` | bool | `toolbar` ← button |
| `:history/can-forward?` | `app-db` | bool | `toolbar` → button |
| `:tabs` | `:<- [:ds/rev]` | ordered open docs | `tab-strip`, `content-view` |
| `:doc/active` | `:<- [:ds/rev]` `:<- [:ui/active-path]` | the active doc (pull) | `content-view`, `:doc/toc` |
| `:doc/toc` | `:<- [:doc/active]` | `[{:level :text :id}]` | `toc-panel` |

The full per-sub reference (with `reg-sub` forms) is in
[reference/events-effects-subs.md §3](../reference/events-effects-subs.md#3-subscriptions).

### 4.1 Layered (`materialised`) subscriptions

`:doc/toc` is a **layer-3** subscription: it depends on another subscription (`:<- [:doc/active]`),
not on `app-db`, so it only recomputes when the active document actually changes — and `toc/extract`
(a `DOMParser` parse of the HTML) runs only then, not on every keystroke or scroll. This is the
re-frame "signal graph" doing de-duplication for free.

---

## 5. Scroll-spy wiring (a non-subscription reactive path)

The TOC's active-heading highlight is driven imperatively, not by a subscription, because it depends
on **scroll geometry** rather than store state:

1. `content-view` puts `:on-scroll (fn [e] (toc/spy! (.-currentTarget e)))` on `.vv-content`.
2. `toc/spy!` is **rAF-throttled** (a `spy-pending` atom): on each animation frame it finds the last
   heading whose `getBoundingClientRect` top is ≤ 100px below the content top and dispatches
   `[:toc/active-heading id]`.
3. `:toc/active-heading` writes `(:ui :active-heading)`; `:ui/active-heading` → `toc-panel` adds
   `vv-toc-active` to the matching item.
4. Clicking a TOC item → `[:toc/goto id]` → `:toc/scroll` fx → `getElementById` +
   `scrollIntoView {block:"start" behavior:"smooth"}`.

> The heading `id`s come from `rehype-slug` (added during rendering), so the TOC, the scroll-spy, and
> the `:toc/scroll` target all agree on the same anchors.

---

## 6. Devtools

The `:renderer` build's `:devtools :preloads` install three inspectors (dev builds only):

| Tool | Package | What it gives you |
| --- | --- | --- |
| **re-frame-10x** | `day8.re-frame-10x.preload.react-18` | Event log, app-db diffs, subscription graph, time-travel. Requires `re_frame.trace.trace_enabled? = true` (set via `:closure-defines`). |
| **re-frisk** | `re-frisk.preload` | A live `app-db` tree inspector. |
| **binaryage/devtools** | `devtools.preload` | Chrome DevTools custom formatters so cljs values print readably in the console. |

Plus the two hand-rolled console hooks from `init`: `window.__vvdb()` (app-db) and `window.__vvds()`
(open docs). Together these make the renderer's entire state observable at runtime, which is the
practical pay-off of the [thin-main/smart-renderer](./01-overview.md#4-the-thesis-thin-main-smart-renderer)
+ effects-at-the-edge design.

---

## 7. See also

- [`component-renderer.puml`](../diagrams/component-renderer.puml) — the renderer component graph.
- [04 · State Schema](./04-state-schema-reference.md) — the stores `init` wires together.
- [05 · Data Flows](./05-data-flows.md) — the per-action traces through this runtime.
- [reference/namespaces.md](../reference/namespaces.md) — `renderer.*`, `ui.*`, `app.*` details.
