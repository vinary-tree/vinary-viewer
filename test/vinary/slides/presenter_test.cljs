(ns vinary.slides.presenter-test
  "The presenter clock (ADR-0048 §6.5): pdfpc's times of day, schedule and talk start, the phases at their edges,
   the pace at its ±60 s edges, the counter, where End goes, and the timer label; and what the presenter view's pane
   shows (§6.1): the counter, the notes regions, the sidecar note and the next slide. Deterministic: every instant is
   fixed data — one local midnight and offsets from it — so no test reads the machine's clock or depends on its
   time zone."
  (:require [cljs.test :refer-macros [deftest testing is are]]
            [vinary.slides.deck :as deck]
            [vinary.slides.notes :as notes]
            [vinary.slides.presenter :as presenter]))

(def midnight
  "A local midnight: 2026-10-17 00:00 UTC — but any instant would do, since only differences are read."
  1792195200000)

(def day 86400000)

(defn- at
  "The instant `h`:`m`:`s` (and `ms`) after `midnight`."
  ([h m s] (at h m s 0))
  ([h m s ms] (+ midnight (* h 3600000) (* m 60000) (* s 1000) ms)))

(def now
  "10:15:30.250 — so the current minute is 10:15."
  (at 10 15 30 250))

(def show-start (at 10 15 0))

(def position
  "Frame 3 of 10 (u = 2), a one-page frame."
  {:frame 2 :frames 10 :overlay 0 :overlays 1})

(defn- timer-after
  "A show that started at 10:15, whose presenter first left the first slide at `departure` (ms)."
  [departure]
  (presenter/record-departure (presenter/show-timer show-start midnight) departure))

;; ---- times of day ---------------------------------------------------------------------------------------

(deftest parse-hhmm-today-or-tomorrow
  (testing "a later time is today, at HH:MM:00"
    (is (= (at 10 30 0) (presenter/parse-hhmm "10:30" now midnight)))
    (is (= (at 23 59 0) (presenter/parse-hhmm "23:59" now midnight))))
  (testing "the CURRENT minute is today: it began 30.25 s ago"
    (is (= (at 10 15 0) (presenter/parse-hhmm "10:15" now midnight))))
  (testing "a time earlier than the current minute is tomorrow, 24 h later"
    (is (= (+ day (at 10 14 0)) (presenter/parse-hhmm "10:14" now midnight)))
    (is (= (+ day (at 9 5 0)) (presenter/parse-hhmm "9:05" now midnight)))
    (is (= (+ day midnight) (presenter/parse-hhmm "00:00" now midnight)))))

(deftest parse-hhmm-reads-as-sscanf-does
  (testing "%d skips C white space and takes a sign; one digit or leading zeros; what follows the minutes is ignored"
    (are [text] (= (at 11 5 0) (presenter/parse-hhmm text now midnight))
      "11:05" " 11:05" "\t11:05" "\n\u000B\u000C\r 11:05" "11: 05" "11:\n05" "+11:+05" "011:005" "11:5"
      "11:05pm" "11:05:59"))
  (testing "-0 is 0, as in C"
    (is (= (at 11 0 0) (presenter/parse-hhmm "11:-0" now midnight)))
    (is (= (+ day (at 0 30 0)) (presenter/parse-hhmm "-0:30" now midnight)))))

(deftest parse-hhmm-rejects-what-is-no-time
  (testing "both fields must convert: pdfpc's \"10\" (10:00) and \"noon\" (midnight) are no time here"
    (are [text] (nil? (presenter/parse-hhmm text now midnight))
      "" "   " "10" "10:" ":30" "noon" "10.30" "10h30" "10 :30" "1 0:30" "+:30" "10:+"
      " 10:30"                                   ; a no-break space is not C white space
      "１０:３０"))                   ; nor are full-width digits digits
  (testing "a 24-hour clock: hours 0-23, minutes 0-59"
    (are [text] (nil? (presenter/parse-hhmm text now midnight))
      "24:00" "25:30" "-1:30" "10:60" "10:-5" "99999999999999999999:00"))
  (testing "not a string, or no instant to place it from"
    (is (nil? (presenter/parse-hhmm nil now midnight)))
    (is (nil? (presenter/parse-hhmm 1030 now midnight)))
    (is (nil? (presenter/parse-hhmm "10:30" nil midnight)))
    (is (nil? (presenter/parse-hhmm "10:30" js/NaN midnight)))
    (is (nil? (presenter/parse-hhmm "10:30" now nil)))))

