(ns vinary.ir.node
  "The common document IR node — one uniform tagged tree that every format front-end targets and every
   back-end lowers. `:meta` is an open map carrying spans / bboxes / roles / provenance (see
   vinary.ir.meta). A record (not a bare map) gives V8 hidden-class-stable field access + protocol dispatch
   on the tree-transducer hot path, while the single shape keeps the transducer's `(kind …)`/`(children …)`
   interface uniform. Pure + DOM-free.

   `:kind` — block/inline roles: :document :section :heading :paragraph :list :list-item :blockquote
   :table :row :cell :code-block :code :math :figure :image :link :raw-html :thematic-break :plain
   and PDF facets :page :block :line :run. `:meta` keys are documented in vinary.ir.meta.")

(defrecord Node [kind children text meta])

(def ^:private empty-meta {})   ; interned — leaves/branches with no metadata share one map

(defn node
  "A branch node: `kind` keyword, ordered `children` seqable (coerced to a vector), optional open `meta` map."
  ([kind children]      (->Node kind (vec children) nil empty-meta))
  ([kind children meta] (->Node kind (vec children) nil (or meta empty-meta))))

(defn leaf
  "A leaf node carrying `text` (no children)."
  ([kind text]      (->Node kind [] text empty-meta))
  ([kind text meta] (->Node kind [] text (or meta empty-meta))))

(defn node?     [x] (instance? Node x))
(defn kind      [n] (:kind n))
(defn children  [n] (:children n))
(defn text      [n] (:text n))
(defn node-meta [n] (:meta n))              ; NOT `meta` — avoid shadowing clojure.core/meta
(defn leaf?     [n] (empty? (:children n)))
(defn arity     [n] (count (:children n)))

(defn with-children  [n kids] (->Node (:kind n) (vec kids) (:text n) (:meta n)))
(defn with-node-meta [n m]    (->Node (:kind n) (:children n) (:text n) (or m empty-meta)))
(defn assoc-meta     [n & kvs] (->Node (:kind n) (:children n) (:text n) (apply assoc (:meta n) kvs)))

(defn valid-tree?
  "Structural validity: a Node with a keyword `kind`, a vector `children` (each itself valid), a map `meta`,
   and string-or-nil `text`."
  [x]
  (and (node? x)
       (keyword? (:kind x))
       (vector? (:children x))
       (map? (:meta x))
       (or (nil? (:text x)) (string? (:text x)))
       (every? valid-tree? (:children x))))

(defn walk
  "Depth-first preorder side-effecting walk: calls (f node) for every node; returns nil."
  [f n]
  (f n)
  (run! #(walk f %) (:children n))
  nil)

(defn preorder
  "Lazy preorder seq of every node in the subtree (self first, then children left-to-right)."
  [n]
  (cons n (mapcat preorder (:children n))))

(defn map-nodes
  "Bottom-up rebuild: returns (f node') for every node, where node' already has its children mapped.
   Children are rebuilt with a transient for speed."
  [f n]
  (let [kids  (:children n)
        kids' (if (seq kids)
                (persistent! (reduce #(conj! %1 (map-nodes f %2)) (transient []) kids))
                kids)]
    (f (->Node (:kind n) kids' (:text n) (:meta n)))))

(defn text-content
  "Concatenated leaf text of the subtree, in reading (preorder) order — for TOC labels / find indexing."
  [n]
  (if (leaf? n)
    (or (:text n) "")
    (apply str (map text-content (:children n)))))
