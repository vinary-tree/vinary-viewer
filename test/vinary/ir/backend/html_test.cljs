(ns vinary.ir.backend.html-test
  "Guards ir.backend.html/blank? — the predicate that stands between a frontend which legitimately rendered
   nothing and a silently blank preview pane.

   The Org LaTeX-invoice bug: `lower` returned \"\", and because `\"\"` is TRUTHY in ClojureScript the view's
   `(:doc/html doc)` catch-all matched, mounted an empty .markdown-body, and showed a blank pane with no error
   and no \"Rendering…\" placeholder. Any caller testing the lowered HTML for truthiness has the same bug, so
   this predicate — not truthiness — is the contract."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.backend.html :as ir-html]))

(deftest blank?-treats-empty-and-whitespace-as-nothing-to-preview
  (testing "the empty string — truthy in CLJS, which is exactly why it needs a predicate"
    (is (true? (boolean "")) "sanity: \"\" is truthy in ClojureScript")
    (is (ir-html/blank? "") "an empty lowered document has nothing to preview"))
  (testing "whitespace-only output is equally unpreviewable"
    (is (ir-html/blank? "   "))
    (is (ir-html/blank? "\n"))
    (is (ir-html/blank? " \t\n ")))
  (testing "nil (no render yet) is blank — the view distinguishes it via (some? html) before calling this"
    (is (ir-html/blank? nil))))

(deftest blank?-is-false-for-real-markup
  (is (not (ir-html/blank? "<p>x</p>")))
  (is (not (ir-html/blank? "<hr>")) "a document of one thematic break is renderable")
  (is (not (ir-html/blank? "<pre><code class=\"language-latex\">\\begin{center}</code></pre>"))
      "the Org LaTeX fallback code block is renderable content"))
