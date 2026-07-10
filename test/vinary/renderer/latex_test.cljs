(ns vinary.renderer.latex-test
  "Round-trip tests for standalone LaTeX (.tex) support: a sample document through the shared tex-pipeline
   (renderer.latex converts LaTeX → HTML string → raw node, then the same app suffix + tex-normalize as Org),
   then hast->IR, must yield the expected IR node kinds and a slugged heading TOC, and its LOWERED HTML must carry
   the shapes the shared post-passes need — code.math-* for MathJax, language-* for highlighting, real <table>/
   <b>/<ul> for layout. Headless — drives the pipeline directly (not renderer.markdown, which pulls in
   CodeMirror), mirroring vinary.ir.frontend.org-test. It LOWERS the IR through ir.backend.html (the path
   render-latex-ir takes) and guards the `ir-html/blank?` contract (\"\" is truthy in CLJS — the silent blank
   pane). Also unit-tests the pure renderer.latex/latex->html converter, including the macro preprocessor."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.latex :as latex]
            [vinary.renderer.markdown-pipeline :as pipeline]
            [vinary.ir.backend.html :as ir-html]
            [vinary.ir.frontend.markdown :as ir-md]
            [vinary.ir.capability.toc :as ir-toc]
            [vinary.ir.node :as node]))

(def ^:private sample
  (str "\\documentclass{article}\n"
       "\\usepackage{amsmath}\n"
       "\\usepackage{booktabs}\n"
       "\\newcommand{\\price}{\\$18,500}\n"
       "\\begin{document}\n"
       "\\section{Introduction}\n"
       "Some \\textbf{bold} text and \\emph{emphasis}, with an inline value $x^2 + 1$.\n"
       "\\subsection{Details}\n"
       "\\begin{itemize}\n"
       "\\item First point\n"
       "\\item Second point\n"
       "\\end{itemize}\n"
       "\\begin{align}\n"
       "a &= b \\\\\n"
       "c &= d\n"
       "\\end{align}\n"
       "\\begin{center}\n"
       "\\begin{tabular}{@{}ll@{}}\n"
       "\\toprule Description & Amount \\\\ \\midrule\n"
       "Consulting & \\price \\\\ \\bottomrule\n"
       "\\end{tabular}\n"
       "\\end{center}\n"
       "\\includegraphics[width=5cm]{diagram.png}\n"
       "\\end{document}\n"))

