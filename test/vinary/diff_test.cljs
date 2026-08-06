(ns vinary.diff-test
  "Unit coverage for the pure diff subsystem: the vinary.diff parser (git + plain + rename + new/deleted +
   binary + no-newline + git-format-patch preamble/signature), split-row alignment, and the vinary.ir.frontend.diff
   unified IR lowered through BOTH back-ends — HTML classes/gutters and ANSI colours — so a colored unified diff is
   guaranteed identical across the GUI and the terminal. DOM-free (node :test build)."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.diff :as diff]
            [vinary.ir.frontend.diff :as ir-diff]
            [vinary.ir.backend.html :as ir-html]
            [vinary.ir.backend.ansi :as ansi]
            [vinary.ir.node :as node]
            [vinary.renderer.syntax :as syntax]))

(def ^:private git-modify
  (str "diff --git a/src/foo.txt b/src/foo.txt\n"
       "index e69de29..4b825dc 100644\n"
       "--- a/src/foo.txt\n"
       "+++ b/src/foo.txt\n"
       "@@ -1,3 +1,3 @@\n"
       " line one\n"
       "-line two\n"
       "+line 2\n"
       " line three\n"))

(def ^:private git-new
  (str "diff --git a/new.txt b/new.txt\n"
       "new file mode 100644\n"
       "index 0000000..d95f3ad\n"
       "--- /dev/null\n"
       "+++ b/new.txt\n"
       "@@ -0,0 +1,2 @@\n"
       "+hello\n"
       "+world\n"))

(def ^:private git-delete
  (str "diff --git a/gone.txt b/gone.txt\n"
       "deleted file mode 100644\n"
       "index d95f3ad..0000000\n"
       "--- a/gone.txt\n"
       "+++ /dev/null\n"
       "@@ -1,1 +0,0 @@\n"
       "-obsolete\n"))

(def ^:private plain-u
  (str "--- old.txt\t2024-01-01 10:00:00\n"
       "+++ new.txt\t2024-01-02 11:00:00\n"
       "@@ -1 +1 @@\n"
       "-a\n"
       "+b\n"))

(def ^:private git-rename
  (str "diff --git a/old_name.txt b/new_name.txt\n"
       "similarity index 100%\n"
       "rename from old_name.txt\n"
       "rename to new_name.txt\n"))

(def ^:private git-quoted-paths
  (str "diff --git \"a/src/a file.js\" \"b/src/a file.js\"\n"
       "--- \"a/src/a file.js\"\n"
       "+++ \"b/src/a file.js\"\n"
       "@@ -1 +1 @@\n"
       "-const oldName = 1;\n"
       "+const newName = 1;\n"))

(def ^:private git-octal-utf8-path
  ;; Git core.quotePath's C form for 日.js (UTF-8 e6 97 a5), used by a binary section with no ---/+++ lines so
  ;; the diff --git header itself is authoritative.
  (str "diff --git \"a/src/\\346\\227\\245.js\" \"b/src/\\346\\227\\245.js\"\n"
       "Binary files \"a/src/\\346\\227\\245.js\" and \"b/src/\\346\\227\\245.js\" differ\n"))

(def ^:private git-binary
  (str "diff --git a/logo.png b/logo.png\n"
       "index 1111111..2222222 100644\n"
       "Binary files a/logo.png and b/logo.png differ\n"))

(def ^:private no-newline
  (str "diff --git a/eof.txt b/eof.txt\n"
       "--- a/eof.txt\n"
       "+++ b/eof.txt\n"
       "@@ -1 +1 @@\n"
       "-old line\n"
       "\\ No newline at end of file\n"
       "+new line\n"
       "\\ No newline at end of file\n"))

(def ^:private format-patch
  (str "From abc123def Mon Sep 17 00:00:00 2001\n"
       "From: Dev <dev@example.com>\n"
       "Date: Wed, 1 Jan 2024 00:00:00 +0000\n"
       "Subject: [PATCH] fix the thing\n"
       "\n"
       "This fixes it.\n"
       "---\n"
       " f.txt | 2 +-\n"
       " 1 file changed, 1 insertion(+), 1 deletion(-)\n"
       "\n"
       "diff --git a/f.txt b/f.txt\n"
       "index 111..222 100644\n"
       "--- a/f.txt\n"
       "+++ b/f.txt\n"
       "@@ -1 +1 @@\n"
       "-old\n"
       "+new\n"
       "-- \n"
       "2.39.0\n"))

