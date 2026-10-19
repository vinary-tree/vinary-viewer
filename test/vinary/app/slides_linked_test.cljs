(ns vinary.app.slides-linked-test
  "A linked show, the window's side as the events carry it out (ADR-0048 §8): Shift+S opens it (the show from the
   current slide without fullscreen, the presenter view, the request; a one-window show's fullscreen left); main's
   messages (joined never makes the presenter ask for a fullscreen of its own, a push is shown by the :slides/command
   EFFECT with its blank colour, an end stops the show or lets it go on); the presenter window keeps its chrome
   (focusing it, leaving a fullscreen, Escape in it); its presenter view is the console, whose strip shows the
   audience's blank; the audience window's join and start; the ONE outgoing
   interceptor (a report or a blank change sends, :slides/link-state never does); Escape and unmount leave; Swap
   screens and the report that ends it; the pane's line; and S in every preset. The protocol itself is
   vinary.app.slides-link-test's."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.core]
            [vinary.app.commands :as commands]
            [vinary.app.events :as events]
            [vinary.app.slides :as slides]
            [vinary.app.slides-link :as link]
            [vinary.input.events :as input-events]
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
   :out #{} :held nil :applying nil :next-mid 1 :display "separate" :peer :live})

(deftest shift-s-opens-a-linked-show
  (with-redefs [slides/pdf-shown? (constantly true)]
    (testing "from a deck in a window: the show starts from the current slide, without fullscreen, in the presenter
              view, and the request carries the page, the blank colour and the fullscreen preference"
      (let [{:keys [db fx]} (events/slides-present-linked-fx shown-db)]
        (is (= {:active? true :tab-id 1 :path talk :from :current :fullscreen? false :notes-view-before false}
               (slides/presentation db)))
        (is (true? (get-in db [:ui :presenter-view?])))
        (is (= {:phase :opening :role :presenter :path talk} (get-in db [:ui :slides-link])))
        (is (= [[:dom/focus :content] [:vv/presenter-open {:path talk :page 2 :blank nil :fullscreen true}]] fx)
            "no vv:set-fullscreen: the presenter window stays windowed; the preference is the audience window's")
        (is (false? (slides/chromeless? db)) "and it keeps its chrome")))
    (testing "during a one-window show (F5): the show goes on, the request carries its blank colour, and the window
              leaves the fullscreen its show holds — first, because on one display it would cover the audience window"
      (let [{:keys [db fx]} (events/slides-present-linked-fx (assoc-in (presenting-db) [:ui :slides :blank] :black))]
        (is (= :current (:from (slides/presentation db))) "the running show is kept")
        (is (false? (:fullscreen? (slides/presentation db))) "the show holds no fullscreen any more")
        (is (= [[:vv/set-fullscreen {:on false}]
                [:vv/presenter-open {:path talk :page 2 :blank "black" :fullscreen true}]]
               fx))))
    (testing "…and a one-window show that holds no fullscreen has none to leave"
      (is (= [[:vv/presenter-open {:path talk :page 2 :blank nil :fullscreen true}]]
             (:fx (events/slides-present-linked-fx (presenting-db false))))))
    (testing "again while presenting a live link: the request again (main raises the audience window), and a
              fullscreen that F entered stays"
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
                          {:phase :opening :role :presenter :path talk})]
    (testing "on any display the presenter stays windowed (ADR-0048 §8.1): it asks for no fullscreen of its own"
      (doseq [display ["separate" "same" "manual"]]
        (let [{:keys [db fx]} (events/slides-link-state-fx opening (assoc joined :display display))]
          (is (link/live? (get-in db [:ui :slides-link])) display)
          (is (false? (:fullscreen? (slides/presentation db))) display)
          (is (= [[:async/debounce {:key :slides/link-waiting :ms 10000 :dispatch [:slides/link-waited "show-1"]}]]
                 fx)
              (str display ": only the 10 s wait for the audience window starts")))))
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

(deftest the-presenter-window-keeps-its-chrome
  ;; ADR-0048 §8.1 (amended): a linked show's presenter window keeps its menus, tabs, sidebar and bars, and stays
  ;; windowed; only a one-window show and the audience window hide their chrome (.vv-root.vv-presenting)
  (let [presenter (assoc-in (presenting-db) [:ui :slides-link] live)
        audience  (assoc-in (presenting-db) [:ui :slides-link] (assoc live :role :audience))]
    (testing "chromeless: a one-window show and the audience window; never the presenter window, in any phase"
      (is (true? (slides/chromeless? (presenting-db))))
      (is (true? (slides/chromeless? audience)))
      (doseq [l [live {:phase :opening :role :presenter :path talk}
                 {:phase :ended :role :presenter :path talk :reason "audience-left" :stop? false}]]
        (is (false? (slides/chromeless? (assoc-in (presenting-db) [:ui :slides-link] l))) (pr-str (:phase l))))
      (is (false? (slides/chromeless? shown-db)) "no show"))
    (testing "focusing its URI bar, its tree or the focus toggle keeps the presenter's show"
      (doseq [target [:uri :tree :toggle]]
        (is (= {:fx [[:dom/focus target]]} (events/nav-focus-fx presenter target)) (name target))))
    (testing "…while the audience window, which hides them, ends its show first"
      (is (nil? (slides/presentation (:db (events/nav-focus-fx audience :uri))))))
    (testing "F11 or the window manager leaving a fullscreen that F entered: the presenter's show goes on, windowed"
      (let [{:keys [db fx]} (events/fullscreen-changed-fx (assoc-in presenter [:ui :fullscreen?] true)
                                                          {:fullscreen false})]
        (is (some? (slides/presentation db)))
        (is (false? (:fullscreen? (slides/presentation db))) "and its end has no fullscreen to leave")
        (is (nil? fx))))
    (testing "…while a window that hides its chrome ends its show with its fullscreen"
      (is (nil? (slides/presentation (:db (events/fullscreen-changed-fx (assoc-in audience [:ui :fullscreen?] true)
                                                                       {:fullscreen false}))))))))

(deftest escape-in-the-presenters-chrome-leaves-the-show-alone
  (let [presenter (-> (presenting-db) (assoc-in [:ui :slides-link] live) (assoc-in [:ui :slides :blank] :black)
                      (assoc-in [:ui :input :mode] :normal))
        unblank   {:db (slides/clear-blank presenter)}]
    (testing "from a text field or the chrome (:on-document? false): the audience's blank and the show stay"
      (let [{:keys [db]} (input-events/escape-fx presenter {:on-document? false})]
        (is (= :black (slides/blank db)))
        (is (some? (slides/presentation db)))
        (is (= (-> presenter (assoc-in [:ui :input :sequence] []) (assoc-in [:ui :input :count] nil)) db)
            "only the pending key sequence is cleared")))
    (testing "on the document, or with no word on it (the palette, a dispatch): the show's steps, as before"
      (is (= unblank (input-events/escape-fx presenter {:on-document? true})))
      (is (= unblank (input-events/escape-fx presenter {})))
      (is (= unblank (input-events/escape-fx presenter))))
    (testing "a window that hides its chrome has none to be in: there Escape takes the show's steps from anywhere"
      (let [one-window (assoc-in (presenting-db) [:ui :slides :blank] :black)]
        (is (= {:db (slides/clear-blank one-window)} (input-events/escape-fx one-window {:on-document? false})))))
    (testing "the keyboard's Escape carries the resolver's :on-document?; the palette's context carries none"
      (let [sent (atom [])]
        (with-redefs [re-frame.core/dispatch (fn [ev] (swap! sent conj ev))]
          (commands/run :input/escape {:on-document? false} nil {:now? true})
          (commands/run :input/escape {:on-document? true} nil {:now? true})
          (commands/run :input/escape {:in-input? true :tabs [1]}))
        (is (= [[:input/escape {:on-document? false}] [:input/escape {:on-document? true}] [:input/escape {}]]
               @sent))))))

(deftest the-presenter-console
  (with-redefs [slides/slides-view? (constantly true)]
    (let [console (-> (presenting-db false) (assoc-in [:ui :presenter-view?] true) (assoc-in [:ui :slides-link] live))]
      (testing "the presenter view in a linked show's presenter window is the console — while the link opens, while it
                is live, and after the audience window closed"
        (doseq [phase [:opening :live :ended]]
          (is (true? (slides/console? (assoc-in console [:ui :slides-link :phase] phase))) (str phase))))
      (testing "the presenter view anywhere else is not: a one-window show's, a deck's outside a show, an audience
                window's"
        (is (false? (slides/console? (update console :ui dissoc :slides-link))))
        (is (false? (slides/console? (assoc-in shown-db [:ui :presenter-view?] true))))
        (is (false? (slides/console? (assoc-in console [:ui :slides-link :role] :audience)))))
      (testing "nor is it without the presenter view (s hid it), or without a slides view"
        (is (false? (slides/console? (update console :ui dissoc :presenter-view?))))
        (with-redefs [slides/slides-view? (constantly false)]
          (is (false? (slides/console? console)))))
      (testing "a blank in the console is the audience's: the strip shows it whatever the link's phase, and the console
                never goes blank itself"
        (doseq [phase [:opening :live :ended]]
          (is (= :black (slides/audience-blank (-> console (assoc-in [:ui :slides-link :phase] phase)
                                                    (assoc-in [:ui :slides :blank] :black))))
              (str phase)))
        (is (nil? (slides/audience-blank console)) "no blank, no badge")
        (is (nil? (slides/audience-blank (-> console (update :ui dissoc :presenter-view?)
                                             (assoc-in [:ui :slides :blank] :white))))
            "outside the console a blank is this window's own blank screen")))))

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

(def ^:private fresh-db
  "A window showing `talk` whose engine has not reported yet — no [:ui :slides] mirror: an audience window as its deck
   mounts (the engine queues :pdf/deck-detected at mount; its first report waits for an animation frame)."
  (update shown-db :ui dissoc :slides))

(defn- engine-starts
  "The engine's first presenting report after app-db `db` started a show: it opens the show at [:ui :presentation
   :from] (renderer.pdf/apply-present-from!; :current keeps the page it shows, `page`)."
  [db page]
  (let [from (:from (slides/presentation db))]
    {:path talk :layout :slides :page (if (integer? from) from page) :presenting? true}))

(defn- report-out
  "The effects the engine's report `report` has in app-db `db`: :slides/changed's handler, then the interceptor."
  [db report]
  (events/slides-link-out-after (ctx [:slides/changed report] db (:db (events/slides-changed-fx db report)))))

(deftest a-linked-shows-start-follows-pushes-until-its-engine-presents
  ;; R-from (theory/11 §7): the engine applies [:ui :presentation :from] once, when it starts presenting; a push shown
  ;; before that must move :from with it, or the engine opens the show at the old start and the window's first
  ;; presenting report sends that page back: an echo that undoes the other window's move
  (with-redefs [slides/pdf-shown? (constantly true)]
    (testing "an audience window: a push between its deck mounting and its engine presenting"
      (let [joining (:db (events/slides-link-join-fx fresh-db {:link audience-link :path talk}))
            started (:db (events/slides-link-deck-fx joining talk))
            {:keys [db fx]} (events/slides-link-state-fx started {:session "show-1" :seq 1 :page 6 :blank nil})]
        (is (= [[:slides/command [:show 6]]] fx))
        (is (= 6 (:from (slides/presentation db))) "the show starts at the push, not at the link's page 3")
        (is (nil? (get-in (report-out db (engine-starts db 3)) [:effects :fx]))
            "the engine's first presenting report sends nothing: no echo")))
    (testing "a presenter: a push between main's joined and its engine presenting"
      (let [opened (:db (events/slides-present-linked-fx shown-db))
            live   (:db (events/slides-link-state-fx opened joined))
            {:keys [db]} (events/slides-link-state-fx live {:session "show-1" :seq 1 :page 6 :blank nil})]
        (is (= 6 (:from (slides/presentation db))) "the show no longer starts at the slide it showed (:current)")
        (is (nil? (get-in (report-out db (engine-starts db 2)) [:effects :fx])) "no echo")))
    (testing "once the engine presents the show, a push leaves :from alone: the engine applied it as it started"
      (let [{:keys [db]} (events/slides-link-state-fx (assoc-in (presenting-db) [:ui :slides-link] live)
                                                      {:session "show-1" :seq 4 :page 7 :blank nil})]
        (is (= :current (:from (slides/presentation db))))))))

(deftest a-linked-shows-blank-survives-its-engines-first-reports
  ;; R-blank (theory/11 §7): the show's start sets the blank colour before the engine's first report of the deck, and
  ;; the mirror must keep it, or the window's first presenting report sends the page unblanked and both windows unblank
  (with-redefs [slides/pdf-shown? (constantly true)]
    (let [joining (:db (events/slides-link-join-fx fresh-db {:link (assoc audience-link :blank "black") :path talk}))
          started (:db (events/slides-link-deck-fx joining talk))
          pre     {:path talk :layout :slides :page 3 :presenting? false}
          db1     (:db (events/slides-changed-fx started pre))]
      (is (= :black (slides/blank started)) "the audience window's show starts blanked")
      (is (= :black (slides/blank db1)) "the engine's first report, before it presents, keeps the blank colour")
      (is (nil? (get-in (report-out db1 (engine-starts db1 3)) [:effects :fx]))
          "its first presenting report sends nothing: no move unblanks the other window")
      (is (= :black (slides/blank (:db (events/slides-changed-fx db1 (engine-starts db1 3))))))
      (is (= :black (slides/blank (:db (events/slides-changed-fx started {:path talk :layout :document :page 0
                                                                          :presenting? false}))))
          "a PDF that is no deck reports the document layout until its engine presents: kept too")
      (is (nil? (slides/blank (:db (events/slides-changed-fx started {:path "/d/other.pdf" :layout :slides :page 0}))))
          "another document's report does not take the show's blank colour"))))

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
