# Markdown rendering

**Status: Available now.**

---

## 1 · What it is

vinary-viewer renders **GitHub-Flavored Markdown** to HTML using the
[unified](https://unifiedjs.com/) / [remark](https://github.com/remarkjs/remark) /
[rehype](https://github.com/rehypejs/rehype) ecosystem: tables, task lists, and strikethrough
(GFM); stable `id`s on every heading (so the [scroll-spy TOC](10-scroll-spy-toc.md) and in-document
links work); and syntax highlighting on fenced code blocks. The rendered HTML is themed entirely
through CSS variables, so Markdown re-colors with the active theme
([feature 06](06-themes-and-live-switching.md)) — including code, which is highlighted with
highlight.js token classes that map onto the `--vv-*` palette.

Rendering runs **asynchronously in the renderer** (the all-ESM remark stack bundles cleanly for the
browser target) and is wired as a re-frame effect, so it composes with the live-refresh spine: a
file change re-renders and the new HTML flows back into the document.

---

## 2 · How to use it

1. Open a Markdown file: `vv README.md` (extensions `.md`, `.markdown`, `.mdx`).
2. The rendered document appears in the content area; headings, tables, lists, blockquotes, links,
   inline code, and fenced code blocks are all styled by the active theme.
3. Fenced code blocks with a language hint (e.g. ```` ```clojure ````) are syntax-highlighted.
4. Edit and save; the preview re-renders in place ([feature 01](01-live-refresh.md)).

**Example.** A code block like:

````markdown
```clojure
(defn greet [name] (str "Hello, " name))
```
````

renders as a highlighted `<pre><code class="hljs language-clojure">…</code></pre>`, with keywords,
strings, and symbols colored from the theme palette.

> **Raw embedded HTML is not passed through.** The pipeline does **not** include `rehype-raw`, so
> literal HTML written inside a Markdown file is not injected into the output as live markup. This
> is a deliberate safety property (see Design notes and
> [security/threat-model.md](../security/threat-model.md)).

---

## 3 · How it works internally

### The unified pipeline

`src/vinary/renderer/markdown.cljs` builds the pipeline and returns a `Promise<string>`:

```clojure
(ns vinary.renderer.markdown
  (:require ["unified" :refer [unified]]
            ["remark-parse$default"     :as remark-parse]
            ["remark-gfm$default"       :as remark-gfm]
            ["remark-rehype$default"    :as remark-rehype]
            ["rehype-slug$default"      :as rehype-slug]
            ["rehype-highlight$default" :as rehype-highlight]
            ["rehype-stringify$default" :as rehype-stringify]))

(defn render [^String md]
  (-> (unified)
      (.use remark-parse)
      (.use remark-gfm)
      (.use remark-rehype)
      (.use rehype-slug)
      (.use rehype-highlight)
      (.use rehype-stringify)
      (.process md)
      (.then (fn [file] (str file)))))
```

The plugins run in this **exact order**, each doing one job:

1. **`remark-parse`** — parses the Markdown source string into an **mdast** (Markdown Abstract
   Syntax Tree). *mdast* is the remark AST: a tree of nodes like headings, paragraphs, lists, code.
2. **`remark-gfm`** — adds **GitHub-Flavored Markdown** extensions to the mdast: tables, task-list
   checkboxes, strikethrough, autolinks, and footnotes.
3. **`remark-rehype`** — transforms the Markdown tree (mdast) into an HTML tree (**hast**, the
   HTML Abstract Syntax Tree). This is the bridge from "Markdown structure" to "HTML structure".
4. **`rehype-slug`** — walks the hast and gives every heading (`h1`–`h6`) a stable, URL-safe `id`
   derived from its text. These ids are what the [TOC](10-scroll-spy-toc.md) targets and what
   in-document anchor links point to.
5. **`rehype-highlight`** — applies [highlight.js](https://highlightjs.org/) to fenced code blocks,
   wrapping tokens in `hljs-*` classes (e.g. `hljs-keyword`, `hljs-string`). It adds classes only;
   the *colors* come from CSS (below).
6. **`rehype-stringify`** — serializes the hast back into an HTML **string**.

`.process md` runs the chain asynchronously and yields a vfile; `(.then (fn [file] (str file)))`
extracts the HTML string. The `$default` interop suffix (`"remark-parse$default"`) imports each
package's ESM default export under shadow-cljs.

### Wired as an async effect

The render is invoked as a re-frame effect from `src/vinary/app/fx.cljs`, so the async work happens
at the edge and the result is dispatched back into the loop:

```clojure
(rf/reg-fx
 :markdown/render
 (fn [{:keys [text path on-done]}]
   (-> (md/render text)
       (.then (fn [html] (rf/dispatch (conj on-done html))))
       (.catch (fn [e] (rf/dispatch [:content/error {:path path :message (str "render error: " (.-message e))}]))))))
```

- **`.then` → `(conj on-done html)`** — on success, dispatch the `on-done` event vector with the
  HTML appended. In `:content/received`, `on-done` is `[:content/rendered path]`, so the dispatch
  becomes `[:content/rendered path html]`.
- **`.catch` → `[:content/error …]`** — a render failure becomes a recoverable error state (shown
  by the content-view's error branch), not a crash.

This effect is added only for the `markdown` kind in `:content/received`
([feature 01](01-live-refresh.md)):

```clojure
:fx (cond-> [[:ds/transact tx]]
      (= kind "markdown") (conj [:markdown/render {:text text :path path :on-done [:content/rendered path]}])
      (= kind "text")     (conj [:ds/transact [{:doc/path path :doc/html (plain-html text)}]]))
```

### The HTML is transacted onto the document

`:content/rendered` writes the HTML onto the doc's `:doc/html`:

```clojure
(rf/reg-event-fx
 :content/rendered
 (fn [_ [_ path html]] {:fx [[:ds/transact [{:doc/path path :doc/html html}]]]}))
```

That transaction bumps `:ds/rev`, the `:doc/active` subscription recomputes, and the content view
shows the new HTML.

### The body is written imperatively (not VDOM-diffed)

The content-view's `(:doc/html doc)` branch renders `markdown-body`, a reagent **form-3** component
whose single DOM node's `innerHTML` tracks the HTML, set via a ref on mount and update
(`src/vinary/ui/views.cljs`):

```clojure
(defn- set-inner! [^js node html]
  (when node (set! (.-innerHTML node) (or html ""))))

(defn markdown-body [_html]
  (let [node (atom nil)]
    (r/create-class
     {:display-name "vv-markdown-body"
      :component-did-mount  (fn [this] (set-inner! @node (second (r/argv this))))
      :component-did-update (fn [this] (set-inner! @node (second (r/argv this))))
      :reagent-render       (fn [_html] [:div.markdown-body {:ref (fn [el] (reset! node el))}])})))
```

Terms:

- **reagent form-3 / `create-class`** — a component with explicit React lifecycle methods. We need
  `did-mount`/`did-update` to imperatively set `innerHTML`.
- **`(second (r/argv this))`** — `r/argv` is the component's current argument vector; the second
  element is the `html` argument. Reading it in the lifecycle methods gets the latest HTML.
- **`set! (.-innerHTML node) html`** — assigns the HTML string into the node in one operation.
  **The document body is not reconciled by React**; it is a single managed node. This is what makes
  rendering cheap (no VDOM diff of a large document) and is also why [in-page find](05-in-page-find.md)
  paints over Ranges instead of mutating the body.

### Code highlighting is themed by CSS, not by the pipeline

`rehype-highlight` only assigns `hljs-*` classes; the actual colors are mapped to the `--vv-*`
palette in `resources/public/css/app.css`:

```css
.markdown-body { color: var(--vv-fg); line-height: 1.6; max-width: 980px; }
.markdown-body h1 { color: var(--vv-head1); }
.markdown-body h2 { color: var(--vv-head2); }
/* … */
.markdown-body code { background: var(--vv-bg-code); color: var(--vv-head4); … }

/* highlight.js classes (rehype-highlight), Spacemacs palette */
.hljs { color: var(--vv-code); background: transparent; }
.hljs-comment, .hljs-quote { color: var(--vv-comment); font-style: italic; }
.hljs-keyword, .hljs-selector-tag, .hljs-tag { color: var(--vv-head1); }
.hljs-string, .hljs-doctag, .hljs-regexp { color: var(--vv-head2); }
.hljs-number, .hljs-literal, .hljs-symbol, .hljs-bullet { color: var(--vv-const); }
.hljs-variable, .hljs-template-variable, .hljs-type, .hljs-params { color: var(--vv-var); }
/* … */
```

So each highlight.js token class points at a theme token: keywords → `--vv-head1`, strings →
`--vv-head2`, numbers/literals → `--vv-const`, comments → `--vv-comment`, and so on. Switching
theme re-colors code along with everything else.

### The text fallback

A non-Markdown, non-image file (kind `"text"`) is not run through the pipeline; instead its content
is escaped and wrapped in a `<pre>` directly (`src/vinary/app/events.cljs`):

```clojure
(defn- plain-html [text]
  (str "<pre class=\"vv-plain\">" (gstr/htmlEscape (or text "")) "</pre>"))
```

- **`gstr/htmlEscape`** — `goog.string/htmlEscape`, which escapes `&`, `<`, `>`, `"` so the file's
  text is shown *as text*, never interpreted as HTML. This is the same store (`:doc/html`) the
  Markdown branch writes to, so the content-view renders both through `markdown-body`; `.vv-plain`
  styles the `<pre>` with `white-space: pre-wrap`.

---

## 4 · Design notes / trade-offs

- **Why unified/remark in the renderer?** The remark/rehype stack is all-ESM and browser-friendly,
  so it bundles cleanly into the `:browser` shadow-cljs build and runs where the result is needed
  (the renderer). It is also the same family of public libraries the previewer's predecessor used,
  so behavior is familiar. Running it in the renderer keeps MAIN a thin IO service.
- **Why no `rehype-raw`?** Omitting `rehype-raw` means raw HTML embedded in a Markdown file is **not**
  rendered as live markup — a meaningful safety property for a tool that previews arbitrary files,
  since it prevents embedded `<script>`/event-handler injection from the document content. It is a
  deliberate trade-off against rendering hand-authored inline HTML; documented in the
  [threat model](../security/threat-model.md).
- **Why imperative `innerHTML` instead of hiccup/VDOM?** The pipeline already produces a complete
  HTML string; re-parsing it into hiccup to let React diff it would be wasteful for large documents
  and would complicate find. One managed node with `innerHTML` is simpler and faster, at the cost of
  opting that subtree out of React reconciliation (acceptable: the body is a leaf view).
- **Why highlight token classes → CSS variables?** It keeps *all* color in the theme layer, so code
  highlighting participates in live theme switching with zero pipeline involvement.

Recorded in [ADR-0002 render Markdown in the renderer](../design-decisions/0002-render-markdown-in-renderer.md)
and [ADR-0003 ref-`innerHTML` body, no VDOM](../design-decisions/0003-ref-innerHTML-no-vdom-body.md).
See the [ADR index](../design-decisions/README.md) for the full list.

---

## 5 · Diagram

- **Sequence — render a Markdown file:** [`../diagrams/seq-markdown-render.puml`](../diagrams/seq-markdown-render.puml)
  (written by the architecture pillar). `:content/received` (markdown) → `:markdown/render` fx →
  `unified → parse → gfm → rehype → slug → highlight → stringify` → `Promise<string>` →
  `:content/rendered` → `[:ds/transact {:doc/html …}]` → `:doc/active` recompute → `markdown-body`
  `innerHTML`, with the `.catch → :content/error` branch shown.

```plantuml
'' Source: docs/diagrams/seq-markdown-render.puml
'' Render with: plantuml -tsvg docs/diagrams/seq-markdown-render.puml
```

Palette: **green** = the Markdown/unified pipeline, **blue** = the re-frame effect/events,
**purple** = DataScript (`:doc/html`), **teal** = the renderer body. See
[`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml).
