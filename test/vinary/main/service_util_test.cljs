(ns vinary.main.service-util-test
  "Projecting one repository listing onto expanded scopes (vinary.main.service-util): prefix-files, maximal-scopes and
   the one-pass bucket-files — which must equal prefix-files scope by scope, checked on hand cases and on seeded
   random trees."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.main.service-util :as su]))

(deftest prefix-files-honours-segment-boundaries
  (let [files ["a.md" "sub/b.md" "sub/deep/c.md" "submarine/d.md"]]
    (is (= files (su/prefix-files "" files)) "the root scope is everything")
    (is (= ["sub/b.md" "sub/deep/c.md"] (su/prefix-files "sub" files)) "not submarine/")
    (is (= ["sub/deep/c.md"] (su/prefix-files "sub/deep" files)))
    (is (= [] (su/prefix-files "a.md" files)) "a FILE is not a directory scope")))

(deftest maximal-scopes-keeps-only-the-outermost
  (is (= #{""} (su/maximal-scopes ["" "a" "a/b" "c"])) "the root contains everything")
  (is (= #{"a" "c"} (su/maximal-scopes ["a" "a/b" "a/b/c" "c"])))
  (is (= #{"a" "ab"} (su/maximal-scopes ["a" "ab" "a/x"])) "ab is not inside a")
  (is (= #{"a/b" "a/c"} (su/maximal-scopes ["a/b" "a/c" "a/b/d"])) "siblings both survive")
  (is (= #{} (su/maximal-scopes [])))
  (is (= #{"x"} (su/maximal-scopes ["x" "x"])) "duplicates collapse"))

(deftest bucket-files-equals-prefix-files-scope-by-scope
  (let [files  ["a.md" "sub/b.md" "sub/deep/c.md" "submarine/d.md" "sub/deep/e/f.md"]
        scopes ["" "sub" "sub/deep" "submarine" "nope"]]
    (is (= (into {} (map (juxt identity #(su/prefix-files % files))) scopes)
           (su/bucket-files scopes files)))
    (testing "every bucket keeps the listing's order"
      (is (= ["sub/b.md" "sub/deep/c.md" "sub/deep/e/f.md"] (get (su/bucket-files ["sub"] files) "sub"))))))

(defn- lcg [seed]
  (let [s (atom (bit-and seed 0x7fffffff))]
    (fn [n] (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) (mod @s n))))

(defn- random-tree
  "Seeded random root-relative files over a small alphabet of directory names — shared prefixes (d, dd) on purpose."
  [rnd n]
  (let [names ["d" "dd" "e" "x"]]
    (vec (for [i (range n)]
           (str (str/join "/" (repeatedly (rnd 4) #(nth names (rnd 4)))) (when (pos? (rnd 2)) "/") "f" i ".md")))))

(deftest bucket-files-equals-prefix-files-on-random-trees
  (doseq [seed (range 1 31)]
    (let [rnd    (lcg seed)
          files  (mapv #(str/replace % #"^/" "") (random-tree rnd 60))
          dirs   (into #{""} (mapcat (fn [f] (let [parts (butlast (str/split f #"/"))]
                                               (map #(str/join "/" (take % parts)) (range 1 (inc (count parts)))))))
                       files)
          scopes (filterv (fn [_] (pos? (rnd 2))) (sort dirs))]
      (is (= (into {} (map (juxt identity #(su/prefix-files % files))) scopes)
             (su/bucket-files scopes files))
          (str "seed " seed))
      (is (every? (fn [s] (not-any? #(and (not= % s) (or (= % "") (str/starts-with? s (str % "/"))))
                                    (su/maximal-scopes scopes)))
                  (su/maximal-scopes scopes))
          (str "no maximal scope lies inside another, seed " seed))
      (is (every? (fn [s] (some #(or (= % s) (= % "") (str/starts-with? s (str % "/"))) (su/maximal-scopes scopes)))
                  scopes)
          (str "every scope lies inside a maximal one, seed " seed)))))
