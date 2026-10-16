(ns vinary.app.content-guard-test
  "The renderer's ordering guard (ADR-0046): a read not newer than the one a document applied is dropped; payloads
   without a read number, and documents that applied none, always pass. Exercised against in-memory DataScript
   values — never the global conn."
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [vinary.app.content-guard :as guard]
            [vinary.app.ds :as ds]
            [vinary.app.events :as events]))

(deftest stale-reads
  (is (true? (guard/stale? 4 5)) "an older read")
  (is (true? (guard/stale? 5 5)) "the same read again")
  (is (false? (guard/stale? 6 5)) "a newer read")
  (testing "no number on either side → never stale"
    (is (false? (guard/stale? nil 5)) "a payload main did not number (a web-linked PDF, a renderer error)")
    (is (false? (guard/stale? 5 nil)) "a document that applied none yet (just opened, or evicted and reopened)")
    (is (false? (guard/stale? nil nil)))))

(defn- db-with [& docs] (d/db-with (d/empty-db ds/schema) (vec docs)))

(deftest the-document-remembers-its-read
  (let [snap (db-with {:doc/path "/d/notes.md" :doc/kind "markdown" :doc/stamp 9 :doc/seq 7}
                      {:doc/path "/d/plain.md" :doc/kind "markdown" :doc/stamp 9})]
    (is (true? (events/read-stale? snap "/d/notes.md" 6)))
    (is (true? (events/read-stale? snap "/d/notes.md" 7)))
    (is (false? (events/read-stale? snap "/d/notes.md" 8)))
    (is (false? (events/read-stale? snap "/d/plain.md" 1)) "no :doc/seq yet")
    (is (false? (events/read-stale? snap "/d/missing.md" 1)) "no document")
    (is (false? (events/read-stale? snap nil 1)) "no path (a renderer-side error without one)")))

(deftest a-failed-read-records-its-number
  (let [snap (db-with {:doc/path "/d/notes.md" :doc/kind "markdown" :doc/stamp 9})
        eid  (ds/eid-for-path snap "/d/notes.md")]
    (is (= [[:db/add eid :doc/error "gone"] [:db/add eid :doc/stamp 10] [:db/add eid :doc/seq 8]]
           (events/content-error-tx snap "/d/notes.md" "gone" 10 8)))
    (is (= [[:db/add eid :doc/error "gone"] [:db/add eid :doc/stamp 10]]
           (events/content-error-tx snap "/d/notes.md" "gone" 10))
        "a renderer-side error carries no number")
    (is (= [{:doc/path "/d/new.md" :doc/kind "text" :doc/error "gone" :doc/stamp 10 :doc/seq 3}]
           (events/content-error-tx snap "/d/new.md" "gone" 10 3))
        "a first read that failed creates the document with its number")))
