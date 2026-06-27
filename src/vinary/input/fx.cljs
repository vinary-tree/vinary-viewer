(ns vinary.input.fx
  "Effects at the edge for keybinding-driven navigation: scrolling the content viewport and moving DOM
   focus between the sidebar filter and the content pane. Pure-ish (touch the DOM only here)."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [vinary.input.keymap :as keymap]
            [vinary.input.keymaps-registry :as registry]))

;; install the active keymap set (from the registry in app-db) into the live keymap atom + set the mode
(rf/reg-fx
 :keymap/install-active
 (fn [_]
   (registry/install-active! @rfdb/app-db)
   (rf/dispatch [:input/set-mode (keymap/initial-mode)])))

;; persist the keymap registry EDN to disk, debounced (editor edits stream fast; coalesce the writes)
(defonce ^:private save-timer (atom nil))
(rf/reg-fx
 :keymap/persist
 (fn [edn]
   (when @save-timer (js/clearTimeout @save-timer))
   (reset! save-timer
           (js/setTimeout (fn [] (when-let [^js v (.-vv js/window)]
                                   (when (.-saveKeymap v) (.saveKeymap v edn))))
                          400))))

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
 (fn [{:keys [dy dx to]}]
   (when-let [^js el (content-el)]
     (cond
       (= to :top)    (.scrollTo el #js {:top 0 :behavior "smooth"})
       (= to :bottom) (.scrollTo el #js {:top (.-scrollHeight el) :behavior "smooth"})
       (= dy :page)   (.scrollBy el #js {:top (* 0.9 (.-clientHeight el)) :behavior "smooth"})
       (= dy :-page)  (.scrollBy el #js {:top (* -0.9 (.-clientHeight el)) :behavior "smooth"})
       (= dy :half)   (.scrollBy el #js {:top (* 0.5 (.-clientHeight el)) :behavior "smooth"})
       (= dy :-half)  (.scrollBy el #js {:top (* -0.5 (.-clientHeight el)) :behavior "smooth"})
       (= dx :right)  (.scrollBy el #js {:left (* 0.18 (.-clientWidth el)) :behavior "smooth"})
       (= dx :left)   (.scrollBy el #js {:left (* -0.18 (.-clientWidth el)) :behavior "smooth"})
       (number? dy)   (.scrollBy el #js {:top dy :behavior "smooth"})
       (number? dx)   (.scrollBy el #js {:left dx :behavior "smooth"})
       :else          nil))))

(rf/reg-fx
 :dom/focus
 (fn [target]
   (case target
     :uri     (when-let [^js el (.querySelector js/document ".vv-uri-input")]
                (.focus el)
                (.select el))
     :tree    (some-> ^js (.querySelector js/document ".vv-tree-filter") .focus)
     :content (some-> ^js (content-el) .focus)
     :toggle  (let [active (.-activeElement js/document)
                    tree   (.querySelector js/document ".vv-tree-filter")]
                (if (and tree (= active tree))
                  (some-> ^js (content-el) .focus)
                  (some-> ^js tree .focus)))
     nil)))
