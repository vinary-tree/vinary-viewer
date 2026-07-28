(ns vinary.search.match
  "The configurable matcher: one model, five modes, one result shape.

   THE PROBLEM IT SOLVES. Five search surfaces, five hand-rolled matchers, five different answers to the
   same questions:

     in-page find    substring over a flattened buffer, length-preserving fold
     file-tree       `str/includes?` on a lower-cased path, inline in the render function
     command palette a private `fuzzy?` subsequence loop allocating two folded strings per candidate
     URI bar         `matches-prefix?`, folding both sides per entry
     terminal find   substring per rendered line

   None of those choices was wrong for its widget, but each was welded to it. Changing the file tree to
   fuzzy matching, or letting the user choose, meant writing a sixth matcher. So the mode is a PARAMETER
   here and the defaults in `vinary.search.config` reproduce exactly what each widget did before — this
   lands as de-duplication with no behaviour change, and a different choice is then one keyword.

   ONE RESULT SHAPE. Every mode returns nil (no match) or `{:score n :spans [[start end) …]}`. The spans
   are what a caller highlights; the score is what it sorts by. The palette's predecessor returned a
   boolean, which is why it could offer neither.

   FOLDING IS THE CALLER'S TO CHOOSE but this namespace's to apply: `:fold :strict` preserves length, and
   is REQUIRED whenever a returned span index maps back to a source position (see vinary.search.query).
   Spans are indices into the folded candidate, and `:strict` is what guarantees those are also indices
   into the original."
  (:require [clojure.string :as str]
            [vinary.search.query :as q]
            [vinary.search.scan :as scan]))

(def modes
  "Every matching mode, with what it means. Exposed as data so a settings UI can enumerate them without
   this namespace having to know a settings UI exists."
  [{:id :substring   :label "Contains"        :doc "the query appears verbatim"}
   {:id :subsequence :label "Fuzzy"           :doc "the query's characters appear in order, with gaps"}
   {:id :prefix      :label "Starts with"     :doc "the candidate begins with the query"}
   {:id :word-prefix :label "Word starts with" :doc "some word in the candidate begins with the query"}
   {:id :regex       :label "Regular expression" :doc "the query is a regex; an invalid one matches nothing"}])

