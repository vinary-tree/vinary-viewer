(ns vinary.renderer.pdf
  "In-renderer PDF rendering via pdf.js (pdfjs-dist). Pages render to a <canvas> inside the .vv-content
   scroller with an overlaid text layer (real selection / find / copy) + a link layer, so PDFs inherit
   the app's keymap, context menu, in-page find, smooth-scroll, and themes — parity with the Markdown /
   source previews (ADR-0003: imperative DOM, no VDOM over the body). Bytes arrive over IPC (the
   :content/received byte cache); the worker + data assets are vendored under resources/public/pdf/
   (scripts/sync-pdfjs.mjs). Pure geometry/zoom/outline helpers live in vinary.renderer.pdf-layout."
  (:require [vinary.renderer.pdf-layout :as layout]
            [vinary.renderer.pdf-cache :as cache]
            [vinary.renderer.scroll :as scroll]
            [vinary.slides.deck :as deck]
            [vinary.slides.evidence :as evidence]
            [vinary.renderer.toc :as toc]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.pdf :as pdf-fe]
            [vinary.ir.backend.html :as ir-html]
            [re-frame.core :as rf]))

(def ^:const gap 16)        ; px between pages
(def ^:const overscan 1.5)  ; viewport-heights of canvas-render overscan each side (document layout)
;; slides layout (ADR-0045): half a viewport of overscan keeps exactly the neighbouring slots rendered — the next
;; and previous slide are ready for an instant flip — while slot ±2 (a full viewport away) is released. The 1.5
;; of the document layout would keep ~5 fullscreen-sized canvases alive.
(def ^:const slides-overscan 0.5)
(def ^:const slides-margin 16)   ; px around a slide in the in-tab slides layout (0 when presenting)

(declare render-page! release-canvas! build-text-layer! ensure-text-at! build-link-layer!
         resolve-dest scroll-to-page! apply-fit! rescale! ensure-all-text! watch-dpr! command!)

;; ---- pdf.js module + worker bootstrap -----------------------------------------------------------
;; pdfjs-dist uses dynamic import() internally, which Closure :simple cannot bundle — so we do NOT
;; :require it. The vendored legacy module (resources/public/pdf/pdf.min.mjs) is loaded at runtime via
;; native ESM import(), behind `new Function` so Closure never parses the import() expression. The
;; worker runs from a same-origin blob: URL (a module worker built directly from a file:// document can
;; be refused by Chromium even though app-relative fetch works — tree-sitter precedent). Asset URLs are
;; resolved against the document base so they are stable across the dev and release output layouts.
(defn- pdf-asset [rel] (.-href (js/URL. (str "pdf/" rel) (.-baseURI js/document))))

(defonce ^:private dynamic-import (js/Function. "u" "return import(u)"))
(defonce ^:private pdfjs (atom nil))   ; the loaded pdf.js module (set once module-init resolves)

