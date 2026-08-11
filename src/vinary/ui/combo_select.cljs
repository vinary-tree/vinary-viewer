(ns vinary.ui.combo-select
  "A filterable dropdown (ADR-0039): a combo-button-style trigger opening an anchored panel with a
   type-to-filter input and grouped, keyboard-navigable rows. Assembled from the house parts — the
   combo-button shell idea (vinary.ui.views/combo-button), the ADR-0033 async-input (a LOCAL ratom
   is its model here: on-change stores verbatim, so the echo contract holds trivially and no
   re-frame round-trip can clobber a keystroke), and vinary.search.match under the :branch-combo
   surface. Reused by the Commits panel (repo + branch pickers) and the Commit Graph toolbar."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [vinary.search.config :as search-config]
            [vinary.search.match :as match]
            [vinary.ui.text-input :as text-input]))

(def ^:private row-cap 200)

(defn- filtered-groups
  "Narrow each group's items by the query (matched on :name); groups with no survivors vanish."
  [groups q match-opts]
  (let [m (when-not (str/blank? q) (match/matcher q match-opts))]
    (into []
          (keep (fn [{:keys [label items]}]
                  (let [is (if m (filterv #(some? (m (str (:name %)))) items) (vec items))]
                    (when (seq is) {:label label :items is}))))
          groups)))

(defn- cap-groups
  "Bound the panel to row-cap rows total (a monster repo's ref list) → [groups capped?]."
  [groups]
  (loop [gs groups, left row-cap, out []]
    (if-let [{:keys [items] :as g} (first gs)]
      (cond
        (zero? left)            [out true]
        (<= (count items) left) (recur (rest gs) (- left (count items)) (conj out g))
        :else                   [(conj out (assoc g :items (subvec (vec items) 0 left))) true])
      [out false])))

(defn combo-select
  "Props:
     :value          the trigger's label (the current pick)
     :title          trigger tooltip
     :groups         [{:label \"Local\" :items [{:name … :meta str? :current? bool}]}]
     :on-pick        (fn [name])
     :disabled?      render the trigger inert (with :disabled-title explaining why)
     :class          extra class on the wrapper"
  [_props]
  (let [open?  (r/atom false)
        query  (r/atom "")
        cursor (r/atom 0)]
    (fn [{:keys [value title groups on-pick disabled? disabled-title class]}]
      (let [opts            (search-config/mode-for :branch-combo)
            [shown capped?] (cap-groups (filtered-groups groups @query opts))
            flat            (vec (mapcat :items shown))
            name->i         (into {} (map-indexed (fn [i it] [(:name it) i])) flat)
            pick!           (fn [name]
                              (reset! open? false)
                              (reset! query "")
                              (reset! cursor 0)
                              (on-pick name))
            move!           (fn [d] (when (seq flat)
                                      (swap! cursor #(mod (+ % d) (count flat)))))]
        [:div.vv-combo-select {:class class :on-mouse-leave #(reset! open? false)}
         [:button.vv-combo-main
          {:title         (if disabled? (or disabled-title title) title)
           :disabled      (boolean disabled?)
           :aria-haspopup "listbox"
           :on-click      #(when-not disabled?
                             (swap! open? not)
                             (reset! query "")
                             (reset! cursor 0))}
          (str value) " ▾"]
         (when @open?
           [:div.vv-combo-select-panel
            [text-input/async-input
             {:value       @query
              :on-change   #(do (reset! query %) (reset! cursor 0))
              :on-cancel   #(reset! open? false)
              :on-key-down (fn [^js e]
                             (case (.-key e)
                               "ArrowDown" (do (.preventDefault e) (move! 1))
                               "ArrowUp"   (do (.preventDefault e) (move! -1))
                               "Enter"     (do (.preventDefault e)
                                               (when-let [it (get flat (min @cursor (max 0 (dec (count flat)))))]
                                                 (pick! (:name it))))
                               nil))
              :attrs       {:class "vv-combo-select-filter" :placeholder "Filter…"
                            :auto-focus true :spellCheck "false"}}]
            (into [:ul.vv-combo-select-list {:role "listbox"}]
                  (concat
                   (mapcat (fn [{:keys [label items]}]
                             (cons ^{:key (str "hdr:" label)}
                                   [:li.vv-combo-select-hdr label]
                                   (for [{:keys [name meta current?]} items]
                                     ^{:key (str label ":" name)}
                                     [:li.vv-combo-select-row
                                      {:role          "option"
                                       :class         (str (when (= (get name->i name) @cursor) "vv-combo-select-cur ")
                                                           (when current? "vv-combo-select-current"))
                                       :on-mouse-down (fn [^js e] (.preventDefault e) (pick! name))}
                                      (when current? [:span.vv-combo-select-check "✓ "])
                                      [:span.vv-combo-select-name name]
                                      (when meta [:span.vv-combo-select-meta meta])])))
                           shown)
                   (when capped?
                     [^{:key "cap"}
                      [:li.vv-combo-select-hdr (str "…more than " row-cap " rows — type to filter")]])))])]))))
