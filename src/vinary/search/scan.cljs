(ns vinary.search.scan
  "Substring scanning over an already-folded buffer — the one piece of matching that every text search in
   the app performs identically.

   It existed twice, verbatim: `vinary.renderer.find-scan/scan` walked the flattened document buffer, and
   `vinary.tui.find/search` walked each rendered terminal line. Same `indexOf` loop, same
   step-by-the-query-length rule, same blank-query guard — differing only in what they were handed.

   NON-OVERLAPPING, and deliberately. Matching \"aa\" in \"aaaa\" yields [0,2) and [2,4), not three
   overlapping hits. Overlapping matches cannot be highlighted coherently (the second would start inside
   the first), and a counter over them reads as nonsense.

   A CONSEQUENCE WORTH RECORDING, because it forecloses an optimisation that looks obvious: a
   non-overlapping match set is NOT a superset of the match set of any extension of the query, so
   narrowing the previous keystroke's matches instead of rescanning is UNSOUND. Counterexample: in
   \"aaab\", scanning \"aa\" yields the single start {0}; \"aab\" matches at 1, which is not in {0}.
   Incremental reuse therefore has to happen at the BUFFER level, which is what
   `vinary.renderer.find/cached-buffer` does — and that is the cheaper place anyway, since scanning is a
   native call and building the buffer is not."
  (:require [clojure.string :as str]))

(defn scan-all
  "Every non-overlapping match of the folded query `q` in the folded buffer `text`, as [start end) index
   pairs in buffer order. Returns [] for a blank query or buffer.

   Both arguments must ALREADY be folded to the same case — this does no folding of its own, because its
   callers fold once over a whole document and folding again per query would undo that."
  [text q]
  (if (or (nil? text) (nil? q) (= "" q) (= "" text))
    []
    (let [ql   (count q)
          step (max 1 ql)]
      (loop [from 0 acc (transient [])]
        (let [i (.indexOf text q from)]
          (if (neg? i)
            (persistent! acc)
            (recur (+ i step) (conj! acc [i (+ i ql)]))))))))

(defn substring?
  "Does folded `q` occur anywhere in folded `s`?"
  [s q]
  (or (= "" q) (str/includes? s q)))

(defn prefix?
  "Does folded `s` start with folded `q`?"
  [s q]
  (or (= "" q) (str/starts-with? s q)))

(defn subsequence
  "Fuzzy match: do `q`'s characters appear in `s` in order, not necessarily adjacently? Returns the vector
   of matched indices in `s` (empty for a blank query), or nil when `q` is not a subsequence.

   Returning the POSITIONS rather than a boolean is what lets a caller both score the match (how tightly
   packed is it? does it start at a word boundary?) and highlight it, from one pass. The palette's
   predecessor returned a boolean and allocated two lower-cased strings per candidate to do it."
  [s q]
  (let [qn (count q) sn (count s)]
    (if (zero? qn)
      []
      (loop [qi 0 si 0 hits (transient [])]
        (cond
          (>= qi qn) (persistent! hits)
          (>= si sn) nil
          (= (.charAt q qi) (.charAt s si)) (recur (inc qi) (inc si) (conj! hits si))
          :else (recur qi (inc si) hits))))))
