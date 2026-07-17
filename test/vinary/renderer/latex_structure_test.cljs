(ns vinary.renderer.latex-structure-test
  "Unit tests for the standalone-LaTeX document-structure engine (renderer.latex-structure, driven through
   renderer.latex/latex->html). A comprehensive fixture mirroring the real knotted-topoi.tex exercises every
   phase — the macro fixpoint, cross-references, citations, the bibliography, section/theorem/equation numbering,
   the front matter, and the math-only colour/`@` fixes — and a set of focused fixtures pin the tricky cases
   (in-math citations, nested equations, the fragment no-op). A final guarded oracle renders the real paper when
   present and asserts no structural LaTeX leaks. DOM-free: it inspects the HTML string latex->html emits, before
   the app pipeline's tex-normalize/MathJax passes (so math is still `span.inline-math` carrying its TeX source)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.latex :as latex]
            ["fs" :as fs]))

(def ^:private doc
  (str "\\documentclass{article}\n"
       "\\usepackage{amsthm,amsmath,xcolor}\n"
       "\\newtheorem{theorem}{Theorem}\n"
       "\\newtheorem{definition}[theorem]{Definition}\n"
       "\\newtheorem{obligation}{Obligation}\n"
       "\\definecolor{redcopy}{rgb}{0.78,0.08,0.08}\n"
       "\\newcommand{\\rc}[1]{\\textcolor{redcopy}{#1}}\n"
       "\\newcommand{\\rSet}{\\rc{\\mathsf{Set}}}\n"
       "\\newcommand{\\dT}{\\partial T}\n"
       "\\newcommand{\\quo}[1]{@#1}\n"
       "\\title{Test Title\\\\ Subtitle}\n"
       "\\author{An Author\\\\ Affiliation}\n"
       "\\date{June 2026}\n"
       "\\begin{document}\n"
       "\\maketitle\n"
       "\\begin{abstract}\nAbstract text with \\cite{knuth}.\n\\end{abstract}\n"
       "\\tableofcontents\n"
       "\\section{Intro}\\label{sec:intro}\n"
       "See \\S\\ref{sec:next}, eq \\eqref{eq:main}, cites \\cite{knuth} and \\cite{a,b}, math $\\rSet$ and $\\quo{x}$.\n"
       "\\begin{equation}\\label{eq:main}\nx = y\n\\end{equation}\n"
       "\\subsection{Views of \\texorpdfstring{$\\dT$}{dT}}\n"
       "\\begin{definition}[The widget]\\label{def:widget}\n"
       "A definition. \\begin{equation}\\label{eq:nested}\na = b\n\\end{equation}\n\\end{definition}\n"
       "\\begin{obligation}\\label{ob:one}\nAn obligation.\n\\end{obligation}\n"
       "\\section{Next}\\label{sec:next}\n"
       "Refers to Obligation~\\ref{ob:one} and Definition~\\ref{def:widget}.\n"
       "\\begin{thebibliography}{9}\n"
       "\\bibitem{knuth} D. Knuth. \\emph{The Art.} 1968.\n"
       "\\bibitem{a} Author A. Title A.\n"
       "\\bibitem{b} Author B. Title B.\n"
       "\\end{thebibliography}\n"
       "\\end{document}\n"))

(def ^:private html (latex/latex->html doc))

