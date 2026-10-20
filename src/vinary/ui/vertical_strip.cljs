(ns vinary.ui.vertical-strip
  "A bounded vertical strip of selectable, ordered previews. The geometry is independent of slide decks."
  (:require [reagent.core :as r]))

(def ^:private gap 8)
(def ^:private peek-size 20)

(defn window
  "Visible full items and clipped neighbours for `count` ordered items. The selection travels down from the top,
   stays in the middle while the window can move, then travels to the end. `ratio` is preview height / width."
  [count selected width height ratio]
  (when (and (pos? count) (pos? width) (pos? height))
    (let [selected (-> selected (max 0) (min (dec count)))
          tile-h   (min height (+ 20 (* width (if (and (number? ratio) (pos? ratio)) ratio 0.5625))))
          reserve  (+ peek-size gap)
          capacity (max 1 (min count
                               (int (js/Math.floor (/ (+ (- height reserve peek-size) gap)
                                                      (+ tile-h gap))))))
          start    (-> (- selected (quot capacity 2)) (max 0) (min (- count capacity)))
          above?   (pos? start)
          below?   (< (+ start capacity) count)
          top      (if above? reserve 0)
          items    (mapv (fn [i]
                           {:index i :top (+ top (* (- i start) (+ tile-h gap)))
                            :height tile-h :current? (= i selected) :peek? false})
                         (range start (+ start capacity)))
          before   (when above? {:index (dec start) :top (- top gap (- tile-h 20))
                                 :height tile-h :current? false :peek? true})
          after    (when below? {:index (+ start capacity)
                                 :top (max (+ top (* capacity (+ tile-h gap)))
                                           (- height peek-size))
                                 :height tile-h :current? false :peek? true})]
      {:start start :capacity capacity :tile-height tile-h
       :items (cond-> (if before (into [before] items) items)
                after (conj after))})))

(defn strip
  "Generic preview viewport. `items` carry stable :id values; render-item receives [item visual-height].
   `item-attrs` may add domain-specific data attributes to each button."
  [{:keys [items current render-item on-select ratio aria-label item-attrs item-class class]}]
  (let [node (atom nil)
        observer (atom nil)
        size (r/atom {:width 0 :height 0})
        measure! (fn [] (when-let [^js el @node]
                          (let [next {:width (.-clientWidth el) :height (.-clientHeight el)}]
                            (when (not= next @size) (reset! size next)))))
        set-node! (fn [el]
                    (when-not (identical? el @node)
                      (when-let [^js o @observer] (.disconnect o))
                      (reset! observer nil)
                      (reset! node el)
                      (when el
                        (measure!)
                        (when (exists? js/ResizeObserver)
                          (let [o (js/ResizeObserver. (fn [_] (measure!)))]
                            (.observe o el)
                            (reset! observer o))))))]
    (r/create-class
     {:display-name "vv-vertical-strip"
      :component-will-unmount (fn [_] (set-node! nil))
      :reagent-render
      (fn [{:keys [items current render-item on-select ratio aria-label item-attrs item-class class]}]
        (let [all-items (vec items)
              idx (or (first (keep-indexed (fn [i item] (when (= current (:id item)) i)) all-items)) 0)
              {visible-items :items tile-height :tile-height}
              (window (count all-items) idx (:width @size) (:height @size) ratio)]
          [:section.vv-vertical-strip {:ref set-node! :class class :aria-label (or aria-label "Previews")}
           (for [{:keys [index top height current? peek?]} visible-items
                 :let [item (nth all-items index)]]
             ^{:key (:id item)}
             [:button.vv-vertical-strip-item
              (merge {:type "button" :class (str item-class " "
                                              (when current? (str "vv-vertical-strip-current "
                                                                  (when item-class (str item-class "-current "))))
                                              (when peek? (str "vv-vertical-strip-peek "
                                                               (when item-class (str item-class "-peek")))))
               :style {:top (str top "px") :height (str height "px")}
               :aria-label (:label item)
               :aria-current (when current? "step")
               :on-mouse-down #(.preventDefault %)
               :on-key-down (fn [e]
                              (when (contains? #{"Enter" " "} (.-key e))
                                (.preventDefault e) (.stopPropagation e)
                                (on-select item)))
               :on-click #(on-select item)}
                     (when item-attrs (item-attrs item)))
              (render-item item (max 1 (- tile-height 20)))
              [:span.vv-vertical-strip-label (:label item)]])]))})))
