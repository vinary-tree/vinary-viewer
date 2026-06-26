(ns vinary.ui.menu-focus
  "Pure helpers for keyboard focus inside custom menus. Rows are rendered from vectors that may contain
   nils and :sep sentinels; only enabled map rows can be focused or activated.")

(defn focusable?
  "A menu row is focusable when it is an enabled item map."
  [row]
  (and (map? row) (not (:disabled? row))))

(defn focusable-indexes [rows]
  (->> rows
       (keep-indexed (fn [idx row] (when (focusable? row) idx)))
       vec))

(defn first-index [rows]
  (first (focusable-indexes rows)))

(defn last-index [rows]
  (last (focusable-indexes rows)))

(defn move-index
  "Move from `idx` by `dir`, wrapping across the focusable rows. A nil/currently-invalid index starts
   before the first row for positive movement and after the last row for negative movement."
  [rows idx dir]
  (let [indexes (focusable-indexes rows)
        n       (count indexes)]
    (when (pos? n)
      (let [pos (or (first (keep-indexed (fn [i v] (when (= v idx) i)) indexes))
                    (if (neg? dir) 0 -1))]
        (nth indexes (mod (+ pos dir) n))))))

(defn item-at [rows idx]
  (when (number? idx)
    (let [row (get rows idx)]
      (when (focusable? row) row))))
