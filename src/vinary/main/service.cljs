(ns vinary.main.service
  "Main-process IO service: read files, push their content to the renderer over the Mediator IPC seam,
   and own live content/tree subscriptions locally or through an authenticated target daemon. Rendering
   happens in the renderer (the ESM remark pipeline is browser-friendly), so main stays a thin,
   side-effect-at-the-edge service."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["crypto" :as crypto]
            ["child_process" :as cp]
            ["chokidar" :refer [watch]]
            ["./content_service.js" :as content-service]
            ["./daemon_events.js" :as daemon-events]
            ["./ssh_transport.js" :as ssh-transport]
            [cljs.reader :as reader]
            [clojure.set :as set]
            [clojure.string :as str]
            [vinary.main.dir-walk :as dir-walk]
            [vinary.main.doc-overrides :as doc-overrides]
            [vinary.main.diff-source :as diff-source]
            [vinary.main.git :as git]
            [vinary.main.file-kind :as file-kind]
            [vinary.main.retention :as retention]
            [vinary.main.service-util :as service-util]
            ;; [vinary.main.pdf :as pdf]  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
            [vinary.main.grammars :as grammars]))

(defonce ^:private watchers (atom {}))   ; path -> chokidar watcher
(defonce ^:private window-owners (atom {}))  ; webContents.id -> {:wc wc :paths #{retained doc paths}}
(defonce ^:private doc-assets (atom {}))      ; markdown doc path -> #{local media paths}
(defonce ^:private asset-watchers (atom {}))  ; local media path -> {:watcher chokidar :owners #{doc paths}}
(defonce ^:private tree-windows (atom {}))    ; owner -> {:wc|:send :offered {root metadata} :visible :expanded}
(defonce ^:private tree-watchers (atom {}))   ; [root dir] -> {:watcher :owners #{owner} :timer timeout? :synthetic?}
(defonce ^:private remote-content-owners (atom {})) ; local path -> {[session sub-id] invalidate-callback}
(defonce ^:private remote-event-subs (atom #{}))    ; source-side ssh/sftp URIs subscribed to a target daemon
(defonce ^:private remote-owner-windows (atom {}))  ; source-side target owner-id -> renderer webContents
(defonce ^:private remote-tree-pending (atom {}))   ; [webContents.id opened-uri] -> target owner-id

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

(defn- empty-tree-owner [] {:offered {} :visible #{} :expanded #{}})

(defn- offer-tree-owner! [id entry source]
  (swap! tree-windows update id
         (fn [owner]
           (assoc-in (or owner (empty-tree-owner)) [:offered (:root entry)]
                     (merge (select-keys entry [:root :synthetic?]) source)))))

(defn- send-tree-owner-entry! [id entry source]
  (when entry
    (offer-tree-owner! id entry source)
    (let [{:keys [wc send]} (get @tree-windows id)]
      (cond
        (and wc (try (not (.isDestroyed ^js wc)) (catch :default _ false)))
        (.send ^js wc "vv:tree" (clj->js entry))

        send
        (send entry)))))

(defn- send-tree-entry! [^js wc entry]
  (when wc
    (send-tree-owner-entry! (.-id wc) entry {:backend :local})))

(defn- send-remote-tree-entry! [^js wc uri remote-owner-id entry]
  (when (and wc entry)
    (send-tree-owner-entry! (.-id wc) (js->clj entry :keywordize-keys true)
                            {:backend :remote :connection-uri uri :remote-owner-id remote-owner-id})))

(defn- send-tree!
  "Send the sidebar tree for a path: its git repository when it has one, else its containing directory as a
   synthetic project root, so a file outside every repo still gets a Files tab. The fallback walk lives in
   vinary.main.dir-walk, which is Electron-free and therefore node-testable."
  [^js wc file-path]
  (when-let [t (or (repo-tree file-path) (dir-walk/dir-tree file-path (directory? file-path)))]
    (send-tree-entry! wc t)))

(defn- remote-relative-scope [root directory]
  (try
    (let [^js r (js/URL. root)
          ^js d (js/URL. directory)
          same? (and (= (.-protocol r) (.-protocol d))
                     (= (.-username r) (.-username d))
                     (= (.-hostname r) (.-hostname d))
                     (= (.-port r) (.-port d)))
          rp (js/decodeURIComponent (.-pathname r))
          dp (js/decodeURIComponent (.-pathname d))
          prefix (if (str/ends-with? rp "/") rp (str rp "/"))]
      (when (and same? (or (= rp dp) (str/starts-with? dp prefix)))
        (if (= rp dp) "" (subs dp (count prefix)))))
    (catch :default _ nil)))

(defn- relative-scope
  "A root-relative `/` path for directory, or nil when directory escapes root."
  [root directory]
  (if (file-kind/remote-uri? root)
    (remote-relative-scope root directory)
    (let [rel (try (.relative path root directory) (catch :default _ nil))]
      (when (and (string? rel)
                 (not (.isAbsolute path rel))
                 (not= rel "..")
                 (not (str/starts-with? rel (str ".." (.-sep path)))))
        (str/replace rel #"\\" "/")))))

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

;; ── ADR-0038: diff documents adopt the project they describe ────────────────────────────────────────
;; A diff (an on-disk .patch, or `git diff | vv -t diff`) belongs to the repository it was generated
;; in. Two seams push that repository's tree with the diff attached as an :extras member: open! for
;; stdin diffs (the invoking cwd names the repo immediately), and the vv:load-diff-sources handler for
;; every local diff (the resolved targets prove membership). Both skip git-produced commit diffs
;; (doc-overrides/git-info) — transient synthetics that die with their tab.

(defonce ^:private adopted-diff-roots (atom {}))   ; wc-id -> {diff-path root} — repeat-adoption guard

(defn- with-diff-extra
  "Attach the ADR-0038 :extras entry for diff-path to a tree entry. A diff INSIDE the root gets no
   extra — ls-files --others already lists it, and a second row would be a double listing."
  [entry diff-path]
  (if-let [extra (service-util/diff-extra
                  {:path         diff-path
                   :basename     (.basename path diff-path)
                   :inside-root? (some? (relative-scope (:root entry) diff-path))
                   :stdin?       (doc-overrides/stdin-doc? diff-path)})]
    (assoc entry :extras [extra])
    entry))

(defn- record-adoption! [wc-id diff-path root]
  (swap! adopted-diff-roots assoc-in [wc-id diff-path] root))

(defn- send-stdin-diff-tree!
  "Seam 1 (open!): a piped-stdin DIFF adopts the git repository of its invoking cwd, so
   `git diff | vv -t diff` shows the repo in Files with the diff listed like an ordinary member.
   Non-diff stdin docs keep the ADR-0036 skip (a spill dir is not a project), and only a git repo is
   ever adopted — a synthetic root of an arbitrary shell cwd ($HOME from `curl | vv -t diff`) would
   be pure noise."
  [^js wc spill-path]
  (when (and (= "diff" (doc-overrides/effective-kind kind-of spill-path))
             (nil? (doc-overrides/git-info spill-path)))
    (when-let [cwd (doc-overrides/stdin-base-dir spill-path)]
      (when-let [entry (repo-tree cwd)]
        (record-adoption! (.-id wc) spill-path (:root entry))
        (send-tree-entry! wc (with-diff-extra entry spill-path))))))

(defn- adopt-diff-project!
  "Seam 2 (vv:load-diff-sources): after a local diff's referenced paths resolve, adopt the git
   repository that owns most of the resolved targets — an on-disk .patch stored OUTSIDE the repo it
   describes gets that repo into Files too. Cost is bounded: one rev-parse per distinct target
   directory not already under a found root, one ls-files per adoption, and the per-window guard
   makes Split rebuilds and re-renders free."
  [^js wc diff-path resolved]
  (when (and wc (nil? (doc-overrides/git-info diff-path)))
    (let [targets (into [] (keep (fn [[_ v]] (when (map? v) (:path v)))) resolved)
          dirs    (into [] (distinct) (map #(.dirname path %) targets))
          roots   (loop [ds dirs, known [], out []]
                    (if-let [d (first ds)]
                      (if-let [r (some (fn [k] (when (some? (relative-scope k d)) k)) known)]
                        (recur (rest ds) known (conj out r))
                        (let [r (git ["rev-parse" "--show-toplevel"] d)]
                          (recur (rest ds) (cond-> known (some? r) (conj r)) (conj out r))))
                      out))
          root    (service-util/dominant-root roots)]
      (when (and root
                 (not= root (get-in @adopted-diff-roots [(.-id wc) diff-path])))
        (when-let [entry (repo-tree root)]
          (record-adoption! (.-id wc) diff-path (:root entry))
          (send-tree-entry! wc (with-diff-extra entry diff-path)))))))

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

(defn- load-diff-sources
  "Resolve each referenced `rel` path of the diff at `diff-path` against the filesystem and read the found ones →
   the legacy `{rel → utf8-content}` shape, or (when `:include-paths?`) the structured
   `{rel → {:path absolute-path :content optional-utf8}}` shape used by navigable headers and Split enrichment.
   Structured path-only requests do not read file bytes.

   The renderer has no fs access, so the side-by-side view requests this over IPC.
   A piped-stdin diff has no meaningful on-disk directory of its own — its spill dir — so resolution starts
   from the INVOKING cwd instead (doc-overrides :cwd): `git diff | vv -t diff` enriches the side-by-side
   view from the repo the diff was generated in."
  ([diff-path rels] (load-diff-sources diff-path rels nil))
  ([diff-path rels {:keys [include-paths? include-content?]}]
   (let [base (or (doc-overrides/stdin-base-dir diff-path) (.dirname path diff-path))]
     (diff-source/load-local base rels {:include-paths? include-paths?
                                        :include-content? include-content?}))))

(defn- decorate-js-payload!
  "Attach the override-carried presentation keys to an outgoing JS vv:content payload: :language (the
   renderer's source-grammar pick), :stdin (tab labeled from a pipe — excluded from Open Recent), and
   :baseDir (the invoking cwd a piped document's relative assets resolve against)."
  [^js payload ov]
  (when ov
    (when (:language ov) (set! (.-language payload) (:language ov)))
    (when (:stdin? ov)   (set! (.-stdin payload) true))
    (when (and (:stdin? ov) (:cwd ov)) (set! (.-baseDir payload) (:cwd ov))))
  payload)

(defn- decorate-payload
  "decorate-js-payload! for the CLJS-map payload sends (pre-clj->js)."
  [m ov]
  (cond-> m
    (:language ov)               (assoc :language (:language ov))
    (:stdin? ov)                 (assoc :stdin true)
    (and (:stdin? ov) (:cwd ov)) (assoc :baseDir (:cwd ov))))

(defn- send-parsed-content! [^js wc path kind ov]
  ;; `kind` is passed to openUri ONLY when an override exists — for openLocal/openArchiveUri, presence of
  ;; the argument means "explicit, skip classifyName and every content sniff".
  (-> (.openUri content-service path
                (when (:kind ov) kind)
                (clj->js (cond-> {} (:delimiter ov) (assoc :delimiter (:delimiter ov)))))
      (.then (fn [payload] (.send wc "vv:content" (decorate-js-payload! payload ov))))
      (.catch (fn [e] (.send wc "vv:error" (clj->js {:path path :message (.-message e)}))))))

;; A remote (ssh://sftp://) URI is read ASYNCHRONOUSLY by the transport-backed content service. The grammar-aware
;; `kind-of` (behind any explicit override) is threaded in so a remote `.rs` renders as highlighted source (not
;; sniffed text); openRemoteUri stats internally to decide list-vs-read and to fill meta.size (the streaming
;; gate). Because that kind argument is ALWAYS passed, remote explicitness travels separately as opts.explicit.
(defn- send-remote-content! [^js wc uri]
  (let [ov (doc-overrides/for-path uri)]
    (-> (.openRemoteUri content-service uri (doc-overrides/effective-kind kind-of uri)
                        (clj->js (cond-> {}
                                   (:kind ov)      (assoc :explicit true)
                                   (:delimiter ov) (assoc :delimiter (:delimiter ov)))))
        (.then  (fn [payload] (.send wc "vv:content" (decorate-js-payload! payload ov))))
        (.catch (fn [e] (.send wc "vv:error" (clj->js {:path uri :message (.-message e)})))))))

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
    (let [ov    (doc-overrides/for-path path)
          ;; the effective kind — an explicit type (CLI `-t`, Settings ▸ File Type) beats classification on
          ;; EVERY send, including every watcher live-refresh, so the override can never silently revert.
          kind  (doc-overrides/effective-kind kind-of path)
          stamp (js/Date.now)]
    (case (service-util/route {:git-graph? (file-kind/git-graph-uri? path)
                               :directory? (directory? path)
                               :archive?   (archive-uri? path)
                               :kind       kind})
      ;; the Commit Graph virtual document (ADR-0040): validate + canonicalize the wrapped repo root
      ;; (the sync git helper is fine — one rev-parse on an interactive open) and send a tiny payload;
      ;; the commit data itself flows through [:ui :commits], shared with the sidebar panel.
      :git-graph
      (let [req-root (subs path (count "vv-git-graph://"))
            root     (git ["rev-parse" "--show-toplevel"] req-root)]
        (if (and root (not (str/blank? root)))
          (.send wc "vv:content" (clj->js {:path path :kind "git-graph" :git {:root root} :stamp stamp}))
          (.send wc "vv:error" (clj->js {:path path :message "Not a git repository"}))))

      ;; directory — a filesystem listing rendered in-pane (not shelled out to the OS file manager).
      ;; Routed FIRST (in service-util/route) so a real directory lists even when its extensionless name
      ;; classifies as "text" — otherwise the parser fs.readSyncs a directory fd → EISDIR.
      :directory
      (.send wc "vv:content" (clj->js {:path path :kind "directory" :entries (list-dir path) :stamp stamp}))

      ;; archive URI or parser-owned local kind — main streams/parses and returns a bounded preview payload.
      ;; Plain text routes through the parser for the LOG sniff (extensionless syslogs) before falling back
      ;; to escaped text — the delimiter sniff is disabled (plain text renders literally; ADR-0036).
      :parsed
      (send-parsed-content! wc path kind ov)

      ;; image — render by file:// path (binary, not read as text)
      :image
      (.send wc "vv:content" (clj->js (decorate-payload {:path path :kind "image" :stamp stamp} ov)))

      ;; html — render live in the web view (loaded by its file:// URL), not shown as escaped source.
      ;; Live-refresh re-sends with a new stamp → content-view remounts the web host → the page reloads.
      :html
      (.send wc "vv:content" (clj->js (decorate-payload {:path path :kind "html" :stamp stamp} ov)))

      ;; pdf — stream the bytes to the renderer's in-DOM pdf.js view (parity with markdown/source).
      ;; Live-refresh re-sends bytes through the normal watcher → the view re-renders like any doc.
      ;; (The native-PDF WebContentsView path is RETIRED in favor of in-renderer pdf.js — ADR 0013.)
      :pdf
      (try (let [bytes (.readFileSync fs path)
                 ;; the PDF's collocated document group (its authored sources + itself), so the renderer offers
                 ;; the Preview/Source combo over every representation (paper.pdf ↔ paper.tex/paper.org). A lone
                 ;; PDF's group is size 1 → the renderer shows no toggle.
                 grp   (siblings-group path)]
             (.send wc "vv:content" (clj->js (decorate-payload {:path path :kind "pdf" :bytes bytes :stamp stamp :siblings grp} ov))))
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
             (.send wc "vv:content" (clj->js (decorate-payload
                                              (cond-> {:path path :kind kind :text text :stamp stamp}
                                                size (assoc :meta {:size size})
                                                grp  (assoc :siblings grp))
                                              ov))))
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
   (fn [acc id {:keys [expanded offered]}]
     (reduce (fn [m [root _ :as scope]]
               ;; A source daemon never watches the URI spelling locally.  The corresponding target-daemon
               ;; owner has a :local offer and therefore participates here like an ordinary renderer window.
               (if (= :remote (get-in offered [root :backend]))
                 m
                 (update m scope (fnil conj #{}) id)))
             acc expanded))
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
            (let [source (select-keys (get-in @tree-windows [id :offered root])
                                      [:backend :connection-uri])]
              (send-tree-owner-entry! id entry source))))))))

(defn- offered-visible-root [id root]
  (when (contains? (get-in @tree-windows [id :visible] #{}) root)
    (get-in @tree-windows [id :offered root])))

(defn- sync-tree-owner-roots! [id roots]
  (let [owner   (get @tree-windows id (empty-tree-owner))
        offered (set (keys (get-in @tree-windows [id :offered] {})))
        visible (set/intersection offered (->> roots (filter string?) set))]
    (swap! tree-windows update id
           (fn [window]
             (-> (or window owner)
                 (assoc :visible visible)
                 (update :expanded #(into #{} (filter (fn [[root _]] (contains? visible root))) %)))))
    (reconcile-tree-watchers!)))

(defn- sync-tree-owner-expanded! [id scopes]
  (let [visible (get-in @tree-windows [id :visible] #{})
        valid   (into #{}
                      (keep (fn [{:keys [root path]}]
                              (when (and (string? root) (string? path)
                                         (contains? visible root)
                                         (some? (relative-scope root path)))
                                [root path])))
                      scopes)]
    (swap! tree-windows assoc-in [id :expanded] valid)
    (reconcile-tree-watchers!)))

(defn- remote-uri-prefix [uri]
  (or (second (re-find #"(?i)^(s(?:sh|ftp)://[^/]*)" (str uri))) (str uri)))

(defn- renderer-tree-owner-id [^js wc uri]
  ;; One renderer can retain both ssh:// and sftp:// spellings (or multiple ssh_config aliases) for the same
  ;; resolved connection. Keep target ownership separate by source authority so pushed roots preserve the exact
  ;; URI namespace which created them. The digest avoids sending URI userinfo to the target as an owner label.
  (let [digest (-> (.createHash crypto "sha256")
                   (.update (remote-uri-prefix uri))
                   (.digest "base64url"))]
    (str "window-" (.-id wc) "-" digest)))

(defn- remote-offers-by-owner [id]
  (reduce-kv (fn [m root offer]
               (if (= :remote (:backend offer))
                 (let [uri (:connection-uri offer)
                       key (:remote-owner-id offer)]
                   (-> m
                       (assoc-in [key :uri] uri)
                       (assoc-in [key :offers root] offer)))
                 m))
             {} (get-in @tree-windows [id :offered] {})))

(defn- ignore-remote-error! [p]
  (when p (.catch p (fn [_] nil))))

(defn- remote-owner-needed? [id remote-owner-id]
  (or (contains? (remote-offers-by-owner id) remote-owner-id)
      (some (fn [[[_id _uri] owner-id]]
              (and (= id _id) (= remote-owner-id owner-id)))
            @remote-tree-pending)))

(defn- discard-pending-remote-tree-open! [id uri remote-owner-id]
  (let [key [id uri]]
    (when (= remote-owner-id (get @remote-tree-pending key))
      (swap! remote-tree-pending dissoc key)
      (ignore-remote-error! (.cancelTreeOpen daemon-events uri remote-owner-id uri))
      (when-not (remote-owner-needed? id remote-owner-id)
        (swap! remote-owner-windows dissoc remote-owner-id)))))

(defn- cancel-pending-remote-tree-opens! [uri]
  (doseq [[[_id opened-uri :as key] remote-owner-id] @remote-tree-pending
          :when (= uri opened-uri)]
    (discard-pending-remote-tree-open! (first key) opened-uri remote-owner-id)))

(defn- deliver-pending-remote-tree-open! [^js wc uri remote-owner-id entry]
  (let [id  (.-id wc)
        key [id uri]]
    (when (= remote-owner-id (get @remote-tree-pending key))
      (swap! remote-tree-pending dissoc key)
      (send-remote-tree-entry! wc uri remote-owner-id entry))))

(defn- sync-tree-roots! [^js wc roots]
  (let [id (ensure-window! wc)]
    (sync-tree-owner-roots! id roots)
    (doseq [[remote-owner-id {:keys [uri offers]}] (remote-offers-by-owner id)]
      (ignore-remote-error!
       (.treeRoots daemon-events uri remote-owner-id
                   (clj->js (filterv #(contains? offers %) (get-in @tree-windows [id :visible] #{}))))))))

(defn- sync-tree-expanded! [^js wc scopes]
  (let [id (ensure-window! wc)]
    (sync-tree-owner-expanded! id scopes)
    (doseq [[remote-owner-id {:keys [uri offers]}] (remote-offers-by-owner id)]
      (let [remote-scopes (into []
                                (comp (filter (fn [[root _]] (contains? offers root)))
                                      (map (fn [[root p]] {:root root :path p})))
                                (get-in @tree-windows [id :expanded] #{}))]
        (ignore-remote-error!
         (.treeExpanded daemon-events uri remote-owner-id (clj->js remote-scopes)))))))

(defn- refresh-tree-request [^js wc {:keys [root path]}]
  (when-not (and (string? root) (string? path))
    (throw (js/Error. "invalid tree refresh request")))
  (let [id (.-id wc)
        offer (offered-visible-root id root)]
    (when-not offer (throw (js/Error. "tree root is not visible in this window")))
    (when-not (some? (relative-scope root path))
      (throw (js/Error. "tree refresh path escapes its project root")))
    (if (= :remote (:backend offer))
      (.treeRefresh daemon-events (:connection-uri offer) (:remote-owner-id offer) root path)
      (clj->js (or (tree-entry-for root (:synthetic? offer) path)
                   (throw (js/Error. "tree listing failed")))))))

(defn- refresh-all-tree-requests [^js wc]
  (let [id      (.-id wc)
        visible (get-in @tree-windows [id :visible] #{})
        local   (into []
                      (keep (fn [root]
                              (let [offer (get-in @tree-windows [id :offered root])]
                                (when (not= :remote (:backend offer))
                                  (tree-entry-for root (:synthetic? offer) root)))))
                      visible)
        calls   (mapv (fn [[remote-owner-id {:keys [uri]}]]
                        (.treeRefreshAll daemon-events uri remote-owner-id))
                      (remote-offers-by-owner id))]
    (if (seq calls)
      (-> (js/Promise.all (clj->js calls))
          (.then (fn [groups]
                   (clj->js (into local (mapcat #(js->clj % :keywordize-keys true))
                                  (array-seq groups))))))
      (js/Promise.resolve (clj->js local)))))

(defn- release-tree-owner! [id]
  (swap! tree-windows dissoc id)
  (reconcile-tree-watchers!))

(defn- release-window! [id]
  (let [paths (retention/paths-for @window-owners id)
        pending-owners (into {}
                             (keep (fn [[[_id uri] remote-owner-id]]
                                     (when (= id _id) [remote-owner-id {:uri uri}])))
                             @remote-tree-pending)
        remote-owners (merge pending-owners (remote-offers-by-owner id))]
    (swap! window-owners retention/drop-owner id)
    (release-tree-owner! id)
    (swap! adopted-diff-roots dissoc id)
    (git/release-owner! id)
    (swap! remote-tree-pending
           (fn [pending] (into {} (remove (fn [[[owner-id _] _]] (= id owner-id))) pending)))
    (doseq [[remote-owner-id {:keys [uri]}] remote-owners]
      (swap! remote-owner-windows dissoc remote-owner-id)
      (ignore-remote-error! (.releaseOwner daemon-events uri remote-owner-id)))
    (doseq [path paths]
      (when-not (retained? path) (unwatch-file! path)))))

(defn- ensure-window! [^js wc]
  (let [id (owner-id wc)]
    (swap! tree-windows update id #(assoc (or % (empty-tree-owner)) :wc wc))
    (when-not (contains? @window-owners id)
      (swap! window-owners retention/sync-owner id wc #{})
      ;; Process-lifetime watchers must not retain a dead window. One listener per owner
      ;; releases only that window's paths and preserves paths shared with other windows.
      (.once wc "destroyed" (fn [] (release-window! id))))
    id))

(defn- target-tree-owner-id [session-id owner-id]
  [:remote-session (str session-id) (str owner-id)])

(defn- target-tree-open! [session-id owner-id file-path send]
  (let [id (target-tree-owner-id session-id owner-id)]
    (swap! tree-windows update id
           #(assoc (or % (empty-tree-owner)) :send (fn [entry] (send (clj->js entry)))))
    (when-let [entry (or (repo-tree file-path)
                         (dir-walk/dir-tree file-path (directory? file-path)))]
      ;; The request response carries the initial tree; only subsequent watcher refreshes use :send.
      (offer-tree-owner! id entry {:backend :local})
      (clj->js entry))))

(defn- target-tree-roots! [session-id owner-id roots]
  (sync-tree-owner-roots! (target-tree-owner-id session-id owner-id) roots))

(defn- target-tree-expanded! [session-id owner-id scopes]
  (sync-tree-owner-expanded! (target-tree-owner-id session-id owner-id) scopes))

(defn- target-tree-refresh! [session-id owner-id {:keys [root path]}]
  (let [id    (target-tree-owner-id session-id owner-id)
        offer (offered-visible-root id root)]
    (when-not offer (throw (js/Error. "tree root is not visible for this authenticated session")))
    (when-not (some? (relative-scope root path))
      (throw (js/Error. "tree refresh path escapes its project root")))
    (clj->js (or (tree-entry-for root (:synthetic? offer) path)
                 (throw (js/Error. "tree listing failed"))))))

(defn- target-tree-refresh-all! [session-id owner-id]
  (let [id (target-tree-owner-id session-id owner-id)]
    (clj->js
     (into []
           (keep (fn [root]
                   (let [offer (get-in @tree-windows [id :offered root])]
                     (tree-entry-for root (:synthetic? offer) root))))
           (get-in @tree-windows [id :visible] #{})))))

(defn- target-release-tree-owner! [session-id owner-id]
  (release-tree-owner! (target-tree-owner-id session-id owner-id)))

(defn- handle-remote-tree-update! [^js update]
  (let [owner-id (.-ownerId update)
        opened   (.-openedPath update)
        ^js wc   (get @remote-owner-windows owner-id)]
    (when (live-webcontents? wc)
      (if (string? opened)
        (deliver-pending-remote-tree-open! wc opened owner-id (.-entry update))
        (send-remote-tree-entry! wc (.-uri update) owner-id (.-entry update))))))

(defn- open-remote-tree! [^js wc uri]
  (let [id       (.-id wc)
        owner-id (renderer-tree-owner-id wc uri)]
    (swap! remote-owner-windows assoc owner-id wc)
    (swap! remote-tree-pending assoc [id uri] owner-id)
    (-> (.treeOpen daemon-events uri owner-id uri)
        (.then (fn [entry]
                 (if entry
                   (deliver-pending-remote-tree-open! wc uri owner-id entry)
                   (discard-pending-remote-tree-open! id uri owner-id))))
        (.catch (fn [_] nil)))))

(defn- retained-by? [^js wc path]
  (contains? (retention/paths-for @window-owners (owner-id wc)) path))

(defn- send-open-content! [path]
  ;; the single choke point for every watcher/poller-driven re-send (local file watch, asset watch, remote
  ;; poll). The doc's window may have CLOSED since it was retained — under the resident daemon the watcher
  ;; outlives it — so skip a destroyed webContents (else `.send` throws "Object has been destroyed").
  (doseq [wc (webcontents-for path)]
    (send-content! wc path))
  ;; Authenticated source daemons receive only an invalidation.  They re-read through their own SFTP
  ;; connection, so target file bytes never travel over the event protocol and existing content limits apply.
  (doseq [invalidate (vals (get @remote-content-owners path {}))]
    (invalidate "change")))

(defn- content-owned? [path]
  (or (retained? path) (seq (get @remote-content-owners path))))

(defn- ensure-content-watcher! [path]
  (when-not (or (archive-uri? path) (file-kind/remote-uri? path) (get @watchers path))
    (let [dir? (directory? path)
          w    (watch path (if dir? dir-watch-options watch-options))]
      (if dir?
        (doseq [ev ["add" "unlink" "addDir" "unlinkDir" "change"]]
          (.on w ev (fn [_] (send-open-content! path))))
        (doseq [ev ["change" "add" "unlink"]]
          (.on w ev (fn [_] (send-open-content! path)))))
      (swap! watchers assoc path w))))

(defn- release-content-watcher-if-unowned! [path]
  (when-not (content-owned? path)
    (when-let [^js w (get @watchers path)]
      (.close w)
      (swap! watchers dissoc path))))

(declare target-content-unsubscribe!)

(defn- target-content-subscribe! [session-id sub-id file-path invalidate]
  ;; A subscription id is unique inside one authenticated session. Replace its previous path atomically from
  ;; the ownership model so a repeated subscribe cannot accumulate stale target watchers.
  (target-content-unsubscribe! session-id sub-id)
  (swap! remote-content-owners assoc-in [file-path [(str session-id) (str sub-id)]] invalidate)
  (ensure-content-watcher! file-path))

(defn- remove-target-content-owners! [pred]
  (let [affected (into #{}
                       (keep (fn [[file-path owners]]
                               (when (some (fn [[owner _]] (pred owner)) owners) file-path)))
                       @remote-content-owners)]
    (swap! remote-content-owners
           (fn [by-path]
             (into {}
                   (keep (fn [[file-path owners]]
                           (let [owners' (into {} (remove (fn [[owner _]] (pred owner))) owners)]
                             (when (seq owners') [file-path owners']))))
                   by-path)))
    (doseq [file-path affected]
      (release-content-watcher-if-unowned! file-path))))

(defn- target-content-unsubscribe! [session-id sub-id]
  (let [wanted [(str session-id) (str sub-id)]]
    (remove-target-content-owners! #(= wanted %))))

(defn- target-release-session! [session-id]
  (let [session-id (str session-id)
        tree-ids   (filter (fn [id]
                             (and (vector? id) (= :remote-session (first id))
                                  (= session-id (second id))))
                           (keys @tree-windows))]
    (remove-target-content-owners! #(= session-id (first %)))
    (doseq [id tree-ids] (release-tree-owner! id))))

;; ---- remote (SSH) live-refresh polling fallback ----
;; SFTP has no inotify, so when no compatible target daemon event subscription is active an opt-in per-doc
;; poller can re-stat the URI and re-send it on size/mtime change. Configure settings.edn
;; `:remote {:poll-seconds …}`; exponential backoff (to 60s) + ±25% jitter avoid hammering a downed host.
;; Directory listings poll slower (or not at all). Lifecycle remains tied to unwatch-file!.
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

(defn- handle-remote-content-state! [^js state]
  (let [uri (.-subId state)]
    (when (string? uri)
      (if (.-active state)
        (stop-remote-poller! uri)
        (when (retained? uri) (start-remote-poller! uri))))))

(defn- start-remote-live! [uri]
  ;; Polling remains an opt-in compatibility fallback.  It starts while discovery/handshake runs and is
  ;; stopped as soon as the authenticated target subscription becomes active.
  (start-remote-poller! uri)
  (when-not (contains? @remote-event-subs uri)
    (swap! remote-event-subs conj uri)
    (-> (.subscribeContent daemon-events uri uri (fn [_] (send-open-content! uri)))
        (.catch (fn [_] (when (retained? uri) (start-remote-poller! uri)))))))

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
  (release-content-watcher-if-unowned! path)
  (cancel-pending-remote-tree-opens! path)
  (when (contains? @remote-event-subs path)
    (swap! remote-event-subs disj path)
    (ignore-remote-error! (.unsubscribeContent daemon-events path path)))
  (stop-remote-poller! path)                 ; a remote doc's poller stops when the tab can no longer reach it
  ;; a piped-stdin document's spilled snapshot dies with its last retaining tab: unlink the temp file and
  ;; its per-invocation uuid dir (rmdirSync only removes it when empty — exactly the contract we want).
  (when (doc-overrides/stdin-doc? path)
    (try (.rmSync fs path #js {:force true}) (catch :default _ nil))
    (try (.rmdirSync fs (path/dirname path)) (catch :default _ nil)))
  ;; type overrides live exactly as long as retention: close the tab, reopen the file → extension deduction.
  (doc-overrides/clear! path)
  ;; the ADR-0038 adoption guard shares that lifetime — reopening the same diff re-adopts cleanly.
  (swap! adopted-diff-roots (fn [m] (into {} (map (fn [[id sub]] [id (dissoc sub path)])) m)))
  ;; a released commit-diff spill also leaves the ADR-0039 re-activation dedupe map.
  (git/forget-spill! path)
  (release-doc-assets! path))

(defn sync-retained!
  "Replace one renderer window's retained document-identity set. Local watchers, authenticated remote
   subscriptions, fallback pollers, and media ownership are released only when no live window retains it."
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
  (if (file-kind/remote-uri? path)
    (open-remote-tree! wc path)
    (cond
      (archive-uri? path) nil
      ;; a Commit Graph uri names no filesystem object — no tree (its repo is already a project)
      (file-kind/git-graph-uri? path) nil
      ;; a piped-stdin document skips the sidebar tree like an archive does: its spill dir is not a
      ;; project, and adopting $XDG_RUNTIME_DIR as a synthetic root would be pure noise — EXCEPT a
      ;; piped DIFF, which adopts the git repository of its invoking cwd (ADR-0038 seam 1).
      (doc-overrides/stdin-doc? path) (send-stdin-diff-tree! wc path)
      :else (send-tree! wc path)))
  (cond
    ;; remote (ssh://sftp://): prefer the authenticated target daemon event channel; polling stays opt-in fallback.
    (file-kind/remote-uri? path)
    (start-remote-live! path)
    ;; a stdin document is an immutable snapshot (drained to EOF before open) — there is nothing to watch.
    (doc-overrides/stdin-doc? path)
    nil
    ;; a Commit Graph uri has no file to watch; freshness arrives over vv:git-changed (ADR-0039's watcher)
    (file-kind/git-graph-uri? path)
    nil
    :else
    (ensure-content-watcher! path)))

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

(defn start-daemon-events!
  "Publish this daemon's authenticated loopback event endpoint.  The descriptor is owner-readable and clients
   can reach the endpoint only through an SSH direct-tcpip forward to the target host's loopback interface."
  [meta]
  (.startServer daemon-events meta))

(defn shutdown! []
  (.shutdown daemon-events))

(defn init! []
  (.configure daemon-events
              #js {:onTreeUpdate handle-remote-tree-update!
                   :onContentState handle-remote-content-state!
                   :handlers
                   #js {:contentSubscribe target-content-subscribe!
                        :contentUnsubscribe target-content-unsubscribe!
                        :treeOpen target-tree-open!
                        :treeRoots (fn [session-id owner-id roots]
                                     (target-tree-roots! session-id owner-id (js->clj roots)))
                        :treeExpanded (fn [session-id owner-id scopes]
                                        (target-tree-expanded!
                                         session-id owner-id
                                         (js->clj scopes :keywordize-keys true)))
                        :treeRefresh (fn [session-id owner-id req]
                                       (target-tree-refresh!
                                        session-id owner-id
                                        (js->clj req :keywordize-keys true)))
                        :treeRefreshAll target-tree-refresh-all!
                        :releaseTreeOwner target-release-tree-owner!
                        :releaseSession target-release-session!}})
  (.on ipcMain "vv:open"  (fn [^js e path] (open! (.-sender e) path)))
  (.on ipcMain "vv:close" (fn [^js e path] (close! (.-sender e) path)))
  ;; Settings ▸ File Type — re-type an open document in place: register the override (kind/language replace
  ;; as a unit; a piped doc's stdin facts survive) and re-send through the FULL open pipeline to every
  ;; retaining window (the :tab/reload shape — re-read, re-parse, re-render under the new kind).
  (.on ipcMain "vv:set-file-type"
       (fn [_e req]
         (let [{:keys [path kind language]} (js->clj req :keywordize-keys true)]
           (when (and (string? path) (string? kind))
             (doc-overrides/set-type! path kind language)
             (send-open-content! path)))))
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
             (refresh-tree-request (.-sender e) (js->clj req :keywordize-keys true))))
  (.handle ipcMain "vv:tree-refresh-all"
           (fn [^js e] (refresh-all-tree-requests (.-sender e))))
  ;; resolve a diff's referenced files (relative to the diff, walking up ancestors) → {rel → content}, for the
  ;; side-by-side view's full-file enrichment. Renderer-driven (it has no fs). Remote diffs resolve over SFTP.
  (.handle ipcMain "vv:load-diff-sources"
           (fn [^js e req]
             (let [{:keys [diffPath files includePaths includeContent]}
                   (js->clj req :keywordize-keys true)
                   opts {:include-paths? includePaths :include-content? includeContent}]
               (cond
                 (file-kind/remote-uri? diffPath)
                 (.loadRemoteDiffSources content-service diffPath (clj->js files)
                                         #js {:includePaths (boolean includePaths)
                                              :includeContent (boolean includeContent)})

                 ;; ADR-0039 R8: a git-produced commit diff enriches from its own blobs (rev-aware —
                 ;; the working tree may have drifted) and never adopts into the Files tree.
                 (doc-overrides/git-info diffPath)
                 (git/load-rev-sources (doc-overrides/git-info diffPath) files opts)

                 :else
                 (let [resolved (load-diff-sources diffPath files opts)]
                   ;; ADR-0038 seam 2: the resolved targets prove which repository the diff describes.
                   (when includePaths (adopt-diff-project! (.-sender e) diffPath resolved))
                   (clj->js resolved))))))
  ;; the async git data layer (ADR-0039): history pages, refs, commit diffs, and the ownership-scoped
  ;; .git watcher. All logic lives in vinary.main.git (Electron-free); these lines only bind channels.
  (.handle ipcMain "vv:git-log"
           (fn [_e req] (git/handle-log (js->clj req :keywordize-keys true))))
  (.handle ipcMain "vv:git-branches"
           (fn [_e req] (git/handle-branches (js->clj req :keywordize-keys true))))
  (.handle ipcMain "vv:git-open-diff"
           (fn [_e req] (git/handle-open-diff (js->clj req :keywordize-keys true))))
  (.on ipcMain "vv:git-watch"
       (fn [^js e roots] (git/sync-owner-roots! (.-sender e) (js->clj roots))))
  (.handle ipcMain "vv:git-blame"
           (fn [_e req] (git/handle-blame (js->clj req :keywordize-keys true))))
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
