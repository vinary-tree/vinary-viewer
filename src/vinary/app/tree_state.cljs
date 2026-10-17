(ns vinary.app.tree-state
  "Pure expansion state for the Files tree. A scope is `[project-root absolute-directory]`.
   Keeping the arithmetic outside the view makes controlled <details> rendering and the
   renderer→main watcher reconciliation use exactly the same definition of 'expanded'."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vinary.app.projects :as projects]))

(defn file-directory-scopes
  "Every directory scope implied by one root-relative file, including the project root. The DEFINITION of a
   project's directory scopes — project-directory-scopes computes the same union faster, and is tested against
   this."
  [root file]
  (let [dirs (butlast (str/split (str file) #"/"))]
    (into [[root root]]
          (map (fn [n]
                 [root (projects/join-path root (str/join "/" (take n dirs)))])
               (range 1 (inc (count dirs)))))))

(defn- relative-directories
  "The root-relative directories `files` imply: every \"/\"-prefix of every path, found by scanning for separators
   instead of splitting and re-joining each path at each depth. Separators at the very end are ignored, as
   str/split ignores the empty segments they leave."
  [files]
  (persistent!
   (reduce (fn [acc f]
             (let [f   (str f)
                   end (loop [e (count f)] (if (and (pos? e) (= "/" (.charAt f (dec e)))) (recur (dec e)) e))]
               (loop [i (.indexOf f "/") acc acc]
                 (if (or (neg? i) (>= i end))
                   acc
                   (recur (.indexOf f "/" (inc i)) (conj! acc (subs f 0 i)))))))
           (transient #{})
           files)))

;; Directory scopes are derived from a project's files on every tree payload (prune-tree-ui), every expansion and every
;; Files render (effective-expanded) — for a repository of thousands of files, the renderer's longest task while typing
;; in the Files filter (docs/scientific/10 §11). Projects are immutable values and an unchanged project keeps its
;; identity across updates (vinary.app.projects replaces only the one that changed), so both derivations are memoized
;; on identity: only a project whose listing changed pays again.
(defonce ^:private project-scopes-memo (js/WeakMap.))
(defonce ^:private projects-scopes-memo (js/WeakMap.))

(defn- memoized
  "(f), cached in WeakMap `memo` under the value `k` — any object can key a WeakMap, and a collection is one (a string
   or nil is not: those are computed every time)."
  [^js memo k f]
  (if (contains? #{"object" "array" "function"} (goog/typeOf k))
    (let [v (.get memo k)]
      (if (undefined? v) (let [v (f)] (.set memo k v) v) v))
    (f)))

(defn project-directory-scopes
  "Every directory scope of one project: [root root] and one per directory its files imply — the union of
   file-directory-scopes over its files."
  [{:keys [root files] :as project}]
  (memoized project-scopes-memo project
            #(into #{[root root]}
                   (map (fn [rel] [root (projects/join-path root rel)]))
                   (relative-directories files))))

(defn directory-scopes [projects]
  (memoized projects-scopes-memo projects
            #(into #{} (mapcat project-directory-scopes) projects)))

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
  "Scopes that must be open to reveal `active-path`. The deepest containing project wins. A path
   contained by NO project but attached as an ADR-0038 extra (a diff adopted into a project it does
   not live under) reveals its adopting project's root — extras render at root level, so the root
   scope is the only one needed."
  [projects active-path]
  (when (and active-path (seq projects))
    (if-let [{:keys [root]}
             (->> projects
                  (filter #(projects/under? active-path (:root %)))
                  (sort-by (comp - count :root))
                  first)]
      (let [rel       (projects/relative-path active-path root)
            parent-i  (.lastIndexOf rel "/")
            directory (if (neg? parent-i) root (projects/join-path root (subs rel 0 parent-i)))]
        (set (ancestor-scopes root directory)))
      (when-let [{:keys [root]}
                 (first (filter (fn [p]
                                  (some #(projects/same-path? (:path %) active-path) (:extras p)))
                                projects))]
        #{[root root]}))))

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
