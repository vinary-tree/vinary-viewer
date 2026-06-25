# Live refresh

**Status: Available now.**

> The reactive spine of vinary-viewer. Everything else hangs off it.

---

## 1 · What it is

**Live refresh** means: while a document is open in vinary-viewer, *the moment you save the
file in any editor*, the preview updates to the new content — and your **scroll position, the
active tab, the find query, and every other piece of UI state stay exactly where they were**.

This is not a "reload the page" refresh. It is a *reactive* refresh: the main (Node) process
watches each open file; on a change it re-reads the file and pushes the new content across the
IPC seam; the renderer transacts that content into its database; and the small set of
subscriptions that read that document recompute, re-rendering only what changed. Because the
document content and the *where-you-are* UI state live in two **separate** stores, updating one
never disturbs the other.

The chain of components that carries an edit from disk to pixels is what we call the
**live-refresh spine**. The full theory of why it is shaped this way — single source of truth,
effects only at the edge, the `:ds/rev` reactivity bridge — is in
[theory/03-live-refresh-spine.md](../theory/03-live-refresh-spine.md); this page walks the exact
code that implements it.

---

## 2 · How to use it

There is nothing to enable — live refresh is always on for every open document.

**Steps**

1. Open a document, e.g. from a terminal:

   ```bash
   vv README.md
   ```

2. In your editor, change `README.md` and save.
3. The preview updates in place. If you had scrolled halfway down, you stay halfway down.

**Example.** Open a Markdown file, scroll to a heading near the bottom, then add a sentence to
a *different* paragraph near the top and save. The new sentence appears; the viewport does not
jump. (Compare with a browser hard-refresh, which would scroll you back to the top.)

This also works for **atomic saves**: many editors write a temp file and rename it over the
original (which looks like *delete + create* to the OS). vinary-viewer listens for both the
`change` and the `add` events, so a renamed-in file re-sends just like an in-place write.

---

## 3 · How it works internally

The spine has five stages. We follow one edit through all five, quoting the exact code.

### Stage A — one watcher per open file (MAIN process)

