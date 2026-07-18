(ns vinary.renderer.text-edit-test
  "Guards `text-edit/editable-target?` — the predicate that decides which right-clicks open the themed
   Cut/Copy/Paste/Select-All menu: text-like <input>s and <textarea>s only, never password/checkbox/etc.,
   non-inputs, or nil (contentEditable like the CodeMirror source view keeps its own menu)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.renderer.text-edit :as text-edit]))

(defn- el
  "A minimal stand-in for a DOM element: {tagName, type?}. A real <input> always reports a concrete `.type`
   (\"text\" when the attribute is unset), so tests pass a concrete type."
  [tag type]
  (if type #js {:tagName tag :type type} #js {:tagName tag}))

(deftest editable-target-accepts-text-fields
  (testing "text-like <input> and <textarea> are editable targets"
    (is (text-edit/editable-target? (el "TEXTAREA" nil)))
    (doseq [t ["text" "search" "url" "email" "tel" "number"]]
      (is (text-edit/editable-target? (el "INPUT" t)) (str "INPUT type=" t)))))

(deftest editable-target-rejects-non-text
  (testing "password / non-text inputs, non-inputs, and nil are rejected"
    (doseq [t ["password" "checkbox" "radio" "file" "range" "color" "button" "submit"]]
      (is (not (text-edit/editable-target? (el "INPUT" t))) (str "INPUT type=" t)))
    (is (not (text-edit/editable-target? (el "DIV" nil))))
    (is (not (text-edit/editable-target? (el "BUTTON" nil))))
    (is (not (text-edit/editable-target? nil)))))