(deftest parse-git-modify
  (let [{:keys [files preamble]} (diff/parse git-modify)
        f (first files)]
    (is (= "" preamble))
    (is (= 1 (count files)))
    (is (= "src/foo.txt" (:old-path f)))
    (is (= "src/foo.txt" (:new-path f)))
    (is (= "modified" (diff/file-status f)))
    (let [lines (:lines (first (:hunks f)))]
      (is (= [:context :delete :insert :context] (mapv :kind lines)))
      (is (= ["line one" "line two" "line 2" "line three"] (mapv :text lines)))
      ;; line numbering: context advances both, delete advances old, insert advances new
      (is (= [1 2 nil 3] (mapv :old-n lines)))
      (is (= [1 nil 2 3] (mapv :new-n lines))))))

(deftest parse-new-and-deleted
  (let [nf (first (:files (diff/parse git-new)))
        df (first (:files (diff/parse git-delete)))]
    (is (:new-file? nf))
    (is (nil? (:old-path nf)))
    (is (= "new.txt" (:new-path nf)))
    (is (= "added" (diff/file-status nf)))
    (is (= [:insert :insert] (mapv :kind (:lines (first (:hunks nf))))))
    (is (:deleted? df))
    (is (nil? (:new-path df)))
    (is (= "deleted" (diff/file-status df)))))

(deftest parse-plain-unified
  (let [f (first (:files (diff/parse plain-u)))]
    ;; a plain `diff -u` (no `diff --git`) still parses; the trailing tab+timestamp is stripped
    (is (= "old.txt" (:old-path f)))
    (is (= "new.txt" (:new-path f)))
    (is (= [:delete :insert] (mapv :kind (:lines (first (:hunks f))))))))

(deftest parse-rename
  (let [f (first (:files (diff/parse git-rename)))]
    (is (:rename? f))
    (is (= "old_name.txt" (:old-path f)))
    (is (= "new_name.txt" (:new-path f)))
    (is (= "renamed" (diff/file-status f)))
    (is (= "old_name.txt → new_name.txt" (diff/file-label f)))
    (is (empty? (:hunks f)))))

(deftest parse-git-c-quoted-paths
  (let [spaced (first (:files (diff/parse git-quoted-paths)))
        utf8   (first (:files (diff/parse git-octal-utf8-path)))]
    (testing "quoted spaces survive as the real path used for grammar selection and resolution"
      (is (= "src/a file.js" (:old-path spaced)))
      (is (= "src/a file.js" (:new-path spaced))))
    (testing "Git octal UTF-8 quoting decodes before prefix stripping"
      (is (= "src/日.js" (:old-path utf8)))
      (is (= "src/日.js" (:new-path utf8)))
      (is (:binary? utf8)))))

(deftest parse-binary
  (let [f (first (:files (diff/parse git-binary)))]
    (is (:binary? f))
    (is (= "binary" (diff/file-status f)))
    (is (empty? (:hunks f)))))

(deftest parse-no-newline
  (let [f (first (:files (diff/parse no-newline)))
        lines (:lines (first (:hunks f)))]
    (is (= [:delete :insert] (mapv :kind lines)))
    (is (every? :no-newline? lines))))

(deftest parse-format-patch
  (let [{:keys [preamble files]} (diff/parse format-patch)
        f (first files)]
    ;; the email header + commit message + diffstat land in :preamble
    (is (str/includes? preamble "Subject: [PATCH] fix the thing"))
    (is (str/includes? preamble "1 file changed"))
    (is (= 1 (count files)))
    (let [lines (:lines (first (:hunks f)))]
      ;; CRUCIAL: the trailing `-- ` / `2.39.0` signature must NOT be swallowed into the hunk (its leading `-`
      ;; would otherwise read as a deletion) — the hunk is bounded to its declared 1 old / 1 new line budget.
      (is (= [:delete :insert] (mapv :kind lines)))
      (is (= ["old" "new"] (mapv :text lines))))))

(deftest multi-file
  (let [{:keys [files]} (diff/parse (str git-modify git-new git-delete))]
    (is (= 3 (count files)))
    (is (= ["src/foo.txt" "new.txt" "gone.txt"] (map diff/file-label files)))))

(deftest split-row-alignment
  (let [rows (diff/split-rows (first (:files (diff/parse git-modify))))]
    ;; [hunk-sep, context, change (delete paired with insert), context]
    (is (= [:hunk :context :change :context] (mapv :kind rows)))
    (let [change (nth rows 2)]
      (is (= "line two" (get-in change [:old :text])))
      (is (= "line 2"   (get-in change [:new :text]))))))

(deftest referenced-paths
  (is (= ["src/foo.txt" "new.txt"]
         (diff/referenced-paths (diff/parse (str git-modify git-new))))))

