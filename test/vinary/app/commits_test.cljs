(ns vinary.app.commits-test
  "The Commits panel's pure model (ADR-0039): repo derivation, the ref-range grammar, the R3
   selection reducers shared with the Commit Graph document, and refresh-survival pruning."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.commits :as commits]))

(def ^:private projs
  [{:root "/home/u/scratch" :files ["a.md"] :synthetic? true}
   {:root "/repo" :files ["src/a.cljs"] :synthetic? false}
   {:root "/repo/vendor/lib" :files ["b.c"] :synthetic? false}
   {:root "/other" :files ["c.md"] :synthetic? false}])

(deftest derive-root-policy
  (testing "synthetic roots are never candidates"
    (is (= "/repo" (commits/derive-root {} projs "/home/u/scratch/a.md"))
        "a non-repo active path falls through to the first git project"))
  (testing "the deepest git project containing the active path wins"
    (is (= "/repo" (commits/derive-root {} projs "/repo/src/a.cljs")))
    (is (= "/repo/vendor/lib" (commits/derive-root {} projs "/repo/vendor/lib/b.c"))))
  (testing "a pin wins while its project is still open, and releases when it closes"
    (is (= "/other" (commits/derive-root {:pinned "/other"} projs "/repo/src/a.cljs")))
    (is (= "/repo" (commits/derive-root {:pinned "/gone"} projs "/repo/src/a.cljs"))))
  (testing "with no containing project the last shown repo is kept (no thrash)"
    (is (= "/other" (commits/derive-root {:last-root "/other"} projs "/tmp/x.md"))))
  (testing "no git project at all → nil"
    (is (nil? (commits/derive-root {} [{:root "/s" :synthetic? true}] "/s/a.md")))))

(deftest range-grammar
  (is (= {:from "A" :to "B"} (commits/parse-range "A..B")))
  (is (= {:from "A" :to "B" :dots "..."} (commits/parse-range "A...B")))
  (is (= {:to "HEAD~3" :parent? true} (commits/parse-range "HEAD~3")))
  (is (= {:to "v1.0" :parent? true} (commits/parse-range "  v1.0  ")) "whitespace trimmed")
  (is (= {:from "HEAD~3" :to "HEAD"} (commits/parse-range " HEAD~3 .. HEAD "))
      "sides are trimmed independently")
  (is (nil? (commits/parse-range "..B")) "an empty FROM side is malformed")
  (is (nil? (commits/parse-range "A..")) "an empty TO side is malformed")
  (is (nil? (commits/parse-range "")) )
  (is (nil? (commits/parse-range "   "))))

(def ^:private order ["h4" "h3" "h2" "h1"])   ; newest-first, like a loaded log page

(deftest selection-reducers
  (testing ":single collapses to one hash"
    (is (= {:cursor "h3" :anchor "h3" :selected #{"h3"}}
           (commits/select {:cursor "h1" :anchor "h1" :selected #{"h1" "h2"}} order "h3" :single))))
  (testing ":toggle flips membership and moves cursor+anchor"
    (let [s (commits/select {:cursor "h4" :anchor "h4" :selected #{"h4"}} order "h2" :toggle)]
      (is (= #{"h4" "h2"} (:selected s)))
      (is (= "h2" (:cursor s)))
      (is (= #{"h4"} (:selected (commits/select s order "h2" :toggle)) )
          "toggling again removes it")))
  (testing ":range spans anchor→hash inclusive, in either direction"
    (is (= #{"h3" "h2" "h1"}
           (:selected (commits/select {:anchor "h3"} order "h1" :range))))
    (is (= #{"h3" "h2" "h1"}
           (:selected (commits/select {:anchor "h1"} order "h3" :range)))
        "anchor inversion selects the same span")
    (is (= "h3" (:anchor (commits/select {:anchor "h3"} order "h1" :range)))
        "the anchor survives a range extension"))
  (testing ":range falls back to :single when an end left the loaded window"
    (is (= {:cursor "h2" :anchor "h2" :selected #{"h2"}}
           (commits/select {:anchor "gone"} order "h2" :range)))))

(deftest diff-pair-ordering
  (testing "exactly two selected → [older newer] (higher index = older in a newest-first list)"
    (is (= ["h1" "h3"] (commits/diff-pair {:selected #{"h3" "h1"}} order)))
    (is (= ["h3" "h4"] (commits/diff-pair {:selected #{"h3" "h4"}} order))
        "h4 sits at index 0 (newest), h3 at index 1 — so h3 is the older FROM side"))
  (testing "not exactly two, or an unknown hash → nil"
    (is (nil? (commits/diff-pair {:selected #{"h1"}} order)))
    (is (nil? (commits/diff-pair {:selected #{"h1" "h2" "h3"}} order)))
    (is (nil? (commits/diff-pair {:selected #{"h1" "gone"}} order)))))

(deftest history-mode-request-args
  (testing "file history follows renames; the ref stays out (history walks HEAD)"
    (is (= {:file "/repo/src/a.cljs" :follow true}
           (commits/history-args {:mode :file-history :target {:file "/repo/src/a.cljs"}}))))
  (testing "line history rides the lineRange key"
    (is (= {:lineRange {:file "/repo/src/a.cljs" :start 10 :end 42}}
           (commits/history-args {:mode :line-history
                                  :target {:file "/repo/src/a.cljs" :start 10 :end 42}}))))
  (testing "the plain branch log adds nothing"
    (is (nil? (commits/history-args {:mode :log})))
    (is (nil? (commits/history-args {})))))

(deftest refresh-survival
  (let [state {:selection {:cursor "h2" :anchor "h9" :selected #{"h2" "h9"}}
               :expanded #{"h2" "h9"}
               :bodies {"h2" "<p>two</p>" "h9" "<p>nine</p>"}
               :commits :untouched}
        pruned (commits/keep-surviving state ["h4" "h3" "h2" "h1"])]
    (is (= {:cursor "h2" :anchor nil :selected #{"h2"}} (:selection pruned)))
    (is (= #{"h2"} (:expanded pruned)))
    (is (= {"h2" "<p>two</p>"} (:bodies pruned)))
    (is (= :untouched (:commits pruned)) "unrelated keys pass through")))
