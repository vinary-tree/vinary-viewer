(ns vinary.main.shell
  "Main-process handlers for the menu bar's shell actions: the multi-file Open dialog, clipboard writes
   (Copy file path / name), window zoom / devtools / quit, and the app-info (name + version) push for the
   About dialog. The sandboxed renderer can't touch dialog/clipboard/app directly, so these cross the IPC
   seam; the opened paths come back over vv:open-files."
  (:require ["electron" :refer [ipcMain dialog clipboard app shell BrowserWindow]]
            ["fs" :as fs]
            ["path" :as path]
            [vinary.main.windows :as windows]))

(defonce ^:private win*   (atom nil))
(defonce ^:private inited (atom false))

(defn- cur-win
  "The window a shell action targets — the FOCUSED window (multi-instance: the one the user triggered the menu/
   zoom/devtools from), else the most-recently-shown app window; never a hidden pool window. `win*` (the window
   captured at init!) is only an ultimate fallback for the pre-first-window edge."
  ^js []
  (or (windows/active) @win*))

;; ^js return tag → the un-hinted interop callers (e.g. (.toggleDevTools (wc))) get an inferred extern, so
;; advanced compilation can't rename the method to a non-existent one. (Belt-and-suspenders with the :main
;; build's :simple optimization; see shadow-cljs.edn.)
(defn- wc ^js [] (some-> ^js (cur-win) .-webContents))

(defn- app-info []
  (let [pkg (try (js/JSON.parse (.readFileSync fs (path/join js/__dirname ".." ".." "package.json") "utf8"))
                 (catch :default _ #js {}))]
    {:name    "vinary-viewer"
     :version (or (.-version pkg) "0.2.0")
     :repo    "https://github.com/vinary-tree/vinary-viewer"}))

(defn init! [^js win]
  (reset! win* win)
  (when-not @inited
    (reset! inited true)
    ;; multi-file Open dialog → the chosen paths come back over vv:open-files (no invoke/handle needed)
    (.on ipcMain "vv:open-dialog"
         (fn [^js e]
           (when-let [^js w (cur-win)]
             (-> (.showOpenDialog dialog w (clj->js {:title "Open file(s)"
                                                     :properties ["openFile" "multiSelections"]}))
                 (.then (fn [^js r]
                          ;; reply to the window that requested Open (its own tabs), not a global one
                          (when-let [^js sender (and (not (.-canceled r)) (.-sender e))]
                            (.send sender "vv:open-files" (clj->js {:paths (vec (.-filePaths r))})))))))))
    (.on ipcMain "vv:clipboard-write"  (fn [_e text] (.writeText clipboard (str text))))
    (.on ipcMain "vv:open-path"        (fn [_e p]   (.openPath shell (str p))))      ; dir → file manager
    (.on ipcMain "vv:open-external"    (fn [_e url] (.openExternal shell (str url)))) ; http → system browser
    (.on ipcMain "vv:app-info-request" (fn [_e] (when (wc) (.send (wc) "vv:app-info" (clj->js (app-info))))))
    (.on ipcMain "vv:quit"             (fn [_e] (.quit app)))
    (.on ipcMain "vv:devtools"         (fn [_e] (when (wc) (.toggleDevTools (wc)))))
    (.on ipcMain "vv:zoom"
         (fn [_e dir]
           (when-let [^js c (wc)]
             (let [d (js->clj dir)]
               (.setZoomFactor c (cond (= d 0)  1.0
                                       (pos? d) (min 3.0 (+ (.getZoomFactor c) 0.1))
                                       :else    (max 0.4 (- (.getZoomFactor c) 0.1))))
               (.send c "vv:zoom-changed" (clj->js {:context "window" :factor (.getZoomFactor c)}))))))
    (.on ipcMain "vv:zoom-set"
         (fn [_e f]
           (when-let [^js c (wc)]
             (.setZoomFactor c (max 0.4 (min 3.0 (js->clj f))))
             (.send c "vv:zoom-changed" (clj->js {:context "window" :factor (.getZoomFactor c)})))))
    ;; boot pull (renderer asks once its listener is attached): report the (Chromium-restored) window zoom so the
    ;; zoom bar seeds from the true factor instead of the app-db default of 1.0 → 100%.
    (.on ipcMain "vv:zoom-request"
         (fn [_e]
           (when-let [^js c (wc)]
             (.send c "vv:zoom-changed" (clj->js {:context "window" :factor (.getZoomFactor c)})))))))
