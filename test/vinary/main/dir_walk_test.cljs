(ns vinary.main.dir-walk-test
  "Tests for the synthetic (non-git) project root — vinary.main.dir-walk — against REAL temporary
   directories. This is the shipped walk, not a mirror of it: dir-walk is deliberately Electron-free so
   the node :test build can require it directly, which is the whole reason it is not inside
   vinary.main.service.

   What matters here is parity with the git path it stands in for: root-relative \"/\"-joined paths, build
   output and dotted directories excluded the way --exclude-standard excludes them, a bounded walk (an
   arbitrary directory, unlike a repo, is not self-delimiting), and a realpath'd root so one directory
   reached two ways cannot land in the sidebar twice."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [vinary.main.service-util :as service-util]
            [vinary.main.dir-walk :as dir-walk]))

(defn- tmp-dir!
  "A fresh temporary directory, realpath'd — macOS hands out /var/… symlinked to /private/var/…, which
   would otherwise make every root comparison in here a false negative."
  []
  (.realpathSync fs (.mkdtempSync fs (path/join (.tmpdir os) "vv-dir-walk-"))))

(defn- write! [root rel content]
  (let [abs (path/join root rel)]
    (.mkdirSync fs (path/dirname abs) #js {:recursive true})
    (.writeFileSync fs abs content)
    abs))

(defn- rm! [dir] (.rmSync fs dir #js {:recursive true :force true}))

(defn- with-tmp
  "Run f against a fresh temp dir, always cleaning up."
  [f]
  (let [dir (tmp-dir!)]
    (try (f dir) (finally (rm! dir)))))

(deftest walk-dir-lists-files-recursively-root-relative
  (with-tmp
    (fn [root]
      (write! root "a.md" "a")
      (write! root "sub/b.md" "b")
      (write! root "sub/deeper/c.md" "c")
      (let [files (set (dir-walk/walk-dir root))]
        (testing "every file appears, addressed relative to the root (as git ls-files does)"
          (is (= #{"a.md" "sub/b.md" "sub/deeper/c.md"} files)))
        (testing "paths are '/'-joined so the tree view's (str root \"/\" %) reconstitutes them"
          (is (every? #(not (str/includes? % "\\")) files))
          (is (not-any? #(str/starts-with? % "/") files)))))))

(deftest walk-dir-keeps-hidden-files-but-skips-hidden-and-heavy-directories
  (with-tmp
    (fn [root]
      (write! root ".gitignore" "*.log")                 ; hidden FILE — git lists it, so do we
      (write! root "keep.md" "k")
      (write! root ".git/HEAD" "ref: refs/heads/main")   ; hidden DIR
      (write! root ".venv/lib/x.py" "x")                 ; hidden DIR
      (write! root "node_modules/dep/index.js" "d")      ; heavy DIR
      (write! root "target/debug/bin" "t")               ; heavy DIR
      (write! root "distribution/notes.md" "n")          ; NOT "dist" — must survive
      (let [files (set (dir-walk/walk-dir root))]
        (testing "a hidden file is listed — parity with git ls-files --exclude-standard"
          (is (contains? files ".gitignore")))
        (testing "hidden and heavy directories contribute nothing"
          (is (= #{".gitignore" "keep.md" "distribution/notes.md"} files))
          (is (not-any? #(str/starts-with? % ".git/") files))
          (is (not-any? #(str/starts-with? % "node_modules/") files)))))))

(deftest walk-dir-is-bounded-by-depth
  (with-tmp
    (fn [root]
      (let [max-depth (:max-depth service-util/walk-limits)
            ;; one file at every level from 1 to max-depth+3, so the cut-off is observable
            levels    (range 1 (+ max-depth 4))]
        (doseq [n levels]
          (write! root (str (str/join "/" (repeat n "d")) "/f.md") "x"))
        (let [files (set (dir-walk/walk-dir root))
              depth-of #(count (str/split % #"/"))]
          (testing "files within the depth budget are listed"
            (is (contains? files "d/f.md")))
          (testing "nothing deeper than the budget survives — an arbitrary directory is not self-delimiting"
            (is (every? #(<= (depth-of %) (inc max-depth)) files))
            (is (seq files))))))))

(deftest walk-dir-is-bounded-by-total-entries
  (with-tmp
    (fn [root]
      (let [max-entries (:max-entries service-util/walk-limits)
            over        (+ max-entries 25)]
        (doseq [n (range over)] (write! root (str "f" n ".md") "x"))
        (testing "the walk stops at max-entries rather than listing everything"
          (is (= max-entries (count (dir-walk/walk-dir root)))))))))

(deftest walk-dir-resolves-symlinks-without-following-directory-cycles
  (with-tmp
    (fn [root]
      (write! root "real.md" "r")
      (write! root "sub/inner.md" "i")
      (.symlinkSync fs (path/join root "real.md") (path/join root "link.md"))
      (.symlinkSync fs (path/join root "sub")     (path/join root "link-dir"))
      (.symlinkSync fs root                       (path/join root "self"))    ; a cycle
      (let [files (set (dir-walk/walk-dir root))]
        (testing "a symlink to a file is listed like the file it points at"
          (is (contains? files "link.md")))
        (testing "a symlinked directory is never descended into, so a cycle terminates"
          (is (= #{"real.md" "sub/inner.md" "link.md"} files))
          (is (not-any? #(str/starts-with? % "self/") files))
          (is (not-any? #(str/starts-with? % "link-dir/") files)))))))

(deftest walk-dir-survives-an-unreadable-directory
  (with-tmp
    (fn [root]
      (write! root "readable.md" "r")
      (write! root "locked/hidden.md" "h")
      (let [locked (path/join root "locked")]
        (.chmodSync fs locked 0)
        (try
          (testing "an unreadable directory contributes nothing rather than aborting the walk"
            (is (contains? (set (dir-walk/walk-dir root)) "readable.md")))
          (finally (.chmodSync fs locked 0755)))))))       ; restore so cleanup can recurse

(deftest dir-tree-adopts-the-parent-of-a-file
  (with-tmp
    (fn [root]
      (let [file (write! root "notes/a.md" "a")
            tree (dir-walk/dir-tree file false)]
        (testing "the root is the file's directory, and the file is in the listing"
          (is (= (path/join root "notes") (:root tree)))
          (is (= ["a.md"] (:files tree))))
        (testing "the entry is marked synthetic — the renderer treats it as an inference, not a fact"
          (is (true? (:synthetic? tree))))))))

(deftest dir-tree-adopts-a-directory-as-its-own-root
  (with-tmp
    (fn [root]
      (let [dir (path/join root "notes")]
        (write! root "notes/a.md" "a")
        (testing "opening a DIRECTORY adds that directory, never its parent"
          ;; the bug this guards: path/dirname of /notes is /, which must never become a project
          (is (= dir (:root (dir-walk/dir-tree dir true)))))))))

(deftest dir-tree-realpaths-the-root
  (with-tmp
    (fn [root]
      (write! root "real/a.md" "a")
      (let [link (path/join root "link")]
        (.symlinkSync fs (path/join root "real") link)
        (testing "a file reached through a symlinked directory reports the RESOLVED root"
          ;; git rev-parse --show-toplevel resolves; path/dirname does not. The renderer dedupes roots by
          ;; exact string equality, so without this the same directory lands in the sidebar twice.
          (is (= (path/join root "real")
                 (:root (dir-walk/dir-tree (path/join link "a.md") false)))))))))

(deftest dir-tree-refuses-a-filesystem-root
  (testing "a file directly in / never turns the whole filesystem into a project"
    (is (nil? (dir-walk/dir-tree "/a.md" false)))
    (is (nil? (dir-walk/dir-tree "/" true)))))
