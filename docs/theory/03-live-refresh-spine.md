# Theory 03 — The Live-Refresh Spine

> **Where this fits.** This is the *flagship* document of the suite. Theory 01 gave
> the reactive loop; Theory 02 gave the two stores. Here they combine into the one
> capability vinary-viewer exists for: **you edit a file in your editor, hit save,
> and the preview updates instantly — without losing your scroll position, your
> in-page search, or your theme.** We trace that path end-to-end, then prove the
> property that makes it feel magical: *a content update mutates only `:doc/*` in
> DataScript, never `:ui/*`.*

## 1. The spine, end to end

"The spine" is the single longest causal chain in the app: a byte changes on disk
and, eleven hops later, new HTML is painted into the DOM. Every hop is a named,
inspectable transformation. Here is the chain, with the owning layer for each hop:

```
 editor save                         (filesystem)
   └─▶ chokidar "change"             (main: per-path watcher, after awaitWriteFinish)
        └─▶ service/send-content!    (main: kind-of + fs.readFileSync)
             └─▶ vv:content          (IPC seam: webContents.send → ipcRenderer.on)
                  └─▶ onContent       (renderer: window.vv → bridge!)
                       └─▶ [:content/received]        (re-frame event, PURE handler)
                            └─▶ :ds/transact          (DataScript: doc text/meta upserted)
                                 └─▶ :markdown/render  (renderer: async unified pipeline)
                                      └─▶ [:content/rendered path html]   (back-edge event)
                                           └─▶ :ds/transact {:doc/html …} (DataScript: html upserted)
                                                └─▶ :doc/active sub recomputes (via :ds/rev)
                                                     └─▶ markdown-body did-update
                                                          └─▶ set! innerHTML html  (DOM, painted)
```

Read top-to-bottom it is just *"detect → transact → render → paint"* — the four
phases the diagrams divide it into. The full sequence, with `autonumber`,
activation bars, and `== detect / transact / render / paint ==` dividers, is below.
Source: [`../diagrams/seq-live-refresh.puml`](../diagrams/seq-live-refresh.puml).

```plantuml
!include ../diagrams/seq-live-refresh.puml
```

![Live refresh: editor save → painted DOM, full round trip](../diagrams/seq-live-refresh.puml)

## 2. Stage by stage

### Stage 1 — detect (filesystem → main)

When you first open a file, the main-process service starts **one chokidar watcher
for that path** (and only one — it is created idempotently). The watcher is
configured to avoid two classic previewer bugs:

```clojure
;; vinary.main.service/open! (excerpt)
(when-not (get @watchers path)
  (let [w (watch path (clj->js {:ignoreInitial true
                                :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
    (.on w "change" (fn [_] (send-content! wc path)))
    (.on w "add"    (fn [_] (send-content! wc path)))
    (swap! watchers assoc path w)))
```

- **`awaitWriteFinish {stabilityThreshold 80 pollInterval 20}`** waits until the
  file's size has been stable for 80 ms (polling every 20 ms) before firing. This
  prevents previewing a *half-written* file — without it, a large save could be
  read mid-write and render as garbage.
- **It listens for both `change` *and* `add`.** Many editors save *atomically* by
  writing a temp file and renaming it over the target — which the OS reports as the
  original path being *removed and re-created*, i.e. an `add`, not a `change`.
  Handling `add` is what makes live refresh work with those editors.
- **`ignoreInitial true`** suppresses a spurious event for the file's pre-existing
  state at watch-time (the initial content is sent explicitly by `open!`, not via
  the watcher).

One watcher per *open* path means the watch set is exactly the set of tabs;
closing a tab stops its watcher (`close!` → `.close` + `dissoc`).

### Stage 2 — transact (main → IPC → re-frame → DataScript)

The service reads the file and pushes it over the IPC seam, tagged with its kind:

```clojure
;; vinary.main.service (excerpt)
(defn- kind-of [path]
  (let [lower (str/lower-case path)]
    (cond
      (re-find #"\.(md|markdown|mdx)$" lower)                     "markdown"
      (re-find #"\.(png|jpe?g|gif|svg|webp|bmp|ico|avif)$" lower) "image"
      :else                                                      "text")))

(defn- send-content! [wc path]
  (let [kind (kind-of path)]
    (if (= kind "image")
      (.send wc "vv:content" (clj->js {:path path :kind kind}))          ; no :text (binary)
      (try (let [text (.readFileSync fs path "utf8")]
             (.send wc "vv:content" (clj->js {:path path :kind kind :text text})))
           (catch :default e
             (.send wc "vv:error" (clj->js {:path path :message (.-message e)})))))))
```

(The IPC seam and `js->clj` keywordisation are Theory 04.) In the renderer the
payload becomes `[:content/received {…}]`, whose **pure** handler builds the
DataScript transaction. This is the *exact* code dissected in Theory 01 §6 and
Theory 02 §4; the key point *for the spine* is **which keys it writes**:

