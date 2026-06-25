# Multi-tab previews

**Status: Available now.**

---

## 1 · What it is

vinary-viewer shows **one tab per open document**. A horizontal **tab strip** sits above the
content area; clicking a tab activates that document, and the `×` on a tab closes it (and stops
watching that file). Tabs are not a separate data structure you have to keep in sync — they are
simply *a view of which documents are open*, read live from the same DataScript database that
holds the documents. There is no risk of the tab list and the open documents drifting apart,
because they are the same fact.

The order of tabs is stable: each document carries a `:doc/order` (assigned once, when first
opened), and the tab strip renders documents sorted by that order. A content update — including a
live-refresh re-send — never reshuffles the tabs, because the order is fixed at open time.

---

## 2 · How to use it

**Open a document into a tab**

- From the command line: `vv path/to/file.md` opens that file as the first tab.
- From the **git file-tree sidebar** ([feature 04](04-git-file-tree-and-filter.md)): click any
  file entry. It opens in a new tab (or activates its existing tab if already open).

**Switch tabs.** Click the tab. The content area updates to that document; your history records
the navigation ([feature 07](07-navigation-history.md)).

**Close a tab.** Click the `×` on the tab. The document is removed and its file watcher is
stopped. If you closed the *active* tab, the last remaining tab becomes active.

**Example.** Run `vv README.md`, then click `docs/architecture/01-overview.md` in the sidebar.
You now have two tabs; the second is active. Click the first tab to go back to the README, then
close the second tab with its `×`. You are left with one tab (README), still active.

---

## 3 · How it works internally

### Tabs are a subscription over open documents

A document is "open" when its `:doc` entity has `:doc/open? true`. The `open-docs` query in
`src/vinary/app/ds.cljs` returns all open docs, **ordered by `:doc/order`**:

```clojure
(defn open-docs
  "All open docs as {:path :order :kind}, ordered by :doc/order."
  [db]
  (->> (d/q '[:find ?path ?order ?kind
              :where [?e :doc/open? true] [?e :doc/path ?path]
                     [?e :doc/order ?order] [?e :doc/kind ?kind]] db)
       (sort-by second)
       (mapv (fn [[p o k]] {:path p :order o :kind k}))))
```

The `:tabs` subscription in `src/vinary/app/subs.cljs` wraps that query and declares `:ds/rev`
as an input so it recomputes on every transaction:

```clojure
(rf/reg-sub
 :tabs
 :<- [:ds/rev]
 (fn [_ _] (ds/open-docs (ds/snapshot))))
```

Terms:

- **`:ds/rev`** — the DataScript-transaction revision counter in `app-db`. Every transaction
  bumps it (via the `:ds/rev` bridge — see [theory/02](../theory/02-state-model-datascript-app-db.md)),
  so any subscription that lists `:<- [:ds/rev]` recomputes when the database changes. Without
  it, a sub reading `@conn` directly would not know to recompute.
- **`sort-by second`** — sorts the result tuples `[path order kind]` by `order`, giving the
  stable left-to-right tab order.

### `:doc/order` is assigned once, at open time

In `:content/received` (the live-refresh handler, [feature 01](01-live-refresh.md)), the order
is the existing one if the doc is already open, else the next free slot:

```clojure
order (or (ds/order-for-path snap path) (ds/next-order snap))
```

```clojure
(defn next-order [db]
  (->> (d/q '[:find ?o :where [_ :doc/order ?o]] db)
       (map first) (reduce max -1) inc))
```

`next-order` is `(max existing orders) + 1`, starting at `0` when none exist. Because a re-send
reuses `order-for-path`, **live refresh keeps a tab exactly where it is**.

### The tab strip view

`src/vinary/ui/tabs.cljs` renders the strip from `:tabs` and the active path:

