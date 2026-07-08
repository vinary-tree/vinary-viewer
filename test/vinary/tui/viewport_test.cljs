(ns vinary.tui.viewport-test
  "The windowed line buffer: scroll/clamp, paging, jump-to-line, streaming append (follow-tail only at bottom),
   and resize — all pure value→value."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.tui.viewport :as vp]))

(defn- lines [n] (mapv #(str "line-" %) (range n)))

(deftest window-and-clamp
  (let [v (vp/set-lines (vp/viewport 80 3) (lines 10))]
    (is (= ["line-0" "line-1" "line-2"] (:slice (vp/visible v))) "top window")
    (is (= 10 (:total (vp/visible v))))
    (testing "scroll clamps to [0, total-h]"
      (is (= 5 (:top (vp/scroll v 5))))
      (is (= 7 (:top (vp/scroll v 100))) "clamped to max-top (10-3)")
      (is (= 0 (:top (vp/scroll v -100))) "clamped to 0"))
    (testing "to-bottom / to-top"
      (is (= 7 (:top (vp/to-bottom v))))
      (is (vp/at-bottom? (vp/to-bottom v)))
      (is (= 0 (:top (vp/to-top (vp/to-bottom v))))))
    (testing "page keeps one row overlap"
      (is (= 2 (:top (vp/page v 1)))))
    (testing "to-line reveals the target with a little context"
      (is (= 3 (:top (vp/to-line v 5)))))))

(deftest streaming-append
  (let [v (vp/set-lines (vp/viewport 80 3) (lines 5))]     ; 5 lines, h 3 → max-top 2
    (testing "at bottom → follows the growing tail"
      (let [v (vp/to-bottom v)                             ; top 2
            v (vp/append v ["line-5" "line-6"])]           ; now 7 lines, max-top 4
        (is (vp/at-bottom? v))
        (is (= 4 (:top v)) "followed the tail")
        (is (= ["line-4" "line-5" "line-6"] (:slice (vp/visible v))))))
    (testing "scrolled up → append does NOT yank the view"
      (let [v (vp/to-top v)                                ; top 0
            v (vp/append v ["line-5" "line-6"])]
        (is (= 0 (:top v)) "stayed put")
        (is (= ["line-0" "line-1" "line-2"] (:slice (vp/visible v))))))))

(deftest streaming-ring-cap
  (testing "with a :cap the buffer is a bounded ring — oldest lines drop, counted in :dropped (memory stays flat)"
    (let [v (-> (vp/viewport 80 3 5)                       ; cap 5 lines
                (vp/append (lines 4)))]                     ; 4 lines, under cap
      (is (= 4 (:total (vp/visible v))))
      (is (= 0 (:dropped (vp/visible v))))
      (let [v (vp/append v ["line-4" "line-5" "line-6"])]  ; 7 total > cap 5 → drop 2 oldest
        (is (= 5 (:total (vp/visible v))) "retained capped at 5")
        (is (= 2 (:dropped (vp/visible v))) "2 oldest dropped")
        (is (vp/at-bottom? v) "following the tail")
        (is (= ["line-4" "line-5" "line-6"] (:slice (vp/visible v))))))
    (testing "a scrolled-up reader (mid-buffer) keeps the SAME content in view when old lines drop"
      (let [v (-> (vp/viewport 80 3 10) (vp/append (lines 10))   ; line-0..9, at bottom (top 7)
                  (vp/scroll -3))]                               ; top 4 → showing line-4,5,6
        (is (= ["line-4" "line-5" "line-6"] (:slice (vp/visible v))))
        (let [v (vp/append v ["line-10" "line-11"])]             ; drop line-0,1 → top adjusts 4→2
          (is (= 2 (:dropped (vp/visible v))))
          (is (= ["line-4" "line-5" "line-6"] (:slice (vp/visible v))) "same content, not yanked by the drop"))))))

(deftest resize-reclamps
  (let [v (-> (vp/viewport 80 5) (vp/set-lines (lines 20)) (vp/to-bottom))]   ; top 15
    (let [v' (vp/resize v 80 10 (lines 20))]                                  ; taller → max-top 10
      (is (= 10 (:h v')))
      (is (= 10 (:top v')) "top re-clamped to the new max-top"))))
