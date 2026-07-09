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
