(ns vinary.app.preview-strip
  "Preview visibility: the saved default, with session-only opt-in for very large rendered documents."
  (:require [vinary.app.ds :as ds]
            [vinary.app.facet :as facet]))

(def large-document-bytes (* 5 1024 1024))

(defn large-document? [doc reflow?]
  (and (or (contains? #{"markdown" "org" "latex" "office" "diff" "diagram"} (:doc/kind doc))
           (and (= "pdf" (:doc/kind doc)) reflow?))
       (>= (max (or (get-in doc [:doc/meta :size]) 0)
                (count (:doc/text doc)) (count (:doc/html doc)) (count (:doc/reflow-html doc)))
           large-document-bytes)))

(defn visible-for? [ui doc]
  (if (large-document? doc (get-in ui [:pdf :reflow?]))
    (true? (get-in ui [:preview-strip-overrides (:doc/path doc)]))
    (not= false (:preview-strip-visible? ui))))

(defn- active-doc [db]
  (when-let [path (facet/active-content-path db)]
    (ds/active-doc (ds/snapshot) path)))

(defn visible? [db] (visible-for? (:ui db) (active-doc db)))

(defn toggle-result [db doc]
  (let [next? (not (visible-for? (:ui db) doc))]
    (if (large-document? doc (get-in db [:ui :pdf :reflow?]))
      {:db (assoc-in db [:ui :preview-strip-overrides (:doc/path doc)] next?)}
      (let [settings (assoc (get-in db [:ui :settings]) :preview-strip-visible? next?)]
        {:db (-> db (assoc-in [:ui :preview-strip-visible?] next?)
                 (assoc-in [:ui :settings] settings))
         :fx [[:vv/save-settings (pr-str settings)]]}))))

(defn toggle [db] (toggle-result db (active-doc db)))
