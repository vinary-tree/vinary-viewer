(ns vinary.app.slides-link-test
  "A window's link model (ADR-0048 §8.4–§8.6; theory/11 §3): the presenter's request and its answer, the audience's
   join and mount, the interceptor's question (`outgoing`) under each of its conditions — `applying` included — and
   the report that ends a push being shown, a push shown, held or dropped, an ack that releases or forgets a held push,
   the presenter leaving its deck and coming back (away, resume), the end of a link in every way, and the routing of
   main's messages. One worked race at the end runs both windows
   and main's pure sequencer through crossing moves; the randomized simulation (vinary.app.slides-link-sim-test)
   runs every such race."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.app.slides-link :as link]
            [vinary.main.slides-link :as main]))

(def ^:private deck "/d/talk.pdf")
(def ^:private sid "show-1")
(def ^:private request {:path deck :page 2 :blank nil :fullscreen true})

(defn- mirror
  "[:ui :slides] as the engine last reported it: presenting `deck` at `page`, with app-db's `blank` colour."
  ([page] (mirror page nil))
  ([page blank] {:path deck :layout :slides :page page :blank blank :presenting? true}))

(defn- joined
  "Main's joined message for show-1, at page 2 and seq 0 on a separate display, with `changes`."
  ([] (joined {}))
  ([changes] (merge {:session sid :seq 0 :role "presenter" :page 2 :blank nil :joined true :display "separate"}
                    changes)))

(defn- opening [] (:link (link/request-open nil request)))

(defn- presenter
  "The presenter's link, live in show-1 at page 2 (seq 0), nothing on the way."
  []
  (:link (link/on-joined (opening) (joined) (mirror 2))))

(defn- joining
  "The audience's link as vv:open-files {link} starts it, at page 2 and seq 0."
  []
  (link/link-join {:session sid :seq 0 :role "audience" :page 2 :blank nil :fullscreen true} deck))

(defn- push [s page blank] {:session sid :seq s :page page :blank blank})
(defn- ack [s mid accepted?] {:session sid :seq s :ack mid :accepted accepted?})

;; ---- positions and messages ---------------------------------------------------------------------------------------

(deftest positions-and-wire-colours
  (is (= {:page 3 :blank nil} (link/position 3 nil)) "both keys, always: positions compare whole")
  (is (= "black" (link/blank->wire :black)))
  (is (= "white" (link/blank->wire :white)))
  (is (nil? (link/blank->wire nil)))
  (is (nil? (link/blank->wire :purple)))
  (is (= :black (link/wire->blank "black")))
  (is (= :white (link/wire->blank "white")))
  (is (nil? (link/wire->blank nil)))
  (is (nil? (link/wire->blank "red")) "anything else is no blanking")
  (doseq [b [nil :black :white]]
    (is (= b (link/wire->blank (link/blank->wire b))) (pr-str b))))

(deftest every-message-of-vv-presenter-state-is-recognised
  (is (= :refused (link/message-kind {:session nil :refused "busy"})))
  (is (= :ended (link/message-kind {:session sid :ended true :stop true :reason "stopped"})))
  (is (= :joined (link/message-kind (joined))) "a joined message carries a page too, and is still joined")
  (is (= :swapped (link/message-kind {:session sid :swapped false :why "busy"})))
  (is (= :peer (link/message-kind {:session sid :peer "live"})))
  (is (= :ack (link/message-kind (ack 1 1 true))))
  (is (= :push (link/message-kind (push 1 3 nil))))
  (doseq [x [nil "push" [] {} {:session sid} {:session sid :ended false}]]
    (is (nil? (link/message-kind x)) (pr-str x))))

;; ---- the presenter opens a show -----------------------------------------------------------------------------------

