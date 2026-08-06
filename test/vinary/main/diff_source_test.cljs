(ns vinary.main.diff-source-test
  "Real-filesystem coverage for the production local diff-source resolver."
  (:require [cljs.test :refer [deftest is testing]]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [vinary.main.diff-source :as diff-source]))

(defn- with-tree [f]
  (let [root (.realpathSync fs (.mkdtempSync fs (path/join (.tmpdir os) "vv-diff-source-")))]
    (try
      (f root)
      (finally (.rmSync fs root #js {:recursive true :force true})))))

(defn- write! [root rel content]
  (let [absolute (path/join root rel)]
    (.mkdirSync fs (path/dirname absolute) #js {:recursive true})
    (.writeFileSync fs absolute content)
    absolute))

(deftest resolves-from-the-diff-directory-and-its-ancestors
  (with-tree
    (fn [root]
      (let [start   (path/join root "repo" "patches" "nested")
            source  (write! root "repo/src/a file#日.js" "const answer = 42;\n")]
        (.mkdirSync fs start #js {:recursive true})
        (testing "the production ancestor walk finds a repository-relative path above the diff directory"
          (is (= source (diff-source/resolve-from start "src/a file#日.js"))))
        (testing "an absolute path is checked directly"
          (is (= source (diff-source/resolve-from start source))))
        (testing "missing and malformed references stay unresolved"
          (is (nil? (diff-source/resolve-from start "src/missing.js")))
          (is (nil? (diff-source/resolve-from start "")))
          (is (nil? (diff-source/resolve-from start nil))))
        (testing "the walk reaches a repository root more than thirty ancestors above the patch"
          (let [deep (reduce path/join (path/join root "repo") (repeat 35 "nested"))]
            (.mkdirSync fs deep #js {:recursive true})
            (is (= source (diff-source/resolve-from deep "src/a file#日.js")))))))))

(deftest path-only-content-and-legacy-shapes-use-the-same-resolution
  (with-tree
    (fn [root]
      (let [start  (path/join root "repo" "patches")
            source (write! root "repo/src/greet.js" "hello\n")
            empty  (write! root "repo/src/empty.js" "")
            rels   ["src/greet.js" "src/empty.js" "src/missing.js"]]
        (.mkdirSync fs start #js {:recursive true})
        (testing "structured path-only lookup proves existence without returning file bytes"
          (is (= {"src/greet.js" {:path source}
                  "src/empty.js" {:path empty}}
                 (diff-source/load-local start rels
                                         {:include-paths? true :include-content? false}))))
        (testing "structured Split lookup returns both path and content, including an empty file"
          (is (= {"src/greet.js" {:path source :content "hello\n"}
                  "src/empty.js" {:path empty :content ""}}
                 (diff-source/load-local start rels
                                         {:include-paths? true :include-content? true}))))
        (testing "optionless callers retain the legacy rel-to-content wire shape"
          (is (= {"src/greet.js" "hello\n" "src/empty.js" ""}
                 (diff-source/load-local start rels))))
        (testing "structured lookup also accepts an absolute path from a plain unified diff"
          (is (= {source {:path source}}
                 (diff-source/load-local start [source]
                                         {:include-paths? true :include-content? false}))))))))
