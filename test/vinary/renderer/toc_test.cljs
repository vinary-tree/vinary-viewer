(ns vinary.renderer.toc-test
  "DOM-free unit tests for the generalized, content-agnostic scroll-spy core (vinary.renderer.toc). The pure
   binary search `active-heading` is the single algorithm every host-rendered preview (Markdown, PDF, and any
   future kind) shares once its :doc/toc ids are measured into {:id :offset} anchors, so these tests exercise
   it against BOTH content shapes — dense sequential offsets (Markdown headings) and sparse page offsets with
   gaps (a PDF page outline where most pages have no entry) — plus the boundary cases. This is the regression
   guard whose absence let the PDF path silently break: as long as this stays green, no content type can
   regress the section-following behavior."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.toc :as toc]))

(deftest active-heading-empty
  (testing "no anchors → nil at any scroll position"
    (is (nil? (toc/active-heading [] 0)))
    (is (nil? (toc/active-heading [] 999)))))

(deftest active-heading-dense-markdown-like
  (testing "dense sequential offsets (Markdown headings): greatest :offset ≤ scroll+100"
    (let [hs [{:id "a" :offset 0} {:id "b" :offset 120} {:id "c" :offset 340} {:id "d" :offset 500}]]
      (is (= "a" (toc/active-heading hs 0)))        ; target 100 → a(0)≤100, b(120)>100
      (is (= "b" (toc/active-heading hs 20)))        ; target 120 == b.offset → tie inclusive → b
      (is (= "c" (toc/active-heading hs 300)))       ; target 400 → c(340)≤400, d(500)>400
      (is (= "d" (toc/active-heading hs 500)))       ; target 600 → d
      (is (= "d" (toc/active-heading hs 99999))))))  ; far past the end → last

(deftest active-heading-sparse-pdf-like
  (testing "sparse page offsets with gaps (PDF outline: pages 1,5,12) → greatest anchor page ≤ current page"
    (let [hs [{:id "vv-pdf-page-1" :offset 0}
              {:id "vv-pdf-page-5" :offset 4000}
              {:id "vv-pdf-page-12" :offset 11000}]]
      (is (= "vv-pdf-page-1"  (toc/active-heading hs 0)))
      (is (= "vv-pdf-page-1"  (toc/active-heading hs 3899)))    ; target 3999 < 4000 → pages 2-4 keep page-1
      (is (= "vv-pdf-page-5"  (toc/active-heading hs 3901)))    ; target 4001 ≥ 4000 → page-5
      (is (= "vv-pdf-page-5"  (toc/active-heading hs 10899)))   ; target 10999 < 11000 → pages 6-11 keep page-5
      (is (= "vv-pdf-page-12" (toc/active-heading hs 20000))))))

(deftest active-heading-above-first
  (testing "scrolled above the first anchor → nil, until the anchor reaches the reading margin"
    (let [hs [{:id "x" :offset 200} {:id "y" :offset 400}]]
      (is (nil? (toc/active-heading hs 0)))          ; target 100 < 200 → nil
      (is (= "x" (toc/active-heading hs 100))))))     ; target 200 == x.offset → x

(deftest active-heading-single
  (testing "a single anchor activates once its offset is within the margin, nil below it"
    (let [hs [{:id "only" :offset 50}]]
      (is (= "only" (toc/active-heading hs 0)))       ; target 100 ≥ 50
      (is (nil? (toc/active-heading hs -100))))))      ; target 0 < 50 → nil

(deftest active-heading-margin-arg
  (testing "explicit margin shifts the activation boundary (3-arity)"
    (let [hs [{:id "a" :offset 0} {:id "b" :offset 50}]]
      (is (= "a" (toc/active-heading hs 0 0)))         ; target 0 → a(0)≤0, b(50)>0
      (is (= "b" (toc/active-heading hs 50 0)))        ; target 50 == b → b
      (is (= "b" (toc/active-heading hs 0 100))))))     ; target 100 → b

(deftest active-heading-never-fabricates
  (testing "the result is always nil or a real member id across a scroll sweep (both shapes)"
    (doseq [hs [[{:id "a" :offset 0} {:id "b" :offset 100} {:id "c" :offset 250}]
                [{:id "vv-pdf-page-1" :offset 0} {:id "vv-pdf-page-9" :offset 8000}]]]
      (let [ids (set (map :id hs))]
        (doseq [s (range -200 12000 37)]
          (let [a (toc/active-heading hs s)]
            (is (or (nil? a) (contains? ids a)))))))))

(deftest active-heading-monotonic-non-decreasing
  (testing "as you scroll down, the active anchor never moves backward (offset never decreases)"
    (let [hs [{:id "a" :offset 0} {:id "b" :offset 120} {:id "c" :offset 340} {:id "d" :offset 500}]
          off (into {} (map (juxt :id :offset)) hs)]
      (loop [s -50 prev -1]
        (when (<= s 700)
          (let [a (toc/active-heading hs s)
                cur (if a (off a) -1)]
            (is (>= cur prev) (str "regressed at scroll " s))
            (recur (+ s 13) cur)))))))
