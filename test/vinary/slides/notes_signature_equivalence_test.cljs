(ns vinary.slides.notes-signature-equivalence-test
  "H-PAIRWORK-FIX, check E1 (docs/scientific/14): the fixed note-page signature returns exactly what the reference —
   the implementation before the fix, verbatim (vinary.slides.notes-reference) — returns, on generated page pairs
   built to reach every branch: thumbnail copies at and around max-thumbnail-scale with offsets on and beside the
   grids' cell boundaries, noise, many runs of one string, magnitudes that underflow, overflow or are not numbers,
   transforms that are not upright, and the match cap, both at its real value and rebound with set!. Further tests
   check the parts of the fix one at a time: the cells' hash (a function of an index's SameValueZero class, with fresh
   tables on every call), cells whose indices are huge, infinite, NaN or ±0, a table in which every cell collides, the
   binary search over a sweep of heights from the least subnormal to +∞, strings chosen to defeat a hash or a
   comparison (array indices, long common prefixes, lone surrogates, non-strings), the cap on a page's text, and the
   merge sort that groups the note page's strings. The reference has no text cap: it is compared only on pages within
   it, and (h) checks the cap itself."
  (:require [cljs.test :refer [deftest is]]
            [vinary.slides.notes :as notes]
            [vinary.slides.notes-reference :as reference]))

;; ---- a seeded generator (xorshift32): the same pairs on every run --------------------------------------------------

(defn- generator [seed]
  (let [state (volatile! (unsigned-bit-shift-right (bit-or seed 1) 0))]
    (fn []
      (let [x @state
            x (bit-xor x (bit-shift-left x 13))
            x (bit-xor x (unsigned-bit-shift-right x 17))
            x (unsigned-bit-shift-right (bit-xor x (bit-shift-left x 5)) 0)]
        (vreset! state x)
        (/ x 4294967296)))))

(defn- pick [rnd xs] (nth xs (js/Math.floor (* (rnd) (count xs)))))
(defn- between [rnd lo hi] (+ lo (* (rnd) (- hi lo))))
(defn- upto [rnd n] (js/Math.floor (* (rnd) (inc n))))

(def ^:private words ["x" "x" "ab" "Title" "1" "a b" "  " "" "Ünïcode" "x "])
(def ^:private extreme-magnitudes [5e-324 1e-310 1e-300 1e-160 1e160 1e300 js/Number.MAX_VALUE js/Infinity])
(def ^:private extreme-coordinates
  ;; NB the reader reads -0 as the integer 0, so the second entry is +0, as it was when this list was first run (H-PAIRWORK-FIX
  ;; E1 for design F); a true −0 is built at run time (negative-zero) by general-pair and the tests of ±0 below
  [0 -0 1e15 -1e15 1e300 -1e300 js/Infinity (- js/Infinity) js/NaN 7.5e9])

(def ^:private negative-zero
  ;; −0, computed: a -0 literal would be +0
  (/ -1 js/Infinity))
(def ^:private scales [0.125 0.25 0.4 (- 0.4 1e-12) (+ 0.4 1e-12) 0.39 0.41 0.69 1 1e-320])

(defn- run [s h x y] {:str s :t [h 0 0 h x y]})

(defn- odd-transform
  "A run whose transform is not a plain upright one: rotated, mirrored, sheared within or beyond the tolerance,
   with a missing component, or too short to have an origin."
  [rnd s h x y]
  {:str s
   :t   (case (upto rnd 6)
          0 [0 h (- h) 0 x y]
          1 [(- h) 0 0 h x y]
          2 [h (* 1e-4 h) 0 h x y]
          3 [h (* 0.01 h) 0 h x y]
          4 [h 0 nil h x y]
          5 [h 0 0 h]
          [h 0 (* 1e-4 h) h x y])})

(defn- random-run [rnd]
  (let [s (pick rnd words) h (between rnd 0.5 30) x (between rnd -20 380) y (between rnd -20 290)]
    (if (< (rnd) 0.12) (odd-transform rnd s h x y) (run s h x y))))

