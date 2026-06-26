(ns vinary.renderer.toc
  "Table of contents. Headings are parsed from the rendered HTML (rehype-slug put stable ids on them),
   and a scroll-spy marks the heading currently at the top of the viewport. Both are derived views over
   the same content — no separate source of truth."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rfdb]))

(defn extract
  "Parse rendered HTML → ordered [{:level :text :id}] for h1–h6 (ids from rehype-slug)."
  [html]
  (when (and html (exists? js/DOMParser))
    (let [doc (.parseFromString (js/DOMParser.) html "text/html")
          hs  (.querySelectorAll doc "h1,h2,h3,h4,h5,h6")]
      (vec (for [i (range (.-length hs))
                 :let [h (aget hs i)]
                 :when (not= "" (.-id h))]
             {:level (js/parseInt (subs (.-tagName h) 1))
              :text  (.-textContent h)
              :id    (.-id h)})))))

(defonce ^:private spy-pending (atom false))

(defn spy!
  "On content scroll (rAF-throttled), find the last heading at/above the viewport top and mark it active."
  [^js content]
  (when-not @spy-pending
    (reset! spy-pending true)
    (js/requestAnimationFrame
     (fn []
       (reset! spy-pending false)
       (when-let [body (.querySelector content ".markdown-body")]
         (let [hs   (.querySelectorAll body "h1,h2,h3,h4,h5,h6")
               ctop (.. content getBoundingClientRect -top)
               active (loop [i 0 last-id nil]
                        (if (< i (.-length hs))
                          (let [h (aget hs i)]
                            (if (<= (- (.. h getBoundingClientRect -top) ctop) 100)
                              (recur (inc i) (.-id h))
                              last-id))
                          last-id))]
           (when (not= active (get-in @rfdb/app-db [:ui :active-heading]))
             (rf/dispatch [:toc/active-heading active]))))))))
