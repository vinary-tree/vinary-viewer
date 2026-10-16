(ns vinary.app.slides-overview-test
  "The slide overview grid (ADR-0045 §Overview): its pure geometry, opening / closing / picking, Escape's order,
   the representative-page preference, and the keys that open it."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.events :as events]
            [vinary.app.slides :as slides]
            [vinary.input.events :as input-events]
            [vinary.input.keymap :as keymap]
            [vinary.renderer.pdf-layout :as layout]))

(deftest grid-geometry
  (testing "columns come from the width alone: as many ≥ 180 px tiles (16 px gaps) as fit, 1…8"
    (let [{:keys [cols tile-w]} (layout/grid-geometry 1400 900 0.5625)]
      (is (= 7 cols) "floor((1400 − 16) / (180 + 16)) = 7")
      (is (< (js/Math.abs (- tile-w (/ (- 1400 (* 16 8)) 7))) 1e-9) "the gaps around and between 7 tiles are paid first"))
    (is (= 1 (:cols (layout/grid-geometry 150 900 0.5625))) "never fewer than one column")
    (is (= 8 (:cols (layout/grid-geometry 4000 900 0.5625))) "never more than eight")
    (is (= 3 (:cols (layout/grid-geometry 1400 900 0.5625 {:min-tile 400}))) "options override the defaults"))
  (testing "a row is the thumbnail (aspect × width), its label and a gap; PgUp/PgDn move the rows that fit (≥ 1)"
    (let [{:keys [tile-w row-h page-rows]} (layout/grid-geometry 1400 900 0.75)]
      (is (< (js/Math.abs (- row-h (+ (* tile-w 0.75) 22 16))) 1e-9))
      (is (= (js/Math.floor (/ 900 row-h)) page-rows)))
    (is (= 1 (:page-rows (layout/grid-geometry 1400 10 0.5625))) "a very short pane still pages by one row")
    (is (pos? (:row-h (layout/grid-geometry 1400 900 nil))) "an unknown aspect falls back to 16:9")))

(def ^:private slides-db {:ui {:slides {:path "/d/talk.pdf" :layout :slides :page 4}}})

(deftest opening-closing-and-picking
  (testing "Tab / o open the grid over the slides layout (forgetting a typed number) and close it again"
    (let [{db :db} (events/slides-overview-fx (assoc-in slides-db [:ui :slides :digits] "3") nil)]
      (is (true? (slides/overview? db)))
      (is (nil? (slides/digits db)))
      (let [{db' :db fx :fx} (events/slides-overview-fx db nil)]
        (is (false? (slides/overview? db')))
        (is (= [[:dom/focus :content]] fx) "closing gives the keyboard back to the slides"))))
  (testing "Go to slide… only ever opens"
    (let [open (assoc-in slides-db [:ui :slides :overview?] true)]
      (is (nil? (events/slides-overview-fx open {:open? true})))
      (is (true? (slides/overview? (:db (events/slides-overview-fx slides-db {:open? true})))))))
  (testing "no slides view → no grid"
    (is (nil? (events/slides-overview-fx {:ui {:slides {:path "/d/p.pdf" :layout :document}}} nil)))
    (is (nil? (events/slides-overview-fx {:ui {}} nil))))
  (testing "picking a tile closes the grid, JUMPS there (Back returns), and ends a blanked screen"
    (let [{:keys [db fx]} (events/slides-overview-pick-fx (-> slides-db (assoc-in [:ui :slides :overview?] true)
                                                              (assoc-in [:ui :slides :blank] :black)) 9)]
      (is (false? (slides/overview? db)))
      (is (nil? (slides/blank db)))
      (is (= [[:slides/command [:jump 9]] [:dom/focus :content]] fx))))
  (testing "the grid survives the slide show's position reports (it is UI-owned)"
    (is (true? (:overview? (slides/merge-report {:path "/d/talk.pdf" :layout :slides :overview? true}
                                                {:path "/d/talk.pdf" :layout :slides :page 5}))))))

(deftest escape-closes-the-grid-first
  (let [db (-> slides-db (assoc-in [:ui :slides :overview?] true) (assoc-in [:ui :slides :digits] "2")
               (assoc-in [:ui :presentation] {:active? true :path "/d/talk.pdf"}))]
    (is (= {:fx [[:dispatch [:slides/overview-close]]]} (input-events/escape-fx db))
        "the grid before the typed number, the blank screen and the show")))

(deftest the-representative-page-preference
  (is (= :last (slides/overview-page {:ui {}})) "fully built by default (pdfpc)")
  (is (= :first (slides/overview-page {:ui {:settings {:slides-overview-page :first}}})))
  (is (= :last (slides/overview-page {:ui {:settings {:slides-overview-page :bogus}}}))))

(deftest tab-and-o-open-the-grid-in-every-preset
  (doseq [preset [:default :vim :emacs]]
    (let [layer (get-in (keymap/preset preset) [:modes :slides])]
      (is (= :slides/overview (get layer "tab")) (str preset " Tab"))
      (is (= :slides/overview (get layer "o")) (str preset " o")))))
