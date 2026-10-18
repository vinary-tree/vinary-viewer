;; docs/theory/12 §2: check the gap decomposition THEOREM (as the document states it, not as the code computes it), the
;; lemmas and propositions that follow it (§2.2–§2.4), and the code's `interleaving` and `restartable?` (loaded
;; verbatim from notes.cljs), against a direct enumeration of every role assignment of every label sequence of length
;; 1..max-n over the labels 0..max-label — under Beamer's rules R (ADR-0048 §2.1) and under R+, the rules with a
;; restarted frame counter admitted (a slide may carry any label up to f + 1).
;;
;; usage: clojure -M verify_automaton.clj <max-n> <max-label>
;; Prints one tally per line; a line whose key is in CAPITALS is a violation. Exits 1 when there is one.
(load-file (str (.getParent (.getAbsoluteFile (java.io.File. (str *file*)))) "/notes_extract.clj"))
(notes-extract/load-notes!)
(require '[clojure.set :as set])

(def max-n (Long/parseLong (or (first *command-line-args*) "5")))
(def max-label (Long/parseLong (or (second *command-line-args*) "7")))

;; ---- the rules, enumerated directly -------------------------------------------------------------------------------

(defn valid?
  "Is the role vector `roles` (true = note page) a valid parse of the labels L? Under R (`plus?` false) a slide
   carries f or f + 1; under R+ (`plus?` true) any label up to f + 1. Under both, a note page i carries i + 1."
  [plus? ^longs L ^booleans roles]
  (let [n (alength L)]
    (loop [i 1, f (aget L 0)]
      (if (= i n)
        true
        (let [l (aget L i)]
          (if (aget roles i)
            (if (= l (inc i)) (recur (inc i) f) false)
            (if (if plus? (<= l (inc f)) (or (= l f) (= l (inc f)))) (recur (inc i) l) false)))))))

(defn enumerate-parses
  "Every valid parse of L under R (or R+), as its set of note pages."
  [plus? L]
  (let [n (count L)
        La (long-array L)]
    (into #{}
          (keep (fn [mask]
                  (let [roles (boolean-array n)]
                    (dotimes [k (dec n)] (aset roles (inc k) (bit-test mask k)))
                    (when (valid? plus? La roles)
                      (into #{} (filter #(aget roles %)) (range n))))))
          (range (bit-shift-left 1 (dec n))))))

;; ---- the THEOREM of §2.1, written from its statement (not from the code) ------------------------------------------

(defn theorem-parses
  "The valid parses as the THEOREM describes them: the product over the gaps of each gap's locally valid choices."
  [L]
  (let [n (count L)
        eligible? (fn [j] (and (>= j 1) (= (nth L j) (inc j))))
        required (filterv (complement eligible?) (range n))
        bounds (mapv vector required (concat (rest required) [nil]))
        choices-of (fn [[r r']]
                     (let [lo (inc r)
                           hi (if r' (dec r') (dec n))
                           Lr (nth L r)
                           none (when (or (nil? r') (contains? #{Lr (inc Lr)} (nth L r'))) [#{}])
                           xs (filter #(<= lo % hi) [(dec Lr) Lr])
                           blocks (for [x xs
                                        y (if r'
                                            (filter #(<= x % hi) [(- (nth L r') 2) (dec (nth L r'))])
                                            (range x (inc hi)))]
                                    (into #{} (remove #(<= x % y)) (range lo (inc hi))))]
                       (vec (concat (map (fn [_] (into #{} (range lo (inc hi)))) (or none [])) blocks))))
        per-gap (mapv choices-of bounds)]
    {:per-gap per-gap
     :parses (reduce (fn [acc cs] (set (for [a acc c cs] (into a c)))) #{#{}} per-gap)}))

;; ---- the checks ---------------------------------------------------------------------------------------------------

(defn seqs [n] (if (zero? n) [[]] (for [s (seqs (dec n)) l (range (inc max-label))] (conj s l))))

(def tally (atom {}))
(def violations (atom 0))
(defn bump! [& ks] (swap! tally update (vec ks) (fnil inc 0)))
(defn violation! [k & info]
  (swap! violations inc)
  (bump! k)
  (when (<= (get @tally [k]) 5) (apply println k info)))

(defn lead [L s] (- (nth L s) (inc s)))

(defn candidate-structure-holds?
  "§2.3, the candidate structure of an :ambiguous deck: L0 = 1, pages 0 … h read 1 … h + 1 (the plain run), and the
   candidates, :fallback? and :base are one of the five cases (a), (b1)–(b4)."
  [L il n]
  (let [h (:run-end il)
        cands (:candidates il)
        fb (:fallback? il)]
    (and (= 1 (nth L 0))
         (every? #(= (nth L %) (inc %)) (range 0 (inc h)))
         (if (= h (dec n))
           (and (= cands (vec (range 1 n))) fb (= #{} (:base il)))                    ; (a) the whole deck is the run
           (let [v (nth L (inc h))]
             (cond
               (and (= v 2) (>= h 2))        (and (= cands [1 2]) (not fb))         ; (b1)
               (and (= v 2) (= h 1))         (and (= cands [1]) fb)                 ; (b2)
               (and (<= 3 v) (<= v h))       (and (= cands [(dec v) v]) (not fb))   ; (b3)
               (and (= v (inc h)) (>= v 3))  (and (= cands [h]) fb)                 ; (b4)
               :else                         false))))))

(doseq [n (range 1 (inc max-n))
        L (seqs n)]
  (let [ps (enumerate-parses false L)
        ps+ (enumerate-parses true L)
        {:keys [per-gap parses]} (theorem-parses L)
        il (notes/interleaving L)
        st (:status il)
        L0 (first L)
        rs? (notes/restartable? L)]
    (bump! :sequences)
    (bump! :status st)
    (bump! :status-by-L0 st (min L0 2))
    ;; §2.1 THEOREM: both inclusions (equal parse sets), and the bijection (the count is the product of the counts)
    (when-not (= ps parses) (violation! :THEOREM-SET-MISMATCH L ps parses))
    (when-not (= (count ps) (reduce * 1 (map count per-gap))) (violation! :THEOREM-COUNT-MISMATCH L))
    ;; the code against the enumeration (as test/vinary/slides/notes_test.cljs does), for every status
    (let [ok (case st
               :invalid (empty? ps)
               :none (= #{#{}} ps)
               :proven (= #{(:note-pages il)} ps)
               :ambiguous (and (= ps (cond-> (set (map #(notes/candidate-notes il %) (:candidates il)))
                                       (:fallback? il) (conj (:base il))))
                               (= (count ps) (+ (count (:candidates il)) (if (:fallback? il) 1 0))))
               :undecidable (and (> (count ps) 1) (>= L0 2)))]
      (when-not ok (violation! :CODE-MISMATCH L il ps)))
    ;; §2.2 the lead lemma, for every L0: along a valid parse, lead(s') <= lead(s) - (s' - s - 1)
    (doseq [p ps
            [s s'] (partition 2 1 (remove p (range n)))]
      (when (> (lead L s') (- (lead L s) (- s' s 1))) (violation! :LEAD-LEMMA-VIOLATED L p s s')))
    ;; §2.2 the corollary: L0 <= 1 and a valid parse exists => only the first gap has more than one choice
    (when (and (<= L0 1) (seq ps) (some #(> (count %) 1) (rest per-gap))) (violation! :COROLLARY-VIOLATED L))
    ;; §2.2 the slide-label bound: L0 <= 1 => along every valid parse a slide's label is at most its index + 1
    (when (<= L0 1)
      (doseq [p ps, s (remove p (range n))]
        (when (> (nth L s) (inc s)) (violation! :SLIDE-LABEL-BOUND-VIOLATED L p s))))
    ;; §2.2 Proposition: for EVERY L0, when no gap is empty, at most one gap has more than one choice (so the code's
    ;; first :undecidable condition, (> (count multi) 1), can never hold)
    (when (and (every? seq per-gap) (> (count (filter #(> (count %) 1) per-gap)) 1))
      (violation! :TWO-BRANCHING-GAPS L))
    ;; §2.3: L0 <= 1 is never :undecidable, and :ambiguous needs L0 = 1
    (when (and (<= L0 1) (= st :undecidable)) (violation! :UNDECIDABLE-WITH-L0<=1 L))
    (when (and (= st :ambiguous) (not= L0 1)) (violation! :AMBIGUOUS-WITHOUT-L0=1 L))
    ;; §2.3 the candidate structure, and the number of parses it implies (n for case (a), 2 otherwise)
    (when (= st :ambiguous)
      (bump! :ambiguous-case (if (= (:run-end il) (dec n)) :a-the-whole-deck-is-the-run :b-a-slide-follows-the-run))
      (when-not (candidate-structure-holds? L il n) (violation! :CANDIDATE-STRUCTURE-VIOLATED L il))
      (when-not (= (count ps) (if (= (:run-end il) (dec n)) n 2)) (violation! :AMBIGUOUS-PARSE-COUNT L)))
    ;; which of the code's :undecidable conditions hold (they are evaluated in the code's order)
    (when (= st :undecidable)
      (let [eligible? (fn [j] (= (nth L j) (inc j)))
            required (into [0] (remove eligible?) (range 1 n))
            multi (filterv #(> (count %) 1) per-gap)
            g (first (keep-indexed (fn [k cs] (when (> (count cs) 1) k)) per-gap))
            r (nth required g)
            r' (when (< (inc g) (count required)) (nth required (inc g)))
            hi (if r' (dec r') (dec n))
            Lr (nth L r)
            ok? (fn [f] (or (nil? r') (let [l (nth L r')] (or (= l f) (= l (inc f))))))
            xs (filter #(<= (inc r) % hi) [(dec Lr) Lr])
            ys (fn [x] (if r' (filter #(<= x % hi) [(- (nth L r') 2) (dec (nth L r'))]) (range x (inc hi))))
            code-choices (cond-> (vec (for [x xs y (ys x) :when (ok? (inc y))] [x y])) (ok? Lr) (conj nil))
            firsts (map (fn [c] (if-let [[x y] c] (when (= x (inc r)) (if (< y hi) (inc y) ::no-note)) (inc r)))
                        code-choices)]
        (when-not (= (count code-choices) (count (nth per-gap g))) (violation! :CHOICE-COUNT-DIFFERS L))
        (bump! :undecidable-conditions-holding
               (cond-> (sorted-set)
                 (> (count multi) 1) (conj :c1-several-branching-gaps)
                 (not= 0 r) (conj :c2-the-branching-gap-is-not-after-page-0)
                 (>= L0 2) (conj :c3-L0>=2)
                 (some nil? firsts) (conj :c4-a-block-not-starting-at-r+1)
                 (not (apply distinct? firsts)) (conj :c5-two-choices-share-a-first-note-page)))))
    ;; §2.4 a drop: with L0 <= 1, every valid parse reads page i - 1 as a note page and page i as a slide
    (when (<= L0 1)
      (doseq [i (range 1 n) :when (< (nth L i) (nth L (dec i)))
              p ps]
        (when-not (and (contains? p (dec i)) (not (contains? p i))) (violation! :DROP-PROPOSITION-VIOLATED L p i))))
    ;; §2.4 THEOREM 1: restartable?(L) <=> the all-slides reading is valid under R+
    (when-not (= rs? (contains? ps+ #{})) (violation! :RESTARTABLE-THEOREM-VIOLATED L))
    ;; §2.4 THEOREM 2: a jump L_i >= L_{i-1} + 2 puts a note page at i - 1 or i in every R+ parse; with L0 <= 1, at i
    ;; and not at i - 1
    (doseq [i (range 1 n) :when (>= (nth L i) (+ 2 (nth L (dec i))))
            p ps+]
      (when-not (or (contains? p (dec i)) (contains? p i)) (violation! :JUMP-THEOREM-VIOLATED L p i))
      (when (and (<= L0 1) (not (and (contains? p i) (not (contains? p (dec i))))))
        (violation! :JUMP-THEOREM-L0-VIOLATED L p i)))
    ;; R parses are R+ parses
    (when-not (set/subset? ps ps+) (violation! :R-NOT-SUBSET-OF-R+ L))
    ;; §2.4 Proposition: with L0 <= 1, a valid parse with note pages N is restartable exactly when N is one interval
    ;; [a, b] of pages and pages 0 … a - 1 read 1 … a (the notes all annotate the last slide of the plain run)
    (when (<= L0 1)
      (doseq [p ps :when (seq p)]
        (let [a (apply min p) b (apply max p)
              shape? (and (= (count p) (inc (- b a))) (every? #(= (nth L %) (inc %)) (range a)))]
          (bump! :parse-with-notes-and-L0<=1 (if rs? :restartable :not-restartable))
          (when-not (= rs? shape?) (violation! :RESTARTABLE-SHAPE-VIOLATED L p)))))))

(doseq [[k v] (sort-by str @tally)] (println k v))
(println (if (pos? @violations) (str "VIOLATIONS " @violations) "no violation"))
(shutdown-agents)
(System/exit (if (pos? @violations) 1 0))
