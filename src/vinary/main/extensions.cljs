(ns vinary.main.extensions
  "Scoped Chrome-extension runtime (GPL-free). Electron 42 provides some extension APIs natively
   (chrome.runtime/storage/action/tabs/i18n + content scripts + MV3 service workers). For APIs Electron does
   NOT implement (windows/webNavigation/cookies/notifications/contextMenus/privacy) we inject an inert
   chrome.* polyfill into extension page main worlds via a self-contained session preload
   (resources/ext-chrome-polyfill.js). The frame/popup path works; real/heavy password-manager MV3 service
   workers such as LastPass can still evaluate in a separate V8 realm before the missing APIs are present, so
   this runtime must not claim LastPass-class worker support. We add: Web-Store install +
   periodic auto-update (electron-chrome-web-store, MIT), reading each extension's MANIFEST to drive a
   first-party toolbar (Electron renders no browser-action UI), and a popup WebContentsView host. All
   loading happens on the persistent web session (persist:vinary-web). Provenance: Web-Store-only
   (allowUnpackedExtensions false) and in-page 'Add to Chrome' is denied — installs go through our UI."
  (:require ["electron-chrome-web-store" :refer [installChromeWebStore installExtension
                                                 uninstallExtension updateExtensions]]
            ["electron" :refer [ipcMain session app BrowserWindow]]
            ["fs" :as fs]
            ["path" :as path]
            [vinary.main.ext-util :as eu]
            [vinary.main.windows :as windows]
            [vinary.main.ext-popup :as popup]))

