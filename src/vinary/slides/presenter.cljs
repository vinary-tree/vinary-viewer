(ns vinary.slides.presenter
  "The presenter view's pane, timer, counter and pace (ADR-0048 §6.1 and §6.5, decisions P1–P4): pure and DOM-free,
   with pdfpc's semantics. `pane` is what the pane shows for a slide — the counter, the notes regions and the next
   slide — from the engine's deck and notes model. No js/Date, no DOM, no re-frame — TIME IS AN INPUT, so what the status strip shows is a function of
   data, and the tests never depend on the machine's clock or time zone. The pdfpc sources are v4.7.0 (master
   0524719d leaves these files unchanged): timer.vala (TIMER), presentation_controller.vala (CTRL) and
   window/presenter.vala (PRES); line numbers refer to them.

   Vocabulary:
     now             ms since the Unix epoch on the caller's wall clock (Date.now()) — every instant here is one,
                     as time_t is pdfpc's. A clock that is set back or forward moves the timer, as it moves pdfpc's.
     local midnight  an instant at which the LOCAL wall clock read 00:00:00.000, in the UTC offset in force at a
                     reference instant: that instant minus its local time of day (`local-midnight-at`). It places a
                     time of day \"HH:MM\" (`parse-hhmm`) — the one place where the time zone matters.
     settings        pdfpc's, as parsed (ADR-0048 §5): {:duration-min :start-time :end-time :last-minutes} —
                     Duration (minutes), StartTime and EndTime (\"HH:MM\", local) and LastMinutes (default 5)
     schedule        what the settings mean when the show starts (`schedule`)
     timer           one window's timer state, made of EVENTS only — when the show started, when the presenter first
                     left its first slide, a restart (`show-timer`); the talk's start and all that is shown derive
     D               the talk's duration in whole seconds; with one the timer counts down, without one it counts up
     t               the talk's elapsed time in whole seconds
     u, e            the current frame and pdfpc's end slide (else the last frame), both 0-based (deck/position)

   Rounding. Milliseconds become whole seconds in one place each, so a phase or the pace changes exactly when the
   shown second does (pdfpc counts whole seconds too):
     t           floor((now - talk start) / 1000), never below 0 — a stopwatch ticks once a whole second has passed
     count-down  D - t: 00:00:01 through the talk's last second, 00:00:00 at exactly D, -00:00:01 a second later
     pre-talk    -ceil((scheduled start - now) / 1000): -00:00:01 through its last second, never -00:00:00
     pace words  whole minutes, rounded down (`pace`)

   Where this departs from pdfpc, deliberately:
     · a malformed time is no time. pdfpc ignores sscanf's count and checks no range, so its \"10\" is 10:00 and
       its \"noon\" midnight; here both fields must convert, on a 24-hour clock, or the time is absent
     · the talk starts AT the scheduled start (ADR-0048 §6.5), even one that passed before the show began (an end
       time and a duration that no longer fit). pdfpc starts at its first one-second tick after that instant, so a
       late launch gets a FULL duration and overruns the end time; here the count-down still ends at the end time
     · Restart always starts the talk now (ADR-0048 §6.5). pdfpc's Ctrl+T reset returns to the pre-talk before a
       start time, does nothing after one, and stops the timer without one (TIMER 264-282)
     · the phase AND the pace are both given, in words. pdfpc colours the pace by default and shows its last
       minutes only with pace colouring off (CTRL 378-401). As in its first-match order, there is no pace before
       the talk or over time
     · the pace compares with the exact expected time; pdfpc truncates it to a whole second first (CTRL 386), which
       moves the ahead edge by less than a second when the expected time is not whole
     · End goes on from the end slide to the last page; pdfpc's End always returns to the end slide (CTRL 1731-1736)
     · an end slide outside the deck is ignored, as deck/deck ignores it; pdfpc counts it (\"3/999\")"
  (:require [vinary.slides.deck :as deck]
            [vinary.slides.notes :as notes]))

;; ---- constants ------------------------------------------------------------------------------------------

(def default-last-minutes
  "pdfpc's LastMinutes when a deck names none: the last-minutes phase begins five minutes before the end."
  5)

(def pace-tolerance-s
  "How far (seconds) the elapsed time may stray from the expected time before the pace reads behind or ahead:
   pdfpc's 60 s either way (CTRL 388-391)."
  60)

(def phase-words
  "Each phase in words, for the strip and for a screen reader: a phase is never told by colour alone (ADR-0048
   §6.5). The plain talk needs no words."
  {:pre-talk     "Starts in"
   :not-started  "Not started"
   :talk         nil
   :last-minutes "Last minutes"
   :over-time    "Over time"})

