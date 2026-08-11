(ns vinary.git.graph-geometry-test
  "The Commit Graph's SVG geometry (ADR-0040): every `d` string pinned exactly (row-h 24, lane-w 12,
   mid 12), the lane cap's clamping, ref-badge normalization, the keyboard cursor math, and the
   locale-free date column."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.git.graph-geometry :as ggeo]))

(deftest lane-arithmetic
  (is (= 6 (ggeo/lane-x 0)))
  (is (= 18 (ggeo/lane-x 1)))
  (is (= (ggeo/lane-x 11) (ggeo/lane-x 12)) "an over-cap lane draws in the last column (cap 12)")
  (is (= 12 (ggeo/rail-width 0)))
  (is (= 144 (ggeo/rail-width 11)))
  (is (= 144 (ggeo/rail-width 40)) "rail width clamps at the cap")
  (is (= "vv-lane-0" (ggeo/lane-class 0)))
  (is (= "vv-lane-1" (ggeo/lane-class 9)) "colors cycle over the 8-hue palette"))

(deftest edge-path-strings
  (testing "each edge kind's exact path"
    (is (= "M6 0 V 24"          (ggeo/edge-path {:from 0 :to 0 :kind :pass} 2)))
    (is (= "M18 0 Q 18 12 6 12" (ggeo/edge-path {:from 1 :to 0 :kind :collapse} 0))
        "a sibling child-edge arrives from the top edge at the dot")
    (is (= "M6 12 V 24"         (ggeo/edge-path {:from 0 :to 0 :kind :continue} 0)))
    (is (= "M6 12 Q 18 12 18 24" (ggeo/edge-path {:from 0 :to 1 :kind :branch} 0))
        "a merge commit's extra parent leaves the dot toward its new lane")
    (is (= "M30 12 Q 6 12 6 24" (ggeo/edge-path {:from 2 :to 0 :kind :merge} 2))
        "a closing lane's leg leaves the dot toward the lane that already expects the parent"))
  (is (nil? (ggeo/edge-path {:from 0 :to 0 :kind :unknown} 0)) "unknown kinds draw nothing"))

(deftest row-geometry-cells
  (let [row {:hash "h" :lane 0
             :edges [{:from 0 :to 0 :kind :continue} {:from 1 :to 1 :kind :pass}]
             :active 2}
        geo (ggeo/row-geometry row [{:from 0 :to 0 :kind :pass}] 1)]
    (testing "the previous row's edge targeting the dot's lane becomes the incoming top-half"
      (is (some #(= "M6 0 V 12" (:d %)) (:paths geo))))
    (testing "the row's own edges draw with their lanes' classes"
      (is (some #(and (= "M6 12 V 24" (:d %)) (= "vv-lane-0" (:class %))) (:paths geo)))
      (is (some #(and (= "M18 0 V 24" (:d %)) (= "vv-lane-1" (:class %))) (:paths geo))))
    (is (= {:cx 6 :cy 12 :r 3.5 :class "vv-lane-0"} (:dot geo)))
    (is (= 24 (:width geo)) "rail width follows max-lane")
    (is (false? (:overflow? geo))))
  (testing "a lane at or past the cap flags overflow"
    (is (true? (:overflow? (ggeo/row-geometry {:lane 12 :edges [] :active 13} nil 12))))
    (is (true? (:overflow? (ggeo/row-geometry {:lane 0 :edges [{:from 12 :to 12 :kind :pass}] :active 13}
                                              nil 12))))))

(deftest ref-badges
  (is (= [{:name "main" :kind :local :head? true}
          {:name "v1.0" :kind :tag :head? false}
          {:name "origin/main" :kind :remote :head? false}
          {:name "dev" :kind :local :head? false}]
         (ggeo/refs->badges ["HEAD -> main" "tag: v1.0" "origin/main" "dev"])))
  (is (= [{:name "HEAD" :kind :local :head? true}] (ggeo/refs->badges ["HEAD"]))
      "a detached HEAD decorates bare")
  (is (= [] (ggeo/refs->badges ["" "   "])) "blank decorations vanish"))

(deftest cursor-math
  (is (= 3 (ggeo/next-cursor 2 10 :down 5)))
  (is (= 1 (ggeo/next-cursor 2 10 :up 5)))
  (is (= 7 (ggeo/next-cursor 2 10 :pgdn 5)))
  (is (= 0 (ggeo/next-cursor 2 10 :pgup 5)) "PgUp clamps at the first row")
  (is (= 9 (ggeo/next-cursor 7 10 :pgdn 5)) "PgDn clamps at the last row")
  (is (= 0 (ggeo/next-cursor 5 10 :home 5)))
  (is (= 9 (ggeo/next-cursor 5 10 :end 5)))
  (is (= 0 (ggeo/next-cursor 0 10 :up 5)) "Up clamps at the first row")
  (is (nil? (ggeo/next-cursor 0 0 :down 5)) "an empty list has no cursor"))

(deftest date-and-chip-formatting
  (is (= "2026-08-11 10:30" (ggeo/fmt-date "2026-08-11T10:30:00+02:00")))
  (is (= "not-a-date" (ggeo/fmt-date "not-a-date")) "unparsable input passes through")
  (is (= "History: core.cljs" (ggeo/history-chip-label {:mode :file-history
                                                        :target {:file "/repo/src/core.cljs"}})))
  (is (= "History: core.cljs · L10–42"
         (ggeo/history-chip-label {:mode :line-history
                                   :target {:file "/repo/src/core.cljs" :start 10 :end 42}})))
  (is (nil? (ggeo/history-chip-label {:mode :log}))))
