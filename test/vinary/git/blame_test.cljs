(ns vinary.git.blame-test
  "The --line-porcelain parsing contract (ADR-0040): coalescing arithmetic, metadata caching across
   groups, the uncommitted zero-hash, boundary commits, CRLF and unknown-key tolerance, the
   binary-search line lookup, and the gutter's relative-date buckets."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.git.blame :as blame]))

(def ^:private HA "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private HB "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- porcelain [& lines] (str/join "\n" lines))

(deftest coalescing-and-metadata
  (let [text (porcelain
              (str HA " 1 1 2")
              "author Ada"
              "author-mail <ada@x.io>"
              "author-time 1700000000"
              "author-tz +0100"
              "committer Bob"                        ; committer fields are ignored in v1
              "summary feat: first"
              "filename src/a.cljs"
              "\tline one"
              (str HA " 2 2")
              "\tline two"
              (str HB " 9 3 1")
              "author Bob"
              "author-mail <bob@x.io>"
              "author-time 1700000500"
              "summary fix: second"
              "filename src/a.cljs"
              "\tline three"
              (str HA " 3 4")                        ; HA again — metadata comes from the cache
              "\tline four")
        {:keys [hunks]} (blame/parse-line-porcelain text)]
    (testing "consecutive same-hash lines coalesce; a different hash or a gap breaks the run"
      (is (= 3 (count hunks)))
      (is (= [{:hash HA :final-line 1 :count 2}
              {:hash HB :final-line 3 :count 1}
              {:hash HA :final-line 4 :count 1}]
             (mapv #(select-keys % [:hash :final-line :count]) hunks))))
    (testing "metadata parses and is cached across groups (the second HA hunk carries it too)"
      (let [[h1 _ h3] hunks]
        (is (= "Ada" (:author-name h1)))
        (is (= "ada@x.io" (:author-email h1)) "angle brackets stripped")
        (is (= 1700000000000 (:author-date h1)) "seconds → epoch ms")
        (is (= "feat: first" (:summary h1)))
        (is (= "Ada" (:author-name h3)) "group-continuation lines fill from the per-hash cache")))
    (testing "orig-line coalescing is tracked independently of final-line"
      (is (= 1 (:orig-line (first hunks)))))))

(deftest uncommitted-boundary-and-tolerance
  (testing "the zero hash marks an uncommitted line"
    (let [{:keys [hunks]} (blame/parse-line-porcelain
                           (porcelain (str blame/zero-hash " 1 1 1")
                                      "author Not Committed Yet"
                                      "\tdirty"))]
      (is (true? (:uncommitted (first hunks))))))
  (testing "a bare `boundary` token flags the hunk"
    (let [{:keys [hunks]} (blame/parse-line-porcelain
                           (porcelain (str HA " 1 1 1") "author A" "boundary" "\tx"))]
      (is (true? (:boundary (first hunks))))))
  (testing "CRLF input and unknown metadata keys parse identically"
    (let [{:keys [hunks]} (blame/parse-line-porcelain
                           (str HA " 1 1 1\r\n"
                                "author Ada\r\n"
                                "previous " HB " src/old.cljs\r\n"
                                "some-future-key with a value\r\n"
                                "\tcontent\r\n"))]
      (is (= 1 (count hunks)))
      (is (= "Ada" (:author-name (first hunks))))))
  (testing "empty input parses to zero hunks"
    (is (= {:hunks []} (blame/parse-line-porcelain "")))))

(deftest line-lookup
  (let [{:keys [hunks]} (blame/parse-line-porcelain
                         (porcelain (str HA " 1 1 3") "author A" "\ta"
                                    (str HA " 2 2") "\tb"
                                    (str HA " 3 3") "\tc"
                                    (str HB " 1 4 2") "author B" "\td"
                                    (str HB " 2 5") "\te"))]
    (testing "every covered line resolves to its hunk"
      (is (= HA (:hash (blame/hunk-for-line hunks 1))))
      (is (= HA (:hash (blame/hunk-for-line hunks 3))) "last line of the first hunk")
      (is (= HB (:hash (blame/hunk-for-line hunks 4))) "first line of the second")
      (is (= HB (:hash (blame/hunk-for-line hunks 5)))))
    (testing "lines outside every hunk miss"
      (is (nil? (blame/hunk-for-line hunks 0)))
      (is (nil? (blame/hunk-for-line hunks 6)))
      (is (nil? (blame/hunk-for-line [] 1))))))

(deftest relative-date-buckets
  (let [now 1700000000000]
    (is (= "now" (blame/rel-date (- now 30000) now)))
    (is (= "5m"  (blame/rel-date (- now (* 5 60 1000)) now)))
    (is (= "3h"  (blame/rel-date (- now (* 3 3600 1000)) now)))
    (is (= "6d"  (blame/rel-date (- now (* 6 86400 1000)) now)))
    (is (= "2023-10-15" (blame/rel-date (- now (* 30 86400 1000)) now)) "a month out falls to the ISO date")
    (is (= "" (blame/rel-date nil now)) "missing dates render as nothing")))
