(ns vinary.main.core
  "Electron MAIN process entry. Creates the window (sandboxed renderer + contextBridge preload),
   wires the IO/watch service, and opens any files/URIs named on the command line
   (`vv a.md b.pdf https://…`), each in its own tab.
   Original code (Apache-2.0); a new application inspired by vmd (MIT)."
  (:require ["electron" :as electron]
            ["path" :as path]
            ["fs" :as fs]
            [vinary.main.service :as service]
            [vinary.main.startup :as startup]
            [vinary.main.daemon :as daemon]
            [vinary.main.profile :as profile]
            [vinary.main.config :as config]
            [vinary.main.settings :as settings]
            [vinary.main.recent :as recent]
            [vinary.main.window :as window]
            [vinary.main.windows :as windows]
            [vinary.main.paths :as paths]
            [vinary.main.shell :as shell]
            ;; [vinary.main.pdf :as pdf]  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
            [vinary.main.web :as web]
            [vinary.main.adblock :as adblock]
            [vinary.main.extensions :as extensions]
            [vinary.main.ext-config :as ext-config]
            [vinary.main.ext-popup :as ext-popup]
            [vinary.main.passwords :as passwords]
            [vinary.main.ssh :as ssh]
            [vinary.main.connections :as connections]
            [vinary.main.grammars :as grammars]))

(def ^js app (.-app electron))
(def ^js BrowserWindow (.-BrowserWindow electron))

;; Every live (shown) app window is tracked in `vinary.main.windows` — a leaf registry the session-level
;; services also read so they route to the ACTIVE window rather than one captured at init!. The app is
;; SINGLE-INSTANCE (see `main`): the first process owns the lock and stays resident; each subsequent `vv <file>`
;; hands its argv to it (via `second-instance` or the daemon socket), which opens the file in a warm window of
;; the already-running process — no second cold start.

