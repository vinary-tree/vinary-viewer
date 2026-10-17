(ns vinary.main.settings
  "Persisted user settings (~/.config/vinary-viewer/settings.edn): theme + fonts. One watched EDN store
   (vinary.main.edn-store) on vv:settings: the renderer pulls it (vv:settings-request), the Preferences dialog
   writes it back (vv:settings-save), and the watcher re-pushes a hand edit or another window's save to every
   window — never a window's own write back to it (ADR-0047). EDN crosses as raw text (the renderer parses it)
   so keyword keys survive."
  (:require [vinary.main.edn-store :as edn-store]))

(defonce ^:private store
  (edn-store/store {:file "settings.edn" :channel "vv:settings"
                    :request "vv:settings-request" :save "vv:settings-save"}))

(defn push! [^js wc] (edn-store/push! store wc))

(defn init! [^js wc] (edn-store/init! store wc))   ; initial push (the renderer also pulls via requestSettings)
