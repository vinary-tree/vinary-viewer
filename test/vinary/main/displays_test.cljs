(ns vinary.main.displays-test
  "The linked show's displays (ADR-0048 §8.7): which display holds a window, which display the audience window opens
   on (and every tie-break of that choice), where on it, whether a session lets main place windows, the presenter's
   `display` arrangement, and the Swap screens plan — including the same-display case, which has no plan."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.main.displays :as displays]))

(defn- display
  "A display shaped like a keywordized screen.getAllDisplays() entry; the work area is the whole display unless
   `work` says otherwise."
  [id x y w h & {:keys [internal work]}]
  {:id          id
   :bounds      {:x x :y y :width w :height h}
   :workArea    (or work {:x x :y y :width w :height h})
   :internal    internal
   :scaleFactor 1})

;; A laptop with a top panel, a projector to its right, and a large monitor to its left.
(def ^:private laptop    (display 1 0 0 1920 1200 :internal true :work {:x 0 :y 27 :width 1920 :height 1173}))
(def ^:private projector (display 2 1920 0 1280 720 :internal false))
(def ^:private monitor   (display 3 -2560 0 2560 1440 :internal false))

(defn- rect [x y w h] {:x x :y y :width w :height h})

(deftest intersection-areas
  (is (= 100 (displays/intersection-area (rect 0 0 10 10) (rect 0 0 20 20))) "contained")
  (is (= 25 (displays/intersection-area (rect 0 0 10 10) (rect 5 5 10 10))) "overlapping corners")
  (is (= 0 (displays/intersection-area (rect 0 0 10 10) (rect 10 0 10 10))) "sharing an edge only")
  (is (= 0 (displays/intersection-area (rect 0 0 10 10) (rect 50 50 10 10))) "apart"))

(deftest the-display-holding-most-of-a-window
  (let [all [laptop projector monitor]]
    (is (= 1 (:id (displays/display-of all (rect 100 100 800 600)))) "inside the laptop")
    (is (= 2 (:id (displays/display-of all (rect 1800 100 800 600)))) "straddling, mostly on the projector")
    (is (= 3 (:id (displays/display-of all (rect -400 100 600 600)))) "straddling, mostly on the monitor")
    (testing "an exact tie goes to the lowest id"
      (is (= 1 (:id (displays/display-of all (rect 1820 100 200 200)))) "100 px on each side of x = 1920")
      (is (= 1 (:id (displays/display-of [projector laptop] (rect 1820 100 200 200)))) "whatever the list order"))
    (testing "a window on no display belongs to the nearest one"
      (is (= 2 (:id (displays/display-of all (rect 3300 100 100 100)))) "right of the projector")
      (is (= 3 (:id (displays/display-of all (rect -3000 2000 100 100)))) "below and left of the monitor")
      (is (= 2 (:id (displays/display-of all (rect 2000 300 0 0)))) "a point is held by the display under it"))
    (is (nil? (displays/display-of [] (rect 0 0 10 10))) "no displays")
    (is (nil? (displays/display-of all nil)) "no rectangle")))

(deftest the-audience-display
  (let [on-laptop    (rect 100 100 800 600)
        on-projector (rect 2000 100 400 300)
        on-monitor   (rect -2000 100 800 600)]
    (testing "one display, or none: nil (the audience window opens on the presenter's display)"
      (is (nil? (displays/audience-display [laptop] on-laptop)))
      (is (nil? (displays/audience-display [] on-laptop))))
    (testing "first: a display that is not the presenter's — even an internal one"
      (is (= 2 (:id (displays/audience-display [laptop projector] on-laptop))))
      (is (= 1 (:id (displays/audience-display [laptop projector] on-projector)))))
    (testing "then an external display"
      (is (= 2 (:id (displays/audience-display [laptop projector monitor] on-monitor)))
          "the projector, not the larger laptop panel"))
    (testing "then the largest"
      (is (= 3 (:id (displays/audience-display [laptop projector monitor] on-laptop)))
          "the monitor (2560 × 1440), not the projector (1280 × 720)"))
    (testing "then the lowest id"
      (let [twin-a (display 7 1920 0 1280 720 :internal false)
            twin-b (display 5 3200 0 1280 720 :internal false)]
        (is (= 5 (:id (displays/audience-display [laptop twin-a twin-b] on-laptop))))
        (is (= 5 (:id (displays/audience-display [twin-b laptop twin-a] on-laptop))) "whatever the list order")))
    (testing "only `:internal true` is internal: an absent flag counts as external"
      (let [unknown (display 9 3200 0 800 600)]
        (is (= 9 (:id (displays/audience-display [laptop (assoc projector :internal true) unknown] on-laptop)))
            "the small unknown display beats a larger internal one")))
    (testing "all the other displays internal: the largest of them"
      (let [panel-a (display 4 1920 0 1280 720 :internal true)
            panel-b (display 6 3200 0 1920 1080 :internal true)]
        (is (= 6 (:id (displays/audience-display [laptop panel-a panel-b] on-laptop))))))
    (testing "without the presenter's bounds every display is a candidate"
      (is (= 3 (:id (displays/audience-display [laptop projector monitor] nil)))))))

(deftest a-window-is-placed-centred-in-the-work-area
  (is (= {:x 2160 :y 60 :width 800 :height 600} (displays/placement projector {:width 800 :height 600})))
  (testing "the work area, not the whole display: the laptop's top panel is 27 px tall"
    (is (= {:x 560 :y 313 :width 800 :height 600} (displays/placement laptop {:width 800 :height 600}))))
  (testing "a window larger than the work area is clamped to it"
    (is (= {:x 1920 :y 0 :width 1280 :height 720} (displays/placement projector {:width 4000 :height 3000})))
    (is (= {:x 2160 :y 0 :width 800 :height 720} (displays/placement projector {:width 800 :height 3000}))))
  (testing "an odd remainder is rounded down to a whole pixel"
    (is (= {:x 2159 :y 59 :width 801 :height 601} (displays/placement projector {:width 801 :height 601}))))
  (testing "a fractional size is rounded to whole pixels"
    (is (= {:x 2160 :y 60 :width 800 :height 600} (displays/placement projector {:width 799.6 :height 600.2}))))
  (testing "a missing or non-positive dimension takes the work area's"
    (is (= {:x 1920 :y 0 :width 1280 :height 720} (displays/placement projector {})))
    (is (= {:x 2160 :y 0 :width 800 :height 720} (displays/placement projector {:width 800 :height 0})))
    (is (= {:x 1920 :y 60 :width 1280 :height 600} (displays/placement projector {:width -5 :height 600}))))
  (testing "a display without a work area is placed in its bounds"
    (is (= {:x 2160 :y 60 :width 800 :height 600}
           (displays/placement (dissoc projector :workArea) {:width 800 :height 600})))))

(deftest placement-needs-a-session-that-lets-main-place-windows
  (is (true? (displays/placement-supported? false nil)) "X11")
  (is (true? (displays/placement-supported? false "wayland")) "VV_OZONE does not matter off Wayland")
  (is (false? (displays/placement-supported? true nil)) "native Wayland")
  (is (false? (displays/placement-supported? true "wayland")))
  (is (false? (displays/placement-supported? true "auto")) "auto picks Wayland on a Wayland session")
  (is (true? (displays/placement-supported? true "x11")) "XWayland"))

(deftest the-presenter-is-told-how-the-audience-window-was-placed
  (is (= "manual" (displays/arrangement false projector)) "Wayland: wherever the compositor put it")
  (is (= "manual" (displays/arrangement false nil)))
  (is (= "same" (displays/arrangement true nil)) "one display")
  (is (= "separate" (displays/arrangement true projector))))

(deftest swap-screens-moves-each-window-to-the-other-display
  (let [presenter {:bounds (rect 300 200 1280 800) :fullscreen? false}
        audience  {:bounds (:bounds projector) :fullscreen? true}]
    (is (= {:presenter {:display 2 :bounds {:x 1920 :y 0 :width 1280 :height 720} :fullscreen? false}
            :audience  {:display 1 :bounds {:x 320 :y 253 :width 1280 :height 720} :fullscreen? true}}
           (displays/swap-plan {:presenter presenter :audience audience} [laptop projector]))
        "each keeps its own size (clamped to the target's work area) and its own fullscreen")
    (testing "the windows may be keyed by anything — here their ids"
      (is (= #{101 202} (set (keys (displays/swap-plan {101 presenter 202 audience} [laptop projector]))))))
    (testing "with three displays only the two windows' displays are exchanged"
      (let [plan (displays/swap-plan {:presenter {:bounds (rect -2000 100 800 600)} :audience audience}
                                     [laptop projector monitor])]
        (is (= 2 (get-in plan [:presenter :display])))
        (is (= 3 (get-in plan [:audience :display])))
        (is (= {:x -1920 :y 360 :width 1280 :height 720} (get-in plan [:audience :bounds])))
        (is (false? (get-in plan [:presenter :fullscreen?])) "an absent flag is not fullscreen")))))

(deftest nothing-to-swap
  (let [presenter {:bounds (rect 300 200 1280 800) :fullscreen? false}]
    (is (nil? (displays/swap-plan {:presenter presenter :audience {:bounds (rect 900 300 640 480)}}
                                  [laptop projector]))
        "both windows on one display: the user moved one by hand")
    (is (nil? (displays/swap-plan {:presenter presenter :audience {:bounds (rect 900 300 640 480)}} [laptop]))
        "a single display")
    (is (nil? (displays/swap-plan {:presenter presenter} [laptop projector])) "one window")
    (is (nil? (displays/swap-plan {:presenter presenter :audience {:bounds (:bounds projector)}} []))
        "no displays")))
