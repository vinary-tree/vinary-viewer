(ns vinary.renderer.pdf-layout-test
  "Pure PDF geometry added for in-place refresh and the slides layout: the scale-free reading anchor
   (anchor-of / anchor-top) and the per-canvas device-pixel budget (budget-dpr). The older helpers (zoom, fit,
   page-rects, visible-range, outline) are covered by core-test/pdf-layout-helpers."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.renderer.pdf-layout :as layout]))

(defn- approx= [a b] (< (js/Math.abs (- a b)) 1e-9))

(deftest anchor-is-page-plus-fraction
  ;; three 100-px pages, 10-px gaps → tops 0, 110, 220
  (let [rects (layout/page-rects [[10 100] [10 100] [10 100]] 1.0 10)]
    (is (= {:idx 0 :frac 0} (layout/anchor-of rects 0)))
    (is (= {:idx 0 :frac 0.5} (layout/anchor-of rects 50)))
    (is (= {:idx 1 :frac 0} (layout/anchor-of rects 105)) "a viewport top in the gap anchors to the next page's top")
    (is (= {:idx 2 :frac 0.25} (layout/anchor-of rects 245)))
    (is (= 2 (:idx (layout/anchor-of rects 5000))) "past the end → the last page")
    (is (nil? (layout/anchor-of [] 0)))))

(deftest anchor-survives-a-rescale
  (testing "the same reading position maps through a zoom: 50% down page 1 stays 50% down page 1"
    (let [before (layout/page-rects [[10 100] [10 100] [10 100]] 1.0 16)
          after  (layout/page-rects [[10 100] [10 100] [10 100]] 2.0 16)
          anchor (layout/anchor-of before (+ 116 50))]
      (is (= {:idx 1 :frac 0.5} anchor))
      (is (approx= (+ 216 100) (layout/anchor-top after anchor)) "top 216 + 50% of 200")))
  (testing "a refreshed document with fewer pages clamps the anchor page"
    (let [rects (layout/page-rects [[10 100] [10 100]] 1.0 0)]
      (is (= 150 (layout/anchor-top rects {:idx 7 :frac 0.5})))))
  (is (nil? (layout/anchor-top [] {:idx 0 :frac 0}))))

(deftest canvas-pixel-budget
  (testing "within budget the screen DPR is used unchanged"
    (is (= 2 (layout/budget-dpr 1000 800 2)))
    (is (= 1 (layout/budget-dpr 3840 2160 1)) "a 4K fullscreen slide at DPR 1 is 8.3 MP — fine"))
  (testing "over budget the DPR drops so that w·h·dpr² equals the budget"
    (let [w 3840 h 2160 dpr (layout/budget-dpr w h 4)]
      (is (< dpr 4))
      (is (approx= layout/max-canvas-pixels (* w h dpr dpr)))))
  (testing "never below 0.25, and degenerate sizes keep the screen DPR"
    (is (= 0.25 (layout/budget-dpr 1e6 1e6 2)))
    (is (= 2 (layout/budget-dpr 0 100 2)))))
