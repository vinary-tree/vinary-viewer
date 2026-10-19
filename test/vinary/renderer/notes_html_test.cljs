(ns vinary.renderer.notes-html-test
  "A deck's Markdown text notes (ADR-0048 §4, Rendering): GFM through the app's one sanitize schema, the trust boundary
   a Markdown document has (docs/security/threat-model.md) — no script, style, frame, event handler or javascript: URL
   survives, and the raw HTML GitHub allows does."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [vinary.renderer.notes-html :as notes-html]))

(defn- html [md] (notes-html/markdown->html md))

(deftest markdown-notes-render-as-gfm
  (let [out (html "Markdown with *emphasis* and a [link](https://example.org/)")]
    (is (str/includes? out "<em>emphasis</em>"))
    (is (str/includes? out "<a href=\"https://example.org/\">link</a>")))
  (testing "GFM: a table, a task list, strikethrough"
    (is (str/includes? (html "| a | b |\n|---|---|\n| 1 | 2 |") "<table>"))
    (is (str/includes? (html "- [x] done") "type=\"checkbox\""))
    (is (str/includes? (html "~~gone~~") "<del>gone</del>")))
  (testing "raw HTML that GitHub allows is kept"
    (is (str/includes? (html "a <b>bold</b> word") "<b>bold</b>"))
    (is (str/includes? (html "<kbd>s</kbd>") "<kbd>s</kbd>")))
  (testing "a note that is not a string is the empty note"
    (is (= "" (notes-html/markdown->html nil)))
    (is (= "" (notes-html/markdown->html 42)))))

(deftest nothing-active-survives
  (testing "a script element goes with its content (the schema strips it)"
    (let [out (html "before <script>alert(1)</script> after")]
      (is (not (str/includes? out "<script")))
      (is (not (str/includes? out "alert(1)")))))
  (testing "a style element goes; its text stays, as inert text — as in a Markdown document, whose schema this is"
    (let [out (html "<style>body { display: none }</style>text")]
      (is (not (str/includes? out "<style")))
      (is (str/includes? out "body { display: none }") "plain text: nothing applies it")))
  (testing "event handlers go"
    (let [out (html "<img src=\"x.png\" onerror=\"alert(1)\">")]
      (is (not (str/includes? out "onerror")))
      (is (not (str/includes? out "alert(1)"))))
    (is (not (str/includes? (html "<a href=\"https://example.org/\" onclick=\"evil()\">x</a>") "onclick"))))
  (testing "a javascript: URL goes, from a Markdown link and from raw HTML"
    (is (not (str/includes? (html "[x](javascript:alert(1))") "javascript:")))
    (is (not (str/includes? (html "<a href=\"javascript:alert(1)\">x</a>") "javascript:"))))
  (testing "frames, forms and objects go"
    (is (not (str/includes? (html "<iframe src=\"https://example.org/\"></iframe>") "<iframe")))
    (is (not (str/includes? (html "<form action=\"https://example.org/\"><input></form>") "<form")))
    (is (not (str/includes? (html "<object data=\"x.swf\"></object>") "<object"))))
  (testing "inline styles go"
    (is (not (str/includes? (html "<span style=\"position:fixed\">x</span>") "style=")))))
