(ns vinary.app.projects-test
  "Pure unit tests for the Files-tab project list (vinary.app.projects) — the containment arithmetic that
   decides when a root arriving from main joins the sidebar, refreshes an entry already there, or is
   swallowed by (or swallows) a root that overlaps it. A GIT root is authoritative and always survives; a
   SYNTHETIC root (the containing directory of a file in no repository) yields to containment, which is
   what keeps browsing several files under one directory from stacking up overlapping trees."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.projects :as projects]))

(defn- git  [root & files] {:root root :files (vec files) :synthetic? false})
(defn- syn  [root & files] {:root root :files (vec files) :synthetic? true})
(defn- roots [projects] (mapv :root projects))

(deftest under?-compares-on-segment-boundaries
  (testing "a path is under itself and under any ancestor directory"
    (is (true? (projects/under? "/a/b"     "/a/b")))
    (is (true? (projects/under? "/a/b/c"   "/a/b")))
    (is (true? (projects/under? "/a/b/c/d" "/a"))))
  (testing "a shared string prefix that is not a path prefix does NOT count"
    ;; the whole reason under? exists rather than a bare starts-with?
    (is (false? (projects/under? "/a/bc"    "/a/b")))
    (is (false? (projects/under? "/a/b-x/y" "/a/b")))
    (is (false? (projects/under? "/ab"      "/a"))))
  (testing "a parent is not under its own child, and nil never matches"
    (is (false? (projects/under? "/a"   "/a/b")))
    (is (false? (projects/under? nil    "/a")))
    (is (false? (projects/under? "/a"   nil)))))

(deftest merge-project-adds-the-first-root
  (testing "an empty list takes the entry as-is, normalized"
    (is (= [{:root "/r" :files ["a.md"] :synthetic? false}]
           (projects/merge-project [] (git "/r" "a.md")))))
  (testing "missing :files and :synthetic? normalize to [] and false"
    (is (= [{:root "/r" :files [] :synthetic? false}]
           (projects/merge-project [] {:root "/r"})))))

(deftest merge-project-refreshes-a-known-root-in-place
  (testing "the same root replaces its entry WITHOUT reordering the sidebar"
    ;; re-opening a file from the second project must not jump it to the end
    (let [before [(git "/one" "a.md") (git "/two" "b.md") (git "/three" "c.md")]
          after  (projects/merge-project before (git "/two" "b.md" "b2.md"))]
      (is (= ["/one" "/two" "/three"] (roots after)))
      (is (= ["b.md" "b2.md"] (:files (second after))))))
  (testing "in-place replacement applies to synthetic roots too"
    (let [after (projects/merge-project [(syn "/notes" "a.md")] (syn "/notes" "a.md" "b.md"))]
      (is (= 1 (count after)))
      (is (= ["a.md" "b.md"] (:files (first after)))))))

(deftest merge-project-refreshes-rather-than-duplicating-a-covered-synthetic-root
  (testing "a synthetic root beneath a known synthetic root adds no second tree"
    (let [after (projects/merge-project [(syn "/notes" "sub/c.md")] (syn "/notes/sub" "c.md"))]
      (is (= ["/notes"] (roots after)))))
  (testing "its freshly walked subtree is MERGED in, so a file created after the walk still appears"
    ;; the invariant `git ls-files --others` buys for repositories: the file you just opened is in the
    ;; tree. A covered synthetic root must not be staler than that.
    (let [before [(syn "/notes" "a.md" "sub/c.md")]
          after  (projects/merge-project before (syn "/notes/sub" "c.md" "brand-new.md"))]
      (is (= ["a.md" "sub/c.md" "sub/brand-new.md"] (:files (first after))))))
  (testing "the merge REPLACES the subtree — a file deleted under it disappears"
    (let [before [(syn "/notes" "a.md" "sub/gone.md" "sub/c.md")]
          after  (projects/merge-project before (syn "/notes/sub" "c.md"))]
      (is (= ["a.md" "sub/c.md"] (:files (first after))))))
  (testing "paths outside the merged subtree are left untouched"
    (let [before [(syn "/notes" "other/keep.md" "sub/c.md")]
          after  (projects/merge-project before (syn "/notes/sub" "c.md"))]
      (is (= ["other/keep.md" "sub/c.md"] (:files (first after))))))
  (testing "a deeply nested subtree re-bases through every level"
    (let [before [(syn "/notes" "x.md")]
          after  (projects/merge-project before (syn "/notes/a/b" "c.md"))]
      (is (= ["x.md" "a/b/c.md"] (:files (first after))))))
  (testing "coverage by a GIT root drops the synthetic root outright — git re-lists on its own opens"
    (let [before [(git "/repo" "sub/c.md")]]
      (is (= before (projects/merge-project before (syn "/repo/sub" "c.md"))))))
  (testing "a merely-similar prefix is not coverage"
    (let [before [(syn "/notes" "a.md")]
          after  (projects/merge-project before (syn "/notes-archive" "b.md"))]
      (is (= ["/notes" "/notes-archive"] (roots after))))))

(deftest merge-project-lets-a-broader-synthetic-root-absorb-narrower-ones
  (testing "the broader view wins: /notes/sub then /notes leaves ONE tree, not two overlapping"
    (let [before [(syn "/notes/sub" "c.md")]
          after  (projects/merge-project before (syn "/notes" "sub/c.md" "a.md"))]
      (is (= ["/notes"] (roots after)))))
  (testing "absorption takes every nested synthetic root, and leaves unrelated ones alone"
    (let [before [(syn "/notes/x" "1.md") (syn "/other" "2.md") (syn "/notes/y" "3.md")]
          after  (projects/merge-project before (syn "/notes" "x/1.md" "y/3.md"))]
      (is (= ["/other" "/notes"] (roots after))))))

(deftest merge-project-never-absorbs-a-git-root
  (testing "a git repo nested inside a browsed directory stays its own project"
    ;; the asymmetry that makes containment safe: synthetic roots are inferences, git roots are facts
    (let [before [(git "/notes/repo" "a.md")]
          after  (projects/merge-project before (syn "/notes" "repo/a.md"))]
      (is (= ["/notes/repo" "/notes"] (roots after)))))
  (testing "a git root is appended even when a synthetic root already covers it"
    (let [before [(syn "/notes" "repo/a.md")]
          after  (projects/merge-project before (git "/notes/repo" "a.md"))]
      (is (= ["/notes" "/notes/repo"] (roots after))))))

(deftest remove-project-drops-exactly-one-root
  (testing "only the named root leaves; the rest keep their order"
    (let [before [(git "/one") (syn "/two") (git "/three")]]
      (is (= ["/one" "/three"] (roots (projects/remove-project before "/two"))))))
  (testing "removing an unknown root is a no-op"
    (let [before [(git "/one")]]
      (is (= ["/one"] (roots (projects/remove-project before "/nope"))))))
  (testing "an empty list stays empty"
    (is (= [] (projects/remove-project [] "/one")))))
