# 04 · State Schema Reference

> **Scope.** The complete, ground-truth specification of vinary-viewer's state: the **DataScript
> schema** (the documents/tabs single-source-of-truth) and its rationale; the **schema-less attribute
> catalog**; the annotated **`app-db`** default (ephemeral UI); the **cross-store invariants**; the
> **`:ds/rev` reactivity bridge**; and the **query-helper catalog** with the Datalog shown. The
> conceptual state model (the theory of *why two stores*) lives in
> [theory/02-state-model-datascript-app-db.md](../theory/02-state-model-datascript-app-db.md) and the
> structural diagram is [`class-state-model.puml`](../diagrams/class-state-model.puml) (owned there);
> this page is the reference.

---

## 1. Two stores, one rule

vinary-viewer keeps state in **two** places, with a crisp division:

| Store | Holds | Lifetime | Mutated by |
| --- | --- | --- | --- |
| **DataScript** (`vinary.app.ds/conn`) | the open **documents** and **tabs** (the durable, relational truth) | per session | the `:ds/transact` effect |
| **re-frame `app-db`** (`vinary.app.db/default-db`) | **ephemeral UI**: active tab, theme, find, history, tree filter, scroll-spy, input/palette | per session | re-frame event handlers |

**The rule:** content updates write **only** DataScript; UI/scroll/active-tab state lives **only** in
`app-db`. That separation is exactly what lets a live file refresh update the document *without*
moving your scroll position or switching your active tab.

---

## 2. The DataScript schema (verbatim)

```clojure
;; vinary.app.ds
(def schema
  {:doc/path {:db/unique :db.unique/identity}})

(defonce conn (d/create-conn schema))
```

That is the **entire** schema: one declared attribute, `:doc/path`, marked
`:db.unique/identity`. Everything else a document carries (`:doc/kind`, `:doc/text`, `:doc/html`,
`:doc/error`, `:doc/open?`, `:doc/order`) is a **schema-less** attribute.

### 2.1 Why so minimal? (the schema-less rationale)

DataScript only **needs** schema entries for attributes that are one of:

- `:db.unique/identity` or `:db.unique/value` (uniqueness / upsert),
- `:db.type/ref` (entity references / nested-map transactions),
- `:db.cardinality/many` (multi-valued attributes).

Plain **scalar** attributes (strings, booleans, numbers) with default cardinality-one need **no**
schema declaration at all — you can transact `{:doc/text "…"}` without ever registering `:doc/text`.
vinary-viewer's documents are exactly "one entity, a handful of scalar fields keyed by a unique
path", so the schema collapses to the single uniqueness constraint that powers **upsert by path**.

> **Why `:doc/path` is `:db.unique/identity`.** It makes the path the document's *natural key*:
> transacting `{:doc/path "X" …}` **upserts** the existing entity for `"X"` (or creates it) rather
> than making a duplicate. Live refresh relies on this — every `change` event re-transacts the same
> `:doc/path` and updates the one entity in place.

---

## 3. The schema-less attribute catalog (documents)

The full set of attributes a `:doc` entity may carry. "Writer" = the event/effect that transacts it;
"Reader" = the query helper / subscription / view that consumes it.

| Attribute | Type | Meaning | Writer | Reader |
| --- | --- | --- | --- | --- |
| `:doc/path` | string *(unique identity)* | Absolute filesystem path; the document's key. | `:content/received` (base map) | `eid-for-path`, `active-doc`, all helpers; `:tabs` |
| `:doc/kind` | string | `"markdown"` / `"image"` / `"text"` (from `kind-of`). | `:content/received` | `open-docs`, `active-doc`; `content-view` strategy |
| `:doc/text` | string | Raw file text. **Omitted for images** (absence = "no value"). | `:content/received` (only when `text` truthy) | (source for `:markdown/render` / `plain-html`) |
| `:doc/html` | string | Rendered HTML (markdown pipeline output, or `<pre class="vv-plain">` for text). | `:content/rendered`; `:content/received` text branch | `active-doc` → `markdown-body`; `:doc/toc` |
| `:doc/error` | string | Read/render error message. Retracted on next success. | `:content/error`; `:content/received` (retract-stale) | `active-doc` → `.vv-error` view |
| `:doc/open?` | boolean | Whether the doc is an open tab. | `:content/received` (`true`) | `open-docs` (`[?e :doc/open? true]`) |
| `:doc/order` | number | Tab ordering index (monotone; from `next-order`). | `:content/received` | `open-docs` (`sort-by`), `order-for-path` |

