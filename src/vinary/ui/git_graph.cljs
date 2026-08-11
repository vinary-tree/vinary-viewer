(ns vinary.ui.git-graph
  "The Commit Graph document (ADR-0040): a full-pane, GitLens-style view over the SAME
   [:ui :commits :repos <root>] store the sidebar panel feeds — lanes + refs + author/date columns,
   keyboard navigation, and the shared selection (R3), windowed over the pane's own `.vv-content`
   scroller with fixed-height rows (vinary.git.graph-geometry owns every number). The document
   entity is tiny (kind + :doc/git-root); freshness flows through vv:git-changed → the store."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.app.projects :as projects]
            [vinary.git.graph-geometry :as ggeo]
            [vinary.ui.combo-select :refer [combo-select]]
            [vinary.ui.commits :as commits-ui]))

(def ^:private overscan 10)

(defn- key-handler [root vis-rows]
  (fn [^js e]
    (let [k   (.-key e)
          nav (case k
                "ArrowDown" :down "ArrowUp" :up
                "PageDown"  :pgdn "PageUp"  :pgup
                "Home"      :home "End"     :end
                nil)]
      (cond
        nav
        (do (.preventDefault e) (.stopPropagation e)
            (rf/dispatch [:git-graph/cursor-move root nav {:extend?    (.-shiftKey e)
                                                           :move-only? (.-ctrlKey e)
                                                           :vis-rows   vis-rows}]))
        (= k " ")
        (do (.preventDefault e) (.stopPropagation e)
            (rf/dispatch [:git-graph/toggle-at-cursor root]))

        (= k "Enter")
        (do (.preventDefault e) (.stopPropagation e)
            (rf/dispatch [:git-graph/activate-cursor root]))

        :else nil))))

(defn- badge [b]
  [:span.vv-gg-ref {:class (str (when (:head? b) "vv-gg-ref-head ")
                                (case (:kind b) :tag "vv-gg-ref-tag" :remote "vv-gg-ref-remote" ""))
                    :title (:name b)}
   (:name b)])

(defn- graph-row [root max-lane prev-row row commit selection]
  ;; NB: `hash` here shadows cljs.core/hash — value-only by contract; never call it
  (let [{:keys [hash subject refs]} commit
        ;; a HISTORY listing carries no lane rows — the rail degrades to a lane-0 dot
        row       (or row {:lane 0 :edges [] :active 1})
        geo       (ggeo/row-geometry row (:edges prev-row) max-lane)
        selected? (contains? (:selected selection) hash)
        cursor?   (= (:cursor selection) hash)
        pair      (let [s (vec (:selected selection))] (when (= 2 (count s)) s))]
    [:div.vv-gg-row
     {:class           (str (when selected? "vv-gg-row-sel ") (when cursor? "vv-gg-row-cur"))
      :style           {:grid-template-columns (str (:width geo) "px minmax(0,1fr) 12ch 16ch")}
      :data-hash       hash
      :on-click        (fn [^js e]
                         (cond
                           (.-ctrlKey e)  (rf/dispatch [:commits/select root hash :toggle])
                           (.-shiftKey e) (rf/dispatch [:commits/select root hash :range])
                           :else          (rf/dispatch [:commits/select root hash :single])))
      :on-double-click #(rf/dispatch [:commits/activate root hash])
      :on-context-menu (fn [^js e]
                         (.preventDefault e)
                         (rf/dispatch [:context-menu/show
                                       {:x (.-clientX e) :y (.-clientY e)
                                        :target (cond-> {:kind :commit :root root
                                                         :hash hash :subject subject}
                                                  pair (assoc :pair pair))}]))}
     [:svg.vv-gg-rail {:width (:width geo) :height ggeo/row-h
                       :viewBox (str "0 0 " (:width geo) " " ggeo/row-h) :aria-hidden "true"}
      (into [:g]
            (map-indexed (fn [i {:keys [d class]}] ^{:key i} [:path {:class class :d d}])
                         (:paths geo)))
      (let [{:keys [cx cy r class]} (:dot geo)]
        [:circle.vv-gg-dot {:class class :cx cx :cy cy :r r}])]
     [:span.vv-gg-subject-cell
      (when (:overflow? geo) [:span.vv-gg-overflow {:title "graph truncated at the lane cap"} "»"])
      (when (seq refs)
        (into [:span.vv-gg-refs]
              (for [b (ggeo/refs->badges refs)] ^{:key (:name b)} [badge b])))
      [:span.vv-gg-subject {:title subject} subject]]
     [:span.vv-gg-author {:title (:author-email commit)} (:author-name commit)]
     [:span.vv-gg-date (ggeo/fmt-date (:author-date commit))]]))

