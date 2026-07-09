(ns vinary.renderer.figures-test
  "DOM-free unit tests for the pure SVG-metadata parser in vinary.renderer.figures. `parse-svg-meta` reads a
   figure's dimensions (root viewBox, else root width/height) and dominant font-size from SVG source, so
   `scale-figures-html` can size the <img> such that its text matches the document prose font.

   Two reported regressions are guarded here:
     1. svgbob output has NO root viewBox but embeds nested <marker>/<symbol> viewBoxes (e.g. 8×8 arrow-heads);
        a whole-text viewBox scan grabbed one of those as the figure width and shrank the image to a few px —
        the reported 'tiny white square'. Dimensions must come from the ROOT <svg> tag only.
     2. d2 embeds a `.md` stylesheet whenever a shape carries a markdown label; its relative sizes (1em, 1.25em,
        0.875em, 0.85em) each rounded to `1` and outvoted the real 16px text labels, so the font-matched width
        became docFont · viewBox / 1 ≈ 10065px — clamped by CSS max-width:100% to the full column, i.e. a
        figure at ~15× its intended size with magnified text. Only ABSOLUTE font sizes may vote."
  (:require [cljs.test :refer [deftest is testing async]]
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

;; ---- dominant font: only ABSOLUTE sizes vote (regression 2 above) ----

(def ^:private d2-markdown-label-svg
  "A trimmed d2 0.7.1 figure with a markdown label, mirroring libdictenstein's kernel-substrate.svg: the `.md`
   stylesheet contributes six RELATIVE sizes (three 1em, 1.25em, 0.875em, 0.85em) — one more than the five real
   16px text labels — plus two 12px rules. Under a unit-blind count the six `em`s all round to 1 and win."
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<svg xmlns=\"http://www.w3.org/2000/svg\" data-d2-version=\"0.7.1\" viewBox=\"0 0 671 723\">"
       "<svg class=\"d2-svg\" width=\"671\" height=\"723\" viewBox=\"-6 -126 671 723\">"
       "<style type=\"text/css\"><![CDATA["
       ".md { font-size: 1em; }"
       ".md p { font-size: 1em; }"
       ".md li { font-size: 1em; }"
       ".md h1 { font-size: 1.25em; }"
       ".md code { font-size: 0.875em; }"
       ".md small { font-size: 0.85em; }"
       ".md kbd { font-size: 12px; }"
       ".md sub { font-size: 12px; }"
       "]]></style>"
       "<text class=\"text-bold\" style=\"text-anchor:middle;font-size:16px\">one</text>"
       "<text class=\"text-bold\" style=\"text-anchor:middle;font-size:16px\">two</text>"
       "<text class=\"text-bold\" style=\"text-anchor:middle;font-size:16px\">three</text>"
       "<text class=\"text-bold\" style=\"text-anchor:middle;font-size:16px\">four</text>"
       "<text class=\"text-bold\" style=\"text-anchor:middle;font-size:16px\">five</text>"
       "</svg></svg>"))

(deftest relative-css-font-sizes-ignored
  (testing "d2 markdown labels: six relative `em` sizes must NOT outvote five absolute 16px text labels"
    (let [m (figures/parse-svg-meta d2-markdown-label-svg)]
      (is (= 671 (:v m)) "dimensions come from the ROOT viewBox, not the nested d2 <svg>")
      (is (= 723 (:h m)))
      (is (= 16 (:f m)) "dominant font is 16px, not 1 (the rounded 1em/1.25em/0.875em/0.85em)")))
  (testing "the font-matched width is the figure's intended 629px, not the 10065px blow-up"
    (is (= {:width "629px" :aspect-ratio "671 / 723"}
           (figures/svg-style (figures/parse-svg-meta d2-markdown-label-svg) 15))
        "15·671/16 = 629.06 → 629 (the bug produced 15·671/1 = 10065px, CSS-clamped to the column)")))

(deftest percent-font-size-ignored
  (testing "`font-size: 85%` is a multiplier on an inherited size, never a candidate"
    (let [svg (str "<svg viewBox=\"0 0 100 50\">"
                   "<style>.a{font-size:85%}.b{font-size:85%}.c{font-size:100%}</style>"
                   "<text font-size=\"10\">x</text></svg>")]
      (is (= 10 (:f (figures/parse-svg-meta svg)))))))

(deftest unitless-attribute-vs-unitless-css
  (testing "a bare number is user units as an SVG attribute, but invalid CSS as a declaration"
    (let [svg (str "<svg viewBox=\"0 0 100 50\">"
                   "<style>.a{font-size:99}.b{font-size:99}.c{font-size:99}</style>"
                   "<text font-size=\"14\">x</text></svg>")]
      (is (= 14 (:f (figures/parse-svg-meta svg)))
          "three unitless CSS declarations the browser would drop must not outvote one attribute"))))

(deftest absolute-units-converted
  (testing "pt/pc/in/cm/mm are absolute → converted to px before counting"
    (is (= 16 (:f (figures/parse-svg-meta "<svg viewBox=\"0 0 100 50\"><style>text{font-size:12pt}</style></svg>")))
        "12pt · 96/72 = 16px")
    (is (= 16 (:f (figures/parse-svg-meta "<svg viewBox=\"0 0 100 50\"><style>text{font-size:1pc}</style></svg>")))
        "1pc = 16px")
    (is (= 96 (:f (figures/parse-svg-meta "<svg viewBox=\"0 0 100 50\"><style>text{font-size:1in}</style></svg>")))
        "1in = 96px")))

(deftest implausibly-small-font-ignored
  (testing "a sub-2px `font-size` cannot be real text and would send the width → ∞; it never votes"
    (let [svg (str "<svg viewBox=\"0 0 100 50\">"
                   "<text font-size=\"1\">a</text><text font-size=\"1\">b</text>"
                   "<text font-size=\"10\">c</text></svg>")]
      (is (= 10 (:f (figures/parse-svg-meta svg)))))))

(deftest no-absolute-font-size
  (testing "only relative sizes → no dominant font → natural viewBox width (CSS caps to the column)"
    (let [svg "<svg viewBox=\"0 0 200 100\"><style>text{font-size:1.5em}.t{font-size:90%}</style></svg>"
          m   (figures/parse-svg-meta svg)]
      (is (= 0 (:f m)) "no absolute candidate survives")
      (is (= {:width "200px" :aspect-ratio "200 / 100"} (figures/svg-style m 15))
          "target-width falls back to the natural viewBox width"))))

(deftest dominant-font-tie-break
  (testing "equal counts → the SMALLER size wins deterministically (body text over titles)"
    (let [svg (str "<svg viewBox=\"0 0 320 200\">"
                   "<text font-size=\"28\">Title</text><text font-size=\"16\">body</text></svg>")]
      (is (= 16 (:f (figures/parse-svg-meta svg)))))))

(deftest font-size-adjust-not-counted
  (testing "the `font-size-adjust` property must not be read as a `font-size`"
    (let [svg (str "<svg viewBox=\"0 0 100 50\">"
                   "<style>text{font-size-adjust:0.5;font-size-adjust:0.5;font-size-adjust:0.5}</style>"
                   "<text font-size=\"11\">x</text></svg>")]
      (is (= 11 (:f (figures/parse-svg-meta svg)))))))

(deftest no-dimensions
  (testing "no root viewBox and no root width/height → zeros (scale-figures! then leaves CSS in charge)"
    (let [m (figures/parse-svg-meta "<svg class=\"x\"><text>y</text></svg>")]
      (is (= 0 (:v m)))
      (is (= 0 (:h m))))))

(deftest target-width-font-match
  (testing "font-match: width = doc-font · viewBoxWidth / dominantFont (scales up and down)"
    (is (= 30 (figures/target-width 20 10 15)) "20/10·15 → 30 (up-scale)")
    (is (= 30 (figures/target-width 40 20 15)) "40/20·15 → 30 (down-scale)"))
  (testing "no font to match → natural viewBox width (CSS still caps to the column)"
    (is (= 20 (figures/target-width 20 0 15)) "no dominant font → v")
    (is (= 20 (figures/target-width 20 10 0)) "no doc-font → v")))

(deftest svg-style-shape
  (testing "svg-style emits a rounded px width + aspect-ratio (no explicit px height — CSS owns it)"
    (is (= {:width "30px" :aspect-ratio "20 / 8"} (figures/svg-style {:v 20 :h 8 :f 10} 15)))
    (is (= {:width "94px" :aspect-ratio "100 / 50"} (figures/svg-style {:v 100 :h 50 :f 16} 15))
        "15·100/16 = 93.75 rounds to 94"))
  (testing "no height → width only (no aspect-ratio key)"
    (is (= {:width "30px"} (figures/svg-style {:v 20 :h 0 :f 10} 15))))
  (testing "no usable viewBox width → nil (caller clears inline sizing, CSS owns)"
    (is (nil? (figures/svg-style {:v 0 :h 8 :f 10} 15)))))

(deftest parse-then-style-chain
  (testing "end-to-end pure chain: svgbob root size (656×320, dominant font 14) → font-matched style at 15px"
    (let [svg (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"656\" height=\"320\" class=\"svgbob\">"
                   "<defs><marker id=\"a\" viewBox=\"0 0 8 8\"><path d=\"M0 0\"/></marker></defs>"
                   "<text font-size=\"14\">WAL</text></svg>")
          style (figures/svg-style (figures/parse-svg-meta svg) 15)]
      ;; 15·656/14 = 702.857… → 703
      (is (= "703px" (:width style)))
      (is (= "656 / 320" (:aspect-ratio style))))))

(deftest scale-figures-html-node-passthrough
  (testing "without a DOMParser (:node-test) the pre-DOM pass returns the HTML unchanged"
    (async done
      (let [html "<p>text <img src=\"file:///a.svg\"></p>"]
        (-> (figures/scale-figures-html html)
            (.then (fn [out]
                     (is (= html out) "node has no DOMParser → identity pass-through")
                     (done))))))))
