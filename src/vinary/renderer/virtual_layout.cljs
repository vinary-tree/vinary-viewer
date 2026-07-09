(ns vinary.renderer.virtual-layout
  "Pure, DOM-free vertical-stack geometry shared by the in-renderer PDF view and the streaming preview body:
   cumulative tops/heights, total height, the visible/overscan band, windowed-band spacer padding, and height
   estimation/extrapolation. Content-agnostic on purpose — it knows only heights and scroll math, never 'page'
   vs 'block' vs 'record' (the shared-subsystem rule: a per-type notion leaking in here is exactly what once
   silently broke the PDF scroll-spy). All functions are referentially transparent and unit-tested.")

(defn stack
  "Cumulative vertical layout for `items` (a seq of maps carrying at least :height, e.g. {:height h} or
   {:height h :width w}) stacked top-to-bottom with `gap` px between them. Returns [{:top …}…] where :top is
   each item's offset from the top of the stack; extra keys on each item (e.g. :width) are preserved."
  [items gap]
  (loop [items (seq items), top 0, acc []]
    (if-let [it (first items)]
      (let [h (:height it)]
        (recur (rest items) (+ top h gap) (conj acc (assoc it :top top))))
      acc)))

(defn total
  "Total stack height (px) for a rects vector from `stack` — the last item's bottom (gaps are interior)."
  [rects]
  (if-let [{:keys [top height]} (peek rects)]
    (+ top height)
    0))

(defn visible-range
  "Indices [first last] of items in `rects` whose [top,bottom] intersects the viewport
   [scroll-top, scroll-top+viewport-h] expanded by `overscan` px each side. Returns [-1 -1] when none."
  [rects scroll-top viewport-h overscan]
  (let [lo (- scroll-top overscan)
        hi (+ scroll-top viewport-h overscan)
        n  (count rects)]
    (loop [i 0, first-idx -1, last-idx -1]
      (if (< i n)
        (let [{:keys [top height]} (nth rects i)
              bottom (+ top height)]
          (if (and (< top hi) (> bottom lo))
            (recur (inc i) (if (neg? first-idx) i first-idx) i)
            (recur (inc i) first-idx last-idx)))
        [first-idx last-idx]))))

(defn est-heights
  "A vector of `n` per-item heights: the MEASURED height at index i when known (`measured` is a map i→px), else
   the `default` estimate. Models 'remember the real size once it is measured' — the code-side analog of
   contain-intrinsic-size:auto — so the height model converges to exact as blocks are measured."
  [n default measured]
  (mapv (fn [i] (get measured i default)) (range n)))

(defn extrapolate-total
  "Estimate the whole-document height from the height rendered SO FAR and a completion `progress` in (0,1]:
   rendered-h / progress, floored at `floor` (the rendered height, so the estimate never dips below what is
   already on screen). Returns `floor` when progress is not yet positive."
  [rendered-h progress floor]
  (if (and (number? progress) (pos? progress))
    (max floor (/ rendered-h progress))
    floor))

(defn spacer-height
  "The trailing-spacer height that pads a rendered body of `rendered-h` up to `estimated-total`. Never negative,
   so once the real content overshoots the estimate the spacer simply collapses to 0."
  [estimated-total rendered-h]
  (max 0 (- estimated-total rendered-h)))

(defn pads
  "Top/bottom spacer heights that position a windowed band [lo..hi] (inclusive indices into `rects`) inside a
   document of height `total-h`: {:top <band's first top> :bottom <total-h − band's last bottom>}. Both zero for
   an empty band ([-1 -1])."
  [rects lo hi total-h]
  (if (or (neg? lo) (neg? hi) (empty? rects))
    {:top 0 :bottom 0}
    (let [{ltop :top}          (nth rects lo)
          {htop :top hh :height} (nth rects hi)]
      {:top    (max 0 ltop)
       :bottom (max 0 (- total-h (+ htop hh)))})))

(defn band-range
  "Indices [first last] of the items to keep MATERIALISED around the viewport: `visible-range` with the overscan
   derived from a px budget (`budget-px` total, split evenly above and below). This is the band a windowed body
   renders; everything outside it is virtualised behind the top/bottom spacers."
  [rects scroll-top viewport-h budget-px]
  (visible-range rects scroll-top viewport-h (max 0 (/ (or budget-px 0) 2))))
