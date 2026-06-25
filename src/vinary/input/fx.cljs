(ns vinary.input.fx
  "Effects at the edge for keybinding-driven navigation: scrolling the content viewport and moving DOM
   focus between the sidebar filter and the content pane. Pure-ish (touch the DOM only here)."
  (:require [re-frame.core :as rf]))

;; sequence timeout (abandon a half-typed chord/leader after timeout-ms)
(rf/reg-fx
 :input/arm-timeout
 (fn [ms]
   (let [id (js/setTimeout #(rf/dispatch [:input/timeout]) ms)]
     (rf/dispatch [:input/set-timeout-id id]))))

(rf/reg-fx :input/cancel-timeout (fn [id] (when id (js/clearTimeout id))))

(defn- content-el [] (.querySelector js/document ".vv-content"))

(rf/reg-fx
 :dom/scroll
 (fn [{:keys [dy to]}]
   (when-let [^js el (content-el)]
     (cond
       (= to :top)    (.scrollTo el #js {:top 0 :behavior "smooth"})
       (= to :bottom) (.scrollTo el #js {:top (.-scrollHeight el) :behavior "smooth"})
       (= dy :page)   (.scrollBy el #js {:top (* 0.9 (.-clientHeight el)) :behavior "smooth"})
       (= dy :-page)  (.scrollBy el #js {:top (* -0.9 (.-clientHeight el)) :behavior "smooth"})
       (= dy :half)   (.scrollBy el #js {:top (* 0.5 (.-clientHeight el)) :behavior "smooth"})
       (= dy :-half)  (.scrollBy el #js {:top (* -0.5 (.-clientHeight el)) :behavior "smooth"})
       (number? dy)   (.scrollBy el #js {:top dy :behavior "smooth"})
       :else          nil))))

(rf/reg-fx
 :dom/focus
 (fn [target]
   (case target
     :tree    (some-> ^js (.querySelector js/document ".vv-tree-filter") .focus)
     :content (some-> ^js (content-el) .focus)
     :toggle  (let [active (.-activeElement js/document)
                    tree   (.querySelector js/document ".vv-tree-filter")]
                (if (and tree (= active tree))
                  (some-> ^js (content-el) .focus)
                  (some-> ^js tree .focus)))
     nil)))
