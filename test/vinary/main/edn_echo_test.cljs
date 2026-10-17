(ns vinary.main.edn-echo-test
  "The watched EDN stores' echo rule (vinary.main.edn-echo): a window never receives its own write back, and
   everything else reaches every window — the property whose absence clobbered a Preferences font field
   (docs/scientific/10 §12)."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.main.edn-echo :as echo]))

(deftest a-window-never-gets-its-own-write-back
  (testing "the file still holds what main wrote for window 1 → window 1 is skipped"
    (let [lw (echo/record-write nil "{:font \"Noto\"}" 1)]
      (is (= {:text "{:font \"Noto\"}" :writers #{1}} lw))
      (is (= #{1} (echo/skipped lw "{:font \"Noto\"}")))))
  (testing "two windows saved identical state: both already hold it"
    (let [lw (-> nil (echo/record-write "{:a 1}" 1) (echo/record-write "{:a 1}" 2))]
      (is (= #{1 2} (echo/skipped lw "{:a 1}"))))))

(deftest everything-else-reaches-everyone
  (testing "before main has written anything (the startup push, a hand edit) → nobody is skipped"
    (is (= #{} (echo/skipped nil "{:font \"Noto\"}"))))
  (testing "a text main did not write — a hand edit after window 1's save — reaches window 1 too"
    (let [lw (echo/record-write nil "{:font \"Noto\"}" 1)]
      (is (= #{} (echo/skipped lw "{:font \"Hand Edited\"}")))))
  (testing "a later save by another window supersedes the record: window 1 must learn window 2's state"
    (let [lw (-> nil (echo/record-write "{:font \"Noto\"}" 1) (echo/record-write "{:font \"Fira\"}" 2))]
      (is (= {:text "{:font \"Fira\"}" :writers #{2}} lw))
      (is (= #{2} (echo/skipped lw "{:font \"Fira\"}")))))
  (testing "the deleted-file text a store broadcasts (\"\") is never a write main made"
    (is (= #{} (echo/skipped (echo/record-write nil "{:k 1}" 1) "")))))

(deftest a-burst-of-saves-from-one-window
  (testing "window 1 saves twice before the watcher fires: it is skipped for the LATEST text it saved"
    (let [lw (-> nil (echo/record-write "{:font \"No\"}" 1) (echo/record-write "{:font \"Noto\"}" 1))]
      (is (= #{1} (echo/skipped lw "{:font \"Noto\"}")))
      (is (= #{} (echo/skipped lw "{:font \"No\"}"))
          "once main has written the newer text, the file holds the older one only if something else wrote it"))))
