(ns vinary.ui.keybindings-editor
  "The key-binding editor dialog (Settings ▸ Key Bindings ▸ Customize…). A two-pane modal: the LEFT pane
   lists the keymap sets (read-only built-ins first, then draggable custom sets with in-place rename, a
   right-click Clone/Rename/Delete menu, and +/− buttons); the RIGHT pane is the bindable-action catalog
   (vinary.app.commands/registry, grouped by category) where each action shows its current binding chips
   (× to unbind) plus a Capture button + a modifier-chip builder. Every edit is an undoable command
   (vinary.input.kbedit-history) and live-applies + persists. Modifiers use emacs set-semantics — the chip
   order is cosmetic and canonicalizes to C- M- S- on build.

   While the dialog is open it owns a capture-phase window keydown listener that (a) routes keys to the
   active capture, and (b) handles Ctrl+Z / Ctrl+Shift+Z / Esc — ahead of the global resolver."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [clojure.string :as str]
            [vinary.app.commands :as commands]
            [vinary.input.keymaps-registry :as registry]
            [vinary.input.keys :as keys]
            [vinary.ui.access-keys :as access]
            [vinary.ui.icons :as icons]
            [vinary.ui.menu-focus :as menu-focus]))

;; ---------- chord display ----------
(def ^:private named-bases
  {"space" "Space" "enter" "⏎" "tab" "Tab" "escape" "Esc" "backspace" "⌫" "delete" "Del"
   "left" "←" "right" "→" "up" "↑" "down" "↓" "home" "Home" "end" "End" "prior" "PgUp" "next" "PgDn"})