;; state: the web session, the app window's webContents (for state pushes), disabled-ids, id→unpacked path
(defonce ^:private state (atom {:sess nil :wc nil :disabled-ids #{} :enabled? true :paths {}}))
(defonce ^:private inited (atom false))

(defn- ext-session [] (.fromPartition session "persist:vinary-web"))
(defn- extensions-path [] (path/join (.getPath app "userData") "Extensions"))
(defn- polyfill-preload-path [] (path/join js/__dirname ".." ".." "resources" "ext-chrome-polyfill.js"))

;; ---- icon reading (untrusted manifest → magic-byte sniff + size cap, render as a data URL) ----
(defn- sniff-mime [^js buf]
  (let [n (.-length buf)
        b (fn [i] (aget buf i))]
    (cond
      (and (>= n 8)  (= 0x89 (b 0)) (= 0x50 (b 1)) (= 0x4E (b 2)) (= 0x47 (b 3))) "image/png"
      (and (>= n 3)  (= 0xFF (b 0)) (= 0xD8 (b 1)) (= 0xFF (b 2)))                 "image/jpeg"
      (and (>= n 12) (= 0x52 (b 0)) (= 0x49 (b 1)) (= 0x46 (b 2)) (= 0x46 (b 3))
                     (= 0x57 (b 8)) (= 0x45 (b 9)) (= 0x42 (b 10)) (= 0x50 (b 11))) "image/webp"
      :else nil)))

(defn- icon-data-url [^js ext icon-rel]
  (when (seq (str icon-rel))
    (try
      (let [^js buf (.readFileSync fs (path/join (.-path ext) icon-rel))]
        (when (< (.-length buf) 2000000)              ; 2MB cap (DoS guard on a hostile manifest)
          (when-let [mime (sniff-mime buf)]
            (str "data:" mime ";base64," (.toString buf "base64")))))
      (catch :default _ nil))))

;; ---- build the installed-extension list for the renderer ----
(defn- installed-list []
  (let [{:keys [sess disabled-ids]} @state]
    (mapv (fn [^js ext]
            (let [mani (js->clj (.-manifest ext))      ; STRING keys (no keywordize) for ext-util
                  am   (eu/action-model mani)]
              {:id        (.-id ext)
               :name      (.-name ext)
               :version   (.-version ext)
               :enabled?  (not (contains? disabled-ids (.-id ext)))
               :action    {:title      (str (:title am))
                           :icon       (icon-data-url ext (:icon-rel am))
                           :popup      (:popup am)
                           :has-popup? (:has-popup? am)}}))
          (array-seq (.getAllExtensions ^js (.-extensions sess))))))

;; extension runtime state (shared across the whole app) + action results go to the ACTIVE app window (the one
;; the user has the Extensions UI focused in), falling back to the window captured at init!.
(defn- push-state! []
  (when-let [^js wc (or (windows/active-wc) (:wc @state))]
    (.send wc "vv:ext-state"
           (pr-str {:enabled?  (:enabled? @state)
                    :installed (installed-list)}))))

(defn- result! [channel m]
  (when-let [^js wc (or (windows/active-wc) (:wc @state))] (.send wc channel (clj->js m))))

;; ---- runtime actions ----
(defn- cache-path! [^js ext] (swap! state update :paths assoc (.-id ext) (.-path ext)))

(defn- set-enabled! [id on?]
  (let [{:keys [^js sess paths]} @state]
    (swap! state update :disabled-ids (if on? disj conj) id)
    (try
      (if on?
        (when-let [p (get paths id)] (-> (.loadExtension ^js (.-extensions sess) p) (.then cache-path!)))
        (.removeExtension ^js (.-extensions sess) id))
      (catch :default _ nil))
    (push-state!)))

(defn- install! [id-or-url]
  (let [id (eu/parse-store-id id-or-url)]
    (if-not id
      (result! "vv:ext-install-result" {:ok false :error "Not a valid Chrome Web Store URL or ID."})
      (-> (installExtension id #js {:session (:sess @state) :extensionsPath (extensions-path)})
          (.then (fn [^js ext] (cache-path! ext) (result! "vv:ext-install-result" {:ok true :id (.-id ext) :name (.-name ext)}) (push-state!)))
          (.catch (fn [e] (result! "vv:ext-install-result" {:ok false :id id :error (str (.-message e))})))))))

(defn- remove! [id]
  (-> (uninstallExtension id #js {:session (:sess @state) :extensionsPath (extensions-path)})
      (.then (fn [] (swap! state update :paths dissoc id) (push-state!)))
      (.catch (fn [_] nil))))

(defn- check-updates! []
  (result! "vv:ext-update-result" {:checking? true})
  (-> (updateExtensions (:sess @state))
      (.then (fn [] (result! "vv:ext-update-result" {:checking? false :ok true}) (push-state!)))
      (.catch (fn [e] (result! "vv:ext-update-result" {:checking? false :ok false :error (str (.-message e))})))))

;; ---- init ----
(defn init!
  "Set up the extension runtime on persist:vinary-web from persisted prefs ({:enabled? :disabled-ids}).
   `win` provides the app window's webContents for state pushes. Idempotent."
  [^js win prefs]
  (let [sess (ext-session)]
    (swap! state assoc :sess sess :wc (.-webContents win)
           :enabled? (get-in prefs [:extensions :enabled?])
           :disabled-ids (set (get-in prefs [:extensions :disabled-ids])))
    ;; register load/unload listeners BEFORE installChromeWebStore (which loads stored extensions)
    (.on ^js (.-extensions sess) "extension-loaded"   (fn [_e ^js ext] (cache-path! ext) (push-state!)))
    (.on ^js (.-extensions sess) "extension-unloaded" (fn [_e ^js _ext] (push-state!)))
    ;; chrome.* polyfill for APIs Electron lacks (windows/webNavigation/cookies/…), registered BEFORE
    ;; extensions load. The frame/popup path works. The service-worker registration is retained for the
    ;; narrow probe path documented in ADR-0015, but real LastPass-class MV3 workers can still run in a
    ;; different V8 realm whose native chrome object lacks those APIs.
    (when-not @inited
      (let [pf (polyfill-preload-path)]
        (try (.registerPreloadScript sess #js {:type "service-worker" :filePath pf})
             (.registerPreloadScript sess #js {:type "frame"          :filePath pf})
             (catch :default e (js/console.error "[extensions] chrome.* polyfill preload registration failed" e)))))
    (-> (installChromeWebStore
         #js {:session sess
              :extensionsPath (extensions-path)
              :loadExtensions true               ; reload installed extensions at startup
              :allowUnpackedExtensions false      ; provenance: Web-Store-only
              :autoUpdate (get-in prefs [:extensions :enabled?])   ; background updates (startup + ~5h)
              ;; deny in-page 'Add to Chrome' — installs go through our Extensions UI (installExtension)
              :beforeInstall (fn [_d] (js/Promise.resolve #js {:action "deny"}))})
        (.then (fn []
                 ;; reconcile: unload any user-disabled extensions after the web-store loads them
                 (doseq [id (:to-unload (eu/reconcile-enabled
                                         (mapv #(.-id ^js %) (array-seq (.getAllExtensions ^js (.-extensions sess))))
                                         (:disabled-ids @state)))]
                   (try (.removeExtension ^js (.-extensions sess) id) (catch :default _ nil)))
                 (push-state!)))
        (.catch (fn [e] (js/console.error "[extensions] init failed" e))))
    (when-not @inited
      (reset! inited true)
      (.on ipcMain "vv:ext-state-request"  (fn [_e] (push-state!)))
      (.on ipcMain "vv:ext-install"        (fn [_e id-or-url] (install! id-or-url)))
      (.on ipcMain "vv:ext-remove"         (fn [_e id] (remove! id)))
      (.on ipcMain "vv:ext-set-enabled"    (fn [_e ^js p] (set-enabled! (.-id p) (.-on p))))
      (.on ipcMain "vv:ext-check-updates"  (fn [_e] (check-updates!)))
      (.on ipcMain "vv:ext-action-clicked" (fn [^js e ^js p]
                                             ;; anchor the popup to the window whose toolbar icon was clicked
                                             (popup/open! (windows/from-wc (.-sender e)) (:sess @state) (.-id p) (.-popup p) (js->clj (.-bounds p) :keywordize-keys true))))
      (.on ipcMain "vv:ext-popup-close"    (fn [_e] (popup/close!))))))
