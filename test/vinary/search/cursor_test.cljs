(ns vinary.search.cursor-test
  "The wrapping match cursor, shared by the GUI and terminal finders."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.search.cursor :as cursor]))

(deftest stepping
  (testing "forward and backward, wrapping at both ends"
    (is (= 1 (cursor/step 3 0 1)))
    (is (= 0 (cursor/step 3 2 1)))       ; wraps forward
    (is (= 2 (cursor/step 3 0 -1)))      ; wraps backward
    (is (= 1 (cursor/step 3 2 -1))))
  (testing "next/prev are the named forms"
    (is (= (cursor/step 5 2 1)  (cursor/next 5 2)))
    (is (= (cursor/step 5 2 -1) (cursor/prev 5 2))))
  (testing "nil idx is treated as 0"
    (is (= 1 (cursor/step 3 nil 1)))
    (is (= 2 (cursor/step 3 nil -1))))
  (testing "nil when there is nothing to point at — every caller must decide what that means"
    (is (nil? (cursor/step 0 0 1)))
    (is (nil? (cursor/next 0 nil)))
    (is (nil? (cursor/prev 0 nil))))
  (testing "a full forward cycle visits every match exactly once and returns to the start"
    (let [n 4
          seen (loop [i 0 acc [] k 0]
                 (if (>= k n) acc (recur (cursor/step n i 1) (conj acc i) (inc k))))]
      (is (= (set (range n)) (set seen)))
      (is (= n (count seen))))))

(deftest clamping
  (testing "keeps a cursor in range after the match count changed"
    (is (= 2 (cursor/clamp 3 5)))        ; the list shrank
    (is (= 1 (cursor/clamp 9 1)))        ; the list grew: position kept
    (is (= 0 (cursor/clamp 3 -4)))       ; a negative cursor lands at the start
    (is (= 0 (cursor/clamp 3 nil))))
  (testing "nil when nothing matches"
    (is (nil? (cursor/clamp 0 0)))
    (is (nil? (cursor/clamp 0 7)))))

(deftest display-position
  (testing "the counter is 1-based, and 0 means nothing to show"
    (is (= 1 (cursor/display 12 0)))
    (is (= 3 (cursor/display 12 2)))
    (is (= 0 (cursor/display 0 0)))
    (is (= 1 (cursor/display 5 nil)))))
