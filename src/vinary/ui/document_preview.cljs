(ns vinary.ui.document-preview
  "Passive, scaled document overview with confined click/drag navigation. The DOM mirror owns no pipeline."
  (:require [reagent.core :as r]
            ["./document_preview.js" :as mirror]))

(def scale-for mirror/scaleFor)
(def centered-top mirror/centeredTop)
(def reveal-top mirror/revealTop)

(defn preview [{:keys [scroller root]}]
  (let [pane (atom nil)
        stop (atom nil)]
    (r/create-class
     {:display-name "vv-document-preview"
      :component-did-mount
      (fn [_]
        (let [^js node @pane]
          (reset! stop (mirror/mount #js {:pane node
                                          :host (.querySelector node ".vv-document-preview-host")
                                          :track (.querySelector node ".vv-document-preview-track")
                                          :marker (.querySelector node ".vv-preview-viewport")
                                          :scroller scroller :root root}))))
      :component-will-unmount (fn [_] (when @stop (@stop)))
      :reagent-render
      (fn [_]
        [:div.vv-preview-minimap.vv-document-preview
         {:ref #(reset! pane %) :tab-index 0 :role "region" :aria-label "Preview Strip — document overview"}
         [:div.vv-document-preview-track
          [:div.vv-document-preview-host {:inert true :aria-hidden true}]
          [:div.vv-preview-viewport]]])})))
