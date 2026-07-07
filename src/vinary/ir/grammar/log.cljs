(ns vinary.ir.grammar.log
  "WPDA for streaming log-record segmentation. The pushdown STACK tracks brace nesting, so a multi-line
   JSON/braced log entry stays ONE record: a header-looking line appearing at brace-depth > 0 does not split
   the record. That is the context-free part a flat (finite-state) counter can't express in general — it is
   the golden balanced-brackets machine applied to log structure. Header/continuation classification (the
   regular part) and record emission live in vinary.ir.frontend.log-stream; this ns provides the brace
   pushdown + a depth reader that the front-end consults per line. Pure + DOM-free."
  (:require [vinary.ir.semiring :as sr]
            [vinary.ir.wpda :as wpda]
            [vinary.ir.decode :as dec]))

(def ^:private one (sr/bool true))

;; Tokens are per-line net brace events: :ob (a line net-opens a brace level) / :cb (net-closes). A stray
;; close at depth 0 (more } than {) is absorbed (noop) so the tracker never dies on malformed input.
(def pda
  (wpda/pda {:start :in :initial-stack [] :accept-mode :final-state :finals #{:in} :one one
             :transitions
             [(wpda/->PdaTransition :in :ob nil :in (wpda/act-push [:b]) one)
              (wpda/->PdaTransition :in :ob :b  :in (wpda/act-push [:b]) one)
              (wpda/->PdaTransition :in :cb :b  :in (wpda/act-pop)       one)
              (wpda/->PdaTransition :in :cb nil :in (wpda/act-noop)      one)]}))

(defn initial-frontier [] [(wpda/initial-config pda)])

(defn depth
  "Brace-nesting depth of `frontier` (0 = not inside braces) — the number of :b markers on the config's stack.
   The brace grammar is deterministic, so the frontier holds a single config."
  [frontier]
  (if-let [cfg (first frontier)] (count (:stack cfg)) 0))

(defn advance-net
  "Advance `frontier` by a line's net brace delta `nb` (|nb| :ob or :cb tokens through the streaming decoder)."
  [frontier nb]
  (let [tok (if (pos? nb) :ob :cb)]
    (reduce (fn [fr _] (dec/advance-step pda fr tok {})) frontier (range (js/Math.abs nb)))))
