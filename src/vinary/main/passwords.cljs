(ns vinary.main.passwords
  "Native password-manager bridge. Provider CLIs run only in the trusted main process; the renderer sees
   provider state and sanitized item metadata; revealed secrets are sent directly to the isolated web-view
   preload for filling. Save prompts use short-lived main-memory tokens so passwords never enter app-db."
  (:require ["electron" :refer [ipcMain]]
            [clojure.string :as str]
            [vinary.main.password-adapters :as adapters]
            [vinary.main.password-config :as config]
            [vinary.main.password-util :as pu]
            [vinary.main.web :as web]))

(defonce ^:private state
  (atom {:win nil
         :providers []
         :statuses {}
         :forms {:count 0}
         :items []
         :busy? false
         :error nil}))

(defonce ^:private inited (atom false))
(defonce ^:private token-seq (atom 0))
(defonce ^:private candidates (atom {}))
(defonce ^:private last-candidate (atom {:fingerprint nil :time 0}))

(def candidate-ttl-ms (* 2 60 1000))

(defn- app-wc ^js [] (some-> ^js (:win @state) .-webContents))

(defn- normalize-kind [kind]
  (if (keyword? kind) kind (keyword (or kind "json-command"))))

(defn- configured-providers []
  (let [cfg       (config/load-config)
        disabled  (set (:disabled-provider-ids cfg))
        overrides (:builtins cfg)
        builtins  (mapv (fn [p]
                          (let [o (get overrides (:id p))]
                            (-> (merge p (select-keys o [:label :bin :vault :enabled? :save-supported?]))
                                (assoc :kind (normalize-kind (:kind p)))
                                (update :enabled? #(if (nil? %) true (boolean %))))))
                        adapters/builtin-providers)
        custom    (mapv (fn [p]
                          (-> p
                              (assoc :kind (normalize-kind (:kind p))
                                     :save-supported? (boolean (or (:save-supported? p)
                                                                   (get-in p [:commands :save :stdin-json?]))))))
                        (:providers cfg))]
    (if (:enabled? cfg)
      (->> (concat builtins custom)
           (remove #(contains? disabled (:id %)))
           (filter :enabled?)
           vec)
      [])))

(defn- public-providers []
  (let [{:keys [providers statuses]} @state]
    (mapv #(pu/provider-public % (get statuses (:id %) {:status "unavailable"})) providers)))

(defn- push-state! []
  (when-let [^js wc (app-wc)]
    (.send wc "vv:password-state"
           (clj->js {:providers (public-providers)
                     :forms (:forms @state)
                     :busy? (:busy? @state)
                     :error (:error @state)}))))

(defn- result! [m]
  (when-let [^js wc (app-wc)]
    (.send wc "vv:password-result" (clj->js m))))

(defn- push-items! [url items]
  (when-let [^js wc (app-wc)]
    (.send wc "vv:password-items"
           (clj->js {:url url
                     :origin (pu/url-origin url)
                     :items (vec items)}))))

(defn- status-map [statuses]
  (into {} (map (fn [s] [(:id s) s]) statuses)))

(defn refresh-status! []
  (let [providers (configured-providers)]
    (swap! state assoc :providers providers :error nil)
    (push-state!)
    (-> (js/Promise.all (clj->js (map adapters/provider-status providers)))
        (.then (fn [arr]
                 (let [statuses (vec (array-seq arr))]
                   (swap! state assoc :statuses (status-map statuses) :error nil)
                   (push-state!)
                   statuses)))
        (.catch (fn [e]
                  (swap! state assoc :error (or (.-message e) "Could not refresh password providers."))
                  (push-state!)
                  [])))))

(defn- ready-providers []
  (let [{:keys [providers statuses]} @state]
    (filterv #(pu/ready-status? (get-in statuses [(:id %) :status])) providers)))

(defn- merge-failed-statuses! [results]
  (doseq [r results
          :let [s (:status r)]
          :when (map? s)]
    (swap! state assoc-in [:statuses (:id s)] s)))

(defn search! [url]
  (let [url (str url)]
    (if-not (pu/web-url? url)
      (do (swap! state assoc :items [] :busy? false :error nil)
          (push-items! url [])
          (push-state!))
      (do
        (swap! state assoc :busy? true :items [] :error nil)
        (push-state!)
        (-> (refresh-status!)
            (.then (fn [_]
                     (let [providers (ready-providers)]
                       (if (empty? providers)
                         (do
                           (swap! state assoc :busy? false :items [])
                           (push-items! url [])
                           (push-state!))
                         (-> (js/Promise.all (clj->js (map #(adapters/search % url) providers)))
                             (.then (fn [arr]
                                      (let [results (vec (array-seq arr))
                                            items   (->> results (mapcat :items) (sort-by (comp - :score)) vec)]
                                        (merge-failed-statuses! results)
                                        (swap! state assoc :busy? false :items items)
                                        (push-items! url items)
                                        (push-state!))))
                             (.catch (fn [e]
                                       (swap! state assoc :busy? false
                                              :error (or (.-message e) "Password search failed."))
                                       (push-items! url [])
                                       (push-state!)))))))))))))

(defn- provider-by-id [id]
  (first (filter #(= (:id %) (str id)) (:providers @state))))

(defn- status-from-adapter-result [provider result]
  (let [s (:status result)]
    (if (map? s)
      (merge {:id (:id provider) :label (:label provider)}
             s)
      {:id (:id provider)
       :label (:label provider)
       :status (pu/normalize-status s)
       :message (or (:message result) "")})))

(defn fill! [item]
  (let [item (if (map? item) item (js->clj item :keywordize-keys true))
        p    (provider-by-id (:provider item))]
    (if-not p
      (result! {:ok false :action "fill" :message "Unknown password provider."})
      (do
        (swap! state assoc :busy? true :error nil)
        (push-state!)
        (-> (adapters/reveal p item)
            (.then (fn [r]
                     (swap! state assoc :busy? false)
                     (if (:ok? r)
                       (do
                         (web/fill-password! (:credentials r))
                         (result! {:ok true :action "fill" :message "Filled login fields."}))
                       (do
                         (swap! state assoc-in [:statuses (:id p)] (status-from-adapter-result p r))
                         (result! {:ok false :action "fill"
                                   :message (or (:message r) "Could not reveal that item.")})))
                     (push-state!)))
            (.catch (fn [e]
                      (swap! state assoc :busy? false :error (or (.-message e) "Password fill failed."))
                      (result! {:ok false :action "fill" :message "Password fill failed."})
                      (push-state!))))))))

(defn- prune-candidates! []
  (let [now (pu/now-ms)]
    (swap! candidates
           (fn [m] (into {} (filter (fn [[_ c]] (> (:expires-at c) now)) m))))))

(defn- next-token []
  (str "pw-" (swap! token-seq inc) "-" (pu/now-ms)))

(defn- send-save-prompt! [token candidate]
  (when-let [^js wc (app-wc)]
    (.send wc "vv:password-save-prompt"
           (clj->js {:token token
                     :url (:url candidate)
                     :origin (pu/url-origin (:url candidate))
                     :username (or (:username candidate) "")
                     :providers (filterv :save-supported? (public-providers))}))))

(defn save-candidate! [payload]
  (let [m        (if (map? payload) payload (js->clj payload :keywordize-keys true))
        url      (or (pu/blank->nil (:url m)) (web/active-url))
        username (or (pu/blank->nil (:username m)) "")
        password (pu/blank->nil (:password m))
        fp       (str (pu/url-origin url) "|" username "|" (hash password))
        now      (pu/now-ms)]
    (when (and (pu/web-url? url) password)
      (prune-candidates!)
      (when-not (and (= fp (:fingerprint @last-candidate))
                     (< (- now (:time @last-candidate)) 1500))
        (reset! last-candidate {:fingerprint fp :time now})
        (let [token     (next-token)
              candidate {:url url
                         :title (str "Login for " (or (pu/url-host url) url))
                         :username username
                         :password password
                         :expires-at (+ now candidate-ttl-ms)}]
          (swap! candidates assoc token candidate)
          (send-save-prompt! token candidate))))))

(defn dismiss-save! [token]
  (swap! candidates dissoc (str token))
  (when-let [^js wc (app-wc)]
    (.send wc "vv:password-save-prompt" (clj->js nil))))

(defn save! [payload]
  (let [m           (if (map? payload) payload (js->clj payload :keywordize-keys true))
        token       (str (:token m))
        provider-id (str (:provider m))
        provider    (provider-by-id provider-id)]
    (prune-candidates!)
    (if-let [candidate (get @candidates token)]
      (if-not provider
        (result! {:ok false :action "save" :message "Unknown password provider."})
        (do
          (swap! state assoc :busy? true :error nil)
          (push-state!)
          (-> (adapters/save provider candidate)
              (.then (fn [r]
                       (swap! state assoc :busy? false)
                       (if (:ok? r)
                         (do
                           (swap! candidates dissoc token)
                           (when-let [^js wc (app-wc)]
                             (.send wc "vv:password-save-prompt" (clj->js nil)))
                           (result! {:ok true :action "save"
                                     :message (or (:message r) "Saved login.")}))
                         (do
                           (when (:status r)
                             (swap! state assoc-in [:statuses (:id provider)]
                                    (status-from-adapter-result provider r)))
                           (result! {:ok false :action "save"
                                     :message (or (:message r) "Could not save login.")})))
                       (push-state!)))
              (.catch (fn [e]
                        (swap! state assoc :busy? false :error (or (.-message e) "Password save failed."))
                        (result! {:ok false :action "save" :message "Password save failed."})
                        (push-state!))))))
      (result! {:ok false :action "save" :message "That save prompt expired."}))))

(defn web-forms! [payload]
  (let [m (if (map? payload) payload (js->clj payload :keywordize-keys true))
        url (or (pu/blank->nil (:url m)) (web/active-url))]
    (swap! state assoc :forms {:url url
                               :origin (pu/url-origin url)
                               :count (or (:count m) 0)
                               :has-password? (boolean (:hasPassword m))})
    (push-state!)))

(defn init! [^js win]
  (swap! state assoc :win win :providers (configured-providers))
  (when-not @inited
    (reset! inited true)
    (.on ipcMain "vv:password-state-request" (fn [_e] (refresh-status!)))
    (.on ipcMain "vv:password-search"        (fn [_e url] (search! url)))
    (.on ipcMain "vv:password-fill"          (fn [_e item] (fill! item)))
    (.on ipcMain "vv:password-save"          (fn [_e payload] (save! payload)))
    (.on ipcMain "vv:password-dismiss-save"  (fn [_e token] (dismiss-save! token)))
    ;; web-view preload -> main. Only accept messages from the active WebContentsView.
    (.on ipcMain "vv:password-forms"
         (fn [^js e payload]
           (when (web/active-webcontents? (.-sender e))
             (web-forms! payload))))
    (.on ipcMain "vv:password-save-candidate"
         (fn [^js e payload]
           (when (web/active-webcontents? (.-sender e))
             (save-candidate! payload))))))