(deftest parse-hhmm-reads-only-the-time-of-day-from-the-midnight
  (testing "any day's local midnight, in the same offset, gives the same instant — one before now or after it"
    (is (= (at 10 30 0) (presenter/parse-hhmm "10:30" now (- midnight (* 3 day)))))
    (is (= (at 10 30 0) (presenter/parse-hhmm "10:30" now (+ midnight day))))))

(deftest local-midnight-follows-the-wall-clock
  (is (= midnight (presenter/local-midnight-at now {:hours 10 :minutes 15 :seconds 30 :milliseconds 250})))
  (is (= midnight (presenter/local-midnight-at (at 10 0 0) {:hours 10})) "a missing field is 0")
  (testing "the day the clocks went forward: the CURRENT offset's midnight, so \"10:30\" at 10:00 is 30 minutes away"
    ;; 2026-03-29 08:00 UTC is 10:00 in Berlin (CEST, UTC+2); its clocks read 00:00 at 23:00 UTC the day before,
    ;; while the offset was still CET (UTC+1)
    (let [dst-now       1774771200000
          true-midnight 1774738800000
          wall-midnight (presenter/local-midnight-at dst-now {:hours 10 :minutes 0 :seconds 0 :milliseconds 0})]
      (is (= (- dst-now (* 10 3600000)) wall-midnight))
      (is (= (+ dst-now (* 30 60000)) (presenter/parse-hhmm "10:30" dst-now wall-midnight)))
      (is (= (+ dst-now (* 90 60000)) (presenter/parse-hhmm "10:30" dst-now true-midnight))
          "the instant the clocks read 00:00 would place it an hour late"))))

;; ---- the schedule ---------------------------------------------------------------------------------------

(defn- schedule [settings] (presenter/schedule settings show-start midnight))

(deftest schedule-from-a-start-and-an-end
  (testing "D = end - start, and a duration setting is ignored"
    (is (= {:scheduled-start (at 10 30 0) :duration-ms (* 45 60000) :mode :count-down :last-minutes 5}
           (schedule {:start-time "10:30" :end-time "11:15" :duration-min 20}))))
  (testing "an end that is not after the start: plus a day"
    (is (= (* (+ (* 23 60) 30) 60000) (:duration-ms (schedule {:start-time "11:00" :end-time "10:30"}))))
    (is (= day (:duration-ms (schedule {:start-time "11:00" :end-time "11:00"}))) "equal times: 24 h"))
  (testing "each time is placed on its own: a start before this minute is tomorrow's, and an end before it follows"
    (is (= {:scheduled-start (+ day (at 9 0 0)) :duration-ms (* 2 3600000)}
           (select-keys (schedule {:start-time "09:00" :end-time "11:00"}) [:scheduled-start :duration-ms]))))
  (testing "over midnight: 23:30 today to 00:15 (tomorrow's, being before this minute) is 45 minutes"
    (is (= (* 45 60000) (:duration-ms (schedule {:start-time "23:30" :end-time "00:15"}))))))

(deftest schedule-from-an-end-and-a-duration
  (is (= {:scheduled-start (at 10 45 0) :duration-ms (* 30 60000) :mode :count-down :last-minutes 5}
         (schedule {:end-time "11:15" :duration-min 30})))
  (testing "a scheduled start that has already passed stays where it is"
    (is (= (at 9 30 0) (:scheduled-start (schedule {:end-time "10:30" :duration-min 60}))))))

(deftest schedule-an-end-alone-does-nothing
  (are [settings] (= {:scheduled-start nil :duration-ms nil :mode :count-up :last-minutes 5} (schedule settings))
    {:end-time "11:15"}
    {:end-time "11:15" :duration-min 0}
    {:end-time "11:15" :duration-min -5}
    {}
    nil))

(deftest schedule-a-start-alone-and-a-duration-alone
  (is (= {:scheduled-start (at 10 30 0) :duration-ms nil :mode :count-up :last-minutes 5}
         (schedule {:start-time "10:30"})))
  (is (= {:scheduled-start (at 10 30 0) :duration-ms (* 20 60000) :mode :count-down :last-minutes 5}
         (schedule {:start-time "10:30" :duration-min 20})))
  (is (= {:scheduled-start nil :duration-ms (* 20 60000) :mode :count-down :last-minutes 5}
         (schedule {:duration-min 20}))))

(deftest schedule-ignores-unreadable-times
  (testing "an unreadable start leaves the end and the duration"
    (is (= (at 10 45 0) (:scheduled-start (schedule {:start-time "10" :end-time "11:15" :duration-min 30})))))
  (testing "an unreadable end leaves the start alone"
    (is (= {:scheduled-start (at 10 30 0) :duration-ms (* 20 60000)}
           (select-keys (schedule {:start-time "10:30" :end-time "25:00" :duration-min 20})
                        [:scheduled-start :duration-ms])))))

