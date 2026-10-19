(ns vinary.renderer.pdf-region
  "One rectangle of one page of a BORROWED PDF document, rendered into a box of its own (ADR-0048 §6.3, decision P3):
   the presenter view's notes regions — a split deck's notes half, an interleaved deck's note page — and its next slide.
   It generalizes the overview's thumbnail renderer (vinary.renderer.pdf-thumbs) from a whole tile to any rectangle:

     · the rectangle is rasterized ALONE, through a viewport offset so that its top-left corner is the canvas origin
       (the engine rasterizes only a split page's slide half, so there is nothing of its to crop);
     · it is scaled to fit the box both ways (pdf-layout/region-scale) and centred in it, and rasterized at the
       screen's device-pixel ratio capped by the per-canvas budget (pdf-layout/budget-dpr under max-canvas-pixels);
     · into a DETACHED canvas that replaces the shown one only when it is complete, so a re-render never flashes;
     · every async step re-checks the controller's generation, so a render that lands after show! asked for another
       page, after a rebuild!, or after detach! does nothing; the pdf.js tasks still running are cancelled first;
     · with :text? it lays the page's pdf.js text layer over the canvas — shifted as the canvas is, clipped by the
       region's sheet, the runs that start outside the region hidden (hide-runs-outside!) — so a notes region stays
       selectable and a screen reader reads it, while the canvas itself is aria-hidden;
     · when the box is resized the region takes its new place at once (the canvas CSS-stretches, the text layer follows
       its --total-scale-factor) and rasterizes sharp after a short quiet; a change of the device-pixel ratio (the
       window moved between a 2× laptop and a 1× projector) re-renders it too.

   The box is an element the caller lays out (its size is the CSS's); the controller owns one child of it, the
   .vv-pdf-region-sheet, and everything inside that. Free of any pdfjs-dist require: it calls methods on the borrowed
   document, and builds a text layer with the TextLayer class the caller hands over (pdf-cache/slides-snapshot's
   :text-layer)."
  (:require [vinary.renderer.pdf-layout :as layout]))

(def ^:const resize-quiet-ms
  "How long a box must keep its size before the region is rasterized again at the new size (the engine's re-fit
   debounce: a drag-resize does not thrash pdf.js)."
  120)

(defn- live? [ctrl gen] (and (not (:destroyed? @ctrl)) (= gen (:gen @ctrl))))

(defn- release-canvas!
  "Drop a canvas, zeroing its backing store first: Chromium then frees the bitmap at once instead of at GC."
  [^js cv]
  (set! (.-width cv) 0)
  (set! (.-height cv) 0)
  (.remove cv))

(defn- cancel-tasks!
  "Cancel the pdf.js render and text-layer tasks the controller still runs (their promises reject, and the
   generation fence makes the rejections do nothing)."
  [ctrl]
  (let [{:keys [^js task ^js text-task]} @ctrl]
    (when task (try (.cancel task) (catch :default _ nil)))
    (when text-task (try (.cancel text-task) (catch :default _ nil)))
    (swap! ctrl assoc :task nil :text-task nil)))

(defn fit
  "PURE. Where region `rect` {:w :h} (page units) sits in a `box-w` × `box-h` px box: scaled by pdf-layout/region-scale
   and centred, as {:scale :left :top :w :h} in CSS px; nil when the region or the box has no extent."
  [rect box-w box-h]
  (when-let [s (layout/region-scale rect box-w box-h)]
    (let [w (* (:w rect) s)
          h (* (:h rect) s)]
      {:scale s :w w :h h
       :left  (/ (- box-w w) 2)
       :top   (if (and (number? box-h) (pos? box-h)) (/ (- box-h h) 2) 0)})))

(defn- box-fit [ctrl rect]
  (let [^js box (:box @ctrl)]
    (fit rect (.-clientWidth box) (.-clientHeight box))))

(defn- place-sheet! [^js sheet {:keys [left top w h]}]
  (let [st (.-style sheet)]
    (set! (.-left st) (str left "px"))
    (set! (.-top st) (str top "px"))
    (set! (.-width st) (str w "px"))
    (set! (.-height st) (str h "px"))))

