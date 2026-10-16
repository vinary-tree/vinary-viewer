(ns vinary.renderer.pdf-cache
  "PDF byte cache + the in-page-find hook, deliberately FREE of any pdfjs-dist require so re-frame
   effects (vinary.app.fx, transitively required by the tested vinary.app.events) can reference it
   without pulling pdf.js — which touches DOMMatrix/Worker — into the node :test build. The pdf.js
   engine (vinary.renderer.pdf) registers the text-ensurer; the pdf-view reads bytes; effects write
   bytes + trigger the ensurer. Bytes live here (keyed by :doc/path), never in DataScript (ADR-0010).

   KEEP-LAST-GOOD (ADR-0045 §Live refresh). Arriving bytes are a CANDIDATE, not a replacement: each path holds
   {:good {:bytes :stamp} :candidate {:bytes :stamp}}. The pdf-view prepares the candidate and PROMOTES it on a
   successful parse, or REJECTS it (dropping it, keeping :good) when pdf.js cannot open it — a refresh read
   while pdflatex was still writing, a corrupt file. So a torn reload never displaces the last version that
   rendered, and a later remount (a tab switch back) still finds bytes that open.")

(defonce ^:private bytes-cache (atom {}))   ; :doc/path → {:good {:bytes :stamp}|nil :candidate {:bytes :stamp}|nil}
(defonce ^:private ensurer (atom nil))      ; 0-arg fn → Promise materializing the active PDF's text

(defn put-bytes!
  "Cache newly arrived `bytes` for `path` (stamped `stamp`) as the CANDIDATE version."
  ([path bytes] (put-bytes! path bytes nil))
  ([path bytes stamp]
   (swap! bytes-cache update path assoc :candidate {:bytes bytes :stamp stamp})))

(defn bytes-for
  "The newest version to try for `path`: the candidate when one is pending, else the last good one — as
   {:bytes :stamp}, or nil when nothing is cached."
  [path]
  (let [{:keys [good candidate]} (get @bytes-cache path)]
    (or candidate good)))

(defn get-bytes
  "The newest cached bytes for `path` (candidate, else good), or nil."
  [path]
  (:bytes (bytes-for path)))

(defn good-version
  "The last version of `path` that rendered successfully, {:bytes :stamp}, or nil."
  [path]
  (:good (get @bytes-cache path)))

(defn promote!
  "The candidate stamped `stamp` rendered: it becomes the good version. A stale stamp (a newer candidate has
   arrived since) is ignored."
  [path stamp]
  (swap! bytes-cache update path
         (fn [{:keys [candidate] :as entry}]
           (if (and candidate (= stamp (:stamp candidate)))
             (assoc entry :good candidate :candidate nil)
             entry))))

(defn reject!
  "The candidate stamped `stamp` failed to open: drop it, keeping the good version. Returns true when a good
   version remains to fall back to."
  [path stamp]
  (let [entry (get (swap! bytes-cache update path
                          (fn [{:keys [candidate] :as entry}]
                            (if (and candidate (= stamp (:stamp candidate)))
                              (assoc entry :candidate nil)
                              entry)))
                   path)]
    (boolean (:good entry))))

(defn evict-keep!
  "Drop cached bytes for paths not in `keep-paths` (piggy-backs on the retention sweep)."
  [keep-paths]
  (let [keep (set keep-paths)]
    (swap! bytes-cache (fn [m] (into {} (filter (fn [[k _]] (contains? keep k)) m))))))

;; ---- the slides hook (ADR-0045) ---------------------------------------------------------------------------
;; The mounted PDF engine registers {:command! :slides? :position :snapshot}; re-frame effects, the scroll
;; delegate and the overview grid reach the slide show through these pdf.js-free functions. Identity-checked like
;; the find ensurer, so an unmounting controller never clears a newer one's registration.
(defonce ^:private slides-hook (atom nil))

(defn set-slides!   [hook] (reset! slides-hook hook))
(defn clear-slides! [hook] (when (and hook (identical? @slides-hook hook)) (reset! slides-hook nil)))

(defn slides-active?
  "Is a PDF mounted in the slides layout right now?"
  []
  (boolean (when-let [f (:slides? @slides-hook)] (f))))

(defn slides-command!
  "Run slide-navigation `op` (vinary.slides.deck/step ops) on the mounted slide show. Returns true when a slides
   view took it."
  [op]
  (if (slides-active?)
    (do ((:command! @slides-hook) op) true)
    false))

(defn slides-position
  "The current slide (0-based page) of the mounted slide show, or nil."
  []
  (when (slides-active?) ((:position @slides-hook))))

(defn slides-snapshot
  "{:doc :sizes :deck :current :doc-gen :path :layout} of the mounted PDF controller (borrowed by the overview
   grid — never a second PDFDocumentProxy), or nil."
  []
  (when-let [f (:snapshot @slides-hook)] (f)))

(defn set-ensurer!   [f] (reset! ensurer f))
(defn clear-ensurer! [f] (when (identical? @ensurer f) (reset! ensurer nil)))

(defn ensure-active!
  "Materialize all text layers on the active PDF (so in-page find covers the whole document). Returns a
   Promise, resolved immediately when no PDF is mounted."
  []
  (if-let [f @ensurer] (f) (js/Promise.resolve nil)))
