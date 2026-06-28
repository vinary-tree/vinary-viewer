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

(defn- target-for-el
  "Classify one candidate element into a hint target map (no DOM element), or nil. A [data-path] element
   (a directory-browser row or git-tree file) becomes a :file/:dir target; an a[href] is classified via
   vinary.app.link."
  [^js el]
  (if (.hasAttribute el "data-path")
    (let [path (.getAttribute el "data-path")]
      (when (seq path)
        {:kind (if (= "true" (.getAttribute el "data-dir")) :dir :file)
         :path path
         :text (str (.-textContent el))}))
    (link/classify (link/target-for-anchor el) (.-textContent el))))

(defn collect
  "Hint candidates inside the given root elements that intersect the viewport: preview links (a[href])
   and file/dir rows ([data-path], from the in-pane directory browser + git tree). Each becomes
   {:kind :path :text :x :y} (no DOM element — these flow through app-db). `roots` is a collection of
   elements; nils are skipped (e.g. the tree is absent when the sidebar is hidden)."
  [roots]
  (let [vh (.-innerHeight js/window) vw (.-innerWidth js/window)]
    (->> roots
         (remove nil?)
         (mapcat (fn [^js root] (array-seq (.querySelectorAll root "a[href], [data-path]"))))
         (keep (fn [^js el]
                 (let [r (.getBoundingClientRect el)]
                   (when (and (> (.-width r) 0) (> (.-height r) 0)
                              (< (.-top r) vh) (> (.-bottom r) 0)
                              (< (.-left r) vw) (> (.-right r) 0))
                     (when-let [t (target-for-el el)]
                       (assoc t :x (js/Math.round (.-left r)) :y (js/Math.round (.-top r))))))))
         (vec))))

(defn with-labels
  "Assign uniform-length labels to the collected targets."
  [targets]
  (mapv (fn [t l] (assoc t :label l)) targets (labels (count targets))))
