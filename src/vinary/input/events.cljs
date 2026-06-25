(ns vinary.input.events
  "re-frame events for keybinding input: modal state, the pending key-sequence, the command palette, and
   receiving the user keymap config from main. (State pattern: the modal FSM; all ephemeral → app-db.)"
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [cljs.reader :as reader]
            [vinary.input.keymap :as keymap]
            [vinary.input.fx]))

;; ---- modal + input-focus state ----
(rf/reg-event-db :input/set-mode     (fn [db [_ mode]] (assoc-in db [:ui :input :mode] mode)))
(rf/reg-event-db :input/set-sequence (fn [db [_ s]]    (assoc-in db [:ui :input :sequence] (vec s))))
(rf/reg-event-db :input/set-in-input (fn [db [_ v]]   (assoc-in db [:ui :input :in-input?] (boolean v))))
(rf/reg-event-db :input/set-timeout-id (fn [db [_ id]] (assoc-in db [:ui :input :timeout-id] id)))

;; ---- pending key-sequence (chord/leader prefixes) ----
(rf/reg-event-fx
 :input/push-sequence
 (fn [{:keys [db]} [_ token timeout-ms]]
   {:db (update-in db [:ui :input :sequence] (fnil conj []) token)
    :fx [[:input/cancel-timeout (get-in db [:ui :input :timeout-id])]
         [:input/arm-timeout timeout-ms]]}))

(rf/reg-event-fx
 :input/reset-sequence
 (fn [{:keys [db]} _]
   {:db (-> db (assoc-in [:ui :input :sequence] []) (assoc-in [:ui :input :count] nil))
    :fx [[:input/cancel-timeout (get-in db [:ui :input :timeout-id])]]}))

(rf/reg-event-db
 :input/timeout
 (fn [db _] (-> db (assoc-in [:ui :input :sequence] []) (assoc-in [:ui :input :count] nil))))

;; ---- universal escape/cancel ----
(rf/reg-event-fx
 :input/escape
 (fn [{:keys [db]} _]
   (let [palette? (get-in db [:ui :palette :open?])
         find?    (get-in db [:ui :find :visible?])
         mode     (get-in db [:ui :input :mode])]
     (cond
       palette?           {:fx [[:dispatch [:palette/close]]]}
       find?              {:fx [[:dispatch [:find/close]]]}
       (not= mode :normal) {:db (assoc-in db [:ui :input :mode] :normal)}
       :else              {:db (-> db (assoc-in [:ui :input :sequence] [])
                                   (assoc-in [:ui :input :count] nil))}))))

;; ---- user keymap config from main (over the Mediator IPC) ----
(rf/reg-event-db
 :keymap/config-received
 (fn [db [_ payload]]
   ;; payload is raw EDN TEXT from main (parse it) or a map from the dev hook
   (let [cfg (cond
               (map? payload)                                   payload
               (and (string? payload) (not (str/blank? payload)))
               (try (reader/read-string payload) (catch :default _ nil))
               :else                                            nil)]
     (if (map? cfg) (keymap/install-user! cfg) (keymap/install! :default))
     (assoc-in db [:ui :input :mode] (keymap/initial-mode)))))

;; ---- command palette / fuzzy finder ----
(rf/reg-event-db
 :palette/open
 (fn [db [_ {:keys [source prefix]}]]
   (-> db (assoc-in [:ui :palette :open?] true)
          (assoc-in [:ui :palette :source] (or source :command))
          (assoc-in [:ui :palette :prefix] (or prefix ""))
          (assoc-in [:ui :palette :query] "")
          (assoc-in [:ui :palette :selected] 0))))

(rf/reg-event-db :palette/close (fn [db _] (assoc-in db [:ui :palette :open?] false)))

(rf/reg-event-db
 :palette/set-query
 (fn [db [_ q]] (-> db (assoc-in [:ui :palette :query] q) (assoc-in [:ui :palette :selected] 0))))

(rf/reg-event-db
 :palette/move
 (fn [db [_ dir n]] (update-in db [:ui :palette :selected] #(mod (+ (or % 0) dir) (max 1 n)))))
