(ns vinary.app.slides-link-sim-test
  "A randomized simulation of the linked slide show (docs/theory/11 §4, §6 and §8). A seeded, deterministic PRNG
   drives the two windows' pure link models (vinary.app.slides-link) and main's pure sequencer
   (vinary.main.slides-link) through random interleavings of the people's moves (pages and blank colours), the
   engines' reports, the audience's mount, the windows learning their hidden pages at moments of their own (snaps,
   always downward, the two windows disagreeing for a while), the presenter leaving its deck and coming back
   (theory/11 §3.5: link/away and link/resume), and the deliveries — over FIFO and reordering channels.

   It plays the TLA+ model's part with the real code: the engine, app-db's mirror and the channels are simulated
   here, exactly as LinkedShow.tla describes them; every protocol decision is the code's. The model's ghost
   variables — pcause, echo, lastUserPos, nonUser — are computed here, never in the code. Every step checks P2 (no
   echo: no move sent for no cause, and no received message makes a window send), P4 (monotone numbers), P6
   (bounded corrections) and the bookkeeping of Lemma 1; every quiescent state checks P1 (convergence) and, when no
   window ever snaps, P3 (no undo); a run that stops short of quiescence, or does not stop, violates P5.

   The nine negative controls of theory/11 §6.3 are re-implemented here, each by wrapping one pure function so
   that one rule is gone. Each must find a violation of the property its rule protects: a control that passes, or
   fails on another property, would mean the simulation does not test what it claims to."
  (:require [cljs.test :refer-macros [deftest is]]
            [vinary.app.slides-link :as link]
            [vinary.main.slides-link :as main]))

(def ^:private deck "/d/talk.pdf")
(def ^:private presenter 101)
(def ^:private audience 202)
(def ^:private windows [presenter audience])

(def ^:private step-cap
  "Far more steps than a downward run can take (theory/11 Theorem 6 bounds them: a few hundred here); a run still
   going at the cap does not settle (P5)."
  4000)

;; ---- a seeded PRNG ------------------------------------------------------------------------------------------------
;; mulberry32: one 32-bit word of state and integer arithmetic only, so a seed replays the same run on every machine
;; and every run — a failure message's seed and trace are a reproduction. Math.random is never used.

(defn- next-unit
  "Advance the generator: `state` → [state' u], u uniform in [0, 1)."
  [state]
  (let [a (bit-or (+ state 0x6D2B79F5) 0)
        t (js/Math.imul (bit-xor a (unsigned-bit-shift-right a 15)) (bit-or a 1))
        t (bit-xor t (+ t (js/Math.imul (bit-xor t (unsigned-bit-shift-right t 7)) (bit-or t 61))))]
    [a (/ (unsigned-bit-shift-right (bit-xor t (unsigned-bit-shift-right t 14)) 0) 4294967296)]))

(defn- draw
  "A uniform integer in [0, n) from the world's generator → [world' i]."
  [world n]
  (let [[state u] (next-unit (:rng world))]
    [(assoc world :rng state) (js/Math.floor (* u n))]))

;; ---- snaps --------------------------------------------------------------------------------------------------------
;; A snap is a vector: (nth snap p) is the page a window shows for page p. A window shows every page until it learns
;; its hidden pages (a Rehide); each Rehide puts its next snap in force.

(def ^:private hides-1-down [0 0 2])   ; LinkedShow.tla's SnapHide1Down over {0, 1, 2}: page 1 shows page 0
(def ^:private hides-last   [0 1 1])   ; SnapLast over {0, 1, 2}: page 2 shows page 1
(def ^:private hides-1-up   [0 2 2])   ; SnapHide1Up: page 1 shows page 2 — UPWARD, which ADR-0048 §3 forbids

(defn- shown-page
  "The page window `win` shows for page `p`: its snap's once it knows its hidden pages, `p` itself before."
  [win p]
  (if-let [s (:snap win)] (nth s p) p))

(defn- random-snap
  "A random DOWNWARD snap over `pages` pages: page 0 is always shown, every other page is hidden with probability
   1/3, and a hidden page shows the nearest shown page below it — so S(p) ≤ p and S(S(p)) = S(p) (theory/11 §2)."
  [world pages]
  (loop [world world, p 1, s [0]]
    (if (= p pages)
      [world s]
      (let [[world i] (draw world 3)]
        (recur world (inc p) (conj s (if (zero? i) (peek s) p)))))))

