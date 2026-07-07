(ns vinary.ir.parity-test
  "The load-bearing parity guard for the Markdown IR cutover: the IR must round-trip HAST losslessly, so the
   flag-gated IR render produces byte-identical HTML to the current pipeline (making :vv/ir invisible to the
   user). Builds representative HAST trees, converts HAST→IR→HAST, serializes both, and asserts equality —
   plus checks that the front-end assigns the semantic kinds + metadata the capability layer relies on.
   (Full-render parity over real Markdown, incl. the math/mermaid/syntax post-passes, is covered by the
   electron smoke, which has a DOM.)"
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.markdown :as fe]
            [vinary.ir.backend.html :as be]))

;; ---- tiny HAST builders ----
(defn- el [tag props & children]
  #js {:type "element" :tagName tag :properties (clj->js props) :children (into-array children)})
(defn- txt  [s]        #js {:type "text" :value s})
(defn- root [& children] #js {:type "root" :children (into-array children)})

(def ^:private fixtures
  [(root (el "h1" {:id "intro"} (txt "Intro")))
   (root (el "p" {} (txt "Hello ") (el "a" {:href "http://x"} (txt "link")) (txt ".")))
   (root (el "p" {} (el "a" {:href "file:///a.png" :className ["vv-figure-link"]}
                        (el "img" {:src "file:///a.png" :alt "a"}))))
   (root (el "ul" {} (el "li" {} (txt "one")) (el "li" {} (txt "two"))))
   (root (el "pre" {} (el "code" {:className ["hljs" "language-clojure"]} (txt "(+ 1 2)"))))
   (root (el "table" {} (el "tbody" {} (el "tr" {} (el "td" {} (txt "a")) (el "td" {} (txt "b"))))))
   (root (el "h2" {:id "s" :data-vv-source-start-line "3"} (txt "Sec")))
   (root (el "blockquote" {} (el "p" {} (txt "q"))))
   (root (el "p" {} (el "code" {:className ["math-inline"]} (txt "x^2"))))
   (root (el "div" {:className ["custom"]} (el "span" {:data-x "1"} (txt "raw-ish"))))])

(deftest hast-ir-roundtrip-parity
  (testing "HAST → IR → HAST → HTML equals HAST → HTML for representative documents"
    (doseq [tree fixtures]
      (let [direct    (be/stringify-hast tree)
            roundtrip (be/lower (fe/hast->ir tree))]
        (is (= direct roundtrip) (str "parity mismatch; direct = " direct))))))

(deftest roundtrip-preserves-valid-ir
  (testing "every fixture maps to a structurally-valid IR tree"
    (doseq [tree fixtures]
      (is (node/valid-tree? (fe/hast->ir tree))))))

(deftest semantic-kinds-and-metadata
  (testing "HAST→IR assigns semantic kinds + derived metadata for the capability layer"
    (let [ir (fe/hast->ir (root (el "h2" {:id "s"
                                          :data-vv-source-start-line "3" :data-vv-source-start-column "1"
                                          :data-vv-source-start-offset "40"} (txt "Sec"))))
          h2 (first (node/children ir))]
      (is (= :document (node/kind ir)))
      (is (= :heading  (node/kind h2)))
      (is (= 2         (get-in h2 [:meta :level])))
      (is (= :heading  (get-in h2 [:meta :role])))
      (is (= 40        (get-in h2 [:meta :span :start :offset])))
      (is (= "h2"      (get-in h2 [:meta :tag]))))
    (testing "images/links/lists/tables get their semantic kinds"
      (let [ir (fe/hast->ir (root (el "ul" {} (el "li" {} (el "img" {:src "x"})))))
            li (first (node/children (first (node/children ir))))]
        (is (= :list (node/kind (first (node/children ir)))))
        (is (= :list-item (node/kind li)))
        (is (= :image (node/kind (first (node/children li)))))))))
