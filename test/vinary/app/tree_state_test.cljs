(ns vinary.app.tree-state-test
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.tree-state :as tree-state]))

(def projects
  [{:root "/repo" :files ["top.md" "sub/a.md" "sub/deep/b.md"]}])

(def shown
  [{:root "/repo" :files ["top.md" "sub/a.md" "sub/deep/b.md"] :filtered? false}])

(deftest directory-scope-arithmetic
  (is (= #{["/repo" "/repo"]
           ["/repo" "/repo/sub"]
           ["/repo" "/repo/sub/deep"]}
         (tree-state/directory-scopes projects)))
  (is (= [["/repo" "/repo"]
          ["/repo" "/repo/sub"]
          ["/repo" "/repo/sub/deep"]]
         (tree-state/ancestor-scopes "/repo" "/repo/sub/deep")))
  (is (nil? (tree-state/ancestor-scopes "/repo" "/repo-other"))))

(deftest effective-expansion-requires-every-open-ancestor
  (let [root ["/repo" "/repo"]
        sub  ["/repo" "/repo/sub"]
        deep ["/repo" "/repo/sub/deep"]]
    (testing "an expanded chain watches every visible directory"
      (is (= #{root sub deep}
             (tree-state/effective-expanded projects shown #{root sub deep} #{}))))
    (testing "collapsing a parent suspends but does not forget its descendants"
      (is (= #{root}
             (tree-state/effective-expanded projects shown #{root deep} #{}))))
    (testing "a collapsed project suspends the entire remembered branch"
      (is (= #{}
             (tree-state/effective-expanded projects shown #{sub deep} #{}))))
    (testing "a pending direct refresh cannot be force-opened by another reactive trigger"
      (is (= #{root}
             (tree-state/effective-expanded projects shown #{root sub deep} #{sub}))))))

(deftest filter-forced-directories-are-effective-only-while-shown
  (let [filtered [{:root "/repo" :files ["sub/deep/b.md"] :filtered? true}]]
    (is (= #{["/repo" "/repo"] ["/repo" "/repo/sub"] ["/repo" "/repo/sub/deep"]}
           (tree-state/effective-expanded projects filtered #{} #{})))
    (is (= #{["/repo" "/repo"]}
           (tree-state/effective-expanded projects filtered #{} #{["/repo" "/repo/sub"]})))
    (is (= #{} (tree-state/effective-expanded projects [] #{["/repo" "/repo"]} #{})))))

(deftest reveal-and-prune-scopes
  (is (= #{["/repo" "/repo"] ["/repo" "/repo/sub"] ["/repo" "/repo/sub/deep"]}
         (tree-state/active-scopes projects "/repo/sub/deep/b.md")))
  (is (= #{["/repo" "/repo/sub"]}
         (tree-state/prune-scopes projects
                                  #{["/repo" "/repo/sub"] ["/gone" "/gone"]}))))

(deftest windows-paths-reveal-the-file-derived-scope-identities
  (let [root     "C:\\work\\repo"
        projects [{:root root :files ["top.md" "sub/deep/b.md"]}]]
    (is (= #{[root root]
             [root "C:\\work\\repo/sub"]
             [root "C:\\work\\repo/sub/deep"]}
           (tree-state/active-scopes projects "C:\\work\\repo\\sub\\deep\\b.md")))
    (is (= [[root root]
            [root "C:\\work\\repo/sub"]
            [root "C:\\work\\repo/sub/deep"]]
           (tree-state/ancestor-scopes root "C:\\work\\repo\\sub\\deep")))))