(defn- shuffle-with [rnd xs]
  (let [a (to-array xs)]
    (loop [i (dec (alength a))]
      (when (pos? i)
        (let [j (js/Math.floor (* (rnd) (inc i))) t (aget a i)]
          (aset a i (aget a j)) (aset a j t) (recur (dec i)))))
    (vec a)))

(defn- thumbnail-pair
  "A slide and a note page holding a scaled copy of some of its runs, at one of `scales`, offset by (ox, oy), each
   copied run jittered by a multiple of half a cell (tol / 2) so copies straddle the grids' boundaries; plus repeats
   of copied strings and noise."
  [rnd diag]
  (let [slide  (vec (repeatedly (inc (upto rnd 11)) #(random-run rnd)))
        sigma  (pick rnd scales)
        ox     (between rnd 0 200)
        oy     (between rnd 0 200)
        tol    (* notes/offset-tolerance diag)
        jitter (fn [] (* (pick rnd [0 0 0.49 0.51 1 -0.49 -1 (rnd)]) 0.5 tol))
        copied (for [{s :str [a b c d e f] :t} slide :when (< (rnd) 0.8)]
                 {:str s :t [(* sigma a) (* sigma b) (* sigma c) (* sigma d)
                             (+ ox (* sigma e) (jitter)) (+ oy (* sigma f) (jitter))]})
        again  (for [r copied :when (< (rnd) 0.3)] (update-in r [:t 4] + (* 3 tol)))
        noise  (repeatedly (upto rnd 6) #(random-run rnd))]
    [slide (shuffle-with rnd (concat copied again noise))]))

(defn- extreme-pair [rnd]
  (let [mag   #(if (< (rnd) 0.5) (pick rnd extreme-magnitudes) (between rnd 0.5 30))
        coord #(if (< (rnd) 0.5) (pick rnd extreme-coordinates) (between rnd 0 300))
        page  (fn [] (vec (repeatedly (inc (upto rnd 8)) #(run (pick rnd words) (mag) (coord) (coord)))))]
    [(page) (page)]))

(defn- dense-pair [rnd]
  (let [n     (inc (upto rnd 39))
        sigma (pick rnd scales)
        s     (pick rnd ["x" "ab"])
        slide (vec (for [i (range n)] (run s 10 (* 7 (mod i 8)) (* 9 (quot i 8)))))
        note  (vec (for [i (range (inc (upto rnd 39)))]
                     (run s (* 10 (if (< (rnd) 0.7) sigma 1)) (between rnd 0 60) (between rnd 0 60))))]
    [slide note]))

(defn- a-pair [rnd]
  (let [diag (if (< (rnd) 0.9) 453.544 (pick rnd [0 1e-12 1e12 js/Infinity]))
        [slide note] (case (upto rnd 4)
                       (0 1) (thumbnail-pair rnd (if (js/isFinite diag) (max diag 1) 453.544))
                       2     (extreme-pair rnd)
                       3     (dense-pair rnd)
                       [(vec (repeatedly (upto rnd 10) #(random-run rnd)))
                        (vec (repeatedly (upto rnd 10) #(random-run rnd)))])]
    [slide note diag]))

(defn- agree-on
  "Draws `n` pairs from `pair-of` (default a-pair) and compares the two signatures on each: the tally of the
   reference's verdicts and the first pair on which they differ, if any."
  ([rnd n] (agree-on rnd n a-pair))
  ([rnd n pair-of]
   (loop [k 0, tally {true 0 false 0 nil 0}, first-disagreement nil]
     (if (= k n)
       {:tally tally :first-disagreement first-disagreement}
       (let [[slide note diag] (pair-of rnd)
             want (reference/note-page-signature-reference? slide note diag)
             got  (notes/note-page-signature? slide note diag)]
         (recur (inc k) (update tally want inc)
                (or first-disagreement
                    (when-not (identical? want got) {:k k :want want :got got :slide slide :note note :diag diag}))))))))

(deftest the-fixed-signature-equals-the-reference-on-generated-pairs
  (let [{:keys [tally first-disagreement]} (agree-on (generator 20261018) 24000)]
    (println "[H-PAIRWORK E1] 24000 generated pairs at the real caps; the reference's verdicts:" tally)
    (is (nil? first-disagreement) (pr-str first-disagreement))
    (is (every? #(<= 50 (get tally %)) [true false nil]) "the generators reach all three verdicts")))

(deftest the-fixed-signature-equals-the-reference-around-a-small-match-cap
  (let [cap notes/max-signature-matches]
    (try
      (let [rnd (generator 14)
            results (vec (for [c [0 1 2 3 5 8 13 21 34 55 89 144]]
                           (do (set! notes/max-signature-matches c) (agree-on rnd 400))))]
        (println "[H-PAIRWORK E1] 4800 generated pairs at match caps 0…144; verdicts per cap:" (mapv :tally results))
        (is (every? (comp nil? :first-disagreement) results) (pr-str (some :first-disagreement results)))
        (is (every? #(pos? (get-in % [:tally nil])) (rest results)) "every small cap is reached"))
      (finally (set! notes/max-signature-matches cap)))))

(defn- cap-cases
  "Three pairs at a match cap of `cap` (a multiple of 64): 64 slide runs \"x\" × cap/64 note runs \"x\" at σ = 0.1 is
   exactly cap matches — not more than the cap, so the copy decides (the note runs sit where a copy of the slide would,
   so it covers): true; one more matched pair (a \"y\") is cap + 1: nil; the same matches against a slide that also
   holds 1000 characters no note run repeats need 532 characters, of 64 to be had, so no copy covers
   (docs/scientific/14's case C): false, after all eight passes."
  [cap]
  (let [per      (quot cap 64)
        slide    (vec (for [i (range 64)] (run "x" 10 (* 5 (mod i 8)) (* 5 (quot i 8)))))
        covering (vec (for [k (range per)] (let [i (mod k 64)] (run "x" 1 (+ 50 (* 0.5 (mod i 8))) (+ 50 (* 0.5 (quot i 8)))))))
        rnd      (generator 7)
        scatter  (vec (for [_ (range per)] (run "x" 1 (between rnd 0 360) (between rnd 0 270))))
        slide+y  (conj slide (run "y" 10 300 200))
        slide+z  (conj slide (run (apply str (repeat 1000 "z")) 10 0 250))]
    [[slide covering true] [slide+y (conj covering (run "y" 1 80 70)) nil] [slide+z scatter false]]))

(deftest the-fixed-signature-equals-the-reference-at-a-match-cap-of-65536
  ;; registered (design F) at the cap of 65 536, then the real one; the cap is now lower (H-PAIRWORK-FIX′'s Q2′), so it
  ;; is bound to 65 536 here, and (j) runs the same cases at the real cap
  (let [cap notes/max-signature-matches]
    (try
      (set! notes/max-signature-matches 65536)
      (doseq [[s n want] (cap-cases 65536)]
        (is (= want (reference/note-page-signature-reference? s n 453.544)) "the reference, as registered")
        (is (identical? (reference/note-page-signature-reference? s n 453.544) (notes/note-page-signature? s n 453.544))))
      (finally (set! notes/max-signature-matches cap)))))

(deftest the-fixed-signature-equals-the-reference-at-the-real-match-cap
  ;; Test (j): the same three cases at the cap in force
  (let [cap notes/max-signature-matches]
    (is (zero? (mod cap 64)) "the cap is a multiple of 64")
    (doseq [[s n want] (cap-cases cap)]
      (is (= want (reference/note-page-signature-reference? s n 453.544)) "the reference, at the real cap")
      (is (identical? (reference/note-page-signature-reference? s n 453.544) (notes/note-page-signature? s n 453.544))))))

;; ---- (a), (b): the cells' hash ---------------------------------------------------------------------------------------

(defn- double-of
  "The double whose 64 bits are hi:lo (two unsigned 32-bit words), built without arithmetic, so a NaN keeps its bits."
  [hi lo]
  (let [view (js/DataView. (js/ArrayBuffer. 8))]
    (.setUint32 view 0 hi)
    (.setUint32 view 4 lo)
    (.getFloat64 view 0)))

(defn- words-of
  "The two unsigned 32-bit words hi, lo of the double x."
  [x]
  (let [view (js/DataView. (js/ArrayBuffer. 8))]
    (.setFloat64 view 0 x)
    [(.getUint32 view 0) (.getUint32 view 4)]))

(deftest an-index-hash-depends-only-on-the-index-class
  ;; Test (a). For random tables, two different table entries are equal with probability 2^-32, so a check that two
  ;; hashes differ fails by chance with at most that probability; the ~2 400 such checks below, at most ~6e-7.
  (let [index-hash #'notes/index-hash
        tables     (notes/hash-tables)
        bits       (js/Float64Array. 1)
        words      (js/Uint32Array. (.-buffer bits))
        h          (fn [axis v] (index-hash tables bits words axis v))
        nans       [(double-of 0x7ff80000 0) (double-of 0xfff80000 0) (double-of 0x7ff00000 1)]
        values     [0 1 -1 65535 -65535 65536 -65536 (js/Math.pow 2 53) (- (js/Math.pow 2 53)) 1e300 -1e300
                    js/Infinity (- js/Infinity)]]
    (is (every? js/isNaN nans) "the three bit patterns are NaNs")
    (is (and (zero? negative-zero) (neg? (/ 1 negative-zero))) "negative-zero is −0")
    (doseq [axis [0 1 2]]
      (is (identical? (h axis 0) (h axis negative-zero)) "−0 and +0 hash alike")
      (is (apply = (map #(h axis %) nans)) "every NaN hashes alike, whatever its bits")
      (is (every? #(and (number? %) (js/Number.isInteger %) (<= -2147483648 % 2147483647))
                  (map #(h axis %) (concat values nans)))
          "a hash is a 32-bit integer")
      (is (= (inc (count values)) (count (set (map #(h axis %) (conj values (first nans))))))
          "distinct classes, distinct hashes")
      ;; every one of the 8 bytes takes part: flip each bit of each value; a flip that lands in another class
      ;; (a NaN, or ±0) is skipped
      (doseq [v (remove zero? values)
              bit (range 64)]
        (let [[hi lo] (words-of v)
              flipped (if (< bit 32)
                        (double-of hi (unsigned-bit-shift-right (bit-xor lo (bit-shift-left 1 bit)) 0))
                        (double-of (unsigned-bit-shift-right (bit-xor hi (bit-shift-left 1 (- bit 32))) 0) lo))]
          (when-not (or (js/isNaN flipped) (zero? flipped))
            (is (not= (h axis v) (h axis flipped)) (str "flipping bit " bit " of " v " changes its hash"))))))
    (is (not= (h 0 1) (h 1 1)) "each axis has its own tables")))

(deftest the-tables-are-fresh-on-every-call
  ;; Test (b): 6 144 random words per call. Two draws are equal with probability 2^-196608; 6 144 random words have
  ;; fewer than 6 100 distinct values with probability far below 2^-100.
  (let [t1 (notes/hash-tables)
        t2 (notes/hash-tables)]
    (is (instance? js/Int32Array t1))
    (is (= 6144 (.-length t1) (.-length t2)))
    (is (not= (vec (js/Array.from t1)) (vec (js/Array.from t2))) "two calls draw different tables")
    (is (< 6100 (count (set (js/Array.from t1)))) "the words look random")))

;; ---- (c): cells whose indices are huge, infinite, NaN or ±0 --------------------------------------------------------

(defn- copy-pair
  "A slide and a note page holding a copy of some of its runs at scale σ, with the copy's origin offset by the
   output of `offset` (called once per copied run with the run's index), plus noise. The slide's runs are placed by
   `origin` (called with the run's index)."
  [rnd sigma origin offset]
  (let [n      (inc (upto rnd 11))
        slide  (vec (for [i (range n)] (let [[x y] (origin i)] (run (pick rnd words) (between rnd 5 20) x y))))
        copied (for [[i {s :str [a _ _ d e f] :t}] (map-indexed vector slide) :when (< (rnd) 0.85)]
                 (let [[ox oy] (offset i)]
                   {:str s :t [(* sigma a) 0 0 (* sigma d) (+ ox (* sigma e)) (+ oy (* sigma f))]}))
        noise  (repeatedly (upto rnd 4) #(random-run rnd))]
    [slide (shuffle-with rnd (concat copied noise))]))

(defn- general-pair
  "A pair whose cells' indices lie beyond ±65 535, or are ±∞, NaN or ±0 (test (c))."
  [rnd]
  (let [diag  453.544
        tol   (* notes/offset-tolerance diag)
        sigma (pick rnd [0.125 0.25 0.4])
        far   #(* (pick rnd [1 -1]) (between rnd 65536 1e7) tol)
        half  #(* (pick rnd [0 0 0.49 0.51 -0.49 -0.51]) tol)
        inf   #(pick rnd [js/Infinity (- js/Infinity)])
        [slide note]
        (case (upto rnd 4)
          ;; far offsets: every cell's offset indices beyond the old packing's ±65 535, some straddling a boundary
          0 (let [ox (far) oy (far)]
              (copy-pair rnd sigma (fn [_] [(between rnd 0 300) (between rnd 0 250)]) (fn [_] [(+ ox (half)) (+ oy (half))])))
          ;; NaN origins: σ·NaN makes the copy's x offset NaN for the slide runs placed at x = NaN
          1 (copy-pair rnd sigma (fn [_] [(if (< (rnd) 0.7) js/NaN (between rnd 0 300)) (between rnd 0 250)])
                       (fn [_] [(between rnd 0 2) 40]))
          ;; infinite origins: x = ±∞ on the slide and ±∞ or finite on the note page — ∞ − ∞ is NaN, x − ∞ is −∞
          2 (let [[slide note] (copy-pair rnd sigma (fn [_] [(if (< (rnd) 0.6) (inf) (between rnd 0 300)) 10])
                                          (fn [_] [0 (between rnd 0 3)]))]
              [slide (mapv (fn [r] (if (< (rnd) 0.5) (assoc-in r [:t 4] (inf)) r)) note)])
          ;; signed zeros: slide runs at y = 0, the copies at y = +0, −0 or just above, so a c index is +0, −0 or 0
          ;; (the copy's y is set, not added: −0 + σ·0 would be +0)
          3 (let [[slide note] (copy-pair rnd sigma (fn [_] [(if (< (rnd) 0.5) js/NaN (between rnd 0 300)) 0])
                                          (fn [_] [(between rnd 0 2) 0]))]
              [slide (mapv (fn [r] (if (= 6 (count (:t r)))
                                     (assoc-in r [:t 5] (pick rnd [0 negative-zero (* 0.3 tol) (* 0.7 tol)]))
                                     r))
                           note)])
          ;; everything at once, with a true −0 among the coordinates
          (let [coords (conj extreme-coordinates negative-zero)
                mag    #(if (< (rnd) 0.5) (pick rnd extreme-magnitudes) (between rnd 0.5 30))
                coord  #(if (< (rnd) 0.5) (pick rnd coords) (between rnd 0 300))
                page   (fn [] (vec (repeatedly (inc (upto rnd 8)) #(run (pick rnd words) (mag) (coord) (coord)))))]
            [(page) (page)]))]
    [slide note diag]))

(deftest the-fixed-signature-equals-the-reference-on-general-path-pairs
  (let [{:keys [tally first-disagreement]} (agree-on (generator 65536) 6000 general-pair)]
    (println "[H-PAIRWORK E1] 6000 pairs with huge, infinite, NaN or ±0 cell indices; the reference's verdicts:" tally)
    (is (nil? first-disagreement) (pr-str first-disagreement))
    (is (every? #(<= 50 (get tally %)) [true false]) "the generators reach true and false")))

;; ---- (d): every cell collides ----------------------------------------------------------------------------------------

(deftest the-fixed-signature-equals-the-reference-when-every-cell-collides
  ;; with every table word 0, every cell of every grid hashes to 0 and shares bucket 0: the chains alone keep the cells
  ;; apart, which the hash's randomness otherwise hides
  (with-redefs [notes/hash-tables (fn [] (js/Int32Array. 6144))]
    (let [first-4000 (agree-on (generator 20261018) 4000)
          general    (agree-on (generator 65536) 2000 general-pair)]
      (println "[H-PAIRWORK E1] every cell in one bucket: 4000 + 2000 pairs; the reference's verdicts:"
               (:tally first-4000) (:tally general))
      (is (nil? (:first-disagreement first-4000)) (pr-str (:first-disagreement first-4000)))
      (is (nil? (:first-disagreement general)) (pr-str (:first-disagreement general))))))

;; ---- (e): the binary search over a sweep of heights ------------------------------------------------------------------

(defn- adjacent-double
  "The double next to the positive finite x: above it for step 1, below it for step −1."
  [x step]
  (let [[hi lo] (words-of x)
        lo'     (+ lo step)]
    (cond
      (> lo' 0xffffffff) (double-of (inc hi) 0)
      (neg? lo')         (double-of (dec hi) 0xffffffff)
      :else              (double-of hi lo'))))

(deftest prefix-count-is-the-linear-count-on-a-sweep-of-heights
  ;; Test (e): one string's heights, 2 000 log-spaced from 5e−324 to 1e300 and +∞; for each slide height hs of the
  ;; sweep, the heights next to 0.4·hs are added. prefix-count must count exactly the heights with h/hs ≤ bound, and
  ;; [lo, hi) must be exactly the heights the reference keeps (0 < σ ≤ max-thumbnail-scale). For every 50th slide
  ;; height it must do the same on each range [from, from + 9) of the sorted heights: a group is a range of the note
  ;; page's sorted runs (by-height).
  (let [prefix-count #'notes/prefix-count
        sweep        (vec (concat [5e-324]
                                  (for [k (range 1 1999)] (js/Math.pow 10 (+ -323 (* k (/ 623 1999)))))
                                  [1e300 js/Infinity]))
        bound        notes/max-thumbnail-scale
        failures     (volatile! [])
        slid         (volatile! 0)]
    (doseq [hs sweep]
      (let [edge    (* bound hs)
            near    (if (and (js/isFinite edge) (pos? edge))
                      (filterv #(and (js/isFinite %) (pos? %)) [(adjacent-double edge -1) edge (adjacent-double edge 1)])
                      [])
            heights (.sort (js/Float64Array.from (to-array (concat sweep near))))   ; a typed array sorts numerically
            n       (.-length heights)
            linear  (fn [b] (loop [k 0, c 0] (if (= k n) c (recur (inc k) (if (<= (/ (aget heights k) hs) b) (inc c) c)))))
            lo      (prefix-count heights 0 n hs 0)
            hi      (prefix-count heights 0 n hs bound)
            kept    (loop [k 0, acc []]
                      (if (= k n)
                        acc
                        (let [s (/ (aget heights k) hs)]
                          (recur (inc k) (if (and (pos? s) (<= s bound)) (conj acc k) acc)))))]
        (when-not (and (= lo (linear 0)) (= hi (linear bound)) (= kept (vec (range lo hi))))
          (vswap! failures conj {:hs hs :lo lo :hi hi :linear [(linear 0) (linear bound)]}))
        (when (zero? (mod (vswap! slid inc) 50))
          (doseq [from (range 0 (- n 8))
                  b    [0 bound]]
            (let [to   (+ from 9)
                  want (+ from (loop [k from, c 0] (if (= k to) c (recur (inc k) (if (<= (/ (aget heights k) hs) b) (inc c) c)))))
                  got  (prefix-count heights from to hs b)]
              (when-not (= want got)
                (vswap! failures conj {:hs hs :range [from to] :bound b :got got :want want})))))))
    (println "[H-PAIRWORK E1] prefix-count over" (count sweep) "slide heights × ~2003 note heights, and on every"
             "9-height range for" (quot @slid 50) "of those slide heights (every 50th)")
    (is (empty? @failures) (pr-str (take 3 @failures)))))

;; ---- (f): two crafted pairs where the grouping of a NaN or a ±0 index decides --------------------------------------

(deftest a-nan-index-is-one-cell-whatever-its-bits
  ;; Slide: "aaaaaaaaaa" and "cccccccccc" at x = NaN, "bbbbbbbbbb" at x = +∞, and 20 characters no copy carries:
  ;; need = 25. Their copies at σ = 0.25: σ·NaN gives a NaN offset with js/NaN's bits; ∞ − σ·∞ = ∞ − ∞ gives the
  ;; platform's default NaN (on x86-64, with the sign bit set). The reference's keys print both "NaN", so one cell
  ;; holds 30 characters: true. A NaN that is not equal to itself, or hashed by its own bits, splits them.
  (let [slide [(run "aaaaaaaaaa" 10 js/NaN 0) (run "bbbbbbbbbb" 10 js/Infinity 0) (run "cccccccccc" 10 js/NaN 0)
               (run "dddddddddddddddddddd" 10 50 200)]
        note  [(run "aaaaaaaaaa" 2.5 100 50) (run "bbbbbbbbbb" 2.5 js/Infinity 50) (run "cccccccccc" 2.5 100 50)]]
    (is (true? (reference/note-page-signature-reference? slide note 453.544)) "the reference, as constructed")
    (dotimes [_ 40]                                    ; 40 fresh draws of the tables
      (is (true? (notes/note-page-signature? slide note 453.544))))))

(deftest a-zero-index-is-one-cell-whatever-its-sign
  ;; Slide: three runs of 10 characters at y = 0 and 20 characters no copy carries: need = 25. Their copies at
  ;; σ = 0.25 sit at y = +0, −0 and 0.7 of a cell, so their c indices are +0, −0 and +0 in the unshifted grids (one
  ;; cell, 30 characters: true, as the reference's keys print both zeros "0"), and −1, −1, 0 in the shifted ones (20
  ;; characters at most). A −0 hashed by its own bits splits the unshifted cell too.
  (let [tol   (* notes/offset-tolerance 453.544)
        slide [(run "aaaaaaaaaa" 10 0 0) (run "bbbbbbbbbb" 10 0 0) (run "cccccccccc" 10 0 0)
               (run "dddddddddddddddddddd" 10 50 200)]
        note  [(run "aaaaaaaaaa" 2.5 100 0) (run "bbbbbbbbbb" 2.5 100 negative-zero) (run "cccccccccc" 2.5 100 (* 0.7 tol))]]
    (is (true? (reference/note-page-signature-reference? slide note 453.544)) "the reference, as constructed")
    (dotimes [_ 40]
      (is (true? (notes/note-page-signature? slide note 453.544))))))

;; ---- (g): strings chosen to defeat a hash or a comparison ------------------------------------------------------------

(defn- family-string
  "A :str from one of test (g)'s families: array-index strings whose values share their low bits (one bucket of a
   string-keyed js/Map in Electron's V8, docs/scientific/14), a long common prefix with the last code unit differing,
   strings that are prefixes of each other, lone surrogates and code units around them and at the top of the range, the
   empty string and white space, ordinary words, and values that are not strings."
  [rnd]
  (case (upto rnd 6)
    0 (str (* 2048 (upto rnd 4095)))
    1 (str (apply str (repeat 40 "p")) (pick rnd ["A" "B" "C" "D"]))
    2 (subs "abcdef" 0 (upto rnd 6))
    3 (pick rnd ["\uD800" "\uDBFF" "\uDC00" "\uDFFF" "\uD7FF" "\uE000" "\uFFFF" "\uFFFE" "\uD800\uDC00" "\uDC00\uD800"])
    4 (pick rnd ["" " " "  " "\t" "\n x" "x\n"])
    5 (pick rnd ["x" "ab" "Title"])
    (pick rnd [nil 0 1 2048 negative-zero])))

(defn- family-pair
  "A pair whose strings are drawn from family-string, with a copy of some of the slide's runs at a thumbnail scale
   (so that what matches turns on how the strings group) and runs that repeat the families at other scales."
  [rnd]
  (let [diag   453.544
        tol    (* notes/offset-tolerance diag)
        slide  (vec (for [_ (range (inc (upto rnd 15)))]
                      (run (family-string rnd) (between rnd 5 20) (between rnd 0 300) (between rnd 0 250))))
        sigma  (pick rnd [0.125 0.25 0.4 0.41])
        ox     (between rnd 0 100)
        oy     (between rnd 0 100)
        copied (for [{s :str [a _ _ d e f] :t} slide :when (< (rnd) 0.7)]
                 {:str s :t [(* sigma a) 0 0 (* sigma d) (+ ox (* sigma e) (* (pick rnd [0 0.49 -0.49 1]) 0.5 tol))
                             (+ oy (* sigma f))]})
        others (repeatedly (upto rnd 10)
                           #(run (family-string rnd) (between rnd 0.5 30) (between rnd 0 300) (between rnd 0 250)))]
    [slide (shuffle-with rnd (concat copied others)) diag]))

(deftest the-fixed-signature-equals-the-reference-on-adversarial-strings
  (let [{:keys [tally first-disagreement]} (agree-on (generator 2048) 6000 family-pair)]
    (println "[H-PAIRWORK E1] 6000 pairs of adversarial strings; the reference's verdicts:" tally)
    (is (nil? first-disagreement) (pr-str first-disagreement))
    (is (every? #(<= 50 (get tally %)) [true false]) "the generators reach true and false")))

;; ---- (h): the cap on a page's text ----------------------------------------------------------------------------------

(deftest the-text-cap-bounds-each-page
  ;; Test (h). A slide of 64 runs "x" and a note page holding their copy at σ = 0.25: true. Either page is padded by a
  ;; ROTATED run of "q"s — counted in its text, but not in the slide's characters nor among the note page's upright runs
  ;; — to exactly max-signature-chars code units (compared: the reference's true), or one more (nil). A run whose :str
  ;; is not a string counts nothing.
  (let [cap    notes/max-signature-chars
        slide  (vec (for [i (range 64)] (run "x" 10 (* 5 (mod i 8)) (* 5 (quot i 8)))))
        note   (vec (for [i (range 64)] (run "x" 2.5 (+ 50 (* 1.25 (mod i 8))) (+ 50 (* 1.25 (quot i 8))))))
        units  (fn [page] (reduce + (map #(count (:str %)) page)))
        pad    (fn [page total] {:str (apply str (repeat (- total (units page)) "q")) :t [0 10 -10 0 300 20]})
        padded (fn [page total] (conj page (pad page total)))
        sig    (fn [s n] (notes/note-page-signature? s n 453.544))
        ref    (fn [s n] (reference/note-page-signature-reference? s n 453.544))]
    (is (true? (ref slide note)) "the pair, as constructed")
    (is (true? (sig (padded slide cap) note)) "a slide of exactly the cap is compared")
    (is (true? (ref (padded slide cap) note)) "…as the reference compares it")
    (is (nil? (sig (padded slide (inc cap)) note)) "a slide one code unit over the cap is not")
    (is (true? (sig slide (padded note cap))) "a note page of exactly the cap is compared")
    (is (true? (ref slide (padded note cap))) "…as the reference compares it")
    (is (nil? (sig slide (padded note (inc cap)))) "a note page one code unit over the cap is not")
    (is (true? (sig (conj (padded slide cap) {:str nil :t [10 0 0 10 0 0]} {:str 12345 :t [10 0 0 10 0 0]}) note))
        "a run whose :str is not a string counts nothing")))

;; ---- (i): the merge sort ------------------------------------------------------------------------------------------

(deftest the-merge-sort-is-the-stable-sort
  ;; Test (i). A stable sort by a total preorder has exactly one result, so sorted-runs must return what
  ;; Array.prototype.sort (stable since ES2019) returns with the same order: 2 000 random inputs of 0 to 300 runs,
  ;; with repeated keys, (g)'s strings and heights that include +∞.
  (let [sorted-runs #'notes/sorted-runs
        rnd         (generator 9)
        mismatches  (volatile! [])]
    (dotimes [t 2000]
      (let [n    (upto rnd 300)
            strs (js/Array. n)
            hs   (js/Float64Array. n)]
        (dotimes [k n]
          (aset strs k (let [s (family-string rnd)] (if (string? s) s (str s))))
          (aset hs k (pick rnd [1 2 2 3 js/Infinity (between rnd 0.5 30)])))
        (let [got  (vec (js/Array.from (sorted-runs strs hs n)))
              want (vec (.sort (to-array (range n))
                               (fn [a b] (let [c (compare (aget strs a) (aget strs b))]
                                           (cond (not (zero? c))             c
                                                 (< (aget hs a) (aget hs b)) -1
                                                 (> (aget hs a) (aget hs b)) 1
                                                 :else                       0)))))]
          (when-not (= got want) (vswap! mismatches conj {:t t :n n})))))
    (is (empty? @mismatches) (pr-str (take 3 @mismatches)))))
