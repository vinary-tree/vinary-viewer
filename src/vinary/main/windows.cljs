(ns vinary.main.windows
  "The live app-window registry and the shared \"which window does a global/session action target?\" resolver.
   `vinary.main.core` owns window lifecycle and registers each REAL (shown) window here; the process- and
   session-level services (web view, extension popups, password/extension/adblock status) resolve their target
   window through `active`/`active-wc` instead of capturing a single window at init!, so in a multi-window app
   they always serve the window the user is actually looking at.

   Hidden pool windows are deliberately NOT registered (core adds a window only when it is claimed/shown), so a
   global or session action can never be routed to a window that isn't on screen. This is a leaf namespace — it
   depends on nothing else in the app, so every service and core can require it without a cycle."
  (:require ["electron" :refer [BrowserWindow]]))

(defonce ^:private registry (atom []))   ; real, on-screen app windows, most-recently-registered LAST

(defn add!
  "Register `win` as a live app window (idempotent; moves it to most-recent)."
  [^js win]
  (swap! registry (fn [ws] (conj (vec (remove #(identical? % win) ws)) win))))

(defn remove!
  "Drop `win` from the registry (on close)."
  [^js win]
  (swap! registry (fn [ws] (vec (remove #(identical? % win) ws)))))

(defn all
  "All live app windows, most-recently-registered last."
  []
  @registry)

(defn active
  "The window a global/session action targets: the FOCUSED app window, else the most-recently-registered one.
   Never a hidden pool window (those are never registered). nil only before the first window is shown."
  ^js []
  (or (.getFocusedWindow BrowserWindow) (peek @registry)))

(defn active-wc
  "The active window's webContents (see `active`), or nil."
  ^js []
  (some-> ^js (active) .-webContents))

(defn broadcast!
  "Send `channel` + `payload` to EVERY live window's renderer, skipping any destroyed webContents. For GLOBAL
   state that a file-watcher re-pushes (recent files, settings, keymap, connections, extension config): the
   watcher outlives any single window — especially under the resident daemon, which survives window-all-closed —
   so it must target the live windows dynamically here, never a `webContents` captured when the watcher was
   created (that window may since have closed → `WebContents.send` throws \"Object has been destroyed\"). Pool /
   hidden windows are not registered, so they are never targeted; closed windows are already removed via core's
   `closed` handler → `remove!`."
  [^String channel payload]
  (doseq [^js win (all)]
    (let [^js wc (.-webContents win)]
      (when (and wc (not (.isDestroyed wc)))
        (.send wc channel payload)))))

(defn from-wc
  "The BrowserWindow that owns `wc` (e.g. `event.sender`), or nil — used to bind a shared native view/popup to
   the window whose renderer requested it."
  ^js [^js wc]
  (when wc (.fromWebContents BrowserWindow wc)))
