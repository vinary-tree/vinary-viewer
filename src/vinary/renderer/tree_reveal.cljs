(ns vinary.renderer.tree-reveal
  "Post-render DOM action for the Files tree. Expansion is declarative app-db state; this namespace owns
   only the imperative edge needed after React commits: bring the active row into view. Multiple triggers
   in one render frame coalesce into one action."
  (:require [reagent.core :as r]))

(defonce ^:private scheduled? (atom false))

(defn reveal-active!
  "Scroll the active file into view after its controlled ancestor disclosures render. With no argument,
   resolve the currently mounted Files tree. Returns true when an active row was revealed."
  ([]
   (when (exists? js/document)
     (reveal-active! (.querySelector js/document ".vv-tree"))))
  ([^js root-el]
   (when root-el
     (when-let [^js a (.querySelector root-el ".vv-file-active")]
       (.scrollIntoView a #js {:block "nearest"})
       true))))

(defn schedule!
  "Schedule one reveal after Reagent's queued render commits. Repeated active/tree events in the same frame
   share the pending callback; this is event follow-up scheduling, not polling or request retry.

   The reveal re-asserts once web fonts settle: a late font swap can shift the accumulated row metrics
   above the target by a pixel or two AFTER the one-shot scroll, leaving the row clipped at the
   scrollport edge. `scrollIntoView` with `:block \"nearest\"` is a no-op for a fully-visible row, so the
   re-assert never fights manual scrolling — and `document.fonts.ready` resolves immediately once fonts
   are already loaded, so steady-state reveals pay nothing."
  []
  (when (compare-and-set! scheduled? false true)
    (r/after-render
     (fn []
       (reset! scheduled? false)
       (reveal-active!)
       (when-let [^js fonts (.-fonts js/document)]
         (.then (.-ready fonts) (fn [] (reveal-active!))))))))
