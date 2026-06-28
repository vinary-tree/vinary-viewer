(ns vinary.main.web
  "In-app HTTP browsing. A second main-owned WebContentsView (sibling of the PDF view) loads http(s)
   pages and is positioned over the renderer's content area, so the app can follow remote links
   (e.g. citations) without being a full browser. Its own preload (resources/web-preload.js) extracts the
   page's heading outline and reports the active heading on scroll — so the Contents/TOC tab follows HTML
   sections just like Markdown — and scrolls to a heading on request. did-navigate is relayed so in-page
   link clicks update the active tab's URI + history."
  (:require ["electron" :refer [ipcMain WebContentsView Menu clipboard]]
            ["path" :as path]))

(defonce ^:private state  (atom {:view nil :win nil :url nil :app-command-win nil}))
(defonce ^:private inited (atom false))
(defonce ^:private last-history-nav (atom {:dir nil :time 0}))

(defn- web-preload [] (path/join js/__dirname ".." ".." "resources" "web-preload.js"))
(defn- app-wc    []   (some-> ^js (:win @state) .-webContents))

(defn- send-history-nav! [dir]
  (let [now (.now js/Date)
        {:keys [time]} @last-history-nav
        last-dir (:dir @last-history-nav)]
    ;; App-command and low-level mouse/input hooks can both fire for one physical gesture.
    (when (or (not= dir last-dir) (> (- now time) 180))
      (reset! last-history-nav {:dir dir :time now})
      (when-let [^js awc (app-wc)]
        (.send awc "vv:history-nav" dir)))))

(defn- input-history-dir [^js input]
  (when (and (= "keyDown" (.-type input))
             (.-alt input)
             (not (.-control input))
             (not (.-meta input))
             (not (.-isComposing input)))
    (case (.-key input)
      ("ArrowLeft" "Left")   "back"
      ("ArrowRight" "Right") "forward"
      nil)))

(defn- mouse-history-dir [^js mouse]
  (when (= "mouseDown" (.-type mouse))
    (case (.-button mouse)
      ("back" 3)    "back"
      ("forward" 4) "forward"
      nil)))

(defn- attach-web-input! [^js wc]
  (.on wc "before-input-event"
       (fn [event input]
         (when-let [dir (input-history-dir input)]
           (.preventDefault event)
           (send-history-nav! dir))))
  (.on wc "before-mouse-event"
       (fn [event mouse]
         (when-let [dir (mouse-history-dir mouse)]
           (.preventDefault event)
           (send-history-nav! dir)))))

(defn- attach-app-command! [^js win]
  (when (and win (not= win (:app-command-win @state)))
    (swap! state assoc :app-command-win win)
    (.on win "app-command"
         (fn [event cmd]
           (case cmd
             "browser-backward" (do (.preventDefault event) (send-history-nav! "back"))
             "browser-forward"  (do (.preventDefault event) (send-history-nav! "forward"))
             nil)))))

(defn- ensure-view! [^js win]
  (or (:view @state)
      (let [^js v  (WebContentsView.
                     (clj->js {:webPreferences {:contextIsolation true
                                                :nodeIntegration  false
                                                :preload          (web-preload)
                                                ;; a persistent, dedicated session so cookies/logins for
                                                ;; documentation sites survive restarts and stay isolated
                                                ;; from the app's own session (and host browser extensions)
                                                :partition        "persist:vinary-web"}}))
            ^js wc (.-webContents v)
            ;; in-page navigation (a link clicked on the remote page) → track the url + sync the active tab
            relay  (fn [_e url]
                     (swap! state assoc :url url)
                     (when-let [^js awc (app-wc)] (.send awc "vv:http-navigated" (clj->js {:url url}))))]
        (.addChildView ^js (.-contentView win) v)
        (.setVisible v false)
        (.on wc "did-navigate"          relay)
        (.on wc "did-navigate-in-page"  relay)
        (attach-web-input! wc)
        ;; right-click → a Copy context menu (the native view paints over the DOM, so this is a native
        ;; Electron menu rather than the renderer's themed menu; same affordance as the markdown preview).
        (.on wc "context-menu"
             (fn [_e ^js params]
               (let [sel  (.-selectionText params)
                     link (.-linkURL params)
                     items (into-array
                            (concat
                             (when (seq sel)  [#js {:role "copy" :label "Copy"}])
                             (when (seq link) [#js {:label "Copy Link Address"
                                                    :click #(.writeText clipboard (str link))}])
                             [#js {:type "separator"} #js {:role "selectAll" :label "Select All"}]))]
                 (.popup (.buildFromTemplate Menu items)))))
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
  (attach-app-command! win)
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
