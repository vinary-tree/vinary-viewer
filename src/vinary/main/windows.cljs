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

(defn live
  "`wc` when it is a USABLE webContents, else nil — the one liveness predicate every service shares for the
   `webContents` it captured at init!. A captured wc outlives its window (the resident daemon survives
   window-all-closed, and the first window services bind to is often a hidden pool window that is later claimed
   and closed), and `WebContents.send` on a closed window throws \"Object has been destroyed\" — which, from a
   timer or session-event callback, is an uncaught main-process exception. Guard every captured wc through here."
  ^js [^js wc]
  (when (and wc (not (.isDestroyed wc))) wc))

(defn- focused
  "The focused BrowserWindow, or nil. Wrapped because `BrowserWindow` is undefined outside Electron (the
   :node-test build's `require(\"electron\")` resolves to the binary's path string), which keeps `active` and
   everything built on it unit-testable, and because it is null-safe before app `ready`."
  ^js []
  (try (.getFocusedWindow BrowserWindow) (catch :default _ nil)))

(defn active
  "The window a global/session action targets: the FOCUSED app window, else the most-recently-registered LIVE
   one. Destroyed windows are skipped — core removes a window from the registry on `closed`, so between `close`
   and `closed` the registry still holds a window whose webContents is already destroyed. Never a hidden pool
   window (those are never registered). nil when no live window is on screen (the daemon's steady state)."
  ^js []
  (let [live-win? (fn [^js win] (some-> win .-webContents live))
        ^js f     (focused)]
    (or (when (live-win? f) f)
        (last (filter live-win? @registry)))))

(defn active-wc
  "The active window's LIVE webContents (see `active`), or nil when no window is on screen."
  ^js []
  (live (some-> ^js (active) .-webContents)))

(defn send!
  "Send `channel` + `payload` to the ACTIVE window's renderer, or do nothing when no live window is on screen.
   Returns true when it was delivered. The targeted counterpart to `broadcast!`, for state that only means
   something to the window the user is looking at (extension/adblock/password status): a background service —
   the adblock refresh timer, a session `extension-loaded` event — can fire long after every window closed, and
   must never resolve its target to a stale captured wc (see `live`)."
  [^String channel payload]
  (if-let [^js wc (active-wc)]
    (do (.send wc channel payload) true)
    false))

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
