(ns vinary.app.slides-linked-test
  "A linked show, the window's side as the events carry it out (ADR-0048 §8): Shift+S opens it (the show from the
   current slide without fullscreen, the presenter view, the request); main's messages (joined asks for fullscreen
   only on a separate display, a push is shown by the :slides/command EFFECT with its blank colour, an end stops the
   show or lets it go on); the audience window's join and start; the ONE outgoing interceptor (a report or a blank
   change sends, :slides/link-state never does); Escape and unmount leave; Swap screens and the report that ends it;
   the pane's line; and S in every preset. The protocol itself is vinary.app.slides-link-test's."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.events :as events]
            [vinary.app.slides :as slides]
            [vinary.app.slides-link :as link]
            [vinary.input.keymap :as keymap]))

(def ^:private talk "/d/talk.pdf")

(def ^:private shown-db
  {:ui {:active-tab 1
        :tabs [{:id 1 :uri talk :hist {:stack [{:uri talk :scroll 0 :facet nil}] :idx 0}}]
        :slides {:path talk :layout :slides :page 2 :presenting? false}}})

(defn- presenting-db
  "A show of `talk` running in this window, its engine presenting page 2."
  ([] (presenting-db true))
  ([fs?] (-> shown-db
             (assoc-in [:ui :presentation] {:active? true :tab-id 1 :path talk :from :current :fullscreen? fs?})
             (assoc-in [:ui :slides :presenting?] true))))

