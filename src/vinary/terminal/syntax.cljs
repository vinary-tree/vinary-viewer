(ns vinary.terminal.syntax
  "Headless tree-sitter → ANSI syntax highlighter — the terminal-colour analog of the CodeMirror-coupled
   renderer.syntax. Loads the web-tree-sitter runtime + the bundled grammars (`grammar.wasm` + `highlights.scm`)
   FROM DISK (resources/public/grammars/<id>/), parses code, and maps highlight capture names to SGR colours
   (the same palette ir.backend.ansi uses). web-tree-sitter loads async, but the ANSI backend is a pure sync
   function, so the flow is two-phase: `ensure-grammars!` pre-loads the languages a document needs (Promise),
   then `highlighter` returns a SYNC `(fn [lang code] → [[span …] …])` the backend calls per code block. A
   language whose grammar isn't bundled/loaded yields nil → the backend renders that code plain."
  (:require ["web-tree-sitter" :refer [Language Parser Query]]
            ["path" :as path]
            ["fs" :as fs]
            [clojure.string :as str]
            [vinary.grammar-catalog :as gc]))

;; highlights.scm capture name → ir.backend.ansi palette colour (falls back to the dotted-prefix root)
(def ^:private capture-color
  {"keyword" :magenta "keyword.function" :magenta "keyword.operator" :magenta "conditional" :magenta
   "repeat" :magenta "include" :magenta "operator" :cyan "function" :blue "function.method" :blue
   "function.builtin" :blue "method" :blue "type" :yellow "type.builtin" :yellow "namespace" :yellow
   "constant" :yellow "constant.builtin" :yellow "number" :yellow "float" :yellow "boolean" :yellow
   "string" :green "string.special" :green "character" :green "comment" :gray "variable" :white
   "variable.builtin" :red "parameter" :white "property" :cyan "field" :cyan "attribute" :cyan
   "punctuation" :gray "punctuation.bracket" :gray "punctuation.delimiter" :gray "punctuation.special" :cyan
   "tag" :red "constructor" :yellow "escape" :cyan "label" :magenta "symbol" :magenta})

(defn- color-for [nm]
  (or (capture-color nm) (capture-color (first (str/split nm #"\.")))))

(defn- res-dir [] (path/join js/__dirname ".." ".." "resources" "public"))

(defonce ^:private ts-ready
  (delay (.init Parser #js {:locateFile (fn [_ _] (path/join (res-dir) "js" "tree-sitter.wasm"))})))

(defonce ^:private loaded (atom {}))   ; grammar id -> {:language :query} | nil (tried, unavailable)

(defn- load! [id]
  (let [dir  (path/join (res-dir) "grammars" id)
        wasm (path/join dir "grammar.wasm")
        scm  (path/join dir "highlights.scm")]
    (if (and (.existsSync fs wasm) (.existsSync fs scm))
      (-> @ts-ready
          (.then (fn [_] (.load Language wasm)))
          (.then (fn [lang] (swap! loaded assoc id {:language lang :query (Query. lang (.readFileSync fs scm "utf8"))}) nil))
          (.catch (fn [_] (swap! loaded assoc id nil) nil)))
      (do (swap! loaded assoc id nil) (js/Promise.resolve nil)))))

(defn- id-for [lang] (some-> (gc/grammar-for-language lang gc/bundled-grammars) :id))

(defn ensure-grammars!
  "Pre-load the grammars for `langs` (language names/ids/aliases). Returns a Promise resolved once every one is
   loaded or determined unavailable — call before rendering, so `highlighter` can then run synchronously."
  [langs]
  (let [ids (->> langs (map id-for) (remove nil?) distinct (remove #(contains? @loaded %)))]
    (js/Promise.all (into-array (map load! ids)))))

(defn- captures->colors
  "A JS array (length = code UTF-16 units) of colour-or-nil per unit; later (more specific) captures override."
  [^js code ^js caps]
  (let [n   (.-length code)
        arr (js/Array. n)]
    (doseq [cap (array-seq caps)]
      (when-let [color (color-for (.-name cap))]
        (let [^js node (.-node cap) s (max 0 (.-startIndex node)) e (min n (.-endIndex node))]
          (loop [i s] (when (< i e) (aset arr i color) (recur (inc i)))))))
    arr))

(defn- line-spans [^js code colors]
  ;; split into lines (UTF-16 indices align with tree-sitter's) → per-line vectors of {:text :style}
  (let [n (.-length code)]
    (loop [i 0, line-start 0, cur nil, buf-start 0, spans [], out []]
      (letfn [(flush-span [spans i] (if (< buf-start i)
                                      (conj spans {:text (.substring code buf-start i) :style (when cur {:fg cur})})
                                      spans))]
        (cond
          (>= i n)  (conj out (flush-span spans i))
          (= "\n" (.charAt code i))
          (recur (inc i) (inc i) nil (inc i) [] (conj out (flush-span spans i)))
          :else
          (let [c (aget colors i)]
            (if (= c cur)
              (recur (inc i) line-start cur buf-start spans out)
              (recur (inc i) line-start c i (flush-span spans i) out))))))))

(defn highlighter
  "Return a sync highlighter `(fn [lang code] → [[span …] …] | nil)` over the pre-loaded grammars. Spans match
   the ir.backend.ansi :highlight port; nil when the language grammar isn't loaded (→ plain code)."
  []
  (fn [lang code]
    (when-let [{:keys [^js language ^js query]} (some-> (id-for lang) (@loaded))]
      (let [^js parser (Parser.)]
        (.setLanguage parser language)
        (let [^js tree (.parse parser (or code ""))
              colors (captures->colors (or code "") (.captures query (.-rootNode tree)))]
          (line-spans (or code "") colors))))))
