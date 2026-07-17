(ns vinary.main.grammars
  "Grammar registry (main side): decides which files are 'source' (→ the read-only CodeMirror view) and
   discovers USER tree-sitter grammars under ~/.config/vinary-viewer/grammars/<lang>/ (each a
   grammar.wasm + highlights.scm, extension defaulting to .<lang>). User filetype mappings live in
   ~/.config/vinary-viewer/filetypes.edn. The combined user registry is sent to the renderer as EDN text
   (file:// urls); bundled grammars are compiled into vinary.grammar-catalog."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            [clojure.string :as str]
            [cljs.reader :as reader]
            [vinary.grammar-catalog :as grammar-catalog]))

(def ^:private plain-source-exts
  "Known source extensions whose tree-sitter grammar is not bundled yet. These still use the
   read-only source view, but without syntax decorations until a grammar entry is enabled."
  #{".sql" ".vim" ".v"})

(defonce ^:private user-grammars (atom []))   ; [{:language :extensions :wasm-url :scm-url} …]
(defonce ^:private user-filetypes (atom {}))  ; {:filenames {...} :patterns [...]}

(defn- config-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))

(defn- grammars-dir []
  (path/join (config-dir) "grammars"))

(defn- filetypes-path []
  (path/join (config-dir) "filetypes.edn"))

(defn- load-user! []
  (reset! user-grammars
          (try
            (let [dir (grammars-dir)]
              (if (.existsSync fs dir)
                (vec (keep (fn [lang]
                             (let [ld   (path/join dir lang)
                                   wasm (path/join ld "grammar.wasm")
                                   scm  (path/join ld "highlights.scm")
                                   cfg  (path/join ld "config.edn")
                                   exts (try (when (.existsSync fs cfg)
                                               (:extensions (reader/read-string (.readFileSync fs cfg "utf8"))))
                                             (catch :default _ nil))]
                               (when (and (.existsSync fs wasm) (.existsSync fs scm))
                                 {:language    lang
                                  :extensions  (or exts [(str "." lang)])
                                  :wasm-url    (str "file://" wasm)
                                  :scm-url     (str "file://" scm)})))
                           (.readdirSync fs dir)))
                []))
            (catch :default _ []))))

(defn- load-filetypes! []
  (reset! user-filetypes
          (try
            (let [p (filetypes-path)]
              (if (.existsSync fs p)
                (grammar-catalog/normalize-filetype-config (reader/read-string (.readFileSync fs p "utf8")))
                {}))
            (catch :default _ {}))))

(defn- all-grammars []
  (concat @user-grammars grammar-catalog/bundled-grammars))

(defn source?
  "Should path open in the source view? (a built-in/user grammar match, filetype mapping, or plain source ext)"
  [path]
  (let [e (str/lower-case (path/extname path))]
    (boolean (or (grammar-catalog/grammar-for-path path (all-grammars) @user-filetypes)
                 (contains? plain-source-exts e)
                 (some (fn [g] (some #{e} (:extensions g))) @user-grammars)))))

(defn push! [^js wc]
  (.send wc "vv:grammars" (pr-str {:grammars @user-grammars
                                    :filetypes @user-filetypes})))

(defonce ^:private inited (atom false))

(defn init! [^js wc]
  (load-user!)
  (load-filetypes!)
  (push! wc)                 ; per-window initial push (each new window gets the registry)
  ;; register the request handler ONCE for the whole app — multi-window would otherwise stack duplicate
  ;; listeners and push the registry once per open window on every request
  (when (compare-and-set! inited false true)
    (.on ipcMain "vv:grammars-request" (fn [^js e] (push! (.-sender e))))))
