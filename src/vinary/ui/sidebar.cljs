(ns vinary.ui.sidebar
  "The left sidebar: a tabbed pane hosting the multi-project Files tree and the Contents (TOC) outline.
   Collapsible (to maximize the preview) and resizable (drag the right edge). Width + collapsed state
   live in app-db (persisted via settings in Phase 5)."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.ui.icons :as icons]
            [vinary.ui.tree :as tree]
            [vinary.ui.tabs :as tabs-ui]))

(defn- reveal-active!
  "Scroll the sidebar's own scroll container (.vv-sidebar-body) just enough to bring the active TOC row into
   view — only when it is out of view, and only that container (never the content pane, which is not an
   ancestor). A no-op when the row is already visible, so it won't fight manual sidebar scrolling."
  [^js list-node]
  (when-let [^js c (some-> list-node (.closest ".vv-sidebar-body"))]
    (when-let [^js el (.querySelector c ".vv-toc-active")]
      (let [cr (.getBoundingClientRect c)
            er (.getBoundingClientRect el)]
        (cond
          (< (.-top er) (.-top cr))
          (set! (.-scrollTop c) (+ (.-scrollTop c) (- (.-top er) (.-top cr))))
          (> (.-bottom er) (.-bottom cr))
          (set! (.-scrollTop c) (+ (.-scrollTop c) (- (.-bottom er) (.-bottom cr)))))))))

(defn- contents-panel
  "The Contents tab: the active document's section outline — Markdown headings, the PDF outline, or an HTTP
   page's web-view outline (:doc/toc is unified across content types). Click scrolls to the section; the
   scroll-spy highlights the current one and this panel auto-scrolls the list to keep it in view. Content-
   agnostic: it acts on .vv-toc-active regardless of which preview produced it."
  []
  (let [node (atom nil)
        prev (atom nil)]
    (r/create-class
     {:display-name "vv-contents-panel"
      :component-did-mount  (fn [_]
                              (reset! prev @(rf/subscribe [:ui/active-heading]))
                              (reveal-active! @node))
      :component-did-update (fn [_]
                              (let [active @(rf/subscribe [:ui/active-heading])]
                                (when (not= active @prev)
                                  (reset! prev active)
                                  (reveal-active! @node))))
      :reagent-render
      (fn []
        (let [headings @(rf/subscribe [:doc/toc])
              active   @(rf/subscribe [:ui/active-heading])]
          (if (seq headings)
            [:div.vv-toc-list {:ref (fn [el] (reset! node el))}
             ;; key by [id idx]: PDF outlines can list two sections on one page (same vv-pdf-page-N id), so id
             ;; alone is not unique. Both same-page rows share the active id and highlight together — correct,
             ;; since both sections begin on the page currently being read.
             (map-indexed
              (fn [idx {:keys [level text id number]}]
                ^{:key (str id "-" idx)}
                [:a.vv-toc-item {:class    (str "vv-toc-l" level (when (= id active) " vv-toc-active"))
                                 :title    (if number (str number " " text) text)
                                 :on-click #(rf/dispatch [:toc/goto id])}
                 ;; :number is present only for PDF outlines (derived by pdf-layout/number-outline); Markdown/web
                 ;; entries have none and render exactly as before — the panel stays content-agnostic.
                 (when number [:span.vv-toc-num number])
                 text])
              headings)]
            [:div.vv-sidebar-empty "No sections"])))})))

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
      [:div.vv-sidebar-rail {:title "Show sidebar" :on-click #(rf/dispatch [:sidebar/toggle])} (icons/icon :expand)]
      [:div.vv-sidebar {:style {:width (str (or width 280) "px")}}
       [:div.vv-sidebar-tabs
        [:div.vv-sidebar-tab {:class    (when (= tab :files) "vv-sidebar-tab-active")
                              :on-click #(rf/dispatch [:sidebar/tab :files])} (icons/icon :section-files) "Files"]
        [:div.vv-sidebar-tab {:class    (when (= tab :contents) "vv-sidebar-tab-active")
                              :on-click #(rf/dispatch [:sidebar/tab :contents])} (icons/icon :section-contents) "Contents"]
        [:div.vv-sidebar-tab {:class    (when (= tab :tabs) "vv-sidebar-tab-active")
                              :on-click #(rf/dispatch [:sidebar/tab :tabs])} (icons/icon :section-tabs) "Tabs"]
        [:div.vv-sidebar-tabs-spacer]
        [:div.vv-sidebar-collapse {:title "Hide sidebar" :on-click #(rf/dispatch [:sidebar/toggle])} (icons/icon :collapse)]]
       [:div.vv-sidebar-body
        (case tab
          :contents [contents-panel]
          :tabs     [tabs-panel]
          [tree/file-tree])]
       [resize-handle]])))
