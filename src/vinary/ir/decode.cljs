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

(defn beam-prune
  "Keep only the `beam` ⊕-best configs (or all when `beam` is nil). This frontier-width bound is what keeps a
   streaming decode's working set bounded regardless of how much input has been consumed."
  [configs beam]
  (if (and beam (> (count configs) beam))
    (vec (take beam (sort #(cond (= (:weight %1) (:weight %2)) 0 (weight-better? %1 %2) -1 :else 1) configs)))
    configs))

(defn advance-step
  "One streaming step: consume `input` from `frontier`, then beam-prune. THE bounded-memory streaming
   primitive — hold the frontier yourself (e.g. in a stream controller) and call this per token: the frontier
   stays ≤ beam wide and each config's stack ≤ max-stack deep, so working memory is independent of input
   length. `decode` is just a fold of this over a whole input."
  ([pda frontier input] (advance-step pda frontier input {}))
  ([pda frontier input {:keys [max-stack beam] :or {max-stack default-max-stack}}]
   (beam-prune (advance pda frontier input max-stack) beam)))

(defn decode
  "Fold `advance-step` over `inputs` from the initial config. Returns the frontier of live configs after
   consuming all inputs (NOT yet ε-closed for acceptance)."
  [pda inputs opts]
  (reduce (fn [frontier input] (advance-step pda frontier input opts))
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