```clojure
(defn- basename [path] (last (str/split path #"/")))

(defn tab-strip []
  (let [tabs   @(rf/subscribe [:tabs])
        active @(rf/subscribe [:ui/active-path])]
    (when (seq tabs)
      [:div.vv-tabs
       (for [{:keys [path]} tabs]
         ^{:key path}
         [:div.vv-tab {:class    (when (= path active) "vv-tab-active")
                       :title    path
                       :on-click #(rf/dispatch [:tab/activate path])}
          [:span.vv-tab-name (basename path)]
          [:span.vv-tab-close {:title    "Close tab"
                               :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:tab/close path]))}
           "×"]])])))
```

Notes:

- **`^{:key path}`** — reagent's React key. Using the *path* as the key means a tab's DOM node is
  stable across re-renders as long as the document stays open, so switching tabs does not tear
  down unrelated tab nodes.
- **`(when (seq tabs) …)`** — when there are no open documents the strip renders nothing, and the
  content area shows the watermark ([feature 03](03-watermark-empty-tabs.md)).
- **`.vv-tab-active`** — the active tab's CSS class (a colored bottom border and head1-colored
  label; see [reference/css-variables.md](../reference/css-variables.md)).
- **`(.stopPropagation e)`** on the close button — without it, the click would *also* bubble to
  the tab's `:on-click` and activate the tab you are trying to close. Stopping propagation makes
  the `×` close-only.

### Activating a tab

```clojure
(rf/reg-event-db
 :tab/activate
 (fn [db [_ path]] (nav-to db path)))
```

`nav-to` sets `:ui/active-path` and records navigation history:

```clojure
(defn- nav-to [db path]
  (-> db (assoc-in [:ui :active-path] path) (record-nav path)))
```

Activation is a pure `app-db` change — it does **not** touch DataScript. The content area then
re-derives via the `:doc/active` subscription, which reads the now-active path:

```clojure
(rf/reg-sub
 :doc/active
 :<- [:ds/rev] :<- [:ui/active-path]
 (fn [[_rev path] _] (when path (ds/active-doc (ds/snapshot) path))))
```

### Closing a tab

```clojure
(rf/reg-event-fx
 :tab/close
 (fn [{:keys [db]} [_ path]]
   (let [snap      (ds/snapshot)
         eid       (ds/eid-for-path snap path)
         remaining (->> (ds/open-docs snap) (remove #(= (:path %) path)) vec)
         active    (get-in db [:ui :active-path])
         new-active (if (= active path) (:path (last remaining)) active)]
     {:db (assoc-in db [:ui :active-path] new-active)
      :fx (cond-> []
            eid  (conj [:ds/transact [[:db/retractEntity eid]]])
            true (conj [:vv/close path]))})))
```

Step by step:

1. **`remaining`** — the open docs minus the one being closed.
2. **`new-active`** — if you closed the *active* tab, the **last** remaining doc becomes active;
   otherwise the active tab is unchanged. If nothing remains, `(:path (last remaining))` is `nil`
   and the content area falls through to the watermark.
3. **`[:db/retractEntity eid]`** — removes the entire `:doc` entity from DataScript. The `:tabs`
   sub recomputes and the tab disappears.
