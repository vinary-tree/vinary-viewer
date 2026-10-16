(ns vinary.main.content-flight-test
  "The per-path read model behind reload safety (ADR-0046): one read per path, joins, dirty → one trailing read,
   :pending carries its requesters, stale completions do nothing — and a seeded randomized simulation checking
   the invariants over thousands of interleavings."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.main.content-flight :as flight]))

(def ^:private a "/d/talk.pdf")
(def ^:private b "sftp://host/notes.md")

(defn- run
  "Apply `steps` ([:request path opts] | [:complete path seq outcome]) from `flights`; → [flights all-effects]."
  [flights steps]
  (reduce (fn [[f effs] [op path x y]]
            (let [[f' e] (case op
                           :request  (flight/request f path x)
                           :complete (flight/complete f path x y))]
              [f' (into effs e)]))
          [flights []] steps))

(deftest an-idle-path-starts-a-read
  (let [[f effs] (flight/request flight/empty-flights a {:requester 7})]
    (is (= [[:read a 1]] effs))
    (is (= {:seq 1 :requesters #{7} :fanout? false} (flight/in-flight f a))))
  (let [[_ effs] (flight/request flight/empty-flights a {:fanout? true})]
    (is (= [[:read a 1]] effs) "a change notification on an idle path reads it too")))

(deftest an-open-joins-the-running-read
  (let [[f effs] (run flight/empty-flights [[:request a {:requester 1}] [:request a {:requester 2}]])]
    (is (= [[:read a 1]] effs) "no second read")
    (is (= #{1 2} (:requesters (flight/in-flight f a))))
    (is (= [[:deliver a {:seq 1 :requesters #{1 2} :fanout? false}]]
           (second (flight/complete f a 1 :delivered)))
        "both windows receive the one read")))

(deftest a-burst-of-changes-costs-one-trailing-read
  (let [[f effs] (run flight/empty-flights
                      (into [[:request a {:requester 1}]]
                            (repeat 5 [:request a {:fanout? true}])))]
    (is (= [[:read a 1]] effs) "five notifications during the read start nothing yet")
    (let [[f2 effs2] (flight/complete f a 1 :delivered)]
      (is (= [[:deliver a {:seq 1 :requesters #{1} :fanout? false}] [:read a 2]] effs2)
          "the read is delivered, then exactly ONE trailing read starts")
      (is (= {:seq 2 :requesters #{1} :fanout? true} (flight/in-flight f2 a))
          "it fans out, and the window that asked is owed the newer version too")
      (let [[f3 effs3] (flight/complete f2 a 2 :delivered)]
        (is (= [[:deliver a {:seq 2 :requesters #{1} :fanout? true}]] effs3) "and nothing after it")
        (is (nil? (flight/in-flight f3 a)))))))

(deftest a-pending-read-hands-its-requesters-on
  (testing "no change yet: nothing is sent; the next notification's read owes the requesters"
    (let [[f _]      (run flight/empty-flights [[:request a {:requester 3}]])
          [f2 effs2] (flight/complete f a 1 :pending)]
      (is (= [] effs2) "a PDF still being written is not delivered")
      (is (nil? (flight/in-flight f2 a)))
      (let [[f3 effs3] (flight/request f2 a {:fanout? true})]
        (is (= [[:read a 2]] effs3))
        (is (= {:seq 2 :requesters #{3} :fanout? true} (flight/in-flight f3 a))))))
  (testing "a change already arrived: the next read starts at once, still owing the requesters"
    (let [[f _]      (run flight/empty-flights [[:request a {:requester 3}] [:request a {:fanout? true}]])
          [f2 effs2] (flight/complete f a 1 :pending)]
      (is (= [[:read a 2]] effs2))
      (is (= {:seq 2 :requesters #{3} :fanout? true} (flight/in-flight f2 a)))))
  (testing "an open after a pending read starts a read owed to both"
    (let [[f _]      (run flight/empty-flights [[:request a {:requester 3}]])
          [f2 _]     (flight/complete f a 1 :pending)
          [f3 effs3] (flight/request f2 a {:requester 4})]
      (is (= [[:read a 2]] effs3))
      (is (= #{3 4} (:requesters (flight/in-flight f3 a)))))))

(deftest stale-and-released-completions-change-nothing
  (let [[f _] (run flight/empty-flights [[:request a {:requester 1}]])]
    (is (= [f []] (flight/complete f a 99 :delivered)) "not the running read's number")
    (is (= [f []] (flight/complete f b 1 :delivered)) "another path")
    (let [released (flight/release f a)]
      (is (nil? (flight/in-flight released a)))
      (is (= [released []] (flight/complete released a 1 :delivered)) "a released path's read lands in nothing"))))

(deftest a-closed-window-is-forgotten
  (let [[f _] (run flight/empty-flights [[:request a {:requester 1}] [:request a {:requester 2}]
                                         [:request b {:requester 1}]])
        f'    (flight/forget-requester f 1)]
    (is (= #{2} (:requesters (flight/in-flight f' a))))
    (is (= #{} (:requesters (flight/in-flight f' b))))))

(deftest read-numbers-increase-across-paths
  (let [[_ effs] (run flight/empty-flights [[:request a {:requester 1}] [:request b {:requester 1}]
                                            [:complete a 1 :delivered] [:request a {:fanout? true}]])]
    (is (= [[:read a 1] [:read b 2] [:deliver a {:seq 1 :requesters #{1} :fanout? false}] [:read a 3]] effs))))

;; ---- the invariants, over random interleavings ----------------------------------------------------------

(defn- lcg
  "A tiny deterministic PRNG (numerical recipes LCG) — the simulation must replay identically."
  [seed]
  (let [s (atom (bit-and seed 0x7fffffff))]
    (fn [n] (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) (mod @s n))))

(defn- simulate
  "Drive the model with `n` random events over 3 paths and 4 windows, then drain it. Returns the invariant
   violations found (empty when the model holds)."
  [seed n]
  (let [rnd       (lcg seed)
        paths     ["/p0" "/p1" "/p2"]
        f         (atom flight/empty-flights)
        running   (atom {})          ; path → the read number started and not yet completed
        last-seq  (atom 0)
        delivered (atom #{})         ; [path seq requester] triples delivered (read numbers are process-wide)
        asked     (atom [])          ; [path requester seq-of-the-read-it-joined-or-started]
        problems  (atom [])
        apply!    (fn [[f' effs]]
                    (reset! f f')
                    (doseq [[op path x] effs]
                      (case op
                        :read    (do (when (contains? @running path)
                                       (swap! problems conj [:two-reads path x]))
                                     (when (<= x @last-seq) (swap! problems conj [:seq-not-increasing x]))
                                     (reset! last-seq x)
                                     (swap! running assoc path x))
                        :deliver (doseq [r (:requesters x)]
                                   (when (contains? @delivered [path (:seq x) r])
                                     (swap! problems conj [:twice path (:seq x) r]))
                                   (swap! delivered conj [path (:seq x) r])))))
        finish!   (fn [path outcome]
                    (when-let [s (get @running path)]
                      (swap! running dissoc path)
                      (apply! (flight/complete @f path s outcome))))]
    (dotimes [_ n]
      (let [path (nth paths (rnd 3))]
        (case (rnd 5)
          0 (let [w (rnd 4)]
              (apply! (flight/request @f path {:requester w}))
              (swap! asked conj [path w (:seq (flight/in-flight @f path))]))
          1 (apply! (flight/request @f path {:fanout? true}))
          2 (finish! path :pending)
          (finish! path :delivered))))
    ;; drain: finish every running read as delivered; a debt left by a :pending read is flushed by the change
    ;; notification that, in the real system, always follows one
    (loop [guard 0]
      (when (< guard 1000)
        (let [busy (seq (keys @running))
              owed (seq (filter #(get-in @f [:paths % :carry]) paths))]
          (cond busy (do (doseq [p busy] (finish! p :delivered)) (recur (inc guard)))
                owed (do (doseq [p owed] (apply! (flight/request @f p {:fanout? true}))) (recur (inc guard)))
                :else nil))))
    ;; every window that asked received a read that started at or after its request
    (doseq [[path w s] @asked]
      (when-not (some (fn [[dp ds dr]] (and (= dp path) (= dr w) (>= ds s))) @delivered)
        (swap! problems conj [:never-served path w s])))
    @problems))

(deftest the-invariants-hold-over-random-interleavings
  (doseq [seed (range 1 41)]
    (is (= [] (simulate seed 400)) (str "seed " seed))))
