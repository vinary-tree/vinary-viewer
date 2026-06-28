(ns vinary.ui.menubar
  "The custom, theme-matched menu bar (File / View / Settings / Help). Clicking a top-level label opens its
   dropdown; items dispatch re-frame events (the same events the keybindings use). Settings hosts radio
   SUBMENUS (Theme, Key Bindings) that flyout to the right and mark the active choice. A full-window overlay
   closes on click-away; hovering another top-level label switches menus."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [vinary.app.uri :as uri]
            [vinary.app.zoom :as zoom]
            [vinary.input.keymaps-registry :as registry]
            [vinary.ui.access-keys :as access]
            [vinary.ui.icons :as icons]
            [vinary.ui.menu-focus :as menu-focus]))

(def ^:private themes
  [["spacemacs-dark" "Spacemacs Dark" "d"]
   ["spacemacs-light" "Spacemacs Light" "l"]])

(def menus
  [{:label "File" :access-key "f"
    :items [{:label "Open…"     :access-key "o" :accel "Ctrl+O" :event [:file/open-dialog]}
            {:label "Open in New Tab…" :access-key "n" :accel "Ctrl+Shift+O" :event [:file/open-dialog :new-tab]}
            {:submenu "Open Recent" :access-key "r" :radio :sub/recent}
            {:label "Close Tab" :access-key "c" :accel "Ctrl+W" :event [:tab/close-active]}
            :sep
            {:label "Reload"    :access-key "r" :accel "Ctrl+R" :event [:tab/reload]}
            {:label "Quit"      :access-key "q" :accel "Ctrl+Q" :event [:app/quit]}]}
   {:label "View" :access-key "v"
    :items [{:label "Toggle Sidebar" :access-key "s" :accel "Ctrl+B" :event [:sidebar/toggle]}
            :sep
            {:label "View Source"    :access-key "v" :event [:tab/toggle-source]}
            {:label "Find…"          :access-key "n" :accel "Ctrl+F" :event [:find/toggle]}
            :sep
            {:label "Zoom In"        :access-key "i" :accel "Ctrl++" :event [:view/zoom 1]}
            {:label "Zoom Out"       :access-key "o" :accel "Ctrl+-" :event [:view/zoom -1]}
            {:label "Reset Zoom"     :access-key "r" :accel "Ctrl+0" :event [:view/zoom 0]}
            :sep
            {:submenu "Fit" :access-key "f" :radio :sub/fit :pdf-only true}
            {:label "Invert PDF"        :access-key "t" :event [:pdf/invert-toggle] :pdf-only true}
            :sep
            {:label "Developer Tools" :access-key "d" :accel "Ctrl+Shift+I" :event [:view/devtools]}
            {:label "re-frame-10x"   :access-key "x" :event [:view/re-frame-10x] :dev-only true}]}
   {:label "Settings" :access-key "s"
    :items [{:submenu "Theme"        :access-key "t" :radio :sub/theme}
            {:submenu "Key Bindings" :access-key "k" :radio :sub/keymaps}
            :sep
            {:label "Preferences…"   :access-key "p" :event [:settings/open]}
            {:label "Extensions…"    :access-key "x" :event [:extensions/open]}]}
   {:label "Help" :access-key "h"
    :items [{:label "Command Palette" :access-key "c" :accel "Ctrl+Shift+P" :event [:palette/open {:source :command}]}
            :sep
            {:label "About vinary-viewer" :access-key "a" :event [:about/open]}]}])

(def ^:private submenu-preferred-keys
  {"Spacemacs Dark" "d"
   "Spacemacs Light" "l"
   "Standard Mode" "s"
   "Vim Mode" "v"
   "Emacs Mode" "e"
   "Customize…" "c"})

(defn radio-rows-from-db
  "The flyout rows for a submenu source, using db so keyboard access can resolve outside subscriptions."
  [db src]
  (let [rows (case src
               :sub/theme   (let [cur (get-in db [:ui :theme])]
                               (mapv (fn [[v label k]]
                                       {:label label :access-key k :selected? (= v cur) :event [:theme/set v]})
                                     themes))
               :sub/keymaps (conj (mapv (fn [id]
                                           {:label (registry/display-name db id)
                                            :selected? (= id (registry/active-id db))
                                            :event [:keymap/select id]})
                                         (registry/set-ids db))
                                   :sep
                                   {:label "Customize…" :access-key "c" :event [:kbedit/open]})
               :sub/recent  (let [files (get-in db [:ui :recent :recent-files])]
                              (if (seq files)
                                (conj (mapv (fn [p] {:label (uri/basename p) :event [:doc/open p]}) files)
                                      :sep
                                      {:label "Clear Recent" :event [:recent/clear]})
                                [{:label "No recent files" :event [:menu/close]}]))
               :sub/fit     (let [cur (get-in db [:ui :pdf :fit])]
                              [{:label "Fit Width"   :access-key "w" :selected? (= :width cur)  :event [:pdf/fit :width]}
                               {:label "Fit Page"    :access-key "p" :selected? (= :page cur)   :event [:pdf/fit :page]}
                               {:label "Actual Size" :access-key "a" :selected? (= :actual cur) :event [:pdf/fit :actual]}])
               nil)]
    (access/annotate-rows (or rows []) submenu-preferred-keys)))

