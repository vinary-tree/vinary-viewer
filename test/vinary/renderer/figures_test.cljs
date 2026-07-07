(ns vinary.renderer.figures-test
  "DOM-free unit tests for the pure SVG-metadata parser in vinary.renderer.figures. `parse-svg-meta` reads a
   figure's dimensions (root viewBox, else root width/height) and dominant font-size from SVG source, so
   `scale-figures!` can size the <img> such that its text matches the document prose font. The regression this
   guards: svgbob output has NO root viewBox but embeds nested <marker>/<symbol> viewBoxes (e.g. 8×8
   arrow-heads); a whole-text viewBox scan grabbed one of those as the figure width and shrank the image to a
   few px — the reported 'tiny white square'. The parser must read dimensions from the ROOT <svg> tag only."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.figures :as figures]))

(deftest root-viewbox
  (testing "a root viewBox provides the figure width/height and the dominant font-size"
    (let [m (figures/parse-svg-meta
             "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 322 256\"><text font-size=\"11\">x</text></svg>")]
      (is (= 322 (:v m)))
      (is (= 256 (:h m)))
      (is (= 11 (:f m))))))

(deftest svgbob-nested-viewbox-ignored
  (testing "svgbob: root has width/height + NO viewBox; nested marker viewBoxes are 8×8 → use the ROOT size (656×320), NOT 8"
    (let [svg (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"656\" height=\"320\" class=\"svgbob\">"
                   "<defs><marker id=\"a\" viewBox=\"-2 -2 8 8\"><path d=\"M0 0\"/></marker>"
                   "<marker id=\"b\" viewBox=\"0 0 8 8\"><path d=\"M0 0\"/></marker></defs>"
                   "<style>text{font-size:14px;}</style>"
                   "<text font-size=\"14\">WAL</text></svg>")
          m   (figures/parse-svg-meta svg)]
      (is (= 656 (:v m)) "figure width comes from the ROOT <svg>, not a nested 8×8 marker viewBox")
      (is (= 320 (:h m)))
      (is (= 14 (:f m))))))

(deftest root-viewbox-preferred-over-width
  (testing "when the root has both viewBox and width/height, the viewBox (drawing coordinate space) wins"
    (let [m (figures/parse-svg-meta "<svg width=\"100\" height=\"50\" viewBox=\"0 0 322 256\"></svg>")]
      (is (= 322 (:v m)))
      (is (= 256 (:h m))))))

(deftest dominant-font-size
  (testing "the most-frequent font-size wins (many small byte labels vs a few large headers)"
    (let [svg (str "<svg viewBox=\"0 0 322 256\">"
                   "<text font-size=\"11\">a</text><text font-size=\"11\">b</text><text font-size=\"11\">c</text>"
                   "<text font-size=\"18\">H</text></svg>")]
      (is (= 11 (:f (figures/parse-svg-meta svg)))))))

(deftest no-dimensions
  (testing "no root viewBox and no root width/height → zeros (scale-figures! then leaves CSS in charge)"
    (let [m (figures/parse-svg-meta "<svg class=\"x\"><text>y</text></svg>")]
      (is (= 0 (:v m)))
      (is (= 0 (:h m))))))
