(ns vinary.ir.backend.html
  "IR → HTML back-end: lower the common document IR to a HAST tree and serialize it with the SAME
   rehype-stringify the current markdown pipeline uses, reproducing byte-identical HTML (round-trip parity).
   As more front-ends adopt the IR this becomes the single HTML producer that replaces the renderer's ad-hoc
   stringify and the main-process office sanitizer. `ir->hast` reconstructs each node from its :meta :tag /
   :attrs (verbatim for the markdown front-end ⇒ exact) with a kind→tag fallback for pure front-ends
   (tables/logs) that carry no original tag. DOM-free (unified + rehype-stringify are pure); the
   math/mermaid/syntax post-passes stay with the caller so they apply identically to the legacy path."
  (:require ["unified" :refer [unified]]
            ["rehype-stringify$default" :as rehype-stringify]
            [vinary.ir.node :as node]))

(defn- kind->tag
  "Fallback HAST tag for an IR node whose front-end supplied no :meta :tag (pure front-ends)."
  [k level]
  (case k
    :heading        (str "h" (or level 1))
    :paragraph      "p"
    :link           "a"
    :image          "img"
    :list           "ul"
    :list-item      "li"
    :blockquote     "blockquote"
    :code-block     "pre"
    :code           "code"
    :table          "table"
    :table-head     "thead"
    :table-body     "tbody"
    :row            "tr"
    :cell           "td"
    :thematic-break "hr"
    :math           "span"
    "div"))

(defn- props->js
  "Coerce an IR node's :attrs to a HAST `properties` object: a JS object (markdown front-end) is used
   verbatim; a cljs map (pure front-ends) is converted."
  [attrs]
  (cond
    (nil? attrs)  #js {}
    (map? attrs)  (clj->js attrs)
    :else         attrs))

(defn ir->hast
  "Reconstruct a HAST node from an IR node."
  [n]
  (case (node/kind n)
    :document #js {:type "root"    :children (into-array (map ir->hast (node/children n)))}
    :text     #js {:type "text"    :value (or (node/text n) "")}
    :comment  #js {:type "comment" :value (or (node/text n) "")}
    :doctype  #js {:type "doctype"}
    :raw-node (:hast (node/node-meta n))
    (let [m (node/node-meta n)]
      #js {:type "element"
           :tagName    (or (:tag m) (kind->tag (node/kind n) (:level m)))
           :properties (props->js (:attrs m))
           :children   (into-array (map ir->hast (node/children n)))})))

(defonce ^:private stringifier (-> (unified) (.use rehype-stringify)))

(defn stringify-hast
  "Serialize a HAST tree to HTML with the shared rehype-stringify compiler."
  [hast]
  (.stringify stringifier hast))

(defn lower
  "IR tree → HTML string, BEFORE the math/mermaid/syntax post-passes (which the caller applies identically to
   the legacy pipeline). Because ir->hast reconstructs the captured HAST faithfully, this equals the legacy
   rehype-stringify output."
  [ir]
  (stringify-hast (ir->hast ir)))