(defn- named? [b]
  (or (contains? named-bases b) (boolean (re-matches #"f\d{1,2}" b))))

(defn- single-letter? [s]
  (boolean (re-matches #"[A-Za-z]" s)))

(defn- base-pretty [b]
  (cond (contains? named-bases b)        (named-bases b)
        (re-matches #"f\d{1,2}" b)       (str/upper-case b)
        :else                            b))

(defn- parse-chord [chord]
  (loop [s chord mods []]
    (cond
      (str/starts-with? s "C-") (recur (subs s 2) (conj mods "Ctrl"))
      (str/starts-with? s "M-") (recur (subs s 2) (conj mods "Alt"))
      (str/starts-with? s "S-") (recur (subs s 2) (conj mods "Shift"))
      :else                     {:mods mods :base s})))

(defn- pretty-chord [chord]
  (let [{:keys [mods base]} (parse-chord chord)]
    (str/join "+" (conj (vec mods) (base-pretty base)))))

(defn- pretty-seq [chords] (str/join " " (map pretty-chord chords)))

(defn- build-chord
  "An ordered modifier vector + a base token → a canonical chord (C- M- S- order)."
  [mods base]
  (let [b0 (keys/normalize-token (str/trim (or base "")))]
    (when (seq b0)
      (let [ctrl  (boolean (some #{"Ctrl"} mods))
            alt   (boolean (some #{"Alt"} mods))
            shift (boolean (some #{"Shift"} mods))
            modified? (or ctrl alt)
            letter? (single-letter? b0)
            b     (cond
                    (and shift modified? letter?) (str/lower-case b0)
                    (and shift (not modified?) (not (named? b0)) (= 1 (count b0))) (str/upper-case b0)
                    :else b0)
            shift' (and shift (or (named? b0) (and modified? letter?)))]
        (str (when ctrl "C-") (when alt "M-") (when shift' "S-") b)))))

;; ---------- left pane: sets ----------
(defn- rename-input [id name]
  (let [draft (r/atom name)]
    (fn [id _name]
      [:input.vv-kb-rename
       {:value     @draft
        :auto-focus true
        :on-focus   (fn [^js e] (.select (.-target e)))
        :on-change  #(reset! draft (.. % -target -value))
        :on-blur    #(rf/dispatch [:kbedit/commit-rename id @draft])
        :on-click   #(.stopPropagation %)
        :on-key-down (fn [^js e]
                       (case (.-key e)
                         "Enter"  (do (.preventDefault e) (rf/dispatch [:kbedit/commit-rename id @draft]))
                         "Escape" (do (.stopPropagation e) (rf/dispatch [:kbedit/cancel-rename]))
                         nil))}])))

(defn- set-row [{:keys [id name builtin? custom? active? focused?]} editing?]
  [:div {:class (str "vv-kb-set" (when focused? " vv-kb-set-focused") (when builtin? " vv-kb-readonly"))
         :draggable (boolean custom?)
         :on-click  #(rf/dispatch [:kbedit/select id])
         :on-double-click #(when custom? (rf/dispatch [:kbedit/begin-rename id]))
         :on-context-menu (fn [^js e] (.preventDefault e) (.stopPropagation e)
                            (rf/dispatch [:kbedit/ctx-show {:x (.-clientX e) :y (.-clientY e) :id id}]))
         :on-drag-start (fn [^js e] (when custom? (.setData (.-dataTransfer e) "text/plain" id)))
         :on-drag-over  (fn [^js e] (when custom? (.preventDefault e)))
         :on-drop       (fn [^js e]
                          (.preventDefault e)
                          (let [from (.getData (.-dataTransfer e) "text/plain")]
                            (rf/dispatch [:kbedit/reorder from id])))}
   (if editing?
     [rename-input id name]
     [:span.vv-kb-set-name name])
   (cond active?  [:span.vv-kb-badge.vv-kb-badge-active "active"]
         builtin? [:span.vv-kb-badge "built-in"])])

(defn- sets-pane []
  (let [sets    @(rf/subscribe [:kbedit/sets])
        editing @(rf/subscribe [:kbedit/editing])
        sel     @(rf/subscribe [:kbedit/sel])
        sel-custom? (boolean (:custom? (first (filter #(= (:id %) sel) sets))))]
    [:div.vv-kb-sets
     [:div.vv-kb-pane-head
      [:span "Key Binding Sets"]
      [:span.vv-kb-pane-actions
       [:button.vv-kb-iconbtn (merge {:title "New set (Alt+N)" :on-click #(rf/dispatch [:kbedit/add])}
                                     (access/access-attrs "n")) (icons/icon :add)]
       [:button.vv-kb-iconbtn (merge {:title "Delete the selected set (Alt+D)" :disabled (not sel-custom?)
                                      :on-click #(when sel-custom? (rf/dispatch [:kbedit/delete sel]))}
                                     (access/access-attrs "d")) (icons/icon :delete)]]]
     [:div.vv-kb-set-list
      (for [{:keys [id] :as s} sets]
        ^{:key id} [set-row s (= id editing)])]]))

;; ---------- the modifier-chip builder (inside the capture overlay) ----------
(defn- mod-chip [builder i m]
  [:span.vv-kb-modchip
   [:button.vv-kb-modmove {:title "Move left"  :disabled (zero? i)
                           :on-click #(swap! builder update :mods (fn [v] (let [a (get v (dec i)) b (get v i)]
                                                                            (assoc v (dec i) b i a))))} (icons/icon :move-left)]
   [:span.vv-kb-modname m]
   [:button.vv-kb-modmove {:title "Move right" :disabled (= i (dec (count (:mods @builder))))
                           :on-click #(swap! builder update :mods (fn [v] (let [a (get v (inc i)) b (get v i)]
                                                                            (assoc v (inc i) b i a))))} (icons/icon :move-right)]
   [:button.vv-kb-modx {:title "Remove" :on-click #(swap! builder update :mods (fn [v] (vec (concat (subvec v 0 i) (subvec v (inc i))))))} (icons/icon :close)]])

(defn- builder-row [builder]
  [:div.vv-kb-builder
   [:div.vv-kb-builder-mods
    (for [[i m] (map-indexed vector (:mods @builder))] ^{:key (str i m)} [mod-chip builder i m])
    (for [m ["Ctrl" "Alt" "Shift"]]
      (when-not (some #{m} (:mods @builder))
        ^{:key m} [:button.vv-kb-modadd {:on-click #(swap! builder update :mods (fnil conj []) m)} (str "+ " m)]))]
   [:input.vv-kb-base {:placeholder "key (e.g. x, space, f1)" :value (:base @builder)
                       :on-click #(.stopPropagation %)
                       :on-change #(swap! builder assoc :base (.. % -target -value))}]
   [:button.vv-kb-addchord
    {:disabled (str/blank? (:base @builder))
     :on-click #(when-let [c (build-chord (:mods @builder) (:base @builder))]
                  (rf/dispatch [:kbedit/capture-chord c])
                  (reset! builder {:mods [] :base ""}))} "Add chord"]])

;; ---------- capture overlay ----------
(defn- capture-overlay []
  (let [builder (r/atom {:mods [] :base ""})]
    (fn []
      (when-let [{:keys [chords action]} @(rf/subscribe [:kbedit/capture])]
        [:div.vv-kb-capture-overlay {:on-click #(rf/dispatch [:kbedit/capture-cancel])}
         [:div.vv-kb-capture {:on-click #(.stopPropagation %)}
          [:div.vv-kb-capture-title "Capture key binding"]
          [:div.vv-kb-capture-sub (get-in commands/registry [action :title])]
          [:div.vv-kb-capture-hint "Press a key or chord — chain for a sequence (e.g. Ctrl+x Ctrl+f). "
           "Esc cancels · Enter confirms."]
          [:div.vv-kb-capture-seq
           (if (seq chords)
             (for [[i ch] (map-indexed vector chords)]
               ^{:key i} [:span.vv-kb-chord (pretty-chord ch)])
             [:span.vv-kb-capture-empty "(press keys…)"])]
          [builder-row builder]
          [:div.vv-kb-capture-btns
           [:button.vv-kb-btn {:disabled (empty? chords) :on-click #(rf/dispatch [:kbedit/capture-pop])} (icons/icon :backspace {:class "vv-ico-gap"}) "Remove last"]
           [:span.vv-kb-spacer]
           [:button.vv-kb-btn {:on-click #(rf/dispatch [:kbedit/capture-cancel])} "Cancel"]
           [:button.vv-kb-btn.vv-kb-btn-primary {:disabled (empty? chords)
                                                 :on-click #(rf/dispatch [:kbedit/capture-commit])} "Done"]]]]))))

;; ---------- right pane: action catalog ----------
(def ^:private category-order ["Tabs" "File" "Navigation" "Search" "View" "Mode" "Editor" "Settings" "Help"])

(defn- binding-chips [{:keys [id custom? default-mode]} action idx]
  (let [bindings (get idx action)]
    [:span.vv-kb-chips
     (for [[i [mode chords]] (map-indexed vector bindings)]
       ^{:key i}
       [:span.vv-kb-chip {:title (str (name mode) " mode")}
        (pretty-seq chords)
        (when custom?
          [:span.vv-kb-chip-x {:title "Remove this binding"
                               :on-click #(rf/dispatch [:kbedit/unbind id mode chords])} (icons/icon :close)])])
     (when (empty? bindings) [:span.vv-kb-chip-none "—"])]))

(defn- action-row [{:keys [id custom? default-mode] :as focused} action-spec idx]
  (let [action (:id action-spec)]
    [:div.vv-kb-action
     [:div.vv-kb-action-info
      [:span.vv-kb-action-title (:title action-spec)]
      [binding-chips focused action idx]]
     (when custom?
       [:div.vv-kb-action-btns
        [:button.vv-kb-iconbtn {:title "Capture a key binding"
                                :on-click #(rf/dispatch [:kbedit/capture-start {:set-id id :mode default-mode :action action}])}
         (icons/icon :keyboard {:class "vv-ico-gap"}) "Capture"]])]))

(defn- actions-pane []
  (let [{:keys [id name builtin? custom? modal?] :as focused} @(rf/subscribe [:kbedit/focused])
        idx @(rf/subscribe [:kbedit/action-index])
        by-cat (group-by :category (vals commands/registry))
        access-active? @(rf/subscribe [:ui/access-keys-active?])]
    [:div.vv-kb-actions
     [:div.vv-kb-pane-head
      [:span "Bindings — " [:b name]]
      (when builtin?
        [:span.vv-kb-pane-actions
         [:button.vv-kb-iconbtn (merge {:title "Clone to a new editable set (Alt+L)"
                                        :on-click #(rf/dispatch [:kbedit/clone id])}
                                       (access/access-attrs "l"))
          [access/label "Clone" "l" access-active?]]])]
     (when builtin?
       [:div.vv-kb-readonly-banner "Built-in sets are read-only. Clone this set to customize its bindings."])
     [:div.vv-kb-action-list
      (for [cat category-order
            :let [specs (->> (get by-cat cat)
                             ;; hide vim-only :mode/* actions for non-modal sets (keep Escape/cancel)
                             (remove #(and (= cat "Mode") (not modal?) (not= (:id %) :input/escape)))
                             (sort-by :title))]
            :when (seq specs)]
        ^{:key cat}
        [:div.vv-kb-cat
         [:div.vv-kb-cat-head cat]
         (for [spec specs] ^{:key (:id spec)} [action-row focused spec idx])])]]))

;; ---------- editor context menu (left-pane right-click) ----------
(defn- editor-ctx-items [id custom?]
  (cond-> [{:label "Clone" :event [:kbedit/clone id]}]
    custom? (conj {:label "Rename" :event [:kbedit/begin-rename id]}
                  {:label "Delete" :event [:kbedit/delete id]})))

(defn- close-and-dispatch! [event]
  (rf/dispatch [:kbedit/ctx-close])
  (rf/dispatch event))

(defn- consume! [^js e]
  (.preventDefault e)
  (.stopPropagation e))

(defn- editor-ctx-keydown [items focus ^js e]
  (case (.-key e)
    "ArrowDown" (do (consume! e) (reset! focus (menu-focus/move-index items @focus 1)))
    "ArrowUp"   (do (consume! e) (reset! focus (menu-focus/move-index items @focus -1)))
    "Home"      (do (consume! e) (reset! focus (menu-focus/first-index items)))
    "End"       (do (consume! e) (reset! focus (menu-focus/last-index items)))
    "Enter"     (do (consume! e) (when-let [item (menu-focus/item-at items @focus)] (close-and-dispatch! (:event item))))
    " "         (do (consume! e) (when-let [item (menu-focus/item-at items @focus)] (close-and-dispatch! (:event item))))
    "Escape"    (do (consume! e) (rf/dispatch [:kbedit/ctx-close]))
    nil))

(defn- editor-ctx []
  (r/with-let [focus (r/atom nil)
               last-ctx (r/atom nil)]
    (let [ctx @(rf/subscribe [:kbedit/ctx])]
      (if-let [{:keys [x y id] :as ctx} ctx]
        (let [sets    @(rf/subscribe [:kbedit/sets])
              custom? (:custom? (first (filter #(= (:id %) id) sets)))
              items   (editor-ctx-items id custom?)
              focused @focus]
          (when (not= @last-ctx ctx)
            (reset! last-ctx ctx)
            (reset! focus nil))
          [:div.vv-ctx-overlay {:on-click #(rf/dispatch [:kbedit/ctx-close])
                                :on-context-menu (fn [^js e] (.preventDefault e) (rf/dispatch [:kbedit/ctx-close]))}
           [:div.vv-ctx-menu {:style {:left (str x "px") :top (str y "px")}
                              :role "menu"
                              :tab-index 0
                              :on-click #(.stopPropagation %)
                              :on-key-down #(editor-ctx-keydown items focus %)
                              :ref (fn [el] (when el (.focus el)))}
            (doall
             (for [[i item] (map-indexed vector items)]
               ^{:key (:label item)}
               [:div.vv-menu-item {:class (when (= focused i) "vv-menu-item-focused")
                                   :role "menuitem"
                                   :on-mouse-enter #(reset! focus i)
                                   :on-click #(close-and-dispatch! (:event item))}
                [:span.vv-menu-item-label (:label item)]]))]])
        nil))))

;; ---------- the dialog ----------
(defn- handle-access-key! [db k]
  (let [sel (get-in db [:ui :kbedit :sel])]
    (case k
      "u" (when (seq (get-in db [:ui :kbedit :undo])) (rf/dispatch [:kbedit/undo]) true)
      "r" (when (seq (get-in db [:ui :kbedit :redo])) (rf/dispatch [:kbedit/redo]) true)
      "c" (do (rf/dispatch [:kbedit/close]) true)
      "n" (do (rf/dispatch [:kbedit/add]) true)
      "d" (do (when (registry/custom? db sel) (rf/dispatch [:kbedit/delete sel])) true)
      "l" (do (when sel (rf/dispatch [:kbedit/clone sel])) true)
      false)))

(defn- on-keydown [^js e]
  (let [db  @rfdb/app-db
        cap (get-in db [:ui :kbedit :capture])
        ctx (get-in db [:ui :kbedit :ctx])]
    (if cap
      (let [k (.-key e)]
        (.preventDefault e) (.stopPropagation e)
        (cond
          (= k "Escape") (rf/dispatch [:kbedit/capture-cancel])
          (= k "Enter")  (rf/dispatch [:kbedit/capture-commit])
          :else          (when-let [chord (keys/event->chord e (keys/mac?))]
                           (rf/dispatch [:kbedit/capture-chord chord]))))
      (if (and ctx (= "Escape" (.-key e)))
        (do (.preventDefault e) (.stopPropagation e) (rf/dispatch [:kbedit/ctx-close]))
        (if-let [k (and (.-altKey e) (access/event-letter e))]
          (when (handle-access-key! db k)
            (.preventDefault e)
            (.stopPropagation e))
          ;; Ctrl+Shift+Z normalizes to C-S-z; C-y is the conventional redo fallback.
          (case (keys/event->chord e (keys/mac?))
            "C-z"                 (do (.preventDefault e) (.stopPropagation e) (rf/dispatch [:kbedit/undo]))
            ("C-S-z" "C-y")       (do (.preventDefault e) (.stopPropagation e) (rf/dispatch [:kbedit/redo]))
            "escape"              (do (.preventDefault e) (.stopPropagation e) (rf/dispatch [:kbedit/close]))
            nil))))))

(defn dialog []
  (r/create-class
   {:display-name "vv-kbedit"
    :component-did-mount    (fn [_] (.addEventListener js/window "keydown" on-keydown true))
    :component-will-unmount (fn [_] (.removeEventListener js/window "keydown" on-keydown true))
    :reagent-render
    (fn []
      (let [can-undo? @(rf/subscribe [:kbedit/can-undo?])
            can-redo? @(rf/subscribe [:kbedit/can-redo?])
            access-active? @(rf/subscribe [:ui/access-keys-active?])]
        [:div.vv-modal-overlay {:on-click #(rf/dispatch [:kbedit/close])}
         [:div.vv-modal.vv-modal-wide.vv-kb-modal {:on-click #(.stopPropagation %)}
          [:div.vv-kb-header
           [:span.vv-kb-header-title "Key Bindings"]
           [:span.vv-kb-header-btns
            [:button.vv-kb-btn (merge {:disabled (not can-undo?) :title "Undo (Ctrl+Z, Alt+U)"
                                       :on-click #(rf/dispatch [:kbedit/undo])}
                                      (access/access-attrs "u"))
             (icons/icon :undo {:class "vv-ico-gap"}) [access/label "Undo" "u" access-active?]]
            [:button.vv-kb-btn (merge {:disabled (not can-redo?) :title "Redo (Ctrl+Shift+Z, Alt+R)"
                                       :on-click #(rf/dispatch [:kbedit/redo])}
                                      (access/access-attrs "r"))
             (icons/icon :redo {:class "vv-ico-gap"}) [access/label "Redo" "r" access-active?]]
            [:button.vv-kb-btn (merge {:on-click #(rf/dispatch [:kbedit/close])}
                                      (access/access-attrs "c"))
             [access/label "Close" "c" access-active?]]]]
          [:div.vv-kb-body
           [sets-pane]
           [actions-pane]]
          [capture-overlay]
          [editor-ctx]]]))}))
