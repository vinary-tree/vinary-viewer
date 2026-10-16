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

(deftest deck-summary-transactions
  (let [summary {:deck? true :tier :definite :source :beamer :pages 12 :frame-count 7}
        snap    (db-with {:doc/path "/d/talk.pdf" :doc/kind "pdf"})
        eid     (ds/eid-for-path snap "/d/talk.pdf")]
    (testing "a new classification is written onto the doc entity"
      (is (= [[:db/add eid :doc/deck summary]] (events/deck-tx snap "/d/talk.pdf" summary))))
    (testing "an unchanged classification (a refresh of the same deck) writes nothing"
      (let [stored (d/db-with snap [[:db/add eid :doc/deck summary]])]
        (is (= [] (events/deck-tx stored "/d/talk.pdf" summary)))
        (is (= [[:db/add eid :doc/deck (assoc summary :pages 13)]]
               (events/deck-tx stored "/d/talk.pdf" (assoc summary :pages 13))))))
    (testing "no entity, or no summary, → no transaction"
      (is (= [] (events/deck-tx snap "/d/missing.pdf" summary)))
      (is (= [] (events/deck-tx snap "/d/talk.pdf" nil))))))

(deftest a-failed-read-keeps-what-is-shown
  (let [snap (db-with {:doc/path "/d/notes.md" :doc/kind "markdown" :doc/stamp 9 :doc/seq 4}
                      {:doc/path "/d/broken.md" :doc/kind "text" :doc/error "ENOENT" :doc/stamp 3})
        eid  (ds/eid-for-path snap "/d/notes.md")]
    (testing "a document with content keeps it; an error page or no document has nothing to keep"
      (is (true? (events/keeps-last-good? snap "/d/notes.md")))
      (is (false? (events/keeps-last-good? snap "/d/broken.md")) "already showing an error")
      (is (false? (events/keeps-last-good? snap "/d/missing.md")) "never loaded")
      (is (false? (events/keeps-last-good? snap nil))))
    (testing "the failure becomes a refresh note + the applied read number — never :doc/error or :doc/stamp"
      (is (= [[:db/add eid :doc/refresh-note {:kind "refresh-failed" :message "ENOENT: no such file" :seq 5 :at 77}]
              [:db/add eid :doc/seq 5]]
             (events/refresh-failed-tx snap "/d/notes.md" "ENOENT: no such file" 5 77)))
      (is (= "Unknown content error" (-> (events/refresh-failed-tx snap "/d/notes.md" nil 5 77) first last :message))
          "a failure without a message still says something"))))

(deftest incomplete-pdfs
  (testing "main's reasons in words"
    (doseq [[code words] [["no-eof" "it has no end-of-file marker yet"]
                          ["no-startxref" "its cross-reference pointer is missing"]
                          ["bad-startxref" "its cross-reference pointer points outside the file"]
                          ["bad-xref" "its cross-reference pointer does not point at a cross-reference section"]
                          ["no-header" "it does not start like a PDF"]
                          ["no-data" "it is empty"]
                          ["changing" "it kept changing while it was read"]]]
      (is (= words (events/incomplete-message code)) code))
    (is (= "unfinished (mystery)" (events/incomplete-message "mystery")) "an unknown reason is still shown"))
  (testing "the note"
    (is (= {:kind "incomplete" :message "The PDF on disk is incomplete: it has no end-of-file marker yet."
            :seq 9 :at 77 :partial? false}
           (events/incomplete-note "no-eof" 9 77 false))))
  (let [snap (db-with {:doc/path "/d/talk.pdf" :doc/kind "pdf" :doc/stamp 7 :doc/seq 8})
        eid  (ds/eid-for-path snap "/d/talk.pdf")]
    (testing "a document this window shows keeps it: the note and the read number — no stamp, no byte cache"
      (is (= [[:db/add eid :doc/refresh-note (events/incomplete-note "no-eof" 9 77 false)]
              [:db/add eid :doc/seq 9]]
             (events/incomplete-kept-tx snap "/d/talk.pdf" "no-eof" 9 77)))))
  (testing "a PDF pdf.js could not open: main's 'incomplete' word when it said so, else pdf.js's reason"
    (is (= "The PDF on disk is incomplete: it has no end-of-file marker yet. It will appear once the file is finished."
           (events/open-failed-message (events/incomplete-note "no-eof" 9 77 true) "Invalid PDF structure.")))
    (is (= "This PDF could not be opened: Invalid PDF structure."
           (events/open-failed-message nil "Invalid PDF structure.")))
    (is (= "This PDF could not be opened: Invalid PDF structure."
           (events/open-failed-message {:kind "render-failed" :message "x"} "Invalid PDF structure.")))))
