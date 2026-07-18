(ns vinary.renderer.org-handlers-test
  "Phase 3 (ADR-0029): the Org functionality gaps closed at the uniorg-rehype seam. Drives the REAL shared
   org-pipeline (the exact one the GUI and terminal render through) on minimal Org sources and asserts the
   produced HTML, so a gap regressing is caught. Node-only (unified/rehype are pure)."
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            ["rehype-stringify$default" :as rehype-stringify]
            ;; the org-pipeline now reaches unified-latex/uniorg through the runtime registry (they code-split out
            ;; of the renderer boot bundle); populate it eagerly for Node, exactly as cli.core/tui.core do at
            ;; startup — otherwise the pipeline throws "renderer not loaded" and uniorg isn't even bundled.
            [vinary.renderer.heavy-node :as heavy-node]
            [vinary.renderer.markdown-pipeline :as pipeline]))

(heavy-node/install!)   ; wire unified-latex/uniorg into the shared registry before any test runs (Node: eager, no shadow.lazy)

(defn- org-html
  "Run the shared org-pipeline on `src`, stringify, return Promise<html>."
  [src]
  (-> ^js (pipeline/org-pipeline (atom {:toc [] :assets #{}}) "." nil)
      (.use rehype-stringify)
      (.process src)
      (.then (fn [vf] (str vf)))))

(defn- check [src assert-fn done]
  (-> (org-html src)
      (.then (fn [html] (assert-fn html) (done)))
      (.catch (fn [e] (is false (str "org-pipeline error: " (.-message e))) (done)))))

(deftest gap-e-drawer-renders-contents
  (testing "an ordinary :DRAWER: renders its contents (uniorg-rehype dropped them)"
    (async done (check ":MYDRAWER:\ncontent here\n:END:\n"
                       (fn [h] (is (str/includes? h "content here"))) done))))

(deftest gap-g-list-counter
  (testing "an Org `[@5]` list counter becomes <li value=5> (numbered identically in both back-ends)"
    (async done (check "1. [@5] fifth\n2. sixth\n"
                       (fn [h] (is (re-find #"<li[^>]*value=\"5\"" h))) done))))

(deftest gap-f-custom-todo-keyword
  (testing "a custom #+TODO: sequence styles NEXT (active→todo) and CANCELLED (after |→done) in headings"
    (async done (check "#+TODO: TODO NEXT | DONE CANCELLED\n* NEXT do it\n* CANCELLED nope\n"
                       (fn [h]
                         (is (re-find #"<span class=\"todo\">NEXT</span>" h) "NEXT → span.todo")
                         (is (re-find #"<span class=\"done\">CANCELLED</span>" h) "CANCELLED → span.done")) done))))

(deftest gap-h-inlinetask-not-a-heading
  (testing "an inlinetask (level-15 headline) renders its content as a block, not an <h7>..<h15>"
    (async done (check "*************** TODO an inline task\n"
                       (fn [h]
                         (is (str/includes? h "an inline task"))
                         (is (not (re-find #"<h(?:1[0-5]|[7-9])[ >]" h)) "no invalid <h7..15>")) done))))

;; ─────────────── the source preprocessor (gaps a–d): uniorg implements none, so we expand first ───────────────
(deftest gap-a-macro-expansion
  (testing "built-in {{{title}}} and user #+MACRO: expand in the pre-pass; unknown macros drop"
    (let [out (pipeline/org-preprocess "#+TITLE: Foo\n#+MACRO: greet Hi $1!\n{{{title}}} / {{{greet(World)}}} / {{{nope}}}")]
      (is (str/includes? out "Foo / Hi World! / "))
      (is (not (str/includes? out "{{{"))))))

(deftest gap-b-inline-src
  (testing "src_lang{code} → inline code (uniorg would mangle the underscore into a subscript)"
    (is (= "before ~print(1)~ after" (pipeline/org-preprocess "before src_python{print(1)} after")))))

(deftest gap-c-babel-call
  (testing "call_x(args) → inline code"
    (is (= "run ~call_fn(1,2)~ now" (pipeline/org-preprocess "run call_fn(1,2) now")))))

(deftest gap-d-targets
  (testing "targets <<t>> and radio targets <<<r>>> become visible text (no leaked <<…>>)"
    (is (= "see t and a radio here" (pipeline/org-preprocess "see <<t>> and a <<<radio>>> here")))))

(deftest gap-i-source-positions
  (testing "trackPosition + the patched uniorg-rehype h() stamp data-vv-source-* on Org preview nodes (the
            fine-grained source⇄preview jump Markdown has). Requires patches/uniorg-rehype+2.2.0.patch applied."
    (async done (check "* A Heading\n\nsome paragraph text\n"
                       (fn [h] (is (re-find #"data-vv-source-start-line" h))) done))))

(deftest gap-preprocessor-skips-verbatim
  (testing "a macro inside a #+begin_src verbatim block is NOT expanded"
    (let [out (pipeline/org-preprocess "#+begin_src text\n{{{title}}}\n#+end_src\nout {{{title}}}")]
      (is (re-find #"#\+begin_src text\n\{\{\{title\}\}\}\n#\+end_src" out) "src body untouched")
      (is (not (re-find #"out \{\{\{title\}\}\}" out)) "outside the block IS expanded"))))
