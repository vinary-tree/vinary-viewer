(ns vinary.main.web
  "In-app HTTP browsing. A second main-owned WebContentsView (sibling of the PDF view) loads http(s)
   pages and is positioned over the renderer's content area, so the app can follow remote links
   (e.g. citations) without being a full browser. Its own preload (resources/web-preload.js) extracts the
   page's heading outline and reports the active heading on scroll — so the Contents/TOC tab follows HTML
   sections just like Markdown — and scrolls to a heading on request. did-navigate is relayed so in-page
   link clicks update the active tab's URI + history."
  (:require ["electron" :refer [ipcMain WebContentsView Menu clipboard]]
            ["path" :as path]))

(defonce ^:private state  (atom {:view nil :win nil :url nil :app-command-win nil :snapshot nil :visible? false :owner-tab nil}))
(defonce ^:private inited (atom false))
(defonce ^:private last-history-nav (atom {:dir nil :time 0}))

(defn- web-preload [] (path/join js/__dirname ".." ".." "resources" "web-preload.js"))
(defn- app-wc    ^js []   (some-> ^js (:win @state) .-webContents))   ; ^js return: extern-safe interop under advanced (parity with shell/wc)

;; ---- pre-cached page snapshot ------------------------------------------------------------------
;; The native view always paints above the DOM, so an overlay is shown by hiding the view and painting this
;; raster in the DOM. We capture it PROACTIVELY (after load + after scroll, while the page is live + visible)
;; and push it to the app renderer, so opening a menu/dialog is a synchronous swap — no capture on the
;; critical path, no behind-then-front flash.
(defn- capture!
  "Capture the live, visible web page to a JPEG data-URL; cache it and push it to the app renderer."
  []
  (when-let [^js v (:view @state)]
    (when (and (:visible? @state) (.-webContents v))
      (-> (.capturePage ^js (.-webContents v))
          (.then (fn [^js img]
                   (let [^js buf (.toJPEG img 70)
                         data    (str "data:image/jpeg;base64," (.toString buf "base64"))]
                     (swap! state assoc :snapshot data)
                     (when-let [^js awc (app-wc)] (.send awc "vv:http-snapshot-ready" data)))))
          (.catch (fn [_] nil))))))

(defonce ^:private capture-timer (atom nil))
(defn- capture-soon!
  "Debounced capture! — coalesces scroll frames / repeated shows and gives the page a moment to paint."
  []
  (when-let [t @capture-timer] (js/clearTimeout t))
  (reset! capture-timer (js/setTimeout capture! 150)))

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

