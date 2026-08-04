(ns vinary.file-type
  "Explicit file types — the ONE resolver + pairing core shared by every interface (parity: ADR-0036).

   A user can name a document's type instead of relying on its extension: `-t/--type TYPE` at the CLI
   (GUI `vv`, `vv --cli`, `vv --tui` — same grammar everywhere) and Settings ▸ File Type in the GUI.
   TYPE is a standard MIME type (`text/x-diff`, `application/pdf`), a short alias (`diff`, `md`, `csv`),
   or a tree-sitter grammar language (`python`, `rust` — rendered as source with that grammar). Types
   pair with files positionally: the Nth type applies to the Nth file, where a piped-stdin document is
   file 0 (or wherever a lone `-` places it). Files without an explicit type deduce from their extension
   as always, falling back to plain text.

   Kind tokens win over grammar names on collision (`markdown` means the RENDERED document kind, exactly
   as the .md extension does — the highlighted form remains one View Source away); a grammar hit means
   {:kind \"source\" :language <catalog id>}. This namespace is pure (no fs/electron) so the :test build
   covers the whole grammar: main/startup, cli, tui, and the menubar all consume these fns."
  (:require [clojure.string :as str]
            [vinary.grammar-catalog :as gc]))

;; ---- token → kind ------------------------------------------------------------------------------

(def ^:private kind-tokens
  "Short tokens and irregular full-MIME spellings → type spec. Regular MIME forms need no entry here:
   `resolve-type-token` reduces `text/x-markdown` → subtype `x-markdown` → `markdown` and retries this
   table, so only subtypes that differ from a short token (tab-separated-values, the vnd.* office
   mouthfuls) are spelled out in full. :delimiter rides through to the table parser's meta (Papa.parse
   splits on it; absent → auto-detect), mirroring content_service delimiterFor's extension rules."
  {"text" {:kind "text"} "txt" {:kind "text"} "plain" {:kind "text"}
   "markdown" {:kind "markdown"} "md" {:kind "markdown"} "mdx" {:kind "markdown"} "gfm" {:kind "markdown"}
   "org" {:kind "org"}
   "latex" {:kind "latex"} "tex" {:kind "latex"} "ltx" {:kind "latex"}
   "diff" {:kind "diff"} "patch" {:kind "diff"}
   "log" {:kind "log"}
   "csv" {:kind "table" :delimiter ","}
   "tsv" {:kind "table" :delimiter "\t"} "tab" {:kind "table" :delimiter "\t"}
   "text/tab-separated-values" {:kind "table" :delimiter "\t"}
   "psv" {:kind "table" :delimiter "|"}
   "dsv" {:kind "table"} "table" {:kind "table"} "delimited" {:kind "table"}
   "mermaid" {:kind "mermaid"} "mmd" {:kind "mermaid"} "text/vnd.mermaid" {:kind "mermaid"}
   "html" {:kind "html"} "htm" {:kind "html"} "xhtml" {:kind "html"}
   "source" {:kind "source"} "code" {:kind "source"}
   "pdf" {:kind "pdf"}
   "image" {:kind "image"}
   "office" {:kind "office"}
   "application/vnd.openxmlformats-officedocument.wordprocessingml.document" {:kind "office"} ; .docx
   "application/vnd.oasis.opendocument.text" {:kind "office"}                                 ; .odt
   "application/vnd.oasis.opendocument.presentation" {:kind "office"}                         ; .odp
   "application/vnd.oasis.opendocument.formula" {:kind "office"}                              ; .odf
   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" {:kind "table"}        ; .xlsx
   "application/vnd.oasis.opendocument.spreadsheet" {:kind "table"}                           ; .ods
   "archive" {:kind "archive"}
   "zip" {:kind "archive"} "tar" {:kind "archive"} "gzip" {:kind "archive"}
   "java-archive" {:kind "archive"} "epub" {:kind "archive"}})

(def ^:private extra-language-aliases
  "MIME-subtype spellings that name a grammar under a token the catalog's language-aliases don't know.
   (file(1) reports shell scripts as text/x-shellscript.)"
  {"shellscript" "bash"})

(defn- grammar-spec
  "token → {:kind \"source\" :language <catalog id>} when a bundled grammar answers to it (aliases like
   py→python and the .ext fallback come free from grammar-for-language); nil otherwise. The stored
   :language is always the RESOLVED catalog id, so every later lookup (renderer grammar pick, ANSI
   highlighter) round-trips through grammar-for-id without re-consulting the alias table."
  [token]
  (let [tok (get extra-language-aliases token token)]
    (when-let [g (gc/grammar-for-language tok gc/bundled-grammars)]
      {:kind "source" :language (:id g)})))