`src/vinary/main/service.cljs` keeps an atom mapping each open path to its
[chokidar](https://github.com/paulmillr/chokidar) watcher:

```clojure
(defonce ^:private watchers (atom {}))   ; path -> chokidar watcher
```

When a file is opened, `open!` sends the current content immediately, then installs a watcher
**once** for that path:

```clojure
(defn open! [^js wc path]
  (send-content! wc path)
  (send-tree! wc path)
  (when-not (get @watchers path)
    (let [w (watch path (clj->js {:ignoreInitial true
                                  :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
      (.on w "change" (fn [_] (send-content! wc path)))
      (.on w "add"    (fn [_] (send-content! wc path)))
      (swap! watchers assoc path w))))
```

Terms, defined:

- **`wc`** — the Electron `webContents` of the renderer window; the handle MAIN uses to send
  IPC messages *to* the renderer.
- **`:ignoreInitial true`** — chokidar normally emits an `add` event for the file at watch
  start; we suppress that because `open!` has already sent the initial content via
  `send-content!`. Without this, the file would be sent twice on open.
- **`:awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}`** — chokidar waits until the
  file size has been stable for `80 ms` (polling every `20 ms`) before firing. This *debounces*
  an editor that writes in several `write()` calls, so the renderer is not handed a
  half-written file. `stabilityThreshold` and `pollInterval` are both in milliseconds.
- **`change` + `add`** — an in-place save fires `change`; an atomic save (write-temp +
  rename-over) fires `add` when the new inode appears. Listening to both makes live refresh
  robust across editors.

The `(when-not (get @watchers path) …)` guard is the **idempotence** of the spine: re-opening
an already-open file does not stack a second watcher.

### Stage B — read + classify + send (MAIN → IPC)

`send-content!` decides how to read the file from its **kind**, then sends a JSON envelope:

```clojure
(defn- kind-of [^String path]
  (let [lower (str/lower-case path)]
    (cond
      (re-find #"\.(md|markdown|mdx)$" lower)                     "markdown"
      (re-find #"\.(png|jpe?g|gif|svg|webp|bmp|ico|avif)$" lower) "image"
      :else                                                      "text")))

(defn- send-content! [^js wc path]
  (let [kind (kind-of path)]
    (if (= kind "image")
      ;; images are binary — don't read as text; the renderer displays them by file:// path.
      (.send wc "vv:content" (clj->js {:path path :kind kind}))
      (try
        (let [text (.readFileSync fs path "utf8")]
          (.send wc "vv:content" (clj->js {:path path :kind kind :text text})))
        (catch :default e
          (.send wc "vv:error" (clj->js {:path path :message (.-message e)})))))))
```

- **`kind`** ∈ `{"markdown", "image", "text"}`. This is the key the renderer's Strategy uses to
  choose how to display the document (see [theory/05](../theory/05-strategy-renderer-registry.md)).
- **Images carry no `:text`.** They are binary; reading them as UTF-8 would corrupt them. The
  renderer loads images directly by `file://` path (see [feature 08](08-image-view.md)). For
  Markdown and text, the file is read as UTF-8 and the string travels in `:text`.
- A read failure is reported on the separate `vv:error` channel, not thrown. This is how a
  transient error (e.g. mid-rename) becomes a recoverable state rather than a crash.

The envelope crosses the **IPC seam** via the preload `contextBridge` API. The renderer never
touches `fs` or `ipcRenderer` directly; every message flows through `window.vv` (the Mediator).
See [reference/ipc-channels.md](../reference/ipc-channels.md) for the exact channel contract.

### Stage C — receive into the reactive loop (RENDERER)

`src/vinary/renderer/core.cljs` wires the `window.vv` callbacks to re-frame on startup:

```clojure
(defn bridge! []
  (when-let [^js vv (.-vv js/window)]
    (.onContent vv (fn [payload] (rf/dispatch [:content/received (js->clj payload :keywordize-keys true)])))
    (.onError   vv (fn [payload] (rf/dispatch [:content/error   (js->clj payload :keywordize-keys true)])))
    (.onTree    vv (fn [payload] (rf/dispatch [:tree/received    (js->clj payload :keywordize-keys true)])))))
```

Each incoming envelope becomes a re-frame **event**. `js->clj … :keywordize-keys true` turns the
JS object `{path, kind, text}` into the Clojure map `{:path … :kind … :text …}`.

### Stage D — transact content, untouched UI (the crux)

`src/vinary/app/events.cljs` handles `:content/received`. This handler is the reason live
refresh preserves your position:

```clojure
(rf/reg-event-fx
 :content/received
 (fn [{:keys [db]} [_ {:keys [path kind text]}]]
   (let [snap    (ds/snapshot)
         eid     (ds/eid-for-path snap path)
         cur-err (and eid (ds/doc-attr snap path :doc/error))
         order   (or (ds/order-for-path snap path) (ds/next-order snap))
         ;; absence = "no value" (DataScript rejects nil): omit :doc/text for images; retract stale errors.
         base    (cond-> {:doc/path path :doc/kind kind :doc/open? true :doc/order order}
                   text (assoc :doc/text text))
         tx      (cond-> [base] cur-err (conj [:db/retract eid :doc/error cur-err]))]
     {:db (nav-to db path)
      :fx (cond-> [[:ds/transact tx]]
            (= kind "markdown") (conj [:markdown/render {:text text :path path :on-done [:content/rendered path]}])
            (= kind "text")     (conj [:ds/transact [{:doc/path path :doc/html (plain-html text)}]]))})))
```

Term-by-term:

- **`ds/snapshot`** — `@conn`, the current DataScript database value. Reads are taken against an
  immutable snapshot.
- **`eid-for-path`** — the entity id of the `:doc` for this path, or `nil` if this is the first
  time we have seen it. `:doc/path` is the DataScript identity attribute (`:db.unique/identity`),
  so a transaction with an existing `:doc/path` **upserts** the same entity rather than creating
  a duplicate. This is what makes a re-send an *update*, not a new tab.
- **`order`** — the tab's position. On first open it is `(next-order snap)` (max existing order
  + 1); on re-send it is the *existing* order, so a content update never reshuffles the tabs.
- **`base` via `cond->`** — the transaction map. `:doc/text` is `assoc`ed **only when `text` is
  truthy**, because DataScript rejects a `nil` attribute value. For images (no `:text`), the
  key is simply omitted. This is the **nil-as-absence** convention used throughout the codebase.
- **stale-error retraction** — if the doc currently holds a `:doc/error` and we are now
  delivering good content, we `[:db/retract eid :doc/error cur-err]` so the error view clears.
- **`:db (nav-to db path)`** — updates the active path and records navigation history (see
  [feature 07](07-navigation-history.md)). This is the *only* `app-db` change; **scroll, find,
  and theme are untouched**.
- **`:fx`** — the effects. Always transact the content; additionally, for `markdown` fire the
  async render fx; for `text` transact a pre-escaped `<pre>` directly (no async needed).

The schema is deliberately tiny — only the identity is declared, the rest are schema-less
scalars:

```clojure
(def schema {:doc/path {:db/unique :db.unique/identity}})
```

`:doc/{kind,text,html,error,open?,order}` need no schema entry because DataScript requires schema
only for `:db.unique` / `:db.type/ref` / `:db.cardinality/many`; plain scalars do not. The full
schema reference is [architecture/04-state-schema-reference.md](../architecture/04-state-schema-reference.md).

### Stage E — render fx, then transact HTML; subs recompute