> **`nil` is forbidden in DataScript.** You cannot transact `{:doc/text nil}`. vinary-viewer models
> *absence* by **omitting** the key (built with `cond->`) and *clearing* a value by **retracting** it
> (`[:db/retract eid :doc/error cur-err]`). This is why `:content/received` builds `base` with
> `cond->` and conditionally appends a retract. See
> [05 · Data Flows §2](./05-data-flows.md#2-live-edit-refresh).

---

## 4. The `app-db` default, annotated

```clojure
;; vinary.app.db
(def default-db
  {:ds/rev 0                                  ; DataScript transaction revision (the bridge counter)
   :ui {:active-path nil                      ; path of the active tab (Strategy/Observer key)
        :theme "spacemacs-dark"               ; current theme name → css/themes/<name>.css
        :active-heading nil                   ; TOC scroll-spy: id of the heading at the viewport top
        :sidebar-visible? true                ; (file-tree sidebar visibility)
        :tree-selected nil                    ; (tree selection marker)
        :history {:stack [] :idx -1}          ; navigation history (Command model): path stack + cursor
        :find {:visible? false                ; in-page find: bar shown?
               :query ""                       ;   current query string
               :count 0                        ;   number of matches
               :idx 0}                         ;   1-based index of the focused match (0 = none)
        ;; keybinding / modal / sequence state (ephemeral UI; the keymap itself lives in
        ;; vinary.input.keymap's atom, not here). :mode starts :insert (the non-modal default
        ;; keymap); the active keymap's :initial-mode is applied at boot (vim → :normal).
        :input {:mode :insert :sequence [] :count nil :in-input? false :timeout-id nil}
        ;; command palette / fuzzy finder
        :palette {:open? false :source :command :prefix "" :query "" :items [] :selected 0}}})
```

| `app-db` path | Type | Meaning | Writer event | Reader sub |
| --- | --- | --- | --- | --- |
| `:ds/rev` | int | Bumped on every DataScript tx; the reactivity trigger. | `:ds/changed` | `:ds/rev`, `:tabs`, `:doc/active` |
| `:ui :active-path` | string \| nil | Path of the active tab. | `:tab/activate`, `:content/received`, `:history/*`, `:tab/close` | `:ui/active-path` |
| `:ui :theme` | string | Theme name. | `:theme/set` | `:ui/theme` |
| `:ui :active-heading` | string \| nil | rehype-slug id of the heading at the top. | `:toc/active-heading` | `:ui/active-heading` |
| `:ui :sidebar-visible?` | bool | File-tree visibility. | `:sidebar/toggle` | `:ui/sidebar-visible?` (views do not yet honour it) |
| `:ui :tree-selected` | string \| nil | Keyboard-selected tree path. | `:tree/move` | `:ui/tree-selected`; `:tree/activate` reads it |
| `:ui :history` | `{:stack [path…] :idx int}` | Back/forward history. | `:tab/activate`/`:content/received` (`nav-to`), `:history/back`/`forward` | `:history/can-back?`, `:history/can-forward?` |
| `:ui :tree` | `{:root :files}` | The git file-tree payload. | `:tree/received` | `:ui/tree` |
| `:ui :tree-filter` | string | File-tree filter query. | `:tree/filter` | `:ui/tree-filter` |
| `:ui :find` | `{:visible? :query :count :idx}` | In-page find state. | `:find/*` | `:ui/find` |
| `:ui :input` | `{:mode :sequence :count :in-input? :timeout-id}` | Modal/chord keybinding state. | `:input/*` (`vinary.input.events`) | `:input/mode`, `:input/pending`, `:input/in-input?`; the resolver mirrors `:sequence` here |
| `:ui :palette` | `{:open? :source :prefix :query :items :selected}` | Command-palette state (view pending). | `:palette/*` (`vinary.input.events`) | `:palette/state` |

> **`:ui :tree` and `:ui :tree-filter` are absent from `default-db`** — they are `assoc`ed in by
> `:tree/received` / `:tree/filter` on demand, and the views guard with `(when (seq (:files tree)) …)`.
> Listing them above documents their shape once present.

---

## 5. The `:ds/rev` reactivity bridge

DataScript and re-frame are connected by an **explicit, hand-rolled** bridge (re-posh is declared but
unused — see [02 §3](./02-process-and-build-topology.md#3-the-clojurescript-dependency-stack-depsedn)):

```clojure
;; vinary.app.ds
(defn install-bridge! []
  (d/listen! conn ::reframe (fn [_tx-report] (rf/dispatch [:ds/changed]))))

;; vinary.app.events
(rf/reg-event-db :ds/changed (fn [db _] (update db :ds/rev inc)))

;; vinary.app.subs — conn-reading subs depend on :ds/rev so they recompute per transaction
(rf/reg-sub :tabs       :<- [:ds/rev]                    (fn [_ _]  (ds/open-docs (ds/snapshot))))
(rf/reg-sub :doc/active :<- [:ds/rev] :<- [:ui/active-path]
            (fn [[_rev path] _] (when path (ds/active-doc (ds/snapshot) path))))
```

**Mechanism:** every `d/transact!` fires the listener → dispatches `[:ds/changed]` → `(update db
:ds/rev inc)`. Subscriptions that *read the conn* list `:<- [:ds/rev]` as an input, so re-frame
recomputes them whenever the integer changes — and the subscribing reagent views re-render. This is a
**signal**, not the data itself: the integer carries no document content; it only says "the store
changed, re-read it". The full theory is in
[theory/01-reactive-architecture.md](../theory/01-reactive-architecture.md).

---

## 6. Cross-store invariants

These must hold across the DataScript ⇄ `app-db` boundary; the events maintain them.

1. **Active-path ⇒ open doc (eventually).** `(:ui :active-path)` should name a path that has an open
   `:doc` entity. On `:tab/close`, if the closed path was active, `new-active` is recomputed as the
   last remaining open doc (or `nil` if none) *before* the entity is retracted.
2. **Order is dense-enough and monotone.** `:doc/order` comes from `next-order` =
   `(inc (max-of-existing-orders or -1))`, so new tabs append after all current ones; `open-docs`
   sorts by it.
3. **At most one entity per path.** Guaranteed by `:doc/path` `:db.unique/identity` (upsert).
4. **No `nil` attribute values.** Absence = omitted key; clearing = retract (see [§3](#3-the-schema-less-attribute-catalog-documents)).
5. **`:ds/rev` is monotone non-decreasing** and is the *only* `app-db` field a DataScript write
   touches (writes never touch `:ui …`). This is the invariant that preserves scroll/active-tab
   across live refresh.
6. **History reflects navigation, not tabs.** The history stack records *navigations* (`nav-to`); a
   `:tab/close` does **not** rewrite history (a closed path may still sit in the stack and is simply
   re-opened if navigated to).

---

## 7. Query-helper catalog (`vinary.app.ds`)

All reads of the document store go through these helpers. Each is shown with its Datalog.

### `eid-for-path` — entity id for a path

```clojure
(defn eid-for-path [db path]
  (d/q '[:find ?e . :in $ ?p :where [?e :doc/path ?p]] db path))
```
Returns the entity id (the `.` makes it a scalar result) or `nil`. Used to target retractions and to
test existence.

### `order-for-path` — the tab order of a path

```clojure
(defn order-for-path [db path]
  (d/q '[:find ?o . :in $ ?p :where [?e :doc/path ?p] [?e :doc/order ?o]] db path))
```
Used by `:content/received` to **preserve** an existing tab's order across refreshes (so re-rendering
a file does not jump its tab).

### `next-order` — the order for a brand-new tab

```clojure
(defn next-order [db]
  (->> (d/q '[:find ?o :where [_ :doc/order ?o]] db)
       (map first) (reduce max -1) inc))
```
`-1` seeds the fold so the first tab gets order `0`. New tabs always append after the current maximum.

### `open-docs` — the ordered tab list

```clojure
(defn open-docs [db]
  (->> (d/q '[:find ?path ?order ?kind
              :where [?e :doc/open? true] [?e :doc/path ?path]
                     [?e :doc/order ?order] [?e :doc/kind ?kind]] db)
       (sort-by second)
       (mapv (fn [[p o k]] {:path p :order o :kind k}))))
```
The backbone of the `:tabs` subscription: every open doc as `{:path :order :kind}`, sorted by order.

### `doc-attr` — one attribute of one doc

```clojure
(defn doc-attr [db path attr]
  (d/q '[:find ?v . :in $ ?p ?a :where [?e :doc/path ?p] [?e ?a ?v]] db path attr))
```
Generic single-value lookup; used by `:content/received` to read the *current* `:doc/error` so it can
decide whether to retract it.

### `active-doc` — the active document, pulled

```clojure
(defn active-doc [db path]
  (when (eid-for-path db path)
    (d/pull db [:doc/path :doc/kind :doc/html :doc/error] [:doc/path path])))
```
A `d/pull` of exactly the fields the content view needs: `{:doc/path :doc/kind :doc/html :doc/error}`,
or `nil` if no entity exists. Powers the `:doc/active` subscription (and hence `content-view` and
`:doc/toc`).

### `snapshot` — the current immutable db value

```clojure
(defn snapshot [] @conn)
```
Dereferences the conn to an immutable DataScript db value. Subscriptions call this so they read a
*consistent* snapshot per recompute.

---

## 8. See also

- [theory/02-state-model-datascript-app-db.md](../theory/02-state-model-datascript-app-db.md) — *why*
  two stores; the design theory.
- [`class-state-model.puml`](../diagrams/class-state-model.puml) — the structural state diagram
  (theory-owned).
- [reference/events-effects-subs.md](../reference/events-effects-subs.md) — every event/effect/sub.
- [06 · Renderer Runtime §4](./06-renderer-runtime.md#4-the-subscription-graph) — the subscription
  graph that consumes this state.
