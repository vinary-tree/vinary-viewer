(ns vinary.ir.lattice
  "A token lattice — a weighted DAG of alternative token hypotheses that the Earley parser (vinary.ir.earley)
   intersects a CFG with. A LINEAR chain encodes an unambiguous token string; BRANCHING encodes ambiguous
   segmentations (e.g. alternative PDF run→line or multi-line log-record groupings), each edge carrying a
   semiring weight so the parse forest can be ranked. Nodes are integers 0..size-1. Pure + DOM-free."
  (:require [vinary.ir.semiring :as sr]))

(defrecord Lattice [size out])            ; out: vector; out[i] = vector of edges {:to :label :weight}

(defn edges-out [lat i] (get (:out lat) i []))

(defn linear-lattice
  "A straight chain 0→1→…→n over `tokens` (each edge weight `one`) — an unambiguous input string."
  [tokens one]
  (let [n (count tokens)]
    (->Lattice (inc n)
               (conj (mapv (fn [i tok] [{:to (inc i) :label tok :weight one}]) (range n) tokens)
                     []))))

(defn from-edges
  "A lattice with `size` nodes from explicit edges {:from :to :label :weight} — for ambiguous hypothesis DAGs."
  [size edges]
  (->Lattice size
             (reduce (fn [out {:keys [from] :as e}] (update out from (fnil conj []) (dissoc e :from)))
                     (vec (repeat size []))
                     edges)))
