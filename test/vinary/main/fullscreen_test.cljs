(ns vinary.main.fullscreen-test
  "Swap screens' four steps in the shell (ADR-0048 §8.7; vinary.main.fullscreen/swap-displays!), on fake windows.
   No display server the tests can run gives Chromium two displays — Xvfb reports one, with RandR monitors set by
   `xrandr --setmonitor` and with two screens joined by Xinerama alike — so test/presenter-e2e.js covers the
   one-display show and this covers the swap: the order of the steps (a fullscreen window cannot be moved), the
   fullscreen and the owner each window gets back, one report per window and none during the swap, a window that
   cannot go fullscreen again, and a window closed part-way."
  (:require ["events" :as events]
            [cljs.test :refer [deftest is async]]
            [vinary.main.fullscreen :as fullscreen]))

(defn- fake-window
  "An EventEmitter standing in for a BrowserWindow named `label`. `log` records, in order, each setFullScreen, each
   setBounds (with whether the window was fullscreen when it was moved) and each message its webContents is sent.
   Entering or leaving fullscreen takes effect, and emits its event, a few milliseconds later, as on Linux.
   `refuse-enter?` is a window manager that will not make the window fullscreen again: no event, still windowed."
  [log label {:keys [fullscreen? owned? refuse-enter?]}]
  (let [^js w (events/EventEmitter.)
        state (atom {:fullscreen? (boolean fullscreen?) :destroyed? false})]
    (set! (.-vvFullscreenOwned w) (boolean owned?))
    (set! (.-isDestroyed w) (fn [] (:destroyed? @state)))
    (set! (.-isFullScreen w) (fn [] (:fullscreen? @state)))
    (set! (.-isMaximized w) (fn [] false))
    (set! (.-setFullScreen w)
          (fn [on]
            (swap! log conj [label :set-fullscreen on])
            (when-not (and on refuse-enter?)
              (js/setTimeout (fn []
                               (swap! state assoc :fullscreen? on)
                               (.emit w (if on "enter-full-screen" "leave-full-screen")))
                             5))))
    (set! (.-setBounds w)
          (fn [b] (swap! log conj [label :set-bounds (js->clj b :keywordize-keys true) (:fullscreen? @state)])))
    (set! (.-destroy w) (fn [] (swap! state assoc :destroyed? true)))
    (set! (.-webContents w)
          #js {:isDestroyed (fn [] false)
               :send        (fn [channel payload]
                              (swap! log conj [label :send channel (js->clj payload :keywordize-keys true)]))})
    w))

(defn- log-of [log label] (filterv #(= label (first %)) @log))

(def ^:private left-half  {:x 0 :y 0 :width 1280 :height 720})
(def ^:private right-half {:x 1920 :y 0 :width 1280 :height 720})

(deftest swap-screens-moves-each-window-and-gives-back-its-fullscreen
  (async done
    (let [log       (atom [])
          audience  (fake-window log :audience {:fullscreen? true :owned? true})     ; main made it fullscreen
          presenter (fake-window log :presenter {:fullscreen? true :owned? false})]  ; the user did, with F11
      (fullscreen/wire! audience)
      (fullscreen/wire! presenter)
      (-> (fullscreen/swap-displays! {:audience  {:win audience :bounds left-half}
                                      :presenter {:win presenter :bounds right-half}})
          (.then (fn [lost]
                   (is (= [] lost) "both windows are fullscreen again")
                   (is (= [[:audience :set-fullscreen false]
                           [:audience :set-bounds left-half false]
                           [:audience :set-fullscreen true]
                           [:audience :send "vv:fullscreen-changed" {:fullscreen true :owned true :swap true}]]
                          (log-of log :audience))
                       "the audience window leaves fullscreen, moves only once it has left, enters it again, and is
                        told once, at the end — still main's fullscreen")
                   (is (= [[:presenter :set-fullscreen false]
                           [:presenter :set-bounds right-half false]
                           [:presenter :set-fullscreen true]
                           [:presenter :send "vv:fullscreen-changed" {:fullscreen true :owned false :swap true}]]
                          (log-of log :presenter))
                       "the presenter window too — and its fullscreen is still the user's, so main never leaves it")
                   (is (every? (fn [^js w] (not (.-vvFullscreenSwapping w))) [audience presenter]) "neither is left marked")
                   (done)))
          (.catch (fn [e] (is false (str "the swap failed: " e)) (done)))))))

(deftest a-window-that-cannot-go-fullscreen-again-is-reported-lost
  (async done
    (let [log       (atom [])
          wait-ms   fullscreen/swap-wait-ms
          finish    (fn [] (set! fullscreen/swap-wait-ms wait-ms) (done))
          audience  (fake-window log :audience {:fullscreen? true :owned? true :refuse-enter? true})
          presenter (fake-window log :presenter {:fullscreen? true :owned? false})]
      (set! fullscreen/swap-wait-ms 40)            ; the refused window's wait ends at the timeout
      (fullscreen/wire! audience)
      (fullscreen/wire! presenter)
      (.once presenter "leave-full-screen" (fn [] (.destroy presenter)))   ; closed while the swap runs
      (-> (fullscreen/swap-displays! {:audience  {:win audience :bounds left-half}
                                      :presenter {:win presenter :bounds right-half}})
          (.then (fn [lost]
                   (is (= #{:audience :presenter} (set lost))
                       "lost: the window the window manager refused, and the window that closed")
                   (is (= [[:audience :set-fullscreen false]
                           [:audience :set-bounds left-half false]
                           [:audience :set-fullscreen true]
                           [:audience :send "vv:fullscreen-changed" {:fullscreen false :owned true :swap true}]]
                          (log-of log :audience))
                       "the refused window is told its real state once: windowed, so its show keeps running
                        windowed (fullscreen-changed-fx), and Shift+F asks again")
                   (is (= [[:presenter :set-fullscreen false]] (log-of log :presenter))
                       "the closed window is neither moved, nor made fullscreen, nor sent anything")
                   (finish)))
          (.catch (fn [e] (is false (str "the swap failed: " e)) (finish)))))))
