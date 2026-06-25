(ns vinary.ui.context-menu
  "The themed right-click context menu. State [:ui :context-menu] = {:x :y :target {:kind :path :text}}.
   Items adapt to the target kind (a tree/link file, a directory, or an http link), arranged + separated
   for clarity. A click-away (or right-click) overlay closes it."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(defn- basename [p] (last (str/split (str p) #"/")))

(defn- items-for [{:keys [kind path text]}]
  (case kind
    :file [{:label "Open"                 :event [:doc/open path]}
           {:label "Open in new tab"      :event [:doc/open-new path]}
           :sep
           {:label "Copy file path"       :event [:clipboard/copy path]}
           {:label "Copy file name"       :event [:clipboard/copy (basename path)]}]
    :dir  [{:label "Open in file manager" :event [:shell/open-path path]}
           :sep
           {:label "Copy directory path"  :event [:clipboard/copy path]}
           {:label "Copy directory name"  :event [:clipboard/copy (basename path)]}]
    :http [{:label "Open"                 :event [:doc/open path]}
           {:label "Open in new tab"      :event [:doc/open-new path]}
           {:label "Open in system browser" :event [:shell/open-external path]}
           :sep
           {:label "Copy link URL"        :event [:clipboard/copy path]}
           (when (seq text) {:label "Copy link text" :event [:clipboard/copy text]})]
    nil))

(defn- act! [event] (rf/dispatch [:context-menu/close]) (rf/dispatch event))

(defn context-menu []
  (let [{:keys [x y target] :as m} @(rf/subscribe [:ui/context-menu])]
    (when m
      [:div.vv-ctx-overlay
       {:on-click        #(rf/dispatch [:context-menu/close])
        :on-context-menu (fn [^js e] (.preventDefault e) (rf/dispatch [:context-menu/close]))}
       [:div.vv-ctx-menu {:style    {:left (str x "px") :top (str y "px")}
                          :on-click #(.stopPropagation %)}
        (for [[i item] (map-indexed vector (remove nil? (items-for target)))]
          (if (= item :sep)
            ^{:key i} [:div.vv-menu-sep]
            ^{:key i} [:div.vv-menu-item {:on-click #(act! (:event item))}
                       [:span.vv-menu-item-label (:label item)]]))]])))
