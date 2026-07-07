(ns vinary.ir.capability.toc
  "Table-of-contents capability over the common IR: walk the tree for heading-role nodes and emit the
   [{:level :text :id}] shape the sidebar Contents outline + the scroll-spy consume — ONE implementation for
   every format (Markdown, office, source, PDF), replacing the Markdown-only collect-metadata harvest. It
   reproduces that harvest exactly (heading tag → level, trimmed text-content, the rehype-slug id property,
   skipping headings with a blank id) so the IR render's :toc is byte-equal to the legacy pipeline's. Pure +
   DOM-free."
  (:require [clojure.string :as str]
            [vinary.ir.node :as node]
            [vinary.ir.meta :as meta]))

(defn- heading-id
  "The heading's anchor id: the hast `id` property (markdown front-end) if present, else an explicit :id in
   metadata (pure front-ends), else nil."
  [n]
  (let [a (meta/attrs n)]
    (or (when (and a (not (map? a))) (aget a "id"))   ; JS hast properties
        (when (map? a) (get a "id"))                  ; cljs attrs (pure front-ends)
        (meta/explicit-id n))))

(defn toc-of
  "Vector of {:level :text :id} for every heading-role node in `ir`, in document (preorder) order, skipping
   headings whose id is blank — matching the legacy collect-metadata harvest."
  [ir]
  (into []
        (comp (filter #(= :heading (meta/role %)))
              (keep (fn [h]
                      (let [id (heading-id h)]
                        (when-not (str/blank? id)
                          {:level (or (meta/level h) 1)
                           :text  (str/trim (node/text-content h))
                           :id    id})))))
        (node/preorder ir)))
