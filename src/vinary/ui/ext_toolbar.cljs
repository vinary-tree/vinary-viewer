(ns vinary.ui.ext-toolbar
  "Browser-action toolbar: a small strip of extension icon buttons rendered in the sandboxed renderer
   from each extension's manifest action (the icon arrives as a data-URL from main, since Electron
   renders no native browser-action UI). Shown only for http(s) tabs while the extension runtime is
   enabled. Clicking an icon asks main to open that extension's popup anchored below the button."
  (:require [re-frame.core :as rf]
            [vinary.app.uri :as uri]
            [vinary.ui.icons :as icons]))

(defn ext-toolbar []
  (let [active-uri @(rf/subscribe [:ui/active-uri])
        exts       @(rf/subscribe [:ui/extensions])]
    (when (and (uri/http? active-uri) (:enabled? exts) (seq (:installed exts)))
      [:div.vv-ext-toolbar
       (doall
        (for [{:keys [id name action enabled?]} (:installed exts)
              :when enabled?]
          ^{:key id}
          [:button.vv-ext-action
           {:title    (str (or (not-empty (:title action)) name))
            :on-click (fn [^js e]
                        (let [^js r (.getBoundingClientRect (.-currentTarget e))]
                          (rf/dispatch [:extensions/action-clicked id (:popup action)
                                        {:x (.-left r) :y (.-top r) :width (.-width r) :height (.-height r)}])))}
           (if (:icon action)
             [:img.vv-ext-action-icon {:src (:icon action) :alt ""}]
             (icons/icon :globe))]))])))
