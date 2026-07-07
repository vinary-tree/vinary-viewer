(ns vinary.stream.flag-test
  "Tests for the document-streaming gate (vinary.stream.flag): streaming engages only when the flag is on AND
   the kind is streamable AND the size clears the per-kind threshold — so small docs always take the faster,
   byte-identical batch path."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.stream.flag :as flag]))

(deftest flag-on-override
  (testing "the runtime setting overrides the compile-time default; nil falls back to the default (off)"
    (is (false? (flag/flag-on? nil)))
    (is (true?  (flag/flag-on? true)))
    (is (false? (flag/flag-on? false)))))

(deftest streamable-kinds
  (is (flag/streamable? "log"))
  (is (flag/streamable? "markdown"))
  (is (flag/streamable? "pdf"))
  (is (not (flag/streamable? "image")))
  (is (not (flag/streamable? "office"))))

(deftest enabled-requires-flag-kind-and-size
  (testing "off unless flag on AND streamable AND size ≥ threshold"
    (is (not (flag/enabled? "log" 999999999 nil)) "flag off by default")
    (is (not (flag/enabled? "office" 999999999 true)) "non-streamable kind never streams")
    (is (not (flag/enabled? "log" 1024 true)) "a small log stays batch (below the 5 MiB threshold)")
    (is (flag/enabled? "log" 6000000 true) "a large log streams")
    (is (flag/enabled? "markdown" 300000 true) "a large markdown streams (Phase 2 — progressive block-commit)")
    (is (not (flag/enabled? "markdown" 1024 true)) "a small markdown stays batch (below the 256 KiB threshold)")
    (is (not (flag/enabled? "pdf" 0 true)) "pdf not yet implemented (Phase 3) → batch")
    (is (flag/implemented? "log"))
    (is (flag/implemented? "markdown"))
    (is (not (flag/implemented? "pdf")))))
