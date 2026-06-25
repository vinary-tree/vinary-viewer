(ns vinary.ui.tabs
  "The tab strip. Tabs are browser-like views (vinary.app.nav): each shows a current URI and is labeled by
   its basename. Click activates; × closes (stopping main's watcher when no other tab shows that file)."
  (:require [re-frame.core :as rf]
            [vinary.app.uri :as uri]))

(defn tab-strip []
  (let [tabs   @(rf/subscribe [:ui/tabs])
        active @(rf/subscribe [:ui/active-tab-id])]
    (when (seq tabs)
      [:div.vv-tabs
       (for [{:keys [id] tab-uri :uri} tabs]
         ^{:key id}
         [:div.vv-tab {:class    (when (= id active) "vv-tab-active")
                       :title    (uri/display tab-uri)
                       :on-click #(rf/dispatch [:tab/activate id])}
          [:span.vv-tab-name (uri/basename tab-uri)]
          [:span.vv-tab-close {:title    "Close tab"
                               :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:tab/close id]))}
           "×"]])])))
