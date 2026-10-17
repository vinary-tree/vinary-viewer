(ns vinary.terminal.pdf
  "Headless PDF text extraction for the terminal: pdf.js (the legacy build — pure JS, no DOM/canvas) extracts text
   items per page, which the shared, DOM-free vinary.ir.frontend.pdf turns into a reflowable prose IR (paragraphs +
   font-size-derived headings). pdf.js v5's legacy build is ESM-only, so it is loaded via a dynamic import (works
   under shadow-cljs's CommonJS :node-script). Text/reflow facet only — the terminal has no canvas, so fixed-layout
   figures degrade to their extracted text (the acknowledged terminal limitation vs. the GUI's raster page)."
  (:require ["path" :as path]
            [vinary.ir.frontend.pdf :as pdf]
            [vinary.ir.node :as node]))

;; resources/public ships alongside the compiled script (found relative to __dirname), like the tree-sitter/resvg
;; wasms. pdf-loader.js there is a plain CommonJS file — required at RUNTIME via a computed path so shadow neither
;; bundles nor Closure-analyses its dynamic import() (both of which break it); Node loads it as a real module whose
;; import() has a proper resolution context.
(defn- res-dir [] (path/join js/__dirname ".." ".." "resources" "public"))
(defonce ^:private pdfjs (atom nil))
(defn- load-pdfjs []
  (or @pdfjs (reset! pdfjs ((js/require (path/join (res-dir) "js" "pdf-loader.js"))))))

(defn- page-items
  "One page's text content → Promise<[page-num [normalized-item …]]>."
  [^js doc p]
  (-> (.getPage doc p)
      (.then (fn [^js page] (.getTextContent page)))
      (.then (fn [^js tc] [p (mapv pdf/normalize-item (array-seq (.-items tc)))]))))

(defn open-document
  "PDF bytes (Uint8Array/Buffer) → Promise<PDFDocumentProxy> from the headless legacy pdf.js (main thread, no
   worker) — the one loader behind text extraction and `vv --cli --deck-info`."
  [bytes]
  (-> (load-pdfjs)
      (.then (fn [^js lib]
               ;; pdf.js v5 requires a Uint8Array (a Node Buffer is rejected even though it is one) — wrap it.
               ;; :verbosity 0 (VerbosityLevel.ERRORS): pdf.js prints its warnings ("Indexing all PDF objects" for a
               ;; damaged file, font fallbacks, …) with console.log — onto STDOUT, which carries the document in
               ;; vv --cli and the screen in vv --tui. Errors still reject the promise and reach stderr.
               (.-promise (.getDocument lib #js {:data (js/Uint8Array. bytes) :useSystemFonts true :isEvalSupported false
                                                 :verbosity 0}))))))

(defn page-sizes
  "Promise<[[w h] …]>: every page's size in PDF points at scale 1, rotation applied (/Rotate 90 swaps w and h) — the
   same measure the GUI engine gives the deck detector (renderer.pdf/page-sizes)."
  [^js doc]
  (-> (js/Promise.all
       (into-array
        (for [i (range 1 (inc (or (.-numPages doc) 0)))]
          (-> (.getPage doc i)
              (.then (fn [^js page] (let [^js vp (.getViewport page #js {:scale 1})] [(.-width vp) (.-height vp)])))))))
      (.then vec)))

(defn extract
  "PDF bytes (Uint8Array/Buffer) → Promise<[[page-num [normalized-item …]] …]> for ir.frontend.pdf/doc->ir. Runs
   pdf.js on the main thread (no worker), text content only."
  [bytes]
  (-> (open-document bytes)
      (.then (fn [^js doc]
               (js/Promise.all (into-array (map #(page-items doc %) (range 1 (inc (or (.-numPages doc) 0))))))))
      (.then (fn [pages] (vec pages)))))

(defn- add-heading-ids
  "Give each reflowed :heading a unique node-meta :id so ir.capability.toc/toc-of picks it up and
   ir.backend.ansi/render-lines anchors it — the TUI's PDF Contents + jump. (reflow-ir emits id-less headings,
   which is fine for the GUI's page-anchored outline but leaves the terminal with no jump target.)"
  [doc]
  (let [counter (atom 0)]
    (node/node :document
               (mapv (fn [b]
                       (if (= :heading (node/kind b))
                         (node/node :heading (node/children b)
                                    (assoc (node/node-meta b) :id (str "vv-pdf-h-" (swap! counter inc))))
                         b))
                     (node/children doc))
               (node/node-meta doc))))

(defn pdf->ir
  "PDF bytes → Promise<reflowable :document IR> (paragraphs + anchored headings), ready for ir.backend.ansi."
  [bytes]
  (-> (extract bytes)
      (.then (fn [pages] (add-heading-ids (pdf/reflow-ir (pdf/doc->ir pages)))))))
