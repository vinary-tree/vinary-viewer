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
            [vinary.main.grammars :as grammars]))

(def ^js app (.-app electron))
(def ^js BrowserWindow (.-BrowserWindow electron))

(defonce main-window (atom nil))

(defn renderer-index [] (path/join js/__dirname ".." ".." "resources" "public" "index.html"))
(defn preload-path  [] (path/join js/__dirname ".." ".." "resources" "preload.js"))

(defn initial-args
  "All non-flag document arguments passed on the command line (e.g. `vv a.md b.pdf https://…`),
   normalized to canonical tab uris in order — see `startup/doc-uris`. node's `path.resolve` is the
   injected cwd-relative resolver for local paths."
  []
  (startup/doc-uris (js->clj js/process.argv) #(.resolve path %)))

(defn create-window! []
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
    (.loadFile win (renderer-index))
    ;; Lock the app frame to its bundled index.html. Now that markdown renders (sanitized) raw HTML, a stray
    ;; top-frame navigation must never hand the privileged window.vv bridge to another origin. Real links open
    ;; in the isolated web view or externally (never here), and new-window requests are denied outright.
    (.on (.-webContents win) "will-navigate"
         (fn [^js e ^js url] (when-not (= url (.getURL (.-webContents win))) (.preventDefault e))))
    (.setWindowOpenHandler (.-webContents win) (fn [_] #js {:action "deny"}))
    (window/remember! win)                 ; reapply maximized state + persist bounds on resize/move/close
    (.once (.-webContents win) "did-finish-load"
           (fn []
             (config/init! (.-webContents win))
             (settings/init! (.-webContents win))
             (recent/init! (.-webContents win))
             (ext-config/init! (.-webContents win))
             (grammars/init! (.-webContents win))
             ;; open every file/URI named on the command line, each in its own tab (first focused);
             ;; reuses the renderer's multi-file pipeline (vv:open-files → :files/opened → files-opened-fx)
             (when-let [args (seq (initial-args))]
               (.send (.-webContents win) "vv:open-files"
                      (clj->js {:paths (vec args) :focus-first true})))))
    (.on win "closed" (fn [] (reset! main-window nil)))
    ;; (pdf/init! win)  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
    ;; extension runtime + ad-blocking on the web session (before the lazy web view's first load)
    (let [prefs (ext-config/load-config)]
      (ext-popup/init! win)
      (extensions/init! win prefs)
      (adblock/init! win (:adblock prefs)))
    (web/init! win)
    (passwords/init! win)
    (shell/init! win)
    (reset! main-window win)
    win))

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
  ;; `electron . --help`/`--version` (and `vv --gui --help`) print usage/version and exit BEFORE any window —
  ;; the primary `vv --help` path is the install.sh dispatcher; this covers direct/`--gui` invocation.
  (case (startup/help-request? (js->clj js/process.argv))
    :help    (do (js/console.log startup/usage-text) (.exit js/process 0))
    :version (do (js/console.log (startup/version-text (app-version))) (.exit js/process 0))
    nil)
  ;; surface main-process crashes in a copyable dialog (the Electron default isn't copyable) + log them
  (install-crash-reporting!)
  ;; Chromium switches the app always needs — see vinary.main.startup/chromium-switches: disable-gpu-sandbox
  ;; (Linux GPU-process DRI/GBM driver access; renderer stays sandboxed) + two defensive software-compositor
  ;; switches (disable-partial-raster, ui-disable-partial-swap). NOTE: those two do NOT fix the NVIDIA +
  ;; Wayland scroll-band; the actual band fix is disable-hardware-acceleration? below (remove the GPU process).
  (doseq [sw startup/chromium-switches] (.appendSwitch (.-commandLine app) sw))
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
  (-> (.whenReady app) (.then (fn [] (.setApplicationMenu (.-Menu electron) nil) (create-window!))))
  (.on app "activate" (fn [] (when (nil? @main-window) (create-window!))))
  (.on app "window-all-closed"
       (fn [] (when-not (= js/process.platform "darwin") (.quit app)))))
