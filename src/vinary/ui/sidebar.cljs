(ns vinary.ui.sidebar
  "The left sidebar: a vertical icon rail (ADR-0041) beside one full-height panel — the multi-project
   Files tree, the Contents (TOC) outline, the vertical Tabs list, or the Commits panel. The rail
   never overflows as panels are added and unifies with collapse: collapsed, the rail alone remains
   and any icon expands straight into its panel; clicking the active icon collapses. Resizable (drag
   the right edge; the width is the TOTAL including the 36px rail). State lives in app-db."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.ui.commits :as commits]
            [vinary.ui.error-boundary :as eb]
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
       (for [{:keys [id facet] tab-uri :uri} tabs]
         ^{:key id} [tabs-ui/tab-item {:id id :uri tab-uri :active? (= id active)
                                        :horizontal? false :view-source? (= :source (:type facet))}])]
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

(defn- files-context! [^js e]
  (.preventDefault e)
  (.stopPropagation e)
  (rf/dispatch [:context-menu/show {:x (.-clientX e) :y (.-clientY e)
                                    :target {:kind :files-tab}}]))

(def ^:private rail-sections
  [[:files    "Files"    :section-files]
   [:contents "Contents" :section-contents]
   [:tabs     "Tabs"     :section-tabs]
   [:commits  "Commits"  :section-commits]])

(defn- rail-bar
  "The vertical icon rail (ADR-0041): one button per panel, ALWAYS rendered — collapsed, the rail
   IS the sidebar. Click wiring (VS Code semantics, unifying panel switching with the old chevron
   and thin re-open strip): collapsed → show that panel; another panel → switch; the ACTIVE panel →
   collapse to the rail. The Files button keeps the :files-tab context menu (Refresh All)."
  [visible? tab]
  (into [:div.vv-sidebar-railbar {:role "toolbar" :aria-orientation "vertical"}]
        (for [[k label icon] rail-sections
              :let [active? (and visible? (= tab k))]]
          ^{:key k}
          [:button.vv-rail-btn
           (cond-> {:class        (when active? "vv-rail-btn-active")
                    :data-vv-rail (name k)
                    :title        (if active? (str label " — hide sidebar") label)
                    :aria-label   label
                    :aria-pressed (str (boolean active?))
                    :on-click     (fn [_]
                                    (cond
                                      (not visible?) (rf/dispatch [:sidebar/show k])
                                      (= tab k)      (rf/dispatch [:sidebar/toggle])
                                      :else          (rf/dispatch [:sidebar/tab k])))}
             (= k :files) (assoc :on-context-menu files-context!))
           (icons/icon icon)])))

(defn sidebar []
  (let [visible?   @(rf/subscribe [:ui/sidebar-visible?])
        width      @(rf/subscribe [:ui/sidebar-width])
        tab        @(rf/subscribe [:ui/sidebar-tab])
        restoring? @(rf/subscribe [:ui/tree-restoring?])]
    (if-not visible?
      ;; collapsed → the rail alone (≈37px); every icon expands straight into its panel
      [:div.vv-sidebar.vv-sidebar-collapsed [rail-bar false tab]]
      [:div.vv-sidebar {:style {:width (str (or width 280) "px")}}
       [rail-bar true tab]
       [:div.vv-sidebar-body
        ;; keyed by tab: a panel crash paints a labeled strip instead of unmounting the React root
        ;; (the blank-window failure mode), and switching tabs mounts a fresh boundary — recovery.
        ^{:key tab}
        [eb/boundary "Sidebar panel"
         (case tab
           :contents [contents-panel]
           :tabs     [tabs-panel]
           :commits  [commits/commits-panel]
           (if restoring?
             [:div.vv-sidebar-empty "Refreshing files…"]
             [tree/file-tree]))]]
       [resize-handle]])))
