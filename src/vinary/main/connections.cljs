(ns vinary.main.connections
  "Persisted SSH connection metadata (~/.config/vinary-viewer/connections.edn): non-secret host entries
   (alias, hostname, user, port, last-used, last-path, per-host prefs) and a recent-remote MRU surfaced in
   File ▸ Open Recent. One watched EDN store (vinary.main.edn-store) on vv:connections — the EDN crosses the IPC
   seam as raw text (the renderer owns it and parses/writes it, vv:connections-save), and the watcher re-pushes
   an external edit or another window's save to every window, never a window's own write back to it (ADR-0047).

   SECRETS ARE NEVER STORED HERE. Passwords, passphrases, private-key contents, and host-key material live
   only in the transport's main-process memory (for the connection lifetime) and in the user's existing
   ~/.ssh (keys, known_hosts). This file holds addresses and preferences only."
  (:require [vinary.main.edn-store :as edn-store]))

(defonce ^:private store
  (edn-store/store {:file "connections.edn" :channel "vv:connections"
                    :request "vv:connections-request" :save "vv:connections-save"}))

(defn push! [^js wc] (edn-store/push! store wc))

(defn init! [^js wc] (edn-store/init! store wc))
