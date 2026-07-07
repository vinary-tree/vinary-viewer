(ns vinary.ir.forest
  "Packed parse forest extraction from an Earley chart (vinary.ir.earley) — blueprint: lling-llang
   src/cfg/forest.rs. `all-parses` enumerates every derivation of the start symbol (shared sub-derivations
   are memoized so the exponential set stays compact); `best-parse` extracts the ⊕-optimal derivation by the
   grammar/edge semiring weights (Viterbi over the forest). A parse tree is {:label sym :children [tree…]}
   for a nonterminal and {:label t :terminal t} for a terminal leaf. Assumes an ε-free grammar. Pure + DOM-free."
  (:require [vinary.ir.semiring :as sr]
            [vinary.ir.earley :as earley]))

(declare parse-sym)

(defn- build-children
  "seq of [children-vector weight] for the ways `item`'s dot walked from 0 to `end` (following back-links)."
  [chart cache item end]
  (let [ck [:children item end]]
    (or (get @cache ck)
        (let [res (if (zero? (:dot item))
                    [[[] (:one (:g chart))]]                       ; dot 0 ⇒ no children yet, weight 1̄
                    (vec (mapcat
                           (fn [{:keys [from sub]}]
                             (let [prev (assoc item :dot (dec (:dot item)))]
                               (for [[pre-ch w1] (build-children chart cache prev from)
                                     [child w2]  (if-let [e (:t sub)]
                                                   [[{:label (:label e) :terminal (:label e)} (:weight e)]]
                                                   (parse-sym chart cache (:nt sub) (:from sub) (:to sub)))]
                                 [(conj pre-ch child) (sr/times w1 w2)])))
                           (get (:links chart) [item end]))))]
          (swap! cache assoc ck res)
          res))))

(defn parse-sym
  "seq of [tree weight] deriving nonterminal `sym` over lattice span [from,to]."
  [chart cache sym from to]
  (let [ck [:sym sym from to]]
    (or (get @cache ck)
        (let [g   (:g chart)
              res (vec (mapcat
                         (fn [prod]
                           (let [ci (earley/->Item sym (:rhs prod) (count (:rhs prod)) from)]
                             (when (contains? (nth (:items chart) to) ci)
                               (for [[children w] (build-children chart cache ci to)]
                                 [{:label sym :children children} (sr/times w (:weight prod))]))))
                         (get (:prods g) sym)))]
          (swap! cache assoc ck res)
          res))))

(defn all-parses
  "All [tree weight] derivations of the start symbol spanning the whole lattice."
  [chart]
  (let [g (:g chart) final (dec (count (:items chart)))]
    (parse-sym chart (atom {}) (:start g) 0 final)))

(defn best-parse
  "The single ⊕-optimal [tree weight] (Viterbi over the packed forest), or nil if not recognized."
  [chart]
  (when-let [ps (seq (all-parses chart))]
    (reduce (fn [[_ bw :as best] [_ w :as cur]] (if (sr/at-least-as-good? bw w) best cur)) ps)))

(defn parse-count "Number of distinct derivations of the whole input." [chart] (count (all-parses chart)))
