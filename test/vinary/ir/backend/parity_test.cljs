(ns vinary.ir.backend.parity-test
  "The BACKEND-PARITY GATE (ADR-0029, de-duplication goal): every document feature must render through BOTH
   back-ends off the SAME common IR — no feature may be GUI-only or terminal-only. Build representative HAST,
   convert to IR ONCE via the shared front-end (the exact path the GUI uses), then lower through
   ir.backend.html AND ir.backend.ansi and assert the feature is represented in each. A construct that
   legitimately cannot exist on one surface would go in the documented allowlist (currently empty — every
   feature renders in both). Adding a feature that lowers in only one back-end fails this gate, which is how
   we keep the two back-ends from silently diverging (the divergence the list-ordinal fix closed)."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.ir.frontend.markdown :as fe]
            [vinary.ir.backend.html :as html]
            [vinary.ir.backend.ansi :as ansi]))

(defn- el [tag props & children] #js {:type "element" :tagName tag :properties (clj->js props) :children (into-array children)})
(defn- txt [s] #js {:type "text" :value s})
(defn- root [& children] #js {:type "root" :children (into-array children)})

(defn- both
  "Convert `hast` to IR once (shared front-end), then lower through both back-ends. {:html str :ansi str}."
  [hast]
  (let [ir (fe/hast->ir hast)]
    {:html (html/lower ir) :ansi (ansi/render ir {:color? false :width 60})}))

;; ─────────────── the list-ordinal unification (the one real GUI/terminal duplication closed) ───────────────
(deftest ordered-list-start-and-value-in-both-backends
  (testing "<ol start=3> — HTML carries start=\"3\" (the browser numbers), ANSI numbers the text; ONE IR source"
    (let [{:keys [html ansi]} (both (root (el "ol" {:start 3} (el "li" {} (txt "a")) (el "li" {} (txt "b")))))]
      (is (re-find #"<ol[^>]*start=\"3\"" html))
      (is (str/includes? ansi "3. a"))
      (is (str/includes? ansi "4. b"))))
  (testing "a per-item <li value=7> overrides AND resets the ordinal in both back-ends (the Org [@n] seam)"
    (let [{:keys [html ansi]} (both (root (el "ol" {} (el "li" {:value 7} (txt "x")) (el "li" {} (txt "y")))))]
      (is (re-find #"<li[^>]*value=\"7\"" html))
      (is (str/includes? ansi "7. x"))
      (is (str/includes? ansi "8. y"))))
  (testing "a plain ordered list is unchanged: starts at 1, +1 per item"
    (is (str/includes? (:ansi (both (root (el "ol" {} (el "li" {} (txt "a")) (el "li" {} (txt "b")))))) "1. a"))))

;; ─────────────── the parity matrix: each feature MUST appear in both back-end outputs ───────────────
(def ^:private features
  [{:name "heading"     :hast (root (el "h2" {:id "h"} (txt "Heading")))
    :html #"<h2[^>]*>Heading</h2>"          :ansi "Heading"}
   {:name "emphasis"    :hast (root (el "p" {} (el "strong" {} (txt "bold")) (txt " ") (el "em" {} (txt "it"))))
    :html #"<strong>bold</strong>"          :ansi "bold"}
   {:name "code-block"  :hast (root (el "pre" {} (el "code" {:className ["language-clojure"]} (txt "(+ 1 2)"))))
    :html #"<code[^>]*language-clojure"     :ansi "(+ 1 2)"}
   {:name "link"        :hast (root (el "p" {} (el "a" {:href "https://x"} (txt "lnk"))))
    :html #"<a[^>]*href=\"https://x\""      :ansi "lnk"}
   {:name "table-cell"  :hast (root (el "table" {} (el "tbody" {} (el "tr" {} (el "td" {} (txt "cel"))))))
    :html #"<td>cel</td>"                   :ansi "cel"}
   {:name "task-list"   :hast (root (el "ul" {:className ["contains-task-list"]}
                                      (el "li" {:className ["task-list-item"]}
                                        (el "input" {:type "checkbox" :checked true}) (txt " done"))))
    :html #"type=\"checkbox\""              :ansi "☑"}
   {:name "blockquote"  :hast (root (el "blockquote" {} (el "p" {} (txt "quoted"))))
    :html #"<blockquote>"                   :ansi "quoted"}
   {:name "thematic"    :hast (root (el "hr" {}))
    :html #"<hr"                            :ansi "─"}])

(deftest every-feature-renders-in-both-backends
  (doseq [{:keys [name hast] hre :html astr :ansi} features]
    (testing (str name " renders through both back-ends off one IR")
      (let [{:keys [html ansi]} (both hast)]
        (is (re-find hre html)          (str name ": absent from the HTML back-end — feature is GUI-only"))
        (is (str/includes? ansi astr)   (str name ": absent from the ANSI back-end — feature is terminal-only"))))))