;; load the module from a same-origin blob: URL (always import-allowed) rather than a bare file:// import
;; (which some Chromium configs refuse). The minified build is self-contained; its only internal import()s
;; are the fake-worker / node fallbacks, which aren't reached once a real worker is set below.
(defonce ^:private module-init
  (-> (js/fetch (pdf-asset "pdf.min.mjs"))
      (.then (fn [r] (.text r)))
      (.then (fn [src]
               (dynamic-import (.createObjectURL js/URL (js/Blob. #js [src] #js {:type "text/javascript"})))))
      (.then (fn [m] (reset! pdfjs m) m))))

(defonce ^:private worker-init
  (-> module-init
      (.then (fn [^js m]
               (-> (js/fetch (pdf-asset "pdf.worker.min.mjs"))
                   (.then (fn [r] (.text r)))
                   (.then (fn [src]
                            (let [url (.createObjectURL js/URL (js/Blob. #js [src] #js {:type "text/javascript"}))]
                              (set! (.. m -GlobalWorkerOptions -workerSrc) url)
                              m))))))))

(defn- get-document [^js bytes]
  (-> worker-init
      (.then (fn [^js m]
               ;; pass a COPY: pdf.js transfers (detaches) the :data ArrayBuffer to its worker, which would
               ;; empty the cached Uint8Array → a remount (tab switch away+back) would get a 0-length buffer
               ;; and render nothing. Slicing keeps the cached original pristine for every (re)mount.
               (-> (.getDocument m #js {:data                (.slice bytes 0)
                                        :cMapUrl              (pdf-asset "cmaps/")
                                        :cMapPacked           true
                                        :standardFontDataUrl  (pdf-asset "standard_fonts/")
                                        :wasmUrl              (pdf-asset "wasm/")
                                        :iccUrl               (pdf-asset "iccs/")
                                        ;; true (pdf.js default) JIT-compiles Type-4 (PostScript) shading
                                        ;; functions instead of interpreting them — a large speedup on
                                        ;; shaded/mesh figures. The renderer already permits unsafe-eval
                                        ;; (ADR-0013) and is sandboxed, so this is low marginal risk.
                                        :isEvalSupported      true})
                   (.-promise))))))

(defn- page-sizes
  "One lightweight pass over the document → [[w h]…] intrinsic (scale-1) page sizes."
  [^js doc]
  (let [n (.-numPages doc)]
    (-> (js/Promise.all
         (into-array
          (for [i (range 1 (inc n))]
            (-> (.getPage doc i)
                (.then (fn [^js pg] (let [vp (.getViewport pg #js {:scale 1})]
                                  #js [(.-width vp) (.-height vp)])))))))
        (.then (fn [arr] (mapv (fn [a] [(aget a 0) (aget a 1)]) (array-seq arr)))))))

;; ---- DOM helpers --------------------------------------------------------------------------------
(defn- scroller-of ^js [^js node] (.closest node ".vv-content"))

(defn- toggle-class! [^js el cls on?]
  (if on? (.add (.-classList el) cls) (.remove (.-classList el) cls)))

(defn- sheet-of
  "Page `idx`'s SHEET: the page-sized box that hosts its canvas, text, link and status layers. The placeholder
   `.vv-pdf-page#vv-pdf-page-N` around it carries the layout (and the Contents anchor id); in the continuous
   document layout the sheet fills it exactly, while in the slides layout (ADR-0045) the placeholder becomes a
   full-viewport slot and the sheet sits centred inside it — which is why the layers never attach to the
   placeholder itself."
  ^js [state idx]
  (nth (:sheet-divs @state) idx nil))

(defn- slides? [state] (= :slides (:layout @state)))

(defn- page-scale
  "The render scale of page `idx`: the document scale, or — in the slides layout — that page's own slide fit."
  [state idx]
  (if (slides? state)
    (nth (:scales @state) idx (:scale @state))
    (:scale @state)))

(defn- apply-document-layout!
  "Continuous layout: placeholders sized to the pages at the document :scale, stacked with `gap` (preallocates
   exact heights → no scroll jank); the sheets fill them (their inline sizes cleared)."
  [state]
  (let [{:keys [sizes scale page-divs sheet-divs]} @state
        rects (layout/page-rects sizes scale gap)]
    (swap! state assoc :rects rects)
    (dotimes [i (count page-divs)]
      (let [^js div   (nth page-divs i)
            ^js sheet (nth sheet-divs i)
            {:keys [height width]} (nth rects i)]
        (set! (.. div -style -height) (str height "px"))
        (set! (.. div -style -width) (str width "px"))
        (set! (.. sheet -style -height) "")
        (set! (.. sheet -style -width) "")))))

(defn- apply-slides-layout!
  "Slides layout (ADR-0045): every placeholder is a SLOT exactly the scroller's client size, the slots stack
   with no gap, and each sheet is sized to its page at that page's own fit (pdf-layout/slide-scale; no margin
   when presenting) and centred by CSS. Measured after the vv-pdf-slides class is on, so the hidden scrollbar is
   already excluded from the client size."
  [state]
  (let [{:keys [container sizes page-divs sheet-divs presenting?]} @state
        ^js scroller (scroller-of container)
        sw     (max 1 (if scroller (.-clientWidth scroller) 1))
        sh     (max 1 (if scroller (.-clientHeight scroller) 1))
        scales (layout/slide-scales sizes sw sh (if presenting? 0 slides-margin))
        rects  (layout/slot-rects (count sizes) sw sh)]
    (swap! state assoc :rects rects :scales scales :slot [sw sh])
    (dotimes [i (count page-divs)]
      (let [^js div   (nth page-divs i)
            ^js sheet (nth sheet-divs i)
            [pw ph]   (nth sizes i)
            sc        (nth scales i)]
        (set! (.. div -style -width) (str sw "px"))
        (set! (.. div -style -height) (str sh "px"))
        (set! (.. sheet -style -width) (str (* pw sc) "px"))
        (set! (.. sheet -style -height) (str (* ph sc) "px"))))))

(defn- apply-layout!
  "Size every placeholder (and, in the slides layout, every sheet) for the current layout, then re-measure the
   scroll-spy offsets of the outline anchors. Heights are preallocated here and the offset formula is
   scroll-invariant, so measurement is correct even before canvases paint. This one hook covers mount (via
   build-placeholders!, :toc-ids still empty → measures nothing), zoom/resize/layout switches (via relayout!) and
   live refresh (replace-doc!)."
  [state]
  (if (slides? state) (apply-slides-layout! state) (apply-document-layout! state))
  (when-let [^js scroller (scroller-of (:container @state))]
    (toc/refresh! scroller (:toc-ids @state))))

(defn- make-page!
  "One page placeholder `.vv-pdf-page#vv-pdf-page-<i+1>[data-pdf-idx=i]` with its inner `.vv-pdf-sheet`.
   Returns [page sheet]."
  [i]
  (let [div   (js/document.createElement "div")
        sheet (js/document.createElement "div")]
    (set! (.-className div) "vv-pdf-page")
    (set! (.-id div) (str "vv-pdf-page-" (inc i)))
    (set! (.. div -dataset -pdfIdx) (str i))
    (set! (.-className sheet) "vv-pdf-sheet")
    (.appendChild div sheet)
    [div sheet]))

(defn- build-placeholders!
  "Create every page's placeholder + sheet (one DocumentFragment append → one layout), then size them."
  [state]
  (let [{:keys [container sizes]} @state
        frag (js/document.createDocumentFragment)
        n    (count sizes)]
    (loop [i 0, pages (transient []), sheets (transient [])]
      (if (< i n)
        (let [[div sheet] (make-page! i)]
          (.appendChild frag div)
          (recur (inc i) (conj! pages div) (conj! sheets sheet)))
        (do (.appendChild container frag)
            (swap! state assoc :page-divs (persistent! pages) :sheet-divs (persistent! sheets)))))
    (apply-layout! state)))

;; ---- per-page render: canvas + text + link layers ----------------------------------------------
(defn- clear-status! [^js div]
  (when-let [^js s (.querySelector div ".vv-pdf-status")] (.remove s)))

(defn- show-status!
  "Overlay a status note (rendering… / failed+retry) centered on a page placeholder."
  [^js div cls text on-click]
  (clear-status! div)
  (let [s (js/document.createElement "div")]
    (set! (.-className s) (str "vv-pdf-status " cls))
    (set! (.-textContent s) text)
    (when on-click (.addEventListener s "click" on-click))
    (.appendChild div s)))

(defn- render-page! [state idx]
  (let [{:keys [doc page-divs rendered destroyed? gen]} @state]
    (when (and (not destroyed?) doc (< idx (count page-divs)) (not (contains? rendered idx)))
      (swap! state update :rendered conj idx)
      (-> (.getPage ^js doc (inc idx))
          (.then (fn [^js pg]
                   ;; bail if the controller was torn down OR a rescale bumped the generation while getPage
                   ;; was in flight — a stale resolve would otherwise delete the canvas a re-render just made
                   (when (and (not (:destroyed? @state)) (= gen (:gen @state)))
                     (let [^js div (sheet-of state idx)
                           scale   (page-scale state idx)
                           vp      (.getViewport pg #js {:scale scale})
                           ;; the screen's DPR, capped by the per-canvas device-pixel budget (an extreme zoom
                           ;; or a fullscreen slide on a HiDPI display must not allocate a gigapixel canvas)
                           dpr     (layout/budget-dpr (.-width vp) (.-height vp)
                                                      (or (.-devicePixelRatio js/window) 1))
                           canvas  (js/document.createElement "canvas")
                           ;; willReadFrequently → a CPU-backed 2D surface on the normal raster path; avoids
                           ;; the GPU "blank canvas until a forced repaint" compositing bug (Linux/Electron)
                           ctx     (.getContext canvas "2d" #js {:willReadFrequently true})]
                       ;; DOUBLE-BUFFERED: rasterize into this DETACHED canvas and swap it in only once it is
                       ;; complete. A re-render — after a zoom, a re-fit, a DPR change, or a live refresh — so
                       ;; keeps showing the previous raster (CSS-stretched to the new box: .vv-pdf-canvas is
                       ;; 100% × 100% of its sheet) instead of flashing white while pdf.js paints.
                       (clear-status! div)
                       (set! (.-className canvas) "vv-pdf-canvas")
                       (set! (.-width canvas) (js/Math.round (* (.-width vp) dpr)))
                       (set! (.-height canvas) (js/Math.round (* (.-height vp) dpr)))
                       (let [task (.render pg #js {:canvasContext ctx :viewport vp
                                                   :transform #js [dpr 0 0 dpr 0 0]})
                             ;; a heavy page rasterizes on the main thread for a long time and would look
                             ;; frozen/blank — show a "rendering…" hint, but only once it's slow enough to
                             ;; matter (a fast page settles before this fires, so it never flashes).
                             slow (js/setTimeout
                                   #(when (get-in @state [:tasks idx])
                                      (show-status! div "vv-pdf-status-rendering" "rendering…" nil))
                                   400)]
                         (swap! state update :tasks assoc idx task)
                         (-> (.-promise task)
                             (.then (fn []
                                      (js/clearTimeout slow)
                                      ;; only act if THIS task is still the live one (a rescale may have
                                      ;; replaced it with a newer task for the same idx)
                                      (when (identical? (get-in @state [:tasks idx]) task)
                                        (clear-status! div)
                                        (swap! state update :tasks dissoc idx)
                                        ;; the swap: the finished raster replaces the stale one in place
                                        (if-let [^js old (.querySelector div "canvas.vv-pdf-canvas")]
                                          (.replaceWith old canvas)
                                          (.prepend div canvas))
                                        (build-text-layer! state idx pg vp)
                                        (build-link-layer! state idx pg vp))))
                             (.catch (fn [^js err]
                                       (js/clearTimeout slow)
                                       (when (identical? (get-in @state [:tasks idx]) task)
                                         (swap! state update :tasks dissoc idx))
                                       (if (= "RenderingCancelledException" (.-name err))
                                         (clear-status! div)   ; expected — scrolled away / rescaled
                                         (do (js/console.error "[pdf] page" (inc idx) "render failed:" err)
                                             (when (= gen (:gen @state))
                                               (swap! state update :rendered disj idx)   ; allow a retry
                                               (show-status! div "vv-pdf-status-error"
                                                             "⚠ failed to render — click to retry"
                                                             (fn [] (clear-status! div) (render-page! state idx))))))))))))))
          (.catch (fn [^js err]
                    (when (= gen (:gen @state)) (swap! state update :rendered disj idx))
                    (js/console.error "[pdf] page" (inc idx) "load failed:" err)
                    (when (and (= gen (:gen @state)) (not (:destroyed? @state)))
                      (when-let [^js div (sheet-of state idx)]
                        (show-status! div "vv-pdf-status-error" "⚠ failed to load — click to retry"
                                      (fn [] (clear-status! div) (render-page! state idx)))))))))))

(defn- release-canvas!
  "Drop a page's canvas when it scrolls out of the overscan band (bounds memory on large PDFs). The text
   + link layers stay so find/selection still cover the page; the canvas re-renders on re-intersect."
  [state idx]
  (when (contains? (:rendered @state) idx)
    (when-let [task (get-in @state [:tasks idx])]
      (try (.cancel task) (catch :default _ nil))
      (swap! state update :tasks dissoc idx))
    (when-let [^js sheet (sheet-of state idx)]
      (when-let [^js canvas (.querySelector sheet "canvas.vv-pdf-canvas")] (.remove canvas)))
    (swap! state update :rendered disj idx)))

;; :text-built maps page index → the Promise of that page's text-layer render, NOT just the index.
;;
;; It used to be a set, conj'd SYNCHRONOUSLY here — before .render() resolved. So a second ensure-all-text!
;; arriving while the first was still rendering saw every index already "built", resolved immediately, and
;; in-page find then searched empty text layers and reported nothing. Since find awaits ensure-active! on
;; every query, that was reachable by simply typing at a normal speed into the find box over a PDF.
;; Holding the promise makes the second caller await the first caller's work.
(defn- build-text-layer! [state idx ^js pg ^js vp]
  (if-let [p (get (:text-built @state) idx)]
    p
    (let [^js div (sheet-of state idx)
          tdiv    (js/document.createElement "div")]
      (set! (.-className tdiv) "vv-pdf-text")
      (set! (.. tdiv -style -width) (str (.-width vp) "px"))
      (set! (.. tdiv -style -height) (str (.-height vp) "px"))
      ;; pdf.js 5.x sizes/positions every text span with calc(var(--total-scale-factor) * …); if that custom
      ;; property is undefined the declarations are invalid-at-computed-value (font-size→inherited, marked-content
      ;; left/top→auto), so the transparent spans land at the wrong boxes and ::highlight(vv-find) paints over
      ;; them in the wrong place. Set the name pdf.js 5.x actually reads; keep the old --scale-factor for back-compat.
      (doto (.-style tdiv)
        (.setProperty "--total-scale-factor" (str (page-scale state idx)))
        (.setProperty "--scale-factor"       (str (page-scale state idx))))
      (.appendChild div tdiv)
      (let [p (-> (.render (js/Reflect.construct (.-TextLayer ^js @pdfjs)
                                                 #js [#js {:textContentSource (.streamTextContent pg)
                                                           :container tdiv :viewport vp}]))
                  ;; a failed render must not leave a poisoned entry: drop it so a later ensure retries
                  (.catch (fn [_] (swap! state update :text-built dissoc idx) nil)))]
        (swap! state update :text-built assoc idx p)
        p))))

(defn- ensure-text-at!
  "A Promise that resolves once page `idx`'s text layer EXISTS. Returns the in-flight render when one is
   already running, so concurrent callers await it instead of assuming it is finished."
  [state idx]
  (if (or (:destroyed? @state) (not (:doc @state)))
    (js/Promise.resolve nil)
    (or (get (:text-built @state) idx)
        (-> (.getPage ^js (:doc @state) (inc idx))
            (.then (fn [^js pg]
                     (when-not (:destroyed? @state)
                       (build-text-layer! state idx pg (.getViewport pg #js {:scale (page-scale state idx)})))))
            (.catch (fn [_] nil))))))

(defn- place! [^js el ^js vp rect]
  (let [vr (.convertToViewportRectangle vp rect)
        x  (min (aget vr 0) (aget vr 2))
        y  (min (aget vr 1) (aget vr 3))
        w  (js/Math.abs (- (aget vr 2) (aget vr 0)))
        h  (js/Math.abs (- (aget vr 3) (aget vr 1)))
        st (.-style el)]
    (set! (.-left st) (str x "px"))
    (set! (.-top st) (str y "px"))
    (set! (.-width st) (str w "px"))
    (set! (.-height st) (str h "px"))))

(defn- make-link [state ^js a ^js vp]
  (let [rect (.-rect a) url (.-url a) dest (.-dest a)]
    (cond
      (and url (string? url))
      (doto (js/document.createElement "a")
        (-> .-className (set! "vv-pdf-link"))
        (-> .-href (set! url))
        (place! vp rect)
        (.addEventListener "click" (fn [e] (.preventDefault e) (rf/dispatch [:shell/open-external url]))))

      dest
      (doto (js/document.createElement "a")
        (-> .-className (set! "vv-pdf-link"))
        (-> .-href (set! "#"))
        (place! vp rect)
        (.addEventListener "click"
                           (fn [e]
                             (.preventDefault e)
                             (-> (resolve-dest (:doc @state) dest)
                                 (.then (fn [page] (when page (scroll-to-page! state page))))))))
      :else nil)))

(defn- build-link-layer! [state idx ^js pg ^js vp]
  (let [^js div (sheet-of state idx)
        adiv    (js/document.createElement "div")]
    ;; idempotent: a page whose canvas was released and later re-rendered keeps its text layer but must not
    ;; stack a second link layer over the first
    (when-let [^js old (.querySelector div ".vv-pdf-anno")] (.remove old))
    (set! (.-className adiv) "vv-pdf-anno")
    (set! (.. adiv -style -width) (str (.-width vp) "px"))
    (set! (.. adiv -style -height) (str (.-height vp) "px"))
    (.appendChild div adiv)
    (-> (.getAnnotations pg #js {:intent "display"})
        (.then (fn [annos]
                 (when-not (:destroyed? @state)
                   (doseq [^js a (array-seq annos)]
                     (when (= "Link" (.-subtype a))
                       (when-let [link (make-link state a vp)] (.appendChild adiv link)))))))
        (.catch (fn [_] nil)))))

(defn- resolve-dest
  "Resolve a pdf.js destination (explicit array or named string) → 1-based page number (Promise)."
  [^js doc dest]
  (-> (if (string? dest) (.getDestination doc dest) (js/Promise.resolve dest))
      (.then (fn [^js explicit]
               (when (and explicit (.-length explicit))
                 (-> (.getPageIndex doc (aget explicit 0))
                     (.then (fn [idx] (inc idx)))))))
      (.catch (fn [_] nil))))

(defn- scroll-to-page!
  "Show 1-based `page`: in the slides layout a JUMP (recorded in the GoBack/GoForward history), else scroll its
   top to the viewport top."
  [state page]
  (if (slides? state)
    (command! state [:jump (dec page)])
    (let [{:keys [rects container]} @state
          scroller (scroller-of container)
          idx      (dec page)]
      (when (and scroller (<= 0 idx) (< idx (count rects)))
        (set! (.-scrollTop scroller) (+ (.-offsetTop container) (:top (nth rects idx))))))))

;; ---- outline → :doc/toc (async dest resolution, then dispatch) ----------------------------------
(defn- walk-outline [^js doc items level]
  (-> (js/Promise.all
       (into-array
        (map (fn [^js item]
               (-> (resolve-dest ^js doc (.-dest item))
                   (.then (fn [page]
                            (-> (walk-outline doc (.-items item) (inc level))
                                (.then (fn [children]
                                         (let [title (str (.-title item))
                                               self  (when (and page (seq title))
                                                       [{:level level :text title :id (str "vv-pdf-page-" page)}])]
                                           (vec (concat self children))))))))))
             (array-seq items))))
      (.then (fn [arr] (vec (mapcat identity (array-seq arr)))))))

(defn- publish-outline!
  "Number a resolved TOC (1, 2, 2.1, …; number-outline preserves :id so the scroll-spy is unaffected),
   dispatch it as :doc/toc, and measure the anchors now. Shared by the getOutline and font-size paths."
  [state path toc]
  (when (and (seq toc) (not (:destroyed? @state)))
    (let [numbered (layout/number-outline (vec toc))]
      (rf/dispatch [:pdf/outline path numbered])
      (swap! state assoc :toc-ids (mapv :id numbered))
      (when-let [^js scroller (scroller-of (:container @state))]
        (toc/refresh! scroller (:toc-ids @state))))))

(def ^:private outline-scan-pages 40)   ; bound the font-size-fallback scan for large PDFs

(defn- page-items
  "Promise<[normalized-item]> for 1-based page `pnum` via getTextContent (ir.frontend.pdf/normalize-item)."
  [^js doc pnum]
  (-> (.getPage doc pnum)
      (.then (fn [^js pg] (.getTextContent pg)))
      (.then (fn [^js tc] (mapv pdf-fe/normalize-item (array-seq (.-items tc)))))
      (.catch (fn [_] []))))

(defn- extract-text-outline!
  "Fallback outline for a PDF with no getOutline: extract text over a bounded page scan, build the common IR
   (ir.frontend.pdf/doc->ir), and derive a font-size heading outline keyed by page anchor."
  [state ^js doc path]
  (let [n (min outline-scan-pages (.-numPages doc))]
    (-> (js/Promise.all (into-array (map (fn [p] (-> (page-items doc p) (.then (fn [items] [p items]))))
                                         (range 1 (inc n)))))
        (.then (fn [^js pages]
                 (when-not (:destroyed? @state)
                   (publish-outline! state path (pdf-fe/outline (pdf-fe/doc->ir (array-seq pages)))))))
        (.catch (fn [_] nil)))))

(defn- extract-outline! [state ^js doc path]
  (-> (.getOutline doc)
      (.then (fn [^js outline]
               (if (and outline (pos? (.-length outline)) (not (:destroyed? @state)))
                 (-> (walk-outline doc outline 1)
                     (.then (fn [toc] (publish-outline! state path toc))))       ; built-in outline
                 (when-not (:destroyed? @state)
                   (extract-text-outline! state doc path)))))                    ; font-size fallback
      (.catch (fn [_] nil))))

;; ---- IntersectionObserver: render visible, release offscreen ------------------------------------
(defn- overscan-of
  "Viewport-heights of render overscan for the current layout (slides: exactly the neighbouring slots)."
  [state]
  (if (slides? state) slides-overscan overscan))

(defn- observe! [state]
  (let [{:keys [container page-divs]} @state
        scroller (scroller-of container)
        margin   (str (js/Math.round (* (overscan-of state) 100)) "% 0px")
        obs (js/IntersectionObserver.
             (fn [entries _]
               (doseq [^js e (array-seq entries)]
                 (let [idx (js/parseInt (.. e -target -dataset -pdfIdx) 10)]
                   (if (.-isIntersecting e)
                     (render-page! state idx)
                     (release-canvas! state idx)))))
             #js {:root scroller :rootMargin margin :threshold 0})]
    (doseq [^js div page-divs] (.observe obs div))
    (swap! state assoc :observer obs)))

(defn- apply-fit!
  "Set :scale from a fit mode (:width | :page | :actual) against the scroller's content box, and report the
   resolved scale to app-db so the zoom bar shows the live % while fitting (re-frame's = check dedups the
   echo render, so this can't loop)."
  [state mode]
  (let [{:keys [container sizes]} @state
        scroller (scroller-of container)]
    (when (and scroller (seq sizes))
      (let [[pw ph] (first sizes)
            cw (max 1 (- (.-clientWidth scroller) (* 2 gap)))
            ch (max 1 (.-clientHeight scroller))
            sc (layout/clamp-zoom (layout/fit-scale cw ch pw ph mode))]
        (swap! state assoc :scale sc :fit mode)
        (rf/dispatch [:pdf/scale-resolved sc])))))

(defn- render-visible!
  "Render every page currently in (or near) the viewport, computed directly from geometry (idempotent via
   the :rendered guard). Used after a resize/rescale so visible pages re-render deterministically instead of
   waiting on an IntersectionObserver re-fire that can miss or mis-report mid-layout-change."
  [state]
  (let [{:keys [container rects]} @state
        scroller (scroller-of container)]
    (when (and scroller (seq rects))
      (let [doc-top (- (.-scrollTop scroller) (.-offsetTop container))
            vh      (.-clientHeight scroller)
            [lo hi] (layout/visible-range rects doc-top vh (* (overscan-of state) vh))]
        (when (<= 0 lo)
          (doseq [idx (range lo (inc hi))] (render-page! state idx)))))))

(defn- strip-layers!
  "Remove a sheet's scale-dependent layers (text, links, status) — the next render rebuilds them. The canvas
   stays: CSS stretches it to the sheet's new size until its double-buffered re-render swaps in."
  [^js sheet]
  (doseq [^js el (array-seq (.querySelectorAll sheet ".vv-pdf-text, .vv-pdf-anno, .vv-pdf-status"))]
    (.remove el)))

(defn- drop-offscreen-canvases!
  "After a relayout, drop every canvas outside the (overscanned) visible band: the IntersectionObserver's
   release path only knows pages in :rendered, which a rescale just reset, so stale rasters off-screen would
   otherwise linger and pin memory."
  [state]
  (let [{:keys [container rects sheet-divs]} @state
        scroller (scroller-of container)]
    (when (and scroller (seq rects))
      (let [doc-top (- (.-scrollTop scroller) (.-offsetTop container))
            vh      (.-clientHeight scroller)
            [lo hi] (layout/visible-range rects doc-top vh (* (overscan-of state) vh))]
        (dotimes [i (count sheet-divs)]
          (when (or (neg? lo) (< i lo) (> i hi))
            (when-let [^js c (.querySelector (nth sheet-divs i) "canvas.vv-pdf-canvas")] (.remove c))))))))

(defn- current-anchor
  "Where the reader is: in the slides layout the current slide's top, else the page at the viewport top plus how
   far down it the viewport top sits (pdf-layout/anchor-of)."
  [state]
  (let [{:keys [container rects]} @state
        ^js scroller (scroller-of container)]
    (cond
      (slides? state) {:idx (:current @state 0) :frac 0}
      (and scroller (seq rects)) (layout/anchor-of rects (- (.-scrollTop scroller) (.-offsetTop container)))
      :else nil)))

(defn- reobserve!
  "Recreate the IntersectionObserver over the current placeholders (its rootMargin depends on the layout, and a
   fresh observer reports every target's initial state)."
  [state]
  (when-let [^js o (:observer @state)] (.disconnect o))
  (observe! state))

(defn- relayout!
  "Re-layout placeholders + re-render the visible pages for the current layout/scale, putting `anchor`
   ({:idx :frac}, see current-anchor) back at the viewport top — so a zoom, a re-fit, a DPR change, a resize or
   a layout switch keeps the same line (or slide) in view. Visible pages keep their (stretched) canvases until
   the double-buffered re-render swaps fresh ones in; off-screen canvases are dropped."
  [state anchor]
  (let [{:keys [container]} @state
        ^js scroller (scroller-of container)]
    (doseq [[_ task] (:tasks @state)] (try (.cancel task) (catch :default _ nil)))
    ;; bump :gen so any in-flight getPage/render from the OLD layout bails instead of clobbering the new one
    (swap! state #(-> % (assoc :tasks {} :rendered #{} :text-built {}) (update :gen inc)))
    (doseq [^js sheet (:sheet-divs @state)] (strip-layers! sheet))
    (apply-layout! state)
    (when (and scroller anchor)
      (set! (.-scrollTop scroller) (+ (.-offsetTop container) (layout/anchor-top (:rects @state) anchor))))
    (drop-offscreen-canvases! state)
    (reobserve! state)
    (render-visible! state)))   ; deterministic re-render — don't rely on the IO re-fire after re-observe

(defn- rescale!
  "relayout! at the reader's current anchor (zoom / re-fit / DPR change)."
  [state]
  (relayout! state (current-anchor state)))

(defn- watch-dpr!
  "Re-render when the device-pixel ratio changes — the window moved to a display with a different scale (a
   projector, an external monitor) or the OS scale changed. Canvases are rasterized at a fixed DPR, so they
   would otherwise turn blurry or needlessly heavy. A `(resolution: Xdppx)` media query fires `change` exactly
   when the ratio leaves X; it is re-armed for the new ratio after each change."
  [state]
  (when (and (exists? js/matchMedia) (not (:destroyed? @state)))
    (let [^js mq (js/matchMedia (str "(resolution: " (or (.-devicePixelRatio js/window) 1) "dppx)"))
          handler (fn [_]
                    (when-not (:destroyed? @state)
                      (rescale! state)
                      (watch-dpr! state)))]
      (.addEventListener mq "change" handler #js {:once true})
      (swap! state assoc :dpr-watch [mq handler]))))

;; ---- slides layout: current slide, navigation, listeners (ADR-0045) ------------------------------------
(defn- report!
  "Tell app-db where the slide show stands — [:ui :slides] via :slides/changed: the deck position of the current
   page plus the layout — coalesced to one dispatch per animation frame however many scroll events arrive."
  [state]
  (when-not (:report-raf @state)
    (swap! state assoc :report-raf
           (js/requestAnimationFrame
            (fn [_]
              (swap! state assoc :report-raf nil)
              (when-not (:destroyed? @state)
                (let [{:keys [deck path layout current doc-gen]} @state]
                  (rf/dispatch [:slides/changed (assoc (deck/position deck (or current 0))
                                                       :path path :layout layout :doc-gen (or doc-gen 0))]))))))))

(defn- slot-height [state] (second (:slot @state [1 1])))

(defn- go-to-page!
  "Show slide `idx` (clamped) at once: its slot's top at the scroller top. Instant by design — a slide show
   flips, it does not glide — and the neighbouring slots are already rendered (slides-overscan)."
  [state idx]
  (let [{:keys [container deck]} @state
        ^js scroller (scroller-of container)
        idx (deck/clamp-page deck idx)]
    (when scroller
      (set! (.-scrollTop scroller) (+ (.-offsetTop container) (* idx (slot-height state)))))
    (swap! state assoc :current idx)
    (report! state)))

(defn command!
  "Apply a slide-navigation `op` (any vinary.slides.deck/step op: :next :prev :next-frame :prev-frame :first
   :last [:goto i] [:goto-label s] [:jump i] :back :forward) in the slides layout. Jumps are recorded in the
   GoBack/GoForward history; sequential steps are not. No-op in the document layout."
  [state op]
  (when (and (slides? state) (not (:destroyed? @state)))
    (let [nav  {:page (:current @state 0) :back (:back @state []) :fwd (:fwd @state [])}
          nav' (deck/step (:deck @state) nav op)]
      (swap! state assoc :back (:back nav') :fwd (:fwd nav'))
      (go-to-page! state (:page nav')))))

(defn- track-current!
  "As the scroller moves (find centring a match, a Contents click, keyboard), keep :current = the slot at the
   top — rAF-coalesced so a scroll burst costs one measurement per frame."
  [state]
  (when-not (:scroll-raf @state)
    (swap! state assoc :scroll-raf
           (js/requestAnimationFrame
            (fn [_]
              (swap! state assoc :scroll-raf nil)
              (when (and (slides? state) (not (:destroyed? @state)))
                (let [{:keys [container sizes]} @state
                      ^js scroller (scroller-of container)
                      idx (layout/slot-at (- (.-scrollTop scroller) (.-offsetTop container))
                                          (slot-height state) (count sizes))]
                  (when (not= idx (:current @state))
                    (swap! state assoc :current idx)
                    (report! state)))))))))

(defn- snap!
  "At the END of a scroll (a smooth Contents jump, find's centring, a stray pixel scroll) settle exactly on the
   slot nearest the top. Never mid-scroll: snapping during a smooth scroll would fight it and land on the wrong
   slide."
  [state]
  (when (and (slides? state) (not (:destroyed? @state)))
    (let [{:keys [container sizes]} @state
          ^js scroller (scroller-of container)
          idx    (layout/slot-at (- (.-scrollTop scroller) (.-offsetTop container)) (slot-height state) (count sizes))
          target (+ (.-offsetTop container) (* idx (slot-height state)))]
      (when (> (js/Math.abs (- (.-scrollTop scroller) target)) 0.5)
        (set! (.-scrollTop scroller) target))
      (swap! state assoc :current idx)
      (report! state))))

(defn- normalize-wheel
  "pdf.js's normalizeWheelEventDelta, signed TOWARDS THE NEXT SLIDE: the length of the (dx, dy) vector, positive
   when scrolling down/right, in notch units — pixel deltas ÷ 1200 (30 px per line × 40 lines), line deltas ÷ 30."
  [^js e]
  (let [dx    (.-deltaX e)
        dy    (.-deltaY e)
        len   (js/Math.hypot dx dy)
        angle (js/Math.atan2 dy dx)
        d     (if (and (< (* -0.25 js/Math.PI) angle) (< angle (* 0.75 js/Math.PI))) len (- len))]
    (case (.-deltaMode e)
      0 (/ d 1200)
      1 (/ d 30)
      d)))

(defn- on-wheel! [state ^js e]
  ;; Ctrl/Meta+wheel is zoom elsewhere in the app — leave it alone
  (when (and (slides? state) (not (.-ctrlKey e)) (not (.-metaKey e)))
    (let [{st :state op :op} (deck/wheel-step (:wheel @state) (normalize-wheel e) (js/performance.now))]
      (swap! state assoc :wheel st)
      (when op (command! state op)))))

(defn- scroll-delegate
  "The content-scroll delegate of the slides layout (vinary.renderer.scroll): every keymap's scroll command —
   arrows, PageUp/PageDown (Shift = a frame), Home/End, Vim j/k/h/l/C-d/C-u/C-f/C-b/gg/G, Emacs C-v/M-v/C-n/C-p —
   becomes the slide op deck/scroll->op names, run through :slides/command so a typed slide number or (while
   presenting) a blanked screen sees it first. Always claims the command: a pixel scroll would only be undone by
   the snap."
  [_state]
  (fn [opts]
    (when-let [op (deck/scroll->op opts)]
      (rf/dispatch [:slides/command op]))
    true))

(defn- attach-slides-listeners!
  "While the slides layout is active: track the current slot on scroll, snap at scroll end (`scrollend`, plus a
   150 ms quiet-period fallback), turn the wheel into slide flips (the scroller itself is overflow:hidden in
   this layout, so the wheel never scrolls it natively), and claim the keyboard's scroll commands (the scroll
   delegate)."
  [state]
  (when-not (:slides-listeners @state)
    (when-let [^js scroller (scroller-of (:container @state))]
      (let [on-scroll (fn [_]
                        (track-current! state)
                        (when-let [t (:snap-timer @state)] (js/clearTimeout t))
                        (swap! state assoc :snap-timer (js/setTimeout #(snap! state) 150)))
            on-end    (fn [_] (snap! state))
            on-wheel  (fn [e] (on-wheel! state e))
            delegate  (scroll-delegate state)]
        (.addEventListener scroller "scroll" on-scroll #js {:passive true})
        (.addEventListener scroller "scrollend" on-end)
        (.addEventListener scroller "wheel" on-wheel #js {:passive true})
        (scroll/set-delegate! delegate)
        (swap! state assoc :slides-listeners [scroller on-scroll on-end on-wheel delegate])))))

(defn- detach-slides-listeners! [state]
  (when-let [[^js scroller on-scroll on-end on-wheel delegate] (:slides-listeners @state)]
    (.removeEventListener scroller "scroll" on-scroll)
    (.removeEventListener scroller "scrollend" on-end)
    (.removeEventListener scroller "wheel" on-wheel)
    (scroll/clear-delegate! delegate))
  (when-let [t (:snap-timer @state)] (js/clearTimeout t))
  (swap! state assoc :slides-listeners nil :snap-timer nil))

(defn- set-layout!
  "Switch between the continuous document layout and the slides layout, keeping the reader's page: entering
   slides shows the page that was at the viewport top; leaving shows the current slide's page at the top."
  [state layout]
  (when (not= layout (:layout @state))
    (let [page (or (:idx (current-anchor state)) 0)]
      (swap! state assoc :layout layout :current page)
      ;; the class goes on FIRST: CSS hides the scroller's scrollbar in the slides layout, and the slot geometry
      ;; must be measured without it
      (toggle-class! (:container @state) "vv-pdf-slides" (= layout :slides))
      (if (= layout :slides)
        (attach-slides-listeners! state)
        (do (detach-slides-listeners! state)
            (when-let [mode (:fit @state)] (apply-fit! state mode))))
      (relayout! state {:idx page :frac 0})
      (report! state))))

(defn- refit!
  "Re-resolve a fit-mode scale against the (resized) scroller and re-render only if it changed. No-op for
   manual-zoom PDFs (no :fit) and in the slides layout (slides fit themselves)."
  [state]
  (when-let [mode (when-not (slides? state) (:fit @state))]
    (let [old (:scale @state)]
      (apply-fit! state mode)
      (when (not= (:scale @state) old) (rescale! state)))))

(defn- observe-resize!
  "Re-fit the PDF when the scroller (content pane) resizes — e.g. the window grows/shrinks — so the page
   size tracks the window. Debounced so a drag-resize doesn't thrash re-render; only fit-mode PDFs re-fit."
  [state]
  (when (and (exists? js/ResizeObserver) (not (:resize-observer @state)))
    (when-let [scroller (scroller-of (:container @state))]
      (let [timer (atom nil)
            obs   (js/ResizeObserver.
                   (fn [_]
                     (when-let [t @timer] (js/clearTimeout t))
                     ;; slides: resize the slots NOW (cheap style writes; the canvases CSS-stretch), so the next
                     ;; slide never peeks in while a window grows or enters fullscreen — the sharp re-render waits
                     ;; for the debounce below
                     (when (and (slides? state) (not (:destroyed? @state)))
                       (apply-layout! state)
                       (when-let [^js sc (scroller-of (:container @state))]
                         (set! (.-scrollTop sc) (+ (.-offsetTop (:container @state))
                                                   (* (:current @state 0) (slot-height state))))))
                     (reset! timer (js/setTimeout
                                    (fn []
                                      (when-not (:destroyed? @state)
                                        (if (slides? state)
                                          (relayout! state (current-anchor state))
                                          (do (refit! state) (render-visible! state)))))
                                    120))))]
        (.observe obs scroller)
        (swap! state assoc :resize-observer obs)))))

;; ---- opt-in reflow: re-extract the current PDF's text as reflowable prose HTML (augments the canvas) ----
(defonce ^:private current-doc (atom nil))   ; {:doc :path} of the most-recently-mounted PDF

;; blocks for the STREAMING reflow view, populated by the :pdf/reflow fx while the PDF is still mounted (so the
;; pdf.js doc is alive for text extraction); the streaming view drains from here after content-view swaps it in.
(defonce ^:private reflow-store (atom {}))                    ; path -> {:blocks :toc :assets}

(defn- compute-reflow!
  "Extract the currently-mounted PDF's text (bounded page scan) → PDF IR → reflow IR + page-anchored outline.
   Returns Promise<{:path :reflow-ir :toc}|nil> (nil when no PDF is mounted)."
  []
  (let [{d :doc path :path} @current-doc]
    (if d
      (let [n (min outline-scan-pages (.-numPages ^js d))]
        (-> (js/Promise.all (into-array (map (fn [p] (-> (page-items d p) (.then (fn [items] [p items]))))
                                             (range 1 (inc n)))))
            (.then (fn [^js pages]
                     (let [pdf-ir (pdf-fe/doc->ir (array-seq pages))]
                       {:path path :reflow-ir (pdf-fe/reflow-ir pdf-ir) :toc (pdf-fe/outline pdf-ir)})))
            (.catch (fn [_] nil))))
      (js/Promise.resolve nil))))

(defn reflow-html!
  "Extract text from the currently-mounted PDF → reflowable prose HTML (ir.backend.html). Returns
   Promise<{:path :html}|nil>, keyed by the mounted doc's own :doc/path. The faithful pdf.js canvas render is
   untouched — reflow is a separate, additive view."
  []
  (-> (compute-reflow!)
      (.then (fn [res] (when res {:path (:path res) :html (ir-html/lower (:reflow-ir res))})))))

(defn reflow-blocks!
  "Block-provider for the PROGRESSIVE (streaming) reflow view: return the reflow IR's top-level blocks + the
   page-anchored Contents, as stored by the :pdf/reflow fx (computed while the doc was mounted). A 0-arg-style
   provider taking `path`; returns Promise<{:blocks :toc :assets}>."
  [path]
  (js/Promise.resolve (or (get @reflow-store path) {:blocks [] :toc [] :assets []})))

;; The reflow effect lives HERE (not in vinary.app.fx) so requiring it does not pull pdf.js — which touches
;; `document` at load — into the DOM-free :node-test build. pdf.cljs is loaded at renderer init (via ui.views).
(rf/reg-fx
 :pdf/reflow
 (fn [_]
   (-> (compute-reflow!)
       (.then (fn [res]
                (when res
                  ;; stash the IR blocks for the streaming view, and set the batch reflow HTML for the non-stream view
                  (swap! reflow-store assoc (:path res)
                         {:blocks (vec (node/children (:reflow-ir res))) :toc (:toc res) :assets []})
                  (let [html (ir-html/lower (:reflow-ir res))]
                    (when (seq html) (rf/dispatch [:pdf/reflowed (:path res) html]))))))
       (.catch (fn [_] nil)))))

;; ---- public API (driven by the Reagent pdf-view component) --------------------------------------
(defn prepare!
  "Load a PDF once, measure its intrinsic page sizes, and classify it as a slide deck (ADR-0045) — the evidence
   (Info/XMP creator, page mode, page labels) is read IN PARALLEL with the page measurement, so detection adds
   no round trip and the FIRST mount already knows whether to use the slides layout. Returns the DOM-free
   artifact {:doc :sizes :deck}, which may be leased across view mounts; `dispose-prepared!` owns the eventual
   pdf.js destruction. Detection runs here, in the renderer, on the loaded document — so it behaves identically
   for a local file, an ssh:// / sftp:// URI, an archive member or a web-linked PDF."
  [bytes]
  (-> (get-document bytes)
      (.then (fn [^js doc]
               (-> (js/Promise.all #js [(page-sizes doc) (evidence/gather doc)])
                   (.then (fn [^js results]
                            (let [sizes (aget results 0)]
                              {:doc   doc
                               :sizes sizes
                               :deck  (deck/deck (assoc (aget results 1) :sizes sizes))}))))))))

(defn- mark-deck!
  "Expose the classification on the view element (data-deck = source, or empty): CSS and DOM-level tests
   (incl. release builds, which hide re-frame) can read it."
  [^js container d]
  (set! (.. container -dataset -deck) (if (:deck? d) (name (:source d)) "")))

(defn dispose-prepared! [{:keys [doc]}]
  (when doc (try (.destroy ^js doc) (catch :default _ nil))))

(defn- wanted-layout
  "The layout `view-state` asks for, given this document's deck classification (vinary.slides.deck/
   effective-layout: presenting → slides; else the document view's explicit choice; else a detected deck opens as
   slides unless the preference says :document)."
  [deck view-state]
  (deck/effective-layout {:presenting? (boolean (:presenting? view-state))
                          :explicit    (:explicit view-state)
                          :deck?       (boolean (:deck? deck))
                          :open-as     (:open-as view-state)}))

(defn- slides-hook
  "The slides controls this controller registers with pdf-cache (a pdf.js-free seam): re-frame effects and the
   scroll delegate drive navigation through it without requiring this namespace."
  [state]
  {:command!  (fn [op] (command! state op))
   :slides?   (fn [] (and (slides? state) (not (:destroyed? @state))))
   :position  (fn [] (when (slides? state) (:current @state 0)))
   :snapshot  (fn [] (select-keys @state [:doc :sizes :deck :current :doc-gen :path :layout]))})

(defn mount-prepared!
  "Mount a leased `{doc sizes deck}` artifact into a fresh DOM surface, in the layout `view-state` asks for
   ({:scale :fit :invert? :explicit :open-as :presenting? :page}). Per-mount canvases, observers, tasks, and
   text/link layers are still recreated and torn down normally. A slides-layout mount opens on `:page` (a
   restored slide, ADR-0045 §Remembering the slide) or the first page."
  [container {:keys [doc sizes] :as prepared} path view-state]
  (let [d      (:deck prepared)
        layout (wanted-layout d view-state)
        state  (atom {:container container :path path :doc doc :owns-doc? false
                      :deck d :doc-gen 0 :layout layout
                      :presenting? (boolean (:presenting? view-state))
                      :current (deck/clamp-page d (or (:page view-state) 0))
                      :sizes sizes :rects [] :page-divs [] :sheet-divs [] :toc-ids []
                      :scale (layout/clamp-zoom (or (:scale view-state) 1.0))
                      :fit (:fit view-state) :invert? (boolean (:invert? view-state))
                      :rendered #{} :text-built {} :tasks {} :observer nil :destroyed? false :gen 0})
        ensure-fn (fn [] (ensure-all-text! state))
        hook      (slides-hook state)]
    (swap! state assoc :ensure-fn ensure-fn :hook hook)
    (cache/set-ensurer! ensure-fn)
    (cache/set-slides! hook)
    (toggle-class! container "vv-pdf-invert" (:invert? view-state))
    (toggle-class! container "vv-pdf-slides" (= layout :slides))   ; before any measurement (hides the scrollbar)
    (mark-deck! container d)
    (reset! current-doc {:doc doc :path path})
    (when (and (:fit view-state) (not= layout :slides)) (apply-fit! state (:fit view-state)))
    (build-placeholders! state)
    (when (= layout :slides)
      (go-to-page! state (:current @state))
      (attach-slides-listeners! state))
    (observe! state)
    (observe-resize! state)
    (watch-dpr! state)
    (extract-outline! state doc path)
    (report! state)
    state))

(defn mount!
  "Render `bytes` (a PDF) into `container` (a .vv-pdf-doc div inside .vv-content): `prepare!` followed by
   `mount-prepared!`, with the controller OWNING the prepared document (destroyed on unmount). Returns a Promise
   of the controller atom — pass it to update!/unmount!/ensure-all-text!. `view-state` = {:scale n :fit kw
   :invert? b}. (The pdf-view leases prepared artifacts through the warm cache instead; routing this entry
   point through the same two functions keeps the paths from drifting — it used to duplicate them and missed
   every later addition, e.g. deck detection.)"
  [container bytes path view-state]
  (-> (prepare! bytes)
      (.then (fn [prepared]
               (doto (mount-prepared! container prepared path view-state)
                 (swap! assoc :owns-doc? true))))))

(defn- reconcile-pages!
  "Grow or shrink the placeholder/sheet vectors (and the DOM) to `n` pages — a refreshed document may have
   gained or lost pages. Existing placeholders are kept, so their stretched canvases stay visible."
  [state n]
  (let [{:keys [container page-divs sheet-divs]} @state
        have (count page-divs)]
    (cond
      (< have n)
      (let [frag (js/document.createDocumentFragment)
            [pages sheets] (loop [i have, pages (transient page-divs), sheets (transient sheet-divs)]
                             (if (< i n)
                               (let [[div sheet] (make-page! i)]
                                 (.appendChild frag div)
                                 (recur (inc i) (conj! pages div) (conj! sheets sheet)))
                               [(persistent! pages) (persistent! sheets)]))]
        (.appendChild container frag)
        (swap! state assoc :page-divs pages :sheet-divs sheets))

      (> have n)
      (do (doseq [^js div (subvec page-divs n)] (.remove div))
          (swap! state assoc :page-divs (subvec page-divs 0 n) :sheet-divs (subvec sheet-divs 0 n)))

      :else nil)))

(defn replace-doc!
  "Swap a freshly prepared {:doc :sizes} into a MOUNTED controller in place — a live refresh (ADR-0045
   §Live refresh): no unmount, no DOM teardown, no scroll reset. Keeps the reading anchor (page + in-page
   fraction, clamped if the document shrank), keeps the visible canvases (CSS-stretched) until their
   double-buffered re-render swaps in, grows/shrinks the placeholders when the page count changed, and
   re-extracts the outline. The CALLER releases the previous artifact's lease afterwards — that is what
   destroys the old PDFDocumentProxy, never this function."
  [state {:keys [doc sizes] :as prepared}]
  (when-not (:destroyed? @state)
    (let [{:keys [container path]} @state
          scroller (scroller-of container)
          anchor   (current-anchor state)]
      (doseq [[_ task] (:tasks @state)] (try (.cancel task) (catch :default _ nil)))
      ;; :gen fences in-flight work against the OLD document; :doc-gen counts document swaps (observers that
      ;; borrow the proxy — the overview grid — rebuild when it moves)
      (swap! state #(-> %
                        (assoc :doc doc :sizes sizes :deck (:deck prepared)
                               :tasks {} :rendered #{} :text-built {} :toc-ids [])
                        (update :gen inc)
                        (update :doc-gen (fnil inc 0))))
      (mark-deck! container (:deck prepared))
      (reconcile-pages! state (count sizes))
      (swap! state update :current #(deck/clamp-page (:deck prepared) (or % 0)))
      (doseq [^js sheet (:sheet-divs @state)] (strip-layers! sheet))
      (when-let [mode (when-not (slides? state) (:fit @state))] (apply-fit! state mode))
      (apply-layout! state)
      (when (and scroller anchor)
        (set! (.-scrollTop scroller) (+ (.-offsetTop container) (layout/anchor-top (:rects @state) anchor))))
      (drop-offscreen-canvases! state)
      (reobserve! state)
      (render-visible! state)
      (report! state)
      (reset! current-doc {:doc doc :path path})
      (cache/set-ensurer! (:ensure-fn @state))
      (extract-outline! state doc path)
      state)))

(defn update!
  "Apply a new view-state to a mounted controller: dark-invert always; then a LAYOUT change (document ↔ slides,
   ADR-0045) if the effective layout moved; in the slides layout a presenting change (its margin: none when
   presenting); in the document layout zoom/fit as before. Slides fit themselves, so zoom/fit never apply there."
  [state view-state]
  (when-not (:destroyed? @state)
    (let [container   (:container @state)
          old-scale   (:scale @state)
          layout      (wanted-layout (:deck @state) view-state)
          presenting? (boolean (:presenting? view-state))]
      (toggle-class! container "vv-pdf-invert" (:invert? view-state))
      (swap! state assoc :invert? (boolean (:invert? view-state)))
      (cond
        (not= layout (:layout @state))
        (do (swap! state assoc :presenting? presenting? :fit (:fit view-state)
                   :scale (layout/clamp-zoom (or (:scale view-state) old-scale)))
            (set-layout! state layout))

        (slides? state)
        (when (not= presenting? (:presenting? @state))
          (swap! state assoc :presenting? presenting?)
          (relayout! state (current-anchor state)))

        :else
        (do (if (:fit view-state)
              (apply-fit! state (:fit view-state))
              (swap! state assoc :scale (layout/clamp-zoom (or (:scale view-state) old-scale)) :fit nil))
            (when (not= (:scale @state) old-scale)
              (rescale! state)))))))

(defn ensure-all-text!
  "Materialize text layers for every page (so in-page find covers the whole document, not just the
   pages whose canvas is currently rendered). Idempotent; returns a Promise that resolves once every
   page's text layer is built (so the find hook can search afterwards)."
  [state]
  (if (:destroyed? @state)
    (js/Promise.resolve nil)
    (js/Promise.all (into-array (for [idx (range (count (:page-divs @state)))]
                                  (ensure-text-at! state idx))))))

(defn unmount! [state]
  (cache/clear-ensurer! (:ensure-fn @state))
  (cache/clear-slides! (:hook @state))
  (detach-slides-listeners! state)
  (doseq [k [:report-raf :scroll-raf]] (when-let [r (k @state)] (js/cancelAnimationFrame r)))
  (swap! state assoc :destroyed? true)
  (doseq [[_ task] (:tasks @state)] (try (.cancel task) (catch :default _ nil)))
  (when-let [^js obs (:observer @state)] (.disconnect obs))
  (when-let [^js ro (:resize-observer @state)] (.disconnect ro))
  (when-let [[^js mq handler] (:dpr-watch @state)] (.removeEventListener mq "change" handler))
  (when (not= false (:owns-doc? @state))
    (when-let [^js doc (:doc @state)] (try (.destroy doc) (catch :default _ nil)))))
