(ns vinary.ui.menu-item
  "Shared menu-row content and checkbox semantics; menus retain their own activation and focus handling."
  (:require [vinary.ui.icons :as icons]))

(defn layout-class [items]
  (when (some #(and (map? %) (:check-toggle %)) items) "vv-menu-has-toggles"))

(defn attributes [item]
  (cond-> {:role (if (:check-toggle item) "menuitemcheckbox" "menuitem")}
    (:check-toggle item) (assoc :aria-checked (boolean (:checked? item)))))

(defn content [item label]
  [:<>
   [:span.vv-menu-item-label label]
   (when-let [accel (:accel item)] [:span.vv-menu-item-accel accel])
   (when (:check-toggle item) [:span.vv-menu-check {:aria-hidden true}])
   (when (:submenu item) [:span.vv-menu-item-arrow {:aria-hidden true} (icons/icon :submenu)])])
