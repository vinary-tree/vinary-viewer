(ns vinary.app.subs-test
  "Unit tests for the pure `:doc/toc` outline selector (vinary.app.subs/active-toc): the source-view outline
   (`L<line>` ids) and the preview outline (rehype slug ids) live in SEPARATE db attrs so neither clobbers the
   other; `active-toc` picks the one whose id-space matches the active view. Regression guard for the cross-view
   Contents clobber (a source outline overwriting the preview's, leaving preview clicks un-navigable)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.subs :as subs]))

(def ^:private preview [{:level 1 :text "Intro" :id "intro"} {:level 1 :text "Usage" :id "usage"}]) ; slug ids
(def ^:private source  [{:level 1 :text "Intro" :id "L1"}    {:level 1 :text "Usage" :id "L5"}])     ; L<line> ids

(deftest active-toc-selection
  (testing "HTTP page → the web view's headings, regardless of the source-view flag"
    (is (= [{:id "h"}] (subs/active-toc true false [{:id "h"}] preview source)))
    (is (= [{:id "h"}] (subs/active-toc true true  [{:id "h"}] preview source))))
  (testing "source view WITH a source outline → the source outline (L<line> ids), never the preview's slug ids"
    (is (= source (subs/active-toc false true nil preview source))))
  (testing "source view but the source outline has NOT arrived yet → the preview outline (no blank-Contents flash)"
    (is (= preview (subs/active-toc false true nil preview [])))
    (is (= preview (subs/active-toc false true nil preview nil))))
  (testing "preview view → the preview outline (slug ids), never the source's L<line> ids (the clobber guard)"
    (is (= preview (subs/active-toc false false nil preview source))))
  (testing "nil-safe → an empty vector, never nil"
    (is (= [] (subs/active-toc false false nil nil nil)))
    (is (= [] (subs/active-toc true  false nil nil nil)))))
