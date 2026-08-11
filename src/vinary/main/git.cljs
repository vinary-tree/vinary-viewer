(ns vinary.main.git
  "Async main-process git data layer (ADR-0039): the vv:git-log / vv:git-branches / vv:git-open-diff
   request handlers, the ownership-scoped .git watcher behind vv:git-watch → vv:git-changed, and the
   commit-diff spill lifecycle. Everything strictly READ-ONLY: log, for-each-ref, rev-parse, diff —
   no mutating subcommand exists in this namespace.

   Electron-free by design (webContents arrive as arguments; the service registers the ipcMain
   handlers), like dir-walk and service-util — argv building and text parsing live one layer lower
   still, in the pure vinary.git.log. Every subprocess here is ASYNC (`run-git` wraps execFile):
   log/diff output can be large and the sync `git` helper in vinary.main.service must never be used
   for it — that helper stays for tiny calls (rev-parse toplevel) on interactive paths.

   Spilled commit diffs are ADR-0036 citizens: the diff text lands in a private
   `$XDG_RUNTIME_DIR/vinary-viewer/git-diff/<uuid>/<shortA..shortB>.diff` file (twin of
   vinary.terminal.stdin's spill), registered in doc-overrides as {:kind \"diff\" :stdin? true
   :cwd <root> :git {:root :from :to}} — :stdin? buys MRU exclusion, no watcher, unlink-on-release,
   and (via the :git key) the ADR-0038 tree-adoption opt-out plus rev-aware enrichment."
  (:require ["child_process" :as cp]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["chokidar" :refer [watch]]
            [clojure.string :as str]
            [vinary.git.blame :as blame]
            [vinary.git.log :as glog]
            [vinary.main.diff-source :as diff-source]
            [vinary.main.doc-overrides :as doc-overrides]))

(def empty-tree-hash
  "git's well-known empty-tree object — the FROM side that turns a root commit's \"diff against my
   parent\" into a plain `git diff` showing the full addition (R4: one code path, no `git show` arm)."
  "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(def ^:private page-limit 250)

(defn run-git
  "Async `git <args>` in `cwd` → a Promise ALWAYS resolving to {:ok out} or {:error message} —
   never rejecting, so handler chains stay linear. argv array, no shell, 64 MiB maxBuffer (large
   monorepo logs/diffs), stderr only ever feeds the error message. A missing git binary reads as a
   human sentence, not ENOENT."
  [args cwd]
  (js/Promise.
   (fn [resolve _]
     (try
       (cp/execFile "git" (clj->js args)
                    (clj->js {:cwd cwd :encoding "utf8"
                              :maxBuffer (* 64 1024 1024) :windowsHide true})
                    (fn [err out stderr]
                      (if err
                        (resolve {:error (cond
                                           (= "ENOENT" (.-code err)) "git is not available on PATH"
                                           (some-> (.-message err) (str/includes? "maxBuffer"))
                                           "git output too large (>64 MiB)"
                                           :else (or (some-> stderr str/trim not-empty)
                                                     (.-message err)
                                                     "git failed"))})
                        (resolve {:ok out}))))
       (catch :default e
         (resolve {:error (or (.-message e) "git failed")}))))))

(defn- toplevel
  "Canonical repository root for dir (worktree-safe, symlink-resolved) or {:error}."
  [dir]
  (-> (run-git ["rev-parse" "--show-toplevel"] dir)
      (.then (fn [{:keys [ok error]}]
               (if ok
                 (let [top (str/trim ok)]
                   (if (str/blank? top) {:error "not a git repository"} {:ok top}))
                 {:error (or error "not a git repository")})))))

(defn- verify-rev
  "rev-parse --verify one user-supplied ref → {:ok full-sha} or {:error \"unknown revision: …\"}."
  [root ref]
  (-> (run-git (glog/verify-args ref) root)
      (.then (fn [{:keys [ok]}]
               (if-let [sha (some-> ok str/trim not-empty)]
                 {:ok sha}
                 {:error (str "unknown revision: " ref)})))))

(defn- empty-repo-error? [message]
  (boolean (some-> message (str/includes? "does not have any commits"))))

;; ── vv:git-log ──────────────────────────────────────────────────────────────────────────────────

(defn handle-log
  "{root ref? skip limit file? follow? lineRange?} → Promise of {commits exhausted empty?}|{error}.
   The ref (when given) is rev-parse-verified before any log argv runs; lineRange is RESERVED until
   the history feature lands its own `-L` record discipline (R1) — the key is validated, the reply
   is an explicit error, and the schema is already carved out."
  [{:keys [root ref skip limit file follow lineRange]}]
  (let [limit (long (or limit page-limit))]
    (if lineRange
      (js/Promise.resolve (clj->js {:error "lineRange unsupported"}))
      (-> (toplevel root)
          (.then (fn [{:keys [ok error]}]
                   (if-not ok
                     {:error error}
                     (let [top ok
                           run (fn [verified-ref]
                                 (-> (run-git (glog/log-args {:ref verified-ref :skip skip
                                                              :limit limit :file file
                                                              :follow? follow})
                                              top)
                                     (.then (fn [{:keys [ok error]}]
                                              (cond
                                                ok (let [{:keys [commits count]} (glog/parse-log ok)]
                                                     {:commits commits :exhausted (< count limit)})
                                                (empty-repo-error? error) {:commits [] :exhausted true :empty true}
                                                :else {:error error})))))]
                       (if ref
                         (-> (verify-rev top ref)
                             (.then (fn [{:keys [ok error]}] (if ok (run ref) {:error error}))))
                         (run nil))))))
          (.then clj->js)))))

;; ── vv:git-branches ─────────────────────────────────────────────────────────────────────────────

(defn handle-branches
  "{root} → Promise of {head detached? branches [{name kind current?}]}|{error}. A detached HEAD
   labels itself with the short commit hash."
  [{:keys [root]}]
  (-> (toplevel root)
      (.then (fn [{:keys [ok error]}]
               (if-not ok
                 (js/Promise.resolve {:error error})
                 (let [top ok]
                   (-> (js/Promise.all
                        #js [(run-git (glog/branches-args) top)
                             (run-git (glog/head-args) top)])
                       (.then (fn [[refs head]]
                                (cond
                                  (:error refs) {:error (:error refs)}
                                  (:error head) {:error (:error head)}
                                  :else
                                  (let [head-name (str/trim (:ok head))
                                        detached? (= head-name "HEAD")]
                                    (if detached?
                                      (-> (run-git ["rev-parse" "--short" "HEAD"] top)
                                          (.then (fn [{:keys [ok]}]
                                                   (glog/parse-branches (:ok refs)
                                                                        (or (some-> ok str/trim) "HEAD")
                                                                        true))))
                                      (glog/parse-branches (:ok refs) head-name false)))))))))))
      (.then clj->js)))

