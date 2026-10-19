(ns vinary.main.slides-link
  "PURE. Main's side of a linked slide show (ADR-0048 §8; the protocol, its proofs and its TLA+ model:
   docs/theory/11): the SESSIONS — each one's members and their roles, its move counter `seq` and its authoritative
   position — and the rules by which main numbers the windows' moves. No Electron and no IPC: a window is an opaque
   id `wc` (the shell, vinary.main.presenter, maps webContents to ids and back), a message is the keywordized payload
   of its channel, and every transition returns data that the shell carries out:

     {:state    the new state
      :out      [[wc message] …]   vv:presenter-state messages, in send order; only ever to members
      :claim    {:session :presenter :path}       open accepted: claim an audience window for this session
      :audience wc, :link {…}                     the window is claimed: send it vv:open-files with this `link`
      :close    [wc …]                            close these windows (the audience window existed for the show)
      :raise    wc                                raise this window (Shift+S again while linked)
      :ended    {:session :reason}                a session ended
      :swap     {:session :requester :members}    run a Swap screens plan (vinary.main.displays/swap-plan)}

   Only :state and :out are always present.

   State: {:next-id n :sessions {id session} :member-of {wc id}}. Ids are \"show-1\", \"show-2\", …, minted from
   :next-id; they need not be secret, because main knows each sender from its webContents. A session:

     {:id \"show-1\" :phase :claiming|:active :path deck :fullscreen bool :display \"separate\"|\"same\"|\"manual\"
      :seq n :page p :blank nil|\"black\"|\"white\" :members {wc :presenter|:audience} :last-mid {wc n}
      :swapping wc}

   :fullscreen is the presenter's :slides-fullscreen? preference, :display how the audience window was placed
   (vinary.main.displays/arrangement), :last-mid each member's highest accepted mid, :swapping the member whose Swap
   screens is running. Positions are kept as they travel: a page index and a blank colour string.

   The protocol (theory/11 §3.1). A move {session mid page blank} from a member whose mid is higher than its
   :last-mid is ACCEPTED: seq + 1, the position is stored, an ack {seq, ack: mid, accepted: true} goes to the sender
   only, and a push {seq page blank} to every other member. An older mid — impossible over Electron's ordered IPC,
   possible over a reordering transport — is acknowledged {seq: 0, ack: mid, accepted: false} and changes nothing:
   the sender stops waiting for it, and a late move never overwrites a newer one (the mid guard). A move from a
   non-member, a move for a session that is not active, and a malformed move are dropped (ADR-0048 §8.9).

   The lifecycle (ADR-0048 §8.6; docs/diagrams/state-linked-slide-show.puml):

     state           input                                      effect
     —               open: valid, sender free, focused, retains a :claiming session with the presenter as its
                     the deck                                   only member; :claim
     —               open: otherwise                            refused {invalid | busy | not-focused | not-retained}
     active          open from its presenter                    the joined message again; :raise the audience
     claiming        claimed wc                                 active; joined → presenter; :audience + :link
     claiming        claim-failed                               ended {stop: false, no-window} → presenter
     (gone)          claimed wc                                 :close [wc] — the show it was claimed for is over
     active          move                                       as above
     active          ready from the audience                    peer \"live\" → presenter; moves nothing
     any             leave, member-gone                         ended → the other member; :close the audience

   How a session ends, by who leaves and why — what the OTHER member is told:

     who        why                                                ended message to the other member
     either     leave \"stopped\" (Escape, End Slide Show)         {stop: true,  reason: \"stopped\"}
     presenter  leave \"unmounted\"; its window closed, reloaded   {stop: true,  reason: \"presenter-left\"}
                or crashed
     audience   leave \"unmounted\"; its window closed, reloaded   {stop: false, reason: \"audience-left\"}
                or crashed
     audience   its display was removed                            {stop: false, reason: \"display-removed\"}

   In every row main closes the audience window (unless that is the window that closed). A member that is gone is
   sent nothing. Swap screens changes no session field but :swapping: roles, positions and the seq are untouched, so
   the protocol never sees a swap.")

;; ---- vocabulary -------------------------------------------------------------------------------------------------

(def page-limit
  "A move's page index is an integer in [0, page-limit) (ADR-0048 §8.9)."
  1000000)

(def wire-blanks
  "The blank colours a payload may carry: nil (not blanked), \"black\" or \"white\"."
  #{nil "black" "white"})

(def leave-reasons
  "Why a window leaves its show (vv:presenter-leave): \"stopped\" — Escape or End Slide Show — or \"unmounted\" — its
   deck view went away for good: the audience window's view, or the tab the presenter's show is of closed. A
   presenter whose deck view only unmounts while that tab stays open (a tab switch, navigation, Reflow) does not
   leave: it is away, and its show runs on in the audience window (ADR-0048 §8.10)."
  #{"stopped" "unmounted"})

(def gone-causes
  "Why a member is gone without leaving (ADR-0048 §8.6): its window closed (`closed`), reloaded (a main-frame
   `did-start-navigation`), crashed (`render-process-gone`) — each ends the show as an unmount of its view — or the
   display it was on was removed (`display-removed`, which ends the show only for the audience window)."
  #{:closed :reloaded :crashed :display-removed})

(def arrangements
  "How the audience window was placed, as the presenter's joined message says (vinary.main.displays/arrangement)."
  #{"separate" "same" "manual"})

(def empty-state
  "No sessions; the next session is \"show-1\"."
  {:next-id 1 :sessions {} :member-of {}})

(def ^:private role-rank {:presenter 0 :audience 1})

;; ---- validation (ADR-0048 §8.4, §8.9) ---------------------------------------------------------------------------

(defn valid-page?
  "Is `p` a page index main accepts: an integer in [0, page-limit)?"
  [p]
  (and (integer? p) (<= 0 p) (< p page-limit)))

(defn valid-blank?
  "Is `b` a blank colour main accepts: nil, \"black\" or \"white\"?"
  [b]
  (contains? wire-blanks b))

(defn valid-mid?
  "Is `m` a move id main accepts: a positive integer?"
  [m]
  (and (integer? m) (pos? m)))

(defn- session-id? [s] (and (string? s) (not= "" s)))

(defn valid-open?
  "Is `payload` a well-formed vv:presenter-open {path page blank fullscreen}: a non-empty path, a valid page and
   blank colour, and a boolean fullscreen (absent counts as false)?"
  [{:keys [path page blank fullscreen] :as payload}]
  (boolean (and (map? payload) (string? path) (not= "" path) (valid-page? page) (valid-blank? blank)
                (or (nil? fullscreen) (boolean? fullscreen)))))

(defn valid-move?
  "Is `payload` a well-formed vv:presenter-move {session mid page blank}?"
  [{:keys [session mid page blank] :as payload}]
  (boolean (and (map? payload) (session-id? session) (valid-mid? mid) (valid-page? page) (valid-blank? blank))))

(defn valid-ready?
  "Is `payload` a well-formed vv:presenter-ready {session}?"
  [{:keys [session] :as payload}]
  (boolean (and (map? payload) (session-id? session))))

(defn valid-leave?
  "Is `payload` a well-formed vv:presenter-leave {session reason}, the reason one of `leave-reasons`?"
  [{:keys [session reason] :as payload}]
  (boolean (and (map? payload) (session-id? session) (contains? leave-reasons reason))))

(defn valid-swap?
  "Is `payload` a well-formed vv:presenter-swap {session}?"
  [{:keys [session] :as payload}]
  (boolean (and (map? payload) (session-id? session))))

;; ---- queries ----------------------------------------------------------------------------------------------------

(defn session-of
  "The id of the session window `wc` is a member of, or nil. A window is in at most one."
  [state wc]
  (get-in state [:member-of wc]))

(defn session
  "Session `id`, or nil."
  [state id]
  (get-in state [:sessions id]))

(defn members
  "Session `id`'s members, {wc role}; nil for no such session."
  [state id]
  (get-in state [:sessions id :members]))

(defn role-of
  "Window `wc`'s role in its session — :presenter or :audience — or nil when it is in none."
  [state wc]
  (when-let [id (session-of state wc)]
    (get-in state [:sessions id :members wc])))

(defn- member-with-role [state id role]
  (some (fn [[wc r]] (when (= r role) wc)) (members state id)))

(defn presenter-of
  "The presenter window of session `id`, or nil."
  [state id]
  (member-with-role state id :presenter))

(defn audience-of
  "The audience window of session `id` — nil while the session is still claiming one."
  [state id]
  (member-with-role state id :audience))

(defn prompt-window
  "The window an SSH prompt raised on behalf of window `wc` goes to (ADR-0048 §8.8, L7): `wc` itself — unless it is
   a linked show's audience window, then that show's presenter window, so a host-key dialog or a password prompt
   never appears on the projector."
  [state wc]
  (if (= :audience (role-of state wc))
    (or (presenter-of state (session-of state wc)) wc)
    wc))

(defn- members-in-order
  "Session `sess`'s member windows, the presenter first — so messages to several members go out in one order."
  [sess]
  (map key (sort-by (comp role-rank val) (:members sess))))

;; ---- messages (vv:presenter-state, ADR-0048 §8.3) -------------------------------------------------------------

(defn joined-message
  "The presenter's joined message for session `sess`: {session seq role page blank joined display}."
  [{id :id s :seq page :page blank :blank display :display}]
  {:session id :seq s :role "presenter" :page page :blank blank :joined true :display display})

(defn refused-message
  "The answer to an open main refuses: {session: nil, refused: `reason`}."
  [reason]
  {:session nil :refused reason})

(defn- ended-message [id stop? reason] {:session id :ended true :stop stop? :reason reason})
(defn- ack-message [id s mid accepted?] {:session id :seq s :ack mid :accepted accepted?})
(defn- push-message [id s page blank] {:session id :seq s :page page :blank blank})

(defn- audience-link
  "The `link` field of the audience window's vv:open-files: where its show starts, and whether it goes fullscreen —
   the presenter's preference, on a separate display (placement supported is implied: without it the display is
   \"manual\")."
  [{id :id s :seq page :page blank :blank fullscreen :fullscreen display :display}]
  {:session id :seq s :role "audience" :page page :blank blank
   :fullscreen (boolean (and fullscreen (= "separate" display)))})

;; ---- transitions --------------------------------------------------------------------------------------------------

(defn- unchanged [state] {:state state :out []})

(defn- remove-session [state id]
  (-> state
      (update :sessions dissoc id)
      (update :member-of #(apply dissoc % (keys (members state id))))))

(def ^:private endings
  "What the other member of a session is told, by the role of the member that left and why."
  {[:presenter :stopped]         {:stop true  :reason "stopped"}
   [:audience  :stopped]         {:stop true  :reason "stopped"}
   [:presenter :unmounted]       {:stop true  :reason "presenter-left"}
   [:audience  :unmounted]       {:stop false :reason "audience-left"}
   [:audience  :display-removed] {:stop false :reason "display-removed"}})

(defn- end-session
  "Session `id` ends because its member `wc` left for `why` (:stopped, :unmounted or :display-removed): the other
   members are told (`endings`), the audience window is closed, and the session is forgotten."
  [state id wc why]
  (let [sess                  (session state id)
        {:keys [stop reason]} (get endings [(get-in sess [:members wc]) why])
        audience              (audience-of state id)]
    {:state (remove-session state id)
     :out   (into [] (comp (remove #{wc}) (map (fn [w] [w (ended-message id stop reason)])))
                  (members-in-order sess))
     :close (if audience [audience] [])
     :ended {:session id :reason reason}}))

(defn open
  "Window `wc` asks for a linked show (vv:presenter-open {path page blank fullscreen}). `facts` are what only the
   shell can know: {:focused? — the window is focused (a stand-in for a user gesture), :retains? — it already
   retains `path` (vinary.main.service/window-retains?)}; the shell answers a sender that is not an app window's own
   webContents itself, by dropping it. In order:

     the payload is malformed                     refused \"invalid\"
     wc presents an active show                   the joined message again, and :raise its audience window —
                                                  Shift+S while linked opens nothing new (ADR-0048 §8.1)
     wc is in a session (or its claim)            refused \"busy\"
     wc is not focused                            refused \"not-focused\"
     wc does not retain the path                  refused \"not-retained\"
     otherwise                                    a new :claiming session \"show-<n>\" whose only member is wc, at
                                                  seq 0 and the payload's position; :claim asks the shell to
                                                  claim an audience window for it"
  [state wc payload {:keys [focused? retains?]}]
  (let [current (session-of state wc)
        sess    (when current (session state current))
        refuse  (fn [reason] {:state state :out [[wc (refused-message reason)]]})]
    (cond
      (not (valid-open? payload))
      (refuse "invalid")

      (and (= :active (:phase sess)) (= :presenter (get-in sess [:members wc])))
      {:state state :out [[wc (joined-message sess)]] :raise (audience-of state current)}

      sess          (refuse "busy")
      (not focused?) (refuse "not-focused")
      (not retains?) (refuse "not-retained")

      :else
      (let [n                                    (:next-id state)
            id                                   (str "show-" n)
            {:keys [path page blank fullscreen]} payload]
        {:state (-> state
                    (assoc :next-id (inc n))
                    (assoc-in [:sessions id] {:id id :phase :claiming :path path :fullscreen (true? fullscreen)
                                              :seq 0 :page page :blank blank
                                              :members {wc :presenter} :last-mid {wc 0}})
                    (assoc-in [:member-of wc] id))
         :out   []
         :claim {:session id :presenter wc :path path}}))))

(defn claim-failed
  "No audience window could be claimed for session `id`: the session ends, and its presenter is told
   ended {stop: false, reason: \"no-window\"} — it keeps presenting in the presenter view. Unchanged unless `id`
   is claiming."
  [state id]
  (let [sess (session state id)]
    (if (= :claiming (:phase sess))
      {:state (remove-session state id)
       :out   [[(presenter-of state id) (ended-message id false "no-window")]]
       :ended {:session id :reason "no-window"}}
      (unchanged state))))

(defn claimed
  "The shell claimed window `audience` for session `id` and placed it; `display` is how
   (vinary.main.displays/arrangement: \"separate\", \"same\" or \"manual\"). The session goes live: `audience` joins
   it, the presenter gets its joined message, and :link is what the shell sends the audience window in
   vv:open-files — the session's position at seq 0, and fullscreen when the presenter prefers it and the display is
   separate.

   When the session is gone — its presenter left while the window was being claimed — or no longer claiming, the
   window has no show: :close it. A window that already belongs to a session (a shell error — perhaps the presenter
   itself), or an unknown `display`, fails the claim instead (see claim-failed), and nothing is closed."
  [state id audience {:keys [display]}]
  (let [sess (session state id)]
    (cond
      (not= :claiming (:phase sess))
      {:state state :out [] :close [audience]}

      (session-of state audience)
      (claim-failed state id)

      (not (contains? arrangements display))
      (assoc (claim-failed state id) :close [audience])

      :else
      (let [sess' (-> sess
                      (assoc :phase :active :display display)
                      (assoc-in [:members audience] :audience)
                      (assoc-in [:last-mid audience] 0))]
        {:state    (-> state
                       (assoc-in [:sessions id] sess')
                       (assoc-in [:member-of audience] id))
         :out      [[(presenter-of state id) (joined-message sess')]]
         :audience audience
         :link     (audience-link sess')}))))

(defn move
  "Window `wc` moved (vv:presenter-move {session mid page blank}). Dropped — unchanged, nothing sent — unless the
   payload is well formed, its session is active, and `wc` is a member of it. Then a mid not above the sender's
   last accepted one is acknowledged {seq: 0, ack: mid, accepted: false} and changes nothing (the mid guard); any
   other is accepted: seq + 1, the position is stored, the sender gets {seq, ack: mid, accepted: true} and every
   other member the push {seq page blank}."
  [state wc {id :session mid :mid page :page blank :blank :as payload}]
  (let [sess (when (valid-move? payload) (session state id))]
    (cond
      (not (and (= :active (:phase sess)) (= id (session-of state wc))))
      (unchanged state)

      (<= mid (get-in sess [:last-mid wc] 0))
      {:state state :out [[wc (ack-message id 0 mid false)]]}

      :else
      (let [s (inc (:seq sess))]
        {:state (update-in state [:sessions id]
                           #(-> % (assoc :seq s :page page :blank blank) (assoc-in [:last-mid wc] mid)))
         :out   (into [[wc (ack-message id s mid true)]]
                      (comp (remove #{wc}) (map (fn [w] [w (push-message id s page blank)])))
                      (members-in-order sess))}))))

(defn ready
  "The audience window's deck is showing (vv:presenter-ready {session}): the presenter is told {peer: \"live\"}, and
   its pane drops its waiting notice. It moves nothing — the audience never announces its start page as a move,
   which main would order after any step the presenter took meanwhile, and so undo it (theory/11 §3.4, control
   N-announce). Dropped unless `wc` is the audience window of that active session."
  [state wc {id :session :as payload}]
  (let [sess (when (valid-ready? payload) (session state id))]
    (if (and (= :active (:phase sess)) (= id (session-of state wc)) (= :audience (get-in sess [:members wc])))
      {:state state :out [[(presenter-of state id) {:session id :peer "live"}]]}
      (unchanged state))))

(defn leave
  "Window `wc` leaves its show (vv:presenter-leave {session reason}): the session ends as the namespace's table says
   — the other member is told, main closes the audience window. Dropped unless the payload is well formed and `wc`
   is a member of that session."
  [state wc {id :session reason :reason :as payload}]
  (if (and (valid-leave? payload) (= id (session-of state wc)))
    (end-session state id wc (keyword reason))
    (unchanged state)))

(defn member-gone
  "Window `wc` is gone without leaving, for `cause` (one of `gone-causes`). A window that closed, reloaded or crashed
   ends its show as an unmount of its view would — and a closed audience window is not closed again. A removed
   display ends the show only when it was the audience window's: the operating system would otherwise move a
   fullscreen projector window over the presenter (ADR-0048 §8.6); the presenter's window just moves to another
   display. Unchanged for a window in no session."
  [state wc cause]
  (let [id   (session-of state wc)
        role (role-of state wc)]
    (cond
      (not (and id (contains? gone-causes cause)))
      (unchanged state)

      (= :display-removed cause)
      (if (= :audience role) (end-session state id wc :display-removed) (unchanged state))

      (and (= :closed cause) (= :audience role))
      (assoc (end-session state id wc :unmounted) :close [])

      :else
      (end-session state id wc :unmounted))))

(defn swap-begin
  "Window `wc` asks to swap screens (vv:presenter-swap {session}; ADR-0048 §8.7). `facts` {:focused? bool} — a swap
   is accepted only from a focused member of an active session whose audience window main placed on a separate
   display (:display \"separate\", ADR-0048 §8.9): with \"same\" there was no other display, and with \"manual\"
   (native Wayland) main cannot place windows at all, so neither offers Swap screens. Whether the two windows are
   STILL on separate displays only the shell can see; vinary.main.displays/swap-plan answers that, and its nil is
   answered with swap-end … {:swapped false :why \"same-display\"}. While a swap of that session runs, the answer is
   {swapped: false, why: \"busy\"}. Otherwise the session is marked swapping, and :swap {:session :requester
   :members} tells the shell to plan and run the swap. Anything else is dropped."
  [state wc {id :session :as payload} {:keys [focused?]}]
  (let [sess (when (valid-swap? payload) (session state id))]
    (cond
      (not (and (= :active (:phase sess)) (= "separate" (:display sess)) (= id (session-of state wc)) focused?))
      (unchanged state)

      (:swapping sess)
      {:state state :out [[wc {:session id :swapped false :why "busy"}]]}

      :else
      {:state (assoc-in state [:sessions id :swapping] wc)
       :out   []
       :swap  {:session id :requester wc :members (:members sess)}})))

(defn swap-end
  "Session `id`'s swap is over, with `outcome` {:swapped true :lost [role …]} — the roles whose window could not go
   fullscreen again — or {:swapped false :why \"same-display\"}. The window that asked is answered, and the session
   may swap again. Unchanged when the session ended meanwhile (its windows' fullscreen state is the shell's to
   report) or no swap runs."
  [state id {:keys [swapped lost why]}]
  (let [requester (get-in state [:sessions id :swapping])]
    (if requester
      {:state (update-in state [:sessions id] dissoc :swapping)
       :out   [[requester (if swapped
                            {:session id :swapped true :lost (into [] (comp (remove nil?) (map name)) lost)}
                            {:session id :swapped false :why why})]]}
      (unchanged state))))
