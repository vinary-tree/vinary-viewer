(ns vinary.app.preview-strip-test
  (:require [cljs.test :refer [deftest is]]
            [vinary.app.preview-strip :as preview]))

(def large-doc {:doc/path "/large.md" :doc/kind "markdown"
                :doc/meta {:size preview/large-document-bytes}})
(def small-doc (assoc-in large-doc [:doc/meta :size] (dec preview/large-document-bytes)))

(deftest large-rendered-documents-start-collapsed
  (is (false? (preview/visible-for? {:preview-strip-visible? true} large-doc)))
  (is (true? (preview/visible-for? {:preview-strip-visible? true} small-doc)))
  (is (false? (preview/visible-for? {:preview-strip-visible? false} small-doc)))
  (is (not (preview/large-document? (assoc large-doc :doc/kind "source") false)))
  (is (not (preview/large-document? (assoc large-doc :doc/kind "pdf") false)))
  (is (preview/large-document? (assoc large-doc :doc/kind "pdf") true)))

(deftest large-document-opt-in-is-local-to-the-document-and-session
  (let [db {:ui {:preview-strip-visible? true :settings {:preview-strip-visible? true}}}
        opened (preview/toggle-result db large-doc)
        closed (preview/toggle-result (:db opened) large-doc)]
    (is (nil? (:fx opened)))
    (is (true? (preview/visible-for? (:ui (:db opened)) large-doc)))
    (is (false? (preview/visible-for? (:ui (:db opened)) (assoc large-doc :doc/path "/other.md"))))
    (is (true? (preview/visible-for? (:ui (:db closed)) small-doc)))
    (is (false? (preview/visible-for? (:ui (:db closed)) large-doc)))
    (is (= (:settings (:ui db)) (get-in closed [:db :ui :settings])))))

(deftest ordinary-toggle-still-persists-the-global-default
  (let [result (preview/toggle-result {:ui {:preview-strip-visible? true :settings {}}} small-doc)]
    (is (false? (get-in result [:db :ui :preview-strip-visible?])))
    (is (= :vv/save-settings (ffirst (:fx result))))))
