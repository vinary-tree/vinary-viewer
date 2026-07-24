(ns vinary.input.scroll-math-test
  "Termination properties of the eased scroll chase.

   These exist because the original defect was unreachable by test: a non-terminating fixed-point search
   living entirely inside a requestAnimationFrame callback. It shipped, and the only way to see it was to
   scroll a document in a real window and notice that the view fought the wheel. Extracting the arithmetic
   (vinary.input.scroll-math) turns 'the chase always stops' from a hope into an assertion.

   The measured cause is encoded in `sub-pixel-steps-never-stall`: the app's scroller snaps offsets to
   whole pixels, so the old 0.25·|dt| step produced NO movement for |dt| ∈ [0.5, 2.0) — a window the
   geometric decay must pass through — freezing dt below the 0.5 settle threshold forever. See
   docs/scientific/09-in-page-find-and-scroll-experiments.md."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [vinary.input.scroll-math :as sm]))

(defn- geom
  ([top] (geom top 0 100000 0))
  ([top left max-top max-left] {:top top :left left :max-top max-top :max-left max-left}))

(defn- run
  "Drive the chase to completion against a scroller that SNAPS offsets to whole pixels (the measured
   behaviour: probe E3 reported quantum = 0, i.e. a 0.4px write moves nothing). Returns
   {:frames n :final t :reason kw} — or :reason :runaway if it exceeds the frame cap, which is the
   failure the whole namespace exists to prevent."
  [start target max-top]
  (loop [cur (js/Math.round start), prev nil, n 0, aim target]
    (let [g (geom cur 0 max-top 0)
          d (sm/frame {:top aim :left 0} g prev n)]
      (cond
        (> n (+ sm/max-frames 5)) {:frames n :final cur :reason :runaway}
        (:done? d)                {:frames n :final (js/Math.round (:top d)) :reason (:reason d)}
        :else (recur (js/Math.round (:top d))            ; the snap
                     {:top cur :left 0}
                     (inc n)
                     (:aim-top d))))))

(deftest clamping
  (testing "clamp confines to [0, max]"
    (is (= 0 (sm/clamp -50 1000)))
    (is (= 1000 (sm/clamp 5000 1000)))
    (is (= 250 (sm/clamp 250 1000))))
  (testing "a non-scrollable (or not-yet-laid-out) scroller parks at the top rather than producing NaN"
    (is (= 0 (sm/clamp 500 0)))
    (is (= 0 (sm/clamp 500 -1)))
    (is (= 0 (sm/clamp 500 js/NaN)))
    (is (= 0 (sm/clamp js/NaN 1000)))))

(deftest settling
  (testing "settled? is the sub-threshold test, symmetric in both directions"
    (is (sm/settled? 100 100))
    (is (sm/settled? 100 100.9))
    (is (sm/settled? 100 99.1))
    (is (not (sm/settled? 100 101)))
    (is (not (sm/settled? 100 99)))))

(deftest no-gap-between-settling-and-stepping
  (testing "THE STRUCTURAL INVARIANT: the settle threshold is never below the step floor"
    ;; A smaller threshold would open a window in which the chase is neither finished nor able to take a
    ;; full-pixel step (a step may not overshoot), which is the original defect in miniature.
    (is (>= sm/settle-epsilon sm/min-step)
        "settle-epsilon < min-step re-opens the sub-pixel stall window")))

(deftest sub-pixel-steps-never-stall
  (testing "THE REGRESSION GUARD: every unsettled frame moves at least one whole pixel"
    ;; these are the deltas the old 0.25·|dt| step died on: it asked for 0.125–0.5px, the scroller ignored
    ;; the write, dt never shrank, and the loop re-armed forever
    (doseq [d [1.0 1.25 1.5 1.75 1.9 1.99 2.0 2.5 3.0 7.0]]
      (is (not (sm/settled? 100 (+ 100 d))) (str "delta " d " must not be treated as settled"))
      (let [next (sm/axis-step 100 (+ 100 d))]
        (is (>= (js/Math.abs (- next 100)) 1.0)
            (str "a delta of " d " must produce a >= 1px step, got " (- next 100))))
      (let [next (sm/axis-step 100 (- 100 d))]
        (is (>= (js/Math.abs (- next 100)) 1.0)
            (str "a delta of -" d " must produce a >= 1px step, got " (- next 100))))))
  (testing "sub-pixel deltas are SETTLED rather than stepped, so no sub-pixel write is ever requested"
    (doseq [d [0.1 0.4 0.5 0.75 0.99]]
      (is (sm/settled? 100 (+ 100 d)))
      (is (sm/settled? 100 (- 100 d)))))
  (testing "the step never overshoots the target"
    (doseq [d [1.0 1.4 2.0 8.0 400.0]]
      (is (<= (sm/axis-step 100 (+ 100 d)) (+ 100 d)))
      (is (>= (sm/axis-step 100 (- 100 d)) (- 100 d)))))
  (testing "an already-settled axis snaps exactly onto the target"
    (is (= 100.3 (sm/axis-step 100 100.3)))))

