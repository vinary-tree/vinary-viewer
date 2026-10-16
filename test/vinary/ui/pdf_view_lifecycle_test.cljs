(ns vinary.ui.pdf-view-lifecycle-test
  "The pdf-view lifecycle decision table (vinary.ui.pdf-view-lifecycle; ADR-0045 §Live refresh): a pending refresh
   never defers the view state, and a version that raced the first mount is refreshed to when that mount lands."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.ui.pdf-view-lifecycle :as life]))

(def ^:private talk "/d/talk.pdf")

(deftest a-mounted-view-always-applies-the-view-state
  (testing "a render with the shown stamp: only the view state"
    (is (= #{:update} (life/update-actions {:path talk :stamp 5 :shown [talk 5] :mounted? true :preparing? true}))))
  (testing "a render that ALSO carries a newer stamp refreshes AND applies the view state — the old component did
            one or the other, so a change rendered while a refresh was pending was lost (the refresh re-renders
            nothing when it lands)"
    (is (= #{:refresh :update}
           (life/update-actions {:path talk :stamp 6 :shown [talk 5] :mounted? true :preparing? true}))))
  (testing "a stamp whose bytes already failed to open is not retried — the view state still applies"
    (is (= #{:update}
           (life/update-actions {:path talk :stamp 6 :shown [talk 5] :mounted? true :preparing? true :failed 6})))))

(deftest before-the-first-mount-lands
  (testing "the first preparation is in flight: nothing — it mounts the newest cached version when it lands"
    (is (= #{} (life/update-actions {:path talk :stamp 6 :shown nil :mounted? false :preparing? true}))))
  (testing "nothing is preparing (the first preparation failed): a NEW stamp mounts; the failed one is not retried"
    (is (= #{:mount} (life/update-actions {:path talk :stamp 7 :mounted? false :preparing? false :failed 6})))
    (is (= #{} (life/update-actions {:path talk :stamp 6 :mounted? false :preparing? false :failed 6}))))
  (testing "nothing ever mounted, nothing failed: mount"
    (is (= #{:mount} (life/update-actions {:path talk :stamp 1 :mounted? false :preparing? false})))))

(deftest another-document-in-the-slot-remounts
  (is (= #{:remount} (life/update-actions {:path "/d/other.pdf" :stamp 1 :shown [talk 5] :mounted? true
                                           :preparing? true}))
      "never one file's pages under another's name — and nothing else happens on that render"))

(deftest a-version-that-raced-the-first-mount
  (testing "a newer version cached while the first mount was being prepared is refreshed to when it lands"
    (is (true? (life/refresh-after-mount? {:mounted 5 :newest 6}))))
  (testing "the mounted version is the newest: nothing"
    (is (false? (life/refresh-after-mount? {:mounted 5 :newest 5}))))
  (testing "nothing cached any more (evicted): nothing"
    (is (false? (life/refresh-after-mount? {:mounted 5 :newest nil}))))
  (testing "a newest stamp that already failed to open is not retried"
    (is (false? (life/refresh-after-mount? {:mounted 5 :newest 6 :failed 6})))))