4. **`[:vv/close path]`** — the effect that tells MAIN to stop watching the file:

   ```clojure
   (rf/reg-fx :vv/close (fn [path] (when-let [^js vv (.-vv js/window)] (.close vv path))))
   ```

   which crosses the IPC seam to `service/close!`, releasing the chokidar watcher (see
   [feature 01, Stage A](01-live-refresh.md#stage-a--one-watcher-per-open-file-main-process)).

The lifecycle of a single tab — *open → active → background → closed* — and the close path are
illustrated in the diagrams below.

---

## 4 · Design notes / trade-offs

- **Why model tabs as a query, not a vector?** A vector of tabs would be a *second* source of
  truth that must be kept consistent with the open documents. Deriving tabs from `:doc/open?` +
  `:doc/order` makes "the tabs" and "the open documents" the same fact — there is nothing to
  reconcile. This is the single-source-of-truth principle applied to the UI.
- **Why `:doc/order` instead of a list position?** An explicit order survives re-sends and lets a
  content update be a pure upsert that cannot reorder tabs. The alternative (positional ordering)
  would couple tab order to transaction order, which live refresh would disturb.
- **Close-to-last-remaining policy.** When the active tab closes, focusing the *last* remaining
  tab is a simple, predictable rule. A more elaborate "focus the neighbor to the left" policy is
  possible but was not chosen for v1; recorded as a trade-off.
- **Forthcoming docking.** A docking/split layout (e.g. `dockview-react`) is a documented future
  upgrade; v1 uses a single tab strip. See the ADR.

Recorded in [ADR-0008 DataScript + app-db split](../design-decisions/0008-datascript-plus-app-db-split.md)
(tabs are a DataScript view; the active tab is ephemeral `app-db` state). See the
[ADR index](../design-decisions/README.md) for the full list.

---

## 5 · Diagrams

- **State — one tab's lifecycle:** [`../diagrams/state-tab-lifecycle.puml`](../diagrams/state-tab-lifecycle.puml)
  (written by the architecture pillar). States: *Opening → Active ⇄ Background → Closing → Closed*,
  with the transitions `:doc/open`, `:tab/activate`, `:content/received` (self-loop = live
  refresh), and `:tab/close`.
- **Sequence — closing a tab:** [`../diagrams/seq-tab-close.puml`](../diagrams/seq-tab-close.puml)
  (written by the architecture pillar). `×` click → `:tab/close` → choose `new-active` →
  `[:db/retractEntity]` + `[:vv/close]` → `:tabs` recompute → watcher released in MAIN.

![Tab lifecycle state machine](../diagrams/state-tab-lifecycle.svg)

![Close tab sequence](../diagrams/seq-tab-close.svg)

Palette: **purple** = DataScript (the `:doc` entities that *are* the tabs), **blue-violet** =
`app-db` (`:ui/active-path`), **teal** = the renderer UI (the tab strip), **amber** = the IPC
seam (`:vv/close`). See [`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml).

---

## Tab management (new this round)

The tab strip gained the management affordances you'd expect of a browser, and a second representation:

- **Drag to reorder.** Drag a tab along the strip to move it; the drop position is decided by which side of
  a tab's midline you release on. Reordering is a pure splice of the ordered tab vector (`nav/reorder`).
- **Right-click context menu** — **Close · Close Others · Close to the Right · View Source · Copy file
  path/name** (the `:tab` target in `context_menu.cljs`).
- **View Source** — a per-tab toggle (View menu, the tab menu, or right-clicking the document) shows a
  Markdown document's **raw source in the pane** (the cached text via `source-view`) instead of its rendered
  form — *without* replacing the window. Toggle it back to return to the render.
- **A `Tabs` sidebar panel** — a third sidebar tab alongside *Files* and *Contents* lists the open tabs
  **vertically, in the same order** as the horizontal strip (top→bottom == left→right). It shares the
  strip's tab component, so its drag-reorder and right-click menu behave identically and **reordering either
  representation reorders both** — useful when more tabs are open than fit horizontally.

Closing all of a project's tabs leaves the project in the **Files** tree (projects are never pruned), so
you can re-open its documents without re-adding it.

**Diagram — one model, two representations:**
[`../diagrams/component-tab-dual-representation.puml`](../diagrams/component-tab-dual-representation.puml).
The horizontal strip and the vertical Tabs panel both render the *same* ordered `[:ui :tabs]` vector through
the *same* `tab-item`, so their orders never drift and a drag in either splices the one vector.

![Tab dual representation component view](../diagrams/component-tab-dual-representation.svg)

Palette: **blue-violet** = the single ordered tab model `[:ui :tabs]`, **teal** = the renderer views + the
shared `tab-item`, **blue** = the re-frame reorder / context-menu events. See
[`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml).
