(ns vinary.ui.settings
  "The Preferences dialog: variable + fixed-width font families and sizes (the theme lives in the Settings
   menu). Each change applies live (CSS vars) and persists to settings.edn via :settings/set."
  (:require [re-frame.core :as rf]))

(defn- text-field [label k value placeholder]
  [:div.vv-pref-row
   [:label.vv-pref-label label]
   [:input.vv-pref-input
    {:type "text" :value (or value "") :placeholder placeholder :spellCheck false
     :on-change #(rf/dispatch [:settings/set k (.. % -target -value)])}]])

(defn- num-field [label k value]
  [:div.vv-pref-row
   [:label.vv-pref-label label]
   [:input.vv-pref-input.vv-pref-num
    {:type "number" :min 8 :max 40 :value (or value "")
     :on-change #(let [n (js/parseInt (.. % -target -value) 10)]
                   (when-not (js/isNaN n) (rf/dispatch [:settings/set k n])))}]])

(defn dialog []
  (let [open? @(rf/subscribe [:ui/settings-open?])
        s     @(rf/subscribe [:ui/settings])]
    (when open?
      [:div.vv-modal-overlay {:on-click #(rf/dispatch [:settings/close])}
       [:div.vv-modal {:on-click #(.stopPropagation %)}
        [:div.vv-modal-title "Preferences"]
        [:div.vv-modal-body
         [:div.vv-pref-section "Fonts"]
         [text-field "Variable-width font" :font-variable (:font-variable s) "Inter, system-ui, sans-serif"]
         [num-field  "Document font size (px)" :font-size (:font-size s)]
         [text-field "Fixed-width font" :font-fixed (:font-fixed s) "Fira Code, monospace"]
         [num-field  "Code font size (px)" :code-font-size (:code-font-size s)]]
        [:div.vv-modal-actions
         [:button.vv-btn {:on-click #(rf/dispatch [:settings/close])} "Close"]]]])))
