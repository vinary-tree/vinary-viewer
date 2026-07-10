(ns vinary.main.file-kind-test
  "DOM-free unit tests for vinary.main.file-kind/kind-of — the pure extension→kind classifier the main-process
   service dispatches on. Guards the new Org (.org) classification and a few neighboring kinds so the Org arm
   can't accidentally shadow or be shadowed."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.main.file-kind :as fk]))

(def ^:private never-source (constantly false))
(def ^:private always-source (constantly true))

(deftest kind-of-org
  (testing ".org classifies as \"org\" (case-insensitive), ahead of the generic source/text fallbacks"
    (is (= "org" (fk/kind-of never-source "/notes/todo.org")))
    (is (= "org" (fk/kind-of never-source "/notes/TODO.ORG")))
    (is (= "org" (fk/kind-of never-source "README.Org")))
    ;; even when the source? predicate would claim it, the explicit .org arm wins (it precedes the source arm)
    (is (= "org" (fk/kind-of always-source "/notes/todo.org")))))

(deftest kind-of-latex
  (testing ".tex/.latex/.ltx classify as \"latex\" — CRUCIALLY ahead of the source arm, because a tree-sitter-latex
            grammar is bundled, so always-source would otherwise upgrade .tex to \"source\" (the pre-support trap)"
    (is (= "latex" (fk/kind-of never-source "/papers/thesis.tex")))
    (is (= "latex" (fk/kind-of never-source "/papers/Thesis.TEX")))
    (is (= "latex" (fk/kind-of never-source "/papers/report.latex")))
    (is (= "latex" (fk/kind-of never-source "/papers/report.ltx")))
    ;; the explicit .tex arm must win over the source grammar — this is the whole point of the ordering
    (is (= "latex" (fk/kind-of always-source "/papers/thesis.tex")))
    ;; .sty/.cls/.bib are LaTeX SUPPORT files — they stay source, not rendered documents
    (is (= "source" (fk/kind-of always-source "/papers/style.sty")))
    (is (= "source" (fk/kind-of always-source "/papers/class.cls")))))

(deftest kind-of-neighbors-unaffected
  (testing "adding Org/LaTeX does not disturb the neighboring kinds"
    (is (= "markdown" (fk/kind-of never-source "/a/b.md")))
    (is (= "markdown" (fk/kind-of never-source "/a/b.markdown")))
    (is (= "text" (fk/kind-of never-source "/a/notes.txt")))
    (is (= "source" (fk/kind-of always-source "/a/main.rs")))
    (is (= "image" (fk/kind-of never-source "/a/pic.svg")))))

(deftest pdf-sibling-path-computation
  (testing "the same-stem .pdf candidate path (the pure half of the Document↔PDF sibling detection)"
    (is (= "/a/paper.pdf"      (fk/pdf-sibling-path "/a/paper.tex"))    "a .tex → same-stem .pdf")
    (is (= "/a/invoice.pdf"    (fk/pdf-sibling-path "/a/invoice.org"))  "an .org → same-stem .pdf")
    (is (= "/a/notes.pdf"      (fk/pdf-sibling-path "/a/notes.md"))     "a .md → same-stem .pdf")
    (is (= "/dir.v2/paper.pdf" (fk/pdf-sibling-path "/dir.v2/paper.tex")) "a dotted DIRECTORY name is not mistaken for the extension"))
  (testing "no sibling candidate when there is no stem to swap, or the doc is itself a PDF"
    (is (nil? (fk/pdf-sibling-path "/a/README"))    "an extensionless path has no stem swap")
    (is (nil? (fk/pdf-sibling-path "/a/paper.pdf"))  "a .pdf never siblings itself")
    (is (nil? (fk/pdf-sibling-path "/a/paper.PDF"))  "…case-insensitively")))
