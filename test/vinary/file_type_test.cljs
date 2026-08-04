(ns vinary.file-type-test
  "DOM-free unit tests for vinary.file-type — the ONE type-token resolver and file↔type pairing core
   shared by the GUI launch pipeline, vv-cli, vv-tui, and the Settings ▸ File Type menu (ADR-0036).
   Everything here is pure, so this is where the whole CLI grammar's edge cases live: MIME forms,
   grammar-language tokens, stdin insertion and `-` placement, and every error message."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.file-type :as ft]))

;; ---- resolve-type-token ------------------------------------------------------------------------

(deftest token-kind-shorthands
  (testing "short kind tokens map to their document kind"
    (is (= {:kind "text"} (ft/resolve-type-token "text")))
    (is (= {:kind "text"} (ft/resolve-type-token "txt")))
    (is (= {:kind "markdown"} (ft/resolve-type-token "markdown")))
    (is (= {:kind "markdown"} (ft/resolve-type-token "md")))
    (is (= {:kind "org"} (ft/resolve-type-token "org")))
    (is (= {:kind "latex"} (ft/resolve-type-token "tex")))
    (is (= {:kind "diff"} (ft/resolve-type-token "diff")))
    (is (= {:kind "diff"} (ft/resolve-type-token "patch")))
    (is (= {:kind "log"} (ft/resolve-type-token "log")))
    (is (= {:kind "mermaid"} (ft/resolve-type-token "mmd")))
    (is (= {:kind "html"} (ft/resolve-type-token "html")))
    (is (= {:kind "source"} (ft/resolve-type-token "source")))
    (is (= {:kind "pdf"} (ft/resolve-type-token "pdf")))
    (is (= {:kind "image"} (ft/resolve-type-token "image")))
    (is (= {:kind "office"} (ft/resolve-type-token "office")))
    (is (= {:kind "archive"} (ft/resolve-type-token "archive")))))

(deftest token-delimiters
  (testing "csv/tsv/psv carry their delimiter; dsv/table auto-detect (no :delimiter)"
    (is (= {:kind "table" :delimiter ","} (ft/resolve-type-token "csv")))
    (is (= {:kind "table" :delimiter "\t"} (ft/resolve-type-token "tsv")))
    (is (= {:kind "table" :delimiter "|"} (ft/resolve-type-token "psv")))
    (is (= {:kind "table"} (ft/resolve-type-token "dsv")))
    (is (= {:kind "table"} (ft/resolve-type-token "table")))))

(deftest token-mime-forms
  (testing "full MIME types resolve via their subtype (x- prefix and +suffix stripped)"
    (is (= {:kind "text"} (ft/resolve-type-token "text/plain")))
    (is (= {:kind "markdown"} (ft/resolve-type-token "text/markdown")))
    (is (= {:kind "markdown"} (ft/resolve-type-token "text/x-markdown")))
    (is (= {:kind "org"} (ft/resolve-type-token "text/x-org")))
    (is (= {:kind "latex"} (ft/resolve-type-token "text/x-tex")))
    (is (= {:kind "latex"} (ft/resolve-type-token "application/x-latex")))
    (is (= {:kind "diff"} (ft/resolve-type-token "text/x-diff")))
    (is (= {:kind "diff"} (ft/resolve-type-token "text/x-patch")))
    (is (= {:kind "log"} (ft/resolve-type-token "text/x-log")))
    (is (= {:kind "table" :delimiter ","} (ft/resolve-type-token "text/csv")))
    (is (= {:kind "table" :delimiter "\t"} (ft/resolve-type-token "text/tab-separated-values")))
    (is (= {:kind "html"} (ft/resolve-type-token "text/html")))
    (is (= {:kind "html"} (ft/resolve-type-token "application/xhtml+xml")))
    (is (= {:kind "pdf"} (ft/resolve-type-token "application/pdf")))
    (is (= {:kind "mermaid"} (ft/resolve-type-token "text/vnd.mermaid")))
    (is (= {:kind "archive"} (ft/resolve-type-token "application/zip")))
    (is (= {:kind "archive"} (ft/resolve-type-token "application/x-tar")))
    (is (= {:kind "archive"} (ft/resolve-type-token "application/gzip")))
    (is (= {:kind "office"}
           (ft/resolve-type-token "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
    (is (= {:kind "office"} (ft/resolve-type-token "application/vnd.oasis.opendocument.text")))
    (is (= {:kind "table"} (ft/resolve-type-token "application/vnd.oasis.opendocument.spreadsheet")))))

(deftest token-mime-normalization
  (testing "case, surrounding space, and a ;charset parameter tail are all normalized away"
    (is (= {:kind "diff"} (ft/resolve-type-token "TEXT/X-DIFF")))
    (is (= {:kind "diff"} (ft/resolve-type-token "  text/x-diff  ")))
    (is (= {:kind "html"} (ft/resolve-type-token "text/html; charset=utf-8")))
    (is (= {:kind "text"} (ft/resolve-type-token "text/plain;charset=US-ASCII")))))

(deftest token-image-mime
  (testing "any image/* MIME is the image kind"
    (is (= {:kind "image"} (ft/resolve-type-token "image/png")))
    (is (= {:kind "image"} (ft/resolve-type-token "image/svg+xml")))))

(deftest token-grammar-languages
  (testing "grammar languages (and their catalog aliases) resolve to source + the RESOLVED catalog id"
    (is (= {:kind "source" :language "python"} (ft/resolve-type-token "python")))
    (is (= {:kind "source" :language "python"} (ft/resolve-type-token "py")))
    (is (= {:kind "source" :language "rust"} (ft/resolve-type-token "rs")))
    (is (= {:kind "source" :language "cpp"} (ft/resolve-type-token "c++")))
    (is (= {:kind "source" :language "clojure"} (ft/resolve-type-token "clj")))
    (is (= {:kind "source" :language "bash"} (ft/resolve-type-token "sh"))))
  (testing "MIME-wrapped grammar languages work the same way"
    (is (= {:kind "source" :language "python"} (ft/resolve-type-token "text/x-python")))
    (is (= {:kind "source" :language "rust"} (ft/resolve-type-token "text/x-rust")))
    (is (= {:kind "source" :language "cpp"} (ft/resolve-type-token "text/x-c++")))
    (is (= {:kind "source" :language "json"} (ft/resolve-type-token "application/json")))
    (is (= {:kind "source" :language "yaml"} (ft/resolve-type-token "application/x-yaml")))
    (is (= {:kind "source" :language "bash"} (ft/resolve-type-token "text/x-shellscript")))))

(deftest token-kind-beats-grammar
  (testing "kind tokens win over grammar names: `markdown`/`org`/`latex`/`html` mean the RENDERED kind,
            exactly as the matching extension does (the highlighted form is one View Source away)"
    (is (= {:kind "markdown"} (ft/resolve-type-token "markdown")))
    (is (= {:kind "org"} (ft/resolve-type-token "org")))
    (is (= {:kind "latex"} (ft/resolve-type-token "latex")))
    (is (= {:kind "html"} (ft/resolve-type-token "html")))))

(deftest token-unknown
  (testing "unknown tokens resolve to nil (callers turn that into a usage error)"
    (is (nil? (ft/resolve-type-token "diph")))
    (is (nil? (ft/resolve-type-token "application/x-no-such-thing")))
    (is (nil? (ft/resolve-type-token "")))
    (is (nil? (ft/resolve-type-token nil)))
    (is (nil? (ft/resolve-type-token "   ")))))

;; ---- scan-open-args ----------------------------------------------------------------------------

(deftest scan-basic
  (testing "type flags collect in order; files keep their order; nothing else is consumed"
    (is (= {:files ["a.log" "b"] :types ["diff" "md"] :dash-index nil :passthrough []}
           (ft/scan-open-args ["-t" "diff" "a.log" "--type" "md" "b"])))
    (is (= {:files ["a"] :types ["text/x-diff"] :dash-index nil :passthrough []}
           (ft/scan-open-args ["--type=text/x-diff" "a"])))
    (is (= {:files ["a"] :types ["diff" "md"] :dash-index nil :passthrough []}
           (ft/scan-open-args ["--file-type" "diff" "--file-type=md" "a"])))))

(deftest scan-dash-and-passthrough
  (testing "a lone - records the stdin position among files; unknown flags pass through"
    (is (= {:files ["a.md" "b.md"] :types [] :dash-index 1 :passthrough ["--verbose"]}
           (ft/scan-open-args ["a.md" "--verbose" "-" "b.md"])))
    (is (= {:files [] :types [] :dash-index 0 :passthrough []}
           (ft/scan-open-args ["-"])))))

(deftest scan-value-flags
  (testing "the caller's value-taking flags keep their trailing value out of :files"
    (is (= {:files ["doc.md"] :types ["diff"] :dash-index nil :passthrough ["--width" "80"]}
           (ft/scan-open-args ["--width" "80" "-t" "diff" "doc.md"]
                              {:value-flags #{"--width"}})))
    (is (= {:files [] :types [] :dash-index nil :passthrough ["--drive"]}
           (ft/scan-open-args ["--drive"] {:value-flags #{"--drive"}})))))

(deftest scan-errors
  (testing "a type flag without a value (or eating a following flag) errors"
    (is (str/includes? (:error (ft/scan-open-args ["-t"])) "requires a value"))
    (is (str/includes? (:error (ft/scan-open-args ["-t" "--toc" "f"])) "requires a value"))
    (is (str/includes? (:error (ft/scan-open-args ["--type="])) "requires a value"))
    (is (str/includes? (:error (ft/scan-open-args ["-" "a" "-"])) "only once"))))

;; ---- resolve-specs -----------------------------------------------------------------------------

(def ^:private stdin-doc {:path "/run/user/1000/vinary-viewer/stdin/u1/stdin" :cwd "/work/repo"})

(deftest specs-plain-files
  (testing "no types, no stdin: one bare spec per file"
    (is (= {:specs [{:uri "/a.md"} {:uri "/b.txt"}]}
           (ft/resolve-specs {:files ["/a.md" "/b.txt"] :types []})))))

(deftest specs-pairing
  (testing "the Nth type applies to the Nth file; extras deduce (no :kind on their spec)"
    (is (= {:specs [{:uri "/a.log" :kind "diff"} {:uri "/b"}]}
           (ft/resolve-specs {:files ["/a.log" "/b"] :types ["diff"]})))
    (is (= {:specs [{:uri "/a" :kind "diff"} {:uri "/b" :kind "markdown"}]}
           (ft/resolve-specs {:files ["/a" "/b"] :types ["diff" "md"]})))
    (is (= {:specs [{:uri "/x" :kind "source" :language "python"}]}
           (ft/resolve-specs {:files ["/x"] :types ["py"]})))
    (is (= {:specs [{:uri "/d.txt" :kind "table" :delimiter "\t"}]}
           (ft/resolve-specs {:files ["/d.txt"] :types ["tsv"]})))))

(deftest specs-stdin-first
  (testing "a stdin document is file 0 by default and takes the first type"
    (is (= {:specs [{:uri (:path stdin-doc) :stdin? true :cwd "/work/repo" :kind "diff"}
                    {:uri "/notes.md"}]}
           (ft/resolve-specs {:files ["/notes.md"] :types ["diff"] :stdin stdin-doc}))))
  (testing "with no type, the stdin spec stays bare (deduces later → extensionless → text)"
    (is (= {:specs [{:uri (:path stdin-doc) :stdin? true :cwd "/work/repo"}]}
           (ft/resolve-specs {:files [] :types [] :stdin stdin-doc})))))

(deftest specs-dash-placement
  (testing "`-` moves the stdin document to its position; types pair around it positionally"
    (is (= {:specs [{:uri "/a.md" :kind "markdown"}
                    {:uri (:path stdin-doc) :stdin? true :cwd "/work/repo" :kind "diff"}
                    {:uri "/b.md"}]}
           (ft/resolve-specs {:files ["/a.md" "/b.md"] :types ["md" "diff"]
                              :stdin stdin-doc :dash-index 1})))))

(deftest specs-errors
  (testing "`-` without piped data"
    (is (str/includes? (:error (ft/resolve-specs {:files [] :types [] :dash-index 0}))
                       "no piped data")))
  (testing "more types than files"
    (is (str/includes? (:error (ft/resolve-specs {:files ["/a"] :types ["diff" "md"]}))
                       "2 types"))
    (is (str/includes? (:error (ft/resolve-specs {:files [] :types ["diff"]}))
                       "no file or piped input")))
  (testing "unknown token carries the hint"
    (let [e (:error (ft/resolve-specs {:files ["/a"] :types ["diph"]}))]
      (is (str/includes? e "unknown type 'diph'"))
      (is (str/includes? e "valid types"))))
  (testing "extension-driven byte-format parsers are refused for piped input"
    (is (str/includes? (:error (ft/resolve-specs {:files [] :types ["office"] :stdin stdin-doc}))
                       "not supported for piped input"))
    (is (str/includes? (:error (ft/resolve-specs {:files [] :types ["zip"] :stdin stdin-doc}))
                       "not supported for piped input")))
  (testing "…but pdf/image ARE fine for piped input (the temp file feeds their byte routes)"
    (is (= {:specs [{:uri (:path stdin-doc) :stdin? true :cwd "/work/repo" :kind "pdf"}]}
           (ft/resolve-specs {:files [] :types ["pdf"] :stdin stdin-doc})))))

;; ---- menu rows ---------------------------------------------------------------------------------

(deftest menu-kind-rows
  (testing "the kind section is the fixed text-representable set (no binary/structural kinds)"
    (is (= ["text" "markdown" "org" "latex" "diff" "log" "table" "mermaid" "html" "source"]
           (mapv first ft/kind-menu-rows)))
    (is (not-any? #{"image" "pdf" "office" "archive" "directory"} (map first ft/kind-menu-rows)))))

(deftest menu-language-rows
  (let [rows (ft/language-menu-rows)
        ids  (set (map first rows))]
    (testing "every user-facing bundled grammar appears once; the span-level helper grammar does not"
      (is (contains? ids "python"))
      (is (contains? ids "rust"))
      (is (contains? ids "clojure"))
      (is (not (contains? ids "markdown-inline")))
      (is (= (count rows) (count ids))))
    (testing "labels: irregulars are spelled, the rest capitalize, and rows sort by label"
      (is (= ["cpp" "C++"] (first (filter #(= "cpp" (first %)) rows))))
      (is (= ["python" "Python"] (first (filter #(= "python" (first %)) rows))))
      (is (= ["latex" "LaTeX"] (first (filter #(= "latex" (first %)) rows))))
      (is (= (map second rows)
             (sort-by str/lower-case (map second rows)))))))
