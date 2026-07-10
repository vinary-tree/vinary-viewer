(ns vinary.ir.frontend.org-test
  "Async round-trip test for Org (.org) support: a sample document through the shared org-pipeline (uniorg →
   hast → the same app suffix as Markdown) then hast->IR must yield the expected IR node kinds and a slugged
   heading TOC, and its serialized HTML must keep the per-language `language-*` code classes (so nested
   #+begin_src blocks highlight). Headless — drives the org-pipeline directly (not renderer.markdown, which pulls
   in CodeMirror), mirroring how ir.parity-test exercises the Markdown pipeline.

   It also LOWERS the IR through ir.backend.html — the path render-org-ir actually takes. Asserting only on
   rehype-stringify is what let the blank-preview bug ship: a document of nothing but `#+KEYWORD:` lines and a
   `#+BEGIN_EXPORT latex` block lowered to \"\", and `\"\"` is truthy in ClojureScript, so the preview mounted an
   empty body with no error."
  (:require [cljs.test :refer [deftest is testing async]]
            ["rehype-stringify$default" :as rehype-stringify]
            [vinary.renderer.markdown-pipeline :as pipeline]
            [vinary.ir.backend.html :as ir-html]
            [vinary.ir.backend.sanitize :as sanitize]
            [vinary.ir.frontend.markdown :as ir-md]
            [vinary.ir.capability.toc :as ir-toc]
            [vinary.ir.node :as node]))

(def ^:private sample
  (str "#+TITLE: Demo Document\n"
       "* First Heading\n"
       "Some *bold* text and a [[https://example.com][link]].\n"
       "** Nested Heading\n"
       "#+begin_src python\n"
       "def add(a, b):\n"
       "    return a + b\n"
       "#+end_src\n"
       "#+begin_src clojure\n"
       "(defn add [a b] (+ a b))\n"
       "#+end_src\n"
       "#+begin_src emacs-lisp\n"
       "(message \"hi\")\n"
       "#+end_src\n"
       "| a | b |\n"
       "|---+---|\n"
       "| 1 | 2 |\n"
       "[[./pic.png]]\n"))

;; An invoice-shaped document: nothing but keywords and a LaTeX export block. This is the exact shape that
;; rendered as a silent blank pane — uniorg drops every keyword and drops any export block whose backend is not
;; `html`, leaving a hast root with zero children.
(def ^:private latex-only-sample
  (str "#+TITLE: Invoice #42\n"
       "#+AUTHOR: Ada Lovelace\n"
       "#+DATE: 2026-06-30\n"
       "#+LATEX_HEADER: \\usepackage{booktabs}\n"
       "#+OPTIONS: toc:nil num:nil\n"
       "\n"
       "#+BEGIN_EXPORT latex\n"
       "\\begin{center}\n"
       "  Billing Period: June 2026\n"
       "\\end{center}\n"
       "#+END_EXPORT\n"))

