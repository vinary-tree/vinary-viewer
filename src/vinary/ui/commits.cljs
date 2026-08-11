(ns vinary.ui.commits
  "The Commits sidebar tab (ADR-0039): a paged commit list with a mini lane-graph rail, a filterable
   branch combo, a free-form ref-range input, two-commit selection with a Diff-selected affordance,
   and lazy GFM message bodies. All git data arrives through the vv:git-* bridge; per-repo state
   lives at [:ui :commits :repos <root>], shared with the Commit Graph document.

   THE RAIL. Each row is a self-contained SVG cell — windowing-compatible (content-visibility) and
   append-friendly. A cell is a pure function of (previous row's edges, this row's edges, this
   row's lane): the previous row's edge TARGETS supply the ink arriving at this row's top edge, the
   row's own edges supply the dot-anchored and through ink, and quadratics with mid-height control
   points keep the halves visually continuous at every row border."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.app.commits :as commits]
            [vinary.app.projects :as projects]
            [vinary.ui.combo-select :refer [combo-select]]
            [vinary.ui.text-input :as text-input]))

;; ── mini lane rail (ADR-0039 D9) ────────────────────────────────────────────────────────────────
(def ^:private lane-w 10)
(def ^:private row-h 40)
(def ^:private lane-cap 8)

(defn- lx [l] (+ (* (min (long l) (dec lane-cap)) lane-w) (/ lane-w 2)))

(defn- edge-paths
  "[[class d] …] for one rail cell; see the namespace docstring for the two-half convention."
  [prev-edges edges lane]
  (let [mid (/ row-h 2)
        c   (fn [l] (str "vv-lane-" (mod (long l) lane-cap)))]
    (-> []
        ;; incoming top-half: every previous edge targeting the dot's lane continues to the dot
        (into (keep (fn [{:keys [to]}]
                      (when (= to lane)
                        [(c lane) (str "M" (lx lane) " 0 V " mid)])))
              (or prev-edges []))
        (into (mapcat (fn [{:keys [from to kind]}]
                        (case kind
                          :pass     [[(c from) (str "M" (lx from) " 0 V " row-h)]]
                          :collapse [[(c from) (str "M" (lx from) " 0 Q " (lx from) " " mid " "
                                                    (lx lane) " " mid)]]
                          :continue [[(c lane) (str "M" (lx lane) " " mid " V " row-h)]]
                          :branch   [[(c to)   (str "M" (lx lane) " " mid " Q " (lx to) " " mid " "
                                                    (lx to) " " row-h)]]
                          :merge    [[(c to)   (str "M" (lx lane) " " mid " Q " (lx to) " " mid " "
                                                    (lx to) " " row-h)]]
                          [])))
              (or edges [])))))

(defn- rail-cell [prev-row row rail-w]
  [:svg.vv-commits-rail {:width rail-w :height row-h
                         :viewBox (str "0 0 " rail-w " " row-h)
                         :aria-hidden "true"}
   (into [:g]
         (map-indexed (fn [i [cls d]] ^{:key i} [:path {:class cls :d d}])
                      (edge-paths (:edges prev-row) (:edges row) (:lane row))))
   [:circle.vv-commits-dot {:class (str "vv-lane-" (mod (long (:lane row 0)) lane-cap))
                            :cx (lx (:lane row 0)) :cy (/ row-h 2) :r 3.5}]])

;; ── rows ────────────────────────────────────────────────────────────────────────────────────────
(defn- rel-date
  "Compact age for the meta line; falls back to the ISO date part for old commits or unparsable input."
  [iso]
  (let [t (js/Date.parse (str iso))]
    (if (js/isNaN t)
      (str iso)
      (let [s (quot (- (js/Date.now) t) 1000)]
        (cond
          (< s 60)             "now"
          (< s 3600)           (str (quot s 60) "m")
          (< s 86400)          (str (quot s 3600) "h")
          (< s (* 14 86400))   (str (quot s 86400) "d")
          :else                (subs (str iso) 0 10))))))

(defn- ref-chips [refs]
  (when (seq refs)
    (into [:span.vv-commits-refs]
          (for [r refs] ^{:key r} [:span.vv-commits-ref {:title r} r]))))

(defn- commit-row [root rail-w prev-row row commit selection expanded bodies]
  (let [{:keys [hash subject refs body]} commit
        author    (:author-name commit)
        date      (:author-date commit)
        selected? (contains? (:selected selection) hash)
        cursor?   (= (:cursor selection) hash)
        expanded? (contains? expanded hash)]
    [:div.vv-commits-row
     {:class           (str (when selected? "vv-commits-row-sel ") (when cursor? "vv-commits-row-cur"))
      :data-hash       hash
      ;; plain click = diff vs parent (D4); Ctrl = toggle-select; Shift = range-extend (R3)
      :on-click        (fn [^js e]
                         (cond
                           (.-ctrlKey e)  (rf/dispatch [:commits/select root hash :toggle])
                           (.-shiftKey e) (rf/dispatch [:commits/select root hash :range])
                           :else          (do (rf/dispatch [:commits/select root hash :single])
                                              (rf/dispatch [:commits/activate root hash]))))
      :on-context-menu (fn [^js e]
                         (.preventDefault e)
                         (rf/dispatch [:context-menu/show
                                       {:x (.-clientX e) :y (.-clientY e)
                                        :target {:kind :commit :root root :hash hash :subject subject}}]))}
     [rail-cell prev-row row rail-w]
     [:div.vv-commits-main
      [:div.vv-commits-subject-line
       (when-not (str/blank? body)
         [:button.vv-commits-expander
          {:title    (if expanded? "Collapse message" "Expand message")
           :on-click (fn [^js e]
                       (.stopPropagation e)
                       (rf/dispatch [:commits/toggle-expand root hash]))}
          (if expanded? "▾" "▸")])
       [:span.vv-commits-subject {:title subject} subject]
       (ref-chips refs)]
      [:div.vv-commits-meta
       [:span author] " · " [:span (rel-date date)] " · " [:span.vv-commits-hash (subs hash 0 7)]]
      (when expanded?
        (let [html (get bodies hash ::pending)]
          (cond
            ;; sanitized upstream by the single markdown pipeline (:commits/render-body)
            (string? html) [:div.vv-commits-body.markdown-body
                            {:dangerouslySetInnerHTML {:__html html}}]
            (false? html)  [:pre.vv-commits-body-plain body]
            :else          [:div.vv-commits-body-loading "…"])))]]))

