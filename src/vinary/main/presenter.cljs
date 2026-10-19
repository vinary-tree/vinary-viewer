(ns vinary.main.presenter
  "The shell of a linked slide show (ADR-0048 §8): it runs the pure session model (vinary.main.slides-link) and the
   pure display rules (vinary.main.displays) against Electron, and carries out what they decide.

     the channels     vv:presenter-open / -move / -ready / -leave / -swap (renderer → main) arrive here; every
                      answer goes out on vv:presenter-state, to members only
     the senders      a request counts only from an app window's OWN main webContents (`own-sender`); the model
                      knows each window by its webContents id and never sees an Electron object
     the claim        an accepted open claims the audience window through core (open-window! :presenter): from the
                      warm pool when one is ready, placed on another display when there is one, shown without the
                      focus, never saved to window.edn
     the lifecycle    a member window that closes, reloads or crashes, and a removed display, are reported to the
                      model (`wire!`, `init!`)
     Swap screens     vinary.main.displays/swap-plan, run by vinary.main.fullscreen/swap-displays!
     SSH prompts      vinary.main.windows/prompt-window sends an audience window's prompts to its presenter

   A window is mapped back from its id through webContents.fromId, so a window that is gone has no window, and a
   message to it is dropped."
  (:require ["electron" :refer [ipcMain BrowserWindow screen webContents]]
            [vinary.main.displays :as displays]
            [vinary.main.fullscreen :as fullscreen]
            [vinary.main.service :as service]
            [vinary.main.slides-link :as link]
            [vinary.main.startup :as startup]
            [vinary.main.windows :as windows]))

(defonce ^:private sessions (atom link/empty-state))
(defonce ^:private placed (atom {}))      ; session id → the id of the display main placed its audience window on
(defonce ^:private claim-fn (atom nil))   ; core's claim of an audience window (open-window! :presenter), from init!
(defonce ^:private unclaimed (atom {}))   ; webContents id → session: a cold window still loading toward its claim

(def refill-delay-ms
  "How long a claim defers the warm pool's refill, so a new window does not boot during the show's first
   transitions (ADR-0048 §8.8)."
  5000)

;; ---- Electron, as data ------------------------------------------------------------------------------------------

(defn- live-wc ^js [id]
  (let [^js wc (.fromId webContents id)]
    (when (and wc (not (.isDestroyed wc))) wc)))

(defn- window-of
  "The live app window whose webContents has id `id`, or nil."
  ^js [id]
  (when-let [^js wc (live-wc id)]
    (let [^js win (.fromWebContents BrowserWindow wc)]
      (when (and win (not (.isDestroyed win))) win))))

(defn- rect [^js r] {:x (.-x r) :y (.-y r) :width (.-width r) :height (.-height r)})

(defn- bounds-of [^js win] (rect (.getBounds win)))

(defn- display
  "An Electron Display as the map vinary.main.displays reads."
  [^js d]
  {:id (.-id d) :bounds (rect (.-bounds d)) :workArea (rect (.-workArea d)) :internal (boolean (.-internal d))
   :scaleFactor (.-scaleFactor d)})

(defn- all-displays [] (mapv display (.getAllDisplays screen)))

(defn- placement-supported? []
  (let [env (.-env js/process)]
    (displays/placement-supported?
     (startup/wayland-session? {:XDG_SESSION_TYPE (.-XDG_SESSION_TYPE env) :WAYLAND_DISPLAY (.-WAYLAND_DISPLAY env)})
     (.-VV_OZONE env))))

(defn- own-sender
  "The webContents id of the app window whose own main frame sent IPC event `e`, or nil: a web view, an embedded
   view or a subframe is never a member of a show (ADR-0048 §8.9)."
  [^js e]
  (let [^js wc    (.-sender e)
        ^js win   (when wc (.fromWebContents BrowserWindow wc))
        ^js frame (.-senderFrame e)]
    (when (and win (not (.isDestroyed win)) (= (.-id (.-webContents win)) (.-id wc))
               frame (nil? (.-parent frame)))
      (.-id wc))))

(defn- payload [^js p] (js->clj p :keywordize-keys true))

