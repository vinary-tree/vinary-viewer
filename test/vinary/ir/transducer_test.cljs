(ns vinary.ir.transducer-test
  "Unit tests for the weighted tree transducer (vinary.ir.transducer): identity round-trip, wildcard-override
   relabeling, semiring weight threading (Tropical cost = sum of fired-rule costs), and exact sequential
   composition. This engine drives both IR→IR normalization and the deterministic IR→HTML lowering."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.semiring :as sr]
            [vinary.ir.transducer :as t]))

(def ^:private doc
  (node/node :document
    [(node/node :heading   [(node/leaf :text "Intro")] {:level 1})
     (node/node :paragraph [(node/leaf :text "Hello ") (node/leaf :text "world")])
     (node/node :heading   [(node/leaf :text "End")] {:level 2})]))

(def ^:private id-child {:op :all :state :start})
(defn- id-out [] {:op :build :kind node/kind :text-fn node/text :meta-fn node/node-meta :children [id-child]})

(deftest identity-round-trip
  (let [tt (t/identity-transducer (sr/bool true))]
    (is (= doc (t/best-output tt doc)) "identity transducer reproduces the tree exactly")
    (is (= 1 (count (t/transduce tt doc))) "one derivation")
    (is (= (sr/bool true) (second (first (t/transduce tt doc)))) "weight 1̄")))

(deftest wildcard-override-relabel
  (let [one (sr/bool true)
        relabel (t/transducer
                  [(t/rule :start :paragraph
                           {:op :build :kind :block :meta-fn node/node-meta :children [id-child]} one)
                   (t/rule :start :_ (id-out) one)]   ; identity fallback for every other kind
                  :start one)
        out (t/best-output relabel doc)]
    (is (= :block (node/kind (second (node/children out)))) "specific :paragraph rule overrides the :_ fallback")
    (is (= :heading (node/kind (first (node/children out)))) ":heading falls through to identity")
    (is (= "IntroHello worldEnd" (node/text-content out)) "text preserved through relabel")))

(deftest weighted-cost-threading
  (let [zero (sr/tropical 0)
        costed (t/transducer
                 [(t/rule :start :heading {:op :build :kind :heading :meta-fn node/node-meta :children [id-child]} (sr/tropical 2))
                  (t/rule :start :_ (id-out) zero)]
                 :start zero)
        [out w] (first (t/transduce costed doc))]
    (is (= doc out) "structure preserved (kinds unchanged)")
    (is (= 4 (:c w)) "Tropical cost = sum of the two heading rules' costs (2 + 2)")))

(deftest composition
  (let [one (sr/bool true)
        id  (t/identity-transducer one)
        relabel (t/transducer
                  [(t/rule :start :paragraph {:op :build :kind :block :meta-fn node/node-meta :children [id-child]} one)
                   (t/rule :start :_ (id-out) one)]
                  :start one)
        composed (t/compose-transduce id relabel doc)]
    (is (= 1 (count composed)))
    (let [[out _] (first composed)]
      (is (= :block (node/kind (second (node/children out)))) "(id ; relabel) == relabel"))))

(deftest ambiguity-and-best
  (testing "two rules on the same kind → two derivations; best-output picks the ⊕-optimal by weight"
    (let [tt (t/transducer
               [(t/rule :start :heading {:op :build :kind :h-cheap :meta-fn node/node-meta :children [id-child]} (sr/tropical 1))
                (t/rule :start :heading {:op :build :kind :h-dear  :meta-fn node/node-meta :children [id-child]} (sr/tropical 9))
                (t/rule :start :_ (id-out) (sr/tropical 0))]
               :start (sr/tropical 0))
          h  (node/node :heading [(node/leaf :text "X")] {})]
      (is (= 2 (count (t/transduce tt h))) "two derivations for the heading")
      (is (= :h-cheap (node/kind (t/best-output tt h))) "best = the cheaper (Tropical min)"))))
