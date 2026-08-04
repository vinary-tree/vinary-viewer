(ns vinary.app.diff-collapse-test
  "DOM-free unit tests for the per-file diff collapse tier (ADR-0037): the nav :diff-collapsed set
   semantics (toggle/set/default, per-tab isolation), its navigation clearing (positional vv-diff-file-N
   ids must never leak across documents), and the facet gate helpers the View menu / combo / events share
   (diff-preview-active?, diff-file-ids, diff-all-collapsed? — exercised against the real DataScript conn,
   with the transacted fixture retracted afterwards so the global snapshot stays clean for other tests)."
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [vinary.app.ds :as ds]
            [vinary.app.facet :as facet]
            [vinary.app.nav :as nav]))

(defn- tab [id uri & kvs]
  (apply assoc {:id id :uri uri :hist {:stack [{:uri uri :scroll 0 :facet nil}] :idx 0}} kvs))

(def ^:private db2
  {:ui {:active-tab 1
        :tabs [(tab 1 "/d/change.diff")
               (tab 2 "/d/other.diff")]}})

(deftest collapse-set-semantics
  (testing "default: absent → #{} (all expanded)"
    (is (= #{} (nav/diff-collapsed db2))))
  (testing "toggle flips one id in/out on the addressed tab only"
    (let [db' (nav/toggle-diff-collapsed db2 1 "vv-diff-file-0")]
      (is (= #{"vv-diff-file-0"} (nav/diff-collapsed db')))
      (is (nil? (:diff-collapsed (nth (get-in db' [:ui :tabs]) 1))) "the other tab is untouched")
      (is (= #{} (nav/diff-collapsed (nav/toggle-diff-collapsed db' 1 "vv-diff-file-0"))) "toggling back empties")))
  (testing "set-diff-collapsed coerces to a set; the by-id arity targets that tab"
    (let [db' (nav/set-diff-collapsed db2 ["vv-diff-file-0" "vv-diff-file-1" "vv-diff-file-0"])]
      (is (= #{"vv-diff-file-0" "vv-diff-file-1"} (nav/diff-collapsed db'))))
    (let [db' (nav/set-diff-collapsed db2 2 #{"vv-diff-file-3"})]
      (is (= #{} (nav/diff-collapsed db')) "the active tab is untouched")
      (is (= #{"vv-diff-file-3"} (:diff-collapsed (nth (get-in db' [:ui :tabs]) 1)))))))

(deftest collapse-cleared-on-navigation
  (let [collapsed (nav/set-diff-collapsed db2 #{"vv-diff-file-0"})]
    (testing "nav-active to a DIFFERENT uri drops the set (ids are positional per document)"
      (is (nil? (:diff-collapsed (first (get-in (nav/nav-active collapsed "/d/other.diff" {:scroll 0})
                                                [:ui :tabs]))))))
    (testing "nav-active to the SAME uri (a refresh) keeps it"
      (is (= #{"vv-diff-file-0"}
             (nav/diff-collapsed (nav/nav-active collapsed "/d/change.diff" {:scroll 0})))))
    (testing "nav-tab (the web view's owner-tab recorder) clears the same way"
      (is (nil? (:diff-collapsed (first (get-in (nav/nav-tab collapsed 1 "/d/third.diff") [:ui :tabs]))))))
    (testing "step: Back/Forward across DIFFERENT uris clears; a same-uri step (facet flip) keeps"
      (let [two-docs (assoc-in collapsed [:ui :tabs 0 :hist]
                               {:stack [{:uri "/d/old.diff" :scroll 0 :facet nil}
                                        {:uri "/d/change.diff" :scroll 0 :facet nil}] :idx 1})
            [db' uri] (nav/step two-docs -1 {:scroll 0})]
        (is (= "/d/old.diff" uri))
        (is (nil? (:diff-collapsed (first (get-in db' [:ui :tabs])))))
        (let [same-uri (assoc-in collapsed [:ui :tabs 0 :hist]
                                 {:stack [{:uri "/d/change.diff" :scroll 0 :facet nil}
                                          {:uri "/d/change.diff" :scroll 0
                                           :facet {:path "/d/change.diff" :type :source}}] :idx 1})
              [db'' _] (nav/step same-uri -1 {:scroll 0})]
          (is (= #{"vv-diff-file-0"} (nav/diff-collapsed db''))))))))

(deftest facet-diff-gates
  ;; the gates read the GLOBAL DataScript conn (like the app's glue layer) — transact a fixture, assert,
  ;; then retract it so other node tests see a clean snapshot
  (d/transact! ds/conn [{:doc/path "/d/change.diff" :doc/kind "diff"
                         :doc/toc [{:level 1 :text "a.txt" :id "vv-diff-file-0"}
                                   {:level 1 :text "b.txt" :id "vv-diff-file-1"}]}
                        {:doc/path "/d/readme.md" :doc/kind "markdown" :doc/toc []}])
  (try
    (testing "diff-preview-active?: true for a shown diff preview, false for its source facet / a non-diff"
      (is (true? (facet/diff-preview-active? db2)))
      (is (false? (facet/diff-preview-active? (nav/set-facet db2 "/d/change.diff" :source))))
      (is (false? (facet/diff-preview-active?
                   (assoc-in db2 [:ui :tabs 0 :uri] "/d/readme.md"))))
      (is (false? (facet/diff-preview-active? {:ui {:tabs [] :active-tab nil}})) "nil-safe on an empty db"))
    (testing "diff-file-ids come from the Contents outline, in order"
      (is (= ["vv-diff-file-0" "vv-diff-file-1"] (facet/diff-file-ids db2))))
    (testing "diff-all-collapsed? truth table"
      (is (false? (facet/diff-all-collapsed? db2)) "nothing collapsed")
      (is (false? (facet/diff-all-collapsed? (nav/set-diff-collapsed db2 #{"vv-diff-file-0"}))) "partial")
      (is (true?  (facet/diff-all-collapsed?
                   (nav/set-diff-collapsed db2 #{"vv-diff-file-0" "vv-diff-file-1"}))) "all")
      (is (true?  (facet/diff-all-collapsed?
                   (nav/set-diff-collapsed db2 #{"vv-diff-file-0" "vv-diff-file-1" "stale-extra"})))
          "a stale superset still counts as all-collapsed")
      (is (false? (facet/diff-all-collapsed?
                   (assoc-in (nav/set-diff-collapsed db2 #{"x"}) [:ui :tabs 0 :uri] "/d/readme.md")))
          "an empty/unknown file list is never all-collapsed"))
    (finally
      (doseq [p ["/d/change.diff" "/d/readme.md"]]
        (when-let [eid (ds/eid-for-path (ds/snapshot) p)]
          (d/transact! ds/conn [[:db/retractEntity eid]]))))))
