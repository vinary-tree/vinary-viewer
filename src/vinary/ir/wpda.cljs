(ns vinary.ir.wpda
  "Weighted pushdown automaton (WPDA) — blueprint: lling-llang src/pushdown/. A WPDA is
   P = (Q, Σ, Γ, q₀, Z₀, F, Δ, ρ): states, input alphabet, stack alphabet, start state, initial stack,
   final states, weighted transition relation Δ, and final-weight fn ρ. Recognizes context-free structure
   (nesting) — the machinery behind ambiguity-ranked segmentation of messy inputs (PDF run→line→block,
   multi-line log records). This namespace defines the structures + single-step firing + acceptance + a
   builder with golden example grammars; the streaming exploration (ε-closure, decode, accept) lives in
   vinary.ir.decode. Pure + DOM-free.

   A configuration (instantaneous description) is (state, stack, accumulated-weight); the stack TOP is the
   last vector element (peek). A transition fires when its `from` state and `stack-top` match the config,
   consuming `input` (nil ⇒ an ε-transition), applying `action` to the stack, and ⊗-ing its `weight`."
  (:require [vinary.ir.semiring :as sr]))

;; ---- stack actions ----
(defrecord StackAction [op syms])   ; op ∈ #{:pop :push :replace :noop}; syms = vector for :push/:replace
(defn act-pop     []     (->StackAction :pop nil))
(defn act-push    [syms] (->StackAction :push (vec syms)))
(defn act-replace [syms] (->StackAction :replace (vec syms)))
(defn act-noop    []     (->StackAction :noop nil))

(defn apply-action
  "Apply `action` to `stack` (top = last). :push adds above the top; :replace swaps the top for `syms`
   (last of `syms` becomes the new top)."
  [stack action]
  (case (:op action)
    :noop    stack
    :pop     (pop stack)
    :push    (into stack (:syms action))
    :replace (into (pop stack) (:syms action))))

;; ---- transitions + configurations ----
(defrecord PdaTransition [from input stack-top to action weight])
(defrecord PdaConfiguration [state stack weight])

(defn stack-top  [stack] (when (seq stack) (peek stack)))
(defn config-key [cfg]   [(:state cfg) (:stack cfg)])

;; ---- the automaton ----
(defrecord VectorPda [index start initial-stack accept-mode finals final-weights one])

(defn pda
  "Build a WPDA. `transitions` are indexed by [from stack-top] for O(1) lookup. `accept-mode` ∈
   #{:final-state :empty-stack :both} (default :final-state). `one` = the semiring 1̄."
  [{:keys [transitions start initial-stack accept-mode finals final-weights one]}]
  (->VectorPda (group-by (juxt :from :stack-top) transitions)
               start (vec initial-stack) (or accept-mode :final-state)
               (set finals) (or final-weights {}) one))

(defn transitions-from [pda state stack] (get (:index pda) [state (stack-top stack)] []))
(defn eps-transitions  [pda cfg] (filterv #(nil? (:input %)) (transitions-from pda (:state cfg) (:stack cfg))))
(defn input-transitions [pda cfg input]
  (filterv #(= input (:input %)) (transitions-from pda (:state cfg) (:stack cfg))))

(defn fire
  "The config reached by firing `trans` from `cfg` (weight ⊗-accumulated)."
  [pda cfg trans]
  (->PdaConfiguration (:to trans)
                      (apply-action (:stack cfg) (:action trans))
                      (sr/times (:weight cfg) (:weight trans))))

(defn accepting?
  [pda cfg]
  (case (:accept-mode pda)
    :final-state (contains? (:finals pda) (:state cfg))
    :empty-stack (empty? (:stack cfg))
    :both        (and (contains? (:finals pda) (:state cfg)) (empty? (:stack cfg)))))

(defn final-weight
  "An accepting config's total weight: its accumulated weight ⊗ the state's final weight ρ (default 1̄)."
  [pda cfg]
  (sr/times (:weight cfg) (get (:final-weights pda) (:state cfg) (:one pda))))

(defn initial-config [pda] (->PdaConfiguration (:start pda) (:initial-stack pda) (:one pda)))

;; ---- golden example grammars (used as decoder tests) ----
(defn balanced-brackets-pda
  "L = balanced strings over ( ). Empty initial stack; push :o on `(`, pop on `)`; accept by empty stack
   (so the empty string — trivially balanced — is accepted). All transitions weighted `one`."
  [one]
  (pda {:start :q :initial-stack [] :accept-mode :empty-stack :one one :finals #{:q}
        :transitions
        [(->PdaTransition :q "(" nil :q (act-push [:o]) one)
         (->PdaTransition :q "(" :o  :q (act-push [:o]) one)
         (->PdaTransition :q ")" :o  :q (act-pop)       one)]}))

(defn a-n-b-n-pda
  "L = { aⁿbⁿ : n ≥ 1 }. Push :x per a in state :a; on the first b switch to :b and pop; pop per b.
   Accept in state :b with an empty stack (so ε and unbalanced strings are rejected)."
  [one]
  (pda {:start :a :initial-stack [] :accept-mode :both :finals #{:b} :one one
        :transitions
        [(->PdaTransition :a "a" nil :a (act-push [:x]) one)
         (->PdaTransition :a "a" :x  :a (act-push [:x]) one)
         (->PdaTransition :a "b" :x  :b (act-pop)       one)
         (->PdaTransition :b "b" :x  :b (act-pop)       one)]}))
