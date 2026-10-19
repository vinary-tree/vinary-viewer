(ns vinary.app.slides-test
  "The slides layout's app-db side (ADR-0045): the explicit PDF layout lives on the document VIEW — the tab and
   its current history entry — so Back/Forward restores it, a facet switch keeps it, and a new document starts
   from detection again (D14); and the effective layout is the engine's own rule fed the :doc/deck summary."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.events :as events]
            [vinary.app.nav :as nav]
            [vinary.app.slides :as slides]
            [vinary.renderer.pdf-cache :as pdf-cache]))

(defn- tab [id uri] {:id id :uri uri :hist {:stack [{:uri uri :scroll 0 :facet nil}] :idx 0}})

(def ^:private db1 {:ui {:active-tab 1 :tabs [(tab 1 "/d/talk.pdf") (tab 2 "/d/paper.pdf")]}})

(deftest explicit-layout-is-per-document-view
  (let [db (nav/set-pdf-layout db1 :document)]
    (testing "set on the tab AND its current history entry, the other tab untouched"
      (is (= :document (nav/pdf-layout db)))
      (is (= :document (get-in (nav/active-tab db) [:hist :stack 0 :pdf-layout])))
      (is (nil? (:pdf-layout (second (get-in db [:ui :tabs]))))))
    (testing "nil clears it (back to detection + preference)"
      (let [db' (nav/set-pdf-layout db nil)]
        (is (nil? (nav/pdf-layout db')))
        (is (not (contains? (get-in (nav/active-tab db') [:hist :stack 0]) :pdf-layout)))))
    (testing "navigating to ANOTHER document starts from the default; re-opening the same uri keeps it"
      (is (nil? (nav/pdf-layout (nav/nav-active db "/d/other.pdf" {:scroll 0}))))
      (is (= :document (nav/pdf-layout (nav/nav-active db "/d/talk.pdf" {:scroll 0})))))
    (testing "Back restores the entry's explicit layout; Forward to an entry without one clears it"
      (let [away     (nav/nav-active db "/d/other.pdf" {:scroll 0})
            [back _] (nav/step away -1 {:scroll 0})
            [fwd _]  (nav/step back 1 {:scroll 0})]
        (is (= :document (nav/pdf-layout back)))
        (is (nil? (nav/pdf-layout fwd)))))
    (testing "a facet switch within the document group carries the layout into its new entry"
      (let [db' (nav/push-facet db 1 "/d/talk.tex" :source {:scroll 0} nil)
            {:keys [stack idx]} (:hist (nav/active-tab db'))]
        (is (= :document (get-in stack [idx :pdf-layout])))))
    (testing "the background-tab navigation path clears it too"
      (is (nil? (:pdf-layout (first (get-in (nav/nav-tab db 1 "/d/x.pdf") [:ui :tabs]))))))))

(deftest effective-layout-rule
  (let [deck {:deck? true :tier :definite :source :beamer}]
    (is (= :slides (slides/effective-layout db1 deck)) "a detected deck opens as slides by default")
    (is (= :document (slides/effective-layout db1 {:deck? false})) "an ordinary PDF stays a document")
    (is (= :document (slides/effective-layout db1 nil)))
    (is (= :document (slides/effective-layout (assoc-in db1 [:ui :settings :slides-open-as] :document) deck))
        "the preference can open decks as documents")
    (is (= :slides (slides/effective-layout (nav/set-pdf-layout db1 :slides) {:deck? false}))
        "View as Slide Show works for any PDF")
    (is (= :document (slides/effective-layout (nav/set-pdf-layout db1 :document) deck))
        "an explicit choice beats detection")
    (is (= :slides (slides/effective-layout (-> db1 (nav/set-pdf-layout :document)
                                                (assoc-in [:ui :presentation :active?] true))
                                            {:deck? false}))
        "presenting always shows slides")
    (is (= :slides (slides/open-as db1)) "the default preference")
    (is (= {:explicit nil :open-as :slides :presenting? false :present-from nil :transition :none :notes :auto
            :presenter-view? false}
           (slides/layout-request db1)))))

(deftest the-transition-preference
  (is (= :none (slides/transition {})) "the default: an instant flip")
  (doseq [t [:fade :push]]
    (is (= t (slides/transition {:ui {:settings {:slides-transition t}}}))))
  (is (= :none (slides/transition {:ui {:settings {:slides-transition :spin}}})) "an unknown kind reads as none")
  (is (= :push (:transition (slides/layout-request {:ui {:settings {:slides-transition :push}}})))
      "the engine receives it through the layout request"))

(deftest the-notes-preference
  (is (= :auto (slides/notes-position {})) "the default: Beamer's own (notes on the right, or below)")
  (doseq [v [:right :left :bottom :top :off]]
    (is (= v (slides/notes-position {:ui {:settings {:slides-notes v}}}))))
  (is (= :auto (slides/notes-position {:ui {:settings {:slides-notes :sideways}}})) "an unknown value reads as auto")
  (is (= :left (:notes (slides/layout-request {:ui {:settings {:slides-notes :left}}})))
      "the engine receives it through the layout request"))

(deftest the-slide-is-remembered-not-the-pixel-offset
  (let [t       (tab 1 "/d/talk.pdf")
        src-tab (assoc-in t [:hist :stack 0 :facet] {:path "/d/talk.tex" :type :source})]
    (testing "leaving a PDF in the slides layout saves its slide beside the pixel offset"
      (is (= {:uri "/d/talk.pdf" :scroll 900 :facet nil :page 7}
             (get-in (nav/capture-pos t {:scroll 900 :line nil :page 7}) [:hist :stack 0]))))
    (testing "a later position without a slide clears a stale one (the layout changed, or another document)"
      (let [t' (nav/capture-pos (nav/capture-pos t {:scroll 900 :page 7}) {:scroll 40 :page nil})]
        (is (not (contains? (get-in t' [:hist :stack 0]) :page)))
        (is (= 40 (get-in t' [:hist :stack 0 :scroll])))))
    (testing "a :source entry keeps its line and never a slide"
      (is (= {:uri "/d/talk.pdf" :scroll 0 :facet {:path "/d/talk.tex" :type :source} :line 12}
             (get-in (nav/capture-pos src-tab {:scroll 900 :line 12 :page 7}) [:hist :stack 0])))))
  (testing "restoring an entry with a slide parks it for the pdf-view of the entry's document"
    (is (= [:slides/want-page {:path "/d/talk.pdf" :page 7}]
           (events/entry-restore-fx {:uri "/d/talk.pdf" :scroll 900 :facet nil :page 7}))
        "a local file: its path")
    (is (= [:slides/want-page {:path "/d/talk.pdf" :page 2}]
           (events/entry-restore-fx {:uri "/d/talk.tex" :scroll 0 :facet {:path "/d/talk.pdf" :type :preview} :page 2}))
        "a collocated group's PDF facet: the facet's path")
    (is (= [:slides/want-page {:path "sftp://host/~/talk.pdf" :page 3}]
           (events/entry-restore-fx {:uri "sftp://host/~/talk.pdf" :scroll 0 :facet nil :page 3}))
        "a remote PDF is keyed by its uri")
    (is (= [:scroll/restore 900] (events/entry-restore-fx {:uri "/d/paper.pdf" :scroll 900 :facet nil}))
        "no slide: the pixel offset as before")))

(deftest a-parked-slide-is-taken-once-by-its-own-document
  (pdf-cache/want-page! "/d/talk.pdf" 5)
  (is (nil? (pdf-cache/take-want-page! "/d/other.pdf")) "another document's mount does not take it")
  (is (= 5 (pdf-cache/take-want-page! "/d/talk.pdf")))
  (is (nil? (pdf-cache/take-want-page! "/d/talk.pdf")) "taken once"))

(deftest closing-the-active-tab-restores-the-revealed-one
  (let [deck  (assoc-in (tab 1 "/d/talk.pdf") [:hist :stack 0 :page] 4)
        blank {:id 2 :uri nil :hist {:stack [{:uri nil :scroll 0 :facet nil}] :idx 0}}
        db    {:ui {:active-tab 2 :tabs [deck blank]}}
        {:keys [fx] db' :db} (events/close-tab-result db 2)]
    (is (= 1 (get-in db' [:ui :active-tab])) "the deck tab is revealed")
    (is (some #{[:slides/want-page {:path "/d/talk.pdf" :page 4}]} fx)
        "…on the slide it was left on, like an explicit tab switch"))
  (testing "closing a BACKGROUND tab restores nothing (the shown view never changed)"
    (let [deck  (assoc-in (tab 1 "/d/talk.pdf") [:hist :stack 0 :page] 4)
          other (tab 2 "/d/b.pdf")
          {:keys [fx]} (events/close-tab-result {:ui {:active-tab 1 :tabs [deck other]}} 2)]
      (is (not-any? #(= :slides/want-page (first %)) fx)))))
