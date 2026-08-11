(ns vinary.git.graph-test
  "Lane assignment (ADR-0039): topology cases pinned row by row, and the paging-equivalence
   property that makes incremental page appends sound."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.git.graph :as graph]))

(defn- c [hash & parents] {:hash hash :parents (vec parents)})
(defn- rows [commits] (:rows (graph/assign (graph/init-state) commits)))

(deftest linear-chain-stays-on-lane-zero
  (let [[a b r] (rows [(c "A" "B") (c "B" "R") (c "R")])]
    (is (= [0 0 0] [(:lane a) (:lane b) (:lane r)]))
    (is (= [{:from 0 :to 0 :kind :continue}] (:edges a)))
    (is (= [{:from 0 :to 0 :kind :continue}] (:edges b)))
    (is (= [] (:edges r)) "a root ends its lane at the dot")
    (is (= [1 1 0] [(:active a) (:active b) (:active r)]))))

(deftest diamond-branch-and-merge
  ;; M merges A and B; both descend from R.   M → {A,B} → R
  (let [[m a b r] (rows [(c "M" "A" "B") (c "A" "R") (c "B" "R") (c "R")])]
    (is (= 0 (:lane m)))
    (is (= [{:from 0 :to 0 :kind :continue} {:from 0 :to 1 :kind :branch}] (:edges m))
        "the merge dot continues to its first parent and opens a lane for the second")
    (is (= 0 (:lane a)))
    (is (= [{:from 0 :to 0 :kind :continue} {:from 1 :to 1 :kind :pass}] (:edges a))
        "B's expectation passes through A's row untouched")
    (is (= 1 (:lane b)))
    (is (= [{:from 1 :to 0 :kind :merge} {:from 0 :to 0 :kind :pass}] (:edges b))
        "B joins the lane already expecting R; lane 0's own line still passes through")
    (is (= 0 (:lane r)))
    (is (= [2 2 1 0] (mapv :active [m a b r])))))

(deftest octopus-opens-one-lane-per-extra-parent
  (let [[m] (rows [(c "M" "A" "B" "C")])]
    (is (= [{:from 0 :to 0 :kind :continue}
            {:from 0 :to 1 :kind :branch}
            {:from 0 :to 2 :kind :branch}]
           (:edges m)))
    (is (= 3 (:active m)))))

(deftest sibling-children-collapse-into-the-older-commit
  ;; Two branch tips (no merge commit) both descend from R: T1 and T2 each expect R,
  ;; so R's row collapses the second child-edge into the leftmost one.
  (let [[t1 t2 r] (rows [(c "T1" "R") (c "T2" "R") (c "R")])]
    (is (= 0 (:lane t1)))
    (is (= 1 (:lane t2)) "an unexpected tip opens the next free lane")
    (is (= [{:from 1 :to 0 :kind :merge} {:from 0 :to 0 :kind :pass}] (:edges t2))
        "T2's line joins the lane already expecting R")
    (is (= 0 (:lane r)))
    (is (= [] (:edges r)))))

(deftest collapse-edges-when-both-lanes-expect-the-same-commit
  ;; Force two lanes to expect R simultaneously: M(A,B) → A→R, B→R would merge early (B sees lane 0
  ;; expecting R). To exercise :collapse, both expectations must survive until R's own row — two
  ;; UNRELATED tips whose parents differ, converging on a shared grandparent, cannot do that either
  ;; (the leftmost-expectation merge fires first). :collapse therefore fires exactly when several
  ;; lanes expect R with NO intermediate row between the last of them and R — adjacent tips.
  (let [[t1 t2 r] (rows [(c "T1" "R") (c "T2" "X") (c "R")])]
    ;; T2 keeps lane 1 busy expecting X, so R still has only lane 0 expecting it — lane 1 passes.
    (is (= [{:from 1 :to 1 :kind :pass}] (:edges r)))
    (is (= [0 1 0] (mapv :lane [t1 t2 r]))))
  ;; The direct collapse shape: two tips, SAME parent, parent immediately next.
  (let [state    (graph/init-state)
        {s1 :state} (graph/assign state [(c "T1" "R")])
        ;; hand-craft a second expectation for R on lane 1 (what a page boundary can produce)
        {r2 :rows} (graph/assign {:lanes (conj (:lanes s1) "R")} [(c "R")])]
    (is (= 0 (:lane (first r2))))
    (is (= [{:from 1 :to 0 :kind :collapse}] (:edges (first r2)))
        "the sibling expectation curves into the dot and its lane frees")))

(deftest freed-lanes-are-reused-leftmost-first
  (let [[t1 r1 t2] (rows [(c "T1" "R1") (c "R1") (c "T2" "R2")])]
    (is (= 0 (:lane t1)))
    (is (= 0 (:lane r1)))
    (is (= 0 (:lane t2)) "the lane freed by R1's root row is reused by the next tip")))

(deftest criss-cross-merges
  (let [[m1 m2 a b] (rows [(c "M1" "A" "B") (c "M2" "B" "A") (c "A" "R") (c "B" "R")])]
    (is (= 0 (:lane m1)))
    (is (= 2 (:lane m2)) "no lane expects M2 and none is free — it appends")
    (is (= [{:from 2 :to 1 :kind :merge} {:from 2 :to 0 :kind :merge}
            {:from 0 :to 0 :kind :pass} {:from 1 :to 1 :kind :pass}]
           (:edges m2))
        "both of M2's parent legs join existing expectations; its own lane closes")
    (is (= [0 1] [(:lane a) (:lane b)]))))

(deftest date-order-skew-degrades-to-a-fresh-lane
  ;; A parent emitted BEFORE its child (broken children-first precondition): the child later finds
  ;; nobody expecting it and simply opens a lane — no throw, deterministic output.
  (let [[p orphan] (rows [(c "P") (c "C" "P")])]
    (is (= 0 (:lane p)))
    (is (= 0 (:lane orphan)) "the freed lane is reused")
    (is (= [{:from 0 :to 0 :kind :continue}] (:edges orphan))
        "the stale expectation continues below the window rather than exploding")))

(deftest paging-equivalence-property
  ;; assign(s, a ++ b) ≡ append of page-wise assigns — at EVERY split point.
  (let [commits [(c "M1" "A" "B") (c "M2" "B" "A") (c "A" "R") (c "B" "R") (c "R")
                 (c "T" "Q") (c "Q" "Z") (c "Z")]
        whole   (graph/assign (graph/init-state) commits)]
    (doseq [k (range (inc (count commits)))]
      (let [{rows-a :rows state-a :state} (graph/assign (graph/init-state) (take k commits))
            {rows-b :rows state-b :state} (graph/assign state-a (drop k commits))]
        (is (= (:rows whole) (into rows-a rows-b)) (str "rows diverge at split " k))
        (is (= (:state whole) state-b) (str "state diverges at split " k))))))

(deftest max-lane-tracks-dots-and-through-lanes
  (let [rs (rows [(c "M" "A" "B" "C") (c "A" "R") (c "B" "R") (c "C" "R") (c "R")])]
    (is (= 2 (graph/max-lane rs))))
  (is (= 0 (graph/max-lane []))))