For Markdown, the render effect runs the async [unified](https://unifiedjs.com/) pipeline in the
renderer (see [feature 09](09-markdown-rendering.md)) and dispatches the resulting HTML back into
the loop:

```clojure
(rf/reg-fx
 :markdown/render
 (fn [{:keys [text path on-done]}]
   (-> (md/render text)
       (.then (fn [html] (rf/dispatch (conj on-done html))))
       (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "render error: " (.-message e))}]))))))
```

`:content/rendered` then transacts the HTML onto the doc:

```clojure
(rf/reg-event-fx
 :content/rendered
 (fn [_ [_ path html]] {:fx [[:ds/transact [{:doc/path path :doc/html html}]]]}))
```

Every DataScript transaction bumps a revision counter, which is what makes the subscriptions
recompute. `src/vinary/app/ds.cljs` installs the bridge:

```clojure
(defn install-bridge! []
  (d/listen! conn ::reframe (fn [_tx-report] (rf/dispatch [:ds/changed]))))
```

```clojure
(rf/reg-event-db :ds/changed (fn [db _] (update db :ds/rev inc)))
```

Conn-reading subscriptions declare `:ds/rev` as an input, so they recompute on every
transaction. For example the active document:

```clojure
(rf/reg-sub
 :doc/active
 :<- [:ds/rev] :<- [:ui/active-path]
 (fn [[_rev path] _] (when path (ds/active-doc (ds/snapshot) path))))
```

When `:doc/html` changes, `:doc/active` recomputes, and the content view writes the new HTML into
the document body — *imperatively*, via a ref, not by VDOM-diffing the whole document:

```clojure
(defn markdown-body [_html]
  (let [node (atom nil)]
    (r/create-class
     {:display-name "vv-markdown-body"
      :component-did-mount  (fn [this] (set-inner! @node (second (r/argv this))))
      :component-did-update (fn [this] (set-inner! @node (second (r/argv this))))
      :reagent-render       (fn [_html] [:div.markdown-body {:ref (fn [el] (reset! node el))}])})))
```

`set-inner!` is `(set! (.-innerHTML node) html)`. The document body is one DOM node whose
`innerHTML` tracks `:doc/html`; React does not reconcile the document's internal tree. The
viewport (a separate scroll container, `.vv-content`) is **not** replaced, so the scroll offset
is preserved across the swap — completing the "update content, keep your place" guarantee.

> **The `:ds/rev` bridge in one sentence.** [re-posh](https://github.com/denistakeda/re-posh) is a
> declared dependency but is **not used** for reactivity; the explicit `:ds/rev`-bump-on-transaction
> bridge is the real mechanism, chosen because it is guaranteed and inspectable rather than relying
> on re-posh subscription internals. See [theory/02](../theory/02-state-model-datascript-app-db.md).

---

## 4 · Design notes / trade-offs

- **Why a watcher *per open path* rather than one recursive directory watcher?** Precision and
  cost. We watch exactly the files that are open, so we never wake the renderer for unrelated
  edits, and we never recursively walk a large tree. The cost is one watcher handle per tab,
  released by `close!`:

  ```clojure
  (defn close! [path]
    (when-let [^js w (get @watchers path)] (.close w) (swap! watchers dissoc path)))
  ```

- **Why split content (DataScript) from UI (`app-db`)?** This split is *the* enabling decision
  for "live refresh preserves your position". If scroll/find/theme lived in the same store as the
  document, a content transaction could plausibly perturb them. Keeping them apart makes the
  guarantee structural, not incidental.

- **Why `awaitWriteFinish`?** Without debouncing, a multi-`write()` save can fire `change`
  mid-write and hand the renderer a truncated file. The `80 ms` stability window trades a barely
  perceptible latency for correctness.

- **Trade-off — `readFileSync` on the main process.** Reads are synchronous. For the document
  sizes vinary-viewer targets (docs, source) this is simpler and fast enough; it is a candidate
  to move to async `fs.promises` if very large files become common. Recorded as a known trade-off.

These decisions are recorded in the ADRs:
[ADR-0006 multi-watcher live refresh](../design-decisions/0006-multi-watcher-live-refresh.md),
[ADR-0008 DataScript + app-db split](../design-decisions/0008-datascript-plus-app-db-split.md),
[ADR-0004 the `:ds/rev` bridge vs re-posh](../design-decisions/0004-ds-rev-bridge-vs-re-posh.md),
and [ADR-0003 ref-`innerHTML` body, no VDOM](../design-decisions/0003-ref-innerHTML-no-vdom-body.md).
See the [ADR index](../design-decisions/README.md) for the full list.

---

## 5 · Diagrams

The spine is illustrated by two diagrams written by the theory/architecture pillars and embedded
here:

- **Sequence — one edit, end to end:** [`../diagrams/seq-live-refresh.puml`](../diagrams/seq-live-refresh.puml).
  File on disk → chokidar → `send-content!` → `vv:content` → `:content/received` →
  `:ds/transact` → `:markdown/render` → `:content/rendered` → subs recompute → body `innerHTML`.
- **Activity — the spine as swimlanes:** [`../diagrams/activity-live-refresh-spine.puml`](../diagrams/activity-live-refresh-spine.puml).
  Partitions for MAIN (Node-IO), the IPC seam, and the RENDERER reactive loop.

```plantuml
'' Rendered from docs/diagrams/seq-live-refresh.puml (written by the theory pillar).
'' Embed the generated SVG here, or render with:
''   plantuml -tsvg docs/diagrams/seq-live-refresh.puml
```

Both reuse the shared palette: **slate** = MAIN/Node-IO, **amber** = the IPC seam, **teal** =
the renderer, **purple** = DataScript, **blue-violet** = `app-db`, **green** = the Markdown
pipeline. See [`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml) for the color contract.
