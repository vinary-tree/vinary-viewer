(ns vinary.slides.deck-test
  "The pure slide-deck model (ADR-0045): detection rules and their negatives, frame grouping, every navigation op,
   and the input-mapping helpers. Sizes are in PDF points (bp)."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.slides.deck :as deck]))

(def b169 (get deck/beamer-papers 169))
(def b43 (get deck/beamer-papers 43))
(def letter-landscape [792 612])
(def a4-portrait [595.276 841.89])
(def a4-landscape [841.89 595.276])

(defn- pages [n size] (vec (repeat n size)))

;; ---- classification ---------------------------------------------------------------------------------------

(deftest classify-beamer-creator
  (testing "Beamer's pdfcreator (current and versioned historical spellings) on a multi-page document"
    (doseq [creator ["LaTeX with Beamer class" "LaTeX with Beamer class version 3.10" "latex with BEAMER class"]]
      (is (= {:deck? true :tier :definite :source :beamer}
             (deck/classify {:creator creator :sizes (pages 12 b169)})) creator)))
  (testing "the XMP CreatorTool carries the same signal when /Info does not"
    (is (= :beamer (:source (deck/classify {:creator-tool "LaTeX with Beamer class" :sizes (pages 3 b43)})))))
  (testing "a single slide at a Beamer paper size is still a deck"
    (is (:deck? (deck/classify {:creator "LaTeX with Beamer class" :sizes [b43]}))))
  (testing "a ONE-page beamerposter (same Creator, A0 page) is not a deck"
    (is (not (:deck? (deck/classify {:creator "LaTeX with Beamer class" :sizes [[2383.94 3370.39]]})))))
  (testing "'beamer' must be a whole word (no substring hits)"
    (is (not (:deck? (deck/classify {:creator "Beamerstraße Publishing" :sizes (pages 4 a4-portrait)}))))))

(deftest classify-fullscreen-page-mode
  (is (= {:deck? true :tier :definite :source :fullscreen-mode}
         (deck/classify {:page-mode "FullScreen" :sizes (pages 5 a4-portrait)})))
  (is (not (:deck? (deck/classify {:page-mode "UseOutlines" :sizes (pages 5 a4-portrait)})))))

(deftest classify-slide-exports
  (testing "exact PowerPoint / Google Slides canvases, uniform, ≥ 2 pages"
    (doseq [size [[960 540] [720 540] [720 405] [960.2 539.8]]]
      (is (= :slide-export (:source (deck/classify {:sizes (pages 10 size)}))) (pr-str size))))
  (testing "Impress's own presets as Impress 26.8 exports them (docs/scientific/12, export corpus), and the 16:10
            on-screen show"
    (doseq [size [[793.7 595.28] [793.7 446.46] [793.7 496.06] [720 450]]]
      (is (= :slide-export (:source (deck/classify {:creator "Impress" :producer "LibreOffice 26.8.1.1" :sizes (pages 3 size)})))
          (pr-str size))))
  (testing "an older Impress's whole-point 28 × 21 cm canvas (observed in the natural corpus) is within the tolerance"
    (is (= :slide-export (:source (deck/classify {:sizes (pages 12 [794 595])})))))
  (testing "Keynote's Standard and Wide canvases"
    (doseq [size [[1024 768] [1920 1080]]]
      (is (= :slide-export (:source (deck/classify {:sizes (pages 6 size)}))) (pr-str size))))
  (testing "A4 landscape is NOT Impress's 28 × 21 cm canvas: the widths differ by 48 bp"
    (is (not (:deck? (deck/classify {:sizes (pages 4 a4-landscape)}))))
    (is (nil? (deck/export-canvas a4-landscape))))
  (testing "a single exported page is not enough"
    (is (not (:deck? (deck/classify {:sizes [[960 540]]})))))
  (testing "mixed sizes are not an export"
    (is (not (:deck? (deck/classify {:sizes [[960 540] [960 540] a4-portrait]}))))))

(deftest classify-beamer-geometry
  (testing "a Beamer paper + a TeX-engine producer, with pdfcreator overridden"
    (doseq [producer ["pdfTeX-1.40.26" "LuaTeX-1.18.0" "xdvipdfmx (20240305)" "LuaHBTeX, Version 1.18.0"]]
      (is (= {:deck? true :tier :probable :source :beamer-geometry}
             (deck/classify {:creator "my talk" :producer producer :sizes (pages 8 b169)})) producer)))
  (testing "a Beamer-sized document from a non-TeX producer is not enough"
    (is (not (:deck? (deck/classify {:producer "cairo 1.18.0" :sizes (pages 8 b169)})))))
  (testing "a TeX-made Beamer-sized single page is not enough"
    (is (not (:deck? (deck/classify {:producer "pdfTeX-1.40.26" :sizes [b169]}))))))

(deftest classify-negatives
  (testing "landscape alone never qualifies (Letter landscape web print-outs, forms, refcards)"
    (is (not (:deck? (deck/classify {:creator "Mozilla/5.0 … Chrome/142" :producer "Skia/PDF m142"
                                     :sizes (pages 4 letter-landscape)}))))
    (is (not (:deck? (deck/classify {:creator "TeX" :producer "pdfTeX-1.40.26" :sizes (pages 2 a4-landscape)})))))
  (testing "an ordinary paper"
    (is (= {:deck? false :tier nil :source nil}
           (deck/classify {:creator "LaTeX with hyperref" :producer "pdfTeX-1.40.26" :sizes (pages 300 a4-portrait)}))))
  (testing "no evidence at all"
    (is (not (:deck? (deck/classify {:sizes []}))))
    (is (not (:deck? (deck/classify {}))))))

;; ---- frames ---------------------------------------------------------------------------------------------

(deftest frames-group-adjacent-equal-labels
  (is (= [[0 0] [1 3] [4 4] [5 6]]
         (deck/frames-from-labels 7 ["1" "2" "2" "2" "3" "4" "4"] true)))
  (testing "\\againframe: an equal label LATER is a new frame (adjacency only)"
    (is (= [[0 0] [1 1] [2 2]] (deck/frames-from-labels 3 ["1" "2" "1"] true))))
  (testing "blank labels never join"
    (is (= [[0 0] [1 1] [2 2]] (deck/frames-from-labels 3 ["" "" "x"] true)))
    (is (= [[0 0] [1 1]] (deck/frames-from-labels 2 [nil nil] true))))
  (testing "no grouping without labels, with a wrong-length label vector, or when grouping is off"
    (is (= [[0 0] [1 1] [2 2]] (deck/frames-from-labels 3 nil true)))
    (is (= [[0 0] [1 1] [2 2]] (deck/frames-from-labels 3 ["1" "1"] true)))
    (is (= [[0 0] [1 1] [2 2]] (deck/frames-from-labels 3 ["1" "1" "1"] false))))
  (testing "edge sizes"
    (is (= [] (deck/frames-from-labels 0 [] true)))
    (is (= [[0 0]] (deck/frames-from-labels 1 ["1"] true)))))

(deftest frame-index-maps-every-page
  (is (= [0 1 1 1 2] (deck/frame-index [[0 0] [1 3] [4 4]])))
  (is (= [] (deck/frame-index []))))

(deftest deck-groups-only-beamer-sources
  (let [labels ["1" "2" "2" "3"]
        beamer (deck/deck {:creator "LaTeX with Beamer class" :labels labels :sizes (pages 4 b169)})
        other  (deck/deck {:creator "Word" :labels labels :sizes (pages 4 a4-portrait)})]
    (is (= [[0 0] [1 2] [3 3]] (:frames beamer)))
    (is (= [0 1 1 2] (:frame-of beamer)))
    (is (= 3 (:frame-count (deck/summary beamer))))
    (is (= [[0 0] [1 1] [2 2] [3 3]] (:frames other)) "non-Beamer labels are page numbers, never overlay groups")
    (is (false? (:deck? other)) "…and the document is still navigable as slides")
    (is (= {:deck? false :tier nil :source nil :pages 4 :frame-count 4 :second-screen nil} (deck/summary other)))))

;; ---- positions + navigation -----------------------------------------------------------------------------

;; frames: [0] [1 2 3] [4] [5 6]  (7 pages, 4 frames)
(def d (deck/deck {:creator "LaTeX with Beamer class"
                   :labels ["1" "2" "2" "2" "3" "4" "4"]
                   :sizes (pages 7 b169)}))
(defn- go [page op] (:page (deck/step d {:page page} op)))

(deftest position-reports-frame-and-overlay
  (is (= {:page 2 :pages 7 :frame 1 :frames 4 :overlay 1 :overlays 3 :label "2"} (deck/position d 2)))
  (is (= {:page 4 :pages 7 :frame 2 :frames 4 :overlay 0 :overlays 1 :label "3"} (deck/position d 4)))
  (is (= 6 (:page (deck/position d 99))) "clamped"))

(deftest step-page-ops
  (is (= 1 (go 0 :next)))
  (is (= 6 (go 6 :next)) "clamped at the end")
  (is (= 0 (go 0 :prev)) "clamped at the start")
  (is (= 4 (go 5 :prev))))

(deftest step-frame-ops
  (testing "next-frame skips the remaining overlays to the next frame's first page"
    (is (= 1 (go 0 :next-frame)))
    (is (= 4 (go 1 :next-frame)))
    (is (= 4 (go 3 :next-frame)))
    (is (= 6 (go 5 :next-frame)) "last frame → its last page"))
  (testing "prev-frame goes to the previous frame FULLY BUILT (its last page)"
    (is (= 3 (go 4 :prev-frame)))
    (is (= 0 (go 2 :prev-frame)))
    (is (= 4 (go 6 :prev-frame)))
    (is (= 0 (go 0 :prev-frame)) "first frame → page 0")))

(deftest step-jumps-and-history
  (let [s0 {:page 2}
        s1 (deck/step d s0 [:jump 5])
        s2 (deck/step d s1 :next)
        s3 (deck/step d s2 :back)
        s4 (deck/step d s3 :forward)]
    (is (= 5 (:page s1)))
    (is (= [2] (:back s1)) "a jump records where it came from")
    (is (= 6 (:page s2)))
    (is (= [2] (:back s2)) "a sequential step records nothing")
    (is (= 2 (:page s3)))
    (is (= [6] (:fwd s3)) "Back records the forward target")
    (is (= 6 (:page s4))))
  (is (= 0 (go 4 :first)))
  (is (= 6 (go 0 :last)))
  (is (= 3 (go 0 [:goto 3])))
  (is (= 6 (go 0 [:goto 40])) "goto clamps")
  (is (= {:page 3} (deck/step d {:page 3} [:jump 3])) "a jump to the current page records nothing")
  (is (= {:page 1} (deck/step d {:page 1} :back)) "empty history: no-op")
  (testing "history is bounded"
    (let [s (reduce (fn [s i] (deck/step d s [:jump (mod i 7)])) {:page 0} (range 1 300))]
      (is (<= (count (:back s)) deck/history-limit))))
  (testing "an empty deck never moves"
    (is (= {:page 0} (deck/step (deck/deck {:sizes []}) {:page 0} :next)))))

(deftest goto-resolves-labels-before-numbers
  (is (= 4 (deck/resolve-goto d "3")) "label 3 = frame 3's first page")
  (is (= 1 (deck/resolve-goto d " 2 ")) "trimmed")
  (is (= nil (deck/resolve-goto d "")))
  (is (= nil (deck/resolve-goto d "99")) "neither a label nor a page")
  (let [unlabelled (deck/deck {:sizes (pages 5 [720 540])})]
    (is (= 2 (deck/resolve-goto unlabelled "3")) "without labels: a 1-based page number")
    (is (= nil (deck/resolve-goto unlabelled "0"))))
  (is (= 4 (go 0 [:goto-label "3"])))
  (is (= 2 (go 2 [:goto-label "nope"])) "an unresolvable reference is a no-op"))

;; ---- input mapping --------------------------------------------------------------------------------------

(deftest digits-buffer
  (is (= "1" (deck/digits-step "" 1)))
  (is (= "12" (deck/digits-step "1" 2)))
  (is (= "234567" (deck/digits-step "123456" 7)) "keeps the last max-digits digits"))

(deftest scroll-commands-map-to-slide-ops
  (is (= :next (deck/scroll->op {:dy 80})))
  (is (= :prev (deck/scroll->op {:dy -80})))
  (is (= :next (deck/scroll->op {:dy :page})))
  (is (= :prev (deck/scroll->op {:dy :-page})))
  (is (= :next (deck/scroll->op {:dy :half})))
  (is (= :prev (deck/scroll->op {:dy :-half})))
  (is (= :next (deck/scroll->op {:dx :right})))
  (is (= :prev (deck/scroll->op {:dx :left})))
  (is (= :first (deck/scroll->op {:to :top})))
  (is (= :last (deck/scroll->op {:to :bottom})))
  (is (= :next-frame (deck/scroll->op {:dy :page :shift? true})))
  (is (= :prev-frame (deck/scroll->op {:dy :-page :shift? true})))
  (is (= :first (deck/scroll->op {:to :top :shift? true})) "Shift doesn't change edge jumps")
  (is (nil? (deck/scroll->op {:dy 0})))
  (is (nil? (deck/scroll->op {}))))

(deftest wheel-accumulates-with-cooldown-and-direction-reset
  (let [{s1 :state o1 :op} (deck/wheel-step {} 0.04 1000)
        {s2 :state o2 :op} (deck/wheel-step s1 0.04 1010)
        {s3 :state o3 :op} (deck/wheel-step s2 0.04 1020)
        {s4 :state o4 :op} (deck/wheel-step s3 0.5 1040)
        {s5 :state o5 :op} (deck/wheel-step s4 0.5 1100)]
    (is (= [nil nil :next] [o1 o2 o3]) "flips once the accumulated delta reaches the threshold")
    (is (nil? o4) "cooldown: a fling right after a flip is ignored")
    (is (= :next o5) "after the cooldown the next notch flips again")
    (is (= 1100 (:last s5))))
  (let [{s :state} (deck/wheel-step {} 0.08 0)
        {o :op s' :state} (deck/wheel-step s -0.05 10)]
    (is (nil? o) "a direction change resets the accumulator instead of cancelling out")
    (is (= -0.05 (:acc s'))))
  (is (= :prev (:op (deck/wheel-step {} -0.2 0)))))

(defn- near? [a b] (< (js/Math.abs (- a b)) 1e-9))

(deftest wheel-notches-normalizes-as-pdf-js-and-undoes-the-zoom
  (testing "pdf.js's units: pixels ÷ 900 (30 px per line × 30 lines), lines ÷ 30, pages as they are"
    (is (near? (/ 120 900) (deck/wheel-notches {:dy 120 :mode 0 :zoom 1})))
    (is (near? (/ 3 30) (deck/wheel-notches {:dy 3 :mode 1})))
    (is (= 1 (deck/wheel-notches {:dy 1 :mode 2}))))
  (testing "signed towards the next slide: down and right are positive, up and left negative"
    (is (pos? (deck/wheel-notches {:dy 120 :mode 0})))
    (is (neg? (deck/wheel-notches {:dy -120 :mode 0})))
    (is (pos? (deck/wheel-notches {:dx 120 :mode 0})))
    (is (neg? (deck/wheel-notches {:dx -120 :mode 0})))
    (is (zero? (deck/wheel-notches {:dx 0 :dy 0 :mode 0}))))
  (testing "a notch is the same notch at any window zoom — Chromium's measured CSS-pixel deltas (docs/scientific/12 §8.7)"
    (doseq [[zoom dy] [[1 120] [1.25 96] [1.5 80] [0.8 149.99999776482585]]]
      (is (< (js/Math.abs (- (/ 120 900) (deck/wheel-notches {:dy dy :mode 0 :zoom zoom}))) 1e-6) (str "zoom " zoom)))))

(deftest one-wheel-notch-flips-one-slide
  (testing "every measured notch, at every zoom, and a smaller 100-px notch: one flip per notch, none skipped"
    (doseq [[zoom dy] [[1 120] [1.25 96] [1.5 80] [0.8 149.99999776482585] [1 100]]]
      (let [ops (loop [s {} t 1000 n 5 ops []]
                  (if (zero? n)
                    ops
                    (let [{s' :state op :op} (deck/wheel-step s (deck/wheel-notches {:dy dy :mode 0 :zoom zoom}) t)]
                      (recur s' (+ t 250) (dec n) (conj ops op)))))]
        (is (= (repeat 5 :next) ops) (str "zoom " zoom ", " dy " px")))))
  (testing "a trackpad's small deltas accumulate: 12 px at a time flips on the 8th event (96 px), not before"
    (let [ops (loop [s {} t 1000 n 8 ops []]
                (if (zero? n)
                  ops
                  (let [{s' :state op :op} (deck/wheel-step s (deck/wheel-notches {:dy 12 :mode 0 :zoom 1}) t)]
                    (recur s' (+ t 16) (dec n) (conj ops op)))))]
      (is (= (concat (repeat 7 nil) [:next]) ops)))))

;; ---- overview -------------------------------------------------------------------------------------------

(deftest overview-tiles-one-per-frame
  (is (= [{:frame 0 :page 0 :alt 0 :label "1"} {:frame 1 :page 3 :alt 1 :label "2"}
          {:frame 2 :page 4 :alt 4 :label "3"} {:frame 3 :page 6 :alt 5 :label "4"}]
         (deck/overview-tiles d :last)))
  (is (= [0 1 4 5] (mapv :page (deck/overview-tiles d :first)))))

(deftest grid-moves
  (let [g {:n 10 :cols 4 :page-rows 2}]
    (is (= 1 (deck/grid-move 0 :right g)))
    (is (= 4 (deck/grid-move 3 :right g)) "right wraps to the next row")
    (is (= 9 (deck/grid-move 9 :right g)) "clamped at the end")
    (is (= 0 (deck/grid-move 0 :left g)))
    (is (= 5 (deck/grid-move 1 :down g)))
    (is (= 7 (deck/grid-move 7 :down g)) "no row below in that column → stay")
    (is (= 2 (deck/grid-move 2 :up g)) "top row → stay")
    (is (= 8 (deck/grid-move 0 :page-down g)))
    (is (= 0 (deck/grid-move 3 :page-up g)))
    (is (= 9 (deck/grid-move 4 :end g)))
    (is (= 0 (deck/grid-move 4 :home g))))
  (is (= 0 (deck/grid-move 3 :right {:n 0 :cols 4}))))

;; ---- layout --------------------------------------------------------------------------------------------

(deftest effective-layout-precedence
  (is (= :slides (deck/effective-layout {:presenting? true :explicit :document})) "presenting wins")
  (is (= :document (deck/effective-layout {:explicit :document :deck? true})) "an explicit choice beats detection")
  (is (= :slides (deck/effective-layout {:explicit :slides :deck? false})))
  (is (= :slides (deck/effective-layout {:deck? true :open-as :slides})))
  (is (= :slides (deck/effective-layout {:deck? true})) "the default for a detected deck is the Slides layout")
  (is (= :document (deck/effective-layout {:deck? true :open-as :document})) "…unless the preference says otherwise")
  (is (= :document (deck/effective-layout {:deck? false}))))

(deftest named-actions-are-an-allow-list
  (testing "paging and find are allowed, mapped to the viewer's ops"
    (is (= :next (deck/named-action-op "NextPage")))
    (is (= :prev (deck/named-action-op "PrevPage")))
    (is (= :first (deck/named-action-op "FirstPage")))
    (is (= :last (deck/named-action-op "LastPage")))
    (is (= :back (deck/named-action-op "GoBack")) "Beamer's back-find-forward symbol")
    (is (= :forward (deck/named-action-op "GoForward")))
    (is (= :find (deck/named-action-op "Find"))))
  (testing "everything else is inert — a document never prints, saves, quits or goes fullscreen (ADR-0045 D13)"
    (doseq [a ["Print" "SaveAs" "Quit" "FullScreen" "Open" "Close" "nextpage" "" nil]]
      (is (nil? (deck/named-action-op a)) (pr-str a))))
  (testing "every allowed paging op is one `step` understands"
    (let [d (deck/deck {:creator "LaTeX with Beamer class" :sizes [[453.5 255.1] [453.5 255.1] [453.5 255.1]]})]
      (doseq [[a op] deck/named-actions :when (not= op :find)]
        (is (map? (deck/step d {:page 1 :back [0] :fwd [2]} op)) a)))))

(deftest swipes-turn-pages
  (testing "a quick, mostly horizontal swipe flips one slide — leftward forward, rightward back"
    (is (= :next (deck/swipe-op {:dx -120 :dy 10 :dt 250})))
    (is (= :prev (deck/swipe-op {:dx 120 :dy -20 :dt 250})))
    (is (= :next (deck/swipe-op {:dx (- deck/swipe-min-px) :dy 0 :dt deck/swipe-max-ms})) "the thresholds are inclusive"))
  (testing "too short, too slow, too vertical, or malformed → nothing"
    (is (nil? (deck/swipe-op {:dx -30 :dy 0 :dt 100})) "a tap / jitter")
    (is (nil? (deck/swipe-op {:dx -200 :dy 0 :dt 1200})) "a slow drag")
    (is (nil? (deck/swipe-op {:dx -100 :dy 80 :dt 200})) "a diagonal mostly-vertical drag (100 < 1.5 × 80)")
    (is (nil? (deck/swipe-op {:dx nil :dy 0 :dt 100})))
    (is (nil? (deck/swipe-op {})))))

(deftest transitions-are-planned-purely
  (is (nil? (deck/transition-plan {:transition :none :from 0 :to 1})) "none is the default: an instant flip")
  (is (nil? (deck/transition-plan {:from 0 :to 1})) "no preference → instant")
  (is (= {:kind :fade :direction :forward :ms 220} (deck/transition-plan {:transition :fade :from 2 :to 3})))
  (is (= {:kind :push :direction :backward :ms 260} (deck/transition-plan {:transition :push :from 5 :to 1}))
      "the direction follows page order (Back after a jump pushes the other way)")
  (is (nil? (deck/transition-plan {:transition :push :from 3 :to 3})) "no page change → nothing to animate")
  (is (nil? (deck/transition-plan {:transition :fade :from 0 :to 1 :reduced-motion? true}))
      "prefers-reduced-motion wins")
  (is (nil? (deck/transition-plan {:transition :spin :from 0 :to 1})) "an unknown kind is ignored"))

;; ---- second-screen decks (Beamer `show notes on second screen`) — evidence measured on LuaLaTeX 1.24 output,
;;      docs/scientific/data/12/second-screen-2026-10-16.txt ----

(def ^:private notes-right-evidence
  "A real 4:3 deck built with \\setbeameroption{show notes on second screen=right}: two frames (2 overlays + 1) whose
   page labels pgfpages corrupted to 1 1 1 (a plain build of the same source is labelled 1 1 2)."
  {:creator "LaTeX with Beamer class" :producer "LuaTeX-1.24.0" :creator-tool nil :page-mode nil
   :labels ["1" "1" "1"] :sizes (vec (repeat 3 [725.669 272.126]))})

(deftest second-screen-pages-double-a-beamer-paper
  (doseq [[ratio [w h]] deck/beamer-papers]
    (is (= :horizontal (deck/second-screen-axis [(* 2 w) h])) (str ratio " doubled in width (notes right / left)"))
    (is (= :vertical (deck/second-screen-axis [w (* 2 h)])) (str ratio " doubled in height (notes above / below)"))
    (is (nil? (deck/second-screen-axis [w h])) (str ratio " itself is a plain slide"))
    (is (nil? (deck/second-screen-axis [(* 2 w) (* 2 h)])) (str ratio " doubled both ways is not a second screen")))
  (testing "the measured LuaLaTeX sizes, within the half-point tolerance"
    (is (= :horizontal (deck/second-screen-axis [725.669 272.126])))
    (is (= :vertical (deck/second-screen-axis [362.835 544.252])))
    (is (nil? (deck/second-screen-axis [726.5 272.126])) "0.83 bp off is not the same paper"))
  (is (nil? (deck/second-screen-axis [792 612])) "landscape Letter is no doubled Beamer paper")
  (is (nil? (deck/second-screen-axis nil))))

(deftest a-second-screen-deck-trusts-no-labels
  (let [d (deck/deck notes-right-evidence)]
    (is (= {:deck? true :tier :definite :source :beamer} (select-keys d [:deck? :tier :source])))
    (is (= :horizontal (:second-screen d)))
    (is (= [[0 0] [1 1] [2 2]] (:frames d)) "one frame per page: the corrupted labels would merge all three")
    (is (nil? (:labels d)) "no labels: the counter, typed goto and the overview use page numbers")
    (is (= "3" (deck/label-of d 2)))
    (is (= 2 (deck/resolve-goto d "3")) "a typed 3 is page 3, not the first page wrongly labelled 3")
    (is (= :horizontal (:second-screen (deck/summary d)))))
  (testing "a WELL-FORMED corrupt sequence is still ignored — five single-overlay frames + one two-overlay frame
            measured as 1 1 2 2 3 3 (truth: 1 2 3 4 5 5), which no consistency check could tell from a real one"
    (let [d (deck/deck (assoc notes-right-evidence :labels ["1" "1" "2" "2" "3" "3"]
                              :sizes (vec (repeat 6 [725.669 272.126]))))]
      (is (= 6 (count (:frames d))))
      (is (nil? (:labels d)))))
  (testing "a vertically doubled deck (notes above / below)"
    (is (= :vertical (:second-screen (deck/deck (assoc notes-right-evidence :sizes (vec (repeat 3 [362.835 544.252])))))))))

(deftest second-screen-geometry-classifies-as-beamer
  (testing "Beamer geometry doubled for a second screen, from a TeX engine, without the Creator tag"
    (is (= {:deck? true :tier :probable :source :beamer-geometry}
           (deck/classify {:producer "pdfTeX-1.40.26" :sizes (vec (repeat 4 [907.086 255.118]))}))))
  (testing "one doubled page still counts with the Beamer Creator (a one-slide deck with notes)"
    (is (:deck? (deck/classify {:creator "LaTeX with Beamer class" :sizes [[725.669 272.126]]})))))

(deftest which-half-is-the-slide
  ;; [axis notes] → the slide side
  (doseq [[axis notes want] [[:horizontal :auto :left] [:horizontal :right :left] [:horizontal :left :right]
                             [:horizontal :bottom :left] [:horizontal :top :left] [:horizontal :off nil]
                             [:horizontal nil :left] [:horizontal :sideways :left]
                             [:vertical :auto :top] [:vertical :bottom :top] [:vertical :top :bottom]
                             [:vertical :right :top] [:vertical :left :top] [:vertical :off nil]
                             [nil :auto nil] [nil :left nil]]]
    (is (= want (deck/slide-side axis notes)) (str axis " / notes " notes))))

(deftest the-shown-half
  (let [page [725.669 272.126]]
    (is (= {:x 0 :y 0 :w 362.8345 :h 272.126} (deck/half-rect page :left)))
    (is (= {:x 362.8345 :y 0 :w 362.8345 :h 272.126} (deck/half-rect page :right)))
    (is (= {:x 0 :y 0 :w 725.669 :h 272.126} (deck/half-rect page nil)) "no side: the whole page")
    (is (= [362.8345 272.126] (deck/display-size page :right))))
  (let [page [362.835 544.252]]
    (is (= {:x 0 :y 0 :w 362.835 :h 272.126} (deck/half-rect page :top)))
    (is (= {:x 0 :y 272.126 :w 362.835 :h 272.126} (deck/half-rect page :bottom)) "y runs DOWN from the top edge")))

(deftest text-runs-on-the-slide-half
  (is (deck/on-slide-half? :left 1.2 40))
  (is (not (deck/on-slide-half? :left 53.9 10)) "a notes run (x 391 of 725.7 bp)")
  (is (not (deck/on-slide-half? :left 50 10)) "the midline belongs to the second half")
  (is (deck/on-slide-half? :right 51.2 10))
  (is (deck/on-slide-half? :top 30 1.6))
  (is (not (deck/on-slide-half? :top 30 50.8)))
  (is (deck/on-slide-half? :bottom 3 71.2))
  (is (deck/on-slide-half? nil 99 99) "whole pages hide nothing"))

;; ---- the audience's pages (ADR-0048 §3, §5) ------------------------------------------------------------------------
;; The real LuaLaTeX `show notes` deck test/fixtures/beamer-shownotes.pdf: Intro (2 overlays + an in-frame note on
;; each), a \note between frames, Middle, Outro (2 overlays, \note<2>), \againframe{middle}. Its labels and its note
;; pages {1 3 4 8} were written down before it was compiled (test/fixtures/beamer-shownotes.tex).

(def ^:private shownotes-evidence
  {:creator "LaTeX with Beamer class" :producer "LuaTeX-1.24.0"
   :labels ["1" "2" "1" "4" "5" "2" "3" "3" "9" "4"] :sizes (pages 10 b43)})

(def ^:private sn (deck/deck (assoc shownotes-evidence :hidden #{1 3 4 8})))
(defn- go-sn [page op] (:page (deck/step sn {:page page} op)))

(deftest without-hidden-pages-the-model-is-adr-0045s
  (let [d0 (deck/deck shownotes-evidence)]
    (is (= (vec (range 10)) (:shown d0) (:ordinal d0) (:snap d0)) "every page shown, in order, snapping to itself")
    (is (= (:frames d0) (deck/frames-from-labels 10 (:labels shownotes-evidence) true)))
    (is (= #{} (:hidden d0) (:skipped d0) (:forced d0)))
    (is (nil? (:end-frame d0)))))

(deftest note-pages-leave-the-frames
  (is (= [0 2 5 6 7 9] (:shown sn)))
  (is (= [0 nil 1 nil nil 2 3 4 nil 5] (:ordinal sn)))
  (is (= [0 0 2 2 2 5 6 7 7 9] (:snap sn)) "a note page snaps to the slide page below it")
  (is (= [[0 2] [5 5] [6 7] [9 9]] (:frames sn)) "four frames: Intro, Middle, Outro, Middle again")
  (is (= [0 0 0 0 0 1 2 2 2 3] (:frame-of sn)) "a note page belongs to its slide's frame")
  (is (= 4 (:frame-count (deck/summary sn))))
  (testing "a snap never moves up"
    (doseq [p (range 10)] (is (<= (deck/snap sn p) p) (str "page " p)))))

(deftest navigation-runs-over-shown-pages
  (is (= 2 (go-sn 0 :next)) "Intro overlay 1 → overlay 2, past its note page")
  (is (= 5 (go-sn 2 :next)) "…→ Middle, past two note pages")
  (is (= 2 (go-sn 5 :prev)))
  (is (= 9 (go-sn 9 :next)) "clamped at the last shown page")
  (is (= 5 (go-sn 0 :next-frame)))
  (is (= 2 (go-sn 5 :prev-frame)) "the previous frame fully built")
  (is (= 9 (go-sn 0 :last)))
  (is (= 0 (go-sn 7 :first)))
  (testing "from a note page, a step starts at its slide"
    (is (= 5 (go-sn 3 :next)))
    (is (= 0 (go-sn 1 :prev)) "page 1 is page 0's note, and no slide comes before page 0: :prev stays on it"))
  (testing "a goto or a jump to a note page lands on its slide, and records history"
    (let [s (deck/step sn {:page 0} [:goto 3])]
      (is (= 2 (:page s)))
      (is (= [0] (:back s))))
    (is (= 7 (go-sn 0 [:jump 8]))))
  (testing "[:show p] follows another window: snapped, and NO history"
    (is (= {:page 7} (deck/step sn {:page 0} [:show 8])))
    (is (= {:page 9 :back [2] :fwd []} (deck/step sn {:page 9 :back [2] :fwd []} [:show 99])) "clamped, history untouched")))

(deftest typed-numbers-name-shown-labels-then-the-ordinal
  (is (= 5 (deck/resolve-goto sn "2")) "frame 2 — not page 2, Intro's note page (label 2)")
  (is (= 9 (deck/resolve-goto sn "4")) "the shown page labelled 4, not note page 3 (labelled 4)")
  (is (= 0 (deck/resolve-goto sn "1")))
  (is (= 9 (deck/resolve-goto sn "6")) "no shown label 6: the 6th shown page")
  (is (nil? (deck/resolve-goto sn "9")) "a note page's own number names nothing")
  (is (= 5 (go-sn 0 [:goto-label "2"]))))

(deftest counters-count-shown-overlays
  (is (= {:page 2 :pages 10 :frame 0 :frames 4 :overlay 1 :overlays 2 :label "1"} (deck/position sn 2)))
  (is (= {:page 3 :pages 10 :frame 0 :frames 4 :overlay 1 :overlays 2 :label "4"} (deck/position sn 3))
      "a note page reports its slide's frame and overlay; :page and :label stay its own")
  (is (= {:page 7 :pages 10 :frame 2 :frames 4 :overlay 1 :overlays 2 :label "3"} (deck/position sn 7))))

(deftest the-overview-shows-shown-pages
  (is (= [2 5 7 9] (mapv :page (deck/overview-tiles sn :last))))
  (is (= [0 5 6 9] (mapv :page (deck/overview-tiles sn :first)))))

(deftest hidden-pages-are-sanitized
  (let [d (deck/deck (assoc shownotes-evidence :hidden #{0 1 10 -1 "3" 2.5}))]
    (is (= #{1} (:hidden d)) "page 0, out-of-range pages and non-integers are ignored")
    (is (= 0 (deck/snap d 0)))))

(deftest pdfpc-skipped-pages
  ;; five one-page frames, pdfpc hides page 2 (pages[].hidden): the steps pass over it; Home, End, a goto, the overview
  ;; and the history still reach it (pdfpc's rule, ADR-0048 §5)
  (let [d  (deck/deck {:sizes (pages 5 [720 540]) :skipped #{2}})
        go (fn [p op] (:page (deck/step d {:page p} op)))]
    (is (= #{2} (:skipped d)))
    (is (= 3 (go 1 :next)))
    (is (= 1 (go 3 :prev)))
    (is (= 3 (go 1 :next-frame)))
    (is (= 1 (go 3 :prev-frame)))
    (is (= 2 (go 0 [:goto 2])) "a goto reaches it")
    (is (= 3 (go 2 :next)) "and the steps leave it")
    (is (some #{2} (map :page (deck/overview-tiles d :last))) "the overview still shows it"))
  (testing "only skipped pages ahead: the step stays put"
    (let [d (deck/deck {:sizes (pages 4 [720 540]) :skipped #{2 3}})]
      (is (= 1 (:page (deck/step d {:page 1} :next))))
      (is (= 1 (:page (deck/step d {:page 1} :next-frame))))
      (is (= 3 (:page (deck/step d {:page 1} :last))) "End still reaches the last page"))))

(deftest pdfpc-forced-overlays-join-the-previous-frame
  (let [d (deck/deck {:sizes (pages 5 [720 540]) :forced #{2 3 0}})]
    (is (= [[0 0] [1 3] [4 4]] (:frames d)) "pages 2 and 3 join page 1's frame; page 0 cannot be forced")
    (is (= [0 1 1 1 2] (:frame-of d)))
    (is (= 4 (:page (deck/step d {:page 1} :next-frame))))))

(deftest pdfpc-end-slide-is-one-end-away
  ;; six one-page frames, pdfpc's end slide is frame 3 (EndUserSlide 4): End goes there first, then to the last page
  (let [d  (deck/deck {:sizes (pages 6 [720 540]) :end-frame 3})
        go (fn [p op] (:page (deck/step d {:page p} op)))]
    (is (= 3 (:end-frame d)))
    (is (= 3 (go 0 :last)))
    (is (= 5 (go 3 :last)) "from the end slide, the last page")
    (is (= 5 (go 4 :last)) "…and from beyond it")
    (is (nil? (:end-frame (deck/deck {:sizes (pages 6 [720 540]) :end-frame 6}))) "an end slide past the last frame is ignored")
    (is (nil? (:end-frame (deck/deck {:sizes (pages 6 [720 540]) :end-frame -1}))))))

(deftest a-split-axis-from-pdfpc
  ;; pdfpc honours a notes position on pages that are no doubled Beamer paper: they split along its axis, and their
  ;; labels are dropped as on any second-screen deck
  (let [d (deck/deck {:creator "LaTeX with Beamer class" :labels ["1" "1" "2"] :sizes (pages 3 [800 300])
                      :split-axis :horizontal})]
    (is (= :horizontal (:second-screen d)))
    (is (nil? (:labels d)))
    (is (= 3 (count (:frames d)))))
  (is (= :vertical (:second-screen (deck/deck {:sizes (pages 2 [362.835 544.252]) :split-axis :horizontal})))
      "the geometry's own axis wins")
  (is (nil? (:second-screen (deck/deck {:sizes (pages 2 [720 540]) :split-axis :sideways})))))
