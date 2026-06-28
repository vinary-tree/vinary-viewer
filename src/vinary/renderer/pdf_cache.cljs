(ns vinary.renderer.pdf-cache
  "PDF byte cache + the in-page-find hook, deliberately FREE of any pdfjs-dist require so re-frame
   effects (vinary.app.fx, transitively required by the tested vinary.app.events) can reference it
   without pulling pdf.js — which touches DOMMatrix/Worker — into the node :test build. The pdf.js
   engine (vinary.renderer.pdf) registers the text-ensurer; the pdf-view reads bytes; effects write
   bytes + trigger the ensurer. Bytes live here (keyed by :doc/path), never in DataScript (ADR-0010).")

(defonce ^:private bytes-cache (atom {}))   ; :doc/path → Uint8Array
(defonce ^:private ensurer (atom nil))      ; 0-arg fn → Promise materializing the active PDF's text

(defn put-bytes! [path bytes] (swap! bytes-cache assoc path bytes))
(defn get-bytes  [path] (get @bytes-cache path))

(defn evict-keep!
  "Drop cached bytes for paths not in `keep-paths` (piggy-backs on the retention sweep)."
  [keep-paths]
  (let [keep (set keep-paths)]
    (swap! bytes-cache (fn [m] (into {} (filter (fn [[k _]] (contains? keep k)) m))))))

(defn set-ensurer!   [f] (reset! ensurer f))
(defn clear-ensurer! [f] (when (identical? @ensurer f) (reset! ensurer nil)))

(defn ensure-active!
  "Materialize all text layers on the active PDF (so in-page find covers the whole document). Returns a
   Promise, resolved immediately when no PDF is mounted."
  []
  (if-let [f @ensurer] (f) (js/Promise.resolve nil)))
