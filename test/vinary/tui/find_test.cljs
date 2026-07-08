(ns vinary.tui.find-test
  "In-buffer search: matching on ANSI-stripped visible text (so colour escapes don't corrupt offsets), reverse-video
   highlighting that preserves existing SGR, and the match cursor (next/prev cycling)."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.tui.find :as find]))

(def ^:private esc (str (char 27)))
(defn- sgr [n s] (str esc "[" n "m" s esc "[0m"))

(deftest search-plain
  (is (= [{:line 0 :col 0 :len 3} {:line 1 :col 4 :len 3}]
         (find/search ["foo bar" "baz foo"] "foo")))
  (testing "case-insensitive"
    (is (= [{:line 0 :col 0 :len 3}] (find/search ["FOO bar"] "foo"))))
  (testing "blank query → no matches"
    (is (= [] (find/search ["anything"] "")))))

(deftest search-strips-ansi
  (testing "matching runs on the VISIBLE text — colour escapes don't shift the column"
    (let [line (str (sgr 31 "foo") " bar")]                 ; visible: "foo bar"
      (is (= [{:line 0 :col 4 :len 3}] (find/search [line] "bar"))))))

(deftest highlight-preserves-ansi
  (testing "the match is wrapped in reverse-video; existing colour + visible text survive"
    (let [line (str (sgr 31 "foo") " bar")
          out  (find/highlight line [{:col 4 :len 3}])]
      (is (str/includes? out (str esc "[7m")) "reverse-video on")
      (is (str/includes? out (str esc "[27m")) "reverse-video off")
      (is (str/includes? out (str esc "[31m")) "original colour preserved")
      (is (= "foo bar" (find/strip out)) "visible text unchanged")))
  (testing "no spans → line unchanged"
    (is (= "plain" (find/highlight "plain" []))))
  (testing "a match spanning a style boundary keeps reverse-video across the interior RESET"
    (let [line (str (sgr 31 "foo") (sgr 34 "bar"))          ; visible "foobar"; a RESET sits between foo and bar
          out  (find/highlight line [{:col 2 :len 3}])]      ; match "oob" straddles the RESET
      ;; ESC[7m must be re-asserted after the interior ESC[0m, else the tail of the match loses its highlight
      (is (>= (count (re-seq #"\[7m" out)) 2) "reverse-video re-asserted after the interior RESET")
      (is (= "foobar" (find/strip out)) "visible text intact"))))

(deftest match-cursor
  (let [f (find/start ["a x a" "x a x"] "a")]                ; matches at (0,0),(0,4),(1,2)
    (is (= 3 (count (:matches f))))
    (is (= {:line 0 :col 0 :len 1} (find/current f)))
    (is (= {:line 0 :col 4 :len 1} (find/current (find/next-match f))))
    (is (= {:line 1 :col 2 :len 1} (find/current (-> f find/next-match find/next-match))))
    (testing "cycles"
      (is (= {:line 0 :col 0 :len 1} (find/current (-> f find/next-match find/next-match find/next-match))))
      (is (= {:line 1 :col 2 :len 1} (find/current (find/prev-match f))))))
  (testing "line-spans selects this line's matches"
    (is (= [{:col 0 :len 1} {:col 4 :len 1}] (find/line-spans (find/start ["a x a"] "a") 0)))))