(def ^:private ms-per-second 1000)
(def ^:private ms-per-minute 60000)
(def ^:private ms-per-hour 3600000)
(def ^:private ms-per-day 86400000)
(def ^:private minutes-per-day 1440)

(defn- finite-number? [x] (and (number? x) (js/isFinite x)))

;; ---- times of day -------------------------------------------------------------------------------------

(defn local-midnight-at
  "The local midnight for instant `now` (ms), from the local wall clock's reading at `now` —
   {:hours :minutes :seconds :milliseconds}, what Date's getHours … getMilliseconds return (a missing field is 0):
   `now` minus that time of day. The caller reads the wall clock; this keeps the arithmetic, and its one subtlety,
   in the model. On a day whose UTC offset changed since midnight (a daylight-saving switch) the result is not the
   instant the clocks showed 00:00 but the midnight of the CURRENT offset — what `parse-hhmm` needs, because pdfpc
   places a time of day from the wall clock's current reading (it adds the minutes between the two readings to
   now, TIMER 31-45): at 10:00 on the day the clocks went forward, \"10:30\" is in 30 minutes, not 90."
  [now {:keys [hours minutes seconds milliseconds]}]
  (- now (+ (* (or hours 0) ms-per-hour)
            (* (or minutes 0) ms-per-minute)
            (* (or seconds 0) ms-per-second)
            (or milliseconds 0))))

