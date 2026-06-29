(ns vinary.main.core
  "Electron MAIN process entry. Creates the window (sandboxed renderer + contextBridge preload),
   wires the IO/watch service, and opens any file named on the command line (`vv README.md`).
   Original code (Apache-2.0); a new application inspired by vmd (MIT)."
  (:require ["electron" :as electron]
            ["path" :as path]
            [clojure.string :as str]
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
            [vinary.main.grammars :as grammars]))

(def ^js app (.-app electron))
(def ^js BrowserWindow (.-BrowserWindow electron))

(defonce main-window (atom nil))

(defn renderer-index [] (path/join js/__dirname ".." ".." "resources" "public" "index.html"))
(defn preload-path  [] (path/join js/__dirname ".." ".." "resources" "preload.js"))

(defn initial-file
  "The document path passed on the command line (e.g. `vv README.md`), resolved to an absolute path
   (relative to the launch CWD), or nil. argv[0]=electron, argv[1]=app path; the first remaining
   non-flag argument is the document."
  []
  (when-let [f (->> (drop 2 (js->clj js/process.argv))
                    (remove #(str/starts-with? % "-"))
                    first)]
    (.resolve path f)))

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
    (window/remember! win)                 ; reapply maximized state + persist bounds on resize/move/close
    (.once (.-webContents win) "did-finish-load"
           (fn []
             (config/init! (.-webContents win))
             (settings/init! (.-webContents win))
             (recent/init! (.-webContents win))
             (ext-config/init! (.-webContents win))
             (grammars/init! (.-webContents win))
             (when-let [f (initial-file)] (service/open! (.-webContents win) f))))
    (.on win "closed" (fn [] (reset! main-window nil)))
    ;; (pdf/init! win)  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
    ;; extension runtime + ad-blocking on the web session (before the lazy web view's first load)
    (let [prefs (ext-config/load-config)]
      (ext-popup/init! win)
      (extensions/init! win prefs)
      (adblock/init! win (:adblock prefs)))
    (web/init! win)
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

(defn ^:export main []
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
