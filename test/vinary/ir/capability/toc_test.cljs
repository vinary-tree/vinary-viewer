(ns vinary.ir.capability.toc-test
  "Unit tests for the IR TOC capability (vinary.ir.capability.toc): toc-of walks the IR headings and
   reproduces the legacy collect-metadata harvest exactly ({:level :text :id}, blank-id headings skipped),
   so the IR render's :toc is byte-equal to the current pipeline's — the same outline + scroll-spy targets."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.markdown :as fe]
            [vinary.ir.capability.toc :as toc]))

(defn- el [tag props & children]
  #js {:type "element" :tagName tag :properties (clj->js props) :children (into-array children)})
(defn- txt  [s]          #js {:type "text" :value s})
(defn- root [& children] #js {:type "root" :children (into-array children)})

(deftest toc-from-markdown-ir
  (let [ir (fe/hast->ir (root (el "h1" {:id "intro"} (txt "Intro"))
                              (el "p" {} (txt "body"))
                              (el "h2" {:id "sec-a"} (txt "  Section A  "))   ; text is trimmed
                              (el "h3" {} (txt "no id heading"))              ; blank id → skipped
                              (el "h2" {:id "sec-b"} (txt "Section B"))))]
    (is (= [{:level 1 :text "Intro"     :id "intro"}
            {:level 2 :text "Section A" :id "sec-a"}
            {:level 2 :text "Section B" :id "sec-b"}]
           (toc/toc-of ir)))))

(deftest toc-nested-heading-text
  (testing "heading text-content concatenates inline children (links/code/emphasis)"
    (let [ir (fe/hast->ir (root (el "h2" {:id "h"}
                                    (txt "See ") (el "a" {:href "#x"} (txt "the link"))
                                    (txt " and ") (el "code" {} (txt "code")))))]
      (is (= [{:level 2 :text "See the link and code" :id "h"}] (toc/toc-of ir))))))

(deftest toc-pure-frontend-ids
  (testing "a pure front-end heading (cljs attrs / explicit :id) also yields a TOC entry"
    (let [h (node/node :heading [(node/leaf :text "Chapter")] {:role :heading :level 1 :id "chapter"})
          doc (node/node :document [h] {})]
      (is (= [{:level 1 :text "Chapter" :id "chapter"}] (toc/toc-of doc))))))
