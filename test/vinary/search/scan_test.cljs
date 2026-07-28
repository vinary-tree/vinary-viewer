(ns vinary.search.scan-test
  "Substring scanning, and the negative result that forecloses incremental narrowing."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.search.scan :as scan]))

(deftest scanning
  (testing "every occurrence, in buffer order"
    (is (= [[0 3]] (scan/scan-all "abc" "abc")))
    (is (= [[0 1] [2 3] [4 5]] (scan/scan-all "aXaXa" "a")))
    (is (= [[4 9]] (scan/scan-all "the quick brown" "quick"))))
  (testing "blank query or buffer yields nothing"
    (is (= [] (scan/scan-all "abc" "")))
    (is (= [] (scan/scan-all "" "abc")))
    (is (= [] (scan/scan-all nil "abc")))
    (is (= [] (scan/scan-all "abc" nil))))
  (testing "matches are NON-overlapping — the step is the query length"
    ;; "aa" in "aaaa" is two matches, not three: overlapping spans cannot be highlighted coherently
    (is (= [[0 2] [2 4]] (scan/scan-all "aaaa" "aa")))
    (is (= [[0 3]] (scan/scan-all "aaaaa" "aaa"))))
  (testing "it does NOT fold — both sides must already be folded by the caller"
    (is (= [] (scan/scan-all "ABC" "abc")))
    (is (= [[0 3]] (scan/scan-all "abc" "abc"))))
  (testing "matches at the boundaries"
    (is (= [[0 1]] (scan/scan-all "ab" "a")))
    (is (= [[1 2]] (scan/scan-all "ab" "b"))))
  (testing "every reported span really is the query"
    (doseq [[text q] [["the quick brown fox" "o"] ["mississippi" "ss"] ["aaaa" "aa"]]]
      (doseq [[s e] (scan/scan-all text q)]
        (is (= q (subs text s e)))))))

(deftest incremental-narrowing-is-unsound
  (testing "a non-overlapping match set is NOT a superset of the starts of any query extension"
    ;; The optimisation this rules out: 'the user typed one more character, so filter the previous
    ;; matches instead of rescanning'. It looks obviously correct and is not, because stepping by the
    ;; query length can skip a start that a LONGER query needs.
    (let [text "aaab"
          starts   (set (map first (scan/scan-all text "aa")))
          extended (set (map first (scan/scan-all text "aab")))]
      (is (= #{0} starts))
      (is (= #{1} extended))
      (is (not (clojure.set/subset? extended starts))
          "if this ever passes, narrowing has become sound and this test should be revisited")))
  (testing "so reuse must happen at the buffer level, which is what the finder actually does"
    ;; the buffer is the expensive artefact; scanning it is a native indexOf loop
    (is (= [[1 4]] (scan/scan-all "aaab" "aab")))))

(deftest predicates
  (testing "substring?"
    (is (true?  (scan/substring? "abcdef" "cde")))
    (is (true?  (scan/substring? "abcdef" "")))
    (is (false? (scan/substring? "abcdef" "ceg"))))
  (testing "prefix?"
    (is (true?  (scan/prefix? "abcdef" "abc")))
    (is (true?  (scan/prefix? "abcdef" "")))
    (is (false? (scan/prefix? "abcdef" "bcd")))))

(deftest subsequence-matching
  (testing "returns the matched positions, in order"
    (is (= [] (scan/subsequence "anything" "")))
    (is (= [0 2 4] (scan/subsequence "aXbXc" "abc")))
    (is (= [0 1 2] (scan/subsequence "abc" "abc")))
    ;; greedy and leftmost: the `n` matched is the one in "open", not the one in "new"
    (is (= [0 3 12] (scan/subsequence "open in new tab" "ont"))))
  (testing "nil when the query is not a subsequence"
    (is (nil? (scan/subsequence "abc" "cba")))
    (is (nil? (scan/subsequence "abc" "abcd")))
    (is (nil? (scan/subsequence "" "a"))))
  (testing "it does NOT fold — the caller folds both sides"
    (is (nil? (scan/subsequence "ABC" "abc"))))
  (testing "the positions really do spell the query"
    (let [s "the quick brown fox" q "tqbf"]
      (is (= q (apply str (map #(.charAt s %) (scan/subsequence s q))))))))