(defn- header-bar [root repo]
  (let [{:keys [branches ref commits exhausted? loading? mode history-target]} repo
        history? (contains? #{:file-history :line-history} mode)]
    [:div.vv-gg-header
     [:span.vv-gg-repo (last (remove str/blank? (str/split (projects/normalized-path root) #"/")))]
     [:span.vv-gg-count (str (count commits) (if exhausted? "" "+") " commits")]
     [combo-select {:value   (or ref (:head branches) "HEAD")
                    :title   "Branch / tag"
                    :groups  (commits-ui/branch-groups branches)
                    :on-pick #(rf/dispatch [:commits/set-ref root %])
                    :disabled? history?
                    :disabled-title "History follows HEAD"
                    :class   "vv-gg-branch"}]
     (when-let [label (ggeo/history-chip-label {:mode mode :target history-target})]
       [:span.vv-commits-chip {:title (:file history-target)}
        label
        [:button.vv-commits-chip-x {:title    "Back to the branch log"
                                    :on-click #(rf/dispatch [:git/history-exit root])} "×"]])
     (when loading? [:span.vv-gg-loading "…"])]))

(defn graph-view [_path _git-root]
  (let [scroll* (r/atom {:top 0 :vh 600 :offset 0})
        node*   (atom nil)
        detach* (atom nil)]
    (r/create-class
     {:display-name "vv-git-graph"
      :component-did-mount
      (fn [this]
        (let [[_ _path root] (r/argv this)]
          (rf/dispatch [:git-graph/shown root])
          (when-let [^js n @node*]
            (when-let [^js sc (.closest n ".vv-content")]
              (let [handler (fn [_]
                              (reset! scroll* {:top    (.-scrollTop sc)
                                               :vh     (.-clientHeight sc)
                                               :offset (.-offsetTop n)})
                              ;; near-end paging is guarded event-side (loading?/exhausted?)
                              (rf/dispatch [:git-graph/near-end root
                                            (quot (+ (- (.-scrollTop sc) (.-offsetTop n))
                                                     (.-clientHeight sc))
                                                  ggeo/row-h)]))]
                (handler nil)
                (.addEventListener sc "scroll" handler #js {:passive true})
                (reset! detach* (fn [] (.removeEventListener sc "scroll" handler)))))
            (.focus ^js n))))
      :component-will-unmount
      (fn [_]
        (when-let [d @detach*] (d) (reset! detach* nil))
        (rf/dispatch [:git-graph/hidden]))
      :reagent-render
      (fn [_path root]
        (let [repo (or @(rf/subscribe [:commits/for-root root]) {})
              ;; :empty? binds as empty-repo? — never shadow cljs.core/empty? (the Commits-panel
              ;; blank-window bug was exactly that shadow invoked as a function)
              {:keys [commits graph selection error]
               empty-repo? :empty?} repo
              rows     (vec (:rows graph))
              n        (count commits)
              max-lane (long (or (:max-lane graph) 0))
              {:keys [top vh offset]} @scroll*
              body-top (max 0 (- (long top) (long offset)))
              lo       (max 0 (- (quot body-top ggeo/row-h) overscan))
              hi       (min n (+ (quot (+ body-top (long (or vh 600))) ggeo/row-h) overscan))
              sel      (or selection {})
              vis-rows (max 1 (quot (long (or vh 600)) ggeo/row-h))]
          [:div.vv-gg {:tab-index 0
                       :data-vv-keynav ""
                       :ref #(when % (reset! node* %))
                       :on-key-down (key-handler root vis-rows)}
           [header-bar root repo]
           (cond
             error       [:div.vv-gg-error error]
             empty-repo? [:div.vv-gg-empty "No commits yet"]
             :else
             [:div.vv-gg-body
              [:div {:style {:height (* lo ggeo/row-h)}}]
              (into [:<>]
                    (for [i (range lo hi)
                          :let [commit (get commits i)]
                          :when commit]
                      ^{:key (:hash commit)}
                      [graph-row root max-lane (get rows (dec i)) (get rows i) commit sel]))
              [:div {:style {:height (* (max 0 (- n hi)) ggeo/row-h)}}]])]))})))