(deftest split-html-enrichment
  (let [model   (diff/parse git-modify)
        sources {"src/foo.txt" "line one\nline two\nline three\nline four\nline five\n"}
        html    (diff/split-html model sources)]
    (is (str/includes? html "vv-diff-splitview"))
    (is (str/includes? html "vv-diff-side-old"))
    ;; the enriched view pulls unchanged tail lines (four, five) from the on-disk file
    (is (str/includes? html "line four"))
    (is (str/includes? html "line five"))
    ;; per-file collapsible wrapper (ADR-0037): details/summary, banner id, default-open
    (is (str/includes? html "<details class=\"vv-diff-file vv-diff-split\" data-status=\"modified\" open>"))
    (is (str/includes? html "<summary class=\"vv-diff-file-head\" id=\"vv-diff-file-0\">"))
    (is (not (str/includes? html "<header")) "the banner is a <summary> now, not a <header>")))

(deftest syntax-spans-project-back-to-lines
  ;; One capture crosses a newline. The diff renderer parses a whole logical side, then needs token fragments
  ;; clipped back to its line-oriented grid without losing or duplicating a source byte.
  (let [lines ["abcd" "efgh"]
        projected (syntax/spans->line-segments lines [{:from 2 :to 6 :class "cm-comment"}])]
    (is (= [[{:text "ab"} {:text "cd" :class "cm-comment"}]
            [{:text "e" :class "cm-comment"} {:text "fgh"}]]
           projected))
    (is (= lines (mapv #(apply str (map :text %)) projected)))))

(deftest syntax-segments-layer-under-diff-structure
  (let [model (-> (diff/parse git-modify)
                  (assoc-in [:files 0 :hunks 0 :lines 1 :old-syntax]
                            [{:text "line "} {:text "two" :class "cm-string"}])
                  (assoc-in [:files 0 :hunks 0 :lines 2 :new-syntax]
                            [{:text "line "} {:text "2" :class "cm-number"}]))
        html  (ir-html/lower (ir-diff/model->ir model))
        split (diff/split-html model)]
    (is (str/includes? html "vv-diff-delete"))
    (is (str/includes? html "-line <span class=\"cm-string\">two</span>"))
    (is (str/includes? html "+line <span class=\"cm-number\">2</span>"))
    (is (str/includes? split "vv-diff-row-change"))
    (is (str/includes? split "<span class=\"cm-string\">two</span>"))
    (is (str/includes? split "<span class=\"cm-number\">2</span>"))
    ;; Syntax wrapper nodes are transparent to ANSI: the established diff marker/color contract survives.
    (let [out (ansi/render (ir-diff/model->ir model) {:width 80 :color? false :block-sep "\n"})]
      (is (str/includes? out "-line two"))
      (is (str/includes? out "+line 2")))))

(deftest syntax-segments-remain-escaped-and-class-whitelisted
  (let [model (-> (diff/parse git-modify)
                  (assoc-in [:files 0 :hunks 0 :lines 1 :old-syntax]
                            [{:text "<script>alert(1)</script>"
                              :class "cm-string\" onclick=\"alert(1)"}]))
        unified (ir-html/lower (ir-diff/model->ir model))
        split   (diff/split-html model)]
    (doseq [html [unified split]]
      (is (and (str/includes? html "alert(1)")
               (not (str/includes? html "<script>")))
          "token text survives but never serializes as an element")
      (is (not (str/includes? html "onclick"))
          "a class outside the fixed cm-* grammar is discarded rather than serialized"))))

(deftest diff-header-path-placeholders
  (let [unified (ir-html/lower (ir-diff/diff->ir git-modify))
        rename-u (ir-html/lower (ir-diff/diff->ir git-rename))
        rename-s (diff/split-html (diff/parse git-rename))]
    (is (str/includes? unified "class=\"vv-diff-file-path\" data-vv-diff-path=\"src/foo.txt\""))
    (is (not (str/includes? unified "href=")) "main has not resolved the inert placeholder yet")
    (doseq [html [rename-u rename-s]]
      (is (= 2 (count (re-seq #"class=\"vv-diff-file-path\"" html)))
          "rename old/new paths are independently activatable")
      (is (str/includes? html "data-vv-diff-path=\"old_name.txt\""))
      (is (str/includes? html "data-vv-diff-path=\"new_name.txt\"")))))

(deftest unified-ir->html
  (let [ir   (ir-diff/diff->ir git-modify)
        html (ir-html/lower ir)]
    (is (str/includes? html "vv-diff-file-head"))
    (is (str/includes? html "id=\"vv-diff-file-0\""))
    (is (str/includes? html "vv-diff-insert"))
    (is (str/includes? html "vv-diff-delete"))
    (is (str/includes? html "vv-diff-hunk"))
    ;; the gutter line numbers must serialize as real data-* attributes (camelCase property → data-old/data-new)
    (is (str/includes? html "data-new=\"2\""))
    (is (str/includes? html "data-old=\"2\""))
    ;; per-file collapsible wrapper (ADR-0037): a details element, open by default, summary banner, no h2
    (is (str/includes? html "<details class=\"vv-diff-file\" data-status=\"modified\" open>"))
    (is (str/includes? html "<summary"))
    (is (not (str/includes? html "<h2")) "the banner is a <summary> now, not an <h2>")
    (is (not (ir-html/blank? html)))))

(deftest unified-wrapper-structure
  ;; The two-backend invariants of the per-file wrapper (ADR-0037), asserted on the IR itself:
  ;; one :details wrapper per file, the banner as its FIRST child (the ANSI inline-container guard +
  ;; anchor line), the file id as wrapper META only (the DOM id lives solely on the summary — no
  ;; duplicate getElementById targets), and the default-open attribute.
  (let [ir       (ir-diff/diff->ir (str git-modify git-new))
        wrappers (filterv #(= :details (node/kind %)) (node/children ir))]
    (is (= 2 (count wrappers)) "one :details wrapper per file, as direct document children")
    (doseq [[idx w] (map-indexed vector wrappers)]
      (let [m      (node/node-meta w)
            banner (first (node/children w))
            bm     (node/node-meta banner)]
        (is (= "details" (:tag m)))
        (is (= (str "vv-diff-file-" idx) (:id m)) "the wrapper carries the file id as meta (the TUI anchor)")
        (is (nil? (get (:attrs m) "id")) "…but NEVER as a DOM attribute (the summary owns getElementById)")
        (is (true? (get (:attrs m) "open")) "files render expanded by default")
        (is (= :heading (node/kind banner)) "the banner is the wrapper's FIRST child")
        (is (= "summary" (:tag bm)))
        (is (= (str "vv-diff-file-" idx) (get (:attrs bm) "id")) "the summary carries the canonical DOM id"))))
  ;; every body shape keeps the banner-first invariant (binary / rename / empty files included)
  (let [ir (ir-diff/diff->ir (str git-binary git-rename))]
    (doseq [w (filter #(= :details (node/kind %)) (node/children ir))]
      (is (= :heading (node/kind (first (node/children w))))))))

(deftest unified-ir->ansi
  (let [out (ansi/render (ir-diff/diff->ir git-modify) {:width 80 :color? true})]
    ;; insert → green (SGR 32), delete → red (SGR 31), hunk header → cyan (SGR 36)
    (is (str/includes? out "[32m"))
    (is (str/includes? out "[31m"))
    (is (str/includes? out "[1;36m"))
    ;; the +/- markers ride in the text; the data-* gutters are NOT text, so no bare line numbers leak in
    (is (str/includes? out "+line 2"))
    (is (str/includes? out "-line two"))))

;; (unified-ir->ansi above runs at the DEFAULT "\n\n" block-sep — its SGR/marker assertions are
;;  separator-independent; the byte-level contract at diff's PRODUCTION separator "\n" is pinned below)

(deftest unified-ansi-golden
  ;; BYTE-parity with the pre-ADR-0037 flat structure at diff's production block separator "\n"
  ;; (cli/render + the TUI render diffs that way): the per-file wrapper recurses in the ANSI backend, and
  ;; within-block joins equal the former between-block separators. Captured from the pre-change build.
  (let [opts {:block-sep "\n" :color? false :width 80}]
    (is (= "modified src/foo.txt\n@@ -1 +1 @@\nline one\n-line two\n+line 2\nline three"
           (ansi/render (ir-diff/diff->ir git-modify) opts)))
    (is (= (str "modified src/foo.txt\n@@ -1 +1 @@\nline one\n-line two\n+line 2\nline three\n"
                "added new.txt\n@@ -0 +1 @@\n+hello\n+world")
           (ansi/render (ir-diff/diff->ir (str git-modify git-new)) opts)))))

(deftest unified-ansi-anchors
  ;; The TUI Contents-jump contract: render-lines anchors each file id (the wrapper's META :id — top-level
  ;; blocks are the wrappers now) to the banner's 0-based line index. Captured from the pre-change build.
  (let [{:keys [anchors]} (ansi/render-lines (ir-diff/diff->ir (str git-modify git-new))
                                             {:block-sep "\n" :color? false :width 80})]
    (is (= {"vv-diff-file-0" 0 "vv-diff-file-1" 6} anchors))))

(deftest unified-outline
  (let [ir (ir-diff/diff->ir (str git-modify git-new))
        toc (ir-diff/outline ir)]
    ;; exercises the PREORDER scan — the banners live inside the per-file wrappers now (ADR-0037)
    (is (= 2 (count toc)))
    (is (= ["src/foo.txt" "new.txt"] (map :text toc)))
    (is (= ["vv-diff-file-0" "vv-diff-file-1"] (map :id toc)))))
