(ns vinary.ir.backend.sanitize
  "The single HTML sanitize schema for the IR back-end (and the current markdown pipeline). It is GitHub's
   hast-util-sanitize defaultSchema — the exact allowlist GitHub uses — plus four minimal, safe extensions:
   (1) keep code.math-inline / code.math-display so math lowering can distinguish inline vs display; (2) allow
   `data:` image URIs so `![](data:image/svg+xml,…)` renders; (3) keep code.vv-tex-attempt, the marker the Org
   frontend stamps on a non-HTML `#+BEGIN_EXPORT` block so the post-sanitize MathJax pass can find it; (4) keep
   span.todo / span.done so Org TODO keywords stay styleable. structuredClone so the shared defaultSchema is
   never mutated. Everything dangerous (script / style / iframe / on*-handlers / javascript: / vbscript: /
   file: / <base> / <meta>) stays blocked. This ONE schema is intended to replace the renderer's inline copy
   AND the main-process office regex sanitizer (Phase 2), so every tree-producing format is sanitized by the
   same policy.

   Only CLASS NAMES are added — no new tags, attributes, or protocols — so the extensions cannot widen the
   markup surface. The GFM task-list shape (ul.contains-task-list, li.task-list-item, input[type=checkbox])
   is already in GitHub's allowlist, so the Org frontend reuses it verbatim rather than extending anything.

   aget with string literals (not .-prop) so the :advanced release build cannot mangle these accesses on the
   external defaultSchema object."
  (:require ["rehype-sanitize$defaultSchema" :as default-schema]))

(def tex-attempt-class
  "Marker class the Org frontend stamps on `#+BEGIN_EXPORT latex` code blocks. It survives sanitization (see
   `schema`) so renderer.math-engine/render-tex-blocks — which must run POST-sanitize, because MathJax's <svg> is not
   in the allowlist — can find those blocks, try to typeset them, and fall back to the code block on failure."
  "vv-tex-attempt")

(def schema
  (let [s     (js/structuredClone default-schema)
        attrs (aget s "attributes")]
    (.push (aget (aget attrs "code") 0) "math-inline" "math-display" tex-attempt-class)
    ;; Org TODO keywords: GitHub's schema allows NO attributes on <span> at all. uniorg emits
    ;; <span class="todo-keyword TODO"> — the second class is the keyword itself, so it is unbounded and no
    ;; allowlist could enumerate it. markdown-pipeline/org-normalize collapses it to the STATE class allowed
    ;; here (`todo` / `done`); without that collapse the keyword would render as unstyled prose.
    (aset attrs "span" #js [#js ["className" "todo" "done"]])
    (.push (aget (aget s "protocols") "src") "data")
    s))
