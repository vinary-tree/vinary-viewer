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

(deftest slides-geometry
  (testing "per-page fit inside a slot, with a margin, clamped"
    (is (approx= 2.0 (layout/slide-scale [400 225] 800 600 0)) "width-bound")
    (is (approx= 1.0 (layout/slide-scale [400 600] 800 600 0)) "height-bound portrait")
    (is (approx= (/ 568 400) (layout/slide-scale [400 225] 600 600 16)) "the margin is taken from both sides")
    (is (= 16.0 (layout/slide-scale [10 10] 10000 10000 0)) "clamped at slides-max-zoom")
    (is (= 1.0 (layout/slide-scale [0 0] 800 600 0)) "zero guard"))
  (testing "a 16:9 Beamer page fills a 3840-px fullscreen (needs > the document max zoom of 8)"
    (is (> (layout/slide-scale [453.543 255.118] 3840 2160 0) 8)))
  (testing "slide-scales is per page"
    (is (= 2 (count (layout/slide-scales [[400 225] [300 600]] 800 600 0))))
    (is (not= (first (layout/slide-scales [[400 225] [300 600]] 800 600 0))
              (second (layout/slide-scales [[400 225] [300 600]] 800 600 0)))))
  (testing "slots stack one viewport apart with no gap"
    (is (= [{:top 0 :height 600 :width 800} {:top 600 :height 600 :width 800} {:top 1200 :height 600 :width 800}]
           (layout/slot-rects 3 800 600))))
  (testing "slot-at rounds to the nearest slot top and clamps"
    (is (= 0 (layout/slot-at 0 600 3)))
    (is (= 1 (layout/slot-at 600 600 3)))
    (is (= 1 (layout/slot-at 899 600 3)) "within half a slot → that slot")
    (is (= 2 (layout/slot-at 901 600 3)))
    (is (= 2 (layout/slot-at 99999 600 3)))
    (is (= 0 (layout/slot-at -50 600 3)))
    (is (= 0 (layout/slot-at 100 0 3)) "degenerate slot height")))

(deftest text-run-positions
  (testing "a root run: a percent of the page"
    (is (= 52.31 (layout/run-percent "52.31%" 725.669)))
    (is (= -0.5 (layout/run-percent "-0.50%" 100))))
  (testing "a marked-content run: page units, as pdf.js writes them (no space after the *) and as a
            re-serialization might space them"
    (is (< (js/Math.abs (- (layout/run-percent "calc(var(--total-scale-factor) *391.19px)" 725.669) 53.9071)) 1e-3))
    (is (< (js/Math.abs (- (layout/run-percent "calc(var(--total-scale-factor) * 391.19px)" 725.669) 53.9071)) 1e-3))
    (is (= 50 (layout/run-percent " calc( var(--total-scale-factor)*100px ) " 200))))
  (testing "anything else is unknown (the run then stays visible)"
    (is (nil? (layout/run-percent "391.19px" 725.669)))
    (is (nil? (layout/run-percent "" 725.669)))
    (is (nil? (layout/run-percent nil 725.669)))
    (is (nil? (layout/run-percent "calc(var(--total-scale-factor) *10px)" 0)) "no extent → no percent")))
