(ns vinary.renderer.syntax
  "Read-only source preview: a CodeMirror 6 editor whose syntax highlighting comes from web-tree-sitter
   (parse → highlights.scm query → CodeMirror decorations). Adapted from LightningBug's lib/editor/
   syntax.cljs, reduced to the read-only case (no incremental editing — the doc is set once and
   re-created on live-refresh). A grammar registry maps file extensions to a tree-sitter `.wasm` +
   `highlights.scm`; bundled grammars are shipped in resources/public/grammars, and more can be
   dropped in ~/.config/vinary-viewer/grammars/."
  (:require [clojure.string :as str]
            ["@codemirror/state" :refer [EditorState Compartment StateField]]
            ["@codemirror/view" :refer [EditorView Decoration lineNumbers]]
            ["web-tree-sitter" :refer [Language Parser Query]]
            [vinary.grammar-catalog :as grammar-catalog]))

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

(defn register-user!
  "Install the user grammar list pushed from main (vv:grammars)."
  [grammars]
  (reset! user-grammars (vec grammars)))

(defn- all-grammars []
  (concat @user-grammars bundled-grammars))

(defn grammar-for
  "The grammar config whose extensions include path's extension, or nil (→ a plain read-only view)."
  [path]
  (grammar-catalog/grammar-for-path path (all-grammars)))

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

(defn- mark-range! [ranges cls s e]
  (when (and cls (< s e))
    (.push ranges (.range (.mark Decoration #js {:class cls}) s e))))

(defn- capture-ranges! [ranges ^js tree ^js query offset]
  (doseq [cap (array-seq (.captures query (.-rootNode tree)))]
    (let [^js node (.-node cap)
          cls      (class-for (.-name cap) node)
          s        (+ offset (.-startIndex node))
          e        (+ offset (.-endIndex node))]
      (mark-range! ranges cls s e)))
  ranges)

(defn- decoration-set [ranges]
  (.set Decoration ranges true))   ; sort=true (handles overlapping/nested captures)

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

(defn- highlight-fenced-code! [ranges text ^js block]
  (when-let [content (first (nodes-of-type block #{"code_fence_content"}))]
    (mark-range! ranges "cm-md-code-block" (.-startIndex content) (.-endIndex content))
    (if-let [grammar (some-> (fenced-language text block) grammar-for-language)]
      (-> (load-grammar grammar)
          (.then (fn [loaded]
                   (let [tree (parse-tree loaded (node-text text content))]
                     (capture-ranges! ranges tree (:query loaded) (.-startIndex content)))))
          (.catch (fn [e] (js/console.warn "[vv] fenced code grammar failed:" e))))
      (js/Promise.resolve nil))))

(defn- generic-highlight-ranges [text grammar]
  (-> (load-grammar grammar)
      (.then (fn [loaded]
               (let [tree   (parse-tree loaded text)
                     ranges (array)]
                 (capture-ranges! ranges tree (:query loaded) 0))))))

(defn- markdown-highlight-ranges [text grammar]
  (let [inline-grammar (grammar-by-id "markdown-inline")
        inline-p      (if inline-grammar (load-grammar inline-grammar) (js/Promise.resolve nil))]
    (-> (js/Promise.all #js [(load-grammar grammar) inline-p])
        (.then (fn [arr]
                 (let [block-loaded (aget arr 0)
                       inline-loaded (aget arr 1)
                       tree         (parse-tree block-loaded text)
                       root         (.-rootNode tree)
                       ranges       (array)
                       code-jobs    (array)]
                   (capture-ranges! ranges tree (:query block-loaded) 0)
                   (when inline-loaded
                     (doseq [inline (nodes-of-type root #{"inline"})]
                       (let [inline-tree (parse-tree inline-loaded (node-text text inline))]
                         (capture-ranges! ranges inline-tree (:query inline-loaded) (.-startIndex inline)))))
                   (doseq [block (nodes-of-type root #{"fenced_code_block"})]
                     (when-let [job (highlight-fenced-code! ranges text block)]
                       (.push code-jobs job)))
                   (if (pos? (.-length code-jobs))
                     (-> (js/Promise.all code-jobs) (.then (fn [_] ranges)))
                     ranges)))))))

(defn- highlight-ranges [text grammar]
  (if (markdown-grammar? grammar)
    (markdown-highlight-ranges text grammar)
    (generic-highlight-ranges text grammar)))

(defn- highlight-field [decos]
  (.define StateField
           #js {:create  (fn [_] decos)
                :update  (fn [v _] v)            ; read-only: decorations are computed once
                :provide (fn [f] (.. EditorView -decorations (from f)))}))

(defn create-source-view
  "Mount a read-only CodeMirror view of text in parent. If grammar (a {:wasm-url :scm-url}) is given,
   asynchronously load the tree-sitter grammar and reconfigure with highlighting. Returns the view."
  [^js parent text grammar]
  (let [hl   (Compartment.)
        exts #js [(.of (.-readOnly EditorState) true)
                  (.of (.-editable EditorView) false)
                  (lineNumbers)
                  (.-lineWrapping EditorView)        ; a pre-built Extension, not a Facet (no .of)
                  (.of hl #js [])]
        state (.create EditorState #js {:doc text :extensions exts})
        view  (EditorView. #js {:state state :parent parent})]
    (when grammar
      (-> (highlight-ranges text grammar)
          (.then (fn [ranges]
                   (.dispatch view #js {:effects (.reconfigure hl (highlight-field (decoration-set ranges)))})))
          (.catch (fn [e] (js/console.warn "[vv] grammar load failed:" e)))))
    view))
