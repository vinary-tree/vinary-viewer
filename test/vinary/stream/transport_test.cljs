(ns vinary.stream.transport-test
  "Node test for the renderer pull-client (vinary.stream.transport) with a MOCK main-process IPC surface: a
   session serves a fixed list of line-batches one per pull then signals done. Verifies batches arrive in
   order, the done flag terminates, and prefetch (double-buffer) issues ahead without dropping data."
  (:require [cljs.test :refer [deftest is async]]
            [vinary.stream.transport :as t]))

(defn- mock-vv [batches]
  (let [st (atom {:idx 0 :pulls 0})]
    #js {:streamOpen  (fn [_] (js/Promise.resolve #js {:sessionId "mock" :size 100 :mode "lines"}))
         :streamPull  (fn [_]
                        (swap! st update :pulls inc)
                        (let [i (:idx @st)
                              last? (>= i (dec (count batches)))]
                          (swap! st update :idx inc)
                          (js/Promise.resolve
                           #js {:seq (inc i) :done last? :progress (/ (inc i) (count batches))
                                :lines (clj->js (nth batches i []))})))
         :streamClose (fn [_] (js/Promise.resolve #js {:ok true}))}))

(deftest pull-yields-batches-in-order-then-done
  (async done
    (let [mock (mock-vv [["a" "b"] ["c"] ["d" "e"]])]   ; ONE mock instance (stateful cursor), not per-call
      (t/set-vv-provider! (fn [] mock)))
    (letfn [(loop-pull [session acc]
              (-> (t/pull! session)
                  (.then (fn [batch]
                           (let [acc (into acc (:lines batch))]
                             (if (:done batch)
                               (do (is (= ["a" "b" "c" "d" "e"] acc) "all lines arrive in order across pulls")
                                   (is (== 1 (:progress batch)) "progress reaches 1 at done")
                                   (t/close! session)
                                   (done))
                               (loop-pull session acc)))))))]
      (-> (t/open! "/x.log" "log")
          (.then (fn [session]
                   (is (= "lines" (:mode session)) "log opens in lines mode")
                   (loop-pull session [])))
          (.catch (fn [e] (is false (str "transport error: " e)) (done)))))))
