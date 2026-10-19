(ns vinary.app.slides-present-test
  "The fullscreen slide show (ADR-0045 §Presenting): starting and ending a show, blanking, click-advance, what
   ends a show (Escape, unmount, hidden-chrome focus, the window leaving fullscreen), the F5 keys, main's
   fullscreen decision table, and window-bounds persistence while fullscreen."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.events :as events]
            [vinary.app.slides :as slides]
            [vinary.input.events :as input-events]
            [vinary.input.keymap :as keymap]
            [vinary.main.fullscreen :as fullscreen]
            [vinary.main.window :as window]))

(def ^:private talk "/d/talk.pdf")

(def ^:private shown-db
  {:ui {:active-tab 1
        :tabs [{:id 1 :uri talk :hist {:stack [{:uri talk :scroll 0 :facet nil}] :idx 0}}]
        :slides {:path talk :layout :slides :page 2}}})

(defn- presenting-db
  ([] (presenting-db true))
  ([fs?] (assoc-in shown-db [:ui :presentation] {:active? true :tab-id 1 :path talk :from :first :fullscreen? fs?})))

(deftest starting-a-show
  (with-redefs [slides/pdf-shown? (constantly true)]
    (testing "F5 starts from the first slide, focuses the document, and asks for fullscreen"
      (let [{:keys [db fx]} (events/slides-present-fx shown-db :first)]
        (is (= {:active? true :tab-id 1 :path talk :from :first :fullscreen? true :notes-view-before false}
               (slides/presentation db))
            "a show starts in the audience view, and remembers the view it leaves (ADR-0048 §6.1)")
        (is (= [[:dom/focus :content] [:vv/set-fullscreen {:on true}]] fx))
        (is (= {:explicit nil :open-as :slides :presenting? true :present-from :first :transition :none :notes :auto
                :presenter-view? false}
               (slides/layout-request db))
            "the engine sees :presenting? and where to start through the layout request")))
    (testing "nil means :first; Shift+F5 starts from the current slide"
      (is (= :first (slides/present-from (:db (events/slides-present-fx shown-db nil)))))
      (is (= :current (slides/present-from (:db (events/slides-present-fx shown-db :current))))))
    (testing "the :slides-fullscreen? preference off: chrome hides, the window is left as it is"
      (let [{:keys [db fx]} (events/slides-present-fx (assoc-in shown-db [:ui :settings :slides-fullscreen?] false) :first)]
        (is (false? (:fullscreen? (slides/presentation db))))
        (is (= [[:dom/focus :content]] fx))))
    (testing "starting clears a typed number and a stale blank"
      (let [{:keys [db]} (events/slides-present-fx (-> shown-db (assoc-in [:ui :slides :digits] "4")
                                                       (assoc-in [:ui :slides :blank] :black)) :first)]
        (is (nil? (slides/digits db)))
        (is (nil? (slides/blank db)))))
    (testing "while presenting: F5 again goes to the first slide, an index goes there, Shift+F5 does nothing"
      (is (= {:fx [[:dispatch [:slides/command :first]]]} (events/slides-present-fx (presenting-db) :first)))
      (is (= {:fx [[:dispatch [:slides/command [:goto 5]]]]} (events/slides-present-fx (presenting-db) 5)))
      (is (nil? (events/slides-present-fx (presenting-db) :current)))))
  (with-redefs [slides/pdf-shown? (constantly false)]
    (is (nil? (events/slides-present-fx shown-db :first)) "no PDF shown → no show")))

(deftest ending-a-show
  (testing "stop forgets the show and the blank, and leaves the fullscreen it asked for"
    (let [{:keys [db fx]} (events/slides-stop-fx (assoc-in (presenting-db) [:ui :slides :blank] :white) {})]
      (is (nil? (slides/presentation db)))
      (is (nil? (slides/blank db)))
      (is (= [[:vv/set-fullscreen {:on false}]] fx))))
  (is (= [] (:fx (events/slides-stop-fx (presenting-db false) {})))
      "a show that never asked for fullscreen leaves the window alone")
  (is (nil? (events/slides-stop-fx shown-db {})) "nothing running → nothing to stop")
  (testing "the view unmounting ends a show of that document (and only that one)"
    (let [{:keys [db fx]} (events/slides-unmounted-fx (presenting-db) talk)]
      (is (nil? (slides/presentation db)))
      (is (nil? (get-in db [:ui :slides])))
      (is (= [[:vv/set-fullscreen {:on false}]] fx)))
    (is (= {:db (presenting-db)} (events/slides-unmounted-fx (presenting-db) "/d/other.pdf"))))
  (testing "focusing chrome the show hides ends it first; focusing the content does not"
    (let [{:keys [db fx]} (events/nav-focus-fx (presenting-db) :uri)]
      (is (nil? (slides/presentation db)))
      (is (= [[:vv/set-fullscreen {:on false}] [:dom/focus :uri]] fx)))
    (is (= {:fx [[:dom/focus :content]]} (events/nav-focus-fx (presenting-db) :content)))
    (is (= {:fx [[:dom/focus :tree]]} (events/nav-focus-fx shown-db :tree)) "no show: unchanged"))
  (testing "the window leaving the show's fullscreen (F11, the window manager) ends the show"
    (let [fs-db (assoc-in (presenting-db) [:ui :fullscreen?] true)
          {:keys [db fx]} (events/fullscreen-changed-fx fs-db {:fullscreen false :owned false})]
      (is (nil? (slides/presentation db)))
      (is (false? (get-in db [:ui :fullscreen?])))
      (is (= [] fx) "it is already out of fullscreen — nothing to leave"))
    (is (= {:db (assoc-in (presenting-db) [:ui :fullscreen?] true)}
           (events/fullscreen-changed-fx (presenting-db) {:fullscreen true :owned true}))
        "entering fullscreen just records it")
    (is (some? (slides/presentation (:db (events/fullscreen-changed-fx (presenting-db) {:fullscreen false}))))
        "a 'not fullscreen' report that is not a TRANSITION (the boot pull) never ends a show")
    (is (some? (slides/presentation (:db (events/fullscreen-changed-fx
                                           (assoc-in (presenting-db false) [:ui :fullscreen?] true)
                                           {:fullscreen false}))))
        "a show that did not ask for fullscreen survives the user leaving their own fullscreen")))

