(ns vinary.renderer.source-nav-test
  "DOM-free unit tests for the pure reverse-lookup behind the source→preview jump. `nearest-line-index` maps a
   source line to the index of the nearest anchored preview element (the greatest start-line ≤ the target) — a
   binary search over the NON-DECREASING start-lines that querySelectorAll returns in document order. It is the
   reverse of toc/active-heading; the DOM glue (scroll-preview-to-line! / current-preview-line) is exercised by
   the electron smoke."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.source-nav :as sn]))

(deftest nearest-line-index-empty
  (testing "no anchored elements → nil"
    (is (nil? (sn/nearest-line-index [] 5)))))

(deftest nearest-line-index-basic
  (testing "the greatest start-line ≤ target"
    (let [lines [1 3 5 9 12]]
      (is (= 0 (sn/nearest-line-index lines 1)))
      (is (= 1 (sn/nearest-line-index lines 3)))
      (is (= 1 (sn/nearest-line-index lines 4)))   ; between 3 and 5 → the 3
      (is (= 2 (sn/nearest-line-index lines 5)))
      (is (= 2 (sn/nearest-line-index lines 8)))   ; between 5 and 9 → the 5
      (is (= 3 (sn/nearest-line-index lines 9)))
      (is (= 4 (sn/nearest-line-index lines 12))))))

(deftest nearest-line-index-clamp-before-all
  (testing "a target before the first line clamps to index 0 (scroll to top of the preview)"
    (is (= 0 (sn/nearest-line-index [4 7 9] 1)))
    (is (= 0 (sn/nearest-line-index [4 7 9] 0)))))

(deftest nearest-line-index-past-end
  (testing "a target past the last line returns the last index"
    (is (= 2 (sn/nearest-line-index [1 5 9] 100)))
    (is (= 0 (sn/nearest-line-index [7] 100)))))

(deftest nearest-line-index-duplicate-lines
  (testing "ties (a parent + its first child sharing a start-line) resolve to the LAST (deepest) index"
    (is (= 3 (sn/nearest-line-index [1 3 5 5 9] 5)))
    (is (= 4 (sn/nearest-line-index [2 2 2 2 2] 2)))
    (is (= 4 (sn/nearest-line-index [2 2 2 2 2] 7)))))

(deftest nearest-line-index-single
  (testing "a single element clamps on both sides"
    (is (= 0 (sn/nearest-line-index [5] 3)))
    (is (= 0 (sn/nearest-line-index [5] 5)))
    (is (= 0 (sn/nearest-line-index [5] 8)))))
