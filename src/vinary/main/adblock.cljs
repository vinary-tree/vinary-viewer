(ns vinary.main.adblock
  "Native ad/tracker blocking via @ghostery/adblocker-electron (MPL-2.0) on the web view's persistent
   session (persist:vinary-web). Blocks at webRequest before the page sees the request — no extension,
   immune to Chrome MV3 limits, and isolated to the web-browsing session (never the app renderer / PDF /
   file:// tabs). The EasyList/uBO engine is cached under userData + refreshed on a schedule; degrades to
   the cached engine, then an empty (no-op) engine, when offline."
  (:require ["@ghostery/adblocker-electron" :refer [ElectronBlocker]]
            ["electron" :refer [ipcMain session app]]
            ["fs/promises" :as fsp]
            ["path" :as path]))

(defonce ^:private inited (atom false))

(defonce ^:private state (atom {:blocker nil :enabled? true :sess nil :lists :ads-and-tracking :timer nil :wc nil}))

(defn- engine-cache-path [] (path/join (.getPath app "userData") "adblock-engine.bin"))

(defn- build [lists]
  ;; the cache opts let the prebuilt engine reuse a fresh local cache (offline-friendly) or refetch+rewrite
  (let [opts #js {:path  (engine-cache-path)
                  :read  (fn [p] (.readFile fsp p))
                  :write (fn [p data] (.writeFile fsp p data))}]
    (if (= lists :ads-only)
      (.fromPrebuiltAdsOnly ElectronBlocker js/fetch opts)
      (.fromPrebuiltAdsAndTracking ElectronBlocker js/fetch opts))))

(defn- enable!  [^js b ^js sess] (when (and b sess) (.enableBlockingInSession b sess)))
(defn- disable! [^js b ^js sess] (when (and b sess) (try (.disableBlockingInSession b sess) (catch :default _ nil))))

;; push refresh status to the app renderer (mirrors vinary.main.extensions/result!) so the dialog can show
;; "Updating… → ✓ Updated / ⚠ Offline / ✗ error"
(defn- result! [m] (when-let [^js wc (:wc @state)] (.send wc "vv:adblock-status" (clj->js m))))

(defn refresh!
  "(Re)build the engine (fetch + re-serialize to cache, or fall back to cache / empty) and (re)enable it
   on the session when enabled. Disables the PREVIOUS engine BEFORE enabling the new one — `build` returns a
   fresh ElectronBlocker whose per-instance idempotency guard is empty, so enabling it while the old engine's
   process-global `ipcMain.handle('@ghostery/adblocker/inject-cosmetic-filters')` is still registered throws
   'Attempted to register a second handler'; disabling first calls `ipcMain.removeHandler` so re-registration
   is clean. Returns a Promise that never rejects (terminal catch), so callers/scheduler can't leak one."
  []
  (let [{:keys [sess enabled? lists]} @state
        offline? (atom false)]
    (result! {:status :updating})
    (-> (build lists)
        (.catch (fn [_] (reset! offline? true) (.parse ElectronBlocker "")))   ; offline → empty engine (no-op)
        (.then (fn [^js b]
                 (let [old (:blocker @state)]
                   (when (and old (not= old b)) (disable! old sess))   ; remove old global handlers first
                   (swap! state assoc :blocker b)
                   (when enabled? (enable! b sess))
                   (if @offline?
                     (result! {:status :offline})                       ; fetch failed → using cached/empty engine
                     (result! {:status :ok :last-updated (js/Date.now)}))
                   b)))
        (.catch (fn [e]
                  (js/console.error "[adblock] refresh failed:" e)
                  (result! {:status :error :error (str (.-message e))})
                  nil)))))

(defn set-enabled! [on?]
  (let [{:keys [blocker sess]} @state]
    (swap! state assoc :enabled? on?)
    (if on? (enable! blocker sess) (disable! blocker sess))))

(defn set-lists! [lists]
  (swap! state assoc :lists lists)
  (refresh!))

(defn- start-scheduler! [every-hours]
  (when-let [t (:timer @state)] (js/clearInterval t))
  (swap! state assoc :timer (js/setInterval refresh! (* (max 1 (or every-hours 24)) 3600000))))

(defn init!
  "Initialize ad-blocking from persisted prefs ({:enabled? :lists :update-every-hours}). `win` supplies the
   app-renderer webContents for pushing refresh status on vv:adblock-status."
  [^js win prefs]
  (let [sess (.fromPartition session "persist:vinary-web")]
    (swap! state assoc :sess sess :wc (.-webContents win) :enabled? (:enabled? prefs) :lists (:lists prefs))
    (-> (refresh!) (.catch (fn [_] nil)))
    (start-scheduler! (:update-every-hours prefs))
    (when-not @inited
      (reset! inited true)
      (.on ipcMain "vv:adblock-set-enabled" (fn [_e on?] (set-enabled! (boolean on?))))
      (.on ipcMain "vv:adblock-set-lists"   (fn [_e kw] (set-lists! (keyword kw))))
      (.on ipcMain "vv:adblock-refresh"     (fn [_e] (refresh!))))))
