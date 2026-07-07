(ns vinary.ir.semiring
  "Semiring-generic weights — the algebra (K, ⊕ plus, ⊗ times, 0̄ zero, 1̄ one) that lets ONE parser or
   transducer compute best-derivation, total mass, reachability, or multi-objective results just by
   swapping the weight type (Goodman, `Semiring Parsing`, 1999; Mohri, `Semiring Frameworks and Algorithms
   for Shortest-Distance Problems`, 2002). Ported from the lling-llang blueprint (`src/semiring/`).
   Pure + DOM-free (node-testable).

   Every weight type upholds the semiring axioms (checked in semiring-test): ⊕ is associative + commutative
   with identity 0̄; ⊗ is associative with identity 1̄ and annihilator 0̄; ⊗ distributes over ⊕. Boolean and
   Tropical are additionally idempotent (⊕ selective), giving the natural order used for best-parse.")

(defprotocol Semiring
  "zero/one dispatch on the weight's concrete type (the argument's value is used only for its type)."
  (plus  [a b] "⊕ — combine alternative derivations")
  (times [a b] "⊗ — combine sequential steps")
  (zero  [a]   "additive identity 0̄ (⊕-identity, ⊗-annihilator)")
  (one   [a]   "multiplicative identity 1̄"))

;; ---- Boolean (∨, ∧, ⊥, ⊤): recognition — 'is there ANY valid derivation?' (idempotent) ----
(defrecord BoolW [v]
  Semiring
  (plus  [_ b] (->BoolW (or v (:v b))))
  (times [_ b] (->BoolW (and v (:v b))))
  (zero  [_]   (->BoolW false))
  (one   [_]   (->BoolW true)))
(defn bool [v] (->BoolW (boolean v)))

;; ---- Tropical (min, +, +∞, 0): best / shortest derivation — Viterbi in −log space (idempotent) ----
(defrecord TropicalW [c]
  Semiring
  (plus  [_ b] (->TropicalW (min c (:c b))))
  (times [_ b] (->TropicalW (+ c (:c b))))
  (zero  [_]   (->TropicalW js/Infinity))
  (one   [_]   (->TropicalW 0)))
(defn tropical [c] (->TropicalW c))

;; ---- Probability (+, ×, 0, 1): total probability mass ----
(defrecord ProbW [p]
  Semiring
  (plus  [_ b] (->ProbW (+ p (:p b))))
  (times [_ b] (->ProbW (* p (:p b))))
  (zero  [_]   (->ProbW 0.0))
  (one   [_]   (->ProbW 1.0)))
(defn prob [p] (->ProbW p))

;; ---- Log (⊕ = −log(e^−a + e^−b), ⊗ = +, +∞, 0): total mass in −log space (numerically-stable log-sum-exp) ----
(defrecord LogW [x]
  Semiring
  (plus  [_ b] (let [y (:x b)]
                 (cond
                   (= x js/Infinity) (->LogW y)
                   (= y js/Infinity) (->LogW x)
                   :else (let [m (min x y)]
                           (->LogW (- m (js/Math.log (+ (js/Math.exp (- m x))
                                                        (js/Math.exp (- m y))))))))))
  (times [_ b] (->LogW (+ x (:x b))))
  (zero  [_]   (->LogW js/Infinity))
  (one   [_]   (->LogW 0)))
(defn logw [x] (->LogW x))

;; ---- Product (componentwise): multi-objective — carry two semirings at once ----
(defrecord ProductW [a b]
  Semiring
  (plus  [_ o] (->ProductW (plus a (:a o)) (plus b (:b o))))
  (times [_ o] (->ProductW (times a (:a o)) (times b (:b o))))
  (zero  [_]   (->ProductW (zero a) (zero b)))
  (one   [_]   (->ProductW (one a) (one b))))
(defn product [a b] (->ProductW a b))

;; ---- Lexicographic (ordered pair): tie-break — ⊕ selects by the primary (idempotent) component,
;;      merging secondaries on a tie; ⊗ componentwise. Meaningful when the primary component is selective. ----
(defrecord LexW [a b]
  Semiring
  (plus  [_ o] (let [oa (:a o)]
                 (cond
                   (= a oa)          (->LexW a (plus b (:b o)))   ; tie on the primary → merge secondaries
                   (= (plus a oa) a) (->LexW a b)                 ; primary a is ⊕-optimal → keep this pair
                   :else             (->LexW oa (:b o)))))
  (times [_ o] (->LexW (times a (:a o)) (times b (:b o))))
  (zero  [_]   (->LexW (zero a) (zero b)))
  (one   [_]   (->LexW (one a) (one b))))
(defn lex [a b] (->LexW a b))

;; ---- helpers ----
(defn at-least-as-good?
  "True iff `a` is ⊕-optimal of {a,b}: a ⊕ b = a. For Tropical this is a ≤ b (cost); for Boolean, a ∨ b = a."
  [a b] (= (plus a b) a))

(defn natural-less?
  "Strict natural order: a ≺ b iff (a ⊕ b) = a and a ≠ b. Well-defined for idempotent (selective) semirings
   (Boolean, Tropical, Lexicographic-over-idempotent); returns false otherwise."
  [a b] (and (not= a b) (= (plus a b) a)))

(defn best
  "The ⊕-fold of a NON-EMPTY seq of same-type weights. For a selective (idempotent) ⊕ this is the argmin/
   argmax; for a non-selective ⊕ it is the ⊕-sum (e.g. total probability mass)."
  [ws] (reduce plus ws))

(defn approx=
  "Float-tolerant equality for weights carrying doubles (Tropical/Prob/Log), for tests."
  ([a b] (approx= a b 1e-9))
  ([a b eps]
   (letfn [(close? [x y] (or (= x y) (< (js/Math.abs (- x y)) eps)))]
     (cond
       (and (instance? TropicalW a) (instance? TropicalW b)) (close? (:c a) (:c b))
       (and (instance? ProbW a)     (instance? ProbW b))     (close? (:p a) (:p b))
       (and (instance? LogW a)      (instance? LogW b))      (close? (:x a) (:x b))
       (and (instance? ProductW a)  (instance? ProductW b))  (and (approx= (:a a) (:a b) eps) (approx= (:b a) (:b b) eps))
       (and (instance? LexW a)      (instance? LexW b))       (and (approx= (:a a) (:a b) eps) (approx= (:b a) (:b b) eps))
       :else (= a b)))))
