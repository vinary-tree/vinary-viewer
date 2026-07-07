(ns vinary.ir.frontend.office-test
  "Unit tests for the office front-end (vinary.ir.frontend.office): office HTML parses to a valid IR via
   rehype-raw (no new dep), headings gain ids so office gets a TOC it previously lacked, and the shared
   sanitizer strips dangerous markup — unifying office with the Markdown IR path."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.office :as office]
            [vinary.ir.backend.html :as be]
            [vinary.ir.capability.toc :as toc]))

(deftest office-html-to-ir
  (let [ir (office/html->ir "<h1>Report Title</h1><p>Body <strong>bold</strong>.</p><h2>Section</h2><ul><li>a</li><li>b</li></ul>")]
    (is (= :document (node/kind ir)))
    (is (node/valid-tree? ir))
    (is (= [:heading :paragraph :heading :list] (mapv node/kind (node/children ir))))
    (is (= "Report TitleBody bold.Sectionab" (node/text-content ir)))))

(deftest office-headings-get-toc
  (testing "rehype-slug ids office headings → toc-of yields a TOC (office previously produced none)"
    (let [ir (office/html->ir "<h1>Overview</h1><p>x</p><h2>Details</h2>")]
      (is (= [{:level 1 :text "Overview" :id "overview"}
              {:level 2 :text "Details"  :id "details"}]
             (toc/toc-of ir))))))

(deftest office-sanitized
  (testing "the shared sanitizer strips dangerous office HTML (on*-handlers / <script> / javascript:)"
    (let [html (be/lower (office/html->ir "<p onclick=\"evil()\">x</p><script>bad()</script><a href=\"javascript:hack()\">z</a>"))]
      (is (not (re-find #"onclick" html)) "on* handler stripped")
      (is (not (re-find #"(?i)<script" html)) "<script> stripped")
      (is (not (re-find #"javascript:" html)) "javascript: URL stripped"))))

(deftest office-lower-preserves-structure
  (testing "office IR lowers to clean HTML preserving structure + text"
    (let [html (be/lower (office/html->ir "<h1>T</h1><p>hello</p>"))]
      (is (re-find #"<h1[^>]*>T</h1>" html))
      (is (re-find #"<p>hello</p>" html)))))
