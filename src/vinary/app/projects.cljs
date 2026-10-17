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

;; ---- listing order ------------------------------------------------------------------------------------------
;; Main numbers every listing and stamps each entry built from it, :listing {:epoch :n} (vinary.main.tree-listing).
;; Entries can arrive out of order — a Refresh All reply waits for its slowest root while a watcher pushes a newer
;; listing of another; a target daemon's forwarded reply can trail its own push — so a project remembers, per SCOPE
;; (a root-relative directory, \"\" for the root), the stamp of the listing that last wrote it: :listings
;; {scope → [epoch n]}. Writing scope S with stamp t then gives exactly what applying every entry in stamp order would:
;;   • a scope written by a NEWER listing at or above S makes the write a no-op (that listing already covers S);
;;   • directories inside S written by newer listings keep their files (they are protected), the rest of S takes the
;;     incoming files;
;;   • the stamps of S and everything inside it that t supersedes are forgotten, so :listings holds only scopes no
;;     newer write covers.
;; An unstamped entry (an older daemon) writes as entries always did, and drops the stamps it covers. A project with
;; no stamps has no :listings key.

(defn- stamp-of
  "An entry's listing stamp as [epoch n], or nil."
  [{:keys [listing]}]
  (let [{:keys [epoch n]} listing]
    (when (and (number? epoch) (number? n)) [epoch n])))

(defn- newer? [a b] (pos? (compare a b)))

(defn- scope-within?
  "Is directory scope `t` at or inside scope `s`?"
  [t s]
  (or (str/blank? s) (= t s) (str/starts-with? t (str s "/"))))

(defn- file-within?
  "Is root-relative file `f` inside scope `s`?"
  [f s]
  (or (str/blank? s) (str/starts-with? f (str s "/"))))

(defn- write-scope
  "`project` with scope `scope` replaced by `incoming` (root-relative files, all inside it) from the listing stamped
   `t` (nil: unstamped), or nil when a newer listing already wrote a scope containing it (see above)."
  [{:keys [files listings] :as project} scope t incoming]
  (cond
    (nil? t)
    (let [kept     (filterv #(not (file-within? % scope)) files)
          listings (into {} (remove (fn [[s _]] (scope-within? s scope))) listings)]
      (cond-> (-> project (assoc :files (into kept incoming)) (dissoc :listings))
        (seq listings) (assoc :listings listings)))

    (some (fn [[s u]] (and (scope-within? scope s) (newer? u t))) listings)
    nil

    :else
    (let [protected  (into [] (keep (fn [[s u]] (when (and (not= s scope) (scope-within? s scope) (newer? u t)) s)))
                           listings)
          protected? (fn [f] (some #(file-within? f %) protected))]
      (assoc project
             :files    (into (filterv #(or (not (file-within? % scope)) (protected? %)) files)
                             (if (seq protected) (remove protected?) identity)
                             incoming)
             :listings (-> (into {} (remove (fn [[s u]] (and (scope-within? s scope) (not (newer? u t))))) listings)
                           (assoc scope t))))))

(defn- rebase
  "Root-relative path `p` of the root `child`, re-based onto the `parent` root that contains it."
  [parent child p]
  (let [r (relative-path child parent)]
    (cond (str/blank? r) p
          (str/blank? p) r
          :else          (str r "/" p))))

(defn- merge-subtree
  "Refresh `parent`'s listing with a freshly walked subtree rooted at `entry`'s root: every existing path
   under that subtree is replaced by the incoming paths, re-based onto the parent — unless a newer listing already
   wrote it (write-scope). This is what keeps a synthetic root from going stale — `git ls-files --others` guarantees
   the file you just opened is in the tree, and a covered synthetic root must not be staler than that."
  [parent entry t]
  (or (write-scope parent
                   (rebase (:root parent) (:root entry) "")
                   t
                   (mapv #(rebase (:root parent) (:root entry) %) (:files entry)))
      parent))

(defn- reapply-absorbed
  "`project` (just built from an entry stamped `t`) after re-applying what each absorbed synthetic root holds from
   listings NEWER than `t`, oldest first — the state applying every entry in stamp order would have produced (the
   absorbed root's entry would then have arrived second, and merged into the broader one)."
  [project t absorbed]
  (if (nil? t)
    project
    (->> (for [{:keys [root files listings]} absorbed
               [s u] listings
               :when (newer? u t)]
           {:stamp u
            :scope (rebase (:root project) root s)
            :files (into [] (comp (filter #(file-within? % s)) (map #(rebase (:root project) root %))) files)})
         (sort-by :stamp)
         (reduce (fn [p {:keys [stamp scope files]}] (or (write-scope p scope stamp files) p)) project))))

(defn- merge-extras
  "Union two :extras vectors (ADR-0038 attached documents — a diff adopted into the project), deduped
   by :path with `incoming` winning (a re-send may relabel), keeping first-seen order. Returns nil when
   the union is empty so callers can keep the key absent. Extras are attachments, not listed files, so
   refreshes that carry none must never wipe them."
  [existing incoming]
  (let [merged (reduce (fn [acc e]
                         (let [i (first (keep-indexed #(when (same-path? (:path %2) (:path e)) %1) acc))]
                           (if i (assoc acc i e) (conj acc e))))
                       (vec (or existing []))
                       (or incoming []))]
    (when (seq merged) merged)))

(defn merge-project
  "Fold one incoming {:root :files :synthetic? :extras?} into the projects vector:
     • a root already present is replaced IN PLACE — re-opening a file refreshes its tree without
       reordering the sidebar; its :extras are UNIONED with the incoming entry's, because a root-level
       Refresh / re-open sends a full entry with no extras and must not lose the attached diffs;
     • a synthetic root covered by a known SYNTHETIC root does not become a second tree; instead its
       freshly walked subtree is merged into that root, so the file just opened is always visible
       (incoming extras are unioned onto the parent — defensive: extras never ride synthetic entries
       today, ADR-0038 adopts git roots only);
     • a synthetic root covered by a GIT root is dropped outright — the git root is authoritative and its
       incoming payload is already a complete repository listing (a covered synthetic never carries
       extras, so nothing is lost);
     • otherwise it is appended, and any SYNTHETIC root it now covers is absorbed (the broader view wins,
       so /notes/sub followed by /notes leaves one tree rather than two overlapping ones); extras of the
       absorbed roots fold into the new entry (defensive, same reason as above).
   Containment only ever removes synthetic roots: a git repository nested inside a browsed directory is a
   project in its own right and must survive."
  [projects {:keys [root synthetic?] :as entry}]
  (let [projects (vec projects)
        t        (stamp-of entry)
        entry    (-> entry (update :files vec) (update :synthetic? boolean) (dissoc :listing))
        idx      (first (keep-indexed #(when (= (:root %2) root) %1) projects))
        ;; the most specific covering root — nested synthetic roots cannot coexist (see the absorb rule),
        ;; so at most one synthetic and any number of git roots can cover, and the deepest one wins
        cover-idx (when synthetic?
                    (->> (keep-indexed (fn [i p] (when (under? root (:root p)) i)) projects)
                         (sort-by #(- (count (:root (nth projects %)))))
                         first))]
    (cond
      idx
      (let [old     (nth projects idx)
            merged  (merge-extras (:extras old) (:extras entry))
            ;; superseded (a newer listing wrote the root): the project keeps that listing's files and metadata
            written (write-scope old "" t (:files entry))]
        (assoc projects idx (cond-> (dissoc (if written (merge written (dissoc entry :files)) old) :extras)
                              merged (assoc :extras merged))))

      (and cover-idx (:synthetic? (nth projects cover-idx)))
      (update projects cover-idx
              (fn [parent]
                (let [merged (merge-extras (:extras parent) (:extras entry))]
                  (cond-> (merge-subtree parent entry t)
                    merged (assoc :extras merged)))))

      cover-idx                                        ; covered by a git root — it refreshes itself
      projects

      :else
      (let [absorbed  (when synthetic?
                        (filterv #(and (:synthetic? %) (under? (:root %) root)) projects))
            projects' (if synthetic?
                        (filterv #(not (and (:synthetic? %) (under? (:root %) root))) projects)
                        projects)
            inherited (reduce (fn [acc p] (merge-extras acc (:extras p))) nil absorbed)
            merged    (merge-extras inherited (:extras entry))
            project   (cond-> (dissoc entry :extras) t (assoc :listings {"" t}))]
        (conj projects' (cond-> (reapply-absorbed project t absorbed) merged (assoc :extras merged)))))))

(defn prune-extras
  "Drop :transient? extras whose :path is no longer in the `retained` document set — their spilled
   backing file is being unlinked main-side at the same retention edge. Persistent (on-disk) extras
   stay until their project is removed, like any tree row. An emptied :extras key is removed entirely."
  [projects retained]
  (mapv (fn [p]
          (if-let [extras (:extras p)]
            (let [kept (filterv #(or (not (:transient? %))
                                     (contains? retained (:path %)))
                                extras)]
              (if (seq kept) (assoc p :extras kept) (dissoc p :extras)))
            p))
        (vec projects)))

(defn apply-tree-update
  "Apply a main-process tree payload. A payload without :scope is a full project entry and follows
   `merge-project`'s root/containment rules. A scoped payload keeps the exact project identity and
   replaces only root-relative files beneath that directory; if the project disappeared while an
   async refresh was in flight, the stale scoped reply is ignored. Either way the write is ordered by
   the payload's listing stamp (write-scope): an entry older than what a scope already shows changes
   nothing there."
  [projects {:keys [root scope files] :as entry}]
  (if (nil? scope)
    (merge-project projects entry)
    (let [projects (vec projects)
          idx      (first (keep-indexed #(when (= (:root %2) root) %1) projects))]
      (if (nil? idx)
        projects
        (if-let [p (write-scope (nth projects idx) scope (stamp-of entry) (vec files))]
          (assoc projects idx p)
          projects)))))

(defn remove-project
  "Drop the project rooted at `root` from the list. The root returns if a file under it is opened again —
   removal is a sidebar decision, not a persisted exclusion."
  [projects root]
  (filterv #(not= (:root %) root) (vec projects)))
