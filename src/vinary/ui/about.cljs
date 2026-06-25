(ns vinary.ui.about
  "The Help ▸ About dialog: the app name + version (pushed by main from package.json), the GitHub repo
   link (opened in the system browser), and a short summary."
  (:require [re-frame.core :as rf]))

(def ^:private repo-url "https://github.com/vinary-tree/vinary-viewer")

(defn dialog []
  (let [open? @(rf/subscribe [:ui/about-open?])
        {:keys [name version repo]} @(rf/subscribe [:ui/app-info])
        repo  (or repo repo-url)]
    (when open?
      [:div.vv-modal-overlay {:on-click #(rf/dispatch [:about/close])}
       [:div.vv-modal.vv-about {:on-click #(.stopPropagation %)}
        [:img.vv-about-logo {:src "assets/vinary-tree-logo.svg" :alt ""}]
        [:div.vv-modal-title (or name "vinary-viewer")]
        [:div.vv-about-version (str "Version " (or version "0.2.0"))]
        [:p.vv-about-summary
         "A reactive, browser-like previewer for repository documentation and source code — live-refreshing "
         "Markdown, diagrams, PDFs, images, and tree-sitter-highlighted source, with navigable tabs, a URI "
         "bar, HTTP browsing, and a multi-project sidebar. Inspired by vmd."]
        [:a.vv-about-link
         {:href "#" :on-click (fn [^js e] (.preventDefault e) (rf/dispatch [:shell/open-external repo]))}
         repo]
        [:div.vv-modal-actions
         [:button.vv-btn {:on-click #(rf/dispatch [:about/close])} "Close"]]]])))
