(ns vinary.ui.preview-strip-test
  (:require [cljs.test :refer [deftest is]]
            [vinary.ui.preview-strip :as strip]))

(deftest minimap-height-is-bounded
  (is (= 600 (strip/map-height 1200 600)))
  (is (= 1200 (strip/map-height 10000 600)))
  (is (= 4800 (strip/map-height 1000000 600))))

(deftest viewport-marker-follows-the-document
  (is (= {:top 0 :height 60} (strip/viewport-rect 10000 500 0 1200)))
  (is (= {:top 570 :height 60} (strip/viewport-rect 10000 500 4750 1200)))
  (is (= {:top 1140 :height 60} (strip/viewport-rect 10000 500 9500 1200)))
  (is (= {:top 0 :height 300} (strip/viewport-rect 300 600 0 300))))
