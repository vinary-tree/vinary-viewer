(ns vinary.renderer.org-handlers-test
  "Phase 3 (ADR-0029): the Org functionality gaps closed at the uniorg-rehype seam. Drives the REAL shared
   org-pipeline (the exact one the GUI and terminal render through) on minimal Org sources and asserts the
   produced HTML, so a gap regressing is caught. Node-only (unified/rehype are pure)."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            ["rehype-stringify$default" :as rehype-stringify]
            [vinary.renderer.markdown-pipeline :as pipeline]))

(defn- org-html
  "Run the shared org-pipeline on `src`, stringify, return Promise<html>."
  [src]
  (-> ^js (pipeline/org-pipeline (atom {:toc [] :assets #{}}) "." nil)
      (.use rehype-stringify)
      (.process src)
      (.then (fn [vf] (str vf)))))

(defn- check [src assert-fn done]
  (-> (org-html src)
      (.then (fn [html] (assert-fn html) (done)))
      (.catch (fn [e] (is false (str "org-pipeline error: " (.-message e))) (done)))))

(deftest gap-e-drawer-renders-contents
  (testing "an ordinary :DRAWER: renders its contents (uniorg-rehype dropped them)"
    (async done (check ":MYDRAWER:\ncontent here\n:END:\n"
                       (fn [h] (is (str/includes? h "content here"))) done))))

(deftest gap-g-list-counter
  (testing "an Org `[@5]` list counter becomes <li value=5> (numbered identically in both back-ends)"
    (async done (check "1. [@5] fifth\n2. sixth\n"
                       (fn [h] (is (re-find #"<li[^>]*value=\"5\"" h))) done))))

(deftest gap-f-custom-todo-keyword
  (testing "a custom #+TODO: sequence styles NEXT (active→todo) and CANCELLED (after |→done) in headings"
    (async done (check "#+TODO: TODO NEXT | DONE CANCELLED\n* NEXT do it\n* CANCELLED nope\n"
                       (fn [h]
                         (is (re-find #"<span class=\"todo\">NEXT</span>" h) "NEXT → span.todo")
                         (is (re-find #"<span class=\"done\">CANCELLED</span>" h) "CANCELLED → span.done")) done))))

(deftest gap-h-inlinetask-not-a-heading
  (testing "an inlinetask (level-15 headline) renders its content as a block, not an <h7>..<h15>"
    (async done (check "*************** TODO an inline task\n"
                       (fn [h]
                         (is (str/includes? h "an inline task"))
                         (is (not (re-find #"<h(?:1[0-5]|[7-9])[ >]" h)) "no invalid <h7..15>")) done))))
