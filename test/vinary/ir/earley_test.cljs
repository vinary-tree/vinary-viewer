(ns vinary.ir.earley-test
  "Unit tests for the Earley-over-lattice parser (vinary.ir.earley) + packed forest (vinary.ir.forest):
   recognition, ambiguity packing (the classic ambiguous grammar S → S S | a has Catalan-many parses),
   Viterbi best-parse under semiring weights, and parsing over a branching lattice (alternative
   segmentations) — the machinery that ranks ambiguous PDF/log groupings."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.semiring :as sr]
            [vinary.ir.lattice :as lat]
            [vinary.ir.earley :as earley]
            [vinary.ir.forest :as forest]))

(def ^:private one (sr/bool true))

(deftest recognition
  (testing "right-recursive S → a S | a accepts a⁺"
    (let [g (earley/grammar :S [{:lhs :S :rhs ["a" :S] :weight one}
                                {:lhs :S :rhs ["a"]     :weight one}] one)]
      (is (earley/accepts? (earley/recognize g ["a"])))
      (is (earley/accepts? (earley/recognize g ["a" "a" "a"])))
      (is (not (earley/accepts? (earley/recognize g [])))    "empty rejected")
      (is (not (earley/accepts? (earley/recognize g ["b"]))) "unknown terminal rejected"))))

(deftest ambiguity-packing
  (testing "S → S S | a has Catalan(n-1) parses of aⁿ"
    (let [g (earley/grammar :S [{:lhs :S :rhs [:S :S] :weight one}
                                {:lhs :S :rhs ["a"]   :weight one}] one)]
      (is (= 1 (forest/parse-count (earley/recognize g ["a"]))))
      (is (= 1 (forest/parse-count (earley/recognize g ["a" "a"]))))
      (is (= 2 (forest/parse-count (earley/recognize g ["a" "a" "a"]))) "C₂ = 2")
      (is (= 5 (forest/parse-count (earley/recognize g ["a" "a" "a" "a"]))) "C₃ = 5")
      (is (= "a" (get-in (first (first (forest/all-parses (earley/recognize g ["a"]))))
                         [:children 0 :terminal])) "terminal leaf carries its token"))))

(deftest viterbi-best-parse
  (testing "best-parse picks the ⊕-optimal derivation by Tropical cost"
    (let [zero (sr/tropical 0)
          ;; Two ways to derive a `pair`: cheap NN (cost 1) vs dear WIDE (cost 9); prefer the cheap one.
          g (earley/grammar :S
              [{:lhs :S    :rhs [:pair]        :weight zero}
               {:lhs :pair :rhs ["a" "a"]      :weight (sr/tropical 1)}
               {:lhs :pair :rhs [:wide]        :weight zero}
               {:lhs :wide :rhs ["a" "a"]      :weight (sr/tropical 9)}] zero)
          chart (earley/recognize g ["a" "a"])
          [tree w] (forest/best-parse chart)]
      (is (= 2 (forest/parse-count chart)) "two derivations")
      (is (= 1 (:c w)) "best cost = 1 (the cheap :pair rule)")
      (is (= :pair (get-in tree [:children 0 :label])))
      (is (= "aa" (apply str (map :terminal (get-in tree [:children 0 :children])))) "leaves preserved"))))

(deftest branching-lattice
  (testing "a lattice offering EITHER two 'a' tokens OR one 'aa' token; grammar accepts both, forest packs 2"
    (let [;; nodes 0,1,2 ; edges: 0-a->1, 1-a->2  AND  0-aa->2
          lattice (lat/from-edges 3 [{:from 0 :to 1 :label "a"  :weight one}
                                     {:from 1 :to 2 :label "a"  :weight one}
                                     {:from 0 :to 2 :label "aa" :weight one}])
          g (earley/grammar :S [{:lhs :S :rhs ["a" "a"] :weight one}
                                {:lhs :S :rhs ["aa"]    :weight one}] one)
          chart (earley/parse g lattice)]
      (is (earley/accepts? chart))
      (is (= 2 (forest/parse-count chart)) "both segmentations parse"))))
