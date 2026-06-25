(ns vinary.renderer.hints
  "Vimium-style link hints for the content pane: pressing `f` overlays a short alphabetic label on every
   link visible in the viewport; typing a label activates that link. This namespace holds the pure label
   generator + the DOM collection (find visible a[href], classify each via vinary.app.link, stamp its
   viewport position). The overlay + the typing sub-mode live in the views + a capture-phase key listener."
  (:require [clojure.string :as str]
            [vinary.app.link :as link]))

;; home-row-biased alphabet (easy to type), like Vimium's default
(def ^:private alphabet "SADFJKLEWCMPGH")

(defn labels
  "n labels of UNIFORM length (1 char while they fit in the alphabet, else 2) — uniform length keeps the
   typing sub-mode unambiguous."
  [n]
  (let [a (vec alphabet) base (count a)]
    (cond
      (zero? n)     []
      (<= n base)   (mapv str (take n a))
      :else         (vec (take n (for [x a y a] (str x y)))))))

(defn collect
  "All a[href] inside `content-el` that intersect the viewport, classified (vinary.app.link/classify) and
   stamped with their top-left viewport position. Returns a vector of {:kind :path :text :x :y} (no DOM
   element — these flow through app-db)."
  [^js content-el]
  (if-not content-el
    []
    (let [vh (.-innerHeight js/window) vw (.-innerWidth js/window)]
      (->> (array-seq (.querySelectorAll content-el "a[href]"))
           (keep (fn [^js a]
                   (let [r (.getBoundingClientRect a)
                         t (link/classify (link/target-for-anchor a) (.-textContent a))]
                     (when (and t (> (.-width r) 0) (> (.-height r) 0)
                                (< (.-top r) vh) (> (.-bottom r) 0)
                                (< (.-left r) vw) (> (.-right r) 0))
                       (assoc t :x (js/Math.round (.-left r)) :y (js/Math.round (.-top r)))))))
           (vec)))))

(defn with-labels
  "Assign uniform-length labels to the collected targets."
  [targets]
  (mapv (fn [t l] (assoc t :label l)) targets (labels (count targets))))
