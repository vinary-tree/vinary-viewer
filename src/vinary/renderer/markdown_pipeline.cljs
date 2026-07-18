(ns vinary.renderer.markdown-pipeline
  "The DOM-FREE Markdown parse+transform pipeline (remark → rehype through metadata collection), extracted from
   vinary.renderer.markdown so it can run HEADLESS — in the Electron renderer, in :node-test, and in the
   command-line / TUI builds. It deliberately requires NONE of the DOM-coupled post-passes (renderer.syntax
   pulls in CodeMirror's EditorView, renderer.mermaid touches `document`); those stay in renderer.markdown's
   `apply-posts`. Everything here is pure hast/mdast tree work over the isomorphic unified/remark/rehype stack,
   plus the two pure helpers it needs (media URL rewriting, math fence stripping).

   `base-pipeline` is the single source of truth for HOW Markdown is parsed, sanitized, slugged, highlighted,
   URL-rewritten, and source-position-annotated: the GUI (renderer.markdown/render-ir, stream-blocks) and the
   terminal front-end both build on it, so they can never diverge on structure. Callers append their own
   compile step (`capture-hast` + rehype-stringify, or hast→IR) after it."
  (:require ["unified" :refer [unified]]
            ["remark-parse$default"     :as remark-parse]
            ["remark-gfm$default"       :as remark-gfm]
            ["remark-math$default"      :as remark-math]
            ["remark-rehype$default"    :as remark-rehype]
            ["rehype-slug$default"      :as rehype-slug]
            ["rehype-highlight$default" :as rehype-highlight]
            ["rehype-raw$default"       :as rehype-raw]
            ["rehype-sanitize$default"  :as rehype-sanitize]
            [clojure.string :as str]
            [vinary.ir.backend.sanitize :as sanitize]
            ;; unified-latex AND uniorg are reached through the runtime registry (renderer.heavy-registry), NOT
            ;; static requires, so they code-split out of the renderer boot bundle into the :heavy-engine chunk
            ;; while the node builds populate the registry eagerly (heavy-node/install!). The org-handler arms call
            ;; registry/latex->html and org-pipeline installs the two registry/uniorg-plugins instead of importing
            ;; renderer.latex / uniorg-parse / uniorg-rehype.
            [vinary.renderer.heavy-registry :as registry]
            [vinary.renderer.media :as media]
            [vinary.renderer.math :as math]))

(defn dir-of
  "The directory of an absolute POSIX path (\"/a/b/c.md\" → \"/a/b\"), or nil if there is no \"/\"."
  [p]
  (when (and p (str/index-of p "/"))
    (subs p 0 (.lastIndexOf p "/"))))

;; hast element tagName → the attributes whose relative URLs get absolutized against the doc dir
(def ^:private url-attrs
  {"img"    ["src"]
   "a"      ["href"]
   "source" ["src"]
   "video"  ["src" "poster"]
   "link"   ["href"]})

(def ^:private media-url-attrs
  #{["img" "src"] ["source" "src"] ["video" "src"] ["video" "poster"]})

(defn- absolutize
  "Resolve a possibly-relative URL against base (an absolute file:// dir URL, trailing slash). Leaves
   already-absolute urls untouched — scheme: (http:/file:/data:/mailto:), //host, #anchor — and passes a
   malformed url through unchanged."
  [base url]
  (if (or (not (string? url)) (str/blank? url)
          (re-find #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|#)" url))
    url
    (try (.-href (js/URL. url base)) (catch :default _ url))))

(defn- rewrite-url [base tag attr url cache-token]
  (let [url (absolutize base url)]
    (if (contains? media-url-attrs [tag attr])
      (media/cache-bust-local-media-url url cache-token)
      url)))

(defn- walk-rewrite!
  "Depth-first walk of a hast tree, rewriting relative URL attributes on element nodes (no external
   visitor dep). Mutates node.properties in place."
  [^js node base cache-token]
  (when node
    (when-let [attrs (and (= "element" (.-type node)) (get url-attrs (.-tagName node)))]
      (when-let [props (.-properties node)]
        (doseq [a attrs]
          (let [v (aget props a)]
            (when (string? v) (aset props a (rewrite-url base (.-tagName node) a v cache-token)))))))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)] (walk-rewrite! (aget kids i) base cache-token)))))

(defn- source-props [pos kind]
  (let [props #js {}]
    (when pos
      (when-let [^js start (.-start pos)]
        (aset props "data-vv-source-start-line" (str (.-line start)))
        (aset props "data-vv-source-start-column" (str (.-column start)))
        (when (some? (.-offset start))
          (aset props "data-vv-source-start-offset" (str (.-offset start)))))
      (when-let [^js end (.-end pos)]
        (aset props "data-vv-source-end-line" (str (.-line end)))
        (aset props "data-vv-source-end-column" (str (.-column end)))
        (when (some? (.-offset end))
          (aset props "data-vv-source-end-offset" (str (.-offset end)))))
      (aset props "data-vv-source-kind" kind))
    props))

(defn- merge-source-props! [^js node kind]
  (when-let [pos (.-position node)]
    (let [props (or (.-properties node) #js {})]
      (when-not (.-properties node) (set! (.-properties node) props))
      (let [src (source-props pos kind)]
        (doseq [k ["data-vv-source-start-line"
                   "data-vv-source-start-column"
                   "data-vv-source-start-offset"
                   "data-vv-source-end-line"
                   "data-vv-source-end-column"
                   "data-vv-source-end-offset"
                   "data-vv-source-kind"]]
          (when-let [v (aget src k)]
            (aset props k v)))))))

(defn- source-text-span [^js node]
  #js {:type "element"
       :tagName "span"
       :properties (source-props (.-position node) "text")
       :children #js [node]
       :position (.-position node)})

(defn- annotate-source! [^js node]
  (when node
    (when (= "element" (.-type node))
      (merge-source-props! node "element"))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)]
        (let [child (aget kids i)]
          (if (and (= "text" (.-type child))
                   (.-position child)
                   (not (str/blank? (.-value child))))
            (aset kids i (source-text-span child))
            (annotate-source! child)))))))

