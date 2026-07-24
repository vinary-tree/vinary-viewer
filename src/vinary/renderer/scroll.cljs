(ns vinary.renderer.scroll
  "Scroll ownership for the content pane. Two things live here:

   1. Per-navigation scroll RESTORE. A nav event (back/forward/open/activate) stashes the target history
      entry's scrollTop; the content component applies it once after the next render — so navigating back
      returns a document to where you were. Live-refresh of the same doc leaves it untouched (no pending
      value → no jump), preserving the reader's position.

   2. CONFINED scrolling — the one way anything in this app scrolls the content pane to a target. It moves
      `.vv-content` and nothing else, via .scrollTo with an explicit offset, rather than
      `el.scrollIntoView`, which walks up and scrolls EVERY scrollable ancestor (including inner <pre>/table
      scrollers and #app itself). Two subsystems independently discovered that and hand-rolled the same
      formula — the TOC jump and the source→preview jump — while in-page find kept calling scrollIntoView
      and produced exactly the reported misbehaviour. `confined-top` is that formula, extracted once, pure,
      and unit-tested. See ADR-0032.")

(defonce ^:private pending (atom nil))   ; scrollTop to apply on the next content render, or nil

(defn want!
  "Request that the content scroller be set to n after the next render."
  [n]
  (reset! pending n))

(defn- content-el [^js node]
  (or (some-> node (.closest ".vv-content"))
      (.querySelector js/document ".vv-content")))

(defn apply!
  "If a scroll was requested, apply it to the content scroller and clear it. Called from the content
   component's did-mount/did-update; deferred one frame so the new document has fully laid out (otherwise
   scrollTop clamps to a not-yet-complete scrollHeight)."
  [^js node]
  (when-let [n @pending]
    (reset! pending nil)
    (when-let [^js c (content-el node)]
      (let [applied (atom nil)
            set-it  (fn []
                      (set! (.-scrollTop c) n)
                      (reset! applied (.-scrollTop c)))
            retry   (fn []
                      (when (or (nil? @applied)
                                (= (.-scrollTop c) @applied))
                        (set-it)))]
        ;; apply next frame, and once more after late layout (figures/fonts) settles, so a tall document
        ;; doesn't clamp the target to a not-yet-complete scrollHeight. If the user scrolls after the
        ;; first apply, skip the late retry instead of snapping them back.
        (js/requestAnimationFrame set-it)
        (js/setTimeout retry 80)))))

(defn current
  "The content scroller's current scrollTop — read at nav time to save the leaving history entry."
  []
  (or (some-> (.querySelector js/document ".vv-content") .-scrollTop) 0))

;; ---- confined scrolling ------------------------------------------------------------------------------

(defn confined-top
  "PURE. The scrollTop that brings a target into view inside ONE scroller, scrolling nothing else.

     st     the scroller's current scrollTop
     c-top  the scroller's viewport-relative top (getBoundingClientRect().top)
     c-h    the scroller's clientHeight
     max-st the scroller's maximum scrollTop (scrollHeight - clientHeight, floored at 0)
     t      the TARGET's viewport-relative top
     h      the target's height
     block  :start | :center | :nearest
     margin leading padding in px

   :start   → st + (t - c-top) - margin
   :center  → st + (t - c-top) - (c-h - h)/2
   :nearest → st unchanged when the target already sits inside [margin, c-h - margin]; else :center.

   All results are clamped to [0, max-st]. With block = :start and margin = 0 this reproduces the formula
   the TOC jump and the source→preview jump were each carrying separately, so adopting it there is
   behaviour-preserving by construction."
  [st c-top c-h max-st t h block margin]
  (let [rel   (- t c-top)                          ; the target's offset inside the scroller's viewport
        m     (or margin 0)
        clamp #(-> % (max 0) (min (max 0 max-st)))]
    (case block
      :start   (clamp (+ st rel (- m)))
      :center  (clamp (+ st rel (- (/ (- c-h h) 2))))
      :nearest (if (and (>= rel m) (<= (+ rel h) (- c-h m)))
                 (clamp st)                        ; already comfortably on screen — do not move
                 (clamp (+ st rel (- (/ (- c-h h) 2)))))
      (clamp (+ st rel (- m))))))

(defn scroller-of
  "The `.vv-content` scroller containing `el`, or the document's one if `el` is not inside a scroller."
  ^js [^js el]
  (or (some-> el (.closest ".vv-content"))
      (.querySelector js/document ".vv-content")))

(defn scroll-rect-to!
  "Confine-scroll `scroller` so `rect` (a viewport-relative DOMRect-like) sits at `:block`. Returns the
   scrollTop it asked for, or nil when there is nothing to scroll.

   opts: :block (:start|:center|:nearest, default :start) :margin (px, default 0)
         :behavior (\"auto\"|\"smooth\", default \"auto\")"
  [^js scroller ^js rect {:keys [block margin behavior] :or {block :start margin 0 behavior "auto"}}]
  (when (and scroller rect)
    (let [c-rect (.getBoundingClientRect scroller)
          top    (confined-top (.-scrollTop scroller)
                               (.-top c-rect)
                               (.-clientHeight scroller)
                               (max 0 (- (.-scrollHeight scroller) (.-clientHeight scroller)))
                               (.-top rect)
                               (.-height rect)
                               block
                               margin)]
      (.scrollTo scroller #js {:top top :behavior behavior})
      top)))

(defn scroll-el-to!
  "Confine-scroll the `.vv-content` containing `el` so `el` sits at `:block`. See scroll-rect-to!."
  [^js el opts]
  (when el
    (scroll-rect-to! (scroller-of el) (.getBoundingClientRect el) opts)))
