(ns vinary.ir.semiring-test
  "Law tests for the semiring weights (vinary.ir.semiring). Every weight type must satisfy the semiring
   axioms — ⊕ associative+commutative with identity 0̄; ⊗ associative with identity 1̄ and annihilator 0̄;
   ⊗ distributes over ⊕ — because those axioms are exactly what let one weighted transducer/parser compute
   best-path / total-mass / recognition merely by swapping the weight type. Boolean and Tropical are
   additionally idempotent (⊕ selective), giving the natural order that best-parse relies on."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.semiring :as s]))

(defn- check-laws
  "Assert the semiring axioms hold for three same-type sample weights, comparing with `eq`."
  [a b c eq]
  (let [z (s/zero a) o (s/one a)]
    (is (eq (s/plus a b) (s/plus b a)) "⊕ commutative")
    (is (eq (s/plus (s/plus a b) c) (s/plus a (s/plus b c))) "⊕ associative")
    (is (eq (s/plus a z) a) "0̄ is the ⊕-identity")
    (is (eq (s/times (s/times a b) c) (s/times a (s/times b c))) "⊗ associative")
    (is (eq (s/times a o) a) "1̄ is the ⊗-identity (right)")
    (is (eq (s/times o a) a) "1̄ is the ⊗-identity (left)")
    (is (eq (s/times a z) z) "0̄ annihilates ⊗ (right)")
    (is (eq (s/times z a) z) "0̄ annihilates ⊗ (left)")
    (is (eq (s/times a (s/plus b c)) (s/plus (s/times a b) (s/times a c))) "⊗ left-distributes over ⊕")
    (is (eq (s/times (s/plus b c) a) (s/plus (s/times b a) (s/times c a))) "⊗ right-distributes over ⊕")))

(deftest boolean-laws
  (check-laws (s/bool true) (s/bool false) (s/bool true) =)
  (testing "recognition semantics + idempotency"
    (is (= (s/bool true)  (s/one (s/bool false))) "1̄ = ⊤")
    (is (= (s/bool false) (s/zero (s/bool true))) "0̄ = ⊥")
    (is (= (s/bool true)  (s/plus (s/bool true) (s/bool true))) "⊕ idempotent")
    (is (= (s/bool false) (s/plus (s/bool false) (s/bool false))))))

(deftest tropical-laws
  (check-laws (s/tropical 3) (s/tropical 5) (s/tropical 2) =)
  (testing "best/shortest semantics + idempotency"
    (is (= (s/tropical 2) (s/plus (s/tropical 5) (s/tropical 2))) "⊕ = min (picks the cheaper)")
    (is (= (s/tropical 8) (s/times (s/tropical 3) (s/tropical 5))) "⊗ = + (adds costs)")
    (is (= (s/tropical 3) (s/plus (s/tropical 3) (s/tropical 3))) "⊕ idempotent")
    (is (= (s/tropical js/Infinity) (s/times (s/tropical 3) (s/zero (s/tropical 0)))) "+∞ annihilates")))

(deftest probability-laws
  (check-laws (s/prob 0.2) (s/prob 0.5) (s/prob 0.3) s/approx=)
  (testing "mass semantics"
    (is (s/approx= (s/prob 0.7) (s/plus (s/prob 0.2) (s/prob 0.5))) "⊕ sums mass")
    (is (s/approx= (s/prob 0.1) (s/times (s/prob 0.2) (s/prob 0.5))) "⊗ multiplies")))

(deftest log-laws
  (check-laws (s/logw 0.4) (s/logw 1.1) (s/logw 0.7) s/approx=)
  (testing "log-space mass (⊗ adds in −log; ⊕ is log-sum-exp)"
    (is (s/approx= (s/logw 0.9) (s/times (s/logw 0.4) (s/logw 0.5))))
    ;; combining two equal −log(p)=0 weights (p=1 each) → −log(2)
    (is (s/approx= (s/logw (- (js/Math.log 2))) (s/plus (s/logw 0) (s/logw 0))))
    (is (s/approx= (s/logw 0) (s/plus (s/logw 0) (s/zero (s/logw 0)))) "+∞ is ⊕-identity")))

(deftest product-laws
  (check-laws (s/product (s/bool true) (s/tropical 3))
              (s/product (s/bool false) (s/tropical 5))
              (s/product (s/bool true) (s/tropical 2))
              s/approx=)
  (testing "componentwise"
    (is (= (s/product (s/bool true) (s/tropical 2))
           (s/plus (s/product (s/bool true) (s/tropical 5))
                   (s/product (s/bool false) (s/tropical 2)))) "⊕ each component independently")))

(deftest lexicographic-laws
  ;; primary = Tropical (selective), secondary = Probability
  (check-laws (s/lex (s/tropical 3) (s/prob 0.2))
              (s/lex (s/tropical 5) (s/prob 0.5))
              (s/lex (s/tropical 2) (s/prob 0.3))
              s/approx=)
  (testing "primary decides; ties merge the secondary"
    (is (= (s/lex (s/tropical 2) (s/prob 0.3))
           (s/plus (s/lex (s/tropical 5) (s/prob 0.5)) (s/lex (s/tropical 2) (s/prob 0.3)))) "smaller primary wins")
    (is (s/approx= (s/lex (s/tropical 3) (s/prob 0.7))
                   (s/plus (s/lex (s/tropical 3) (s/prob 0.2)) (s/lex (s/tropical 3) (s/prob 0.5)))) "tie merges secondaries")))

(deftest natural-order-and-best
  (testing "natural-less? / at-least-as-good? on selective semirings"
    (is (s/natural-less? (s/tropical 2) (s/tropical 5)))
    (is (not (s/natural-less? (s/tropical 5) (s/tropical 2))))
    (is (not (s/natural-less? (s/tropical 3) (s/tropical 3))))
    (is (s/at-least-as-good? (s/tropical 2) (s/tropical 5)))
    (is (s/natural-less? (s/bool true) (s/bool false)) "⊤ ≺ ⊥ in the recognition order"))
  (testing "best = ⊕-fold"
    (is (= (s/tropical 1) (s/best [(s/tropical 4) (s/tropical 1) (s/tropical 7)])) "Tropical best = min")
    (is (= (s/bool true)  (s/best [(s/bool false) (s/bool true) (s/bool false)])) "Boolean best = any")
    (is (s/approx= (s/prob 1.0) (s/best [(s/prob 0.25) (s/prob 0.25) (s/prob 0.5)])) "Probability best = sum")))
