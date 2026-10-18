(ns vinary.slides.evidence
  "Gather slide-deck EVIDENCE from a pdf.js PDFDocumentProxy (ADR-0045): the /Info Creator + Producer, the XMP
   xmp:CreatorTool, the catalog /PageMode, and the page labels — everything vinary.slides.deck/classify reads
   except page sizes, which the PDF engine already measures for layout (renderer.pdf/page-sizes) and merges in.

   Works with the browser and the Node legacy builds of pdf.js alike (only document-level API calls), so the GUI
   engine, `vv --cli --deck-info`, and a future terminal slide mode share it. Every call is isolated: a document
   whose metadata, page mode or labels cannot be read still yields the rest (nil for the missing parts) — the
   detector must never be the reason a PDF fails to open.

   Runs in the renderer on the already-loaded document, so it costs no extra I/O and behaves identically for a
   local file, an ssh:// / sftp:// URI, an archive member or a web-linked PDF.

   SPEAKER NOTES (ADR-0048 §2–§5): gather also reads pdfpc's XMP keys, and the page-level readers below turn page text
   and annotations into the plain data vinary.slides.notes decides on. `settle` runs a notes model's pending work — the
   probe, the confirmation, the scan — in the background after the first paint; `detect` is the whole decision at once,
   for `vv --cli --deck-info`. Page-level calls work in the Node build too."
  (:require [vinary.slides.notes :as notes]))

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

(defn- raw-xmp
  "The XMP packet as text (pdf.js keeps it beside its parse, after removing anything before the first tag), or nil."
  [^js metadata]
  (when (and metadata (fn? (.-getRaw metadata)))
    (try (let [raw (.getRaw metadata)] (when (string? raw) raw))
         (catch :default _ nil))))

(defn gather
  "Promise<{:creator :producer :creator-tool :page-mode :labels :pdfpc}> from a PDFDocumentProxy. :labels is a vector
   of per-page label strings, or nil when the document has no /PageLabels. :pdfpc is pdfpc's XMP keys
   (vinary.slides.notes/pdfpc-xmp: {} without keys, nil without a packet). Never rejects."
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
                                  (vec (map text (array-seq labels))))
                  :pdfpc        (notes/pdfpc-xmp (raw-xmp (some-> md .-metadata)))})))
      (.catch (fn [_] {:creator nil :producer nil :creator-tool nil :page-mode nil :labels nil :pdfpc nil}))))

;; ---- page text and annotations (ADR-0048 §2, §4) -----------------------------------------------------------------

