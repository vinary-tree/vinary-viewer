(ns vinary.main.web
  "In-app HTTP browsing. A second main-owned WebContentsView (sibling of the PDF view) loads http(s)
   pages and is positioned over the renderer's content area, so the app can follow remote links
   (e.g. citations) without being a full browser. Its own preload (resources/web-preload.js) extracts the
   page's heading outline and reports the active heading on scroll — so the Contents/TOC tab follows HTML
   sections just like Markdown — and scrolls to a heading on request. did-navigate is relayed so in-page
   link clicks update the active tab's URI + history."
  (:require ["electron" :refer [ipcMain WebContentsView Menu clipboard session net]]
            ["path" :as path]
            ["./ssh_transport.js" :as ssh-transport]))

(defonce ^:private state  (atom {:view nil :win nil :url nil :app-command-win nil :snapshot nil :visible? false :owner-tab nil}))
(defonce ^:private inited (atom false))
(defonce ^:private last-history-nav (atom {:dir nil :time 0}))

;; a PDF link clicked in the web view must open in the app's own pdf.js viewer, not Chromium's pdfium plugin —
;; ensure-view! wires the interception before these are defined (below, after mime-for), so forward-declare them.
(declare pdf-url? route-pdf!)

(defn- web-preload [] (path/join js/__dirname ".." ".." "resources" "web-preload.js"))
(defn- app-wc    ^js []   (some-> ^js (:win @state) .-webContents))   ; ^js return: extern-safe interop under advanced (parity with shell/wc)

(defn app-webcontents ^js [] (app-wc))

(defn active-webcontents ^js []
  (some-> ^js (:view @state) .-webContents))

(defn active-webcontents? [^js wc]
  (identical? wc (active-webcontents)))

(defn active-url [] (:url @state))

