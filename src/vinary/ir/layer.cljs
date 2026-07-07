(ns vinary.ir.layer
  "A composable IR→IR transform pipeline (blueprint: lling-llang src/layers/ LayerPipeline). A layer is
   simply a fn IR-node → IR-node (a deterministic normalization, or a wrapper around a weighted-transducer
   stage that extracts its best output). `run` threads a node through the layers left-to-right; each stage
   is independently testable. Pure + DOM-free.")

(defrecord Pipeline [names fns])

(defn pipeline
  "Bundle stages into a pipeline. Each stage is either a bare fn (IR→IR) or a [name fn] pair (name aids
   debugging / logging)."
  [& stages]
  (let [pairs (map-indexed (fn [i s] (if (vector? s) s [(str "stage-" i) s])) stages)]
    (->Pipeline (mapv first pairs) (mapv second pairs))))

(defn run
  "Thread `tree` through the pipeline's stages left-to-right."
  [pl tree]
  (reduce (fn [t f] (f t)) tree (:fns pl)))

(defn run-fns
  "Thread `tree` through a bare seq of IR→IR fns left-to-right."
  [fns tree]
  (reduce (fn [t f] (f t)) tree fns))
