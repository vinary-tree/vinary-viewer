(ns vinary.main.web-policy-test
  "The web-view popup policy (ADR-0044): which popup requests become app tabs, and whether the tab opens
   in the background. Pure, so the decision is pinned here rather than only through a live web view."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.main.web-policy :as web-policy]))

(deftest disposition-open-mode
  (testing "Chromium reports a middle-click / Ctrl+click popup as background-tab → a background tab"
    (is (= :background (web-policy/open-mode "background-tab"))))
  (testing "every other disposition opens focused"
    (doseq [d ["foreground-tab" "new-window" "default" "save-to-disk" "other" "" nil]]
      (is (= :focused (web-policy/open-mode d)) (str "disposition " (pr-str d))))))

(deftest tab-worthy-targets
  (testing "http(s) popup targets become app tabs (scheme match is case-insensitive)"
    (is (web-policy/tab-worthy-url? "http://example.com"))
    (is (web-policy/tab-worthy-url? "https://example.com/a?b=c#d"))
    (is (web-policy/tab-worthy-url? "HTTPS://EXAMPLE.COM")))
  (testing "everything else is denied outright — no tab, and (since ADR-0044) no native window either"
    (doseq [u ["about:blank" "javascript:alert(1)" "data:text/html,<b>x</b>" "file:///etc/passwd"
               "ssh://host/x" "vv-archive://open" "chrome://settings" "" "   " nil 42]]
      (is (false? (web-policy/tab-worthy-url? u)) (str "denied: " (pr-str u)))))
  (testing "a scheme merely CONTAINING http is not an http url (the match is anchored)"
    (is (false? (web-policy/tab-worthy-url? "x-http://evil")))
    (is (false? (web-policy/tab-worthy-url? " http://leading-space")))))
