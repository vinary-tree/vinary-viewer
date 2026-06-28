(ns vinary.main.ext-config
  "Persisted extension + ad-block prefs (~/.config/vinary-viewer/extensions.edn): the ad-block toggle /
   list-set, the extensions master toggle, and disabled-ids. Mirrors recent.cljs/settings.cljs (EDN as
   text over the seam, chokidar re-push, renderer→main save) — PLUS a synchronous main-side `load-config`
   so adblock/extensions boot with the user's saved prefs at init (not after a renderer round-trip)."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["chokidar" :refer [watch]]
            [clojure.string :as str]
            [cljs.reader :as reader]
            [vinary.main.ext-util :as eu]))

(defonce ^:private watcher (atom nil))
(defonce ^:private inited  (atom false))

(defn- conf-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))
(defn- config-path [] (path/join (conf-dir) "extensions.edn"))

(defn- config-text []
  (let [p (config-path)]
    (try (if (.existsSync fs p) (.readFileSync fs p "utf8") "")
         (catch :default _ ""))))

(defn load-config
  "Read + parse extensions.edn synchronously (main-side), normalized against defaults. Used by
   adblock/extensions init so they boot with the user's saved prefs rather than defaults."
  []
  (let [t (config-text)
        m (when (seq (str/trim t)) (try (reader/read-string t) (catch :default _ nil)))]
    (eu/merge-config m)))

(defn- save! [text]
  (try
    (when-not (.existsSync fs (conf-dir)) (.mkdirSync fs (conf-dir) (clj->js {:recursive true})))
    (.writeFileSync fs (config-path) (str text))
    (catch :default _ nil)))

(defn push! [^js wc] (.send wc "vv:ext-config" (config-text)))

(defn init! [^js wc]
  (push! wc)
  (let [p (config-path)]
    (when-not @watcher
      (let [w (watch p (clj->js {:ignoreInitial true
                                 :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
        (.on w "change" (fn [_] (push! wc)))
        (.on w "add"    (fn [_] (push! wc)))
        (reset! watcher w))))
  (when-not @inited
    (reset! inited true)
    (.on ipcMain "vv:ext-config-request" (fn [^js e] (push! (.-sender e))))
    (.on ipcMain "vv:ext-config-save"    (fn [_e text] (save! text)))))