(defn- render-tex
  "renderer.latex → raw tree → tex-processor.runSync → hast->IR; returns (ir, metadata, html). `html` is the IR
   LOWERED back through ir.backend.html — the exact string render-latex-ir hands to the preview. Synchronous (no
   custom Parser), like the office frontend."
  [text]
  (let [metadata (atom {:toc [] :assets #{}})
        tree     (.runSync ^js (pipeline/tex-processor metadata nil nil)
                           (pipeline/latex-raw-tree (latex/latex->html text)))
        ir       (ir-md/hast->ir tree)]
    {:ir ir :meta @metadata :html (ir-html/lower ir)}))

;; ── the pure converter + macro preprocessor ─────────────────────────────────────────────────────────────────

(deftest latex->html-structures
  (testing "unified-latex lowers common constructs to the expected HTML shapes"
    (is (re-find #"<h3>" (latex/latex->html "\\section{Hi}"))          "section → heading")
    (is (re-find #"<b" (latex/latex->html "\\textbf{x}"))              "textbf → <b>")
    (is (re-find #"<ul" (latex/latex->html "\\begin{itemize}\\item a\\end{itemize}")) "itemize → <ul>")
    (is (re-find #"<table" (latex/latex->html "\\begin{tabular}{ll}a & b\\end{tabular}")) "tabular → <table>")
    (is (re-find #"inline-math" (latex/latex->html "value $x^2$ here"))  "inline math → span.inline-math")
    (is (re-find #"display-math" (latex/latex->html "\\[ E=mc^2 \\]"))   "display math → div.display-math")
    (is (re-find #"<img" (latex/latex->html "\\includegraphics{a.png}")) "includegraphics → <img>")))

(deftest latex->html-expands-user-macros
  (testing "the preprocessor discovers \\newcommand definitions, expands their usages, and drops the definitions"
    (let [argless (latex/latex->html "\\newcommand{\\price}{\\$18,500}\nTotal: \\price")]
      (is (re-find #"\$18,500" argless)         "an argument-less macro expands at its use site")
      (is (not (re-find #"newcommand" argless))  "the spent \\newcommand definition is removed"))
    (let [withargs (latex/latex->html "\\newcommand{\\tri}[3]{#1-#2-#3}\nRow: \\tri{a}{b}{c}.")]
      (is (re-find #"a-b-c" withargs) "an argument-bearing macro expands with its attached arguments"))
    (let [preamble (latex/latex->html "Total: \\price" {:preamble "\\newcommand{\\price}{PAID}"})]
      (is (re-find #"PAID" preamble) "a macro defined in an external preamble (e.g. Org #+LATEX_HEADER) expands"))))

(deftest latex->html-never-loses-content
  (testing "unified-latex is lenient; even malformed input renders its recoverable text rather than vanishing"
    (let [html (latex/latex->html "\\begin{unbalanced and some visible words")]
      (is (re-find #"visible words" html) "recoverable text is preserved, never a silent blank"))))

(deftest latex->html-sweeps-leaked-html-like-macros
  (testing "a known-but-unhandled custom-class macro (\\address/\\title/\\href from e.g. the entcs class) stringifies
            its argument, leaking unified-latex's internal \\html-tag: syntax — sweep-html-like rewrites it to real tags"
    (let [html (latex/latex->html "\\address{My Dept\\\\ My University}")]
      (is (not (re-find #"html-tag|html-attr" html)) "no \\html-tag: / \\html-attr: internal syntax leaks into output")
      (is (re-find #"<br" html) "the \\\\ linebreak inside the custom macro became a real <br>"))
    (let [html (latex/latex->html "See \\href{http://example.com}{the site} today.")]
      (is (not (re-find #"html-tag|html-attr" html)) "a leaked \\href (a-tag with class+href attrs) is also swept")
      (is (re-find #"example\.com" html) "the link URL survives"))))

;; ── the full pipeline through the common IR ─────────────────────────────────────────────────────────────────

(deftest tex->ir-structure
  (let [{:keys [ir meta]} (render-tex sample)]
    (is (some? ir) (str "tex-processor produced an IR" (when (:error meta) (str " — error: " (:error meta)))))
    (when ir
      (let [kinds (set (map :kind (node/preorder ir)))]
        (is (contains? kinds :heading) "\\section/\\subsection become :heading nodes")
        (is (contains? kinds :list)    "\\begin{itemize} becomes a :list node")
        (is (contains? kinds :table)   "\\begin{tabular} becomes a :table node")
        (is (or (contains? kinds :image) (contains? kinds :figure)) "\\includegraphics becomes an :image/:figure node")))))

(deftest tex->toc
  (let [{:keys [ir]} (render-tex sample)]
    (when ir
      (let [toc (ir-toc/toc-of ir)]
        (is (<= 2 (count toc)) "the heading TOC lists the section headings")
        (is (every? (comp seq :id) toc) "every TOC entry has a slug id")))))

(deftest tex-stream-blocks-concatenate-to-the-batch-html
  (testing "lowering the IR document's children and concatenating with NO separator reproduces the batch HTML —
            the contract latex-stream-blocks + stream.scheduler sep-for rely on (progressive paint, byte-parity).
            The inter-block whitespace lives in emitted :text leaves, not a re-synthesized separator."
    (let [{:keys [ir html]} (render-tex sample)]
      (when (and ir html)
        (let [per-block (apply str (map ir-html/lower (node/children ir)))]
          (is (= html per-block)
              "streamed per-block lowering is byte-identical to the whole-document lowering"))))))

(deftest tex-lowered-html-shapes
  (let [{:keys [html]} (render-tex sample)]
    (is (some? html) "tex-processor serialized HTML")
    (when html
      (is (not (ir-html/blank? html)) "a real document must NOT lower to the empty string (the blank pane)")
      (is (re-find #"<code class=\"math-inline\">" html)
          "inline LaTeX math becomes code.math-inline (renderer.math selects it)")
      (is (re-find #"<pre><code class=\"math-display\">" html)
          "display/align LaTeX math becomes pre > code.math-display")
      (is (re-find #"begin\{align\}" html) "the align environment's TeX is preserved verbatim for MathJax")
      (is (re-find #"<table" html)  "the tabular renders as a real <table>, not a code block")
      (is (re-find #"<b" html)      "\\textbf renders as bold, not literal markup")
      (is (re-find #"18,500" html)  "the \\price macro expanded inside the table")
      (is (re-find #"<img" html)    "\\includegraphics renders as an <img>")
      (is (not (re-find #"<center" html)) "the deprecated <center> is normalized to a block <div>"))))
