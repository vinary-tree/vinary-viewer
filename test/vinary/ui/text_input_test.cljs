(ns vinary.ui.text-input-test
  "The draft-vs-model reconciliation that makes a text field unable to lose a keystroke.

   These are the cases the component has to tell apart:

     the model CATCHING UP        — it trails by one or more characters while typing; keep shadowing
     the model being PUSHED       — :find/reset, :palette/open, a tab switch; stop shadowing

   Getting the first wrong reintroduces exactly the clobber the component exists to prevent, so it is
   tested here rather than only through the browser."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.ui.text-input :as ti]))

(deftest first-render-adopts-the-model
  (testing "nothing is external before there is a baseline to compare against"
    (let [bk (ti/reconcile {:seen ::ti/init :pending []} "hello")]
      (is (= "hello" (:seen bk)))
      (is (false? (:external? bk))))))

(deftest unchanged-model-is-not-a-push
  (let [bk (ti/reconcile {:seen "abc" :pending ["abcd"]} "abc")]
    (is (false? (:external? bk)))
    (is (= ["abcd"] (:pending bk)) "an unobserved publication is still outstanding")))

(deftest the-model-catching-up-is-our-own-echo
  (testing "one keystroke behind"
    (let [bk (ti/reconcile {:seen "" :pending ["a"]} "a")]
      (is (false? (:external? bk)))
      (is (= [] (:pending bk)))
      (is (= "a" (:seen bk)))))
  (testing "SEVERAL keystrokes behind — the case a last-value comparison gets wrong"
    ;; The user typed a, ab, abc; only the first has round-tripped. Treating that as an external push
    ;; would drop the draft and show "a" while "abc" is in the box.
    (let [bk (ti/reconcile {:seen "" :pending ["a" "ab" "abc"]} "a")]
      (is (false? (:external? bk)))
      (is (= ["ab" "abc"] (:pending bk)) "everything up to and including the echo is accounted for")))
  (testing "a later echo retires the earlier ones too"
    (let [bk (ti/reconcile {:seen "" :pending ["a" "ab" "abc"]} "abc")]
      (is (false? (:external? bk)))
      (is (= [] (:pending bk))))))

(deftest a-value-we-never-published-is-external
  (testing "with nothing outstanding"
    (let [bk (ti/reconcile {:seen "abc" :pending []} "")]
      (is (true? (:external? bk)))
      (is (= "" (:seen bk)))))
  (testing "even with publications outstanding — a reset must win over a pending echo"
    (let [bk (ti/reconcile {:seen "ab" :pending ["abc" "abcd"]} "")]
      (is (true? (:external? bk)))
      (is (= [] (:pending bk)) "outstanding publications are abandoned with the draft"))))

(deftest deleting-back-to-a-previous-value-is-still-an-echo
  (testing "type a, ab, then delete to a: the second `a` is ours, not a push"
    (let [bk (ti/reconcile {:seen "" :pending ["a" "ab" "a"]} "a")]
      (is (false? (:external? bk)))
      ;; the FIRST occurrence is consumed; the rest stay outstanding, which is the conservative choice —
      ;; a later echo of the same value will retire them
      (is (= ["ab" "a"] (:pending bk))))))

(deftest publishing-is-bounded
  (testing "a handler that never writes the model cannot grow the queue without limit"
    (let [bk (reduce (fn [b i] (ti/publish b (str i))) {:seen "" :pending []} (range 200))]
      (is (= 32 (count (:pending bk))))
      (is (= "199" (last (:pending bk))) "the NEWEST publications are the ones kept"))))

(deftest a-typing-burst-never-reports-a-push
  (testing "the property that matters: while only our own values come back, we keep shadowing"
    ;; Simulate typing "hello" with the model trailing arbitrarily: publish every prefix, then let the
    ;; model observe them in order. No step may be classified as external.
    (let [prefixes (map #(subs "hello" 0 (inc %)) (range 5))
          bk0 (reduce ti/publish {:seen "" :pending []} prefixes)]
      (is (= 5 (count (:pending bk0))))
      (let [final (reduce (fn [b v]
                            (let [b' (ti/reconcile b v)]
                              (is (false? (:external? b')) (str "reported a push for " (pr-str v)))
                              b'))
                          bk0 prefixes)]
        (is (= "hello" (:seen final)))
        (is (= [] (:pending final)))))))