(deftest schedule-units-and-last-minutes
  (testing "Duration in minutes, truncated to a whole second; nothing positive is no duration"
    (is (= 30000 (:duration-ms (schedule {:duration-min 0.5}))))
    (is (= 61000 (:duration-ms (schedule {:duration-min (/ 61.9 60)}))))
    (are [d] (nil? (:duration-ms (schedule {:duration-min d}))) 0.001 0 -1 js/NaN js/Infinity "20" nil))
  (testing "LastMinutes, else 5; 0 is kept (it turns the phase off)"
    (are [m expected] (= expected (:last-minutes (schedule {:last-minutes m})))
      3 3, 0 0, 2.5 2.5, nil 5, js/NaN 5, "3" 5)))

;; ---- the talk's start and the timer's events -------------------------------------------------------------

(deftest talk-start-is-whichever-came-first
  (let [scheduled {:scheduled-start (at 10 30 0)}]
    (testing "nothing has happened yet"
      (is (nil? (presenter/talk-start {} nil (at 10 20 0))))
      (is (nil? (presenter/talk-start scheduled nil (dec (at 10 30 0))))))
    (testing "the scheduled start, from that very instant on"
      (is (= (at 10 30 0) (presenter/talk-start scheduled nil (at 10 30 0))))
      (is (= (at 10 30 0) (presenter/talk-start scheduled nil (at 11 0 0)))))
    (testing "a departure before it starts the talk early, and the start time passing later changes nothing"
      (is (= (at 10 20 0) (presenter/talk-start scheduled (at 10 20 0) (at 10 25 0))))
      (is (= (at 10 20 0) (presenter/talk-start scheduled (at 10 20 0) (at 10 35 0)))))
    (testing "a departure after it: the scheduled start came first"
      (is (= (at 10 30 0) (presenter/talk-start scheduled (at 10 40 0) (at 10 45 0)))))
    (testing "without a scheduled start, the first departure"
      (is (= (at 10 20 0) (presenter/talk-start {} (at 10 20 0) (at 10 25 0)))))
    (testing "an event after now has not happened"
      (is (nil? (presenter/talk-start {} (at 10 20 0) (at 10 19 0)))))))

(deftest the-timer-holds-events
  (let [timer (presenter/show-timer show-start midnight)]
    (is (= {:show-started show-start :local-midnight midnight :first-departure nil :talk-started nil} timer))
    (testing "only the first departure counts"
      (is (= (at 10 20 0) (:first-departure (-> timer
                                                (presenter/record-departure (at 10 20 0))
                                                (presenter/record-departure (at 10 22 0)))))))
    (testing "each restart sets the talk's start again"
      (is (= (at 10 40 0) (:talk-started (-> timer
                                             (presenter/restart (at 10 35 0))
                                             (presenter/restart (at 10 40 0)))))))))

;; ---- the status strip -----------------------------------------------------------------------------------

(deftest clock-counts-down-to-the-scheduled-start
  (let [timer    (presenter/show-timer show-start midnight)
        settings {:start-time "10:30" :duration-min 20}
        clock    (fn [timer t] (presenter/clock timer settings position t))]
    (is (= {:phase :pre-talk :phase-words "Starts in" :mode :count-down :timer "-00:15:00" :timer-seconds -900
            :pace nil :pace-words nil :counter "Slide 3 / 10" :overlay-counter nil :talk-started nil}
           (clock timer show-start)))
    (testing "rounded up: -00:00:01 through the last second, never -00:00:00"
      (is (= "-00:00:01" (:timer (clock timer (dec (at 10 30 0))))))
      (is (= "-00:00:01" (:timer (clock timer (at 10 29 59)))))
      (is (= "-00:00:02" (:timer (clock timer (at 10 29 58 999))))))
    (testing "at the scheduled start the talk begins, counting down from D"
      (is (= [:talk "00:20:00" (at 10 30 0)] ((juxt :phase :timer :talk-started) (clock timer (at 10 30 0))))))
    (testing "a departure during the pre-talk starts the talk; the start time passing later changes nothing"
      (let [departed (presenter/record-departure timer (at 10 20 0))]
        (is (= [:talk "00:15:00"] ((juxt :phase :timer) (clock departed (at 10 25 0)))))
        (is (= "00:09:00" (:timer (clock departed (at 10 31 0)))))))
    (testing "the schedule is read at the show's start: a 10:30 that has passed is not tomorrow's"
      (is (= [:talk "00:19:00"] ((juxt :phase :timer) (clock timer (at 10 31 0))))))))

