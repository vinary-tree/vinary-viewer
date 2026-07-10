(ns vinary.main.file-kind
  "Pure file-kind classification shared by the main-process service and tests."
  (:require [clojure.string :as str]))

(def ^:private markdown-exts #{".md" ".markdown" ".mdx"})
(def ^:private org-exts #{".org"})   ; Emacs Org-mode → rendered via uniorg through the common IR (like markdown)
(def ^:private latex-exts #{".tex" ".latex" ".ltx"})   ; LaTeX → rendered via unified-latex through the common IR
;; NOTE: NOT .sty/.cls/.bib — those are LaTeX support files, better shown as highlighted source than rendered.
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

(defn pdf-sibling-path
  "The same-directory, same-stem candidate `.pdf` path for a document path `p` (PURE — the caller checks
   existence on disk). nil when `p` has no extension (no stem to swap) or IS itself a `.pdf` (a document never
   siblings itself). Case-insensitive on the extension. Lives here (the pure, node-tested classifier) rather than
   in the electron-bound service so the path arithmetic is unit-testable; service.cljs adds the `fs.existsSync`."
  [p]
  (let [ext (extension p)]
    (when (and ext (not= ".pdf" (str/lower-case ext)))
      (str (subs (str p) 0 (- (count (str p)) (count ext))) ".pdf"))))

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
  (let [ext (extension file-path)]
    (cond
      (archive-uri? file-path) "archive"
      (contains? markdown-exts ext) "markdown"
      (contains? org-exts ext) "org"
      ;; latex BEFORE the source? arm: a tree-sitter-latex grammar is bundled, so `.tex` would otherwise
      ;; classify as "source" (highlighted markup) instead of a rendered document — the exact trap org had.
      (contains? latex-exts ext) "latex"
      (contains? image-exts ext) "image"
      (= ".pdf" ext) "pdf"
      (contains? html-exts ext) "html"
      (contains? office-exts ext) "office"
      (contains? table-exts ext) "table"
      (contains? archive-exts ext) "archive"
      (or (contains? log-exts ext) (log-basename? file-path)) "log"
      (contains? mermaid-exts ext) "mermaid"
      (or (contains? source-diagram-exts ext)
          (source? file-path)) "source"
      :else "text")))
