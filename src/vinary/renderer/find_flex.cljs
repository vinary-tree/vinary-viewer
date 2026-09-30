(ns vinary.renderer.find-flex
  "The file://-safe worker client for the pinned Vinary Tree search runtime."
  (:require [goog.object :as gobj]))

(defonce ^:private state
  (atom {:worker nil :loading nil :ready-resolve nil :ready-reject nil
         :key nil :doc-id 0 :serial 0 :indexes 0 :pending {}}))

(defn index-count [] (:indexes @state))

(defn- asset [name]
  (.-href (js/URL. (str "js/" name) (.-baseURI js/document))))

(defn- settle! [id success? value]
  (when-let [{:keys [resolve reject]} (get-in @state [:pending id])]
    (swap! state update :pending dissoc id)
    ((if success? resolve reject) value)))

(defn- on-message! [^js message]
  (case (gobj/get message "type")
    "ready" (when-let [resolve (:ready-resolve @state)]
              (swap! state dissoc :ready-resolve :ready-reject)
              (resolve (:worker @state)))
    "results" (settle! (gobj/get message "requestId") true
                       (js->clj (gobj/get message "results") :keywordize-keys true))
    "error" (let [error (js/Error. (gobj/get message "message"))]
              (when (= "index" (gobj/get message "operation"))
                (swap! state assoc :key nil))
              (if-let [reject (:ready-reject @state)]
                (do (swap! state dissoc :ready-resolve :ready-reject)
                    (reject error))
                (settle! (gobj/get message "requestId") false error)))
    nil))

(defn- ensure-worker! []
  (or (:loading @state)
      (let [promise
            (-> (js/Promise.all
                 #js [(-> (js/fetch (asset "find-worker.js")) (.then #(.text %)))
                      (-> (js/fetch (asset "find-wasm.wasm")) (.then #(.arrayBuffer %)))])
                (.then (fn [^js assets]
                         (let [source (aget assets 0)
                               bytes (aget assets 1)
                               blob (js/Blob. #js [source] #js {:type "text/javascript"})
                               url (.createObjectURL js/URL blob)
                               worker (js/Worker. url #js {:type "module"})]
                           (.revokeObjectURL js/URL url)
                           (swap! state assoc :worker worker)
                           (set! (.-onmessage worker) #(on-message! (.-data %)))
                           (set! (.-onerror worker)
                                 (fn [e]
                                   (let [error (js/Error. (or (.-message e) "Search worker failed"))]
                                     (when-let [reject (:ready-reject @state)] (reject error))
                                     (doseq [id (keys (:pending @state))] (settle! id false error)))))
                           (js/Promise.
                            (fn [resolve reject]
                              (swap! state assoc :ready-resolve resolve :ready-reject reject)
                              (.postMessage worker #js {:type "init" :wasm bytes} #js [bytes]))))))
                (.catch (fn [error]
                          (swap! state assoc :loading nil :worker nil)
                          (throw error))))]
        (swap! state assoc :loading promise)
        promise)))

(defn cancel! []
  (let [pending (:pending @state)]
    (swap! state (fn [s] (-> s (update :serial inc) (assoc :pending {}))))
    (doseq [[_ {:keys [resolve]}] pending] (resolve nil))
    (when-let [^js worker (:worker @state)]
      (.postMessage worker #js {:type "cancel"}))))

(defn search!
  "Promise of [{:anchor :spans :kind}], or nil when superseded. `key` identifies an immutable document snapshot."
  [key text kind no-phonetic-ranges query mode phonetic?]
  (-> (ensure-worker!)
      (.then
       (fn [^js worker]
         (js/Promise.
          (fn [resolve reject]
            (let [new-doc? (not= key (:key @state))
                  _ (when new-doc? (swap! state (fn [s] (-> s (assoc :key key)
                                                          (update :doc-id inc)
                                                          (update :indexes inc)))))
                  {:keys [doc-id]} @state
                  id (:serial (swap! state update :serial inc))]
              (doseq [[old-id {:keys [resolve]}] (:pending @state)]
                (resolve nil)
                (swap! state update :pending dissoc old-id))
              (swap! state assoc-in [:pending id] {:resolve resolve :reject reject})
              (when new-doc?
                (.postMessage worker
                              (clj->js {:type "index" :requestId id :docId doc-id :text text :kind kind
                                        :noPhoneticRanges no-phonetic-ranges})))
              (.postMessage worker
                            (clj->js {:type "search" :requestId id :docId doc-id
                                      :query query :mode mode :phonetic phonetic?})))))))))
