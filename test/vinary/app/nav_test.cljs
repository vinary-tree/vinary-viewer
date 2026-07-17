(ns vinary.app.nav-test
  "Pure unit tests for the per-tab view-FACET helpers in vinary.app.nav (set-facet / facet / facet-mru) and for the
   retention set, which must include each tab's shown + MRU facet paths so a just-loaded sibling isn't evicted."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.nav :as nav]))

(def ^:private db1
  {:ui {:active-tab 1
        :tabs [{:id 1 :uri "file:///a/paper.tex" :hist {:stack [{:uri "file:///a/paper.tex" :scroll 0}] :idx 0}}
               {:id 2 :uri "file:///a/notes.md"  :hist {:stack [{:uri "file:///a/notes.md" :scroll 0}] :idx 0}}]}})

(deftest facet-transforms
  (testing "a fresh tab has no explicit facet (nil = follow the default)"
    (is (nil? (nav/facet db1)))
    (is (nil? (nav/facet-mru db1))))
  (testing "set-facet stores {:path :type} + the per-type MRU on the ACTIVE tab only"
    (let [db' (nav/set-facet db1 "/a/paper.pdf" :preview)]
      (is (= {:path "/a/paper.pdf" :type :preview} (nav/facet db')))
      (is (= "/a/paper.pdf" (get (nav/facet-mru db') :preview)))
      (is (nil? (:facet (nth (get-in db' [:ui :tabs]) 1))) "the other tab is untouched")))
  (testing "set-facet by explicit id targets that tab"
    (let [db' (nav/set-facet db1 2 "/a/notes.md" :source)]
      (is (= {:path "/a/notes.md" :type :source} (:facet (nth (get-in db' [:ui :tabs]) 1))))
      (is (nil? (nav/facet db')) "the active tab (id 1) is untouched")))
  (testing "the MRU accumulates a last-file per type across switches"
    (let [db' (-> db1 (nav/set-facet "/a/paper.pdf" :preview) (nav/set-facet "/a/paper.tex" :source))]
      (is (= {:path "/a/paper.tex" :type :source} (nav/facet db')) "the latest switch is the active facet")
      (is (= {:preview "/a/paper.pdf" :source "/a/paper.tex"} (nav/facet-mru db')) "both types remembered"))))

(deftest retained-file-paths-includes-facet
  (testing "the retained set is each tab's history uris (as local paths) PLUS its active + MRU facet paths — the
            facet paths are load-bearing (a shown facet is not a history entry, so without them the just-loaded
            sibling would be retracted/unwatched)"
    (let [db' (nav/set-facet db1 "/a/paper.pdf" :preview)
          ret (set (nav/retained-file-paths db'))]
      (is (contains? ret "/a/paper.tex") "tab-1 history file (from its file:// uri)")
      (is (contains? ret "/a/notes.md")  "tab-2 history file")
      (is (contains? ret "/a/paper.pdf") "the active facet's file — the addition this feature requires"))))
