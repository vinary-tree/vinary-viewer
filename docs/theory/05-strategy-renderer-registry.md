# Theory 05 — The Strategy Renderer

> **Where this fits.** Theory 03 ended at the *paint* stage with "`content-view`
> selects the document body." This document explains *how it selects*: the
> **Strategy** pattern, choosing an interchangeable rendering algorithm by the
> document's `:doc/kind`. It also documents the exact precedence of the current
> selector, the Markdown pipeline each kind feeds, and the **planned** evolution to
> a *registry-as-data* so new kinds (PDF, diagrams, source) plug in without editing
> the selector.

## 1. The Strategy pattern, applied to document bodies

A previewer must render different *kinds* of document differently: Markdown becomes
highlighted HTML, an image becomes an `<img>`, plain text becomes an escaped
`<pre>`. The **Strategy** pattern (Gamma, Helm, Johnson & Vlissides, 1994) is the
right tool: define a family of interchangeable algorithms and **select one at
runtime** based on context — here, the context is `:doc/kind`.

vinary-viewer's strategy is the `content-view` function. It reads two subscriptions
— the active document (`:doc/active`) and the open tabs (`:tabs`) — and returns the
appropriate body:

```clojure
;; vinary.ui.views
(defn content-view
  "Renderer registry (Strategy): the active document is shown by its :doc/kind."
  []
  (let [doc  @(rf/subscribe [:doc/active])
        tabs @(rf/subscribe [:tabs])]
    [:div.vv-content {:on-scroll (fn [e] (toc/spy! (.-currentTarget e)))}
     (cond
       (empty? tabs)               [watermark]
       (:doc/error doc)            [:div.vv-error "Error: " (:doc/error doc)]
       (= "image" (:doc/kind doc)) [:div.vv-image-view
                                    [:img {:src (str "file://" (:doc/path doc)) :alt (:doc/path doc)}]]
       (:doc/html doc)             [markdown-body (:doc/html doc)]
       :else                       [:div.vv-empty "Rendering…"])]))
```

Each `cond` arm is one Strategy. The selection is data-driven (it branches on
`:doc/kind` and the presence of `:doc/error`/`:doc/html`), and adding a kind is, in
principle, adding an arm — which is exactly what the planned registry generalises
(§4).

## 2. The precedence, exactly

The selector is an **ordered `cond`: the first matching arm wins.** Order is
therefore semantically significant, and it encodes a deliberate priority. The full
precedence:

| # | Condition | Body rendered | Why it sits here |
|---|-----------|---------------|------------------|
| 1 | `(empty? tabs)` | `[watermark]` — the Vinary Tree shield (`.vv-watermark`/`.vv-shield`) | With *no* documents open there is nothing to render; the empty state takes precedence over everything. |
| 2 | `(:doc/error doc)` | `[:div.vv-error "Error: " msg]` | An **error dominates content**: if the active doc has an error, show it even if a stale `:doc/html` lingers. |
| 3 | `(= "image" (:doc/kind doc))` | `[:div.vv-image-view [:img {:src "file://"+path}]]` | Images render from path, not HTML; this must precede the `:doc/html` arm (an image has no `:doc/html`). |
| 4 | `(:doc/html doc)` | `[markdown-body html]` — imperative `innerHTML` | The common case: rendered Markdown (or the `<pre>` for text). |
| 5 | `:else` | `[:div.vv-empty "Rendering…"]` | A document is open but its HTML has not arrived yet (async render in flight); a placeholder. |

Reading the precedence as prose: *no tabs → watermark; otherwise an error wins;
otherwise an image renders by path; otherwise rendered HTML shows; otherwise we are
mid-render.* This is exactly the order the activity diagram draws as a top-to-bottom
decision cascade (with the **planned** future arms dashed). Source:
[`../diagrams/activity-content-strategy.puml`](../diagrams/activity-content-strategy.puml).