```clojure
;; the transacted doc map (markdown/text case)
{:doc/path path :doc/kind kind :doc/open? true :doc/order order
 :doc/text text}                                  ; (omitted for images — nil-as-absence)
;; plus, if a stale error existed:  [:db/retract eid :doc/error cur-err]
```

Every written key is a **`:doc/*`** attribute. *Nothing in this transaction touches
`app-db`'s `:ui/*` slices.* Hold that thought — §3 turns it into the invariant.

### Stage 3 — render (the async Markdown pipeline)

For `kind = "markdown"`, the handler additionally emits a `:markdown/render`
effect. Rendering runs the **unified / remark / rehype** pipeline (detailed in
Theory 05 and the
[`../diagrams/seq-markdown-render.puml`](../diagrams/seq-markdown-render.puml)
sequence) and returns a `Promise<string>` of HTML. The effect resolves it and
*re-enters the loop* via the back-edge event:

```clojure
;; vinary.app.fx (excerpt)
(rf/reg-fx
 :markdown/render
 (fn [{:keys [text path on-done]}]
   (-> (md/render text)
       (.then  (fn [html] (rf/dispatch (conj on-done html))))    ; → [:content/rendered path html]
       (.catch (fn [e]    (rf/dispatch [:content/error {…}]))))))

;; vinary.app.events
(rf/reg-event-fx
 :content/rendered
 (fn [_ [_ path html]] {:fx [[:ds/transact [{:doc/path path :doc/html html}]]]}))
```

Two consequences:

- **The async hop does not break unidirectional flow** — it *schedules the next
  cycle*. The HTML returns as a normal event and lands as one more `:doc/html`
  transaction (again, a `:doc/*`-only write).
- **`text` kind takes a synchronous shortcut.** Plain text needs no async render;
  the `:content/received` handler transacts the body directly, wrapping the escaped
  text in `<pre class="vv-plain">` (Theory 05). Images take *no* `:doc/html` at all
  — the view renders `<img file://path>` from `:doc/kind`/`:doc/path`.

### Stage 4 — paint (DataScript → sub → DOM)

The `:doc/html` transaction trips the **`:ds/rev` bridge** (Theory 02 §3):
`[:ds/changed]` bumps `:ds/rev`, the `:doc/active` sub (which is `:<- [:ds/rev]`)
recomputes and re-pulls the now-rendered doc, and `content-view` selects the
Markdown body. The body component writes the HTML **imperatively**:

```clojure
;; vinary.ui.views (excerpt)
(defn markdown-body [_html]
  (let [node (atom nil)]
    (r/create-class
     {:display-name "vv-markdown-body"
      :component-did-mount  (fn [this] (set-inner! @node (second (r/argv this))))
      :component-did-update (fn [this] (set-inner! @node (second (r/argv this))))
      :reagent-render       (fn [_html] [:div.markdown-body {:ref (fn [el] (reset! node el))}])})))

(defn- set-inner! [node html] (when node (set! (.-innerHTML node) (or html ""))))
```

This is a **form-3** reagent component (a `create-class` with lifecycle methods).
Its render produces an *empty* `.markdown-body` div and captures the DOM node in a
`ref` atom; the actual HTML is written by `component-did-update` via `set!
(.-innerHTML node) html`. The document body is therefore **not VDOM-diffed** —
React never reconciles the rendered HTML tree. (The rationale — avoiding the cost
and fragility of diffing a large foreign HTML subtree — is in
[`../design-decisions/`](../design-decisions/README.md) and Theory 05.)

## 3. The invariant: a refresh touches only `:doc/*`

Now the property that makes live refresh feel *seamless* rather than *jarring*:

> **Live-refresh invariant.** Processing a `vv:content` message mutates only
> **`:doc/*`** attributes in **DataScript**. It never writes any **`:ui/*`** slice
> of **app-db**. Therefore scroll position, in-page find, the active theme, the
> TOC's active heading, and the navigation history are all **preserved** across a
> refresh.

### 3.1 Why it holds (by construction)

Walk the writes a refresh performs and note each one's target store:

| Hop | Write | Target |
|-----|-------|--------|
| `:content/received` → `:ds/transact` | `{:doc/path :doc/kind :doc/text :doc/open? :doc/order}` (+ maybe retract `:doc/error`) | **DataScript `:doc/*`** |
| `:content/rendered` → `:ds/transact` | `{:doc/path :doc/html}` | **DataScript `:doc/*`** |
| `:content/error` → `:ds/transact` | `{:doc/path :doc/error}` | **DataScript `:doc/*`** |

There is no `:db`/`app-db` change *in the refresh path at all* beyond `:ds/rev`
(which is an invalidation token, not UI state). And critically, the things the user
would hate to lose live **elsewhere**:

- **Scroll position** is owned by the **DOM** (the `.vv-content` element's scroll
  offset). The body is updated via `innerHTML` on the inner `.markdown-body`; the
  scrolling container is its parent `.vv-content`, whose scroll offset the browser
  leaves untouched when a descendant's `innerHTML` changes.
