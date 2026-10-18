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
      (is (= {:bytes :v1 :stamp 1 :sidecar nil} (cache/bytes-for p)))
      (is (nil? (cache/good-version p)))
      (cache/promote! p 1)
      (is (= {:bytes :v1 :stamp 1 :sidecar nil} (cache/good-version p)))
      (is (= :v1 (cache/get-bytes p))))
    (testing "a newer candidate is tried first; rejecting it falls back to the good version"
      (cache/put-bytes! p :torn 2)
      (is (= {:bytes :torn :stamp 2 :sidecar nil} (cache/bytes-for p)))
      (is (true? (cache/reject! p 2)) "a good version remains")
      (is (= {:bytes :v1 :stamp 1 :sidecar nil} (cache/bytes-for p))))
    (testing "a stale promote/reject (a newer candidate arrived meanwhile) is ignored"
      (cache/put-bytes! p :v3 3)
      (cache/promote! p 2)
      (is (= 1 (:stamp (cache/good-version p))))
      (cache/reject! p 2)
      (is (= 3 (:stamp (cache/bytes-for p))) "the newer candidate survives a stale reject")
      (cache/promote! p 3)
      (is (= {:bytes :v3 :stamp 3 :sidecar nil} (cache/good-version p))))
    (testing "eviction drops the whole entry"
      (cache/evict-keep! ["/other"])
      (is (nil? (cache/bytes-for p))))
    (testing "rejecting with no good version reports that nothing remains"
      (cache/put-bytes! p :bad 9)
      (is (false? (cache/reject! p 9)))
      (is (nil? (cache/bytes-for p))))))

(deftest the-sidecar-is-part-of-its-version
  ;; ADR-0048 N7: the pdfpc sidecar main read beside the PDF travels WITH the bytes it was read with — a torn candidate
  ;; that is rejected takes its sidecar with it, and the good version keeps the one it rendered with
  (let [p  "/tmp/talk.pdf"
        s1 {:path "/tmp/talk.pdfpc" :text "{\"pdfpcFormat\": 2}"}
        s2 {:path "/tmp/talk.pdfpc" :size 5000000 :tooLarge true}]
    (cache/evict-keep! [])
    (cache/put-bytes! p :v1 1 s1)
    (is (= s1 (:sidecar (cache/bytes-for p))) "the candidate carries its sidecar")
    (cache/promote! p 1)
    (is (= {:bytes :v1 :stamp 1 :sidecar s1} (cache/good-version p)) "and keeps it once it is the good version")
    (cache/put-bytes! p :v2 2 s2)
    (is (= s2 (:sidecar (cache/bytes-for p))) "a newer read's sidecar comes with the newer candidate")
    (cache/reject! p 2)
    (is (= s1 (:sidecar (cache/bytes-for p))) "rejecting the candidate falls back to the good version's own sidecar")
    (cache/put-bytes! p :v3 3)
    (is (nil? (:sidecar (cache/bytes-for p))) "a read without a sidecar has none — the old one does not linger")
    (cache/evict-keep! [])))
