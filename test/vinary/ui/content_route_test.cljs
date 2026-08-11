(ns vinary.ui.content-route-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.ui.content-route :as route]))

(def large-markdown
  {:doc/path "/large.md" :doc/kind "markdown" :doc/text "# source"
   :doc/stamp 2 :doc/streaming? true})

(deftest an-explicit-source-facet-outranks-streaming-preview
  (testing "the reported >256 KiB Markdown shape"
    (is (= :source (route/route {:doc large-markdown :tabs [{}] :uri "/large.md" :source? true})))
    (is (= :stream (route/route {:doc large-markdown :tabs [{}] :uri "/large.md" :source? false}))))
  (testing "the same invariant holds for every progressive prose frontend"
    (doseq [kind ["markdown" "org" "latex"]]
      (is (= :source
             (route/route {:doc (assoc large-markdown :doc/kind kind)
                           :tabs [{}] :uri (str "/large." kind) :source? true}))))))

(deftest git-graph-kind-routes-to-its-view
  (is (= :git-graph
         (route/route {:doc {:doc/path "vv-git-graph:///repo" :doc/kind "git-graph" :doc/stamp 1}
                       :tabs [{}] :uri "vv-git-graph:///repo" :source? false})))
  (testing "never the source view — a graph doc has no :doc/text facet"
    (is (= :git-graph
           (route/route {:doc {:doc/path "vv-git-graph:///repo" :doc/kind "git-graph" :doc/stamp 1}
                         :tabs [{}] :uri "vv-git-graph:///repo" :source? true})))))

(deftest non-source-routes-remain-specific
  (is (= :http-pdf
         (route/route {:doc {:doc/kind "pdf"} :tabs [{}]
                       :uri "https://example.test/paper.pdf"})))
  (is (= :diff-split
         (route/route {:doc {:doc/kind "diff" :doc/diff-split-html "split"}
                       :tabs [{}] :uri "/x.diff" :diff-view :split}))))
