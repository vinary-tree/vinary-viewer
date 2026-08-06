(ns vinary.renderer.hints
  "Vimium-style link hints for the content pane: pressing `f` overlays a short alphabetic label on every
   hintable element visible in the viewport; typing a label activates it. Targets are preview links
   (a[href]), file/dir rows ([data-path]), and — ADR-0037 — a diff's collapsible file banners
   (.vv-diff-file-head → a :toggle target whose activation clicks the banner, riding the same delegated
   toggle handler as a mouse click). This namespace holds the pure label generator + the pure target
   classifier (classify-target — DOM-free, unit-tested) + the DOM collection (find visible candidates,
   stamp viewport positions). The overlay + the typing sub-mode live in the views + a capture-phase key
   listener."
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

(defn classify-target
  "Pure hint classification over extracted element props — the DOM-free core (unit-tested; the
   generalize-shared-subsystems convention). props:
     :diff-head? — the element is a diff file banner (.vv-diff-file-head; ADR-0037)
     :id         — its DOM id (the vv-diff-file-N toggle handle)
     :data-path  — a directory-browser / git-tree row's path attr (nil when absent)
     :dir?       — that row's data-dir flag
     :href       — an anchor's classified target (link/target-for-anchor)
     :text       — the element's text content
   → {:kind … :path … :text …} | nil. The diff-head branch is FIRST and deliberately does NOT reuse
   :data-path (that kind dispatches [:doc/open] — it would reopen the file instead of toggling)."
  [{:keys [diff-head? id data-path dir? href text]}]
  (cond
    diff-head?        (when (seq (str id)) {:kind :toggle :path (str id) :text (str text)})
    (some? data-path) (when (seq data-path)
                        {:kind (if dir? :dir :file) :path data-path :text (str text)})
    :else             (link/classify href text)))

(defn- target-for-el
  "Extract one candidate element's props and classify it (classify-target) into a hint target map
   (no DOM element), or nil."
  [^js el]
  (classify-target
   (cond
     (.contains (.-classList el) "vv-diff-file-head")
     {:diff-head? true :id (.-id el) :text (.-textContent el)}

     (.hasAttribute el "data-path")
     {:data-path (.getAttribute el "data-path")
      :dir?      (= "true" (.getAttribute el "data-dir"))
      :text      (.-textContent el)}

     :else
     {:href (link/target-for-anchor el) :text (.-textContent el)})))

(defn collect
  "Hint candidates inside the given root elements that intersect the viewport: preview links (a[href]),
   file/dir rows ([data-path], from the in-pane directory browser + git tree), and diff file banners
   (.vv-diff-file-head — collapse toggles, ADR-0037). Each becomes {:kind :path :text :x :y} (no DOM
   element — these flow through app-db). `roots` is a collection of elements; nils are skipped (e.g. the
   tree is absent when the sidebar is hidden)."
  [roots]
  (let [vh (.-innerHeight js/window) vw (.-innerWidth js/window)]
    (->> roots
         (remove nil?)
         (mapcat (fn [^js root] (array-seq (.querySelectorAll root "a[href], [data-path], .vv-diff-file-head"))))
         (keep (fn [^js el]
                 ;; A navigable filename anchor now lives inside the diff banner. Keep BOTH affordances hintable
                 ;; without stacking their labels: the banner-toggle hint is pinned to the status chip/disclosure
                 ;; side, while the ordinary anchor hint uses the filename's own rectangle.
                 (let [diff-head? (.contains (.-classList el) "vv-diff-file-head")
                       ^js pos-el (if diff-head? (or (.querySelector el ".vv-diff-file-status") el) el)
                       r (.getBoundingClientRect pos-el)]
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
