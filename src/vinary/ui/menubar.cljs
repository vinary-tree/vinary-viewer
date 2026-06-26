(ns vinary.ui.menubar
  "The custom, theme-matched menu bar (File / View / Settings / Help). Clicking a top-level label opens its
   dropdown; items dispatch re-frame events (the same events the keybindings use). Settings hosts radio
   SUBMENUS (Theme, Key Bindings) that flyout to the right and mark the active choice. A full-window overlay
   closes on click-away; hovering another top-level label switches menus."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

(def ^:private themes
  [["spacemacs-dark" "Spacemacs Dark"]
   ["spacemacs-light" "Spacemacs Light"]])

(def ^:private menus
  [{:label "File"
    :items [{:label "Open…"     :accel "Ctrl+O" :event [:file/open-dialog]}
            {:label "Close Tab" :accel "Ctrl+W" :event [:tab/close-active]}
            :sep
            {:label "Reload"    :accel "Ctrl+R" :event [:tab/reload]}
            {:label "Quit"      :accel "Ctrl+Q" :event [:app/quit]}]}
   {:label "View"
    :items [{:label "Toggle Sidebar" :accel "Ctrl+B" :event [:sidebar/toggle]}
            {:label "Files"          :event [:sidebar/show :files]}
            {:label "Contents"       :event [:sidebar/show :contents]}
            {:label "Tabs"           :event [:sidebar/show :tabs]}
            :sep
            {:label "View Source"    :event [:tab/toggle-source]}
            {:label "Find…"          :accel "Ctrl+F" :event [:find/toggle]}
            :sep
            {:label "Zoom In"        :accel "Ctrl++" :event [:view/zoom 1]}
            {:label "Zoom Out"       :accel "Ctrl+-" :event [:view/zoom -1]}
            {:label "Reset Zoom"     :accel "Ctrl+0" :event [:view/zoom 0]}
            :sep
            {:label "Developer Tools" :accel "Ctrl+Shift+I" :event [:view/devtools]}
            {:label "re-frame-10x"   :event [:view/re-frame-10x]}]}
   {:label "Settings"
    :items [{:submenu "Theme"        :radio :sub/theme}
            {:submenu "Key Bindings" :radio :sub/keymaps}
            :sep
            {:label "Preferences…"   :event [:settings/open]}]}
   {:label "Help"
    :items [{:label "Command Palette" :accel "Ctrl+Shift+P" :event [:palette/open {:source :command}]}
            :sep
            {:label "About vinary-viewer" :event [:about/open]}]}])

(defn- radio-rows
  "The flyout rows for a submenu source — each {:label :selected? :event} (or :sep)."
  [src]
  (case src
    :sub/theme   (let [cur @(rf/subscribe [:ui/theme])]
                   (mapv (fn [[v label]] {:label label :selected? (= v cur) :event [:theme/set v]}) themes))
    :sub/keymaps (conj (mapv (fn [{:keys [id name selected?]}]
                               {:label name :selected? selected? :event [:keymap/select id]})
                             @(rf/subscribe [:keymaps/set-rows]))
                       :sep
                       {:label "Customize…" :event [:kbedit/open]})
    nil))

(defn- act! [event]
  (rf/dispatch [:menu/close])
  (rf/dispatch event))

(defn- row-item [row]
  [:div.vv-menu-item.vv-menu-item-radio {:on-click #(act! (:event row))}
   (if (contains? row :selected?)
     [:span.vv-radio-dot {:class (when (:selected? row) "vv-radio-dot-on")}]
     [:span.vv-radio-dot.vv-radio-dot-blank])
   [:span.vv-menu-item-label (:label row)]])

(defn menubar []
  (let [sub-open (r/atom nil)]
    (fn []
      (let [open @(rf/subscribe [:ui/menu])]
        [:div.vv-menubar
         (when open [:div.vv-menu-overlay {:on-click #(do (rf/dispatch [:menu/close]) (reset! sub-open nil))}])
         (for [{:keys [label items]} menus]
           ^{:key label}
           [:div.vv-menu
            [:div.vv-menu-label {:class          (when (= open label) "vv-menu-label-open")
                                 :on-click       #(do (rf/dispatch [:menu/toggle label]) (reset! sub-open nil))
                                 :on-mouse-enter #(when open (do (rf/dispatch [:menu/open label]) (reset! sub-open nil)))}
             label]
            (when (= open label)
              [:div.vv-menu-dropdown
               (for [[i item] (map-indexed vector items)]
                 (cond
                   (= item :sep) ^{:key i} [:div.vv-menu-sep]
                   (:submenu item)
                   ^{:key i}
                   [:div.vv-menu-item.vv-menu-item-submenu
                    {:on-mouse-enter #(reset! sub-open (:submenu item))
                     :on-mouse-over  #(reset! sub-open (:submenu item))
                     :on-click       (fn [e]
                                       (.preventDefault e)
                                       (.stopPropagation e)
                                       (reset! sub-open (:submenu item)))}
                    [:span.vv-menu-item-label (:submenu item)]
                    [:span.vv-menu-item-arrow "▸"]
                    (when (= @sub-open (:submenu item))
                      [:div.vv-menu-subdropdown
                       (for [[j row] (map-indexed vector (remove nil? (radio-rows (:radio item))))]
                         (if (= row :sep)
                           ^{:key j} [:div.vv-menu-sep]
                           ^{:key j} [row-item row]))])]
                   :else
                   ^{:key i}
                   [:div.vv-menu-item {:on-click       #(act! (:event item))
                                       :on-mouse-enter #(reset! sub-open nil)}
                    [:span.vv-menu-item-label (:label item)]
                    (when (:accel item) [:span.vv-menu-item-accel (:accel item)])]))])])]))))
