(ns vinary.main.core
  "Electron MAIN process entry. Creates the window (sandboxed renderer + contextBridge preload),
   wires the IO/watch service, and opens any file named on the command line (`vv README.md`).
   Original code (Apache-2.0); a new application inspired by vmd (MIT)."
  (:require ["electron" :as electron]
            ["path" :as path]
            [clojure.string :as str]
            [vinary.main.service :as service]
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

(defn ^:export main []
  ;; Linux GPU resilience: many systems sandbox the GPU process so it cannot open the system DRI/GBM
  ;; driver ("MESA-LOADER: failed to open dri … Permission denied"). Loosen only the GPU sandbox (the
  ;; renderer stays sandboxed). VV_SOFTWARE_GL=1 forces software rendering where even that fails.
  (.appendSwitch (.-commandLine app) "disable-gpu-sandbox")
  (when (.. js/process -env -VV_SOFTWARE_GL) (.disableHardwareAcceleration app))
  (service/init!)
  ;; remove Electron's default application menu — vinary-viewer draws its own themed menu bar, and its
  ;; keybindings own the accelerators (so the default menu's Ctrl+R/W/etc. don't double-fire)
  (-> (.whenReady app) (.then (fn [] (.setApplicationMenu (.-Menu electron) nil) (create-window!))))
  (.on app "activate" (fn [] (when (nil? @main-window) (create-window!))))
  (.on app "window-all-closed"
       (fn [] (when-not (= js/process.platform "darwin") (.quit app)))))
