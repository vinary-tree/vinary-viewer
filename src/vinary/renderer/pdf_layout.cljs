(ns vinary.renderer.pdf-layout
  "Pure, DOM-free geometry + zoom + outline helpers for the in-renderer PDF view. Kept separate from
   vinary.renderer.pdf so the node :test build can cover them without transitively requiring pdfjs-dist
   (which touches DOMMatrix/Worker). All functions are referentially transparent and unit-tested. The generic
   stack/total/visible-range geometry now lives in vinary.renderer.virtual-layout (shared with the streaming
   preview body); this namespace keeps the PDF-specific pieces (scale mapping, zoom, outline numbering) and
   delegates the raw geometry there."
  (:require [clojure.string :as str]
            [vinary.renderer.virtual-layout :as vl]))

(def ^:const min-zoom 0.25)
(def ^:const max-zoom 8.0)
(def ^:const zoom-factor 1.2)

(defn clamp-zoom
  "Clamp a render scale into the supported [min-zoom, max-zoom] band."
  [scale]
  (-> scale (max min-zoom) (min max-zoom)))

(defn zoom-step
  "Next scale for a zoom command. `dir` is :in (×factor), :out (÷factor), or :reset (→ 1.0). Clamped."
  [scale dir]
  (case dir
    :in    (clamp-zoom (* scale zoom-factor))
    :out   (clamp-zoom (/ scale zoom-factor))
    :reset 1.0
    scale))

(defn fit-scale
  "Scale that fits a page of intrinsic size [page-w page-h] into a content box [content-w content-h]:
   :width fills the width, :page fits entirely, :actual is 1.0. Guards against zero page dimensions."
  [content-w content-h page-w page-h mode]
  (if (or (<= page-w 0) (<= page-h 0))
    1.0
    (case mode
      :width  (/ content-w page-w)
      :page   (min (/ content-w page-w) (/ content-h page-h))
      :actual 1.0
      1.0)))

(defn page-rects
  "Cumulative vertical layout for pages of intrinsic sizes [[w h]…] at `scale`, with `gap` px between
   pages. Returns [{:top :height :width}…]; :top is each page's offset from the top of the document.
   Delegates the raw stacking to virtual-layout/stack after applying the PDF scale to each page."
  [sizes scale gap]
  (vl/stack (map (fn [[w h]] {:height (* h scale) :width (* w scale)}) sizes) gap))

(defn total-height
  "Total document height (px) for a page-rects vector — the last page's bottom (gaps are interior)."
  [rects]
  (vl/total rects))

(defn visible-range
  "Indices [first last] of pages in `rects` whose [top,bottom] intersects the viewport
   [scroll-top, scroll-top+viewport-h] expanded by `overscan` px each side. Returns [-1 -1] when none."
  [rects scroll-top viewport-h overscan]
  (vl/visible-range rects scroll-top viewport-h overscan))

(defn anchor-of
  "The reading anchor at document offset `doc-top` (the viewport top, in document coordinates) over page
   `rects`: {:idx i :frac f} — the first page whose box reaches below doc-top, and how far down that page the
   viewport top sits, as a fraction of its height in [0, 1] (0 when doc-top falls in the gap above it). nil
   when there are no pages. Scale-free, so `anchor-top` can restore the same reading position after a zoom, a
   re-fit, or a live refresh that changed page sizes."
  [rects doc-top]
  (when (seq rects)
    (let [n (count rects)
          i (or (first (keep-indexed (fn [i {:keys [top height]}] (when (< doc-top (+ top height)) i)) rects))
                (dec n))
          {:keys [top height]} (nth rects i)]
      {:idx i :frac (if (pos? height) (-> (/ (- doc-top top) height) (max 0) (min 1)) 0)})))

(defn anchor-top
  "The document offset that puts `anchor` {:idx :frac} back at the viewport top under `rects`. :idx is clamped
   into range — a refreshed document may have fewer pages. nil when there are no pages."
  [rects {:keys [idx frac]}]
  (when (seq rects)
    (let [i (-> (or idx 0) (max 0) (min (dec (count rects))))
          {:keys [top height]} (nth rects i)]
      (+ top (* (or frac 0) height)))))

(def max-canvas-pixels
  "Device-pixel budget for one page canvas: pdf.js's own viewer default (maxCanvasPixels = 2^25 ≈ 33.6 M
   pixels, ≈ 134 MB of RGBA). Past it a page rasterizes at a lower density and CSS stretches it."
  (* 4096 8192))

(defn budget-dpr
  "The device-pixel ratio to rasterize a page of CSS size w×h at: the screen's `dpr`, lowered when w·h·dpr²
   would exceed `max-pixels`, so an extreme zoom — or a fullscreen slide on a HiDPI display — can never
   allocate a gigapixel canvas. Never below 0.25."
  ([w h dpr] (budget-dpr w h dpr max-canvas-pixels))
  ([w h dpr max-pixels]
   (let [area (* w h)]
     (if (and (pos? area) (> (* area dpr dpr) max-pixels))
       (max 0.25 (js/Math.sqrt (/ max-pixels area)))
       dpr))))

