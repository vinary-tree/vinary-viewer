(ns vinary.app.refresh-note-test
  "The non-fatal live-refresh note's transactions (ADR-0045 / ADR-0046), exercised against an in-memory
   DataScript value — never the global conn."
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [vinary.app.ds :as ds]
            [vinary.app.events :as events]))

(defn- db-with [& docs] (d/db-with (d/empty-db ds/schema) (vec docs)))

(deftest refresh-note-transactions
  (let [note {:kind "render-failed" :message "Invalid PDF structure." :stamp 7 :at 1}
        snap (db-with {:doc/path "/d/talk.pdf" :doc/kind "pdf" :doc/stamp 7})
        eid  (ds/eid-for-path snap "/d/talk.pdf")]
    (testing "setting a note targets the doc entity by id; an unknown path yields no transaction"
      (is (= [[:db/add eid :doc/refresh-note note]] (events/refresh-note-tx snap "/d/talk.pdf" note)))
      (is (= [] (events/refresh-note-tx snap "/d/missing.pdf" note))))
    (testing "clearing retracts the exact stored value, and is a no-op without one"
      (let [noted (d/db-with snap [[:db/add eid :doc/refresh-note note]])]
        (is (= [[:db/retract eid :doc/refresh-note note]] (events/clear-refresh-note-tx noted "/d/talk.pdf")))
        (is (nil? (ds/doc-attr (d/db-with noted (events/clear-refresh-note-tx noted "/d/talk.pdf"))
                               "/d/talk.pdf" :doc/refresh-note))
            "after the retraction the attribute is absent (DataScript nil-as-absence)"))
      (is (= [] (events/clear-refresh-note-tx snap "/d/talk.pdf")))
      (is (= [] (events/clear-refresh-note-tx snap "/d/missing.pdf"))))))