(defn fill-password!
  "Send revealed credentials directly to the isolated web-view preload. The renderer only ever receives
   non-secret item metadata; username/password values enter the web page through this main-owned path."
  [payload]
  (when-let [^js wc (active-webcontents)]
    (.send wc "vv:password-fill" (clj->js payload))))

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
        ;; a PDF LINK opens in the app's own pdf.js viewer, not Chromium's inline pdfium plugin. Catch a normal
        ;; link navigation (will-navigate) and a target=_blank / Ctrl-click (setWindowOpenHandler); a forced
        ;; download (Content-Disposition: attachment) is caught by the session-level will-download in init!.
        (.on wc "will-navigate"
             (fn [^js e url]
               (when (pdf-url? url)
                 (.preventDefault e)
                 (route-pdf! url (:owner-tab @state)))))
        (.setWindowOpenHandler wc
             (fn [^js details]
               (if (pdf-url? (.-url details))
                 (do (route-pdf! (.-url details) (:owner-tab @state)) #js {:action "deny"})
                 #js {:action "allow"})))
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

(def ^:private mime-types
  {".html" "text/html" ".htm" "text/html" ".xhtml" "application/xhtml+xml"
   ".css" "text/css" ".js" "text/javascript" ".mjs" "text/javascript" ".json" "application/json"
   ".svg" "image/svg+xml" ".png" "image/png" ".jpg" "image/jpeg" ".jpeg" "image/jpeg" ".gif" "image/gif"
   ".webp" "image/webp" ".avif" "image/avif" ".ico" "image/x-icon" ".bmp" "image/bmp"
   ".woff" "font/woff" ".woff2" "font/woff2" ".ttf" "font/ttf" ".otf" "font/otf"
   ".pdf" "application/pdf" ".txt" "text/plain" ".xml" "application/xml" ".webmanifest" "application/manifest+json"})

(defn- mime-for [uri]
  (let [ext (some-> (re-find #"(\.[^./?#]+)(?:[?#].*)?$" (str uri)) second .toLowerCase)]
    (get mime-types ext "application/octet-stream")))

(defn- pdf-url? [url] (= "application/pdf" (mime-for url)))

(defn- download-pdf!
  "Download a non-file:// PDF's bytes over the web view's OWN session (so cookies/logins for the source site
   apply), then hand them to the app renderer as a `pdf` document keyed by the URL (main has no http reader, and
   there is no byte-only PDF IPC). The renderer caches the bytes and opens the URL in a tab → pdf.js renders it.
   Ordered: send the content BEFORE the open, so the pdf doc entity exists when the tab mounts (no web-view flash)."
  [url tab]
  (let [^js req (.request net (clj->js {:url url :partition "persist:vinary-web"}))
        chunks  (array)]
    (.on req "response"
         (fn [^js resp]
           (.on resp "data" (fn [chunk] (.push chunks chunk)))
           (.on resp "end"
                (fn []
                  (when-let [^js awc (app-wc)]
                    (.send awc "vv:content" (clj->js {:path url :kind "pdf" :bytes (.concat js/Buffer chunks)
                                                      :stamp (js/Date.now)}))
                    (.send awc "vv:http-open-pdf" (clj->js {:url url :tab tab})))))))
    (.on req "error"
         (fn [^js e]
           (when-let [^js awc (app-wc)]
             (.send awc "vv:error" (clj->js {:path url :message (str "PDF download failed: " (.-message e))})))))
    (.end req)))

(defn- route-pdf!
  "Open a PDF link clicked in the web view in the app's pdf.js viewer instead of Chromium's pdfium plugin. A
   file:// PDF is opened straight through the app's local open flow (which produces its own bytes); any other URL
   (http(s), vv-remote://) is fetched here first via `download-pdf!`. `tab` = the owning http tab."
  [url tab]
  (if (.startsWith (str url) "file:")
    (when-let [^js awc (app-wc)]
      (.send awc "vv:http-open-pdf" (clj->js {:url url :tab tab})))
    (download-pdf! url tab)))

(defn- register-remote-protocol!
  "Serve vv-remote:// on the web view's session: map each request URL 1:1 back to its ssh:// URI and stream the
   file's bytes over SFTP. This lets the web view live-render a remote HTML page whose relative assets (CSS / JS
   / images) resolve to vv-remote:// URLs — the SSH analog of loading an http(s) page. Registered once."
  []
  (let [^js sess (.fromPartition session "persist:vinary-web")]
    (.handle (.-protocol sess) "vv-remote"
             (fn [^js request]
               (let [url     (.-url request)
                     ssh-uri (str "ssh://" (subs url (count "vv-remote://")))]
                 (-> (.remoteReadFile ssh-transport ssh-uri)
                     (.then (fn [bytes] (js/Response. bytes #js {:headers #js {"content-type" (mime-for ssh-uri)}})))
                     (.catch (fn [^js e] (js/Response. (str "remote fetch failed: " (.-message e))
                                                       #js {:status 502 :headers #js {"content-type" "text/plain"}})))))))))

(defn init! [^js win]
  ;; a recreated window invalidates the old view (it belonged to the closed window)
  (when (not= win (:win @state)) (swap! state assoc :view nil :url nil :snapshot nil :visible? false))
  (swap! state assoc :win win)
  (attach-app-command! win)
  (when-not @inited
    (reset! inited true)
    (register-remote-protocol!)   ; vv-remote:// → SFTP, for live-rendering remote HTML in the web view
    ;; a PDF served as a forced download (Content-Disposition: attachment, or an extension-less application/pdf) →
    ;; open it in the app's pdf.js viewer instead of downloading it. Registered once on the web view's session.
    (.on (.fromPartition session "persist:vinary-web") "will-download"
         (fn [^js e ^js item _]
           (when (or (= "application/pdf" (.getMimeType item)) (pdf-url? (.getURL item)))
             (.preventDefault e)
             (route-pdf! (.getURL item) (:owner-tab @state)))))
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
    ;; page/edge keys (PageDown/PageUp/Home/End) forwarded from the app's capture handler — scroll the web
    ;; PAGE only when the native view is visible/active (else app chrome handled it), so they work even when
    ;; app chrome holds focus instead of the web view (which handles them itself via its preload when focused).
    (.on ipcMain "vv:http-scroll"
         (fn [_e ^js kind]
           (when (and (:visible? @state) (:view @state))
             (.send (.-webContents ^js (:view @state)) "vv:web-scroll" kind))))
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
