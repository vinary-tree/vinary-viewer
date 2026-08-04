(ns vinary.main.startup-test
  "The electron-free main-process startup helpers: --help/--version request detection (so `electron . --help`
   prints usage and exits before opening a window) and the usage/version text."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.main.startup :as startup]))

;; argv shape mirrors `electron <app> <args…>` (index 2 onward is the user's args), same as doc-uris
(defn- argv [& args] (into ["/node/electron" "/app"] args))

(deftest help-request-detection
  (testing "-h/--help → :help; -V/--version → :version; documents → nil"
    (is (= :help    (startup/help-request? (argv "--help"))))
    (is (= :help    (startup/help-request? (argv "-h"))))
    (is (= :version (startup/help-request? (argv "--version"))))
    (is (= :version (startup/help-request? (argv "-V"))))
    (is (nil?       (startup/help-request? (argv "README.md" "a.pdf"))))
    (is (nil?       (startup/help-request? (argv))))
    (testing "a flag anywhere among the args still triggers (e.g. vv --gui --help)"
      (is (= :help (startup/help-request? (argv "--gui" "--help"))))
      (is (= :help (startup/help-request? (argv "foo.md" "--help")))))))

(deftest usage-and-version-text
  (testing "usage lists all three modes"
    (is (str/includes? startup/usage-text "vv [--gui]"))
    (is (str/includes? startup/usage-text "vv --cli"))
    (is (str/includes? startup/usage-text "vv --tui")))
  (testing "version-text prefixes the app name"
    (is (= "vinary-viewer 0.3.0-dev" (startup/version-text "0.3.0-dev")))))

(deftest instance-id-extraction
  (testing "reads the --vv-instance-id=<id> launch key from anywhere in argv, else nil"
    (is (= "abc-123" (startup/instance-id (argv "--vv-instance-id=abc-123"))))
    (is (= "abc-123" (startup/instance-id (argv "README.md" "--vv-instance-id=abc-123" "b.pdf")))
        "position-independent — it can sit among the file args")
    (is (nil? (startup/instance-id (argv "README.md"))) "absent → nil")
    (is (nil? (startup/instance-id (argv))) "no args → nil")
    (is (nil? (startup/instance-id (argv "--vv-instance-id="))) "empty value → nil, not the empty string"))
  (testing "the flag is NOT treated as a document (doc-uris drops it), so the two agree"
    (let [av (argv "--vv-instance-id=xyz" "/abs/README.md")]
      (is (= "xyz" (startup/instance-id av)))
      (is (= ["/abs/README.md"] (startup/doc-uris av identity)) "the id flag never becomes a tab"))))

;; ---- doc-specs: the full open grammar (ADR-0036) -----------------------------------------------

(defn- resolve-stub [p] (if (str/starts-with? p "/") p (str "/cwd/" p)))   ; a deterministic resolve-abs stand-in

(deftest doc-specs-plain
  (testing "no flags: one bare spec per file, resolved exactly like doc-uris"
    (is (= {:specs [{:uri "/cwd/a.md"} {:uri "/abs/b.pdf"}]}
           (startup/doc-specs (argv "a.md" "/abs/b.pdf") resolve-stub))))
  (testing "URIs stay verbatim, never resolve-abs'd"
    (is (= {:specs [{:uri "https://example.com/x"} {:uri "sftp://arch-ws/path/to/file.pdf"}]}
           (startup/doc-specs (argv "https://example.com/x" "sftp://arch-ws/path/to/file.pdf") resolve-stub)))))

(deftest doc-specs-types
  (testing "-t/--type/--type= pair positionally, interleaved freely with files"
    (is (= {:specs [{:uri "/cwd/a.log" :kind "diff"} {:uri "/cwd/b"}]}
           (startup/doc-specs (argv "-t" "diff" "a.log" "b") resolve-stub)))
    (is (= {:specs [{:uri "/cwd/a" :kind "diff"} {:uri "/cwd/b" :kind "markdown"}]}
           (startup/doc-specs (argv "a" "--type=text/x-diff" "b" "--type" "md") resolve-stub)))
    (is (= {:specs [{:uri "/cwd/x" :kind "source" :language "python"}]}
           (startup/doc-specs (argv "--file-type" "py" "x") resolve-stub))))
  (testing "a type on a REMOTE URI rides the verbatim uri (`vv -t diff sftp://host/x.log`)"
    (is (= {:specs [{:uri "sftp://arch-ws/x.log" :kind "diff"}]}
           (startup/doc-specs (argv "-t" "diff" "sftp://arch-ws/x.log") resolve-stub))))
  (testing "errors surface instead of specs"
    (is (str/includes? (:error (startup/doc-specs (argv "-t" "diph" "a") resolve-stub)) "unknown type"))
    (is (str/includes? (:error (startup/doc-specs (argv "-t" "diff" "-t" "md" "only-one") resolve-stub)) "2 types"))
    (is (str/includes? (:error (startup/doc-specs (argv "-t") resolve-stub)) "requires a value"))
    (is (str/includes? (:error (startup/doc-specs (argv "-" "a.md") resolve-stub)) "no piped data")
        "'-' without a spilled stdin document (--vv-stdin) is an error")))

(deftest doc-specs-stdin-marker
  (testing "--vv-stdin=<path> marks which positional arg is the piped document; it takes type 0 and
            carries the invoking cwd for diff-source enrichment"
    (is (= {:specs [{:uri "/run/u/s/1/stdin" :stdin? true :cwd "/work/repo" :kind "diff"}
                    {:uri "/cwd/notes.md"}]}
           (startup/doc-specs (argv "--vv-stdin=/run/u/s/1/stdin" "-t" "diff" "/run/u/s/1/stdin" "notes.md")
                              resolve-stub "/work/repo"))))
  (testing "the marker respects the document's position among the files (a `-` already replaced client-side)"
    (is (= {:specs [{:uri "/cwd/a.md" :kind "markdown"}
                    {:uri "/run/u/s/2/stdin" :stdin? true :cwd "/w" :kind "diff"}]}
           (startup/doc-specs (argv "a.md" "--vv-stdin=/run/u/s/2/stdin" "/run/u/s/2/stdin" "-t" "md" "-t" "diff")
                              resolve-stub "/w"))))
  (testing "a marker whose path is not among the files is ignored (defensive)"
    (is (= {:specs [{:uri "/cwd/a.md"}]}
           (startup/doc-specs (argv "--vv-stdin=/gone/stdin" "a.md") resolve-stub "/w")))))

(deftest doc-specs-doc-uris-equivalence
  (testing "with no type flags and no stdin, (map :uri specs) ≡ doc-uris — the pre-ADR-0036 view"
    (let [av (argv "--vv-instance-id=xyz" "a.md" "https://x.io/y" "sftp://h/z" "/abs/w.pdf")]
      (is (= (startup/doc-uris av resolve-stub)
             (mapv :uri (:specs (startup/doc-specs av resolve-stub))))))))

;; ---- socket-specs: the daemon-socket open message (client-resolved) ----------------------------

(deftest socket-specs-plain-and-legacy
  (testing "a legacy message (args only) yields bare specs — byte-identical pre-ADR-0036 behavior"
    (is (= {:specs [{:uri "/a.md"} {:uri "sftp://h/x"}]}
           (startup/socket-specs {:args ["/a.md" "sftp://h/x"] :types nil :stdin-index nil :cwd nil})))))

(deftest socket-specs-types-and-stdin
  (testing "types pair positionally; stdinIndex reconstructs the stdin spec with the client cwd"
    (is (= {:specs [{:uri "/run/u/s/3/stdin" :stdin? true :cwd "/work" :kind "diff"}
                    {:uri "/b.md"}]}
           (startup/socket-specs {:args ["/run/u/s/3/stdin" "/b.md"] :types ["diff"]
                                  :stdin-index 0 :cwd "/work"})))
    (is (= {:specs [{:uri "/a.md" :kind "markdown"}
                    {:uri "/run/u/s/4/stdin" :stdin? true :cwd "/w" :kind "log"}]}
           (startup/socket-specs {:args ["/a.md" "/run/u/s/4/stdin"] :types ["md" "log"]
                                  :stdin-index 1 :cwd "/w"}))))
  (testing "an out-of-range stdinIndex is ignored (defensive against a malformed client)"
    (is (= {:specs [{:uri "/a.md"}]}
           (startup/socket-specs {:args ["/a.md"] :types [] :stdin-index 7 :cwd "/w"}))))
  (testing "errors surface for the daemon to reply with"
    (is (str/includes? (:error (startup/socket-specs {:args ["/a"] :types ["nope-type"]})) "unknown type"))))