- **In-page find** lives in `app-db` `:ui/find` *and* as live DOM **Ranges**
  painted via the CSS Custom Highlight API (Theory 06) — neither is a `:doc/*`
  write.
- **Theme** is `app-db` `:ui/theme` plus a `<link>` href (Theory 05 / the themes
  feature) — untouched.
- **Active heading / TOC** is `app-db` `:ui/active-heading` — untouched (the TOC
  *list* is re-derived from the new HTML, but the spy state is not clobbered by the
  content write).
- **History** is `app-db` `:ui/history` — untouched. (A *refresh* of the active
  file does not even change `:active-path`, because `nav-to`'s `record-nav` no-ops
  when the path equals the current entry — Theory 07.)

Because none of those are `:doc/*` attributes, and the refresh writes *only*
`:doc/*` attributes, they cannot be disturbed. The invariant is **structural** — it
follows from the two-store split (Theory 02), not from careful ordering you must
maintain by hand.

### 3.2 Why the two-store split is what buys it

This is the deepest payoff of putting documents in DataScript and UI in app-db.
Had the document HTML lived *in `app-db`* next to the find/scroll/theme state, a
content update would be an `app-db` write — and you would have to be perpetually
careful not to stomp neighbouring keys, and the `:doc/active` recompute would risk
invalidating UI subs. By giving documents their **own store** and bridging it with
a *single* counter, "update the document" and "the UI is unchanged" become two
*independent* facts. The diagram below shows the same spine as swimlanes by layer,
with the explicit *"UI untouched"* note at the paint stage. Source:
[`../diagrams/activity-live-refresh-spine.puml`](../diagrams/activity-live-refresh-spine.puml).

```plantuml
!include ../diagrams/activity-live-refresh-spine.puml
```

![Live-refresh spine as layered swimlanes, UI untouched](../diagrams/activity-live-refresh-spine.puml)

## 4. Convergence: per-path watcher + `:doc/path` identity = LWW upsert

A live previewer must also be **convergent**: no matter how many times you save,
the preview must settle on the file's *latest* content, with no duplication and no
drift. vinary-viewer gets this from two facts that compose:

1. **One watcher per path** (Stage 1). Each open file has exactly one watcher, so
   each save produces exactly one `vv:content` for that path. There is no fan-out
   or duplicate stream to reconcile.
2. **`:doc/path` is a `:db.unique/identity`** (Theory 02 §2.4). Transacting a map
   with an existing `:doc/path` **upserts** the *same* entity rather than creating
   a new one.

Together these give **Last-Writer-Wins (LWW) by path**: a stream of edits to one
file is a stream of upserts on one entity, and the final state is the content of
the last save. Concretely, if you save three times in quick succession, you get
three `:ds/transact {:doc/path p …}` writes to the *same* entity `e`; the third
wins; there is never a second tab, a second entity, or a stale body. The
`state-tab-lifecycle` diagram (Theory 05 / the tab feature) draws this as a
**self-loop on the Loaded state** — a refresh re-enters the same state with new
`:doc/html`.

> **Edge cases handled by construction.**
> - *Atomic save (write-temp-then-rename)* → surfaces as `add`; handled (Stage 1).
> - *A file that briefly errors then reads fine* → the next good content **retracts**
>   the stale `:doc/error` (Theory 02 §4), so the error view clears itself.
> - *Rapid saves* → coalesced by `awaitWriteFinish`'s stability window before they
>   even reach the renderer.

## 5. Summary

- The **spine** is the chain *detect → transact → render → paint*: chokidar →
  `service` → `vv:content` → `[:content/received]` → `:ds/transact` →
  `:markdown/render` → `[:content/rendered]` → `:ds/transact {:doc/html}` →
  `:doc/active` recompute → `set! innerHTML`.
- **chokidar** is configured with `awaitWriteFinish` and `change`+`add` to handle
  partial writes and atomic-rename saves; **one watcher per open path**.
- The **live-refresh invariant**: a content update writes only **`:doc/*`** in
  DataScript and **never `:ui/*`**, so scroll/find/theme/TOC/history survive. This
  is **structural**, a direct consequence of the two-store split.
- **Convergence is LWW by path**: one watcher per path + `:doc/path` identity ⇒
  upsert; the latest save wins, with no duplication.

Next: [Theory 04 — hexagonal architecture and the IPC Mediator](04-hexagonal-and-ipc-mediator.md)
for the boundary the front of this spine crosses.

## References

- chokidar. <https://github.com/paulmillr/chokidar> — `awaitWriteFinish`,
  `ignoreInitial`, `change`/`add`.
- DataScript. <https://github.com/tonsky/datascript> — `:db.unique/identity`
  upsert semantics (LWW by path).
- re-frame documentation. <https://day8.github.io/re-frame/> — effects, the
  async-effect → event back-edge.
- reagent. <https://reagent-project.github.io/> — `create-class` (form-3)
  lifecycle methods.
