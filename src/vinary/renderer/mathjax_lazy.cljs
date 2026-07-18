(ns vinary.renderer.mathjax-lazy
  "Thin facade over the lazily-loaded MathJax engine chunk (renderer.math-engine). The heavy @mathjax/src source +
   the Latin-Modern MathJax Modern font (the single biggest dependency, ~3-5 MB) live in their own build module
   (`:math-engine`, depends-on `:main`) so they stay OUT of the renderer boot bundle; this ns loads that chunk on
   demand (ensure!) and passes calls through to its exported fns. renderer.core schedules a pool/idle preload
   (ensure! + install-stylesheet! shortly after first paint) so warm — including hidden pool-window — first
   renders already have the engine + its stylesheet ready off the boot critical path.

   Call discipline (mirrors renderer.cm):
   - render-html-math / render-tex-blocks are invoked ONLY from renderer.markdown/apply-posts, and ONLY inside the
     `.then` AFTER ensure! resolves (apply-posts gates the whole math branch on `(exists? js/DOMParser)` + a math
     marker, so it never even calls ensure! for a no-math document or in DOM-free Node), which implies the chunk
     is loaded — so they deref @engine-module directly.
   - install-stylesheet! may be scheduled independently of a render (the idle preload), so it is guarded with
     (ready?): a nil result matches the old \"engine not built yet\" no-op, and the preload's own ensure!→then
     resolves before it calls through."
  (:require [shadow.lazy :as lazy]
            [goog.object :as gobj]))

;; handle to the lazily-loaded math-engine module (named engine-module, not `mod`, which shadows cljs.core/mod —
;; see the same choice in renderer.cm/sv-module).
(def ^:private engine-module (lazy/loadable vinary.renderer.math-engine/exports))

(defn ready?
  "True once the math-engine chunk has loaded (its exports are dereferenceable)."
  []
  (lazy/ready? engine-module))

(defn ensure!
  "Load the math-engine chunk (idempotent — the module loads once). Returns a Promise of its exports; the caller
   (apply-posts / the idle preload) chains its render / install-stylesheet! off the resolve."
  []
  (lazy/load engine-module))

;; ---- pass-throughs to the loaded chunk ----
;; render-html-math / render-tex-blocks are only ever called AFTER ensure! resolves (see the ns docstring), so
;; they deref @engine-module directly — exactly the view-bound discipline in renderer.cm.
(defn render-html-math  [html] ((gobj/get @engine-module "render-html-math") html))
(defn render-tex-blocks [html] ((gobj/get @engine-module "render-tex-blocks") html))

;; install-stylesheet! may run before any render (the idle preload), so guard with ready?; a nil result is the
;; old "engine not built yet" no-op. The preload calls it from inside its own ensure!→then, so ready? is true.
(defn install-stylesheet! [] (when (ready?) ((gobj/get @engine-module "install-stylesheet!"))))
