(ns vinary.tui.viewport
  "A pure windowed line buffer. Only a `:h`-row window is ever PAINTED (`visible`), so per-frame cost is bounded by
   the terminal height. Memory is bounded by an optional `:cap` on retained lines: a BATCH doc (markdown/source —
   already O(document) once rendered) uses no cap and is fully scrollable; the STREAMING path (a huge log) sets a
   cap so the buffer is a ring of the last `:cap` lines — older lines drop and are counted in `:dropped` (the driver
   shows a `… N earlier lines` head), keeping RSS flat regardless of log length (a `less`-with-scrollback-limit
   model; the absolute top of a multi-GB log is not retained, by design). Every op is value→value (unit-testable).
   Streaming appends follow the tail only at the bottom; a reader scrolled up is not yanked, and when old lines drop
   `:top` is decremented so their view stays on the same content."
  (:require [clojure.string :as str]))

(defn viewport
  ([w h] (viewport w h nil))
  ([w h cap] {:lines [] :top 0 :w (max 1 w) :h (max 1 h) :cap cap :dropped 0}))

(defn- max-top [vp] (max 0 (- (count (:lines vp)) (:h vp))))
(defn- clamp [vp] (update vp :top #(-> (or % 0) (max 0) (min (max-top vp)))))

(defn at-bottom? [vp] (>= (:top vp) (max-top vp)))

(defn set-lines [vp lines] (clamp (assoc vp :lines (vec lines))))

(defn scroll  [vp d]   (clamp (update vp :top + d)))
(defn page    [vp dir] (scroll vp (* dir (max 1 (dec (:h vp))))))   ; a page keeps one row of overlap
(defn to-top    [vp] (assoc vp :top 0))
(defn to-bottom [vp] (assoc vp :top (max-top vp)))

(defn to-line
  "Reveal content line `i`, placed a couple of rows below the top for context, clamped into range."
  [vp i]
  (clamp (assoc vp :top (max 0 (- (or i 0) 2)))))

(defn resize
  "Apply a new terminal size. `lines` (already re-rendered at the new width by the caller) replaces the buffer;
   `top` is re-clamped and kept roughly stable."
  [vp w h lines]
  (clamp (assoc vp :w (max 1 w) :h (max 1 h) :lines (vec lines))))

(defn append
  "Append streamed `new-lines`. Follows the growing tail only if already at the bottom (a scrolled-up reader stays
   put). When a `:cap` is set and exceeded, the oldest lines drop (bounded ring): the drop count accumulates in
   `:dropped`, and a scrolled-up reader's `:top` is decremented by the drop so their VIEW stays on the same content
   (clamped at 0 once they've scrolled into dropped territory)."
  [vp new-lines]
  (let [follow? (at-bottom? vp)
        lines   (into (:lines vp) new-lines)
        cap     (:cap vp)
        over    (if cap (max 0 (- (count lines) cap)) 0)
        vp'     (assoc vp :lines (if (pos? over) (subvec lines over) lines)
                          :dropped (+ (:dropped vp) over))]
    (cond
      follow?      (to-bottom vp')
      (pos? over)  (clamp (update vp' :top #(max 0 (- % over))))   ; keep the reader on the same content
      :else        (clamp vp'))))

(defn visible
  "The paint window: {:slice [line …] :top :total :h :at-bottom? :progress}. `:slice` is at most `:h` rows."
  [vp]
  (let [top (:top vp) h (:h vp) total (count (:lines vp))]
    {:slice     (subvec (:lines vp) (min top total) (min (+ top h) total))
     :top       top
     :total     total
     :h         h
     :dropped   (:dropped vp)
     :at-bottom? (at-bottom? vp)
     :progress  (if (<= total h) 1.0 (/ (min (+ top h) total) total))}))
