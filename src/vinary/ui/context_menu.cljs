(ns vinary.ui.context-menu
  "The themed right-click context menu. State [:ui :context-menu] = {:x :y :target {:kind :path :text}}.
   Items adapt to the target kind (a tree/link file, a directory, or an http link), arranged + separated
   for clarity. A click-away (or right-click) overlay closes it."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [vinary.ui.preview-navigation :as preview-nav]))

(defn- basename [p] (last (str/split (str p) #"/")))

(defn- source-label [source?]
  (if source? "View Preview" "View Source"))

(defn tab-close-side-label [orientation]
  (if (= orientation :vertical) "Close Below" "Close to the Right"))

(defn tab-items [{:keys [path id view-source? orientation]}]
  [{:label "Close"                :event [:tab/close id]}
   {:label "Duplicate tab"        :event [:tab/duplicate id]}
   {:label "Close Others"         :event [:tab/close-others id]}
   {:label (tab-close-side-label orientation) :event [:tab/close-right id]}
   :sep
   {:label (source-label view-source?) :event [:tab/toggle-source id]}
   (when path :sep)
   (when path {:label "Copy file path" :event [:clipboard/copy path]})
   (when path {:label "Copy file name" :event [:clipboard/copy (basename path)]})])

(defn- items-for [{:keys [kind path uri text source-location] :as target} vs?]
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
    :preview-link
    [{:label "Open"                 :event (preview-nav/open-event target false)}
     (when (preview-nav/new-tab? target)
       {:label "Open in new tab"    :event (preview-nav/open-event target true)})
     :sep
     {:label "Copy link location"   :event [:clipboard/copy uri]}
     {:label "Copy link text"       :event [:clipboard/copy (or text "")]}
     (when source-location
       {:label "Copy source location" :event [:clipboard/copy source-location]})]
    :preview-body
    [{:label "Copy"                 :event [:clipboard/copy (or text "")]}
     (when source-location
       {:label "Copy source location" :event [:clipboard/copy source-location]})]
    ;; a tab (right-clicked in either the horizontal strip or the vertical Tabs panel)
    :tab  (tab-items target)
    ;; the active markdown document (right-clicked in the content pane, not on a link)
    :doc  [{:label (source-label vs?) :event [:tab/toggle-source]}
           :sep
           {:label "Copy file path"       :event [:clipboard/copy path]}
           {:label "Copy file name"       :event [:clipboard/copy (basename path)]}]
    nil))

(defn- act! [event] (rf/dispatch [:context-menu/close]) (rf/dispatch event))

(defn context-menu []
  (let [{:keys [x y target] :as m} @(rf/subscribe [:ui/context-menu])
        vs? @(rf/subscribe [:ui/active-view-source?])]
    (when m
      [:div.vv-ctx-overlay
       {:on-click        #(rf/dispatch [:context-menu/close])
        :on-context-menu (fn [^js e] (.preventDefault e) (rf/dispatch [:context-menu/close]))}
       [:div.vv-ctx-menu {:style    {:left (str x "px") :top (str y "px")}
                          :on-click #(.stopPropagation %)}
        (for [[i item] (map-indexed vector (remove nil? (items-for target vs?)))]
          (if (= item :sep)
            ^{:key i} [:div.vv-menu-sep]
            ^{:key i} [:div.vv-menu-item {:on-click #(act! (:event item))}
                       [:span.vv-menu-item-label (:label item)]]))]])))
