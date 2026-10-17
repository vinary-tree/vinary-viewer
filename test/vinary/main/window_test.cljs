(ns vinary.main.window-test
  "Guards window.edn's writer (vinary.main.window). Every window debounces its OWN save: with one timer shared by
   every window, a second window's move or resize cancelled the first window's pending save, and when the second
   window's save wrote nothing — it was fullscreen, or a linked show's audience window — the first window's move was
   lost. An ephemeral window (the audience window, ADR-0048 §8.8) is never saved, and a close still flushes at once.
   Each test points XDG_CONFIG_HOME at a fresh temporary directory, so nothing here touches the user's config."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.reader :as reader]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [vinary.main.window :as window]))

(defn- fake-window
  "A stand-in for a BrowserWindow: `on` records the handlers `remember!` registers, and `fire!` runs one."
  [{:keys [bounds fullscreen? ephemeral?]}]
  (let [handlers (atom {})
        ^js win  #js {:on              (fn [ev f] (swap! handlers assoc ev f))
                      :isDestroyed     (fn [] false)
                      :isFullScreen    (fn [] (boolean fullscreen?))
                      :isMaximized     (fn [] false)
                      :maximize        (fn [] nil)
                      :getNormalBounds (fn [] (clj->js bounds))}]
    (when ephemeral? (set! (.-vvEphemeral win) true))
    {:win win :fire! (fn [ev] ((get @handlers ev) nil))}))

(defn- with-config-dir
  "Run (f dir finish) with XDG_CONFIG_HOME pointing at a fresh temporary directory; `finish` restores the
   environment, removes the directory and ends the async test."
  [done f]
  (let [env  (.-env js/process)
        prev (.-XDG_CONFIG_HOME env)
        dir  (.mkdtempSync fs (path/join (os/tmpdir) "vv-window-test-"))]
    (set! (.-XDG_CONFIG_HOME env) dir)
    (f dir (fn []
             (if (some? prev) (set! (.-XDG_CONFIG_HOME env) prev) (js-delete env "XDG_CONFIG_HOME"))
             (.rmSync fs dir #js {:recursive true :force true})
             (done)))))

(defn- saved
  "The bounds window.edn holds under the config dir `dir`, or nil when nothing was written."
  [dir]
  (let [p (path/join dir "vinary-viewer" "window.edn")]
    (when (.existsSync fs p) (reader/read-string (.readFileSync fs p "utf8")))))

(defn- wait-for
  "Call (k value) as soon as (pred) returns a truthy value, or (k nil) after `limit-ms`, checking every 10 ms."
  [pred limit-ms k]
  (let [deadline (+ (js/Date.now) limit-ms)]
    ((fn tick []
       (if-let [v (pred)]
         (k v)
         (if (< (js/Date.now) deadline) (js/setTimeout tick 10) (k nil)))))))

(deftest persistable-bounds-skips-an-ephemeral-window
  (let [normal {:x 10 :y 20 :width 1280 :height 860}]
    (is (= (assoc normal :maximized? false)
           (window/persistable-bounds {:fullscreen? false :maximized? false :ephemeral? false :normal normal})))
    (is (nil? (window/persistable-bounds {:fullscreen? false :maximized? false :ephemeral? true :normal normal}))
        "an audience window exists only for the show: its bounds must never become where the next window opens")))

(deftest a-second-window-does-not-cancel-the-first-windows-save
  ;; The regression: window A is moved; within the debounce window B — fullscreen (or an audience window) — fires
  ;; resize. With one shared timer B's resize cancelled A's save and B's own save wrote nothing, so A's move was
  ;; never written. The wait allows a full second, far beyond any delay, so the shared-timer code fails by
  ;; writing nothing at all, not by being slow.
  (async done
    (with-config-dir done
      (fn [dir finish]
        (let [a (fake-window {:bounds {:x 10 :y 20 :width 1000 :height 700}})
              b (fake-window {:bounds {:x 0 :y 0 :width 3840 :height 2160} :fullscreen? true})]
          (window/remember! (:win a) {:delay-ms 20})
          (window/remember! (:win b) {:delay-ms 20})
          ((:fire! a) "move")
          (js/setTimeout (fn [] ((:fire! b) "resize")) 5)
          (wait-for #(saved dir) 1000
                    (fn [m]
                      (is (= {:x 10 :y 20 :width 1000 :height 700 :maximized? false} m)
                          "window A's move is saved although window B resized within the debounce")
                      (finish))))))))

(deftest an-ephemeral-window-is-never-saved-and-close-flushes
  (async done
    (with-config-dir done
      (fn [dir finish]
        (let [audience (fake-window {:bounds {:x 1920 :y 0 :width 1920 :height 1080} :ephemeral? true})
              normal   (fake-window {:bounds {:x 30 :y 40 :width 900 :height 600}})]
          (window/remember! (:win audience) {:delay-ms 10})
          ((:fire! audience) "move")
          ((:fire! audience) "resize")
          ((:fire! audience) "close")
          (js/setTimeout
            (fn []
              (is (nil? (saved dir)) "the audience window wrote nothing — not when moved, resized or closed")
              (window/remember! (:win normal) {:delay-ms 60000})
              ((:fire! normal) "move")
              ((:fire! normal) "close")
              (is (= {:x 30 :y 40 :width 900 :height 600 :maximized? false} (saved dir))
                  "closing a window writes its bounds at once, without waiting for the debounce")
              (finish))
            60))))))
