(ns vinary.slides.evidence
  "Gather slide-deck EVIDENCE from a pdf.js PDFDocumentProxy (ADR-0045): the /Info Creator + Producer, the XMP
   xmp:CreatorTool, the catalog /PageMode, and the page labels — everything vinary.slides.deck/classify reads
   except page sizes, which the PDF engine already measures for layout (renderer.pdf/page-sizes) and merges in.

   Works with the browser and the Node legacy builds of pdf.js alike (only document-level API calls), so the GUI
   engine, `vv --cli --deck-info`, and a future terminal slide mode share it. Every call is isolated: a document
   whose metadata, page mode or labels cannot be read still yields the rest (nil for the missing parts) — the
   detector must never be the reason a PDF fails to open.

   Runs in the renderer on the already-loaded document, so it costs no extra I/O and behaves identically for a
   local file, an ssh:// / sftp:// URI, an archive member or a web-linked PDF.")

(defn- attempt
  "Call (f) → a Promise that never rejects: the result, or nil when f throws or its promise rejects."
  [f]
  (try
    (-> (js/Promise.resolve (f)) (.catch (fn [_] nil)))
    (catch :default _ (js/Promise.resolve nil))))

(defn- text [x] (when (and (some? x) (not= "" (str x))) (str x)))

(defn- xmp-creator-tool
  "pdf.js's Metadata lower-cases property names on parse; ask for both spellings to be robust across versions."
  [^js metadata]
  (when (and metadata (fn? (.-get metadata)))
    (or (text (.get metadata "xmp:creatortool"))
        (text (.get metadata "xmp:CreatorTool")))))

(defn gather
  "Promise<{:creator :producer :creator-tool :page-mode :labels}> from a PDFDocumentProxy. :labels is a vector of
   per-page label strings, or nil when the document has no /PageLabels. Never rejects."
  [^js doc]
  (-> (js/Promise.all #js [(attempt #(.getMetadata doc))
                           (attempt #(.getPageMode doc))
                           (attempt #(.getPageLabels doc))])
      (.then (fn [^js results]
               (let [^js md     (aget results 0)
                     ^js info   (some-> md .-info)
                     mode       (aget results 1)
                     labels     (aget results 2)]
                 {:creator      (some-> info .-Creator text)
                  :producer     (some-> info .-Producer text)
                  :creator-tool (xmp-creator-tool (some-> md .-metadata))
                  :page-mode    (text mode)
                  :labels       (when (and labels (pos? (.-length labels)))
                                  (vec (map text (array-seq labels))))})))
      (.catch (fn [_] {:creator nil :producer nil :creator-tool nil :page-mode nil :labels nil}))))