(defn page-runs
  "Promise<[{:str :t}…]|nil>: the text runs of page `p` (0-based) as vinary.slides.notes reads them — each pdf.js text
   item's string and transform (page units, y up) — or nil when the page's text cannot be read. Never rejects."
  [^js doc p]
  (-> (attempt #(-> (.getPage doc (inc p)) (.then (fn [^js page] (.getTextContent page)))))
      (.then (fn [^js content]
               (let [items (some-> content .-items)]
                 (when (array? items)
                   (into []
                         (keep (fn [^js item]
                                 (let [s (.-str item)
                                       t (.-transform item)]
                                   (when (and (string? s) (array? t) (= 6 (.-length t)))
                                     {:str s :t (vec t)}))))
                         (array-seq items))))))))

(defn page-annotations
  "Promise<[{:subtype :contents :dir}…]|nil>: the annotations of page `p` (0-based), in /Annots order, with their
   /Contents as pdf.js decodes it and its direction (\"ltr\"/\"rtl\"); nil when they cannot be read. Hidden ones are
   included: pdfpc.sty writes its notes with /F 6 (Hidden, NoView), and pdf.js's default \"display\" intent drops
   those, so this asks for intent \"any\". Never rejects."
  [^js doc p]
  (-> (attempt #(-> (.getPage doc (inc p)) (.then (fn [^js page] (.getAnnotations page #js {:intent "any"})))))
      (.then (fn [annots]
               (when (array? annots)
                 (mapv (fn [^js a]
                         (let [^js c (.-contentsObj a)]
                           {:subtype  (.-subtype a)
                            :contents (some-> c .-str)
                            :dir      (some-> c .-dir)}))
                       (array-seq annots)))))))

(defn text-notes
  "Promise<{:text :dir}|nil>: page `p`'s annotation note (vinary.slides.notes/annotation-text), its pdfTeX bytes
   repaired for `producer`. Never rejects."
  [doc p producer]
  (-> (page-annotations doc p) (.then #(notes/annotation-text % producer))))

;; ---- settling a notes model (ADR-0048 §2.3–§2.5) ------------------------------------------------------------------

(def ^:private kept-pages
  ;; the reader keeps the runs of this many pages: a slide is compared with each of its note pages in turn
  4)

(defn- runs-reader
  "A function p → Promise<runs|nil> over `doc` that keeps the last kept-pages pages it read and counts the pages it
   reads: it rejects with ::too-many-pages instead of reading more than vinary.slides.notes/max-content-pages, and
   with ::cancelled instead of reading once `alive?` is false. (A decision reached while a read was in flight is
   dropped by settle's own last check.)"
  [doc alive?]
  (let [kept (js/Map.)
        read (volatile! 0)]
    (fn [p]
      (cond
        (not (alive?))                        (js/Promise.reject ::cancelled)
        (.has kept p)                         (js/Promise.resolve (.get kept p))
        (>= @read notes/max-content-pages)    (js/Promise.reject ::too-many-pages)
        :else
        (do (vswap! read inc)
            (-> (page-runs doc p)
                (.then (fn [runs]
                         (.set kept p runs)
                         (when (> (.-size kept) kept-pages)
                           (.delete kept (.-value (.next (.keys kept)))))
                         runs))))))))

(defn- diagonal [sizes p]
  (let [[w h] (nth sizes p nil)] (if (and (number? w) (number? h)) (js/Math.hypot w h) 0)))

(defn- read-pair
  "Promise<{:sig :dated?}> for slide page `s` and note page `j`: SIG(s, j) and ROTATED-DATE(j). Rejects with
   ::unreadable when either page's text cannot be read."
  [read sizes s j]
  (-> (js/Promise.all #js [(read s) (read j)])
      (.then (fn [^js rs]
               (let [slide (aget rs 0)
                     note  (aget rs 1)]
                 (if (and slide note)
                   {:sig    (notes/note-page-signature? slide note (diagonal sizes j))
                    :dated? (notes/rotated-date? note)}
                   (js/Promise.reject ::unreadable)))))))

(defn- probe
  "THE PROBE (ADR-0048 §2.3): Promise<{:note-pages ps :via a|:fallback}>, walking interleaving `il`'s candidates by
   vinary.slides.notes/probe-next."
  [read sizes il]
  (letfn [(walk [seen]
            (let [move (notes/probe-next il seen)]
              (if-let [a (:read move)]
                (-> (read-pair read sizes (dec a) a) (.then #(walk (conj seen %))))
                (js/Promise.resolve move))))]
    (walk [])))

(defn- confirm
  "CONFIRM (ADR-0048 §2.5): Promise<bool>, true when every note page of `note-pages` in an n-page deck carries its
   slide's thumbnail or the rotated date; it stops at the first page that does not."
  [read sizes n note-pages]
  (let [pairs (notes/confirm-pairs n note-pages)]
    (letfn [(walk [k]
              (if (= k (count pairs))
                (js/Promise.resolve true)
                (let [[s j] (nth pairs k)]
                  (-> (read-pair read sizes s j)
                      (.then (fn [{:keys [sig dated?]}]
                               (if (notes/confirmed? sig dated?) (walk (inc k)) false)))))))]
      (walk 0))))

(defn- scan
  "THE SCAN (ADR-0048 §2.4): Promise<#{note pages}> of an n-page deck without labels, reading every page once, in
   order: page j is a note page when it carries the thumbnail of the last slide page before it
   (vinary.slides.notes/scan-step)."
  [read sizes n]
  (letfn [(walk [state slide j]
            (if (= j n)
              (js/Promise.resolve (:notes state))
              (-> (read j)
                  (.then (fn [runs]
                           (if (nil? runs)
                             (js/Promise.reject ::unreadable)
                             (let [sig (notes/note-page-signature? slide runs (diagonal sizes j))]
                               (walk (notes/scan-step state j sig) (if (true? sig) slide runs) (inc j)))))))))]
    (-> (read 0)
        (.then (fn [runs]
                 (if (nil? runs)
                   (js/Promise.reject ::unreadable)
                   (walk {:slide 0 :notes #{}} runs 1)))))))

(defn settle
  "Promise<model|nil>: the notes `model` (vinary.slides.notes/initial) with its :pending work done over `doc`'s page
   text, page `sizes` given (page units), by vinary.slides.notes/decided:
     :scan     the scan's note pages, proof [:scan]; none → :no-note-pages
     :confirm  the labels' parse once confirmed, proof [:labels :confirm]; else :confirm-failed
     :probe    the probe's choice, proof [:labels :probe] (+ :confirm when (:confirm? model), else :confirm-failed);
               the parse without notes → :no-note-pages; no valid parse → :probe-undecided
   and, whatever the work, :too-many-pages when it would read more than vinary.slides.notes/max-content-pages pages,
   :unreadable when a page's text cannot be read, :error when anything else goes wrong — each of these shows the deck
   as it is. The model itself when nothing is pending; nil when `alive?` turns false first (the document was closed or
   replaced, so there is nothing to apply). Never rejects."
  [doc model sizes alive?]
  (let [read     (runs-reader doc alive?)
        n        (count sizes)
        il       (:interleaving model)
        confirmed (fn [ps proof]
                    (-> (confirm read sizes n ps)
                        (.then #(if % (notes/decided model ps proof nil) (notes/decided model nil nil :confirm-failed)))))
        work     (case (:pending model)
                   :scan    (-> (scan read sizes n) (.then #(notes/decided model % [:scan] :no-note-pages)))
                   :confirm (confirmed (:note-pages il) [:labels :confirm])
                   :probe   (-> (probe read sizes il)
                                (.then (fn [{ps :note-pages}]
                                         (cond
                                           (nil? ps)               (notes/decided model nil nil :probe-undecided)
                                           (empty? ps)             (notes/decided model nil nil :no-note-pages)
                                           (not (:confirm? model)) (notes/decided model ps [:labels :probe] nil)
                                           :else                   (confirmed ps [:labels :probe :confirm])))))
                   nil)]
    (if (nil? work)
      (js/Promise.resolve model)
      (-> work
          (.then #(when (alive?) %))
          (.catch (fn [e]
                    (when (alive?)
                      (case e
                        ::cancelled      nil
                        ::too-many-pages (notes/decided model nil nil :too-many-pages)
                        ::unreadable     (notes/decided model nil nil :unreadable)
                        (notes/decided model nil nil :error)))))))))

(defn detect
  "Promise<model>: a deck's whole notes decision at once — vinary.slides.notes/initial from its `evidence` (gather,
   plus :sizes) and parsed sidecar `sc`, then settle. `vv --cli --deck-info` prints it; the GUI engine runs the same two
   steps, settling after its first paint (ADR-0048 N6). Never rejects."
  [doc evidence sc]
  (settle doc (notes/initial evidence sc) (:sizes evidence) (constantly true)))