;; ── header: repo switcher · branch combo · range input · Diff-selected pill ─────────────────────
(defn- basename-of [root]
  (last (remove str/blank? (str/split (projects/normalized-path root) #"/"))))

(defn- branch-groups [branches]
  (let [by-kind (group-by :kind (:branches branches))]
    (into []
          (keep (fn [[label kind]]
                  (let [items (get by-kind kind)]
                    (when (seq items)
                      {:label label
                       :items (vec (sort-by (juxt (complement :current?) :name) items))}))))
          [["Local" "local"] ["Remote" "remote"] ["Tags" "tag"]])))

(defn- header [root repo repos]
  (let [{:keys [branches ref range-input range-error selection commits]} repo
        pair (commits/diff-pair selection (mapv :hash commits))]
    [:div.vv-commits-header
     (when (> (count repos) 1)
       [combo-select {:value   (basename-of root)
                      :title   "Repository"
                      :groups  [{:label "Repositories"
                                 :items (mapv (fn [p] {:name (:root p)}) repos)}]
                      :on-pick #(rf/dispatch [:commits/set-root %])
                      :class   "vv-commits-repo"}])
     [combo-select {:value   (or ref (:head branches) "HEAD")
                    :title   "Branch / tag"
                    :groups  (branch-groups branches)
                    :on-pick #(rf/dispatch [:commits/set-ref root %])
                    :class   "vv-commits-branch"}]
     [text-input/async-input
      {:value     (or range-input "")
       :on-change #(rf/dispatch [:commits/range-input root %])
       :on-commit (fn [_] (rf/dispatch [:commits/range-submit root]))
       :attrs     {:class "vv-commits-range" :placeholder "A..B, tag, HEAD~3" :spellCheck "false"}}]
     (when pair
       [:button.vv-commits-pill
        {:title    (str "Diff " (subs (first pair) 0 7) " → " (subs (second pair) 0 7))
         :on-click #(rf/dispatch [:commits/diff-selected root])}
        "Diff selected"])
     (when range-error [:div.vv-commits-error range-error])]))

;; ── the list + panel ────────────────────────────────────────────────────────────────────────────
(defn- commits-list [root repo]
  (let [{:keys [commits graph loading? exhausted? empty? error selection expanded bodies]} repo
        rows   (vec (:rows graph))
        rail-w (* lane-w (min lane-cap (inc (long (or (:max-lane graph) 0)))))]
    [:div.vv-commits-list
     (when error [:div.vv-commits-error error])
     (cond
       empty?
       [:div.vv-sidebar-empty "No commits yet"]

       (and (empty? commits) loading?)
       [:div.vv-sidebar-empty "Loading history…"]

       :else
       (into [:<>]
             (concat
              (map-indexed
               (fn [i commit]
                 ^{:key (:hash commit)}
                 [commit-row root rail-w (get rows (dec i)) (get rows i) commit
                  (or selection {}) (or expanded #{}) (or bodies {})])
               commits)
              (cond
                loading?
                [^{:key "more"} [:div.vv-commits-footer "Loading…"]]

                (and (seq commits) (not exhausted?))
                [^{:key "more"}
                 [:button.vv-commits-footer.vv-commits-more
                  {:on-click #(rf/dispatch [:commits/load-more root])}
                  (str "Load more (" (count commits) " shown)")]]

                :else nil))))]))

(defn- panel-body [root]
  (r/create-class
   {:display-name           "vv-commits-panel"
    ;; mount/unmount own the vv:git-watch sync — collapsing the sidebar or switching tabs
    ;; unmounts the panel and releases this window's .git watcher ownership (D8)
    :component-did-mount    (fn [_] (rf/dispatch [:commits/shown root]))
    :component-will-unmount (fn [_] (rf/dispatch [:commits/hidden]))
    :reagent-render
    (fn [root]
      (let [repo  @(rf/subscribe [:commits/for-root root])
            repos @(rf/subscribe [:commits/repos])]
        [:div.vv-commits
         [header root (or repo {}) repos]
         [commits-list root (or repo {})]]))}))

(defn commits-panel
  "The sidebar Commits tab. Keyed by the derived repo root, so a root change (active doc moved to
   another project, or an explicit pin) remounts the body — re-syncing watcher ownership and
   ensuring that repo's data through the ordinary mount path."
  []
  (if-let [root @(rf/subscribe [:commits/panel-root])]
    ^{:key root} [panel-body root]
    [:div.vv-sidebar-empty "No git repository open"]))