;; Process/session-level services (shared-web-session extensions + ad-block, the web view, password autofill, the
;; shell IPC handlers) register ONCE for the whole app; re-running them for a second window throws (e.g. "Cannot
;; register preload script with existing ID"). They configure the SHARED web session, so all windows share them.
(defonce ^:private services-inited? (atom false))

;; `--daemon` = resident mode: boot the process but open NO initial window and stay alive when all windows close,
;; so a systemd user service can keep it warm at login and each `vv <file>` (second-instance) opens instantly.
(def ^:private daemon?
  (boolean (some #{"--daemon"} (js->clj js/process.argv))))

;; ── warm window pool ────────────────────────────────────────────────────────────────────────────────────────
;; Pre-booted, fully-wired HIDDEN windows. A real open CLAIMS one and shows it instantly — the per-window bundle
;; eval (~700 ms even in the warm process) was already paid at boot — then the pool refills in the background.
;; Pool windows are registered NOWHERE public (only in `pool`), so `windows/active` and global menu actions never
;; target a hidden one. `booting` counts windows loading toward the pool so a refill doesn't overshoot the target.
(defonce ^:private pool (atom []))
(defonce ^:private booting (atom 0))
(def ^:private pool-target
  ;; how many warm windows to keep ready. Default 1 (small memory footprint); VV_POOL=N tunes it, VV_POOL=0
  ;; disables the pool entirely (every open cold-creates — the pre-pool behaviour).
  (let [v (some-> js/process .-env .-VV_POOL js/parseInt)]
    (if (and (number? v) (not (js/isNaN v)) (>= v 0)) v 1)))

(defn renderer-index [] (path/join js/__dirname ".." ".." "resources" "public" "index.html"))
(defn preload-path  [] (path/join js/__dirname ".." ".." "resources" "preload.js"))

(defn initial-args
  "All non-flag document arguments passed on the command line (e.g. `vv a.md b.pdf https://…`),
   normalized to canonical tab uris in order — see `startup/doc-uris`. node's `path.resolve` is the
   injected cwd-relative resolver for local paths."
  []
  (startup/doc-uris (js->clj js/process.argv) #(.resolve path %)))

(defn- open-files!
  "Send `args` (canonical doc uris) to an already-loaded window's renderer, each in its own tab (first focused).
   Reuses the renderer's multi-file pipeline (vv:open-files → :files/opened → files-opened-fx). No-op if empty."
  [^js win args]
  (when-let [paths (seq args)]
    (profile/mark! "open-sent")
    (.send (.-webContents win) "vv:open-files"
           (clj->js {:paths (vec paths) :focus-first true}))))

(defn- wire-window!
  "Build a fully-wired app window (nav lockdown, per-window init!s, session services once). `show?` starts it
   visible (a normal open) or hidden (a pool window). `on-ready` runs with the window AFTER did-finish-load +
   the init!s — it opens the requested files (visible) or hands the window to the pool (hidden). Returns the win."
  [show? on-ready]
  (let [win (BrowserWindow.
              (clj->js (merge {:show show?
                               :backgroundColor "#292b2e"
                               :autoHideMenuBar true
                               :webPreferences {:contextIsolation true
                                                :nodeIntegration false
                                                ;; keep the compositor live so off-screen-rendered PDF
                                                ;; canvases don't hold a stale/blank GPU surface
                                                :backgroundThrottling false
                                                :preload (preload-path)}}
                              ;; restore last position/size (clamped on-screen); defaults to 1280×860
                              (window/options))))
        wc  (.-webContents win)]
    ;; profiling: propagate VV_PROFILE into the renderer via a ?profile=1 search param (it has no process.env),
    ;; and forward the renderer's `[vv-profile]` console marks to main's stdout (a renderer console.log does not
    ;; otherwise reach it) so scripts/profile-cold-start.mjs sees main + renderer marks on one stream
    (when profile/on?
      (.on wc "console-message"
           (fn [& args]
             (let [msg (first (filter string? args))]   ; signature varies by Electron version; find the message
               (when (and msg (re-find #"\[vv-profile\]" msg)) (js/console.log msg))))))
    (if profile/on?
      (.loadFile win (renderer-index) #js {:search "profile=1"})
      (.loadFile win (renderer-index)))
    ;; Lock the app frame to its bundled index.html. Now that markdown renders (sanitized) raw HTML, a stray
    ;; top-frame navigation must never hand the privileged window.vv bridge to another origin. Real links open
    ;; in the isolated web view or externally (never here), and new-window requests are denied outright.
    (.on wc "will-navigate"
         (fn [^js e ^js url] (when-not (= url (.getURL wc)) (.preventDefault e))))
    (.setWindowOpenHandler wc (fn [_] #js {:action "deny"}))
    (window/remember! win)                 ; reapply maximized state + persist bounds on resize/move/close
    (.once wc "did-finish-load"
           (fn []
             (profile/mark! "did-finish-load")
             (config/init! wc)
             (settings/init! wc)
             (recent/init! wc)
             (ext-config/init! wc)
             (grammars/init! wc)
             (connections/init! wc)   ; persisted (non-secret) SSH connection metadata
             (ssh/init! win)          ; SSH/SFTP transport prompts + vv:ssh-* channels
             (on-ready win)))
    ;; drop a closed window from BOTH the live registry and the pool (a pool window can be closed before claim)
    (.on win "closed" (fn []
                        (windows/remove! win)
                        (swap! pool (fn [ps] (vec (remove #(= % win) ps))))))
    ;; (pdf/init! win)  ; RETIRED — native PDF WebContentsView superseded by in-renderer pdf.js (ADR 0013)
    ;; process/session-level services register ONCE (see `services-inited?`) — extension runtime + ad-blocking on
    ;; the shared web session, the web view, password autofill, and the shell IPC handlers. They bind to the first
    ;; window (pool or visible); because the web session is shared, every window's web view inherits them.
    (when (compare-and-set! services-inited? false true)
      (let [prefs (ext-config/load-config)]
        (ext-popup/init! win)
        (extensions/init! win prefs)
        (adblock/init! win (:adblock prefs)))
      (web/init! win)
      (passwords/init! win)
      (shell/init! win))
    (profile/mark! "window")
    win))

(declare refill-pool!)

(defn create-window!
  "Cold-open a NEW visible window showing `args` (canonical doc uris; nil/empty → an empty window). This pays a
   full per-window boot; prefer `claim-window!`, which reuses a pre-warmed pool window when one is ready."
  ([] (create-window! nil))
  ([args]
   (let [win (wire-window! true (fn [win] (open-files! win args)))]
     (windows/add! win)
     win)))

(defn- boot-pool-window!
  "Pre-boot one hidden, fully-warmed window into the pool (no file). `booting` is held until it lands in `pool`."
  []
  (swap! booting inc)
  (wire-window! false (fn [win] (swap! booting dec) (swap! pool conj win))))

(defn- refill-pool!
  "Top the warm pool back up to `pool-target` (counting windows still booting), one hidden window per slot."
  []
  (dotimes [_ (max 0 (- pool-target (+ (count @pool) @booting)))]
    (boot-pool-window!)))

(defn- claim-window!
  "Open `args` in a pre-warmed pool window — instant, no bundle eval — else cold-create one. Refills the pool
   afterward so the next open is warm too."
  [args]
  (if-let [win (peek @pool)]
    (do (swap! pool (fn [ps] (vec (butlast ps))))   ; pop the claimed window out of the pool
        (windows/add! win)                          ; it is now a real, tracked, on-screen window
        (profile/mark! "claim")
        (open-files! win args)
        (.show win)
        (.focus win)
        (refill-pool!))
    (do (create-window! args)
        (refill-pool!))))

(declare app-version)

(defonce ^:private last-crash (atom {:sig nil :t 0}))   ; dedupe a crash STORM: one repeating fault → not N dialogs

(defn- write-crash-log!
  "Write the full trace to ~/.config/vinary-viewer/crash-<timestamp>.log — reliable and survives app exit, so the
   user can always recover the trace to report it even if the clipboard or dialog misbehave. Returns the path,
   or nil if it couldn't be written."
  [text]
  (try
    (let [dir   (paths/conf-dir)
          stamp (.replace (.toISOString (js/Date.)) (js/RegExp. ":" "g") "-")
          file  (path/join dir (str "crash-" stamp ".log"))
          hdr   (str "vinary-viewer crash — " (.toISOString (js/Date.)) "\n"
                     "version " (app-version) "  platform " js/process.platform "-" js/process.arch "\n\n")]
      (when-not (.existsSync fs dir) (.mkdirSync fs dir (clj->js {:recursive true})))
      (.writeFileSync fs file (str hdr text "\n"))
      file)
    (catch :default _ nil)))

(defn- report-crash!
  "Surface an uncaught main-process exception so the user can actually recover the trace to report it: ALWAYS
   write it to a crash-log file (reliable — survives app exit), pre-copy it to the clipboard, and show a dialog
   whose 'Open crash log' button opens that file in the user's editor (selectable + copyable — a native message
   box's own detail text is not selectable, which is why manual highlight never worked). A repeating identical
   fault is logged every time but shows the modal at most once per ~2 s, so a crash storm can't stack N dialogs."
  [^js err]
  (let [text (or (some-> err .-stack) (str err))
        now  (.now js/Date)]
    (js/console.error "[vinary] uncaught main-process exception:\n" text)
    (let [logfile (write-crash-log! text)]                        ; log EVERY occurrence, even a deduped repeat
      (when-not (and (= text (:sig @last-crash)) (< (- now (:t @last-crash)) 2000))
        (reset! last-crash {:sig text :t now})
        (let [^js clipboard (.-clipboard electron)
              ^js dialog    (.-dialog electron)
              ^js shell     (.-shell electron)]
          (try (.writeText clipboard text) (catch :default _ nil))   ; pre-copy (best-effort) before the dialog
          (try
            (let [detail (cond-> text logfile (str "\n\nFull trace saved to:\n  " logfile))
                  choice (.showMessageBoxSync dialog
                           (clj->js {:type      "error"
                                     :title     "vinary-viewer — unexpected error"
                                     :message   "An unexpected error occurred in the main process."
                                     :detail    detail
                                     :buttons   ["Copy details" "Open crash log" "Dismiss"]
                                     :defaultId 0
                                     :cancelId  2
                                     :noLink    true}))]
              (case choice
                0 (try (.writeText clipboard text) (catch :default _ nil))
                1 (when logfile (try (.openPath shell logfile) (catch :default _ nil)))
                nil))
            (catch :default _ nil)))))))   ; pre-ready / headless → the console + crash log still have the trace

(defn- install-crash-reporting! []
  (.on js/process "uncaughtException"  (fn [err]      (report-crash! err)))
  ;; benign promise rejections are common (extensions/network) — log them, but don't pop a modal for each
  (.on js/process "unhandledRejection"
       (fn [reason _] (js/console.error "[vinary] unhandled promise rejection:"
                                        (or (some-> ^js reason .-stack) reason)))))

(defn- app-version []
  (try (-> (.readFileSync fs (path/join js/__dirname ".." ".." "package.json") "utf8") (js/JSON.parse) (.-version))
       (catch :default _ "unknown")))

(defn ^:export main []
  (profile/mark! "entry")
  ;; `electron . --help`/`--version` (and `vv --gui --help`) print usage/version and exit BEFORE any window —
  ;; the primary `vv --help` path is the install.sh dispatcher; this covers direct/`--gui` invocation.
  (case (startup/help-request? (js->clj js/process.argv))
    :help    (do (js/console.log startup/usage-text) (.exit js/process 0))
    :version (do (js/console.log (startup/version-text (app-version))) (.exit js/process 0))
    nil)
  ;; surface main-process crashes in a copyable dialog (the Electron default isn't copyable) + log them
  (install-crash-reporting!)
  ;; SINGLE-INSTANCE: the first process becomes the resident app; a subsequent `vv <file>` fails the lock and
  ;; hands its argv to the primary via `second-instance` (below), which opens the file in a NEW window of the
  ;; already-warm process — no second cold start. A non-primary exits immediately without booting anything.
  (if-not (.requestSingleInstanceLock app)
    (.quit app)
    (do
  ;; register the privileged vv-remote:// scheme BEFORE app 'ready' — the web view loads it to live-render remote
  ;; HTML, and main serves each vv-remote:// URL's bytes over SFTP (vinary.main.web). standard+secure so the
  ;; page's relative assets resolve and it runs in a secure context, exactly like an http(s) page.
  (.registerSchemesAsPrivileged (.-protocol electron)
                                (clj->js [{:scheme "vv-remote"
                                           :privileges {:standard true :secure true :supportFetchAPI true
                                                        :stream true :corsEnabled true}}]))
  ;; Chromium switches the app always needs — see vinary.main.startup/chromium-switches: disable-gpu-sandbox
  ;; (Linux GPU-process DRI/GBM driver access; renderer stays sandboxed) + two defensive software-compositor
  ;; switches (disable-partial-raster, ui-disable-partial-swap). NOTE: those two do NOT fix the NVIDIA +
  ;; Wayland scroll-band; the actual band fix is disable-hardware-acceleration? below (remove the GPU process).
  (doseq [sw startup/chromium-switches] (.appendSwitch (.-commandLine app) sw))
  ;; VV_OZONE overrides the Ozone platform (e.g. VV_OZONE=x11) — an escape hatch for headless/xvfb runs (so the
  ;; window renders into an X server instead of the host Wayland compositor) and users who want a specific backend.
  (when-let [oz (.. js/process -env -VV_OZONE)]
    (.appendSwitch (.-commandLine app) "ozone-platform" oz))
  ;; NVIDIA + Wayland scroll-band fix that KEEPS the GPU process (best performance): GPU-rasterized tiles
  ;; go through the broken Vulkan-on-Wayland presentation that paints a duplicated scroll-band; disabling
  ;; GPU rasterization (already software-blocklisted here, so no loss) makes tiles software-rasterized and
  ;; presented via a path that avoids it, while keeping the GPU for compositing/presentation/WebGL/video.
  ;; Verified by screen-captured A/B (this flag alone flips banded<->clean). VV_GPU_RASTER=1 opts out.
  (when (startup/disable-gpu-rasterization?
          {:VV_GPU_RASTER    (.. js/process -env -VV_GPU_RASTER)
           :XDG_SESSION_TYPE (.. js/process -env -XDG_SESSION_TYPE)
           :WAYLAND_DISPLAY  (.. js/process -env -WAYLAND_DISPLAY)})
    (.appendSwitch (.-commandLine app) "disable-gpu-rasterization"))
  ;; VV_SOFTWARE_GL=1 forces FULL software rendering (no GPU process) anywhere — last-resort escape hatch.
  (when (startup/disable-hardware-acceleration? {:VV_SOFTWARE_GL (.. js/process -env -VV_SOFTWARE_GL)})
    (.disableHardwareAcceleration app))
  (service/init!)
  ;; remove Electron's default application menu — vinary-viewer draws its own themed menu bar, and its
  ;; keybindings own the accelerators (so the default menu's Ctrl+R/W/etc. don't double-fire)
  ;; a second `vv <file>` invocation: its argv/cwd arrive here (its own process then exits). Open the files in a
  ;; NEW window of this warm process. `wd` is the second instance's working directory — resolve its relative paths.
  (.on app "second-instance"
       (fn [_e ^js argv ^js wd]
         (let [args (startup/doc-uris (js->clj argv) #(.resolve path wd %))]
           (claim-window! (seq args)))))
  (-> (.whenReady app) (.then (fn [] (profile/mark! "ready") (.setApplicationMenu (.-Menu electron) nil)
                                ;; the resident-server socket (systemd-independent): a `vv <file>` client connects
                                ;; and sends paths; we open them in a warm pool window of this process. `args` are
                                ;; already cwd-resolved by the client — normalise via doc-uris (identity resolver).
                                (daemon/listen! (fn [args] (claim-window! (startup/doc-uris (into ["_" "_"] args) identity))))
                                ;; a daemon opens no window (it stays resident and pre-warms the pool); a normal
                                ;; launch claims a window for its command-line files (cold the very first time,
                                ;; then refills the pool so every subsequent open is instant)
                                (if daemon?
                                  (refill-pool!)
                                  (claim-window! (initial-args))))))
  (.on app "activate" (fn [] (when (and (empty? (windows/all)) (not daemon?)) (claim-window! nil))))
  (.on app "before-quit" (fn [] (ssh/shutdown!)))   ; tear down pooled SSH connections on quit
  ;; a daemon survives all its windows closing (so it stays warm for the next `vv <file>`); a normal launch quits
  (.on app "window-all-closed"
       (fn [] (when-not (or daemon? (= js/process.platform "darwin")) (.quit app)))))))