;; ---- configurations -----------------------------------------------------------------------------------------------

(def ^:private fifo    {:name "fifo"    :pages 2 :max-user 3 :reorder? false :blanks [nil :black]})
(def ^:private reorder {:name "reorder" :pages 2 :max-user 3 :reorder? true  :blanks [nil :black]})
;; the presenter may leave its deck and come back, twice (LinkedShow.tla's MaxAway; every other configuration: 0)
(def ^:private away-fifo (assoc fifo :name "away-fifo" :max-away 2))

(def ^:private configurations
  "theory/11 §6.1's six configurations (same pages, people's actions, delivery and snaps; blanks none and black), and
   random ones. :runs is how many seeds each explores; :expect names the rules a configuration must exercise at
   least once over its runs, so that a pass is not vacuous (see `win-recv` and `main-recv` for the counters)."
  [(assoc fifo :runs 300 :expect #{:held :held-discarded})
   (assoc reorder :runs 300 :expect #{:held :held-shown :held-discarded :stale-dropped :not-accepted})
   {:name "snap-fifo" :pages 3 :max-user 3 :reorder? false :blanks [nil :black]
    :snaps {presenter [hides-1-down] audience [hides-last]} :runs 300 :expect #{:corrections :held}}
   {:name "snap-reorder" :pages 3 :max-user 3 :reorder? true :blanks [nil :black]
    :snaps {presenter [hides-1-down] audience [hides-last]} :runs 300
    :expect #{:corrections :held-shown :stale-dropped}}
   (assoc away-fifo :runs 300 :expect #{:away :remounted :away-recorded})
   (assoc reorder :name "away-reorder" :max-away 2 :runs 300
          :expect #{:away :remounted :away-recorded :held-shown :stale-dropped})
   {:name "away-snap-fifo" :pages 3 :max-user 3 :reorder? false :blanks [nil :black] :max-away 2
    :snaps {presenter [hides-1-down] audience [hides-last]} :runs 300 :expect #{:away :remounted :corrections}}
   {:name "fifo-deep" :pages 3 :max-user 5 :reorder? false :blanks [nil :black] :runs 200 :expect #{:held}}
   {:name "reorder-deep" :pages 3 :max-user 4 :reorder? true :blanks [nil :black] :runs 200
    :expect #{:held-shown :not-accepted}}
   ;; 2–7 pages, 2–6 people's actions, either delivery order, a random start page, three blank colours, up to two
   ;; random downward snaps per window, each learned at a random moment — before the audience mounts, too — and the
   ;; presenter leaving its deck up to twice.
   {:name "random" :random? true :runs 600
    :expect #{:corrections :deferred-blanks :held-shown :not-accepted :away :remounted}}])

(defn- randomize
  "A random configuration for one run of `config` → [world' config']."
  [world config]
  (let [[world pages]    (draw world 6)
        pages            (+ 2 pages)
        [world max-user] (draw world 5)
        [world order]    (draw world 2)
        [world start]    (draw world pages)
        [world snaps]    (reduce (fn [[world snaps] w]
                                   (let [[world n] (draw world 3)]
                                     (loop [world world, k 0, ss []]
                                       (if (= k n)
                                         [world (assoc snaps w ss)]
                                         (let [[world s] (random-snap world pages)]
                                           (recur world (inc k) (conj ss s)))))))
                                 [world {}] windows)
        [world max-away] (draw world 3)]
    [world (assoc config :pages pages :max-user (+ 2 max-user) :reorder? (= 1 order) :start-page start
                  :blanks [nil :black :white] :snaps snaps :max-away max-away)]))

;; ---- the world ----------------------------------------------------------------------------------------------------
;; {:rng :config :seed :session :main
;;  :wins    {wc window}     a window: {:link — the code's link · :engine — its page · :pending? — a report is due ·
;;                           :mirror — the last report's page (nil before the first) · :blank — app-db's colour ·
;;                           :snap · :rehides — the snaps it will learn, in order · :cause — the ghost pcause
;;                           (:none :user :snap :announce) · :sent — the moves it sent}
;;  :to-main {wc [{:kind :move|:ready :payload … :user? …}]}    :user? is the model's ghost flag on a move
;;  :to-win  {wc [message …]}                                    main's vv:presenter-state messages
;;  :users :rehides-done :aways                                  the people's actions, Rehides and departures so far
;;  :ghost   {:echo? :last-user-pos :non-user}                   theory/11 §4's ghosts
;;  :seen    {wc [seq own-seq]}                                  for P4
;;  :steps :trace :coverage}

(defn- mirror
  "Window `win`'s [:ui :slides]: the engine's last report — presenting once its engine has reported at all — and
   app-db's blank colour."
  [win]
  {:path deck :layout :slides :presenting? (some? (:mirror win)) :page (:mirror win) :blank (:blank win)})

(defn- initial-world
  "The model's Init (theory/11 §4, §7): main has claimed the audience window, whose vv:open-files {link} has arrived
   — it is joining — and the presenter has had its joined message: it is live at the start, nothing due."
  [config seed]
  (let [world0         {:rng seed}
        [world config] (if (:random? config) (randomize world0 config) [world0 config])
        p0             (:start-page config 0)
        opened         (main/open main/empty-state presenter {:path deck :page p0 :blank nil :fullscreen true}
                                  {:focused? true :retains? true})
        id             (get-in opened [:claim :session])
        claimed        (main/claimed (:state opened) id audience {:display "separate"})
        a-link         (:link (link/on-state (:link (link/request-open nil {:path deck :page p0 :fullscreen true}))
                                             (second (first (:out claimed)))
                                             {:path deck :layout :slides :presenting? true :page p0 :blank nil}))
        snaps          (:snaps config {})
        window         (fn [w lk mirrored]
                         {:link lk :engine p0 :pending? false :mirror mirrored :blank nil :snap nil
                          :rehides (get snaps w []) :cause :none :sent 0})]
    (assoc world
           :config       config
           :seed         seed
           :session      id
           :main         (:state claimed)
           :wins         {presenter (window presenter a-link p0)
                          audience  (window audience (link/link-join (:link claimed) deck) nil)}
           :to-main      {presenter [] audience []}
           :to-win       {presenter [] audience []}
           :users        0
           :rehides-done 0
           :aways        0
           :check-undo?  (every? empty? (vals snaps))   ; P3 holds only when no window snaps (theory/11 §5.3)
           :ghost        {:echo? false :last-user-pos {:page p0 :blank nil} :non-user 0}
           :seen         {presenter [0 0] audience [0 0]}
           :steps        0
           :trace        []
           :coverage     {})))

(defn- tally [world k] (update-in world [:coverage k] (fnil inc 0)))
(defn- controls [world] (get-in world [:config :controls] #{}))

;; ---- the people, the engines, the windows' sends ------------------------------------------------------------------

(defn- send-move
  "Window `w`'s interceptor sent `move`, for `cause` — the ghost pcause: why its engine or colour differed from
   :known. A move sent for no cause is an echo (P2); one sent for a snap is a correction."
  [world w move cause]
  (cond-> (-> world
              (update-in [:to-main w] conj {:kind :move :payload move :user? (= :user cause)})
              (update-in [:wins w :sent] inc))
    (= :none cause) (assoc-in [:ghost :echo?] true)
    (= :snap cause) (tally :corrections)))

(defn- user-page
  "A person moves window `w`'s engine to page `q` (a key, a click, the overview, a link, find): a report is due,
   and the person is its cause."
  [world w q]
  (-> world
      (update :users inc)
      (update-in [:wins w] assoc :engine q :pending? true :cause :user)))

(defn- user-blank
  "A person blanks or unblanks window `w`: app-db takes colour `b` at once, and the interceptor runs in the same
   event. N-applying: the interceptor reads the stale mirror page instead of the page being shown.

   A window whose engine has not presented yet — the audience between its mount and its first report — sends nothing
   now: `outgoing` waits for :presenting? (ADR-0048 §8.5). The report that is due then carries the colour, so that
   report's cause becomes the person's. (The TLA+ model sends at once; both are a person's move, and neither echoes.)"
  [world w b]
  (let [win   (assoc (get-in world [:wins w]) :blank b)
        lk    (:link win)
        shown (mirror win)
        sent  (link/outgoing (cond-> lk (contains? (controls world) :no-applying) (assoc :applying nil)) shown)
        world (update world :users inc)]
    (cond
      sent
      (-> world
          (assoc-in [:wins w] (assoc win :link (assoc (:link sent) :applying (:applying lk))))
          (send-move w (:move sent) :user))

      (not (:presenting? shown))
      (-> world (assoc-in [:wins w] (assoc win :cause :user)) (tally :deferred-blanks))

      :else
      (assoc-in world [:wins w] win))))

(defn- report
  "Window `w`'s engine reports (:slides/changed, then the interceptor slides-link-out): the mirror takes the
   engine's page, `reported` ends a push being shown, and `outgoing` sends when the position differs from :known —
   for the cause the window had. LinkedShow.tla's Report is one step, the engine read and the interceptor together;
   so is this one, which is link/reported's precondition on the code that delivers the engine's reports."
  [world w]
  (let [win   (get-in world [:wins w])
        cause (:cause win)
        win   (assoc win :mirror (:engine win) :pending? false :cause :none)
        lk    (link/reported (:link win) {:path deck :page (:engine win)})
        sent  (link/outgoing lk (mirror win))]
    (cond-> (assoc-in world [:wins w] (assoc win :link (if sent (:link sent) lk)))
      sent (send-move w (:move sent) cause))))

(defn- mount
  "The audience's deck mounts (:pdf/deck-detected while joining): its show starts at :start — app-db takes the
   colour, the engine snaps the page — and the window says ready, which moves nothing. N-announce: :known is
   forgotten instead, so the first report announces the start as a move, and nothing says ready."
  [world]
  (let [announce? (contains? (controls world) :announce)
        win       (get-in world [:wins audience])
        mounted   (link/deck-mounted (:link win) deck)
        pos       (:show mounted)
        page      (shown-page win (:page pos))]
    (cond-> (assoc-in world [:wins audience]
                      (assoc win
                             :link     (cond-> (:link mounted) announce? (assoc :known nil))
                             :blank    (:blank pos)
                             :engine   page
                             :pending? true
                             :cause    (cond announce? :announce (not= page (:page pos)) :snap :else :none)))
      (not announce?) (update-in [:to-main audience] conj {:kind :ready :payload (:ready mounted)}))))

(defn- rehide
  "Window `w` learns which pages it hides (its deck model is built, a background scan ends, a refresh changes the
   pages): its next snap is in force, and a live engine on a page it now hides snaps — a report is due, for the person
   if a person's report was due, else for the snap (LinkedShow.tla's Rehide)."
  [world w]
  (let [win  (get-in world [:wins w])
        s    (first (:rehides win))
        win  (assoc win :snap s :rehides (subvec (:rehides win) 1))
        page (nth s (:engine win))
        ;; an away window has no engine to snap (LinkedShow.tla's Rehide: phase live only)
        win  (if (and (link/live? (:link win)) (not (link/away? (:link win))) (not= page (:engine win)))
               (assoc win :engine page :pending? true :cause (if (= :user (:cause win)) :user :snap))
               win)]
    (-> world (assoc-in [:wins w] win) (update :rehides-done inc))))

;; ---- deliveries ---------------------------------------------------------------------------------------------------

(defn- next-index
  "Which message of channel `ch` arrives next: the first (FIFO), or any one (a reordering channel)."
  [world ch]
  (if (get-in world [:config :reorder?]) (draw world (count ch)) [world 0]))

(defn- without [v i] (into (subvec v 0 i) (subvec v (inc i))))

(defn- main-move
  "Main's sequencer takes window `w`'s move. N-midguard: the window's last accepted mid is forgotten for this one
   move, then kept as the larger of the two (LinkedShow.tla's Max(lastMid, mid))."
  [world w payload]
  (if (contains? (controls world) :no-mid-guard)
    (let [path [:sessions (:session world) :last-mid w]
          old  (get-in (:main world) path)
          r    (main/move (assoc-in (:main world) path 0) w payload)]
      (update r :state (fn [s] (assoc-in s path (max old (get-in s path))))))
    (main/move (:main world) w payload)))

(defn- main-recv
  "Main takes the next message from window `w` — a move or a ready — and sends what its sequencer answers. An
   accepted move updates the ghosts: the last person's move main accepted, or one more move no person made."
  [world w]
  (let [[world i]                    (next-index world (get-in world [:to-main w]))
        {:keys [kind payload user?]} (get-in world [:to-main w i])
        world                        (update-in world [:to-main w] without i)
        id                           (:session world)
        before                       (:seq (main/session (:main world) id))
        {s :state out :out}          (case kind
                                       :move  (main-move world w payload)
                                       :ready (main/ready (:main world) w payload))
        world                        (reduce (fn [world [target msg]] (update-in world [:to-win target] conj msg))
                                             (assoc world :main s) out)]
    (cond
      (= before (:seq (main/session s id))) (cond-> world (= :move kind) (tally :not-accepted))
      user?                                 (assoc-in world [:ghost :last-user-pos] (select-keys payload [:page :blank]))
      :else                                 (update-in world [:ghost :non-user] inc))))

(defn- take-message-rules
  "Window link `lk` takes main's message `msg`: through link/on-state, or, under a control `cs` names, through the
   wrapper that removes its one rule."
  [cs lk msg shown]
  (case (link/message-kind msg)
    :push (cond
            ;; N-hold: show a push even while my own move is on the way
            (contains? cs :no-hold)
            (assoc-in (link/on-push (assoc lk :out #{}) msg) [:link :out] (:out lk))
            ;; N-staleguard: take a push even when it is not newer than what I have seen
            (contains? cs :no-stale-guard)
            (let [r (link/on-push (assoc lk :seq -1) msg)]
              (assoc-in r [:link :seq] (max (:seq lk) (get-in r [:link :seq]))))
            :else
            (link/on-state lk msg shown))
    :ack  (if (and (contains? cs :no-held-check) (:held lk))
            ;; N-heldcheck: show the held push without comparing it to my own last accepted move
            (let [r (link/on-ack (assoc-in lk [:held :seq] ##Inf) msg)]
              (cond-> r (get-in r [:link :held]) (assoc-in [:link :held] (:held lk))))
            (link/on-state lk msg shown))
    (link/on-state lk msg shown)))

(defn- take-message
  "Window link `lk` takes main's message `msg` (take-message-rules). N-awaydrop: an away link forgets the position a
   message showed it — its :known stays as it was."
  [world lk msg shown]
  (let [cs (controls world)
        r  (take-message-rules cs lk msg shown)]
    (if (and (contains? cs :away-drop) (link/away? lk))
      (assoc-in r [:link :known] (:known lk))
      r)))

(defn- show-position
  "⟨show π⟩ in the engine and app-db (LinkedShow.tla's Apply): app-db takes the colour at once, the engine is told
   [:show page] and snaps it, and a report is due — for the snap when it moved the page, else for nothing."
  [win pos]
  (let [page (shown-page win (:page pos))]
    (assoc win :blank (:blank pos) :engine page :pending? true :cause (if (= page (:page pos)) :none :snap))))

(defn- win-recv
  "Window `w` takes its next message from main; a shown position runs ⟨show π⟩. A received message never makes a
   window send — following never speaks — so a :move (or any other send) here counts as an echo (P2)."
  [world w]
  (let [[world i] (next-index world (get-in world [:to-win w]))
        msg       (get-in world [:to-win w i])
        world     (update-in world [:to-win w] without i)
        win       (get-in world [:wins w])
        lk        (:link win)
        r         (take-message world lk msg (mirror win))
        lk'       (:link r)
        kind      (link/message-kind msg)]
    (cond-> (assoc-in world [:wins w] (cond-> (assoc win :link lk') (:show r) (show-position (:show r))))
      (or (:move r) (:leave r) (:open r) (:ready r))
      (assoc-in [:ghost :echo?] true)

      (and (= :push kind) (<= (:seq msg) (:seq lk)))
      (tally :stale-dropped)

      (and (= :push kind) (some? (:held lk')) (not= (:held lk) (:held lk')))
      (tally :held)

      (and (= :ack kind) (:held lk) (:show r))
      (tally :held-shown)

      (and (= :ack kind) (:held lk) (nil? (:show r)) (nil? (:held lk')))
      (tally :held-discarded)

      (and (link/away? lk) (not= (:known lk) (:known lk')))
      (tally :away-recorded))))

(defn- unmount
  "The presenter's deck view unmounts while its link is live — its tab shows another document (theory/11 §3.5,
   LinkedShow.tla's Unmount) — through the code's link/away: the link stays, away; the report that was due, the mirror
   and :applying are gone (app-db's colour stays, as the model's blank does); nothing is sent."
  [world]
  (let [win (get-in world [:wins presenter])]
    (-> world
        (update :aways inc)
        (assoc-in [:wins presenter] (assoc win :link (:link (link/away (:link win))) :pending? false :mirror nil
                                               :cause :none))
        (tally :away))))

(defn- remount
  "The presenter's deck mounts again in the show's view (LinkedShow.tla's Remount), through the code's link/resume:
   the engine opens at known's page — snapped, if the window hides it — with known's blank colour, and a report is
   due: for the snap when it moved the page, else for nothing. Nothing is sent. N-staleremount: the engine reopens
   where it was when the window went away."
  [world]
  (let [win    (get-in world [:wins presenter])
        r      (link/resume (:link win))
        pos    (:show r)
        stale? (contains? (controls world) :stale-remount)
        page   (shown-page win (if stale? (:engine win) (:page pos)))]
    (-> world
        (assoc-in [:wins presenter]
                  (assoc win :link (:link r) :blank (:blank pos) :engine page :pending? true
                             :cause (if (and (not stale?) (not= page (:page pos))) :snap :none)))
        (tally :remounted))))

;; ---- choosing the next step ---------------------------------------------------------------------------------------

(defn- people
  "The people's actions possible now: while fewer than :max-user were taken, each live window may move to a page
   it shows, other than its engine's, or change its blank colour."
  [world]
  (let [{:keys [pages blanks max-user]} (:config world)]
    (when (< (:users world) max-user)
      (into []
            (mapcat (fn [w]
                      (let [win (get-in world [:wins w])]
                        ;; an away presenter has no engine, nor a show on screen to blank
                        (when (and (link/live? (:link win)) (not (link/away? (:link win))))
                          (concat (for [q (range pages)
                                        :when (and (not= q (:engine win)) (= q (shown-page win q)))]
                                    [:user-page w q])
                                  (for [b blanks :when (not= b (:blank win))]
                                    [:user-blank w b]))))))
            windows))))

(defn- rehides
  "Each window that has not yet learned all its snaps may learn the next one."
  [world]
  (into [] (comp (filter (fn [w] (seq (get-in world [:wins w :rehides])))) (map (fn [w] [:rehide w]))) windows))

(defn- departures
  "The presenter leaving its deck — fewer than :max-away times so far — or, while away, coming back (theory/11 §3.5's
   Unmount and Remount). A person does both: like the people's actions, they need not happen at all."
  [world]
  (let [lk (get-in world [:wins presenter :link])]
    (cond
      (link/away? lk)                                                                [[:remount presenter]]
      (and (link/live? lk) (< (:aways world) (get-in world [:config :max-away] 0))) [[:unmount presenter]]
      :else                                                                          [])))

(defn- protocol
  "The protocol's actions possible now (weakly fair in the model): a due report, the audience's mount, and a delivery
   on each channel that holds a message."
  [world]
  (reduce (fn [acts w]
            (let [win (get-in world [:wins w])]
              (cond-> acts
                (and (link/live? (:link win)) (:pending? win)) (conj [:report w])
                (= :joining (:phase (:link win)))              (conj [:mount w])
                (seq (get-in world [:to-main w]))              (conj [:main-recv w])
                (seq (get-in world [:to-win w]))               (conj [:win-recv w]))))
          [] windows))

(defn- choose
  "The next step → [world' action]: a person's about 40% of the time while any is possible, a window learning its
   hidden pages about 5% of the time, the presenter leaving or coming back about 7% of the time, else a protocol
   step — falling back to whatever is possible; nil when nothing is. Every interleaving has a positive probability.
   A configuration whose presenter never leaves (:max-away 0) chooses exactly as before departures existed."
  [world]
  (let [ppl       (people world)
        rh        (rehides world)
        dep       (departures world)
        prot      (protocol world)
        [world u] (draw world 100)
        pool      (cond
                    (and (seq ppl) (< u 40)) ppl
                    (and (seq rh) (< u 45))  rh
                    (and (seq dep) (< u 52)) dep
                    (seq prot)               prot
                    (seq ppl)                ppl
                    (seq rh)                 rh
                    (seq dep)                dep)]
    (if pool
      (let [[world i] (draw world (count pool))] [world (nth pool i)])
      [world nil])))

(defn- perform [world [kind w x :as action]]
  (-> (case kind
        :user-page  (user-page world w x)
        :user-blank (user-blank world w x)
        :report     (report world w)
        :mount      (mount world)
        :rehide     (rehide world w)
        :unmount    (unmount world)
        :remount    (remount world)
        :main-recv  (main-recv world w)
        :win-recv   (win-recv world w))
      (update :steps inc)
      (update :trace conj action)))

;; ---- the properties -----------------------------------------------------------------------------------------------

(defn- main-position [world] (select-keys (main/session (:main world) (:session world)) [:page :blank]))

(defn- quiescent?
  "Both windows live, no report due, nothing of theirs unacknowledged, and every channel empty (theory/11 §2)."
  [world]
  (every? (fn [w]
            (let [win (get-in world [:wins w])]
              (and (link/live? (:link win)) (not (:pending? win)) (empty? (:out (:link win)))
                   (empty? (get-in world [:to-main w])) (empty? (get-in world [:to-win w])))))
          windows))

(defn- converged?
  "P1: each window last exchanged main's position with main, and each window showing the deck shows it — an away
   presenter shows it when it comes back (its Remount opens at :known)."
  [world]
  (let [{:keys [page blank]} (main-position world)
        target               (link/position page (link/wire->blank blank))]
    (every? (fn [w]
              (let [win (get-in world [:wins w])]
                (and (= target (:known (:link win)))
                     (or (link/away? (:link win)) (= target (link/position (:engine win) (:blank win)))))))
            windows)))

(defn- in-flight-mids
  "The mids of window `w`'s moves on their way to main and of the acks on their way back."
  [world w]
  (-> #{}
      (into (keep (fn [{:keys [kind payload]}] (when (= :move kind) (:mid payload)))) (get-in world [:to-main w]))
      (into (keep :ack) (get-in world [:to-win w]))))

(defn- bookkeeping-violation
  "Lemma 1 (theory/11 §5.1) for window `w`: the mids sent are 1 … next-mid − 1; :out is exactly the mids in flight;
   nothing is held while nothing is on the way; a joining window has sent nothing and receives only pushes."
  [world w]
  (let [win (get-in world [:wins w])
        lk  (:link win)]
    (cond
      (not= (dec (:next-mid lk)) (:sent win))     {:property :lemma-1.1 :window w}
      (not= (:out lk) (in-flight-mids world w))   {:property :lemma-1.2 :window w}
      (and (empty? (:out lk)) (some? (:held lk))) {:property :lemma-1.3 :window w}
      (and (= :joining (:phase lk))
           (or (seq (get-in world [:to-main w]))
               (not-every? #(= :push (link/message-kind %)) (get-in world [:to-win w]))))
      {:property :lemma-1.4 :window w})))

(defn- violation
  "The first property state `world` violates, or nil. In every state: P2, P4, P6 and Lemma 1. In a quiescent state:
   P1, and P3 when no window ever snaps. P6's bound is theory/11 Theorem 5's, counting the roots so far — the
   people's actions, the one mount, the Rehides and the presenter's departures (each a Remount to come) — times the
   largest page index."
  [world]
  (let [{:keys [ghost config users rehides-done aways]} world
        bound (* (+ users 1 rehides-done aways) (dec (:pages config)))]
    (or (when (:echo? ghost)
          {:property :P2 :why "a move was sent for no cause"})
        (some (fn [w]
                (let [lk (get-in world [:wins w :link]) [s own] (get-in world [:seen w])]
                  (when (or (< (:seq lk) s) (< (:own-seq lk) own))
                    {:property :P4 :window w})))
              windows)
        (when (> (:non-user ghost) bound)
          {:property :P6 :non-user (:non-user ghost) :bound bound})
        (some #(bookkeeping-violation world %) windows)
        (when (quiescent? world)
          (cond
            (not (converged? world))
            {:property :P1 :main (main-position world)
             :windows (into {} (map (fn [w] [w (select-keys (get-in world [:wins w]) [:engine :blank])])) windows)}

            (and (:check-undo? world) (not= (main-position world) (:last-user-pos ghost)))
            {:property :P3 :main (main-position world) :last-user-pos (:last-user-pos ghost)})))))

(defn- seen [world]
  (into {} (map (fn [w] (let [lk (get-in world [:wins w :link])] [w [(:seq lk) (:own-seq lk)]]))) windows))

(defn- run
  "One run of `config` from `seed`, to its end → the final world; :violation when a property failed (P5 when the
   run stopped short of quiescence, or did not stop)."
  [config seed]
  (loop [world (initial-world config seed)]
    (if-let [v (violation world)]
      (assoc world :violation v)
      (let [world (cond-> (assoc world :seen (seen world)) (quiescent? world) (tally :quiescent))]
        (if (>= (:steps world) step-cap)
          (assoc world :violation {:property :P5 :why "still exchanging moves at the step cap"})
          (let [[world action] (choose world)]
            (cond
              action             (recur (perform world action))
              (quiescent? world) world
              :else              (assoc world :violation {:property :P5 :why "stuck short of quiescence"}))))))))

(defn- seed-of
  "The generator's seed for run `n` of the configuration named `config-name`: a distinct stream per run."
  [config-name n]
  (bit-or (+ (hash config-name) (* n 0x9E3779B9)) 0))

(defn- explore
  "Runs 1 … `runs` of `config` → {:runs :coverage :violation}: the first violation, with its run's seed, length and
   trace (a reproduction), or none; and the counters summed over the runs before it."
  [config runs]
  (loop [n 1, coverage {}]
    (if (> n runs)
      {:runs runs :coverage coverage}
      (let [world (run config (seed-of (:name config) n))]
        (if-let [v (:violation world)]
          {:runs      n
           :coverage  coverage
           :violation (assoc v :config (:name config) :seed (:seed world) :steps (:steps world)
                               :trace (:trace world))}
          (recur (inc n) (merge-with + coverage (:coverage world))))))))

;; ---- the tests ----------------------------------------------------------------------------------------------------

(deftest the-protocol-keeps-its-promises-in-every-configuration
  (doseq [{:keys [name runs expect] :as config} configurations]
    (let [{:keys [violation coverage]} (explore config runs)]
      (is (nil? violation) (str name ": " (pr-str violation)))
      (is (pos? (:quiescent coverage 0)) (str name ": no quiescent state was checked"))
      (doseq [k expect]
        (is (pos? (get coverage k 0)) (str name ": no run exercised " k " — " (pr-str coverage)))))))

(def ^:private negative-controls
  "theory/11 §6.3: each control removes one rule from the configuration it names, and must break the property that
   rule protects (theory/11 §5.5)."
  [{:control "N-hold"       :config (assoc fifo :controls #{:no-hold})           :violates :P1}
   {:control "N-heldcheck"  :config (assoc fifo :controls #{:no-held-check})     :violates :P1}
   {:control "N-applying"   :config (assoc fifo :controls #{:no-applying})       :violates :P2}
   {:control "N-staleguard" :config (assoc reorder :controls #{:no-stale-guard}) :violates :P1}
   {:control "N-midguard"   :config (assoc reorder :controls #{:no-mid-guard})   :violates :P1}
   {:control "N-announce"   :config (assoc fifo :controls #{:announce})          :violates :P3}
   ;; the presenter leaves its deck and comes back: reopening where its engine was, or forgetting what it was shown
   ;; while away
   {:control "N-staleremount" :config (assoc away-fifo :controls #{:stale-remount}) :violates :P2}
   {:control "N-awaydrop"     :config (assoc away-fifo :controls #{:away-drop})     :violates :P1}
   ;; neg-upsnap: pages {0, 1, 2}, one person's action, FIFO, the presenter snapping page 1 UP to 2 while the
   ;; audience snaps page 2 down to 1 — each window's correction makes the other snap back, for ever
   {:control "N-upsnap"     :config {:pages 3 :max-user 1 :reorder? false :blanks [nil :black]
                                     :snaps {presenter [hides-1-up] audience [hides-last]}}
    :violates :P6}])

(def ^:private control-runs
  "The most seeds a control may need to find its violation."
  2000)

(deftest each-negative-control-breaks-the-property-its-rule-protects
  (doseq [{:keys [control config violates]} negative-controls]
    (let [{:keys [violation runs]} (explore (assoc config :name control) control-runs)]
      (is (some? violation) (str control ": no violation in " runs " runs"))
      (is (= violates (:property violation)) (str control ": " (pr-str (dissoc violation :trace)))))))