(deftest shift-s-asks-main-for-a-show
  (testing "no link: a new opening link, and the request — which carries the fullscreen preference for the audience
            window; the presenter's link does not keep it (it never asks for its own fullscreen, ADR-0048 §8.1), but
            knows the position it asked for: a presenter that leaves its deck before main answers resumes there"
    (is (= {:link {:phase :opening :role :presenter :path deck :known {:page 2 :blank nil}}
            :open {:path deck :page 2 :blank nil :fullscreen true}}
           (link/request-open nil request))))
  (testing "the blank colour travels as a string, fullscreen as a boolean"
    (is (= {:path deck :page 0 :blank "white" :fullscreen false}
           (:open (link/request-open nil {:path deck :page 0 :blank :white :fullscreen nil})))))
  (testing "after a link ended, a new one"
    (is (= :opening (:phase (:link (link/request-open (link/ended (presenter) "stopped" true) request))))))
  (testing "while opening, nothing: a second request would be refused as busy, and the refusal would end this one"
    (is (= {:link (opening)} (link/request-open (opening) request))))
  (testing "a live presenter asks again: main re-sends the joined state and raises the audience window"
    (is (= {:link (presenter) :open {:path deck :page 2 :blank nil :fullscreen true}}
           (link/request-open (presenter) request))))
  (testing "an audience window, joining or live, takes part in one show only"
    (is (= {:link (joining)} (link/request-open (joining) request)))
    (let [live (:link (link/deck-mounted (joining) deck))]
      (is (= {:link live} (link/request-open live request))))))

