(ns vinary.renderer.latex-structure
  "The LaTeX document-structure engine for standalone `.tex` rendering: it turns the *structural* LaTeX a
   document class provides — cross-references, citations, a bibliography, section/theorem/equation numbering,
   and the `\\maketitle`/`abstract`/`\\tableofcontents` front matter — into the plain HTML shapes the app's
   shared sanitize + MathJax pipeline already understands. It runs ENTIRELY inside unified-latex's own
   `unified@10` (like the rest of `renderer.latex`), mutating the AST BEFORE `convertToHtml` freezes each math
   island to an opaque string — the crux, because a ref/cite/label/colour inside math cannot be rewritten once
   it is a frozen TeX string (see `rewrite-refs!`/`sanitize-math!`, which descend into math via `replaceNode`).

   `restructure!` is a NO-OP unless the AST has a top-level `document` environment, so the Org-embedded LaTeX
   path (fragments like `\\textbf{…}` / an invoice environment, which never carry `\\begin{document}`) is
   untouched — only a full `.tex` document is restructured.

   Six ordered phases (ordered because references are FORWARD — everything must be numbered before anything is
   rewritten to a number):
     1. parse-preamble         — the `\\newtheorem` counter graph, `\\definecolor` map, and `\\title`/`\\author`/
                                 `\\date` argument nodes (all preamble siblings of the document environment).
     2. resolve-texorpdfstring! — `\\texorpdfstring{TeX}{pdf}` → its TeX argument, so an inline-math heading
                                 renders as math (done before numbering so the heading/slug text is settled).
     3. number-document!       — one document-order pass assigning section / theorem / equation numbers, turning
                                 each sectioning macro into an html-heading with an EXPLICIT slug id (so the TOC
                                 anchors are independent of rehype-slug), injecting a theorem header, and
                                 `\\tag{N}`-ing equations; records label→{:num :kind} and the ordered sections.
     4. build-bib!             — `thebibliography`/`\\bibitem{key}` → a `<dl>` vertical list (`<dt
                                 id=vv-bib-KEY>[n]</dt><dd>body</dd>`) and a key→number map.
     5. rewrite-refs!          — `\\ref`/`\\autoref`→num, `\\eqref`→(num), `\\cite`→`[n, …]` (a bib link in text,
                                 plain text in math), `\\label`→removed. Descends into math.
     6. rewrite-frontmatter!   — `\\maketitle`→title/author/date, the abstract env→`<h2>Abstract</h2>`+section,
                                 `\\tableofcontents`→a `<ul>` of section links.
     7. sanitize-math!         — `\\textcolor{name}`→`\\textcolor[rgb]{spec}` from the definecolor map, and a bare
                                 `@` (from `\\quo`→`@#1`) neutralised so MathJax's amscd stops forcing a CD cell.

   Every element emitted (`h1`–`h4`, `p`, `section`, `ul`/`li`, `dl`/`dt`/`dd`, `a`, `strong`, and `id`/`href`/
   `class` attributes) is already in GitHub's sanitize allowlist — this pass needs NO schema change. Anchors use
   the sanitizer's `user-content-` clobber prefix (`id=\"vv-bib-x\"` → `user-content-vv-bib-x`; the matching
   `href=\"#user-content-vv-bib-x\"` is a fragment, so it survives untouched)."
  (:require ["@unified-latex/unified-latex-util-arguments" :refer [getArgsContent]]
            ["@unified-latex/unified-latex-util-replace"   :refer [replaceNode]]
            ["@unified-latex/unified-latex-util-visit"     :refer [visit]]
            ["@unified-latex/unified-latex-util-to-string" :refer [toString]]
            ["@unified-latex/unified-latex-util-html-like" :refer [htmlLike]]
            ["@unified-latex/unified-latex-builder"        :refer [m s arg]]
            ["github-slugger$default"                      :as GithubSlugger]
            [clojure.string :as str]))

;; ── unified-latex node interop (^js so the :advanced release build never renames these external props) ──
(defn- macro? [^js n nm] (and (= "macro" (.-type n)) (= nm (.-content n))))
(defn- environment? [^js n nm] (and (= "environment" (.-type n)) (= nm (.-env n))))
(defn- mathenv-name
  "The environment name of a `mathenv` node (`\\begin{equation}`): its `.env` is a string NODE, not a string."
  [^js n]
  (let [e (.-env n)] (if (string? e) e (.-content e))))
