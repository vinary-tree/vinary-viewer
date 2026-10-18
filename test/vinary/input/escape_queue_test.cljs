(ns vinary.input.escape-queue-test
  "Escape through re-frame's REAL event queue (H-ESC, docs/scientific/12 §8.8): each Escape cancels the next thing,
   however many Escapes are already queued. Keys that arrive in one burst — a blocked renderer thread drops none of
   them (ADR-0033) — are all queued before the router runs the first, so an Escape that answered with
   [:dispatch <step>] put its step BEHIND the next Escape, which then saw the same state and was spent on the same
   step. The other tests of the chain (slides_present_test, slides_keys_test) call escape-fx on a db; only here does
   the queue's order matter."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [vinary.app.events]
            [vinary.input.events]))

(def ^:private talk "/d/talk.pdf")

(def ^:private typed-show-db
  "A slide show running in this window (not fullscreen: no window effect to run) with a slide number typed."
  {:ui {:active-tab 1
        :tabs [{:id 1 :uri talk :hist {:stack [{:uri talk :scroll 0 :facet nil}] :idx 0}}]
        :input {:mode :normal :sequence [] :count nil}
        :slides {:path talk :layout :slides :page 2 :digits "2"}
        :presentation {:active? true :tab-id 1 :path talk :from :first :fullscreen? false}}})

(defn- run-queued!
  "Reset app-db to `db`, dispatch `events` in the SAME tick — queued together, as a burst of keys is — and, once the
   queue has drained, restore app-db and call `(then db' processed)`: the app-db the events left and the ids of every
   event the router ran, in order."
  [db events then]
  (let [before @rf-db/app-db
        seen   (atom [])
        cb-id  ::escape-queue]
    (reset! rf-db/app-db db)
    (rf/add-post-event-callback cb-id (fn [ev _] (swap! seen conj (first ev))))
    (doseq [ev events] (rf/dispatch ev))
    (js/setTimeout
     (fn []
       (let [after @rf-db/app-db]
         (rf/remove-post-event-callback cb-id)
         (reset! rf-db/app-db before)
         (then after @seen)))
     50)))

(deftest two-queued-escapes-take-two-steps
  (async done
    (run-queued! typed-show-db [[:input/escape] [:input/escape]]
                 (fn [db processed]
                   (try
                     (println "[H-ESC E1] two queued Escapes; events the router ran:" (pr-str processed))
                     (is (nil? (get-in db [:ui :slides :digits])) "the first forgets the typed number")
                     (is (nil? (get-in db [:ui :presentation])) "the second ends the show")
                     (finally (done)))))))

(deftest escapes-one-at-a-time-take-the-same-steps
  ;; the control: with the queue drained between them, two Escapes take the two steps under any design
  (async done
    (run-queued! typed-show-db [[:input/escape]]
                 (fn [db processed]
                   (println "[H-ESC E1 control] the first Escape alone; events the router ran:" (pr-str processed))
                   (is (nil? (get-in db [:ui :slides :digits])) "the first forgets the typed number")
                   (is (some? (get-in db [:ui :presentation])) "…and the show still runs")
                   (run-queued! db [[:input/escape]]
                                (fn [db' processed']
                                  (try
                                    (println "[H-ESC E1 control] the second Escape alone; events the router ran:"
                                             (pr-str processed'))
                                    (is (nil? (get-in db' [:ui :presentation])) "the second ends it")
                                    (finally (done)))))))))
