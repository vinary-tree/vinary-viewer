(ns vinary.main.service
  "Main-process IO service: read files, push their content to the renderer over the Mediator IPC seam,
   and watch every retained local file path so edits stream back live. Rendering happens in the renderer
   (the ESM remark pipeline is browser-friendly), so main stays a thin, side-effect-at-the-edge
   service."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            ["chokidar" :refer [watch]]
            [clojure.set :as set]
            [clojure.string :as str]
            [vinary.main.file-kind :as file-kind]
            [vinary.main.pdf :as pdf]
            [vinary.main.grammars :as grammars]))

(defonce ^:private watchers (atom {}))   ; path -> chokidar watcher
(defonce ^:private retained-paths (atom #{})) ; local file paths reachable from open renderer tabs/history
(defonce ^:private doc-webcontents (atom {})) ; open doc path -> current sender webContents
(defonce ^:private doc-assets (atom {}))      ; markdown doc path -> #{local media paths}
(defonce ^:private asset-watchers (atom {}))  ; local media path -> {:watcher chokidar :owners #{doc paths}}

(def ^:private watch-options
  (clj->js {:ignoreInitial true
            :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))

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
  (file-kind/kind-of grammars/source? path))

(defn- send-content! [^js wc path]
  (let [kind  (kind-of path)
        stamp (js/Date.now)]
    (cond
      ;; binary — don't read as text; images render by file:// path, PDFs in a main-owned native view.
      (#{"image" "pdf"} kind)
      (do (.send wc "vv:content" (clj->js {:path path :kind kind :stamp stamp}))
          (when (= kind "pdf") (pdf/reload! path)))   ; live-refresh the native PDF view on change

      :else
      (try (let [text (.readFileSync fs path "utf8")]
             (.send wc "vv:content" (clj->js {:path path :kind kind :text text :stamp stamp})))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)})))))))

(defn- send-open-content! [path]
  (when-let [wc (and (contains? @retained-paths path) (get @doc-webcontents path))]
    (send-content! wc path)))

(defn- refresh-asset-owners! [asset-path]
  (doseq [doc-path (:owners (get @asset-watchers asset-path))]
    (send-open-content! doc-path)))

(defn- add-asset-owner! [asset-path doc-path]
  (if (get @asset-watchers asset-path)
    (swap! asset-watchers update-in [asset-path :owners] (fnil conj #{}) doc-path)
    (let [w (watch asset-path watch-options)]
      (.on w "change" (fn [_] (refresh-asset-owners! asset-path)))
      (.on w "add"    (fn [_] (refresh-asset-owners! asset-path)))
      (.on w "unlink" (fn [_] (refresh-asset-owners! asset-path)))
      (swap! asset-watchers assoc asset-path {:watcher w :owners #{doc-path}}))))

(defn- remove-asset-owner! [asset-path doc-path]
  (when-let [{:keys [watcher owners]} (get @asset-watchers asset-path)]
    (let [owners' (disj (or owners #{}) doc-path)]
      (if (seq owners')
        (swap! asset-watchers assoc-in [asset-path :owners] owners')
        (do
          (.close ^js watcher)
          (swap! asset-watchers dissoc asset-path))))))

(defn- release-doc-assets! [doc-path]
  (doseq [asset-path (get @doc-assets doc-path #{})]
    (remove-asset-owner! asset-path doc-path))
  (swap! doc-assets dissoc doc-path))

(defn- asset-paths [paths]
  (->> paths
       (filter string?)
       (remove str/blank?)
       set))

(defn- watch-assets! [doc-path paths]
  (when (and (string? doc-path) (contains? @retained-paths doc-path) (get @doc-webcontents doc-path))
    (let [old (get @doc-assets doc-path #{})
          new (asset-paths paths)]
      (doseq [asset-path (set/difference old new)]
        (remove-asset-owner! asset-path doc-path))
      (doseq [asset-path (set/difference new old)]
        (add-asset-owner! asset-path doc-path))
      (if (seq new)
        (swap! doc-assets assoc doc-path new)
        (swap! doc-assets dissoc doc-path)))))

(defn- retained-path-set [paths]
  (->> paths
       (filter string?)
       (remove str/blank?)
       set))

(defn- unwatch-file! [path]
  (when-let [^js w (get @watchers path)]
    (.close w)
    (swap! watchers dissoc path))
  (release-doc-assets! path)
  (swap! doc-webcontents dissoc path))

(defn sync-retained!
  "Replace the retained local-file set. Watchers and media ownership are released for paths no open tab
   history can still reach."
  [^js wc paths]
  (let [old @retained-paths
        new (retained-path-set paths)]
    (reset! retained-paths new)
    (swap! doc-webcontents
           (fn [m]
             (reduce (fn [acc p] (assoc acc p wc)) m new)))
    (doseq [path (set/difference old new)]
      (unwatch-file! path))))

(defn open!
  "Send the file's content now, and watch it (once) so changes re-send live (and on re-create — many
   editors land an atomic save that way)."
  [^js wc path]
  (swap! doc-webcontents assoc path wc)
  (send-content! wc path)
  (send-tree! wc path)
  (when-not (get @watchers path)
    (let [w (watch path watch-options)]
      (.on w "change" (fn [_] (send-open-content! path)))
      (.on w "add"    (fn [_] (send-open-content! path)))
      (swap! watchers assoc path w))))

(defn close! [path]
  (swap! retained-paths disj path)
  (unwatch-file! path))

(defn init! []
  (.on ipcMain "vv:open"  (fn [^js e path] (open! (.-sender e) path)))
  (.on ipcMain "vv:close" (fn [_e path] (close! path)))
  (.on ipcMain "vv:retained-files" (fn [^js e paths] (sync-retained! (.-sender e) (js->clj paths))))
  (.on ipcMain "vv:watch-assets"
       (fn [^js e payload]
         (let [{:keys [docPath paths]} (js->clj payload :keywordize-keys true)]
           (when (get @doc-webcontents docPath)
             (swap! doc-webcontents assoc docPath (.-sender e))
             (watch-assets! docPath paths))))))
