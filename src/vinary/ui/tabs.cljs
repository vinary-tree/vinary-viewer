(ns vinary.ui.tabs
  "The tab strip + the shared tab-item used by both the horizontal strip and the sidebar's vertical Tabs
   panel. Tabs are browser-like views (vinary.app.nav): each shows a current URI, labeled by its basename.
   Click activates; × closes; right-click → context menu; drag reorders (the two representations share one
   ordered model, so reordering one reorders the other)."
  (:require [re-frame.core :as rf]
            [vinary.app.uri :as uri]))

(defn tab-item
  "One tab row. `horizontal?` selects the strip vs. the vertical Tabs panel; the behavior (activate / close
   / context menu / drag-reorder) is identical in both, only the drop-side test (x vs y) differs."
  [{:keys [id uri active? horizontal? view-source?]}]
  [:div {:class           (str (if horizontal? "vv-tab" "vv-vtab") (when active? " vv-tab-active"))
         :title           (uri/display uri)
         :draggable       true
         :on-click        #(rf/dispatch [:tab/activate id])
         :on-context-menu (fn [^js e]
                            (.preventDefault e) (.stopPropagation e)
                            (rf/dispatch [:context-menu/show
                                          {:x (.-clientX e) :y (.-clientY e)
                                           :target {:kind :tab :id id :path (uri/file-path uri)
                                                    :view-source? view-source?}}]))
         :on-drag-start   (fn [^js e] (.setData (.-dataTransfer e) "text/plain" (str id)))
         :on-drag-over    (fn [^js e] (.preventDefault e))
         :on-drop         (fn [^js e]
                            (.preventDefault e)
                            (let [from   (js/parseInt (.getData (.-dataTransfer e) "text/plain") 10)
                                  rect   (.getBoundingClientRect (.-currentTarget e))
                                  after? (if horizontal?
                                           (> (.-clientX e) (+ (.-left rect) (/ (.-width rect) 2)))
                                           (> (.-clientY e) (+ (.-top rect) (/ (.-height rect) 2))))]
                              (when-not (js/isNaN from) (rf/dispatch [:tab/reorder from id after?]))))}
   [:span.vv-tab-name (uri/basename uri)]
   [:span.vv-tab-close {:title    "Close tab"
                        :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:tab/close id]))} "×"]])

(defn tab-strip []
  (let [tabs   @(rf/subscribe [:ui/tabs])
        active @(rf/subscribe [:ui/active-tab-id])]
    (when (seq tabs)
      [:div.vv-tabs
       (for [{:keys [id view-source?] tab-uri :uri} tabs]
         ^{:key id} [tab-item {:id id :uri tab-uri :active? (= id active)
                                :horizontal? true :view-source? view-source?}])])))
