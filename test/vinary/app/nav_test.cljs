(ns vinary.app.nav-test
  "Pure unit tests for the Document↔PDF representation helpers in vinary.app.nav — the per-tab representation
   transforms and the effective-representation resolution the :ui/active-representation sub shares."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.nav :as nav]))

(def ^:private db1
  {:ui {:active-tab 1
        :tabs [{:id 1 :uri "/a/paper.tex" :hist {:stack [{:uri "/a/paper.tex" :scroll 0}] :idx 0}}
               {:id 2 :uri "/a/notes.md"  :hist {:stack [{:uri "/a/notes.md" :scroll 0}] :idx 0}}]}})

(deftest representation-transforms
  (testing "a fresh tab has no explicit representation (nil = follow the preference default)"
    (is (nil? (nav/representation db1))))
  (testing "set-representation stores the choice on the active tab only"
    (let [db' (nav/set-representation db1 :pdf)]
      (is (= :pdf (nav/representation db')))
      (is (nil? (:representation (nth (get-in db' [:ui :tabs]) 1))) "the other tab is untouched")))
  (testing "set-representation by explicit id targets that tab"
    (let [db' (nav/set-representation db1 2 :document)]
      (is (= :document (:representation (nth (get-in db' [:ui :tabs]) 1))))
      (is (nil? (nav/representation db')) "the active tab (id 1) is untouched"))))

(deftest effective-representation-resolution
  (testing "no sibling PDF → always :document, regardless of tab choice or preference"
    (is (= :document (nav/effective-representation nil false :pdf)))
    (is (= :document (nav/effective-representation :pdf false :pdf))))
  (testing "sibling exists, tab has not chosen → follow the preference default"
    (is (= :pdf      (nav/effective-representation nil true :pdf)))
    (is (= :document (nav/effective-representation nil true :document)))
    (is (= :pdf      (nav/effective-representation nil true nil))  "an unset preference defaults to :pdf"))
  (testing "sibling exists, tab chose explicitly → the tab choice wins over the preference"
    (is (= :document (nav/effective-representation :document true :pdf)))
    (is (= :pdf      (nav/effective-representation :pdf true :document)))))
