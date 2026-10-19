(ns vinary.main.displays
  "PURE. The displays of a linked slide show (ADR-0048 §8.7): which display a window is on, which display the
   audience window opens on, where on it, whether this session lets main place windows at all, and how Swap screens
   exchanges the two windows' displays. No Electron, so the node :test build checks every rule.

   Vocabulary:
     rectangle  {:x :y :width :height} in device-independent pixels (DIP) — the unit of Electron's window bounds
                and display bounds alike
     display    a keywordized entry of Electron's screen.getAllDisplays():
                  {:id 2528732444
                   :bounds   {:x 0 :y 0  :width 1920 :height 1080}   ; the whole display
                   :workArea {:x 0 :y 27 :width 1920 :height 1053}   ; minus the panels and docks
                   :internal false                                  ; true for a laptop's built-in panel
                   :scaleFactor 1}

   The shell (vinary.main.presenter) reads the displays and the windows' bounds, calls these functions, and
   applies their answers: it un-maximizes the claimed window, sets its bounds to the `placement`, shows it inactive,
   and runs a `swap-plan` in the four steps of ADR-0048 §8.7.")

;; ---- rectangles -----------------------------------------------------------------------------------------------

(defn- right [{:keys [x width]}] (+ x width))
(defn- bottom [{:keys [y height]}] (+ y height))
(defn- area [{:keys [width height]}] (* width height))

(defn intersection-area
  "The area of the overlap of rectangles `a` and `b`: 0 when they only touch or do not meet."
  [a b]
  (let [w (- (min (right a) (right b)) (max (:x a) (:x b)))
        h (- (min (bottom a) (bottom b)) (max (:y a) (:y b)))]
    (if (and (pos? w) (pos? h)) (* w h) 0)))

(defn- squared-distance-to
  "The squared distance from point (`px`, `py`) to rectangle `r`: 0 on or inside it."
  [r px py]
  (let [dx (max (- (:x r) px) 0 (- px (right r)))
        dy (max (- (:y r) py) 0 (- py (bottom r)))]
    (+ (* dx dx) (* dy dy))))

;; ---- which display ----------------------------------------------------------------------------------------------

(defn display-of
  "The display that holds most of rectangle `rect`: the one whose bounds overlap it the most, the lowest id on a
   tie. A rectangle that overlaps no display — a window dragged off every screen — belongs to the display nearest
   its centre, which is how Electron's screen.getDisplayMatching answers too. nil when there are no displays or
   there is no rectangle."
  [displays rect]
  (when (and (seq displays) rect)
    (let [overlaps (map (fn [d] [(intersection-area rect (:bounds d)) d]) displays)]
      (if (some (fn [[a _]] (pos? a)) overlaps)
        (second (first (sort-by (fn [[a d]] [(- a) (:id d)]) overlaps)))
        (let [cx (+ (:x rect) (/ (:width rect) 2))
              cy (+ (:y rect) (/ (:height rect) 2))]
          (first (sort-by (fn [d] [(squared-distance-to (:bounds d) cx cy) (:id d)]) displays)))))))

(defn audience-display
  "The display the audience window opens on, given the presenter window's bounds — or nil when there is only one
   display (the audience window then opens on the presenter's display, and is the window to share in a call).
   Preferences, in order: a display that is not the presenter's; then an external one (`:internal` not true —
   the projector is rarely the laptop's own panel); then the largest; then the lowest id, so the answer is the same
   on every run. A wrong guess is what Swap screens repairs (ADR-0048 §8.7)."
  [displays presenter-bounds]
  (when (>= (count displays) 2)
    (let [presenter-id (:id (display-of displays presenter-bounds))]
      (first (sort-by (fn [d] [(if (= (:id d) presenter-id) 1 0)
                               (if (true? (:internal d)) 1 0)
                               (- (area (:bounds d)))
                               (:id d)])
                      displays)))))

;; ---- where on it ------------------------------------------------------------------------------------------------

(defn- clamped-length
  "A window length `n` as a whole number of pixels in [1, `limit`]; `limit` itself when `n` is not a positive
   number."
  [n limit]
  (if (and (number? n) (pos? n))
    (-> n js/Math.round (max 1) (min limit))
    limit))

(defn placement
  "Bounds for a window of size `size` ({:width :height} — any rectangle will do) on `display`: centred in the
   display's work area, and no larger than it in either direction (a dimension that is missing, or not positive,
   takes the work area's). Offsets are rounded down to whole pixels, as BrowserWindow.setBounds needs."
  [display {:keys [width height]}]
  (let [{wx :x wy :y ww :width wh :height} (or (:workArea display) (:bounds display))
        w (clamped-length width ww)
        h (clamped-length height wh)]
    {:x      (+ wx (js/Math.floor (/ (- ww w) 2)))
     :y      (+ wy (js/Math.floor (/ (- wh h) 2)))
     :width  w
     :height h}))

(defn placement-supported?
  "May main place its own windows on this session? Not on a native Wayland session, where a client cannot position
   its windows: `wayland-session?` (vinary.main.startup's test of XDG_SESSION_TYPE / WAYLAND_DISPLAY) while the
   Ozone platform is not forced to X11 — `ozone` is the VV_OZONE value (nil when unset), and \"x11\" runs the app
   under XWayland, where placement works. Without placement the audience window opens where the compositor puts it
   (the link's display is \"manual\"), and Swap screens is not offered."
  [wayland-session? ozone]
  (not (and wayland-session? (not= "x11" ozone))))

(defn arrangement
  "The `display` field of the presenter's joined message (ADR-0048 §8.3): \"manual\" when main cannot place
   windows (placement-supported? is false), \"same\" when there is no other display (`target`, the
   audience-display, is nil), else \"separate\". The presenter asks for its own fullscreen only on \"separate\", and
   the link's fullscreen needs it too, so a show never covers the only display."
  [placement-supported? target]
  (cond
    (not placement-supported?) "manual"
    (nil? target)              "same"
    :else                      "separate"))

(defn audience-plan
  "Where a new linked show's audience window goes, given the displays, the presenter window's bounds and whether
   main may place windows (`placement-supported?`): {:display arrangement :target id :bounds rect}. With placement
   and another display, the window takes the presenter window's size, `placement`d on the audience-display, whose
   id is :target. Otherwise :target and :bounds are nil, and the window opens where it would: beside the presenter
   with one display (\"same\"), where the compositor puts it on native Wayland (\"manual\")."
  [displays presenter-bounds placement-supported?]
  (let [target (when placement-supported? (audience-display displays presenter-bounds))]
    {:display (arrangement placement-supported? target)
     :target  (:id target)
     :bounds  (when target (placement target presenter-bounds))}))

(defn on-removed-display?
  "Was a window on display `removed` (the display a display-removed event names), so that removing it ends the show
   (ADR-0048 §8.6)? Yes when `removed` is the display main placed the window on (`placed-id`; nil when main placed
   nothing), or the display its bounds `rect` overlap most, counting `removed` among the `remaining` displays. The
   first covers a fullscreen window the system has already moved onto another display — over the presenter, which
   is what ending the show prevents; the second, a window the user moved there by hand."
  [removed remaining rect placed-id]
  (boolean (and removed
                (or (= placed-id (:id removed))
                    (= (:id removed) (:id (display-of (conj (vec remaining) removed) rect)))))))

;; ---- swap screens -----------------------------------------------------------------------------------------------

(defn swap-plan
  "Swap screens (ADR-0048 §8.7, L9): `windows` is a map of the session's two windows, keyed by anything the caller
   likes (their roles, or their ids), each {:bounds rectangle :fullscreen? bool}. The plan moves each window to the
   other window's display, keeping its own size (clamped to the target's work area, see `placement`) and its own
   fullscreen:

     {key {:display id-of-its-new-display :bounds placement-there :fullscreen? restore-fullscreen-there?} …}

   nil when there is nothing to swap: not exactly two windows, a window on no display (no displays at all), or both
   windows on one display (the user moved one by hand) — main then answers swapped: false, why: \"same-display\"."
  [windows displays]
  (when (= 2 (count windows))
    (let [[[ka a] [kb b]] (seq windows)
          da (display-of displays (:bounds a))
          db (display-of displays (:bounds b))]
      (when (and da db (not= (:id da) (:id db)))
        {ka {:display (:id db) :bounds (placement db (:bounds a)) :fullscreen? (boolean (:fullscreen? a))}
         kb {:display (:id da) :bounds (placement da (:bounds b)) :fullscreen? (boolean (:fullscreen? b))}}))))
