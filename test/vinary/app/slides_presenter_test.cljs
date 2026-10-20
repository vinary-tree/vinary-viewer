(ns vinary.app.slides-presenter-test
  "The presenter view in one window (ADR-0048 §6, P1, P5): `s` toggles it in any slides view; a single-window show
   starts in the audience view and gives the window back the view it had; the speaker-notes hint — when it is due,
   what it saves and arms, and everything that dismisses it; and the layer, command and request that carry `s`."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.commands :as commands]
            [vinary.app.events :as events]
            [vinary.app.slides :as slides]
            [vinary.input.keymap :as keymap]))

(def ^:private talk "/d/talk.pdf")

(def ^:private shown-db
  {:ui {:active-tab 1
        :tabs [{:id 1 :uri talk :hist {:stack [{:uri talk :scroll 0 :facet nil}] :idx 0}}]
        :slides {:path talk :layout :slides :page 2}}})

(def ^:private hint-fx
  "What showing the hint adds to an event's effects."
  [[:dispatch [:settings/set :slides-notes-hinted? true]]
   [:async/debounce {:key events/notes-hint-key :ms events/notes-hint-ms :dispatch [:slides/notes-hint-dismiss]}]])

(defn- presenting
  "`db` with a show running in the audience view."
  [db]
  (assoc-in db [:ui :presentation] {:active? true :tab-id 1 :path talk :from :first :fullscreen? true
                                    :notes-view-before false}))

(deftest s-toggles-the-presenter-view
  (with-redefs [slides/slides-view? (constantly true)]
    (let [off (:db (events/slides-presenter-view-fx shown-db))
          on  (:db (events/slides-presenter-view-fx off))]
      (is (true? (slides/presenter-view? shown-db)) "notes are allowed by default")
      (is (false? (slides/presenter-view? off)) "s hides joined notes")
      (is (false? (get-in off [:ui :presenter-view?])) "the slide-only choice is explicit")
      (is (true? (slides/presenter-view? on)) "s again allows joined notes")
      (is (true? (slides/presenter-shown?
                  (assoc-in shown-db [:ui :slides :current-notes?] true))) "current notes pair automatically"))
    (testing "while presenting too"
      (is (true? (slides/presenter-view? (:db (events/slides-presenter-view-fx (presenting shown-db)))))))
    (testing "choosing the presenter view dismisses the hint that pointed at it, and its timer"
      (let [hinted (assoc-in (presenting shown-db) [:ui :presentation :hint?] true)
            {:keys [db fx]} (events/slides-presenter-view-fx hinted)]
        (is (false? (slides/notes-hint? db)))
        (is (= [[:async/cancel events/notes-hint-key]] fx))))
    (is (nil? (:fx (events/slides-presenter-view-fx shown-db))) "no hint: no effects but the db"))
  (with-redefs [slides/slides-view? (constantly false)]
    (is (nil? (events/slides-presenter-view-fx shown-db)) "no slides view: s does nothing")
    (is (false? (slides/presenter-shown? (assoc-in shown-db [:ui :presenter-view?] true)))
        "the choice outlives the slides view, but the pane shows only in one")))

(deftest the-presenter-view-reaches-the-engine
  (is (false? (:presenter-view? (slides/layout-request shown-db))) "a notes-free step may still animate")
  (is (false? (:presenter-view? (slides/layout-request (assoc-in shown-db [:ui :presenter-view?] false)))))
  (is (true? (:joined? (slides/layout-request (assoc-in shown-db [:ui :slides :current-notes?] true))))
      "the engine gives the joined slide its full cell")
  (is (true? (:presenter-view? (slides/layout-request
                                (assoc-in shown-db [:ui :slides :current-notes?] true))))
      "a joined page disables the engine's separate transition snapshot"))

(deftest a-show-starts-in-the-audience-view-and-gives-the-view-back
  (with-redefs [slides/pdf-shown?     (constantly true)
                slides/slides-view?   (constantly true)
                slides/deck-has-notes? (constantly false)]
    (testing "from the presenter view: the show hides it, and remembers it"
      (let [windowed (assoc-in shown-db [:ui :presenter-view?] true)
            started  (:db (events/slides-present-fx windowed :first))]
        (is (false? (slides/presenter-view? started)) "the show starts in the audience view")
        (is (true? (:notes-view-before (slides/presentation started))))
        (testing "s during the show, then the show ends: the presenter view it had"
          (let [shown (:db (events/slides-presenter-view-fx started))]
            (is (true? (slides/presenter-view? shown)))
            (is (true? (slides/presenter-view? (:db (events/slides-stop-fx shown {})))))))
        (is (true? (slides/presenter-view? (:db (events/slides-stop-fx started {}))))
            "ending the show without s gives the presenter view back")))
    (testing "from the audience view: s during the show is forgotten when it ends"
      (let [started (:db (events/slides-present-fx (assoc-in shown-db [:ui :presenter-view?] false) :first))
            shown   (:db (events/slides-presenter-view-fx started))]
        (is (false? (:notes-view-before (slides/presentation started))))
        (is (true? (slides/presenter-view? shown)))
        (is (false? (slides/presenter-view? (:db (events/slides-stop-fx shown {})))))))))

