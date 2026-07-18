(ns vinary.main.paths
  "Canonical per-user data paths for the main process, computed in ONE place. The config-mirror services
   (config/settings/recent/connections/ext-config) each still carry their own file path, but new code (the
   crash reporter) resolves the config dir here rather than adding yet another `conf-dir` copy."
  (:require ["path" :as path]
            ["os" :as os]))

(defn conf-dir
  "The app's per-user config directory: `$XDG_CONFIG_HOME/vinary-viewer`, else `~/.config/vinary-viewer`."
  []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))
