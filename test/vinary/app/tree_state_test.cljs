(ns vinary.app.tree-state-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
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

(deftest active-scopes-for-extras
  (let [ps [{:root "/repo" :files ["top.md"]
             :extras [{:path "/tmp/vv/stdin" :name "(piped diff)" :kind "diff" :transient? true}]}]]
    (testing "an extra's path (outside every root) reveals the adopting project's root scope"
      (is (= #{["/repo" "/repo"]} (tree-state/active-scopes ps "/tmp/vv/stdin"))))
    (testing "containment still wins when a project contains the path"
      (is (= #{["/repo" "/repo"]} (tree-state/active-scopes ps "/repo/top.md"))))
    (testing "a path in no project and no extras reveals nothing"
      (is (nil? (tree-state/active-scopes ps "/elsewhere/x.md"))))))

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

;; ---- the fast derivation equals the definition (docs/scientific/10 §11) --------------------------------------------

(defn- by-definition
  "A project's directory scopes as file-directory-scopes DEFINES them: the union over its files."
  [{:keys [root files]}]
  (into #{[root root]} (mapcat #(tree-state/file-directory-scopes root %)) files))

(defn- lcg [seed]
  (let [s (atom (bit-and seed 0x7fffffff))]
    (fn [n] (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) (mod @s n))))

(defn- random-path
  "A seeded random root-relative path, including the odd shapes a listing could carry: doubled, leading and trailing
   separators, and names that share prefixes."
  [rnd]
  (let [parts ["a" "ab" "b" "" "c.md" "d"]
        body  (str/join "/" (repeatedly (inc (rnd 4)) #(nth parts (rnd (count parts)))))]
    (case (rnd 6)
      0 (str "/" body)
      1 (str body "/")
      2 (str body "//")
      body)))

(deftest project-directory-scopes-equals-its-definition
  (testing "hand cases"
    (doseq [files [[] ["top.md"] ["a/b/c.md"] ["a//b.md"] ["/lead.md"] ["trail/"] ["x//"] [""] ["a/b" "a/bc/d"]]]
      (is (= (by-definition {:root "/r" :files files}) (tree-state/project-directory-scopes {:root "/r" :files files}))
          (pr-str files))))
  (testing "seeded random listings"
    (doseq [seed (range 1 101)]
      (let [rnd     (lcg seed)
            project {:root (if (zero? (rnd 2)) "/repo" "/") :files (vec (repeatedly (rnd 30) #(random-path rnd)))}]
        (is (= (by-definition project) (tree-state/project-directory-scopes project)) (str "seed " seed))))))

(deftest directory-scopes-is-memoized-on-identity-and-only-on-identity
  (let [p  {:root "/repo" :files ["a/x.md"]}
        ps [p]]
    (is (identical? (tree-state/directory-scopes ps) (tree-state/directory-scopes ps))
        "the same projects vector answers from the memo")
    (is (identical? (tree-state/project-directory-scopes p) (tree-state/project-directory-scopes p)))
    (testing "an equal but distinct value is computed afresh — and agrees"
      (let [ps' [(assoc p :files ["a/x.md"])]]
        (is (= (tree-state/directory-scopes ps) (tree-state/directory-scopes ps')))))
    (testing "a changed listing is never served a stale answer"
      (is (contains? (tree-state/directory-scopes [(update p :files conj "b/y.md")]) ["/repo" "/repo/b"]))
      (is (not (contains? (tree-state/directory-scopes ps) ["/repo" "/repo/b"]))))))
