(ns vinary.ir.earley
  "Earley recognizer over a token LATTICE (blueprint: lling-llang src/cfg/). The classic predict / scan /
   complete chart parser, generalized so the Scan step follows lattice edges rather than a single string
   position — intersecting a CFG with a hypothesis DAG. Records back-links from which vinary.ir.forest
   extracts the packed parse forest. Handles ambiguity and (via nullable productions) ε-rules; forest
   extraction assumes an ε-free grammar. Pure + DOM-free.

   A production is {:lhs kw :rhs [sym…] :weight w}; a `sym` is a nonterminal (some production's :lhs) or a
   terminal (matched against lattice edge labels). An item is a dotted rule (lhs → rhs, dot, origin-node)."
  (:require [clojure.set :as set]
            [vinary.ir.lattice :as lat]))

(defrecord Grammar [start prods nts one])
(defn grammar
  "Build a grammar from `start` symbol, `productions`, and the semiring 1̄ `one` (weight of empty derivations)."
  [start productions one]
  (->Grammar start (group-by :lhs productions) (set (map :lhs productions)) one))
(defn terminal? [g sym] (not (contains? (:nts g) sym)))

(defrecord Item [lhs rhs dot origin])
(defn- item->     [prod origin] (->Item (:lhs prod) (:rhs prod) 0 origin))
(defn- next-sym   [it] (get (:rhs it) (:dot it)))
(defn- complete?  [it] (>= (:dot it) (count (:rhs it))))
(defn- advance-it [it] (->Item (:lhs it) (:rhs it) (inc (:dot it)) (:origin it)))

(defrecord Chart [items links g lattice])   ; items: vector of item-sets; links: {[item end] #{{:from :sub}}}

(defn parse
  "Run Earley over `lattice` with `g`; returns a Chart (item-sets per node + back-links for forest extraction)."
  [g lattice]
  (let [size  (:size lattice)
        chart (vec (repeatedly size #(atom #{})))
        links (atom {})
        add!  (fn [node it] (swap! (nth chart node) conj it))
        link! (fn [it end from sub] (swap! links update [it end] (fnil conj #{}) {:from from :sub sub}))]
    (doseq [prod (get (:prods g) (:start g))] (add! 0 (item-> prod 0)))
    (doseq [i (range size)]
      ;; predict + complete to a fixpoint at node i
      (loop [seen #{}]
        (let [cur @(nth chart i)
              fresh (set/difference cur seen)]
          (when (seq fresh)
            (doseq [it fresh]
              (if (complete? it)
                (doseq [pre @(nth chart (:origin it))
                        :when (= (next-sym pre) (:lhs it))]
                  (let [adv (advance-it pre)]
                    (add! i adv)
                    (link! adv i (:origin it) {:nt (:lhs it) :from (:origin it) :to i})))
                (let [s (next-sym it)]
                  (when (contains? (:nts g) s)
                    (doseq [prod (get (:prods g) s)] (add! i (item-> prod i)))))))
            (recur cur))))
      ;; scan: advance items whose dot precedes a terminal, following lattice edges out of node i
      (doseq [it @(nth chart i)
              :when (and (not (complete? it)) (terminal? g (next-sym it)))
              e  (lat/edges-out lattice i)
              :when (= (:label e) (next-sym it))]
        (let [adv (advance-it it)]
          (add! (:to e) adv)
          (link! adv (:to e) i {:t e}))))
    (->Chart (mapv deref chart) @links g lattice)))

(defn accepting-items
  "Completed start-symbol items spanning the whole lattice (origin 0 → final node)."
  [chart]
  (let [g (:g chart) final (dec (count (:items chart)))]
    (filter (fn [it] (and (= (:lhs it) (:start g)) (complete? it) (zero? (:origin it))))
            (nth (:items chart) final))))

(defn accepts? [chart] (boolean (seq (accepting-items chart))))

(defn recognize
  "Convenience: parse `tokens` (a linear lattice) with `g` and return the Chart."
  [g tokens]
  (parse g (lat/linear-lattice tokens (:one g))))
