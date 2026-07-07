(ns vinary.ir.backend.sanitize
  "The single HTML sanitize schema for the IR back-end (and the current markdown pipeline). It is GitHub's
   hast-util-sanitize defaultSchema — the exact allowlist GitHub uses — plus two minimal, safe extensions:
   (1) keep code.math-inline / code.math-display so math lowering can distinguish inline vs display; (2) allow
   `data:` image URIs so `![](data:image/svg+xml,…)` renders. structuredClone so the shared defaultSchema is
   never mutated. Everything dangerous (script / style / iframe / on*-handlers / javascript: / vbscript: /
   file: / <base> / <meta>) stays blocked. This ONE schema is intended to replace the renderer's inline copy
   AND the main-process office regex sanitizer (Phase 2), so every tree-producing format is sanitized by the
   same policy.

   aget with string literals (not .-prop) so the :advanced release build cannot mangle these accesses on the
   external defaultSchema object."
  (:require ["rehype-sanitize$defaultSchema" :as default-schema]))

(def schema
  (let [s     (js/structuredClone default-schema)
        attrs (aget s "attributes")]
    (.push (aget (aget attrs "code") 0) "math-inline" "math-display")
    (.push (aget (aget s "protocols") "src") "data")
    s))
