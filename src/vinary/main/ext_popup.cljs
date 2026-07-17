(ns vinary.main.ext-popup
  "Browser-action popup host: a main-owned WebContentsView (sibling of the web view) that loads an
   extension's chrome-extension://<id>/<popup> on the web session and is positioned just below the
   clicked toolbar icon. The popup's own chrome.* APIs work natively (Electron 42 — no preload needed);
   main measures the content via executeJavaScript and resizes (Chrome-style content sizing). Navigation
   is locked to the extension's own origin; external links open in the OS browser. Dismissed on blur."
  (:require ["electron" :refer [WebContentsView shell]]
            [vinary.main.ext-util :as eu]))

;; :win  = default host window (set at init!, a fallback)
;; :host = the window the CURRENTLY-open popup is attached to (set per open!), so close! detaches from the same
;;         window even after focus moved elsewhere — the popup follows the toolbar that opened it (multi-window).
(defonce ^:private state (atom {:view nil :win nil :host nil}))

(defn init! [^js win] (swap! state assoc :win win))

(defn close! []
  (when-let [^js v (:view @state)]
    (let [^js win (or (:host @state) (:win @state))]
      (.setVisible v false)
      (try (when (and win (not (.isDestroyed win))) (.removeChildView ^js (.-contentView win) v)) (catch :default _ nil))
      (try (.close (.-webContents v)) (catch :default _ nil)))
    (swap! state assoc :view nil :host nil)))

(defn open!
  "Open extension `id`'s `popup` (a path relative to the extension root) anchored below `bounds` (the clicked
   toolbar icon's {:x :y :width :height}) in `host` — the window whose toolbar was clicked (falls back to the
   init! window)."
  [^js host ^js sess id popup bounds]
  (close!)
  (when (and sess id (seq (str popup)))
    (let [^js win (or host (:win @state))
          base    (str "chrome-extension://" id "/")
          url     (str base popup)
          v       (WebContentsView. (clj->js {:webPreferences {:partition "persist:vinary-web"
                                                               :contextIsolation true :nodeIntegration false}}))
          ^js wc  (.-webContents v)
          place!  (fn [pw ph]
                    (let [^js wb (.getBounds win)]
                      (.setBounds v (clj->js (eu/anchor->bounds bounds (.-width wb) (.-height wb) [pw ph])))))]
      ;; navigation lock-down: keep the popup on its own origin; external links → OS browser
      (.on wc "will-navigate" (fn [^js e nav-url] (when-not (.startsWith (str nav-url) base) (.preventDefault e))))
      (.setWindowOpenHandler wc (fn [^js d]
                                  (when-let [u (.-url d)] (when (re-find #"^https?:" u) (.openExternal shell u)))
                                  #js {:action "deny"}))
      (.on wc "blur" (fn [_] (close!)))
      ;; measure the popup content (main can eval in the popup's main world) → resize Chrome-style
      (.on wc "did-finish-load"
           (fn []
             (-> (.executeJavaScript wc "[document.documentElement.scrollWidth||360, document.documentElement.scrollHeight||480]" true)
                 (.then (fn [^js wh] (let [[pw ph] (eu/clamp-popup-size (aget wh 0) (aget wh 1))] (place! pw ph))))
                 (.catch (fn [_] nil)))))
      (.addChildView ^js (.-contentView win) v)
      (let [[pw ph] (eu/clamp-popup-size 360 480)] (place! pw ph))   ; initial default until measured
      (.setVisible v true)
      (.loadURL wc url)
      (.focus wc)
      (swap! state assoc :view v :host win))))
