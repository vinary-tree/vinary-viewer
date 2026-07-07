(ns vinary.ir.meta
  "Per-node metadata accessors + stable anchor identity for the document IR. Metadata rides in the node's
   open `:meta` map. `anchor-id` folds a node's slug + a per-document occurrence counter into a stable,
   collision-free id (rehype-slug-compatible: first `notes`, then `notes-1`, `notes-2`, …) so byte-identical
   headings — or repeated PDF page/table titles — still get DISTINCT ids while a same-order re-render keeps
   them stable. The id is the scroll-spy / find-jump target and the render key. Pure + DOM-free.

   `:meta` keys (all optional): :span {:start/:end {:line :column :offset}} (source provenance, == lling-llang
   SyntaxNode Range) · :bbox {:x :y :w :h :page} (PDF/geometry facet) · :role (semantic role, defaults to the
   node's kind) · :level (heading depth / list nesting) · :id (explicit anchor) · :attrs (hast properties:
   href/src/class…) · :lang (code-block language) · :tex/:display? (math) · :diagram/:source (mermaid/…) ·
   :provenance {:frontend :rule} · :weight (semiring weight — grouping confidence)."
  (:require [clojure.string :as str]
            [vinary.ir.node :as node]))

(defn span        [n] (-> n node/node-meta :span))
(defn bbox        [n] (-> n node/node-meta :bbox))
(defn role        [n] (or (-> n node/node-meta :role) (node/kind n)))   ; role defaults to kind
(defn level       [n] (-> n node/node-meta :level))
(defn explicit-id [n] (-> n node/node-meta :id))
(defn attrs       [n] (-> n node/node-meta :attrs))
(defn lang        [n] (-> n node/node-meta :lang))
(defn weight      [n] (-> n node/node-meta :weight))
(defn provenance  [n] (-> n node/node-meta :provenance))

(defn start-offset
  "Best available source position for ordering/stability: byte offset, else line, else nil."
  [n]
  (let [s (:start (span n))]
    (or (:offset s) (:line s))))

(defn slug
  "rehype-slug-compatible slug: lowercase, drop chars outside [\\w\\- ], collapse whitespace to single `-`."
  [s]
  (-> (str s) str/trim str/lower-case
      (str/replace #"[^\w\- ]" "")
      (str/replace #"\s+" "-")))

(defn anchor-id
  "Stable, collision-free id for `n`. 1-arity uses a fresh occurrence map (standalone / tests); 2-arity
   threads + mutates `seen` (an atom of base-slug → count) across a document walk so duplicates get
   `-1`, `-2`, … suffixes exactly like rehype-slug. A blank slug falls back to the node's role name."
  ([n] (anchor-id n (atom {})))
  ([n seen]
   (let [s    (slug (node/text-content n))
         base (if (str/blank? s) (name (role n)) s)
         cnt  (get @seen base 0)]
     (swap! seen update base (fnil inc 0))
     (if (zero? cnt) base (str base "-" cnt)))))
