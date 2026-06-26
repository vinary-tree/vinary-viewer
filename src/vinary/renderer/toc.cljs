(ns vinary.renderer.toc
  "Table of contents. Rendered Markdown stores its outline as render metadata; the scroll-spy caches
   heading offsets after render/resize and uses those offsets during scroll instead of repeatedly
   querying the DOM and reading layout."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]))

(defonce ^:private spy-pending (atom false))
(defonce ^:private offset-cache (atom {:content nil :body nil :headings []}))

(defn- heading-nodes [^js body]
  (when body (.querySelectorAll body "h1,h2,h3,h4,h5,h6")))

(defn refresh!
  "Rebuild the scroll-spy offset cache for a content scroller. Call after Markdown body replacement and
   after layout-affecting resize/figure work."
  [^js content]
  (if-let [^js body (and content (.querySelector content ".markdown-body"))]
    (let [hs   (heading-nodes body)
          ctop (.. content getBoundingClientRect -top)
          stop (.-scrollTop content)]
      (reset! offset-cache
              {:content content
               :body body
               :headings (vec (for [i (range (.-length hs))
                                    :let [^js h (aget hs i)
                                          id (.-id h)]
                                    :when (not= "" id)]
                                {:id id
                                 :offset (+ stop (- (.. h getBoundingClientRect -top) ctop))}))}))
    (reset! offset-cache {:content content :body nil :headings []})))

(defn- cached-headings [^js content]
  (let [{cached-content :content headings :headings} @offset-cache]
    (if (identical? cached-content content)
      headings
      (do (refresh! content) (:headings @offset-cache)))))

(defn- active-for-scroll [headings scroll-top]
  (let [target (+ scroll-top 100)]
    (loop [lo 0 hi (count headings) active nil]
      (if (< lo hi)
        (let [mid (quot (+ lo hi) 2)
              h   (nth headings mid)]
          (if (<= (:offset h) target)
            (recur (inc mid) hi (:id h))
            (recur lo mid active)))
        active))))

(defn spy!
  "On content scroll (rAF-throttled), mark the heading currently at/above the viewport top."
  [^js content]
  (when-not @spy-pending
    (reset! spy-pending true)
    (js/requestAnimationFrame
     (fn []
       (reset! spy-pending false)
       (let [active (active-for-scroll (cached-headings content) (.-scrollTop content))]
         (when (not= active (get-in @rfdb/app-db [:ui :active-heading]))
           (rf/dispatch [:toc/active-heading active])))))))
