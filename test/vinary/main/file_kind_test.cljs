(ns vinary.main.file-kind-test
  "DOM-free unit tests for vinary.main.file-kind/kind-of — the pure extension→kind classifier the main-process
   service dispatches on. Guards the new Org (.org) classification and a few neighboring kinds so the Org arm
   can't accidentally shadow or be shadowed."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.main.file-kind :as fk]))

(def ^:private never-source (constantly false))
(def ^:private always-source (constantly true))

(deftest kind-of-org
  (testing ".org classifies as \"org\" (case-insensitive), ahead of the generic source/text fallbacks"
    (is (= "org" (fk/kind-of never-source "/notes/todo.org")))
    (is (= "org" (fk/kind-of never-source "/notes/TODO.ORG")))
    (is (= "org" (fk/kind-of never-source "README.Org")))
    ;; even when the source? predicate would claim it, the explicit .org arm wins (it precedes the source arm)
    (is (= "org" (fk/kind-of always-source "/notes/todo.org")))))

(deftest kind-of-neighbors-unaffected
  (testing "adding Org does not disturb the neighboring kinds"
    (is (= "markdown" (fk/kind-of never-source "/a/b.md")))
    (is (= "markdown" (fk/kind-of never-source "/a/b.markdown")))
    (is (= "text" (fk/kind-of never-source "/a/notes.txt")))
    (is (= "source" (fk/kind-of always-source "/a/main.rs")))
    (is (= "image" (fk/kind-of never-source "/a/pic.svg")))))
