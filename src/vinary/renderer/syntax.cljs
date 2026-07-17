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

(defn view-from-dom
  "Return the CodeMirror EditorView associated with node, if node is inside one."
  [^js node]
  (when (and node (.-findFromDOM EditorView))
    (.findFromDOM EditorView node)))

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

(defn- capture-spans! [spans ^js tree ^js query offset]
  (doseq [cap (array-seq (.captures query (.-rootNode tree)))]
    (let [^js node (.-node cap)
          cls      (class-for (.-name cap) node)
          s        (+ offset (.-startIndex node))
          e        (+ offset (.-endIndex node))]
      (when (and cls (< s e))
        (.push spans {:from s :to e :class cls}))))
  spans)

(defn- capture-ranges! [ranges ^js tree ^js query offset]
  (doseq [span (array-seq (capture-spans! (array) tree query offset))]
    (mark-range! ranges (:class span) (:from span) (:to span)))
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
  (when-let [^js content (first (nodes-of-type block #{"code_fence_content"}))]
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
                     (doseq [^js inline (nodes-of-type root #{"inline"})]
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

(defn selected-text
  "Return the selected text in a CodeMirror view, or nil when all ranges are empty."
  [^js view]
  (when view
    (let [state  (.-state view)
          ranges (.. state -selection -ranges)
          pieces (->> (range (.-length ranges))
                      (keep (fn [i]
                              (let [r (.at ranges i)
                                    from (.-from r)
                                    to   (.-to r)]
                                (when (< from to)
                                  (.sliceDoc state from to))))))]
      (when (seq pieces)
        (str/join "\n" pieces)))))

(defn selection-start
  "Return the first selected position in a CodeMirror view, or nil."
  [^js view]
  (when view
    (let [ranges (.. view -state -selection -ranges)]
      (some (fn [i]
              (let [r (.at ranges i)]
                (when (< (.-from r) (.-to r)) (.-from r))))
            (range (.-length ranges))))))

(defn pos-at-coords [^js view x y]
  (when view
    (.posAtCoords view #js {:x x :y y})))

(defn line-info-at
  "Return {:line :column :text :line-from} for a CodeMirror document position."
  [^js view pos]
  (when (and view (number? pos))
    (let [doc  (.. view -state -doc)
          line (.lineAt doc pos)]
      {:line (.-number line)
       :column (inc (- pos (.-from line)))
       :text (.-text line)
       :line-from (.-from line)})))

(defn- highlight-field [decos]
  (.define StateField
           #js {:create  (fn [_] decos)
                :update  (fn [v _] v)            ; read-only: decorations are computed once
                :provide (fn [f] (.. EditorView -decorations (from f)))}))

;; the currently-mounted source EditorView, so a source Contents-outline click can scroll it to a line
(defonce ^:private current-view (atom nil))

;; a pending preview→source jump line: set by the :source/want-line fx BEFORE toggling to source remounts the
;; view, consumed when create-source-view mounts (mirrors renderer.scroll want!→apply!). defonce survives reload.
(defonce ^:private pending-source-line (atom nil))
(defn want-source-line! [line] (reset! pending-source-line line))

(defn current-source-line
  "The 1-based cursor line of the mounted source view (the anchor for a keyboard/palette 'Go to preview' invoked
   from source with no click target) — the selection start, else the document start. nil when no source view is
   mounted."
  []
  (when-let [^js view @current-view]
    (:line (line-info-at view (or (selection-start view) 0)))))

(defn parse-outline
  "Load path's grammar (if any), parse `text`, and derive the source-code Contents outline via the common IR
   (source-fe/tree->ir → outline). Returns Promise<[{:level :text :id :line}]> — [] when there is no grammar
   for the extension or the parse fails."
  [text path]
  (if-let [grammar (grammar-for path)]
    (-> (load-grammar grammar)
        ;; thread the grammar's language so `outline` dispatches deterministically (markup → headings, code →
        ;; declarations) rather than guessing from node kinds Markdown/Org/LaTeX share
        (.then (fn [loaded] (source-fe/outline (source-fe/tree->ir (parse-tree loaded text) (or text ""))
                                               (or (:language grammar) (:id grammar)))))
        (.catch (fn [_] [])))
    (js/Promise.resolve [])))

(defn scroll-source-to-line!
  "Scroll the mounted source view to 1-based `line` (for a source Contents-outline click). No-op if no source
   view is mounted."
  [line]
  (when-let [^js view @current-view]
    (let [doc (.. view -state -doc)
          n   (max 1 (min line (.-lines doc)))
          pos (.-from (.line doc n))]
      (.dispatch view #js {:effects   (.scrollIntoView EditorView pos #js {:y "start"})
                           :selection #js {:anchor pos}}))))

(defn viewport-top-line
  "The 1-based document line at the top of `view`'s viewport — the anchor for the source-view Contents scroll-spy
   (the analog of a pixel scrollTop for the DOM preview spy). `.lineBlockAtHeight` maps the scroller's scrollTop
   (a document-relative height) to the line block currently at the viewport top. nil when `view` is absent."
  [^js view]
  (when view
    (let [block (.lineBlockAtHeight view (.-scrollTop (.-scrollDOM view)))
          doc   (.. view -state -doc)]
      (.-number (.lineAt doc (.-from block))))))

(defn current-viewport-line
  "The 1-based viewport-top line of the MOUNTED source view (nil when none is mounted) — the source coordinate
   saved into history when leaving a :source facet (the :view-pos cofx). A pixel scrollTop is meaningless for a
   source view because CodeMirror scrolls its own `.cm-scroller`, not the `.vv-content` DOM scroller."
  []
  (when-let [^js view @current-view] (viewport-top-line view)))

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
    (reset! current-view view)          ; register for source-outline line-scroll
    (when-let [l @pending-source-line]  ; consume a pending preview→source jump (deferred across the remount)
      (reset! pending-source-line nil)
      (scroll-source-to-line! l))
    (when grammar
      (-> (highlight-ranges text grammar)
          (.then (fn [ranges]
                   (.dispatch view #js {:effects (.reconfigure hl (highlight-field (decoration-set ranges)))})))
          (.catch (fn [e] (js/console.warn "[vv] grammar load failed:" e)))))
    view))
