(ns vinary.search.query-test
  "The two folding strategies, and the invariant that makes the distinction between them necessary."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.search.query :as q]))

(deftest ascii-detection
  (testing "the fast-path predicate"
    (is (true?  (q/ascii? "")))
    (is (true?  (q/ascii? "The quick brown fox 0123 !@#$%^&*()")))
    (is (true?  (q/ascii? "\n\t\r")))
    (is (false? (q/ascii? "café")))
    (is (false? (q/ascii? "İ")))
    (is (false? (q/ascii? "—")))                       ; U+2014, an em dash: prose is full of these
    (is (false? (q/ascii? " ")))))                ; a non-breaking space is NOT ASCII

(deftest strict-fold-preserves-length
  (testing "every input folds to a string of exactly the same length"
    ;; This is the invariant the whole in-page find buffer rests on: a buffer index must remain an index
    ;; into the source text node. "İ" (U+0130) is the canonical counterexample to the naive fold — it
    ;; lower-cases to TWO UTF-16 units.
    (doseq [s ["İstanbul" "ǅ" "ẞ" "Ⅷ" "A İ B" "ΑΣ" "" "plain ascii" "café" "ǄǅǆǇǈǉ"]]
      (is (= (count s) (count (q/fold-strict s)))
          (str "length changed folding " (pr-str s))))))

(deftest strict-fold-lowercases
  (testing "it still actually folds case"
    (is (= "abc" (q/fold-strict "ABC")))
    (is (= "abc def" (q/fold-strict "AbC DeF")))
    (is (= "café" (q/fold-strict "CAFÉ")))
    (is (= "ασ" (q/fold-strict "ΑΣ"))))
  (testing "characters whose fold would change length are left alone, by construction"
    ;; "İ" cannot fold to one unit, so it is kept verbatim rather than corrupting every later index
    (is (= "İ" (q/fold-strict "İ")))))

(deftest ascii-fast-path-agrees-with-the-slow-one
  (testing "the fast path is an optimisation, not a second behaviour"
    ;; Every ASCII string must fold identically whichever branch runs, or the optimisation is a bug. The
    ;; slow path is re-derived here rather than called, so this compares two independent implementations.
    (let [slow (fn [s] (apply str (map (fn [c] (let [l (.toLowerCase c)]
                                                 (if (= 1 (.-length l)) l c)))
                                       s)))]
      (doseq [s ["" "A" "z" "Hello, World!" "MiXeD 123 ~`" "\n\tX"]]
        (is (= (slow s) (q/fold-strict s)) (str "fast/slow disagree on " (pr-str s)))))))

(deftest simple-fold
  (testing "the cheap strategy folds but promises nothing about length"
    (is (= "abc" (q/fold-simple "ABC")))
    (is (= "" (q/fold-simple nil)))
    ;; the very case :strict exists to avoid — recorded so the difference is visible in the test output
    (is (not= (count "İ") (count (q/fold-simple "İ"))))))

(deftest fold-dispatch
  (testing "the strategy argument selects, and :strict is the default"
    (is (= (q/fold-strict "İ") (q/fold "İ")))
    (is (= (q/fold-strict "İ") (q/fold "İ" :strict)))
    (is (= (q/fold-simple "İ") (q/fold "İ" :simple)))))

(deftest normalization
  (testing "fold, collapse whitespace, trim"
    (is (= "" (q/normalize nil)))
    (is (= "" (q/normalize "")))
    (is (= "" (q/normalize "   \n\t ")))
    (is (= "abc" (q/normalize "  ABC  ")))
    (is (= "quick brown" (q/normalize "Quick\n   Brown")))
    (is (= "a b c" (q/normalize "a \t b \n c"))))
  (testing "a normalized query can never contain a newline"
    ;; …which is what stops a match from spanning a block boundary, since block boundaries are the only
    ;; newlines in the find buffer
    (doseq [s ["a\nb" "\n\na\n\nb\n\n" "line1\r\nline2"]]
      (is (not (re-find #"\n" (q/normalize s))))))
  (testing "normalization preserves length under :strict for the folded core"
    (is (= (count "istanbul") (count (q/normalize "İSTANBUL"))))))
