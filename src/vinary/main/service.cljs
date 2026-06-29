(ns vinary.main.service
  "Main-process IO service: read files, push their content to the renderer over the Mediator IPC seam,
   and watch every retained local file path so edits stream back live. Rendering happens in the renderer
   (the ESM remark pipeline is browser-friendly), so main stays a thin, side-effect-at-the-edge
   service."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["child_process" :as cp]
            ["chokidar" :refer [watch]]
            ["./content_service.js" :as content-service]
            [clojure.set :as set]
            [clojure.string :as str]
            [vinary.main.file-kind :as file-kind]
            [vinary.main.service-util :as service-util]
            ;; [vinary.main.pdf :as pdf]  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
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

(defn- archive-uri? [uri]
  (file-kind/archive-uri? uri))

(def ^:private dir-watch-options
  ;; immediate children only (depth 0) — not the recursive whole-tree watch ADR-0006 rejects.
  (clj->js {:ignoreInitial true :depth 0}))

(defn- directory? [path]
  (try (and (not (archive-uri? path)) (.isDirectory (.statSync fs path))) (catch :default _ false)))

(defn- entry->map
  "One directory child as plain data for the renderer's directory view. Symlinks are flagged and
   resolved through to report the target's dir?/size/mtime."
  [dir ^js dirent]
  (let [name    (.-name dirent)
        abs     (path/join dir name)
        ^js st  (try (.lstatSync fs abs) (catch :default _ nil))
        link?   (boolean (and st (.isSymbolicLink st)))
        ^js st* (if link? (try (.statSync fs abs) (catch :default _ st)) st)]
    {:name    name
     :path    abs
     :dir?    (boolean (and st* (.isDirectory st*)))
     :size    (when st* (.-size st*))
     :mtime   (when st* (.-mtimeMs st*))
     :symlink link?}))

(defn- list-dir
  "Immediate children of `dir` as a vector of entry maps (unsorted; the renderer sorts)."
  [dir]
  (try (mapv #(entry->map dir %) (.readdirSync fs dir #js {:withFileTypes true}))
       (catch :default _ [])))

(defn- send-parsed-content! [^js wc path]
  (-> (.openUri content-service path)
      (.then (fn [payload] (.send wc "vv:content" payload)))
      (.catch (fn [e] (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))))

(defn- send-content! [^js wc path]
  (let [kind  (kind-of path)
        stamp (js/Date.now)]
    (case (service-util/route {:directory? (directory? path)
                               :archive?   (archive-uri? path)
                               :kind       kind})
      ;; directory — a filesystem listing rendered in-pane (not shelled out to the OS file manager).
      ;; Routed FIRST (in service-util/route) so a real directory lists even when its extensionless name
      ;; classifies as "text" — otherwise the parser fs.readSyncs a directory fd → EISDIR.
      :directory
      (.send wc "vv:content" (clj->js {:path path :kind "directory" :entries (list-dir path) :stamp stamp}))

      ;; archive URI or parser-owned local kind — main streams/parses and returns a bounded preview payload.
      ;; Plain text routes through the parser so extensionless logs / delimited files can be sniffed
      ;; before falling back to escaped text.
      :parsed
      (send-parsed-content! wc path)

      ;; image — render by file:// path (binary, not read as text)
      :image
      (.send wc "vv:content" (clj->js {:path path :kind "image" :stamp stamp}))

      ;; html — render live in the web view (loaded by its file:// URL), not shown as escaped source.
      ;; Live-refresh re-sends with a new stamp → content-view remounts the web host → the page reloads.
      :html
      (.send wc "vv:content" (clj->js {:path path :kind "html" :stamp stamp}))

      ;; pdf — stream the bytes to the renderer's in-DOM pdf.js view (parity with markdown/source).
      ;; Live-refresh re-sends bytes through the normal watcher → the view re-renders like any doc.
      ;; (The native-PDF WebContentsView path is RETIRED in favor of in-renderer pdf.js — ADR 0013.)
      :pdf
      (try (let [bytes (.readFileSync fs path)]
             (.send wc "vv:content" (clj->js {:path path :kind "pdf" :bytes bytes :stamp stamp})))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))

      ;; everything else (source, markdown, diagram, …) — read as UTF-8 text and send with its kind.
      :text
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
  (when-not (archive-uri? path)
    (send-tree! wc path))
  (when-not (or (archive-uri? path) (get @watchers path))
    (let [dir? (directory? path)
          w    (watch path (if dir? dir-watch-options watch-options))]
      (if dir?
        ;; a directory tab: re-list as immediate children appear / vanish / change
        (doseq [ev ["add" "unlink" "addDir" "unlinkDir" "change"]]
          (.on w ev (fn [_] (send-open-content! path))))
        (do
          (.on w "change" (fn [_] (send-open-content! path)))
          (.on w "add"    (fn [_] (send-open-content! path)))))
      (swap! watchers assoc path w))))

(defn close! [path]
  (swap! retained-paths disj path)
  (unwatch-file! path))

;; ---- URI-bar path auto-completion ----
(defn- expand-home [p]
  (cond
    (= p "~")                 (os/homedir)
    (str/starts-with? p "~/") (path/join (os/homedir) (subs p 2))
    :else                     p))

(defn- complete
  "Path-completion data for a raw URI-bar input: the children of the directory it points into, plus
   whether the exact input is an existing file/dir and its resolved absolute path. The renderer filters
   `entries` by the typed basename. A leading file:// and ~ are resolved here (the renderer is sandboxed)."
  [raw]
  (let [s        (let [s (str raw)] (if (str/starts-with? s "file://") (subs s 7) s))
        s        (expand-home s)
        sep-i    (max (.lastIndexOf s "/") (.lastIndexOf s "\\"))
        dir-part (if (neg? sep-i) "." (subs s 0 (inc sep-i)))
        parent   (try (.resolve path dir-part) (catch :default _ dir-part))
        entries  (if (directory? parent) (list-dir parent) [])
        target   (try (.resolve path s) (catch :default _ s))
        ^js st   (try (.statSync fs target) (catch :default _ nil))]
    {:input   (str raw)
     :dir     parent
     :target  target
     :entries entries
     :exists? (boolean st)
     :dir?    (boolean (and st (.isDirectory st)))}))

(defn init! []
  (.on ipcMain "vv:open"  (fn [^js e path] (open! (.-sender e) path)))
  (.on ipcMain "vv:close" (fn [_e path] (close! path)))
  (.handle ipcMain "vv:content-page" (fn [_e req] (.contentPage content-service req)))
  (.handle ipcMain "vv:complete-path" (fn [_e raw] (clj->js (complete raw))))
  (.on ipcMain "vv:retained-files" (fn [^js e paths] (sync-retained! (.-sender e) (js->clj paths))))
  (.on ipcMain "vv:watch-assets"
       (fn [^js e payload]
         (let [{:keys [docPath paths]} (js->clj payload :keywordize-keys true)]
           (when (get @doc-webcontents docPath)
             (swap! doc-webcontents assoc docPath (.-sender e))
             (watch-assets! docPath paths))))))
