(ns vinary.main.connections
  "Persisted SSH connection metadata (~/.config/vinary-viewer/connections.edn): non-secret host entries
   (alias, hostname, user, port, last-used, last-path, per-host prefs) and a recent-remote MRU surfaced in
   File ▸ Open Recent. Mirrors recent.cljs — the EDN crosses the IPC seam as raw text (the renderer owns it
   and parses/writes it), with a chokidar re-push on external edits and a renderer→main write.

   SECRETS ARE NEVER STORED HERE. Passwords, passphrases, private-key contents, and host-key material live
   only in the transport's main-process memory (for the connection lifetime) and in the user's existing
   ~/.ssh (keys, known_hosts). This file holds addresses and preferences only."
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
(defn- connections-path [] (path/join (conf-dir) "connections.edn"))

(defn- connections-text []
  (let [p (connections-path)]
    (try (if (.existsSync fs p) (.readFileSync fs p "utf8") "")
         (catch :default _ ""))))

(defn- save! [text]
  (try
    (when-not (.existsSync fs (conf-dir)) (.mkdirSync fs (conf-dir) (clj->js {:recursive true})))
    (.writeFileSync fs (connections-path) (str text))
    (catch :default _ nil)))

(defn push! [^js wc] (.send wc "vv:connections" (connections-text)))

(defn init! [^js wc]
  (push! wc)
  (let [p (connections-path)]
    (when-not @watcher
      (let [w (watch p (clj->js {:ignoreInitial true
                                 :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
        (.on w "change" (fn [_] (push! wc)))
        (.on w "add"    (fn [_] (push! wc)))
        (reset! watcher w))))
  (when-not @inited
    (reset! inited true)
    (.on ipcMain "vv:connections-request" (fn [^js e] (push! (.-sender e))))
    (.on ipcMain "vv:connections-save"    (fn [_e text] (save! text)))))
