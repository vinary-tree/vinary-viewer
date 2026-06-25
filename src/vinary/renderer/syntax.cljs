(ns vinary.renderer.syntax
  "Read-only source preview: a CodeMirror 6 editor whose syntax highlighting comes from web-tree-sitter
   (parse → highlights.scm query → CodeMirror decorations). Adapted from LightningBug's lib/editor/
   syntax.cljs, reduced to the read-only case (no incremental editing — the doc is set once and
   re-created on live-refresh). A grammar registry maps file extensions to a tree-sitter `.wasm` +
   `highlights.scm`; Rholang is bundled, more can be dropped in ~/.config/vinary-viewer/grammars/."
  (:require [clojure.string :as str]
            ["@codemirror/state" :refer [EditorState Compartment StateField]]
            ["@codemirror/view" :refer [EditorView Decoration lineNumbers]]
            ["web-tree-sitter" :refer [Language Parser]]))

;; tree-sitter capture name → CodeMirror CSS class (styled in app.css against the --vv-* palette)
(def ^:private style-map
  {"keyword" "cm-keyword" "keyword.operator" "cm-keyword"
   "operator" "cm-operator" "variable" "cm-variable" "variable.parameter" "cm-variable"
   "number" "cm-number" "string" "cm-string" "string.special" "cm-string"
   "boolean" "cm-boolean" "comment" "cm-comment" "type" "cm-type" "type.builtin" "cm-type"
   "constant" "cm-constant" "constant.builtin" "cm-constant" "function" "cm-function"
   "function.builtin" "cm-function" "constructor" "cm-type" "method" "cm-function"
   "property" "cm-property" "punctuation" "cm-punctuation" "punctuation.delimiter" "cm-punctuation"
   "punctuation.bracket" "cm-bracket" "label" "cm-label"})

(defn- class-for [name]
  (or (get style-map name) (get style-map (first (str/split name #"\.")))))

;; ---- grammar registry: bundled (shipped in resources/) + user (from main, file:// urls) ----
(def ^:private bundled-grammars
  [{:language "rholang" :extensions [".rho"]
    :wasm-url "grammars/rholang/grammar.wasm" :scm-url "grammars/rholang/highlights.scm"}])

(defonce ^:private user-grammars (atom []))

(defn register-user!
  "Install the user grammar list pushed from main (vv:grammars)."
  [grammars]
  (reset! user-grammars (vec grammars)))

(defn grammar-for
  "The grammar config whose extensions include path's extension, or nil (→ a plain read-only view)."
  [path]
  (when-let [e (some-> (re-find #"(\.[^./\\]+)$" path) second str/lower-case)]
    (some (fn [g] (when (some #{e} (:extensions g)) g))
          (concat @user-grammars bundled-grammars))))

;; ---- tree-sitter init (once) + grammar cache ----
(defonce ^:private ts-ready
  (delay (.init Parser #js {:locateFile (fn [_ _] "js/tree-sitter.wasm")})))

(defonce ^:private lang-cache (atom {}))   ; wasm-url -> Promise<Language>

(defn- load-language [wasm-url]
  (or (get @lang-cache wasm-url)
      (let [p (-> @ts-ready (.then (fn [_] (.load Language wasm-url))))]
        (swap! lang-cache assoc wasm-url p)
        p)))

(defn- build-decorations [^js tree ^js query]
  (let [captures (.captures query (.-rootNode tree))
        ranges   (array)]
    (doseq [cap (array-seq captures)]
      (let [^js node (.-node cap)
            cls      (class-for (.-name cap))
            s        (.-startIndex node)
            e        (.-endIndex node)]
        (when (and cls (< s e))
          (.push ranges (.range (.mark Decoration #js {:class cls}) s e)))))
    (.set Decoration ranges true)))   ; sort=true (handles overlapping/nested captures)

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
      (-> (js/Promise.all #js [(load-language (:wasm-url grammar))
                               (-> (js/fetch (:scm-url grammar)) (.then #(.text %)))])
          (.then (fn [arr]
                   (let [lang   (aget arr 0)
                         scm    (aget arr 1)
                         parser (Parser.)]
                     (.setLanguage parser lang)
                     (let [tree  (.parse parser text)
                           query (.query lang scm)
                           decos (build-decorations tree query)]
                       (.dispatch view #js {:effects (.reconfigure hl (highlight-field decos))})))))
          (.catch (fn [e] (js/console.warn "[vv] grammar load failed:" e)))))
    view))
