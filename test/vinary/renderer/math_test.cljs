(ns vinary.renderer.math-test
  "DOM-free unit tests for the pure copy-support helper in vinary.renderer.math. `delimit-tex` is the single
   place that turns a rendered equation's stashed `data-tex` (raw LaTeX, no delimiters) back into copyable
   Markdown math, and it is shared verbatim by BOTH copy paths — the Ctrl+C selection rewrite
   (vinary.renderer.core) and the \"Copy LaTeX\" context-menu item (vinary.ui.views). Keeping it pure and
   guarded here means the delimiter contract can't silently drift out from under either caller. The DOM-bound
   `render-html-math` (needs js/DOMParser, absent under :node-test) is exercised by the electron smoke test."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.renderer.math :as math]))

;; ── Inline math geometry: one <svg>, no line breaks, a box that encloses the expression ─────────────────────
;;
;; MathJax 4 turned on automatic INLINE line-breaking by default (SVG.OPTIONS.linebreaks.inline = true), whose
;; break opportunities are exactly `mo` (operators) and `mspace` (spacing). We typeset off-DOM through
;; liteAdaptor, so MathJax has no container to measure and takes EVERY break opportunity: it emits one <svg> per
;; "line", the FIRST of which holds all the ink inside a degenerate viewBox. `\implies` came out as three
;; siblings — a 16-unit-wide box containing the whole ⟹ path, plus two empty spacers — so the browser's
;; `svg:not(:root){overflow:hidden}` clipped it to an invisible blank gap of roughly the right width.
;;
;; Structural assertions ("does it contain <svg>") cannot see this; these are geometric. They are also
;; font- and version-independent: every check is RELATIONAL, so a font bump cannot make them lie.

(defn- svg-count [html] (count (re-seq #"<svg" (or html ""))))
(defn- break-count [html] (count (re-seq #"<mjx-break" (or html ""))))

(defn- viewbox-width
  "Width (3rd number) of the FIRST viewBox in a rendered container. When line-breaking fires there is more than
   one <svg>, and this sees only the first — which is precisely the trap the original bug report fell into."
  [html]
  (some-> (re-find #"viewBox=\"([^\"]+)\"" html)
          second
          (str/split #"\s+")
          (nth 2)
          js/parseFloat))

(def ^:private inline-cases
  ["\\implies" "\\iff" "x \\le y" "a \\in B" "a\\,b" "\\quad" "\\," "x^2" "ab"])

(deftest inline-math-is-a-single-unbroken-svg
  (testing "inline math never line-breaks: one <svg>, no <mjx-break> (the \\implies regression emitted three)"
    (doseq [tex inline-cases]
      (let [html (math/render-tex tex false)]
        (is (= 1 (svg-count html))
            (str tex ": expected exactly one <svg>, got " (svg-count html)
                 " — MathJax line-broke the inline expression"))
        (is (zero? (break-count html))
            (str tex ": expected no <mjx-break> element"))))))

(deftest display-math-is-a-single-unbroken-svg
  (testing "the inline fix must not disturb display math, which was never broken"
    (doseq [tex ["\\implies" "x \\le y" "\\max\\{\\,L\\,\\}" "\\frac{a}{b}"]]
      (let [html (math/render-tex tex true)]
        (is (= 1 (svg-count html)) (str tex " (display): exactly one <svg>"))
        (is (zero? (break-count html)) (str tex " (display): no <mjx-break>"))))))

(deftest inline-math-viewbox-encloses-the-expression
  (testing "the container's box grows with the expression. Each of these is FALSE when the row collapses to its
            first child, and none depends on a specific font metric."
    (let [w #(viewbox-width (math/render-tex % false))]
      (is (> (w "\\implies") (w "\\Longrightarrow"))
          "\\implies is \\;\\Longrightarrow\\; — it must be WIDER than the arrow alone")
      (is (> (w "\\iff") (w "\\Longleftrightarrow"))
          "\\iff is \\;\\Longleftrightarrow\\; — wider than the arrow alone")
      (is (> (w "a\\,b") (w "ab"))
          "a\\,b inserts a thin space — wider than ab")
      (is (> (w "\\quad") (w "\\,"))
          "\\quad (1em) is wider than \\, (3/18 em)")
      (is (> (w "x \\le y") (+ (w "x") (w "y")))
          "x \\le y contains both operands plus an operator and its spacing")
      (is (> (w "a \\in B") (+ (w "a") (w "B")))
          "a \\in B contains both operands plus an operator and its spacing"))))

;; ── Org `#+BEGIN_EXPORT latex`: the attempt/fallback contract ───────────────────────────────────────────────
;;
;; The engine loads the `noerrors` + `noundefined` TeX packages, so MathJax does NOT throw on bad input — it
;; renders an error node. `tex-error?` (not try/catch) is therefore the fallback signal, and `tex-block-math?`
;; screens out the case no error check can catch: prose built from math-legal macros, which typesets
;; "successfully" into garbage.

(deftest tex-error?-detects-mathjax-error-nodes
  (testing "an unknown environment renders an error node rather than throwing"
    (let [bad (math/render-tex "\\begin{center}hi\\end{center}" true)]
      (is (string? bad) "render-tex returns normally — noerrors/noundefined suppress the throw")
      (is (math/tex-error? bad) "…and the failure is visible as a data-mjx-error node")))
  (testing "real math carries no error node"
    (is (not (math/tex-error? (math/render-tex "E = mc^2" true))))
    (is (not (math/tex-error? (math/render-tex "\\begin{align} a &= b \\end{align}" true)))))
  (testing "the raw predicate"
    (is (math/tex-error? "<mjx-container data-mjx-error=\"Unknown environment 'center'\">"))
    (is (math/tex-error? "<merror>boom</merror>"))
    (is (not (math/tex-error? "<mjx-container><svg/></mjx-container>")))
    (is (not (math/tex-error? nil)))))

(deftest tex-block-math?-screens-document-markup
  (testing "positively math → attempt"
    (is (math/tex-block-math? "\\begin{align} a &= b \\end{align}"))
    (is (math/tex-block-math? "\\begin{equation} x \\end{equation}"))
    (is (math/tex-block-math? "\\[ E = mc^2 \\]"))
    (is (math/tex-block-math? "\\begin{bmatrix} 1 & 2 \\end{bmatrix}")))
  (testing "document-structure macros → never attempt (the invoice's shape)"
    (is (not (math/tex-block-math? "\\begin{center}\n  Billing Period\n\\end{center}")))
    (is (not (math/tex-block-math? "\\begin{tabular}{ll}a & b\\end{tabular}")))
    (is (not (math/tex-block-math? "\\begin{itemize}\\item x\\end{itemize}")))
    (is (not (math/tex-block-math? "\\includegraphics{logo.pdf}")))
    (is (not (math/tex-block-math? "\\begin{align} a &= b \\end{align}\n\\begin{center}x\\end{center}"))
        "a math env does not license a block that also carries document markup"))
  (testing "prose built only from math-legal macros → never attempt (no error node would catch it)"
    (is (not (math/tex-block-math? "\\textbf{Hello} \\\\ World")))
    (is (not (math/tex-block-math? "")))
    (is (not (math/tex-block-math? nil)))))

(deftest render-tex-modern-font
  (testing "TeX renders (DOM-free via liteAdaptor) to a MathJax SVG container in the Latin-Modern MathJax Modern font"
    (let [inline  (math/render-tex "x^2 + \\alpha" false)
          display (math/render-tex "\\begin{cases} a & b \\\\ c & d \\end{cases}" true)]
      (is (str/includes? inline "mjx-container") "produces a MathJax SVG container")
      (is (str/includes? inline "<svg") "with an <svg> element")
      (is (str/includes? display "<svg") "display math renders too")
      (is (= "MathJaxModern" (math/engine-font-name)) "SVG output uses the Latin-Modern-derived Modern font"))))

(deftest render-tex-dynamic-glyphs
  (testing "glyphs in MathJax 4's dynamically-loaded font chunks render synchronously — no async 'retry' throw"
    ;; MathJax 4 splits the SVG font into a base set + dynamic chunks; \mathtt (monospace), \mathbb (double-struck),
    ;; \mathfrak, \mathscr, \mathsf all live in chunks. Before the dynamic-font preload, the SYNCHRONOUS .convert
    ;; threw MathJax's 'retry -- an asynchronous action is required' error on the first such glyph. This renders it.
    (let [out (math/render-tex "\\mathtt{for}\\;\\mathbb{R}\\;\\mathfrak{g}\\;\\mathscr{L}\\;\\mathsf{X}" false)]
      (is (str/includes? out "<svg") "dynamic-chunk variant glyphs render to an <svg> (no retry throw)")
      (is (not (str/includes? out "fill=\"red\"")) "and are real glyphs, not error text"))))

(deftest render-tex-stmaryrd-brackets
  (testing "\\llbracket / \\rrbracket render as real ⟦ ⟧ container delimiters, not red undefined-macro text"
    ;; stmaryrd isn't bundled by MathJax, so these are bound (in mj-engine!) to the bare U+27E6/7 chars, which are
    ;; MO.OPEN/MO.CLOSE in the operator dictionary and stretchy in the font.
    (let [bare    (math/render-tex "\\llbracket x \\rrbracket" false)
          stretch (math/render-tex "\\left\\llbracket \\frac{a}{b} \\right\\rrbracket" true)]
      (is (str/includes? bare "<svg") "bare brackets render")
      (is (not (str/includes? bare "fill=\"red\"")) "not red noundefined text — the macros are defined")
      (is (str/includes? stretch "<svg") "stretchy \\left\\llbracket…\\right\\rrbracket renders (⟦ accepted as a delimiter)"))))

(deftest delimit-tex-inline
  (testing "inline math is wrapped in single $…$"
    (is (= "$x^2$" (math/delimit-tex false "x^2")))
    (is (= "$V = ()$" (math/delimit-tex false "V = ()")))
    (is (= "$O(m)$" (math/delimit-tex false "O(m)")))))

(deftest delimit-tex-display
  (testing "display math is wrapped in double $$…$$"
    (is (= "$$\\frac{a}{b}$$" (math/delimit-tex true "\\frac{a}{b}")))
    (is (= "$$\\sum_{i=0}^{n} i$$" (math/delimit-tex true "\\sum_{i=0}^{n} i")))))

(deftest delimit-tex-trims
  (testing "surrounding whitespace on the raw source is trimmed before delimiting"
    (is (= "$x$" (math/delimit-tex false "  x  ")))
    (is (= "$$x$$" (math/delimit-tex true "\n x \n")))))

(deftest delimit-tex-preserves-backslashes
  (testing "backslashes/braces in the TeX are carried through verbatim (no escaping/mangling)"
    (is (= "$\\text{term} \\to \\text{id}$" (math/delimit-tex false "\\text{term} \\to \\text{id}")))
    (is (= "$\\leftrightarrow$" (math/delimit-tex false "\\leftrightarrow")))))

(deftest delimit-tex-blank-is-nil
  (testing "blank or nil source yields nil (nothing to copy → no menu item / no substitution)"
    (is (nil? (math/delimit-tex false "")))
    (is (nil? (math/delimit-tex false "   ")))
    (is (nil? (math/delimit-tex false nil)))
    (is (nil? (math/delimit-tex true "")))
    (is (nil? (math/delimit-tex true nil)))))

(deftest strip-math-fence-github-form
  (testing "GitHub's $`…`$ inline math (parsed by remark-math to a value that KEEPS the backticks) is cleaned"
    (is (= "x^2" (math/strip-math-fence "`x^2`")))
    (is (= "\\oplus" (math/strip-math-fence "`\\oplus`")))
    (is (= "a + b" (math/strip-math-fence "`a + b`")))))

(deftest strip-math-fence-plain-math-untouched
  (testing "ordinary $x$ math (no wrapping backticks) is returned verbatim"
    (is (= "x^2" (math/strip-math-fence "x^2")))
    (is (= "\\frac{a}{b}" (math/strip-math-fence "\\frac{a}{b}")))
    (is (= "\\oplus" (math/strip-math-fence "\\oplus")))))

(deftest strip-math-fence-edges
  (testing "only a BALANCED wrapping run is stripped; degenerate inputs are safe"
    (is (= "" (math/strip-math-fence "")))
    (is (= "" (math/strip-math-fence nil)))
    (is (= "`" (math/strip-math-fence "`")) "a lone backtick has no balanced pair → unchanged")
    (is (= "``" (math/strip-math-fence "``")) "empty inner → not stripped to blank")
    (is (= "x`y" (math/strip-math-fence "``x`y``")) "double-backtick fence stripped, inner backtick kept")
    (is (= "a`b" (math/strip-math-fence "`a`b`")) "unbalanced middle backtick is inner content")))
