(ns vinary.renderer.math
  "The PURE, mathjax-FREE Markdown-math helpers — the shared, EAGER core of the old renderer.math, kept free of
   `@mathjax/*` so it stays in every bundle (the markdown-pipeline, :cli, :tui, :node, and the renderer boot
   base) without dragging in the heavy engine. It holds only string/regex helpers: `strip-math-fence`
   (backtick-fence cleanup), `delimit-tex` (copy round-trip), `tex-error?` (MathJax error-node detection),
   `tex-block-math?` (should an Org export block be ATTEMPTED as math?), and `error-html`.

   The HEAVY TeX→SVG engine — the `@mathjax/src` source + the Latin-Modern MathJax Modern font (the single biggest
   dependency) — was split out to vinary.renderer.math-engine so it can code-split: the renderer reaches it ONLY
   through the vinary.renderer.mathjax-lazy facade (a lazily-loaded chunk, preloaded on idle by the window pool),
   and it is GONE from the :cli/:tui node bundles (which never render SVG math to the terminal). :node-test
   requires math-engine directly (eager) so the math tests still exercise the real engine. math-engine
   `(:require [vinary.renderer.math :as math])`s this ns for `tex-block-math?` + `tex-error?`. See the
   math-engine ns docstring for the engine, the safe-package set, and the synchronous-font-loading rationale."
  (:require [clojure.string :as str]
            [goog.string :as gstr]))

;; ---- the MathJax TeX→SVG engine moved to vinary.renderer.math-engine ----
;; The heavy engine (mj-engine!, render-tex, render-html-math, render-tex-blocks, install-stylesheet!,
;; engine-stylesheet, engine-font-name, the svg cache, safe-packages, loaded-configs, the 26 dynamic-font
;; modules, and every `@mathjax/*` require) now lives in vinary.renderer.math-engine — VERBATIM — so the
;; @mathjax source + Modern font code-split out of the renderer boot bundle and out of the :cli/:tui node
;; bundles. The renderer calls it through the vinary.renderer.mathjax-lazy facade; :node-test requires
;; math-engine directly. Only the pure helpers below remain here (eager, shared everywhere).

(defn strip-math-fence
  "Clean the TeX of a math node produced from GitHub's backtick-wrapped inline form.

   GitHub writes inline math as $`x^2`$ (backticks INSIDE the dollars). remark-math DOES parse that as an
   inlineMath node, but keeps the backticks in the value (`x^2`), so MathJax would see literal backticks.
   This strips a balanced leading/trailing backtick run → x^2. A math value with no wrapping backticks
   (ordinary $x$) is returned unchanged. This runs ONLY on math nodes; a code span `$x$` is an inlineCode
   node — never a math node — so it is untouched and stays literal (GitHub does not render math in code).

   The former string-level normalize (a raw-markdown regex) was replaced by this mdast-level cleanup because
   a pre-parse regex cannot see code-span boundaries and corrupted documents containing multiple `$…$`
   inline-code examples."
  [tex]
  (let [tex (or tex "")]
    (if-let [[_ open inner close] (re-matches #"^(`+)([\s\S]*?)(`+)$" tex)]
      (if (and (= (count open) (count close)) (pos? (count inner)))
        inner
        tex)
      tex)))

(defn delimit-tex
  "Wrap raw TeX in markdown math delimiters so it round-trips into Markdown.
   display? → $$…$$, else $…$. Blank/nil source → nil (nothing copyable)."
  [display? tex]
  (let [tex (str/trim (or tex ""))]
    (when (seq tex)
      (if display? (str "$$" tex "$$") (str "$" tex "$")))))

;; render-html-math (the `code.math-*` → cached MathJax SVG DOM pass, with its private display-code? helper) moved
;; to vinary.renderer.math-engine, VERBATIM — it drives the heavy engine, so it code-splits with it. The renderer
;; calls it through the vinary.renderer.mathjax-lazy facade in renderer.markdown/apply-posts.

;; ── Org `#+BEGIN_EXPORT latex` blocks: attempt MathJax, fall back to a highlighted code block ────────────────
;;
;; MathJax does NOT throw on bad TeX here: the engine loads the `noerrors` + `noundefined` packages (see
;; math-engine/safe-packages), so a bad macro or environment renders an error node instead of raising. The
;; reliable failure signal is therefore the error node itself, not an exception — `\begin{center}` yields
;; `data-mjx-error="Unknown environment 'center'"`. tex-error? checks for both, plus a real throw (a font
;; "retry"); tex-block-math? decides whether to even attempt the block. Both are PURE, so they stay here; the
;; block-replacing DOM pass render-tex-blocks that composes them lives in math-engine (it drives render-tex).

(defn tex-error?
  "Did MathJax fail to typeset? True when the rendered container carries an error node. `render-tex` returns
   normally in that case (noerrors/noundefined are loaded), so this — not try/catch — is the fallback signal."
  [html]
  (boolean (re-find #"data-mjx-error|<merror" (or html ""))))

;; MathJax typesets MATH, not document markup. An export block full of \begin{center} / tabular / itemize is
;; not math; worse, a block of prose built only from math-legal macros (`\textbf{Hi} \\ World`) typesets
;; "successfully" into garbage that no error check can catch. So attempt a conversion only when the block
;; positively looks like math AND carries no document-structure macro.
(def ^:private math-env-re
  #"\\begin\{(equation|align|gather|multline|split|aligned|alignat|array|[bBpvV]?matrix|cases|eqnarray|CD)\*?\}|\\\[|\$\$|\\\(")

(def ^:private non-math-re
  #"\\begin\{(center|flushleft|flushright|itemize|enumerate|description|tabular\w*|longtable|figure|table|minipage|verbatim|quote|quotation|abstract|document)\}|\\(includegraphics|usepackage|section|subsection|maketitle|tableofcontents|newpage)\b")

(defn tex-block-math?
  "Should this `#+BEGIN_EXPORT latex` body even be ATTEMPTED as math? See math-env-re / non-math-re."
  [source]
  (let [source (or source "")]
    (and (boolean (re-find math-env-re source))
         (not (re-find non-math-re source)))))

;; render-tex-blocks (the `code.vv-tex-attempt` → MathJax-or-fall-back-to-code-block DOM pass) moved to
;; vinary.renderer.math-engine, VERBATIM — it drives render-tex, so it code-splits with the engine. It composes
;; the pure tex-block-math? + tex-error? above (as math/tex-block-math? / math/tex-error?). The renderer calls it
;; through the vinary.renderer.mathjax-lazy facade in renderer.markdown/apply-posts.

(defn error-html [message source]
  (str "<div class=\"vv-math-error\"><strong>MathJax error:</strong> "
       (gstr/htmlEscape (or message "Unable to render math"))
       (when (seq source)
         (str "<pre><code>" (gstr/htmlEscape source) "</code></pre>"))
       "</div>"))
