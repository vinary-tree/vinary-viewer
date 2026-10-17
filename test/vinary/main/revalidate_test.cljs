(ns vinary.main.revalidate-test
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.main.revalidate :as rv]))

(def have {:size 1048576 :mtime 1760659200000})

(deftest the-same-size-and-time-is-the-same-version
  (is (rv/same-version? have {:size 1048576 :mtime 1760659200000}))
  (is (rv/same-version? have (assoc have :isFile true)) "extra stat fields do not matter"))

(deftest any-difference-is-a-new-version
  (is (not (rv/same-version? have (assoc have :size 1048577))) "a different size")
  (is (not (rv/same-version? have (assoc have :mtime 1760659201000))) "a later modification time")
  (is (not (rv/same-version? have (assoc have :mtime 1760659199000))) "an earlier one (a restored file) too"))

(deftest a-missing-or-malformed-version-is-never-the-same
  (testing "nothing to compare against: read the file"
    (is (not (rv/same-version? nil have)))
    (is (not (rv/same-version? {} have)))
    (is (not (rv/same-version? {:size 1048576} have)) "no mtime")
    (is (not (rv/same-version? {:mtime 1760659200000} have)) "no size"))
  (testing "a stat without the fields"
    (is (not (rv/same-version? have {})))
    (is (not (rv/same-version? have nil))))
  (testing "strings are not numbers (a payload that went through JSON oddly)"
    (is (not (rv/same-version? {:size "1048576" :mtime "1760659200000"} {:size "1048576" :mtime "1760659200000"})))))
