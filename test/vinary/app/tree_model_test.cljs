(ns vinary.app.tree-model-test
  "The sidebar file tree's model: folding flat paths into a nested structure, and narrowing it."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.app.tree-model :as tm]
            [vinary.search.config :as config]))

(def ^:private opts (config/mode-for :tree))

(deftest folding-paths-into-a-tree
  (testing "a flat path list becomes nested folders with file leaves"
    (is (= {"a.md" {:file "a.md"}}
           (tm/build-tree ["a.md"])))
    (is (= {"src" {:children {"core.cljs" {:file "src/core.cljs"}}}}
           (tm/build-tree ["src/core.cljs"])))
    (is (= {"src" {:children {"ui" {:children {"views.cljs" {:file "src/ui/views.cljs"}}}}}}
           (tm/build-tree ["src/ui/views.cljs"]))))
  (testing "siblings share their parent rather than duplicating it"
    (let [t (tm/build-tree ["src/a.cljs" "src/b.cljs"])]
      (is (= #{"a.cljs" "b.cljs"} (set (keys (get-in t ["src" :children])))))))
  (testing "a leaf carries the FULL repo-relative path, which is what the view opens"
    (is (= "src/ui/views.cljs"
           (get-in (tm/build-tree ["src/ui/views.cljs"]) ["src" :children "ui" :children "views.cljs" :file]))))
  (testing "an empty list folds to an empty tree"
    (is (= {} (tm/build-tree [])))))

(deftest filtering
  (let [projects [{:root "/p" :files ["README.md" "src/ui/views.cljs" "src/web/core.cljs"]}
                  {:root "/q" :files ["notes.txt"]}]]
    (testing "a blank query keeps every project, unfiltered"
      (let [got (tm/filtered projects "" opts)]
        (is (= ["/p" "/q"] (mapv :root got)))
        (is (= 3 (count (:files (first got)))))
        (is (false? (:filtered? (first got)))
            "an unfiltered tree must not force every folder open")))
    (testing "nil is treated as blank"
      (is (= 2 (count (tm/filtered projects nil opts)))))
    (testing "a query narrows to matching paths and marks the result filtered"
      (let [got (tm/filtered projects "views" opts)]
        (is (= ["/p"] (mapv :root got)) "a project with nothing left is omitted entirely")
        (is (= ["src/ui/views.cljs"] (:files (first got))))
        (is (true? (:filtered? (first got))))))
    (testing "matching is case-insensitive"
      (is (= ["README.md"] (:files (first (tm/filtered projects "readme" opts))))))
    (testing "it matches anywhere in the path, not just the basename"
      (is (= 2 (count (:files (first (tm/filtered projects "src/" opts)))))))
    (testing "a query matching nothing yields no projects at all"
      (is (= [] (tm/filtered projects "zzzz-no-such-file" opts))))
    (testing "the nested tree is built for the SURVIVORS only"
      (let [{:keys [nodes]} (first (tm/filtered projects "views" opts))]
        (is (= #{"src"} (set (keys nodes))))
        (is (= #{"ui"} (set (keys (get-in nodes ["src" :children])))))))))

(deftest filtering-honours-the-configured-mode
  (let [projects [{:root "/p" :files ["src/ui/views.cljs"]}]]
    (testing "the default (substring) does not match a scattered query"
      (is (= [] (tm/filtered projects "vwc" opts))))
    (testing "…and switching the mode is one keyword, which is the point of the shared model"
      (is (= ["src/ui/views.cljs"]
             (:files (first (tm/filtered projects "vwc" (assoc opts :mode :subsequence)))))))))
