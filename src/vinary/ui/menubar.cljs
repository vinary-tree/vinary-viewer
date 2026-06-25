(ns vinary.ui.menubar
  "The custom, theme-matched menu bar (File / View / Settings / Help). Clicking a top-level label opens
   its dropdown; items dispatch re-frame events (the same events the keybindings use). A full-window
   overlay closes the menu on click-away; hovering another label while a menu is open switches to it."
  (:require [re-frame.core :as rf]))

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
            :sep
            {:label "Find…"          :accel "Ctrl+F" :event [:find/toggle]}
            :sep
            {:label "Zoom In"        :accel "Ctrl++" :event [:view/zoom 1]}
            {:label "Zoom Out"       :accel "Ctrl+-" :event [:view/zoom -1]}
            {:label "Reset Zoom"     :accel "Ctrl+0" :event [:view/zoom 0]}
            :sep
            {:label "Developer Tools" :accel "Ctrl+Shift+I" :event [:view/devtools]}]}
   {:label "Settings"
    :items [{:label "Theme: Spacemacs Dark"  :event [:theme/set "spacemacs-dark"]}
            {:label "Theme: Spacemacs Light" :event [:theme/set "spacemacs-light"]}
            :sep
            {:label "Preferences…" :event [:settings/open]}]}
   {:label "Help"
    :items [{:label "Command Palette" :accel "Ctrl+Shift+P" :event [:palette/open {:source :command}]}
            :sep
            {:label "About vinary-viewer" :event [:about/open]}]}])

(defn- act! [event]
  (rf/dispatch [:menu/close])
  (rf/dispatch event))

(defn menubar []
  (let [open @(rf/subscribe [:ui/menu])]
    [:div.vv-menubar
     (when open [:div.vv-menu-overlay {:on-click #(rf/dispatch [:menu/close])}])
     (for [{:keys [label items]} menus]
       ^{:key label}
       [:div.vv-menu
        [:div.vv-menu-label {:class          (when (= open label) "vv-menu-label-open")
                             :on-click       #(rf/dispatch [:menu/toggle label])
                             :on-mouse-enter #(when open (rf/dispatch [:menu/open label]))}
         label]
        (when (= open label)
          [:div.vv-menu-dropdown
           (for [[i item] (map-indexed vector items)]
             (if (= item :sep)
               ^{:key i} [:div.vv-menu-sep]
               ^{:key i} [:div.vv-menu-item {:on-click #(act! (:event item))}
                          [:span.vv-menu-item-label (:label item)]
                          (when (:accel item) [:span.vv-menu-item-accel (:accel item)])]))])])]))
