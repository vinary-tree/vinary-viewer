(ns vinary.git.log-test
  "The git-log plumbing contract (ADR-0039): argv vectors are asserted VERBATIM — a drifted flag is
   a silent protocol break with main — and the %x1f/%x00 record discipline is exercised against
   hostile and edge-shaped input."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.git.log :as glog]))

(def ^:private SEP "\u001f")
(def ^:private TERM "\u0000")
(def ^:private H1 "1111111111111111111111111111111111111111")
(def ^:private H2 "2222222222222222222222222222222222222222")
(def ^:private H3 "3333333333333333333333333333333333333333")

(defn- rec
  "One well-formed 9-field record: hash parents an ae aI cI D s b, %x00-terminated."
  [& fields]
  (str (str/join SEP fields) TERM))

(deftest log-argv-verbatim
  (testing "the plain page argv, flag for flag"
    (is (= ["log" "--date-order" "--max-count=250" "--skip=0"
            (str "--pretty=" glog/pretty-format)
            "--end-of-options" "HEAD"]
           (glog/log-args {}))))
  (testing "ref/skip/limit thread through; --end-of-options ALWAYS precedes the ref"
    (let [args (glog/log-args {:ref "feature/x" :skip 500 :limit 100})]
      (is (= ["log" "--date-order" "--max-count=100" "--skip=500"
              (str "--pretty=" glog/pretty-format)
              "--end-of-options" "feature/x"]
             args))
      (is (= (inc (.indexOf args "--end-of-options")) (.indexOf args "feature/x")))))
  (testing "file history: --follow before --end-of-options, pathspec after --"
    (is (= ["log" "--date-order" "--max-count=250" "--skip=0"
            (str "--pretty=" glog/pretty-format)
            "--follow" "--end-of-options" "HEAD" "--" "/repo/src/a.cljs"]
           (glog/log-args {:file "/repo/src/a.cljs" :follow? true})))))

(deftest branches-and-verify-argv
  (is (= ["for-each-ref" "--format=%(refname)%1f%(refname:short)%1f%(HEAD)"
          "refs/heads" "refs/remotes" "refs/tags"]
         (glog/branches-args)))
  (is (= ["rev-parse" "--abbrev-ref" "HEAD"] (glog/head-args)))
  (testing "user refs are verified as commit-ish behind --end-of-options"
    (is (= ["rev-parse" "--verify" "--quiet" "--end-of-options" "HEAD~3^{commit}"]
           (glog/verify-args "HEAD~3")))))

(deftest open-diff-argv
  (testing "two-dot (plain pair) form"
    (is (= ["diff" "--no-color" "--no-ext-diff" "--end-of-options" H1 H2]
           (glog/open-diff-args {:from H1 :to H2}))))
  (testing "three-dot symmetric difference composes ONE validated token"
    (is (= ["diff" "--no-color" "--no-ext-diff" "--end-of-options" (str H1 "..." H2)]
           (glog/open-diff-args {:from H1 :to H2 :dots "..."}))))
  (testing "optional path scoping lands after --"
    (is (= ["diff" "--no-color" "--no-ext-diff" "--end-of-options" H1 H2 "--" "src/a.cljs"]
           (glog/open-diff-args {:from H1 :to H2 :paths ["src/a.cljs"]})))))

(deftest parse-log-records
  (testing "a linear commit parses field-for-field"
    (let [{:keys [commits count]}
          (glog/parse-log (rec H1 H2 "Ada" "ada@x.io"
                               "2026-08-11T10:00:00+02:00" "2026-08-11T10:05:00+02:00"
                               "HEAD -> main, tag: v1.0" "fix: subject" "body line 1\nbody line 2\n"))]
      (is (= 1 count))
      (is (= {:hash H1 :parents [H2] :author-name "Ada" :author-email "ada@x.io"
              :author-date "2026-08-11T10:00:00+02:00" :committer-date "2026-08-11T10:05:00+02:00"
              :refs ["HEAD -> main" "tag: v1.0"] :subject "fix: subject"
              :body "body line 1\nbody line 2"}
             (first commits)))))
  (testing "root, merge, and octopus parent lists"
    (let [text (str (rec H1 (str H2 " " H3) "a" "a@x" "d" "d" "" "merge" "")
                    (rec H2 "" "a" "a@x" "d" "d" "" "root" ""))
          {:keys [commits]} (glog/parse-log text)]
      (is (= [H2 H3] (:parents (first commits))))
      (is (= [] (:parents (second commits))))
      (is (= [] (:refs (first commits))) "an undecorated %D is an empty vector")
      (is (= "" (:body (second commits))) "an empty %b trailing field survives the split")))
  (testing "the newline git inserts between format records is absorbed"
    (let [text (str (rec H1 "" "a" "a@x" "d" "d" "" "one" "")
                    "\n" (rec H2 "" "a" "a@x" "d" "d" "" "two" ""))]
      (is (= [H1 H2] (mapv :hash (:commits (glog/parse-log text)))))))
  (testing "a hostile %x1f inside a message corrupts ONLY its own record (field count ≠ 9 → dropped)"
    (let [text (str (rec H1 "" "a" "a@x" "d" "d" "" (str "evil" SEP "subject") "")
                    (rec H2 "" "a" "a@x" "d" "d" "" "clean" ""))]
      (is (= [H2] (mapv :hash (:commits (glog/parse-log text)))))))
  (testing "a record whose first field is not 40-hex is dropped"
    (is (= [] (:commits (glog/parse-log (rec "not-a-hash" "" "a" "a@x" "d" "d" "" "s" ""))))))
  (testing "empty input parses to zero commits"
    (is (= {:commits [] :count 0} (glog/parse-log "")))))

(def ^:private RS "\u001e")

(defn- lrec
  "One 8-field line-history record: %x1e-opened, no body field."
  [& fields]
  (str RS (str/join SEP fields)))

(deftest line-log-argv-and-records
  (testing "the -L argv: no pathspec, no ref, --no-patch, single-shot bound"
    (is (= ["log" "-L10,42:src/a.cljs" "--no-patch" "-n" "500"
            (str "--pretty=" glog/line-log-format)]
           (glog/line-log-args {:rel "src/a.cljs" :start 10 :end 42 :limit 500}))))
  (testing "clean records (git ≥ 2.42) parse field-for-field with an empty body"
    (let [{:keys [commits count]}
          (glog/parse-line-log
           (str (lrec H1 H2 "Ada" "a@x" "2026-08-11T10:00:00+02:00" "2026-08-11T10:00:00+02:00"
                      "HEAD -> main" "touch lines")
                "\n" (lrec H2 "" "Bob" "b@x" "d" "d" "" "origin")))]
      (is (= 2 count))
      (is (= {:hash H1 :parents [H2] :subject "touch lines" :body ""
              :refs ["HEAD -> main"]}
             (select-keys (first commits) [:hash :parents :subject :body :refs])))))
  (testing "pre-2.42 patch bleed after the subject is discarded (the %x1e START marker's whole point)"
    (let [bleed (str "\ndiff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n")
          {:keys [commits]}
          (glog/parse-line-log
           (str (lrec H1 "" "Ada" "a@x" "d" "d" "" (str "subject" bleed))
                (lrec H2 "" "Bob" "b@x" "d" "d" "" "clean")))]
      (is (= ["subject" "clean"] (mapv :subject commits))
          "the subject truncates at its first newline; the bleed never leaks")))
  (testing "a stray %x1f inside bleed only widens the field count — the record survives"
    (let [{:keys [commits]}
          (glog/parse-line-log
           (lrec H1 "" "Ada" "a@x" "d" "d" "" (str "subject\npatch " SEP " garbage")))]
      (is (= ["subject"] (mapv :subject commits)))))
  (is (= {:commits [] :count 0} (glog/parse-line-log ""))))

(deftest parse-branches-records
  (let [text (str "refs/heads/main" SEP "main" SEP "*" "\n"
                  "refs/heads/dev" SEP "dev" SEP " " "\n"
                  "refs/remotes/origin/main" SEP "origin/main" SEP " " "\n"
                  "refs/tags/v1.0" SEP "v1.0" SEP " " "\n"
                  "refs/stash" SEP "stash" SEP " " "\n"          ; unrecognized namespace → dropped
                  "garbage-line")
        {:keys [head detached? branches]} (glog/parse-branches text "main" false)]
    (is (= "main" head))
    (is (false? detached?))
    (is (= [{:name "main" :kind "local" :current? true}
            {:name "dev" :kind "local" :current? false}
            {:name "origin/main" :kind "remote" :current? false}
            {:name "v1.0" :kind "tag" :current? false}]
           branches))))
