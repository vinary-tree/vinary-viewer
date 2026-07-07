(ns vinary.ir.frontend.markdown
  "Markdown front-end: convert the rehype HAST (produced by the app's unified pipeline, after
   rehype-raw/sanitize/slug/highlight/rewrite-urls/wrap-images/source-positions) into the common document
   IR. It is a FAITHFUL structural mirror — every HAST node becomes an IR node preserving its tag +
   properties (verbatim in :meta :attrs) + children/text — so the IR round-trips back to byte-identical HAST
   (the parity guarantee that makes the :vv/ir cutover invisible). On top of that mirror it assigns a
   semantic :kind and derives metadata (:role/:level, and :span from the data-vv-source-* attributes) so the
   capability layer (toc / positions / figures) reads the tree uniformly. Pure — walks plain JS HAST objects,
   no DOM."
  (:require [vinary.ir.node :as node]))

(defn heading-level
  "1..6 for an h1..h6 tag, else nil."
  [tag]
  (when (and (string? tag) (= 2 (count tag)) (= \h (nth tag 0)))
    (let [d (js/parseInt (subs tag 1))] (when (and (integer? d) (<= 1 d 6)) d))))

(defn- semantic-kind [tag]
  (or (when (heading-level tag) :heading)
      (case tag
        "p" :paragraph
        "a" :link
        "img" :image
        ("ul" "ol") :list
        "li" :list-item
        "blockquote" :blockquote
        "pre" :code-block
        "code" :code
        "table" :table
        "thead" :table-head
        "tbody" :table-body
        "tr" :row
        ("td" "th") :cell
        "hr" :thematic-break
        :element)))

(defn- attr [^js props k] (when props (aget props k)))
(defn- ->int [s] (when (some? s) (let [n (js/parseInt s)] (when-not (js/isNaN n) n))))

(defn- source-span
  "Extract a source {:start/:end {:line :column :offset}} from data-vv-source-* attributes, or nil."
  [^js props]
  (when-let [sl (->int (attr props "data-vv-source-start-line"))]
    {:start {:line sl
             :column (->int (attr props "data-vv-source-start-column"))
             :offset (->int (attr props "data-vv-source-start-offset"))}
     :end   {:line   (->int (attr props "data-vv-source-end-line"))
             :column (->int (attr props "data-vv-source-end-column"))
             :offset (->int (attr props "data-vv-source-end-offset"))}}))

(defn- kids->ir [^js n f]
  (let [^js ks (.-children n)]
    (if ks (mapv f (array-seq ks)) [])))

(defn hast->ir
  "Convert a HAST node (root/element/text/comment/doctype) to an IR node."
  [^js n]
  (case (.-type n)
    "root"    (node/node :document (kids->ir n hast->ir) {})
    "element" (let [tag   (.-tagName n)
                    props (.-properties n)
                    lvl   (heading-level tag)
                    m     (cond-> {:tag tag :attrs props}
                            lvl                  (assoc :role :heading :level lvl)
                            (source-span props)  (assoc :span (source-span props)))]
                (node/node (semantic-kind tag) (kids->ir n hast->ir) m))
    "text"    (node/leaf :text (or (.-value n) "") {})
    "comment" (node/leaf :comment (or (.-value n) "") {})
    "doctype" (node/leaf :doctype nil {})
    ;; any other node type — preserve verbatim so lowering can splice it back unchanged
    (node/leaf :raw-node nil {:hast n})))
