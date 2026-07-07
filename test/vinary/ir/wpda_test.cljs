(ns vinary.ir.wpda-test
  "Unit tests for the WPDA (vinary.ir.wpda) + its streaming decoder (vinary.ir.decode): recognition of the
   golden context-free grammars (balanced brackets, aⁿbⁿ — which no regular/finite-state machine can accept),
   incremental `legal-next`, bounded ε-closure termination, and semiring weight accumulation (Tropical
   best-cost). This is the machinery that will rank ambiguous PDF/log segmentations."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.semiring :as sr]
            [vinary.ir.wpda :as wpda]
            [vinary.ir.decode :as dec]))

(defn- chars [s] (mapv str s))                     ; "()" → ["(" ")"]
(def ^:private T1 (sr/bool true))                  ; recognition weight (1̄ of the Boolean semiring)

(deftest balanced-brackets-recognition
  (let [p (wpda/balanced-brackets-pda T1)]
    (testing "accepts balanced strings (incl. the empty string)"
      (doseq [s ["" "()" "(())" "()()" "((()))" "(()())"]]
        (is (dec/accepts? p (chars s)) (str "accept " (pr-str s)))))
    (testing "rejects unbalanced strings"
      (doseq [s ["(" ")" "(()" "())" ")(" "(()))"]]
        (is (not (dec/accepts? p (chars s))) (str "reject " (pr-str s)))))))

(deftest a-n-b-n-recognition
  (let [p (wpda/a-n-b-n-pda T1)]
    (testing "accepts aⁿbⁿ (n ≥ 1)"
      (doseq [s ["ab" "aabb" "aaabbb" "aaaabbbb"]]
        (is (dec/accepts? p (chars s)) (str "accept " (pr-str s)))))
    (testing "rejects everything else"
      (doseq [s ["" "a" "b" "aab" "abb" "ba" "aabbb" "abab"]]
        (is (not (dec/accepts? p (chars s))) (str "reject " (pr-str s)))))))

(deftest incremental-legal-next
  (let [p  (wpda/balanced-brackets-pda T1)
        f0 (dec/decode p [] {})]                    ; frontier before any input
    (is (= #{"("} (dec/legal-next p f0 dec/default-max-stack)) "from an empty stack only `(` is legal")
    (let [f1 (dec/advance p f0 "(" dec/default-max-stack)]
      (is (= #{"(" ")"} (dec/legal-next p f1 dec/default-max-stack)) "with an open bracket, both are legal"))))

(deftest weighted-best-cost
  (testing "Tropical accepting weight = number of `(` (each open costs 1)"
    (let [zero (sr/tropical 0) open (sr/tropical 1)
          p (wpda/pda {:start :q :initial-stack [] :accept-mode :empty-stack :one zero :finals #{:q}
                       :transitions
                       [(wpda/->PdaTransition :q "(" nil :q (wpda/act-push [:o]) open)
                        (wpda/->PdaTransition :q "(" :o  :q (wpda/act-push [:o]) open)
                        (wpda/->PdaTransition :q ")" :o  :q (wpda/act-pop)       zero)]})]
      (is (= 2 (:c (dec/accepting-weight p (chars "(())") {}))))
      (is (= 3 (:c (dec/accepting-weight p (chars "()()()"  ) {}))))
      (is (nil? (dec/accepting-weight p (chars "(()") {})) "unbalanced → no accepting weight"))))

(deftest eps-closure-terminates-and-bounds
  (testing "an ε-push cycle is bounded by max-stack (no infinite loop)"
    (let [one (sr/bool true)
          ;; q --ε,push :x--> q  (an unbounded ε-push cycle); accept immediately in state q
          p (wpda/pda {:start :q :initial-stack [] :accept-mode :final-state :finals #{:q} :one one
                       :transitions [(wpda/->PdaTransition :q nil nil :q (wpda/act-push [:x]) one)
                                     (wpda/->PdaTransition :q nil :x  :q (wpda/act-push [:x]) one)]})
          closed (dec/eps-closure p [(wpda/initial-config p)] 8)]
      (is (<= (count closed) 9) "at most one config per stack depth 0..8")
      (is (dec/accepts? p [] {:max-stack 8}) "still accepts (start state is final)"))))
