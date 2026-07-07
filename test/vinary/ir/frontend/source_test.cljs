(ns vinary.ir.frontend.source-test
  "Unit tests for the source-code front-end (vinary.ir.frontend.source): a (synthetic) web-tree-sitter parse
   tree maps to the common IR preserving kinds, spans, leaf text, and error/missing flags, and `outline`
   derives a line-anchored code Contents outline from the top-level declarations."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.source :as source]))

;; a synthetic web-tree-sitter node (only the API node->ir uses)
(defn- ts [type start-idx end-idx start-row named-children & {:keys [error missing]}]
  (let [kids (vec named-children)]
    #js {:type type
         :startIndex start-idx :endIndex end-idx
         :startPosition #js {:row start-row :column 0}
         :endPosition   #js {:row start-row :column 0}
         :namedChildCount (count kids)
         :namedChild (fn [i] (nth kids i))
         :isError (boolean error) :isMissing (boolean missing)}))

(def ^:private src "def foo():\n    pass\n\ndef bar():\n    pass\n")

(defn- tree []
  (let [fn-foo (ts "function_definition" 0 19 0 [(ts "identifier" 4 7 0 [])])
        fn-bar (ts "function_definition" 21 41 3 [(ts "identifier" 25 28 3 [])])]
    #js {:rootNode (ts "module" 0 42 0 [fn-foo fn-bar])}))

(deftest source-tree-to-ir
  (let [ir (source/tree->ir (tree) src)]
    (is (= :document (node/kind ir)))
    (is (node/valid-tree? ir))
    (is (= [:function_definition :function_definition] (mapv node/kind (node/children ir))))
    (is (= "module" (get-in (node/node-meta ir) [:root-type])))
    (testing "spans + leaf source slices preserved"
      (let [foo (first (node/children ir))
            id  (first (node/children foo))]
        (is (= 1 (get-in (node/node-meta foo) [:span :start :line])))
        (is (= 0 (get-in (node/node-meta foo) [:span :start :offset])))
        (is (= :identifier (node/kind id)))
        (is (= "foo" (node/text id)))))))

(deftest source-outline
  (testing "top-level declarations → a line-anchored code Contents outline"
    (is (= [{:level 1 :text "foo" :id "L1" :line 1}
            {:level 1 :text "bar" :id "L4" :line 4}]
           (source/outline (source/tree->ir (tree) src))))))

(deftest source-error-flags
  (testing "error / missing nodes are flagged in metadata"
    (let [ir (source/tree->ir #js {:rootNode (ts "module" 0 3 0 [(ts "ERROR" 0 3 0 [] :error true)])} "xyz")]
      (is (true? (get-in (first (node/children ir)) [:meta :error?]))))))
