(ns vinary.renderer.heavy-engine
  "The heavy document engine(s) — unified-latex (renderer.latex, transitively renderer.latex-structure and the
   @unified-latex/* npm sources) and, from the Org split, uniorg — collected into their OWN lazily-loaded build
   module (`:heavy-engine`, depends-on `:main`) so those megabyte-scale sources stay OFF the renderer's boot
   critical path AND out of the base bundle, exactly as renderer.math-engine does for @mathjax.

   The renderer reaches this ns ONLY through the renderer.heavy-lazy facade (shadow.lazy/loadable — a bare js*
   ref, NOT a :require edge), so it code-splits; renderer.core schedules an idle preload (heavy-lazy/ensure!) so
   pool windows warm it off the boot path. The NODE builds (:cli/:tui/:node-test) require this ns EAGERLY through
   renderer.heavy-node (no shadow.lazy — browser-only), so the shared DOM-free pipeline (markdown-pipeline /
   cli.render) resolves latex->html and the org-pipeline under Node exactly as before the split — the same
   dual-consumption renderer.math-engine has (:node-test requires it directly for the real MathJax engine).

   `install!` populates the renderer.heavy-registry atoms the shared pipeline reads (latex->html-fn — and, after
   the Org split, uniorg-parse*/uniorg-rehype*), so loading this chunk (or requiring it under Node) wires the
   pipeline. Idempotent (reset! is)."
  (:require [vinary.renderer.heavy-registry :as registry]
            [vinary.renderer.latex :as latex]))

(defn install!
  "Wire the heavy engine(s) into the shared pipeline's runtime registry. Idempotent. Called by the heavy-lazy
   facade after the chunk loads (renderer) and by heavy-node at startup (node)."
  []
  (reset! registry/latex->html-fn latex/latex->html)
  nil)

;; The renderer.heavy-lazy facade loads this module lazily and calls install! through this export (string key so
;; :simple/:advanced never rename it), exactly as renderer.mathjax-lazy drives renderer.math-engine's exports.
(def ^:export exports
  #js {"install!" install!})
