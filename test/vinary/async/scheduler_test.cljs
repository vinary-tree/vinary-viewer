(ns vinary.async.scheduler-test
  "The budget arithmetic and the cancellation semantics that make a keystroke-triggered job safe.

   The budget is tested with a SUBSTITUTED CLOCK rather than by sleeping: 'did this slice overrun?' has an
   exact boundary, and a test that sleeps can only observe it approximately."
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures async]]
            [vinary.async.budget :as budget]
            [vinary.async.scheduler :as sched]))

(def ^:private clock (atom 0))

(use-fixtures :each
  {:before (fn [] (reset! clock 0) (budget/set-clock! (fn [] @clock)))
   :after  (fn [] (budget/set-clock! nil))})

(defn- advance! [ms] (swap! clock + ms))

;; ---- pure arithmetic --------------------------------------------------------------------------------

(deftest budget-boundary
  (testing "spent? is inclusive, so a zero budget yields after exactly one unit of work"
    ;; If this were exclusive, a frozen or coarse clock would spin forever inside one tick — precisely the
    ;; failure a chunked job must not have.
    (is (true?  (budget/spent? 0 0)))
    (is (false? (budget/spent? 0 1)))
    (advance! 1)
    (is (true?  (budget/spent? 0 1)))
    (is (false? (budget/spent? 0 2))))
  (testing "elapsed reads through the installed clock"
    (reset! clock 100)
    (is (= 40 (budget/elapsed 60)))))

(deftest epoch-cancellation
  (testing "a fresh key starts at epoch 0 and is live"
    (is (= 0 (sched/epoch ::fresh)))
    (is (true? (sched/live? ::fresh 0))))
  (testing "cancel! invalidates the token a job captured"
    (let [k ::cancelled
          token (do (sched/cancel! k) (sched/epoch k))]
      (is (true? (sched/live? k token)))
      (sched/cancel! k)
      (is (false? (sched/live? k token))
          "a job holding the old token must see itself as superseded")))
  (testing "keys are independent — arming one does not disturb another"
    (let [a ::key-a b ::key-b]
      (sched/cancel! a)
      (let [ta (sched/epoch a) tb (sched/epoch b)]
        (sched/cancel! b)
        (is (true?  (sched/live? a ta)))
        (is (false? (sched/live? b tb))))))
  (testing "run-now! cancels whatever was in flight, then runs"
    (let [k ::run-now
          token (do (sched/cancel! k) (sched/epoch k))]
      (is (= :value (sched/run-now! k (fn [] :value))))
      (is (false? (sched/live? k token))))))

;; ---- debounce ---------------------------------------------------------------------------------------

(deftest debounce-keeps-only-the-last-arming
  (async done
    (let [k ::debounce-a
          calls (atom [])]
      (sched/debounce! k 0 #(swap! calls conj :first))
      (sched/debounce! k 0 #(swap! calls conj :second))
      (is (true? (sched/pending? k)))
      (js/setTimeout
       (fn []
         (is (= [:second] @calls) "the superseded arming must not have fired")
         (is (false? (sched/pending? k)))
         (done))
       10))))

(deftest debounce-is-genuinely-cancellable
  (async done
    (let [k ::debounce-b
          calls (atom [])]
      (sched/debounce! k 0 #(swap! calls conj :ran))
      (sched/cancel! k)
      (is (false? (sched/pending? k)) "cancel! clears the pending timer outright")
      (js/setTimeout
       (fn []
         (is (= [] @calls) "a cancelled debounce must not fire")
         (done))
       10))))

;; ---- sliced work ------------------------------------------------------------------------------------

(deftest slice-runs-every-unit-then-finishes-once
  (async done
    (let [k ::slice-a
          steps (atom 0)
          dones (atom 0)]
      (sched/slice! k {:step (fn [] (swap! steps inc) (advance! 4) (< @steps 10))
                       :done (fn [] (swap! dones inc))
                       :budget-ms 8})
      (js/setTimeout
       (fn []
         (is (= 10 @steps) "every unit of work ran")
         (is (= 1 @dones) "done fired exactly once, after the last step")
         (done))
       80))))

(deftest slice-yields-when-the-budget-is-spent
  (async done
    ;; Each step advances the clock past the budget, so at most one can run per tick. With 5 steps that is
    ;; 4 yields — the last step finishes the job instead of yielding. Observed through :on-yield, which is
    ;; the pacing made visible rather than inferred.
    (let [k ::slice-b
          steps (atom 0)
          yields (atom 0)]
      (sched/slice! k {:step (fn [] (swap! steps inc) (advance! 10) (< @steps 5))
                       :on-yield (fn [] (swap! yields inc))
                       :budget-ms 8})
      (js/setTimeout
       (fn []
         (is (= 5 @steps))
         (is (= 4 @yields) "one yield after every step but the last")
         (done))
       80))))

(deftest slice-batches-within-the-budget
  (async done
    ;; The complement: steps that cost nothing never exhaust the budget, so the whole job runs in ONE tick
    ;; and never yields. This is what keeps the per-slice overhead negligible on a small document.
    (let [k ::slice-c
          steps (atom 0)
          yields (atom 0)]
      (sched/slice! k {:step (fn [] (swap! steps inc) (< @steps 50))
                       :on-yield (fn [] (swap! yields inc))
                       :budget-ms 8})
      (js/setTimeout
       (fn []
         (is (= 50 @steps))
         (is (= 0 @yields) "a job that fits in the budget must not pay for a yield")
         (done))
       80))))

(deftest slice-cancellation-stops-mid-job
  (async done
    (let [k ::slice-d
          steps (atom 0)
          cancels (atom 0)
          dones (atom 0)]
      ;; never finishes on its own, so the only way out is cancellation
      (sched/slice! k {:step (fn [] (swap! steps inc) (advance! 10) true)
                       :done (fn [] (swap! dones inc))
                       :on-cancel (fn [] (swap! cancels inc))
                       :budget-ms 8})
      (js/setTimeout
       (fn []
         (let [ran @steps]
           (is (pos? ran) "the job had actually started")
           (sched/cancel! k)
           (js/setTimeout
            (fn []
              (is (= ran @steps) "no further work ran after cancellation")
              (is (= 1 @cancels) "on-cancel was reported exactly once")
              (is (= 0 @dones) "done must NOT fire for a superseded job")
              (done))
            40)))
       20))))

(deftest re-arming-supersedes-the-running-job
  (async done
    ;; The typing case: a second search arrives while the first is still slicing. The first must stop and
    ;; only the second may report a result.
    (let [k ::slice-e
          finished (atom [])]
      (sched/slice! k {:step (fn [] (advance! 10) true)          ; endless
                       :done (fn [] (swap! finished conj :first))
                       :budget-ms 8})
      (js/setTimeout
       (fn []
         (let [n (atom 0)]
           (sched/slice! k {:step (fn [] (swap! n inc) (advance! 10) (< @n 3))
                            :done (fn [] (swap! finished conj :second))
                            :budget-ms 8})
           (js/setTimeout
            (fn []
              (is (= [:second] @finished)
                  "only the newest job may report — a superseded one is silent")
              (done))
            80)))
       20))))
