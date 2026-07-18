(ns vinary.renderer.syntax
  "Tree-sitter source highlighting — the DOM-free, @codemirror-FREE core. web-tree-sitter parses a source file
   (parse → highlights.scm query) into highlight SPANS ({:from :to :class}); the same spans feed two consumers:
   the source EditorView (vinary.renderer.source-view — lazily loaded, turns spans into CodeMirror Decorations) and
   the rendered-HTML path here (highlight-language-html turns spans into <span class> markup for Markdown/Org/LaTeX
   fenced code). A grammar registry maps file extensions to a tree-sitter `.wasm` + `highlights.scm`; bundled
   grammars are shipped in resources/public/grammars, and more can be dropped in ~/.config/vinary-viewer/grammars/.

   Keeping this namespace free of @codemirror is what lets the heavy editor packages (~1 MB) live in a lazily-loaded
   renderer chunk (source-view, behind the renderer.cm facade), AND lets the node builds (:cli/:tui reuse
   highlight-html-code-blocks; :test compiles the app) bundle the highlighter without the editor. Adapted from
   LightningBug's lib/editor/syntax.cljs, reduced to the read-only case (the doc is set once and re-created on
   live-refresh)."
  (:require [clojure.string :as str]
            ["web-tree-sitter" :refer [Language Parser Query]]
            [vinary.grammar-catalog :as grammar-catalog]
            [vinary.ir.frontend.source :as source-fe]))

;; tree-sitter capture name → CodeMirror CSS class (styled in app.css against the --vv-* palette)
(def ^:private style-map
  {"keyword" "cm-keyword" "keyword.operator" "cm-keyword"
   "keyword.directive" "cm-keyword" "keyword.function" "cm-keyword" "keyword.return" "cm-keyword"
   "operator" "cm-operator" "variable" "cm-variable" "variable.parameter" "cm-variable"
   "variable.builtin" "cm-variable" "variable.special" "cm-variable"
   "number" "cm-number" "string" "cm-string" "string.special" "cm-string"
   "string.escape" "cm-string" "character" "cm-string"
   "boolean" "cm-boolean" "comment" "cm-comment" "comment.documentation" "cm-comment"
   "type" "cm-type" "type.builtin" "cm-type" "tag" "cm-type" "tag.builtin" "cm-type"
   "constant" "cm-constant" "constant.builtin" "cm-constant" "constant.numeric" "cm-number"
   "constant.character" "cm-string" "function" "cm-function" "function.call" "cm-function"
   "function.builtin" "cm-function" "function.method" "cm-function" "constructor" "cm-type"
   "method" "cm-function" "module" "cm-label" "namespace" "cm-label"
   "property" "cm-property" "attribute" "cm-property" "punctuation" "cm-punctuation"
   "punctuation.delimiter" "cm-punctuation" "punctuation.special" "cm-punctuation"
   "punctuation.bracket" "cm-bracket" "label" "cm-label"
   "text.title" "cm-md-heading" "markup.heading" "cm-md-heading"
   "markup.heading.marker" "cm-md-heading-marker"
   "text.literal" "cm-md-code" "markup.raw" "cm-md-code" "markup.raw.block" "cm-md-code-block"
   "text.uri" "cm-md-url" "markup.link.url" "cm-md-url" "markup.link.uri" "cm-md-url"
   "text.reference" "cm-md-link" "markup.link" "cm-md-link" "markup.link.label" "cm-md-link"
   "text.strong" "cm-md-strong" "markup.strong" "cm-md-strong"
   "text.emphasis" "cm-md-emphasis" "markup.italic" "cm-md-emphasis" "markup.emphasis" "cm-md-emphasis"
   "text.strike" "cm-md-strike" "markup.strikethrough" "cm-md-strike"
   "markup.list" "cm-md-list" "markup.list.checked" "cm-md-task" "markup.list.unchecked" "cm-md-task"
   "markup.quote" "cm-md-quote"})

(defn- node-type [^js node] (.-type node))

