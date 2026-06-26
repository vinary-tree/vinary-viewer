(ns vinary.main.grammars
  "Grammar registry (main side): decides which files are 'source' (→ the read-only CodeMirror view) and
   discovers USER tree-sitter grammars under ~/.config/vinary-viewer/grammars/<lang>/ (each a
   grammar.wasm + highlights.scm, extension defaulting to .<lang>). The user list is sent to the
   renderer as EDN text (file:// urls); bundled grammars are compiled into vinary.grammar-catalog."
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

(defn- grammars-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer" "grammars")))

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

(defn source?
  "Should path open in the source view? (a built-in code extension, or a user grammar's extension)"
  [path]
  (let [e (str/lower-case (path/extname path))]
    (boolean (or (contains? grammar-catalog/bundled-source-exts e)
                 (contains? plain-source-exts e)
                 (some (fn [g] (some #{e} (:extensions g))) @user-grammars)))))

(defn push! [^js wc] (.send wc "vv:grammars" (pr-str @user-grammars)))

(defn init! [^js wc]
  (load-user!)
  (push! wc)
  (.on ipcMain "vv:grammars-request" (fn [^js e] (push! (.-sender e)))))
