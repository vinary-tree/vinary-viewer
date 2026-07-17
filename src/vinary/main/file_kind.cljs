(ns vinary.main.file-kind
  "Pure file-kind classification shared by the main-process service and tests."
  (:require [clojure.string :as str]))

(def ^:private markdown-exts #{".md" ".markdown" ".mdx"})
(def ^:private org-exts #{".org"})   ; Emacs Org-mode → rendered via uniorg through the common IR (like markdown)
(def ^:private latex-exts #{".tex" ".latex" ".ltx"})   ; LaTeX → rendered via unified-latex through the common IR
;; NOTE: NOT .sty/.cls/.bib — those are LaTeX support files, better shown as highlighted source than rendered.
(def ^:private diff-exts #{".diff" ".patch"})   ; unified/git diffs → rendered (colored + side-by-side) via vinary.ir.frontend.diff
(def ^:private image-exts #{".png" ".jpg" ".jpeg" ".gif" ".svg" ".webp" ".bmp" ".ico" ".avif"})
(def ^:private mermaid-exts #{".mmd" ".mermaid"})
(def ^:private html-exts #{".html" ".htm" ".xhtml"})   ; rendered live in the web view, not shown as source
(def ^:private office-exts #{".docx" ".odt" ".odp" ".odf"})
(def ^:private table-exts #{".xlsx" ".xlsm" ".ods" ".fods" ".csv" ".tsv" ".tab" ".psv" ".dsv"})
(def ^:private log-exts #{".log" ".out" ".err" ".trace"})
(def ^:private archive-exts #{".zip" ".jar" ".war" ".ear" ".epub" ".tar" ".tgz" ".tar.gz"})
(def ^:private source-diagram-exts
  #{".d2" ".puml" ".plantuml" ".pu" ".iuml" ".wsd" ".dot" ".gv" ".graphviz"})

(defn extension [file-path]
  (let [s (str/lower-case (str file-path))]
    (if (re-find #"\.tar\.gz$" s)
      ".tar.gz"
      (some-> (re-find #"(\.[^./\\]+)$" s) second))))

(defn archive-uri? [uri]
  (str/starts-with? (str uri) "vv-archive://"))

(defn remote-uri?
  "True for an ssh:// or sftp:// remote URI — served by the async remote reader (SFTP over ssh2), not the
   local filesystem path. Mirrors vinary.app.uri/remote? on the main side so service.cljs can short-circuit
   into the async path. `kind-of` needs no remote arm: it classifies off the basename extension, which is
   already correct on the ssh://…/a/b tail (a dotted host never leaks — `extension` is end-anchored)."
  [uri]
  (let [s (str uri)]
    (or (str/starts-with? s "ssh://") (str/starts-with? s "sftp://"))))

(def group-kinds
  "Document kinds that participate in a collocated representation GROUP (the Preview/Source combo). Each such file
   is an alternate representation of the same document — an authored source (markdown/org/latex/mermaid/diff) or
   its compiled PDF. Office/table/log/image/html/source files are NOT group members (they have no Preview/Source
   pairing). Mirrored in content_service.js/GROUP_KINDS (the CLI/TUI + remote twin)."
  #{"pdf" "markdown" "org" "latex" "mermaid" "diff"})

(def ^:private group-exts
  ;; the file extensions whose kinds are in `group-kinds` — the candidate set probed for same-stem collocated
  ;; siblings. Keep in sync with `group-kinds`. Mirrored in content_service.js/GROUP_EXTS.
  (into #{".pdf"} (concat markdown-exts org-exts latex-exts mermaid-exts diff-exts)))

(defn stem
  "The path minus its extension — the same-directory, same-basename stem used to find collocated representations —
   or nil when `p` has no extension. Case-insensitive on the extension (matches `extension`)."
  [p]
  (when-let [ext (extension p)]
    (subs (str p) 0 (- (count (str p)) (count ext)))))

(defn group-candidate-paths
  "Same-directory, same-stem candidate paths for `p`'s document GROUP — `p` itself first, then one path per
   `group-exts` (deduped; `p`'s own re-derived candidate is dropped so `p` is not repeated). PURE: the caller
   checks existence on disk and classifies each via `kind-of`, keeping only `group-kinds` members. `[p]` when `p`
   has no extension (no stem to swap). Replaces the old pairwise `pdf-sibling-path`/`source-sibling-paths`: a
   document may have SEVERAL collocated representations (e.g. `paper.org` + `paper.tex` + `paper.pdf`), not one."
  [p]
  (if-let [s (stem p)]
    (into [p] (comp (map #(str s %)) (remove #{p})) (sort group-exts))
    [p]))

(defn- basename-of [path]
  (-> (str path) (str/replace #"\\" "/") (str/split #"/") last))

(def ^:private well-known-text-names
  ;; standard repo prose files that carry no extension (or a bare name) → plain text, NOT delimited/log-sniffed.
  #{"license" "licence" "copying" "copying.lesser" "copyright" "authors" "contributors" "notice" "patents"
    "install" "news" "thanks" "maintainers" "todo" "version" "readme" "changelog" "codeowners"})

(def ^:private well-known-source-names
  ;; standard repo build/config files (extensionless or dotfiles) → source. Grammar-INDEPENDENT: this guarantees
  ;; a `Makefile` classifies as source (escaped-source route) instead of tripping the delimited-content sniffer,
  ;; whether or not a tree-sitter grammar is bundled for it. Highlighting (where a grammar exists) is layered on
  ;; separately via grammar-catalog/built-in-filetypes.
  #{"makefile" "gnumakefile" "dockerfile" "containerfile" "cmakelists.txt"
    "gemfile" "rakefile" "guardfile" "podfile" "vagrantfile" "brewfile" "capfile" "berksfile" "thorfile"
    "jenkinsfile" "pkgbuild"
    ".gitignore" ".gitattributes" ".gitmodules" ".gitconfig"
    ".dockerignore" ".npmignore" ".prettierignore" ".eslintignore" ".stylelintignore"
    ".editorconfig" ".npmrc" ".inputrc"
    ".bashrc" ".zshrc" ".bash_profile" ".bash_aliases" ".bash_logout" ".zprofile" ".zshenv" ".profile"})

(def ^:private well-known-source-suffixes
  ;; basename suffixes → source (the extension arms miss these — no dedicated ext set)
  [".mk" ".make" ".mak" ".dockerfile" ".gemspec" ".podspec" ".rake"])

(defn well-known-kind
  "Classify a well-known repo file by basename/path, INDEPENDENT of grammar availability — returns \"source\",
   \"text\", or nil. Makes classification deterministic for `Makefile`/`LICENSE`/`.gitignore`/git config/… so a
   Makefile never mis-detects as a delimited table and standard prose files render as plain text. Mirrored in
   content_service.js/wellKnownKind (the CLI/TUI twin)."
  [path]
  (let [name  (str/lower-case (basename-of path))
        norm  (str/replace (str path) #"\\" "/")]
    (cond
      (contains? well-known-text-names name)                        "text"
      (contains? well-known-source-names name)                      "source"
      (some #(str/ends-with? name %) well-known-source-suffixes)    "source"
      (or (= name ".env") (str/starts-with? name ".env."))          "source"   ; .env, .env.local, .env.production …
      (re-find #"(?:^|/)\.git/config$" norm)                        "source"   ; a repo's .git/config
      :else nil)))

(defn- log-basename? [file-path]
  (let [name (some-> (str file-path)
                     (str/replace #"\\" "/")
                     (str/split #"/")
                     last
                     str/lower-case)]
    (boolean (or (#{"syslog" "messages" "secure" "debug" "kern.log" "auth.log"} name)
                 (re-matches #".+\.(log|out|err|trace)\.gz" (or name ""))
                 (re-matches #".+\.log\.\d+(\.gz)?" (or name ""))))))

(defn kind-of
  "Classify file-path. source? is injected so this namespace stays pure and testable."
  [source? file-path]
  (let [ext (extension file-path)
        wk  (well-known-kind file-path)]
    (cond
      (archive-uri? file-path) "archive"
      (contains? markdown-exts ext) "markdown"
      (contains? org-exts ext) "org"
      ;; latex BEFORE the source? arm: a tree-sitter-latex grammar is bundled, so `.tex` would otherwise
      ;; classify as "source" (highlighted markup) instead of a rendered document — the exact trap org had.
      (contains? latex-exts ext) "latex"
      ;; diff/patch BEFORE the source? arm too — rendered (colored + side-by-side) via the diff IR front-end.
      (contains? diff-exts ext) "diff"
      (contains? image-exts ext) "image"
      (= ".pdf" ext) "pdf"
      (contains? html-exts ext) "html"
      (contains? office-exts ext) "office"
      (contains? table-exts ext) "table"
      (contains? archive-exts ext) "archive"
      (or (contains? log-exts ext) (log-basename? file-path)) "log"
      (contains? mermaid-exts ext) "mermaid"
      ;; well-known repo files (Makefile/LICENSE/.gitignore/git config/…) BEFORE the source? arm so their kind is
      ;; deterministic even when no grammar is bundled — a Makefile becomes "source" (not a sniffed delimited table).
      wk wk
      (or (contains? source-diagram-exts ext)
          (source? file-path)) "source"
      :else "text")))
