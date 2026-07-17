(ns vinary.renderer.latex
  "LaTeX → HTML string, the reusable front-end shared by standalone `.tex` documents (renderer.markdown-pipeline/
   tex-pipeline) and by LaTeX embedded in Org (markdown-pipeline's export-block / latex-environment / latex-
   fragment handlers, so invoices render as real layout instead of a code block).

   It is NOT a LaTeX compiler: the goal is comprehensible rendering of most documents by reusing the app's
   existing capabilities (MathJax math, sections, styling, tables, figures, syntax-highlighted code). The
   `@unified-latex/*` toolchain parses LaTeX → AST → HTML entirely inside its OWN bundled `unified@10`, so nothing
   here touches the app's `unified@11` — only a plain HTML string crosses the boundary, which the caller feeds
   through the shared `app-hast-suffix` (rehype-raw → sanitize → slug → highlight → …) exactly as the office
   front-end feeds its HTML string. DOM-free (unified-latex's parser + convertToHtml are pure JS), so it runs in
   the GUI, :node-test, the CLI and the TUI.

   Preprocessor: `\\newcommand`/`\\NewDocumentCommand`-family definitions in the document (or in an Org
   `#+LATEX_HEADER:` preamble passed via `:preamble`) are discovered, their usages reparsed so arguments attach,
   and then expanded to a FIXPOINT (`expand-fixpoint`) — so a macro that expands into another macro (`\\rSet`→
   `\\rc`→`\\textcolor`) fully resolves rather than leaking a half-expanded red literal. The spent definitions are
   dropped (they carry no visible output). For a full `.tex` document, `latex-structure/restructure!` then turns
   the structural LaTeX (cross-references, citations, a bibliography, section/theorem/equation numbering, and the
   `\\maketitle`/`abstract`/`\\tableofcontents` front matter) into plain sanitizer-safe HTML — see that namespace.
   Math is preserved verbatim as `span.inline-math` / `div.display-math`; the caller's `tex-normalize` pass remaps
   those to `code.math-inline` / `pre>code.math-display` so the existing MathJax pass (renderer.math/render-html-
   math) typesets them — even `align`/`equation` environments survive intact into MathJax."
  (:require ["@unified-latex/unified-latex-util-parse"    :refer [parse getParser]]
            ["@unified-latex/unified-latex-to-hast"        :refer [convertToHtml wrapPars]]
            ["@unified-latex/unified-latex-util-macros"    :refer [listNewcommands expandMacrosExcludingDefinitions]]
            ["@unified-latex/unified-latex-util-to-string" :refer [toString]]
            [vinary.renderer.latex-structure :as structure]
            [clojure.string :as str]
            [goog.object :as gobj]
            [goog.string :as gstr]))

(def ^:private newcommand-defs
  "The macro names that DEFINE a new command (LaTeX `\\newcommand` family + xparse `\\NewDocumentCommand`
   family). Their nodes are removed after expansion — a definition renders nothing. Hardcoded (rather than
   imported from unified-latex's `LATEX_NEWCOMMAND`/`XPARSE_NEWCOMMAND` Sets) to keep the JS interop surface to
   plain function calls; these names are fixed LaTeX."
  #{"newcommand" "renewcommand" "providecommand"
    "NewDocumentCommand" "RenewDocumentCommand" "ProvideDocumentCommand" "DeclareDocumentCommand"
    "NewExpandableDocumentCommand" "RenewExpandableDocumentCommand"
    "ProvideExpandableDocumentCommand" "DeclareExpandableDocumentCommand"})

