(ns vinary.ui.slides-entry-test
  "Slide-show entry points (ADR-0045 §Entry points): the View menu's PDF section in every layout — which items show,
   the flipping layout label and its access key, and that no two VISIBLE items ever share an access key (Reflow
   moved r → w off Reset Zoom's r) — and the PDF content-pane context menu's slide items."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ui.context-menu :as context-menu]
            [vinary.ui.menubar :as menubar]))

(defn- view-items [ctx] (filterv map? (:items (menubar/realized-menu ctx "View"))))
(defn- labels [items] (mapv #(or (:label %) (:submenu %)) items))
(defn- by-label [items label] (first (filter #(= label (or (:label %) (:submenu %))) items)))

(def ^:private ctxs
  {:no-pdf      {:pdf? false}
   :pdf-doc     {:pdf? true :slides? false}
   :pdf-slides  {:pdf? true :slides? true}
   :diff        {:diff? true}
   :diff-all    {:diff? true :diff-all-collapsed? true}})

(deftest view-menu-pdf-section
  (testing "no PDF → no PDF or slide items"
    (let [ls (set (labels (view-items (:no-pdf ctxs))))]
      (is (not-any? ls ["Fit" "Invert PDF" "Reflow Text" "View as Slide Show" "Start Slide Show"
                        "Start from Current Slide" "Slide Overview"]))))
  (testing "a PDF in the document layout: Fit, View as Slide Show (l), the show items, no overview"
    (let [items (view-items (:pdf-doc ctxs))]
      (is (some? (by-label items "Fit")))
      (is (= "l" (:access-key (by-label items "View as Slide Show"))))
      (is (= [:pdf/layout-toggle] (:event (by-label items "View as Slide Show"))))
      (is (= [:slides/present {:from :first}] (:event (by-label items "Start Slide Show"))))
      (is (= "F5" (:accel (by-label items "Start Slide Show"))))
      (is (= [:slides/present {:from :current}] (:event (by-label items "Start from Current Slide"))))
      (is (nil? (by-label items "Slide Overview")))))
  (testing "a PDF in the slides layout: no Fit; the toggle flips to View as Document (m); the overview shows"
    (let [items (view-items (:pdf-slides ctxs))]
      (is (nil? (by-label items "Fit")) "slides fit themselves")
      (is (nil? (by-label items "View as Slide Show")))
      (is (= "m" (:access-key (by-label items "View as Document"))))
      (is (= [:pdf/layout-toggle] (:event (by-label items "View as Document"))) "one static event, both labels")
      (is (= [:slides/overview {:open? true}] (:event (by-label items "Slide Overview"))))))
  (testing "Reflow Text moved to w — r is Reset Zoom's"
    (is (= "w" (:access-key (by-label (view-items (:pdf-doc ctxs)) "Reflow Text"))))
    (is (= "r" (:access-key (by-label (view-items (:pdf-doc ctxs)) "Reset Zoom"))))))

(deftest every-visible-view-item-has-a-unique-access-key-that-is-in-its-label
  (doseq [[nm ctx] ctxs]
    (let [items (view-items ctx)
          keys  (keep :access-key items)]
      (is (= (count keys) (count (set keys))) (str nm ": duplicate access keys " (frequencies keys)))
      (doseq [{:keys [access-key] :as item} items
              :when access-key
              :let [label (or (:label item) (:submenu item))]]
        (is (re-find (re-pattern (str "(?i)" access-key)) label)
            (str nm ": '" access-key "' must be a letter of \"" label "\" (it is underlined there)"))))))

(deftest pdf-context-menu-slide-items
  (let [items-of (fn [pdf] (remove #(or (nil? %) (= :sep %))
                                   (context-menu/items-for {:kind :preview-body :text "x" :pdf pdf} false false)))
        ls       (fn [pdf] (mapv :label (items-of pdf)))]
    (testing "document layout: start, start from the right-clicked page, and the layout toggle"
      (is (= ["Copy" "Start Slide Show" "Start From This Slide" "View as Slide Show"]
             (ls {:page 4 :layout :document :presenting? false})))
      (is (= [:slides/present {:from 4}]
             (:event (first (filter #(= "Start From This Slide" (:label %)) (items-of {:page 4 :layout :document}))))))
      (is (= [:slides/present {:from :first}]
             (:event (first (filter #(= "Start Slide Show" (:label %)) (items-of {:page 4 :layout :document})))))))
    (testing "slides layout: the toggle reads View as Document; no page → no Start From This Slide"
      (is (= ["Copy" "Start Slide Show" "View as Document"] (ls {:page nil :layout :slides}))))
    (testing "presenting: only End Slide Show"
      (is (= ["Copy" "End Slide Show"] (ls {:page 2 :layout :slides :presenting? true}))))
    (testing "not a PDF: unchanged"
      (is (= ["Copy"] (mapv :label (remove #(or (nil? %) (= :sep %))
                                         (context-menu/items-for {:kind :preview-body :text "x"} false false))))))))
