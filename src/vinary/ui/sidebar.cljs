(ns vinary.ui.sidebar
  "The left sidebar: a tabbed pane hosting the multi-project Files tree and the Contents (TOC) outline.
   Collapsible (to maximize the preview) and resizable (drag the right edge). Width + collapsed state
   live in app-db (persisted via settings in Phase 5)."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.ui.tree :as tree]
            [vinary.ui.tabs :as tabs-ui]))

(defn- contents-panel
  "The Contents tab: the active document's section outline (Markdown headings or, for an HTTP page, the
   web view's outline — :doc/toc is unified). Click scrolls to the section; scroll-spy highlights it."
  []
  (let [headings @(rf/subscribe [:doc/toc])
        active   @(rf/subscribe [:ui/active-heading])]
    (if (seq headings)
      [:div.vv-toc-list
       (for [{:keys [level text id]} headings]
         ^{:key id}
         [:a.vv-toc-item {:class    (str "vv-toc-l" level (when (= id active) " vv-toc-active"))
                          :on-click #(rf/dispatch [:toc/goto id])}
          text])]
      [:div.vv-sidebar-empty "No sections"])))

(defn- tabs-panel
  "The Tabs tab: a vertical list of the open tabs in the same order as the horizontal strip (top→bottom ==
   left→right). Shares the strip's tab-item — so drag-reorder and the right-click context menu behave the
   same, and reordering here reorders the strip. Useful when tabs overflow the horizontal strip."
  []
  (let [tabs   @(rf/subscribe [:ui/tabs])
        active @(rf/subscribe [:ui/active-tab-id])]
    (if (seq tabs)
      [:div.vv-vtabs
       (for [{:keys [id view-source?] tab-uri :uri} tabs]
         ^{:key id} [tabs-ui/tab-item {:id id :uri tab-uri :active? (= id active)
                                        :horizontal? false :view-source? view-source?}])]
      [:div.vv-sidebar-empty "No open tabs"])))

(defn- resize-handle
  "A draggable bar on the sidebar's right edge; dragging writes [:ui :sidebar-width] (clamped in the event)."
  []
  (let [dragging (atom false)
        on-move  (fn [^js e] (when @dragging (rf/dispatch [:sidebar/width (.-clientX e)])))
        on-up    (fn end [_]
                   (reset! dragging false)
                   (.removeEventListener js/window "mousemove" on-move)
                   (.removeEventListener js/window "mouseup" end))]
    (fn []
      [:div.vv-sidebar-resize
       {:on-mouse-down (fn [^js e]
                         (.preventDefault e)
                         (reset! dragging true)
                         (.addEventListener js/window "mousemove" on-move)
                         (.addEventListener js/window "mouseup" on-up))}])))

(defn sidebar []
  (let [visible? @(rf/subscribe [:ui/sidebar-visible?])
        width    @(rf/subscribe [:ui/sidebar-width])
        tab      @(rf/subscribe [:ui/sidebar-tab])]
    (if-not visible?
      ;; collapsed → a thin rail with a re-open affordance
      [:div.vv-sidebar-rail {:title "Show sidebar" :on-click #(rf/dispatch [:sidebar/toggle])} "›"]
      [:div.vv-sidebar {:style {:width (str (or width 280) "px")}}
       [:div.vv-sidebar-tabs
        [:div.vv-sidebar-tab {:class    (when (= tab :files) "vv-sidebar-tab-active")
                              :on-click #(rf/dispatch [:sidebar/tab :files])} "Files"]
        [:div.vv-sidebar-tab {:class    (when (= tab :contents) "vv-sidebar-tab-active")
                              :on-click #(rf/dispatch [:sidebar/tab :contents])} "Contents"]
        [:div.vv-sidebar-tab {:class    (when (= tab :tabs) "vv-sidebar-tab-active")
                              :on-click #(rf/dispatch [:sidebar/tab :tabs])} "Tabs"]
        [:div.vv-sidebar-tabs-spacer]
        [:div.vv-sidebar-collapse {:title "Hide sidebar" :on-click #(rf/dispatch [:sidebar/toggle])} "‹"]]
       [:div.vv-sidebar-body
        (case tab
          :contents [contents-panel]
          :tabs     [tabs-panel]
          [tree/file-tree])]
       [resize-handle]])))
