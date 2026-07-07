(ns vinary.ir.decode-test
  "Tests for the streaming decoder primitive (vinary.ir.decode/advance-step): folding it per-symbol equals the
   whole-input `decode` (streaming == batch), incremental acceptance matches, and — the load-bearing property —
   the beam bounds the frontier width regardless of input length (bounded-memory streaming)."
  (:refer-clojure :exclude [chars])   ; local `chars` helper (string → single-char-string tokens)
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.semiring :as sr]
            [vinary.ir.wpda :as wpda]
            [vinary.ir.decode :as dec]))

(defn- chars [s] (mapv str s))
(def ^:private T1 (sr/bool true))

(deftest advance-step-folds-to-decode
  (testing "folding advance-step per symbol == decode over the whole input"
    (let [p (wpda/balanced-brackets-pda T1)]
      (doseq [s ["" "()" "(())" "()()" "((()))"]]
        (let [streamed (reduce (fn [f c] (dec/advance-step p f c {})) [(wpda/initial-config p)] (chars s))
              batch    (dec/decode p (chars s) {})]
          (is (= (set (map wpda/config-key streamed)) (set (map wpda/config-key batch)))
              (str "streamed frontier == batch frontier for " (pr-str s))))))))

(deftest streaming-acceptance-matches-batch
  (testing "incremental consumption accepts exactly the language decode/accepts? does"
    (let [p (wpda/a-n-b-n-pda T1)]
      (doseq [[s ok?] [["ab" true] ["aabb" true] ["aaabbb" true] ["abab" false] ["aab" false] ["" false]]]
        (is (= ok? (dec/accepts? p (chars s) {})) (str "aⁿbⁿ membership of " (pr-str s)))))))

(deftest bounded-frontier-under-beam
  (testing "the beam caps the streaming frontier width no matter how much input is consumed"
    (let [zero (sr/tropical 0) one (sr/tropical 1)
          ;; each 'a' forks the stack (push :x cost 0 OR push :y cost 1) → 2^n configs WITHOUT a beam
          p (wpda/pda {:start :q :initial-stack [] :accept-mode :final-state :finals #{:q} :one zero
                       :transitions [(wpda/->PdaTransition :q "a" nil :q (wpda/act-push [:x]) zero)
                                     (wpda/->PdaTransition :q "a" :x  :q (wpda/act-push [:x]) zero)
                                     (wpda/->PdaTransition :q "a" :y  :q (wpda/act-push [:x]) zero)
                                     (wpda/->PdaTransition :q "a" :x  :q (wpda/act-push [:y]) one)
                                     (wpda/->PdaTransition :q "a" :y  :q (wpda/act-push [:y]) one)]})
          beam 4]
      (loop [f [(wpda/initial-config p)] n 0]
        (is (<= (count f) beam) (str "frontier ≤ beam (" beam ") after " n " symbols"))
        (when (< n 60)
          (recur (dec/advance-step p f "a" {:beam beam}) (inc n)))))))