(deftest front-matter
  (testing "\\maketitle → a titled <section>; the abstract env → an Abstract heading + section; \\tableofcontents → a ul"
    (is (re-find #"<section[^>]*id=\"vv-doc-header\"" html) "\\maketitle → a <section id=vv-doc-header> block")
    (is (re-find #"<h1[^>]*>Test Title" html)              "…containing the title as an <h1>")
    (is (re-find #"An Author" html)                        "…the author")
    (is (re-find #"June 2026" html)                        "…and the date")
    (is (re-find #"<h2[^>]*>Abstract</h2>" html)           "abstract → an <h2>Abstract</h2>")
    (is (re-find #"<section[^>]*id=\"vv-abstract\"" html)  "abstract body → a <section id=vv-abstract>")
    (is (re-find #"<ul[^>]*id=\"vv-toc\"" html)            "\\tableofcontents → a <ul id=vv-toc>")
    (is (not (re-find #"\\maketitle|\\tableofcontents" html)) "no front-matter macro leaks")))

(deftest section-numbering-and-anchors
  (testing "sections get a running number, an explicit slug id, and a level by depth; the TOC links to them"
    (is (re-find #"<h2 id=\"1-intro\">1 Intro" html)             "section → <h2 id=slug>N Title")
    (is (re-find #"<h3 id=\"11-views[^\"]*\">1.1 Views" html)    "subsection → <h3> numbered 1.1")
    (is (re-find #"<h2 id=\"2-next\">2 Next" html)               "the second section is 2")
    (is (re-find #"href=\"#user-content-1-intro\"" html)         "the TOC links to the clobber-prefixed section id")))

(deftest references
  (testing "\\ref→number, \\eqref→(number), \\label→removed (all resolved, none leaked)"
    (is (re-find #"Obligation~?\s*1" html)     "\\ref{ob:one} → 1 (obligation own counter)")
    (is (re-find #"Definition~?\s*1" html)     "\\ref{def:widget} → 1 (shared theorem counter)")
    (is (re-find #"\(1\)" html)                "\\eqref{eq:main} → (1)")
    (is (not (re-find #"\\label|sec:intro|sec:next|def:widget|ob:one" html)) "no \\label / raw label-key leaks")
    (is (not (re-find #"\?\?|\(\?\)" html))    "no unresolved reference markers")))

(deftest citations
  (testing "\\cite → the [n] marker; a text cite links to its bib entry, a multi-key cite lists each number"
    (is (re-find #"\[<a href=\"#user-content-vv-bib-knuth\">1</a>\]" html) "\\cite{knuth} → [<a …>1</a>]")
    (is (re-find #"vv-bib-a\">2</a>, <a href=\"#user-content-vv-bib-b\">3</a>" html)
        "\\cite{a,b} → [2, 3], each number linked to its own entry")
    (is (not (re-find #"\\cite" html)) "no \\cite leaks")))

(deftest bibliography
  (testing "thebibliography → a <ul> hanging list; each entry is <li id=vv-bib-KEY>[n] body</li>"
    (is (re-find #"<ul[^>]*id=\"vv-bibliography\"" html)       "→ a <ul id=vv-bibliography>")
    (is (re-find #"<li id=\"vv-bib-knuth\">\[1\] " html)       "first entry is [1] with an anchor id")
    (is (re-find #"\[1\][^<]*D. Knuth" html)                   "the entry body follows the [1] mark")
    (is (re-find #"<li id=\"vv-bib-b\">\[3\] " html)           "the third entry is [3]")
    (is (not (re-find #"\\bibitem" html))                     "no \\bibitem leaks")))

(deftest theorem-numbering
  (testing "a numbered theorem env gets an injected header; the shared counter spans the theorem family, obligation owns one"
    (is (re-find #"<strong>Definition 1 \(The widget\)\." html)
        "definition (first theorem-family env) → Definition 1, with its optional title")
    (is (re-find #"<strong>Obligation 1\." html)
        "obligation has its OWN counter → Obligation 1")))

(deftest equations-are-tagged
  (testing "each equation (including one nested inside a theorem env) is numbered in document order via \\tag{N}"
    (is (re-find #"\\tag\{1\}" html) "the top-level equation is \\tag{1}")
    (is (re-find #"\\tag\{2\}" html) "the equation NESTED inside the definition is \\tag{2} (document order)")))

(deftest texorpdfstring-and-math-fixes
  (testing "\\texorpdfstring keeps the TeX (math) form; named colours resolve to rgb; a bare @ is neutralised"
    (is (not (re-find #"texorpdfstring" html))          "\\texorpdfstring is resolved, not leaked")
    (is (re-find #"1.1 Views" html)                     "the $\\dT$ heading renders (with its inline math)")
    (is (re-find #"\\textcolor\[rgb\]\{0.78,0.08,0.08\}" html) "\\textcolor{redcopy} → \\textcolor[rgb]{spec}")
    (is (not (re-find #"redcopy" html))                 "the named colour + \\definecolor are gone from the output")
    (is (re-find #"@\{\}x" html)                        "\\quo{x} → @x with the @ neutralised to @{} (amscd-safe)")
    (is (not (re-find #"\\rc\b|\\rSet\b|\\quo\b" html)) "the nested user macros fully expanded (no half-expanded leaks)")))

(deftest fragment-is-untouched
  (testing "with NO \\begin{document}, restructure! is a no-op — an Org-embedded fragment renders as before"
    (let [frag (latex/latex->html "\\section{Hi} and \\textbf{bold} text")]
      (is (re-find #"<h3>Hi</h3>" frag)          "a bare \\section still lowers via convertToHtml (h3, unnumbered)")
      (is (not (re-find #"vv-doc-title|vv-toc|id=\"" frag)) "no document-structure rewriting for a fragment")
      (is (re-find #"<b" frag)                   "ordinary fragment rendering is unaffected"))))

(deftest in-math-citation-is-plain
  (testing "a citation INSIDE math becomes plain [n] text (a frozen math string can hold no anchor)"
    (let [h (latex/latex->html
             (str "\\begin{document}\nMath $a + \\cite{x} + b$.\n"
                  "\\begin{thebibliography}{9}\\bibitem{x} X. 2020.\\end{thebibliography}\n\\end{document}"))]
      (is (re-find #"inline-math\">[^<]*\[1\]" h) "the in-math \\cite is a plain [1] inside the math span")
      (is (not (re-find #"<a[^>]*>1</a>[^<]*</span>" h)) "…not an <a> anchor frozen into the math"))))

;; ── a guarded oracle over the real paper (skipped when the file is absent, so the suite never depends on it) ──
(def ^:private real-doc "/home/dylon/Workspace/f1r3fly.io/publications/knotted-topoi/knotted-topoi.tex")

(deftest knotted-topoi-has-no-structural-leaks
  (when (.existsSync fs real-doc)
    (testing "the real knotted-topoi.tex renders with every structural macro resolved and a numbered bibliography"
      (let [h (latex/latex->html (.readFileSync fs real-doc "utf8"))]
        (doseq [pat [#"\\re\b" #"\\rc\b" #"\\quo\b" #"\\cite" #"\\ref\{" #"\\eqref" #"\\label"
                     #"\\maketitle" #"\\tableofcontents" #"texorpdfstring" #"html-tag|html-attr" #"\(\?\)"]]
          (is (not (re-find pat h)) (str "no leak of " pat)))
        (is (<= 25 (count (re-seq #"<li id=\"vv-bib-" h))) "the bibliography is a numbered list of ~30 entries")
        (is (re-find #"id=\"vv-doc-header\"" h) "the title block renders")
        (is (<= 20 (count (re-seq #"<h[234] id=\"" h))) "the sections are numbered headings with anchors")))))
