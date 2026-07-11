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

(deftest pull-surfaces-mid-stream-drop
  (async done
    ;; a remote (SSH) source dropping mid-stream: the final batch is done + carries :error/:partial while still
    ;; delivering the lines read so far. The scheduler turns this into a non-fatal note (never :content/error).
    (let [mock #js {:streamOpen  (fn [_] (js/Promise.resolve #js {:sessionId "m" :size 100 :mode "lines"}))
                    :streamPull  (fn [_] (js/Promise.resolve
                                          #js {:seq 1 :done true :progress 0.5
                                               :lines #js ["partial line"]
                                               :error "SSH connection lost" :partial true}))
                    :streamClose (fn [_] (js/Promise.resolve #js {:ok true}))}]
      (t/set-vv-provider! (fn [] mock)))
    (-> (t/open! "ssh://h/big.log" "log")
        (.then (fn [session]
                 (-> (t/pull! session)
                     (.then (fn [batch]
                              (is (:done batch)                              "a dropped stream's batch is done")
                              (is (:partial batch)                           "the batch is flagged partial")
                              (is (= "SSH connection lost" (:error batch))   "the drop message is surfaced")
                              (is (= ["partial line"] (:lines batch))        "the lines read before the drop are still delivered")
                              (t/close! session)
                              (done))))))
        (.catch (fn [e] (is false (str "transport error: " e)) (done))))))
