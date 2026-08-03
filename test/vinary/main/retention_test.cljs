(ns vinary.main.retention-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.main.retention :as retention]))

(deftest retention-is-owned-per-window
  (let [s1 (retention/sync-owner {} 1 :window-a #{"/a.md" "/shared.md"})
        s2 (retention/sync-owner s1 2 :window-b #{"/b.md" "/shared.md"})]
    (testing "a later window sync does not replace the first window's paths"
      (is (= #{"/a.md" "/b.md" "/shared.md"} (retention/all-paths s2)))
      (is (= #{1} (retention/owner-ids-for s2 "/a.md")))
      (is (= #{1 2} (retention/owner-ids-for s2 "/shared.md"))))
    (testing "dropping one owner preserves shared resources for the other"
      (let [s3 (retention/drop-owner s2 1)]
        (is (false? (retention/retained? s3 "/a.md")))
        (is (true? (retention/retained? s3 "/shared.md")))
        (is (= #{2} (retention/owner-ids-for s3 "/shared.md")))))))

(deftest compatibility-close-is-sender-scoped
  (let [s (-> {}
              (retention/sync-owner 1 :window-a #{"/shared.md"})
              (retention/sync-owner 2 :window-b #{"/shared.md"}))
        closed (retention/drop-path s 1 "/shared.md")]
    (is (= #{} (retention/paths-for closed 1)))
    (is (= #{"/shared.md"} (retention/paths-for closed 2)))
    (is (retention/retained? closed "/shared.md"))))
