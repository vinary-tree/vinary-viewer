(ns vinary.ir.frontend.source
  "Source-code front-end: convert a web-tree-sitter parse tree into the common document IR — the direct
   analog of lling-llang's SyntaxNode (kind = grammar node type, span = line/byte range, error/missing flags,
   leaf text = the source slice). The read-only CodeMirror source-view still renders the highlighted code;
   this IR is the canonical syntax parse, and `outline` derives a code Contents outline (top-level
   declarations) — navigation source files previously lacked. Pure — walks tree-sitter node objects, no DOM."
  (:require [clojure.string :as str]
            [vinary.ir.node :as node]))

(defn- pos [^js p] {:line (inc (.-row p)) :column (.-column p)})

(defn node->ir
  "A tree-sitter node → an IR node. Named children become IR children; a node with no named children becomes
   a leaf carrying its source slice. `text` is the full source string."
  [^js n text]
  (let [nc   (.-namedChildCount n)
        kids (when (pos? nc) (mapv #(node->ir (.namedChild n %) text) (range nc)))
        m    (cond-> {:span {:start (assoc (pos (.-startPosition n)) :offset (.-startIndex n))
                             :end   (assoc (pos (.-endPosition n)) :offset (.-endIndex n))}}
               (.-isError n)   (assoc :error? true)
               (.-isMissing n) (assoc :missing? true))]
    (if (seq kids)
      (node/node (keyword (.-type n)) kids m)
      (node/leaf (keyword (.-type n)) (subs text (.-startIndex n) (.-endIndex n)) m))))

(defn tree->ir
  "A parse tree + its source `text` → the IR: the grammar root's children under a :document node."
  [^js tree text]
  (let [root (node->ir (.-rootNode tree) text)]
    (node/node :document (node/children root)
               (assoc (node/node-meta root) :root-type (name (node/kind root))))))

;; grammar node-type substrings that mark an outline-worthy top-level declaration (language-agnostic)
(def ^:private decl-pattern
  #"(?i)(function|method|class|struct|impl|module|interface|enum|trait|def|declaration|definition|namespace|type_alias|const|fn_|procedure)")

(defn- decl? [n] (boolean (re-find decl-pattern (name (node/kind n)))))

(defn- decl-name
  "The name of a declaration `n`: the text of its first identifier-like descendant, else its first source
   line (trimmed)."
  [n]
  (or (some (fn [c] (when (re-find #"(?i)(identifier|name)" (name (node/kind c)))
                      (let [t (str/trim (node/text-content c))] (when (seq t) t))))
            (node/preorder n))
      (first (remove str/blank? (str/split-lines (str/trim (node/text-content n)))))))

;; ── markup Contents: outline a document's HEADINGS (Markdown / Org / LaTeX), not its blocks or preamble ─────
;; The decl outline above is for CODE. Markup formats carry headings, not declarations; each markup grammar
;; shapes them differently, so we extract per grammar — but every branch emits the same
;; {:level :text :id "L<line>" :line} entry the code outline, the ANSI/HTML backends, and the rendered-HTML
;; preview toc all use (one outline contract, format-specialized extraction). :id = `L<start-line>` drives
;; CodeMirror line nav (syntax/scroll-source-to-line!). A prior LaTeX-only fix auto-detected LaTeX via the
;; `:section`/`:paragraph` kinds — which tree-sitter-markdown AND tree-sitter-org ALSO emit — so it mis-outlined
;; every Markdown/Org source view (each `:section`/`:paragraph` became a raw-source-line entry). We now detect by
;; the explicit grammar language, or (no language) by format-UNIQUE kinds only.

(defn- start-line [n] (get-in (node/node-meta n) [:span :start :line]))

(defn- child-text
  "Trimmed text-content of `n`'s first DIRECT child whose kind ∈ `kinds`, else nil."
  [n kinds]
  (some (fn [c] (when (contains? kinds (node/kind c))
                  (let [t (str/trim (node/text-content c))] (when (seq t) t))))
        (node/children n)))

(defn- markup-outline
  "Outline the heading-like nodes of `ir` (preorder, so nested headings are found): keep those matching
   `heading?`, with entry level = (`level` n) and title = (`title` n). Drops any node without a start line or a
   non-blank title."
  [ir heading? level title]
  (into []
        (keep (fn [n]
                (when (heading? n)
                  (let [line (start-line n)
                        lvl  (level n)
                        nm   (title n)]
                    (when (and line lvl nm)
                      {:level lvl :text nm :id (str "L" line) :line line})))))
        (node/preorder ir)))

;; Markdown (tree-sitter-markdown): atx level from the `atx_h{1..6}_marker` child, setext level from the
;; `setext_h{1,2}_underline` child; title from the `inline` (atx) / `paragraph` (setext) content child.
(def ^:private atx-marker-level
  {:atx_h1_marker 1 :atx_h2_marker 2 :atx_h3_marker 3 :atx_h4_marker 4 :atx_h5_marker 5 :atx_h6_marker 6})
(def ^:private setext-underline-level {:setext_h1_underline 1 :setext_h2_underline 2})

(defn- md-heading? [n] (contains? #{:atx_heading :setext_heading} (node/kind n)))
(defn- md-level [n]
  (some #(or (atx-marker-level (node/kind %)) (setext-underline-level (node/kind %))) (node/children n)))
(defn- md-title [n] (child-text n #{:inline :paragraph}))
(defn- markdown-outline [ir] (markup-outline ir md-heading? md-level md-title))

;; Org (tree-sitter-org): headline level = the length of its `stars` child, title = its `item` child.
(defn- org-heading? [n] (= :headline (node/kind n)))
(defn- org-level [n]
  (some (fn [c] (when (= :stars (node/kind c)) (max 1 (count (str/trim (node/text-content c))))))
        (node/children n)))
(defn- org-title [n] (child-text n #{:item}))
(defn- org-outline [ir] (markup-outline ir org-heading? org-level org-title))

;; LaTeX (tree-sitter-latex): level from the sectioning node's kind, title from its `:curly_group` text field
;; (the sectioning command precedes it, the body follows), else the node's first non-blank source line. The
;; preamble `*_definition` family / `class_include` are excluded — they are not sectioning kinds.
(def ^:private section-level
  {:part 1 :chapter 1 :section 1 :subsection 2 :subsubsection 3 :paragraph 4 :subparagraph 5})
(defn- latex-heading? [n] (contains? section-level (node/kind n)))
(defn- latex-level [n] (section-level (node/kind n)))
(defn- latex-title [n]
  (or (child-text n #{:curly_group})
      (first (remove str/blank? (str/split-lines (str/trim (node/text-content n)))))))
(defn- latex-outline [ir] (markup-outline ir latex-heading? latex-level latex-title))

;; LaTeX-UNIQUE node kinds (Markdown/Org emit neither `:curly_group` nor these) → detect LaTeX for the
;; no-language fallback WITHOUT the shared `:section`/`:paragraph`/`:part`/`:chapter` kinds that mis-detected
;; Markdown & Org.
(def ^:private latex-detect-kinds
  #{:class_include :new_command_definition :subsection :subsubsection :subparagraph :curly_group})

(defn- code-outline
  "Code Contents outline: top-level declaration nodes (functions/classes/…) → line-anchored entries."
  [ir]
  (into []
        (comp (filter decl?)
              (keep (fn [n]
                      (let [line (start-line n)
                            nm   (decl-name n)]
                        (when (and line nm)
                          {:level 1 :text (str nm) :id (str "L" line) :line line})))))
        (node/children ir)))

(defn- has-kind? [ir pred] (boolean (some pred (node/preorder ir))))

(defn outline
  "A Contents outline over the source IR, emitting {:level :text :id \"L<line>\" :line} entries (the shape the
   backends + the rendered-HTML preview toc share). Markup → its HEADINGS (Markdown atx/setext · Org headlines ·
   LaTeX \\part/\\chapter/\\section/…, nested); code → its top-level declarations. Dispatch by `lang` (the grammar's
   language, threaded from syntax/parse-outline); with no/unknown `lang`, auto-detect by format-UNIQUE node kinds
   — never the `:section`/`:paragraph` kinds Markdown, Org, and LaTeX share (checking Markdown's and Org's unique
   heading kinds before the LaTeX-unique set keeps the three disjoint). :id = `L<start-line>` for CodeMirror nav."
  ([ir] (outline ir nil))
  ([ir lang]
   (case (some-> lang name str/lower-case)
     ("markdown" "md" "gfm" "mdx") (markdown-outline ir)
     "org"                         (org-outline ir)
     ("latex" "tex")               (latex-outline ir)
     (cond
       (has-kind? ir md-heading?)                                   (markdown-outline ir)
       (has-kind? ir org-heading?)                                  (org-outline ir)
       (has-kind? ir #(contains? latex-detect-kinds (node/kind %))) (latex-outline ir)
       :else                                                        (code-outline ir)))))
