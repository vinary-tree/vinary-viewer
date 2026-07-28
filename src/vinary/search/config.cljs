(ns vinary.search.config
  "Which matching mode each search surface uses — the single place that choice is made.

   The defaults below reproduce EXACTLY what each widget did before `vinary.search.*` existed, so
   consolidating the five matchers is a de-duplication with no user-visible change. That is deliberate:
   a refactor and a behaviour change landing together is a refactor whose behaviour change cannot be
   reviewed.

   Having them here rather than at each call site is what makes the semantics configurable. Changing the
   file tree to fuzzy matching, or ranking the palette by match quality, is now one keyword against a
   tested model — and a settings UI would read `vinary.search.match/modes` for the choices and write
   `[:ui :settings :search-modes]`, which `mode-for` already consults.

   `:ranked?` is per-surface too, and off by default everywhere it was off before. A file tree wants its
   own order (paths sort meaningfully); a command palette arguably wants relevance, but changing that is
   a behaviour decision to make on its own evidence, not a side effect of this consolidation."
  (:require [vinary.search.match :as m]))

(def defaults
  "surface → {:mode :fold :ranked?}.

   :fold is :strict wherever a returned span index is mapped back to a source position, and :simple where
   it is not — see vinary.search.query for why that distinction is not cosmetic."
  {;; in-page find: spans index a flattened buffer that maps back to DOM text-node offsets, so the fold
   ;; MUST preserve length
   :find    {:mode :substring :fold :strict :ranked? false}
   ;; terminal find: spans index the ANSI-stripped visible line, then map back to raw byte columns —
   ;; same requirement
   :tui     {:mode :substring :fold :strict :ranked? false}
   ;; file tree: a path filter, matched against the repo-relative path
   :tree    {:mode :substring :fold :simple :ranked? false}
   ;; command palette: subsequence matching over command titles and file names
   :palette {:mode :subsequence :fold :simple :ranked? false}
   ;; address bar: completes the last path segment, so only a prefix makes sense
   :uri     {:mode :prefix :fold :simple :ranked? false}})

(defn mode-for
  "The options for `surface`, with any user override from app-db applied.

   `settings` is the `[:ui :settings]` map (or nil). An override is honoured only if it names a mode this
   build actually implements — a stale or hand-edited settings file must not be able to produce a matcher
   that silently matches nothing."
  ([surface] (mode-for surface nil))
  ([surface settings]
   (let [base (get defaults surface (:find defaults))
         over (get-in settings [:search-modes surface])]
     (if (contains? m/mode-ids over)
       (assoc base :mode over)
       base))))
