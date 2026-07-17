(ns vinary.app.nav
  "The browser-tab model over app-db. A tab is a view: {:id :uri :hist {:stack [uri…] :idx n}}. Tabs live
   in [:ui :tabs] (ordered) + [:ui :active-tab] (id); DataScript caches document content by :doc/path.
   These pure helpers read + transform that state — events thread the transforms; subs / resolver / palette
   read via the reads + base-ctx. One source of truth for navigation."
  (:require [clojure.string :as str]
            [vinary.app.uri :as uri]))

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

(defn retained-file-paths
  "Every path still reachable from any open tab — its history uris (as local file paths) PLUS each tab's active +
   MRU facet paths (already in :doc/path form). This is the ownership set for main-process file watchers and the
   renderer content cache. The facet paths are load-bearing: a facet's file is shown IN-PLACE and is NOT a history
   entry, so without retaining it the first eviction pass would retract/unwatch the just-loaded sibling doc."
  [db]
  (->> (tabs db)
       (mapcat (fn [t]
                 (concat (keep (comp uri/file-path :uri) (get-in t [:hist :stack]))
                         (keep :path [(:facet t)])
                         (vals (:facet-mru t)))))
       (remove nil?)
       distinct
       vec))

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
            ;; a new destination is a new document → clear the in-place facet so it gets a fresh default
            (-> (assoc t :uri uri :hist {:stack stack' :idx (dec (count stack'))})
                (dissoc :facet :facet-mru))))))))

(defn nav-tab
  "Point the tab with `id` at uri, pushing a history entry (a repeat just refreshes the uri) — like
   `nav-active` but keyed on a supplied id, NOT the active tab, and without capturing scroll (the owner tab
   isn't the one being viewed). Records the web view's navigation onto its OWNER tab while the user may be on
   a different tab. No-op if no tab has that id."
  [db id uri]
  (if (some #(= (:id %) id) (tabs db))
    (update-in db [:ui :tabs]
               (fn [ts]
                 (mapv (fn [t]
                         (if (= (:id t) id)
                           (let [{:keys [stack idx]} (:hist t)]
                             (if (= uri (:uri (get stack idx)))
                               (assoc t :uri uri)
                               (let [stack' (conj (vec (take (inc idx) stack)) {:uri uri :scroll 0})]
                                 (-> (assoc t :uri uri :hist {:stack stack' :idx (dec (count stack'))})
                                     (dissoc :facet :facet-mru)))))
                           t))
                       ts)))
    db))

(defn step
  "Move the active tab back (-1) / forward (+1), saving the leaving entry's `scroll`. Returns
   [db' uri target-scroll] or nil at an end."
  [db delta scroll]
  (let [t (active-tab db) {:keys [stack idx]} (:hist t) idx' (+ (or idx 0) delta)]
    (when (and idx (<= 0 idx') (< idx' (count stack)))
      (let [entry (get stack idx')]
        [(update-active db (fn [t] (-> (capture-scroll t scroll)
                                       (assoc :uri (:uri entry))
                                       (assoc-in [:hist :idx] idx')
                                       (dissoc :facet :facet-mru))))  ; back/forward lands on a different doc
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

;; ---- per-tab view FACET: which collocated representation is showing + as which type (:preview/:source) ----
;; A tab's :facet is {:path :type}; nil = follow the default (see vinary.app.facet/default-facet). :facet-mru
;; remembers the last file chosen per type ({:preview p :source p}) so a combo button's MAIN region re-activates
;; it. This uniform facet replaces the old :view-source? / :representation axes; the derivations (options, active,
;; show?) live in vinary.app.facet. Facet switches are IN-PLACE (no history push — set-facet below), so the tab
;; keeps its identity; navigation to a DIFFERENT uri clears the facet (a new doc gets a fresh default).
(defn facet     [db] (:facet (active-tab db)))
(defn facet-mru [db] (:facet-mru (active-tab db)))
(defn set-facet
  "Show `path` as `type` (:preview/:source) on the target tab, and remember it as that type's MRU. IN-PLACE — no
   history entry (the tab's uri/identity is unchanged; only the shown facet changes)."
  ([db path type]    (set-facet db (active-id db) path type))
  ([db id path type] (update-in db [:ui :tabs]
                                (fn [ts] (mapv #(if (= (:id %) id)
                                                  (-> % (assoc :facet {:path path :type type})
                                                        (assoc-in [:facet-mru type] path))
                                                  %)
                                               ts)))))

;; ---- Diff unified⇄split view (per-tab; only meaningful for a .diff/.patch doc) ----
;; A tab's :diff-view is the user's explicit choice (:unified / :split); nil follows the default (:unified —
;; side-by-side is opt-in, so opening a diff never triggers the split view's on-disk source resolution).
(defn diff-view [db] (:diff-view (active-tab db)))
(defn set-diff-view
  ([db view]    (set-diff-view db (active-id db) view))
  ([db id view] (update-in db [:ui :tabs] (fn [ts] (mapv #(if (= (:id %) id) (assoc % :diff-view view) %) ts)))))
(defn effective-diff-view
  "The diff view to show: the tab's explicit choice, else :unified. Pure so the sub + tests share it."
  [tab-view]
  (or tab-view :unified))

(defn nth-id
  "The id of the tab `dir` steps from the active one (wrapping), or nil if no tabs."
  [db dir]
  (let [ts (tabs db) n (count ts)]
    (when (pos? n)
      (let [i (or (first (keep-indexed #(when (= (:id %2) (active-id db)) %1) ts)) -1)]
        (:id (nth ts (mod (+ i dir) n)))))))

;; ---- directory browser selection (shared by the renderer view, keyboard nav, and Alt+Down) ----
(defn sort-entries
  "Directory entries (from main) sorted dirs-first, then case-insensitive by name. The directory view
   and keyboard selection share this order so the highlight and the rendered list always agree."
  [entries]
  (sort-by (juxt #(if (:dir? %) 0 1) #(str/lower-case (or (:name %) ""))) entries))

(defn effective-selected
  "The highlighted entry path for a directory listing: the explicit `dir-selected` if it is a current
   child, else the remembered `trail` child for `dir` if it is a current child, else the first entry
   (or nil when empty)."
  [dir entries dir-selected trail]
  (let [child-paths (into #{} (map :path) entries)]
    (or (when (contains? child-paths dir-selected) dir-selected)
        (let [t (get trail dir)] (when (contains? child-paths t) t))
        (:path (first (sort-entries entries))))))
