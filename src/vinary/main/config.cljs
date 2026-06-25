(ns vinary.main.config
  "Main-process loader for the user keymap config ~/.config/vinary-viewer/keybindings.edn. Mirrors
   vinary.main.service (chokidar watch + push), so editing the file live-rebinds the keymap. The config
   crosses the IPC seam as raw EDN TEXT (not clj->js) — the renderer parses it with cljs.reader so the
   keyword command-ids survive (clj->js would flatten them to strings)."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["chokidar" :refer [watch]]))

(defonce ^:private watcher (atom nil))

(defn- config-path []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer" "keybindings.edn")))

(defn- config-text []
  (let [p (config-path)]
    (try (if (.existsSync fs p) (.readFileSync fs p "utf8") "")
         (catch :default _ ""))))

(defn push! [^js wc] (.send wc "vv:keymap" (config-text)))

(defn init! [^js wc]
  (push! wc)                                   ; initial push (the renderer also pulls via requestKeymap)
  (let [p (config-path)]
    (when-not @watcher
      (let [w (watch p (clj->js {:ignoreInitial true
                                 :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
        (.on w "change" (fn [_] (push! wc)))
        (.on w "add"    (fn [_] (push! wc)))
        (.on w "unlink" (fn [_] (.send wc "vv:keymap" "")))
        (reset! watcher w))))
  (.on ipcMain "vv:keymap-request" (fn [^js e] (push! (.-sender e)))))
