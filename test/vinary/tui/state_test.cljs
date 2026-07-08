(ns vinary.tui.state-test
  "The key→command reducer: scroll bindings, find-mode query entry + search + jump, toc overlay navigation + jump,
   quit paths, and the bracketed-paste guard (a pasted `q` must not quit)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.tui.state :as state]
            [vinary.tui.viewport :as vp]))

(defn- st []
  (state/init (-> (vp/viewport 80 3) (vp/set-lines ["alpha one" "beta two" "gamma alpha" "delta" "epsilon alpha"]))
              [{:level 1 :text "Top" :line 0} {:level 2 :text "Mid" :line 3}]))
(defn- run [s & evs] (reduce state/step s evs))
(defn- top [s] (get-in s [:vp :top]))

(deftest scroll-bindings
  (is (= 1 (top (run (st) {:type :down}))))
  (is (= 1 (top (run (st) {:type :char :ch "j"}))))
  (is (= 0 (top (run (st) {:type :char :ch "j"} {:type :char :ch "k"}))))
  (is (= 2 (top (run (st) {:type :end}))) "G/end → bottom (5 lines, h 3 → top 2)")
  (is (= 2 (top (run (st) {:type :char :ch "G"}))))
  (is (= 0 (top (run (st) {:type :char :ch "G"} {:type :char :ch "g"})))))

(deftest find-flow
  (testing "/ enters find mode, chars build the query, Enter searches + jumps to the first match"
    (let [s (run (st) {:type :char :ch "/"} {:type :char :ch "a"} {:type :char :ch "l"} {:type :char :ch "p"})]
      (is (= :find (:mode s)))
      (is (= "alp" (:query s)))
      (let [s (state/step s {:type :enter})]
        (is (= :normal (:mode s)))
        (is (= 3 (count (:matches (:find s)))) "alpha appears on 3 lines")
        (is (= 0 (get-in s [:find :idx])) "cursor on the first match"))))
  (testing "n advances the match cursor and jumps"
    (let [s (-> (st) (run {:type :char :ch "/"} {:type :char :ch "a"} {:type :char :ch "l"}
                          {:type :char :ch "p"} {:type :char :ch "h"} {:type :char :ch "a"} {:type :enter}))
          s2 (state/step s {:type :char :ch "n"})]
      (is (= 1 (get-in s2 [:find :idx])))
      (is (= 0 (top (run (st) {:type :char :ch "/"}))) "opening find doesn't scroll")))
  (testing "Esc cancels find"
    (is (= :normal (:mode (run (st) {:type :char :ch "/"} {:type :char :ch "x"} {:type :escape}))))))

(deftest toc-flow
  (testing "t opens the overlay; down selects; Enter jumps to the entry's line and closes"
    (let [s (run (st) {:type :char :ch "t"})]
      (is (= :toc (:mode s)))
      (let [s (run s {:type :down} {:type :enter})]
        (is (= :normal (:mode s)))
        (is (= 1 (top s)) "jumped near the Mid heading (line 3 → top clamped)"))))
  (testing "t on a doc with no headings does nothing"
    (let [s (state/init (vp/set-lines (vp/viewport 80 3) ["a" "b"]) [])]
      (is (= :normal (:mode (state/step s {:type :char :ch "t"})))))))

(deftest quit-paths
  (is (:quit? (state/step (st) {:type :char :ch "q"})))
  (is (:quit? (state/step (st) {:type :interrupt})) "Ctrl-C (byte 0x03) quits")
  (testing "bracketed-paste guard: a pasted q does NOT quit in normal mode"
    (let [s (run (st) {:type :paste-start} {:type :char :ch "q"} {:type :paste-end})]
      (is (not (:quit? s)) "pasted q ignored as a command")
      (is (not (:pasting? s)) "paste mode ended"))))
