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
            ["./ssh_transport.js" :as ssh-transport]
            [cljs.reader :as reader]
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
      ;; --cached (tracked) + --others (untracked) --exclude-standard (drop .gitignore'd/excluded clutter):
      ;; shows files you're actively creating — including the one you just opened — while keeping build
      ;; output / node_modules out. --cached and --others are disjoint, so no de-duplication is needed.
      (let [out   (git ["ls-files" "--cached" "--others" "--exclude-standard"] root)
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

(defn- sibling-pdf
  "The same-directory, same-stem `.pdf` companion of `p` if it exists on disk, else nil. Lets a previewable
   document (LaTeX/Org/Markdown) collocated with an exported PDF offer a Document↔PDF representation switch —
   computed main-side because the renderer has no filesystem access. The pure candidate-path arithmetic lives in
   file-kind/pdf-sibling-path (node-tested); here we only add the filesystem existence check."
  [p]
  (when-let [pdf (file-kind/pdf-sibling-path p)]
    (when (try (.isFile (.statSync fs pdf)) (catch :default _ false))
      pdf)))

(defn- sibling-source
  "The first existing same-directory, same-stem previewable SOURCE companion of a `.pdf` `p` (`.tex`/`.md`/
   `.org`/…), else nil — the reverse of `sibling-pdf`. Lets a PDF opened alongside its source offer the same
   [Doc | PDF] switch (\"Doc\" navigates the tab to the rendered source). Candidate paths come from the pure,
   node-tested file-kind/source-sibling-paths; here we add the filesystem existence check."
  [p]
  (some (fn [cand] (when (try (.isFile (.statSync fs cand)) (catch :default _ false)) cand))
        (file-kind/source-sibling-paths p)))

(defn- resolve-diff-source
  "Locate a diff's referenced file `rel` on disk: try it relative to the diff's own directory, then walk up the
   ancestors (a diff is usually generated from a repo root but may be viewed from a subdirectory). Returns an
   absolute path, or nil when not found. Powers the side-by-side view's full-file enrichment."
  [diff-path rel]
  (loop [dir (.dirname path diff-path) depth 0]
    (when (and dir (< depth 30))
      (let [cand (.join path dir rel)]
        (if (try (.isFile (.statSync fs cand)) (catch :default _ false))
          cand
          (let [parent (.dirname path dir)]
            (when (not= parent dir) (recur parent (inc depth)))))))))

(defn- load-diff-sources
  "Resolve each referenced `rel` path of the diff at `diff-path` against the filesystem and read the found ones →
   {rel → utf8-content}. The renderer has no fs access, so the side-by-side view requests this over IPC."
  [diff-path rels]
  (reduce (fn [acc rel]
            (if-let [p (resolve-diff-source diff-path rel)]
              (if-let [content (try (.readFileSync fs p "utf8") (catch :default _ nil))]
                (assoc acc rel content)
                acc)
              acc))
          {} (or rels [])))

(defn- send-parsed-content! [^js wc path]
  (-> (.openUri content-service path)
      (.then (fn [payload] (.send wc "vv:content" payload)))
      (.catch (fn [e] (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))))

;; A remote (ssh://sftp://) URI is read ASYNCHRONOUSLY by the transport-backed content service. The grammar-aware
;; `kind-of` is threaded in so a remote `.rs` renders as highlighted source (not sniffed text); openRemoteUri
;; stats internally to decide list-vs-read and to fill meta.size (the streaming gate). Errors surface as vv:error.
(defn- send-remote-content! [^js wc uri]
  (-> (.openRemoteUri content-service uri (kind-of uri))
      (.then  (fn [payload] (.send wc "vv:content" payload)))
      (.catch (fn [e] (.send wc "vv:error" (clj->js {:path uri :message (.-message e)}))))))

(defn- conf-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))

(defn- read-remote-prefs
  "The `:remote {:poll-seconds :poll-dirs?}` block from settings.edn (read main-side so the poller is
   self-sufficient), or nil. Polling is opt-in: absent / non-positive :poll-seconds means no live-refresh."
  []
  (try
    (let [p   (path/join (conf-dir) "settings.edn")
          txt (when (.existsSync fs p) (.readFileSync fs p "utf8"))
          m   (when (and txt (not (str/blank? txt))) (reader/read-string txt))]
      (:remote m))
    (catch :default _ nil)))

(defn- send-content! [^js wc path]
  (if (file-kind/remote-uri? path)
    (send-remote-content! wc path)
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
      (try (let [bytes (.readFileSync fs path)
                 ;; a PDF collocated with its previewable source advertises it, so the renderer can offer the
                 ;; reverse [Doc | PDF] switch ("Doc" navigates to the rendered source — paper.pdf → paper.tex).
                 src   (sibling-source path)]
             (.send wc "vv:content" (clj->js (cond-> {:path path :kind "pdf" :bytes bytes :stamp stamp}
                                               src (assoc :sourceSibling src)))))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))

      ;; everything else (source, markdown, org, diagram, …) — read as UTF-8 text and send with its kind.
      ;; :meta {:size} is REQUIRED, not decorative: stream-flag/enabled? gates on it, so without it a large
      ;; markdown/org document silently never streams (it compares 0 against the 256 KiB threshold). The
      ;; :parsed route already supplies meta; this one used to omit it.
      :text
      (try (let [text (.readFileSync fs path "utf8")
                 size (try (.-size (.statSync fs path)) (catch :default _ nil))
                 ;; a previewable doc collocated with a same-stem exported PDF advertises it, so the renderer can
                 ;; offer a Document↔PDF switch (kind-agnostic: LaTeX papers AND Org/Markdown invoices).
                 pdf  (when (contains? #{"latex" "org" "markdown"} kind) (sibling-pdf path))]
             (.send wc "vv:content" (clj->js (cond-> {:path path :kind kind :text text :stamp stamp}
                                               size (assoc :meta {:size size})
                                               pdf  (assoc :pdfSibling pdf)))))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))))))

(defn- send-open-content! [path]
  (when-let [wc (and (contains? @retained-paths path) (get @doc-webcontents path))]
    (send-content! wc path)))

;; ---- remote (SSH) live-refresh via polling ----
;; SFTP has no inotify, so a remote doc cannot be chokidar-watched. Instead a per-doc poller re-stats the URI
;; and, on a size/mtime change, re-sends it (send-open-content! → a fresh Date.now stamp → the renderer remounts
;; / re-streams). Opt-in via settings.edn `:remote {:poll-seconds …}`; exponential backoff (to 60s) + ±25%
;; jitter avoid hammering a downed host; directory listings poll slower (or not at all). Lifecycle is tied to
;; unwatch-file!, so closing a tab / navigating away stops the poll for free (the same guarantee as watchers).
(defonce ^:private remote-pollers (atom {}))   ; ssh-uri -> {:sig {…} :base ms :backoff ms :poll-dirs? bool :timer t}

(defn- stop-remote-poller! [path]
  (when-let [{:keys [timer]} (get @remote-pollers path)]
    (js/clearTimeout timer))
  (swap! remote-pollers dissoc path))

(declare poll-remote!)

(defn- reschedule-remote-poll! [path delay-ms]
  (when (get @remote-pollers path)
    (let [jitter (js/Math.floor (* (js/Math.random) 0.25 delay-ms))
          t      (js/setTimeout #(poll-remote! path) (+ delay-ms jitter))]
      (when (.-unref t) (.unref t))
      (swap! remote-pollers update path assoc :timer t))))

(defn- poll-remote! [path]
  (when-let [entry (get @remote-pollers path)]
    (if-not (and (contains? @retained-paths path) (get @doc-webcontents path))
      (stop-remote-poller! path)                       ; tab gone → stop polling
      (let [base (:base entry)]
        (-> (.remoteStat ssh-transport path)
            (.then (fn [^js st]
                     ;; the tab may have closed (stop-remote-poller! → dissoc) DURING this async stat — bail so a
                     ;; stale `update` can't resurrect a zombie poller (which would leak a connection)
                     (when (get @remote-pollers path)
                       (let [is-dir (.-isDirectory st)
                             sig    {:size (.-size st) :mtime (.-mtime st) :dir is-dir}]
                         (if (and is-dir (not (:poll-dirs? entry)))
                           (stop-remote-poller! path)     ; a directory listing, and dir-polling is off → stop
                           (do
                             (when (and (:sig entry) (not= sig (:sig entry)))
                               (send-open-content! path))
                             (swap! remote-pollers update path assoc :sig sig :backoff base)
                             (reschedule-remote-poll! path (if is-dir (max base 15000) base))))))))
            (.catch (fn [_]
                      (when (get @remote-pollers path)   ; likewise: don't resurrect a poller stopped mid-stat
                        (let [next (min 60000 (* 2 (:backoff entry base)))]
                          (swap! remote-pollers update path assoc :backoff next)
                          (reschedule-remote-poll! path next))))))))))

(defn- start-remote-poller! [path]
  (let [prefs (read-remote-prefs)
        secs  (:poll-seconds prefs)]
    (when (and (number? secs) (pos? secs) (not (get @remote-pollers path)))
      (let [base (* 1000 (max 1 secs))]
        (swap! remote-pollers assoc path {:sig nil :base base :backoff base
                                          :poll-dirs? (boolean (:poll-dirs? prefs))})
        (reschedule-remote-poll! path base)))))

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
  (stop-remote-poller! path)                 ; a remote doc's poller stops when the tab can no longer reach it
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
  (when-not (or (archive-uri? path) (file-kind/remote-uri? path))
    (send-tree! wc path))                    ; the git tree sidebar is a LOCAL-repo concern
  (cond
    ;; remote (ssh://sftp://): no inotify over SSH, so poll for changes instead of chokidar-watching (which
    ;; would statSync/watch a non-path). Opt-in via settings.edn :remote :poll-seconds.
    (file-kind/remote-uri? path)
    (start-remote-poller! path)
    (not (or (archive-uri? path) (get @watchers path)))
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

(defn- complete-remote
  "Async URI-bar completion for a remote (ssh://sftp://) input: list the directory it points into via SFTP so
   the renderer can filter by the typed basename. Same shape as `complete`, but resolved as a Promise (the
   renderer is sandboxed and has no SFTP access)."
  [raw]
  (let [s        (str raw)
        sep-i    (.lastIndexOf s "/")
        dir-part (if (neg? sep-i) s (subs s 0 (inc sep-i)))]
    (-> (.remoteReaddir ssh-transport dir-part)
        (.then (fn [entries]
                 (clj->js {:input s :dir dir-part :target s :exists? true :dir? true
                           :entries (mapv (fn [^js e] {:name (.-name e) :path (.-path e) :dir? (.-dir e)
                                                       :size (.-size e) :mtime (.-mtime e) :symlink (.-symlink e)})
                                          entries)})))
        (.catch (fn [_] (clj->js {:input s :dir dir-part :target s :entries [] :exists? false :dir? false}))))))

(defn init! []
  (.on ipcMain "vv:open"  (fn [^js e path] (open! (.-sender e) path)))
  (.on ipcMain "vv:close" (fn [_e path] (close! path)))
  (.handle ipcMain "vv:content-page" (fn [_e req] (.contentPage content-service req)))
  ;; bounded-memory document streaming (session pull-cursor) — open/pull/close a paused file read
  (.handle ipcMain "vv:stream-open"  (fn [_e req] (.streamOpen  content-service req)))
  (.handle ipcMain "vv:stream-pull"  (fn [_e req] (.streamPull  content-service req)))
  (.handle ipcMain "vv:stream-close" (fn [_e req] (.streamClose content-service req)))
  (.handle ipcMain "vv:complete-path"
           (fn [_e raw] (if (file-kind/remote-uri? raw) (complete-remote raw) (clj->js (complete raw)))))
  ;; load a collocated sibling PDF's bytes into the renderer's pdf-cache WITHOUT opening a tab (the Document↔PDF
  ;; representation switch renders the sibling in-place). Returns the Buffer, or nil if unreadable; a remote
  ;; sibling reads over SFTP (so Doc↔PDF works for a remote paper.tex ↔ paper.pdf too).
  (.handle ipcMain "vv:load-pdf-bytes"
           (fn [_e p]
             (if (file-kind/remote-uri? p)
               (.remoteReadFile ssh-transport p)
               (try (.readFileSync fs p) (catch :default _ nil)))))
  ;; resolve a diff's referenced files (relative to the diff, walking up ancestors) → {rel → content}, for the
  ;; side-by-side view's full-file enrichment. Renderer-driven (it has no fs). Remote diffs resolve over SFTP.
  (.handle ipcMain "vv:load-diff-sources"
           (fn [_e req]
             (let [{:keys [diffPath files]} (js->clj req :keywordize-keys true)]
               (if (file-kind/remote-uri? diffPath)
                 (.loadRemoteDiffSources content-service diffPath (clj->js files))
                 (clj->js (load-diff-sources diffPath files))))))
  ;; fetch a remote asset's bytes → a data: URL, so a remote Markdown/Office doc's relative images render (the
  ;; renderer can't reach the host, and file:// cannot either). `relativeTo` is the remote doc's URI.
  (.handle ipcMain "vv:load-remote-asset"
           (fn [_e req]
             (let [{:keys [uri relativeTo]} (js->clj req :keywordize-keys true)]
               (.loadRemoteAsset content-service uri relativeTo))))
  (.on ipcMain "vv:retained-files" (fn [^js e paths] (sync-retained! (.-sender e) (js->clj paths))))
  (.on ipcMain "vv:watch-assets"
       (fn [^js e payload]
         (let [{:keys [docPath paths]} (js->clj payload :keywordize-keys true)]
           (when (get @doc-webcontents docPath)
             (swap! doc-webcontents assoc docPath (.-sender e))
             (watch-assets! docPath paths))))))