;; ---- carrying out a transition ----------------------------------------------------------------------------------

(defn- send-state! [[id message]]
  (when-let [^js wc (live-wc id)]
    (.send wc "vv:presenter-state" (clj->js message))))

(declare claim-audience! run-swap!)

(defn- carry-out!
  "Adopt transition `result`'s state and carry out its effects: its messages in order, then the windows it closes
   and raises, then a claim or a swap it asks for. :audience and :link are the claim's own (claim-audience!)."
  [{new-state :state :keys [out close raise claim ended] swap-request :swap}]
  (reset! sessions new-state)
  (run! send-state! out)
  (doseq [id close] (some-> (window-of id) .close))
  (when-let [^js win (some-> raise window-of)]
    (.showInactive win)
    (.moveTop win))
  (when ended (swap! placed dissoc (:session ended)))
  (when claim (claim-audience! claim))
  (when swap-request (run-swap! swap-request)))

(defn- refocus-on-fullscreen!
  "After the audience window first goes fullscreen, give the presenter window the focus back — best effort, since
   window managers differ; correctness never depends on it, because either window drives (ADR-0048 §8.7)."
  [^js audience presenter-id]
  (.once audience "enter-full-screen" (fn [] (some-> (window-of presenter-id) .focus))))

(defn- claim-audience!
  "Claim the audience window for session `session`: plan where it goes from the presenter window's bounds, ask core
   for a window, and — once the model has it as a member — give core the `link` its vv:open-files carries. A pool
   window joins at once; a cold one only when it has loaded, and until then it is `unclaimed`, so its end before that
   fails the claim (`wire!`). A claim that throws, or a presenter already gone, fails the claim too: the presenter
   keeps presenting in one window."
  [{:keys [session presenter path]}]
  (let [^js pwin (window-of presenter)]
    (if-not (and pwin @claim-fn)
      (carry-out! (link/claim-failed @sessions session))
      (let [plan (displays/audience-plan (all-displays) (bounds-of pwin) (placement-supported?))]
        (try
          (let [^js win (@claim-fn {:path            path
                                    :show            :inactive
                                    :ephemeral?      true
                                    :bounds          (:bounds plan)
                                    :refill-delay-ms refill-delay-ms
                                    :on-claim        (fn [^js win]
                                                       (let [id     (.-id (.-webContents win))
                                                             _      (swap! unclaimed dissoc id)
                                                             result (link/claimed @sessions session id
                                                                                  {:display (:display plan)})
                                                             link   (:link result)]
                                                         (carry-out! result)
                                                         (when link
                                                           (swap! placed assoc session (:target plan))
                                                           (when (:fullscreen link) (refocus-on-fullscreen! win presenter))
                                                           link)))})]
            (when (and win (= :claiming (:phase (link/session @sessions session))))
              (swap! unclaimed assoc (.-id (.-webContents win)) session)))
          (catch :default e
            (js/console.warn "[vinary] linked show: no audience window:" (.-message e))
            (carry-out! (link/claim-failed @sessions session))))))))

(defn- run-swap!
  "Run Swap screens for `members` ({wc role}): plan each window onto the other's display; with no plan (both on one
   display) answer same-display; else swap them (fullscreen/swap-displays!), record where the audience window now
   is, give the requester the focus back (best effort), and answer with the roles that lost their fullscreen."
  [{:keys [session requester members]}]
  (let [wins (into {} (keep (fn [[id role]] (when-let [w (window-of id)] [role w]))) members)
        plan (displays/swap-plan (into {} (map (fn [[role ^js w]] [role {:bounds (bounds-of w) :fullscreen? (.isFullScreen w)}]))
                                       wins)
                                 (all-displays))]
    (if-not plan
      (carry-out! (link/swap-end @sessions session {:swapped false :why "same-display"}))
      (-> (fullscreen/swap-displays! (into {} (map (fn [[role w]] [role {:win w :bounds (get-in plan [role :bounds])}]))
                                           wins))
          (.then (fn [lost]
                   (when (link/session @sessions session)
                     (swap! placed assoc session (get-in plan [:audience :display])))
                   (some-> (window-of requester) .focus)
                   (carry-out! (link/swap-end @sessions session {:swapped true :lost lost}))))))))

