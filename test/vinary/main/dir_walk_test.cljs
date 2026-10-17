(ns vinary.main.dir-walk-test
  "Tests for the synthetic (non-git) project root — vinary.main.dir-walk — against REAL temporary
   directories. This is the shipped walk, not a mirror of it: dir-walk is deliberately Electron-free so
   the node :test build can require it directly, which is the whole reason it is not inside
   vinary.main.service.

   What matters here is parity with the git path it stands in for: root-relative \"/\"-joined paths, build
   output and dotted directories excluded the way --exclude-standard excludes them, a bounded walk (an
   arbitrary directory, unlike a repo, is not self-delimiting), and a realpath'd root so one directory
   reached two ways cannot land in the sidebar twice.

   The walk is asynchronous (docs/scientific/10 §11). An ORACLE — the synchronous walk it replaced, kept here
   verbatim — pins that it returns exactly the same vector, order included, on the cases that could differ: a level
   wider than the entry cap (the async walk stops reading that level early), a deep chain, symlinks and a cycle, and
   an unreadable directory."
  (:require [cljs.test :refer [deftest is testing async]]
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
  "Run (f dir) — which returns a Promise — against a fresh temp dir, always cleaning up, then call `done`."
  [done f]
  (let [dir (tmp-dir!)]
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f dir)))
        (.catch (fn [e] (is (nil? e) (str "unexpected error: " e))))
        (.finally (fn [] (rm! dir) (done))))))

