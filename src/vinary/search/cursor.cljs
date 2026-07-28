(ns vinary.search.cursor
  "A wrapping cursor over a match list.

   Trivial arithmetic, shared because it was written twice with different names and different edge-case
   behaviour: `vinary.renderer.find-scan` had `step-idx`/`clamp-idx` returning nil for an empty list,
   while `vinary.tui.find` had a private `step` that returned the session unchanged. Two answers to
   'what does next-match mean when there are no matches?' is one too many.

   Every function here returns nil when there is nothing to point at, so a caller is forced to say what
   that case means rather than inheriting an arbitrary default."
  (:refer-clojure :exclude [next]))

(defn step
  "Advance a 0-based cursor over `n` matches by `dir` (+1 next, -1 previous), wrapping at both ends.
   nil when n = 0."
  [n idx dir]
  (when (pos? n)
    (mod (+ (or idx 0) dir) n)))

(defn next [n idx] (step n idx 1))
(defn prev [n idx] (step n idx -1))

(defn clamp
  "Keep a cursor in range after a re-collect changed the match count. nil when nothing matches.

   Clamping rather than resetting: a document that changed under the user (a streamed append, a live
   refresh) should leave them near where they were, not back at the first match."
  [n idx]
  (when (pos? n)
    (min (max 0 (or idx 0)) (dec n))))

(defn display
  "The 1-based position a match counter shows: `idx` + 1, or 0 when there is nothing to point at. The
   whole reason the counter reads '3/12' and not '2/12'."
  [n idx]
  (if (pos? n) (inc (or idx 0)) 0))
