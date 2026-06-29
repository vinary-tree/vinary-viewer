(ns vinary.input.events
  "re-frame events for keybinding input: modal state, the pending key-sequence, the command palette, and
   receiving the user keymap config from main. (State pattern: the modal FSM; all ephemeral → app-db.)"
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [cljs.reader :as reader]
            [vinary.input.keymap :as keymap]
            [vinary.input.keymaps-registry :as registry]
            [vinary.input.kbedit-history :as hist]
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
(rf/reg-event-fx
 :keymap/config-received
 (fn [{:keys [db]} [_ payload]]
   ;; payload is raw EDN TEXT from main (parse it) or a map from the dev hook → the registry envelope
   (let [cfg (cond
               (map? payload)                                   payload
               (and (string? payload) (not (str/blank? payload)))
               (try (reader/read-string payload) (catch :default _ nil))
               :else                                            nil)
         db' (assoc-in db [:ui :keymaps] (registry/normalize-config cfg))
         id  (registry/active-id db')]
     ;; set the input mode in the SAME :db (re-frame applies :db before :fx) so the persisted set is fully
     ;; live at boot — keymap AND mode — with no one-tick :insert window; pass the id so the install effect
     ;; needn't re-read the active-id. This is the init bug: a modal (vim) set needs its :normal mode set now.
     {:db (assoc-in db' [:ui :input :mode] (registry/initial-mode-for db' id))
      :fx [[:keymap/install-active id]]})))

;; switch the active keymap set (Settings ▸ Key Bindings radio) → install it live + persist the registry
(rf/reg-event-fx
 :keymap/select
 (fn [{:keys [db]} [_ id]]
   (let [db' (registry/select db id)]
     ;; mirror config-received: set the new set's initial mode synchronously + install by id
     {:db (assoc-in db' [:ui :input :mode] (registry/initial-mode-for db' id))
      :fx [[:keymap/install-active id] [:vv/save-keymap (registry/->edn db')]]})))

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

;; ================= key-binding editor (vinary.ui.keybindings-editor) =================
;; every edit goes through :kbedit/do (a vinary.input.kbedit-history command) so it is undoable; after
;; each commit the active set is re-installed live + the registry is persisted (debounced).
(defn- kb-commit-fx [db'] [[:keymap/install-active (registry/active-id db')] [:keymap/persist (registry/->edn db')]])

(defn- kb-push [db cmd]
  (let [db' (hist/apply-cmd db cmd)]
    [db' (-> db' (update-in [:ui :kbedit :undo] conj cmd) (assoc-in [:ui :kbedit :redo] []))]))

(rf/reg-event-db
 :kbedit/open
 (fn [db _]
   (-> db (assoc-in [:ui :kbedit :open?]   true)
          (assoc-in [:ui :kbedit :sel]     (or (first (registry/order db)) (registry/active-id db)))
          (assoc-in [:ui :kbedit :ctx]     nil)
          (assoc-in [:ui :kbedit :editing] nil)
          (assoc-in [:ui :kbedit :capture] nil))))

(rf/reg-event-db
 :kbedit/close
 (fn [db _]
   (-> db (assoc-in [:ui :kbedit :open?]   false)
          (assoc-in [:ui :kbedit :capture] nil)
          (assoc-in [:ui :kbedit :ctx]     nil)
          (assoc-in [:ui :kbedit :editing] nil))))

(rf/reg-event-db :kbedit/select   (fn [db [_ id]] (-> db (assoc-in [:ui :kbedit :sel] id)
                                                      (assoc-in [:ui :kbedit :ctx] nil))))
(rf/reg-event-db :kbedit/ctx-show  (fn [db [_ m]] (assoc-in db [:ui :kbedit :ctx] m)))
(rf/reg-event-db :kbedit/ctx-close (fn [db _]     (assoc-in db [:ui :kbedit :ctx] nil)))

(rf/reg-event-fx
 :kbedit/do
 (fn [{:keys [db]} [_ cmd]]
   (let [[db' db''] (kb-push db cmd)] {:db db'' :fx (kb-commit-fx db')})))

(rf/reg-event-fx
 :kbedit/undo
 (fn [{:keys [db]} _]
   (if-let [cmd (peek (get-in db [:ui :kbedit :undo]))]
     (let [db' (hist/apply-cmd db (hist/invert cmd))]
       {:db (-> db' (update-in [:ui :kbedit :undo] pop) (update-in [:ui :kbedit :redo] conj cmd))
        :fx (kb-commit-fx db')})
     {})))

(rf/reg-event-fx
 :kbedit/redo
 (fn [{:keys [db]} _]
   (if-let [cmd (peek (get-in db [:ui :kbedit :redo]))]
     (let [db' (hist/apply-cmd db cmd)]
       {:db (-> db' (update-in [:ui :kbedit :redo] pop) (update-in [:ui :kbedit :undo] conj cmd))
        :fx (kb-commit-fx db')})
     {})))

(rf/reg-event-fx
 :kbedit/add
 (fn [{:keys [db]} _]
   (let [nm  (registry/fresh-name db)
         cmd {:op :insert-set :name nm :cfg {:extends :default :keymaps {}} :idx (count (registry/order db))}
         [db' db''] (kb-push db cmd)]
     {:db (-> db'' (assoc-in [:ui :kbedit :sel] nm) (assoc-in [:ui :kbedit :editing] nm))
      :fx (kb-commit-fx db')})))

(rf/reg-event-fx
 :kbedit/delete
 (fn [{:keys [db]} [_ id]]
   (if (registry/custom? db id)
     (let [idx (first (keep-indexed #(when (= %2 id) %1) (registry/order db)))
           cmd {:op :remove-set :name id :cfg (get-in db [:ui :keymaps :sets id]) :idx idx}
           [db' db''] (kb-push db cmd)
           sel' (if (= (get-in db [:ui :kbedit :sel]) id)
                  (or (first (registry/order db')) (registry/active-id db'))
                  (get-in db [:ui :kbedit :sel]))]
       {:db (-> db'' (assoc-in [:ui :kbedit :sel] sel') (assoc-in [:ui :kbedit :ctx] nil))
        :fx (kb-commit-fx db')})
     {:db (assoc-in db [:ui :kbedit :ctx] nil)})))

(rf/reg-event-fx
 :kbedit/clone
 (fn [{:keys [db]} [_ id]]
   (let [nm  (registry/clone-name db (registry/display-name db id))
         cfg (if (registry/builtin? id) {:extends (keyword id) :keymaps {}} (get-in db [:ui :keymaps :sets id]))
         cmd {:op :insert-set :name nm :cfg cfg :idx (count (registry/order db))}
         [db' db''] (kb-push db cmd)]
     {:db (-> db'' (assoc-in [:ui :kbedit :sel] nm) (assoc-in [:ui :kbedit :ctx] nil))
      :fx (kb-commit-fx db')})))

(rf/reg-event-fx
 :kbedit/reorder
 (fn [{:keys [db]} [_ id to-idx]]
   (let [from (first (keep-indexed #(when (= %2 id) %1) (registry/order db)))]
     (if (and from to-idx (not= from to-idx))
       (let [cmd {:op :reorder :name id :from-idx from :to-idx to-idx}
             [db' db''] (kb-push db cmd)]
         {:db db'' :fx (kb-commit-fx db')})
       {}))))

;; in-place rename
(rf/reg-event-db :kbedit/begin-rename  (fn [db [_ id]] (cond-> db (registry/custom? db id) (assoc-in [:ui :kbedit :editing] id))))
(rf/reg-event-db :kbedit/cancel-rename (fn [db _] (assoc-in db [:ui :kbedit :editing] nil)))

(rf/reg-event-fx
 :kbedit/commit-rename
 (fn [{:keys [db]} [_ old raw]]
   (let [new (str/trim (or raw ""))]
     (if (and (registry/custom? db old) (not (str/blank? new)) (not= old new)
              (not (contains? (registry/sets db) new)) (not (registry/builtin? new)))
       (let [cmd {:op :rename-set :old old :new new}
             [db' db''] (kb-push db cmd)]
         {:db (-> db'' (assoc-in [:ui :kbedit :editing] nil)
                  (cond-> (= (get-in db [:ui :kbedit :sel]) old) (assoc-in [:ui :kbedit :sel] new)))
          :fx (kb-commit-fx db')})
       {:db (assoc-in db [:ui :kbedit :editing] nil)}))))

;; bindings: set / clear a chord-sequence on a custom set (built-ins are read-only)
(rf/reg-event-fx
 :kbedit/set-binding
 (fn [{:keys [db]} [_ set-id mode chords action]]
   (if (and (registry/custom? db set-id) (seq chords))
     (let [prev (hist/current-binding db set-id mode chords)]
       {:fx [[:dispatch [:kbedit/do {:op :put :set-id set-id :mode mode :chords (vec chords)
                                     :value action :prev prev}]]]})
     {})))

(rf/reg-event-fx
 :kbedit/unbind
 (fn [{:keys [db]} [_ set-id mode chords]]
   (if (registry/custom? db set-id)
     (let [prev (hist/current-binding db set-id mode chords)]
       {:fx [[:dispatch [:kbedit/do {:op :put :set-id set-id :mode mode :chords (vec chords)
                                     :value :unbind :prev prev}]]]})
     {})))

;; key capture: accumulate a chord-sequence (C-x C-f, SPC f f) then commit it onto the action
(rf/reg-event-db :kbedit/capture-start  (fn [db [_ m]] (assoc-in db [:ui :kbedit :capture] (assoc m :chords []))))
(rf/reg-event-db :kbedit/capture-chord  (fn [db [_ chord]] (update-in db [:ui :kbedit :capture :chords] (fnil conj []) chord)))
(rf/reg-event-db :kbedit/capture-pop    (fn [db _] (update-in db [:ui :kbedit :capture :chords] #(vec (butlast %)))))
(rf/reg-event-db :kbedit/capture-cancel (fn [db _] (assoc-in db [:ui :kbedit :capture] nil)))

(rf/reg-event-fx
 :kbedit/capture-commit
 (fn [{:keys [db]} _]
   (let [{:keys [set-id mode action chords]} (get-in db [:ui :kbedit :capture])]
     (cond-> {:db (assoc-in db [:ui :kbedit :capture] nil)}
       (seq chords) (assoc :fx [[:dispatch [:kbedit/set-binding set-id mode chords action]]])))))