(deftest clock-waits-for-the-first-departure-without-a-schedule
  (let [timer (presenter/show-timer show-start midnight)]
    (is (= {:phase :not-started :phase-words "Not started" :mode :count-down :timer "00:20:00" :timer-seconds 1200
            :pace nil :pace-words nil :talk-started nil}
           (select-keys (presenter/clock timer {:duration-min 20} position (at 11 0 0))
                        [:phase :phase-words :mode :timer :timer-seconds :pace :pace-words :talk-started])))
    (is (= [:not-started "00:00:00" :count-up] ((juxt :phase :timer :mode) (presenter/clock timer {} position now))))
    (testing "the first departure starts the talk"
      (is (= [:talk "00:19:18" (at 11 0 0)]
             ((juxt :phase :timer :talk-started)
              (presenter/clock (timer-after (at 11 0 0)) {:duration-min 20} position (at 11 0 42))))))))

(deftest clock-counts-up-without-a-duration
  (is (= {:phase :talk :phase-words nil :mode :count-up :timer "01:01:42" :timer-seconds 3702 :pace nil
          :pace-words nil}
         (select-keys (presenter/clock (timer-after (at 10 16 0)) {} position (at 11 17 42 999))
                      [:phase :phase-words :mode :timer :timer-seconds :pace :pace-words])))
  (testing "a count-up has no last minutes and is never over time"
    (is (= :talk (:phase (presenter/clock (timer-after (at 10 16 0)) {:last-minutes 5} position (+ day now)))))))

(deftest clock-phases-at-their-edges
  ;; D = 20 min, LastMinutes 5: the last minutes begin once fewer than 300 s are left; over time at t = D
  (let [start (at 10 20 0)
        timer (timer-after start)
        seen  (fn [ms] ((juxt :phase :timer :phase-words)
                        (presenter/clock timer {:duration-min 20} position (+ start ms))))]
    (is (= [:talk "00:20:00" nil] (seen 0)))
    (testing "exactly 60·m left is not yet the last minutes, and the phase changes with the shown second"
      (is (= [:talk "00:05:00" nil] (seen 900000)))
      (is (= [:talk "00:05:00" nil] (seen 900999)))
      (is (= [:last-minutes "00:04:59" "Last minutes"] (seen 901000))))
    (testing "the last second before D, then exactly D"
      (is (= [:last-minutes "00:00:01" "Last minutes"] (seen 1199999)))
      (is (= [:over-time "00:00:00" "Over time"] (seen 1200000)))
      (is (= [:over-time "00:00:00" "Over time"] (seen 1200999))))
    (testing "over time it counts on, negative"
      (is (= [:over-time "-00:00:01" "Over time"] (seen 1201000)))
      (is (= [:over-time "-00:01:01" "Over time"] (seen 1261500)))
      (is (= [:over-time "-01:00:00" "Over time"] (seen (* 80 60000)))))))

(deftest clock-last-minutes-setting
  (let [start (at 10 20 0)
        timer (timer-after start)
        phase (fn [settings s] (:phase (presenter/clock timer settings position (+ start (* 1000 s)))))]
    (testing "LastMinutes 2: under 120 s left"
      (is (= :talk (phase {:duration-min 20 :last-minutes 2} 1080)))
      (is (= :last-minutes (phase {:duration-min 20 :last-minutes 2} 1081))))
    (testing "LastMinutes 0 or less never reaches the phase"
      (is (= :talk (phase {:duration-min 20 :last-minutes 0} 1199)))
      (is (= :talk (phase {:duration-min 20 :last-minutes -3} 1199)))
      (is (= :over-time (phase {:duration-min 20 :last-minutes 0} 1200))))
    (testing "a threshold beyond D: the last minutes from the start"
      (is (= :last-minutes (phase {:duration-min 20 :last-minutes 30} 0))))))

(deftest clock-from-a-start-and-an-end-time
  (let [timer    (presenter/show-timer show-start midnight)
        settings {:start-time "10:30" :end-time "11:15" :duration-min 90}
        clock    (fn [t] (presenter/clock timer settings position t))]
    (is (= "-00:15:00" (:timer (clock show-start))))
    (is (= ["00:45:00" :count-down] ((juxt :timer :mode) (clock (at 10 30 0)))) "the duration setting is ignored")
    (is (= :over-time (:phase (clock (at 11 15 0)))))))

(deftest clock-from-an-end-and-a-duration-ends-at-the-end-time
  ;; EndTime 10:30 with Duration 60 schedules 09:30, but the show only starts at 10:15: the talk started at 09:30,
  ;; so 15 minutes are left and it is over time at 10:30. (pdfpc would count a full hour from 10:15.)
  (let [timer    (presenter/show-timer show-start midnight)
        settings {:end-time "10:30" :duration-min 60}]
    (is (= [:talk "00:15:00" (at 9 30 0)]
           ((juxt :phase :timer :talk-started) (presenter/clock timer settings position show-start))))
    (is (= [:over-time "00:00:00"] ((juxt :phase :timer) (presenter/clock timer settings position (at 10 30 0)))))))

