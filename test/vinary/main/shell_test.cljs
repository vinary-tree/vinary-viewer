(ns vinary.main.shell-test
  "Unit test for shell/seed-dir — the native Open-dialog folder resolver. Given the LOCAL path the renderer
   supplies (the active file/dir, or the most-recent file), it must resolve to a real directory: an existing
   directory → itself; an existing file → its parent directory; and anything missing / blank / nil → nil, so
   the handler falls back to the OS home dir. Exercises the real statSync against temp fixtures (the :node-test
   build runs on Node, where fs/os/path are the genuine builtins)."
  (:require [cljs.test :refer [deftest is testing]]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [vinary.main.shell :as shell]))

(deftest seed-dir-resolves-to-a-folder
  (let [dir  (.mkdtempSync fs (path/join (.tmpdir os) "vv-seed-"))
        file (path/join dir "note.md")]
    (.writeFileSync fs file "hello")
    (try
      (testing "an existing directory → itself"
        (is (= dir (shell/seed-dir dir))))
      (testing "an existing file → its parent directory"
        (is (= dir (shell/seed-dir file))))
      (testing "a path that does not exist → nil (handler falls back to the OS home dir)"
        (is (nil? (shell/seed-dir (path/join dir "nope" "gone.md")))))
      (testing "nil / empty seed → nil"
        (is (nil? (shell/seed-dir nil)))
        (is (nil? (shell/seed-dir ""))))
      (finally
        (.rmSync fs dir #js {:recursive true :force true})))))

(deftest seeds-dir-walks-to-first-existing-folder
  (let [dir  (.mkdtempSync fs (path/join (.tmpdir os) "vv-seeds-"))
        file (path/join dir "a.md")]
    (.writeFileSync fs file "x")
    (try
      (testing "the first candidate that resolves to a folder wins (active file → its parent)"
        (is (= dir (shell/seeds->dir #js [file (path/join dir "recent.md")]))))
      (testing "a since-deleted higher-priority path is walked past to the next candidate"
        (is (= dir (shell/seeds->dir #js [(path/join dir "gone.md") file]))))
      (testing "a directory candidate resolves to itself"
        (is (= dir (shell/seeds->dir #js [dir]))))
      (testing "no candidate resolves → nil (handler falls back to the OS home dir)"
        (is (nil? (shell/seeds->dir #js [(path/join dir "nope1") (path/join dir "nope2" "x.md")]))))
      (testing "nil / empty candidate list → nil"
        (is (nil? (shell/seeds->dir nil)))
        (is (nil? (shell/seeds->dir #js []))))
      (finally
        (.rmSync fs dir #js {:recursive true :force true})))))
