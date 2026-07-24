(ns vinary.renderer.scroll-test
  "Properties of the CONFINED scroll offset — the one formula every programmatic scroll of the content pane
   now goes through (ADR-0032).

   It exists because `el.scrollIntoView` scrolls every scrollable ancestor, not just the pane: an inner
   <pre>, a wide table's own scroller, a math block's overflow-x. Two subsystems had each hand-rolled the
   same replacement with a comment explaining why; in-page find had not, and still called scrollIntoView.
   Extracting the formula makes it one thing to test instead of three things to keep in step.

   The `:start`-with-zero-margin case is the behaviour-preservation guard: it must reproduce, exactly, what
   the :toc/scroll effect and the source→preview jump each computed inline before the refactor."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [vinary.renderer.scroll :as scroll]))

;; A scroller 500px tall whose content is 2000px, currently scrolled to 300. Targets are given as
;; VIEWPORT-relative tops, the way getBoundingClientRect reports them.
(def ^:private st 300)      ; current scrollTop
(def ^:private c-top 40)    ; the scroller's own viewport-relative top
(def ^:private c-h 500)     ; clientHeight
(def ^:private max-st 1500) ; scrollHeight - clientHeight

(defn- at [t h block margin] (scroll/confined-top st c-top c-h max-st t h block margin))

(deftest start-reproduces-the-inlined-formula
  (testing "THE BEHAVIOUR-PRESERVATION GUARD: :start with margin 0 is scrollTop + (targetTop - scrollerTop)"
    ;; the expression both :toc/scroll and source-nav/scroll-preview-to-line! carried inline
    (doseq [t [40 100 240 539]]
      (is (= (+ st (- t c-top)) (at t 20 :start 0))
          (str "target at " t))))
  (testing "a target already at the scroller's top is a no-op"
    (is (= st (at c-top 20 :start 0))))
  (testing "margin lifts the target further down the viewport"
    (is (= (- (at 240 20 :start 0) 24) (at 240 20 :start 24)))))

(deftest center-puts-the-target-in-the-middle
  (testing "the target's centre lands on the scroller's centre"
    ;; after scrolling by the returned delta, the target's viewport top should be c-top + (c-h - h)/2
    (doseq [[t h] [[240 20] [400 60] [90 12]]]
      (let [top' (at t h :center 0)
            delta (- top' st)
            landed (- t delta)]
        (is (= (+ c-top (/ (- c-h h) 2)) landed)
            (str "target " t " of height " h " landed at " landed)))))
  (testing "a target taller than the viewport is pinned to the top rather than centred above it"
    ;; (c-h - h) goes negative, which pushes the target's top ABOVE the scroller — clamping keeps the
    ;; result in range, which is the sane reading position for an oversized block
    (is (<= 0 (at 240 900 :center 0) max-st))))

(deftest nearest-does-not-move-a-visible-target
  (testing "a target comfortably inside the viewport is left alone"
    (is (= st (at 200 20 :nearest 0)))
    (is (= st (at (+ c-top 10) 20 :nearest 0))))
  (testing "a target above the viewport is centred"
    (is (= (at 10 20 :center 0) (at 10 20 :nearest 0))))
  (testing "a target below the viewport is centred"
    (is (= (at 600 20 :center 0) (at 600 20 :nearest 0))))
  (testing "the margin narrows the band that counts as 'already visible'"
    ;; 12px below the scroller top: inside the band with no margin, outside it with a 40px margin
    (is (= st (at (+ c-top 12) 20 :nearest 0)))
    (is (not= st (at (+ c-top 12) 20 :nearest 40)))))

(deftest results-are-always-in-range
  (testing "never scrolls above the top"
    (is (= 0 (at -5000 20 :start 0)))
    (is (= 0 (at -5000 20 :center 0))))
  (testing "never scrolls past the bottom"
    (is (= max-st (at 99999 20 :start 0)))
    (is (= max-st (at 99999 20 :center 0))))
  (testing "a non-scrollable pane stays at 0"
    (is (= 0 (scroll/confined-top 0 c-top c-h 0 900 20 :start 0)))
    (is (= 0 (scroll/confined-top 0 c-top c-h 0 900 20 :center 0))))
  (testing "an unknown block keyword falls back to :start rather than throwing"
    (is (= (at 240 20 :start 0) (at 240 20 :bogus 0)))))
