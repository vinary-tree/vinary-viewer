(ns vinary.git.graph
  "Lane assignment for commit graphs (ADR-0039) — the single shared shape behind the sidebar
   mini-rail and the Commit Graph document. Pure, deterministic, incremental.

   STATE. :lanes is a vector whose i-th slot holds the commit hash lane i expects to meet next
   (nil = free). Feeding pages of commits (children strictly before parents — git --date-order)
   through `assign` threads that state, so pages append with no recomputation:

     (assign s (concat a b))  ≡  rows of (assign s a) ++ rows of (assign (:state (assign s a)) b)

   A commit no lane expects (a branch tip — or date-order clock skew) opens a fresh lane: skew
   degrades, it never throws. The last page's open expectations simply continue past the window,
   which is also what truncated history should look like.

   ROWS. {:hash :lane :edges [{:from :to :kind}] :active} — edges span from this row's center
   toward the next row's center, and each renderer maps kinds to its own geometry:
     :collapse  a sibling child-edge curving INTO this dot (its lane frees on this row)
     :continue  the dot continuing its own lane downward to its first parent
     :merge     the dot joining a lane some other child already expects (this lane closes)
     :branch    the dot opening a lane for an extra parent (a merge commit's other leg)
     :pass      an uninvolved lane running straight through the row
   :active = lane count at the row (a rail-width hint). Lane indices are leftmost-first and reuse
   freed slots, which keeps rails compact and the assignment deterministic."
  )

(defn init-state [] {:lanes []})

(defn- first-index
  "Leftmost index of v satisfying pred, or nil."
  [pred v]
  (loop [i 0]
    (cond
      (>= i (count v)) nil
      (pred (nth v i)) i
      :else            (recur (inc i)))))

(defn- lane-of
  "Leftmost lane expecting `hash`, excluding index `exclude` (-1 = none), or nil."
  [lanes hash exclude]
  (first (keep-indexed (fn [i x] (when (and (not= i exclude) (= x hash)) i)) lanes)))

(defn- trim-trailing-nils [v]
  (loop [n (count v)]
    (if (and (pos? n) (nil? (nth v (dec n))))
      (recur (dec n))
      (subvec v 0 n))))

(defn assign
  "Fold `commits` ([{:hash :parents} …], children-first) through `state` → {:rows [row …] :state
   state'}. See the namespace docstring for the row shape and the incrementality contract."
  [state commits]
  (loop [cs    (seq commits)
         lanes (vec (:lanes state))
         rows  (transient [])]
    (if-let [{:keys [hash parents]} (first cs)]
      (let [entering  (into [] (keep-indexed (fn [i h] (when (= h hash) i))) lanes)
            lane      (or (first entering) (first-index nil? lanes) (count lanes))
            lanes     (if (= lane (count lanes)) (conj lanes nil) lanes)
            collapse  (vec (rest entering))
            lanes     (reduce (fn [ls i] (assoc ls i nil)) lanes collapse)
            edges     (into [] (map (fn [i] {:from i :to lane :kind :collapse})) collapse)
            p0        (first parents)
            merge-j   (when p0 (lane-of lanes p0 lane))
            [lanes edges] (cond
                            (nil? p0) [(assoc lanes lane nil) edges]
                            merge-j   [(assoc lanes lane nil)
                                       (conj edges {:from lane :to merge-j :kind :merge})]
                            :else     [(assoc lanes lane p0)
                                       (conj edges {:from lane :to lane :kind :continue})])
            [lanes edges opened]
            (reduce (fn [[ls es op] pk]
                      (if-let [j (lane-of ls pk -1)]
                        [ls (conj es {:from lane :to j :kind :merge}) op]
                        (let [s  (or (first-index nil? ls) (count ls))
                              ls (if (= s (count ls)) (conj ls pk) (assoc ls s pk))]
                          [ls (conj es {:from lane :to s :kind :branch}) (conj op s)])))
                    [lanes edges #{}]
                    (rest parents))
            lanes  (trim-trailing-nils lanes)
            ;; uninvolved-but-occupied lanes run straight through: everything still expecting a
            ;; commit, except the dot's own lane (its :continue covers it) and lanes opened by a
            ;; :branch edge this very row (their line starts at the dot, not the row's top edge)
            edges  (into edges
                         (keep-indexed
                          (fn [i h]
                            (when (and (some? h) (not= i lane) (not (contains? opened i)))
                              {:from i :to i :kind :pass})))
                         lanes)]
        (recur (next cs) lanes
               (conj! rows {:hash hash :lane lane :edges edges :active (count lanes)})))
      {:rows (persistent! rows) :state {:lanes lanes}})))

(defn max-lane
  "The highest lane index any row's dot or through-lane occupies — the rail-width driver."
  [rows]
  (reduce (fn [m {:keys [lane active]}] (max m lane (dec active))) 0 rows))
