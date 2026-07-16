(ns vinary.ir.forest-test
  "The packed-forest Viterbi (ir.forest/viterbi-parse): the ⊕-optimal derivation computed by polynomial DP
   (best per (sym,from,to) node), NOT the exponential enumerate-all-then-min of `best-parse`. Tropical weights,
   so 'optimal' = minimum total cost — the property the ambiguous PDF-reflow segmentation relies on."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.semiring :as sr]
            [vinary.ir.lattice :as lat]
            [vinary.ir.earley :as earley]
            [vinary.ir.forest :as forest]))

(deftest viterbi-picks-min-cost
  (testing "an ambiguous grammar (two derivations of the span) — viterbi returns the cheaper one"
    (let [one     (sr/tropical 0)
          ;; span 0→2 two ways: 0-a(1)->1-a(1)->2 (total 2) OR 0-b(10)->2 (total 10). S → a S | a | b.
          edges   [{:from 0 :to 1 :label :a :weight (sr/tropical 1)}
                   {:from 1 :to 2 :label :a :weight (sr/tropical 1)}
                   {:from 0 :to 2 :label :b :weight (sr/tropical 10)}]
          lattice (lat/from-edges 3 edges)
          g       (earley/grammar :S [{:lhs :S :rhs [:a :S] :weight one}
                                      {:lhs :S :rhs [:a]    :weight one}
                                      {:lhs :S :rhs [:b]    :weight one}] one)
          chart   (earley/parse g lattice)
          [tree w] (forest/viterbi-parse chart)]
      (is (some? tree) "input recognized")
      (is (= (sr/tropical 2) w) "the two-a path (1+1) beats the single b (10)")
      (is (> (forest/parse-count chart) 1) "the grammar is genuinely ambiguous over this lattice"))))

(deftest viterbi-nil-when-unrecognized
  (testing "viterbi-parse returns nil for input the grammar cannot derive"
    (let [one     (sr/tropical 0)
          lattice (lat/from-edges 2 [{:from 0 :to 1 :label :x :weight one}])
          g       (earley/grammar :S [{:lhs :S :rhs [:y] :weight one}] one)]
      (is (nil? (forest/viterbi-parse (earley/parse g lattice)))))))
