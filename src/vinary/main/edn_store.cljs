(ns vinary.main.edn-store
  "One persisted, watched EDN file under ~/.config/vinary-viewer — the shape keybindings.edn, settings.edn,
   recent.edn, connections.edn and extensions.edn share, implemented once (vinary.main.config, .settings,
   .recent, .connections and .ext-config each hold one store).

   The renderer owns the state. It asks for the file's text on the store's `:request` channel, writes the state
   back as EDN TEXT on its `:save` channel, and main re-broadcasts the file on its `:channel` whenever it changes,
   so a hand edit or another window's save reaches every window. EDN crosses the IPC seam as raw text, never
   clj->js, so keyword keys survive (the renderer parses it with cljs.reader).

   Two rules a plain watch → broadcast loop gets wrong (docs/scientific/10 §12, ADR-0047):

   • A window never receives its own write back (vinary.main.edn-echo). It already holds that state, or a
     newer one; merging the echo moved state backwards, and a Preferences font field lost characters.
   • The hidden warm-pool windows receive the broadcasts too (windows/add-standby!). They hold this state
     from their own boot, so a window claimed after a change would otherwise show the old theme, fonts,
     keymap, recent files or connections."
  (:require ["electron" :refer [ipcMain]]
            ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["chokidar" :refer [watch]]
            [vinary.main.edn-echo :as echo]
            [vinary.main.windows :as windows]))

(defn- conf-dir []
  (let [home (or (.. js/process -env -XDG_CONFIG_HOME) (path/join (os/homedir) ".config"))]
    (path/join home "vinary-viewer")))

(defn store
  "A store for `file` (its name under the config directory): main pushes and broadcasts its text on `:channel`
   (e.g. \"vv:settings\"), answers `:request` with a push, and writes what arrives on `:save`. The three names
   are spelled out at each store rather than derived, so every channel literal stays greppable (and
   docs/reference/ipc-channels.md's lint gate sees it). `:unlink-text`, when given, is broadcast when the file
   is deleted (the keymap falls back to its defaults on \"\"); without it a deletion is not broadcast. Nothing
   happens until `init!`."
  [{:keys [file channel request save unlink-text]}]
  {:file file :channel channel :request request :save save :unlink-text unlink-text
   :watcher (atom nil) :inited (atom false)
   ;; {:text :writers} — the text main itself last wrote, and for which windows (vinary.main.edn-echo)
   :last-write (atom nil)})

(defn file-path [{:keys [file]}] (path/join (conf-dir) file))

(defn text
  "The file's current text, or \"\" when it is absent or unreadable."
  [store]
  (let [p (file-path store)]
    (try (if (.existsSync fs p) (.readFileSync fs p "utf8") "")
         (catch :default _ ""))))

(defn push!
  "Send the file's current text to one window (its initial push, or the answer to its request)."
  [{:keys [channel] :as store} ^js wc]
  (when (and wc (not (.isDestroyed wc))) (.send wc channel (text store))))

(defn- save!
  "Write `text` for the window `sender`, and remember the write so its echo skips that window. A failed write
   records nothing — the file did not change, so there is no echo to suppress."
  [{:keys [last-write] :as store} ^js sender text]
  (try
    (when-not (.existsSync fs (conf-dir)) (.mkdirSync fs (conf-dir) (clj->js {:recursive true})))
    (.writeFileSync fs (file-path store) (str text))
    (swap! last-write echo/record-write (str text) (some-> sender .-id))
    (catch :default _ nil)))

(defn- broadcast!
  "Send `text` on the store's channel to every window holding its state — the live windows and the warm-pool
   windows (windows/broadcast!) — except the windows main last wrote exactly this text for (edn-echo/skipped)."
  [{:keys [channel last-write]} text]
  (windows/broadcast! channel text (echo/skipped @last-write text)))

(defn init!
  "Wire the store for the window `wc` just loaded: push it the file's text, and — once per process — start the
   watcher and register the request/save handlers. The watcher outlives any single window (the resident daemon
   survives window-all-closed), so it re-broadcasts to the windows live at that moment, never to `wc`."
  [{:keys [request save unlink-text watcher inited] :as store} ^js wc]
  (push! store wc)
  (when-not @watcher
    (let [w (watch (file-path store) (clj->js {:ignoreInitial true
                                               :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
      (.on w "change" (fn [_] (broadcast! store (text store))))
      (.on w "add"    (fn [_] (broadcast! store (text store))))
      (when (some? unlink-text)
        (.on w "unlink" (fn [_] (broadcast! store unlink-text))))
      (reset! watcher w)))
  (when-not @inited
    (reset! inited true)
    (.on ipcMain request (fn [^js e] (push! store (.-sender e))))
    (.on ipcMain save    (fn [^js e text] (save! store (.-sender e) text)))))