(def ^:private web-edit-keys
  ;; Ctrl/Cmd chords the web PAGE keeps (copy/paste/cut/select-all/undo) — never forwarded to the app keymap
  #{"c" "v" "x" "a" "z"})

(defn- web-app-chord
  "An input the web view should NOT swallow but forward to the app keymap resolver: a Ctrl/Cmd chord that is
   not a page editing/clipboard shortcut. Lets Ctrl+O / Ctrl+Shift+O / Ctrl+L / Ctrl+F … work from the web
   view (it is a separate native context, so its keys never reach the app's window keydown listener).
   Returns {:key :ctrl :shift :alt :meta} or nil."
  [^js input]
  (when (and (= "keyDown" (.-type input))
             (or (.-control input) (.-meta input))
             (not (.-isComposing input)))
    (let [key (.-key input)]
      (when (and key (not (contains? web-edit-keys (.toLowerCase key))))
        {:key   key
         :ctrl  (boolean (.-control input))
         :shift (boolean (.-shift input))
         :alt   (boolean (.-alt input))
         :meta  (boolean (.-meta input))}))))

(defn- attach-web-input! [^js wc]
  (.on wc "before-input-event"
       (fn [event input]
         (if-let [dir (input-history-dir input)]
           (do (.preventDefault event) (send-history-nav! dir))
           ;; forward app-global Ctrl/Cmd chords (the web view can't reach the app's keymap resolver itself)
           (when-let [chord (web-app-chord input)]
             (.preventDefault event)
             (when-let [^js awc (app-wc)] (.send awc "vv:web-key" (clj->js chord)))))))
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
            ;; navigation → record it onto the OWNER tab (the http tab that showed this view), not the active
            ;; tab — the user may have switched to another tab while the page was still loading.
            relay  (fn [_e url]
                     (swap! state assoc :url url)
                     (when-let [^js awc (app-wc)]
                       (.send awc "vv:http-navigated" (clj->js {:url url :tab (:owner-tab @state)}))))]
        (.addChildView ^js (.-contentView win) v)
        (.setVisible v false)
        (.on wc "did-navigate"          relay)
        (.on wc "did-navigate-in-page"  relay)
        (.on wc "did-stop-loading"      (fn [_] (capture-soon!)))   ; load done → refresh the frozen-page snapshot
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

(defn show! [^js win url bounds tab-id]
  (let [^js v (ensure-view! win)]
    (set-bounds! v bounds)
    (.setVisible v true)
    (swap! state assoc :visible? true :owner-tab tab-id)   ; remember which tab owns the view (for nav routing)
    (when (not= url (:url @state))
      (.loadURL ^js (.-webContents v) url)
      (swap! state assoc :url url :snapshot nil))   ; new page → invalidate the cached snapshot
    (capture-soon!)))                               ; keep the frozen-page snapshot fresh for the next overlay

(defn hide! [] (when-let [^js v (:view @state)] (.setVisible v false) (swap! state assoc :visible? false)))

(defn init! [^js win]
  ;; a recreated window invalidates the old view (it belonged to the closed window)
  (when (not= win (:win @state)) (swap! state assoc :view nil :url nil :snapshot nil :visible? false))
  (swap! state assoc :win win)
  (attach-app-command! win)
  (when-not @inited
    (reset! inited true)
    ;; ---- app renderer → web view ----
    (.on ipcMain "vv:http-show"
         (fn [_e ^js p] (let [m (js->clj p :keywordize-keys true)] (show! (:win @state) (:url m) (:bounds m) (:tabId m)))))
    (.on ipcMain "vv:http-hide"   (fn [_e] (hide!)))
    (.on ipcMain "vv:http-bounds"
         (fn [_e ^js p] (set-bounds! (:view @state) (:bounds (js->clj p :keywordize-keys true)))))
    ;; zoom the web PAGE (the native view's own webContents), not the app chrome; report the factor back
    (.on ipcMain "vv:http-zoom"
         (fn [_e dir]
           (when-let [^js v (:view @state)]
             (let [^js c (.-webContents v)
                   d (js->clj dir)
                   f (cond (= d 0)  1.0
                           (pos? d) (min 3.0 (+ (.getZoomFactor c) 0.1))
                           :else    (max 0.4 (- (.getZoomFactor c) 0.1)))]
               (.setZoomFactor c f)
               (when-let [^js awc (app-wc)] (.send awc "vv:zoom-changed" (clj->js {:context "web" :factor f})))))))
    (.on ipcMain "vv:http-zoom-set"
         (fn [_e f]
           (when-let [^js v (:view @state)]
             (let [^js c (.-webContents v)
                   nf (max 0.4 (min 3.0 (js->clj f)))]
               (.setZoomFactor c nf)
               (when-let [^js awc (app-wc)] (.send awc "vv:zoom-changed" (clj->js {:context "web" :factor nf})))))))
    ;; cold-cache fallback: return the latest cached snapshot (the proactive capture+push keeps it fresh);
    ;; capture on-demand only if nothing is cached yet and the page is live-visible.
    (.handle ipcMain "vv:http-snapshot"
             (fn [_e]
               (or (:snapshot @state)
                   (let [^js v (:view @state)]
                     (if (and v (:visible? @state) (.-webContents v))
                       (-> (.capturePage ^js (.-webContents v))
                           (.then (fn [^js img]
                                    (let [^js buf (.toJPEG img 70)]
                                      (str "data:image/jpeg;base64," (.toString buf "base64")))))
                           (.catch (fn [_] nil)))
                       (js/Promise.resolve nil))))))
    (.on ipcMain "vv:http-toc-goto"
         (fn [_e ^js id] (when-let [^js v (:view @state)] (.send (.-webContents v) "vv:web-scroll-to" id))))
    ;; ---- web view (its preload) → app renderer (relayed) ----
    (.on ipcMain "vv:web-toc"
         (fn [_e ^js headings] (when-let [^js awc (app-wc)] (.send awc "vv:web-toc" headings))))
    (.on ipcMain "vv:web-active-heading"
         (fn [_e ^js id]
           (when-let [^js awc (app-wc)] (.send awc "vv:web-active-heading" id))
           (capture-soon!)))))   ; page scrolled → refresh the frozen-page snapshot
