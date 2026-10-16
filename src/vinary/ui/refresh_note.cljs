(ns vinary.ui.refresh-note
  "The non-fatal live-refresh banner (ADR-0045 / ADR-0046). When a refresh of a document that already rendered
   cannot be shown — pdf.js could not open the new bytes, main could not re-read the file, or the PDF on disk is
   still incomplete — the LAST GOOD version stays on screen and this banner says why, with Retry (re-read the
   file through the ordinary open flow) and dismiss. A successful refresh removes it. It floats over the top of
   the content area (inside .vv-content-wrap, below the find bar), so it never reflows or scrolls the document."
  (:require [re-frame.core :as rf]
            [vinary.ui.icons :as icons]))

(defn- headline [{:keys [kind]}]
  (case kind
    "render-failed"  "The updated file couldn't be displayed — showing the last good version."
    "incomplete"     "The file on disk is incomplete (still being written?) — showing the last complete version."
    "refresh-failed" "Couldn't reload the file — showing the last loaded version."
    "The document couldn't be refreshed — showing the last good version."))

(defn refresh-note
  "The banner for the shown document's :doc/refresh-note, or nothing."
  []
  (let [note @(rf/subscribe [:doc/refresh-note])
        path @(rf/subscribe [:ui/active-content-path])]
    (when (and note path)
      [:div.vv-refresh-note {:role "status" :aria-live "polite"
                             :data-kind (:kind note)
                             :title (str (:message note))}
       [icons/icon :warning]
       [:span.vv-refresh-note-text (headline note)]
       [:button.vv-refresh-note-retry {:type "button"
                                       :on-click #(rf/dispatch [:doc/refresh-retry path])}
        "Retry"]
       [:button.vv-refresh-note-dismiss {:type "button" :aria-label "Dismiss"
                                         :on-click #(rf/dispatch [:doc/refresh-note-dismiss path])}
        "×"]])))