(def ^:private hhmm-re
  ;; C's sscanf(t, "%d:%d") (TIMER 29): each %d skips the C locale's white space (space, \t, \n, \v, \f, \r) and
  ;; reads an optional sign and decimal digits; the ':' must follow the hour's digits at once; what follows the
  ;; minutes is ignored ("10:30:15" and "10:30pm" both read 10:30)
  #"^[ \t\n\x0B\x0C\r]*([+-]?\d+):[ \t\n\x0B\x0C\r]*([+-]?\d+)")

(defn parse-hhmm
  "The instant (ms) that pdfpc's time of day `text` (\"HH:MM\", 24-hour, local) names, seen from instant `now`, with
   `local-midnight` a local midnight in the UTC offset in force at `now` (`local-midnight-at`; any day's will do,
   since only `now`'s time of day is read from it): today's HH:MM:00 — or TOMORROW's, 24 h later, when that is
   earlier than the current minute (\"Assume it's about tomorrow\", TIMER 36-40). The current minute itself is
   today: a talk that began a few seconds ago.

   nil unless `text` reads as sscanf(\"%d:%d\") reads it (`hhmm-re`) with BOTH fields converted, the hours in
   0-23 and the minutes in 0-59 — pdfpc checks neither, so its \"10\" is 10:00 and its \"noon\" is midnight — and
   nil when `now` or `local-midnight` is not a number."
  [text now local-midnight]
  (when (and (string? text) (finite-number? now) (finite-number? local-midnight))
    (when-let [[_ hh mm] (re-find hhmm-re text)]
      (let [hours   (js/parseInt hh 10)
            minutes (js/parseInt mm 10)]
        (when (and (<= 0 hours 23) (<= 0 minutes 59))
          (let [time-of-day (mod (- now local-midnight) ms-per-day)        ; ms since today's local midnight
                today       (- now time-of-day)
                this-minute (js/Math.floor (/ time-of-day ms-per-minute))
                minute      (+ (* 60 hours) minutes)]
            (+ today (* ms-per-minute (if (< minute this-minute) (+ minute minutes-per-day) minute)))))))))

;; ---- the schedule ---------------------------------------------------------------------------------------

(defn- duration-ms
  "D in ms from pdfpc's Duration in `minutes`, truncated to a whole second (a C int), when that is positive —
   pdfpc's `duration > 0` (TIMER 146, 154); else nil."
  [minutes]
  (when (finite-number? minutes)
    (let [seconds (js/Math.floor (* 60 minutes))]
      (when (pos? seconds) (* ms-per-second seconds)))))

(defn schedule
  "What pdfpc's `settings` {:duration-min :start-time :end-time :last-minutes} mean for a show that starts at `now`
   (ms; `local-midnight` as for `parse-hhmm`): {:scheduled-start ms|nil :duration-ms ms|nil
   :mode :count-down|:count-up :last-minutes m}. pdfpc's Timer constructor (TIMER 117-160), first match wins:

     start AND end      D = end - start, plus a day when the end is not after the start (a talk over midnight);
                        a duration setting is ignored. The talk is scheduled to start at the start time.
     end AND duration   the talk is scheduled to start at end - D.
     otherwise          a start alone is the scheduled start, with D from the duration if there is one; an end
                        alone does nothing (the constructor is pdfpc's only reader of the end time).

   Each time is placed on its own by `parse-hhmm`, a time that is absent or unreadable counting as not given. D is
   positive or nil, and :mode is :count-down exactly when there is a D. A scheduled start may lie before `now` (an
   end and a duration that no longer fit): the talk then started there (`talk-start`). :last-minutes is
   LastMinutes, else `default-last-minutes`; 0 or less never reaches the last-minutes phase.

   The schedule is read once per show, at its start: read at a later `now`, a start time that has just passed
   would be tomorrow's. `clock` does so."
  [{:keys [duration-min start-time end-time last-minutes]} now local-midnight]
  (let [start    (parse-hhmm start-time now local-midnight)
        end      (parse-hhmm end-time now local-midnight)
        duration (duration-ms duration-min)
        [scheduled-start duration]
        (cond
          (and start end)    [start (- (if (< start end) end (+ end ms-per-day)) start)]
          (and end duration) [(- end duration) duration]
          :else              [start duration])]
    {:scheduled-start scheduled-start
     :duration-ms     duration
     :mode            (if duration :count-down :count-up)
     :last-minutes    (if (finite-number? last-minutes) last-minutes default-last-minutes)}))

;; ---- the timer's events -------------------------------------------------------------------------------

(defn talk-start
  "When the talk started (ms), or nil while it has not: the `schedule`'s :scheduled-start or the presenter's
   `first-departure` from the slide the show started on, whichever came first, counting only what has happened by
   `now` (ADR-0048 §6.5). pdfpc starts its timer at the start time (TIMER 301-306) and at the first slide change
   (CTRL 103-106). The caller records the departure (`record-departure`); a restart overrides this (`restart`)."
  [schedule first-departure now]
  (let [happened  (fn [t] (when (and (finite-number? t) (finite-number? now) (<= t now)) t))
        scheduled (happened (:scheduled-start schedule))
        departed  (happened first-departure)]
    (if (and scheduled departed) (min scheduled departed) (or scheduled departed))))

(defn show-timer
  "A window's presenter timer for a show that starts at `now` (ms), with `local-midnight` for `now`
   (`local-midnight-at`): {:show-started :local-midnight :first-departure nil :talk-started nil}. It holds events,
   never derived values: `clock` reads the schedule from the deck's settings at :show-started — so a start time
   that passes during the show is not pushed to tomorrow, and a refreshed deck's new settings apply at once — and
   the talk's start from that schedule and :first-departure, unless :talk-started (a restart) names it."
  [now local-midnight]
  {:show-started now :local-midnight local-midnight :first-departure nil :talk-started nil})

(defn record-departure
  "`timer` once the presenter has left the slide the show started on, at `now`: only the first departure counts,
   because the talk has started by then (`talk-start`)."
  [timer now]
  (if (some? (:first-departure timer)) timer (assoc timer :first-departure now)))

(defn restart
  "Restart the presenter timer (the palette's *Restart the presenter timer*, while presenting): the talk starts
   again at `now`, whatever the schedule and the departures say — during a pre-talk it ends the pre-talk
   (ADR-0048 §6.5)."
  [timer now]
  (assoc timer :talk-started now))

;; ---- the counter and the pace ---------------------------------------------------------------------------

(defn effective-end-frame
  "pdfpc's end slide as the counter, the pace and End count it: `end-frame` when it names one of the deck's
   `frame-count` frames, else the last frame (pdfpc's unset end slide, metadata/pdf.vala 1176-1182); nil for a deck
   without frames. An end frame outside the deck is ignored, as deck/deck ignores it."
  [frame-count end-frame]
  (when (and (integer? frame-count) (pos? frame-count))
    (if (and (integer? end-frame) (<= 0 end-frame) (< end-frame frame-count)) end-frame (dec frame-count))))

(defn counter
  "The slide counter for `position` {:frame u :frames n :end-frame}: \"Slide u+1 / e+1\", counting frames up to
   the end slide e (`effective-end-frame`) — pdfpc's \"%d/%u\" (PRES 707-710) in the ADR's words. Past the end
   slide it reads like \"Slide 23 / 20\". nil without a frame to count."
  [{:keys [frame frames end-frame]}]
  (let [e (effective-end-frame frames end-frame)]
    (when (and e (integer? frame) (>= frame 0))
      (str "Slide " (inc frame) " / " (inc e)))))

(defn overlay-counter
  "Which overlay of its frame `position` {:overlay o :overlays k} shows (deck/position: o is 0-based): \"overlay
   o+1 / k\", or nil for a frame of one page, which has no overlays to count."
  [{:keys [overlay overlays]}]
  (when (and (integer? overlays) (> overlays 1) (integer? overlay) (<= 0 overlay))
    (str "overlay " (inc overlay) " / " overlays)))

(defn pace
  "pdfpc's pace (CTRL 382-394) after `elapsed-s` whole seconds of a talk of `duration-s` seconds, at `position`
   {:frame u :frames n :end-frame} (deck/position with the deck's end frame). The expected elapsed time is the
   middle of the current frame's share,

     t_expected = D * (u + 1/2) / (e + 1),   e = `effective-end-frame`,

   and the presenter is :behind when t > t_expected + 60 s, :ahead when t < t_expected - 60 s, else :on-time.
   Returns {:pace :pace-words :expected-s}, or nil without a positive duration or a frame.

   :pace-words counts the WHOLE minutes past the expected time, rounded down — \"1 min behind\" from 61 s through
   119 s, \"2 min behind\" from 120 s — so it never overstates, and beyond the tolerance it is never 0 min; on time
   it is \"On time\". The comparison is exact: both sides are scaled by 2(e + 1), which makes them integers, so the
   +-60 s edges carry no rounding error."
  [duration-s elapsed-s {:keys [frame frames end-frame]}]
  (let [e (effective-end-frame frames end-frame)]
    (when (and e (finite-number? duration-s) (pos? duration-s) (finite-number? elapsed-s)
               (integer? frame) (>= frame 0))
      (let [scale     (* 2 (inc e))                              ; 2(e + 1)
            expected  (* duration-s (inc (* 2 frame)))           ; t_expected · 2(e + 1) = D(2u + 1)
            deviation (- (* scale elapsed-s) expected)           ; (t − t_expected) · 2(e + 1)
            tolerance (* scale pace-tolerance-s)                 ; 60 s · 2(e + 1)
            minutes   (fn [d] (js/Math.floor (/ d (* 60 scale))))]
        (assoc (cond
                 (> deviation tolerance)     {:pace :behind  :pace-words (str (minutes deviation) " min behind")}
                 (< deviation (- tolerance)) {:pace :ahead   :pace-words (str (minutes (- deviation)) " min ahead")}
                 :else                       {:pace :on-time :pace-words "On time"})
               :expected-s (/ expected scale))))))

;; ---- the timer label ------------------------------------------------------------------------------------

(defn- two-digits [n] (if (< n 10) (str "0" n) (str n)))

(defn hms
  "A signed number of `seconds` as pdfpc's timer label (TIMER 362-377): [-]HH:MM:SS, each field two digits at
   least — the hours grow as needed, \"100:00:00\" — and a \"-\" only below zero, so zero is \"00:00:00\", never
   \"-00:00:00\". A fraction is truncated toward zero first, as a C int is (-1.9 → \"-00:00:01\", -0.5 →
   \"00:00:00\"); `clock` passes whole seconds, rounded as the namespace documents. nil for a non-number or a
   non-finite one."
  [seconds]
  (when (finite-number? seconds)
    (let [whole (js/Math.trunc seconds)
          size  (js/Math.abs whole)]
      (str (when (neg? whole) "-")
           (two-digits (js/Math.floor (/ size 3600))) ":"
           (two-digits (js/Math.floor (/ (mod size 3600) 60))) ":"
           (two-digits (mod size 60))))))

;; ---- the status strip -----------------------------------------------------------------------------------

(defn- talk-phase
  "The phase after `elapsed-s` seconds of the talk (nil before it) of `duration-s` seconds (nil: counting up)."
  [{:keys [scheduled-start last-minutes]} duration-s elapsed-s]
  (cond
    (nil? elapsed-s)                                 (if scheduled-start :pre-talk :not-started)
    (nil? duration-s)                                :talk
    (>= elapsed-s duration-s)                        :over-time
    (< (- duration-s elapsed-s) (* 60 last-minutes)) :last-minutes
    :else                                            :talk))

(defn clock
  "What the presenter view's status strip shows at `now` (ms), for `timer` (`show-timer`), the deck's pdfpc
   `settings` (`schedule`) and `position` {:frame :frames :end-frame :overlay :overlays} (deck/position with the
   deck's end frame):

     :phase            :pre-talk      a scheduled start is ahead and the talk has not started: the timer counts
                                      down to it
                       :not-started   no scheduled start, and the presenter has not left the first slide: the timer
                                      stands at D, or at 00:00:00 (pdfpc's Stopped)
                       :talk          the talk is on
                       :last-minutes  0 < D - t < 60 m, for LastMinutes m
                       :over-time     t >= D
     :phase-words      the phase in words (`phase-words`): never colour alone
     :mode             :count-down with a duration, else :count-up
     :timer            `hms` of :timer-seconds — -HH:MM:SS to the scheduled start; then D - t counting down
                       (negative once over time), or t counting up
     :timer-seconds    the signed whole seconds :timer shows (pdfpc's timeInSecs, TIMER 340-360)
     :pace :pace-words `pace`, during :talk and :last-minutes with a duration; nil otherwise, as pdfpc shows no pace
                       before the talk or over time (CTRL 373-378)
     :counter          `counter`
     :overlay-counter  `overlay-counter`
     :talk-started     when the talk started (ms), or nil — a caller may store it, or let it be derived again

   The talk's start is the timer's :talk-started when a restart set it, else `talk-start` of the schedule — read at
   the timer's :show-started, never at `now` — and the timer's :first-departure. A nil timer reads as a show that
   has not begun: :not-started, with no time of day placed."
  [timer settings position now]
  (let [{:keys [show-started local-midnight first-departure talk-started]} timer
        {:keys [scheduled-start duration-ms mode] :as sched} (schedule settings show-started local-midnight)
        started    (if (finite-number? talk-started) talk-started (talk-start sched first-departure now))
        duration-s (some-> duration-ms (/ ms-per-second))
        elapsed-s  (when started (js/Math.floor (/ (max 0 (- now started)) ms-per-second)))
        phase      (talk-phase sched duration-s elapsed-s)
        seconds    (case phase
                     :pre-talk    (- (js/Math.ceil (/ (- scheduled-start now) ms-per-second)))
                     :not-started (or duration-s 0)
                     (if duration-s (- duration-s elapsed-s) elapsed-s))
        paced      (case phase
                     (:talk :last-minutes) (when duration-s (pace duration-s elapsed-s position))
                     nil)]
    {:phase           phase
     :phase-words     (phase-words phase)
     :mode            mode
     :timer           (hms seconds)
     :timer-seconds   seconds
     :pace            (:pace paced)
     :pace-words      (:pace-words paced)
     :counter         (counter position)
     :overlay-counter (overlay-counter position)
     :talk-started    started}))

;; ---- End ------------------------------------------------------------------------------------------------

(defn end-target
  "Where End goes (ADR-0048 §6.5) from physical `page`, in a deck whose frames are `frames` [[first last] …]
   (physical pages, deck/deck's :frames) and whose pdfpc end slide is frame `end-frame` (or nil): first to the last
   page of the end slide's frame, and from there — or from beyond it — to the last page, so the backup slides after
   the end slide stay one more End away. An end frame outside `frames` is ignored, and a `page` that is not a number
   counts as page 0. nil for a deck without frames.

   A note page (ADR-0048 §3) needs no snapping first: the end slide's last page is a shown page, so a hidden page
   lies before it exactly when its slide does."
  [frames end-frame page]
  (let [frames (vec frames)]
    (when-let [e (effective-end-frame (count frames) end-frame)]
      (let [end-last  (second (nth frames e))
            last-page (second (peek frames))
            page      (if (number? page) page 0)]
        (if (< page end-last) end-last last-page)))))

;; ---- the pane (ADR-0048 §6.1) ----------------------------------------------------------------------------------

(defn next-label
  "The next slide's accessible name for its `position` {:frame :frames :end-frame :overlay :overlays} (deck/position
   with the deck's end frame): \"Next: slide f+1 of e+1\", counted as `counter` counts, and its overlay — \", overlay
   o+1 of k\" — when its frame has several. nil without a frame to count."
  [{:keys [frame frames end-frame overlay overlays]}]
  (let [e (effective-end-frame frames end-frame)]
    (when (and e (integer? frame) (>= frame 0))
      (str "Next: slide " (inc frame) " of " (inc e)
           (when (and (integer? overlays) (> overlays 1) (integer? overlay) (<= 0 overlay))
             (str ", overlay " (inc overlay) " of " overlays))))))

(defn pane
  "What the presenter view's pane shows (ADR-0048 §6.1) while the view stands on `page`, from the engine's deck `d`,
   the page `sizes`, the notes `model` (vinary.slides.notes), the :slides-notes preference `pref` and the slide half
   `side` the audience sees (deck/slide-side; nil: whole pages):
     :page             the page the slide shows: `page` snapped (a note page shows as its slide)
     :counter          \"Slide u+1 / e+1\" (`counter`), nil for a deck without frames
     :overlay-counter  \"overlay o+1 / k\", or nil for a frame of one page (`overlay-counter`)
     :regions          the notes regions, in order — a split deck's notes half, an interleaved deck's note pages after
                       the slide — as [{:page :rect}…], page units with y down (notes/sources-for)
     :sidecar-note     the pdfpc sidecar's note for the page, or nil (its annotation note is read from the document)
     :next             the next slide, {:page :rect :label}: what the next step (:next) will show, its audience
                       rectangle and its accessible name (`next-label`); nil when no step goes on — the end of the show
     :deck-notes?      whether the notes model knows of notes on some page (notes/has-notes?)"
  [d sizes model pref side page]
  (let [p       (deck/snap d (or page 0))
        at      (fn [q] (assoc (deck/position d q) :end-frame (:end-frame d)))
        pos     (at p)
        nxt     (:page (deck/step d {:page p :back [] :fwd []} :next))
        regions (into [] (comp (filter #(= :region (:kind %))) (map #(select-keys % [:page :rect])))
                      (notes/sources-for model d sizes p pref))]
    {:page            p
     :counter         (counter pos)
     :overlay-counter (overlay-counter pos)
     :regions         regions
     :sidecar-note    (get-in model [:sidecar :notes p])
     :next            (when (and (integer? nxt) (not= nxt p))
                        {:page  nxt
                         :rect  (deck/half-rect (nth sizes nxt [0 0]) side)
                         :label (next-label (at nxt))})
     :deck-notes?     (notes/has-notes? model)}))

(defn preview-items
  "All selectable slide steps in order. The generic vertical strip decides which few need rendering."
  [d sizes side page]
  (let [current (deck/snap d (or page 0))
        pages (or (:shown d) (vec (range (:pages d 0))))
        pages (->> pages (remove (or (:skipped d) #{})) (cons current) distinct sort)]
    (mapv (fn [p]
            (let [pos (assoc (deck/position d p) :end-frame (:end-frame d))]
              {:id p :rect (deck/half-rect (nth sizes p [0 0]) side)
               :label (str (or (counter pos) (str "Page " (inc p)))
                           (when-let [overlay (overlay-counter pos)] (str " · " overlay)))}))
          pages)))

(defn joined-geometry
  "Pixel rectangles for two touching, equal-size pages centred in the available pane. `side` names the slide's
   authored half; nil (separate note pages or text notes) puts the slide on the left."
  [width height {:keys [w h]} side]
  (when (and (number? width) (pos? width) (number? height) (pos? height)
             (number? w) (pos? w) (number? h) (pos? h))
    (let [vertical? (contains? #{:top :bottom} side)
          scale (min (/ (max 1 (- width 24)) (* w (if vertical? 1 2)))
                     (/ (max 1 (- height 24)) (* h (if vertical? 2 1))))
          pw (* w scale)
          ph (* h scale)
          x (/ (- width (* pw (if vertical? 1 2))) 2)
          y (/ (- height (* ph (if vertical? 2 1))) 2)
          note-first? (contains? #{:right :bottom} side)
          first-rect {:left x :top y :width pw :height ph}
          second-rect (if vertical? (assoc first-rect :top (+ y ph))
                         (assoc first-rect :left (+ x pw)))]
      {:slide (if note-first? second-rect first-rect)
       :notes (if note-first? first-rect second-rect)})))
