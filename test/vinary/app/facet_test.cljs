(ns vinary.app.facet-test
  "DOM-free unit tests for the pure document-FACET core (vinary.app.facet): how a collocated group's Preview/Source
   options are ordered, which are shown, the default + active facet, the combo main/toggle targets, and the toolbar
   view-model. The glue fns (group-of/resolve-facet/…) read DataScript and are exercised via the E2E harness."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.facet :as facet]))

;; the confirmed example: an Org document opened alongside its LaTeX source and compiled PDF
(def ^:private group
  [{:path "/a/p.org" :kind "org"} {:path "/a/p.tex" :kind "latex"} {:path "/a/p.pdf" :kind "pdf"}])
(def ^:private primary "/a/p.org")
(def ^:private lone-pdf [{:path "/a/x.pdf" :kind "pdf"}])
(def ^:private lone-md  [{:path "/a/n.md" :kind "markdown"}])

(deftest ordering
  (testing "ordered-members: opened file first, then by kind-priority (pdf<org<latex), then path"
    (is (= ["/a/p.org" "/a/p.pdf" "/a/p.tex"] (mapv :path (facet/ordered-members group primary)))))
  (testing "preview-options hoist the PDF to the head, then the ordered remainder → the example's [PDF Org LaTeX]"
    (is (= ["/a/p.pdf" "/a/p.org" "/a/p.tex"] (mapv :path (facet/preview-options group primary)))))
  (testing "source-options are the text sources (never the PDF), opened-file-first → the example's [Org LaTeX]"
    (is (= ["/a/p.org" "/a/p.tex"] (mapv :path (facet/source-options group primary))))))

(deftest show-hide
  (testing "a group with sources → shown"        (is (true?  (facet/show-view-switch? group primary))))
  (testing "a lone markdown (render + source) → shown" (is (true?  (facet/show-view-switch? lone-md "/a/n.md"))))
  (testing "a lone PDF (preview-only, no source) → NOT shown" (is (false? (facet/show-view-switch? lone-pdf "/a/x.pdf"))))
  (testing "an empty / nil group → not shown"
    (is (false? (facet/show-view-switch? [] "/a/x")))
    (is (false? (facet/show-view-switch? nil "/a/x")))))

(deftest defaults
  (testing "default-facet: PDF exists + pref :pdf + opened file isn't the pdf → the PDF preview"
    (is (= {:path "/a/p.pdf" :type :preview} (facet/default-facet group primary :pdf))))
  (testing "pref :document → the opened file's own preview"
    (is (= {:path "/a/p.org" :type :preview} (facet/default-facet group primary :document))))
  (testing "opening the PDF itself → the PDF preview (regardless of pref)"
    (is (= {:path "/a/p.pdf" :type :preview} (facet/default-facet group "/a/p.pdf" :pdf))))
  (testing "no PDF in the group → the opened file's preview"
    (is (= {:path "/a/n.md" :type :preview} (facet/default-facet lone-md "/a/n.md" :pdf)))))

(deftest validity+active
  (testing "a stored facet naming a real option is valid, and active-facet keeps it"
    (is (true? (facet/valid-facet? group primary {:path "/a/p.tex" :type :source})))
    (is (= {:path "/a/p.tex" :type :source}
           (facet/active-facet {:path "/a/p.tex" :type :source} group primary :pdf))))
  (testing "a :source facet naming the PDF is INVALID (a PDF has no source) → active-facet falls back to default"
    (is (false? (facet/valid-facet? group primary {:path "/a/p.pdf" :type :source})))
    (is (= {:path "/a/p.pdf" :type :preview}
           (facet/active-facet {:path "/a/p.pdf" :type :source} group primary :pdf))))
  (testing "a facet naming a file no longer in the group is invalid → default"
    (is (false? (facet/valid-facet? group primary {:path "/gone.md" :type :preview})))))

(deftest targets
  (testing "main-target: the MRU when still an option, else the first (default) option"
    (is (= "/a/p.tex" (facet/main-target (facet/source-options group primary) "/a/p.tex")))
    (is (= "/a/p.org" (facet/main-target (facet/source-options group primary) nil)) "no MRU → first")
    (is (= "/a/p.org" (facet/main-target (facet/source-options group primary) "/gone")) "stale MRU → first"))
  (testing "toggle-target: preview→source lands on the source main target (honoring the source MRU)"
    (is (= {:path "/a/p.org" :type :source} (facet/toggle-target :preview group primary nil)))
    (is (= {:path "/a/p.tex" :type :source} (facet/toggle-target :preview group primary {:source "/a/p.tex"}))))
  (testing "toggle-target: a PDF-only doc cannot switch to source → nil"
    (is (nil? (facet/toggle-target :preview lone-pdf "/a/x.pdf" nil)))))

(deftest view-model-shape
  (testing "the opened-org example, currently showing the PDF: Preview combo [PDF Org LaTeX], Source combo [Org LaTeX]"
    (let [vm (facet/view-model {:path "/a/p.pdf" :type :preview} group primary :pdf {:preview "/a/p.pdf"})]
      (is (true? (:show? vm)))
      (is (= :preview (:active-type vm)))
      (is (= "/a/p.pdf" (:active-path vm)))
      (is (= :combo (get-in vm [:preview :mode])))
      (is (= ["PDF" "Org" "LaTeX"] (mapv :label (get-in vm [:preview :options]))))
      (is (= [true false false] (mapv :active? (get-in vm [:preview :options]))) "the shown PDF is the active preview")
      (is (= :combo (get-in vm [:source :mode])))
      (is (= ["Org" "LaTeX"] (mapv :label (get-in vm [:source :options]))))
      (is (= [false false] (mapv :active? (get-in vm [:source :options]))) "no source is active while in preview")))
  (testing "a lone PDF: not shown; Preview plain, Source hidden"
    (let [vm (facet/view-model nil lone-pdf "/a/x.pdf" :pdf nil)]
      (is (false? (:show? vm)))
      (is (= :plain  (get-in vm [:preview :mode])))
      (is (= :hidden (get-in vm [:source :mode])))))
  (testing "a lone markdown: shown; both plain buttons"
    (let [vm (facet/view-model nil lone-md "/a/n.md" :pdf nil)]
      (is (true? (:show? vm)))
      (is (= :plain (get-in vm [:preview :mode])))
      (is (= :plain (get-in vm [:source :mode]))))))