(defn- wrap-image-node [^js img]
  (let [props (or (.-properties img) #js {})
        src   (aget props "src")]
    (when (and (string? src) (not (str/blank? src)))
      #js {:type "element"
           :tagName "a"
           :properties #js {:href src :className #js ["vv-figure-link"]}
           :children #js [img]
           :position (.-position img)})))

(defn- wrap-unlinked-images! [^js node parent-tag]
  (when node
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)]
        (let [child (aget kids i)
              tag   (.-tagName child)]
          (if (and (= "element" (.-type child))
                   (= "img" tag)
                   (not= "a" parent-tag))
            (when-let [wrapped (wrap-image-node child)]
              (aset kids i wrapped))
            (wrap-unlinked-images! child tag)))))))

(defn hast-text
  "Text content for a HAST subtree, matching the browser's rendered text closely enough for TOC labels."
  [^js node]
  (cond
    (nil? node) ""
    (= "text" (.-type node)) (or (.-value node) "")
    :else
    (if-let [^js kids (.-children node)]
      (apply str (for [i (range (.-length kids))] (hast-text (aget kids i))))
      "")))

(defn- heading-level [tag]
  (when-let [[_ n] (and (string? tag) (re-matches #"h([1-6])" tag))]
    (js/parseInt n)))

(defn- collect-metadata!
  "Collect derived render metadata from the already-mutated HAST tree. This avoids reparsing the final
   HTML just to find headings and local media paths."
  [state ^js node]
  (when node
    (when (= "element" (.-type node))
      (let [tag   (.-tagName node)
            props (.-properties node)]
        (when-let [level (heading-level tag)]
          (when-let [id (and props (aget props "id"))]
            (when-not (str/blank? id)
              (swap! state update :toc conj {:level level :text (str/trim (hast-text node)) :id id}))))
        (when-let [attrs (seq (for [[media-tag attr] media-url-attrs
                                    :when (= media-tag tag)]
                                attr))]
          (doseq [attr attrs
                  :let [url (and props (aget props attr))
                        path (media/local-media-path url)]
                  :when path]
            (swap! state update :assets conj path)))))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)] (collect-metadata! state (aget kids i))))))

