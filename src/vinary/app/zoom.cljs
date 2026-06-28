(ns vinary.app.zoom
  "Pure helpers for the context-aware zoom shared by events (write) and subs (read). The zoom bar and the
   View ▸ Zoom commands target whichever surface the active tab shows: the in-renderer PDF view, the native
   web view (http or local html), or the app-renderer DOM views (markdown/image/source/directory)."
  (:require [vinary.app.nav :as nav]
            [vinary.app.ds :as ds]
            [vinary.app.uri :as uri]))

(def presets
  "Preset zoom percentages offered in the zoom-bar dropdown."
  [50 75 100 125 150 200 300 400])

(defn context
  "The zoom target for the active tab: :pdf, :web (http or local .html), or :window (app-renderer DOM)."
  [db]
  (let [active-uri (nav/active-uri db)
        kind       (some-> (nav/active-path db) (#(ds/doc-attr (ds/snapshot) % :doc/kind)))]
    (cond
      (= "pdf" kind)                              :pdf
      (or (uri/http? active-uri) (= "html" kind)) :web
      :else                                       :window)))

(defn percent
  "Current zoom percentage (round of factor × 100) for the active context."
  [db]
  (js/Math.round
   (* 100 (case (context db)
            :pdf    (get-in db [:ui :pdf :scale] 1.0)
            :web    (get-in db [:ui :web-zoom] 1.0)
            :window (get-in db [:ui :window-zoom] 1.0)))))