(deftest chase-terminates
  (testing "converges from a large delta, in both directions, on a pixel-snapping scroller"
    (let [down (run 0 1000 5000)
          up   (run 1000 0 5000)]
      (is (= :settled (:reason down)))
      (is (= :settled (:reason up)))
      (is (= 1000 (:final down)))
      (is (= 0 (:final up)))
      (is (< (:frames down) 60) (str "took " (:frames down) " frames"))
      (is (< (:frames up) 60) (str "took " (:frames up) " frames"))))
  (testing "every delta in the old death window still terminates and LANDS on the target"
    ;; before the step floor, each of these froze the loop forever
    (doseq [d (range 1 12)]
      (let [r (run 100 (+ 100 d) 5000)]
        (is (= :settled (:reason r)) (str "delta " d " → " (:reason r)))
        (is (= (+ 100 d) (:final r)) (str "delta " d " landed on " (:final r))))))
  (testing "a target beyond the live maximum is re-clamped, and the chase still ends"
    (let [r (run 0 9999 500)]
      (is (= :settled (:reason r)))
      (is (= 500 (:final r)))))
  (testing "a chase already at its target ends on the first frame without moving"
    (let [r (run 300 300 5000)]
      (is (= :settled (:reason r)))
      (is (zero? (:frames r))))))

(deftest chase-survives-a-shrinking-document
  (testing "the target is re-clamped against the LIVE maximum every frame"
    ;; the scroller shrinks under the animation (a PDF rescale, a streaming spacer collapsing): the target
    ;; the chase was given no longer exists, and without a live re-clamp it would be unreachable forever
    (loop [cur 0, prev nil, n 0, aim 4000, max-top 5000]
      (let [max' (if (= n 10) 900 max-top)          ; the document shrinks at frame 10
            g    (geom (js/Math.round cur) 0 max' 0)
            d    (sm/frame {:top aim :left 0} g prev n)]
        (cond
          (> n 400) (is false "the chase never terminated after the document shrank")
          (:done? d) (do (is (= :settled (:reason d)))
                         (is (= 900 (js/Math.round (:top d)))
                             "it settles at the NEW maximum, not the stale target"))
          :else (recur (js/Math.round (:top d)) {:top (js/Math.round cur) :left 0}
                       (inc n) (:aim-top d) max')))))
  (testing "the re-clamped target is reported back so the caller stops chasing a stale one"
    (let [d (sm/frame {:top 9999 :left 0} (geom 0 0 300 0) nil 0)]
      (is (= 300 (:aim-top d))))))

(deftest chase-bails-out-when-it-cannot-move
  (testing "a frame that produced no movement while unsettled ends the chase"
    ;; the hypothesis-independent backstop: whatever the reason a write does not take effect, the loop
    ;; must not spin. Modelled as a scroller frozen at 100 while the target is 400.
    (let [g (geom 100 0 5000 0)
          d (sm/frame {:top 400 :left 0} g {:top 100 :left 0} 1)]
      (is (:done? d))
      (is (= :stalled (:reason d)))
      (is (= 100 (:top d)) "it leaves the scroller exactly where it is")))
  (testing "no-movement is NOT a stall when the chase has already settled"
    (let [g (geom 400 0 5000 0)
          d (sm/frame {:top 400 :left 0} g {:top 400 :left 0} 1)]
      (is (:done? d))
      (is (= :settled (:reason d)))))
  (testing "the first frame can never stall (there is no previous geometry to compare against)"
    (let [d (sm/frame {:top 400 :left 0} (geom 100 0 5000 0) nil 0)]
      (is (not (:done? d)))
      (is (= :stepping (:reason d))))))

(deftest chase-has-a-hard-frame-cap
  (testing "the frame cap ends a chase no matter what the other guards do"
    (let [d (sm/frame {:top 4000 :left 0} (geom 0 0 5000 0) {:top -1 :left -1} sm/max-frames)]
      (is (:done? d))
      (is (= :frame-cap (:reason d))))))

(deftest both-axes-participate
  (testing "a chase is finished only when BOTH axes have settled"
    ;; vertical settled, horizontal not → keep stepping (the old code had this right; keep it right)
    (let [d (sm/frame {:top 100 :left 500} (geom 100 0 5000 900) nil 0)]
      (is (not (:done? d)))
      (is (= 100 (:top d)))
      (is (> (:left d) 0))))
  (testing "the horizontal axis is clamped against max-left, not max-top"
    (let [d (sm/frame {:top 0 :left 9999} (geom 0 0 5000 120) nil 0)]
      (is (= 120 (:aim-left d))))))
