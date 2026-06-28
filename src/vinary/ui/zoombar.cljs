(ns vinary.ui.zoombar
  "A slim, always-visible zoom control at the bottom of the content pane: − / editable % field / preset
   dropdown / +. Every control dispatches the same context-aware [:view/zoom …] / [:view/zoom-set …] events
   as the View menu and the keymap, so it zooms whichever surface the active tab shows (the in-renderer PDF,
   the native web view, or the app-renderer DOM views). The % reflects the active surface's live zoom."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [vinary.app.zoom :as zoom]))

(defn zoombar []
  (let [open? (r/atom false)         ; preset dropdown open?
        draft (r/atom nil)]          ; non-nil while the % field is being edited
    (fn []
      (let [pct     @(rf/subscribe [:view/zoom-percent])
            shown   (if (nil? @draft) (str pct) @draft)
            commit! (fn []
                      (let [v (js/parseInt @draft 10)]
                        (when-not (js/isNaN v) (rf/dispatch [:view/zoom-set v])))
                      (reset! draft nil))]
        [:div.vv-bottombar
         [:div.vv-zoom {:on-mouse-leave #(reset! open? false)}
          [:button.vv-zoom-btn {:title "Zoom out (Ctrl+-)" :on-click #(rf/dispatch [:view/zoom -1])} "−"]
          [:div.vv-zoom-field
           [:input.vv-zoom-input
            {:value shown :spellCheck false :title "Zoom (%)"
             :on-focus    (fn [^js e] (rf/dispatch [:input/set-in-input true]) (reset! draft (str pct)) (.select (.-target e)))
             :on-blur     (fn [_]     (rf/dispatch [:input/set-in-input false]) (commit!))
             :on-change   (fn [^js e] (reset! draft (.. e -target -value)))
             :on-key-down (fn [^js e]
                            (case (.-key e)
                              "Enter"  (do (.preventDefault e) (commit!) (.blur (.-target e)))
                              "Escape" (do (reset! draft nil) (.blur (.-target e)))
                              nil))}]
           [:span.vv-zoom-pct "%"]
           [:button.vv-zoom-caret {:title "Zoom presets" :on-click #(swap! open? not)} "▾"]
           (when @open?
             [:ul.vv-zoom-menu
              (doall
               (for [p zoom/presets]
                 ^{:key p}
                 [:li.vv-zoom-opt {:class         (when (= p pct) "vv-zoom-opt-sel")
                                   :on-mouse-down (fn [^js e] (.preventDefault e)
                                                    (rf/dispatch [:view/zoom-set p]) (reset! open? false))}
                  (str (if (= p pct) "✓ " "") p "%")]))])]
          [:button.vv-zoom-btn {:title "Zoom in (Ctrl++)" :on-click #(rf/dispatch [:view/zoom 1])} "+"]]]))))
