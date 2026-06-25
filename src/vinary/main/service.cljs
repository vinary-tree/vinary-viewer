(ns vinary.main.service
  "Main-process IO service: read files, push their content to the renderer over the Mediator IPC seam,
   and watch every OPEN file (one chokidar watcher per path) so edits stream back live. Rendering
   happens in the renderer (the ESM remark pipeline is browser-friendly), so main stays a thin,
   side-effect-at-the-edge service."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            ["chokidar" :refer [watch]]
            [clojure.string :as str]
            [vinary.main.pdf :as pdf]
            [vinary.main.diagram :as diagram]
            [vinary.main.grammars :as grammars]))

(defonce ^:private watchers (atom {}))   ; path -> chokidar watcher

;; ---- git file-tree (sidebar) ----
(defn- git [args cwd]
  (try
    (str/trim (cp/execFileSync "git" (clj->js args)
                               (clj->js {:cwd cwd :encoding "utf8"
                                         :maxBuffer (* 64 1024 1024) :stdio ["ignore" "pipe" "ignore"]})))
    (catch :default _ nil)))

(defn- repo-tree
  "The git repository containing file-path, as {:root <abs> :files [repo-relative…]}, or nil if not
   in a repo / git unavailable."
  [file-path]
  (let [dir  (path/dirname file-path)
        root (git ["rev-parse" "--show-toplevel"] dir)]
    (when (and root (not (str/blank? root)))
      (let [out   (git ["ls-files"] root)
            files (when out (vec (remove str/blank? (str/split out #"\n"))))]
        {:root root :files (or files [])}))))

(defn- send-tree! [^js wc file-path]
  (when-let [t (repo-tree file-path)]
    (.send wc "vv:tree" (clj->js t))))

(defn- kind-of [^String path]
  (let [lower (str/lower-case path)]
    (cond
      (re-find #"\.(md|markdown|mdx)$" lower)                     "markdown"
      (re-find #"\.(png|jpe?g|gif|svg|webp|bmp|ico|avif)$" lower) "image"
      (re-find #"\.pdf$" lower)                                   "pdf"
      (re-find #"\.(d2|puml|plantuml|pu|iuml|wsd|mmd|mermaid|dot|gv|graphviz)$" lower) "diagram"
      (grammars/source? path)                                    "source"
      :else                                                      "text")))

(defn- send-content! [^js wc path]
  (let [kind (kind-of path)]
    (cond
      ;; binary — don't read as text; images render by file:// path, PDFs in a main-owned native view.
      (#{"image" "pdf"} kind)
      (do (.send wc "vv:content" (clj->js {:path path :kind kind}))
          (when (= kind "pdf") (pdf/reload! path)))   ; live-refresh the native PDF view on change

      ;; diagrams render to SVG in main (shelling out to d2/plantuml/mmdc/dot) and ship as HTML
      (= "diagram" kind)
      (try (.send wc "vv:content" (clj->js {:path path :kind kind :html (diagram/render path)}))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))

      :else
      (try (let [text (.readFileSync fs path "utf8")]
             (.send wc "vv:content" (clj->js {:path path :kind kind :text text})))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)})))))))

(defn open!
  "Send the file's content now, and watch it (once) so changes re-send live (and on re-create — many
   editors land an atomic save that way)."
  [^js wc path]
  (send-content! wc path)
  (send-tree! wc path)
  (when-not (get @watchers path)
    (let [w (watch path (clj->js {:ignoreInitial true
                                  :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
      (.on w "change" (fn [_] (send-content! wc path)))
      (.on w "add"    (fn [_] (send-content! wc path)))
      (swap! watchers assoc path w))))

(defn close! [path]
  (when-let [^js w (get @watchers path)] (.close w) (swap! watchers dissoc path)))

(defn init! []
  (.on ipcMain "vv:open"  (fn [^js e path] (open! (.-sender e) path)))
  (.on ipcMain "vv:close" (fn [_e path] (close! path))))
