(ns vinary.renderer.source-nav
  "Bidirectional source⇄preview jump helpers — content-agnostic (Markdown, Org, and any format that stamps
   data-vv-source-* onto its preview nodes). The pure `nearest-line-index` (a binary search — the reverse of
   toc/active-heading) maps a source line to the nearest anchored preview element; the DOM helper confine-scrolls
   the .vv-content preview pane to it, using the SAME offset math as the :toc/scroll fx so chrome outside the
   scroller never moves. Kept free of re-frame and of per-content-type selectors (the shared-subsystem rule — a
   per-type selector once silently broke the PDF scroll-spy). A pending-line atom carries a jump across the
   source-view→preview REMOUNT that toggling :view-source? triggers, consumed once the preview mounts.")

(defn nearest-line-index
  "Index into `lines` (a NON-DECREASING vector of 1-based source line numbers, in document order — the order
   querySelectorAll returns [data-vv-source-start-line] elements) of the element whose start-line is the
   greatest ≤ `target`. Clamps to 0 when `target` precedes all lines; returns the LAST index on ties (the
   deepest element sharing a line); nil when `lines` is empty."
  [lines target]
  (let [n (count lines)]
    (when (pos? n)
      (loop [lo 0, hi (dec n), best 0]
        (if (> lo hi)
          best
          (let [mid (quot (+ lo hi) 2)]
            (if (<= (nth lines mid) target)
              (recur (inc mid) hi mid)
              (recur lo (dec mid) best))))))))

(defn- parse-line [x]
  (let [n (js/parseInt (str x) 10)]
    (when-not (js/isNaN n) n)))

;; a pending source→preview jump line: set by the :preview/want-line fx BEFORE the toggle-driven remount, then
;; consumed once the preview mounts (markdown-body) / finishes streaming (ir-stream-body). defonce so it survives
;; hot-reload.
(defonce ^:private pending-preview-line (atom nil))
(defn want-preview-line! [line] (reset! pending-preview-line line))
(defn take-preview-line!
  "Read + clear the pending source→preview jump line (nil when none is pending)."
  []
  (let [v @pending-preview-line] (reset! pending-preview-line nil) v))

(defn current-preview-line
  "The source line of the preview element nearest the TOP of the current .vv-content viewport — the anchor for a
   keyboard/palette 'Go to source' invoked from the preview (no click target). Walks the anchored elements in
   document order and returns the first whose top is at/below the viewport top (else the last element's line).
   nil without a document / preview / any anchored element."
  []
  (when (exists? js/document)
    (when-let [^js content (.querySelector js/document ".vv-content")]
      (let [els   (.querySelectorAll content "[data-vv-source-start-line]")
            n     (.-length els)
            c-top (.. content getBoundingClientRect -top)]
        (when (pos? n)
          (loop [i 0]
            (if (< i n)
              (let [^js el (aget els i)]
                (if (>= (.. el getBoundingClientRect -top) c-top)
                  (parse-line (.getAttribute el "data-vv-source-start-line"))
                  (recur (inc i))))
              (parse-line (.getAttribute (aget els (dec n)) "data-vv-source-start-line")))))))))

(defn scroll-preview-to-line!
  "Confine-scroll the preview (.vv-content) so the element whose data-vv-source-start-line is the greatest ≤
   `line` is brought to the top — the source→preview jump. Same offset math as the :toc/scroll fx (a bounded
   .vv-content scrollTo, never el.scrollIntoView, so chrome outside the scroller never moves). No-op without a
   document, a preview scroller, or any anchored element."
  [line]
  (when (and (exists? js/document) (number? line))
    (when-let [^js content (.querySelector js/document ".vv-content")]
      (let [els (.querySelectorAll content "[data-vv-source-start-line]")
            n   (.-length els)]
        (when (pos? n)
          (let [lines (mapv (fn [i] (or (parse-line (.getAttribute (aget els i) "data-vv-source-start-line")) 0))
                            (range n))]
            (when-let [idx (nearest-line-index lines line)]
              (let [^js el (aget els idx)]
                (.scrollTo content
                           #js {:top      (+ (.-scrollTop content)
                                             (- (.. el getBoundingClientRect -top)
                                                (.. content getBoundingClientRect -top)))
                                :behavior "smooth"})))))))))
