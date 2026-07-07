(ns vinary.ir.frontend.office
  "Office / OpenDocument front-end: convert office HTML (docx via mammoth, or the ODF block HTML built in the
   main content-service) into the common document IR. It parses the HTML by running it as a `raw` node
   through rehype-raw — the SAME raw-HTML parser already in the Markdown pipeline, so no new dependency — then
   the shared sanitizer and rehype-slug (so headings gain stable ids), and finally hast->ir. Lowering the
   result through the IR back-end gives office documents the same TOC (from headings), figure handling, and
   single sanitize policy as Markdown — capabilities they previously lacked. DOM-free (rehype-raw's parser is
   pure JS)."
  (:require ["unified" :refer [unified]]
            ["rehype-raw$default"      :as rehype-raw]
            ["rehype-sanitize$default" :as rehype-sanitize]
            ["rehype-slug$default"     :as rehype-slug]
            [vinary.ir.backend.sanitize :as sanitize]
            [vinary.ir.frontend.markdown :as md-fe]))

(defonce ^:private raw-processor
  ;; runSync runs the transform phase on a given tree (no Parser/Compiler needed): rehype-raw parses the raw
  ;; node into real elements, the shared sanitizer strips anything dangerous, rehype-slug ids the headings.
  (-> (unified)
      (.use rehype-raw)
      (.use rehype-sanitize sanitize/schema)
      (.use rehype-slug)))

(defn html->hast
  "Parse an office HTML string into a sanitized, heading-slugged HAST tree."
  [html]
  (.runSync raw-processor #js {:type "root" :children #js [#js {:type "raw" :value (or html "")}]}))

(defn html->ir
  "Office HTML string → common document IR."
  [html]
  (md-fe/hast->ir (html->hast html)))
