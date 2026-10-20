(ns vinary.main.slides-link-test
  "Main's linked-show sequencer (ADR-0048 §8.4, §8.6, §8.9; theory/11 §3.1): an open begins a claiming session and is
   refused for every reason in its order, a claim joins the audience window (or closes it when its show is gone),
   moves are numbered, acknowledged to their sender and pushed to every other member, an older mid is acknowledged
   but not accepted, every malformed or foreign message is dropped, `ready` moves nothing, every lifecycle row of
   §8.6 ends the session as the table says, messages go only to members, and Swap screens runs one at a time."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.main.slides-link :as sl]))

(def ^:private a 101)               ; the presenter window
(def ^:private b 202)               ; the audience window main claims
(def ^:private c 303)               ; a window in no show
(def ^:private deck "/d/talk.pdf")
(def ^:private ok {:focused? true :retains? true})
(def ^:private request {:path deck :page 2 :blank nil :fullscreen true})

(defn- claiming
  "A state with session show-1 claiming an audience window for presenter `a`."
  []
  (:state (sl/open sl/empty-state a request ok)))

(defn- active
  "A state with show-1 live: `a` presents, `b` was claimed on `display`."
  ([] (active "separate"))
  ([display] (:state (sl/claimed (claiming) "show-1" b {:display display}))))

(defn- run
  "Apply `steps` ([:move wc payload] | [:ready wc payload] | [:leave wc payload] | [:gone wc cause]) from `state`;
   → [state all-out]."
  [state steps]
  (reduce (fn [[s outs] [op wc x]]
            (let [{s' :state out :out} (case op
                                         :move  (sl/move s wc x)
                                         :ready (sl/ready s wc x)
                                         :leave (sl/leave s wc x)
                                         :gone  (sl/member-gone s wc x))]
              [s' (into outs out)]))
          [state []] steps))

;; ---- opening --------------------------------------------------------------------------------------------------

(deftest an-open-begins-a-claiming-session
  (let [{:keys [state out claim] :as r} (sl/open sl/empty-state a request ok)]
    (is (= [] out) "the presenter hears nothing until a window is claimed")
    (is (= {:session "show-1" :presenter a :path deck} claim))
    (is (= {:id "show-1" :phase :claiming :path deck :fullscreen true :seq 0 :page 2 :blank nil
            :members {a :presenter} :last-mid {a 0}}
           (sl/session state "show-1")))
    (is (= "show-1" (sl/session-of state a)) "the presenter is a member while the window is claimed")
    (is (nil? (sl/audience-of state "show-1")))
    (is (not (contains? r :link)))))

(deftest session-ids-count-up
  (let [s1 (active)
        s2 (:state (sl/claimed (:state (sl/open s1 c request ok)) "show-2" 404 {:display "same"}))]
    (is (= "show-2" (sl/session-of s2 c)))
    (is (= "show-1" (sl/session-of s2 a)) "two shows of two window pairs coexist")
    (testing "an id is never reused, even after its show ended"
      (let [{s3 :state} (sl/leave s2 a {:session "show-1" :reason "stopped"})]
        (is (= {:session "show-3" :presenter a :path deck} (:claim (sl/open s3 a request ok))))))))

(deftest an-open-is-refused-for-each-reason-in-order
  (let [refused (fn [state wc payload facts] (:out (sl/open state wc payload facts)))]
    (testing "a malformed request"
      (doseq [bad [nil "show" []
                   (dissoc request :path) (assoc request :path "") (assoc request :path 7)
                   (dissoc request :page) (assoc request :page -1) (assoc request :page 1000000)
                   (assoc request :page 2.5) (assoc request :page "2") (assoc request :page js/NaN)
                   (assoc request :blank "red") (assoc request :blank :black)
                   (assoc request :fullscreen "yes") (assoc request :fullscreen 1)]]
        (is (= [[a {:session nil :refused "invalid"}]] (refused sl/empty-state a bad ok)) (pr-str bad))))
    (testing "a well-formed request may leave out blank and fullscreen"
      (is (:claim (sl/open sl/empty-state a {:path deck :page 0} ok))))
    (testing "busy: a window takes part in at most one show, with at most one claim in flight"
      (is (= [[a {:session nil :refused "busy"}]] (refused (claiming) a request ok)) "its claim is in flight")
      (is (= [[b {:session nil :refused "busy"}]] (refused (active) b request ok)) "the audience window"))
    (is (= [[c {:session nil :refused "not-focused"}]] (refused (active) c request {:focused? false :retains? true})))
    (is (= [[c {:session nil :refused "not-retained"}]] (refused (active) c request {:focused? true :retains? false})))
    (testing "the order: invalid, then busy, then not-focused, then not-retained"
      (is (= "invalid" (-> (refused (claiming) a (dissoc request :path) {}) first second :refused)))
      (is (= "busy" (-> (refused (claiming) a request {}) first second :refused)))
      (is (= "not-focused" (-> (refused sl/empty-state a request {}) first second :refused))))
    (testing "a refusal changes nothing"
      (is (= (claiming) (:state (sl/open (claiming) a request ok)))))))

(deftest shift-s-again-while-linked-re-sends-the-joined-state-and-raises-the-audience-window
  (let [[s _] (run (active) [[:move b {:session "show-1" :mid 1 :page 5 :blank "black"}]])
        {:keys [state out raise claim]} (sl/open s a request ok)]
    (is (= [[a {:session "show-1" :seq 1 :role "presenter" :page 5 :blank "black" :joined true
                :display "separate"}]]
           out)
        "the session as it stands now")
    (is (= b raise))
    (is (nil? claim) "nothing new opens")
    (is (= s state) "nothing changes")))

;; ---- claiming -------------------------------------------------------------------------------------------------

(deftest a-claimed-window-joins-and-the-presenter-is-told
  (let [{:keys [state out audience link]} (sl/claimed (claiming) "show-1" b {:display "separate"})]
    (is (= [[a {:session "show-1" :seq 0 :role "presenter" :page 2 :blank nil :joined true :display "separate"}]]
           out))
    (is (= b audience))
    (is (= {:session "show-1" :seq 0 :role "audience" :page 2 :blank nil :fullscreen true} link)
        "vv:open-files {link}: the start position, and fullscreen on a separate display")
    (is (= {a :presenter b :audience} (sl/members state "show-1")))
    (is (= :active (:phase (sl/session state "show-1"))))
    (is (= "show-1" (sl/session-of state b))))
  (testing "fullscreen needs both the presenter's preference and a separate display"
    (is (false? (:fullscreen (:link (sl/claimed (claiming) "show-1" b {:display "same"})))) "one display")
    (is (false? (:fullscreen (:link (sl/claimed (claiming) "show-1" b {:display "manual"})))) "Wayland")
    (let [no-pref (:state (sl/open sl/empty-state a (assoc request :fullscreen false) ok))]
      (is (false? (:fullscreen (:link (sl/claimed no-pref "show-1" b {:display "separate"}))))))))

(deftest a-failed-claim-ends-the-session
  (let [{:keys [state out ended]} (sl/claim-failed (claiming) "show-1")]
    (is (= [[a {:session "show-1" :ended true :stop false :reason "no-window"}]] out)
        "the presenter keeps presenting, with a notice")
    (is (= {:session "show-1" :reason "no-window"} ended))
    (is (nil? (sl/session state "show-1")))
    (is (nil? (sl/session-of state a)) "the presenter may link again")
    (is (:claim (sl/open state a request ok))))
  (is (= {:state (active) :out []} (sl/claim-failed (active) "show-1")) "only a claiming session can fail its claim")
  (is (= {:state sl/empty-state :out []} (sl/claim-failed sl/empty-state "show-9"))))

(deftest a-window-claimed-for-a-show-that-is-over-is-closed
  (testing "the presenter's window closed while the audience window was being claimed"
    (let [{s :state out :out} (sl/member-gone (claiming) a :closed)]
      (is (= [] out) "nobody is left to tell")
      (is (nil? (sl/session s "show-1")))
      (is (= {:state s :out [] :close [b]} (sl/claimed s "show-1" b {:display "separate"})))))
  (testing "a second claim for a session that is already live"
    (is (= {:state (active) :out [] :close [404]} (sl/claimed (active) "show-1" 404 {:display "separate"}))))
  ;; The opening window itself sends no leave — it knows no session id yet — but a leave naming the session ends it.
  (testing "a leave naming a claiming session ends it"
    (let [{s :state close :close} (sl/leave (claiming) a {:session "show-1" :reason "stopped"})]
      (is (= [] close) "there is no audience window yet")
      (is (= [b] (:close (sl/claimed s "show-1" b {:display "separate"})))))))

(deftest a-claim-that-cannot-be-joined-fails-without-closing-a-window-that-is-not-its-own
  (testing "the claimed window is already a member (here: the presenter itself)"
    (let [{:keys [state out close]} (sl/claimed (claiming) "show-1" a {:display "separate"})]
      (is (= [[a {:session "show-1" :ended true :stop false :reason "no-window"}]] out))
      (is (nil? close) "the presenter's window is never closed")
      (is (nil? (sl/session state "show-1")))))
  (testing "an unknown arrangement: the claim fails and the claimed window is closed"
    (let [{:keys [out close]} (sl/claimed (claiming) "show-1" b {:display "sideways"})]
      (is (= "no-window" (-> out first second :reason)))
      (is (= [b] close)))))

;; ---- moves ----------------------------------------------------------------------------------------------------

(deftest a-move-is-numbered-acknowledged-to-its-sender-and-pushed-to-the-other
  (let [{:keys [state out]} (sl/move (active) a {:session "show-1" :mid 1 :page 3 :blank nil})]
    (is (= [[a {:session "show-1" :seq 1 :ack 1 :accepted true}]
            [b {:session "show-1" :seq 1 :page 3 :blank nil}]]
           out)
        "the ack to the sender only, the push to every other member")
    (is (= {:seq 1 :page 3 :blank nil} (select-keys (sl/session state "show-1") [:seq :page :blank])))
    (is (= {a 1 b 0} (:last-mid (sl/session state "show-1")))))
  (testing "either window moves; main's seq numbers both windows' moves in one order"
    (let [[s out] (run (active) [[:move a {:session "show-1" :mid 1 :page 3 :blank nil}]
                                 [:move b {:session "show-1" :mid 1 :page 1 :blank nil}]
                                 [:move a {:session "show-1" :mid 2 :page 1 :blank "white"}]])]
      (is (= [[a {:session "show-1" :seq 1 :ack 1 :accepted true}]
              [b {:session "show-1" :seq 1 :page 3 :blank nil}]
              [b {:session "show-1" :seq 2 :ack 1 :accepted true}]
              [a {:session "show-1" :seq 2 :page 1 :blank nil}]
              [a {:session "show-1" :seq 3 :ack 2 :accepted true}]
              [b {:session "show-1" :seq 3 :page 1 :blank "white"}]]
             out))
      (is (= {:seq 3 :page 1 :blank "white"} (select-keys (sl/session s "show-1") [:seq :page :blank])))))
  (testing "the boundaries of a valid move are accepted"
    (is (= 999999 (:page (sl/session (:state (sl/move (active) a {:session "show-1" :mid 1 :page 999999}))
                                     "show-1"))))
    (is (= 1 (:seq (sl/session (:state (sl/move (active) a {:session "show-1" :mid 1 :page 0 :blank "black"}))
                               "show-1"))))))

(deftest an-older-mid-is-acknowledged-but-not-accepted
  (let [[s _] (run (active) [[:move a {:session "show-1" :mid 2 :page 3 :blank nil}]])]
    (doseq [mid [1 2]]
      (let [{:keys [state out]} (sl/move s a {:session "show-1" :mid mid :page 0 :blank "black"})]
        (is (= [[a {:session "show-1" :seq 0 :ack mid :accepted false}]] out)
            "the sender stops waiting for it; the other window hears nothing")
        (is (= s state) "and nothing changes")))
    (testing "mids are per window: the other window's first move is new"
      (is (= 2 (:seq (sl/session (:state (sl/move s b {:session "show-1" :mid 1 :page 0})) "show-1")))))))

(deftest malformed-moves-are-dropped
  (let [s    (active)
        good {:session "show-1" :mid 1 :page 3 :blank nil}]
    (doseq [bad [nil "move" [] {}
                 (dissoc good :session) (assoc good :session 1) (assoc good :session "")
                 (dissoc good :mid) (assoc good :mid 0) (assoc good :mid -1) (assoc good :mid 1.5)
                 (assoc good :mid "1") (assoc good :mid js/NaN)
                 (dissoc good :page) (assoc good :page -1) (assoc good :page 1000000) (assoc good :page 2.5)
                 (assoc good :page "3") (assoc good :page js/Infinity)
                 (assoc good :blank "red") (assoc good :blank :black) (assoc good :blank true)]]
      (is (= {:state s :out []} (sl/move s a bad)) (pr-str bad)))))

(deftest moves-from-outside-the-session-are-dropped
  (let [s (active)]
    (is (= {:state s :out []} (sl/move s c {:session "show-1" :mid 1 :page 3})) "a window in no show")
    (is (= {:state s :out []} (sl/move s a {:session "show-9" :mid 1 :page 3})) "a session that does not exist")
    (let [two (:state (sl/claimed (:state (sl/open s c request ok)) "show-2" 404 {:display "same"}))]
      (is (= {:state two :out []} (sl/move two c {:session "show-1" :mid 1 :page 3}))
          "a member of another show naming this one")))
  (testing "a claiming session takes no moves (its presenter knows no session id until it is joined)"
    (is (= {:state (claiming) :out []} (sl/move (claiming) a {:session "show-1" :mid 1 :page 3})))))

(deftest ready-tells-the-presenter-and-moves-nothing
  (let [s (active)]
    (is (= {:state s :out [[a {:session "show-1" :peer "live"}]]} (sl/ready s b {:session "show-1"})))
    (testing "only the audience window of that live show says ready"
      (is (= {:state s :out []} (sl/ready s a {:session "show-1"})) "the presenter")
      (is (= {:state s :out []} (sl/ready s c {:session "show-1"})) "a stranger")
      (is (= {:state s :out []} (sl/ready s b {:session "show-2"})) "another show")
      (is (= {:state s :out []} (sl/ready s b {})) "malformed")
      (is (= {:state s :out []} (sl/ready s b nil))))))

;; ---- endings (ADR-0048 §8.6) -------------------------------------------------------------------------------------

(deftest every-way-a-show-ends
  ;; [who, what happened] → what the other member is told, and which windows main closes
  (doseq [[label step told close]
          [["Escape in the presenter's window" [:leave a {:session "show-1" :reason "stopped"}]
            [[b {:session "show-1" :ended true :stop true :reason "stopped"}]] [b]]
           ["Escape in the audience window" [:leave b {:session "show-1" :reason "stopped"}]
            [[a {:session "show-1" :ended true :stop true :reason "stopped"}]] [b]]
           ["the presenter's view unmounted" [:leave a {:session "show-1" :reason "unmounted"}]
            [[b {:session "show-1" :ended true :stop true :reason "presenter-left"}]] [b]]
           ["the audience's view unmounted" [:leave b {:session "show-1" :reason "unmounted"}]
            [[a {:session "show-1" :ended true :stop true :reason "audience-left" :page 2}]] [b]]
           ["the presenter's window closed" [:gone a :closed]
            [[b {:session "show-1" :ended true :stop true :reason "presenter-left"}]] [b]]
           ["the presenter's window reloaded" [:gone a :reloaded]
            [[b {:session "show-1" :ended true :stop true :reason "presenter-left"}]] [b]]
           ["the presenter's renderer crashed" [:gone a :crashed]
            [[b {:session "show-1" :ended true :stop true :reason "presenter-left"}]] [b]]
           ["the audience window closed" [:gone b :closed]
            [[a {:session "show-1" :ended true :stop true :reason "audience-left" :page 2}]] []]
           ["the audience window reloaded" [:gone b :reloaded]
            [[a {:session "show-1" :ended true :stop true :reason "audience-left" :page 2}]] [b]]
           ["the audience's renderer crashed" [:gone b :crashed]
            [[a {:session "show-1" :ended true :stop true :reason "audience-left" :page 2}]] [b]]
           ["the audience window's display was removed" [:gone b :display-removed]
            [[a {:session "show-1" :ended true :stop true :reason "display-removed" :page 2}]] [b]]]]
    (let [[op wc x]               step
          {:keys [state out] :as r} (case op :leave (sl/leave (active) wc x) :gone (sl/member-gone (active) wc x))]
      (is (= told out) label)
      (is (= close (:close r)) (str label " — closed"))
      (is (= "show-1" (:session (:ended r))) label)
      (is (nil? (sl/session state "show-1")) label)
      (is (and (nil? (sl/session-of state a)) (nil? (sl/session-of state b))) (str label " — both windows free")))))

(deftest what-does-not-end-a-show
  (let [s (active)]
    (is (= {:state s :out []} (sl/member-gone s a :display-removed))
        "the presenter's display: the operating system moves its window to another display")
    (is (= {:state s :out []} (sl/member-gone s c :closed)) "a window in no show")
    (is (= {:state s :out []} (sl/member-gone s a :minimized)) "an unknown cause")
    (doseq [bad [{:session "show-1" :reason "bored"} {:session "show-1"} {:reason "stopped"} nil
                 {:session "show-2" :reason "stopped"}]]
      (is (= {:state s :out []} (sl/leave s a bad)) (pr-str bad)))
    (is (= {:state s :out []} (sl/leave s c {:session "show-1" :reason "stopped"})) "a stranger's leave")))

(deftest a-late-message-after-the-end-is-dropped
  (let [[s out] (run (active) [[:leave b {:session "show-1" :reason "stopped"}]
                               [:move a {:session "show-1" :mid 1 :page 4}]
                               [:ready b {:session "show-1"}]
                               [:leave a {:session "show-1" :reason "stopped"}]])]
    (is (= [[a {:session "show-1" :ended true :stop true :reason "stopped"}]] out)
        "the crossing Escape, the late move and the late ready are dropped")
    (is (= {} (:sessions s)))))

;; ---- queries, delivery, swap ------------------------------------------------------------------------------------

(deftest queries
  (let [s (active)]
    (is (= :presenter (sl/role-of s a)))
    (is (= :audience (sl/role-of s b)))
    (is (nil? (sl/role-of s c)))
    (is (= a (sl/presenter-of s "show-1")))
    (is (= b (sl/audience-of s "show-1")))
    (testing "SSH prompts never go to the projector (L7)"
      (is (= a (sl/prompt-window s b)) "the audience window's prompts go to its presenter")
      (is (= a (sl/prompt-window s a)))
      (is (= c (sl/prompt-window s c))))))

(deftest messages-go-only-to-members
  ;; standby (warm-pool) windows and strangers never receive vv:presenter-state (ADR-0048 §8.6)
  (let [steps [[:move a {:session "show-1" :mid 1 :page 3}] [:move c {:session "show-1" :mid 1 :page 9}]
               [:ready b {:session "show-1"}] [:move b {:session "show-1" :mid 1 :page 2 :blank "white"}]
               [:move a {:session "show-1" :mid 1 :page 0}] [:leave c {:session "show-1" :reason "stopped"}]
               [:leave a {:session "show-1" :reason "unmounted"}]]]
    (reduce (fn [s [op wc x :as step]]
              (let [{s' :state out :out} (case op
                                           :move  (sl/move s wc x)
                                           :ready (sl/ready s wc x)
                                           :leave (sl/leave s wc x))
                    mine (set (keys (sl/members s "show-1")))]
                (is (every? (fn [[target _]] (contains? mine target)) out) (pr-str step))
                s'))
            (active) steps)))

(deftest swap-screens-runs-one-at-a-time
  (let [s                               (active)
        {s1 :state out :out swap :swap} (sl/swap-begin s a {:session "show-1"} {:focused? true})]
    (is (= [] out))
    (is (= {:session "show-1" :requester a :members {a :presenter b :audience}} swap))
    (is (= a (:swapping (sl/session s1 "show-1"))))
    (testing "a second request while it runs is refused as busy"
      (is (= {:state s1 :out [[b {:session "show-1" :swapped false :why "busy"}]]}
             (sl/swap-begin s1 b {:session "show-1"} {:focused? true}))))
    (testing "the swap ends: the window that asked is answered, and the session may swap again"
      (let [{s2 :state out :out} (sl/swap-end s1 "show-1" {:swapped true :lost [:audience]})]
        (is (= [[a {:session "show-1" :swapped true :lost ["audience"]}]] out))
        (is (nil? (:swapping (sl/session s2 "show-1"))))
        (is (= s s2) "nothing else in the session changed")
        (is (:swap (sl/swap-begin s2 b {:session "show-1"} {:focused? true})))))
    (testing "both windows on one display"
      (is (= [[a {:session "show-1" :swapped false :why "same-display"}]]
             (:out (sl/swap-end s1 "show-1" {:swapped false :why "same-display"})))))
    (testing "moves keep flowing while a swap runs"
      (is (= 1 (:seq (sl/session (:state (sl/move s1 b {:session "show-1" :mid 1 :page 4})) "show-1"))))))
  (testing "dropped: not focused, not a member, not live, malformed"
    (is (= {:state (active) :out []} (sl/swap-begin (active) a {:session "show-1"} {:focused? false})))
    (is (= {:state (active) :out []} (sl/swap-begin (active) c {:session "show-1"} {:focused? true})))
    (is (= {:state (claiming) :out []} (sl/swap-begin (claiming) a {:session "show-1"} {:focused? true})))
    (is (= {:state (active) :out []} (sl/swap-begin (active) a {} {:focused? true}))))
  (testing "dropped: the audience window was not placed on a separate display (ADR-0048 §8.9)"
    (doseq [display ["same" "manual"]]
      (is (= {:state (active display) :out []} (sl/swap-begin (active display) a {:session "show-1"} {:focused? true}))
          display)))
  (testing "a swap's end after its show ended answers nobody"
    (let [{s :state} (sl/swap-begin (active) a {:session "show-1"} {:focused? true})
          {s' :state} (sl/leave s b {:session "show-1" :reason "stopped"})]
      (is (= {:state s' :out []} (sl/swap-end s' "show-1" {:swapped true :lost []}))))))
