(ns vinary.app.content-guard
  "PURE. Does an arriving vv:content / vv:error belong BEFORE what this window has already applied? (ADR-0046)

   Main numbers every read of a path (`seq`, vinary.main.content-flight — strictly increasing process-wide) and
   runs at most one per path at a time, so today the deliveries for one path reach a window in order. This guard
   makes the renderer's correctness independent of that: a payload whose read number is not newer than the one
   the document last applied is dropped — it would put an older version over a newer one (or apply the same read
   twice). The applied number lives on the document as :doc/seq.

   Payloads WITHOUT a read number are never stale: a web-linked PDF (main's web view sends it), a renderer-side
   render error, a test stub. And a document that has applied no number yet (just opened, or evicted and reopened)
   accepts any.")

(defn stale?
  "Is a payload with read number `incoming` older than — or the same read as — the `applied` one? Only when both
   are numbers."
  [incoming applied]
  (boolean (and (number? incoming) (number? applied) (<= incoming applied))))
