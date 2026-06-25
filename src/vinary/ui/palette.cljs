(ns vinary.ui.palette
  "Command palette / fuzzy finder. One overlay widget, three sources: :command (all visible commands,
   M-x / vim :), :file (the git tree, C-x C-f / Ctrl+P / SPC f f), :theme. Typing fuzzy-filters; ↑/↓
   move; Enter runs the selection; Esc closes."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [clojure.string :as str]
            [vinary.app.nav :as nav]
            [vinary.app.commands :as commands]))

(defn- fuzzy?
  "Subsequence fuzzy match: do q's chars appear in order within s?"
  [q s]
  (let [q (str/lower-case q) s (str/lower-case s)]
    (loop [qi 0 si 0]
      (cond
        (>= qi (count q)) true
        (>= si (count s)) false
        (= (.charAt q qi) (.charAt s si)) (recur (inc qi) (inc si))
        :else (recur qi (inc si))))))

(defn- ctx
  "Resolution context (read app-db directly — called from event handlers outside reactive context).
   :in-input? is forced true so palette keys never trigger global commands."
  []
  (let [db @rfdb/app-db]
    (merge (nav/base-ctx db)
           {:find-visible? (get-in db [:ui :find :visible?])
            :in-input?     true})))

(defn- candidates [source query projects]
  (case source
    :file  (->> projects
                (mapcat (fn [{:keys [root files]}]
                          (map (fn [f] {:label f :path (str root "/" f) :kind :file}) files)))
                (filter #(or (str/blank? query) (fuzzy? query (:label %))))
                (take 60)
                vec)
    :theme (->> [["spacemacs-dark" "Spacemacs Dark"] ["spacemacs-light" "Spacemacs Light"]]
                (filter (fn [[_ l]] (or (str/blank? query) (fuzzy? query l))))
                (mapv (fn [[v l]] {:label l :theme v :kind :theme})))
    (->> (commands/all-visible (ctx))
         (filter #(or (str/blank? query) (fuzzy? query (:title %))))
         (take 60)
         (mapv (fn [c] {:label (:title c) :command (:id c) :category (:category c) :kind :command})))))

(defn- run-item! [item]
  (rf/dispatch [:palette/close])
  (case (:kind item)
    :file    (rf/dispatch [:doc/open (:path item)])
    :theme   (rf/dispatch [:theme/set (:theme item)])
    :command (commands/run (:command item) (ctx) nil)
    nil))

(defn command-palette []
  (let [{:keys [open? source prefix query selected]} @(rf/subscribe [:palette/state])
        projects @(rf/subscribe [:ui/projects])]
    (when open?
      (let [items (candidates source query projects)
            n     (count items)
            sel   (mod (or selected 0) (max 1 n))]
        [:div.vv-palette-overlay {:on-click #(rf/dispatch [:palette/close])}
         [:div.vv-palette {:on-click #(.stopPropagation %)}
          [:div.vv-palette-bar
           (when (seq prefix) [:span.vv-palette-prefix prefix])
           [:input.vv-palette-input
            {:auto-focus  true
             :placeholder (case source :file "Find file…" :theme "Theme…" "Run command…")
             :value       query
             :on-change   #(rf/dispatch [:palette/set-query (.. % -target -value)])
             :on-key-down (fn [^js e]
                            (case (.-key e)
                              "ArrowDown" (do (.preventDefault e) (rf/dispatch [:palette/move 1 n]))
                              "ArrowUp"   (do (.preventDefault e) (rf/dispatch [:palette/move -1 n]))
                              "Enter"     (do (.preventDefault e) (when (pos? n) (run-item! (nth items sel))))
                              "Escape"    (rf/dispatch [:palette/close])
                              nil))}]]
          [:div.vv-palette-list
           (for [[i item] (map-indexed vector items)]
             ^{:key i}
             [:div.vv-palette-item {:class    (when (= i sel) "vv-palette-selected")
                                    :on-click #(run-item! item)}
              [:span.vv-palette-label (:label item)]
              (when (:category item) [:span.vv-palette-cat (:category item)])])]]]))))
