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

;; ---- smooth scrolling ----
;; A single requestAnimationFrame loop eases the focused pane toward an ACCUMULATING target. Each scroll
;; command advances the target; the loop chases it at a fixed fraction per frame. So a held arrow (OS key
;; auto-repeat) keeps advancing the target and produces continuous, smooth motion instead of the choppy
;; per-press `behavior:"smooth"` jumps (which interrupt each other on repeat). The target also accumulates
;; across the in-flight animation, so a single tap eases smoothly too.
(defonce ^:private scroll-anim (atom nil))   ; {:el :top :left :raf} | nil

(defn- scrollable? [^js el]
  (and el (instance? js/Element el)
       (> (.-scrollHeight el) (+ (.-clientHeight el) 2))
       (let [oy (.-overflowY (.getComputedStyle js/window el))]
         (or (= oy "auto") (= oy "scroll") (= oy "overlay")))))

(defn- focused-scroll-el
  "The scroll container to move: the focused element's nearest scrollable ancestor, else the content pane."
  []
  (or (loop [n (.-activeElement js/document)]
        (cond (nil? n) nil (scrollable? n) n :else (recur (.-parentElement n))))
      (content-el)))

(defn- anim-step! []
  (let [{:keys [^js el top left]} @scroll-anim]
    (if (or (nil? el) (not (.-isConnected el)))
      (reset! scroll-anim nil)
      (let [ct (.-scrollTop el) cl (.-scrollLeft el) dt (- top ct) dl (- left cl)]
        (if (and (< (js/Math.abs dt) 0.5) (< (js/Math.abs dl) 0.5))
          (do (set! (.-scrollTop el) top) (set! (.-scrollLeft el) left) (reset! scroll-anim nil))
          (do (set! (.-scrollTop el) (+ ct (* dt 0.25)))
              (set! (.-scrollLeft el) (+ cl (* dl 0.25)))
              (swap! scroll-anim assoc :raf (js/requestAnimationFrame anim-step!))))))))

(defn- ease-scroll!
  "Set the eased scroll target via `f-top`/`f-left` (each (fn [base-coord ^js el] → new-coord)), clamped
   to the element's scroll range. Reuses the in-flight target as the base so repeats accumulate."
  [^js el f-top f-left]
  (let [s         @scroll-anim
        same?     (and s (identical? (:el s) el))
        max-top   (max 0 (- (.-scrollHeight el) (.-clientHeight el)))
        max-left  (max 0 (- (.-scrollWidth el) (.-clientWidth el)))
        base-top  (if same? (:top s) (.-scrollTop el))
        base-left (if same? (:left s) (.-scrollLeft el))]
    (reset! scroll-anim {:el   el
                         :top  (-> (f-top base-top el)   (max 0) (min max-top))
                         :left (-> (f-left base-left el) (max 0) (min max-left))
                         :raf  (when same? (:raf s))})
    (when-not (and same? (:raf s))
      (swap! scroll-anim assoc :raf (js/requestAnimationFrame anim-step!)))))

(rf/reg-fx
 :dom/scroll
 (fn [{:keys [dy dx to]}]
   (when-let [^js el (focused-scroll-el)]
     (let [keep-top  (fn [t _] t)
           keep-left (fn [l _] l)]
       (cond
         (= to :top)    (ease-scroll! el (fn [_ _]     0)                                 keep-left)
         (= to :bottom) (ease-scroll! el (fn [_ ^js e] (.-scrollHeight e))                keep-left)
         (= dy :page)   (ease-scroll! el (fn [t ^js e] (+ t (* 0.9 (.-clientHeight e))))  keep-left)
         (= dy :-page)  (ease-scroll! el (fn [t ^js e] (- t (* 0.9 (.-clientHeight e))))  keep-left)
         (= dy :half)   (ease-scroll! el (fn [t ^js e] (+ t (* 0.5 (.-clientHeight e))))  keep-left)
         (= dy :-half)  (ease-scroll! el (fn [t ^js e] (- t (* 0.5 (.-clientHeight e))))  keep-left)
         (= dx :right)  (ease-scroll! el keep-top (fn [l ^js e] (+ l (* 0.18 (.-clientWidth e)))))
         (= dx :left)   (ease-scroll! el keep-top (fn [l ^js e] (- l (* 0.18 (.-clientWidth e)))))
         (number? dy)   (ease-scroll! el (fn [t _] (+ t dy)) keep-left)
         (number? dx)   (ease-scroll! el keep-top (fn [l _] (+ l dx)))
         :else          nil)))))

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