;; ---- slides layout (ADR-0045) ----------------------------------------------------------------------------
;; In the slides layout every page owns a SLOT exactly one viewport (sw × sh) tall, stacked with no gap, and the
;; page — its sheet — is centred in the slot at its own fit scale. Navigation sets scrollTop to a slot top, so
;; exactly one slide is ever in view, while the pages stay in the DOM: in-page find, the text layer, links, and
;; the Contents scroll-spy (which looks 100 px below the top — inside the current slot) keep working unchanged.

(def slides-max-zoom
  "Upper clamp for a slide's fit scale. Higher than the document layout's max-zoom (8.0) because a 16:9 Beamer
   page (453.5 bp wide) needs ≈ 8.47 to fill a 3840-px-wide fullscreen display; the device-pixel budget
   (budget-dpr) still bounds the canvas."
  16.0)

(defn slide-scale
  "The scale that fits a page of intrinsic size [pw ph] into a sw × sh slot with `margin` px on every side
   (0 when presenting fullscreen), clamped to [min-zoom, slides-max-zoom]. Guards zero sizes."
  [[pw ph] sw sh margin]
  (if (or (<= pw 0) (<= ph 0))
    1.0
    (let [m  (or margin 0)
          aw (max 1 (- sw (* 2 m)))
          ah (max 1 (- sh (* 2 m)))]
      (-> (min (/ aw pw) (/ ah ph)) (max min-zoom) (min slides-max-zoom)))))