(defn- significant? [^js n] (not (contains? #{"whitespace" "parbreak" "comment"} (.-type n))))

(defn- arg-nodes
  "The content-node array of macro/env `node`'s logical argument `pos` (an integer index, or :last), via
   getArgsContent — nil when that argument is absent/empty."
  [^js node pos]
  (let [a (getArgsContent node)]
    (when (and a (pos? (.-length a)))
      (if (= :last pos) (aget a (dec (.-length a))) (aget a pos)))))

(defn- nodes->text
  "The LaTeX source string of a content-node array (for a label key / citation key / slug text). nil-safe."
  [content]
  (if content (str/trim (toString #js {:type "root" :content content})) ""))

(defn- html
  "An html-like macro node (convertToHtml renders it as a real tag). `content` is a seq of AST nodes; `attrs`
   an optional CLJS map of HTML attributes."
  ([tag content] (html tag content nil))
  ([tag content attrs]
   (htmlLike #js {:tag tag :content (into-array content) :attributes (clj->js (or attrs {}))})))

(defn- clone-nodes [^js nodes] (js/structuredClone nodes))   ; a fresh copy, so the same nodes can sit in two places

;; ── phase 1: preamble (theorem counters, colours, title/author/date) ──────────────────────────────────────
(defn- parse-preamble
  "Harvest from the AST's preamble siblings: the theorem-env graph {env → {:display :counter}} (an env with no
   `[shared]` owns a counter keyed by its own name; otherwise it shares the named counter), the `\\definecolor`
   map {name → {:model :spec}}, and the `\\title`/`\\author`/`\\date` argument node arrays."
  [^js ast]
  (let [theorems (atom {}) colors (atom {}) fm (atom {})]
    (doseq [^js n (array-seq (.-content ast)) :when (= "macro" (.-type n))]
      (case (.-content n)
        "newtheorem"
        (let [nm    (nodes->text (arg-nodes n 1))
              share (arg-nodes n 2)]
          (swap! theorems assoc nm {:display (nodes->text (arg-nodes n 3))
                                    :counter (if (and share (pos? (.-length share))) (nodes->text share) nm)}))
        "definecolor"
        (swap! colors assoc (nodes->text (arg-nodes n 1))
               {:model (nodes->text (arg-nodes n 2)) :spec (nodes->text (arg-nodes n 3))})
        ("title" "author" "date") (swap! fm assoc (keyword (.-content n)) (arg-nodes n :last))
        nil))
    {:theorems @theorems :colors @colors :frontmatter @fm}))

;; ── phase 2: resolve \texorpdfstring{TeX}{pdf} → TeX (before numbering settles the heading/slug text) ──────
(defn- resolve-texorpdfstring!
  [^js ast]
  (replaceNode ast
               (fn [^js node _]
                 (if (macro? node "texorpdfstring")
                   (or (arg-nodes node 0) #js [])
                   js/undefined))))

;; ── phase 3: number sections / theorems / equations; headings, theorem headers, equation tags ─────────────
(defn- alpha [n] (str (char (+ 64 n))))   ; 1→A, 2→B … (appendix section labels)

(defn- record-inner-label!
  "Record the DIRECT-CHILD `\\label` of a numbered environment (a theorem env / equation) → {:num :kind} in the
   `labels` atom. Direct children only, so a `\\label` nested one level deeper (e.g. an equation's own label inside
   a theorem env) belongs to that inner block, not this one."
  [labels ^js env num kind]
  (doseq [^js c (array-seq (.-content env)) :when (macro? c "label")]
    (swap! labels assoc (nodes->text (arg-nodes c :last)) {:num num :kind kind})))

(defn- number-sections!
  "Top-level document-order walk numbering the sectioning macros (they are flat siblings, and a section's label
   is its next sibling — both need the sibling context a recursive visit lacks). Replaces each sectioning macro
   with an html heading (level by depth, an EXPLICIT slug id so the anchors are independent of rehype-slug, the
   number prepended); records the trailing `\\label`; fills the `sections` atom (ordered [{:level :num :nodes
   :id}]) for the table of contents. `\\appendix` switches section numbers to A, B, C…"
  [^js doc ^js slugger labels sections]
  (let [content (.-content doc)
        n       (.-length content)
        sec (atom 0) sub (atom 0) subsub (atom 0) appendix? (atom false)
        out (array)]
    (letfn [(next-sig [i] (loop [j i] (when (< j n) (if (significant? (aget content j)) (aget content j) (recur (inc j))))))]
      (loop [i 0]
        (if (>= i n)
          (set! (.-content doc) out)
          (let [^js node (aget content i)]
            (cond
              (macro? node "appendix")
              (do (reset! appendix? true) (reset! sec 0) (reset! sub 0) (reset! subsub 0) (recur (inc i)))

              (some #(macro? node %) ["section" "subsection" "subsubsection"])
              (let [kind (.-content node)]
                (case kind
                  "section"       (do (swap! sec inc) (reset! sub 0) (reset! subsub 0))
                  "subsection"    (do (swap! sub inc) (reset! subsub 0))
                  "subsubsection" (swap! subsub inc))
                (let [sec-str (if @appendix? (alpha @sec) (str @sec))
                      num     (case kind
                                "section"       sec-str
                                "subsection"    (str sec-str "." @sub)
                                "subsubsection" (str sec-str "." @sub "." @subsub))
                      level   (case kind "section" "h2" "subsection" "h3" "subsubsection" "h4")
                      title   (arg-nodes node :last)
                      id      (.slug slugger (str num " " (nodes->text title)))]
                  (swap! sections conj {:level level :num num :nodes title :id id})
                  (when-let [^js nxt (next-sig (inc i))]
                    (when (macro? nxt "label")
                      (swap! labels assoc (nodes->text (arg-nodes nxt :last)) {:num num :kind "section"})))
                  (.push out (html level (cons (s (str num " ")) (when title (array-seq title))) {:id id}))
                  (recur (inc i))))

              :else (do (.push out node) (recur (inc i))))))))))

(defn- number-blocks!
  "Number the theorem environments and equations in document order, RECURSIVELY (a `visit` is depth-first
   pre-order, so an equation nested inside a theorem environment is numbered in its true document position).
   Two passes: a READ-ONLY visit assigns each block its number (so no node is mutated mid-traversal), then the
   collected work injects a `<strong>` theorem header (from the shared/own counter) and appends `\\tag{N}` to each
   equation and records their inner labels. Independent of section numbering, so the pass order does not matter."
  [^js ast theorems labels]
  (let [thm (atom {}) eqn (atom 0) thm-jobs (array) eqn-jobs (array)]
    (visit ast
           (fn [^js node _]
             (cond
               (and (= "environment" (.-type node)) (contains? theorems (.-env node)))
               (let [info (get theorems (.-env node))]
                 (swap! thm update (:counter info) (fnil inc 0))
                 (.push thm-jobs #js {:node node :num (str (get @thm (:counter info))) :info info}))
               (and (= "mathenv" (.-type node)) (= "equation" (mathenv-name node)))
               (do (swap! eqn inc) (.push eqn-jobs #js {:node node :num (str @eqn)})))
             ;; the visitor MUST NOT return a number — `visit` reads a numeric return as an Index action and
             ;; re-traverses from it (`.push`/`swap!` return numbers); nil = "continue normally".
             nil)
           #js {:includeArrays false})
    (doseq [^js j (array-seq thm-jobs)]
      (let [^js node (.-node j) num (.-num j) info (.-info j) ttl (arg-nodes node 0)
            head (concat [(s (str (:display info) " " num))]
                         (when (and ttl (pos? (.-length ttl))) (concat [(s " (")] (array-seq ttl) [(s ")")]))
                         [(s ". ")])]
        (record-inner-label! labels node num (:display info))
        ;; a plain <strong> (bold) header — the sanitize schema strips a class from <strong>, and the injected
        ;; number/title text is the meaningful part anyway ("Definition 3 (…).")
        (set! (.-content node) (into-array (cons (html "strong" head)
                                                 (array-seq (.-content node)))))))
    (doseq [^js j (array-seq eqn-jobs)]
      (let [^js node (.-node j) num (.-num j)]
        (record-inner-label! labels node num "equation")
        (.push (.-content node) (m "tag" #js [(arg #js [(s num)])]))))))

;; ── phase 4: bibliography → a vertical hanging-indent list + key→number map ───────────────────────────────
;; NOTE on hooks: GitHub's sanitize schema strips a `class` VALUE it does not whitelist (from EVERY element), so a
;; custom class never survives to the DOM — but an `id` (any value) does, clobber-prefixed to `user-content-…`.
;; So every stylable container this engine emits carries an `id` (see app.css `#user-content-vv-…`), not a class.
(defn- build-bib!
  "Replace the `thebibliography` environment (if present) with a `<ul id=vv-bibliography>` whose every entry is a
   `<li id=vv-bib-KEY>[n] body</li>` — a vertically-aligned hanging-indent list, the `[n]` mark matching the cite
   marks and doubling as its anchor target. Returns the key→number (1-based) map for `rewrite-refs!`."
  [^js doc]
  (let [content (.-content doc)
        idx     (first (keep-indexed (fn [i ^js x] (when (environment? x "thebibliography") i)) (array-seq content)))]
    (if (nil? idx)
      {}
      (let [^js bib (aget content idx)
            items   (filter #(macro? % "bibitem") (array-seq (.-content bib)))
            key->n  (atom {}) kids (array)]
        (doseq [[i ^js bi] (map-indexed vector items)]
          (let [num  (inc i)
                args (getArgsContent bi)
                key  (nodes->text (some #(when (aget args %) (aget args %)) (reverse (range (dec (.-length args))))))
                body (aget args (dec (.-length args)))]
            (swap! key->n assoc key num)
            (.push kids (html "li" (cons (s (str "[" num "] ")) (when body (array-seq body))) {:id (str "vv-bib-" key)}))))
        (aset content idx (html "ul" (array-seq kids) {:id "vv-bibliography"}))
        @key->n))))

;; ── phase 5: references / citations / labels (descends into math) ─────────────────────────────────────────
(defn- cite-nodes
  "The replacement for a `\\cite{k1,k2,…}`: the marker `[n1, n2, …]` (nᵢ = bib number of keyᵢ; an unknown key
   falls back to its raw key). In text mode each number links to its bib entry; in math mode it is plain text
   (a frozen math string can hold no anchor)."
  [^js node key->n in-math?]
  (let [parts (->> (str/split (nodes->text (arg-nodes node :last)) #",")
                   (map str/trim) (remove str/blank?)
                   (map (fn [k] [k (get key->n k)])))]
    (if in-math?
      (s (str "[" (str/join ", " (map (fn [[k num]] (str (or num k))) parts)) "]"))
      (into-array (concat [(s "[")]
                          (interpose (s ", ")
                                     (map (fn [[k num]]
                                            (if num
                                              (html "a" [(s (str num))] {:href (str "#user-content-vv-bib-" k)})
                                              (s k)))
                                          parts))
                          [(s "]")])))))

(defn- rewrite-refs!
  "Rewrite the reference/citation/label macros ACROSS the whole tree (descending into math, so an in-math ref/
   cite becomes a plain number before that math freezes): `\\ref`/`\\autoref`→num, `\\eqref`→(num), `\\cite`→the
   `[n, …]` marker, `\\label`→removed. An unresolved label renders `?` rather than leaking its raw key."
  [^js ast labels key->n]
  (replaceNode ast
               (fn [^js node ^js info]
                 (let [in-math? (boolean (some-> (.-context info) .-hasMathModeAncestor))]
                   (cond
                     (macro? node "label") nil                                    ; delete
                     (or (macro? node "ref") (macro? node "autoref"))
                     (s (:num (get labels (nodes->text (arg-nodes node :last))) "?"))
                     (macro? node "eqref")
                     (s (str "(" (:num (get labels (nodes->text (arg-nodes node :last))) "?") ")"))
                     (macro? node "cite") (cite-nodes node key->n in-math?)
                     :else js/undefined)))))

;; ── phase 6: front matter (title/author/date, abstract, table of contents) ────────────────────────────────
(defn- toc-node
  "A `<ul id=vv-toc>` of the collected sections; each `<li>` links to the section's anchor via the sanitizer's
   clobber-prefixed id. The link text keeps the section's own nodes (cloned) so a math heading renders as math in
   the contents too. Flat (the `1.1`/`1.1.1` numbers convey the hierarchy — a per-level `li` class would not
   survive the sanitizer)."
  [sections]
  (html "ul"
        (map (fn [{:keys [num nodes id]}]
               (html "li"
                     [(html "a" (cons (s (str num " ")) (when nodes (array-seq (clone-nodes nodes))))
                            {:href (str "#user-content-" id)})]))
             sections)
        {:id "vv-toc"}))

(defn- rewrite-frontmatter!
  "Replace `\\maketitle` (→ a `<section id=vv-doc-header>` of `<h1>` title + `<p>` author + `<p>` date), the
   abstract environment (→ `<h2>Abstract</h2>` + `<section id=vv-abstract>`), and `\\tableofcontents` (→ the
   sections `<ul id=vv-toc>`) in the document body."
  [^js doc frontmatter sections]
  (let [{:keys [title author date]} frontmatter
        out (array)]
    (doseq [^js node (array-seq (.-content doc))]
      (cond
        ;; wrap the title block in a <section> carrying the (sanitizer-surviving) id so the CSS can centre the whole
        ;; block — the inner <h1>/<p> would each lose a class
        (macro? node "maketitle")
        (let [header (cond-> []
                       title  (conj (html "h1" (array-seq title)))
                       author (conj (html "p" (array-seq author)))
                       date   (conj (html "p" (array-seq date))))]
          (when (seq header) (.push out (html "section" header {:id "vv-doc-header"}))))
        (environment? node "abstract")
        (do (.push out (html "h2" [(s "Abstract")]))
            (.push out (html "section" (array-seq (.-content node)) {:id "vv-abstract"})))
        (macro? node "tableofcontents") (.push out (toc-node @sections))
        :else (.push out node)))
    (set! (.-content doc) out)))

;; ── phase 7: math-only sanitation (named colours → rgb; neutralise a bare @) ──────────────────────────────
(defn- sanitize-math!
  "Applied to math (descending) before it freezes: mutate `\\textcolor{name}{x}` → `\\textcolor[rgb]{spec}{x}` in
   place from the `\\definecolor` map (MathJax's color package needs the rgb spec — it never saw the preamble's
   named colour; mutating in place lets replaceNode keep descending into nested colours), and neutralise a bare
   `@` (from `\\quo`→`@#1`) with a following empty group so MathJax's amscd stops forcing a CD cell."
  [^js ast colors]
  (replaceNode ast
               (fn [^js node ^js info]
                 (let [in-math? (boolean (some-> (.-context info) .-hasMathModeAncestor))]
                   (cond
                     (and in-math? (macro? node "textcolor"))
                     (do (when-let [spec (get colors (nodes->text (arg-nodes node 1)))]
                           (let [^js as (.-args node) ^js a0 (aget as 0) ^js a1 (aget as 1)]
                             (set! (.-openMark a0) "[") (set! (.-closeMark a0) "]")
                             (set! (.-content a0) #js [(s (:model spec))])
                             (set! (.-content a1) #js [(s (:spec spec))])))
                         js/undefined)                                             ; keep descending into content
                     (and in-math? (= "string" (.-type node)) (= "@" (.-content node)))
                     (s "@{}")
                     :else js/undefined)))))

(defn restructure!
  "Run the document-structure pass over `ast` (mutating). A no-op unless `ast` has a top-level `document`
   environment (leaving the Org-embedded fragment path untouched). Returns `ast`."
  [^js ast]
  (when-let [^js doc (first (filter #(environment? % "document") (array-seq (.-content ast))))]
    (let [{:keys [theorems colors frontmatter]} (parse-preamble ast)
          slugger  (GithubSlugger.)
          labels   (atom {}) sections (atom [])]
      (resolve-texorpdfstring! ast)
      (number-blocks! ast theorems labels)                  ; theorems + equations (recursive; may nest)
      (number-sections! doc slugger labels sections)        ; sections (flat, need sibling context)
      (rewrite-refs! ast @labels (build-bib! doc))
      (rewrite-frontmatter! doc frontmatter sections)
      (sanitize-math! ast colors)))
  ast)
