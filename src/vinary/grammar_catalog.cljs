(ns vinary.grammar-catalog
  "Compile-time catalog of bundled tree-sitter grammars.

   The generated EDN lives in resources/grammars/catalog.edn so both the Electron main process and
   renderer resolve source extensions from the same registry."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [shadow.resource :as rc]))

(def bundled-grammars
  (->> (reader/read-string (rc/inline "grammars/catalog.edn"))
       (mapv (fn [g]
               (update g :extensions (fn [exts] (mapv str/lower-case exts)))))))

(defn- normalize-name [s]
  (some-> s str str/lower-case))

(defn- grammar-names [g]
  (remove nil? [(normalize-name (:id g)) (normalize-name (:language g))]))

(def language-aliases
  {"bash" "bash" "sh" "bash" "shell" "bash" "zsh" "bash"
   "clj" "clojure" "cljs" "clojure" "cljc" "clojure" "edn" "clojure"
   "c++" "cpp" "cc" "cpp" "cxx" "cpp"
   "js" "javascript" "jsx" "javascript" "node" "javascript"
   "ts" "typescript"
   "d2" "d2"
   "markdown" "markdown" "md" "markdown" "mdx" "markdown" "gfm" "markdown"
   "py" "python" "rb" "ruby" "rs" "rust"
   "yml" "yaml" "jsonc" "json"})

(defn grammar-for-id
  [id grammars]
  (let [id (normalize-name id)]
    (some (fn [g] (when (some #{id} (grammar-names g)) g)) grammars)))

(def bundled-by-id
  (into {} (mapcat (fn [g] (map (fn [id] [id g]) (grammar-names g))) bundled-grammars)))

(defn by-id [id]
  (get bundled-by-id (normalize-name id)))

(defn grammar-for-extension
  [ext grammars]
  (let [ext (normalize-name ext)]
    (when (seq ext)
      (some (fn [g] (when (some #{ext} (:extensions g)) g)) grammars))))

(defn grammar-for-path
  [path grammars]
  (when-let [ext (some-> (re-find #"(\.[^./\\]+)$" (str path)) second)]
    (grammar-for-extension ext grammars)))

(defn grammar-for-language
  [language grammars]
  (let [raw (normalize-name language)
        id  (get language-aliases raw raw)
        ext (when (seq raw) (str "." raw))]
    (or (grammar-for-id id grammars)
        (grammar-for-extension ext grammars))))

(def bundled-source-exts
  (into #{} (mapcat :extensions) bundled-grammars))
