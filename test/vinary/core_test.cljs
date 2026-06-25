(ns vinary.core-test
  "Unit tests for the pure, DOM-free logic: key normalization, keymap merge, the keybinding resolver,
   command gating, and DataScript helpers. Run with: npx shadow-cljs compile test && node dist/test/test.js"
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [datascript.core :as d]
            [vinary.input.keys :as keys]
            [vinary.input.keymap :as keymap]
            [vinary.input.resolver :as resolver]
            [vinary.app.commands :as commands]
            [vinary.app.ds :as ds]))

(defn- ev [m] (clj->js m))

(deftest key-normalization
  (testing "modifier folding + named keys"
    (is (= "C-f"   (keys/event->chord (ev {:key "f" :ctrlKey true}) false)))
    (is (= "C-f"   (keys/event->chord (ev {:key "f" :metaKey true}) true)) "⌘ folds to C- on mac")
    (is (= "M-left" (keys/event->chord (ev {:key "ArrowLeft" :altKey true}) false)))
    (is (= "G"     (keys/event->chord (ev {:key "G" :shiftKey true}) false)) "Shift folds into a printable")
    (is (= "S-tab" (keys/event->chord (ev {:key "Tab" :shiftKey true}) false)) "Shift kept for named keys")
    (is (= "space" (keys/event->chord (ev {:key " "}) false)))
    (is (nil? (keys/event->chord (ev {:key "Control"}) false)) "modifier-only → nil"))
  (testing "bare-printable?"
    (is (true? (keys/bare-printable? "g")))
    (is (false? (keys/bare-printable? "C-g")))
    (is (false? (keys/bare-printable? "space")))))

(deftest keymap-merge
  (testing "install-user! {:extends :vim} keeps the vim modes (deep-merge nil bug regression)"
    (keymap/install-user! {:extends :vim})
    (let [modes (keymap/modes)]
      (is (= :nav/scroll-down (get-in modes [:normal "j"])))
      (is (map? (get-in modes [:normal "g"])) "g is a prefix")
      (is (= :normal (keymap/initial-mode))))
    (testing "user override + :unbind"
      (keymap/install-user! {:extends :vim :keymaps {:normal {"H" :history/back "gt-not-real" :unbind}}})
      (is (= :history/back (get-in (keymap/modes) [:normal "H"])))))
  (testing "default keymap reproduces the baseline"
    (keymap/install! :default)
    (is (= :search/start (get-in (keymap/modes) [:all "C-f"])))
    (is (= :history/back (get-in (keymap/modes) [:all "M-left"])))))

(deftest resolver-step
  (let [modes {:normal {"j" :nav/scroll-down "g" {"g" :nav/scroll-top}} :all {"escape" :input/escape}}]
    (testing "leaf → dispatch"
      (is (= {:action :dispatch :command :nav/scroll-down}
             (resolver/step modes :normal [] "j" {:in-input? false}))))
    (testing "prefix → :prefix then leaf"
      (is (= :prefix (:action (resolver/step modes :normal [] "g" {:in-input? false}))))
      (is (= :nav/scroll-top (:command (resolver/step modes :normal ["g"] "g" {:in-input? false})))))
    (testing "miss at start in normal mode consumes; in insert passes"
      (is (= :consume (:action (resolver/step modes :normal [] "z" {:in-input? false}))))
      (is (= :pass    (:action (resolver/step modes :insert [] "z" {:in-input? false})))))
    (testing "miss mid-sequence retries"
      (is (= :retry (:action (resolver/step modes :normal ["g"] "z" {:in-input? false})))))))

(deftest command-gating
  (testing ":when predicates gate run/all-visible"
    (is (true?  (commands/allowed? :tab/next {:tabs [1 2]})))
    (is (false? (commands/allowed? :tab/next {:tabs []})) ":has-tabs fails with no tabs")
    (is (false? (commands/allowed? :history/back {:can-back? false})))
    (is (true?  (commands/allowed? :search/start {}))  "no :when ⇒ always"))
  (testing "all-visible filters by :when"
    (let [ids (set (map :id (commands/all-visible {:tabs [] :can-back? false})))]
      (is (not (contains? ids :tab/next)))
      (is (contains? ids :search/start)))))

(deftest datascript-helpers
  (let [conn (d/create-conn {:doc/path {:db/unique :db.unique/identity}})]
    (d/transact! conn [{:doc/path "/a" :doc/order 0 :doc/open? true :doc/kind "markdown"}
                       {:doc/path "/b" :doc/order 1 :doc/open? true :doc/kind "text"}])
    (is (= 2 (ds/next-order @conn)) "next-order = max+1")
    (is (= ["/a" "/b"] (mapv :path (ds/open-docs @conn))) "ordered by :doc/order")
    (is (some? (ds/eid-for-path @conn "/a")))
    (is (= "markdown" (ds/doc-attr @conn "/a" :doc/kind)))))

(defn -main [& _] (run-tests))
