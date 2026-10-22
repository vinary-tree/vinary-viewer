(ns vinary.ui.vertical-strip
  "A scrollable, virtualized vertical strip of selectable previews. The geometry is independent of slide decks."
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
  "Generic, independently scrollable preview viewport. Only cards near the viewport are mounted. A selection
   change reveals the current card; manually scrolling previews never changes the selection. `items` have stable
   :id values, and render-item receives [item visual-height]."
  [{:keys [items current render-item on-select ratio aria-label item-attrs item-class class]}]
  (let [node (atom nil)
        observer (atom nil)
        size (r/atom {:width 0 :height 0})
        scroll-top (r/atom 0)
        last-current (atom ::unset)
        last-size (atom nil)
        stride (fn [width ratio] (+ gap 20 (* width (if (and (number? ratio) (pos? ratio)) ratio 0.5625))))
        reveal! (fn [{:keys [items current ratio]}]
                  (when-let [^js el @node]
                    (let [n (count items)
                          idx (or (first (keep-indexed (fn [i item] (when (= current (:id item)) i)) items)) 0)
                          step (stride (:width @size) ratio)
                          capacity (max 1 (int (js/Math.floor (/ (+ (:height @size) gap) step))))
                          start (-> (- idx (quot capacity 2)) (max 0) (min (max 0 (- n capacity))))
                          ;; Include a sliver of the previous image, beyond its caption and the inter-card gap.
                          peek-offset (if (pos? start) (+ gap 20 peek-size) 0)
                          total (max 0 (- (* n step) gap))
                          target (-> (- (* start step) peek-offset) (max 0)
                                     (min (max 0 (- total (:height @size)))))]
                      (set! (.-scrollTop el) target)
                      (reset! scroll-top target))))
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
      :component-did-mount (fn [this] (reveal! (r/props this))
                             (reset! last-current (:current (r/props this)))
                             (reset! last-size @size))
      :component-did-update (fn [this _]
                              (let [props (r/props this)]
                                (when (or (not= (:current props) @last-current)
                                          (not= @size @last-size))
                                  (reset! last-current (:current props))
                                  (reset! last-size @size)
                                  (reveal! props))))
      :component-will-unmount (fn [_] (set-node! nil))
      :reagent-render
      (fn [{:keys [items current render-item on-select ratio aria-label item-attrs item-class class]}]
        (let [all-items (vec items)
              tile-height (- (stride (:width @size) ratio) gap)
              step (+ tile-height gap)
              n (count all-items)
              first-index (-> (js/Math.floor (/ @scroll-top step)) int (- 2) (max 0))
              last-index (min n (+ 3 (int (js/Math.ceil (/ (+ @scroll-top (:height @size)) step)))))
              total (max 0 (- (* n step) gap))]
          [:section.vv-vertical-strip {:ref set-node! :class class :aria-label (or aria-label "Preview Strip")
                                       :on-scroll #(reset! scroll-top (.. % -currentTarget -scrollTop))}
           [:div.vv-vertical-strip-track {:style {:height (str total "px")}}
            (for [index (range first-index last-index)
                 :let [item (nth all-items index)
                       current? (= current (:id item))]]
             ^{:key (:id item)}
             [:button.vv-vertical-strip-item
              (merge {:type "button" :class (str item-class " "
                                              (when current? (str "vv-vertical-strip-current "
                                                                  (when item-class (str item-class "-current")))))
               :style {:top (str (* index step) "px") :height (str tile-height "px")}
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
              [:span.vv-vertical-strip-label (:label item)]])]]))})))
