(ns vinary.ir.frontend.pdf-test
  "Unit tests for the PDF hybrid front-end (vinary.ir.frontend.pdf): pdf.js text items normalize to
   {:str :x :y :w :h :font}; runs group into lines (same baseline) and blocks; the :page > :block > :line >
   :run IR carries bboxes; page-text extracts reading-order text for find/copy; and the font-size outline
   picks large-font lines as headings (the fallback TOC for PDFs without a built-in outline). All DOM-free."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.pdf :as pdf]))

;; normalized items: a heading line + a two-run body line + another body line
(def ^:private items
  [{:str "Title"      :x 50 :y 700 :w 100 :h 20 :font "Fbig"}
   {:str "Hello "     :x 50 :y 680 :w 30  :h 10 :font "Fbody"}
   {:str "world"      :x 80 :y 680 :w 25  :h 10 :font "Fbody"}
   {:str "More text." :x 50 :y 666 :w 55  :h 10 :font "Fbody"}])

(deftest normalize
  (testing "pdf.js text item → normalized {:str :x :y :w :h :font}"
    (is (= {:str "hi" :x 40 :y 700 :w 20 :h 12 :font "F"}
           (pdf/normalize-item #js {:str "hi" :transform #js [10 0 0 12 40 700] :width 20 :height 12 :fontName "F"})))
    (testing "height falls back to |transform[3]| when the reported height is 0"
      (is (= 12 (:h (pdf/normalize-item #js {:str "x" :transform #js [12 0 0 12 0 0] :width 5 :height 0 :fontName "F"})))))))

(deftest lines-grouping
  (testing "items on the same baseline merge into one line, ordered left→right"
    (let [lines (pdf/group-lines items)]
      (is (= 3 (count lines)))
      (is (= ["Hello " "world"] (map :str (second lines))) "same-baseline runs merge, sorted by x"))))

(deftest page-ir+text
  (let [page (pdf/page->ir 1 items)]
    (is (= :page (node/kind page)))
    (is (node/valid-tree? page))
    (is (= 1 (get-in (node/node-meta page) [:page])))
    (testing "structure is :page > :block > :line > :run with per-node bboxes"
      (let [line (first (filter #(= :line (node/kind %)) (node/preorder page)))]
        (is (= :run (node/kind (first (node/children line)))))
        (is (= 1 (get-in (node/node-meta line) [:bbox :page])))))
    (testing "page-text extracts reading-order text for find + copy"
      (is (= "Title\nHello world\nMore text." (pdf/page-text page))))))

(deftest doc-ir+outline
  (let [doc (pdf/doc->ir [[1 items]])]
    (is (= :document (node/kind doc)))
    (is (= [:page] (mapv node/kind (node/children doc))))
    (testing "the font-size outline picks the large-font line as a heading (fallback for outline-less PDFs)"
      (is (= [{:level 1 :text "Title" :id "vv-pdf-page-1"}] (pdf/outline doc))
          "id is the page anchor so a Contents click scrolls to the heading's page"))))

(deftest empty-page
  (testing "a page with no text items → an empty :page (canvas-only fallback)"
    (let [page (pdf/page->ir 3 [])]
      (is (= :page (node/kind page)))
      (is (empty? (node/children page)))
      (is (= "" (pdf/page-text page))))))
