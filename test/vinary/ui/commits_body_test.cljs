(ns vinary.ui.commits-body-test
  "An expanded commit's message body (vinary.ui.commits/commit-body) reaches React WITH its HTML. Reagent (1.3.0 and
   later) keeps :dangerouslySetInnerHTML only as a tagged reagent.core/unsafe-html value and silently deletes any
   other — a plain {:__html s} map included — so the body rendered empty (H-REAGENT-HTML, docs/engineering/04 §1.5,
   the negative control G-A1). DOM-free:
   r/as-element builds the React element; its props are what React would write."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [reagent.core :as r]
            [vinary.ui.commits :as commits]))

(defn- props ^js [hiccup] (.-props ^js (r/as-element hiccup)))

(defn- inner-html [^js p] (gobj/getValueByKeys p "dangerouslySetInnerHTML" "__html"))

(deftest a-rendered-body-reaches-react-with-its-html
  (let [p (props (commits/commit-body "<p>Fixes <em>it</em></p>" "Fixes *it*"))]
    (is (= "<p>Fixes <em>it</em></p>" (inner-html p))
        "the sanitized HTML is the element's inner HTML")
    (is (= "vv-commits-body markdown-body" (.-className p)))))

(deftest a-failed-or-pending-body
  (testing "rendering failed: the raw message, as text"
    (let [p (props (commits/commit-body false "Fixes *it*"))]
      (is (= "Fixes *it*" (.-children p)))
      (is (nil? (gobj/get p "dangerouslySetInnerHTML")))))
  (is (= "…" (.-children (props (commits/commit-body :vinary.ui.commits/pending "Fixes *it*")))) "still rendering"))
