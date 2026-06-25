(ns vinary.app.subs
  "re-frame subscriptions — the Observer graph. UI slices read app-db; document slices read the DataScript
   content cache (listing [:ds/rev] so they recompute per transaction). The browser-tab model lives in
   app-db ([:ui :tabs]/[:ui :active-tab]); reads go through vinary.app.nav."
  (:require [re-frame.core :as rf]
            [vinary.app.ds :as ds]
            [vinary.app.nav :as nav]
            [vinary.app.uri :as uri]
            [vinary.renderer.toc :as toc]))

(rf/reg-sub :ds/rev         (fn [db _] (:ds/rev db)))
(rf/reg-sub :ui/theme       (fn [db _] (get-in db [:ui :theme])))
(rf/reg-sub :ui/tree-filter (fn [db _] (get-in db [:ui :tree-filter])))
(rf/reg-sub :ui/find           (fn [db _] (get-in db [:ui :find])))
(rf/reg-sub :ui/active-heading (fn [db _] (get-in db [:ui :active-heading])))
(rf/reg-sub :ui/sidebar-visible? (fn [db _] (get-in db [:ui :sidebar-visible?])))
(rf/reg-sub :ui/sidebar-width  (fn [db _] (get-in db [:ui :sidebar-width])))
(rf/reg-sub :ui/sidebar-tab    (fn [db _] (get-in db [:ui :sidebar-tab])))
(rf/reg-sub :ui/projects       (fn [db _] (get-in db [:ui :projects])))
(rf/reg-sub :ui/tree-selected  (fn [db _] (get-in db [:ui :tree-selected])))
(rf/reg-sub :ui/menu           (fn [db _] (get-in db [:ui :menu])))
(rf/reg-sub :ui/settings       (fn [db _] (get-in db [:ui :settings])))
(rf/reg-sub :ui/settings-open? (fn [db _] (get-in db [:ui :settings-open?])))
(rf/reg-sub :ui/about-open?    (fn [db _] (get-in db [:ui :about-open?])))
(rf/reg-sub :ui/app-info       (fn [db _] (get-in db [:ui :app-info])))
(rf/reg-sub :ui/context-menu   (fn [db _] (get-in db [:ui :context-menu])))
(rf/reg-sub :input/mode        (fn [db _] (get-in db [:ui :input :mode])))
(rf/reg-sub :input/pending     (fn [db _] (get-in db [:ui :input :sequence])))
(rf/reg-sub :input/in-input?   (fn [db _] (get-in db [:ui :input :in-input?])))
(rf/reg-sub :palette/state     (fn [db _] (get-in db [:ui :palette])))

;; ---- the browser-tab model (app-db) ----
(rf/reg-sub :ui/tabs          (fn [db _] (nav/tabs db)))
(rf/reg-sub :ui/active-tab-id (fn [db _] (nav/active-id db)))
(rf/reg-sub :ui/active-tab    (fn [db _] (nav/active-tab db)))
(rf/reg-sub :ui/active-uri    (fn [db _] (nav/active-uri db)))
(rf/reg-sub :ui/active-path   (fn [db _] (nav/active-path db)))    ; the active uri iff it is a local file
(rf/reg-sub :history/can-back?    (fn [db _] (nav/can-back? db)))
(rf/reg-sub :history/can-forward? (fn [db _] (nav/can-forward? db)))

;; the tab strip reads the app-db tabs
(rf/reg-sub :tabs :<- [:ui/tabs] (fn [tabs _] tabs))

;; the active document: the cached DataScript doc for the active tab's local-file uri
(rf/reg-sub
 :doc/active
 :<- [:ds/rev] :<- [:ui/active-path]
 (fn [[_rev path] _] (when path (ds/active-doc (ds/snapshot) path))))

(rf/reg-sub :ui/web-toc (fn [db _] (get-in db [:ui :web-toc])))

;; the Contents/TOC outline of the active document: the web view's headings for an HTTP page, else the
;; Markdown headings parsed from the rendered HTML
(rf/reg-sub
 :doc/toc
 :<- [:ui/active-uri] :<- [:doc/active] :<- [:ui/web-toc]
 (fn [[active-uri doc web-toc] _]
   (if (uri/http? active-uri)
     (vec (or web-toc []))
     (toc/extract (:doc/html doc)))))
