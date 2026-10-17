(ns vinary.main.ext-config
  "Persisted extension + ad-block prefs (~/.config/vinary-viewer/extensions.edn): the ad-block toggle /
   list-set, the extensions master toggle, and disabled-ids. One watched EDN store (vinary.main.edn-store) on
   vv:ext-config (EDN as text over the seam, a watcher re-push that never returns a window's own write to it,
   a renderer→main save — ADR-0047) — PLUS a synchronous main-side `load-config` so adblock/extensions boot
   with the user's saved prefs at init (not after a renderer round-trip)."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [vinary.main.edn-store :as edn-store]
            [vinary.main.ext-util :as eu]))

(defonce ^:private store
  (edn-store/store {:file "extensions.edn" :channel "vv:ext-config"
                    :request "vv:ext-config-request" :save "vv:ext-config-save"}))

(defn load-config
  "Read + parse extensions.edn synchronously (main-side), normalized against defaults. Used by
   adblock/extensions init so they boot with the user's saved prefs rather than defaults."
  []
  (let [t (edn-store/text store)
        m (when (seq (str/trim t)) (try (reader/read-string t) (catch :default _ nil)))]
    (eu/merge-config m)))

(defn push! [^js wc] (edn-store/push! store wc))

(defn init! [^js wc] (edn-store/init! store wc))
