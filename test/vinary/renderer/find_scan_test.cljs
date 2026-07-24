(ns vinary.renderer.find-scan-test
  "Properties of in-page find's match scanning, without a DOM.

   Every test here corresponds to a defect the previous single-text-node matcher had. The headline one is
   `matches-cross-node-boundaries`: rendered markup splits text at every inline element, and pdf.js emits
   one <span> per text run, so multi-word queries used to find nothing at all in a PDF and to miss any
   phrase straddling `<code>`/`<a>`/`<em>` in Markdown. The complement is
   `matches-never-cross-a-block-boundary` — flattening must not let a query match across a paragraph, a
   table cell, or a diff column."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [vinary.renderer.find-scan :as fs]))

(defn- text-tok [id s] {:kind :text :id id :s s})
(def ^:private hard {:kind :hard})
(def ^:private soft {:kind :soft})

(defn- buf [tokens] (fs/build tokens))
(defn- hits [tokens q]
  (let [{:keys [text segs]} (buf tokens)]
    (mapv #(fs/match-endpoints segs %) (fs/scan text (fs/normalize-query q)))))

(deftest safe-lower-preserves-length
  (testing "ordinary text lower-cases as usual"
    (is (= "hello world" (fs/safe-lower "Hello World"))))
  (testing "THE INDEX INVARIANT: lower-casing never changes the string's length"
    ;; "İ" (U+0130) lowercases to TWO UTF-16 units in JavaScript. A naive toLowerCase on the whole buffer
    ;; would shift every index after it, mis-highlighting the rest of the document.
    (doseq [s ["İstanbul" "ǅ" "ẞ" "Ⅷ" "A İ B" "ΑΣ"]]
      (is (= (count s) (count (fs/safe-lower s)))
          (str "length changed for " (pr-str s)))))
  (testing "the multi-unit character is left alone rather than corrupted"
    (is (= "İ" (fs/safe-lower "İ")))))

(deftest query-normalization
  (testing "blank-ish queries normalize to the empty string (which callers treat as 'clear')"
    (is (= "" (fs/normalize-query nil)))
    (is (= "" (fs/normalize-query "")))
    (is (= "" (fs/normalize-query "   ")))
    (is (= "" (fs/normalize-query "\n\t"))))
  (testing "case folds, whitespace runs collapse, and the ends are trimmed"
    (is (= "quick brown fox" (fs/normalize-query "  Quick   Brown \n Fox  ")))
    (is (= "a b" (fs/normalize-query "A\t\tB"))))
  (testing "a normalized query can never contain a newline — that is what stops a match crossing a block"
    (is (not (re-find #"\n" (fs/normalize-query "one\n\ntwo"))))))

(deftest buffer-construction
  (testing "a single text node round-trips, and every index maps back to its own offset"
    (let [{:keys [text segs]} (buf [(text-tok 0 "hello")])]
      (is (= "hello" text))
      (is (= 4 (.-length segs)) "one contiguous run needs exactly one segment quad")
      (doseq [i (range 5)]
        (is (= {:id 0 :off i} (fs/locate segs i))))
      (is (nil? (fs/locate segs 5)) "one past the end belongs to no node")))
  (testing "adjacent text nodes concatenate, each contributing one segment"
    (let [{:keys [text segs]} (buf [(text-tok 0 "a ") (text-tok 1 "bold") (text-tok 2 " word")])]
      (is (= "a bold word" text))
      (is (= {:id 1 :off 0} (fs/locate segs 2)) "the 'b' of 'bold' comes from node 1")
      (is (= {:id 2 :off 1} (fs/locate segs 7)) "the 'w' of 'word' comes from node 2")))
  (testing "a whitespace RUN collapses to one space"
    (let [{:keys [text segs]} (buf [(text-tok 0 "a    b")])]
      (is (= "a b" text))
      (is (= {:id 0 :off 5} (fs/locate segs 2)) "'b' still points at its real offset in the node")))
  (testing "a wrapped markdown line becomes searchable, and stays CONTIGUOUS"
    ;; this is the cost guarantee: a lone newline inside a node is emitted from the node's own offset, so
    ;; no extra segment is needed and |segs| stays proportional to nodes, not to words
    (let [{:keys [text segs]} (buf [(text-tok 0 "the quick\nbrown fox")])]
      (is (= "the quick brown fox" text))
      (is (= 4 (.-length segs)) "one segment for one contiguous node")
      (is (= {:id 0 :off 10} (fs/locate segs 10)) "the newline normalizes in place, indices stay aligned")))
  (testing "leading and trailing separators are suppressed"
    (is (= "a" (:text (buf [hard (text-tok 0 "a") hard]))))
    (is (= "a" (:text (buf [soft soft (text-tok 0 "a") soft])))))
  (testing "consecutive separators collapse, and :hard dominates :soft in either order"
    (is (= "a\nb" (:text (buf [(text-tok 0 "a") soft hard (text-tok 1 "b")]))))
    (is (= "a\nb" (:text (buf [(text-tok 0 "a") hard soft (text-tok 1 "b")]))))
    (is (= "a b" (:text (buf [(text-tok 0 "a") soft soft (text-tok 1 "b")])))))
  (testing "empty and whitespace-only token streams produce an empty buffer"
    (is (= "" (:text (buf []))))
    (is (= "" (:text (buf [hard soft hard]))))
    (is (= "" (:text (buf [(text-tok 0 "") (text-tok 1 "   ")]))))))

(deftest matches-cross-node-boundaries
  (testing "THE HEADLINE FIX: a phrase straddling an inline element matches"
    ;; "a <strong>bold</strong> word" — three text nodes; the old matcher searched each in isolation
    (let [ms (hits [(text-tok 0 "a ") (text-tok 1 "bold") (text-tok 2 " word")] "a bold word")]
      (is (= 1 (count ms)))
      (is (= {:id 0 :off 0} (:start (first ms))))
      (is (= {:id 2 :off 5} (:end (first ms))) "the end lands past the last character of node 2")))
  (testing "a phrase spanning a soft boundary matches — a pdf.js <br>, or a wrapped line"
    (let [ms (hits [(text-tok 0 "quick brown") soft (text-tok 1 "fox jumps")] "brown fox")]
      (is (= 1 (count ms)))
      (is (= {:id 0 :off 6} (:start (first ms))))
      (is (= {:id 1 :off 3} (:end (first ms))))))
  (testing "two adjacent pdf.js text runs on one line fuse into one searchable phrase"
    (let [ms (hits [(text-tok 0 "quick ") (text-tok 1 "brown fox")] "quick brown")]
      (is (= 1 (count ms))))))

(deftest matches-never-cross-a-block-boundary
  (testing "a hard boundary blocks a match, even though the same words are adjacent in the buffer"
    (is (empty? (hits [(text-tok 0 "foo") hard (text-tok 1 "bar")] "foo bar")))
    (is (= "foo\nbar" (:text (buf [(text-tok 0 "foo") hard (text-tok 1 "bar")])))))
  (testing "each side of the boundary is still matchable on its own"
    (is (= 1 (count (hits [(text-tok 0 "foo") hard (text-tok 1 "bar")] "foo"))))
    (is (= 1 (count (hits [(text-tok 0 "foo") hard (text-tok 1 "bar")] "bar"))))))

(deftest scanning
  (let [{:keys [text]} (buf [(text-tok 0 "Reactive reactive REACTIVE")])]
    (testing "matching is case-insensitive in both directions"
      (is (= 3 (count (fs/scan text (fs/normalize-query "reactive")))))
      (is (= 3 (count (fs/scan text (fs/normalize-query "REACTIVE")))))
      (is (= 3 (count (fs/scan text (fs/normalize-query "ReAcTiVe")))))))
  (testing "matches are NON-overlapping"
    ;; "aa" in "aaaa" is two matches (offsets 0 and 2), not three — same rule as the terminal finder
    (is (= [[0 2] [2 4]] (fs/scan "aaaa" "aa"))))
  (testing "a blank query matches nothing"
    (is (= [] (fs/scan "anything" "")))
    (is (= [] (fs/scan "anything" nil))))
  (testing "a query longer than the text matches nothing"
    (is (= [] (fs/scan "ab" "abcdef"))))
  (testing "matches at the very start and the very end are both found"
    (is (= [[0 3]] (fs/scan "abcxyz" "abc")))
    (is (= [[3 6]] (fs/scan "xyzabc" "abc"))))
  (testing "every match has exactly the query's length"
    (let [q "brown"]
      (doseq [[s e] (fs/scan "brown brown brown" q)]
        (is (= (count q) (- e s)))))))

(deftest locating
  (testing "an empty segment table locates nothing"
    (is (nil? (fs/locate (array) 0))))
  (testing "an index before the first segment locates nothing"
    (let [{:keys [segs]} (buf [hard (text-tok 0 "abc")])]
      ;; the leading hard separator is suppressed, so index 0 IS node 0 here
      (is (= {:id 0 :off 0} (fs/locate segs 0)))))
  (testing "a synthetic separator locates nothing — it belongs to no node"
    (let [{:keys [text segs]} (buf [(text-tok 0 "foo") hard (text-tok 1 "bar")])]
      (is (= "foo\nbar" text))
      (is (nil? (fs/locate segs 3)) "index 3 is the synthetic newline")
      (is (= {:id 1 :off 0} (fs/locate segs 4)))))
  (testing "THE ENDPOINT INVARIANT: both ends of every match resolve to a real node"
    ;; guaranteed by normalize-query trimming: a match can neither begin nor end on a separator
    (let [tokens [(text-tok 0 "alpha beta") hard (text-tok 1 "beta gamma") soft (text-tok 2 "gamma delta")]
          {:keys [text segs]} (buf tokens)]
      (doseq [q ["beta" "gamma" "gamma delta" "alpha"]]
        (doseq [m (fs/scan text (fs/normalize-query q))]
          (is (some? (fs/match-endpoints segs m))
              (str "unresolvable endpoint for " (pr-str q) " at " (pr-str m))))))))

(deftest cursor-arithmetic
  (testing "no matches means no cursor"
    (is (nil? (fs/step-idx 0 0 1)))
    (is (nil? (fs/step-idx 0 0 -1)))
    (is (nil? (fs/clamp-idx 0 3))))
  (testing "a single match stays put in both directions"
    (is (= 0 (fs/step-idx 1 0 1)))
    (is (= 0 (fs/step-idx 1 0 -1))))
  (testing "the cursor wraps at BOTH ends"
    (is (= 0 (fs/step-idx 5 4 1)) "past the last match wraps to the first")
    (is (= 4 (fs/step-idx 5 0 -1)) "before the first wraps to the last"))
  (testing "a full cycle visits every match exactly once and returns to the start"
    (doseq [n [1 2 5 9] dir [1 -1]]
      (let [visited (loop [i 0 k 0 acc #{}]
                      (if (= k n) acc (recur (fs/step-idx n i dir) (inc k) (conj acc i))))]
        (is (= n (count visited)) (str "n=" n " dir=" dir " visited " visited))
        (is (= (set (range n)) visited)))))
  (testing "clamp keeps a stale cursor in range after a re-collect changed the count"
    (is (= 2 (fs/clamp-idx 3 7)) "shrank: pin to the last match")
    (is (= 2 (fs/clamp-idx 9 2)) "grew: keep the cursor")
    (is (= 0 (fs/clamp-idx 5 -1)) "never negative")))
