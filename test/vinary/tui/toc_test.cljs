(ns vinary.tui.toc-test
  "The TUI TOC overlay: resolving toc entries to rendered line indices via the anchor map, selection movement with
   a windowed list, and the empty-outline (logs/dirs) case."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.tui.toc :as toc]))

(def ^:private entries
  [{:level 1 :text "Title" :id "title"}
   {:level 2 :text "Setup" :id "setup"}
   {:level 2 :text "Usage" :id "usage"}])
(def ^:private anchors {"title" 0 "setup" 8 "usage" 20})

(deftest build-resolves-ids-to-lines
  (let [items (toc/build entries anchors)]
    (is (= [{:level 1 :text "Title" :line 0} {:level 2 :text "Setup" :line 8} {:level 2 :text "Usage" :line 20}]
           items))
    (testing "an entry whose id has no anchor (not yet rendered) is dropped"
      (is (= 2 (count (toc/build [{:level 1 :text "A" :id "a"} {:level 1 :text "B" :id "gone"} {:level 1 :text "C" :id "c"}]
                                 {"a" 1 "c" 9})))))))

(deftest selection-and-jump
  (let [st (toc/state (toc/build entries anchors))]
    (is (= 0 (:sel st)))
    (is (= 0 (toc/selected-line st)) "first entry → its line")
    (is (= 8 (toc/selected-line (toc/move st 1 10))) "down → second entry's line")
    (is (= 20 (toc/selected-line (toc/move (toc/move st 1 10) 1 10))) "third entry's line")
    (testing "selection clamps at the ends"
      (is (= 0 (:sel (toc/move st -1 10))))
      (is (= 2 (:sel (toc/move st 99 10)))))))

(deftest overlay-rendering
  (let [st (toc/state (toc/build entries anchors))]
    (testing "renders each entry indented by level, the selected row reverse-video"
      (let [rows (toc/overlay-lines st 30 10)]
        (is (= 3 (count rows)))
        (is (str/includes? (first rows) "Title") "top entry text")
        (is (str/includes? (first rows) (str (char 27) "[7m")) "selected row is reverse-video")
        (is (str/includes? (nth rows 1) "  • Setup") "level-2 entry indented")))))

(deftest empty-outline
  (testing "logs/dirs have no headings → a friendly placeholder, not a blank overlay"
    (let [st (toc/state (toc/build [] {}))]
      (is (toc/empty? st))
      (is (= ["  (no headings)"] (toc/overlay-lines st 30 10))))))
