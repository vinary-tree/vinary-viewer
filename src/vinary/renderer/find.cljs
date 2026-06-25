(ns vinary.renderer.find
  "In-page find. Matches are highlighted with the CSS Custom Highlight API (Highlight + CSS.highlights +
   ::highlight()) — it paints Ranges without mutating the document DOM, so it composes cleanly with the
   imperative innerHTML content body. A separate 'current' highlight marks the focused match."
  (:require [clojure.string :as str]))

(defonce ^:private state (atom {:ranges [] :idx 0}))

(defn- content-root [] (.querySelector js/document ".vv-content"))

(defn- collect-ranges
  "All case-insensitive matches of query under root, as DOM Ranges (within single text nodes)."
  [root query]
  (let [q (str/lower-case query)
        ql (count q)
        ranges (array)]
    (when (and root (pos? ql))
      (let [walker (.createTreeWalker js/document root js/NodeFilter.SHOW_TEXT nil)]
        (loop []
          (let [node (.nextNode walker)]
            (when node
              (let [text (str/lower-case (or (.-textContent node) ""))]
                (loop [from 0]
                  (let [i (.indexOf text q from)]
                    (when (>= i 0)
                      (let [r (.createRange js/document)]
                        (.setStart r node i)
                        (.setEnd r node (+ i ql))
                        (.push ranges r))
                      (recur (+ i ql))))))
              (recur))))))
    (vec ranges)))

(defn- supported? [] (and (exists? js/CSS) (.-highlights js/CSS) (exists? js/Highlight)))

(defn- paint! [ranges idx]
  (when (supported?)
    (let [all (js/Highlight.)
          cur (js/Highlight.)]
      (doseq [r ranges] (.add all r))
      (when (and (seq ranges) (< idx (count ranges))) (.add cur (nth ranges idx)))
      (.set (.-highlights js/CSS) "vv-find" all)
      (.set (.-highlights js/CSS) "vv-find-current" cur))))

(defn clear! []
  (when (supported?)
    (.delete (.-highlights js/CSS) "vv-find")
    (.delete (.-highlights js/CSS) "vv-find-current"))
  (reset! state {:ranges [] :idx 0}))

(defn- scroll-to! [ranges idx]
  (when (and (seq ranges) (< idx (count ranges)))
    (when-let [el (.. ^js (nth ranges idx) -startContainer -parentElement)]
      (.scrollIntoView el #js {:block "center" :behavior "smooth"}))))

(defn search!
  "Recompute matches for query, paint them, focus + scroll the first. Returns the match count."
  [query]
  (if (str/blank? query)
    (do (clear!) 0)
    (let [ranges (collect-ranges (content-root) query)]
      (reset! state {:ranges ranges :idx 0})
      (paint! ranges 0)
      (scroll-to! ranges 0)
      (count ranges))))

(defn cycle!
  "Move the focused match by dir (+1/-1), wrapping. Returns the new 1-based index (0 if none)."
  [dir]
  (let [{:keys [ranges idx]} @state
        n (count ranges)]
    (if (pos? n)
      (let [idx' (mod (+ idx dir) n)]
        (swap! state assoc :idx idx')
        (paint! ranges idx')
        (scroll-to! ranges idx')
        (inc idx'))
      0)))
