(ns vinary.app.projects
  "The Files tab's project list — [{:root :files :synthetic?} …], one entry per open project. Pure and
   DOM-free so the node :test build covers the merge rules directly.

   Roots arrive from main one at a time (vv:tree, on every document open). A GIT root is authoritative and
   always stands on its own. A SYNTHETIC root — the containing directory of a file that belongs to no
   repository — is an inference, so it yields to containment: it is dropped when a root already on screen
   shows that file, and it absorbs the narrower synthetic roots it now covers. Without those two rules,
   opening a handful of files under one directory would stack up overlapping trees of the same files."
  (:require [clojure.string :as str]))

(defn normalized-path
  "A comparison form for an absolute local path. Main owns canonicalisation; renderer only has to make
   Windows' `\\` separator agree with git's `/`-separated relative entries. The original string remains
   the project identity sent back over IPC."
  [p]
  (when p (str/replace (str p) #"\\" "/")))

(defn same-path?
  "Separator-independent local-path equality (without inventing filesystem case-sensitivity rules)."
  [a b]
  (boolean (and a b (= (normalized-path a) (normalized-path b)))))

(defn join-path
  "Join an absolute renderer path to a `/`-relative child without doubling POSIX or Windows root
   separators. This deliberately preserves `root`'s spelling because it is also the project/IPC identity."
  [root relative]
  (let [relative (str relative)]
    (if (str/blank? relative)
      root
      (str root (if (re-find #"[\\/]$" (str root)) "" "/") relative))))

(defn- child-prefix [parent]
  (if (str/ends-with? parent "/") parent (str parent "/")))

(defn under?
  "Is `child` at or beneath `parent`? Separators are normalised and comparison is on segment boundaries,
   so both Windows paths work and `/a/bc` is NOT under `/a/b`."
  [child parent]
  (let [child  (normalized-path child)
        parent (normalized-path parent)]
    (boolean (and child parent
                  (or (= child parent)
                      (str/starts-with? child (child-prefix parent)))))))

(defn relative-path
  "`child` relative to `parent`, using `/`, or nil when it is outside `parent`. Exact equality returns
   the empty string. This is renderer-side path arithmetic only; MAIN still validates every IPC path with
   Node's platform-native `path.relative`."
  [child parent]
  (let [child  (normalized-path child)
        parent (normalized-path parent)]
    (when (under? child parent)
      (if (= child parent) "" (subs child (count (child-prefix parent)))))))

(defn- rebase-prefix
  "The path of `root` relative to the `parent` that contains it, with a trailing \"/\" — the prefix that
   turns root-relative paths into parent-relative ones. `parent` must strictly contain `root`."
  [parent root]
  (str (relative-path root parent) "/"))

(defn- merge-subtree
  "Refresh `parent`'s listing with a freshly walked subtree rooted at `entry`'s root: every existing path
   under that subtree is replaced by the incoming paths, re-based onto the parent. This is what keeps a
   synthetic root from going stale — `git ls-files --others` guarantees the file you just opened is in
   the tree, and a covered synthetic root must not be staler than that."
  [parent entry]
  (let [prefix (rebase-prefix (:root parent) (:root entry))
        kept   (filterv #(not (str/starts-with? % prefix)) (:files parent))]
    (assoc parent :files (into kept (map #(str prefix %)) (:files entry)))))

(defn merge-project
  "Fold one incoming {:root :files :synthetic?} into the projects vector:
     • a root already present is replaced IN PLACE — re-opening a file refreshes its tree without
       reordering the sidebar;
     • a synthetic root covered by a known SYNTHETIC root does not become a second tree; instead its
       freshly walked subtree is merged into that root, so the file just opened is always visible;
     • a synthetic root covered by a GIT root is dropped outright — the git root is authoritative and its
       incoming payload is already a complete repository listing;
     • otherwise it is appended, and any SYNTHETIC root it now covers is absorbed (the broader view wins,
       so /notes/sub followed by /notes leaves one tree rather than two overlapping ones).
   Containment only ever removes synthetic roots: a git repository nested inside a browsed directory is a
   project in its own right and must survive."
  [projects {:keys [root synthetic?] :as entry}]
  (let [projects (vec projects)
        entry    (-> entry (update :files vec) (update :synthetic? boolean))
        idx      (first (keep-indexed #(when (= (:root %2) root) %1) projects))
        ;; the most specific covering root — nested synthetic roots cannot coexist (see the absorb rule),
        ;; so at most one synthetic and any number of git roots can cover, and the deepest one wins
        cover-idx (when synthetic?
                    (->> (keep-indexed (fn [i p] (when (under? root (:root p)) i)) projects)
                         (sort-by #(- (count (:root (nth projects %)))))
                         first))]
    (cond
      idx
      (assoc projects idx entry)

      (and cover-idx (:synthetic? (nth projects cover-idx)))
      (update projects cover-idx merge-subtree entry)

      cover-idx                                        ; covered by a git root — it refreshes itself
      projects

      :else
      (conj (if synthetic?
              (filterv #(not (and (:synthetic? %) (under? (:root %) root))) projects)
              projects)
            entry))))

(defn apply-tree-update
  "Apply a main-process tree payload. A payload without :scope is a full project entry and follows
   `merge-project`'s root/containment rules. A scoped payload keeps the exact project identity and
   replaces only root-relative files beneath that directory; if the project disappeared while an
   async refresh was in flight, the stale scoped reply is ignored."
  [projects {:keys [root scope files] :as entry}]
  (if (nil? scope)
    (merge-project projects entry)
    (let [projects (vec projects)
          idx      (first (keep-indexed #(when (= (:root %2) root) %1) projects))
          prefix   (when-not (str/blank? scope) (str scope "/"))]
      (if (nil? idx)
        projects
        (let [old  (:files (nth projects idx))
              keep? (if prefix #(not (str/starts-with? % prefix)) (constantly false))]
          (assoc-in projects [idx :files]
                    (into (filterv keep? old) (vec files))))))))

(defn remove-project
  "Drop the project rooted at `root` from the list. The root returns if a file under it is opened again —
   removal is a sidebar decision, not a persisted exclusion."
  [projects root]
  (filterv #(not= (:root %) root) (vec projects)))
