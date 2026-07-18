(ns vinary.renderer.heavy-registry
  "Runtime registry that DECOUPLES the two heavyweight document engines — unified-latex (`.tex` + LaTeX embedded
   in Org / Markdown) and uniorg (`.org`) — from the shared DOM-free Markdown/Org/LaTeX pipeline
   (renderer.markdown-pipeline), so those megabyte-scale npm dependencies code-split out of the renderer BOOT
   bundle into a lazily-loaded chunk (renderer.heavy-engine, behind the renderer.heavy-lazy facade) instead of
   riding in the base bundle (mirroring how @mathjax already lives in renderer.math-engine).

   Why a registry and not a plain :require? The shared pipeline is bundled by the NODE targets too (:cli / :tui /
   :node-test, via cli.render + markdown-pipeline), which cannot use shadow.lazy (browser-only). So the pipeline
   carries NO static require of unified-latex / uniorg; it reaches them only through these atoms, and each target
   populates them its own way:
     • the RENDERER lazily — renderer.heavy-lazy/ensure! loads the chunk then calls heavy-engine/install!
       (idle-preloaded in renderer.core, and awaited by the render fx before a doc that may hit the engines);
     • the NODE builds eagerly — heavy-node/install! at startup (cli.core / tui.core / the Org node-tests).

   This is a LEAF ns with NO heavy requires of its own, so it stays in the base bundle for every target and both
   the pipeline (writer of neither, reader of all) and the two population paths can depend on it without dragging
   unified-latex / uniorg back into the boot bundle.

   `latex->html-fn` holds renderer.latex/latex->html; `uniorg-parse*` / `uniorg-rehype*` hold the two uniorg
   unified plugins. They are nil until install! runs; the accessor helpers throw a clear ex-info if a heavy engine
   is used before its chunk has loaded (a wiring bug — the render fx awaits ensure! first, node calls install! at
   startup — never a user-facing state).")

;; renderer.latex/latex->html — populated by heavy-engine/install! (see the ns docstring). Read via `latex->html`.
(defonce latex->html-fn (atom nil))

;; the two uniorg unified plugins (uniorg-parse$default / uniorg-rehype$default) — populated by
;; heavy-engine/install! (Org split); markdown-pipeline/org-pipeline reads them via `uniorg-plugins`.
(defonce uniorg-parse*  (atom nil))
(defonce uniorg-rehype* (atom nil))

(defn latex->html
  "Convert a LaTeX fragment/document to an HTML string via the registered renderer.latex/latex->html. Throws a
   clear ex-info if the LaTeX engine chunk has not been loaded (install! not yet run) — the render fx awaits
   heavy-lazy/ensure! (renderer) or heavy-node/install! runs at startup (node) before any latex path executes, so
   a throw here is a wiring bug, never a user-facing state. `opts` mirrors renderer.latex/latex->html (:inline?
   :preamble); nil is fine (the 1-arg arity)."
  ([source] (latex->html source nil))
  ([source opts]
   (if-let [f @latex->html-fn]
     (f source opts)
     (throw (ex-info "LaTeX renderer not loaded — heavy-engine/install! has not run (await heavy-lazy/ensure! in the renderer, or call heavy-node/install! at node startup)"
                     {:engine :unified-latex})))))

(defn uniorg-plugins
  "The two uniorg unified plugins `[uniorg-parse uniorg-rehype]` the org-pipeline installs (its ONLY unified-latex/
   uniorg dependency — the handlers/front-matter/footnotes/normalizations stay in the pipeline as plain options).
   Throws a clear ex-info if the Org engine chunk has not been loaded (install! not yet run) — the render entry
   points await heavy-lazy/ensure! (renderer) or heavy-node/install! runs at startup (node) before any org path
   executes, so a throw here is a wiring bug, never a user-facing state."
  []
  (let [parse @uniorg-parse* rehype @uniorg-rehype*]
    (if (and parse rehype)
      [parse rehype]
      (throw (ex-info "Org renderer (uniorg) not loaded — heavy-engine/install! has not run (await heavy-lazy/ensure! in the renderer, or call heavy-node/install! at node startup)"
                      {:engine :uniorg})))))
