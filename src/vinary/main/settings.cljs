(ns vinary.main.settings
  "Persisted user settings (~/.config/vinary-viewer/settings.edn): theme + fonts. Mirrors config.cljs
   (chokidar watch + EDN-text push over vv:settings) and adds a renderer→main write (vv:settings-save) so
   the Preferences dialog persists changes; the watcher then re-pushes, closing the loop. EDN crosses as
   raw text (the renderer parses it) so keyword keys survive."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["chokidar" :refer [watch]]))

(defonce ^:private watcher (atom nil))
(defonce ^:private inited  (atom false))

(defn- conf-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))
(defn- settings-path [] (path/join (conf-dir) "settings.edn"))

(defn- settings-text []
  (let [p (settings-path)]
    (try (if (.existsSync fs p) (.readFileSync fs p "utf8") "")
         (catch :default _ ""))))

(defn- save! [text]
  (try
    (when-not (.existsSync fs (conf-dir)) (.mkdirSync fs (conf-dir) (clj->js {:recursive true})))
    (.writeFileSync fs (settings-path) (str text))
    (catch :default _ nil)))

(defn push! [^js wc] (.send wc "vv:settings" (settings-text)))

(defn init! [^js wc]
  (push! wc)                               ; initial push (the renderer also pulls via requestSettings)
  (let [p (settings-path)]
    (when-not @watcher
      (let [w (watch p (clj->js {:ignoreInitial true
                                 :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
        (.on w "change" (fn [_] (push! wc)))
        (.on w "add"    (fn [_] (push! wc)))
        (reset! watcher w))))
  (when-not @inited
    (reset! inited true)
    (.on ipcMain "vv:settings-request" (fn [^js e] (push! (.-sender e))))
    (.on ipcMain "vv:settings-save"    (fn [_e text] (save! text)))))
