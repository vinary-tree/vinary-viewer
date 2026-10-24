(ns vinary.ui.document-preview-test
  (:require [cljs.test :refer [deftest is]]
            [vinary.ui.document-preview :as preview]
            [vinary.ui.content-route :as route]))

(deftest proportional-scale-without-enlarging
  (is (= 0.2 (preview/scale-for 1000 200)))
  (is (= 1 (preview/scale-for 100 200)))
  (is (= 1 (preview/scale-for 0 0))))

(deftest navigation-centers-and-clamps
  (is (= 4500 (preview/centered-top 5000 1000 10000)))
  (is (= 0 (preview/centered-top 0 1000 10000)))
  (is (= 9000 (preview/centered-top 10000 1000 10000)))
  (is (= 0 (preview/centered-top 200 1000 500))))

(deftest reveal-only-moves-when-needed
  (is (= 100 (preview/reveal-top 100 500 200 100)))
  (is (= 100 (preview/reveal-top 120 500 100 600)))
  (is (= 50 (preview/reveal-top 100 500 50 100)))
  (is (= 200 (preview/reveal-top 100 500 600 100))))

(deftest rendered-preview-follows-the-selected-route
  (doseq [r [:html-doc :office :diff :diff-split :diagram :pdf-reflow :pdf-reflow-stream]]
    (is (route/document-preview? r "pdf")))
  (doseq [kind ["markdown" "org" "latex"]]
    (is (route/document-preview? :stream kind)))
  (doseq [[r kind] [[:source "markdown"] [:http "markdown"] [:html "html"] [:pdf "pdf"]
                   [:stream "log"] [:table "table"] [:directory "directory"]]]
    (is (not (route/document-preview? r kind)))))
