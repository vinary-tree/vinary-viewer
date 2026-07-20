(ns vinary.main.dir-walk
  "The synthetic (non-git) project root: a directory listed the way `git ls-files` lists a repository, so a
   file belonging to no repo still gets a Files-tab tree.

   Split out of vinary.main.service — which cannot be required outside Electron, since it pulls in electron,
   chokidar, and the content service — so the node :test build exercises the REAL walk against a real
   temporary directory instead of a hand-copied mirror of it. Requires only node's fs/path plus the pure
   policy (bounds, exclusions, root selection) in vinary.main.service-util."
  (:require ["fs" :as fs]
            ["path" :as path]
            [vinary.main.service-util :as service-util]))

(defn walk-dir
  "Root-relative paths of the files under `root` — the non-git counterpart of `git ls-files`. Breadth-first
   and bounded by service-util/walk-limits, so hitting a cap yields a useful shallow tree instead of one
   arbitrarily deep branch. Paths are joined with \"/\" on every platform, matching both git's output and the
   tree view's (str root \"/\" %) reconstitution. A symlink is resolved through to its target: listed when it
   points at a file, and never descended into when it points at a directory, since a symlink cycle would not
   terminate. An unreadable directory contributes nothing rather than aborting the walk."
  [root]
  (let [{:keys [max-depth max-entries]} service-util/walk-limits]
    (loop [level [[root ""]]                       ; [absolute-dir relative-prefix] at the current depth
           depth 0
           files []]
      (if (or (empty? level) (> depth max-depth) (>= (count files) max-entries))
        (vec (take max-entries files))
        (let [[next-level files]
              (reduce
               (fn [acc [dir prefix]]
                 (reduce
                  (fn [[dirs found] ^js dirent]
                    (let [name   (.-name dirent)
                          rel    (str prefix name)
                          abs    (path/join dir name)
                          link?  (.isSymbolicLink dirent)
                          ;; a Dirent reports the LINK's own type, so a symlink is neither isDirectory
                          ;; nor isFile — resolve it through to the target to classify it (nil = broken)
                          ^js st (when link? (try (.statSync fs abs) (catch :default _ nil)))
                          dir?   (if link? (boolean (and st (.isDirectory st))) (.isDirectory dirent))
                          file?  (if link? (boolean (and st (.isFile st)))      (.isFile dirent))]
                      (cond
                        ;; only a REAL directory is descended into — a symlinked one risks a cycle
                        (and dir? (not link?) (not (service-util/skip-dir? name)))
                        [(conj dirs [abs (str rel "/")]) found]

                        file? [dirs (conj found rel)]
                        :else [dirs found])))
                  acc
                  (try (.readdirSync fs dir #js {:withFileTypes true}) (catch :default _ []))))
               [[] files]
               level)]
          (recur next-level (inc depth) files))))))

(defn dir-tree
  "The fallback for a path inside no git repository: its containing directory adopted as a project root of
   its own, as {:root <abs> :files [root-relative…] :synthetic? true} — or nil when there is no sane root
   (a filesystem root is refused). `directory?` comes from the caller, which has already excluded archive
   and remote URIs.

   The root is realpath'd because `git rev-parse --show-toplevel` returns a resolved path while
   path/dirname does not, and the renderer dedupes roots by exact string equality — without this, one
   directory reached two ways lands in the sidebar twice."
  [p directory?]
  (when-let [root (service-util/fallback-root {:path       p
                                               :directory? directory?
                                               :parent     (path/dirname p)})]
    (let [root (try (.realpathSync fs root) (catch :default _ root))]
      {:root root :files (walk-dir root) :synthetic? true})))
