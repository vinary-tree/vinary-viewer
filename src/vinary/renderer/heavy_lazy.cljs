(ns vinary.renderer.heavy-lazy
  "Thin facade over the lazily-loaded heavy-engine chunk (renderer.heavy-engine). The heavy unified-latex sources
   (renderer.latex + renderer.latex-structure + the @unified-latex/* npm packages) — and, from the Org split,
   uniorg — live in their own build module (`:heavy-engine`, depends-on `:main`) so they stay OUT of the renderer
   boot bundle; this ns loads that chunk on demand (ensure!) and, on load, calls its install! to populate the
   shared renderer.heavy-registry atoms the DOM-free pipeline reads. renderer.core schedules a pool/idle preload
   (ensure! shortly after first paint) so warm — including hidden pool-window — first .org/.tex (and embedded-
   LaTeX) renders already have the engines wired off the boot critical path.

   Mirrors renderer.mathjax-lazy / renderer.cm. Call discipline: the render entry points in renderer.markdown
   (render-org-ir / render-latex-ir / *-stream-blocks, and render-ir/stream-blocks for embedded-LaTeX markdown)
   await ensure! BEFORE running the synchronous pipeline — gated by format so plain markdown never loads the
   chunk (the cold-start win) — behind the existing Loading/Rendering placeholder, so the pipeline never derefs a
   nil registry atom. ready? guards any boot-reachable use."
  (:require [shadow.lazy :as lazy]
            [goog.object :as gobj]))

;; handle to the lazily-loaded heavy-engine module (named heavy-module, not `mod`, which shadows cljs.core/mod —
;; see the same choice in renderer.cm/sv-module + renderer.mathjax-lazy/engine-module).
(def ^:private heavy-module (lazy/loadable vinary.renderer.heavy-engine/exports))

(defn ready?
  "True once the heavy-engine chunk has loaded (its exports are dereferenceable)."
  []
  (lazy/ready? heavy-module))

(defn ensure!
  "Load the heavy-engine chunk (idempotent — the module loads once) and, on resolve, call its install! to wire the
   shared registry atoms (latex->html-fn, uniorg-parse*/uniorg-rehype*). Returns a Promise; the caller (the render
   entry points / the idle preload) chains its render off the resolve, so the pipeline's synchronous latex->html /
   org-pipeline never sees a nil registry atom."
  []
  (-> (lazy/load heavy-module)
      (.then (fn [exports]
               ((gobj/get exports "install!"))
               exports))))
