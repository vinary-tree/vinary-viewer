(ns vinary.main.grammars
  "Grammar registry (main side): decides which files are 'source' (→ the read-only CodeMirror view) and
   discovers USER tree-sitter grammars under ~/.config/vinary-viewer/grammars/<lang>/ (each a
   grammar.wasm + highlights.scm, extension defaulting to .<lang>). The user list is sent to the
   renderer as EDN text (file:// urls); the renderer already bundles Rholang."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            [clojure.string :as str]
            [cljs.reader :as reader]))

;; built-in source extensions → the read-only CodeMirror view (highlighted iff a grammar matches)
(def ^:private code-exts
  #{".rho" ".rs" ".py" ".js" ".mjs" ".cjs" ".jsx" ".ts" ".tsx" ".clj" ".cljs" ".cljc" ".edn"
    ".go" ".c" ".h" ".cpp" ".hpp" ".cc" ".java" ".kt" ".rb" ".php" ".sh" ".bash" ".zsh"
    ".lua" ".sql" ".scala" ".hs" ".ml" ".ex" ".exs" ".erl" ".swift" ".dart" ".vim" ".el"
    ".toml" ".yaml" ".yml" ".ini" ".cfg" ".conf" ".gradle" ".cmake" ".nim" ".zig"})

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
    (boolean (or (contains? code-exts e)
                 (some (fn [g] (some #{e} (:extensions g))) @user-grammars)))))

(defn push! [^js wc] (.send wc "vv:grammars" (pr-str @user-grammars)))

(defn init! [^js wc]
  (load-user!)
  (push! wc)
  (.on ipcMain "vv:grammars-request" (fn [^js e] (push! (.-sender e)))))
