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
            [vinary.main.dir-walk :as dir-walk]
            [vinary.main.file-kind :as file-kind]
            [vinary.main.retention :as retention]
            [vinary.main.service-util :as service-util]
            ;; [vinary.main.pdf :as pdf]  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
            [vinary.main.grammars :as grammars]))

(defonce ^:private watchers (atom {}))   ; path -> chokidar watcher
(defonce ^:private window-owners (atom {}))  ; webContents.id -> {:wc wc :paths #{retained doc paths}}
(defonce ^:private doc-assets (atom {}))      ; markdown doc path -> #{local media paths}
(defonce ^:private asset-watchers (atom {}))  ; local media path -> {:watcher chokidar :owners #{doc paths}}
(defonce ^:private tree-windows (atom {}))    ; wc.id -> {:wc :offered {root entry} :visible #{root} :expanded #{[root dir]}}
(defonce ^:private tree-watchers (atom {}))   ; [root dir] -> {:watcher :owners #{wc.id} :timer timeout? :synthetic?}

(def ^:private watch-options
  (clj->js {:ignoreInitial true
            :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))

;; ---- git file-tree (sidebar) ----
(declare directory? reconcile-tree-watchers!)

(defn- git [args cwd]
  (try
    (str/trim (cp/execFileSync "git" (clj->js args)
                               (clj->js {:cwd cwd :encoding "utf8"
                                         :maxBuffer (* 64 1024 1024) :stdio ["ignore" "pipe" "ignore"]})))
    (catch :default _ nil)))

(defn- repo-files
  "The navigable file listing for an already-resolved git root, or nil when git fails."
  [root]
  (when-let [out (git ["ls-files" "--cached" "--others" "--exclude-standard"] root)]
    ;; `--cached` describes the index, so a tracked path deleted/renamed only in the working tree remains
    ;; in that output. Remove git's deleted set while keeping the rename destination from `--others`.
    (when-let [deleted-out (git ["ls-files" "--deleted"] root)]
      (let [deleted (into #{} (remove str/blank?) (str/split deleted-out #"\n"))]
        (into []
              (comp (remove str/blank?) (remove deleted))
              (str/split out #"\n"))))))

(defn- repo-tree
  "The git repository containing file-path, as {:root <abs> :files [repo-relative…]}, or nil if not
   in a repo / git unavailable."
  [file-path]
  (let [dir  (if (directory? file-path) file-path (path/dirname file-path))
        root (git ["rev-parse" "--show-toplevel"] dir)]
    (when (and root (not (str/blank? root)))
      ;; --cached (tracked) + --others (untracked) --exclude-standard (drop .gitignore'd/excluded clutter):
      ;; shows files you're actively creating — including the one you just opened — while keeping build
      ;; output / node_modules out. repo-files also subtracts --deleted because the index still names a
      ;; tracked path whose working-tree file was deleted or renamed without being staged.
      (when-let [files (repo-files root)]
        {:root root :files files :synthetic? false}))))

(defn- kind-of [^String path]
  (file-kind/kind-of grammars/source? path))

(defn- archive-uri? [uri]
  (file-kind/archive-uri? uri))

(def ^:private dir-watch-options
  ;; immediate children only (depth 0) — not the recursive whole-tree watch ADR-0006 rejects.
  (clj->js {:ignoreInitial true :depth 0}))

(defn- directory? [path]
  (try (and (not (archive-uri? path)) (.isDirectory (.statSync fs path))) (catch :default _ false)))

(defn- offer-tree! [^js wc entry]
  (let [id (.-id wc)]
    (swap! tree-windows
           (fn [windows]
             (let [window (get windows id {:wc wc :offered {} :visible #{} :expanded #{}})]
               (assoc windows id (-> window
                                     (assoc :wc wc)
                                     (assoc-in [:offered (:root entry)]
                                               (select-keys entry [:root :synthetic?])))))))))

(defn- send-tree-entry! [^js wc entry]
  (when (and entry wc (try (not (.isDestroyed wc)) (catch :default _ false)))
    (offer-tree! wc entry)
    (.send wc "vv:tree" (clj->js entry))))

(defn- send-tree!
  "Send the sidebar tree for a path: its git repository when it has one, else its containing directory as a
   synthetic project root, so a file outside every repo still gets a Files tab. The fallback walk lives in
   vinary.main.dir-walk, which is Electron-free and therefore node-testable."
  [^js wc file-path]
  (when-let [t (or (repo-tree file-path) (dir-walk/dir-tree file-path (directory? file-path)))]
    (send-tree-entry! wc t)))

(defn- relative-scope
  "A root-relative `/` path for directory, or nil when directory escapes root."
  [root directory]
  (let [rel (try (.relative path root directory) (catch :default _ nil))]
    (when (and (string? rel)
               (not (.isAbsolute path rel))
               (not= rel "..")
               (not (str/starts-with? rel (str ".." (.-sep path)))))
      (str/replace rel #"\\" "/"))))

(defn- prefix-files [scope files]
  (if (str/blank? scope)
    (vec files)
    (let [prefix (str scope "/")]
      (filterv #(str/starts-with? % prefix) files))))

(defn- tree-entry-for
  "List one visible project root, optionally scoped to an expanded descendant directory. Scoped
   payloads keep every file root-relative so renderer replacement never needs platform path logic."
  [root synthetic? directory]
  (when-let [scope (relative-scope root directory)]
    (if synthetic?
      (let [subfiles (dir-walk/walk-dir directory)
            files    (if (str/blank? scope)
                       subfiles
                       (mapv #(str scope "/" %) subfiles))]
        (cond-> {:root root :files files :synthetic? true}
          (not (str/blank? scope)) (assoc :scope scope)))
      (when-let [files (repo-files root)]
        (cond-> {:root root :files (prefix-files scope files) :synthetic? false}
          (not (str/blank? scope)) (assoc :scope scope))))))

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

(defn- siblings-group
  "The document GROUP of `p`: every same-directory, same-stem file that EXISTS on disk and classifies to a
   `file-kind/group-kind` (pdf + markdown/org/latex/mermaid/diff), as `[{:path :kind}]` — `p` itself included.
   Lets the renderer offer the Preview/Source combo over all collocated representations of one document (e.g.
   paper.org + paper.tex + paper.pdf), replacing the old pairwise sibling-pdf/sibling-source. Computed main-side
   (the renderer has no fs access); the pure candidate arithmetic + classification live in file-kind (node-tested),
   here we only add the filesystem existence check."
  [p]
  (into []
        (comp (filter (fn [cand] (try (.isFile (.statSync fs cand)) (catch :default _ false))))
              (map (fn [cand] {:path cand :kind (kind-of cand)}))
              (filter (fn [m] (contains? file-kind/group-kinds (:kind m)))))
        (file-kind/group-candidate-paths p)))

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
                 ;; the PDF's collocated document group (its authored sources + itself), so the renderer offers
                 ;; the Preview/Source combo over every representation (paper.pdf ↔ paper.tex/paper.org). A lone
                 ;; PDF's group is size 1 → the renderer shows no toggle.
                 grp   (siblings-group path)]
             (.send wc "vv:content" (clj->js {:path path :kind "pdf" :bytes bytes :stamp stamp :siblings grp})))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))

      ;; everything else (source, markdown, org, diagram, …) — read as UTF-8 text and send with its kind.
      ;; :meta {:size} is REQUIRED, not decorative: stream-flag/enabled? gates on it, so without it a large
      ;; markdown/org document silently never streams (it compares 0 against the 256 KiB threshold). The
      ;; :parsed route already supplies meta; this one used to omit it.
      :text
      (try (let [text (.readFileSync fs path "utf8")
                 size (try (.-size (.statSync fs path)) (catch :default _ nil))
                 ;; a group-kind source (markdown/org/latex/mermaid/diff) advertises its collocated document group
                 ;; (its exported PDF + any sibling sources), so the renderer offers the Preview/Source combo over
                 ;; every representation. Non-group text kinds (source/text) carry no group → no toggle.
                 grp  (when (contains? file-kind/group-kinds kind) (siblings-group path))]
             (.send wc "vv:content" (clj->js (cond-> {:path path :kind kind :text text :stamp stamp}
                                               size (assoc :meta {:size size})
                                               grp  (assoc :siblings grp)))))
           (catch :default e (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))))))

(declare unwatch-file!)

(defn- owner-id [^js wc] (.-id wc))

(defn- live-webcontents? [^js wc]
  (boolean (and wc (try (not (.isDestroyed wc)) (catch :default _ false)))))

(defn- retained? [path]
  (retention/retained? @window-owners path))

(defn- webcontents-for
  "Every live renderer retaining `path`. Destroyed entries are ignored; the Electron
   `destroyed` listener normally removes them eagerly, and this guard makes watcher delivery
   safe during shutdown races."
  [path]
  (into []
        (keep (fn [id]
                (let [wc (get-in @window-owners [id :wc])]
                  (when (live-webcontents? wc) wc))))
        (retention/owner-ids-for @window-owners path)))

;; ---- expansion-scoped Files-tree watchers ----------------------------------------------------------
(declare refresh-watched-tree! ensure-window!)

(def ^:private tree-refresh-debounce-ms 150)

(defn- stop-tree-watcher! [scope]
  (when-let [{:keys [watcher timer]} (get @tree-watchers scope)]
    (when timer (js/clearTimeout timer))
    (.close ^js watcher)
    (swap! tree-watchers dissoc scope)))

(defn- schedule-tree-refresh! [scope]
  (when-let [{:keys [timer]} (get @tree-watchers scope)]
    (when timer (js/clearTimeout timer))
    (let [timer (js/setTimeout #(refresh-watched-tree! scope) tree-refresh-debounce-ms)]
      (when (.-unref timer) (.unref timer))
      (swap! tree-watchers assoc-in [scope :timer] timer))))

(defn- start-tree-watcher! [[root directory :as scope] owners synthetic?]
  ;; Every expanded directory owns one SHALLOW subscription. Descendants become watched only when their
  ;; disclosures are effectively open in the mounted Files view and renderer syncs them separately.
  (let [w (watch directory (clj->js {:ignoreInitial true :depth 0 :followSymlinks false}))]
    (doseq [event ["add" "unlink" "addDir" "unlinkDir"]]
      (.on w event (fn [_] (schedule-tree-refresh! scope))))
    ;; A gitignore edit can alter membership without adding/removing the ignore file itself. Other content
    ;; changes leave the file-derived tree identical and deliberately do not re-list it.
    (.on w "change" (fn [changed]
                       (when (= ".gitignore" (path/basename changed))
                         (schedule-tree-refresh! scope))))
    (swap! tree-watchers assoc scope {:watcher w :owners owners :timer nil
                                      :synthetic? (boolean synthetic?)})
    ;; `ignoreInitial` deliberately suppresses the directory's existing children, but that also creates a
    ;; listing→subscription race: an entry created after expansion's explicit listing and before chokidar is
    ;; ready can be classified as initial. One ready-time reconciliation closes that gap.
    (.once w "ready" (fn [] (schedule-tree-refresh! scope)))))

(defn- desired-tree-watcher-owners []
  (reduce-kv
   (fn [acc id {:keys [expanded]}]
     (reduce (fn [m scope] (update m scope (fnil conj #{}) id)) acc expanded))
   {}
   @tree-windows))

(defn- scope-source [[root _directory] owners]
  (some (fn [id] (get-in @tree-windows [id :offered root :synthetic?])) owners))

(defn- reconcile-tree-watchers! []
  (let [desired (desired-tree-watcher-owners)
        old     (set (keys @tree-watchers))
        new     (set (keys desired))]
    (doseq [scope (set/difference old new)] (stop-tree-watcher! scope))
    (doseq [[scope owners] desired]
      (let [synthetic? (boolean (scope-source scope owners))]
        (if-let [entry (get @tree-watchers scope)]
          (if (= synthetic? (:synthetic? entry))
            (swap! tree-watchers assoc-in [scope :owners] owners)
            (do (stop-tree-watcher! scope)
                (start-tree-watcher! scope owners synthetic?)))
          (start-tree-watcher! scope owners synthetic?))))))

(defn- live-tree-window [id]
  (let [^js wc (get-in @tree-windows [id :wc])]
    (when (and wc (try (not (.isDestroyed wc)) (catch :default _ false))) wc)))

(defn- refresh-watched-tree! [[root directory :as scope]]
  (when-let [{:keys [owners synthetic?]} (get @tree-watchers scope)]
    (swap! tree-watchers assoc-in [scope :timer] nil)
    (when-let [entry (tree-entry-for root synthetic? directory)]
      (let [entry (cond-> entry
                    ;; The root listing is complete, but marking it as the empty relative scope tells the
                    ;; renderer this is an automatic exact-root replacement, not a new-project arrival.
                    (= root directory) (assoc :scope ""))]
        (doseq [id owners]
          (when (contains? (get-in @tree-windows [id :expanded] #{}) scope)
            (when-let [wc (live-tree-window id)]
              (send-tree-entry! wc entry))))))))

(defn- offered-visible-root [^js wc root]
  (let [id (.-id wc)]
    (when (contains? (get-in @tree-windows [id :visible] #{}) root)
      (get-in @tree-windows [id :offered root]))))

(defn- sync-tree-roots! [^js wc roots]
  (let [id      (ensure-window! wc)
        offered (set (keys (get-in @tree-windows [id :offered] {})))
        visible (set/intersection offered (->> roots (filter string?) set))]
    (swap! tree-windows update id
           (fn [window]
             (let [window (or window {:wc wc :offered {} :expanded #{}})]
               (-> window
                   (assoc :wc wc :visible visible)
                   (update :expanded #(into #{} (filter (fn [[root _]] (contains? visible root))) %))))))
    (reconcile-tree-watchers!)))

(defn- sync-tree-expanded! [^js wc scopes]
  (let [id      (ensure-window! wc)
        visible (get-in @tree-windows [id :visible] #{})
        valid   (into #{}
                      (keep (fn [{:keys [root path]}]
                              (when (and (string? root) (string? path)
                                         (contains? visible root)
                                         (some? (relative-scope root path)))
                                [root path])))
                      scopes)]
    (swap! tree-windows assoc-in [id :expanded] valid)
    (reconcile-tree-watchers!)))

(defn- refresh-tree-request [^js wc {:keys [root path]}]
  (when-not (and (string? root) (string? path))
    (throw (js/Error. "invalid tree refresh request")))
  (let [offer (offered-visible-root wc root)]
    (when-not offer (throw (js/Error. "tree root is not visible in this window")))
    (when-not (some? (relative-scope root path))
      (throw (js/Error. "tree refresh path escapes its project root")))
    (or (tree-entry-for root (:synthetic? offer) path)
        (throw (js/Error. "tree listing failed")))))

(defn- refresh-all-tree-requests [^js wc]
  (let [id (.-id wc)]
    (into []
          (keep (fn [root]
                  (let [offer (get-in @tree-windows [id :offered root])]
                    (tree-entry-for root (:synthetic? offer) root))))
          (get-in @tree-windows [id :visible] #{}))))

(defn- release-window! [id]
  (let [paths (retention/paths-for @window-owners id)]
    (swap! window-owners retention/drop-owner id)
    (swap! tree-windows dissoc id)
    (reconcile-tree-watchers!)
    (doseq [path paths]
      (when-not (retained? path) (unwatch-file! path)))))

(defn- ensure-window! [^js wc]
  (let [id (owner-id wc)]
    (swap! tree-windows update id #(or % {:wc wc :offered {} :visible #{} :expanded #{}}))
    (when-not (contains? @window-owners id)
      (swap! window-owners retention/sync-owner id wc #{})
      ;; Process-lifetime watchers must not retain a dead window. One listener per owner
      ;; releases only that window's paths and preserves paths shared with other windows.
      (.once wc "destroyed" (fn [] (release-window! id))))
    id))

(defn- retained-by? [^js wc path]
  (contains? (retention/paths-for @window-owners (owner-id wc)) path))

(defn- send-open-content! [path]
  ;; the single choke point for every watcher/poller-driven re-send (local file watch, asset watch, remote
  ;; poll). The doc's window may have CLOSED since it was retained — under the resident daemon the watcher
  ;; outlives it — so skip a destroyed webContents (else `.send` throws "Object has been destroyed").
  (doseq [wc (webcontents-for path)]
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
    (if-not (retained? path)
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

(defn- watch-assets! [^js wc doc-path paths]
  (when (and (string? doc-path) (retained-by? wc doc-path))
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
  (release-doc-assets! path))

(defn sync-retained!
  "Replace one renderer window's retained-file set. Watchers, remote pollers, and media
   ownership are released only when no live window can still reach a path."
  [^js wc paths]
  (let [id  (ensure-window! wc)
        old (retention/paths-for @window-owners id)
        new (retained-path-set paths)]
    (swap! window-owners retention/sync-owner id wc new)
    (doseq [path (set/difference old new)]
      (when-not (retained? path) (unwatch-file! path)))))

(defn open!
  "Send the file's content now, and watch it (once) so changes re-send live (and on re-create — many
   editors land an atomic save that way)."
  [^js wc path]
  (ensure-window! wc)
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

(defn close! [^js wc path]
  (let [id (ensure-window! wc)]
    (swap! window-owners retention/drop-path id path)
    (when-not (retained? path) (unwatch-file! path))))

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
  (.on ipcMain "vv:close" (fn [^js e path] (close! (.-sender e) path)))
  (.handle ipcMain "vv:content-page" (fn [_e req] (.contentPage content-service req)))
  ;; bounded-memory document streaming (session pull-cursor) — open/pull/close a paused file read
  (.handle ipcMain "vv:stream-open"  (fn [_e req] (.streamOpen  content-service req)))
  (.handle ipcMain "vv:stream-pull"  (fn [_e req] (.streamPull  content-service req)))
  (.handle ipcMain "vv:stream-close" (fn [_e req] (.streamClose content-service req)))
  (.handle ipcMain "vv:complete-path"
           (fn [_e raw] (if (file-kind/remote-uri? raw) (complete-remote raw) (clj->js (complete raw)))))
  ;; Files-tree ownership is independent of document retention: projects persist in the sidebar after their
  ;; tabs close, while automatic subscriptions exist only for effectively expanded directories.
  (.on ipcMain "vv:tree-roots"
       (fn [^js e roots] (sync-tree-roots! (.-sender e) (js->clj roots))))
  (.on ipcMain "vv:tree-expanded"
       (fn [^js e scopes]
         (sync-tree-expanded! (.-sender e) (js->clj scopes :keywordize-keys true))))
  (.handle ipcMain "vv:tree-refresh"
           (fn [^js e req]
             (clj->js (refresh-tree-request (.-sender e) (js->clj req :keywordize-keys true)))))
  (.handle ipcMain "vv:tree-refresh-all"
           (fn [^js e] (clj->js (refresh-all-tree-requests (.-sender e)))))
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
           (watch-assets! (.-sender e) docPath paths)))))
