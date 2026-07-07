(ns vinary.ir.frontend.data-test
  "Unit tests for the data-format IR parsers (table/log/archive): each content-service envelope maps to a
   structurally-valid common-IR tree — :table/:row/:cell, per-line :line leaves, and a listing :list — the
   canonical parse every format yields under the common-IR design."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.table :as table]
            [vinary.ir.frontend.log :as log]
            [vinary.ir.frontend.archive :as archive]))

(deftest table-parse
  (testing "a page of rows → :document > :table > :row > :cell"
    (let [ir (table/payload->ir {:page {:rows [["a" "b"] ["c" "d"]]}})]
      (is (node/valid-tree? ir))
      (is (= :table (node/kind (first (node/children ir)))))
      (is (= [[:cell :cell] [:cell :cell]]
             (mapv (fn [row] (mapv node/kind (node/children row)))
                   (node/children (first (node/children ir))))))
      (is (= "abcd" (node/text-content ir)))))
  (testing "a multi-sheet workbook → per-sheet :sections headed by the sheet name"
    (let [ir (table/payload->ir {:sheets [{:name "Sheet1" :rows [["x"]]} {:name "Sheet2" :rows [["y"]]}]})]
      (is (= [:section :section] (mapv node/kind (node/children ir))))
      (let [s1 (first (node/children ir))]
        (is (= :heading (node/kind (first (node/children s1)))))
        (is (= "Sheet1" (node/text-content (first (node/children s1)))))))))

(deftest log-parse
  (testing "text and paged lines both → a :document of :line leaves"
    (is (node/valid-tree? (log/text->ir "a\nb\nc")))
    (is (= [:line :line :line] (mapv node/kind (node/children (log/text->ir "a\nb\nc")))))
    (is (= "a" (node/text (first (node/children (log/payload->ir {:page {:lines ["a" "b"]}}))))))
    (is (= "log line one" (node/text (first (node/children (log/text->ir "log line one\nsecond"))))))))

(deftest archive-parse
  (testing "entries → a :list of :list-items tagged directory/file with path metadata"
    (let [ir    (archive/payload->ir {:entries [{:name "src" :path "/a/src" :dir? true}
                                                {:name "f.txt" :path "/a/f.txt" :size 10}]})
          items (node/children (first (node/children ir)))]
      (is (node/valid-tree? ir))
      (is (= [:list-item :list-item] (mapv node/kind items)))
      (is (= :directory (get-in (first items) [:meta :role])))
      (is (= :file (get-in (second items) [:meta :role])))
      (is (= "/a/f.txt" (get-in (second items) [:meta :path])))
      (is (= "src" (node/text-content (first items)))))))
