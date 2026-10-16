(ns vinary.renderer.pdf-thumbs
  "Lazy, bounded thumbnail rendering for the slide overview grid (ADR-0045 §Overview).

   The overview BORROWS the mounted slides view's PDFDocumentProxy (pdf-cache/slides-snapshot) — never a second
   proxy, and it never touches the engine's single global document / find-hook slots. Each tile is a box with a
   `data-page` (0-based) attribute; this controller rasterizes the boxes that come near the overview's own
   scroller and releases the far ones, so a 150-page deck costs a few dozen small canvases, not 150:

     · an IntersectionObserver on the overview scroller with a 50 % root margin — a box enters the queue when it
       is within half a viewport of view, and its canvas is released when it leaves that band;
     · a render queue running at most `concurrency` (2) pdf.js renders at a time, nearest-first by arrival;
     · each thumbnail renders into a DETACHED canvas that replaces the placeholder only when complete (no flash);
     · the device-pixel ratio is capped by a per-thumbnail pixel budget (pdf-layout/budget-dpr);
     · every async step re-checks the controller's generation, so a render finishing after detach! / rebuild!
       (a live refresh replaced the document, the overview closed) does nothing — and a render against a
       document that was destroyed meanwhile rejects into a swallowed catch.

   Free of any pdfjs-dist require: it only calls methods on the borrowed document."
  (:require [vinary.renderer.pdf-layout :as layout]))

(def ^:const concurrency 2)      ; pdf.js renders in flight at once
(def ^:const root-margin "50%")  ; the band (beyond the visible area) in which thumbnails stay rendered

(defn- live? [ctrl gen] (and (not (:destroyed? @ctrl)) (= gen (:gen @ctrl))))

(defn- box-of [^js el] (.querySelector el ".vv-slides-tile-thumb"))

(defn- release! [^js el]
  (when-let [^js box (box-of el)]
    (when-let [^js cv (.querySelector box "canvas")]
      ;; zero the backing store before dropping the node: Chromium frees the bitmap at once instead of at GC
      (set! (.-width cv) 0) (set! (.-height cv) 0)
      (.remove cv))))

(declare pump!)

(defn- render! [ctrl ^js el]
  (let [{:keys [doc gen]} @ctrl
        idx (js/parseInt (.getAttribute el "data-page") 10)
        ^js box (box-of el)]
    (swap! ctrl (fn [c] (-> c (update :active inc) (update :inflight conj el))))
    (-> (.getPage ^js doc (inc idx))
        (.then (fn [^js pg]
                 (when (and (live? ctrl gen) (.-isConnected box) (contains? (:near @ctrl) el))
                   (let [vp1   (.getViewport pg #js {:scale 1})
                         css-w (max 1 (.-clientWidth box))
                         scale (/ css-w (.-width vp1))
                         vp    (.getViewport pg #js {:scale scale})
                         dpr   (layout/budget-dpr (.-width vp) (.-height vp) (or (.-devicePixelRatio js/window) 1)
                                                  (:thumb-max-pixels layout/overview-defaults))
                         cv    (js/document.createElement "canvas")
                         ctx   (.getContext cv "2d" #js {:willReadFrequently true})]
                     (set! (.-className cv) "vv-slides-tile-canvas")
                     (set! (.-width cv) (js/Math.max 1 (js/Math.round (* (.-width vp) dpr))))
                     (set! (.-height cv) (js/Math.max 1 (js/Math.round (* (.-height vp) dpr))))
                     (-> (.-promise (.render pg #js {:canvasContext ctx :viewport vp :transform #js [dpr 0 0 dpr 0 0]}))
                         (.then (fn []
                                  (if (and (live? ctrl gen) (.-isConnected box) (contains? (:near @ctrl) el))
                                    (if-let [^js old (.querySelector box "canvas")]
                                      (.replaceWith old cv)
                                      (.append box cv))
                                    (do (set! (.-width cv) 0) (set! (.-height cv) 0))))))))))
        (.catch (fn [_] nil))                        ; a destroyed document / cancelled page: nothing to show
        (.finally (fn []
                    (swap! ctrl (fn [c] (-> c (update :active dec) (update :inflight disj el))))
                    (when (live? ctrl gen) (pump! ctrl)))))))

(defn- pump!
  "Start queued renders while fewer than `concurrency` are in flight."
  [ctrl]
  (loop []
    (let [{:keys [active queue near inflight]} @ctrl]
      (when (and (< active concurrency) (seq queue))
        (let [el (first queue)]
          (swap! ctrl update :queue (fn [q] (vec (rest q))))
          ;; still near, not already rendered, not already being rendered (a tile can be queued twice when it
          ;; leaves and re-enters the band before its first render started)
          (when (and (contains? near el) (not (contains? inflight el))
                     (not (.querySelector ^js (box-of el) "canvas")))
            (render! ctrl el))
          (recur))))))

(defn- on-intersect [ctrl entries]
  (doseq [^js e entries]
    (let [el (.-target e)]
      (if (.-isIntersecting e)
        (do (swap! ctrl (fn [c] (-> c (update :near conj el) (update :queue conj el))))
            nil)
        (do (swap! ctrl update :near disj el)
            (release! el)))))
  (pump! ctrl))

(defn attach!
  "Render thumbnails of `doc` (a pdf.js PDFDocumentProxy, borrowed) into the tiles under `scroller` (each a
   `[data-page]` element containing a `.vv-slides-tile-thumb` box). Returns the controller; pass it to rebuild!
   and detach!."
  [^js scroller doc]
  (let [ctrl (atom {:doc doc :gen 0 :near #{} :queue [] :active 0 :inflight #{} :destroyed? false :observer nil})
        obs  (js/IntersectionObserver. (fn [entries _] (on-intersect ctrl entries))
                                       #js {:root scroller :rootMargin (str root-margin " 0px")})]
    (swap! ctrl assoc :observer obs :scroller scroller)
    (doseq [el (array-seq (.querySelectorAll scroller "[data-page]"))] (.observe obs el))
    ctrl))

(defn rebuild!
  "The document changed (a live refresh swapped a new proxy in) or the tiles were re-rendered: drop every
   thumbnail, bump the generation (in-flight renders discard themselves), and observe the current tiles again."
  [ctrl doc]
  (when (and ctrl (not (:destroyed? @ctrl)))
    (let [{:keys [^js observer ^js scroller]} @ctrl]
      (.disconnect observer)
      (doseq [el (array-seq (.querySelectorAll scroller "[data-page]"))] (release! el))
      (swap! ctrl (fn [c] (-> c (assoc :doc doc :near #{} :queue []) (update :gen inc))))
      (doseq [el (array-seq (.querySelectorAll scroller "[data-page]"))] (.observe observer el)))))

(defn detach!
  "Stop observing, release every thumbnail, and fence off in-flight renders."
  [ctrl]
  (when (and ctrl (not (:destroyed? @ctrl)))
    (let [{:keys [^js observer ^js scroller]} @ctrl]
      (.disconnect observer)
      (doseq [el (array-seq (.querySelectorAll scroller "[data-page]"))] (release! el))
      (swap! ctrl assoc :destroyed? true :near #{} :queue []))))

(defn live-canvases
  "DEV/test: how many thumbnail canvases exist under `scroller` (bounded by the observer band)."
  [^js scroller]
  (.-length (.querySelectorAll scroller "canvas.vv-slides-tile-canvas")))
