(ns vinary.renderer.math-test
  "DOM-free unit tests for the pure copy-support helper in vinary.renderer.math. `delimit-tex` is the single
   place that turns a rendered equation's stashed `data-tex` (raw LaTeX, no delimiters) back into copyable
   Markdown math, and it is shared verbatim by BOTH copy paths — the Ctrl+C selection rewrite
   (vinary.renderer.core) and the \"Copy LaTeX\" context-menu item (vinary.ui.views). Keeping it pure and
   guarded here means the delimiter contract can't silently drift out from under either caller. The DOM-bound
   `render-html-math` (needs js/DOMParser, absent under :node-test) is exercised by the electron smoke test."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.math :as math]))

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