![content-view Strategy: ordered precedence, planned arms dashed](../diagrams/activity-content-strategy.svg)

> **Subtlety — why error precedes image/html.** Errors are stored as a separate
> `:doc/error` attribute and *retracted* when they clear (Theory 02 §4,
> nil-as-absence). Placing the error arm second means a freshly-failed read shows
> its message immediately, even though the doc may still carry the previous
> successful `:doc/html`; once the next good content arrives, the error is retracted
> and arm 4 takes over again. The error/clear cycle is the red substate in the
> tab-lifecycle diagram (Theory: the tab feature;
> [`../diagrams/state-tab-lifecycle.puml`](../diagrams/state-tab-lifecycle.puml)).

## 3. What each Strategy renders

### 3.1 Markdown — the unified pipeline

The Markdown strategy (arm 4, when `:doc/kind` is `"markdown"`) is fed HTML produced
by the **unified / remark / rehype** pipeline, run in the renderer because the
all-ESM remark stack bundles cleanly for the browser target. The pipeline is a
*fixed, ordered* chain:

```clojure
;; vinary.renderer.markdown
(defn render [md]
  (-> (unified)
      (.use remark-parse)        ; 1. Markdown text → mdast (Markdown AST)
      (.use remark-gfm)          ; 2. GitHub-Flavored extensions (tables, task lists, …)
      (.use remark-rehype)       ; 3. mdast → hast (HTML AST)
      (.use rehype-slug)         ; 4. add id="slug" anchors to headings
      (.use rehype-highlight)    ; 5. syntax-highlight code blocks
      (.use rehype-stringify)    ; 6. hast → HTML string
      (.process md)
      (.then (fn [file] (str file)))))   ; → Promise<string>
```

Each stage's role:

1. **`remark-parse`** turns the source text into an **mdast** (Markdown **AST**).
2. **`remark-gfm`** enables **GFM** (GitHub-Flavored Markdown): tables, task-list
   checkboxes, strikethrough, autolinks.
3. **`remark-rehype`** transforms the mdast into a **hast** (HTML AST).
4. **`rehype-slug`** assigns each heading a stable `id` **slug** derived from its
   text (e.g. "Getting Started" → `getting-started`). These ids are what the **TOC**
   and `:toc/scroll` target (Theory: the TOC feature).
5. **`rehype-highlight`** adds syntax-highlight markup to fenced code blocks.
6. **`rehype-stringify`** serialises the hast to an HTML **string**.

The result is a `Promise<string>`; the `:markdown/render` effect resolves it back
into the loop (Theory 03 §3). Notably the pipeline has **no `rehype-raw`**, so
**raw HTML embedded in the Markdown is not re-parsed/passed through** — a deliberate
default that reduces injection surface (Theory 04 §4 and
[`../security/threat-model.md`](../security/threat-model.md)). The pipeline as a
sequence is [`../diagrams/seq-markdown-render.puml`](../diagrams/seq-markdown-render.puml),
and the renderer-namespace component view is
[`../diagrams/component-renderer.puml`](../diagrams/component-renderer.puml).

### 3.2 Text — escaped `<pre>`

The text strategy needs no async render. When `:doc/kind` is `"text"`, the
`:content/received` handler synchronously transacts a body that wraps the file in a
`<pre>`, **HTML-escaping** the content so it is shown verbatim and cannot inject
markup:

```clojure
;; vinary.app.events
(defn- plain-html [text]
  (str "<pre class=\"vv-plain\">" (gstr/htmlEscape (or text "")) "</pre>"))
```

`goog.string/htmlEscape` neutralises `<`, `>`, `&`, etc. — so even a text file full
of HTML renders as literal characters. The resulting `:doc/html` flows into arm 4
like any other body.

### 3.3 Image — render by `file://` path

The image strategy (arm 3) carries no `:doc/html` and no `:doc/text` at all
(nil-as-absence; the main service sends images without text — Theory 03 §2). The
view renders an `<img>` whose `src` is the file's `file://` URL, letting Chromium
load and display the binary directly:

```clojure
[:div.vv-image-view [:img {:src (str "file://" (:doc/path doc)) :alt (:doc/path doc)}]]
```

## 4. How the Markdown body is mounted (and why it is not VDOM-diffed)

Arm 4 renders `[markdown-body html]`, a **form-3** reagent component (Theory 03 §4,
detailed in [`../architecture/06-renderer-runtime.md`](../architecture/06-renderer-runtime.md)).
Recapping the *why* here, since it is part of the rendering strategy: the body is a
potentially large, foreign HTML subtree produced *outside* React (by the unified
pipeline). Letting React's virtual-DOM reconcile that subtree on every change would
be costly and could fight the imperative ids/anchors that the TOC and find depend
on. So the component renders an *empty* `.markdown-body` div and writes the HTML
imperatively via `set! (.-innerHTML node) html` in `component-did-mount` /
`component-did-update`. The Strategy returns *which* body to mount; the form-3
component controls *how* that body's HTML reaches the DOM.

## 5. Planned: registry-as-data

The current selector is a hard-coded `cond`. Its arms are few and stable, so this
is perfectly serviceable today — but it means **adding a kind requires editing
`content-view`**. The roadmap generalises it.

> **«Forthcoming (planned).» Registry-as-data.** Replace the `cond` with a **data
> table** mapping `:doc/kind` → a render function, e.g.
>
> ```clojure
> ;; sketch of the planned registry (NOT yet implemented)
> (def renderers
>   {"markdown" markdown-body-view
>    "image"    image-view
>    "text"     text-view
>    "pdf"      pdf-view        ; «planned» — native BrowserView
>    "diagram"  diagram-view    ; «planned» — d2 / PlantUML / Mermaid → SVG
>    "source"   source-view})   ; «planned» — tree-sitter
> ```
>
> `content-view` would then look up `(renderers (:doc/kind doc))` after the
> error/empty guards, making the renderer set **open for extension, closed for
> modification** (the Open–Closed Principle): a new kind is a new table entry plus
> the `kind-of` classifier learning its extensions, with no change to the selector
> logic. This composes with the **grammar registry** and the **diagram/PDF/source**
> features, all of which are **Forthcoming** (see
> [`../features/README.md`](../features/README.md) and
> [`../design-decisions/`](../design-decisions/README.md)).

The planned arms are drawn dashed/grey in the content-strategy diagram (§2) so the
*shape* of the future is visible without implying it ships today.

## 6. Summary

- `content-view` is a **Strategy** selecting a document body by `:doc/kind`.
- It is an **ordered `cond`, first-match-wins**, with the precise precedence:
  **empty → error → image → html → "Rendering…"**; the order is meaningful (error
  dominates; image precedes html).
- Each strategy feeds a different renderer: **Markdown** via the
  `unified→remark→rehype` pipeline (with **slug** anchors, **GFM**, highlight, and
  **no `rehype-raw`**); **text** via an **escaped `<pre>`**; **image** via a
  `file://` `<img>`.
- The Markdown body is mounted by a **form-3** component and written via
  `innerHTML` (not VDOM-diffed).
- A **registry-as-data** is **planned** to make new kinds (PDF, diagrams, source)
  pluggable without editing the selector.

Next: [Theory 06 — in-page find with the CSS Custom Highlight API](06-find-css-custom-highlight.md).

## References

- Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (1994). *Design Patterns.*
  Addison-Wesley. **ISBN 978-0201633610** (no DOI). — Strategy.
- unified / remark / rehype. <https://unifiedjs.com/> — the pipeline, mdast/hast,
  `remark-gfm`, `rehype-slug`, `rehype-highlight`, `rehype-stringify`.
- reagent. <https://reagent-project.github.io/> — `create-class` (form-3) and
  `innerHTML` interop.
