(ns vinary.renderer.pdf-cache-test
  "Keep-last-good PDF bytes (ADR-0045): arriving bytes are a CANDIDATE, promoted when pdf.js opens them and
   rejected — leaving the last good version — when it cannot."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.renderer.pdf-cache :as cache]))

(deftest candidate-promote-reject
  (let [p "/tmp/deck.pdf"]
    (cache/evict-keep! [])
    (testing "the first arrival is a candidate; promoting it makes it the good version"
      (cache/put-bytes! p :v1 1)
      (is (= {:bytes :v1 :stamp 1} (cache/bytes-for p)))
      (is (nil? (cache/good-version p)))
      (cache/promote! p 1)
      (is (= {:bytes :v1 :stamp 1} (cache/good-version p)))
      (is (= :v1 (cache/get-bytes p))))
    (testing "a newer candidate is tried first; rejecting it falls back to the good version"
      (cache/put-bytes! p :torn 2)
      (is (= {:bytes :torn :stamp 2} (cache/bytes-for p)))
      (is (true? (cache/reject! p 2)) "a good version remains")
      (is (= {:bytes :v1 :stamp 1} (cache/bytes-for p))))
    (testing "a stale promote/reject (a newer candidate arrived meanwhile) is ignored"
      (cache/put-bytes! p :v3 3)
      (cache/promote! p 2)
      (is (= 1 (:stamp (cache/good-version p))))
      (cache/reject! p 2)
      (is (= 3 (:stamp (cache/bytes-for p))) "the newer candidate survives a stale reject")
      (cache/promote! p 3)
      (is (= {:bytes :v3 :stamp 3} (cache/good-version p))))
    (testing "eviction drops the whole entry"
      (cache/evict-keep! ["/other"])
      (is (nil? (cache/bytes-for p))))
    (testing "rejecting with no good version reports that nothing remains"
      (cache/put-bytes! p :bad 9)
      (is (false? (cache/reject! p 9)))
      (is (nil? (cache/bytes-for p))))))
