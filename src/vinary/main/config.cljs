(ns vinary.main.config
  "Main-process loader for the user keymap config ~/.config/vinary-viewer/keybindings.edn — one watched EDN
   store (vinary.main.edn-store) on vv:keymap, so editing the file live-rebinds the keymap, and a keymap the
   renderer saves (vv:keymap-save) reaches every other window but never comes back to the one that saved it
   (ADR-0047; the round trip used to re-run the keymap install, and its mode reset, in the saving window). The
   config crosses the IPC seam as raw EDN TEXT (not clj->js) — the renderer parses it with cljs.reader so the
   keyword command-ids survive (clj->js would flatten them to strings). Deleting the file broadcasts \"\", and
   the renderer falls back to the bundled keymaps."
  (:require [vinary.main.edn-store :as edn-store]))

(defonce ^:private store
  (edn-store/store {:file "keybindings.edn" :channel "vv:keymap"
                    :request "vv:keymap-request" :save "vv:keymap-save"
                    :unlink-text ""}))

(defn push! [^js wc] (edn-store/push! store wc))

(defn init! [^js wc] (edn-store/init! store wc))   ; initial push (the renderer also pulls via requestKeymap)
