(ns vinary.app.slides
  "Pure app-db helpers for the slide-deck mode (ADR-0045), shared by events, subscriptions and the menus: the
   :slides-open-as preference, the active document view's explicit layout, whether a slide show is presenting,
   and the EFFECTIVE layout. The effective layout is computed by the very rule the PDF engine applies
   (vinary.slides.deck/effective-layout), fed the document's :doc/deck summary — so a menu label, the toolbar and
   the engine can never disagree about which layout is showing."
  (:require [vinary.app.nav :as nav]
            [vinary.app.ds :as ds]
            [vinary.app.facet :as facet]
            [vinary.app.zoom :as zoom]
            [vinary.slides.deck :as deck]))

(defn open-as
  "The :slides-open-as preference — what a DETECTED deck opens as: :slides (the default) or :document."
  [db]
  (if (= :document (get-in db [:ui :settings :slides-open-as])) :document :slides))

(defn explicit-layout
  "The active document view's explicit PDF layout (:slides / :document), or nil (follow detection)."
  [db]
  (nav/pdf-layout db))

(defn presenting?
  "Is a fullscreen slide show running in this window?"
  [db]
  (boolean (get-in db [:ui :presentation :active?])))

(defn pdf-shown?
  "Is the active tab showing a PDF (directly, or as the PDF facet of a collocated group)?"
  [db]
  (= :pdf (zoom/context db)))

(defn shown-deck
  "The :doc/deck summary of the shown PDF, or nil."
  [db]
  (when-let [p (facet/active-content-path db)]
    (ds/doc-attr (ds/snapshot) p :doc/deck)))

(defn layout-request
  "What the pdf-view hands the engine besides zoom/fit/invert: {:explicit :open-as :presenting?}. The engine
   combines it with the document's own deck classification."
  [db]
  {:explicit    (explicit-layout db)
   :open-as     (open-as db)
   :presenting? (presenting? db)})

(defn effective-layout
  "The layout the shown PDF uses: :slides or :document (vinary.slides.deck/effective-layout)."
  ([db] (effective-layout db (shown-deck db)))
  ([db deck-summary]
   (deck/effective-layout (assoc (layout-request db) :deck? (boolean (:deck? deck-summary))))))

(defn slides-view?
  "Is a PDF showing in the slides layout?"
  [db]
  (and (pdf-shown? db) (= :slides (effective-layout db))))

;; ---- the [:ui :slides] mirror ------------------------------------------------------------------------------
(def ui-keys
  "Keys of [:ui :slides] that the UI owns, not the engine's position report: the typed slide number (:digits).
   They survive the reports of the same deck in the slides layout (merge-report)."
  [:digits])

(defn merge-report
  "The new [:ui :slides] for the engine's position report `m` over the current `cur`: the report, plus `cur`'s
   UI-owned keys when it is the same document still in the slides layout — a different document, or the document
   layout, starts without them."
  [cur m]
  (if (and (= (:path cur) (:path m)) (= :slides (:layout m)))
    (merge m (select-keys cur ui-keys))
    m))

(defn digits
  "The slide number being typed (a non-empty string of digits), or nil."
  [db]
  (not-empty (get-in db [:ui :slides :digits])))

(defn clear-digits
  "db without a typed slide number."
  [db]
  (if (contains? (get-in db [:ui :slides]) :digits)
    (update-in db [:ui :slides] dissoc :digits)
    db))
