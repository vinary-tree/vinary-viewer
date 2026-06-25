(ns vinary.main.shell
  "Main-process handlers for the menu bar's shell actions: the multi-file Open dialog, clipboard writes
   (Copy file path / name), window zoom / devtools / quit, and the app-info (name + version) push for the
   About dialog. The sandboxed renderer can't touch dialog/clipboard/app directly, so these cross the IPC
   seam; the opened paths come back over vv:open-files."
  (:require ["electron" :refer [ipcMain dialog clipboard app shell]]
            ["fs" :as fs]
            ["path" :as path]))

(defonce ^:private win*   (atom nil))
(defonce ^:private inited (atom false))

(defn- wc [] (some-> ^js @win* .-webContents))

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
         (fn [_e]
           (when-let [^js w @win*]
             (-> (.showOpenDialog dialog w (clj->js {:title "Open file(s)"
                                                     :properties ["openFile" "multiSelections"]}))
                 (.then (fn [^js r]
                          (when (and (not (.-canceled r)) (wc))
                            (.send (wc) "vv:open-files" (clj->js {:paths (vec (.-filePaths r))})))))))))
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
                                       :else    (max 0.4 (- (.getZoomFactor c) 0.1))))))))))