(defn- radio-rows
  "The flyout rows for a submenu source — each {:label :selected? :event :access-key} (or :sep)."
  [src]
  (radio-rows-from-db @rfdb/app-db src))

;; ---- PDF-only / dev-only View-menu items -------------------------------------------------------
;; The Fit submenu + Invert PDF apply only to a PDF; re-frame-10x exists only in dev builds (its runtime
;; is stripped from :release, so the item was a silent no-op there). Both are filtered from the View menu
;; — for rendering AND keyboard nav/access-keys — so the two paths stay consistent. The render passes the
;; :view/pdf-active? sub value; the event/keyboard path reads app-db directly (no reactivity needed there).
(defn- pdf-active-now? [] (= :pdf (zoom/context @rfdb/app-db)))

(defn- item-visible? [pdf? item]
  (cond
    (not (map? item)) true                     ; :sep — kept here, tidied by clean-seps
    (:pdf-only item)  pdf?
    (:dev-only item)  ^boolean js/goog.DEBUG    ; false in :release → re-frame-10x hidden there
    :else             true))

(defn- clean-seps
  "Drop separators left leading, trailing, or consecutive by item filtering."
  [items]
  (let [kept (reduce (fn [acc it]
                       (if (and (= it :sep) (or (empty? acc) (= :sep (peek acc)))) acc (conj acc it)))
                     [] items)]
    (if (= :sep (peek kept)) (pop kept) kept)))

