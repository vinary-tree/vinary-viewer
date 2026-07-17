(ns vinary.main.core
  "Electron MAIN process entry. Creates the window (sandboxed renderer + contextBridge preload),
   wires the IO/watch service, and opens any files/URIs named on the command line
   (`vv a.md b.pdf https://…`), each in its own tab.
   Original code (Apache-2.0); a new application inspired by vmd (MIT)."
  (:require ["electron" :as electron]
            ["path" :as path]
            ["fs" :as fs]
            [vinary.main.service :as service]
            [vinary.main.startup :as startup]
            [vinary.main.profile :as profile]
            [vinary.main.config :as config]
            [vinary.main.settings :as settings]
            [vinary.main.recent :as recent]
            [vinary.main.window :as window]
            [vinary.main.shell :as shell]
            ;; [vinary.main.pdf :as pdf]  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
            [vinary.main.web :as web]
            [vinary.main.adblock :as adblock]
            [vinary.main.extensions :as extensions]
            [vinary.main.ext-config :as ext-config]
            [vinary.main.ext-popup :as ext-popup]
            [vinary.main.passwords :as passwords]
            [vinary.main.ssh :as ssh]
            [vinary.main.connections :as connections]
            [vinary.main.grammars :as grammars]))

(def ^js app (.-app electron))
(def ^js BrowserWindow (.-BrowserWindow electron))

;; Every live app window. The app is SINGLE-INSTANCE (see `main`): the first process owns the lock and stays
;; resident; each subsequent `vv <file>` hands its argv to it via Electron's `second-instance`, which opens the
;; file in a NEW window of the already-warm process — no second cold start.
(defonce windows (atom []))