(deftest the-notes-hint-is-due
  (let [due (presenting shown-db)]
    (with-redefs [slides/deck-has-notes? (constantly true)]
      (is (true? (slides/notes-hint-due? due)) "a single-window show, in the audience view, of a deck with notes")
      (is (false? (slides/notes-hint-due? shown-db)) "not without a show")
      (is (false? (slides/notes-hint-due? (assoc-in due [:ui :presenter-view?] true)))
          "not when the presenter view already shows the notes")
      (is (false? (slides/notes-hint-due? (assoc-in due [:ui :presentation :hint?] true))) "not while it shows")
      (is (false? (slides/notes-hint-due? (assoc-in due [:ui :settings :slides-notes-hinted?] true)))
          "once per installation: not after it was shown")
      (doseq [phase [:joining :live :ended]]
        (is (false? (slides/notes-hint-due? (assoc-in due [:ui :slides-link] {:phase phase :role :audience :path talk})))
            (str "never in a linked show's audience window, whose screen is the projector's (" (name phase) ") — "
                 "its start asks for no hint, and a text note on a rendered page must not ask again"))))
    (with-redefs [slides/deck-has-notes? (constantly false)]
      (is (false? (slides/notes-hint-due? due)) "not for a deck without notes")))
  (testing "a deck has notes: its notes summary says so, or the engine found a text note on a rendered page"
    (with-redefs [slides/shown-deck (constantly {:notes {:has-notes? true}})]
      (is (true? (slides/deck-has-notes? shown-db))))
    (with-redefs [slides/shown-deck (constantly {:notes {:has-notes? false}})]
      (is (false? (slides/deck-has-notes? shown-db)))
      (is (true? (slides/deck-has-notes? (assoc-in shown-db [:ui :slides :text-notes?] true)))))
    (with-redefs [slides/shown-deck (constantly nil)]
      (is (false? (slides/deck-has-notes? shown-db)) "no deck summary yet"))))

(deftest the-notes-hint-shows-once
  (with-redefs [slides/pdf-shown?      (constantly true)
                slides/deck-has-notes? (constantly true)]
    (testing "a show that starts in the audience view of a deck with notes shows it, saves it, and arms its timeout"
      (let [{:keys [db fx]} (events/slides-present-fx shown-db :first)]
        (is (true? (slides/notes-hint? db)))
        (is (= (into [[:dom/focus :content] [:vv/set-fullscreen {:on true}]] hint-fx) fx))))
    (testing "never a second time on this installation"
      (let [{:keys [db fx]} (events/slides-present-fx (assoc-in shown-db [:ui :settings :slides-notes-hinted?] true)
                                                      :first)]
        (is (false? (slides/notes-hint? db)))
        (is (= [[:dom/focus :content] [:vv/set-fullscreen {:on true}]] fx))))
    (testing "the check asked when the deck's notes settle during a show"
      (let [{:keys [db fx]} (events/with-notes-hint (presenting shown-db) nil)]
        (is (true? (slides/notes-hint? db)))
        (is (= hint-fx fx)))
      (is (nil? (events/with-notes-hint shown-db nil)) "nothing is due without a show: no effects at all")))
  (with-redefs [slides/deck-has-notes? (constantly false)]
    (testing "a text note found on a rendered page during a show makes it due"
      (let [m {:path talk :layout :slides :page 3 :text-notes? true}]
        (with-redefs [slides/deck-has-notes? (fn [db] (true? (get-in db [:ui :slides :text-notes?])))]
          (let [{:keys [db fx]} (events/slides-changed-fx (presenting shown-db) m)]
            (is (true? (slides/notes-hint? db)))
            (is (= hint-fx fx))
            (is (= 3 (get-in db [:ui :slides :page])) "the report itself is stored as always"))
          (is (= {:db (assoc-in (presenting (assoc-in shown-db [:ui :slides :text-notes?] true)) [:ui :slides]
                                (slides/merge-report {:path talk :layout :slides :page 2 :text-notes? true} m))}
                 (events/slides-changed-fx (presenting (assoc-in shown-db [:ui :slides :text-notes?] true)) m))
              "only the report that FIRST says so asks"))))
    (is (= {:db (assoc-in shown-db [:ui :slides] (slides/merge-report (get-in shown-db [:ui :slides])
                                                                      {:path talk :layout :slides :page 4}))}
           (events/slides-changed-fx shown-db {:path talk :layout :slides :page 4}))
        "an ordinary report: only the db")))

(deftest the-notes-hint-goes
  (let [hinted (assoc-in (presenting shown-db) [:ui :presentation :hint?] true)]
    (testing "its timeout, the next key or click: :slides/notes-hint-dismiss"
      (let [{:keys [db fx]} (events/notes-hint-dismiss-fx hinted)]
        (is (false? (slides/notes-hint? db)))
        (is (= [[:async/cancel events/notes-hint-key]] fx)))
      (is (nil? (events/notes-hint-dismiss-fx (presenting shown-db))) "not shown: nothing to dismiss"))
    (testing "a typed digit dismisses it first — it floats where the Go to chip does"
      (let [{:keys [db fx]} (events/slides-digit-fx hinted 4)]
        (is (false? (slides/notes-hint? db)))
        (is (= "4" (slides/digits db)))
        (is (some #{[:async/cancel events/notes-hint-key]} fx))))
    (is (not-any? #{[:async/cancel events/notes-hint-key]} (:fx (events/slides-digit-fx (presenting shown-db) 4)))
        "no hint: a digit cancels nothing")
    (testing "the show ending takes it, and its timer, with it"
      (let [{:keys [db fx]} (events/slides-stop-fx hinted {})]
        (is (false? (slides/notes-hint? db)))
        (is (= [[:vv/set-fullscreen {:on false}] [:async/cancel events/notes-hint-key]] fx))))))

(deftest s-is-a-slides-key-and-a-command
  (doseq [preset [:default :vim :emacs]]
    (is (= :slides/presenter-view (get-in (keymap/preset preset) [:modes :slides "s"]))
        (str "s shows the presenter view in the " (name preset) " preset")))
  (let [c (get commands/registry :slides/presenter-view)]
    (is (= [:slides/presenter-view] (:dispatch c)))
    (is (= :slides-view? (:when c)) "offered in any slides view, windowed or presenting")
    (is (= "Slides" (:category c)))))