(defn- place-text!
  "Lay a full-page text layer under the region: the page at `scale`, shifted so that the region's corner is the sheet's.
   The size is written in px after pdf.js's TextLayer wrote its own: that one rounds through a --scale-round-x this
   app's CSS does not define, so it would not resolve."
  [^js layer scale {:keys [x y]} [pw ph]]
  (let [st (.-style layer)]
    (.setProperty st "--total-scale-factor" (str scale))
    (.setProperty st "--scale-factor" (str scale))
    (set! (.-left st) (str (- (* x scale)) "px"))
    (set! (.-top st) (str (- (* y scale)) "px"))
    (set! (.-width st) (str (* pw scale) "px"))
    (set! (.-height st) (str (* ph scale) "px"))))

(defn hide-runs-outside!
  "Hide the runs of pdf.js text layer `layer` that do not start inside region `rect` of a page of size [pw ph]
   (pdf-layout/run-in-region?): find, selection and copy then see what the eye sees. A run's position comes from its
   own inline style (pdf-layout/run-percent), so this reads no layout; a run in a format run-percent does not know
   stays visible. The engine hides a split page's notes half with it, and the presenter view the slide half of the
   notes it shows — the two take every run exactly once between them."
  [^js layer rect [pw ph]]
  (doseq [^js run (array-seq (.querySelectorAll layer "span:not(.markedContent)"))]
    (let [st (.-style run)
          lp (layout/run-percent (.-left st) pw)
          tp (layout/run-percent (.-top st) ph)]
      (when (and (some? lp) (some? tp) (not (layout/run-in-region? rect [pw ph] lp tp)))
        (.add (.-classList run) "vv-pdf-offslide")))))

(defn- swap-in!
  "Make `node` the sheet's element of `selector` (the canvas, or the text layer), dropping the one it replaces."
  [^js sheet selector ^js node]
  (if-let [^js old (.querySelector sheet selector)]
    (do (when (= "CANVAS" (.-tagName old)) (set! (.-width old) 0) (set! (.-height old) 0))
        (.replaceWith old node))
    (.append sheet node)))

(defn- clear-sheet! [ctrl]
  (let [^js sheet (:sheet @ctrl)]
    (when-let [^js cv (.querySelector sheet "canvas")] (release-canvas! cv))
    (when-let [^js layer (.querySelector sheet ".vv-pdf-text")] (.remove layer))
    (set! (.. sheet -style -width) "0px")
    (set! (.. sheet -style -height) "0px")
    (swap! ctrl assoc :shown nil)))

(defn- build-text!
  "Render page `pg`'s text layer at `scale` into a detached layer, and swap it in when it is complete."
  [ctrl gen ^js pg scale rect size TextLayer]
  (let [layer (js/document.createElement "div")
        vp    (.getViewport pg #js {:scale scale})
        tl    (js/Reflect.construct TextLayer #js [#js {:textContentSource (.streamTextContent pg)
                                                        :container layer :viewport vp}])]
    (set! (.-className layer) "vv-pdf-text")
    (place-text! layer scale rect size)
    (swap! ctrl assoc :text-task tl)
    (-> (.render ^js tl)
        (.then (fn [_]
                 (when (live? ctrl gen)
                   (when-not (layout/whole-page? rect size) (hide-runs-outside! layer rect size))
                   ;; the box may have been resized while pdf.js laid the runs out
                   (when-let [{s :scale} (box-fit ctrl rect)] (place-text! layer s rect size))
                   (swap-in! (:sheet @ctrl) ".vv-pdf-text" layer)
                   (swap! ctrl assoc :text-task nil))))
        (.catch (fn [_] nil)))))                          ; cancelled, or a destroyed document: nothing to show

(declare relayout!)

