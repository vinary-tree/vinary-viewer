(ns vinary.ui.palette
  "Command palette / fuzzy finder. One overlay widget, three sources: :command (all visible commands,
   M-x / vim :), :file (the git tree, C-x C-f / Ctrl+P / SPC f f), :theme. Typing fuzzy-filters; ↑/↓
   move; Enter runs the selection; Esc closes.

   Matching goes through vinary.search.match, so the mode is configurable rather than welded in
   (vinary.search.config defaults :palette to subsequence, which is what the private `fuzzy?` this
   replaced did). Candidate assembly is a subscription, not render-time work — see `vinary.app.palette`."
  (:require [re-frame.core :as rf]
            [vinary.app.commands :as commands]
            [vinary.ui.text-input :as text-input]))

(defn- run-item! [item]
  (rf/dispatch [:palette/close])
  (case (:kind item)
    :file    (rf/dispatch [:doc/open (:path item)])
    :theme   (rf/dispatch [:theme/set (:theme item)])
    :command (commands/run (:command item) (commands/palette-ctx) nil)
    nil))

(defn command-palette []
  (let [{:keys [open? prefix query selected]} @(rf/subscribe [:palette/state])]
    (when open?
      (let [items @(rf/subscribe [:palette/candidates])
            n     (count items)
            sel   (mod (or selected 0) (max 1 n))]
        [:div.vv-palette-overlay {:on-click #(rf/dispatch [:palette/close])}
         [:div.vv-palette {:on-click #(.stopPropagation %)}
          [:div.vv-palette-bar
           (when (seq prefix) [:span.vv-palette-prefix prefix])
           ;; async-input: the query box owns its own DOM value, so the debounced (and therefore trailing)
           ;; app-db query can never be committed back over a character the user has already typed.
           [text-input/async-input
            {:value       (or query "")
             :on-change   #(rf/dispatch [:palette/set-query %])
             :attrs       {:class "vv-palette-input"
                           :auto-focus true
                           :placeholder (case (:source @(rf/subscribe [:palette/state]))
                                          :file "Find file…" :theme "Theme…" "Run command…")}
             :on-key-down (fn [^js e]
                            (case (.-key e)
                              "ArrowDown" (do (.preventDefault e) (rf/dispatch [:palette/move 1 n]))
                              "ArrowUp"   (do (.preventDefault e) (rf/dispatch [:palette/move -1 n]))
                              "Enter"     (do (.preventDefault e) (when (pos? n) (run-item! (:item (nth items sel)))))
                              "Escape"    (do (.preventDefault e) (rf/dispatch [:palette/close]))
                              nil))}]]
          [:div.vv-palette-list
           (for [[i {:keys [item]}] (map-indexed vector items)]
             ^{:key i}
             [:div.vv-palette-item {:class    (when (= i sel) "vv-palette-selected")
                                    :on-click #(run-item! item)}
              [:span.vv-palette-label (:label item)]
              (when (:category item) [:span.vv-palette-cat (:category item)])])]]]))))