(defn- filter-items [pdf? menu]
  (update menu :items (fn [items] (clean-seps (filterv #(item-visible? pdf? %) items)))))

(defn- effective-menus [pdf?] (mapv #(filter-items pdf? %) menus))

(defn- menu-index [label]
  (first (keep-indexed (fn [idx m] (when (= (:label m) label) idx)) menus)))

(defn- adjacent-menu-label [label dir]
  (let [idx (or (menu-index label) 0)]
    (:label (nth menus (mod (+ idx dir) (count menus))))))

(defn menu-for-access-key [k]
  (some (fn [m] (when (access/match? (:access-key m) k) m)) menus))

(defn- open-menu-spec [label]
  (some (fn [m] (when (= (:label m) label) (filter-items (pdf-active-now?) m))) menus))

(defn- submenu-spec [menu submenu]
  (some (fn [item] (when (and (map? item) (= (:submenu item) submenu)) item)) (:items menu)))

(defn- focus-main! [menu idx]
  (rf/dispatch [:menu/focus idx])
  (if-let [submenu (:submenu (get (:items menu) idx))]
    (rf/dispatch [:menu/submenu submenu])
    (rf/dispatch [:menu/submenu nil])))

(defn- focus-submenu! [idx]
  (rf/dispatch [:menu/submenu-focus idx]))

(defn- focused-submenu-rows [db menu]
  (when-let [submenu (get-in db [:ui :menu-submenu])]
    (when-let [item (submenu-spec menu submenu)]
      (radio-rows-from-db db (:radio item)))))

(defn- open-submenu-with-focus! [db menu item]
  (let [rows (radio-rows-from-db db (:radio item))]
    (rf/dispatch [:menu/submenu (:submenu item)])
    (rf/dispatch [:menu/submenu-focus (menu-focus/first-index rows)])))

(defn- switch-menu! [label dir]
  (let [next-label (adjacent-menu-label label dir)]
    (rf/dispatch [:menu/open next-label])
    (when-let [menu (open-menu-spec next-label)]
      (rf/dispatch [:menu/focus (menu-focus/first-index (:items menu))]))))

(defn access-action
  "Resolve a menu access key against db. Returns one of:
   {:action :open-menu :label ...}, {:action :open-submenu :submenu ...}, or {:action :dispatch :event ...}."
  [db k]
  (let [open    (get-in db [:ui :menu])
        submenu (get-in db [:ui :menu-submenu])]
    (if open
      (let [menu (open-menu-spec open)]
        (or (when-let [sub (and submenu (submenu-spec menu submenu))]
              (some (fn [row]
                      (when (and (map? row) (access/match? (:access-key row) k))
                        {:action :dispatch :event (:event row)}))
                    (radio-rows-from-db db (:radio sub))))
            (some (fn [item]
                    (when (and (map? item) (access/match? (:access-key item) k))
                      (if (:submenu item)
                        {:action :open-submenu :submenu (:submenu item)}
                        {:action :dispatch :event (:event item)})))
                  (:items menu))))
      (when-let [menu (menu-for-access-key k)]
        {:action :open-menu :label (:label menu)}))))

(defn- act! [event]
  (rf/dispatch [:menu/close])
  (rf/dispatch event))

(defn- menu-item-class
  ([base focused?]
   (str base (when focused? " vv-menu-item-focused")))
  ([base focused? extra]
   (str base (when extra (str " " extra)) (when focused? " vv-menu-item-focused"))))

(defn- row-item [row access-active? focused? on-focus]
  [:div {:class (menu-item-class "vv-menu-item vv-menu-item-radio" focused?)
         :role "menuitemradio"
         :aria-checked (when (contains? row :selected?) (boolean (:selected? row)))
         :on-mouse-enter on-focus
         :on-click #(act! (:event row))}
   (if (contains? row :selected?)
     [:span.vv-radio-dot {:class (when (:selected? row) "vv-radio-dot-on")}]
     [:span.vv-radio-dot.vv-radio-dot-blank])
   [:span.vv-menu-item-label [access/label (:label row) (:access-key row) access-active?]]])

(defn- dispatch-access-action! [action]
  (case (:action action)
    :open-menu    (do (rf/dispatch [:access-keys/set true])
                      (rf/dispatch [:menu/open (:label action)])
                      (when-let [menu (open-menu-spec (:label action))]
                        (rf/dispatch [:menu/focus (menu-focus/first-index (:items menu))])))
    :open-submenu (do (rf/dispatch [:access-keys/set true])
                      (rf/dispatch [:menu/submenu (:submenu action)]))
    :dispatch     (act! (:event action))
    nil))

(defn- dispatch-menu-nav! [db ^js e]
  (when-let [label (get-in db [:ui :menu])]
    (when-let [menu (open-menu-spec label)]
      (let [main-focus (get-in db [:ui :menu-focus])
            sub-focus  (get-in db [:ui :menu-submenu-focus])
            key        (.-key e)
            in-sub?    (some? sub-focus)
            rows       (when in-sub? (focused-submenu-rows db menu))]
        (when (#{"ArrowDown" "ArrowUp" "ArrowLeft" "ArrowRight" "Home" "End" "Enter" " "} key)
          (access/consume! e)
          (case key
            "ArrowDown"
            (if in-sub?
              (focus-submenu! (menu-focus/move-index rows sub-focus 1))
              (focus-main! menu (menu-focus/move-index (:items menu) main-focus 1)))

            "ArrowUp"
            (if in-sub?
              (focus-submenu! (menu-focus/move-index rows sub-focus -1))
              (focus-main! menu (menu-focus/move-index (:items menu) main-focus -1)))

            "Home"
            (if in-sub?
              (focus-submenu! (menu-focus/first-index rows))
              (focus-main! menu (menu-focus/first-index (:items menu))))

            "End"
            (if in-sub?
              (focus-submenu! (menu-focus/last-index rows))
              (focus-main! menu (menu-focus/last-index (:items menu))))

            "ArrowRight"
            (let [item (menu-focus/item-at (:items menu) main-focus)]
              (cond
                in-sub? nil
                (:submenu item) (open-submenu-with-focus! db menu item)
                :else (switch-menu! label 1)))

            "ArrowLeft"
            (if in-sub?
              (focus-submenu! nil)
              (switch-menu! label -1))

            ("Enter" " ")
            (if in-sub?
              (when-let [row (menu-focus/item-at rows sub-focus)]
                (act! (:event row)))
              (when-let [item (menu-focus/item-at (:items menu) main-focus)]
                (if (:submenu item)
                  (open-submenu-with-focus! db menu item)
                  (act! (:event item)))))

            nil)
          true)))))

(defn- modal-open? [db]
  (or (get-in db [:ui :settings-open?])
      (get-in db [:ui :about-open?])
      (get-in db [:ui :kbedit :open?])
      (get-in db [:ui :palette :open?])
      (get-in db [:ui :context-menu])))

(defn- handle-keydown! [^js e]
  (let [db @rfdb/app-db]
    (when-not (get-in db [:ui :kbedit :capture])
      (cond
        (= "Alt" (.-key e))
        (rf/dispatch [:access-keys/set true])

        (and (= "Escape" (.-key e))
             (get-in db [:ui :menu]))
        (do (access/consume! e)
            (rf/dispatch [:menu/close]))

        (and (get-in db [:ui :menu])
             (dispatch-menu-nav! db e))
        nil

        :else
        (when-let [k (access/event-letter e)]
          (when-let [action (and (or (get-in db [:ui :menu])
                                      (and (.-altKey e) (not (modal-open? db))))
                                 (access-action db k))]
            (access/consume! e)
            (dispatch-access-action! action)))))))

(defn- handle-keyup! [^js e]
  (when (and (= "Alt" (.-key e)) (nil? (get-in @rfdb/app-db [:ui :menu])))
    (rf/dispatch [:access-keys/set false])))

(defonce ^:private access-installed? (atom false))

(defn install-access-keys! []
  (when-not @access-installed?
    (reset! access-installed? true)
    (.addEventListener js/window "keydown" handle-keydown! true)
    (.addEventListener js/window "keyup" handle-keyup! true)))

(defn- submenu-dropdown [rows access-active? sub-focus]
  (into [:div.vv-menu-subdropdown {:role "menu"}]
        (map (fn [[j row]]
               (if (= row :sep)
                 ^{:key j} [:div.vv-menu-sep]
                 ^{:key j} [row-item row access-active? (= sub-focus j)
                            #(rf/dispatch [:menu/submenu-focus j])])))
        (map-indexed vector rows)))

(defn- dropdown-item [item i sub-open focus sub-focus access-active?]
  (cond
    (= item :sep)
    ^{:key i} [:div.vv-menu-sep]

    (:submenu item)
    ^{:key i}
    [:div
     (merge {:class          (menu-item-class "vv-menu-item vv-menu-item-submenu" (= focus i))
             :role           "menuitem"
             :aria-haspopup  "menu"
             :aria-expanded  (= sub-open (:submenu item))
             :on-mouse-enter #(do (rf/dispatch [:menu/focus i])
                                  (rf/dispatch [:menu/submenu (:submenu item)]))
             :on-mouse-over  #(do (rf/dispatch [:menu/focus i])
                                  (rf/dispatch [:menu/submenu (:submenu item)]))
             :on-click       (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (rf/dispatch [:menu/focus i])
                               (rf/dispatch [:menu/submenu (:submenu item)]))}
            (access/access-attrs (:access-key item)))
     [:span.vv-menu-item-label [access/label (:submenu item) (:access-key item) access-active?]]
     [:span.vv-menu-item-arrow (icons/icon :submenu)]
     (when (= sub-open (:submenu item))
       (submenu-dropdown (vec (remove nil? (radio-rows (:radio item)))) access-active? sub-focus))]

    :else
    ^{:key i}
    [:div
     (merge {:class          (menu-item-class "vv-menu-item" (= focus i))
             :role           "menuitem"
             :on-click       #(act! (:event item))
             :on-mouse-enter #(do (rf/dispatch [:menu/focus i])
                                  (rf/dispatch [:menu/submenu nil]))}
            (access/access-attrs (:access-key item)))
     [:span.vv-menu-item-label [access/label (:label item) (:access-key item) access-active?]]
     (when (:accel item) [:span.vv-menu-item-accel (:accel item)])]))

(defn- menu-dropdown [items sub-open focus sub-focus access-active?]
  (into [:div.vv-menu-dropdown {:role "menu"}]
        (map (fn [[i item]] (dropdown-item item i sub-open focus sub-focus access-active?)))
        (map-indexed vector items)))

(defn- top-menu [open sub-open focus sub-focus access-active? {:keys [label items access-key]}]
  ^{:key label}
  [:div.vv-menu
   [:div.vv-menu-label (merge {:class          (when (= open label) "vv-menu-label-open")
                               :role           "menuitem"
                               :aria-haspopup  "menu"
                               :aria-expanded  (= open label)
                               :on-click       #(rf/dispatch [:menu/toggle label])
                               :on-mouse-enter #(when open (rf/dispatch [:menu/open label]))}
                              (access/access-attrs access-key))
    [access/label label access-key access-active?]]
   (when (= open label)
     (menu-dropdown items sub-open focus sub-focus access-active?))])

(defn menubar []
  (let [open @(rf/subscribe [:ui/menu])
        sub-open @(rf/subscribe [:ui/menu-submenu])
        focus @(rf/subscribe [:ui/menu-focus])
        sub-focus @(rf/subscribe [:ui/menu-submenu-focus])
        access-active? @(rf/subscribe [:ui/access-keys-active?])
        pdf? @(rf/subscribe [:view/pdf-active?])
        root (cond-> [:div.vv-menubar {:role "menubar"}]
               open (conj [:div.vv-menu-overlay {:on-click #(rf/dispatch [:menu/close])}]))]
    (into root
          (map (fn [menu] (top-menu open sub-open focus sub-focus access-active? menu)))
          (effective-menus pdf?))))
