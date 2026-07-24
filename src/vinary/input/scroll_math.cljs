(ns vinary.input.scroll-math
  "Pure, DOM-free arithmetic for the eased scroll chase driven by vinary.input.fx. Extracted so the
   termination properties can be PROVEN by unit test instead of observed in a browser — the original defect
   (docs/scientific/09-in-page-find-and-scroll-experiments.md) was a non-terminating fixed-point
   search that no test could reach, because the whole loop lived inside a requestAnimationFrame callback.

   The chase eases the scroller toward a target by a fixed fraction per frame. Three invariants make it
   terminate, and each is a property below rather than a comment:

     1. RE-CLAMP. The target is re-clamped against the scroller's LIVE maximum every frame. The document
        can shrink underneath an in-flight animation (a PDF rescale, a streaming spacer collapsing, a late
        image load), which would otherwise strand the target beyond anything reachable.

     2. STEP FLOOR. A frame never requests a sub-pixel move. MEASURED: on this app's scroller a write of
        `scrollTop + 0.4` produces NO movement (probe E3, quantum = 0) — scroll offsets snap to whole
        pixels. The chase's settle threshold is |dt| < 0.5 but its step was 0.25·|dt|, so for
        |dt| ∈ [0.5, 2.0) the step rounded to zero: `dt` froze and the loop re-armed forever. The
        geometric decay dt ← 0.75·dt MUST pass through that window coming down from any larger delta, so
        EVERY scroll command ended trapped. Flooring the step at 1 px closes the window.

     3. STALL BAIL-OUT. If a frame's write produced no movement at all while the chase is not settled, the
        target is unreachable for a reason we did not anticipate — stop rather than spin. This is the
        hypothesis-independent backstop: it makes non-termination impossible even if 1 and 2 are wrong.

   A frame cap is the fourth, blunt guarantee.")

;; Fraction of the remaining distance covered per frame. Preserved from the original animator: the chase
;; exists so a held arrow key's OS auto-repeat produces continuous motion (each press advances the target,
;; the chase follows), which per-press `behavior:"smooth"` cannot do because each call restarts the curve.
(def ease-fraction 0.25)

;; The smallest move a frame may request. See invariant 2.
(def min-step 1.0)

;; Below this remaining distance the chase is finished and the exact target is written.
;;
;; It MUST NOT be smaller than `min-step`. A smaller threshold opens a gap [settle-epsilon, min-step) in
;; which the chase is neither settled nor able to take a floored step — because a step may never overshoot
;; the target, so a remaining distance of 0.7 px can only be closed by a 0.7 px write, which a
;; pixel-snapping scroller ignores. That gap is precisely the original bug in miniature; the invariant
;; `settle-epsilon >= min-step` is what closes it, and scroll-math-test asserts the relationship directly
;; so a future tweak to either constant cannot silently re-open it.
;;
;; Settling up to a pixel short of the target costs nothing: the scroller cannot hold a sub-pixel offset
;; anyway (probe E3: quantum = 0).
(def settle-epsilon 1.0)

;; Blunt upper bound on chase length (~10 s at 60 Hz). Nothing should ever reach it; if something does, the
;; animator stops and — under goog.DEBUG — says so.
(def max-frames 600)

(defn clamp
  "Confine `v` to [0, max-v]. A negative or NaN `max-v` yields 0, so a scroller that is not scrollable
   (or has not been laid out yet) parks the target at the top instead of producing NaN."
  [v max-v]
  (let [m (if (and (number? max-v) (pos? max-v)) max-v 0)]
    (-> (if (number? v) v 0) (max 0) (min m))))

(defn settled?
  "Is the chase finished on this axis? True when the remaining distance is below the settle threshold."
  [cur target]
  (< (js/Math.abs (- target cur)) settle-epsilon))

(defn axis-step
  "PURE. The offset to write this frame on one axis.

   Eases `ease-fraction` of the remaining distance, but never less than `min-step` px and never past the
   target. When already settled, returns the target exactly."
  [cur target]
  (let [d (- target cur)]
    (if (< (js/Math.abs d) settle-epsilon)
      target
      (let [mag (min (js/Math.abs d) (max min-step (* (js/Math.abs d) ease-fraction)))]
        (+ cur (if (neg? d) (- mag) mag))))))

(defn frame
  "PURE. Decide one animation frame.

   aim    — {:top :left} the requested target, possibly stale w.r.t. the live geometry
   geom   — {:top :left :max-top :max-left} the scroller's live offsets and limits
   prev   — {:top :left} the geometry observed on the PREVIOUS frame, or nil on the first
   frames  — how many frames this chase has already run

   Returns {:done? bool :reason kw :top n :left n :aim-top n :aim-left n}. `:top`/`:left` are the offsets
   to write; when :done? they are the final resting values. `:aim-*` is the re-clamped target, which the
   caller should store so the next frame chases a live target."
  [aim geom prev frames]
  (let [t   (clamp (:top aim) (:max-top geom))
        l   (clamp (:left aim) (:max-left geom))
        ct  (:top geom)
        cl  (:left geom)
        fin (and (settled? ct t) (settled? cl l))
        ;; the write we made last frame produced no movement on EITHER axis
        stalled? (and (some? prev)
                      (< (js/Math.abs (- ct (:top prev))) 0.001)
                      (< (js/Math.abs (- cl (:left prev))) 0.001)
                      (not fin))]
    (cond
      fin      {:done? true :reason :settled   :top t  :left l  :aim-top t :aim-left l}
      stalled? {:done? true :reason :stalled   :top ct :left cl :aim-top t :aim-left l}
      (>= frames max-frames)
               {:done? true :reason :frame-cap :top ct :left cl :aim-top t :aim-left l}
      :else    {:done? false :reason :stepping
                :top  (axis-step ct t)
                :left (axis-step cl l)
                :aim-top t :aim-left l})))