(deftest the-presenter-goes-live-when-main-answers
  (let [{:keys [link move] :as r} (link/on-joined (opening) (joined) (mirror 2))]
    (is (= {:phase :live :role :presenter :path deck :session sid :seq 0 :own-seq 0
            :known {:page 2 :blank nil} :out #{} :held nil :applying nil :next-mid 1 :display "separate"
            :peer :waiting}
           link)
        "known is the session's start position")
    (is (nil? move) "it has not moved since it asked: the interceptor sends nothing")
    (is (not (contains? r :fullscreen?)) "a separate display, and its preference: still no fullscreen of its own"))
  (testing "a key pressed while the link was being made is sent like any person's move"
    (let [{:keys [link move]} (link/on-joined (opening) (joined) (mirror 3 :black))]
      (is (= {:session sid :mid 1 :page 3 :blank "black"} move))
      (is (= {:page 3 :blank :black} (:known link)))
      (is (= #{1} (:out link)))
      (is (= 2 (:next-mid link)))))
  (testing "the presenter never asks for its own fullscreen, on any display (ADR-0048 §8.1): it keeps its chrome"
    (doseq [display ["separate" "same" "manual"]]
      (is (not (contains? (link/on-joined (opening) (joined {:display display}) (mirror 2)) :fullscreen?)) display))
    (let [no-pref (:link (link/request-open nil (assoc request :fullscreen false)))]
      (is (not (contains? (link/on-joined no-pref (joined) (mirror 2)) :fullscreen?)))))
  (testing "a joined for its own session again (Shift+S while linked) refreshes the display only"
    (is (= {:link (assoc (presenter) :display "manual")}
           (link/on-joined (presenter) (joined {:display "manual" :seq 4 :page 9}) (mirror 7)))))
  (testing "a joined for a link this window gave up is answered with a leave, so main closes the audience window"
    (let [gave-up (:link (link/leave (opening) "stopped"))]
      (is (= {:link gave-up :leave {:session sid :reason "stopped"}} (link/on-joined gave-up (joined) (mirror 2)))))
    (is (= {:link nil :leave {:session sid :reason "stopped"}} (link/on-joined nil (joined) (mirror 2))))))

;; ---- the audience joins -------------------------------------------------------------------------------------------

(deftest the-audience-window-joins-at-the-link's-position
  (is (= {:phase :joining :role :audience :session sid :path deck :seq 3 :own-seq 0 :out #{} :held nil
          :applying nil :next-mid 1 :start {:seq 3 :pos {:page 5 :blank :white}} :fullscreen true}
         (link/link-join {:session sid :seq 3 :role "audience" :page 5 :blank "white" :fullscreen true} deck)))
  (is (= {:seq 0 :pos {:page 0 :blank nil}} (:start (link/link-join {:session sid :page 0} deck))) "seq 0 if absent")
  (is (false? (:fullscreen (link/link-join {:session sid :seq 0 :page 0} deck)))))

(deftest pushes-while-joining-only-move-the-start
  (let [{l :link :as r} (link/on-push (joining) (push 4 6 "black"))]
    (is (= {:seq 4 :pos {:page 6 :blank :black}} (:start l)))
    (is (= 4 (:seq l)))
    (is (nil? (:show r)) "the deck is not showing yet")
    (is (= {:link l} (link/on-push l (push 4 9 nil))) "the stale guard: not newer")
    (is (= {:link l} (link/on-push l (push 2 9 nil))))
    (is (= {:link l} (link/on-push l (assoc (push 9 9 nil) :session "show-2"))) "another show's")))

(deftest the-audience's-deck-mounts-and-its-show-starts-at-the-start
  (let [moved                              (:link (link/on-push (joining) (push 4 6 "black")))
        {:keys [link show ready fullscreen?]} (link/deck-mounted moved deck)]
    (is (= {:page 6 :blank :black} show) "the newest position main told it")
    (is (= {:session sid} ready) "it says ready, which moves nothing — it never announces its start as a move")
    (is (true? fullscreen?))
    (is (= {:phase :live :role :audience :session sid :path deck :seq 4 :own-seq 0 :out #{} :held nil :applying 6
            :next-mid 1 :known {:page 6 :blank :black} :fullscreen true}
           link))
    (testing "its first presenting report, of the start page, sends nothing"
      (let [after (link/reported link {:path deck :page 6})]
        (is (nil? (:applying after)))
        (is (nil? (link/outgoing after (mirror 6 :black))))))
    (testing "a report of a page its engine snapped is a correction"
      (is (= {:session sid :mid 1 :page 5 :blank "black"}
             (:move (link/outgoing (link/reported link {:path deck :page 5}) (mirror 5 :black))))))
    (testing "a blank key before its engine has presented waits for that report"
      (is (nil? (link/outgoing link (assoc (mirror nil :white) :presenting? false))))
      (is (= {:session sid :mid 1 :page 6 :blank "white"}
             (:move (link/outgoing (link/reported link {:path deck :page 6}) (mirror 6 :white)))))))
  (testing "nothing happens for another deck, or a link that is not joining"
    (is (= {:link (joining)} (link/deck-mounted (joining) "/d/other.pdf")))
    (is (= {:link (presenter)} (link/deck-mounted (presenter) deck)))
    (is (= {:link nil} (link/deck-mounted nil deck))))
  (is (false? (:fullscreen? (link/deck-mounted (link/link-join {:session sid :seq 0 :page 0 :fullscreen false} deck)
                                               deck)))))

;; ---- what a window sends ------------------------------------------------------------------------------------------

(deftest a-local-change-sends-a-move
  (let [{:keys [link move]} (link/outgoing (presenter) (mirror 3))]
    (is (= {:session sid :mid 1 :page 3 :blank nil} move))
    (is (= {:page 3 :blank nil} (:known link)) "what it sent is what it last exchanged")
    (is (= #{1} (:out link)))
    (is (= 2 (:next-mid link)))
    (testing "the next move gets the next mid, and both are on the way"
      (let [{l2 :link m2 :move} (link/outgoing link (mirror 3 :white))]
        (is (= {:session sid :mid 2 :page 3 :blank "white"} m2))
        (is (= #{1 2} (:out l2)))))))

(deftest nothing-is-sent-unless-the-window-presents-its-linked-deck-somewhere-new
  (let [p (presenter)]
    (is (nil? (link/outgoing p (mirror 2))) "the position it last exchanged")
    (is (nil? (link/outgoing p (assoc (mirror 3) :presenting? false))) "the engine has not applied the show's start")
    (is (nil? (link/outgoing p (dissoc (mirror 3) :presenting?))))
    (is (nil? (link/outgoing p (assoc (mirror 3) :layout :document))) "the document layout")
    (is (nil? (link/outgoing p (assoc (mirror 3) :path "/d/other.pdf"))) "another deck")
    (is (nil? (link/outgoing p (mirror nil))) "no page reported")
    (doseq [l [nil (opening) (joining) (link/ended p "stopped" false)]]
      (is (nil? (link/outgoing l (mirror 3))) (pr-str (:phase l))))))

(deftest a-blank-key-while-a-push-is-shown-sends-the-page-being-shown
  ;; The mirror holds the page from before the push until the engine reports (theory/11 §3.2, control N-applying).
  (let [{shown :link} (link/on-push (presenter) (push 1 5 nil))]
    (is (= 5 (:applying shown)))
    (is (= {:session sid :mid 1 :page 5 :blank "black"} (:move (link/outgoing shown (mirror 2 :black))))
        "page 5, not the stale 2 — which would send the push back")
    (testing "the report ends the push being shown, and following sends nothing"
      (let [after (link/reported shown {:path deck :page 5})]
        (is (nil? (:applying after)))
        (is (nil? (link/outgoing after (mirror 5))))))))

(deftest a-report-ends-the-push-being-shown
  (let [{shown :link} (link/on-push (presenter) (push 1 5 nil))]
    (is (nil? (:applying (link/reported shown {:path deck :page 5}))))
    (is (nil? (:applying (link/reported shown {:path deck :page 4}))) "whatever page it reports: a snapped one too")
    (is (= shown (link/reported shown {:path "/d/other.pdf" :page 5})) "a report of another deck changes nothing")
    (is (= (presenter) (link/reported (presenter) {:path deck :page 2})) "no push being shown")))

;; ---- what a window receives ---------------------------------------------------------------------------------------

(deftest a-push-is-shown-when-nothing-of-the-window's-own-is-on-the-way
  (let [r (link/on-push (presenter) (push 1 5 "white"))]
    (is (= {:page 5 :blank :white} (:show r)))
    (is (= (assoc (presenter) :seq 1 :known {:page 5 :blank :white} :applying 5) (:link r))
        "known is what it was told; applying holds the page until the engine reports")
    (is (= [:slides/command [:show 5]] (link/show-effect (:show r))) "the effect, not the event (which would un-blank)")))

(deftest a-push-is-held-while-the-window's-own-move-is-on-the-way
  (let [{sent :link} (link/outgoing (presenter) (mirror 3))
        r            (link/on-push sent (push 1 5 nil))]
    (is (nil? (:show r)) "no flicker back to a slide main may have superseded")
    (is (= {:seq 1 :pos {:page 5 :blank nil}} (:held (:link r))))
    (is (= {:page 3 :blank nil} (:known (:link r))))
    (is (= 1 (:seq (:link r))))
    (testing "a newer push replaces the held one"
      (is (= {:seq 2 :pos {:page 6 :blank :black}} (:held (:link (link/on-push (:link r) (push 2 6 "black")))))))))

(deftest stale-and-foreign-pushes-are-dropped
  (let [p (assoc (presenter) :seq 4)]
    (is (= {:link p} (link/on-push p (push 4 5 nil))) "not newer than what it has seen (ADR-0046's guard)")
    (is (= {:link p} (link/on-push p (push 3 5 nil))))
    (is (= {:link p} (link/on-push p (assoc (push 9 5 nil) :session "show-2"))) "another show's")
    (doseq [l [nil (opening) (link/ended p "stopped" true)]]
      (is (= {:link l} (link/on-push l (push 9 5 nil))) (pr-str (:phase l))))))

(deftest an-ack-ends-the-wait-for-its-move
  (let [{sent :link} (link/outgoing (presenter) (mirror 3))]
    (testing "accepted: its number is the window's own newest, and the newest it has seen"
      (is (= (assoc sent :out #{} :own-seq 7 :seq 7) (:link (link/on-ack sent (ack 7 1 true))))))
    (testing "not accepted: only the wait ends"
      (is (= (assoc sent :out #{}) (:link (link/on-ack sent (ack 0 1 false))))))
    (testing "an ack never lowers what the window has seen"
      (is (= 9 (:seq (:link (link/on-ack (assoc sent :seq 9) (ack 7 1 true)))))))
    (testing "one ack of two moves on the way"
      (let [{two :link} (link/outgoing sent (mirror 4))]
        (is (= #{2} (:out (:link (link/on-ack two (ack 1 1 true))))))))
    (testing "an ack of a mid it is not waiting for ends nothing"
      (is (= #{1} (:out (:link (link/on-ack sent (ack 0 5 false)))))))
    (testing "ignored: another show's, or a link that is not live"
      (is (= {:link sent} (link/on-ack sent (assoc (ack 1 1 true) :session "show-2"))))
      (is (= {:link (joining)} (link/on-ack (joining) (ack 1 1 true))))
      (is (= {:link nil} (link/on-ack nil (ack 1 1 true)))))))

(deftest a-held-push-is-shown-only-when-newer-than-the-window's-own-last-accepted-move
  (let [{sent :link} (link/outgoing (presenter) (mirror 3))]
    (testing "main numbered the push after the move: show it"
      (let [{held :link} (link/on-push sent (push 2 5 nil))
            r            (link/on-ack held (ack 1 1 true))]
        (is (= {:page 5 :blank nil} (:show r)))
        (is (nil? (:held (:link r))))
        (is (= {:page 5 :blank nil} (:known (:link r))))
        (is (= 5 (:applying (:link r))))
        (is (= 2 (:seq (:link r))) "it saw push 2")
        (is (= 1 (:own-seq (:link r))))))
    (testing "main numbered the push before the move: the window's own move is main's state, so forget the push"
      (let [{held :link} (link/on-push sent (push 1 5 nil))
            r            (link/on-ack held (ack 2 1 true))]
        (is (nil? (:show r)))
        (is (nil? (:held (:link r))))
        (is (= {:page 3 :blank nil} (:known (:link r))))))
    (testing "a move main did not accept leaves the window's own newest number where it was"
      (let [{held :link} (link/on-push sent (push 2 5 nil))]
        (is (= {:page 5 :blank nil} (:show (link/on-ack held (ack 0 1 false)))))))
    (testing "while another of its moves is on the way, the push stays held"
      (let [{held :link} (link/on-push sent (push 2 5 nil))
            {two :link}  (link/outgoing held (mirror 4))
            r            (link/on-ack two (ack 1 1 true))]
        (is (nil? (:show r)))
        (is (= {:seq 2 :pos {:page 5 :blank nil}} (:held (:link r))))))))

(deftest showing-a-position
  (let [r (link/show (presenter) {:page 4 :blank :black})]
    (is (= {:page 4 :blank :black} (:show r)))
    (is (= {:page 4 :blank :black} (:known (:link r))))
    (is (= 4 (:applying (:link r)))))
  (is (= [:slides/command [:show 4]] (link/show-effect {:page 4 :blank :black}))))

;; ---- away and back (theory/11 §3.5, ADR-0048 §8.10) ---------------------------------------------------------------

(deftest the-presenter-leaves-its-deck-and-comes-back
  (let [gone (:link (link/away (presenter)))]
    (testing "its deck view unmounted while live: the link stays, away; a push being shown is forgotten — its engine
              is gone — and nothing is sent"
      (let [showing (:link (link/show (presenter) {:page 4 :blank nil}))
            r       (link/away showing)]
        (is (= [:link] (keys r)))
        (is (true? (link/away? (:link r))))
        (is (nil? (:applying (:link r))))
        (is (= (dissoc showing :applying) (dissoc (:link r) :away? :applying)) "the rest of the link is as it was")))
    (testing "only a presenter's opening or live link goes away"
      (doseq [l [nil (joining) (:link (link/deck-mounted (joining) deck)) (link/ended (presenter) "stopped" false)]]
        (is (= {:link l} (link/away l)) (pr-str (:role l) (:phase l)))))
    (testing "away, a push is recorded as known — the show's position — and nothing shows it"
      (let [r (link/on-push gone (push 1 5 "black"))]
        (is (not (contains? r :show)))
        (is (= {:page 5 :blank :black} (:known (:link r))))
        (is (= 1 (:seq (:link r))))
        (is (nil? (:applying (:link r))))))
    (testing "away, nothing is sent, whatever the mirror says"
      (doseq [m [(mirror 3) (mirror 2 :black) (mirror 2)]]
        (is (nil? (link/outgoing gone m)) (pr-str m))))
    (testing "back: the show resumes at known, :applying holds its page, and nothing is sent"
      (let [moved (:link (link/on-push gone (push 1 5 "black")))
            r     (link/resume moved)]
        (is (= #{:link :show} (set (keys r))))
        (is (= {:page 5 :blank :black} (:show r)))
        (is (not (contains? (:link r) :away?)))
        (is (= 5 (:applying (:link r))))
        (is (nil? (link/outgoing (link/reported (:link r) (mirror 5 :black)) (mirror 5 :black)))
            "the new engine's first report, of that page, sends nothing (Lemma 3)")
        (is (= {:session sid :mid 1 :page 4 :blank "black"}
               (:move (link/outgoing (link/reported (:link r) (mirror 4 :black)) (mirror 4 :black))))
            "a first report of a page its engine snapped to is a correction (Theorem 5)")))
    (testing "a link that is not away does not resume"
      (is (= {:link (presenter)} (link/resume (presenter)))))
    (testing "an unmount while the link opens: main's joined makes it live AND away, and nothing is sent"
      (let [l                         (:link (link/away (opening)))
            {:keys [link move] :as r} (link/on-joined l (joined) nil)]
        (is (= {:page 2 :blank nil} (:known l)) "an opening link knows the position it asked main to open at")
        (is (link/away? l))
        (is (= :live (:phase link)))
        (is (link/away? link))
        (is (nil? move))
        (is (= [:link] (keys r)))
        (testing "…and comes back to the position it asked for, with nothing to apply while the link opens"
          (is (= {:page 2 :blank nil} (:show (link/resume l))))
          (is (nil? (:applying (:link (link/resume l))))))))
    (testing "the show it left ends with its link: any end of an away presenter's link stops its show"
      (is (true? (:stop? (link/on-ended gone {:session sid :ended true :stop false :reason "audience-left"}))))
      (let [opening-away (:link (link/away (opening)))]
        (is (true? (:stop? (link/on-refused opening-away {:session nil :refused "busy"}))))
        (is (true? (:stop? (link/on-ended opening-away {:session sid :ended true :stop false :reason "no-window"})))))
      (is (not (contains? (link/on-refused (opening) {:session nil :refused "busy"}) :stop?))
          "a window that did not leave its deck keeps its show, as before"))))

;; ---- the end of a link, and the rest of main's messages -----------------------------------------------------------

(deftest how-a-link-ends
  (testing "main's ended message for this link's session"
    (is (= {:link {:phase :ended :role :presenter :session sid :path deck :reason "audience-left" :stop? false}
            :stop? false}
           (link/on-ended (presenter) {:session sid :ended true :stop false :reason "audience-left"})))
    (let [live (:link (link/deck-mounted (joining) deck))]
      (is (= {:link {:phase :ended :role :audience :session sid :path deck :reason "stopped" :stop? true} :stop? true}
             (link/on-ended live {:session sid :ended true :stop true :reason "stopped"}))))
    (is (true? (:stop? (link/on-ended (joining) {:session sid :ended true :stop true :reason "presenter-left"})))
        "while joining too"))
  (testing "an opening link knows no session id yet: it takes the no-window end of the session main began for it"
    (is (= {:link {:phase :ended :role :presenter :session nil :path deck :reason "no-window" :stop? false}
            :stop? false}
           (link/on-ended (opening) {:session sid :ended true :stop false :reason "no-window"})))
    (is (= {:link (opening)} (link/on-ended (opening) {:session sid :ended true :stop true :reason "stopped"}))))
  (testing "a late ended message about another show is ignored"
    (is (= {:link (presenter)} (link/on-ended (presenter) {:session "show-0" :ended true :stop true :reason "stopped"})))
    (let [done (link/ended (presenter) "stopped" false)]
      (is (= {:link done} (link/on-ended done {:session sid :ended true :stop true :reason "stopped"}))))))

(deftest a-refusal-ends-an-opening-link
  (doseq [reason ["busy" "not-focused" "not-retained" "invalid"]]
    (is (= {:link {:phase :ended :role :presenter :session nil :path deck :reason reason :stop? false}}
           (link/on-refused (opening) {:session nil :refused reason}))
        reason))
  (is (= {:link (presenter)} (link/on-refused (presenter) {:session nil :refused "busy"})) "a live link was not refused"))

(deftest the-window-leaves
  (is (= {:link (link/ended (presenter) "stopped" false) :leave {:session sid :reason "stopped"}}
         (link/leave (presenter) "stopped")))
  (is (= {:session sid :reason "unmounted"} (:leave (link/leave (joining) "unmounted"))))
  (testing "an opening link has no session to leave: it just ends, and main's joined is answered with the leave"
    (is (= {:link (link/ended (opening) "stopped" false)} (link/leave (opening) "stopped"))))
  (is (= {:link nil} (link/leave nil "stopped")))
  (let [done (link/ended (presenter) "stopped" false)]
    (is (= {:link done} (link/leave done "unmounted")))))

(deftest an-ended-link-keeps-only-what-the-window-shows
  (is (= {:phase :ended :role :presenter :session sid :path deck :reason "stopped" :stop? true}
         (link/ended (presenter) "stopped" :yes)))
  (is (link/ended? (link/ended (presenter) "stopped" false)))
  (is (not (link/ended? (presenter))))
  (is (link/live? (presenter)))
  (is (not (link/live? (opening))))
  (is (not (link/live? (joining)))))

(deftest the-presenter-hears-that-the-audience-is-showing
  (is (= :waiting (:peer (presenter))))
  (is (= :live (:peer (:link (link/on-peer (presenter) {:session sid :peer "live"})))))
  (is (= {:link (presenter)} (link/on-peer (presenter) {:session "show-2" :peer "live"})))
  (is (= {:link (presenter)} (link/on-peer (presenter) {:session sid :peer "gone"})))
  (is (= {:link (opening)} (link/on-peer (opening) {:session nil :peer "live"}))))

(deftest main's-answer-to-swap-screens-is-kept-for-the-pane
  (is (= {:swapped true :lost ["audience"]}
         (:swap (:link (link/on-swapped (presenter) {:session sid :swapped true :lost ["audience"]})))))
  (is (= {:swapped false :why "same-display"}
         (:swap (:link (link/on-swapped (presenter) {:session sid :swapped false :why "same-display"})))))
  (is (= {:link (presenter)} (link/on-swapped (presenter) {:session "show-2" :swapped true :lost []}))))

(deftest main's-messages-are-routed-by-kind
  (let [p (presenter)
        m (mirror 2)
        {sent :link} (link/outgoing p (mirror 3))]
    (is (= (link/on-push p (push 1 5 nil)) (link/on-state p (push 1 5 nil) m)))
    (is (= (link/on-ack sent (ack 1 1 true)) (link/on-state sent (ack 1 1 true) m)))
    (is (= (link/on-peer p {:session sid :peer "live"}) (link/on-state p {:session sid :peer "live"} m)))
    (is (= (link/on-swapped p {:session sid :swapped false :why "busy"})
           (link/on-state p {:session sid :swapped false :why "busy"} m)))
    (is (= (link/on-ended p {:session sid :ended true :stop true :reason "stopped"})
           (link/on-state p {:session sid :ended true :stop true :reason "stopped"} m)))
    (is (= (link/on-refused (opening) {:session nil :refused "busy"})
           (link/on-state (opening) {:session nil :refused "busy"} m)))
    (is (= (link/on-joined (opening) (joined) m) (link/on-state (opening) (joined) m)))
    (is (= {:link p} (link/on-state p {:what "else"} m)))
    (is (= {:link p} (link/on-state p nil m)))))

(deftest following-never-sends
  ;; theory/11 Theorem 1, at the level of the code: no incoming message makes a live window send a move. Only an
  ;; opening link's joined runs the interceptor, once.
  (let [{sent :link}  (link/outgoing (presenter) (mirror 3))
        {held :link}  (link/on-push sent (push 1 5 nil))
        links         [(presenter) sent held (joining) (:link (link/deck-mounted (joining) deck))]
        messages      [(push 1 5 nil) (push 2 6 "black") (ack 1 1 true) (ack 0 1 false) (ack 3 1 true)
                       {:session sid :peer "live"} {:session sid :swapped true :lost []} (joined)
                       {:session sid :ended true :stop true :reason "stopped"} {:session nil :refused "busy"}]]
    (doseq [l links msg messages]
      (let [r (link/on-state l msg (mirror 9 :white))]
        (is (nil? (:move r)) (pr-str [(:phase l) msg]))
        (is (nil? (:open r)))))))

;; ---- a worked race --------------------------------------------------------------------------------------------------

(deftest crossing-moves-end-on-main's-last-move
  ;; The race behind control N-hold (theory/11 §6.3), with every rule in place. The presenter steps to page 1 while
  ;; the audience window is still loading; the audience's deck mounts at the start (page 0) before push 1 reaches it,
  ;; and its person blanks. Main numbers the presenter's step 1 and the blank 2. Push 1 then reaches the audience
  ;; while its own blank is on the way: it is held, and forgotten when the blank's ack (2) shows that the audience's
  ;; own move is main's state. Both windows end on (page 0, black).
  (let [a 101, b 202
        {m :state :as opened} (main/open main/empty-state a {:path deck :page 0 :blank nil :fullscreen false}
                                         {:focused? true :retains? true})
        id                    (get-in opened [:claim :session])
        {m :state out :out link-b :link} (main/claimed m id b {:display "separate"})
        la                    (:link (link/on-joined (:link (link/request-open nil {:path deck :page 0}))
                                                     (second (first out)) (mirror 0)))
        lb                    (link/link-join link-b deck)
        ;; the presenter's person steps to page 1; its report sends move 1
        {la :link move-a :move} (link/outgoing la (mirror 1))
        ;; the audience's deck mounts at the start and reports it; its person presses B
        {lb :link}            (link/deck-mounted lb deck)
        lb                    (link/reported lb {:path deck :page 0})
        {lb :link move-b :move} (link/outgoing lb (mirror 0 :black))
        ;; main numbers the presenter's step, then the blank
        {m :state out-1 :out} (main/move m a move-a)
        {m :state out-2 :out} (main/move m b move-b)
        to                    (fn [w] (into [] (comp (filter (fn [[target _]] (= w target))) (map second))
                                            (concat out-1 out-2)))
        ;; each window takes its messages in the order main sent them
        deliver               (fn [l msgs] (reduce (fn [[l shown] msg]
                                                     (let [r (link/on-state l msg (mirror 0))]
                                                       [(:link r) (cond-> shown (:show r) (conj (:show r)))]))
                                                   [l []] msgs))
        [la shown-a]          (deliver la (to a))
        [lb shown-b]          (deliver lb (to b))
        {:keys [page blank]}  (main/session m id)]
    (is (= [{:session id :seq 1 :ack 1 :accepted true} {:session id :seq 2 :page 0 :blank "black"}] (to a)))
    (is (= [{:session id :seq 1 :page 1 :blank nil} {:session id :seq 2 :ack 1 :accepted true}] (to b)))
    (is (= [0 "black"] [page blank]) "main's last move is the audience's blank")
    (is (= [{:page 0 :blank :black}] shown-a) "the presenter shows the blank")
    (is (= [] shown-b) "the audience never shows push 1: it was held, then forgotten")
    (is (= {:page 0 :blank :black} (:known la) (:known lb)))
    (is (and (empty? (:out la)) (empty? (:out lb)) (nil? (:held la)) (nil? (:held lb))))))
