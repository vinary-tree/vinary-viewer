(ns vinary.app.nav-test
  "Pure unit tests for the per-tab view-FACET helpers in vinary.app.nav (set-facet / facet / facet-mru) and for the
   retention set, which must include each tab's shown + MRU facet paths so a just-loaded sibling isn't evicted."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.nav :as nav]))

(def ^:private db1
  {:ui {:active-tab 1
        :tabs [{:id 1 :uri "file:///a/paper.tex" :hist {:stack [{:uri "file:///a/paper.tex" :scroll 0}] :idx 0}}
               {:id 2 :uri "file:///a/notes.md"  :hist {:stack [{:uri "file:///a/notes.md" :scroll 0}] :idx 0}}]}})

(deftest facet-transforms
  (testing "a fresh tab has no explicit facet (nil = follow the default)"
    (is (nil? (nav/facet db1)))
    (is (nil? (nav/facet-mru db1))))
  (testing "set-facet stores {:path :type} + the per-type MRU on the ACTIVE tab only"
    (let [db' (nav/set-facet db1 "/a/paper.pdf" :preview)]
      (is (= {:path "/a/paper.pdf" :type :preview} (nav/facet db')))
      (is (= "/a/paper.pdf" (get (nav/facet-mru db') :preview)))
      (is (nil? (:facet (nth (get-in db' [:ui :tabs]) 1))) "the other tab is untouched")))
  (testing "set-facet by explicit id targets that tab"
    (let [db' (nav/set-facet db1 2 "/a/notes.md" :source)]
      (is (= {:path "/a/notes.md" :type :source} (:facet (nth (get-in db' [:ui :tabs]) 1))))
      (is (nil? (nav/facet db')) "the active tab (id 1) is untouched")))
  (testing "the MRU accumulates a last-file per type across switches"
    (let [db' (-> db1 (nav/set-facet "/a/paper.pdf" :preview) (nav/set-facet "/a/paper.tex" :source))]
      (is (= {:path "/a/paper.tex" :type :source} (nav/facet db')) "the latest switch is the active facet")
      (is (= {:preview "/a/paper.pdf" :source "/a/paper.tex"} (nav/facet-mru db')) "both types remembered"))))

(deftest retained-file-paths-includes-facet
  (testing "the retained set is each tab's history uris (as local paths) PLUS its active + MRU facet paths — the
            facet paths are load-bearing (a shown facet is not a history entry, so without them the just-loaded
            sibling would be retracted/unwatched)"
    (let [db' (nav/set-facet db1 "/a/paper.pdf" :preview)
          ret (set (nav/retained-file-paths db'))]
      (is (contains? ret "/a/paper.tex") "tab-1 history file (from its file:// uri)")
      (is (contains? ret "/a/notes.md")  "tab-2 history file")
      (is (contains? ret "/a/paper.pdf") "the active facet's file — the addition this feature requires"))))

;; ── Part 2: a view (facet) switch is a HISTORY event (Back/Forward returns to the previous view + location) ──
(def ^:private empty-db {:ui {:tabs [] :active-tab nil :next-tab-id 0}})

(deftest push-facet-history
  (testing "push-facet pushes a new same-uri entry carrying the facet, mirrors the tab facet, and saves the
            leaving preview scroll onto the prior entry"
    (let [db0 (nav/add-tab empty-db "file:///a/paper.tex")               ; entry 0: default view (facet nil)
          db1 (nav/push-facet db0 0 "/a/paper.tex" :source {:scroll 220 :line 4} nil)  ; leave preview@220 → source
          t   (nav/active-tab db1)]
      (is (= {:path "/a/paper.tex" :type :source} (nav/facet db1)) "the tab facet mirrors the new entry")
      (is (= 2 (count (get-in t [:hist :stack]))) "a new history entry was pushed")
      (is (= 1 (get-in t [:hist :idx])))
      (is (= 220 (get-in t [:hist :stack 0 :scroll])) "the leaving preview pixel scroll saved on the prior entry")
      (is (= "file:///a/paper.tex" (get-in t [:hist :stack 1 :uri])) "the new entry keeps the same uri (same doc)")
      (is (= {:path "/a/paper.tex" :type :source} (get-in t [:hist :stack 1 :facet])) "the new entry carries the facet")
      (is (= "/a/paper.tex" (get (nav/facet-mru db1) :source)) "the switch is remembered as the type's MRU")))
  (testing "a goto-line target populates the new entry's :line; a plain switch leaves it at the top"
    (let [db0 (nav/add-tab empty-db "file:///a/paper.tex")
          dbj (nav/push-facet db0 0 "/a/paper.tex" :source {:scroll 0 :line 1} {:line 88})]
      (is (= 88 (get-in (nav/active-tab dbj) [:hist :stack 1 :line])) "the jump target line is recorded on the entry")))
  (testing "push-facet truncates any forward history (a switch is a new branch)"
    (let [db0 (nav/add-tab empty-db "file:///a/paper.tex")
          db1 (nav/push-facet db0 0 "/a/paper.tex" :source  {:scroll 0 :line 1} nil)
          [db2 _ _] (nav/step db1 -1 {:scroll 0 :line 1})   ; back to entry 0 (a forward entry now exists)
          db3 (nav/push-facet db2 0 "/a/paper.pdf" :preview {:scroll 0 :line 1} nil)]  ; switch → truncates forward
      (is (= 2 (count (get-in (nav/active-tab db3) [:hist :stack]))) "the stale forward entry was truncated")
      (is (= {:path "/a/paper.pdf" :type :preview} (get-in (nav/active-tab db3) [:hist :stack 1 :facet]))))))

(deftest step-restores-facet
  (testing "Back restores the prior entry's facet onto the tab (not just the uri) and, leaving a source view,
            saves the source viewport :line (not a meaningless pixel scroll); Forward returns to the source view"
    (let [db0 (nav/add-tab empty-db "file:///a/paper.tex")
          db1 (nav/push-facet db0 0 "/a/paper.tex" :source {:scroll 0 :line 1} nil)  ; entry 1 = source (scroll 0)
          ;; Back, leaving source at viewport line 37 — the pixel 999 must be IGNORED (a source entry stores :line)
          [db2 uri2 entry2] (nav/step db1 -1 {:scroll 999 :line 37})]
      (is (= "file:///a/paper.tex" uri2))
      (is (nil? (nav/facet db2)) "Back restored entry 0's facet (nil = the default preview)")
      (is (nil? (:facet entry2)) "the returned entry is the default-view entry")
      (is (= 37 (get-in (nav/active-tab db2) [:hist :stack 1 :line]))
          "leaving the source view saved its viewport LINE on entry 1 (a source has no meaningful pixel scroll)")
      (is (= 0 (get-in (nav/active-tab db2) [:hist :stack 1 :scroll]))
          "…and did NOT clobber :scroll with the pixel 999 — capture is facet-aware (source → :line only)")
      (let [[db3 _uri3 entry3] (nav/step db2 1 {:scroll 0 :line 1})]  ; Forward, back into source
        (is (= {:path "/a/paper.tex" :type :source} (nav/facet db3)) "Forward restores the source facet onto the tab")
        (is (= {:path "/a/paper.tex" :type :source} (:facet entry3)))
        (is (= 37 (:line entry3)) "Forward returns to the captured source line")
        (is (= "/a/paper.tex" (get (nav/facet-mru db3) :source)) "the restored facet updates the type MRU")))))

(deftest retained-scans-entry-facets
  (testing "a Back-reachable ENTRY facet file is retained even when the tab's current facet is a different file
            (without the entry-facet scan the first eviction pass would retract the Back target's sibling)"
    (let [db0 (nav/add-tab empty-db "file:///a/paper.tex")
          db1 (nav/push-facet db0 0 "/a/paper.pdf" :preview {:scroll 0 :line 1} nil)  ; entry 1: pdf preview
          db2 (nav/push-facet db1 0 "/a/paper.tex" :source  {:scroll 0 :line 1} nil)  ; entry 2: tex source (current)
          ret (set (nav/retained-file-paths db2))]
      (is (contains? ret "/a/paper.tex") "the current facet + the history uri")
      (is (contains? ret "/a/paper.pdf") "the Back-reachable entry-1 facet file — the retention this feature adds"))))
