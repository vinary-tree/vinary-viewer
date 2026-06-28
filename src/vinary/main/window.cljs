(ns vinary.main.window
  "Persisted main-window geometry (~/.config/vinary-viewer/window.edn): position, size, and maximized
   state, so the window reopens where it was last. Handled entirely in MAIN (the renderer is not
   involved). A saved position that is no longer on any connected display is dropped so the window can't
   reopen off-screen; the normal (non-maximized) bounds are stored so un-maximizing restores the size."
  (:require ["electron" :refer [screen]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            [cljs.reader :as reader]))

(def ^:private default-bounds {:width 1280 :height 860})

(defonce ^:private save-timer (atom nil))

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

(defn remember!
  "Reapply the saved maximized state and persist the window's normal bounds + maximized flag on
   resize / move (debounced) and on close (flushed)."
  [^js win]
  (let [save (fn []
               (when (and win (not (.isDestroyed win)))
                 (let [b (.getNormalBounds win)]
                   (write-bounds! {:x (.-x b) :y (.-y b) :width (.-width b) :height (.-height b)
                                   :maximized? (.isMaximized win)}))))
        debounced (fn []
                    (when-let [t @save-timer] (js/clearTimeout t))
                    (reset! save-timer (js/setTimeout save 400)))]
    (when (:maximized? (read-bounds)) (.maximize win))
    (.on win "resize" debounced)
    (.on win "move"   debounced)
    (.on win "close"  (fn [_] (save)))))
