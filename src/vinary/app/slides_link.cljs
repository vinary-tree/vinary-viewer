(ns vinary.app.slides-link
  "PURE. A window's side of a linked slide show (ADR-0048 §8.4–§8.6; the protocol, its proofs and its TLA+ model:
   docs/theory/11 §3): the LINK state kept at [:ui :slides-link], and what the window does with each thing that
   happens to it. No re-frame, no DOM, no IPC: every function takes the link (and, where it must, the [:ui :slides]
   mirror) and returns data — the new link and what to do — which the events and the one global interceptor
   `slides-link-out` carry out.

   A position is {:page p :blank b}: a physical page index and the blank colour as app-db keeps it (nil, :black or
   :white); `position` builds one, always with both keys, because positions are compared whole. On the wire the
   colour is a string (\"black\", \"white\" or null): blank->wire and wire->blank convert at the edges.

   The link:

     key          meaning                                                              phase
     :phase       :opening (the presenter asked main for a show), :joining (the        all
                  audience window, before its deck has mounted), :live, or :ended
     :role        :presenter or :audience                                              all
     :session     main's session id; nil while :opening                                all
     :path        the deck                                                             all
     :known       the position last exchanged with main: the last move this window     :live
                  sent, or the last push it showed
     :seq         the newest move number seen                                          :joining :live
     :own-seq     the newest number of this window's own accepted moves                :joining :live
     :out         the mids sent that main has not acknowledged (a set)                 :joining :live
     :held        a push that arrived while :out was not empty {:seq :pos}, or nil     :live
     :applying    the page of a push being shown, until the engine reports, or nil     :live
     :next-mid    the next move's mid: 1, 2, …                                         :joining :live
     :start       {:seq :pos}: where the audience's show starts                        :joining
     :fullscreen  the audience: whether it goes fullscreen (the link's); the          :joining :live
                  presenter never asks for its own (ADR-0048 §8.1)
     :display     presenter: \"separate\", \"same\" or \"manual\" (the joined message)     :live
     :peer        presenter: :waiting until the audience's deck shows, then :live       :live
     :swap        presenter: main's last answer to Swap screens                        :live
     :reason      why it ended, and :stop? whether the show stops with it              :ended

   The window's rules (theory/11 §3.2–§3.4):

     on a local change — after the engine's report (`reported`, then `outgoing`), or after a blank key (`outgoing`):
       cur ← ⟨applying if a push is being shown, else the reported page; the blank colour⟩
       if cur ≠ known then  mid ← next mid;  out ← out ∪ {mid};  known ← cur;  send move(mid, cur)
     on push(s, π):  drop it if s ≤ seq (the stale guard);  seq ← s;  joining: start ← (s, π);
                     else show π when out = ∅, and hold it otherwise
     on ack(s, mid, accepted):  out ← out ∖ {mid};  if accepted: own-seq, seq ← max(…, s);
                     when out becomes ∅: show the held push if its number is above own-seq; forget it either way
     show π:  known ← π;  blank ← π.blank;  applying ← π.page;  the EFFECT [:slides/command [:show page]]

   Following never sends: a window that shows a push reports the page it was told to show, which equals known. It
   sends a correction only when its engine snapped that page (a note page snaps to its slide, always downward).
   The audience window never announces its start page as a move; it says vv:presenter-ready, which moves nothing.

   Returned keys, besides :link — the new link, always present:
     :move  {session mid page blank}  send vv:presenter-move          :open  {path page blank fullscreen}  open
     :ready {session}                 send vv:presenter-ready         :leave {session reason}              leave
     :show  position                  run (show-effect position) and set the blank colour to its :blank
     :fullscreen?  true               the audience window asks for its own fullscreen (vv:set-fullscreen)
     :stop?        true               stop this window's slide show too")

;; ---- positions --------------------------------------------------------------------------------------------------

(defn position
  "The position {:page :blank} — both keys always present, so two positions compare equal exactly when their page
   and blank colour do."
  [page blank]
  {:page page :blank blank})

(defn blank->wire
  "app-db's blank colour (nil, :black, :white) as a payload carries it: nil, \"black\", \"white\"."
  [b]
  (case b :black "black" :white "white" nil))

(defn wire->blank
  "A payload's blank colour (nil, \"black\", \"white\") as app-db keeps it: nil, :black, :white. Anything else is
   no blanking."
  [s]
  (case s "black" :black "white" :white nil))

(defn- wire-position [{:keys [page blank]}] (position page (wire->blank blank)))

;; ---- phases -------------------------------------------------------------------------------------------------------

(defn live?
  "Is `link` live — linked, and exchanging moves with main?"
  [link]
  (= :live (:phase link)))

(defn ended?
  "Has `link` ended (only what the window shows about the end is left)?"
  [link]
  (= :ended (:phase link)))

(defn ended
  "`link` after it ended for `reason` — a main ended message, a refusal, or this window's own leave — with `stop?`
   telling whether its slide show stops too. Only what the window shows about the end is kept,
   {:phase :ended :role :session :path :reason :stop?}; the protocol state is forgotten."
  [link reason stop?]
  {:phase   :ended
   :role    (:role link)
   :session (:session link)
   :path    (:path link)
   :reason  reason
   :stop?   (boolean stop?)})

(defn- in-session?
  "Does message `msg` belong to `link`'s session, while the link is joining or live?"
  [link msg]
  (and (contains? #{:joining :live} (:phase link)) (= (:session msg) (:session link))))

;; ---- what the presenter's pane shows ---------------------------------------------------------------------------

(defn swap-offered?
  "Is Swap screens offered in this window (ADR-0048 §8.7)? Its link is live, it is the presenter, and the joined
   message said the audience window was placed on a separate display (\"same\" has no other display; \"manual\" —
   native Wayland — cannot place windows)."
  [link]
  (boolean (and (live? link) (= :presenter (:role link)) (= "separate" (:display link)))))

(def ^:private ended-texts
  "What the pane says when a link ends and this window keeps presenting, by the end's reason — main's ended
   message, or main's refusal of the request."
  {"audience-left"   "The audience window closed. Shift+S opens it again."
   "display-removed" "The audience window's display was removed. Shift+S opens it again."
   "no-window"       "No audience window could be opened."
   "busy"            "This window is already in a linked show."
   "not-focused"     "No audience window opened: this window was not focused. Press Shift+S again."
   "not-retained"    "No audience window opened: this window no longer holds the deck."
   "invalid"         "No audience window opened: the request was not valid."})

(defn- lost-text [lost]
  (let [lost (set lost)]
    (cond
      (and (lost "audience") (lost "presenter")) "Neither window could go fullscreen again; press Shift+F in each."
      (lost "audience")                          "The audience window could not go fullscreen again; press Shift+F in it."
      (lost "presenter")                         "This window could not go fullscreen again; press Shift+F."
      :else                                      nil)))

(defn notice
  "The line the presenter's pane shows about `link`, {:tone :info|:warning :text}, or nil for none (ADR-0048 §8.6,
   §8.7). In order: opening; waiting for the audience window (and, after 10 s — :waited? — where to look); main's
   last answer to Swap screens; how the audience window was placed when that needs the presenter (\"manual\": move it
   by hand; \"same\": the window to share in a call); an end this window keeps presenting through. Only for the
   presenter: the audience window's screen is the projector's."
  [link]
  (when (= :presenter (:role link))
    (let [{:keys [phase peer waited? swap display reason]} link]
      (case phase
        :opening {:tone :info :text "Opening the audience window…"}
        :ended   (when-let [t (get ended-texts reason)] {:tone :warning :text t})
        :live    (cond
                   (= :waiting peer)
                   {:tone :info
                    :text (if waited?
                            "Waiting for the audience window… It may be behind another window, or on another screen."
                            "Waiting for the audience window…")}

                   (and swap (not (:swapped swap)))
                   {:tone :warning :text (if (= "busy" (:why swap))
                                           "The screens are already being swapped."
                                           "Both windows are on one display.")}

                   (and swap (seq (:lost swap)))
                   {:tone :warning :text (lost-text (:lost swap))}

                   (= "manual" display)
                   {:tone :info :text "Move the audience window to the other screen, then press Shift+F in it."}

                   (= "same" display)
                   {:tone :info :text "One display: share the audience window in a call."}

                   :else nil)
        nil))))

;; ---- what the window sends --------------------------------------------------------------------------------------

(defn outgoing
  "The interceptor's question after the engine's report or a local blank change (ADR-0048 §8.5): does this window
   send a move? `mirror` is [:ui :slides] — the engine's last report plus the UI-owned blank colour. It sends only
   when the link is live, the mirror says the engine is presenting (:presenting? — true once the engine itself
   applied the show's start, so a window's pre-show reports never go out), the layout is :slides, the mirror's path
   is the link's, and the position — the page of a push being shown (:applying) if there is one, else the mirror's
   page, with the mirror's blank colour — differs from :known. → {:link :move}, the link having given the move the
   next mid, added it to :out and made it :known; nil when nothing is sent.

   :applying matters for a blank key pressed while a push is being shown: the mirror still holds the page from
   before the push until the engine reports, and sending that page would send the push back — an echo (theory/11
   §3.2, control N-applying). After a report, call `reported` first: the report ends the push being shown."
  [link mirror]
  (when (and (live? link)
             (true? (:presenting? mirror))
             (= :slides (:layout mirror))
             (= (:path mirror) (:path link)))
    (let [applying (:applying link)
          cur      (position (if (some? applying) applying (:page mirror)) (:blank mirror))]
      (when (and (some? (:page cur)) (not= cur (:known link)))
        (let [mid (:next-mid link)]
          {:link (assoc link :next-mid (inc mid) :out (conj (or (:out link) #{}) mid) :known cur)
           :move {:session (:session link) :mid mid :page (:page cur) :blank (blank->wire (:blank cur))}})))))

(defn reported
  "The engine reported (`report`, the :slides/changed payload): a push being shown is done, so :applying is
   cleared — when the report is of this link's deck (the model's `Report`, theory/11 §4). Run it before `outgoing`
   for a report; never for a blank change. The engine reports after every [:show p], even when the page did not
   change, so the report that clears :applying is the one that tells `outgoing` whether the page snapped.

   Precondition — the model's Report is ONE step: `report` is the engine's page as it is when this event runs. A
   report read on an animation frame but processed after a later [:show p] (a :slides/changed queued behind a
   :slides/link-state) would clear :applying for the wrong show, send the pre-push page, and then send the pushed
   page when the engine reports it: an echo, which can undo the other window's move. So the engine's report must
   reach the link no later than any [:show p] it did not see — dispatched synchronously from its frame callback."
  [link report]
  (if (and (some? (:applying link)) (= (:path report) (:path link)))
    (assoc link :applying nil)
    link))

(defn request-open
  "Shift+S, or View ▸ Present in New Window, in this window — showing deck `path` at `page` with app-db's `blank`
   colour; `fullscreen` is the :slides-fullscreen? preference, which the request carries for the audience window
   (main lets it go fullscreen only on a separate display); the presenter never asks for its own fullscreen, so the
   link does not keep it. → {:link} plus :open, the vv:presenter-open payload, when the request is sent:

     no link, or an ended one     a new :opening link, and the request
     :opening                     unchanged, nothing sent: main has not answered yet, and a second request would be
                                  refused as busy — a refusal that would end this opening
     :live, the presenter         unchanged, and the request again: main re-sends the joined state and raises the
                                  audience window; nothing new opens (ADR-0048 §8.1)
     :joining, or :live audience  unchanged, nothing sent: a window takes part in at most one show"
  [link {:keys [path page blank fullscreen]}]
  (let [request {:path path :page page :blank (blank->wire blank) :fullscreen (boolean fullscreen)}]
    (case (:phase link)
      (:opening :joining) {:link link}
      :live               (if (= :presenter (:role link)) {:link link :open request} {:link link})
      ;; Disabled 2026-10-19 (ADR-0048 §8.1, amended): the opening link kept the presenter's preference for
      ;; on-joined, which asked for the presenter's own fullscreen on a separate display. The presenter window now
      ;; stays windowed — it keeps its chrome, and the show runs in the audience window — so nothing reads it.
      ;; {:link {:phase :opening :role :presenter :path path :fullscreen (boolean fullscreen)}
      {:link {:phase :opening :role :presenter :path path}
       :open request})))

(defn leave
  "This window ends its link: Escape or End Slide Show (`reason` \"stopped\"), or its deck view unmounted
   (\"unmounted\"). → {:link (ended …)} plus :leave, the vv:presenter-leave payload, once the link has a session; an
   :opening link has none yet, so it just ends — main's joined, when it comes, is answered with the leave
   (`on-state`). Without a link, or after it ended, nothing changes."
  [link reason]
  (case (:phase link)
    (:joining :live) {:link (ended link reason false) :leave {:session (:session link) :reason reason}}
    :opening         {:link (ended link reason false)}
    {:link link}))

;; ---- joining ------------------------------------------------------------------------------------------------------

(defn link-join
  "This window was opened for a linked show: vv:open-files carried `link` {session seq role page blank fullscreen}
   for deck `path`. → the :joining audience link, starting at the link's position and seq. Pushes that arrive
   before the deck mounts only move :start (`on-push`)."
  [{session :session link-seq :seq fullscreen :fullscreen :as link} path]
  (let [s (or link-seq 0)]
    {:phase      :joining
     :role       :audience
     :session    session
     :path       path
     :seq        s
     :own-seq    0
     :out        #{}
     :held       nil
     :applying   nil
     :next-mid   1
     :start      {:seq s :pos (wire-position link)}
     :fullscreen (boolean fullscreen)}))

(defn deck-mounted
  "The audience's deck `path` mounted (:pdf/deck-detected, a deck or not — any PDF can be presented): the link goes
   live AT :start, which becomes :known, so the engine's first presenting report sends nothing unless it snapped the
   page (theory/11 §3.4). → {:link :show :ready :fullscreen?}: start the show at (:page show) in the audience view
   with its blank colour, say vv:presenter-ready (it moves nothing), and ask for this window's own fullscreen when
   the link says so. Unchanged unless the link is :joining for `path`."
  [link path]
  (if (and (= :joining (:phase link)) (= path (:path link)))
    (let [{s :seq pos :pos} (:start link)]
      {:link        (-> link
                        (assoc :phase :live :seq (max (:seq link) s) :known pos :applying (:page pos))
                        (dissoc :start))
       :show        pos
       :ready       {:session (:session link)}
       :fullscreen? (boolean (:fullscreen link))})
    {:link link}))

;; ---- what the window receives -----------------------------------------------------------------------------------

(defn show
  "⟨show π⟩ (theory/11 §3.3): this window now shows `pos`, a position main sent. → {:link :show pos}: `pos` is
   :known, and :applying holds its page until the engine reports it. The caller runs the EFFECT (show-effect pos) —
   not the :slides/command event, which would un-blank (ADR-0045 §5) — and sets app-db's blank colour to
   (:blank pos). The engine records no jump history for [:show p] (ADR-0048 §3)."
  [link pos]
  {:link (assoc link :known pos :applying (:page pos)) :show pos})

(defn show-effect
  "The re-frame effect that shows `pos`'s page on the mounted slide show: [:slides/command [:show page]]."
  [pos]
  [:slides/command [:show (:page pos)]])

(defn on-push
  "Main's push {session seq page blank} — another window's move, numbered `seq` — reaches this window. Ignored
   unless it is for this link's session while joining or live. A push not newer than :seq is dropped (the stale
   guard, ADR-0046's rule). A joining window moves :start. A live window shows it when nothing of its own is on the
   way, and HOLDS it otherwise: main ordered it before this window's move or the move's ack will say where it
   stands, and showing it now could take the window back to a slide main has already superseded (theory/11 §3.3)."
  [link msg]
  (let [{s :seq} msg
        pos      (wire-position msg)]
    (cond
      (not (in-session? link msg))     {:link link}
      (<= s (:seq link))               {:link link}
      (= :joining (:phase link))       {:link (assoc link :seq s :start {:seq s :pos pos})}
      (empty? (:out link))             (show (assoc link :seq s) pos)
      :else                            {:link (assoc link :seq s :held {:seq s :pos pos})})))

(defn on-ack
  "Main's ack {session seq ack accepted} of this window's move `ack` (its mid) reaches it. The mid is no longer on
   the way, whatever the answer; an accepted move raises :own-seq and :seq to its number. When nothing of this
   window's is on the way any more, a held push is shown only if its number is above :own-seq — otherwise this
   window's own move, ordered after it, is main's state — and is forgotten either way. Ignored unless it is for this
   live link's session."
  [link msg]
  (if-not (and (live? link) (in-session? link msg))
    {:link link}
    (let [{s :seq mid :ack accepted :accepted} msg
          out  (disj (or (:out link) #{}) mid)
          own  (if accepted (max (:own-seq link) s) (:own-seq link))
          link (assoc link :out out :own-seq own :seq (if accepted (max (:seq link) s) (:seq link)))
          held (:held link)]
      (cond
        (seq out)                     {:link link}
        (and held (> (:seq held) own)) (show (assoc link :held nil) (:pos held))
        :else                         {:link (assoc link :held nil)}))))

(defn on-joined
  "Main's joined message {session seq role page blank joined display} reaches this window, with `mirror` its
   [:ui :slides]. An :opening link goes live at the session's start position, which becomes :known, and the
   interceptor runs once — a key pressed while the link was being made is sent like any other person's move
   (theory/11 §3.4). The presenter never asks for its own fullscreen, on any display: it keeps its chrome and its
   window, and the audience window, which main lets go fullscreen only on a separate display, shows the show
   (ADR-0048 §8.1). A joined for this link's own session again (Shift+S while linked) only refreshes :display. Any
   other joined — this window gave up the link before main answered — is answered with :leave, so main closes the
   audience window it opened."
  [link msg mirror]
  (let [{id :session s :seq display :display} msg]
    (cond
      (= :opening (:phase link))
      (let [live (assoc link :phase :live :session id :seq s :own-seq 0 :known (wire-position msg) :out #{}
                        :held nil :applying nil :next-mid 1 :display display :peer :waiting)]
        ;; Disabled 2026-10-19 (ADR-0048 §8.1, amended): on a separate display the presenter used to ask for its own
        ;; fullscreen here, covering its chrome; the presenter window now stays windowed (F still toggles it).
        ;; (assoc (or (outgoing live mirror) {:link live})
        ;;        :fullscreen? (boolean (and (:fullscreen link) (= "separate" display))))
        (or (outgoing live mirror) {:link live}))

      (in-session? link msg)
      {:link (assoc link :display display)}

      :else
      {:link link :leave {:session id :reason "stopped"}})))

(defn on-ended
  "Main's ended message {session ended stop reason} reaches this window. For this link's session (joining or live)
   — or, while :opening, the \"no-window\" end of the session main had begun for it (the opening window knows no
   session id yet) — the link ends, and :stop? says whether this window's show stops too. Anything else is a late
   message about an older show, and is ignored."
  [link {:keys [stop reason] :as msg}]
  (if (or (in-session? link msg)
          (and (= :opening (:phase link)) (= "no-window" reason)))
    {:link (ended link reason stop) :stop? (true? stop)}
    {:link link}))

(defn on-refused
  "Main refused this window's request {session: nil, refused: reason} — \"busy\", \"not-focused\", \"not-retained\"
   or \"invalid\": an :opening link ends with that reason (the window shows it); otherwise ignored."
  [link {:keys [refused]}]
  (if (= :opening (:phase link))
    {:link (ended link refused false)}
    {:link link}))

(defn on-peer
  "Main says the audience window's deck is showing {session peer: \"live\"}: the presenter's pane drops its waiting
   notice. Ignored unless it is for this live link's session."
  [link msg]
  (if (and (live? link) (in-session? link msg) (= "live" (:peer msg)))
    {:link (assoc link :peer :live)}
    {:link link}))

(defn on-swapped
  "Main's answer to Swap screens — {swapped: true, lost: [role …]} or {swapped: false, why} — kept as :swap for the
   pane. Ignored unless it is for this live link's session."
  [link msg]
  (if (and (live? link) (in-session? link msg))
    {:link (assoc link :swap (select-keys msg [:swapped :lost :why]))}
    {:link link}))

(defn message-kind
  "Which vv:presenter-state message `msg` is (ADR-0048 §8.3): :refused, :ended, :joined, :swapped, :peer, :ack or
   :push — or nil for something else."
  [msg]
  (cond
    (not (map? msg))         nil
    (contains? msg :refused) :refused
    (true? (:ended msg))     :ended
    (true? (:joined msg))    :joined
    (contains? msg :swapped) :swapped
    (contains? msg :peer)    :peer
    (contains? msg :ack)     :ack
    (contains? msg :page)    :push
    :else                    nil))

(defn on-state
  "Message `msg` arrived on vv:presenter-state (the event :slides/link-state; `mirror` is [:ui :slides]). Routes it
   by `message-kind`; anything unknown leaves the link as it is. The interceptor does not run after this event:
   following never sends — only an opening link's joined runs it, once (`on-joined`)."
  [link msg mirror]
  (case (message-kind msg)
    :push    (on-push link msg)
    :ack     (on-ack link msg)
    :joined  (on-joined link msg mirror)
    :peer    (on-peer link msg)
    :swapped (on-swapped link msg)
    :ended   (on-ended link msg)
    :refused (on-refused link msg)
    {:link link}))
