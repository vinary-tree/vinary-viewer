(ns vinary.main.dir-walk
  "The synthetic (non-git) project root: a directory listed the way `git ls-files` lists a repository, so a
   file belonging to no repo still gets a Files-tab tree.

   Split out of vinary.main.service — which cannot be required outside Electron, since it pulls in electron,
   chokidar, and the content service — so the node :test build exercises the REAL walk against a real
   temporary directory instead of a hand-copied mirror of it. Requires only node's fs/path plus the pure
   policy (bounds, exclusions, root selection) in vinary.main.service-util.

   ASYNCHRONOUS (2026-10-17, docs/scientific/10 §11): the walk runs on main's event loop, and a synchronous walk of a
   wide directory under I/O contention blocks every IPC reply behind it. `walk-limits` caps the files and the depth,
   but not how many directories one level holds."
  (:require ["fs" :as fs]
            ["path" :as path]
            [vinary.main.service-util :as service-util]))

(defn- children
  "The classified entries of one directory, in readdir order: [:dir [abs rel/]] for a real directory to descend into,
   [:file rel] for a file, nil otherwise. A symlink is resolved through to its target: listed when it points at a
   file, never descended into when it points at a directory (a symlink cycle would not terminate). An unreadable
   directory has no entries."
  [dir prefix]
  (-> (.readdir (.-promises fs) dir #js {:withFileTypes true})
      (.catch (fn [_] #js []))
      (.then (fn [^js dirents]
               (js/Promise.all
                (.map dirents
                      (fn [^js dirent]
                        (let [name (.-name dirent)
                              rel  (str prefix name)
                              abs  (path/join dir name)]
                          (if (.isSymbolicLink dirent)
                            ;; a Dirent reports the LINK's own type: resolve it to classify it (a broken link: nil)
                            (-> (.stat (.-promises fs) abs)
                                (.then (fn [^js st] (when (.isFile st) [:file rel])))
                                (.catch (fn [_] nil)))
                            (js/Promise.resolve
                             (cond
                               (and (.isDirectory dirent) (not (service-util/skip-dir? name))) [:dir [abs (str rel "/")]]
                               (.isFile dirent) [:file rel]
                               :else nil)))))))))))

(defn walk-dir
  "A Promise of the root-relative paths of the files under `root` — the non-git counterpart of `git ls-files`.
   Breadth-first and bounded by service-util/walk-limits, so hitting a cap yields a useful shallow tree instead of one
   arbitrarily deep branch. Paths are joined with \"/\" on every platform, matching both git's output and the tree
   view's (str root \"/\" %) reconstitution. Never rejects.

   The directories of a level are read one at a time, in level order (a level is never read concurrently, which
   would compete with document reads for libuv's small thread pool), and a level stops being read once the files
   reach the cap: every file after position max-entries is dropped anyway, and the walk ends at the next level's
   head, so the result is exactly the one reading the whole level would give."
  [root]
  (let [{:keys [max-depth max-entries]} service-util/walk-limits]
    (letfn [(read-level [level next files]
              (if (or (empty? level) (>= (count files) max-entries))
                (js/Promise.resolve [next files])
                (let [[dir prefix] (first level)]
                  (-> (children dir prefix)
                      (.then (fn [^js entries]
                               (let [[dirs found] (reduce (fn [[dirs found] [t v]]
                                                            (case t
                                                              :dir  [(conj dirs v) found]
                                                              :file [dirs (conj found v)]
                                                              [dirs found]))
                                                          [next files]
                                                          (remove nil? (array-seq entries)))]
                                 (read-level (rest level) dirs found))))))))
            (walk [level depth files]
              (if (or (empty? level) (> depth max-depth) (>= (count files) max-entries))
                (js/Promise.resolve (vec (take max-entries files)))
                (-> (read-level level [] files)
                    (.then (fn [[next files]] (walk next (inc depth) files))))))]
      (walk [[root ""]] 0 []))))

(defn synthetic-root
  "A Promise of the directory to adopt as a project root for a path in no git repository — or of nil when there is no
   sane root (a filesystem root is refused). `directory?` comes from the caller, which has already excluded archive
   and remote URIs.

   The root is realpath'd because `git rev-parse --show-toplevel` returns a resolved path while path/dirname does not,
   and the renderer dedupes roots by exact string equality — without this, one directory reached two ways lands in
   the sidebar twice."
  [p directory?]
  (if-let [root (service-util/fallback-root {:path       p
                                             :directory? directory?
                                             :parent     (path/dirname p)})]
    (-> (.realpath (.-promises fs) root) (.catch (fn [_] root)))
    (js/Promise.resolve nil)))

(defn dir-tree
  "A Promise of the synthetic project for a path in no git repository, {:root <abs> :files [root-relative…]
   :synthetic? true}, or of nil (see synthetic-root). The service lists synthetic roots through its listing flights
   (vinary.main.tree-listing) rather than calling this directly; it stays the one-call composition for other callers
   and the tests."
  [p directory?]
  (-> (synthetic-root p directory?)
      (.then (fn [root]
               (when root
                 (-> (walk-dir root)
                     (.then (fn [files] {:root root :files files :synthetic? true}))))))))