;; Process/session-level services (shared-web-session extensions + ad-block, the web view, password autofill, the
;; shell IPC handlers) register ONCE for the whole app; re-running them for a second window throws (e.g. "Cannot
;; register preload script with existing ID"). They configure the SHARED web session, so all windows share them.
(defonce ^:private services-inited? (atom false))

(defn- active-window
  "The window a global action (menu, focus-follows) targets: the focused window, else the most recent."
  ^js []
  (or (.getFocusedWindow BrowserWindow) (peek @windows)))

(defn renderer-index [] (path/join js/__dirname ".." ".." "resources" "public" "index.html"))
(defn preload-path  [] (path/join js/__dirname ".." ".." "resources" "preload.js"))

(defn initial-args
  "All non-flag document arguments passed on the command line (e.g. `vv a.md b.pdf https://…`),
   normalized to canonical tab uris in order — see `startup/doc-uris`. node's `path.resolve` is the
   injected cwd-relative resolver for local paths."
  []
  (startup/doc-uris (js->clj js/process.argv) #(.resolve path %)))

(defn create-window!
  "Open a new app window showing `args` (canonical doc uris, e.g. from `initial-args`/a second-instance argv);
   nil/empty opens an empty window."
  ([] (create-window! nil))
  ([args]
  (let [win (BrowserWindow.
              (clj->js (merge {:backgroundColor "#292b2e"
                               :autoHideMenuBar true
                               :webPreferences {:contextIsolation true
                                                :nodeIntegration false
                                                ;; keep the compositor live so off-screen-rendered PDF
                                                ;; canvases don't hold a stale/blank GPU surface
                                                :backgroundThrottling false
                                                :preload (preload-path)}}
                              ;; restore last position/size (clamped on-screen); defaults to 1280×860
                              (window/options))))]
    ;; profiling: propagate VV_PROFILE into the renderer via a ?profile=1 search param (it has no process.env),
    ;; and forward the renderer's `[vv-profile]` console marks to main's stdout (a renderer console.log does not
    ;; otherwise reach it) so scripts/profile-cold-start.mjs sees main + renderer marks on one stream
    (when profile/on?
      (.on (.-webContents win) "console-message"
           (fn [& args]
             (let [msg (first (filter string? args))]   ; signature varies by Electron version; find the message
               (when (and msg (re-find #"\[vv-profile\]" msg)) (js/console.log msg))))))
    (if profile/on?
      (.loadFile win (renderer-index) #js {:search "profile=1"})
      (.loadFile win (renderer-index)))
    ;; Lock the app frame to its bundled index.html. Now that markdown renders (sanitized) raw HTML, a stray
    ;; top-frame navigation must never hand the privileged window.vv bridge to another origin. Real links open
    ;; in the isolated web view or externally (never here), and new-window requests are denied outright.
    (.on (.-webContents win) "will-navigate"
         (fn [^js e ^js url] (when-not (= url (.getURL (.-webContents win))) (.preventDefault e))))
    (.setWindowOpenHandler (.-webContents win) (fn [_] #js {:action "deny"}))
    (window/remember! win)                 ; reapply maximized state + persist bounds on resize/move/close
    (.once (.-webContents win) "did-finish-load"
           (fn []
             (profile/mark! "did-finish-load")
             (config/init! (.-webContents win))
             (settings/init! (.-webContents win))
             (recent/init! (.-webContents win))
             (ext-config/init! (.-webContents win))
             (grammars/init! (.-webContents win))
             (connections/init! (.-webContents win))   ; persisted (non-secret) SSH connection metadata
             (ssh/init! win)                            ; SSH/SFTP transport prompts + vv:ssh-* channels
             ;; open every file/URI named on the command line, each in its own tab (first focused);
             ;; reuses the renderer's multi-file pipeline (vv:open-files → :files/opened → files-opened-fx)
             (when-let [paths (seq args)]
               (profile/mark! "open-sent")
               (.send (.-webContents win) "vv:open-files"
                      (clj->js {:paths (vec paths) :focus-first true})))))
    (.on win "closed" (fn [] (swap! windows (fn [ws] (vec (remove #(= % win) ws))))))
    ;; (pdf/init! win)  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
    ;; process/session-level services register ONCE (see `services-inited?`) — extension runtime + ad-blocking on
    ;; the shared web session, the web view, password autofill, and the shell IPC handlers. They bind to the first
    ;; window; because the web session is shared, every window's web view inherits the extensions + ad-block.
    (when (compare-and-set! services-inited? false true)
      (let [prefs (ext-config/load-config)]
        (ext-popup/init! win)
        (extensions/init! win prefs)
        (adblock/init! win (:adblock prefs)))
      (web/init! win)
      (passwords/init! win)
      (shell/init! win))
    (swap! windows conj win)
    (profile/mark! "window")
    win)))

(defn- report-crash!
  "Log the full stack (terminal/logs) and show a COPYABLE error dialog. Electron's default
   uncaught-exception dialog has only an OK button and its text can't be selected/copied, so the
   user had to retype crash traces by hand; this offers a 'Copy details' button instead."
  [^js err]
  (let [text (or (some-> err .-stack) (str err))]
    (js/console.error "[vinary] uncaught main-process exception:\n" text)
    (try
      (let [^js dialog    (.-dialog electron)
            ^js clipboard (.-clipboard electron)
            choice (.showMessageBoxSync dialog
                     (clj->js {:type      "error"
                               :title     "vinary-viewer — unexpected error"
                               :message   "An unexpected error occurred in the main process."
                               :detail    text
                               :buttons   ["Copy details" "Dismiss"]
                               :defaultId 0
                               :cancelId  1
                               :noLink    true}))]
        (when (zero? choice) (.writeText clipboard text)))
      (catch :default _ nil))))   ; pre-ready / headless → the console still has the full stack

(defn- install-crash-reporting! []
  (.on js/process "uncaughtException"  (fn [err]      (report-crash! err)))
  ;; benign promise rejections are common (extensions/network) — log them, but don't pop a modal for each
  (.on js/process "unhandledRejection"
       (fn [reason _] (js/console.error "[vinary] unhandled promise rejection:"
                                        (or (some-> ^js reason .-stack) reason)))))

(defn- app-version []
  (try (-> (.readFileSync fs (path/join js/__dirname ".." ".." "package.json") "utf8") (js/JSON.parse) (.-version))
       (catch :default _ "unknown")))

(defn ^:export main []
  (profile/mark! "entry")
  ;; `electron . --help`/`--version` (and `vv --gui --help`) print usage/version and exit BEFORE any window —
  ;; the primary `vv --help` path is the install.sh dispatcher; this covers direct/`--gui` invocation.
  (case (startup/help-request? (js->clj js/process.argv))
    :help    (do (js/console.log startup/usage-text) (.exit js/process 0))
    :version (do (js/console.log (startup/version-text (app-version))) (.exit js/process 0))
    nil)
  ;; surface main-process crashes in a copyable dialog (the Electron default isn't copyable) + log them
  (install-crash-reporting!)
  ;; SINGLE-INSTANCE: the first process becomes the resident app; a subsequent `vv <file>` fails the lock and
  ;; hands its argv to the primary via `second-instance` (below), which opens the file in a NEW window of the
  ;; already-warm process — no second cold start. A non-primary exits immediately without booting anything.
  (if-not (.requestSingleInstanceLock app)
    (.quit app)
    (do
  ;; register the privileged vv-remote:// scheme BEFORE app 'ready' — the web view loads it to live-render remote
  ;; HTML, and main serves each vv-remote:// URL's bytes over SFTP (vinary.main.web). standard+secure so the
  ;; page's relative assets resolve and it runs in a secure context, exactly like an http(s) page.
  (.registerSchemesAsPrivileged (.-protocol electron)
                                (clj->js [{:scheme "vv-remote"
                                           :privileges {:standard true :secure true :supportFetchAPI true
                                                        :stream true :corsEnabled true}}]))
  ;; Chromium switches the app always needs — see vinary.main.startup/chromium-switches: disable-gpu-sandbox
  ;; (Linux GPU-process DRI/GBM driver access; renderer stays sandboxed) + two defensive software-compositor
  ;; switches (disable-partial-raster, ui-disable-partial-swap). NOTE: those two do NOT fix the NVIDIA +
  ;; Wayland scroll-band; the actual band fix is disable-hardware-acceleration? below (remove the GPU process).
  (doseq [sw startup/chromium-switches] (.appendSwitch (.-commandLine app) sw))
  ;; VV_OZONE overrides the Ozone platform (e.g. VV_OZONE=x11) — an escape hatch for headless/xvfb runs (so the
  ;; window renders into an X server instead of the host Wayland compositor) and users who want a specific backend.
  (when-let [oz (.. js/process -env -VV_OZONE)]
    (.appendSwitch (.-commandLine app) "ozone-platform" oz))
  ;; NVIDIA + Wayland scroll-band fix that KEEPS the GPU process (best performance): GPU-rasterized tiles
  ;; go through the broken Vulkan-on-Wayland presentation that paints a duplicated scroll-band; disabling
  ;; GPU rasterization (already software-blocklisted here, so no loss) makes tiles software-rasterized and
  ;; presented via a path that avoids it, while keeping the GPU for compositing/presentation/WebGL/video.
  ;; Verified by screen-captured A/B (this flag alone flips banded<->clean). VV_GPU_RASTER=1 opts out.
  (when (startup/disable-gpu-rasterization?
          {:VV_GPU_RASTER    (.. js/process -env -VV_GPU_RASTER)
           :XDG_SESSION_TYPE (.. js/process -env -XDG_SESSION_TYPE)
           :WAYLAND_DISPLAY  (.. js/process -env -WAYLAND_DISPLAY)})
    (.appendSwitch (.-commandLine app) "disable-gpu-rasterization"))
  ;; VV_SOFTWARE_GL=1 forces FULL software rendering (no GPU process) anywhere — last-resort escape hatch.
  (when (startup/disable-hardware-acceleration? {:VV_SOFTWARE_GL (.. js/process -env -VV_SOFTWARE_GL)})
    (.disableHardwareAcceleration app))
  (service/init!)
  ;; remove Electron's default application menu — vinary-viewer draws its own themed menu bar, and its
  ;; keybindings own the accelerators (so the default menu's Ctrl+R/W/etc. don't double-fire)
  ;; a second `vv <file>` invocation: its argv/cwd arrive here (its own process then exits). Open the files in a
  ;; NEW window of this warm process. `wd` is the second instance's working directory — resolve its relative paths.
  (.on app "second-instance"
       (fn [_e ^js argv ^js wd]
         (let [args (startup/doc-uris (js->clj argv) #(.resolve path wd %))]
           (create-window! (seq args)))))
  (-> (.whenReady app) (.then (fn [] (profile/mark! "ready") (.setApplicationMenu (.-Menu electron) nil)
                                (create-window! (initial-args)))))
  (.on app "activate" (fn [] (when (empty? @windows) (create-window!))))
  (.on app "before-quit" (fn [] (ssh/shutdown!)))   ; tear down pooled SSH connections on quit
  (.on app "window-all-closed"
       (fn [] (when-not (= js/process.platform "darwin") (.quit app)))))))
