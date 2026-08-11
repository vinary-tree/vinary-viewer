(ns vinary.ui.error-boundary
  "A small reusable React error boundary. WHY IT EXISTS: an uncaught exception in ANY component's
   render unmounts the React root — the whole window blanks to the background color, which is
   exactly how the Commits-panel shadowed-`empty?` bug presented. A boundary converts that blast
   radius into a visible, labeled error strip while the rest of the chrome keeps working. Never
   silent: the error logs to the console AND paints in place of the children."
  (:require [reagent.core :as r]))

(defn boundary
  "Render `children`; on a descendant render throw, log the error and show a `.vv-error-strip`
   naming `label` instead of letting the React root unmount. Give the boundary a React key that
   changes when its content meaningfully changes (e.g. the active sidebar tab) — remounting a
   fresh boundary is the recovery path."
  [_label & _children]
  (let [err (r/atom nil)]
    (r/create-class
     {:display-name "vv-error-boundary"
      :get-derived-state-from-error (fn [e] (reset! err e) #js {})
      :component-did-catch (fn [_this e info] (js/console.error "vv error boundary:" e info))
      :reagent-render
      (fn [label & children]
        (if-let [e @err]
          [:div.vv-error-strip
           (str label " crashed: " (or (some-> e .-message) (str e)))]
          (into [:<>] children)))})))
