(ns vinary.main.ssh
  "Electron/IPC wiring for the SSH/SFTP transport (ssh_transport.js). Configures the transport with its two
   user prompts and the error/status sinks, and owns the vv:ssh-* channels.

   Secrets stay MAIN-side: the host-key trust prompt is a native Electron dialog (no secret, no renderer
   channel), and the password/passphrase/MFA prompt round-trips a NON-secret request to the renderer over
   vv:ssh-prompt; the typed secret comes back on vv:ssh-prompt-reply (the ONLY secret-bearing channel — one
   shot, resolved into a main-memory promise, never persisted or placed in app-db). Mirrors the password
   bridge's secrets-main-only doctrine (passwords.cljs)."
  (:require ["electron" :refer [ipcMain dialog]]
            ["./ssh_transport.js" :as transport]))

(defonce ^:private state (atom {:win nil}))
(defonce ^:private inited (atom false))
(defonce ^:private prompt-seq (atom 0))
(defonce ^:private pending (atom {}))         ; promptId → resolve-fn (a live secret-prompt promise)

(defn- app-wc ^js [] (some-> ^js (:win @state) .-webContents))

;; Native, main-side, secretless yes/no — never *Sync (that would block main during the connect).
(defn- prompt-host-key [info]
  (let [^js win (:win @state)
        m       (js->clj info :keywordize-keys true)
        detail  (str "Host: " (:host m) (when (:port m) (str ":" (:port m)))
                     "\nKey type: " (:keyType m)
                     "\nFingerprint: " (:fingerprint m)
                     "\n\nThe authenticity of this host cannot be established. Trust this key and add it to "
                     "your ~/.ssh/known_hosts?")]
    (-> (.showMessageBox dialog win
                         (clj->js {:type "warning"
                                   :buttons ["Cancel" "Trust"]
                                   :defaultId 0
                                   :cancelId 0
                                   :noLink true
                                   :title "Unknown SSH host key"
                                   :message (str "Trust the SSH host key for " (:host m) "?")
                                   :detail detail}))
        (.then (fn [^js r] (= 1 (.-response r)))))))

;; Renderer secret prompt: mint an id, stash the resolver, send a NON-secret request; the secret returns on
;; vv:ssh-prompt-reply. Resolves nil (cancel) when there is no window to prompt.
(defn- prompt-secret [req]
  (js/Promise.
   (fn [resolve _reject]
     (if-let [^js wc (app-wc)]
       (let [id  (str "ssh-" (swap! prompt-seq inc))
             obj (js->clj req :keywordize-keys true)]
         (swap! pending assoc id resolve)
         (.send wc "vv:ssh-prompt" (clj->js (assoc obj :promptId id))))
       (resolve nil)))))

(defn- reply! [id secret]
  (when-let [resolve (get @pending id)]
    (swap! pending dissoc id)
    (resolve secret)))

(defn init! [^js win]
  (swap! state assoc :win win)
  (.configure transport
              (clj->js {:promptHostKey prompt-host-key
                        :promptSecret  prompt-secret
                        :onError  (fn [info] (when-let [^js wc (app-wc)] (.send wc "vv:ssh-error"  info)))
                        :onStatus (fn [info] (when-let [^js wc (app-wc)] (.send wc "vv:ssh-status" info)))}))
  (when-not @inited
    (reset! inited true)
    (.on ipcMain "vv:ssh-prompt-reply"
         (fn [_e ^js payload]
           (let [p (js->clj payload :keywordize-keys true)]
             (reply! (:promptId p) (:secret p)))))
    (.on ipcMain "vv:ssh-close-connection"
         (fn [_e connKey] (.closeConnection transport connKey)))))

(defn shutdown! [] (try (.closeAll transport) (catch :default _ nil)))
