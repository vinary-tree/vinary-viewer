(ns vinary.ir.backend.ansi-test
  "Golden-ish tests for the pure IR → ANSI terminal backend: builds representative HAST, converts HAST→IR
   (the same front-end the GUI uses), lowers to terminal text, and asserts the layout — wrapping to width,
   list markers, box-drawing tables, code gutters, blockquote gutters, rules — plus ANSI styling when :color?.
   Deterministic given width, so :color? false gives stable plain-text golden output."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.ir.node :as node]
            [vinary.ir.frontend.markdown :as fe]
            [vinary.ir.frontend.log :as log-fe]
            [vinary.ir.backend.ansi :as ansi]))

(defn- el [tag props & children] #js {:type "element" :tagName tag :properties (clj->js props) :children (into-array children)})
(defn- txt [s] #js {:type "text" :value s})
(defn- root [& children] #js {:type "root" :children (into-array children)})

(defn- plain [hast & [opts]] (ansi/render (fe/hast->ir hast) (merge {:color? false :width 40} opts)))
(defn- lines [hast & [opts]] (clojure.string/split-lines (plain hast opts)))

(deftest display-width-strips-ansi
  (testing "display-width ignores SGR escapes and OSC-8, counts wide glyphs as 2"
    (is (= 2 (ansi/display-width "[1;31mhi[0m")))
    (is (= 5 (ansi/display-width "hello")))
    (is (= 4 (ansi/display-width "日本")))            ; two wide glyphs
    (is (= 0 (ansi/display-width "[0m")))))

(deftest heading-and-paragraph
  (testing "a heading renders its text; a long paragraph word-wraps to the width"
    (is (= "Intro" (plain (root (el "h1" {} (txt "Intro"))))))
    (let [ls (lines (root (el "p" {} (txt "one two three four five six seven eight nine ten eleven"))))]
      (is (> (count ls) 1) "the long paragraph wrapped")
      (is (every? #(<= (ansi/display-width %) 40) ls) "no line exceeds the width")
      (is (= "one two three four five six seven eight" (first ls))))))

(deftest lists
  (testing "unordered → bullets, ordered → numbers, with continuation indent"
    (is (= ["• one" "• two"] (lines (root (el "ul" {} (el "li" {} (txt "one")) (el "li" {} (txt "two")))))))
    (is (= ["1. a" "2. b"] (lines (root (el "ol" {} (el "li" {} (txt "a")) (el "li" {} (txt "b")))))))))

;; GFM's task-list shape — <li class="task-list-item"><input type="checkbox" [checked]> — is what BOTH the
;; Markdown and the Org front-ends emit, so the terminal must read its state from the <input>. Without this the
;; terminal printed "• a" for a checked and an unchecked item alike, dropping the one bit a TODO list carries.
(defn- task-item [checked? & children]
  (apply el "li" {:className ["task-list-item"]}
         (el "input" (cond-> {:type "checkbox" :disabled true} checked? (assoc :checked true)))
         (txt " ") children))

(deftest task-lists
  (testing "an unordered task list swaps the bullet for a ballot box carrying the checkbox state"
    (is (= ["☐ todo" "☑ done"]
           (lines (root (el "ul" {:className ["contains-task-list"]}
                            (task-item false (txt "todo"))
                            (task-item true  (txt "done"))))))))
  (testing "an ordered task list keeps its ordinal and gains the box"
    (is (= ["1. ☐ a" "2. ☑ b"]
           (lines (root (el "ol" {} (task-item false (txt "a")) (task-item true (txt "b"))))))))
  (testing "a plain item in a task list still renders as a bullet"
    (is (= ["☐ todo" "• plain"]
           (lines (root (el "ul" {} (task-item false (txt "todo")) (el "li" {} (txt "plain")))))))))

(deftest blockquote-gutter
  (testing "a blockquote gets a │ gutter"
    (is (= ["│ quoted"] (lines (root (el "blockquote" {} (el "p" {} (txt "quoted")))))))))

(deftest code-block-gutter
  (testing "a fenced code block renders each line with a ▏ gutter"
    (let [ls (lines (root (el "pre" {} (el "code" {:className ["language-clojure"]} (txt "(+ 1 2)\n(* 3 4)")))))]
      (is (= 2 (count ls)))
      (is (every? #(clojure.string/includes? % "▏") ls)))))

(deftest thematic-break
  (testing "hr renders a horizontal rule of ─ across the width"
    (is (re-matches #"─{40}" (plain (root (el "hr" {})))))))

(deftest table-box-drawing
  (testing "a table renders unicode box-drawing borders + rows"
    (let [ls (lines (root (el "table" {} (el "tbody" {}
                              (el "tr" {} (el "td" {} (txt "a")) (el "td" {} (txt "b")))
                              (el "tr" {} (el "td" {} (txt "1")) (el "td" {} (txt "2")))))) )]
      (is (clojure.string/starts-with? (first ls) "┌"))
      (is (clojure.string/starts-with? (last ls) "└"))
      (is (some #(clojure.string/includes? % "│ a") ls))
      (is (some #(clojure.string/includes? % "├") ls) "header separator present"))))

(deftest links-plain-and-hyperlink
  (testing "a link's text shows plainly; with :hyperlinks? it wraps in an OSC-8 escape"
    (is (= "click" (plain (root (el "p" {} (el "a" {:href "http://x"} (txt "click")))))))
    (let [out (ansi/render (fe/hast->ir (root (el "p" {} (el "a" {:href "http://x"} (txt "click")))))
                           {:color? true :hyperlinks? true :width 40})]
      (is (re-find #"\]8;;http://x" out) "OSC-8 hyperlink opens with the href")
      (is (clojure.string/includes? out "click") "the link text is present")
      )))

(deftest color-emits-sgr
  (testing "with :color? a heading emits an SGR escape; without it, none"
    (is (clojure.string/includes? (ansi/render (fe/hast->ir (root (el "h1" {} (txt "H")))) {:color? true}) "["))
    (is (not (clojure.string/includes? (ansi/render (fe/hast->ir (root (el "h1" {} (txt "H")))) {:color? false}) "[")))))

(deftest log-records-colored
  (testing "a log :record with an ERROR line gets a red level colour under :color?"
    (let [rec (node/node :record [(node/node :line [(node/leaf :text "2026 ERROR boom")] {})] {:role :log-record})
          out (ansi/render rec {:color? true :width 40})]
      (is (clojure.string/includes? out "boom"))
      (is (clojure.string/includes? out "[") "styled"))))

(deftest log-lines-single-spaced
  (testing "a :document of :line leaves (log front-end) renders single-spaced under :block-sep \"\\n\""
    (is (= "line one\nline two\nline three"
           (ansi/render (log-fe/text->ir "line one\nline two\nline three")
                        {:color? false :width 40 :block-sep "\n"})))))

(deftest emphasis-and-code
  (testing "strong/em/inline-code fold styling; plain output keeps the text"
    (is (= "bold italic code" (plain (root (el "p" {}
                                              (el "strong" {} (txt "bold")) (txt " ")
                                              (el "em" {} (txt "italic")) (txt " ")
                                              (el "code" {} (txt "code")))))))))

(deftest render-lines-and-anchors
  (testing "render-lines: :lines equals the flat render split, and anchors map heading ids → line index (the TUI's
            TOC jump source — no fragile text matching)"
    (let [ir   (fe/hast->ir (root (el "h1" {:id "title"} (txt "Title"))
                                  (el "p" {} (txt "Body text here"))
                                  (el "h2" {:id "sec"} (txt "Section"))))
          opts {:color? false :width 40}
          {:keys [lines anchors]} (ansi/render-lines ir opts)]
      (is (= (clojure.string/split (ansi/render ir opts) #"\n" -1) lines) ":lines is the flat render, split")
      (is (= 0 (get anchors "title")) "the h1 anchor is line 0")
      (is (clojure.string/includes? (nth lines (get anchors "sec")) "Section") "the h2 anchor points at its line"))))
