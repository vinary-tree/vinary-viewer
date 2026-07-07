(ns vinary.ir.node-test
  "Unit tests for the common IR node (vinary.ir.node): construction, structural validity, preorder/walk,
   bottom-up map-nodes rebuild, and text-content — the uniform tree every format front-end targets."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.ir.node :as node]))

(def ^:private doc
  (node/node :document
    [(node/node :heading   [(node/leaf :text "Intro")] {:level 1})
     (node/node :paragraph [(node/leaf :text "Hello ") (node/leaf :text "world")])
     (node/node :heading   [(node/leaf :text "Intro")] {:level 2})]))   ; duplicate heading text on purpose

(deftest construction+validity
  (is (node/node? doc))
  (is (node/valid-tree? doc))
  (is (= :document (node/kind doc)))
  (is (= 3 (node/arity doc)))
  (is (vector? (node/children doc)))
  (testing "leaf vs branch"
    (let [h1 (first (node/children doc))
          t  (first (node/children h1))]
      (is (not (node/leaf? h1)))
      (is (node/leaf? t))
      (is (= "Intro" (node/text t)))
      (is (= {:level 1} (node/node-meta h1)))))
  (testing "invalid trees are rejected"
    (is (not (node/valid-tree? {:kind :x})))                       ; a bare map, not a Node
    (is (not (node/valid-tree? (node/leaf "not-a-keyword" "x"))))  ; kind must be a keyword
    (is (not (node/valid-tree? (node/leaf :text 42))))))           ; text must be string|nil

(deftest preorder+walk
  (testing "preorder visits self-then-children"
    (is (= 8 (count (node/preorder doc))))                          ; doc + 2 headings(+text each) + para(+2 text)
    (is (= [:document :heading :text :paragraph :text :text :heading :text]
           (mapv node/kind (node/preorder doc)))))
  (testing "walk is side-effecting + returns nil"
    (let [seen (atom [])]
      (is (nil? (node/walk #(swap! seen conj (node/kind %)) doc)))
      (is (= 8 (count @seen))))))

(deftest text-content-reading-order
  (is (= "IntroHello worldIntro" (node/text-content doc))))

(deftest map-nodes-rebuild
  (testing "identity rebuild equals the original"
    (is (= doc (node/map-nodes identity doc))))
  (testing "bottom-up relabel + leaf transform"
    (let [up (node/map-nodes (fn [n]
                               (cond
                                 (node/leaf? n) (node/leaf (node/kind n) (str/upper-case (or (node/text n) "")))
                                 (= :paragraph (node/kind n)) (node/node :block (node/children n) (node/node-meta n))
                                 :else n))
                             doc)]
      (is (= :block (node/kind (second (node/children up)))))
      (is (= "INTROHELLO WORLDINTRO" (node/text-content up)))
      (is (node/valid-tree? up)))))

(deftest immutable-updaters
  (let [h1 (first (node/children doc))]
    (is (= {:level 1 :role :title} (node/node-meta (node/assoc-meta h1 :role :title))))
    (is (= [:x] (mapv node/kind (node/children (node/with-children h1 [(node/leaf :x "y")])))))
    (is (= {:level 1} (node/node-meta h1)) "originals are untouched (persistent)")))
