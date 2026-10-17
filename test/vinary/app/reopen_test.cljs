(ns vinary.app.reopen-test
  "Back/Forward re-opens (ADR-0046 §Re-validation): a REMOTE document the window still holds re-opens conditionally,
   carrying the version it shows; everything else reloads as before. Exercised against in-memory DataScript values —
   never the global conn."
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [vinary.app.ds :as ds]
            [vinary.app.events :as events]))

(defn- db-with [& docs] (d/db-with (d/empty-db ds/schema) (vec docs)))

(def remote "sftp://host/home/me/talk.pdf")
(def meta-v {:size 2097152 :mtime 1760659200000})

(deftest a-remote-document-the-window-holds-re-opens-conditionally
  (is (= [[:vv/open {:path remote :if-unchanged meta-v}]]
         (events/reopen-fx remote (db-with {:doc/path remote :doc/kind "pdf" :doc/meta meta-v})))
      "the delivered version rides along")
  (is (= [[:vv/open {:path remote :if-unchanged meta-v}]]
         (events/reopen-fx remote (db-with {:doc/path remote :doc/kind "pdf" :doc/meta (assoc meta-v :pageSize 500)})))
      "only size and mtime are sent")
  (let [ssh "ssh://me@host/notes.md"]
    (is (= [[:vv/open {:path ssh :if-unchanged meta-v}]]
           (events/reopen-fx ssh (db-with {:doc/path ssh :doc/kind "markdown" :doc/meta meta-v}))))))

(deftest otherwise-the-document-reloads
  (testing "a remote document with nothing (or nothing usable) to revalidate against"
    (is (= [[:vv/open remote]] (events/reopen-fx remote (db-with))) "not held (evicted, or never loaded)")
    (is (= [[:vv/open remote]] (events/reopen-fx remote (db-with {:doc/path remote :doc/kind "pdf"}))) "no :doc/meta")
    (is (= [[:vv/open remote]] (events/reopen-fx remote (db-with {:doc/path remote :doc/meta {:size 3}}))) "no mtime")
    (is (= [[:vv/open remote]]
           (events/reopen-fx remote (db-with {:doc/path remote :doc/meta meta-v :doc/error "No such file"})))
        "an error page shows nothing to keep: read it"))
  (testing "a local file always reloads (a disk read is cheap, and a watcher keeps it current)"
    (is (= [[:vv/open "/home/me/talk.pdf"]]
           (events/reopen-fx "/home/me/talk.pdf" (db-with {:doc/path "/home/me/talk.pdf" :doc/meta meta-v})))))
  (testing "a web page loads nothing here (the web view shows it)"
    (is (= [] (events/reopen-fx "https://example.com/x" (db-with))))))
