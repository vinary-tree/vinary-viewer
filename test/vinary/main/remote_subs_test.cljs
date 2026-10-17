(ns vinary.main.remote-subs-test
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.main.remote-subs :as rs]))

(def uri "sftp://host/talk.pdf")

(deftest a-subscription-is-live-only-once-the-target-reports-it-active
  (let [held (rs/subscribe {} uri)]
    (is (rs/subscribed? held uri))
    (is (not (rs/live? held uri)) "held, but not live until the target reports it active")
    (is (rs/live? (rs/report held uri true) uri))
    (is (not (rs/live? (-> held (rs/report uri true) (rs/report uri false)) uri))
        "a lost connection reports it inactive")
    (is (not (rs/live? {} uri)) "no subscription: not live")))

(deftest subscribing-again-keeps-the-record
  (let [live (-> {} (rs/subscribe uri) (rs/report uri true))]
    (is (= live (rs/subscribe live uri)) "a second subscribe is a no-op: liveness survives")))

(deftest unsubscribing-drops-liveness
  ;; the regression: unsubscribeContent reports no inactive state, so liveness must leave with the record —
  ;; otherwise a reopened URI looks live before its new subscription is, and a still-changing PDF is left
  ;; pending for a notification nobody sends
  (let [gone (-> {} (rs/subscribe uri) (rs/report uri true) (rs/unsubscribe uri))]
    (is (not (rs/subscribed? gone uri)))
    (is (not (rs/live? gone uri)))
    (testing "a reopen subscribes afresh: not live until the target reports the NEW subscription active"
      (is (not (rs/live? (rs/subscribe gone uri) uri)))
      (is (rs/live? (-> gone (rs/subscribe uri) (rs/report uri true)) uri)))))

(deftest a-report-for-a-subscription-no-longer-held-is-ignored
  (is (= {} (rs/report {} uri true)) "a late activation does not resurrect a dropped subscription")
  (is (= {"sftp://host/other.pdf" {:active? false}}
         (rs/report {"sftp://host/other.pdf" {:active? false}} uri true))
      "nor touch another one"))
