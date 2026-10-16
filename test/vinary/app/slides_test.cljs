(ns vinary.app.slides-test
  "The slides layout's app-db side (ADR-0045): the explicit PDF layout lives on the document VIEW — the tab and
   its current history entry — so Back/Forward restores it, a facet switch keeps it, and a new document starts
   from detection again (D14); and the effective layout is the engine's own rule fed the :doc/deck summary."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.nav :as nav]
            [vinary.app.slides :as slides]))

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
    (is (= {:explicit nil :open-as :slides :presenting? false} (slides/layout-request db1)))))
