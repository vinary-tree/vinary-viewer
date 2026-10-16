(ns vinary.main.fullscreen
  "Window-level fullscreen for slide shows (ADR-0045 §Presenting).

   A slide show asks MAIN to make its own window fullscreen (`BrowserWindow.setFullScreen`) rather than using the
   HTML Fullscreen API: the API's top layer would hide the app's own overlays (the SSH prompt, the context menu,
   the palette), Chromium would own Escape, and it needs a user gesture. The renderer hides the app chrome with
   CSS instead.

   Three rules, kept in the pure `decide`:
     1. a request only ever affects the window that SENT it (`BrowserWindow.fromWebContents(event.sender)`);
     2. main remembers whether IT made a window fullscreen (`owned`) and only ever LEAVES a fullscreen it entered
        — a window the user had already made fullscreen stays fullscreen when the show ends;
     3. nothing goes fullscreen on its own — a document's /PageMode /FullScreen is evidence for deck detection,
        never a request (ADR-0045 D13).

   Channels: `vv:set-fullscreen` {on} and `vv:fullscreen-request` (renderer → main; the boot pull), and
   `vv:fullscreen-changed` {fullscreen owned} (main → renderer, on every enter/leave and on request)."
  (:require ["electron" :refer [ipcMain BrowserWindow]]))

(defn decide
  "PURE. What a request does: `want?` (the renderer's {on}), `is?` (the window is fullscreen now) and `owned?`
   (main made it fullscreen) → {:set true|false|nil :owned bool}, where :set nil means leave the window alone.

     want  is   owned  →  set    owned
     yes   no   –         true   true    enter, and own it
     yes   yes  o         nil    o       already fullscreen — ours or the user's; ownership unchanged
     no    yes  yes       false  false   leave the fullscreen we entered
     no    yes  no        nil    false   the user's own fullscreen: never leave it
     no    no   –         nil    false   nothing to do"
  [{:keys [want? is? owned?]}]
  (cond
    (and want? (not is?)) {:set true :owned true}
    want?                 {:set nil :owned (boolean owned?)}
    (and is? owned?)      {:set false :owned false}
    :else                 {:set nil :owned false}))

(defn- owned? [^js win] (boolean (.-vvFullscreenOwned win)))
(defn- set-owned! [^js win v] (set! (.-vvFullscreenOwned win) (boolean v)))

(defn- report!
  "Tell `win`'s renderer whether it is fullscreen, and whether main owns that fullscreen."
  [^js win]
  (when (and win (not (.isDestroyed win)))
    (let [^js wc (.-webContents win)]
      (when (and wc (not (.isDestroyed wc)))
        (.send wc "vv:fullscreen-changed"
               (clj->js {:fullscreen (.isFullScreen win) :owned (owned? win)}))))))

(defn- sender-window ^js [^js e]
  (some-> e .-sender (#(.fromWebContents BrowserWindow %))))

(defonce ^:private inited (atom false))

(defn init!
  "Register the fullscreen IPC handlers once for the whole process (every window shares them; each request is
   routed to its sender's window)."
  []
  (when (compare-and-set! inited false true)
    (.on ipcMain "vv:set-fullscreen"
         (fn [^js e ^js payload]
           (when-let [^js win (sender-window e)]
             (when-not (.isDestroyed win)
               (let [{:keys [set owned]} (decide {:want?  (boolean (some-> payload .-on))
                                                  :is?    (.isFullScreen win)
                                                  :owned? (owned? win)})]
                 (set-owned! win owned)
                 (if (some? set)
                   (.setFullScreen win set)     ; enter-/leave-full-screen then reports the new state
                   (report! win)))))))
    (.on ipcMain "vv:fullscreen-request"
         (fn [^js e] (some-> (sender-window e) report!)))))

(defn wire!
  "Report `win`'s fullscreen transitions to its renderer — including ones the user makes (F11, the window
   manager), which also end main's ownership: a fullscreen the user left is no longer ours to leave."
  [^js win]
  (.on win "enter-full-screen" (fn [] (report! win)))
  (.on win "leave-full-screen" (fn [] (set-owned! win false) (report! win))))
