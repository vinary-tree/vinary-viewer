(ns vinary.ir.meta-test
  "Unit tests for IR node metadata (vinary.ir.meta): slugging, role defaulting, source spans, and — the
   load-bearing one — `anchor-id` distinctness, which guarantees byte-identical headings / repeated titles
   get stable, collision-free ids (the scroll-spy / find-jump target and the render key)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.meta :as meta]))

(deftest slugging
  (is (= "hello-world"       (meta/slug "Hello, World!")))
  (is (= "a-b-c"             (meta/slug "  a   b   c  ")))
  (is (= "keep_underscores"  (meta/slug "keep_underscores")))
  (is (= "drop-punctuation"  (meta/slug "drop: punctuation?!"))))

(deftest role-defaults-to-kind
  (is (= :heading   (meta/role (node/node :heading [] {:level 2}))) "no :role → kind")
  (is (= :title     (meta/role (node/node :heading [] {:role :title}))) "explicit :role wins")
  (is (= 2          (meta/level (node/node :heading [] {:level 2})))))

(deftest spans
  (let [n (node/leaf :text "x" {:span {:start {:line 3 :column 1 :offset 40} :end {:line 3 :column 2 :offset 41}}})]
    (is (= 40 (meta/start-offset n)))
    (is (= {:line 3 :column 1 :offset 40} (:start (meta/span n)))))
  (testing "start-offset falls back to line when byte offset is absent, else nil"
    (is (= 7 (meta/start-offset (node/leaf :text "x" {:span {:start {:line 7 :column 0}}}))))
    (is (nil? (meta/start-offset (node/leaf :text "x"))))))

(deftest anchor-id-distinctness
  (testing "identical heading text → distinct, rehype-slug-style ids under one document counter"
    (let [seen (atom {})
          h    (fn [t] (node/node :heading [(node/leaf :text t)] {}))]
      (is (= "intro"    (meta/anchor-id (h "Intro") seen)))
      (is (= "intro-1"  (meta/anchor-id (h "Intro") seen)) "second duplicate gets -1")
      (is (= "intro-2"  (meta/anchor-id (h "Intro") seen)) "third gets -2")
      (is (= "methods"  (meta/anchor-id (h "Methods") seen)) "a new slug is un-suffixed")))
  (testing "blank slug falls back to the role name"
    (is (= "figure" (meta/anchor-id (node/node :figure [] {}) (atom {})))))
  (testing "standalone 1-arity uses a fresh counter"
    (is (= "intro" (meta/anchor-id (node/node :heading [(node/leaf :text "Intro")] {}))))))
