(ns vinary.main.file-kind-test
  "DOM-free unit tests for vinary.main.file-kind/kind-of — the pure extension→kind classifier the main-process
   service dispatches on. Guards the new Org (.org) classification and a few neighboring kinds so the Org arm
   can't accidentally shadow or be shadowed."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
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

(deftest group-candidate-paths-computation
  (testing "same-directory, same-stem candidates for a document GROUP — the file itself first, one per group ext"
    (let [cands (fk/group-candidate-paths "/a/paper.tex")
          s     (set cands)]
      (is (= "/a/paper.tex" (first cands)) "the file itself is first")
      (is (every? #(str/starts-with? % "/a/paper.") cands) "all share the directory + stem")
      (is (contains? s "/a/paper.pdf") "includes the same-stem .pdf")
      (is (contains? s "/a/paper.org") "includes a same-stem .org")
      (is (contains? s "/a/paper.md")  "includes a same-stem .md")
      (is (= 1 (count (filter #{"/a/paper.tex"} cands))) "the file's own candidate is not duplicated")))
  (testing "the same relationship holds for a .pdf (it siblings its authored sources)"
    (let [s (set (fk/group-candidate-paths "/a/paper.pdf"))]
      (is (contains? s "/a/paper.tex"))
      (is (contains? s "/a/paper.org"))))
  (testing "a dotted DIRECTORY name is not mistaken for the extension"
    (is (= "/dir.v2/x.tex" (first (fk/group-candidate-paths "/dir.v2/x.tex"))))
    (is (contains? (set (fk/group-candidate-paths "/dir.v2/x.tex")) "/dir.v2/x.pdf")))
  (testing "an extensionless path has no stem to swap → just itself"
    (is (= ["/a/README"] (fk/group-candidate-paths "/a/README"))))
  (testing "group-kinds is the exact set of collocatable representation kinds"
    (is (= #{"pdf" "markdown" "org" "latex" "mermaid" "diff"} fk/group-kinds))))

(deftest kind-of-diff
  (testing ".diff/.patch classify as \"diff\" — ahead of the source arm, so they render (colored + side-by-side)"
    (is (= "diff" (fk/kind-of never-source "/p/change.diff")))
    (is (= "diff" (fk/kind-of never-source "/p/Change.PATCH")))
    (is (= "diff" (fk/kind-of always-source "/p/change.diff")) "the explicit .diff arm wins over the source grammar")))

(deftest well-known-repo-files
  (testing "standard repo build/config files classify as source DETERMINISTICALLY — independent of any grammar,
            so a GNU Makefile never trips the delimited-content sniffer (its old bug)"
    (is (= "source" (fk/kind-of never-source "/proj/Makefile")))
    (is (= "source" (fk/kind-of never-source "/proj/GNUmakefile")))
    (is (= "source" (fk/kind-of never-source "/proj/build.mk")))
    (is (= "source" (fk/kind-of never-source "/proj/CMakeLists.txt")))
    (is (= "source" (fk/kind-of never-source "/proj/Dockerfile")))
    (is (= "source" (fk/kind-of never-source "/proj/Gemfile")))
    (is (= "source" (fk/kind-of never-source "/proj/.gitignore")))
    (is (= "source" (fk/kind-of never-source "/proj/.gitconfig")))
    (is (= "source" (fk/kind-of never-source "/home/me/repo/.git/config")) "a repo's .git/config (by path)")
    (is (= "source" (fk/kind-of never-source "/home/me/.bashrc"))))
  (testing "standard prose/legal files classify as plain text (not sniffed as delimited/log)"
    (is (= "text" (fk/kind-of never-source "/proj/LICENSE")))
    (is (= "text" (fk/kind-of never-source "/proj/COPYING")))
    (is (= "text" (fk/kind-of never-source "/proj/AUTHORS")))
    (is (= "text" (fk/kind-of never-source "/proj/README")))
    (is (= "text" (fk/kind-of never-source "/proj/CHANGELOG")) "bare CHANGELOG → text"))
  (testing "the extension-based document kinds still win over a well-known bare name"
    (is (= "markdown" (fk/kind-of never-source "/proj/README.md")) "README.md is still markdown")
    (is (= "text" (fk/kind-of never-source "/proj/LICENSE.txt")))))
