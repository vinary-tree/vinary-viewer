(ns vinary.main.recent
  "Persisted recent-navigation state (~/.config/vinary-viewer/recent.edn): the directory→last-child
   `:trail` (so Alt+Up then Alt+Down returns to the most-recently-opened full path) and the
   `:recent-files` MRU (last opened files, surfaced in File ▸ Open Recent). One watched EDN store
   (vinary.main.edn-store) on vv:recent — the EDN crosses the IPC seam as raw text (the renderer owns it and
   parses/writes it, vv:recent-save), and the watcher re-pushes an external edit or another window's save to
   every window, never a window's own write back to it (ADR-0047)."
  (:require [vinary.main.edn-store :as edn-store]))

(defonce ^:private store
  (edn-store/store {:file "recent.edn" :channel "vv:recent"
                    :request "vv:recent-request" :save "vv:recent-save"}))

(defn push! [^js wc] (edn-store/push! store wc))

(defn init! [^js wc] (edn-store/init! store wc))   ; initial push (the renderer also pulls via requestRecent)
