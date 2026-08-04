(ns vinary.main.doc-overrides-test
  "Unit tests for the main-side per-document type-override registry (ADR-0036): CLI/menu writes, the
   effective-kind veto over classification, stdin virtual-source facts, and retention-driven cleanup."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [vinary.main.doc-overrides :as ov]))

(use-fixtures :each {:before ov/reset-all! :after ov/reset-all!})

(def ^:private classify-text (constantly "text"))

(deftest register-and-effective-kind
  (testing "an explicit kind beats classification on every consult; absent → the classifier answers"
    (ov/register! "/a.txt" {:kind "diff"})
    (is (= "diff" (ov/effective-kind classify-text "/a.txt")))
    (is (= "text" (ov/effective-kind classify-text "/other.txt"))))
  (testing "language + delimiter ride the same entry"
    (ov/register! "/x" {:kind "source" :language "python"})
    (is (= {:kind "source" :language "python"} (ov/for-path "/x")))
    (ov/register! "/d" {:kind "table" :delimiter "\t"})
    (is (= "\t" (:delimiter (ov/for-path "/d"))))))

(deftest register-ignores-empty
  (testing "bare specs (no explicit type, not stdin) register nothing"
    (ov/register! "/plain.md" {})
    (ov/register! "/plain.md" nil)
    (ov/register! "/plain.md" {:kind nil :language nil})
    (is (nil? (ov/for-path "/plain.md")))))

(deftest stdin-facts
  (testing "stdin-doc? and stdin-base-dir come from the same entry as the type"
    (ov/register! "/run/u/stdin/id/stdin" {:kind "diff" :stdin? true :cwd "/work/repo"})
    (is (ov/stdin-doc? "/run/u/stdin/id/stdin"))
    (is (= "/work/repo" (ov/stdin-base-dir "/run/u/stdin/id/stdin")))
    (is (not (ov/stdin-doc? "/elsewhere")))
    (is (nil? (ov/stdin-base-dir "/elsewhere"))))
  (testing "a non-stdin entry never reports a base dir, even with a stray :cwd"
    (ov/register! "/f" {:kind "diff" :cwd "/nope"})
    (is (nil? (ov/stdin-base-dir "/f")))))

(deftest set-type-replaces-as-a-unit
  (testing "a menu re-type replaces kind/language/delimiter together — a kind pick clears a language pick"
    (ov/register! "/doc" {:kind "source" :language "python" :stdin? true :cwd "/w"})
    (ov/set-type! "/doc" "diff" nil)
    (is (= {:kind "diff" :stdin? true :cwd "/w"} (ov/for-path "/doc"))
        "language cleared, stdin facts survive")
    (ov/set-type! "/doc" "source" "rust")
    (is (= {:kind "source" :language "rust" :stdin? true :cwd "/w"} (ov/for-path "/doc"))))
  (testing "set-type! on a path with no prior entry creates one"
    (ov/set-type! "/fresh.txt" "markdown" nil)
    (is (= {:kind "markdown"} (ov/for-path "/fresh.txt")))))

(deftest clear-drops-everything
  (ov/register! "/gone" {:kind "diff" :stdin? true :cwd "/w"})
  (ov/clear! "/gone")
  (is (nil? (ov/for-path "/gone")))
  (is (not (ov/stdin-doc? "/gone")))
  (is (= "text" (ov/effective-kind classify-text "/gone"))))
