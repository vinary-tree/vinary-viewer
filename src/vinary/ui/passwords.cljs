(ns vinary.ui.passwords
  "Renderer UI for the native password-manager bridge. This namespace renders only provider status,
   sanitized item metadata, and save tokens; it never receives a revealed password."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [vinary.app.uri :as uri]
            [vinary.ui.icons :as icons]
            [vinary.ui.modal :as modal]))

(def ^:private subscribe rf/subscribe)
(def ^:private dispatch rf/dispatch)

(defn- status-text [status]
  (case (or status "")
    "ready" "Ready"
    "reauth-required" "Unlock required"
    "mfa-required" "Authentication required"
    "hardware-key-required" "Security key required"
    "locked" "Locked"
    "not-installed" "Not installed"
    "unavailable" "Unavailable"
    "error" "Error"
    "Unknown"))

(defn- provider-row [{:keys [id label status message save-supported?]}]
  [:div.vv-pw-provider
   [:span.vv-pw-provider-name label]
   [:span.vv-pw-provider-status {:class (str "vv-pw-status-" status)}
    (status-text status)]
   (when save-supported? [:span.vv-pw-provider-save "Save"])
   (when (seq message) [:span.vv-pw-provider-msg {:title message} message])])

(defn- item-row [{:keys [id provider title username url provider-label] :as item}]
  [:div.vv-pw-item
   [:div.vv-pw-item-main
    [:div.vv-pw-item-title title]
    [:div.vv-pw-item-meta
     [:span.vv-pw-item-user (if (seq username) username "No username")]
     [:span.vv-pw-item-provider provider-label]
     (when (seq url) [:span.vv-pw-item-url url])]]
   [:button.vv-btn.vv-pw-fill
    {:on-click #(dispatch [:passwords/fill item])
     :title (str "Fill " title)}
    (icons/icon :key)
    [:span "Fill"]]])

(defn toolbar-button []
  (let [active-uri @(subscribe [:ui/active-uri])
        {:keys [forms busy?]} @(subscribe [:ui/passwords])]
    (when (uri/http? active-uri)
      [:button.vv-password-action
       {:title "Passwords"
        :class (when (pos? (or (:count forms) 0)) "vv-password-action-ready")
        :on-click #(dispatch [:passwords/open])}
       (icons/icon :key)
       (when busy? [:span.vv-password-dot])
       (when (pos? (or (:count forms) 0)) [:span.vv-password-badge (:count forms)])])))

(defn dialog []
  (let [{:keys [open? providers items busy? error result]} @(subscribe [:ui/passwords])]
    (when open?
      [modal/modal
       {:on-close #(dispatch [:passwords/close])
        :title    "Passwords"
        :class    "vv-modal-wide vv-pw-dialog"
        :actions  [:<>
                   [:button.vv-btn {:on-click #(dispatch [:passwords/retry])} "Retry"]
                   [:button.vv-btn {:on-click #(dispatch [:passwords/close])} "Close"]]}
       [:div.vv-modal-body
        [:div.vv-pw-providers
         (if (seq providers)
           (doall (for [p providers] ^{:key (:id p)} [provider-row p]))
           [:div.vv-ext-empty "No providers configured."])]
        (when error [:div.vv-pw-error error])
        (when result
          [:div.vv-pw-result {:class (if (:ok result) "vv-pw-result-ok" "vv-pw-result-error")}
           (:message result)])
        [:div.vv-pw-list
         (cond
           busy? [:div.vv-pw-empty [:span.vv-pw-spinner] "Searching..."]
           (seq items) (doall (for [item items]
                                ^{:key (str (:provider item) ":" (:id item))}
                                [item-row item]))
           :else [:div.vv-pw-empty "No matching logins."])]]])))

(defn save-prompt []
  (let [selected (r/atom nil)
        seen-token (atom nil)]
    (fn []
      (let [{:keys [save-prompt]} @(subscribe [:ui/passwords])
            {:keys [token origin username providers]} save-prompt
            ready-providers (vec (filter #(and (:save-supported? %) (not= "not-installed" (:status %))) providers))]
        (when (and token (not= token @seen-token))
          (reset! seen-token token)
          (reset! selected (or (:id (first (filter #(= "ready" (:status %)) ready-providers)))
                               (:id (first ready-providers)))))
        (when save-prompt
          [modal/modal
           {:on-close #(dispatch [:passwords/dismiss-save token])
            :title    "Save Login"
            :class    "vv-pw-save"
            :actions  [:<>
                       [:button.vv-btn
                        {:disabled (not (seq @selected))
                         :on-click #(dispatch [:passwords/save token @selected])}
                        "Save"]
                       [:button.vv-btn {:on-click #(dispatch [:passwords/dismiss-save token])} "Dismiss"]]}
           [:div.vv-modal-body
            [:div.vv-pw-save-target
             [:div.vv-pw-save-origin origin]
             [:div.vv-pw-save-user (if (seq username) username "No username")]]
            (if (seq ready-providers)
              [:select.vv-pw-select
               {:value (or @selected "")
                :on-focus #(dispatch [:input/set-in-input true])
                :on-blur #(dispatch [:input/set-in-input false])
                :on-change #(reset! selected (.. % -target -value))}
               (for [{:keys [id label status]} ready-providers]
                 ^{:key id}
                 [:option {:value id} (str label " - " (status-text status))])]
              [:div.vv-pw-empty "No save-capable provider is available."])]])))))
