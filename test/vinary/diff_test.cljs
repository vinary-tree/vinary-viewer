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
            [vinary.ir.backend.ansi :as ansi]))

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
    (is (str/includes? html "line five"))))

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
    (is (not (ir-html/blank? html)))))

(deftest unified-ir->ansi
  (let [out (ansi/render (ir-diff/diff->ir git-modify) {:width 80 :color? true})]
    ;; insert → green (SGR 32), delete → red (SGR 31), hunk header → cyan (SGR 36)
    (is (str/includes? out "[32m"))
    (is (str/includes? out "[31m"))
    (is (str/includes? out "[1;36m"))
    ;; the +/- markers ride in the text; the data-* gutters are NOT text, so no bare line numbers leak in
    (is (str/includes? out "+line 2"))
    (is (str/includes? out "-line two"))))

(deftest unified-outline
  (let [ir (ir-diff/diff->ir (str git-modify git-new))
        toc (ir-diff/outline ir)]
    (is (= 2 (count toc)))
    (is (= ["src/foo.txt" "new.txt"] (map :text toc)))
    (is (= ["vv-diff-file-0" "vv-diff-file-1"] (map :id toc)))))
