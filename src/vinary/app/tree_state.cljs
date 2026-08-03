(ns vinary.app.tree-state
  "Pure expansion state for the Files tree. A scope is `[project-root absolute-directory]`.
   Keeping the arithmetic outside the view makes controlled <details> rendering and the
   renderer→main watcher reconciliation use exactly the same definition of 'expanded'."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vinary.app.projects :as projects]))

(defn file-directory-scopes
  "Every directory scope implied by one root-relative file, including the project root."
  [root file]
  (let [dirs (butlast (str/split (str file) #"/"))]
    (into [[root root]]
          (map (fn [n]
                 [root (projects/join-path root (str/join "/" (take n dirs)))])
               (range 1 (inc (count dirs)))))))

(defn project-directory-scopes [{:keys [root files]}]
  (into #{[root root]} (mapcat #(file-directory-scopes root %)) files))

(defn directory-scopes [projects]
  (into #{} (mapcat project-directory-scopes) projects))

(defn ancestor-scopes
  "The root→directory scope chain for an absolute directory under `root`, inclusive."
  [root directory]
  (when-let [rel (projects/relative-path directory root)]
    (if (str/blank? rel)
      [[root root]]
      (let [parts (remove str/blank? (str/split rel #"/"))]
        (into [[root root]]
              (map (fn [n]
                     [root (projects/join-path root (str/join "/" (take n parts)))])
                   (range 1 (inc (count parts)))))))))

(defn active-scopes
  "Scopes that must be open to reveal `active-path`. The deepest containing project wins."
  [projects active-path]
  (when (and active-path (seq projects))
    (when-let [{:keys [root]}
               (->> projects
                    (filter #(projects/under? active-path (:root %)))
                    (sort-by (comp - count :root))
                    first)]
      (let [rel       (projects/relative-path active-path root)
            parent-i  (.lastIndexOf rel "/")
            directory (if (neg? parent-i) root (projects/join-path root (subs rel 0 parent-i)))]
        (set (ancestor-scopes root directory))))))

(defn prune-scopes
  "Drop expansion/pending scopes that no longer exist in the file-derived project trees."
  [projects scopes]
  (set/intersection (set scopes) (directory-scopes projects)))

(defn effective-expanded
  "Effectively expanded scopes for the mounted Files view.

   Persistent `open-scopes` are augmented by directories force-opened by an active filter.
   A directory is effective only when every ancestor is also a candidate, so collapsing a
   parent suspends all descendant watchers without forgetting their own open state. A scope
   whose direct-click refresh is pending remains closed even if a concurrent filter or active-file
   reveal would otherwise force it open."
  [projects shown open-scopes expanding-scopes]
  (let [known        (directory-scopes projects)
        shown-roots  (into #{} (map :root) shown)
        forced       (into #{}
                           (mapcat (fn [{:keys [filtered?] :as project}]
                                     (when filtered? (project-directory-scopes project))))
                           shown)
        candidates   (-> (set/intersection known (set/union (set open-scopes) forced))
                         (set/difference (set expanding-scopes)))]
    (into #{}
          (filter (fn [[root directory :as candidate]]
                    (and (contains? shown-roots root)
                         (contains? candidates candidate)
                         (every? candidates (ancestor-scopes root directory)))))
          candidates)))

(defn open-project-roots [projects open-scopes]
  (into []
        (keep (fn [{:keys [root]}]
                (when (contains? (set open-scopes) [root root]) {:root root :path root})))
        projects))
