(ns vinary.main.window
  "Persisted main-window geometry (~/.config/vinary-viewer/window.edn): position, size, and maximized
   state, so the window reopens where it was last. Handled entirely in MAIN (the renderer is not
   involved). A saved position that is no longer on any connected display is dropped so the window can't
   reopen off-screen; the normal (non-maximized) bounds are stored so un-maximizing restores the size.
   Every window debounces its own save, and a linked show's audience window is never saved (ADR-0048 §8.8)."
  (:require ["electron" :refer [screen]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            [cljs.reader :as reader]))

(def ^:private default-bounds {:width 1280 :height 860})

;; How long a window's moves and resizes settle before its bounds are written.
(def ^:private save-delay-ms 400)

;; Disabled: one save timer shared by every window let a second window's move or resize cancel the first window's
;; pending save. Each window now debounces with its own timer, held in `remember!`'s closure (ADR-0048 §8.8).
;; (defonce ^:private save-timer (atom nil))

(defn- conf-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))
(defn- window-path [] (path/join (conf-dir) "window.edn"))

(defn- read-bounds []
  (try
    (let [p (window-path)]
      (when (.existsSync fs p)
        (let [m (reader/read-string (.readFileSync fs p "utf8"))]
          (when (map? m) m))))
    (catch :default _ nil)))

(defn- write-bounds! [m]
  (try
    (when-not (.existsSync fs (conf-dir)) (.mkdirSync fs (conf-dir) (clj->js {:recursive true})))
    (.writeFileSync fs (window-path) (pr-str m))
    (catch :default _ nil)))

(defn- on-some-display?
  "Is the saved rect's top-left within (a small slack of) some connected display's work area?"
  [b]
  (boolean
    (when (and (number? (:x b)) (number? (:y b)))
      (some (fn [^js d]
              (let [wa (.-workArea d)]
                (and (>= (:x b) (- (.-x wa) 8))
                     (>= (:y b) (- (.-y wa) 8))
                     (<  (:x b) (+ (.-x wa) (.-width wa)))
                     (<  (:y b) (+ (.-y wa) (.-height wa))))))
            (.getAllDisplays screen)))))

(defn options
  "BrowserWindow geometry options from the saved window.edn — clamped to a visible display, falling back
   to defaults. (Maximized state is reapplied separately in `remember!`, after the window exists.)"
  []
  (let [saved (read-bounds)
        base  {:width  (or (:width saved)  (:width default-bounds))
               :height (or (:height saved) (:height default-bounds))}]
    (if (on-some-display? saved)
      (assoc base :x (:x saved) :y (:y saved))
      base)))

(defn persistable-bounds
  "PURE. What to save for a window snapshot {:fullscreen? :maximized? :ephemeral? :normal {:x :y :width :height}}:
   the normal bounds plus the maximized flag — or nil while the window is FULLSCREEN (a slide show, ADR-0045), and
   nil for an EPHEMERAL window (a linked show's audience window, ADR-0048 §8.8), which exists only for the show and
   must never become where the next window opens. The fullscreen geometry must never become the window's saved
   size: some platforms report it from getNormalBounds while fullscreen, and entering fullscreen itself fires
   resize. nil leaves the bounds saved before on disk."
  [{:keys [fullscreen? maximized? ephemeral? normal]}]
  (when-not (or fullscreen? ephemeral?)
    (assoc (select-keys normal [:x :y :width :height]) :maximized? (boolean maximized?))))

(defn remember!
  "Reapply the saved maximized state and persist the window's normal bounds + maximized flag on
   resize / move (debounced) and on close (flushed) — never while fullscreen, never for an ephemeral window
   (persistable-bounds). Each window debounces with its OWN timer: one timer shared by every window let a second
   window's move or resize cancel the first window's pending save, so a window moved just before an audience
   window opened or went fullscreen (whose own save writes nothing) lost its move. `win.vvEphemeral` is read when
   saving, not when wiring, because a warm-pool window is wired long before anyone knows its role.
   `opts` {:delay-ms} lets the tests settle quickly."
  ([win] (remember! win {}))
  ([^js win {:keys [delay-ms] :or {delay-ms save-delay-ms}}]
   (let [timer (atom nil)
         save (fn []
                (reset! timer nil)
                (when (and win (not (.isDestroyed win)))
                  (let [b (.getNormalBounds win)]
                    (when-let [m (persistable-bounds {:fullscreen? (.isFullScreen win)
                                                      :maximized?  (.isMaximized win)
                                                      :ephemeral?  (boolean (.-vvEphemeral win))
                                                      :normal      {:x (.-x b) :y (.-y b)
                                                                    :width (.-width b) :height (.-height b)}})]
                      (write-bounds! m)))))
         debounced (fn []
                     (when-let [t @timer] (js/clearTimeout t))
                     (reset! timer (js/setTimeout save delay-ms)))]
     (when (:maximized? (read-bounds)) (.maximize win))
     (.on win "resize" debounced)
     (.on win "move"   debounced)
     (.on win "close"  (fn [_]
                         (when-let [t @timer] (js/clearTimeout t))
                         (save))))))