(defn slide-scales
  "Per-page slide scales for pages of intrinsic `sizes` in a sw × sh slot (one per page — a portrait title page
   must not shrink every landscape slide to its fit)."
  [sizes sw sh margin]
  (mapv #(slide-scale % sw sh margin) sizes))

(defn slot-rects
  "Rects for `n` slots of sw × sh stacked with no gap: [{:top (i·sh) :height sh :width sw}…] — the same shape as
   page-rects, so visible-range / anchors / the observer band work on slots unchanged."
  [n sw sh]
  (vl/stack (repeat n {:height sh :width sw}) 0))

(defn slot-at
  "The slot index whose top is nearest `scroll-top` (round(scroll-top / sh)), clamped into [0, n-1]. After find
   centres a match, the viewport CENTRE lies in the match's slot, so rounding the top lands on that slot."
  [scroll-top sh n]
  (if (or (<= n 0) (<= sh 0))
    0
    (-> (js/Math.round (/ scroll-top sh)) (max 0) (min (dec n)))))

(defn run-percent
  "PURE. Where a pdf.js text run starts along one axis, as a percent of the full page `extent` (its width for a
   `left`, its height for a `top`), from the run's inline style value — or nil for a format this does not know.
   pdf.js 5.x's TextLayer writes one of two forms, both relative to the page's top-left corner and independent of
   any viewport offset: `52.31%` for a run in the layer's root, and `calc(var(--total-scale-factor) *391.19px)` —
   page units — for a run inside a marked-content group (pdf.js writes no space after the `*`; whitespace is
   accepted anywhere a re-serialization might put it)."
  [style-value extent]
  (when (string? style-value)
    (if-let [[_ pct] (re-matches #"\s*(-?\d+(?:\.\d+)?)%\s*" style-value)]
      (js/parseFloat pct)
      (when-let [[_ units] (re-matches #"\s*calc\(\s*var\(--total-scale-factor\)\s*\*\s*(-?\d+(?:\.\d+)?)px\s*\)\s*"
                                       style-value)]
        (when (and (number? extent) (pos? extent))
          (/ (* 100 (js/parseFloat units)) extent))))))

;; ---- one region of a page, in a box of its own (ADR-0048 §6.3: the presenter view's notes and next slide) --------

(defn region-scale
  "PURE. The CSS scale at which a page region `rect` {:w :h} (page units) fits a `box-w` × `box-h` px box: the largest
   that fits both ways, or the width alone when `box-h` is nil or not positive (a box whose height follows its width).
   nil when the region or the box has no extent."
  [{:keys [w h]} box-w box-h]
  (when (and (number? w) (number? h) (pos? w) (pos? h) (number? box-w) (pos? box-w))
    (if (and (number? box-h) (pos? box-h))
      (min (/ box-w w) (/ box-h h))
      (/ box-w w))))

(defn whole-page?
  "PURE. Does `rect` {:x :y :w :h} cover the whole of a page of size [pw ph] (within a hundredth of a unit)?"
  [{:keys [x y w h]} [pw ph]]
  (let [near (fn [a b] (<= (js/Math.abs (- a b)) 0.01))]
    (and (near x 0) (near y 0) (near w pw) (near h ph))))

(defn run-in-region?
  "PURE. Does a text run whose top-left corner sits at (`left-pct`, `top-pct`) — percent of the FULL page [pw ph], y
   down, as run-percent reads them — belong to the region `rect` {:x :y :w :h} of that page (page units, y down)? It
   does when it starts inside the rectangle, half-open as vinary.slides.deck/on-slide-half? is (a run that starts on
   an inner edge belongs to what lies beyond it), except that an edge lying on the page's own edge (within a hundredth
   of a unit) reaches out without bound: a run that starts just off the page — a negative margin — still belongs to the
   region whose page edge it crossed. So the two halves of a split page (deck/half-rect) take every run exactly once
   between them, the slide half exactly as on-slide-half? does, and the whole page takes every run."
  [{:keys [x y w h]} [pw ph] left-pct top-pct]
  (let [px    (* pw (/ left-pct 100))
        py    (* ph (/ top-pct 100))
        edge? (fn [a b] (<= (js/Math.abs (- a b)) 0.01))]
    (and (or (edge? x 0) (<= x px))
         (or (edge? (+ x w) pw) (< px (+ x w)))
         (or (edge? y 0) (<= y py))
         (or (edge? (+ y h) ph) (< py (+ y h))))))

;; ---- the slide overview grid (ADR-0045 §Overview) ------------------------------------------------------------
(def overview-defaults
  "The overview grid's geometry knobs: tiles at least :min-tile CSS px wide, :gap between and around them, at most
   :max-cols columns, a :label-h px label row under each thumbnail, and a :thumb-max-pixels device-pixel budget
   per thumbnail canvas (a 4K-wide tile of a 16:9 slide is ≈ 2.3 MP — the budget keeps a HiDPI grid bounded)."
  {:min-tile 180 :gap 16 :max-cols 8 :label-h 22 :thumb-max-pixels (* 1024 1024)})

(defn grid-geometry
  "PURE. The overview grid for a `width` × `height` px pane: as many columns of at least :min-tile px (with :gap
   between and around them) as fit — at least 1, at most :max-cols — the resulting tile width, the height of one
   row (a thumbnail of `aspect` = height / width, its label row and a gap), and how many whole rows fit (≥ 1),
   which is what PageUp/PageDown move by. The column count depends on the width alone, so a deck with few frames
   keeps ordinary-sized tiles instead of stretching them across the pane."
  ([width height aspect] (grid-geometry width height aspect overview-defaults))
  ([width height aspect opts]
   (let [{:keys [min-tile gap max-cols label-h]} (merge overview-defaults opts)
         w      (max 1 (or width 1))
         cols   (-> (js/Math.floor (/ (- w gap) (+ min-tile gap))) (max 1) (min max-cols))
         tile-w (max 1 (/ (- w (* gap (inc cols))) cols))
         row-h  (+ (* tile-w (if (and (number? aspect) (pos? aspect)) aspect 0.5625)) label-h gap)]
     {:cols      cols
      :tile-w    tile-w
      :row-h     row-h
      :page-rows (max 1 (js/Math.floor (/ (max 1 (or height 1)) row-h)))})))

(defn outline->toc
  "Flatten a pdf.js getOutline() tree [{:title :dest :items [...]}…] into the app's :doc/toc shape
   [{:level :text :id \"vv-pdf-page-N\"}…], using `dest->page` (dest → 1-based page number, or nil).
   Entries whose dest does not resolve are skipped, but their children are still walked."
  [outline dest->page]
  (letfn [(walk [items level acc]
            (reduce (fn [acc item]
                      (let [{:keys [title dest items]} item
                            page (dest->page dest)
                            acc  (if (and page (seq (str title)))
                                   (conj acc {:level level :text (str title) :id (str "vv-pdf-page-" page)})
                                   acc)]
                        (walk items (inc level) acc)))
                    acc
                    items))]
    (walk (or outline []) 1 [])))

(def ^:private numbered-prefix
  ;; a leading section-number: 1, 1., 2.1, 1) — requires a dot/paren/space separator right after the digits,
  ;; so a title like "3D rendering" (digits glued to a letter) is NOT treated as already-numbered.
  #"^\s*\d+(\.\d+)*[.)]?\s")

(defn- already-numbered?
  "True when the outline already carries its own numbering — at least half of the entries' :text begin with a
   section-number prefix. Guards `number-outline` against double-numbering the author's own scheme."
  [entries]
  (boolean
   (and (seq entries)
        (>= (count (filter #(re-find numbered-prefix (str (:text %))) entries))
            (/ (count entries) 2)))))

(defn number-outline
  "Add a derived hierarchical :number (\"1\", \"2\", \"2.1\", …) to each outline entry [{:level :text :id}…]
   from its :level and sibling order. Returns entries unchanged (no :number) when the outline already appears
   numbered, so the author's own numbering is never doubled. Pure; :id/:level/:text are preserved."
  [entries]
  (if (already-numbered? entries)
    (vec entries)
    (loop [items entries, counters [], acc []]
      (if-let [item (first items)]
        (let [lvl    (max 1 (or (:level item) 1))
              base   (vec (take (dec lvl) (concat counters (repeat 0))))
              bumped (conj base (inc (nth counters (dec lvl) 0)))]
          (recur (rest items) bumped (conj acc (assoc item :number (str/join "." bumped)))))
        acc))))
