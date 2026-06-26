(ns vinary.main.pdf
  "Native PDF preview. A main-owned WebContentsView loads file://…pdf in Chromium's built-in PDF viewer
   and is positioned over the renderer's content
   area. The renderer sends show/hide + bounds over the IPC seam; live-refresh reloads it on change."
  (:require ["electron" :refer [ipcMain WebContentsView]]))

(defonce ^:private state (atom {:view nil :win nil :path nil}))
(defonce ^:private inited (atom false))

(defn- ensure-view! [^js win]
  (or (:view @state)
      (let [^js v (WebContentsView. (clj->js {:webPreferences {:plugins true}}))]
        (.addChildView ^js (.-contentView win) v)
        (.setVisible v false)
        (swap! state assoc :view v :win win)
        v)))

(defn- set-bounds! [^js v bounds]
  (when (and v bounds)
    (.setBounds v (clj->js {:x      (js/Math.round (:x bounds))
                            :y      (js/Math.round (:y bounds))
                            :width  (js/Math.round (:width bounds))
                            :height (js/Math.round (:height bounds))}))))

(defn show! [^js win path bounds]
  (let [^js v (ensure-view! win)]
    (set-bounds! v bounds)
    (.setVisible v true)
    (when (not= path (:path @state))
      (.loadURL ^js (.-webContents v) (str "file://" path))
      (swap! state assoc :path path))))

(defn hide! []
  (when-let [^js v (:view @state)] (.setVisible v false)))

(defn reload!
  "Reload the view if path is the one currently shown (called on a watched-PDF change → live-refresh)."
  [path]
  (when-let [^js v (:view @state)]
    (when (= path (:path @state)) (.reload ^js (.-webContents v)))))

(defn init! [^js win]
  ;; a recreated window invalidates the old view (it belonged to the closed window)
  (when (not= win (:win @state)) (swap! state assoc :view nil :path nil))
  (swap! state assoc :win win)
  (when-not @inited
    (reset! inited true)
    (.on ipcMain "vv:pdf-show"
         (fn [_e ^js payload]
           (let [p (js->clj payload :keywordize-keys true)] (show! (:win @state) (:path p) (:bounds p)))))
    (.on ipcMain "vv:pdf-hide" (fn [_e] (hide!)))
    (.on ipcMain "vv:pdf-bounds"
         (fn [_e ^js payload]
           (set-bounds! (:view @state) (:bounds (js->clj payload :keywordize-keys true)))))))