(defn- rewrite-urls
  "A rehype (hast) transformer plugin: rewrite relative element URLs to absolute against base-dir — file:// for a
   local doc, or the remote dir itself (ssh://…) for a remote doc. Those ssh:// media URLs are then fetched to
   data: URLs by a post-DOM pass (media/resolve-remote-media!), since neither the sandboxed renderer nor file://
   can reach the host."
  [base-dir cache-token]
  (fn [_opts]
    (fn [tree _file]
      (when (and base-dir (not (str/blank? base-dir)))
        (let [base (if (re-find #"(?i)^s(?:sh|ftp)://" base-dir)
                     (str base-dir "/")
                     (str "file://" base-dir "/"))]
          (walk-rewrite! tree base cache-token)))
      tree)))

(defn- wrap-images
  "A rehype transformer plugin: wrap bare images in links to their resolved src URI."
  []
  (fn [_opts]
    (fn [tree _file]
      (wrap-unlinked-images! tree nil)
      tree)))

(defn- source-positions
  "A rehype transformer plugin: expose Markdown source positions to the preview DOM."
  []
  (fn [_opts]
    (fn [tree _file]
      (annotate-source! tree)
      tree)))

(defn- collect-metadata
  "A rehype transformer plugin: collect TOC headings and local media asset paths after URL rewriting."
  [state]
  (fn [_opts]
    (fn [tree _file]
      (collect-metadata! state tree)
      tree)))

(defn- clean-math-nodes!
  "Depth-first walk of an mdast tree: strip GitHub's backtick fence from inlineMath/math nodes (see
   math/strip-math-fence). remark-math precomputes the mdast→hast projection at PARSE time in
   `node.data.hChildren`, and remark-rehype uses THAT (not `node.value`), so strip the fence in BOTH places."
  [^js node]
  (when node
    (let [t (.-type node)]
      (when (or (= t "inlineMath") (= t "math"))
        (set! (.-value node) (math/strip-math-fence (.-value node)))
        (when-let [^js data (.-data node)]
          (when-let [^js hkids (.-hChildren data)]
            (dotimes [i (.-length hkids)]
              (let [^js hk (aget hkids i)]
                (when (= "text" (.-type hk))
                  (set! (.-value hk) (math/strip-math-fence (.-value hk))))))))))
    (when-let [^js kids (.-children node)]
      (dotimes [i (.-length kids)] (clean-math-nodes! (aget kids i))))))

(defn- github-math
  "A remark (mdast) transformer: normalize GitHub's $`…`$ inline math to clean TeX at the tree level, where
   code spans are already distinct inlineCode nodes and so stay literal. Replaces the former raw-string
   normalize, which could not see code-span boundaries and corrupted `$…$` inline-code examples."
  []
  (fn [_opts] (fn [tree _file] (clean-math-nodes! tree) tree)))

(defn- app-hast-suffix
  "The shared hast post-parse chain applied to EVERY tree-producing format after its own parse → hast projection:
   rehype-raw (parse embedded raw HTML) → rehype-sanitize (GitHub allowlist) → rehype-slug → rehype-highlight →
   rewrite-urls → wrap-images → source-positions → collect-metadata. Sanitize MUST run before the app's own
   trusted plugins (rewrite-urls/wrap-images/source-positions/slug) so their post-sanitize additions (file://
   srcs, data-vv-source-*, ids, vv-figure-link) survive — sanitize-last would strip every app-generated file://
   image src and break all local images. Factored out so Markdown (`base-pipeline`) and Org (`org-pipeline`)
   inherit an IDENTICAL sanitizing/slugging/highlighting/positions policy.

   `post-raw` (optional) is a rehype transformer inserted BETWEEN rehype-raw and rehype-sanitize — the one place
   a format can rewrite freshly-parsed raw HTML into the shapes the sanitizer/post-passes expect while it is
   still trusted (Org and standalone `.tex` pass `tex-normalize` here, to remap unified-latex's math/center
   markup that only materializes once rehype-raw parses the embedded LaTeX HTML). Markdown passes nil."
  ([processor metadata base-dir cache-token] (app-hast-suffix processor metadata base-dir cache-token nil))
  ([processor metadata base-dir cache-token post-raw]
   (let [raw (.use processor rehype-raw)
         raw (if post-raw (.use raw post-raw) raw)]
     (-> raw
         (.use rehype-sanitize sanitize/schema)
         (.use rehype-slug)
         (.use rehype-highlight)
         (.use (rewrite-urls base-dir cache-token))
         (.use (wrap-images))
         (.use (source-positions))
         (.use (collect-metadata metadata))))))

(defn base-pipeline
  "The shared remark → rehype pipeline through collect-metadata (everything BEFORE the stringify/compile
   step). Both the GUI IR render and the terminal front-end build on this identical prefix, so they can never
   diverge on parsing/sanitizing/slugging/highlighting/URL-rewriting/source-positions. `metadata` is an atom
   {:toc [] :assets #{}} the pipeline mutates; `base-dir` (or nil) resolves relative URLs; `cache-token` busts
   local-media caches."
  [metadata base-dir cache-token]
  (-> (unified)
      (.use remark-parse)
      (.use remark-gfm)
      (.use remark-math)
      (.use (github-math))   ; strip GitHub $`…`$ backtick fences on the mdast (code spans stay literal)
      (.use remark-rehype #js {:allowDangerousHtml true})
      (app-hast-suffix metadata base-dir cache-token)))

;; ── Org (.org) frontend normalization ────────────────────────────────────────────────────────────────────────
;; Org is a SEMANTIC superset of GFM — its node set contains GFM's — so everything downstream of parsing is
;; shared (app-hast-suffix, hast->ir, ir.backend.html/lower, renderer.markdown/apply-posts). But a shared
;; pipeline is not a shared SELECTOR: several post-passes match a specific hast shape, and uniorg emits a
;; different one. These normalizations rewrite Org's hast into the GFM shapes the shared passes already
;; understand, so Org inherits math, task lists, and footnotes without a single Org-specific renderer.

(defn- el
  ([tag props]      (el tag props #js []))
  ([tag props kids] #js {:type "element" :tagName tag :properties props :children kids}))

(defn- txt [v] #js {:type "text" :value (str v)})

(defn- as-array
  "uniorg-rehype's `.toHast` returns a single node or an array; normalize to an array for `.concat`/wrapping."
  [x] (if (array? x) x #js [x]))

(defn- class-set
  "The className list of a hast element as a set of strings (empty when absent)."
  [^js node]
  (let [cn (some-> (.-properties node) (aget "className"))]
    (if cn (set (array-seq cn)) #{})))

(def ^:private front-matter-keys
  "The `#+KEYWORD:` lines that carry document front matter. uniorg-rehype drops EVERY keyword, so without this
   a keywords-only document (an Org file whose body is a `#+BEGIN_EXPORT latex` block, e.g. an invoice) renders
   to the empty string. Emacs' ox-html renders the title, so rendering it is parity, not invention."
  #{"TITLE" "SUBTITLE" "AUTHOR" "DATE"})

(defn- org-footnotes-section
  "uniorg-rehype's `footnotesSection` option. Its default emits a bare <h1>Footnotes:</h1>, which outranks every
   real heading and pollutes the Contents outline. GFM's footnote section is an <h2>, so match it."
  [footnotes]
  (.concat #js [(el "h2" #js {} #js [(txt "Footnotes")])] footnotes))

(def ^:private tex-fragment-text-macro-re
  "The complete set of standard LaTeX TEXT-mode formatting macros — font series/shape/family (`\\text**`), emphasis,
   and the ulem underline/strike family. MathJax renders these poorly but unified-latex lowers them to real markup
   (<b>, <i>, <u>, …). ONLY a latex-fragment matching this is rerouted to the LaTeX renderer; every other fragment —
   real math ($…$, \\(…\\), bare math macros like \\alpha, and CUSTOM macros (which may expand to MATH, e.g.
   `\\newcommand{\\R}{\\mathbb{R}}`) — stays on the existing uniorg→MathJax path, so this cannot regress inline math.
   A custom TEXT macro is inherently undecidable from its name alone and correctly falls through to MathJax."
  #"^\s*\\(textbf|textit|texttt|textsc|textrm|textsf|textmd|textup|textsl|textnormal|emph|underline|uline|uuline|uwave|sout|xout|dashuline|dotuline)\b")

(defn- capture-todo-seq!
  "Parse a `#+TODO:` / `#+SEQ_TODO:` / `#+TYP_TODO:` value ('TODO NEXT | DONE CANCELLED') into `todo-seq` as
   keyword→state ('todo' | 'done'). Keywords after `|` are done states; with no `|`, Emacs treats the LAST
   keyword as the done state. A `(x)` fast-access-key suffix is stripped. uniorg-parse recognizes only its
   configured keywords ([\"TODO\" \"DONE\"]) and never reads this line, so a custom sequence otherwise renders
   as literal title text — org-normalized-node re-styles it from this table."
  [todo-seq value]
  (let [strip  (fn [w] (str/replace w #"\([^)]*\)$" ""))
        words  (fn [s] (->> (str/split (str/trim (or s "")) #"\s+") (remove str/blank?) (mapv strip)))
        [a d]  (str/split value #"\|" 2)
        active (words a)
        done   (if d (words d) (when (seq active) [(peek active)]))
        active (if d active (vec (butlast active)))]
    (doseq [w active] (swap! todo-seq assoc w "todo"))
    (doseq [w done]   (swap! todo-seq assoc w "done"))))

(defn- org-handlers
  "uniorg-rehype `handlers`: merged over its defaultHandlers and dispatched on the Org node type. A handler
   whose return value is falsy falls THROUGH to uniorg's built-in handler, which is how we drop keywords and
   keep `#+BEGIN_EXPORT html` on its raw-HTML path. `front-matter` is an atom the keyword handler fills; `preamble`
   accumulates #+LATEX_HEADER/#+LATEX lines; `todo-seq` collects a document's #+TODO:/#+SEQ_TODO: sequence."
  [front-matter preamble todo-seq]
  (let [preamble-str (fn [] (str/join "\n" @preamble))]
    #js
     {;; #+TITLE / #+AUTHOR / #+DATE / #+SUBTITLE — capture (org-front-matter re-injects them as real headings).
      ;; #+LATEX_HEADER: / #+LATEX: — accumulate into `preamble` so a \newcommand defined there expands in a
      ;; LaTeX export block/environment/fragment below (the macro preprocessor). Both then fall through (return
      ;; nil) so uniorg still drops the keyword node.
      :keyword
      (fn [^js org]
        (let [k (str (.-key org))]
          (cond
            (contains? front-matter-keys k)                (swap! front-matter assoc k (str (.-value org)))
            (contains? #{"LATEX_HEADER" "LATEX"} k)         (swap! preamble conj (str (.-value org)))
            (contains? #{"TODO" "SEQ_TODO" "TYP_TODO"} k)   (capture-todo-seq! todo-seq (str (.-value org)))))
        nil)

    ;; #+BEGIN_EXPORT <backend> — uniorg emits a raw node for `html` and DROPS every other backend, silently
    ;; swallowing the body. A viewer must not lose content. For `latex`: a math-looking block keeps the
    ;; MathJax-attempt marker (renderer.math-engine/render-tex-blocks typesets it, else falls back to the code block); a
    ;; NON-math block (invoice layout: center/flushleft/itemize/tabular) is rendered by unified-latex into a `raw`
    ;; node of real HTML, which app-hast-suffix's rehype-raw parses, tex-normalize normalizes, and sanitize cleans.
    ;; Every other backend → a fenced code block in that backend's language.
    :export-block
    (fn [^js org]
      (let [backend (str/lower-case (str (.-backend org)))
            body    (str (or (.-value org) ""))]
        (cond
          (= "html" backend) nil                            ; fall through to uniorg's raw-HTML path
          (and (= "latex" backend) (not (math/tex-block-math? body)))
          #js {:type "raw" :value (registry/latex->html body {:preamble (preamble-str)})}
          :else
          (let [classes #js []]
            (when (seq backend) (.push classes (str "language-" backend)))
            (when (= "latex" backend) (.push classes sanitize/tex-attempt-class))
            (el "pre" #js {}
                #js [(el "code" #js {:className classes} #js [(txt body)])])))))

    ;; \begin{env}…\end{env} outside an export block. A math environment (equation/align/…) falls through to
    ;; uniorg's div.math-display → MathJax (unchanged — no math regression); a NON-math environment (tabular/
    ;; center/minipage/…) is rendered by unified-latex into a raw HTML node. tex-block-math? screens exactly this.
    :latex-environment
    (fn [^js org]
      (let [value (str (or (.-value org) ""))]
        (when-not (math/tex-block-math? value)
          #js {:type "raw" :value (registry/latex->html value {:preamble (preamble-str)})})))

    ;; Inline latex-fragment. Only a text-formatting macro (\textbf{…}, \emph{…}, …) is rerouted to unified-latex
    ;; (rendered inline, no paragraph wrap); real inline math ($…$, \(…\), bare \alpha) falls through to uniorg's
    ;; span.math-inline → MathJax, so inline math cannot regress (see tex-fragment-text-macro-re).
    :latex-fragment
    (fn [^js org]
      (let [frag (str (or (.-value org) (.-contents org) ""))]
        (when (re-find tex-fragment-text-macro-re frag)
          #js {:type "raw" :value (registry/latex->html frag {:inline? true :preamble (preamble-str)})})))

    ;; `- [ ]` / `- [X]` task items → GFM's task-list shape (which GitHub's sanitize schema allows verbatim, so
    ;; the existing CSS styles it). A plain item bearing an Org counter `[@n]` → <li value=n> so BOTH back-ends
    ;; number it off one IR source (the Phase-2 ordinal seam; `li[value]` is in the global sanitize allowlist).
    ;; An ordinary item returns nil → uniorg's default <li>.
    :list-item
    (fn [^js org]
      (this-as ^js self
        (let [cb      (.-checkbox org)                      ; "on"/"off"/"trans" for a task item, else nil
              counter (.-counter org)                       ; Org `[@n]` → a string like "5", else nil
              kids    (as-array (.toHast self (.-children org) org))]
          (cond
            cb      (let [props #js {:className #js ["task-list-item"]}
                          box   (el "input" #js {:type "checkbox" :disabled true :checked (= "on" cb)})]
                      (when counter (aset props "value" counter))
                      (el "li" props (.concat #js [box (txt " ")] kids)))
            counter (el "li" #js {:value counter} kids)
            :else   nil))))

    ;; ordinary drawer `:NAME:…:END:` — uniorg-rehype's `case 'drawer': return null` drops the CONTENTS entirely
    ;; (Emacs exports them). Render the children in a bare <div> (sanitize strips the class harmlessly). A
    ;; property-drawer is a DISTINCT node type and stays dropped — it is metadata, matching Emacs' ox-html.
    :drawer
    (fn [^js org]
      (this-as ^js self
        (el "div" #js {:className #js ["org-drawer"]}
            (as-array (.toHast self (or (.-children org) #js []) org)))))

    :plain-list
    (fn [^js org]
      (this-as ^js self
        (let [^js kids (.-children org)
              task?    (and (= "unordered" (.-listType org))
                            kids
                            (boolean (some (fn [i] (let [^js item (aget kids i)] (some? (.-checkbox item))))
                                           (range (.-length kids)))))]
          (when task?
            (el "ul" #js {:className #js ["contains-task-list"]} (.toHast self kids org))))))}))

(defn- org-front-matter
  "A rehype transformer: prepend the captured `#+TITLE` / `#+SUBTITLE` / `#+AUTHOR` / `#+DATE` to the tree as
   plain <h1>/<p><em> elements (both in GitHub's allowlist, and the <h1> is slugged by rehype-slug so it joins
   the Contents outline). Runs BEFORE app-hast-suffix so the author-supplied text is sanitized like any other."
  [front-matter]
  (fn [_opts]
    (fn [^js tree _file]
      (let [fm   @front-matter
            head #js []
            add! (fn [^js node] (.push head node))]
        (when-let [title (get fm "TITLE")]
          (when-not (str/blank? title) (add! (el "h1" #js {} #js [(txt title)]))))
        (when-let [subtitle (get fm "SUBTITLE")]
          (when-not (str/blank? subtitle) (add! (el "p" #js {} #js [(el "em" #js {} #js [(txt subtitle)])]))))
        (let [meta (->> [(get fm "AUTHOR") (get fm "DATE")]
                        (remove str/blank?)
                        (str/join " · "))]
          (when (seq meta) (add! (el "p" #js {} #js [(el "em" #js {} #js [(txt meta)])]))))
        (when (pos? (.-length head))
          (set! (.-children tree) (.concat head (.-children tree)))))
      tree)))

(def ^:private org-done-keywords
  "The TODO keywords that mean *done*. uniorg-parse recognizes exactly the keywords it is CONFIGURED with —
   `parse-options.js` defaults to [\"TODO\" \"DONE\"] — and, per Emacs, the last keyword of a sequence is the
   done state. uniorg does NOT read a document's `#+TODO:` / `#+SEQ_TODO:` line, so a custom sequence renders as
   literal title text; that is an upstream limitation, documented in docs/features/26-org-mode.md."
  #{"DONE"})

(defn- wrap-leading-todo
  "If heading `node`'s first text child starts with a captured custom `#+TODO:` keyword, wrap that keyword in
   <span class=todo|done> (the stable state class the sanitize schema permits) and return a new heading; else
   nil. uniorg styles only its CONFIGURED TODO/DONE, so a custom sequence's keyword is otherwise plain title text."
  [^js node todo-seq]
  (let [kids (.-children node)
        c0   (when (and kids (pos? (.-length kids))) (aget kids 0))]
    (when (and c0 (= "text" (.-type c0)) (seq @todo-seq))
      (let [t    (str (.-value c0))
            sp   (.indexOf t " ")
            word (if (neg? sp) t (subs t 0 sp))]
        (when-let [state (get @todo-seq word)]
          (let [rest-txt (subs t (count word))
                head     #js [(el "span" #js {:className #js [state]} #js [(txt word)])]]
            (when (seq rest-txt) (.push head (txt rest-txt)))
            (el (.-tagName node) (.-properties node) (.concat head (.slice kids 1)))))))))

(defn- org-normalized-node
  "Rewrite ONE uniorg hast element into the shape a shared post-pass already matches, or nil to leave it alone.

   • math — renderer.math-engine/render-html-math selects `code.math-*` ONLY, and GitHub's schema strips className from
     <span>/<div>, so uniorg's span.math / div.math would render as literal text. Inline → <code
     class=\"math-inline\">; display → <pre><code class=\"math-display\">…</code></pre> (the pass replaces the
     code's PARENT for display math, so the <pre> wrapper is required).
   • TODO keywords — uniorg emits <span class=\"todo-keyword TODO\">, where the second class is the keyword
     itself and therefore unbounded (it varies with the configured sequence). An allowlist cannot enumerate it,
     so collapse it to a stable state class the sanitize schema DOES permit: `todo` or `done`."
  [^js node todo-seq]
  (let [classes (class-set node)
        tag     (str (.-tagName node))]
    (cond
      (and (= "span" tag) (contains? classes "math-inline"))
      (el "code" #js {:className #js ["math-inline"]} (.-children node))

      (and (= "div" tag) (contains? classes "math-display"))
      (el "pre" #js {} #js [(el "code" #js {:className #js ["math-display"]} (.-children node))])

      (and (= "span" tag) (contains? classes "todo-keyword"))
      (let [keyword (str/trim (hast-text node))
            state   (if (contains? org-done-keywords keyword) "done" "todo")]
        (el "span" #js {:className #js [state]} (.-children node)))

      ;; inlinetask — a headline of level ≥ 7 → uniorg emits <h7>..<h15> (invalid HTML that also pollutes the
      ;; Contents outline). Render it as a bare block <div> so its content survives and it leaves the outline.
      (re-find #"^h(?:[7-9]|1[0-5])$" tag)
      (el "div" #js {:className #js ["org-inlinetask"]} (.-children node))

      ;; a heading whose leading word is a custom #+TODO: keyword → wrap it in span.todo/done (wrap-leading-todo);
      ;; nil (no match) falls through so normalize-org-hast! keeps recursing into the heading.
      (re-find #"^h[1-6]$" tag)
      (wrap-leading-todo node todo-seq)

      :else nil)))

(defn- normalize-org-hast! [^js node todo-seq]
  (when-let [^js kids (.-children node)]
    (dotimes [i (.-length kids)]
      (let [^js child (aget kids i)]
        (when (= "element" (.-type child))
          (if-let [replacement (org-normalized-node child todo-seq)]
            (aset kids i replacement)
            (normalize-org-hast! child todo-seq)))))))

(defn- org-normalize
  "A rehype transformer plugin: rewrite uniorg's math, TODO-keyword, custom-#+TODO heading, and inlinetask
   nodes into the shapes the shared post-passes and the single sanitize schema already understand (see
   org-normalized-node). One walk. `todo-seq` is the atom the keyword handler filled during .toHast."
  [todo-seq]
  (fn [_opts] (fn [tree _file] (normalize-org-hast! tree todo-seq) tree)))

;; ── LaTeX (unified-latex) frontend normalization ─────────────────────────────────────────────────────────────
;; renderer.latex/latex->html emits unified-latex's HTML: math as span.inline-math / div.display-math (NOTE the
;; word order is REVERSED from uniorg's math-inline/math-display, so this never collides with org-normalized-node),
;; and \begin{center} as the deprecated <center> tag (not in GitHub's sanitize allowlist). These rewrites run as
;; app-hast-suffix's `post-raw` hook — AFTER rehype-raw has parsed the embedded LaTeX HTML into real elements,
;; BEFORE the sanitizer — so the math markers reach renderer.math-engine/render-html-math (which selects code.math-* ONLY)
;; and centered content survives as a block. Both the standalone `.tex` pipeline and the Org pipeline (for LaTeX
;; embedded in invoices) install it; it is a no-op on documents that carry no unified-latex markup.

(defn- tex-normalized-node
  "Rewrite ONE unified-latex hast element into the shape a shared post-pass / the sanitizer already matches, or
   nil to leave it alone. Inline math → <code class=\"math-inline\">; display math → <pre><code
   class=\"math-display\">…</code></pre> (render-html-math replaces the code's PARENT for display, so the <pre>
   wrapper is required, and MathJax typesets the raw TeX preserved verbatim inside — even \\begin{align}…);
   <center> → <div> (block, allowlisted)."
  [^js node]
  (let [classes (class-set node)
        tag     (.-tagName node)]
    (cond
      (and (= "span" tag) (contains? classes "inline-math"))
      (el "code" #js {:className #js ["math-inline"]} (.-children node))

      (and (= "div" tag) (contains? classes "display-math"))
      (el "pre" #js {} #js [(el "code" #js {:className #js ["math-display"]} (.-children node))])

      (= "center" tag)
      (el "div" #js {} (.-children node))

      :else nil)))

(defn- normalize-tex-hast! [^js node]
  (when-let [^js kids (.-children node)]
    (dotimes [i (.-length kids)]
      (let [^js child (aget kids i)]
        (when (= "element" (.-type child))
          (if-let [replacement (tex-normalized-node child)]
            (aset kids i replacement)
            (normalize-tex-hast! child)))))))

(defn- tex-normalize
  "A rehype transformer plugin: rewrite unified-latex's math and <center> nodes into the shapes the shared
   post-passes and the single sanitize schema understand (see tex-normalized-node). One walk. Installed as
   app-hast-suffix's post-raw hook by both tex-pipeline and org-pipeline."
  []
  (fn [_opts] (fn [tree _file] (normalize-tex-hast! tree) tree)))

(defn latex-raw-tree
  "Wrap a unified-latex HTML string as a single `raw` hast node under a root, so app-hast-suffix's rehype-raw
   parses it into real elements — the same string→hast entry the office frontend uses."
  [html]
  #js {:type "root" :children #js [#js {:type "raw" :value (or html "")}]})

(defn tex-processor
  "The standalone LaTeX (.tex) counterpart of `base-pipeline`, as a TRANSFORM-ONLY processor (no Parser/Compiler,
   like the office frontend's raw-processor): the SAME `app-hast-suffix` as Markdown/Org — with `tex-normalize`
   as its post-raw hook so unified-latex's math/center markup is remapped after rehype-raw parses it. The caller
   converts the LaTeX document to an HTML string via renderer.latex/latex->html (unified-latex, its own
   unified@10), wraps it with `latex-raw-tree`, and runs it through this with `.runSync` (all suffix transforms
   are synchronous). So `.tex` inherits the common IR, the heading TOC, figure pre-sizing, scroll-spy, MathJax,
   and fenced-code highlighting for FREE — exactly as the office frontend reuses the Markdown hast→IR from an HTML
   string. (A custom unified Parser is deliberately avoided: unified's deprecated `this.Parser` mis-detects a
   ClojureScript fn as a newable class. unified-latex also projects no source positions, so, like Org, `.tex`
   preview nodes carry no data-vv-source-* and the fine-grained source⇄preview jump is Markdown-only; `.tex`
   still gets heading-level navigation through the Contents outline.)"
  [metadata base-dir cache-token]
  (app-hast-suffix (unified) metadata base-dir cache-token (tex-normalize)))

;; ── Org source preprocessor (runs BEFORE uniorg-parse) ────────────────────────────────────────────────────
;; uniorg-parse implements NONE of: macros ({{{…}}}), inline src (src_lang{…}), inline babel (call_x(…)), or
;; targets (<<…>>); it leaves them as literal text and even MANGLES `src_lang` into `src`+<sub>. Emacs expands
;; macros as a source pre-pass (org-macro-expand), so we do the same: a line-oriented source→source rewrite that
;; leaves #+begin_…/#+end_ verbatim blocks untouched. This is a PREPROCESSOR, not a parser — each regex matches
;; exactly one delimited token (the same tokens Org's own element grammar recognizes), not the language.
(def ^:private re-org-macro-def      #"(?i)^[ \t]*#\+MACRO:[ \t]+(\S+)[ \t]+(.*?)[ \t]*$")
(def ^:private re-org-verbatim-begin #"(?i)^[ \t]*#\+begin_(?:src|example|export|verse)\b")
(def ^:private re-org-verbatim-end   #"(?i)^[ \t]*#\+end_(?:src|example|export|verse)\b")
(def ^:private re-org-macro-call     #"\{\{\{([A-Za-z][-\w]*)(?:\(([^)]*)\))?\}\}\}")
(def ^:private re-org-inline-src     #"\bsrc_[-\w]+(?:\[[^\]]*\])?\{([^}]*)\}")
(def ^:private re-org-inline-call    #"\bcall_[-\w]+(?:\[[^\]]*\])?\(([^)]*)\)(?:\[[^\]]*\])?")
(def ^:private re-org-radio-target   #"<<<([^<>\n]+?)>>>")
(def ^:private re-org-target         #"<<([^<>\n]+?)>>")

(defn- org-keyword-value [lines k]
  (some (fn [l] (second (re-find (re-pattern (str "(?i)^[ \\t]*#\\+" k ":[ \\t]+(.*?)[ \\t]*$")) l))) lines))

(defn- expand-org-macro
  "Expand ONE {{{name(args)}}} call: a built-in ({{{title}}} etc.) from the document keywords, else a
   #+MACRO:-defined template with $1..$n substituted, else empty (Emacs drops an unknown macro)."
  [builtins defs name args]
  (let [name (str/lower-case name)
        argv (if (seq args) (mapv str/trim (str/split args #",")) [])]
    (cond
      (contains? builtins name) (get builtins name "")
      (contains? defs name)     (reduce (fn [t i] (str/replace t (str "$" (inc i)) (nth argv i ""))) (get defs name) (range (count argv)))
      :else                     "")))

(defn- preprocess-org-line [line builtins defs]
  (-> line
      (str/replace re-org-macro-call  (fn [m] (expand-org-macro builtins defs (nth m 1) (nth m 2))))
      (str/replace re-org-inline-src  (fn [m] (str "~" (nth m 1) "~")))   ; inline src CONTENT → inline code
      (str/replace re-org-inline-call (fn [m] (str "~" (nth m 0) "~")))   ; babel call → inline code (verbatim)
      (str/replace re-org-radio-target (fn [m] (nth m 1)))                ; <<<radio>>> → visible text
      (str/replace re-org-target       (fn [m] (nth m 1)))))              ; <<target>>  → visible text

(defn org-preprocess
  "Expand Org macros and rewrite inline src/babel/targets in the SOURCE before uniorg parses (uniorg implements
   none of them). Line-oriented; leaves #+begin_…/#+end_ verbatim blocks untouched. Built-in macros
   ({{{title}}}/{{{author}}}/{{{date}}}/{{{email}}}) read the document's #+TITLE/#+AUTHOR/#+DATE/#+EMAIL."
  [src]
  (let [lines (str/split-lines (str src))
        defs  (into {} (keep (fn [l] (when-let [m (re-find re-org-macro-def l)] [(str/lower-case (nth m 1)) (nth m 2)])) lines))
        builtins {"title"  (or (org-keyword-value lines "TITLE") "")
                  "author" (or (org-keyword-value lines "AUTHOR") "")
                  "date"   (or (org-keyword-value lines "DATE") "")
                  "email"  (or (org-keyword-value lines "EMAIL") "")}]
    (->> lines
         (reduce (fn [{:keys [in? out]} l]
                   (cond
                     in?                               {:in? (not (re-find re-org-verbatim-end l)) :out (conj out l)}
                     (re-find re-org-verbatim-begin l) {:in? true  :out (conj out l)}
                     :else                             {:in? false :out (conj out (preprocess-org-line l builtins defs))}))
                 {:in? false :out []})
         :out
         (str/join "\n"))))

(defn org-pipeline
  "The Org (.org) counterpart of `base-pipeline`: uniorg-parse (Emacs Org → uniorg AST) → uniorg-rehype (→ hast,
   emitting <pre><code class=\"language-X\"> for #+begin_src X and raw-HTML nodes for #+begin_export html) →
   the Org normalizations above (front matter, export blocks, task lists, footnotes, math) → the SAME
   `app-hast-suffix`. So Org inherits the common IR, the heading TOC (ir-toc/toc-of), figure pre-sizing,
   scroll-spy, MathJax, and nested-language src-block highlighting for FREE — exactly as the office frontend
   reuses the Markdown hast→IR. (uniorg-rehype does not project source positions onto the hast, so Org preview
   nodes carry no data-vv-source-* and the fine-grained right-click source⇄preview jump is Markdown-only; Org
   still gets heading-level navigation through the Contents outline. If uniorg gains hast-position support,
   adding it here makes the jump work for Org with no other change.)

   `front-matter` is per-CALL, not per-namespace: org-pipeline is invoked once per render, and the keyword
   handler mutates it during `.process`, so two concurrent renders cannot see each other's title."
  [metadata base-dir cache-token]
  (let [front-matter (atom {})
        preamble     (atom [])    ; per-call, like front-matter: #+LATEX_HEADER lines for the LaTeX macro expander
        todo-seq     (atom {})    ; per-call: a document's #+TODO:/#+SEQ_TODO: keyword→state sequence
        ;; the two uniorg plugins from the runtime registry (populated by heavy-engine/install!) — the ONLY uniorg
        ;; dependency; everything downstream (handlers/front-matter/footnotes/normalizations) is pipeline-local.
        ;; The render entry points await heavy-lazy/ensure! (renderer) or heavy-node ran install! (node) first, so
        ;; this deref is always past a loaded chunk; uniorg-plugins throws a clear error otherwise.
        [uniorg-parse uniorg-rehype] (registry/uniorg-plugins)]
    (-> (unified)
        ;; trackPosition → orgast nodes carry source positions; the patched uniorg-rehype `h()` copies them onto
        ;; the hast elements (patches/uniorg-rehype+2.2.0.patch), so the shared source-positions plugin stamps
        ;; data-vv-source-* and Org gains the fine-grained source⇄preview jump Markdown has.
        (.use uniorg-parse #js {:trackPosition true})
        (.use uniorg-rehype #js {:handlers         (org-handlers front-matter preamble todo-seq)
                                 :footnotesSection org-footnotes-section})
        (.use (org-front-matter front-matter))
        (.use (org-normalize todo-seq))
        ;; tex-normalize as post-raw: LaTeX embedded in Org (export blocks / environments / fragments, e.g.
        ;; invoices) is emitted as a `raw` node of unified-latex HTML, so its math/center markup only
        ;; materializes once app-hast-suffix's rehype-raw parses it — normalize it there. No-op for pure Org.
        (app-hast-suffix metadata base-dir cache-token (tex-normalize)))))

(defn capture-hast
  "A rehype transformer that captures the final HAST tree into `store` (for the IR back-end) and passes it
   through unchanged."
  [store]
  (fn [_opts] (fn [tree _file] (reset! store tree) tree)))