(def mode-ids (into #{} (map :id) modes))

;; What separates one "word" from the next, for the word-prefix mode and for boundary scoring. Path
;; separators are included because the candidates most often ranked here are file paths.
(def ^:private separators #{" " "\t" "\n" "/" "\\" "_" "-" "." ":" "," "(" ")" "[" "]" "{" "}" "'" "\""})

(defn- boundary?
  "Is index `i` the start of a word in `s`? True at index 0, after a separator, and at a lower→upper
   transition — so `findScan` counts `S` as a word start even with no separator in sight. `s` here is the
   ORIGINAL candidate, not the folded one, because case is exactly the signal being read."
  [s i]
  (or (zero? i)
      (contains? separators (.charAt s (dec i)))
      (let [prev (.charAt s (dec i))
            cur  (.charAt s i)]
        (and (= prev (str/lower-case prev)) (not= prev (str/upper-case prev))
             (= cur (str/upper-case cur)) (not= cur (str/lower-case cur))))))

(defn- hits->spans
  "Merge a sorted vector of matched indices into [start end) spans, so adjacent characters highlight as
   one run rather than as a row of single-character marks."
  [hits]
  (if (empty? hits)
    []
    (loop [i 1, out (transient []), s (nth hits 0), e (inc (nth hits 0))]
      (if (>= i (count hits))
        (persistent! (conj! out [s e]))
        (let [h (nth hits i)]
          (if (= h e)
            ;; contiguous: extend the open span
            (recur (inc i) out s (inc e))
            ;; a gap: CLOSE the open span before starting the next one. Dropping it here is how the first
            ;; version of this lost every span but the last — caught by the test asserting that `abc`
            ;; against `aXbXc` highlights three separate characters.
            (recur (inc i) (conj! out [s e]) h (inc h))))))))

(defn- score-of
  "How good is this match, on 0–1000?

   Four components, weighted. Each is normalised to 0–1 so the weights are readable as percentages:

     contiguity 0.45  (q - runs + 1) / q      — one unbroken run scores 1; every extra gap costs
     boundary   0.25  hits at word starts / q — `vwc` matching v·iews/w·eb/c·ljs beats a mid-word scatter
     position   0.15  1 - first/len           — an early match is likelier to be what was meant
     density    0.15  q / len                 — the query covering more of a short name beats a long one

   Contiguity dominates because it is the difference between `views` matching `views.cljs` and matching
   `v…i…e…w…s` scattered across an unrelated path, which is the single most common complaint about fuzzy
   finders. The weights are a judgement, not a derivation, and they are here in one place precisely so
   they can be re-tuned against evidence rather than re-derived per widget."
  [orig hits qn]
  (if (or (zero? qn) (empty? hits))
    (if (zero? qn) 1000 0)
    (let [len   (max 1 (count orig))
          runs  (loop [i 1 r 1]
                  (if (>= i (count hits))
                    r
                    (recur (inc i) (if (= (nth hits i) (inc (nth hits (dec i)))) r (inc r)))))
          contiguity (/ (- (+ qn 1) runs) qn)
          bounds     (/ (count (filter #(boundary? orig %) hits)) qn)
          position   (- 1 (/ (nth hits 0) len))
          density    (min 1 (/ qn len))]
      (js/Math.round (* 1000 (+ (* 0.45 contiguity)
                                (* 0.25 bounds)
                                (* 0.15 position)
                                (* 0.15 density)))))))

(defn- compile-regex
  "The query as a case-insensitive RegExp, or nil when it is not valid. An invalid pattern matches nothing
   rather than throwing: the user is mid-typing, and `(` is a legitimate prefix of a legitimate pattern."
  [q]
  (try (js/RegExp. q "gi") (catch :default _ nil)))

(defn matcher
  "Build a matching function for `q` under `opts`, returning (fn [candidate] → nil | {:score :spans}).

   Options:
     :mode    :substring (default) | :subsequence | :prefix | :word-prefix | :regex
     :fold    :strict (default, length-preserving) | :simple
     :ranked? when true, :score reflects match quality; otherwise every match scores 1000

   Built once per query rather than per candidate — folding the query and compiling the regex are
   per-query costs that the previous per-widget matchers paid per candidate."
  [q {:keys [mode fold ranked?] :or {mode :substring fold :strict}}]
  (let [qf (q/fold (or q "") fold)
        qn (count qf)
        blank? (zero? qn)
        re (when (and (= mode :regex) (not blank?)) (compile-regex q))]
    (fn [candidate]
      (let [orig (str candidate)
            s    (q/fold orig fold)]
        (cond
          blank? {:score 1000 :spans []}

          (= mode :regex)
          (when re
            (set! (.-lastIndex re) 0)
            (let [spans (loop [acc (transient [])]
                          (if-let [m (.exec re s)]
                            (let [i (.-index m)
                                  n (.-length (aget m 0))]
                              ;; a zero-width match would loop forever; advance past it
                              (when (zero? n) (set! (.-lastIndex re) (inc (.-lastIndex re))))
                              (recur (if (pos? n) (conj! acc [i (+ i n)]) acc)))
                            (persistent! acc)))]
              (when (seq spans)
                {:score (if ranked?
                          (score-of orig (vec (range (ffirst spans) (second (first spans)))) qn)
                          1000)
                 :spans spans})))

          (= mode :prefix)
          (when (scan/prefix? s qf)
            {:score 1000 :spans [[0 qn]]})

          (= mode :word-prefix)
          (let [starts (into [] (filter #(boundary? orig %)) (range (count orig)))
                hit (some (fn [i] (when (str/starts-with? (subs s i) qf) i)) starts)]
            (when hit
              {:score (if ranked? (score-of orig (vec (range hit (+ hit qn))) qn) 1000)
               :spans [[hit (+ hit qn)]]}))

          (= mode :subsequence)
          (when-let [hits (scan/subsequence s qf)]
            {:score (if ranked? (score-of orig hits qn) 1000)
             :spans (hits->spans hits)})

          ;; :substring, and the fallback for an unrecognised mode — the conservative choice, since it is
          ;; what four of the five call sites did before this namespace existed
          :else
          (let [spans (scan/scan-all s qf)]
            (when (seq spans)
              {:score (if ranked? (score-of orig (vec (range (ffirst spans) (second (first spans)))) qn) 1000)
               :spans spans})))))))

(defn match
  "One-shot convenience: match a single candidate. Prefer `matcher` in a loop."
  ([s q] (match s q {}))
  ([s q opts] ((matcher q opts) s)))

(defn rank
  "Filter `candidates` to those matching `q`, and order them.

   `key-fn` extracts the string to match from each candidate (identity by default), so this works over
   plain strings and over the maps the palette and file tree carry. When `:ranked?` is set the order is by
   descending score with the original order breaking ties; otherwise the original order is preserved
   outright, which is what a file tree wants (paths are already sorted meaningfully).

   `:limit` caps the RESULT, and the cap is applied after ranking so it keeps the best matches rather than
   the first ones encountered."
  [candidates q {:keys [key-fn ranked? limit] :or {key-fn identity} :as opts}]
  (let [m (matcher q opts)
        hits (into []
                   (comp (map-indexed (fn [i c] (when-let [r (m (key-fn c))]
                                                  {:item c :score (:score r) :spans (:spans r) :ord i})))
                         (filter some?))
                   candidates)
        ordered (if ranked?
                  (vec (sort-by (juxt (comp - :score) :ord) hits))
                  hits)]
    (if limit (vec (take limit ordered)) ordered)))
