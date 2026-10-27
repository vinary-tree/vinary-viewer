(ns vinary.app.preview-strip-test
  (:require [cljs.test :refer [deftest is]]
            [vinary.app.preview-strip :as preview]
            [vinary.app.commands :as commands]))

(def large-doc {:doc/path "/large.md" :doc/kind "markdown"
                :doc/meta {:size preview/large-document-bytes}})
(def small-doc (assoc-in large-doc [:doc/meta :size] (dec preview/large-document-bytes)))

(deftest file-browsers-cannot-show-or-toggle-previews
  (doseq [kind ["directory" "archive"]
          path ["/files" "ssh://host/files" "vv-archive://files.zip"]
          visible? [true false]]
    (let [doc {:doc/path path :doc/kind kind}
          db {:ui {:preview-strip-visible? visible?
                   :settings {:preview-strip-visible? visible?}
                   :preview-strip-overrides {"/large.md" true}}}]
      (is (false? (preview/visible-for? (:ui db) doc)))
      (is (= {:db db} (preview/toggle-result db doc)) "no preference writes or save effects")
      (is (= visible? (preview/visible-for? (:ui db) small-doc)))
      (is (true? (preview/visible-for? (:ui db) large-doc)))))
  (is (false? (commands/allowed? :view/preview-strip-toggle {:preview-strip-available? false})))
  (is (true? (commands/allowed? :view/preview-strip-toggle {:preview-strip-available? true})))
  (is (not-any? #(= :view/preview-strip-toggle (:id %))
                (commands/all-visible {:preview-strip-available? false}))))

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
