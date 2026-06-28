(ns vinary.ui.extensions
  "Settings ▸ Extensions dialog: ad-blocking controls + the Chrome-extension manager (install from a Web
   Store URL/ID, enable/disable/remove, check for updates). Mirrors vinary.ui.settings (.vv-modal)."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [vinary.ui.icons :as icons]))

(def ^:private subscribe rf/subscribe)
(def ^:private dispatch  rf/dispatch)

(defn- adblock-section []
  (let [ab @(subscribe [:ui/adblock])]
    [:div.vv-ext-sect
     [:div.vv-ext-sect-title "Ad blocking"]
     [:label.vv-ext-row
      [:input {:type "checkbox" :checked (boolean (:enabled? ab)) :on-change #(dispatch [:adblock/toggle])}]
      [:span "Block ads & trackers (EasyList / uBO filter lists)"]]
     [:div.vv-ext-row
      [:span.vv-ext-label "Lists:"]
      [:label.vv-ext-radio
       [:input {:type "radio" :name "vv-ab-lists" :checked (= (:lists ab) :ads-and-tracking)
                :on-change #(dispatch [:adblock/set-lists :ads-and-tracking])}] " Ads + tracking"]
      [:label.vv-ext-radio
       [:input {:type "radio" :name "vv-ab-lists" :checked (= (:lists ab) :ads-only)
                :on-change #(dispatch [:adblock/set-lists :ads-only])}] " Ads only"]]
     [:button.vv-btn {:on-click #(dispatch [:adblock/refresh])} "Update filter lists"]]))

(defn- ext-section []
  (let [draft (r/atom "")]
    (fn []
      (let [{:keys [installed install-status update-status enabled?]} @(subscribe [:ui/extensions])]
        [:div.vv-ext-sect
         [:div.vv-ext-sect-title "Extensions"]
         [:label.vv-ext-row
          [:input {:type "checkbox" :checked (boolean enabled?) :on-change #(dispatch [:extensions/toggle])}]
          [:span "Enable browser extensions (on web pages)"]]
         [:div.vv-ext-install
          [:input.vv-ext-input {:placeholder "Chrome Web Store URL or extension ID"
                                :value @draft :on-change #(reset! draft (.. % -target -value))
                                ;; mark the field focused so the global keymap resolver lets keystrokes
                                ;; through (else vim :normal/:visual consumes bare printable keys)
                                :on-focus #(dispatch [:input/set-in-input true])
                                :on-blur  #(dispatch [:input/set-in-input false])
                                :on-key-down (fn [^js e] (when (= "Enter" (.-key e))
                                                           (when (seq @draft) (dispatch [:extensions/install @draft]) (reset! draft ""))))}]
          [:button.vv-btn {:on-click (fn [] (when (seq @draft) (dispatch [:extensions/install @draft]) (reset! draft "")))} "Install"]]
         (when install-status
           [:div.vv-ext-status (if (:ok install-status)
                                 (str "✓ Installed " (:name install-status))
                                 (str "✗ " (:error install-status)))])
         [:div.vv-ext-list
          (if (empty? installed)
            [:div.vv-ext-empty "No extensions installed."]
            (doall
             (for [{:keys [id name version action enabled?]} installed]
               ^{:key id}
               [:div.vv-ext-item
                (if (:icon action)
                  [:img.vv-ext-item-icon {:src (:icon action) :alt ""}]
                  [:span.vv-ext-item-icon (icons/icon :globe)])
                [:span.vv-ext-item-name name [:span.vv-ext-item-ver (str "  v" version)]]
                [:label.vv-ext-item-toggle
                 [:input {:type "checkbox" :checked (boolean enabled?)
                          :on-change #(dispatch [:extensions/set-enabled id (not enabled?)])}]]
                [:button.vv-ext-item-rm {:title "Remove" :on-click #(dispatch [:extensions/remove id])}
                 (icons/icon :delete)]])))]
         [:div.vv-ext-row
          [:button.vv-btn {:on-click #(dispatch [:extensions/check-updates])} "Check for updates"]
          (when update-status
            [:span.vv-ext-status (cond (:checking? update-status) "Checking…"
                                       (:ok update-status)        "✓ Up to date"
                                       :else                      (str "✗ " (:error update-status)))])]
         [:div.vv-ext-note
          "Autofill + vault popups work for cloud password managers; native-messaging managers "
          "(1Password, KeePassXC) and some MV3 background features are not supported in Electron."]]))))

(defn dialog []
  (when @(subscribe [:ui/extensions-open?])
    [:div.vv-modal-overlay {:on-click #(dispatch [:extensions/close])}
     [:div.vv-modal.vv-modal-wide {:on-click #(.stopPropagation %)}
      [:div.vv-modal-title "Extensions & Ad Blocking"]
      [:div.vv-modal-body
       [adblock-section]
       [ext-section]]
      [:div.vv-modal-actions
       [:button.vv-btn {:on-click #(dispatch [:extensions/close])} "Close"]]]]))
