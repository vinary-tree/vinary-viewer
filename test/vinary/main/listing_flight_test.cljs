(ns vinary.main.listing-flight-test
  "The Files tree's listing flights (docs/scientific/10 §11): one listing per key at a time, a request never joins a
   running listing, a burst during one costs one trailing listing, stale completions change nothing — and a seeded
   randomized simulation checking the invariants, including that every request is served by the FIRST listing of its
   key launched after it."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.main.listing-flight :as lf]))

(def ^:private k [:git "/repo"])
(def ^:private j [:walk "/notes"])

(defn- run
  "Apply `steps` ([:request key r] | [:start key] | [:complete key seq]) from `flights`; → [flights all-effects]."
  [flights steps]
  (reduce (fn [[f effs] [op key x]]
            (let [[f' e] (case op
                           :request  (lf/request f key x)
                           :start    (lf/start f key)
                           :complete (lf/complete f key x))]
              [f' (into effs e)]))
          [flights []] steps))

(deftest an-idle-key-schedules-one-start
  (let [[f effs] (lf/request lf/empty-flights k :a)]
    (is (= [[:schedule k]] effs))
    (is (= #{:a} (lf/queued f k)))
    (is (nil? (lf/running f k)))))

(deftest requests-before-the-start-share-its-listing
  (let [[f effs] (run lf/empty-flights [[:request k :a] [:request k :b] [:request k :c] [:start k]])]
    (is (= [[:schedule k] [:list k 1 #{:a :b :c}]] effs) "one schedule, one listing for all three")
    (is (= {:seq 1 :requesters #{:a :b :c}} (lf/running f k)))
    (is (nil? (lf/queued f k)))))

(deftest a-request-never-joins-the-running-listing
  (let [[f effs] (run lf/empty-flights [[:request k :a] [:start k] [:request k :b] [:request k :c]])]
    (is (= [[:schedule k] [:list k 1 #{:a}]] effs) "requests during the listing emit nothing")
    (is (= #{:a} (:requesters (lf/running f k))) "the running listing's requesters are unchanged")
    (is (= #{:b :c} (lf/queued f k)) "they wait for the trailing round"))
  (testing "completion delivers the running listing and schedules exactly one trailing round for everyone queued"
    (let [[f effs] (run lf/empty-flights [[:request k :a] [:start k] [:request k :b] [:request k :c]
                                          [:complete k 1] [:start k]])]
      (is (= [[:schedule k] [:list k 1 #{:a}]
              [:deliver k 1 #{:a}] [:schedule k]
              [:list k 2 #{:b :c}]] effs))
      (is (= {:seq 2 :requesters #{:b :c}} (lf/running f k))))))

(deftest a-completion-with-nothing-queued-leaves-the-key-idle
  (let [[f effs] (run lf/empty-flights [[:request k :a] [:start k] [:complete k 1]])]
    (is (= [:deliver k 1 #{:a}] (last effs)))
    (is (= {} (:keys f)) "an idle key is an absent one")
    (testing "and the next request starts afresh (a failed listing caches nothing)"
      (let [[f2 effs2] (run f [[:request k :b] [:start k]])]
        (is (= [[:schedule k] [:list k 2 #{:b}]] effs2))
        (is (= 2 (:seq (lf/running f2 k))))))))

(deftest stale-completions-and-redundant-starts-change-nothing
  (let [[f _] (run lf/empty-flights [[:request k :a] [:start k]])]
    (is (= [f []] (lf/complete f k 7)) "not the running listing")
    (is (= [f []] (lf/complete f j 1)) "an unknown key")
    (is (= [f []] (lf/start f k)) "already running")
    (is (= [lf/empty-flights []] (lf/start lf/empty-flights k)) "nothing queued")))

(deftest equal-requesters-collapse
  (let [[f _] (run lf/empty-flights [[:request k [:watch [:root "/repo/src"] 3]]
                                     [:request k [:watch [:root "/repo/src"] 3]]])]
    (is (= 1 (count (lf/queued f k))))))

(deftest listing-numbers-increase-across-keys
  (let [[_ effs] (run lf/empty-flights [[:request k :a] [:request j :b] [:start j] [:start k]])]
    (is (= [[:list j 1 #{:b}] [:list k 2 #{:a}]] (filterv #(= :list (first %)) effs)))))

;; ---- randomized interleavings ---------------------------------------------------------------------------------

(defn- lcg
  "A tiny deterministic PRNG (numerical recipes LCG) — the simulation must replay identically."
  [seed]
  (let [s (atom (bit-and seed 0x7fffffff))]
    (fn [n] (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) (mod @s n))))

(defn- simulate
  "Drive the model with `n` random events over 3 keys — requests (unique requesters), pending starts fired, running
   listings completed — then drain it. Returns the invariant violations found (empty when the model holds)."
  [seed n]
  (let [rnd       (lcg seed)
        keyz      [[:git "/r0"] [:git "/r1"] [:walk "/r2"]]
        f         (atom lf/empty-flights)
        clock     (atom 0)               ; the event index: when each request was made and each listing launched
        pending   (atom {})              ; key → number of outstanding [:schedule key]
        running   (atom {})              ; key → the listing number launched and not yet completed
        launches  (atom [])              ; [key seq launched-at] in launch order
        asked     (atom {})              ; requester → [key asked-at]
        served    (atom {})              ; requester → the listing number that delivered it
        last-seq  (atom 0)
        problems  (atom [])
        apply!    (fn [[f' effs]]
                    (reset! f f')
                    (doseq [[op key s reqs] effs]
                      (case op
                        :schedule (swap! pending update key (fnil inc 0))
                        :list     (do (when (contains? @running key) (swap! problems conj [:two-listings key s]))
                                      (when (<= s @last-seq) (swap! problems conj [:seq-not-increasing s]))
                                      (reset! last-seq s)
                                      (swap! running assoc key s)
                                      (swap! launches conj [key s @clock]))
                        :deliver  (doseq [r reqs]
                                    (when (contains? @served r) (swap! problems conj [:delivered-twice r]))
                                    (swap! served assoc r s)))))
        fire!     (fn [key]
                    (when (pos? (get @pending key 0))
                      (swap! pending update key dec)
                      (apply! (lf/start @f key))))
        finish!   (fn [key]
                    (when-let [s (get @running key)]
                      (swap! running dissoc key)
                      (apply! (lf/complete @f key s))))]
    (dotimes [i n]
      (reset! clock i)
      (let [key (nth keyz (rnd 3))]
        (case (rnd 4)
          0 (let [r [:req i]]
              (swap! asked assoc r [key i])
              (apply! (lf/request @f key r)))
          1 (let [r [:req i]]
              (swap! asked assoc r [key i])
              (apply! (lf/request @f key r)))
          2 (fire! key)
          (finish! key)))
      (doseq [[key c] @pending]
        (when (> c 1) (swap! problems conj [:two-schedules-outstanding key]))))
    ;; drain: fire every outstanding start and complete every running listing until nothing moves
    (loop [guard 0]
      (when (< guard 1000)
        (reset! clock (+ n guard))
        (let [due  (seq (keep (fn [[key c]] (when (pos? c) key)) @pending))
              busy (seq (keys @running))]
          (cond due  (do (doseq [key due] (fire! key)) (recur (inc guard)))
                busy (do (doseq [key busy] (finish! key)) (recur (inc guard)))
                :else nil))))
    (when (seq (:keys @f)) (swap! problems conj [:not-idle-after-drain (:keys @f)]))
    ;; every request was delivered exactly once, by the FIRST listing of its key launched after it
    (doseq [[r [key at]] @asked]
      (let [first-after (some (fn [[lk s t]] (when (and (= lk key) (> t at)) s)) @launches)
            launched-at (some (fn [[lk s t]] (when (and (= lk key) (= s (get @served r))) t)) @launches)]
        (cond
          (not (contains? @served r))            (swap! problems conj [:never-served r])
          (not (and launched-at (> launched-at at))) (swap! problems conj [:served-by-an-older-listing r])
          (not= first-after (get @served r))     (swap! problems conj [:waited-behind-more-than-one r]))))
    @problems))

(deftest the-invariants-hold-over-random-interleavings
  (doseq [seed (range 1 41)]
    (is (= [] (simulate seed 400)) (str "seed " seed))))