(defn- markdown-node-class [name ^js node]
  (let [typ (node-type node)]
    (cond
      (and (= name "text.literal") (#{"fenced_code_block" "indented_code_block"} typ)) "cm-md-code-block"
      (and (= name "punctuation.special") (str/starts-with? typ "atx_")) "cm-md-heading-marker"
      (and (= name "punctuation.special") (str/starts-with? typ "setext_")) "cm-md-heading-marker"
      (#{"list_marker_plus" "list_marker_minus" "list_marker_star"
         "list_marker_dot" "list_marker_parenthesis"} typ) "cm-md-list"
      (= "block_quote_marker" typ) "cm-md-quote"
      :else nil)))

(defn- class-for [name node]
  (when-not (= "none" name)
    (or (markdown-node-class name node)
        (get style-map name)
        (get style-map (first (str/split name #"\."))))))

;; ---- grammar registry: bundled (shipped in resources/) + user (from main, file:// urls) ----
(def ^:private bundled-grammars grammar-catalog/bundled-grammars)

(defonce ^:private user-grammars (atom []))
(defonce ^:private user-filetypes (atom {}))

(defn register-user!
  "Install the user grammar/filetype registry pushed from main (vv:grammars).

   Older preload/main pairs sent only a grammar vector; accept both shapes so live reloads do not strand
   an already-open renderer."
  [payload]
  (if (map? payload)
    (do
      (reset! user-grammars (vec (:grammars payload)))
      (reset! user-filetypes (grammar-catalog/normalize-filetype-config (:filetypes payload))))
    (do
      (reset! user-grammars (vec payload))
      (reset! user-filetypes {}))))

(defn- all-grammars []
  (concat @user-grammars bundled-grammars))

(defn grammar-for
  "The grammar config whose extensions include path's extension, or nil (→ a plain read-only view)."
  [path]
  (grammar-catalog/grammar-for-path path (all-grammars) @user-filetypes))

(defn- grammar-by-id [id]
  (grammar-catalog/grammar-for-id id (all-grammars)))

(defn- grammar-for-language [language]
  (grammar-catalog/grammar-for-language language (all-grammars)))

;; ---- tree-sitter init (once) + grammar cache ----
(defonce ^:private ts-ready
  (delay (.init Parser #js {:locateFile (fn [_ _] "js/tree-sitter.wasm")})))

(defonce ^:private lang-cache (atom {}))      ; wasm-url -> Promise<Language>
(defonce ^:private grammar-cache (atom {}))   ; [wasm-url scm-url] -> Promise<{:language :query}>

(defn- load-language [wasm-url]
  (or (get @lang-cache wasm-url)
      (let [p (-> @ts-ready (.then (fn [_] (.load Language wasm-url))))]
        (swap! lang-cache assoc wasm-url p)
        p)))

(defn- load-grammar [{:keys [wasm-url scm-url] :as grammar}]
  (let [k [wasm-url scm-url]]
    (or (get @grammar-cache k)
        (let [p (-> (js/Promise.all #js [(load-language wasm-url)
                                         (-> (js/fetch scm-url) (.then #(.text %)))])
                    (.then (fn [arr]
                             (let [lang (aget arr 0)
                                   scm  (aget arr 1)]
                               {:grammar grammar :language lang :query (Query. lang scm)}))))]
          (swap! grammar-cache assoc k p)
          p))))

(defn- parse-tree [{:keys [language]} text]
  (let [parser (Parser.)]
    (.setLanguage parser language)
    (.parse parser (or text ""))))

;; ---- highlight SPANS ({:from :to :class}) — the @codemirror-free product of a tree-sitter parse ----
;; (Was the mark-range!/capture-ranges!/decoration-set trio that produced CodeMirror Decoration ranges; that
;;  Decoration conversion now lives in the lazily-loaded renderer.source-view, which consumes these spans.)

(defn- capture-spans! [spans ^js tree ^js query offset]
  (doseq [cap (array-seq (.captures query (.-rootNode tree)))]
    (let [^js node (.-node cap)
          cls      (class-for (.-name cap) node)
          s        (+ offset (.-startIndex node))
          e        (+ offset (.-endIndex node))]
      (when (and cls (< s e))
        (.push spans {:from s :to e :class cls}))))
  spans)

(defn- push-span!
  "Push a single {:from :to :class} span into `spans` when it is classed and non-empty — the span analog of the old
   mark-range! Decoration guard (`(and cls (< s e))`)."
  [spans cls s e]
  (when (and cls (< s e))
    (.push spans {:from s :to e :class cls})))

(defn- nodes-of-type [^js root types]
  (let [types (set types)
        out   (array)]
    (letfn [(walk [^js node]
              (when (contains? types (node-type node))
                (.push out node))
              (dotimes [i (.-namedChildCount node)]
                (when-let [child (.namedChild node i)]
                  (walk child))))]
      (walk root)
      (array-seq out))))

(defn- node-text [text ^js node]
  (.slice (or text "") (.-startIndex node) (.-endIndex node)))

(defn- markdown-grammar? [grammar]
  (= "markdown" (some-> (or (:id grammar) (:language grammar)) str/lower-case)))

(defn- org-grammar? [grammar]
  (= "org" (some-> (or (:id grammar) (:language grammar)) str/lower-case)))

(defn- math-regions
  "Math spans in `s` (offsets INTO s): display `$$…$$` (two-char delimiters) first, then inline `$…$`
   (one-char). GitHub-ish rules — an unescaped opening `$`/`$$` (not preceded by a backslash); inline requires
   a non-space just inside each `$` and stays on one line; display may span lines. Each region is
   {:open-from :open-to :inner-from :inner-to :close-from :close-to}. Returns a seq (empty when no math). The
   bundled markdown/​markdown-inline grammars have no math node, so the source view detects it here."
  [s]
  (let [out     (array)
        covered (js/Array. (count s))]           ; chars claimed by a display region → inline skips them
    (let [re (js/RegExp. "(?<!\\\\)\\$\\$([\\s\\S]+?)\\$\\$" "g")]
      (loop []
        (when-let [m (.exec re s)]
          (let [start (.-index m) inner (aget m 1)
                open-to    (+ start 2)
                inner-to   (+ open-to (count inner))
                end        (+ inner-to 2)]
            (.push out {:open-from start :open-to open-to :inner-from open-to :inner-to inner-to
                        :close-from inner-to :close-to end})
            (dotimes [i (- end start)] (aset covered (+ start i) true)))
          (recur))))
    (let [re (js/RegExp. "(?<!\\\\)\\$(?=\\S)([^\\n$]+?)(?<=\\S)\\$" "g")]
      (loop []
        (when-let [m (.exec re s)]
          (when-not (aget covered (.-index m))    ; not inside a display region
            (let [start (.-index m) inner (aget m 1)
                  open-to  (+ start 1)
                  inner-to (+ open-to (count inner))]
              (.push out {:open-from start :open-to open-to :inner-from open-to :inner-to inner-to
                          :close-from inner-to :close-to (+ inner-to 1)})))
          (recur))))
    (array-seq out)))

(defn- info-language [raw]
  (let [s     (str/trim (or raw ""))
        token (or (second (re-find #"\.([A-Za-z0-9_+-]+)" s))
                  (first (str/split s #"\s+")))]
    (some-> token
            (str/replace #"^[.{]+" "")
            (str/replace #"[},]+$" "")
            str/lower-case
            not-empty)))

(defn- fenced-language [text ^js block]
  (when-let [info (first (nodes-of-type block #{"info_string"}))]
    (info-language (node-text text info))))

(defn- highlight-fenced-spans!
  "Push a fenced code block's highlight spans into `spans`: a code-block background mark over the fence content,
   then (when a grammar matches its info string) the tree-sitter token spans. Returns the async token-pass Promise,
   or nil when the block has no content node. (Was highlight-fenced-code!, which pushed CodeMirror Decoration
   ranges; it now pushes @codemirror-free spans, so all parsing stays in this ns.)"
  [spans text ^js block]
  (when-let [^js content (first (nodes-of-type block #{"code_fence_content"}))]
    (push-span! spans "cm-md-code-block" (.-startIndex content) (.-endIndex content))
    (if-let [grammar (some-> (fenced-language text block) grammar-for-language)]
      (-> (load-grammar grammar)
          (.then (fn [loaded]
                   (let [tree (parse-tree loaded (node-text text content))]
                     (capture-spans! spans tree (:query loaded) (.-startIndex content)))))
          (.catch (fn [e] (js/console.warn "[vv] fenced code grammar failed:" e))))
      (js/Promise.resolve nil))))

(defn- generic-highlight-spans [text grammar]
  (-> (load-grammar grammar)
      (.then (fn [loaded]
               (let [tree  (parse-tree loaded text)
                     spans (array)]
                 (capture-spans! spans tree (:query loaded) 0))))))

(defn- markdown-highlight-spans [text grammar]
  (let [inline-grammar (grammar-by-id "markdown-inline")
        inline-p      (if inline-grammar (load-grammar inline-grammar) (js/Promise.resolve nil))]
    (-> (js/Promise.all #js [(load-grammar grammar) inline-p])
        (.then (fn [arr]
                 (let [block-loaded (aget arr 0)
                       inline-loaded (aget arr 1)
                       tree         (parse-tree block-loaded text)
                       root         (.-rootNode tree)
                       spans        (array)
                       code-jobs    (array)
                       latex        (grammar-by-id "latex")]
                   (capture-spans! spans tree (:query block-loaded) 0)
                   (doseq [^js inline (nodes-of-type root #{"inline"})]
                     (let [itext (node-text text inline)
                           ioff  (.-startIndex inline)]
                       (when inline-loaded
                         (capture-spans! spans (parse-tree inline-loaded itext) (:query inline-loaded) ioff))
                       ;; $…$ / $$…$$ math has no node in the bundled markdown grammar, so nest LaTeX by hand.
                       ;; Scoping to `inline` text auto-excludes fenced code (its content is not an inline node).
                       (when latex
                         (doseq [{:keys [open-from open-to inner-from inner-to close-from close-to]}
                                 (math-regions itext)]
                           (push-span! spans "cm-punctuation" (+ ioff open-from) (+ ioff open-to))
                           (push-span! spans "cm-punctuation" (+ ioff close-from) (+ ioff close-to))
                           (.push code-jobs
                                  (-> (load-grammar latex)
                                      (.then (fn [ll]
                                               (let [t (parse-tree ll (.slice itext inner-from inner-to))]
                                                 (capture-spans! spans t (:query ll) (+ ioff inner-from)))))
                                      (.catch (fn [e] (js/console.warn "[vv] $-math latex highlight failed:" e)))))))))
                   (doseq [block (nodes-of-type root #{"fenced_code_block"})]
                     (when-let [job (highlight-fenced-spans! spans text block)]
                       (.push code-jobs job)))
                   (if (pos? (.-length code-jobs))
                     (-> (js/Promise.all code-jobs) (.then (fn [_] spans)))
                     spans)))))))

(defn- org-highlight-spans
  "Org source: the org grammar's own capture, PLUS — for every `#+begin_NAME` block whose parameter names a
   language/backend (`#+begin_src LANG`, `#+begin_export BACKEND`) — the block CONTENTS parsed with that
   language's grammar. The org highlights query captures block names/directives only, never block contents, so
   this cannot double-highlight the content."
  [text grammar]
  (-> (load-grammar grammar)
      (.then (fn [loaded]
               (let [tree      (parse-tree loaded text)
                     root      (.-rootNode tree)
                     spans     (array)
                     code-jobs (array)]
                 (capture-spans! spans tree (:query loaded) 0)
                 (doseq [^js block (nodes-of-type root #{"block"})]
                   (when-let [^js contents (.childForFieldName block "contents")]
                     (when-let [g (some-> (.childForFieldName block "parameter") (.-text) grammar-for-language)]
                       (.push code-jobs
                              (-> (load-grammar g)
                                  (.then (fn [gl]
                                           (let [t (parse-tree gl (node-text text contents))]
                                             (capture-spans! spans t (:query gl) (.-startIndex contents)))))
                                  (.catch (fn [e] (js/console.warn "[vv] org block grammar failed:" e))))))))
                 (if (pos? (.-length code-jobs))
                   (-> (js/Promise.all code-jobs) (.then (fn [_] spans)))
                   spans))))))

(defn highlight-spans
  "Parse `text` with `grammar` (a {:wasm-url :scm-url}) and resolve to a JS array of @codemirror-free highlight
   spans ({:from :to :class}, in capture order). Markdown gets block+inline+fenced-code + `$`-math recursion;
   Org gets its capture + per-block language nesting; every other grammar a single generic capture. The source
   EditorView (renderer.source-view) converts these spans into CodeMirror Decorations. Returns Promise<array<span>>."
  [text grammar]
  (cond
    (markdown-grammar? grammar) (markdown-highlight-spans text grammar)
    (org-grammar? grammar)      (org-highlight-spans text grammar)
    :else                       (generic-highlight-spans text grammar)))

(defn- escape-html [s]
  (-> (or s "")
      (str/replace #"&" "&amp;")
      (str/replace #"<" "&lt;")
      (str/replace #">" "&gt;")
      (str/replace #"\"" "&quot;")))

(defn- spans->html [text spans]
  (let [text  (or text "")
        spans (->> spans
                   (filter #(and (:class %) (number? (:from %)) (number? (:to %)) (< (:from %) (:to %))))
                   (sort-by (juxt :from (comp - :to))))]
    (loop [pos 0 spans spans out []]
      (if-let [{:keys [from to class]} (first spans)]
        (if (< from pos)
          (recur pos (rest spans) out)
          (recur to
                 (rest spans)
                 (conj out
                       (escape-html (subs text pos (min from (count text))))
                       "<span class=\""
                       (escape-html class)
                       "\">"
                       (escape-html (subs text from (min to (count text))))
                       "</span>")))
        (apply str (conj out (escape-html (subs text pos))))))))

(defn highlight-language-html
  "Return Promise<string|null> with tree-sitter-highlighted HTML for text in language."
  [language text]
  (if-let [grammar (some-> language grammar-for-language)]
    (-> (load-grammar grammar)
        (.then (fn [loaded]
                 (let [tree  (parse-tree loaded text)
                       spans (capture-spans! (array) tree (:query loaded) 0)]
                   (spans->html text (array-seq spans)))))
        (.catch (fn [e]
                  (js/console.warn "[vv] rendered code grammar failed:" e)
                  nil)))
    (js/Promise.resolve nil)))

(defn- code-language [^js code]
  (let [classes (.-classList code)]
    (some (fn [i]
            (let [cls (.item classes i)]
              (or (second (re-find #"^language-(.+)$" cls))
                  (second (re-find #"^lang-(.+)$" cls)))))
          (range (.-length classes)))))

(defn highlight-html-code-blocks
  "Post-process rendered Markdown HTML so known fenced code languages use tree-sitter highlighting."
  [html]
  (if-not (exists? js/DOMParser)
    (js/Promise.resolve html)
    (let [parser (js/DOMParser.)
          doc (.parseFromString parser (or html "") "text/html")
          node-list (.querySelectorAll doc "pre > code[class*='language-'], pre > code[class*='lang-']")
          nodes (map #(.item node-list %) (range (.-length node-list)))
          jobs (->> nodes
                    (keep (fn [^js code]
                            (when-let [language (code-language code)]
                              (-> (highlight-language-html language (.-textContent code))
                                  (.then (fn [highlighted]
                                           (when highlighted
                                             (set! (.-innerHTML code) highlighted))))))))
                    into-array)]
      (if (pos? (.-length jobs))
        (-> (js/Promise.all jobs)
            (.then (fn [_] (.-innerHTML (.-body doc)))))
        (js/Promise.resolve html)))))

(defn parse-outline
  "Load path's grammar (if any), parse `text`, and derive the source-code Contents outline via the common IR
   (source-fe/tree->ir → outline). Returns Promise<[{:level :text :id :line}]> — [] when there is no grammar
   for the extension or the parse fails. (@codemirror-free — tree-sitter + the IR only — so it stays in this eager
   ns rather than the lazily-loaded editor chunk.)"
  [text path]
  (if-let [grammar (grammar-for path)]
    (-> (load-grammar grammar)
        ;; thread the grammar's language so `outline` dispatches deterministically (markup → headings, code →
        ;; declarations) rather than guessing from node kinds Markdown/Org/LaTeX share
        (.then (fn [loaded] (source-fe/outline (source-fe/tree->ir (parse-tree loaded text) (or text ""))
                                               (or (:language grammar) (:id grammar)))))
        (.catch (fn [_] [])))
    (js/Promise.resolve [])))
