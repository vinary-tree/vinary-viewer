(ns vinary.slides.notes-reference
  "The note-page signature as it was before H-PAIRWORK-FIX (git 2b78f18, vinary.slides.notes), kept verbatim as the
   REFERENCE the fixed one must equal on every input (docs/scientific/14): it visits every pair of equal strings and
   keys each cell by its string. Only the names differ; the helpers the fix did not touch (run-height, upright?,
   glyphs, rotated-date?, the tolerances, the caps and the eight grids) are the namespace's own, so both read the same
   definitions — a cap rebound with set! in a test applies to both."
  (:require [vinary.slides.notes :as notes]))

(defn- copy-covers-reference?
  [glyph-of matches tol need]
  (let [m (count matches)
        grid-shifts @#'notes/grid-shifts
        log-scale-cell @#'notes/log-scale-cell]
    (loop [g 0]
      (if (= g (count grid-shifts))
        false
        (let [[s0 s1 s2] (nth grid-shifts g)
              seen (js/Map.)
              sums (js/Map.)
              hit? (loop [k 0]
                     (if (= k m)
                       false
                       (let [{:keys [i ls tx ty]} (nth matches k)
                             cell (str (js/Math.floor (- (/ ls log-scale-cell) s0)) ","
                                       (js/Math.floor (- (/ tx tol) s1)) ","
                                       (js/Math.floor (- (/ ty tol) s2)))
                             ids  (or (.get seen cell) (let [ids (js/Set.)] (.set seen cell ids) ids))]
                         (if (.has ids i)
                           (recur (inc k))
                           (let [sum (+ (or (.get sums cell) 0) (nth glyph-of i))]
                             (.add ids i)
                             (.set sums cell sum)
                             (if (>= sum need) true (recur (inc k))))))))]
          (if hit? true (recur (inc g))))))))

(defn note-page-signature-reference?
  "vinary.slides.notes/note-page-signature? as of git 2b78f18."
  [slide note diag]
  (let [upright?   #'notes/upright?
        glyphs     #'notes/glyphs
        run-height #'notes/run-height]
    (when (and (<= (count slide) notes/max-signature-runs) (<= (count note) notes/max-signature-runs))
      (let [s-runs   (into [] (filter #(and (upright? %) (pos? (glyphs %)))) slide)
            glyph-of (mapv glyphs s-runs)
            total    (reduce + 0 glyph-of)]
        (if (zero? total)
          (when (some #(pos? (glyphs %)) note) (notes/rotated-date? note))
          (let [by-str  (group-by :str (filter upright? note))
                tol     (max 1e-9 (* notes/offset-tolerance diag))
                matches (loop [i 0, acc (transient [])]
                          (cond
                            (> (count acc) notes/max-signature-matches) nil
                            (= i (count s-runs))                        (persistent! acc)
                            :else
                            (let [r  (nth s-runs i)
                                  hs (run-height r)
                                  [_ _ _ _ xs ys] (:t r)]
                              (recur (inc i)
                                     (reduce (fn [acc k]
                                               (let [sigma (/ (run-height k) hs)
                                                     [_ _ _ _ xk yk] (:t k)]
                                                 (if (and (pos? sigma) (<= sigma notes/max-thumbnail-scale))
                                                   (conj! acc {:i i :ls (js/Math.log sigma)
                                                               :tx (- xk (* sigma xs)) :ty (- yk (* sigma ys))})
                                                   acc)))
                                             acc
                                             (get by-str (:str r)))))))]
            (when matches
              (copy-covers-reference? glyph-of matches tol (/ total 2)))))))))
