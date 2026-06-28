(ns vinary.renderer.pdf-layout
  "Pure, DOM-free geometry + zoom + outline helpers for the in-renderer PDF view. Kept separate from
   vinary.renderer.pdf so the node :test build can cover them without transitively requiring pdfjs-dist
   (which touches DOMMatrix/Worker). All functions are referentially transparent and unit-tested.")

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
   pages. Returns [{:top :height :width}…]; :top is each page's offset from the top of the document."
  [sizes scale gap]
  (loop [sizes sizes, top 0, acc []]
    (if-let [s (first sizes)]
      (let [[w h]  s
            height (* h scale)
            width  (* w scale)]
        (recur (rest sizes) (+ top height gap) (conj acc {:top top :height height :width width})))
      acc)))

(defn total-height
  "Total document height (px) for a page-rects vector — the last page's bottom (gaps are interior)."
  [rects]
  (if-let [{:keys [top height]} (peek rects)]
    (+ top height)
    0))

(defn visible-range
  "Indices [first last] of pages in `rects` whose [top,bottom] intersects the viewport
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