(defn- render!
  "Rasterize the controller's request now, superseding whatever is still rendering."
  [ctrl]
  (cancel-tasks! ctrl)
  (let [gen (:gen (swap! ctrl update :gen inc))
        {:keys [^js box want]} @ctrl
        {:keys [doc page rect text? text-layer]} want]
    (if-not (and doc rect (integer? page) (.-isConnected box))
      (clear-sheet! ctrl)
      (-> (.getPage ^js doc (inc page))
          (.then (fn [^js pg]
                   (when (live? ctrl gen)
                     (when-let [{:keys [scale w h] :as placed} (box-fit ctrl rect)]
                       (let [vp1  (.getViewport pg #js {:scale 1})
                             size [(.-width vp1) (.-height vp1)]
                             {:keys [x y]} rect
                             vp   (.getViewport pg #js {:scale scale :offsetX (- (* x scale)) :offsetY (- (* y scale))})
                             dpr  (layout/budget-dpr w h (or (.-devicePixelRatio js/window) 1))
                             cv   (js/document.createElement "canvas")
                             ;; willReadFrequently → a CPU-backed 2D surface on the normal raster path, as the engine
                             ;; and the overview use (the Linux/Electron blank-GPU-canvas compositing bug)
                             ctx  (.getContext cv "2d" #js {:willReadFrequently true})
                             task (.render pg #js {:canvasContext ctx :viewport vp :transform #js [dpr 0 0 dpr 0 0]})]
                         (set! (.-className cv) "vv-pdf-region-canvas")
                         (.setAttribute cv "aria-hidden" "true")
                         (set! (.-width cv) (js/Math.max 1 (js/Math.round (* w dpr))))
                         (set! (.-height cv) (js/Math.max 1 (js/Math.round (* h dpr))))
                         (swap! ctrl assoc :task task)
                         (-> (.-promise task)
                             (.then (fn []
                                      (if (live? ctrl gen)
                                        (let [^js sheet (:sheet @ctrl)
                                              ^js old   (.querySelector sheet ".vv-pdf-text")
                                              before    (:shown @ctrl)
                                              ;; the box may have been resized while pdf.js painted
                                              now       (or (box-fit ctrl rect) placed)]
                                          (place-sheet! sheet now)
                                          (swap-in! sheet "canvas" cv)
                                          (swap! ctrl assoc :task nil
                                                 :shown {:want want :scale scale :dpr dpr :size size})
                                          (cond
                                            ;; the same region at a new size: its text layer only needs its new
                                            ;; scale (pdf.js lays the runs out in units of --total-scale-factor)
                                            (and text? text-layer old (= want (:want before)))
                                            (place-text! old (:scale now) rect size)

                                            :else
                                            (do (when old (.remove old))   ; never another page's text, even briefly
                                                (when (and text? text-layer)
                                                  (build-text! ctrl gen pg scale rect size text-layer))))
                                          ;; resized while painting: rasterize again at the size the box has now
                                          (when (not= (:scale now) scale) (relayout! ctrl)))
                                        (release-canvas! cv))))
                             (.catch (fn [_] (release-canvas! cv)))))))))
          (.catch (fn [_] nil))))))                    ; a destroyed document / a cancelled page: nothing to show

(defn- relayout!
  "The box was resized: give the shown region its new place at once — the canvas CSS-stretches, the text layer follows
   its scale factor — and rasterize it sharp once the size has been quiet for resize-quiet-ms."
  [ctrl]
  (when-not (:destroyed? @ctrl)
    (let [{:keys [shown ^js sheet timer]} @ctrl
          rect (get-in shown [:want :rect])]
      (when-let [placed (and rect (box-fit ctrl rect))]
        (place-sheet! sheet placed)
        (when-let [^js layer (.querySelector sheet ".vv-pdf-text")]
          (place-text! layer (:scale placed) rect (:size shown))))
      (when timer (js/clearTimeout timer))
      (swap! ctrl assoc :timer
             (js/setTimeout
              (fn []
                (swap! ctrl assoc :timer nil)
                (let [{:keys [shown want task]} @ctrl
                      placed (some->> (:rect want) (box-fit ctrl))]
                  ;; a ResizeObserver reports every box once when it starts observing: skip a render that would
                  ;; repeat the one shown; a render still painting re-checks the size when it lands. A request that
                  ;; found the box without extent (not laid out yet) renders now that it has one.
                  (when (and placed (not (:destroyed? @ctrl)) (nil? task)
                             (or (not= want (:want shown))
                                 (not= (:scale placed) (:scale shown))))
                    (render! ctrl))))
              resize-quiet-ms)))))

(defn- watch-dpr!
  "Re-render when the device-pixel ratio leaves its current value: a `(resolution: Xdppx)` media query fires `change`
   exactly then, and is re-armed for the new ratio after each change (as the engine's own watch-dpr! is)."
  [ctrl]
  (when (and (exists? js/matchMedia) (not (:destroyed? @ctrl)))
    (let [^js mq    (js/matchMedia (str "(resolution: " (or (.-devicePixelRatio js/window) 1) "dppx)"))
          handler   (fn [_]
                      (when-not (:destroyed? @ctrl)
                        (render! ctrl)
                        (watch-dpr! ctrl)))]
      (.addEventListener mq "change" handler #js {:once true})
      (swap! ctrl assoc :dpr-watch [mq handler]))))

(defn attach!
  "A controller that renders regions into `box` (an element the caller lays out). Nothing is shown until show!.
   Returns the controller; pass it to show!, rebuild! and detach!."
  [^js box]
  (let [sheet (js/document.createElement "div")
        ctrl  (atom {:box box :sheet sheet :gen 0 :want nil :shown nil :task nil :text-task nil :timer nil
                     :observer nil :dpr-watch nil :destroyed? false})]
    (set! (.-className sheet) "vv-pdf-region-sheet")
    (.append box sheet)
    (when (exists? js/ResizeObserver)
      (let [obs (js/ResizeObserver. (fn [_] (relayout! ctrl)))]
        (.observe obs box)
        (swap! ctrl assoc :observer obs)))
    (watch-dpr! ctrl)
    ctrl))

(defn show!
  "Show `request` in the controller's box: {:doc :page :rect :text? :text-layer} — `rect` {:x :y :w :h} (page units, y
   down) of page `page` (0-based) of `doc` (a borrowed PDFDocumentProxy), with its text layer when `text?` (built with
   the TextLayer class `text-layer`). A nil request, or one without a document or a rectangle, empties the box. A
   request equal to the one being shown — the same document object, page, rectangle and text — does nothing."
  [ctrl request]
  (when (and ctrl (not (:destroyed? @ctrl)))
    (let [{:keys [want]} @ctrl]
      (when-not (and (identical? (:doc want) (:doc request)) (= (dissoc want :doc) (dissoc request :doc)))
        (swap! ctrl assoc :want request)
        (render! ctrl)))))

(defn rebuild!
  "Render `request` again whatever is shown — the document was replaced in place (a live refresh), or the shown raster
   is stale for a reason show! cannot see."
  [ctrl request]
  (when (and ctrl (not (:destroyed? @ctrl)))
    (swap! ctrl assoc :want request)
    (render! ctrl)))

(defn detach!
  "Stop observing, cancel what is rendering, fence off what has not landed yet, and empty the box."
  [ctrl]
  (when (and ctrl (not (:destroyed? @ctrl)))
    (let [{:keys [^js observer dpr-watch timer ^js sheet]} @ctrl]
      (cancel-tasks! ctrl)
      (when observer (.disconnect observer))
      (when-let [[^js mq handler] dpr-watch] (.removeEventListener mq "change" handler))
      (when timer (js/clearTimeout timer))
      (when-let [^js cv (.querySelector sheet "canvas")] (release-canvas! cv))
      (.remove sheet)
      (swap! ctrl (fn [c] (-> c (assoc :destroyed? true :want nil :shown nil :timer nil) (update :gen inc)))))))

(defn shown
  "DEV/test: what the controller shows — {:want :scale :dpr :size} of the last region swapped in — or nil."
  [ctrl]
  (when ctrl (:shown @ctrl)))