(defn- walk-dir-sync-oracle
  "The synchronous walk this namespace shipped until 2026-10-17, verbatim (bar its name): the reference the
   asynchronous walk must agree with."
  [root]
  (let [{:keys [max-depth max-entries]} service-util/walk-limits]
    (loop [level [[root ""]]
           depth 0
           files []]
      (if (or (empty? level) (> depth max-depth) (>= (count files) max-entries))
        (vec (take max-entries files))
        (let [[next-level files]
              (reduce
               (fn [acc [dir prefix]]
                 (reduce
                  (fn [[dirs found] ^js dirent]
                    (let [name   (.-name dirent)
                          rel    (str prefix name)
                          abs    (path/join dir name)
                          link?  (.isSymbolicLink dirent)
                          ^js st (when link? (try (.statSync fs abs) (catch :default _ nil)))
                          dir?   (if link? (boolean (and st (.isDirectory st))) (.isDirectory dirent))
                          file?  (if link? (boolean (and st (.isFile st)))      (.isFile dirent))]
                      (cond
                        (and dir? (not link?) (not (service-util/skip-dir? name)))
                        [(conj dirs [abs (str rel "/")]) found]

                        file? [dirs (conj found rel)]
                        :else [dirs found])))
                  acc
                  (try (.readdirSync fs dir #js {:withFileTypes true}) (catch :default _ []))))
               [[] files]
               level)]
          (recur next-level (inc depth) files))))))

(deftest walk-dir-lists-files-recursively-root-relative
  (async done
    (with-tmp done
      (fn [root]
        (write! root "a.md" "a")
        (write! root "sub/b.md" "b")
        (write! root "sub/deeper/c.md" "c")
        (-> (dir-walk/walk-dir root)
            (.then (fn [files]
                     (let [files (set files)]
                       (testing "every file appears, addressed relative to the root (as git ls-files does)"
                         (is (= #{"a.md" "sub/b.md" "sub/deeper/c.md"} files)))
                       (testing "paths are '/'-joined so the tree view's (str root \"/\" %) reconstitutes them"
                         (is (every? #(not (str/includes? % "\\")) files))
                         (is (not-any? #(str/starts-with? % "/") files)))))))))))

(deftest walk-dir-keeps-hidden-files-but-skips-hidden-and-heavy-directories
  (async done
    (with-tmp done
      (fn [root]
        (write! root ".gitignore" "*.log")                 ; hidden FILE — git lists it, so do we
        (write! root "keep.md" "k")
        (write! root ".git/HEAD" "ref: refs/heads/main")   ; hidden DIR
        (write! root ".venv/lib/x.py" "x")                 ; hidden DIR
        (write! root "node_modules/dep/index.js" "d")      ; heavy DIR
        (write! root "target/debug/bin" "t")               ; heavy DIR
        (write! root "distribution/notes.md" "n")          ; NOT "dist" — must survive
        (-> (dir-walk/walk-dir root)
            (.then (fn [files]
                     (let [files (set files)]
                       (testing "a hidden file is listed — parity with git ls-files --exclude-standard"
                         (is (contains? files ".gitignore")))
                       (testing "hidden and heavy directories contribute nothing"
                         (is (= #{".gitignore" "keep.md" "distribution/notes.md"} files))
                         (is (not-any? #(str/starts-with? % ".git/") files))
                         (is (not-any? #(str/starts-with? % "node_modules/") files)))))))))))

(deftest walk-dir-is-bounded-by-depth
  (async done
    (with-tmp done
      (fn [root]
        (let [max-depth (:max-depth service-util/walk-limits)
              ;; one file at every level from 1 to max-depth+3, so the cut-off is observable
              levels    (range 1 (+ max-depth 4))]
          (doseq [n levels]
            (write! root (str (str/join "/" (repeat n "d")) "/f.md") "x"))
          (-> (dir-walk/walk-dir root)
              (.then (fn [files]
                       (let [files    (set files)
                             depth-of #(count (str/split % #"/"))]
                         (testing "files within the depth budget are listed"
                           (is (contains? files "d/f.md")))
                         (testing "nothing deeper than the budget survives — an arbitrary directory is not self-delimiting"
                           (is (every? #(<= (depth-of %) (inc max-depth)) files))
                           (is (seq files))))))))))))

(deftest walk-dir-is-bounded-by-total-entries
  (async done
    (with-tmp done
      (fn [root]
        (let [max-entries (:max-entries service-util/walk-limits)]
          (doseq [n (range (+ max-entries 25))] (write! root (str "f" n ".md") "x"))
          (-> (dir-walk/walk-dir root)
              (.then (fn [files]
                       (testing "the walk stops at max-entries rather than listing everything"
                         (is (= max-entries (count files))))))))))))

(deftest walk-dir-resolves-symlinks-without-following-directory-cycles
  (async done
    (with-tmp done
      (fn [root]
        (write! root "real.md" "r")
        (write! root "sub/inner.md" "i")
        (.symlinkSync fs (path/join root "real.md") (path/join root "link.md"))
        (.symlinkSync fs (path/join root "sub")     (path/join root "link-dir"))
        (.symlinkSync fs root                       (path/join root "self"))    ; a cycle
        (-> (dir-walk/walk-dir root)
            (.then (fn [files]
                     (let [files (set files)]
                       (testing "a symlink to a file is listed like the file it points at"
                         (is (contains? files "link.md")))
                       (testing "a symlinked directory is never descended into, so a cycle terminates"
                         (is (= #{"real.md" "sub/inner.md" "link.md"} files))
                         (is (not-any? #(str/starts-with? % "self/") files))
                         (is (not-any? #(str/starts-with? % "link-dir/") files)))))))))))

(deftest walk-dir-survives-an-unreadable-directory
  (async done
    (with-tmp done
      (fn [root]
        (write! root "readable.md" "r")
        (write! root "locked/hidden.md" "h")
        (let [locked (path/join root "locked")]
          (.chmodSync fs locked 0)
          (-> (dir-walk/walk-dir root)
              (.then (fn [files]
                       (testing "an unreadable directory contributes nothing rather than aborting the walk"
                         (is (contains? (set files) "readable.md")))))
              (.finally (fn [] (.chmodSync fs locked 0755)))))))))      ; restore so cleanup can recurse

(deftest the-async-walk-returns-exactly-what-the-sync-walk-did
  (async done
    (with-tmp done
      (fn [root]
        (let [max-entries (:max-entries service-util/walk-limits)
              per-dir     200
              wide        (inc (quot max-entries per-dir))]      ; one more directory than the cap needs
          ;; a level WIDER than the cap: the async walk stops reading it early, the sync walk read it all
          (doseq [d (range wide) f (range per-dir)] (write! root (str "wide/w" d "/f" f ".md") "x"))
          (write! root "wide/w0/deeper/never.md" "x")           ; a next-level directory the cap leaves unread
          ;; a deep chain, symlinks, a cycle and an unreadable directory, beside it
          (write! root (str (str/join "/" (repeat 9 "c")) "/leaf.md") "x")
          (write! root "s/real.md" "r")
          (.symlinkSync fs (path/join root "s" "real.md") (path/join root "s" "link.md"))
          (.symlinkSync fs root (path/join root "s" "loop"))
          (write! root "locked/x.md" "x")
          (.chmodSync fs (path/join root "locked") 0)
          (let [cases [root (path/join root "s") (path/join root "c") (path/join root "wide")]]
            (-> (js/Promise.all (into-array (map dir-walk/walk-dir cases)))
                (.then (fn [results]
                         (doseq [[dir files] (map vector cases (array-seq results))]
                           (is (= (walk-dir-sync-oracle dir) files) (str "the same vector, in order, for " dir)))
                         (testing "the wide case really was over the cap"
                           (is (= max-entries (count (last (array-seq results))))))))
                (.finally (fn [] (.chmodSync fs (path/join root "locked") 0755))))))))))

(deftest dir-tree-adopts-the-parent-of-a-file
  (async done
    (with-tmp done
      (fn [root]
        (let [file (write! root "notes/a.md" "a")]
          (-> (dir-walk/dir-tree file false)
              (.then (fn [tree]
                       (testing "the root is the file's directory, and the file is in the listing"
                         (is (= (path/join root "notes") (:root tree)))
                         (is (= ["a.md"] (:files tree))))
                       (testing "the entry is marked synthetic — the renderer treats it as an inference, not a fact"
                         (is (true? (:synthetic? tree))))))))))))

(deftest dir-tree-adopts-a-directory-as-its-own-root
  (async done
    (with-tmp done
      (fn [root]
        (let [dir (path/join root "notes")]
          (write! root "notes/a.md" "a")
          (-> (dir-walk/dir-tree dir true)
              (.then (fn [tree]
                       (testing "opening a DIRECTORY adds that directory, never its parent"
                         ;; the bug this guards: path/dirname of /notes is /, which must never become a project
                         (is (= dir (:root tree))))))))))))

(deftest synthetic-root-realpaths-the-root
  (async done
    (with-tmp done
      (fn [root]
        (write! root "real/a.md" "a")
        (let [link (path/join root "link")]
          (.symlinkSync fs (path/join root "real") link)
          (-> (dir-walk/synthetic-root (path/join link "a.md") false)
              (.then (fn [r]
                       (testing "a file reached through a symlinked directory reports the RESOLVED root"
                         ;; git rev-parse --show-toplevel resolves; path/dirname does not. The renderer dedupes roots
                         ;; by exact string equality, so without this the same directory lands in the sidebar twice.
                         (is (= (path/join root "real") r)))))))))))

(deftest a-filesystem-root-is-refused
  (async done
    (-> (js/Promise.all #js [(dir-walk/dir-tree "/a.md" false) (dir-walk/dir-tree "/" true)
                             (dir-walk/synthetic-root "/a.md" false)])
        (.then (fn [results]
                 (testing "a file directly in / never turns the whole filesystem into a project"
                   (is (every? nil? (array-seq results))))))
        (.finally done))))