;; ---- the window lifecycle ---------------------------------------------------------------------------------------

(defn wire!
  "Report app window `win`'s end to the linked-show model: closed, reloaded (a main-frame navigation to a new
   document after its first load — the first load itself is not one) or crashed. A window in no show changes
   nothing, so core wires every window, as it does for fullscreen/wire!. A cold audience window that ends before it
   has loaded fails its claim (link/claim-failed): the presenter is told no window could be opened."
  [^js win]
  (let [^js wc (.-webContents win)
        id     (.-id wc)
        gone!  (fn [cause]
                 (if-let [session (get @unclaimed id)]
                   (do (swap! unclaimed dissoc id)
                       (carry-out! (link/claim-failed @sessions session))
                       (some-> (window-of id) .close))          ; a crashed window is still open
                   (carry-out! (link/member-gone @sessions id cause))))]
    (.on win "closed" (fn [] (gone! :closed)))
    (.on wc "render-process-gone" (fn [_ _] (gone! :crashed)))
    (.once wc "did-finish-load"
           (fn []
             (.on wc "did-start-navigation"
                  (fn [^js details]
                    (when (and (.-isMainFrame details) (not (.-isSameDocument details)))
                      (gone! :reloaded))))))))

(defn- display-removed!
  "A display went away: every show whose audience window was on it ends (link/member-gone :display-removed), so
   the system never moves a fullscreen projector window over the presenter."
  [^js old]
  (let [removed   (display old)
        remaining (all-displays)]
    (doseq [id (keys (:sessions @sessions))]
      (when-let [audience (link/audience-of @sessions id)]
        (when-let [^js win (window-of audience)]
          (when (displays/on-removed-display? removed remaining (bounds-of win) (get @placed id))
            (carry-out! (link/member-gone @sessions audience :display-removed))))))))

(defn- redirect-prompt
  "vinary.main.windows' prompt redirect: an audience window's prompts go to its show's presenter window."
  ^js [^js win]
  (let [id     (some-> win .-webContents .-id)
        target (when id (link/prompt-window @sessions id))]
    (when (and target (not= target id)) (window-of target))))

;; ---- the channels -----------------------------------------------------------------------------------------------

(defonce ^:private inited (atom false))

(defn init!
  "Register the linked-show channels, the display-removed watch and the prompt redirect, once per process (after
   app ready: `screen` needs it). `claim!` is core's claim of an audience window,
   (fn [{:keys [path show ephemeral? bounds refill-delay-ms on-claim]}] → BrowserWindow)."
  [{:keys [claim!]}]
  (when (compare-and-set! inited false true)
    (reset! claim-fn claim!)
    (windows/set-prompt-redirect! redirect-prompt)
    (.on ipcMain "vv:presenter-open"
         (fn [^js e ^js p]
           (when-let [id (own-sender e)]
             (let [msg (payload p)]
               (carry-out! (link/open @sessions id msg
                                      {:focused? (.isFocused (window-of id))
                                       :retains? (and (string? (:path msg))
                                                      (service/window-retains? (.-sender e) (:path msg)))}))))))
    (.on ipcMain "vv:presenter-move"
         (fn [^js e ^js p] (when-let [id (own-sender e)] (carry-out! (link/move @sessions id (payload p))))))
    (.on ipcMain "vv:presenter-ready"
         (fn [^js e ^js p] (when-let [id (own-sender e)] (carry-out! (link/ready @sessions id (payload p))))))
    (.on ipcMain "vv:presenter-leave"
         (fn [^js e ^js p] (when-let [id (own-sender e)] (carry-out! (link/leave @sessions id (payload p))))))
    (.on ipcMain "vv:presenter-swap"
         (fn [^js e ^js p]
           (when-let [id (own-sender e)]
             (carry-out! (link/swap-begin @sessions id (payload p) {:focused? (.isFocused (window-of id))})))))
    (.on screen "display-removed" (fn [_ ^js old] (display-removed! old)))))