(defn- mime-subtype
  "\"type/subtype\" → \"subtype\" (nil when there is no '/' or nothing after it)."
  [token]
  (when-let [i (str/index-of token "/")]
    (not-empty (subs token (inc i)))))

(defn- strip-x [s] (if (str/starts-with? s "x-") (subs s 2) s))
(defn- strip-structured-suffix [s] (first (str/split s #"\+")))  ; xhtml+xml → xhtml

(defn resolve-type-token
  "One user-supplied type token → {:kind <str>} (+ :language for a grammar hit, :delimiter for csv/tsv/psv),
   or nil when the token names nothing. Normalization: trim, lower-case, drop a ';charset=…' parameter
   tail. Resolution order — kind table on the full token (kind tokens beat grammar names), any image/*
   MIME, then the MIME subtype (x- prefix and +structured suffix stripped) against the kind table and the
   grammar catalog, and finally the whole token as a grammar language."
  [token]
  (let [tok (-> (str token) (str/split #";") first str/trim str/lower-case)]
    (when (seq tok)
      (or (get kind-tokens tok)
          (when (str/starts-with? tok "image/") {:kind "image"})
          (when-let [sub (mime-subtype tok)]
            (let [sub  (strip-x sub)
                  base (strip-structured-suffix sub)]
              (or (get kind-tokens sub)
                  (get kind-tokens base)
                  (grammar-spec sub)
                  (grammar-spec base))))
          (grammar-spec tok)))))

(defn valid-tokens-hint
  "One-line description of the accepted token classes, for usage errors."
  []
  (str "valid types: text, markdown, org, latex, diff, patch, log, csv, tsv, psv, table, mermaid, html, "
       "source, pdf, image, office, archive; a MIME type (e.g. text/x-diff); "
       "or a grammar language (e.g. python, rust, clojure)"))

;; ---- CLI argument grammar ----------------------------------------------------------------------

(defn scan-open-args
  "Scan an argument vector (mode flag already stripped) for the open grammar every interface shares:
   `-t V` / `--type V` / `--type=V` / `--file-type V` / `--file-type=V` collect raw type tokens in flag
   order; a lone `-` marks where the stdin document goes among the files; everything else dashed goes to
   :passthrough for the caller's own flag loop (files never do — the caller uses :files). Callers whose
   own flags consume a FOLLOWING value (--width 80, --drive keys.txt) must name them in :value-flags so
   that value is not mistaken for a file.
   → {:files [str…] :types [str…] :dash-index int|nil :passthrough [str…]} | {:error \"vv: …\"}"
  ([args] (scan-open-args args nil))
  ([args {:keys [value-flags]}]
   (let [vflags (set value-flags)]
     (loop [as (seq args) files [] types [] dash nil pass []]
       (if-not as
         {:files files :types types :dash-index dash :passthrough pass}
         (let [a (str (first as))]
           (cond
             (#{"-t" "--type" "--file-type"} a)
             (let [v (second as)]
               (if (or (nil? v) (str/starts-with? (str v) "-"))
                 {:error (str "vv: " a " requires a value (a MIME type, short type token, or grammar language)")}
                 (recur (nnext as) files (conj types (str v)) dash pass)))

             (or (str/starts-with? a "--type=") (str/starts-with? a "--file-type="))
             (let [v (subs a (inc (str/index-of a "=")))]
               (if (seq v)
                 (recur (next as) files (conj types v) dash pass)
                 {:error (str "vv: " a " requires a value after '='")}))

             (= "-" a)
             (if dash
               {:error "vv: '-' (the stdin document) may appear only once"}
               (recur (next as) files types (count files) pass))

             (contains? vflags a)                         ; the caller's flag + its trailing value
             (let [taken (if (some? (second as)) 2 1)]
               (recur (seq (drop taken as)) files types dash (into pass (take taken as))))

             (str/starts-with? a "-")
             (recur (next as) files types dash (conj pass a))

             :else
             (recur (next as) (conj files a) types dash pass))))))))

(defn resolve-specs
  "THE pairing + resolution step (identical semantics in GUI, cli, and tui). Inputs:
     files      — ordered uris/paths, already normalized/resolved by the caller
     types      — raw type tokens in flag order
     stdin      — nil, or {:path <spilled temp file> :cwd <invoking cwd>} when piped data exists
     dash-index — nil, or the `-` position among files (stdin placement override)
   The stdin document is inserted at dash-index (default: first) and participates in pairing like any
   other file; the Nth type applies to the Nth file; files beyond the types deduce by extension later
   (their spec carries no :kind). Errors — `-` without piped data, more types than files, an unknown
   token, an office/archive type on stdin (their parsers are extension-driven and need a named on-disk
   file) — return {:error \"vv: …\"} instead.
   → {:specs [{:uri <str> :kind <str>? :language <str>? :delimiter <str>? :stdin? true? :cwd <str>?} …]}"
  [{:keys [files types stdin dash-index]}]
  (if (and dash-index (nil? stdin))
    {:error "vv: '-' given but stdin carries no piped data (stdin is a terminal, or the pipe was empty)"}
    (let [files   (vec files)
          entries (if stdin
                    (let [at (min (or dash-index 0) (count files))]
                      (-> (mapv (fn [f] {:uri f}) (subvec files 0 at))
                          (conj {:uri (:path stdin) :stdin? true :cwd (:cwd stdin)})
                          (into (mapv (fn [f] {:uri f}) (subvec files at)))))
                    (mapv (fn [f] {:uri f}) files))
          nf      (count entries)
          nt      (count types)]
      (cond
        (and (zero? nf) (pos? nt))
        {:error "vv: --type given but there is no file or piped input to apply it to"}

        (> nt nf)
        {:error (str "vv: " nt " types given for only " nf " file" (when (not= 1 nf) "s"))}

        :else
        (loop [i 0 out (transient (vec entries))]
          (if (= i nt)
            {:specs (persistent! out)}
            (let [e    (nth entries i)
                  tok  (nth types i)
                  spec (resolve-type-token tok)]
              (cond
                (nil? spec)
                {:error (str "vv: unknown type '" tok "' — " (valid-tokens-hint))}

                (and (:stdin? e) (contains? #{"office" "archive"} (:kind spec)))
                {:error (str "vv: type '" tok "' is not supported for piped input "
                             "(its parser needs a named on-disk file)")}

                :else
                (recur (inc i) (assoc! out i (merge e spec)))))))))))

;; ---- Settings ▸ File Type menu rows ------------------------------------------------------------

(def kind-menu-rows
  "The text-representable document kinds a user may re-interpret an open document as, in menu order.
   Binary/structural kinds (image, pdf, office, archive, directory) are deliberately absent — forcing
   prose through a byte-format parser has no sensible rendering."
  [["text" "Plain Text"] ["markdown" "Markdown"] ["org" "Org"] ["latex" "LaTeX"]
   ["diff" "Diff / Patch"] ["log" "Log"] ["table" "Delimited Table"]
   ["mermaid" "Mermaid"] ["html" "HTML"] ["source" "Source (auto)"]])

(def ^:private language-labels
  "Display-label irregulars for grammar ids; everything else capitalizes (rust → Rust)."
  {"rholang" "Rholang" "metta" "MeTTa" "cpp" "C++" "javascript" "JavaScript" "typescript" "TypeScript"
   "tsx" "TSX" "php" "PHP" "ocaml" "OCaml" "elisp" "Emacs Lisp" "toml" "TOML" "yaml" "YAML"
   "ini" "INI" "cmake" "CMake" "d2" "D2" "bnfc" "BNFC" "html" "HTML" "css" "CSS" "scss" "SCSS"
   "json" "JSON" "xml" "XML" "latex" "LaTeX" "tlaplus" "TLA+" "proto" "Protobuf"
   "gitignore" "gitignore" "markdown" "Markdown" "org" "Org" "make" "Make"})

(def ^:private hidden-grammars
  "Catalog entries that are implementation details, not user-selectable languages (markdown-inline is
   the span-level helper grammar the markdown highlighter nests)."
  #{"markdown-inline"})

(defn language-menu-rows
  "[[grammar-id label] …] for every user-facing bundled grammar, sorted by label — the language section
   of Settings ▸ File Type (each row = source rendering with that grammar)."
  []
  (->> gc/bundled-grammars
       (map :id)
       (remove hidden-grammars)
       (mapv (fn [id] [id (get language-labels id (str/capitalize id))]))
       (sort-by (comp str/lower-case second))
       vec))
