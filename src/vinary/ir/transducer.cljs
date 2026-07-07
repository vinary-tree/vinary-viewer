(ns vinary.ir.transducer
  "Weighted top-down tree transducer over the document IR (blueprint: lling-llang src/tree_transducers/).
   A transduction maps an input IR tree to weighted output IR trees. Rules fire by (state, input-kind [,
   arity]); the output PATTERN builds output nodes, recursively transduces input children at chosen states,
   and — via :all — recurses over ALL children of an unranked (variable-arity) document node. Weights thread
   through the semiring: a derivation's weight is the firing rule's weight ⊗ the product of its children's
   weights; alternative derivations form the ⊕-set. A DETERMINISTIC lowering (one rule per kind, Boolean
   semiring) yields exactly one output — that is how ir/backend/html lowers IR→HTML. Pure + DOM-free.

   Rule precedence: a rule keyed on the exact input kind overrides the wildcard `:_` fallback (so an identity
   `:_` rule can be a catch-all while specific rules relabel/restructure particular kinds).

   Output-pattern grammar (plain maps):
     {:op :build   :kind K|(fn [in]→K) :text-fn (fn [in]→str)? :meta-fn (fn [in]→map)? :children [spec …]}
     {:op :var     :index i :state q}    ; transduce input child i at q → its output node
     {:op :all     :state q}             ; (child slot) transduce EVERY input child at q → one output child each
     {:op :sub     :pattern P}           ; a constant output pattern (independent of the input)
     {:op :project :index i :state q}    ; delete this node — hoist the transduced input child i"
  (:require [vinary.ir.semiring :as sr]
            [vinary.ir.node :as node]))

(defrecord TreeRule [state in-kind arity out weight])
(defn rule
  "A rule firing at `state` on an input node of `in-kind` (kind keyword, or `:_` = any). `arity` (or nil for
   any) further constrains it; `out` is the output pattern; `weight` its semiring weight."
  ([state in-kind out weight]       (->TreeRule state in-kind nil out weight))
  ([state in-kind arity out weight] (->TreeRule state in-kind arity out weight)))

(defrecord Transducer [index start one])
(defn transducer
  "Build a transducer from `rules`, starting at `start`, with `one` = a sample 1̄ weight of the semiring in
   use (for empty products). Rules are indexed by [state in-kind]."
  [rules start one]
  (->Transducer (group-by (juxt :state :in-kind) rules) start one))

(declare eval-pattern)

(defn- matching
  "Rules that fire for `inp` at `state`: exact-kind rules override the `:_` wildcard fallback; arity-filtered."
  [tt state inp]
  (let [a        (node/arity inp)
        specific (get (:index tt) [state (node/kind inp)] [])
        rs       (if (seq specific) specific (get (:index tt) [state :_] []))]
    (filterv (fn [r] (or (nil? (:arity r)) (= a (:arity r)))) rs)))

(defn transduce-node
  "seq of [output-node weight] for transducing `inp` at `state`."
  [tt state inp]
  (mapcat (fn [r]
            (map (fn [[o w]] [o (sr/times (:weight r) w)])
                 (eval-pattern tt (:out r) inp)))
          (matching tt state inp)))

(defn- cartesian
  "seq-of-colls → seq of vectors, choosing one element from each coll in order (empty coll ⇒ no combos)."
  [colls]
  (reduce (fn [acc coll] (for [xs acc x coll] (conj xs x))) [[]] colls))

(defn- child-slots
  "Expand a :build's child-specs against `inp` into a seq of slots — one per OUTPUT child, each a seq of
   [node weight] alternatives. `:all` fans out to one slot per input child."
  [tt specs inp]
  (mapcat (fn [spec]
            (if (= :all (:op spec))
              (map (fn [c] (transduce-node tt (:state spec) c)) (node/children inp))
              [(eval-pattern tt spec inp)]))
          specs))

(defn eval-pattern
  "Evaluate an output pattern against input node `inp` → seq of [output-node weight]."
  [tt pat inp]
  (case (:op pat)
    (:var :project) (transduce-node tt (:state pat) (nth (node/children inp) (:index pat)))
    :sub            (eval-pattern tt (:pattern pat) inp)
    :build
    (let [{:keys [kind text-fn meta-fn children]} pat
          k (if (fn? kind) (kind inp) kind)
          m (if meta-fn (meta-fn inp) {})
          leaf-out [[(node/leaf k (when text-fn (text-fn inp)) m) (:one tt)]]]
      (if (empty? children)
        leaf-out
        (let [slots (child-slots tt children inp)]
          (if (empty? slots)
            leaf-out                                      ; child-specs present but input had no children ⇒ leaf
            (map (fn [combo]
                   [(node/node k (mapv first combo) m)
                    (reduce sr/times (:one tt) (map second combo))])
                 (cartesian slots))))))))

(defn transduce
  "seq of [output-tree weight] for transducing `inp` from the start state."
  [tt inp] (transduce-node tt (:start tt) inp))

(defn best-output
  "The single ⊕-optimal output tree (deterministic lowering ⇒ exactly one derivation), or nil if none."
  [tt inp]
  (when-let [outs (seq (transduce tt inp))]
    (first (reduce (fn [[_ bw :as best] [_ w :as cur]] (if (sr/at-least-as-good? bw w) best cur))
                   outs))))

(defn compose-transduce
  "Exact sequential composition (tt1 ; tt2): transduce `inp` by tt1, then each output by tt2; weights ⊗."
  [tt1 tt2 inp]
  (for [[o1 w1] (transduce tt1 inp)
        [o2 w2] (transduce tt2 o1)]
    [o2 (sr/times w1 w2)]))

(defn identity-transducer
  "A transducer that reproduces its input tree exactly (one `:_` wildcard rule at :start that rebuilds each
   node and recurses all children). `one` is the semiring 1̄ (weight of every reproduced node)."
  [one]
  (transducer
    [(rule :start :_
           {:op :build :kind node/kind :text-fn node/text :meta-fn node/node-meta
            :children [{:op :all :state :start}]}
           one)]
    :start one))
