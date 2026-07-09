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
   "bnfc" "bnfc" "lbnf" "bnfc" "cf" "bnfc"
   "markdown" "markdown" "md" "markdown" "mdx" "markdown" "gfm" "markdown"
   "py" "python" "rb" "ruby" "rs" "rust"
   "emacs-lisp" "elisp" "el" "elisp"   ; Org #+begin_src emacs-lisp/el → the bundled elisp grammar
   "yml" "yaml" "jsonc" "json"})

(def built-in-filetypes
  "Filename/pattern mappings for extensionless files whose grammar is known.

   User config uses the same shape in ~/.config/vinary-viewer/filetypes.edn:

   {:filenames {\"Cargo.lock\" \"toml\"}
    :patterns  {\"*.config\" \"toml\"}}"
  {:filenames {"Cargo.lock" "toml"}
   :patterns []})

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

(defn grammar-for-language
  [language grammars]
  (let [raw (normalize-name language)
        id  (get language-aliases raw raw)
        ext (when (seq raw) (str "." raw))]
    (or (grammar-for-id id grammars)
        (grammar-for-extension ext grammars))))

(defn- normalize-path [path]
  (str/replace (str path) #"\\" "/"))

(defn- basename [path]
  (last (str/split (normalize-path path) #"/")))

(defn- filename-map [m]
  (into {}
        (keep (fn [[filename filetype]]
                (let [filename (some-> filename str not-empty)
                      filetype (some-> filetype normalize-name)]
                  (when (and filename (seq filetype))
                    [filename filetype]))))
        (or m {})))

(defn- pattern-entry [entry]
  (let [[pattern filetype]
        (cond
          (map-entry? entry) entry
          (vector? entry) entry
          (map? entry) [(or (:pattern entry) (:glob entry) (:filename entry))
                        (or (:filetype entry) (:type entry) (:language entry))]
          :else nil)
        pattern (some-> pattern str not-empty)
        filetype (some-> filetype normalize-name)]
    (when (and pattern (seq filetype))
      {:pattern pattern
       :filetype filetype
       :path? (boolean (str/includes? pattern "/"))})))

(defn normalize-filetype-config
  "Return a canonical filetype mapping config. Invalid entries are ignored."
  [config]
  {:filenames (filename-map (:filenames config))
   :patterns  (->> (:patterns config)
                   (map pattern-entry)
                   (remove nil?)
                   vec)})

(defn- regex-escape-char [ch]
  (if (re-find #"[\\.^$+(){}\[\]|]" ch)
    (str "\\" ch)
    ch))

(defn- glob->regex [glob]
  (loop [chars (seq (str glob))
         out "^"]
    (if-let [ch (first chars)]
      (case ch
        "*" (if (= "*" (second chars))
              (recur (nnext chars) (str out ".*"))
              (recur (next chars) (str out "[^/]*")))
        "?" (recur (next chars) (str out "[^/]"))
        (recur (next chars) (str out (regex-escape-char ch))))
      (re-pattern (str out "$")))))

(defn- glob-match? [glob s]
  (boolean (re-matches (glob->regex glob) (or s ""))))

(defn mapped-filetype
  "Resolve a configured filetype id for path, if any.

   Filename entries match the basename exactly. Pattern entries without a slash match the basename;
   entries with a slash match the normalized path. Patterns are evaluated in config order."
  [path config]
  (let [{:keys [filenames patterns]} (normalize-filetype-config config)
        path' (normalize-path path)
        name  (basename path')]
    (or (get filenames name)
        (some (fn [{:keys [pattern filetype path?]}]
                (when (glob-match? pattern (if path? path' name))
                  filetype))
              patterns))))

(defn grammar-for-path
  ([path grammars] (grammar-for-path path grammars nil))
  ([path grammars filetypes]
   (or (some-> (mapped-filetype path filetypes) (grammar-for-language grammars))
       (some-> (mapped-filetype path built-in-filetypes) (grammar-for-language grammars))
       (when-let [ext (some-> (re-find #"(\.[^./\\]+)$" (str path)) second)]
         (grammar-for-extension ext grammars)))))

(def bundled-source-exts
  (into #{} (mapcat :extensions) bundled-grammars))
