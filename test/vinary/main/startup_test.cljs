(ns vinary.main.startup-test
  "The electron-free main-process startup helpers: --help/--version request detection (so `electron . --help`
   prints usage and exits before opening a window) and the usage/version text."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.main.startup :as startup]))

;; argv shape mirrors `electron <app> <args…>` (index 2 onward is the user's args), same as doc-uris
(defn- argv [& args] (into ["/node/electron" "/app"] args))

(deftest help-request-detection
  (testing "-h/--help → :help; -V/--version → :version; documents → nil"
    (is (= :help    (startup/help-request? (argv "--help"))))
    (is (= :help    (startup/help-request? (argv "-h"))))
    (is (= :version (startup/help-request? (argv "--version"))))
    (is (= :version (startup/help-request? (argv "-V"))))
    (is (nil?       (startup/help-request? (argv "README.md" "a.pdf"))))
    (is (nil?       (startup/help-request? (argv))))
    (testing "a flag anywhere among the args still triggers (e.g. vv --gui --help)"
      (is (= :help (startup/help-request? (argv "--gui" "--help"))))
      (is (= :help (startup/help-request? (argv "foo.md" "--help")))))))

(deftest usage-and-version-text
  (testing "usage lists all three modes"
    (is (str/includes? startup/usage-text "vv [--gui]"))
    (is (str/includes? startup/usage-text "vv --cli"))
    (is (str/includes? startup/usage-text "vv --tui")))
  (testing "version-text prefixes the app name"
    (is (= "vinary-viewer 0.3.0-dev" (startup/version-text "0.3.0-dev")))))

(deftest instance-id-extraction
  (testing "reads the --vv-instance-id=<id> launch key from anywhere in argv, else nil"
    (is (= "abc-123" (startup/instance-id (argv "--vv-instance-id=abc-123"))))
    (is (= "abc-123" (startup/instance-id (argv "README.md" "--vv-instance-id=abc-123" "b.pdf")))
        "position-independent — it can sit among the file args")
    (is (nil? (startup/instance-id (argv "README.md"))) "absent → nil")
    (is (nil? (startup/instance-id (argv))) "no args → nil")
    (is (nil? (startup/instance-id (argv "--vv-instance-id="))) "empty value → nil, not the empty string"))
  (testing "the flag is NOT treated as a document (doc-uris drops it), so the two agree"
    (let [av (argv "--vv-instance-id=xyz" "/abs/README.md")]
      (is (= "xyz" (startup/instance-id av)))
      (is (= ["/abs/README.md"] (startup/doc-uris av identity)) "the id flag never becomes a tab"))))
