(ns vinary.terminal.caps-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [vinary.terminal.caps :as caps]))

(deftest color-precedence
  (testing "an explicit --color overrides NO_COLOR and a pipe"
    (is (caps/color-enabled? {:force-color true}
                             {:no-color-env? true :tty? false :forced-graphics? false})))
  (testing "an explicit graphics protocol also implies colour"
    (is (caps/color-enabled? {}
                             {:no-color-env? true :tty? false :forced-graphics? true})))
  (testing "an explicit --no-color remains the strongest choice"
    (is (false? (caps/color-enabled? {:no-color true :force-color true}
                                     {:no-color-env? false :tty? true :forced-graphics? true}))))
  (testing "NO_COLOR disables automatic TTY colour"
    (is (false? (caps/color-enabled? {}
                                     {:no-color-env? true :tty? true :forced-graphics? false}))))
  (testing "an ordinary TTY gets automatic colour"
    (is (caps/color-enabled? {}
                             {:no-color-env? false :tty? true :forced-graphics? false}))))
