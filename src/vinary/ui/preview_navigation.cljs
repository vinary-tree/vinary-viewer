(ns vinary.ui.preview-navigation
  "Navigation events for links activated from preview surfaces.

   Preview navigation is browser-like: same-tab activation changes the current tab's history, while
   explicit new-tab activation opens a separate tab. This is intentionally different from file-tree or
   palette selection, where opening an already-open document may focus its existing tab."
  (:require [clojure.string :as str]))

(defn link-kind
  "Return the link target kind for either a raw vinary.app.link target or a preview context-menu target."
  [target]
  (or (:link-kind target) (:kind target)))

(defn new-tab? [target]
  (contains? #{:http :file} (link-kind target)))

(defn open-event
  "Return the re-frame event for opening a preview target."
  [target new-tab?]
  (let [kind (link-kind target)
        path (:path target)]
    (when-not (str/blank? path)
      (case kind
        :anchor [:toc/goto path]
        :dir    [:shell/open-path path]
        (:http :file) [(if new-tab? :tab/open :tab/navigate) path]
        nil))))
