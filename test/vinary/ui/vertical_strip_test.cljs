(ns vinary.ui.vertical-strip-test
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ui.vertical-strip :as strip]))

(defn- visible [selected]
  (strip/window 5 selected 180 560 0.5625))

(deftest the-selection-travels-then-the-window-moves
  (is (= [0 0 0 1 1] (mapv (comp :start visible) (range 5))))
  (is (= 0 (:top (first (:items (visible 0))))) "the first card touches the top")
  (is (= [0 1 2 3 4] (mapv :index (:items (visible 1)))) "a clipped next card announces more")
  (is (= [0 1 2 3 4] (mapv :index (:items (visible 2)))) "the border moves while the middle is reached")
  (is (= [0 1 2 3 4] (mapv :index (:items (visible 3)))) "the strip advances after the middle")
  (is (= [0 1 2 3 4] (mapv :index (:items (visible 4)))) "the end clamps the range")
  (is (true? (:peek? (first (:items (visible 3))))) "an upper partial card shows earlier steps")
  (is (= 20 (+ (:top (first (:items (visible 3))))
               (- (:height (first (:items (visible 3)))) 20)))
      "the upper glimpse contains 20 px of the slide image, not only its caption")
  (is (false? (:peek? (last (:items (visible 4))))) "the last step has no lower partial card"))

(deftest short-and-resized-strips
  (testing "all short-deck cards fit from the top"
    (let [layout (strip/window 2 1 180 560 0.5625)]
      (is (= 2 (:capacity layout)))
      (is (= [0 1] (mapv :index (:items layout))))
      (is (= 0 (:top (first (:items layout)))))))
  (testing "a short pane renders only a bounded window"
    (let [layout (strip/window 100 50 180 160 0.5625)]
      (is (= 1 (:capacity layout)))
      (is (<= (count (:items layout)) 3)))))
