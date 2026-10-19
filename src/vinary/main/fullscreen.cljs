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
   `vv:fullscreen-changed` {fullscreen owned} (main → renderer, on every enter/leave and on request).

   Swap screens (ADR-0048 §8.7) is the one place main leaves a fullscreen it did not enter, and it restores it within
   the same swap: `swap-displays!` marks each window SWAPPING — `wire!`'s handlers then neither report nor clear the
   ownership — leaves, moves and re-enters, then gives each window its ownership back and reports its real state
   once, with `swap: true`."
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

(defn- swapping? [^js win] (boolean (.-vvFullscreenSwapping win)))
(defn- set-swapping! [^js win v] (set! (.-vvFullscreenSwapping win) (boolean v)))

(defn- live? [^js win] (boolean (and win (not (.isDestroyed win)))))

(defn- report!
  "Tell `win`'s renderer whether it is fullscreen, and whether main owns that fullscreen; `extra` adds fields
   (`{:swap true}` on the report that ends a swap)."
  ([^js win] (report! win nil))
  ([^js win extra]
   (when (live? win)
     (let [^js wc (.-webContents win)]
       (when (and wc (not (.isDestroyed wc)))
         (.send wc "vv:fullscreen-changed"
                (clj->js (merge {:fullscreen (.isFullScreen win) :owned (owned? win)} extra))))))))

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
   manager), which also end main's ownership: a fullscreen the user left is no longer ours to leave. Not while the
   window is swapping screens: `swap-displays!` reports once, at the end."
  [^js win]
  (.on win "enter-full-screen" (fn [] (when-not (swapping? win) (report! win))))
  (.on win "leave-full-screen" (fn [] (when-not (swapping? win) (set-owned! win false) (report! win)))))

(def swap-wait-ms
  "How long a swap waits for one window's leave-full-screen or enter-full-screen: leaving is asynchronous on Linux
   and animated on macOS, and a window manager may refuse."
  2000)

(defn- event-or-timeout
  "A promise that resolves when `win` emits `event`, or after `ms`, whichever comes first — at once when the window
   is gone."
  [^js win event ms]
  (js/Promise.
   (fn [resolve]
     (if-not (live? win)
       (resolve nil)
       (let [timer    (atom nil)
             on-event (fn [] (js/clearTimeout @timer) (resolve nil))]
         (reset! timer (js/setTimeout (fn [] (.removeListener win event on-event) (resolve nil)) ms))
         (.once win event on-event))))))

(defn- all-settled
  "A promise of every window in `wins` that `start!` returns true for, after each one's `event` (or its timeout)."
  [wins start! event]
  (js/Promise.all
   (into-array (keep (fn [^js w] (when (and (live? w) (start! w)) (event-or-timeout w event swap-wait-ms))) wins))))

(defn swap-displays!
  "Swap screens' four steps (ADR-0048 §8.7) for `moves`, {key {:win BrowserWindow :bounds rect}}:

     1. record each window's fullscreen state and ownership and mark it swapping; each fullscreen window leaves
        fullscreen, and the swap waits for its leave-full-screen (at most `swap-wait-ms`);
     2. place each window at its :bounds, un-maximized first;
     3. each window that was fullscreen enters it again, and the swap waits for its enter-full-screen;
     4. unmark each window, give it its recorded ownership back, and report its real state once, with swap: true.

   A window that closes meanwhile is skipped from then on. Resolves to the keys of the windows that were fullscreen
   and are not now — a window manager refused, or the window is gone."
  [moves]
  (let [entries  (vec moves)
        wins     (mapv (comp :win val) entries)
        recorded (into {} (map (fn [[k {:keys [^js win]}]] [k {:fullscreen? (.isFullScreen win) :owned? (owned? win)}]))
                       entries)
        was-full (into #{} (keep (fn [[k {:keys [win]}]] (when (get-in recorded [k :fullscreen?]) win))) entries)]
    (run! #(set-swapping! % true) wins)
    (-> (all-settled wins (fn [^js w] (when (contains? was-full w) (.setFullScreen w false) true)) "leave-full-screen")
        (.then (fn [_]
                 (doseq [[_ {:keys [^js win bounds]}] entries :when (live? win)]
                   (when (.isMaximized win) (.unmaximize win))
                   (.setBounds win (clj->js bounds)))
                 (all-settled wins (fn [^js w] (when (contains? was-full w) (.setFullScreen w true) true))
                              "enter-full-screen")))
        (.then (fn [_]
                 (doseq [[k {:keys [^js win]}] entries :when (live? win)]
                   (set-swapping! win false)
                   (set-owned! win (get-in recorded [k :owned?]))
                   (report! win {:swap true}))
                 (into [] (keep (fn [[k {:keys [^js win]}]]
                                  (when (and (contains? was-full win) (not (and (live? win) (.isFullScreen win)))) k)))
                       entries))))))