(defn- macro-signature-record
  "A unified-latex MacroInfoRecord {name {:signature sig}} from discovered `\\newcommand` specs (a JS array), so a
   reparse attaches each user macro's arguments (e.g. `\\tri{a}{b}{c}`) before expansion."
  [specs]
  (let [rec #js {}]
    (doseq [^js s (array-seq specs)] (gobj/set rec (.-name s) #js {:signature (.-signature s)}))
    rec))

(def ^:private html-like-leak-re
  "unified-latex's intermediate `\\html-tag:TAG{(\\html-attr:NAME{\"VAL\"})* INNER}` macro, as it LEAKS into
   convertToHtml's output when a known-but-unhandled class macro (\\title/\\author/\\address/\\href/… from a custom
   document class) stringifies an argument that already contained converted content (e.g. a `\\\\` linebreak became
   \\html-tag:br, a `\\href` became \\html-tag:a with className + href attrs). `INNER` excludes braces so only the
   innermost level matches; sweep-html-like iterates to a fixpoint to resolve nesting from the inside out."
  #"\\html-tag:(\w+)\{((?:\\html-attr:\w+\{\"[^\"]*\"\})*)([^{}]*)\}")

(def ^:private html-attr-re #"\\html-attr:(\w+)\{\"([^\"]*)\"\}")

(def ^:private void-tags #{"br" "img" "hr" "wbr" "col" "input"})

(defn- html-like-attrs
  "Turn a run of leaked `\\html-attr:NAME{\"VAL\"}` tokens into an HTML attribute string, mapping unified-latex's
   `className` to `class`."
  [blob]
  (->> (re-seq html-attr-re (or blob ""))
       (map (fn [[_ name value]]
              (str " " (if (= "className" name) "class" name) "=\"" value "\"")))
       (apply str)))

(defn- sweep-html-like
  "Rewrite any leaked `\\html-tag:` intermediate macros in an HTML string back into real tags (innermost-first, to
   a fixpoint), so LaTeX documents that use custom-class frontmatter macros render cleanly instead of showing raw
   internal syntax. A no-op for the common case (standard macros never leak). The reconstructed tags are still
   sanitized downstream by app-hast-suffix's rehype-sanitize."
  [html]
  (loop [s (or html "") guard 0]
    (let [s' (str/replace s html-like-leak-re
                          (fn [m]
                            (let [tag   (nth m 1)
                                  attrs (html-like-attrs (nth m 2))
                                  inner (or (nth m 3) "")]
                              (if (contains? void-tags tag)
                                (str "<" tag attrs ">")
                                (str "<" tag attrs ">" inner "</" tag ">")))))]
      (if (and (not= s s') (< guard 30))
        (recur s' (inc guard))
        s'))))

(defn- strip-definitions!
  "Remove `\\newcommand`-family definition nodes from the AST root content (their effect is already applied by
   expand-macros; the definition itself has no visible rendering)."
  [^js ast]
  (set! (.-content ast)
        (.filter (.-content ast)
                 (fn [^js n]
                   (not (and (= "macro" (.-type n))
                             (contains? newcommand-defs (.-content n)))))))
  ast)

(defn- expand-fixpoint
  "Expand user macros to a FIXPOINT (guarded), so a macro that expands into another macro (`\\rSet`→`\\rc`→
   `\\textcolor`) fully resolves — not just one level, which would leak a half-expanded red literal into the math.
   Each round REPARSES the source fresh (mandatory: `expandMacros*` mutates its input and crashes if re-invoked
   on reused body objects) with the merged preamble+body `\\newcommand` signatures, then runs
   `expandMacrosExcludingDefinitions` (which leaves each `\\newcommand`'s own body intact so the next round can
   still harvest it). Converges when `toString` stabilises; the guard bounds a pathological/recursive macro.

   Harvesting the preamble specs separately (Org `#+LATEX_HEADER:` lines via `:preamble`) lets those macros expand
   in the body WITHOUT rendering the preamble's other content (`\\usepackage`, `\\setmainfont`, `\\backgroundsetup{…
   \\includegraphics{logo.pdf}}`), which would inject spurious output — only the body `src` is ever parsed for
   rendering."
  [src preamble]
  (let [pre-specs (if (seq preamble) (listNewcommands (parse (str preamble))) #js [])]
    (loop [src src, prev nil, guard 0]
      (let [specs (.concat pre-specs (listNewcommands (parse src)))
            ^js ast (if (pos? (.-length specs))
                      (.parse (getParser #js {:macros (macro-signature-record specs)}) src)
                      (parse src))]
        (expandMacrosExcludingDefinitions ast (.map specs (fn [^js s] #js {:name (.-name s) :body (.-body s)})))
        (let [now (toString ast)]
          (if (or (= now prev) (>= guard 8))
            ast
            (recur now now (inc guard))))))))

(defn latex->html
  "Convert a LaTeX source fragment to a self-contained HTML string via the unified-latex toolchain. On any parse
   failure, degrade to an escaped `<pre><code class=\"language-latex\">` so content is never lost (mirroring the
   Org export-block code-block fallback). `opts`:
     :inline?  — skip paragraph wrapping (for inline fragments like `\\textbf{…}`); default false (block).
     :preamble — extra LaTeX (e.g. Org `#+LATEX_HEADER:` lines) parsed for macro definitions ahead of the body."
  ([source] (latex->html source nil))
  ([source opts]
   (let [inline?  (:inline? opts)
         preamble (:preamble opts)
         src      (str source)]
     (try
       (let [^js ast (expand-fixpoint src preamble)]
         (structure/restructure! ast)     ; a full .tex document → structural HTML (no-op for a fragment)
         (strip-definitions! ast)
         (when-not inline?
           (set! (.-content ast) (wrapPars (.-content ast))))
         (sweep-html-like (convertToHtml ast)))
       (catch :default _
         (str "<pre><code class=\"language-latex\">" (gstr/htmlEscape src) "</code></pre>"))))))
