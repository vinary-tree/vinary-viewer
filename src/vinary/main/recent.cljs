(ns vinary.main.recent
  "Persisted recent-navigation state (~/.config/vinary-viewer/recent.edn): the directory→last-child
   `:trail` (so Alt+Up then Alt+Down returns to the most-recently-opened full path) and the
   `:recent-files` MRU (last opened files, surfaced in File ▸ Open Recent). Mirrors settings.cljs — the
   EDN crosses the IPC seam as raw text (the renderer owns it and parses/writes it), with a chokidar
   re-push on external edits and a renderer→main write (vv:recent-save)."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["chokidar" :refer [watch]]
            [vinary.main.windows :as windows]))

(defonce ^:private watcher (atom nil))
(defonce ^:private inited  (atom false))

(defn- conf-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))
(defn- recent-path [] (path/join (conf-dir) "recent.edn"))

(defn- recent-text []
  (let [p (recent-path)]
    (try (if (.existsSync fs p) (.readFileSync fs p "utf8") "")
         (catch :default _ ""))))

(defn- save! [text]
  (try
    (when-not (.existsSync fs (conf-dir)) (.mkdirSync fs (conf-dir) (clj->js {:recursive true})))
    (.writeFileSync fs (recent-path) (str text))
    (catch :default _ nil)))

(defn push! [^js wc] (when (and wc (not (.isDestroyed wc))) (.send wc "vv:recent" (recent-text))))

(defn init! [^js wc]
  (push! wc)                               ; initial push (the renderer also pulls via requestRecent)
  (let [p (recent-path)]
    (when-not @watcher
      ;; the watcher outlives any single window (esp. under the daemon) — re-push global recents to ALL live
      ;; windows, never the `wc` captured here (it may have closed → "Object has been destroyed").
      (let [w (watch p (clj->js {:ignoreInitial true
                                 :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
        (.on w "change" (fn [_] (windows/broadcast! "vv:recent" (recent-text))))
        (.on w "add"    (fn [_] (windows/broadcast! "vv:recent" (recent-text))))
        (reset! watcher w))))
  (when-not @inited
    (reset! inited true)
    (.on ipcMain "vv:recent-request" (fn [^js e] (push! (.-sender e))))
    (.on ipcMain "vv:recent-save"    (fn [_e text] (save! text)))))
