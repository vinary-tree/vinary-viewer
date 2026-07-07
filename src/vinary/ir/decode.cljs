(ns vinary.ir.decode
  "Streaming / incremental WPDA decoder — blueprint: lling-llang src/pushdown/decode.rs PdaDecoder. Drives a
   WPDA one input symbol at a time (the pushdown counterpart of grammar-constrained generation):
   `legal-next` enumerates the terminals some live config may legally consume, `advance` consumes one,
   `accepts?`/`accepting-weight` test the final frontier. A bounded ε-closure (capped stack depth + a
   shortest-distance relaxation over configs) tames ε-push cycles; an optional `beam` bounds the frontier
   for weighted ranking. Pure + DOM-free.

   The ε-closure keeps, per (state, stack) config, the ⊕-optimal accumulated weight, relaxing on
   improvement — a shortest-distance computation that terminates because the config space is finite
   (finite states × stacks bounded by `max-stack`) and ⊕ is monotone for the idempotent semirings
   (Boolean/Tropical) used to rank segmentations."
  (:require [vinary.ir.semiring :as sr]
            [vinary.ir.wpda :as wpda]))

(def ^:const default-max-stack 4096)

(defn eps-closure
  "All configs reachable from `configs` by ε-transitions (bounded by `max-stack`), deduped by (state, stack)
   keeping the ⊕-best weight. Returns a vector of configs."
  [pda configs max-stack]
  (loop [work (vec configs) best {}]
    (if (empty? work)
      (mapv (fn [[[st stk] w]] (wpda/->PdaConfiguration st stk w)) best)
      (let [cfg  (peek work)
            work (pop work)
            k    (wpda/config-key cfg)
            w    (:weight cfg)
            prev (get best k)]
        (if (and prev (sr/at-least-as-good? prev w))
          (recur work best)                                  ; already have an equal-or-better route to k
          (let [best (assoc best k (if prev (sr/plus prev w) w))
                succ (for [t (wpda/eps-transitions pda cfg)
                           :let [nc (wpda/fire pda cfg t)]
                           :when (<= (count (:stack nc)) max-stack)]
                       nc)]
            (recur (into work succ) best)))))))

(defn legal-next
  "The set of input terminals that some config in the ε-closure of `configs` may legally consume next."
  [pda configs max-stack]
  (into #{}
        (comp (mapcat #(wpda/transitions-from pda (:state %) (:stack %)))
              (keep :input))
        (eps-closure pda configs max-stack)))

(defn advance
  "Consume one `input` symbol: ε-close `configs`, fire every matching input transition, ε-close the results."
  [pda configs input max-stack]
  (let [closed  (eps-closure pda configs max-stack)
        stepped (for [cfg closed t (wpda/input-transitions pda cfg input)] (wpda/fire pda cfg t))]
    (eps-closure pda stepped max-stack)))

(defn- weight-better?
  "Comparator: is config a's weight ⊕-preferable to b's? (for beam ordering)"
  [a b] (sr/at-least-as-good? (:weight a) (:weight b)))

(defn- beam-prune [configs beam]
  (if (and beam (> (count configs) beam))
    (vec (take beam (sort #(cond (= (:weight %1) (:weight %2)) 0 (weight-better? %1 %2) -1 :else 1) configs)))
    configs))

(defn decode
  "Fold `advance` over `inputs` from the initial config, beam-pruning the frontier each step. Returns the
   frontier of live configs after consuming all inputs (NOT yet ε-closed for acceptance)."
  [pda inputs {:keys [max-stack beam] :or {max-stack default-max-stack}}]
  (reduce (fn [frontier input] (beam-prune (advance pda frontier input max-stack) beam))
          [(wpda/initial-config pda)]
          inputs))

(defn accepting-configs
  "The accepting configs in the ε-closure of the frontier after decoding `inputs`."
  [pda inputs opts]
  (let [ms (:max-stack opts default-max-stack)]
    (filterv #(wpda/accepting? pda %) (eps-closure pda (decode pda inputs opts) ms))))

(defn accepts?
  "Does the WPDA accept `inputs` (consumes all of them and ends in an accepting config)?"
  ([pda inputs] (accepts? pda inputs {}))
  ([pda inputs opts] (boolean (seq (accepting-configs pda inputs opts)))))

(defn accepting-weight
  "The ⊕-fold of the accepting configs' final weights (best-cost for Tropical, total mass for Prob/Log),
   or nil if `inputs` is not accepted."
  ([pda inputs] (accepting-weight pda inputs {}))
  ([pda inputs opts]
   (when-let [acc (seq (accepting-configs pda inputs opts))]
     (sr/best (map #(wpda/final-weight pda %) acc)))))
