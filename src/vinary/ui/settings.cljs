(ns vinary.ui.settings
  "The Preferences dialog: variable + fixed-width font families and sizes (the theme lives in the Settings
   menu). Each change applies live (CSS vars) and persists to settings.edn via :settings/set."
  (:require [re-frame.core :as rf]
            [vinary.ui.access-keys :as access]))

(defn- text-field [label access-key k value placeholder access-active?]
  [:div.vv-pref-row
   [:label.vv-pref-label [access/label label access-key access-active?]]
   [:input.vv-pref-input
    (merge {:type "text" :value (or value "") :placeholder placeholder :spellCheck false
            :on-change #(rf/dispatch [:settings/set k (.. % -target -value)])}
           (access/access-attrs access-key))]])

(defn- num-field [label access-key k value access-active?]
  [:div.vv-pref-row
   [:label.vv-pref-label [access/label label access-key access-active?]]
   [:input.vv-pref-input.vv-pref-num
    (merge {:type "number" :min 8 :max 40 :value (or value "")
            :on-change #(let [n (js/parseInt (.. % -target -value) 10)]
                          (when-not (js/isNaN n) (rf/dispatch [:settings/set k n])))}
           (access/access-attrs access-key))]])

(defn- on-key-down [^js e]
  (when-let [k (and (.-altKey e) (access/event-letter e))]
    (when (case k
            "v" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='v']")
            "d" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='d']")
            "f" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='f']")
            "s" (access/focus-selector! (.-currentTarget e) ".vv-pref-input[data-vv-access-key='s']")
            "c" (do (rf/dispatch [:settings/close]) true)
            false)
      (access/consume! e))))

(defn dialog []
  (let [open? @(rf/subscribe [:ui/settings-open?])
        s     @(rf/subscribe [:ui/settings])
        access-active? @(rf/subscribe [:ui/access-keys-active?])]
    (when open?
      [:div.vv-modal-overlay {:on-click #(rf/dispatch [:settings/close])}
       [:div.vv-modal {:on-click #(.stopPropagation %)
                       :on-key-down on-key-down}
        [:div.vv-modal-title "Preferences"]
        [:div.vv-modal-body
         [:div.vv-pref-section "Fonts"]
         [text-field "Variable-width font" "v" :font-variable (:font-variable s)
          "Inter, system-ui, sans-serif" access-active?]
         [num-field  "Document font size (px)" "d" :font-size (:font-size s) access-active?]
         [text-field "Fixed-width font" "f" :font-fixed (:font-fixed s) "Fira Code, monospace" access-active?]
         [num-field  "Code font size (px)" "s" :code-font-size (:code-font-size s) access-active?]]
        [:div.vv-modal-actions
         [:button.vv-btn (merge {:on-click #(rf/dispatch [:settings/close])}
                                (access/access-attrs "c"))
          [access/label "Close" "c" access-active?]]]]])))
