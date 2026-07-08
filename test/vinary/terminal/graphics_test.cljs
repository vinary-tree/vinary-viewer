(ns vinary.terminal.graphics-test
  "Unit tests for the pure/sync core of vinary.terminal.graphics: aspect-preserving fit, the kitty + sixel
   encoders, and the encode entry point's degradation matrix. SVG (async resvg WASM) + the CLI wiring are
   covered by test/graphics-smoke.js. A PNG fixture is built in-memory with pngjs so the test needs no asset."
  (:require [cljs.test :refer [deftest is testing async]]
            [vinary.terminal.graphics :as gfx]
            ["pngjs" :refer [PNG]]))

(def ^:private ESC "\u001b")

(defn- png-bytes
  "An w×h PNG (a solid RGBA colour) as a Node Buffer — a real, decodable fixture."
  [w h [r g b]]
  (let [^js png (PNG. #js {:width w :height h})
        ^js data (.-data png)]
    (dotimes [i (* w h)]
      (let [o (* i 4)]
        (aset data o r) (aset data (+ o 1) g) (aset data (+ o 2) b) (aset data (+ o 3) 255)))
    (.write (.-sync PNG) png)))

(deftest fit-preserves-aspect
  (testing "fit clamps to max-cols and preserves aspect (px-w/px-h == w/h)"
    (let [{:keys [cols px-w px-h]} (gfx/fit 100 50 {:max-cols 5 :cell-w 10 :cell-h 20})]
      (is (= 5 cols) "clamped to the 5-column budget")
      (is (= 50 px-w) "5 cols × 10px/cell = 50px wide")
      (is (= 25 px-h) "aspect preserved (100:50 → 50:25)"))
    (let [{:keys [cols]} (gfx/fit 40 40 {:max-cols 80 :cell-w 10 :cell-h 20})]
      (is (= 4 cols) "a small image is NOT upscaled past its native column span"))))

(deftest kitty-escape-well-formed
  (testing "kitty raw-RGBA escape: ESC_G … f=32,s=,v=,c=,r=,C=1 … ESC\\"
    (let [rgba (js/Uint8Array. (* 4 4 4))
          s    (gfx/kitty rgba 4 4 4 2)]
      (is (clojure.string/starts-with? s (str ESC "_G")) "opens with the APC graphics introducer")
      (is (clojure.string/includes? s "f=32") "raw RGBA pixel format")
      (is (clojure.string/includes? s "s=4") "source width transmitted")
      (is (clojure.string/includes? s "C=1") "C=1 so the caller controls cursor advancement")
      (is (clojure.string/ends-with? s (str ESC "\\")) "terminated by ST"))))

(deftest sixel-escape-well-formed
  (testing "sixel is a DCS string (ESC P … ESC \\)"
    (let [rgba (js/Uint8Array. (* 4 4 4))
          s    (gfx/sixel rgba 4 4)]
      (is (clojure.string/includes? s (str ESC "P")) "DCS introducer")
      (is (clojure.string/includes? s (str ESC "\\")) "ST terminator"))))

(deftest encode-kitty-and-sixel
  (testing "a real PNG encodes to a drawable escape + cell dims under :kitty / :sixel"
    (let [buf (png-bytes 16 8 [200 40 40])
          k   (gfx/encode buf ".png" {:graphics :kitty :max-cols 8 :cell-w 10 :cell-h 20})
          x   (gfx/encode buf ".png" {:graphics :sixel :max-cols 8 :cell-w 10 :cell-h 20})]
      (is (clojure.string/starts-with? (:escape k) (str ESC "_G")) "kitty escape")
      (is (pos? (:cols k)) "reports a column span for layout")
      (is (pos? (:rows k)) "reports a row span for cursor advancement")
      (is (clojure.string/includes? (:escape x) (str ESC "P")) "sixel DCS escape"))))

(deftest encode-degrades
  (testing "the degradation matrix: every non-drawable case yields a {:placeholder reason}, never a throw"
    (let [png (png-bytes 8 8 [10 10 10])]
      (is (= {:placeholder :no-graphics}
             (gfx/encode png ".png" {:graphics nil :max-cols 40})) "no graphics capability → placeholder")
      (is (= :too-large (:placeholder (gfx/encode png ".png" {:graphics :kitty :max-cols 40 :max-bytes 10})))
          "an escape over the byte cap → placeholder (never floods the terminal)")
      (is (= :unknown-format (:placeholder (gfx/encode (js/Buffer.from #js [1 2 3 4 5 6 7 8]) ".dat" {:graphics :kitty})))
          "unrecognised bytes → placeholder")
      (is (= :undecodable-webp
             (:placeholder (gfx/encode (js/Buffer.from (clj->js (concat [0x52 0x49 0x46 0x46] (repeat 20 0)))) ".webp"
                                       {:graphics :kitty})))
          "a real-but-unsupported format (webp) → a format-specific placeholder"))))

(deftest encode-svg-async
  (testing "an SVG rasterises to a drawable escape once the resvg WASM is initialised (ensure-ready!)"
    (async done
      (-> (gfx/ensure-ready!)
          (.then (fn [state]
                   (is (= :ready state) "resvg WASM initialised from resources/public/js/resvg.wasm")
                   (let [svg (js/Buffer.from "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"16\"><rect width=\"32\" height=\"16\" fill=\"#639\"/></svg>")
                         r   (gfx/encode svg ".svg" {:graphics :kitty :max-cols 8 :cell-w 10 :cell-h 20})]
                     (is (clojure.string/starts-with? (:escape r) (str ESC "_G")) "SVG → kitty escape after init")
                     (is (pos? (:rows r)) "SVG image reports a row span"))
                   (done)))
          (.catch (fn [e] (is false (str "SVG encode threw: " e)) (done)))))))
