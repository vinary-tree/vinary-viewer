(ns vinary.ui.about
  "The Help ▸ About dialog: the app name + version (pushed by main from package.json), the GitHub repo
   link (opened in the system browser), and a short summary."
  (:require [re-frame.core :as rf]
            [vinary.ui.access-keys :as access]))

(def ^:private repo-url "https://github.com/vinary-tree/vinary-viewer")

(defn- on-key-down [repo ^js e]
  (when-let [k (and (.-altKey e) (access/event-letter e))]
    (when (case k
            "r" (do (rf/dispatch [:shell/open-external repo]) true)
            "c" (do (rf/dispatch [:about/close]) true)
            false)
      (access/consume! e))))

(defn dialog []
  (let [open? @(rf/subscribe [:ui/about-open?])
        {:keys [name version repo]} @(rf/subscribe [:ui/app-info])
        repo  (or repo repo-url)
        access-active? @(rf/subscribe [:ui/access-keys-active?])]
    (when open?
      [:div.vv-modal-overlay {:on-click #(rf/dispatch [:about/close])}
       [:div.vv-modal.vv-about {:on-click #(.stopPropagation %)
                                :on-key-down #(on-key-down repo %)}
        [:img.vv-about-logo {:src "assets/vinary-tree-logo.svg" :alt ""}]
        [:div.vv-modal-title (or name "vinary-viewer")]
        [:div.vv-about-version (str "Version " (or version "0.2.0"))]
        [:p.vv-about-summary
         "A reactive, browser-like previewer for repository documentation and source code — live-refreshing "
         "Markdown, diagrams, PDFs, images, and tree-sitter-highlighted source, with navigable tabs, a URI "
         "bar, HTTP browsing, and a multi-project sidebar. Inspired by vmd."]
        [:a.vv-about-link
         (merge {:href "#" :on-click (fn [^js e] (.preventDefault e) (rf/dispatch [:shell/open-external repo]))}
                (access/access-attrs "r"))
         [access/label repo "r" access-active?]]
        [:div.vv-modal-actions
         [:button.vv-btn (merge {:on-click #(rf/dispatch [:about/close])}
                                (access/access-attrs "c"))
          [access/label "Close" "c" access-active?]]]]])))
