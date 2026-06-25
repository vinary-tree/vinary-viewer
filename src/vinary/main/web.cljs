(ns vinary.main.web
  "In-app HTTP browsing. A second main-owned WebContentsView (sibling of the PDF view) loads http(s)
   pages and is positioned over the renderer's content area, so the app can follow remote links
   (e.g. citations) without being a full browser. Its own preload (resources/web-preload.js) extracts the
   page's heading outline and reports the active heading on scroll — so the Contents/TOC tab follows HTML
   sections just like Markdown — and scrolls to a heading on request. did-navigate is relayed so in-page
   link clicks update the active tab's URI + history."
  (:require ["electron" :refer [ipcMain WebContentsView]]
            ["path" :as path]))

(defonce ^:private state  (atom {:view nil :win nil :url nil}))
(defonce ^:private inited (atom false))

(defn- web-preload [] (path/join js/__dirname ".." ".." "resources" "web-preload.js"))
(defn- app-wc    []   (some-> ^js (:win @state) .-webContents))

(defn- ensure-view! [^js win]
  (or (:view @state)
      (let [^js v  (WebContentsView.
                     (clj->js {:webPreferences {:contextIsolation true
                                                :nodeIntegration  false
                                                :preload          (web-preload)}}))
            ^js wc (.-webContents v)
            ;; in-page navigation (a link clicked on the remote page) → track the url + sync the active tab
            relay  (fn [_e url]
                     (swap! state assoc :url url)
                     (when-let [^js awc (app-wc)] (.send awc "vv:http-navigated" (clj->js {:url url}))))]
        (.addChildView ^js (.-contentView win) v)
        (.setVisible v false)
        (.on wc "did-navigate"          relay)
        (.on wc "did-navigate-in-page"  relay)
        (swap! state assoc :view v :win win)
        v)))

(defn- set-bounds! [^js v bounds]
  (when (and v bounds)
    (.setBounds v (clj->js {:x      (js/Math.round (:x bounds))
                            :y      (js/Math.round (:y bounds))
                            :width  (js/Math.round (:width bounds))
                            :height (js/Math.round (:height bounds))}))))

(defn show! [^js win url bounds]
  (let [^js v (ensure-view! win)]
    (set-bounds! v bounds)
    (.setVisible v true)
    (when (not= url (:url @state))
      (.loadURL ^js (.-webContents v) url)
      (swap! state assoc :url url))))

(defn hide! [] (when-let [^js v (:view @state)] (.setVisible v false)))

(defn init! [^js win]
  ;; a recreated window invalidates the old view (it belonged to the closed window)
  (when (not= win (:win @state)) (swap! state assoc :view nil :url nil))
  (swap! state assoc :win win)
  (when-not @inited
    (reset! inited true)
    ;; ---- app renderer → web view ----
    (.on ipcMain "vv:http-show"
         (fn [_e ^js p] (let [m (js->clj p :keywordize-keys true)] (show! (:win @state) (:url m) (:bounds m)))))
    (.on ipcMain "vv:http-hide"   (fn [_e] (hide!)))
    (.on ipcMain "vv:http-bounds"
         (fn [_e ^js p] (set-bounds! (:view @state) (:bounds (js->clj p :keywordize-keys true)))))
    (.on ipcMain "vv:http-toc-goto"
         (fn [_e ^js id] (when-let [^js v (:view @state)] (.send (.-webContents v) "vv:web-scroll-to" id))))
    ;; ---- web view (its preload) → app renderer (relayed) ----
    (.on ipcMain "vv:web-toc"
         (fn [_e ^js headings] (when-let [^js awc (app-wc)] (.send awc "vv:web-toc" headings))))
    (.on ipcMain "vv:web-active-heading"
         (fn [_e ^js id] (when-let [^js awc (app-wc)] (.send awc "vv:web-active-heading" id))))))