;; ── vv:git-open-diff — spill + register + dedupe ────────────────────────────────────────────────

(defn- spill-root
  "`$XDG_RUNTIME_DIR/vinary-viewer/git-diff`, else the uid-qualified temp fallback — the same
   placement rules as the stdin spill (vinary.terminal.stdin/stdin-root) and the daemon socket."
  []
  (let [rt (.. js/process -env -XDG_RUNTIME_DIR)]
    (if (and rt (not= rt ""))
      (path/join rt "vinary-viewer" "git-diff")
      (path/join (or (.. js/process -env -TMPDIR) "/tmp")
                 (str "vinary-viewer-" (.getuid js/process)) "git-diff"))))

(defn- spill-diff!
  "Write diff text to a fresh private `<spill-root>/<uuid>/<label>.diff` (dir 0700, file 0600) and
   return its absolute path. The basename is the tab title (uri/basename), so the label carries the
   human-readable range."
  [label text]
  (let [dir (path/join (spill-root) (.randomUUID crypto))
        p   (path/join dir (str label ".diff"))]
    (.mkdirSync fs dir #js {:recursive true :mode 0700})
    (.writeFileSync fs p text #js {:mode 0600})
    p))

(defonce ^:private diff-spills (atom {}))   ; [root from to] -> spill path — re-activation dedupe

(defn forget-spill!
  "Drop the dedupe entry whose spill is `path` — called by the service when retention unlinks it,
   so the map stays bounded. A stale entry is harmless either way (hits re-check existence)."
  [path]
  (swap! diff-spills (fn [m] (into {} (remove (fn [[_ v]] (= v path))) m))))

(defn- short-sha [sha] (subs sha 0 (min 7 (count sha))))

(defn handle-open-diff
  "{root from? to parent? dots? paths?} → Promise of {path title}|{error}. Both sides are
   rev-parse-verified (R4: a nil/parent `from` resolves `<to>^`, falling back to the empty tree for
   root commits), the diff text spills to a private file registered in doc-overrides, and
   re-activating the same [root from to] focuses the existing document instead of re-spilling."
  [{:keys [root from to parent? dots paths]}]
  (-> (toplevel root)
      (.then
       (fn [{:keys [ok error]}]
         (if-not ok
           {:error error}
           (let [top ok]
             (-> (verify-rev top to)
                 (.then
                  (fn [{to-sha :ok error :error}]
                    (if-not to-sha
                      {:error error}
                      (-> (cond
                            ;; an explicit FROM must verify (a typo is the user's range input)
                            (and from (not parent?)) (verify-rev top from)
                            ;; parent-of-to, with the empty tree closing the root-commit case
                            :else (-> (verify-rev top (str to-sha "^"))
                                      (.then (fn [{:keys [ok]}]
                                               {:ok (or ok empty-tree-hash)}))))
                          (.then
                           (fn [{from-sha :ok error :error}]
                             (if-not from-sha
                               {:error error}
                               (let [key   [top from-sha to-sha]
                                     label (if (= from-sha empty-tree-hash)
                                             (short-sha to-sha)
                                             (str (short-sha from-sha) ".." (short-sha to-sha)))
                                     known (get @diff-spills key)]
                                 (if (and known (try (.existsSync fs known) (catch :default _ false)))
                                   {:path known :title (str label ".diff")}
                                   (-> (run-git (glog/open-diff-args {:from from-sha :to to-sha
                                                                     :dots dots :paths paths})
                                                top)
                                       (.then
                                        (fn [{:keys [ok error]}]
                                          (if-not ok
                                            {:error error}
                                            (let [p (spill-diff! label ok)]
                                              (doc-overrides/register!
                                               p {:kind "diff" :stdin? true :cwd top
                                                  :git {:root top :from from-sha :to to-sha}})
                                              (swap! diff-spills assoc key p)
                                              {:path p :title (str label ".diff")})))))))))))))))))))
      (.then clj->js)))

;; ── vv:git-blame ────────────────────────────────────────────────────────────────────────────────

(defn handle-blame
  "{root? file} → Promise of {root hunks}|{error}. The renderer's root is advisory — main re-derives
   the repository from the FILE's own directory (the blamed file may belong to a different repo than
   the Commits panel shows). Blames the WORKING TREE — exactly what the source view displays — so
   uncommitted lines arrive as the zero hash. No -M/-C/-w: literal line attribution."
  [{:keys [root file]}]
  (-> (toplevel (.dirname path file))
      (.then (fn [{:keys [ok error]}]
               (let [top (or ok root)]
                 (if-not top
                   (js/Promise.resolve {:error (or error "not in a git repository")})
                   (-> (run-git ["blame" "--line-porcelain" "--" file] top)
                       (.then (fn [{:keys [ok error]}]
                                (if ok
                                  {:root top :hunks (:hunks (blame/parse-line-porcelain ok))}
                                  {:error error}))))))))
      (.then clj->js)))

(defn load-rev-sources
  "R8: vv:load-diff-sources for a git-produced commit diff (doc-overrides :git = {:root :from :to}).
   CONTENT comes from `git cat-file blob <to>:<rel>` — the diff was computed FROM those blobs, so
   split-view row alignment is exact even when the working tree has drifted since (strictly more
   correct than the on-disk read the working-tree path performs). TARGETS still resolve against the
   working tree (a header click means \"open this file NOW\"; a path that no longer exists correctly
   stays inert), and a deleted-since file still enriches from its blob alone. Returns a Promise of
   the same wire shapes load-diff-sources produces synchronously: the legacy `{rel → content}` map,
   or the structured `{rel → {:path abs? :content utf8?}}` map."
  [{:keys [root to]} rels {:keys [include-paths? include-content?]}]
  (let [rels   (vec (or rels []))
        blobs! (fn []
                 (-> (js/Promise.all
                      (into-array
                       (map (fn [rel]
                              (-> (run-git ["cat-file" "blob" (str to ":" rel)] root)
                                  (.then (fn [{:keys [ok]}] [rel ok]))))
                            rels)))
                     ;; a missing blob (a rename's old path, a binary, a bad rel) contributes nothing —
                     ;; the renderer's hunk-window fallback covers that file, exactly as on disk
                     (.then (fn [pairs] (into {} (filter (comp some? second)) (vec pairs))))))]
    (cond
      (and include-paths? include-content?)
      (let [targets (diff-source/load-local root rels {:include-paths? true :include-content? false})]
        (-> (blobs!)
            (.then (fn [contents]
                     (clj->js
                      (reduce (fn [m rel]
                                (let [t (get targets rel)
                                      c (get contents rel)]
                                  (cond
                                    (and t c) (assoc m rel (assoc t :content c))
                                    t         (assoc m rel t)
                                    c         (assoc m rel {:content c})
                                    :else     m)))
                              {} rels))))))

      include-paths?
      (js/Promise.resolve
       (clj->js (diff-source/load-local root rels {:include-paths? true :include-content? false})))

      :else
      (-> (blobs!) (.then clj->js)))))

(defn sweep-stale!
  "Delete git-diff spill dirs older than max-age-ms (crashed sessions) — the boot-time twin of
   vinary.terminal.stdin/sweep-stale!, with the same never-break-boot error swallowing."
  [max-age-ms]
  (try
    (let [root (spill-root)
          now  (js/Date.now)]
      (doseq [name (array-seq (.readdirSync fs root))]
        (let [dir (path/join root name)]
          (try
            (when (> (- now (.-mtimeMs (.statSync fs dir))) max-age-ms)
              (.rmSync fs dir #js {:recursive true :force true}))
            (catch :default _ nil)))))
    (catch :default _ nil)))

;; ── the .git watcher: vv:git-watch ownership → debounced vv:git-changed pushes ──────────────────

(defonce ^:private git-watchers (atom {}))   ; root -> {:watcher :owners {wc-id wc} :timer}

(defn- live-wc? [^js wc]
  (boolean (and wc (try (not (.isDestroyed wc)) (catch :default _ false)))))

(defn- push-changed! [root]
  (let [{:keys [owners]} (get @git-watchers root)]
    (doseq [[_ ^js wc] owners]
      (when (live-wc? wc)
        (.send wc "vv:git-changed" (clj->js {:root root}))))))

(defn- schedule-push!
  "Debounce 300 ms per root: a fetch touches many refs; the panel needs ONE conservative refresh."
  [root]
  (swap! git-watchers update root
         (fn [{:keys [timer] :as entry}]
           (when timer (js/clearTimeout timer))
           (assoc entry :timer (js/setTimeout
                                (fn []
                                  (swap! git-watchers update root dissoc :timer)
                                  (push-changed! root))
                                300)))))

(defn- start-watcher!
  "One chokidar instance per repo over HEAD + refs + packed-refs. git-dir and common-dir are
   resolved separately because WORKTREES keep HEAD and refs in different directories — a literal
   `.git` path would silently watch nothing there. Refs trees are tiny, so the bounded recursive
   watch (depth 4) honors ADR-0034's spirit (bounded, ownership-scoped) — the depth exception is
   recorded in ADR-0039."
  [root]
  (-> (js/Promise.all
       #js [(run-git ["rev-parse" "--absolute-git-dir"] root)
            (run-git ["rev-parse" "--git-common-dir"] root)])
      (.then (fn [[gd cd]]
               (when (and (:ok gd) (:ok cd))
                 (let [git-dir    (str/trim (:ok gd))
                       common-dir (let [c (str/trim (:ok cd))]
                                    (if (.isAbsolute path c) c (path/join root c)))
                       targets    #js [(path/join git-dir "HEAD")
                                       (path/join common-dir "packed-refs")
                                       (path/join common-dir "refs")]
                       w (watch targets #js {:ignoreInitial true :depth 4 :followSymlinks false})]
                   (.on w "all" (fn [_event _path] (schedule-push! root)))
                   (swap! git-watchers update root
                          (fn [entry]
                            (if (some? entry)
                              (assoc entry :watcher w)
                              ;; every owner left while we were resolving — close immediately
                              (do (.close w) entry)))))))))
  nil)

(defn- stop-watcher! [root]
  (when-let [{:keys [watcher timer]} (get @git-watchers root)]
    (when timer (js/clearTimeout timer))
    (when watcher (try (.close watcher) (catch :default _ nil)))
    (swap! git-watchers dissoc root)))

(defn sync-owner-roots!
  "Replace one window's watched-repo set (vv:git-watch; [] on panel unmount). Ownership discipline
   is the tree-watcher twin: last owner out closes the repo's watcher."
  [^js wc roots]
  (let [id     (.-id wc)
        roots  (into #{} (filter string?) roots)
        before (into #{} (keep (fn [[root {:keys [owners]}]]
                                 (when (contains? owners id) root)))
                     @git-watchers)]
    ;; releases
    (doseq [root before]
      (when-not (contains? roots root)
        (swap! git-watchers update root update :owners dissoc id)
        (when (empty? (get-in @git-watchers [root :owners]))
          (stop-watcher! root))))
    ;; acquisitions
    (doseq [root roots]
      (when-not (contains? before root)
        (let [known? (contains? @git-watchers root)]
          (swap! git-watchers update root
                 (fn [entry] (update (or entry {:owners {}}) :owners assoc id wc)))
          (when-not known?
            (start-watcher! root)))))))

(defn release-owner!
  "Window destroyed: release every repo it owned (the release-window! hook)."
  [wc-id]
  (doseq [[root {:keys [owners]}] @git-watchers]
    (when (contains? owners wc-id)
      (swap! git-watchers update root update :owners dissoc wc-id)
      (when (empty? (get-in @git-watchers [root :owners]))
        (stop-watcher! root)))))