(def ^:private live
  "A live presenter link, in step with main at page 2."
  {:phase :live :role :presenter :session "show-1" :path talk :known {:page 2 :blank nil} :seq 0 :own-seq 0
   :out #{} :held nil :applying nil :next-mid 1 :display "separate" :peer :live :fullscreen true})

(deftest shift-s-opens-a-linked-show
  (with-redefs [slides/pdf-shown? (constantly true)]
    (testing "from a deck in a window: the show starts from the current slide, without fullscreen, in the presenter
              view, and the request carries the page, the blank colour and the fullscreen preference"
      (let [{:keys [db fx]} (events/slides-present-linked-fx shown-db)]
        (is (= {:active? true :tab-id 1 :path talk :from :current :fullscreen? false :notes-view-before false}
               (slides/presentation db)))
        (is (true? (get-in db [:ui :presenter-view?])))
        (is (= {:phase :opening :role :presenter :path talk :fullscreen true} (get-in db [:ui :slides-link])))
        (is (= [[:dom/focus :content] [:vv/presenter-open {:path talk :page 2 :blank nil :fullscreen true}]] fx)
            "no vv:set-fullscreen: the joined message decides")))
    (testing "during a show the show goes on, and the request carries its blank colour"
      (let [{:keys [db fx]} (events/slides-present-linked-fx (assoc-in (presenting-db) [:ui :slides :blank] :black))]
        (is (= :current (:from (slides/presentation db))) "the running show is kept")
        (is (= [[:vv/presenter-open {:path talk :page 2 :blank "black" :fullscreen true}]] fx))))
    (testing "again while presenting a live link: the request again (main raises the audience window)"
      (is (= [[:vv/presenter-open {:path talk :page 2 :blank nil :fullscreen true}]]
             (:fx (events/slides-present-linked-fx (assoc-in (presenting-db) [:ui :slides-link] live))))))
    (testing "while the link is being opened: nothing is sent"
      (is (= [] (:fx (events/slides-present-linked-fx
                      (assoc-in (presenting-db) [:ui :slides-link] {:phase :opening :role :presenter :path talk}))))))
    (testing "an audience window does not open a show of its own"
      (is (nil? (events/slides-present-linked-fx
                 (assoc-in (presenting-db) [:ui :slides-link] (assoc live :role :audience)))))))
  (with-redefs [slides/pdf-shown? (constantly false)]
    (is (nil? (events/slides-present-linked-fx shown-db)) "no PDF shown")))

(def ^:private joined
  {:session "show-1" :seq 0 :role "presenter" :page 2 :blank nil :joined true :display "separate"})

(deftest main-says-joined
  (let [opening (assoc-in (presenting-db false) [:ui :slides-link]
                          {:phase :opening :role :presenter :path talk :fullscreen true})]
    (testing "on a separate display the presenter asks for its own fullscreen, and the show records it"
      (let [{:keys [db fx]} (events/slides-link-state-fx opening joined)]
        (is (link/live? (get-in db [:ui :slides-link])))
        (is (true? (:fullscreen? (slides/presentation db))))
        (is (= [[:vv/set-fullscreen {:on true}]
                [:async/debounce {:key :slides/link-waiting :ms 10000 :dispatch [:slides/link-waited "show-1"]}]]
               fx)
            "and the 10 s wait for the audience window starts")))
    (testing "on the only display, or where main cannot place windows, it never covers the screen"
      (doseq [display ["same" "manual"]]
        (is (not-any? #(= :vv/set-fullscreen (first %))
                      (:fx (events/slides-link-state-fx opening (assoc joined :display display))))
            display)))
    (testing "a key pressed while the link was being made is sent as a move"
      (is (= [:vv/presenter-move {:session "show-1" :mid 1 :page 3 :blank nil}]
             (first (:fx (events/slides-link-state-fx (assoc-in opening [:ui :slides :page] 3) joined))))))
    (testing "the audience's deck shows: the wait ends"
      (let [waiting (:db (events/slides-link-state-fx opening joined))
            {:keys [db fx]} (events/slides-link-state-fx waiting {:session "show-1" :peer "live"})]
        (is (= :live (get-in db [:ui :slides-link :peer])))
        (is (= [[:async/cancel :slides/link-waiting]] fx))))
    (testing "10 s without it: the pane says where to look — for that session only"
      (let [waiting (:db (events/slides-link-state-fx opening joined))]
        (is (true? (get-in (:db (events/slides-link-waited-fx waiting "show-1")) [:ui :slides-link :waited?]))))
      (is (nil? (events/slides-link-waited-fx (assoc-in (presenting-db) [:ui :slides-link] live) "show-1"))
          "already live"))))

(deftest main-pushes-another-windows-move
  (let [{:keys [db fx]} (events/slides-link-state-fx (assoc-in (presenting-db) [:ui :slides-link] live)
                                                     {:session "show-1" :seq 4 :page 7 :blank "black"})]
    (is (= [[:slides/command [:show 7]]] fx) "the EFFECT — the event would un-blank")
    (is (= :black (slides/blank db)) "with the push's blank colour")
    (is (= {:page 7 :blank :black} (get-in db [:ui :slides-link :known])))
    (is (= 7 (get-in db [:ui :slides-link :applying])) "until the engine reports it")))

(deftest the-other-window-ends-the-show
  (testing "stop: this window's show stops too, and leaves the fullscreen it asked for; nothing more is sent"
    (let [{:keys [db fx]} (events/slides-link-state-fx (assoc-in (presenting-db) [:ui :slides-link] live)
                                                       {:session "show-1" :ended true :stop true :reason "stopped"})]
      (is (nil? (slides/presentation db)))
      (is (nil? (get-in db [:ui :slides-link])))
      (is (= [[:vv/set-fullscreen {:on false}]] fx))))
  (testing "the audience window closed: the presenter keeps presenting, and the pane says so"
    (let [{:keys [db fx]} (events/slides-link-state-fx (assoc-in (presenting-db) [:ui :slides-link] live)
                                                       {:session "show-1" :ended true :stop false
                                                        :reason "audience-left"})]
      (is (some? (slides/presentation db)))
      (is (= [] fx))
      (is (= {:tone :warning :text "The audience window closed. Shift+S opens it again."}
             (link/notice (get-in db [:ui :slides-link])))))))

(defn- ctx
  "A re-frame context after `event`'s handler ran: app-db `before` and the handler's :db `after`."
  [event before after]
  {:coeffects {:event event :db before} :effects {:db after}})

(deftest the-one-outgoing-interceptor
  (let [before (assoc-in (presenting-db) [:ui :slides-link] live)]
    (testing "the engine's report of a new page sends a move, which becomes :known"
      (let [report {:path talk :layout :slides :page 5 :presenting? true}
            out    (events/slides-link-out-after
                    (ctx [:slides/changed report] before (assoc-in before [:ui :slides] report)))]
        (is (= [[:vv/presenter-move {:session "show-1" :mid 1 :page 5 :blank nil}]] (get-in out [:effects :fx])))
        (is (= {:page 5 :blank nil} (get-in out [:effects :db :ui :slides-link :known])))
        (is (= #{1} (get-in out [:effects :db :ui :slides-link :out])))))
    (testing "a blank change sends the page with its colour, whatever the event"
      (let [out (events/slides-link-out-after
                 (ctx [:slides/black] before (assoc-in before [:ui :slides :blank] :black)))]
        (is (= [[:vv/presenter-move {:session "show-1" :mid 1 :page 2 :blank "black"}]] (get-in out [:effects :fx])))))
    (testing "main's own message never sends — following never echoes"
      (let [c (ctx [:slides/link-state {}] before (assoc-in before [:ui :slides :blank] :black))]
        (is (= c (events/slides-link-out-after c)))))
    (testing "an event that neither reports nor blanks passes untouched"
      (let [c (ctx [:tab/next] before (assoc-in before [:ui :slides :page] 9))]
        (is (= c (events/slides-link-out-after c)))))
    (testing "a report of the page a push asked for ends its showing, and sends nothing"
      (let [showing (assoc-in before [:ui :slides-link] (assoc live :known {:page 7 :blank nil} :applying 7))
            report  {:path talk :layout :slides :page 7 :presenting? true}
            out     (events/slides-link-out-after
                     (ctx [:slides/changed report] showing (assoc-in showing [:ui :slides] report)))]
        (is (nil? (get-in out [:effects :fx])))
        (is (nil? (get-in out [:effects :db :ui :slides-link :applying])))))
    (testing "an engine that does not present yet sends nothing"
      (let [report {:path talk :layout :slides :page 5 :presenting? false}
            c      (ctx [:slides/changed report] before (assoc-in before [:ui :slides] report))]
        (is (= c (events/slides-link-out-after c)))))
    (testing "a window without a link pays one lookup"
      (let [c (ctx [:slides/black] (presenting-db) (assoc-in (presenting-db) [:ui :slides :blank] :black))]
        (is (= c (events/slides-link-out-after c)))))))

(deftest leaving-a-linked-show
  (testing "Escape, End Slide Show: leave \"stopped\", and the link is forgotten"
    (let [{:keys [db fx]} (events/slides-stop-fx (assoc-in (presenting-db) [:ui :slides-link] live) {})]
      (is (nil? (get-in db [:ui :slides-link])))
      (is (= [[:vv/set-fullscreen {:on false}]
              [:vv/presenter-leave {:session "show-1" :reason "stopped"}]
              [:async/cancel :slides/link-waiting]]
             fx))))
  (testing "the deck view unmounted: leave \"unmounted\""
    (is (some #{[:vv/presenter-leave {:session "show-1" :reason "unmounted"}]}
              (:fx (events/slides-unmounted-fx (assoc-in (presenting-db) [:ui :slides-link] live) talk))))))

(def ^:private audience-link
  {:session "show-1" :seq 0 :role "audience" :page 3 :blank nil :fullscreen true})

(deftest the-audience-window-joins-and-starts
  (let [joining (:db (events/slides-link-join-fx shown-db {:link audience-link :path talk}))]
    (testing "vv:open-files's link makes the window the show's joining audience window"
      (is (= :joining (get-in joining [:ui :slides-link :phase])))
      (is (= :audience (get-in joining [:ui :slides-link :role]))))
    (with-redefs [slides/pdf-shown? (constantly true)]
      (testing "its deck mounted: the show starts at the link's page, fullscreen as the link says, no hint; ready"
        (let [{:keys [db fx]} (events/slides-link-deck-fx joining talk)]
          (is (= 3 (:from (slides/presentation db))))
          (is (true? (:fullscreen? (slides/presentation db))))
          (is (nil? (get-in db [:ui :presentation :hint?])))
          (is (= [[:dom/focus :content] [:vv/set-fullscreen {:on true}] [:vv/presenter-ready {:session "show-1"}]] fx))
          (is (= {:page 3 :blank nil} (get-in db [:ui :slides-link :known])))))
      (testing "a link that does not ask for fullscreen, and starts blanked"
        (let [j (:db (events/slides-link-join-fx shown-db {:link (assoc audience-link :fullscreen false :blank "white")
                                                           :path talk}))
              {:keys [db fx]} (events/slides-link-deck-fx j talk)]
          (is (= :white (slides/blank db)))
          (is (not-any? #(= :vv/set-fullscreen (first %)) fx))))
      (testing "another deck, or a window that joins nothing, starts nothing"
        (is (nil? (events/slides-link-deck-fx joining "/d/other.pdf")))
        (is (nil? (events/slides-link-deck-fx shown-db talk)))))))

(deftest swap-screens
  (testing "the request, from a presenter whose audience window is on a separate display"
    (is (= [[:vv/presenter-swap {:session "show-1"}]]
           (:fx (events/slides-swap-screens-fx (assoc-in (presenting-db) [:ui :slides-link] live))))))
  (testing "not offered on one display, where main cannot place windows, or in the audience window"
    (doseq [l [(assoc live :display "same") (assoc live :display "manual") (assoc live :role :audience)]]
      (is (nil? (events/slides-swap-screens-fx (assoc-in (presenting-db) [:ui :slides-link] l))))))
  (testing "the report that ends a swap never ends the show: a window that lost its fullscreen goes on windowed"
    (let [{:keys [db]} (events/fullscreen-changed-fx (assoc-in (presenting-db) [:ui :fullscreen?] true)
                                                     {:fullscreen false :swap true})]
      (is (some? (slides/presentation db)))
      (is (false? (:fullscreen? (slides/presentation db))))))
  (testing "without :swap, leaving the show's fullscreen still ends the show"
    (is (nil? (slides/presentation
               (:db (events/fullscreen-changed-fx (assoc-in (presenting-db) [:ui :fullscreen?] true)
                                                  {:fullscreen false})))))))

(deftest the-panes-line
  (is (= "Opening the audience window…" (:text (link/notice {:phase :opening :role :presenter}))))
  (is (= "Waiting for the audience window…" (:text (link/notice (assoc live :peer :waiting)))))
  (is (re-find #"behind another window" (:text (link/notice (assoc live :peer :waiting :waited? true)))))
  (is (= "Move the audience window to the other screen, then press Shift+F in it."
         (:text (link/notice (assoc live :display "manual")))))
  (is (= "One display: share the audience window in a call." (:text (link/notice (assoc live :display "same")))))
  (is (nil? (link/notice live)) "separate displays, the audience live: only the Swap screens button")
  (is (= "Both windows are on one display."
         (:text (link/notice (assoc live :swap {:swapped false :why "same-display"})))))
  (is (= "The audience window could not go fullscreen again; press Shift+F in it."
         (:text (link/notice (assoc live :swap {:swapped true :lost ["audience"]})))))
  (is (= "No audience window could be opened."
         (:text (link/notice {:phase :ended :role :presenter :reason "no-window"}))))
  (is (nil? (link/notice {:phase :ended :role :presenter :reason "stopped"})) "a stopped show shows nothing")
  (is (nil? (link/notice (assoc live :role :audience :peer :waiting))) "never on the projector"))

(deftest shift-s-in-every-preset
  (doseq [preset [:default :vim :emacs]]
    (is (= :slides/present-linked (get-in (keymap/preset preset) [:modes :all "S"])) (str preset " S"))))
