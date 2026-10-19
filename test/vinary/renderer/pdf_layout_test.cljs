(ns vinary.renderer.pdf-layout-test
  "Pure PDF geometry added for in-place refresh and the slides layout: the scale-free reading anchor
   (anchor-of / anchor-top) and the per-canvas device-pixel budget (budget-dpr); and, for the presenter view's region
   boxes (ADR-0048 §6.3), a region's scale and place in its box and which text runs belong to it. The older helpers
   (zoom, fit, page-rects, visible-range, outline) are covered by core-test/pdf-layout-helpers."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.renderer.pdf-layout :as layout]
            [vinary.renderer.pdf-region :as region]
            [vinary.slides.deck :as deck]))

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

;; ---- one region of a page in a box (ADR-0048 §6.3) ---------------------------------------------------------------

(deftest a-region-fits-its-box
  (testing "the largest scale that fits both ways"
    (is (approx= 2 (layout/region-scale {:w 100 :h 50} 200 400)) "limited by the width")
    (is (approx= 1.5 (layout/region-scale {:w 100 :h 50} 400 75)) "limited by the height"))
  (testing "a box whose height follows its width: the width alone"
    (is (approx= 3 (layout/region-scale {:w 100 :h 50} 300 nil)))
    (is (approx= 3 (layout/region-scale {:w 100 :h 50} 300 0)) "a box not laid out yet has no height"))
  (testing "no extent, no scale"
    (is (nil? (layout/region-scale {:w 0 :h 50} 300 300)))
    (is (nil? (layout/region-scale {:w 100 :h 50} 0 300)))
    (is (nil? (layout/region-scale {} 300 300))))
  (testing "region/fit centres the scaled region in the box"
    (is (= {:scale 2 :w 200 :h 100 :left 0 :top 150} (region/fit {:w 100 :h 50} 200 400)))
    (is (= {:scale 1.5 :w 150 :h 75 :left 125 :top 0} (region/fit {:w 100 :h 50} 400 75)))
    (is (= {:scale 3 :w 300 :h 150 :left 0 :top 0} (region/fit {:w 100 :h 50} 300 nil)) "no height: at the top")
    (is (nil? (region/fit {:w 100 :h 50} 0 0)))))

(deftest whole-pages
  (is (layout/whole-page? {:x 0 :y 0 :w 362.835 :h 272.126} [362.835 272.126]))
  (is (layout/whole-page? {:x 0.004 :y 0 :w 362.83 :h 272.126} [362.835 272.126]) "within a hundredth of a unit")
  (is (not (layout/whole-page? {:x 362.835 :y 0 :w 362.835 :h 272.126} [725.67 272.126])) "a half is not the page"))

(def ^:private doubled [725.67 272.126])

(deftest runs-of-a-region
  (testing "a run belongs to the region it starts in"
    (let [notes (deck/half-rect doubled :right)]
      (is (layout/run-in-region? notes doubled 53.9 10) "a notes run (x 391 of 725.7)")
      (is (not (layout/run-in-region? notes doubled 1.2 40)) "a slide run")
      (is (layout/run-in-region? notes doubled 50 10) "the midline belongs to the second half")))
  (testing "an edge on the page's own edge reaches out: a run that starts just off the page still belongs"
    (let [slide (deck/half-rect doubled :left)
          notes (deck/half-rect doubled :right)]
      (is (layout/run-in-region? slide doubled -0.5 40) "a negative margin on the left")
      (is (layout/run-in-region? notes doubled 100.2 40) "past the right edge")
      (is (layout/run-in-region? slide doubled 20 -1) "above the top")
      (is (layout/run-in-region? slide doubled 20 100) "on the bottom edge")))
  (testing "an inner edge does not reach out"
    (let [middle {:x 100 :y 50 :w 200 :h 100}]
      (is (layout/run-in-region? middle [400 200] 25 25) "at the region's corner")
      (is (not (layout/run-in-region? middle [400 200] 24.9 30)))
      (is (not (layout/run-in-region? middle [400 200] 75 30)) "on its right edge: what lies beyond")
      (is (not (layout/run-in-region? middle [400 200] 30 75)) "on its bottom edge: what lies beyond"))))

(deftest the-halves-of-a-split-page-take-every-run-once
  ;; the engine hides the runs outside the SLIDE half, the presenter view those outside the NOTES half — the same
  ;; predicate both ways (pdf-region/hide-runs-outside!) — so every run must lie in exactly one of the two, and the
  ;; slide half must be exactly deck/on-slide-half?, which the engine used before
  (let [opposite {:left :right :right :left :top :bottom :bottom :top}
        pcts     (concat [-20 -0.01 0 0.01 25 49.99 50 50.01 75 99.99 100 100.01 120]
                         (map #(* 0.37 %) (range -50 330)))]
    (doseq [side [:left :right :top :bottom]
            :let [slide (deck/half-rect doubled side)
                  notes (deck/half-rect doubled (opposite side))]
            lp pcts
            tp [-5 0 33.3 50 66.7 100 105]]
      (let [in-slide (layout/run-in-region? slide doubled lp tp)
            in-notes (layout/run-in-region? notes doubled lp tp)]
        (is (not= in-slide in-notes) (str side " " lp "% " tp "%: exactly one half"))
        (is (= (boolean (deck/on-slide-half? side lp tp)) in-slide) (str side " " lp "% " tp "%: as on-slide-half?"))))
    (testing "the whole page takes every run"
      (doseq [lp pcts tp [-5 0 50 100 105]]
        (is (layout/run-in-region? (deck/half-rect doubled nil) doubled lp tp))))))