(defn- render-org
  "org-pipeline → capture HAST + stringify → callback (ir, metadata, html). `html` is the IR LOWERED back through
   ir.backend.html — the exact string render-org-ir hands to the preview — not the raw rehype-stringify output."
  [text cb]
  (let [metadata (atom {:toc [] :assets #{}})
        captured (atom nil)]
    (-> (pipeline/org-pipeline metadata nil nil)
        (.use (pipeline/capture-hast captured))
        (.use rehype-stringify)
        (.process text)
        (.then (fn [_file]
                 (let [ir (ir-md/hast->ir @captured)]
                   (cb ir @metadata (ir-html/lower ir)))))
        (.catch (fn [e] (cb nil {:error (.-message e)} nil))))))

(deftest org->ir-structure
  (async done
    (render-org sample
      (fn [ir meta _html]
        (is (some? ir) (str "org-pipeline produced an IR" (when (:error meta) (str " — error: " (:error meta)))))
        (when ir
          (let [kinds (set (map :kind (node/preorder ir)))]
            (is (contains? kinds :heading)    "org headings become :heading nodes")
            (is (contains? kinds :code-block) "#+begin_src becomes :code-block nodes")
            (is (contains? kinds :table)      "org tables become :table nodes")
            (is (or (contains? kinds :image) (contains? kinds :figure)) "org images become :image/:figure nodes")))
        (done)))))

(deftest org->toc
  (async done
    (render-org sample
      (fn [ir _meta _html]
        (when ir
          (let [toc (ir-toc/toc-of ir)]
            (is (<= 2 (count toc)) "the heading TOC lists the org headings")
            (is (every? (comp seq :id) toc) "every TOC entry has a slug id")
            (is (some #(= 1 (:level %)) toc) "the top-level heading is level 1")
            (is (some #(= 2 (:level %)) toc) "the nested heading is level 2")))
        (done)))))

(deftest org-nested-src-block-language-classes
  (async done
    (render-org sample
      (fn [_ir _meta html]
        (is (some? html) "org-pipeline serialized HTML")
        (when html
          ;; the per-language code classes survive the GitHub sanitizer, so both rehype-highlight and the
          ;; tree-sitter post-pass can highlight the nested src blocks (emacs-lisp via the language alias).
          (is (re-find #"language-python" html)     "python src-block keeps its language class")
          (is (re-find #"language-clojure" html)    "clojure src-block keeps its language class")
          (is (re-find #"language-emacs-lisp" html) "emacs-lisp src-block keeps its language class"))
        (done)))))

;; ── the blank-preview regression ────────────────────────────────────────────────────────────────────────────

(deftest latex-export-only-org-is-not-blank
  (testing "a document of only keywords + a LaTeX export block still lowers to renderable HTML — and (ADR-0025)
            a NON-math export block now RENDERS through unified-latex instead of showing as a code block"
    (async done
      (render-org latex-only-sample
        (fn [ir _meta html]
          (is (some? ir) "org-pipeline produced an IR")
          (is (some? html) "the IR lowered to HTML")
          (when html
            (is (not (ir-html/blank? html))
                "the LaTeX-only document must NOT lower to the empty string (the silent blank pane)")
            (is (re-find #"Invoice #42" html)             "#+TITLE renders as document front matter")
            (is (re-find #"Ada Lovelace" html)            "#+AUTHOR renders as document front matter")
            (is (re-find #"Billing Period: June 2026" html) "the export block's content is RENDERED, not swallowed")
            ;; ADR-0025: a non-math #+BEGIN_EXPORT latex block (invoice layout) is now rendered by unified-latex,
            ;; so it is NO LONGER a language-latex code block carrying the vv-tex-attempt marker.
            (is (not (re-find #"language-latex" html))    "a non-math export block is rendered, not a code block")
            (is (not (re-find (re-pattern sanitize/tex-attempt-class) html))
                "…so it no longer carries the MathJax-attempt marker")
            (is (not (re-find #"<center" html))           "unified-latex's <center> is normalized to a block <div>")
            (is (not (re-find #"usepackage" html))        "#+LATEX_HEADER keywords stay dropped"))
          (when ir
            (is (seq (node/children ir)) "the IR document has children")
            (is (some #(= 1 (:level %)) (ir-toc/toc-of ir)) "the title joins the Contents outline"))
          (done))))))

;; ── embedded LaTeX renders as real layout (invoices) — ADR-0025 ─────────────────────────────────────────────

(def ^:private invoice-sample
  (str "#+TITLE: Invoice #7\n"
       "#+LATEX_HEADER: \\newcommand{\\total}{\\$18,500}\n"
       "\n"
       "#+BEGIN_EXPORT latex\n"
       "\\begin{center}\\textbf{Billing Period: June 2026}\\end{center}\n"
       "\\begin{tabular}{@{}ll@{}}\n"
       "\\toprule Description & Amount \\\\ \\midrule\n"
       "Consulting & \\total \\\\ \\bottomrule\n"
       "\\end{tabular}\n"
       "#+END_EXPORT\n"))

(deftest org-embedded-latex-renders-invoice-layout
  (testing "a non-math #+BEGIN_EXPORT latex invoice body renders as a real table + bold, with a #+LATEX_HEADER
            \\newcommand expanded — not a highlighted code block"
    (async done
      (render-org invoice-sample
        (fn [_ir _meta html]
          (when html
            (is (re-find #"<table" html)  "the tabular renders as a real <table>")
            (is (re-find #"<b" html)      "\\textbf renders as bold")
            (is (re-find #"18,500" html)  "the #+LATEX_HEADER \\total macro expanded inside the body")
            (is (re-find #"Billing Period: June 2026" html) "the centered heading text renders")
            (is (not (re-find #"language-latex" html)) "the invoice is rendered, not shown as latex source")
            (is (not (re-find #"newcommand" html))     "the \\newcommand definition is not rendered")
            (is (not (re-find #"<center" html))        "<center> is normalized to a block <div>"))
          (done))))))

(deftest org-embedded-math-still-uses-mathjax-shape
  (testing "a bare math latex-environment must NOT be rerouted to unified-latex — it stays on the MathJax path"
    (async done
      (render-org "Text before.\n\n\\begin{align}\na &= b \\\\ c &= d\n\\end{align}\n"
        (fn [_ir _meta html]
          (when html
            (is (re-find #"<pre><code class=\"math-display\">" html)
                "an align environment stays a code.math-display (uniorg → MathJax), not a unified-latex render")
            (is (re-find #"begin\{align\}" html) "the align TeX is preserved verbatim for MathJax")
            (is (not (re-find #"class=\"tabular\"|environment-align" html))
                "the math environment did NOT go through unified-latex"))
          (done))))))

(deftest org-bare-nonmath-latex-environment-renders
  (testing "a bare non-math \\begin{tabular} (a latex-environment OUTSIDE an export block) renders via unified-latex"
    (async done
      (render-org "Before.\n\n\\begin{tabular}{ll}\nA & B \\\\\nc & d\n\\end{tabular}\n\nAfter.\n"
        (fn [_ir _meta html]
          (when html
            (is (re-find #"<table" html) "the bare tabular renders as a real <table>, not MathJax garbage")
            (is (not (re-find #"data-mjx-error" html)) "it did NOT go to MathJax (which would error on tabular)"))
          (done))))))

(deftest org-bare-text-latex-fragment-renders
  (testing "a bare inline text-formatting macro (\\textbf{…}) renders via unified-latex, while inline math stays MathJax"
    (async done
      (render-org "A \\textbf{bold word} and inline $y^2$ math.\n"
        (fn [_ir _meta html]
          (when html
            (is (re-find #"<b" html) "\\textbf renders as bold")
            (is (re-find #"bold word" html) "its content survives")
            (is (re-find #"<code class=\"math-inline\">" html) "the inline $…$ math STILL becomes code.math-inline (MathJax)"))
          (done))))))

(deftest org-html-export-block-still-raw
  (testing "#+BEGIN_EXPORT html keeps its raw-HTML passthrough (rehype-raw parses it, sanitize cleans it)"
    (async done
      (render-org "#+BEGIN_EXPORT html\n<b>hi</b>\n#+END_EXPORT\n"
        (fn [_ir _meta html]
          (when html
            ;; rehype-raw re-parses the raw node, so the <b> carries data-vv-source-* like any other element
            (is (re-find #"<b[ >]" html) "html export block becomes a real <b> element, not an escaped code block")
            (is (re-find #"</b>" html)   "…and it is closed")
            (is (not (re-find #"&lt;b&gt;" html))    "the markup is not escaped")
            (is (not (re-find #"language-html" html)) "html export block is NOT rendered as a code block"))
          (done))))))

;; ── GFM semantic parity: math / task lists / footnotes ──────────────────────────────────────────────────────

(deftest org-math-normalizes-to-the-shared-mathjax-shape
  (testing "uniorg's span.math / div.math become the code.math-* shape renderer.math/render-html-math selects"
    (async done
      (render-org "Some $E = mc^2$ math.\n\n\\begin{align}\na &= b\n\\end{align}\n"
        (fn [_ir _meta html]
          (when html
            (is (re-find #"<code class=\"math-inline\">" html) "inline org math becomes code.math-inline")
            (is (re-find #"<pre><code class=\"math-display\">" html)
                "display org math becomes pre > code.math-display (the pass replaces the code's parent)")
            (is (not (re-find #"<span class=\"math" html)) "no un-normalized span.math survives"))
          (done))))))

(deftest org-task-lists-match-gfm
  (testing "org checkboxes render as GFM's task-list shape, which GitHub's sanitize schema already allows"
    (async done
      (render-org "- [ ] todo\n- [X] done\n- plain\n"
        (fn [_ir _meta html]
          (when html
            (is (re-find #"contains-task-list" html) "the list carries GFM's contains-task-list class")
            (is (re-find #"task-list-item" html)     "checkbox items carry GFM's task-list-item class")
            (is (re-find #"type=\"checkbox\"" html)  "an <input type=checkbox> survives sanitization")
            (is (re-find #"checked" html)            "[X] renders a checked box"))
          (done))))))

;; ── streaming byte-parity ───────────────────────────────────────────────────────────────────────────────────

(deftest org-stream-blocks-concatenate-to-the-batch-html
  (testing "lowering the IR document's children and concatenating with NO separator reproduces the batch HTML"
    ;; This is the contract markdown/org-stream-blocks + stream.scheduler `sep-for` rely on: the progressive
    ;; engine commits IR children one at a time, so `concat(map lower children)` must equal `lower(document)`
    ;; byte for byte. It holds because the inter-block whitespace lives in emitted :text leaves, not a
    ;; re-synthesized "\n". (renderer.markdown itself pulls in CodeMirror, so we assert the invariant it needs.)
    (async done
      (render-org sample
        (fn [ir _meta html]
          (when (and ir html)
            (let [per-block (apply str (map ir-html/lower (node/children ir)))]
              (is (= html per-block)
                  "streamed per-block lowering is byte-identical to the whole-document lowering")))
          (done))))))

(deftest org-todo-keywords-collapse-to-a-sanitizable-state-class
  (testing "uniorg emits <span class=\"todo-keyword TODO\">; the keyword class is unbounded, so it is collapsed
            to the stable todo/done state class the sanitize schema allows (and app.css styles)"
    (async done
      (render-org "* TODO write it\n* DONE shipped\n* plain heading\n"
        (fn [_ir _meta html]
          (when html
            (is (re-find #"<span class=\"todo\">TODO</span>" html) "a TODO keyword keeps a `todo` class")
            (is (re-find #"<span class=\"done\">DONE</span>" html) "a DONE keyword keeps a `done` class")
            ;; the keyword-specific class must NOT survive: it varies with the configured sequence, so no
            ;; allowlist could enumerate it, and an unstripped class would silently vanish at sanitize time
            (is (not (re-find #"todo-keyword" html)) "the unbounded todo-keyword class is normalized away")
            (is (re-find #"plain heading" html) "a headline with no keyword renders unchanged"))
          (done))))))

(deftest org-footnotes-do-not-outrank-real-headings
  (testing "the footnotes section is an <h2>, not uniorg's default bare <h1>Footnotes:</h1>"
    (async done
      (render-org "* Real Heading\nx[fn:1]\n\n[fn:1] note\n"
        (fn [_ir _meta html]
          (when html
            (is (re-find #"<h2[^>]*>Footnotes</h2>" html) "footnotes render under an h2")
            (is (not (re-find #"<h1[^>]*>Footnotes" html)) "no h1 footnotes heading pollutes the outline"))
          (done))))))