(deftest clock-for-a-show-across-midnight
  ;; the show starts at 23:50; StartTime 00:05 is tomorrow's, and stays tomorrow's once midnight has passed
  (let [timer    (presenter/show-timer (at 23 50 0) midnight)
        settings {:start-time "00:05" :duration-min 30}
        clock    (fn [t] (presenter/clock timer settings position t))]
    (is (= "-00:15:00" (:timer (clock (at 23 50 0)))))
    (is (= "-00:01:00" (:timer (clock (+ day (at 0 4 0))))))
    (is (= [:talk "00:30:00"] ((juxt :phase :timer) (clock (+ day (at 0 5 0))))))))

(deftest clock-restart
  (let [settings {:duration-min 20}
        timer    (timer-after (at 10 20 0))
        clock    (fn [timer t] (presenter/clock timer settings position t))]
    (is (= "00:10:00" (:timer (clock timer (at 10 30 0)))))
    (testing "the talk starts again now"
      (let [restarted (presenter/restart timer (at 10 30 0))]
        (is (= [:talk "00:20:00" (at 10 30 0)] ((juxt :phase :timer :talk-started) (clock restarted (at 10 30 0)))))
        (is (= "00:19:00" (:timer (clock restarted (at 10 31 0)))))
        (testing "a clock set back before the restart reads no time elapsed, not negative"
          (is (= "00:20:00" (:timer (clock restarted (at 10 29 0))))))))
    (testing "during a pre-talk it ends the pre-talk"
      (let [restarted (presenter/restart (presenter/show-timer show-start midnight) (at 10 20 0))]
        (is (= [:talk "00:19:00"]
               ((juxt :phase :timer)
                (presenter/clock restarted {:start-time "10:30" :duration-min 20} position (at 10 21 0)))))))))

(deftest clock-without-a-timer
  (testing "a show that has not begun: no time of day can be placed"
    (is (= [:not-started "00:20:00" nil]
           ((juxt :phase :timer :talk-started)
            (presenter/clock nil {:duration-min 20 :start-time "10:30"} position now))))))

(deftest clock-reads-the-adr-strip
  ;; ADR-0048 §6.1: "Slide 7 / 12 · overlay 2 / 3"
  (let [c (presenter/clock (timer-after (at 10 20 0)) {:duration-min 20}
                           {:frame 6 :frames 12 :overlay 1 :overlays 3} (at 10 25 0))]
    (is (= ["Slide 7 / 12" "overlay 2 / 3"] ((juxt :counter :overlay-counter) c)))))

(defn- first-counterexample
  "The first of `xs` that breaks `holds?`, or nil when none does. Asserting that it is nil keeps a failure's report
   to that one sample, not the whole timeline."
  [holds? xs]
  (first (remove holds? xs)))

