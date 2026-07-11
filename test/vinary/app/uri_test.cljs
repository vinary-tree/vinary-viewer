(ns vinary.app.uri-test
  "Pure unit tests for the remote (ssh://sftp://) URI helpers in vinary.app.uri — the scheme predicates and
   the authority-preserving path arithmetic (file-path / normalize / display / basename / dirname / segments)
   that keep a user@host:port authority intact so breadcrumbs, tab labels, and parent-navigation work on a
   remote URI exactly as they do on a local path."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.app.uri :as uri]))

(deftest remote-scheme-predicates
  (testing "ssh? / sftp? / remote? recognize both schemes, case-insensitively, and nothing else"
    (is (true?  (uri/ssh?  "ssh://h/a")))
    (is (true?  (uri/ssh?  "SSH://h/a")))
    (is (false? (uri/ssh?  "sftp://h/a")))
    (is (true?  (uri/sftp? "sftp://h/a")))
    (is (true?  (uri/remote? "ssh://h/a")))
    (is (true?  (uri/remote? "sftp://user@h:22/a")))
    (is (false? (uri/remote? "/a/b")))
    (is (false? (uri/remote? "https://h/a")))
    (is (false? (uri/remote? "vv-archive://open?chain=[]")))
    (is (false? (uri/remote? nil)))))

(deftest remote-parts-splits-authority-from-path
  (testing "the authority ([^/]*) is never split on ':' or '@'; the path always starts with '/'"
    (is (= ["ssh://user@host:22" "/a/b"] (uri/remote-parts "ssh://user@host:22/a/b")))
    (is (= ["ssh://host" "/a"]           (uri/remote-parts "ssh://host/a")))
    (is (= ["ssh://host" "/"]            (uri/remote-parts "ssh://host"))     "no path → root")
    (is (= ["ssh://host" "/"]            (uri/remote-parts "ssh://host/"))    "trailing slash → root path")
    (is (= ["sftp://user@host" "/x/y"]   (uri/remote-parts "sftp://user@host/x/y")))
    (is (= ["ssh://[::1]:2222" "/etc"]   (uri/remote-parts "ssh://[::1]:2222/etc")) "IPv6 host in brackets")))

(deftest file-path-normalize-display-preserve-remote
  (testing "file-path preserves a remote URI verbatim (it is main-openable, like an archive URI)"
    (is (= "ssh://user@h:22/a/b" (uri/file-path "ssh://user@h:22/a/b")))
    (is (= "sftp://h/a"          (uri/file-path "sftp://h/a"))))
  (testing "normalize keeps a remote URI verbatim (address-bar / CLI input)"
    (is (= "ssh://h/a" (uri/normalize "  ssh://h/a  ")))
    (is (= "sftp://h/a" (uri/normalize "sftp://h/a"))))
  (testing "display shows the remote address as-is (never file://-prefixed)"
    (is (= "ssh://user@h:22/a" (uri/display "ssh://user@h:22/a")))))

(deftest remote-basename
  (testing "basename is the last path segment; the remote root shows as '/'"
    (is (= "b.md" (uri/basename "ssh://user@h:22/a/b.md")))
    (is (= "logs" (uri/basename "ssh://h/var/logs/")) "trailing slash ignored (a directory)")
    (is (= "/"    (uri/basename "ssh://h/"))          "remote root")
    (is (= "/"    (uri/basename "ssh://h")))))

(deftest remote-dirname
  (testing "dirname keeps the authority on the parent; the remote root has no parent"
    (is (= "ssh://user@h:22/a" (uri/dirname "ssh://user@h:22/a/b")))
    (is (= "ssh://h/"          (uri/dirname "ssh://h/a"))          "parent of a top-level entry is the root")
    (is (= "ssh://h/a"         (uri/dirname "ssh://h/a/b/"))       "trailing slash → parent of the directory")
    (is (nil?                  (uri/dirname "ssh://h/"))           "the remote root has no parent")
    (is (nil?                  (uri/dirname "ssh://h")))))

(deftest remote-segments-and-ancestors
  (testing "segments: the authority is the root crumb, then one navigable crumb per path segment"
    (is (= [{:name "ssh://u@h:22" :path "ssh://u@h:22/"}
            {:name "a"            :path "ssh://u@h:22/a"}
            {:name "b"            :path "ssh://u@h:22/a/b"}]
           (uri/segments "ssh://u@h:22/a/b")))
    (is (= [{:name "ssh://h" :path "ssh://h/"}]
           (uri/segments "ssh://h/"))                              "the remote root is a single crumb"))
  (testing "ancestor-paths is (map :path segments) — every ancestor is a navigable remote URI"
    (is (= ["ssh://u@h/" "ssh://u@h/a" "ssh://u@h/a/b"]
           (uri/ancestor-paths "ssh://u@h/a/b")))))
