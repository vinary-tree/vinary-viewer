(ns vinary.app.nav
  "The browser-tab model over app-db. A tab is a view: {:id :uri :hist {:stack [uri…] :idx n}}. Tabs live
   in [:ui :tabs] (ordered) + [:ui :active-tab] (id); DataScript caches document content by :doc/path.
   These pure helpers read + transform that state — events thread the transforms; subs / resolver / palette
   read via the reads + base-ctx. One source of truth for navigation."
  (:require [vinary.app.uri :as uri]))

;; ---- reads ----
(defn tabs       [db] (get-in db [:ui :tabs]))
(defn active-id  [db] (get-in db [:ui :active-tab]))
(defn active-tab [db] (let [id (active-id db)] (first (filter #(= (:id %) id) (tabs db)))))
(defn active-uri [db] (:uri (active-tab db)))
(defn active-path [db] (uri/file-path (active-uri db)))
(defn find-tab   [db uri] (first (filter #(= (:uri %) uri) (tabs db))))
(defn can-back?    [db] (let [{:keys [idx]} (:hist (active-tab db))] (boolean (and idx (pos? idx)))))
(defn can-forward? [db] (let [{:keys [stack idx]} (:hist (active-tab db))]
                          (boolean (and idx (< idx (dec (count stack)))))))

(defn base-ctx
  "The navigation slice of the command-resolution context (read by the keymap resolver + palette)."
  [db]
  {:tabs (tabs db) :active-tab (active-id db) :active-path (active-path db)
   :can-back? (can-back? db) :can-forward? (can-forward? db)})

;; ---- transforms (pure db→db, or db→[db' …]) ----
(defn activate [db id] (assoc-in db [:ui :active-tab] id))

;; a history entry is {:uri :scroll}; the tab's top-level :uri mirrors the current entry's :uri
(defn- mk-tab [id uri] {:id id :uri uri :hist {:stack [{:uri uri :scroll 0}] :idx 0}})

(defn add-tab
  "Append a new tab for uri and make it active."
  [db uri]
  (let [id (get-in db [:ui :next-tab-id] 0)]
    (-> db
        (update-in [:ui :tabs] (fnil conj []) (mk-tab id uri))
        (assoc-in [:ui :active-tab] id)
        (assoc-in [:ui :next-tab-id] (inc id)))))

(defn duplicate-tab
  "Duplicate tab id immediately after itself and make the duplicate active."
  [db id]
  (let [ts  (vec (tabs db))
        idx (first (keep-indexed #(when (= (:id %2) id) %1) ts))]
    (if (nil? idx)
      db
      (let [new-id    (get-in db [:ui :next-tab-id] 0)
            duplicate (assoc (get ts idx) :id new-id)
            insert-at (inc idx)]
        (-> db
            (assoc-in [:ui :tabs]
                      (vec (concat (subvec ts 0 insert-at)
                                   [duplicate]
                                   (subvec ts insert-at))))
            (assoc-in [:ui :active-tab] new-id)
            (assoc-in [:ui :next-tab-id] (inc new-id)))))))

(defn- update-active [db f]
  (let [id (active-id db)]
    (update-in db [:ui :tabs] (fn [ts] (mapv #(if (= (:id %) id) (f %) %) ts)))))

;; ---- per-tab history with scroll position ----
(defn- capture-scroll
  "Save scrollTop onto the active tab's current history entry (called before leaving that entry)."
  [t scroll]
  (assoc-in t [:hist :stack (get-in t [:hist :idx]) :scroll] (or scroll 0)))

(defn cur-scroll
  "The saved scrollTop of the active tab's current history entry (restored on tab switch)."
  [db]
  (let [{:keys [stack idx]} (:hist (active-tab db))] (:scroll (get stack idx) 0)))

(defn save-scroll
  "Save scroll onto the active tab's current entry — before switching tabs / opening a new tab elsewhere."
  [db scroll]
  (update-active db #(capture-scroll % scroll)))

(defn nav-active
  "Point the active tab at uri, saving the leaving entry's `scroll` and pushing a new entry (scroll 0);
   a repeat of the current entry just refreshes the uri."
  [db uri scroll]
  (update-active db
    (fn [t]
      (let [t (capture-scroll t scroll)
            {:keys [stack idx]} (:hist t)]
        (if (= uri (:uri (get stack idx)))
          (assoc t :uri uri)
          (let [stack' (conj (vec (take (inc idx) stack)) {:uri uri :scroll 0})]
            (assoc t :uri uri :hist {:stack stack' :idx (dec (count stack'))})))))))

(defn step
  "Move the active tab back (-1) / forward (+1), saving the leaving entry's `scroll`. Returns
   [db' uri target-scroll] or nil at an end."
  [db delta scroll]
  (let [t (active-tab db) {:keys [stack idx]} (:hist t) idx' (+ (or idx 0) delta)]
    (when (and idx (<= 0 idx') (< idx' (count stack)))
      (let [entry (get stack idx')]
        [(update-active db (fn [t] (-> (capture-scroll t scroll)
                                       (assoc :uri (:uri entry))
                                       (assoc-in [:hist :idx] idx'))))
         (:uri entry) (:scroll entry 0)]))))

(defn reorder
  "Move the tab `from-id` to land at insertion gap `insert-idx` (0..n) in the tab strip."
  [db from-id insert-idx]
  (let [ts   (tabs db)
        from (first (keep-indexed #(when (= (:id %2) from-id) %1) ts))]
    (if (and from insert-idx)
      (let [tab     (get ts from)
            removed (vec (concat (subvec ts 0 from) (subvec ts (inc from))))
            ins     (max 0 (min (count removed) (if (< from insert-idx) (dec insert-idx) insert-idx)))]
        (assoc-in db [:ui :tabs] (vec (concat (subvec removed 0 ins) [tab] (subvec removed ins)))))
      db)))

(defn close
  "Remove the tab id (activating a neighbor if it was active). Returns [db' closed-uri still-open?]."
  [db id]
  (let [ts  (tabs db)
        idx (first (keep-indexed #(when (= (:id %2) id) %1) ts))]
    (if (nil? idx)
      [db nil true]
      (let [closing    (get ts idx)
            ts'        (vec (concat (subvec ts 0 idx) (subvec ts (inc idx))))
            new-active (if (= (active-id db) id) (:id (or (get ts' idx) (get ts' (dec idx)))) (active-id db))]
        [(-> db (assoc-in [:ui :tabs] ts') (assoc-in [:ui :active-tab] new-active))
         (:uri closing)
         (boolean (some #(= (:uri %) (:uri closing)) ts'))]))))

(defn view-source? [db] (boolean (:view-source? (active-tab db))))
(defn toggle-source
  ([db]    (toggle-source db (active-id db)))
  ([db id] (update-in db [:ui :tabs] (fn [ts] (mapv #(if (= (:id %) id) (update % :view-source? not) %) ts)))))

(defn nth-id
  "The id of the tab `dir` steps from the active one (wrapping), or nil if no tabs."
  [db dir]
  (let [ts (tabs db) n (count ts)]
    (when (pos? n)
      (let [i (or (first (keep-indexed #(when (= (:id %2) (active-id db)) %1) ts)) -1)]
        (:id (nth ts (mod (+ i dir) n)))))))