(deftest blanking-and-clicks
  (testing "b / w blank the running show; the same key again un-blanks; another colour switches"
    (let [black (:db (events/slides-blank-fx (presenting-db) :black))]
      (is (= :black (slides/blank black)))
      (is (nil? (slides/blank (:db (events/slides-blank-fx black :black)))))
      (is (= :white (slides/blank (:db (events/slides-blank-fx black :white)))))))
  (is (nil? (events/slides-blank-fx shown-db :black)) "no blanking outside a show")
  (testing "while blanked, a navigation only ends the blanking"
    (let [blanked (assoc-in (presenting-db) [:ui :slides :blank] :black)]
      (is (= {:db (presenting-db)} (events/slides-command-fx blanked :next)))
      (is (nil? (slides/blank (:db (events/slides-enter-fx (assoc-in blanked [:ui :slides :digits] "3")))))
          "an explicit go-to (digits + Enter) ends the blanking and goes")))
  (testing "a presenting click advances (Shift goes back) unless the preference is off"
    (is (= {:fx [[:dispatch [:slides/command :next]]]} (events/slides-click-fx (presenting-db) false)))
    (is (= {:fx [[:dispatch [:slides/command :prev]]]} (events/slides-click-fx (presenting-db) true)))
    (is (nil? (events/slides-click-fx (assoc-in (presenting-db) [:ui :settings :slides-click-advance?] false) false)))
    (is (nil? (events/slides-click-fx shown-db false)) "no click-advance outside a show")))

(deftest escape-unwinds-a-show-step-by-step
  ;; each Escape APPLIES its step — the step event's own {:db :fx} — so the next Escape sees it taken (H-ESC; the
  ;; queue itself: test/vinary/input/escape_queue_test.cljs)
  (let [typed+blank (-> (presenting-db) (assoc-in [:ui :slides :digits] "2") (assoc-in [:ui :slides :blank] :black)
                        (assoc-in [:ui :input :mode] :insert))
        step1       (input-events/escape-fx typed+blank)
        step2       (input-events/escape-fx (:db step1))
        step3       (input-events/escape-fx (:db step2))
        step4       (input-events/escape-fx (:db step3))]
    (is (= (events/slides-digits-clear-fx typed+blank) step1) "1. the typed number…")
    (is (= [nil :black true] [(slides/digits (:db step1)) (slides/blank (:db step1)) (slides/presenting? (:db step1))])
        "…only")
    (is (= {:db (slides/clear-blank (:db step1))} step2) "2. the blank screen")
    (is (= (events/slides-stop-fx (:db step2) {}) step3) "3. the show itself")
    (is (= [[:vv/set-fullscreen {:on false}]] (:fx step3)) "…leaving the fullscreen it asked for")
    (is (= [nil :insert] [(slides/presentation (:db step3)) (get-in (:db step3) [:ui :input :mode])])
        "no mode side effect while a slide step was cancelled")
    (is (= :normal (get-in (:db step4) [:ui :input :mode])) "4. only then the mode switch")))

(deftest f5-keys-in-every-preset
  (doseq [preset [:default :vim :emacs]]
    (let [all (get-in (keymap/preset preset) [:modes :all])]
      (is (= :slides/present (get all "f5")) (str preset " F5"))
      (is (= :slides/present-current (get all "S-f5")) (str preset " Shift+F5")))))

(deftest main-fullscreen-decisions
  ;; want  is   owned  →  set    owned
  (is (= {:set true :owned true}   (fullscreen/decide {:want? true :is? false :owned? false})) "enter, and own it")
  (is (= {:set nil :owned true}    (fullscreen/decide {:want? true :is? true :owned? true})) "already ours")
  (is (= {:set nil :owned false}   (fullscreen/decide {:want? true :is? true :owned? false}))
      "the user's own fullscreen: leave it alone and never claim it")
  (is (= {:set false :owned false} (fullscreen/decide {:want? false :is? true :owned? true})) "leave ours")
  (is (= {:set nil :owned false}   (fullscreen/decide {:want? false :is? true :owned? false}))
      "never leave a fullscreen we did not enter")
  (is (= {:set nil :owned false}   (fullscreen/decide {:want? false :is? false :owned? false}))))

(deftest window-bounds-are-never-saved-fullscreen
  (let [normal {:x 10 :y 20 :width 1280 :height 860}]
    (is (= (assoc normal :maximized? false)
           (window/persistable-bounds {:fullscreen? false :maximized? false :normal normal})))
    (is (= (assoc normal :maximized? true)
           (window/persistable-bounds {:fullscreen? false :maximized? true :normal (assoc normal :junk 1)}))
        "only the four geometry keys and the maximized flag")
    (is (nil? (window/persistable-bounds {:fullscreen? true :maximized? false :normal {:x 0 :y 0 :width 3840 :height 2160}}))
        "while fullscreen nothing is saved — the bounds from before the show stay on disk")))
