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
    (is (= {:deck? false :tier nil :source nil :pages 4 :frame-count 4} (deck/summary other)))))

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