(deftest clock-timeline-invariants
  ;; every 250 ms from the show's start at 10:15 to 10:52: a pre-talk to 10:30, then a 20-minute talk
  (let [timer    (presenter/show-timer show-start midnight)
        settings {:start-time "10:30" :duration-min 20}
        order    {:pre-talk 0 :talk 1 :last-minutes 2 :over-time 3}
        strips   (mapv (fn [t] [t (presenter/clock timer settings position t)])
                       (range show-start (at 10 52 0) 250))]
    (is (= 8880 (count strips)))
    (testing "the label is always hms of the seconds; a pre-talk always shows a minus"
      (is (nil? (first-counterexample (fn [[_ c]] (= (:timer c) (presenter/hms (:timer-seconds c)))) strips)))
      (is (nil? (first-counterexample (fn [[_ c]] (or (not= :pre-talk (:phase c)) (neg? (:timer-seconds c))))
                                      strips))))
    (testing "the phases only move forward, through all four"
      (is (nil? (first-counterexample (fn [[[_ a] [_ b]]] (<= (order (:phase a)) (order (:phase b))))
                                      (partition 2 1 strips))))
      (is (= #{:pre-talk :talk :last-minutes :over-time} (set (map (comp :phase second) strips)))))
    (testing "the shown seconds move one way per part, by at most one per second: up to -1 before the talk,
              down from D during it"
      (let [steady          (fn [sign]
                              (fn [[[t0 c0] [t1 c1]]]
                                (let [step (* sign (- (:timer-seconds c1) (:timer-seconds c0)))]
                                  (and (<= 0 step) (<= step (js/Math.ceil (/ (- t1 t0) 1000)))))))
            [before during] (split-with #(= :pre-talk (:phase (second %))) strips)]
        (is (= -900 (:timer-seconds (second (first before)))))
        (is (= -1 (:timer-seconds (second (last before)))))
        (is (= 1200 (:timer-seconds (second (first during)))))
        (is (nil? (first-counterexample (steady 1) (partition 2 1 before))))
        (is (nil? (first-counterexample (steady -1) (partition 2 1 during))))))
    (testing "the talk started at the scheduled start"
      (is (nil? (first-counterexample (fn [[t c]] (= (when (>= t (at 10 30 0)) (at 10 30 0)) (:talk-started c)))
                                      strips))))))

;; ---- the pace -------------------------------------------------------------------------------------------

(deftest pace-is-pdfpcs-expected-time-plus-or-minus-a-minute
  ;; D = 600 s; frame u = 2 of 10 (end frame e = 9): t_expected = 600 · 2.5 / 10 = 150 s
  (let [pace (fn [t] (presenter/pace 600 t {:frame 2 :frames 10}))]
    (is (= 150 (:expected-s (pace 0))))
    (are [t kind words] (= [kind words] ((juxt :pace :pace-words) (pace t)))
      150 :on-time "On time"
      210 :on-time "On time"          ; exactly +60 s
      211 :behind  "1 min behind"
      269 :behind  "1 min behind"
      270 :behind  "2 min behind"     ; whole minutes, rounded down
      90  :on-time "On time"          ; exactly -60 s
      89  :ahead   "1 min ahead"
      31  :ahead   "1 min ahead"
      30  :ahead   "2 min ahead"
      0   :ahead   "2 min ahead")))

(deftest pace-uses-the-exact-expected-time
  ;; 7 frames, frame 2: t_expected = 600 · 2.5 / 7 = 214.285… s. pdfpc truncates it to 214 s first (CTRL 386), which
  ;; would call 154 s on time; at the exact edge, 154.285… s, it is ahead
  (let [pace (fn [t] (:pace (presenter/pace 600 t {:frame 2 :frames 7})))]
    (is (= :ahead (pace 154)))
    (is (= :on-time (pace 155)))
    (is (= :on-time (pace 274)))
    (is (= :behind (pace 275)))))

(deftest pace-counts-to-the-end-slide
  (testing "the end frame, not the last frame, is the denominator"
    (is (= 150 (:expected-s (presenter/pace 600 0 {:frame 2 :frames 20 :end-frame 9})))))
  (testing "an end frame outside the deck is ignored"
    (is (= 75 (:expected-s (presenter/pace 600 0 {:frame 2 :frames 20 :end-frame 20})))))
  (testing "past the end slide the expected time passes D"
    (is (= 750 (:expected-s (presenter/pace 600 0 {:frame 12 :frames 20 :end-frame 9}))))))

(deftest pace-needs-a-duration-and-a-frame
  (are [d position] (nil? (presenter/pace d 10 position))
    nil {:frame 2 :frames 10}
    0   {:frame 2 :frames 10}
    600 {:frames 10}
    600 {:frame -1 :frames 10}
    600 {:frame 2 :frames 0}
    600 nil))

(deftest clock-gives-the-pace-during-the-talk-only
  ;; D = 10 min at frame 3 of 10: t_expected = 150 s
  (let [start (at 10 20 0)
        timer (timer-after start)
        seen  (fn [s] ((juxt :phase :pace :pace-words)
                       (presenter/clock timer {:duration-min 10} position (+ start (* 1000 s)))))]
    (is (= [:talk :on-time "On time"] (seen 210)))
    (is (= [:talk :behind "1 min behind"] (seen 211)))
    (is (= [:talk :ahead "1 min ahead"] (seen 89)))
    (is (= [:last-minutes :behind "7 min behind"] (seen 599)) "the last minutes keep the pace")
    (is (= [:over-time nil nil] (seen 600)) "over time it has none")))

;; ---- the counter ----------------------------------------------------------------------------------------

(deftest counter-counts-frames-to-the-end-slide
  (is (= "Slide 1 / 12" (presenter/counter {:frame 0 :frames 12})))
  (is (= "Slide 7 / 12" (presenter/counter {:frame 6 :frames 12})))
  (is (= "Slide 3 / 10" (presenter/counter {:frame 2 :frames 24 :end-frame 9})))
  (is (= "Slide 23 / 20" (presenter/counter {:frame 22 :frames 24 :end-frame 19})) "past the end slide")
  (testing "an end frame outside the deck, or none: the last frame"
    (are [end] (= "Slide 3 / 24" (presenter/counter {:frame 2 :frames 24 :end-frame end}))
      24 -1 nil 2.5 "9"))
  (testing "nothing to count"
    (is (nil? (presenter/counter {:frame 0 :frames 0})))
    (is (nil? (presenter/counter {:frames 5})))
    (is (nil? (presenter/counter nil)))))

(deftest effective-end-frame-of-a-deck
  (are [frames end expected] (= expected (presenter/effective-end-frame frames end))
    10 3   3
    10 9   9
    10 nil 9
    10 10  9
    10 -1  9
    1  nil 0
    0  nil nil
    nil 3  nil))

(deftest overlay-counter-for-frames-with-overlays
  (is (= "overlay 2 / 3" (presenter/overlay-counter {:overlay 1 :overlays 3})))
  (is (= "overlay 1 / 2" (presenter/overlay-counter {:overlay 0 :overlays 2})))
  (is (nil? (presenter/overlay-counter {:overlay 0 :overlays 1})) "a one-page frame has no overlays to count")
  (is (nil? (presenter/overlay-counter {}))))

;; ---- End ------------------------------------------------------------------------------------------------

;; frames [0] [1 3] [4] [5 6] [7] [8 9]: six frames over ten pages
(def frames [[0 0] [1 3] [4 4] [5 6] [7 7] [8 9]])

(deftest end-goes-to-the-end-slide-first
  (testing "end frame 3 ([5 6]): from before its last page, to its last page"
    (are [page] (= 6 (presenter/end-target frames 3 page)) 0 2 4 5))
  (testing "from there, or beyond it, to the last page"
    (are [page] (= 9 (presenter/end-target frames 3 page)) 6 7 8 9))
  (testing "no end slide, the last frame, or one outside the deck: the last page at once"
    (are [end] (= 9 (presenter/end-target frames end 0)) nil 5 6 -1 2.5))
  (testing "a page that is not a number counts as page 0"
    (is (= 6 (presenter/end-target frames 3 nil))))
  (testing "note pages: page 2 is page 1's and page 4 page 3's, both hidden; frames hold shown pages"
    (let [frames [[0 0] [1 3] [5 5]]]
      (is (= 3 (presenter/end-target frames 1 2)) "a note page inside the end slide's frame is before its last page")
      (is (= 5 (presenter/end-target frames 1 4)) "the end slide's last note page is beyond it")))
  (testing "a deck without frames has nowhere to go"
    (is (nil? (presenter/end-target [] nil 0)))
    (is (nil? (presenter/end-target nil 2 0)))))

;; ---- the timer label ------------------------------------------------------------------------------------

(deftest hms-formats-signed-seconds
  (are [s text] (= text (presenter/hms s))
    0       "00:00:00"
    59      "00:00:59"
    60      "00:01:00"
    3599    "00:59:59"
    3600    "01:00:00"
    86399   "23:59:59"
    86400   "24:00:00"
    360000  "100:00:00"
    -1      "-00:00:01"
    -3661   "-01:01:01"
    (* -1 0) "00:00:00"
    1.9     "00:00:01"
    -1.9    "-00:00:01"
    -0.5    "00:00:00")
  (are [s] (nil? (presenter/hms s)) nil js/NaN js/Infinity "12"))

;; ---- the pane (ADR-0048 §6.1) ------------------------------------------------------------------------------------
;; The real LuaLaTeX `show notes` deck (test/fixtures/beamer-shownotes.pdf, as vinary.slides.notes-test reads it): ten
;; pages of Beamer's 128 mm × 96 mm paper, note pages {1 3 4 8}, so the audience's pages are 0 2 5 6 7 9 — four frames:
;; Intro [0 2] (two overlays), Middle [5], Outro [6 7] (two overlays), and Middle again [9].

(def ^:private paper [362.835 272.126])
(def ^:private whole {:x 0 :y 0 :w 362.835 :h 272.126})

(def ^:private shownotes
  {:creator "LaTeX with Beamer class" :producer "LuaTeX-1.18.0" :creator-tool nil :page-mode nil
   :labels ["1" "2" "1" "4" "5" "2" "3" "3" "9" "4"] :sizes (vec (repeat 10 paper)) :pdfpc nil})

(def ^:private sn-model (notes/initial shownotes nil))
(def ^:private sn-deck (deck/deck (merge shownotes (notes/deck-evidence sn-model :auto))))

(deftest next-label-names-the-next-slide
  (is (= "Next: slide 8 of 12" (presenter/next-label {:frame 7 :frames 12 :overlay 0 :overlays 1})))
  (is (= "Next: slide 1 of 4, overlay 2 of 2" (presenter/next-label {:frame 0 :frames 4 :overlay 1 :overlays 2})))
  (is (= "Next: slide 4 of 3" (presenter/next-label {:frame 3 :frames 5 :end-frame 2 :overlay 0 :overlays 1}))
      "counted as the counter counts: up to the end slide, and past it")
  (is (nil? (presenter/next-label {:frames 0})) "nothing to count"))

(deftest the-pane-of-a-show-notes-deck
  (testing "the first overlay of Intro: its note page, then its text notes; the next step is Intro's second overlay"
    (is (= {:page 0 :counter "Slide 1 / 4" :overlay-counter "overlay 1 / 2"
            :regions [{:page 1 :rect whole}]
            :sidecar-note nil
            :next {:page 2 :rect whole :label "Next: slide 1 of 4, overlay 2 of 2"}
            :deck-notes? true}
           (presenter/pane sn-deck (:sizes shownotes) sn-model :auto nil 0))))
  (testing "Intro's second overlay has two note pages after it (an in-frame note and a between-frames one)"
    (let [p (presenter/pane sn-deck (:sizes shownotes) sn-model :auto nil 2)]
      (is (= [{:page 3 :rect whole} {:page 4 :rect whole}] (:regions p)))
      (is (= "overlay 2 / 2" (:overlay-counter p)))
      (is (= {:page 5 :rect whole :label "Next: slide 2 of 4"} (:next p)))))
  (testing "a note page shows as its slide"
    (is (= (presenter/pane sn-deck (:sizes shownotes) sn-model :auto nil 2)
           (presenter/pane sn-deck (:sizes shownotes) sn-model :auto nil 3))))
  (testing "a slide without note pages; the last slide has no next one"
    (let [p (presenter/pane sn-deck (:sizes shownotes) sn-model :auto nil 5)]
      (is (= [] (:regions p)))
      (is (= "Slide 2 / 4" (:counter p)))
      (is (nil? (:overlay-counter p)) "a one-page frame has no overlays to count"))
    (is (nil? (:next (presenter/pane sn-deck (:sizes shownotes) sn-model :auto nil 9))) "the end of the show"))
  (testing "a page that is not a number is the first page"
    (is (= 0 (:page (presenter/pane sn-deck (:sizes shownotes) sn-model :auto nil nil)))))
  (testing ":off shows every page as it is: no note pages, no regions"
    (let [d (deck/deck (merge shownotes (notes/deck-evidence sn-model :off)))
          p (presenter/pane d (:sizes shownotes) sn-model :off nil 0)]
      (is (= [] (:regions p)))
      (is (= {:page 1 :rect whole :label "Next: slide 2 of 9"} (:next p))
          "page 1 is a frame of its own again (the deck then has nine frames by its labels)"))))

(deftest the-pane-of-a-split-deck
  (let [doubled {:creator "LaTeX with Beamer class" :producer "LuaTeX-1.18.0" :creator-tool nil :page-mode nil
                 :labels nil :sizes [[725.67 272.126] [725.67 272.126]] :pdfpc nil}
        m       (notes/initial doubled nil)
        d       (deck/deck doubled)
        side    (deck/slide-side (:second-screen d) (notes/notes-position :auto (get-in m [:pdfpc :notes-position])))
        p       (presenter/pane d (:sizes doubled) m :auto side 0)]
    (is (= :left side) "notes on the right by default: the audience sees the left half")
    (is (= [{:page 0 :rect {:x 362.835 :y 0 :w 362.835 :h 272.126}}] (:regions p)) "the notes half of the page")
    (is (= {:page 1 :rect {:x 0 :y 0 :w 362.835 :h 272.126} :label "Next: slide 2 of 2"} (:next p))
        "the next slide is the audience's half of the next page")))

(deftest the-pane-carries-the-sidecar-note-and-the-deck-flag
  (let [m (assoc-in sn-model [:sidecar :notes] {0 "From the sidecar"})]
    (is (= "From the sidecar" (:sidecar-note (presenter/pane sn-deck (:sizes shownotes) m :auto nil 0))))
    (is (nil? (:sidecar-note (presenter/pane sn-deck (:sizes shownotes) m :auto nil 5)))))
  (let [plain {:creator "LaTeX with Beamer class" :producer "LuaTeX-1.18.0" :creator-tool nil :page-mode nil
               :labels ["1" "2" "3"] :sizes (vec (repeat 3 paper)) :pdfpc nil}
        m     (notes/initial plain nil)
        p     (presenter/pane (deck/deck plain) (:sizes plain) m :auto nil 0)]
    (is (false? (:deck-notes? p)) "a deck without notes still gets the counter and the next slide")
    (is (= "Slide 1 / 3" (:counter p)))
    (is (= 1 (get-in p [:next :page])))))
