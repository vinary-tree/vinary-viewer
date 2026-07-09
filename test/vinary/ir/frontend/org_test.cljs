(ns vinary.ir.frontend.org-test
  "Async round-trip test for Org (.org) support: a sample document through the shared org-pipeline (uniorg →
   hast → the same app suffix as Markdown) then hast->IR must yield the expected IR node kinds and a slugged
   heading TOC, and its serialized HTML must keep the per-language `language-*` code classes (so nested
   #+begin_src blocks highlight). Headless — drives the org-pipeline directly (not renderer.markdown, which pulls
   in CodeMirror), mirroring how ir.parity-test exercises the Markdown pipeline."
  (:require [cljs.test :refer [deftest is testing async]]
            ["rehype-stringify$default" :as rehype-stringify]
            [vinary.renderer.markdown-pipeline :as pipeline]
            [vinary.ir.frontend.markdown :as ir-md]
            [vinary.ir.capability.toc :as ir-toc]
            [vinary.ir.node :as node]))

(def ^:private sample
  (str "#+TITLE: Demo Document\n"
       "* First Heading\n"
       "Some *bold* text and a [[https://example.com][link]].\n"
       "** Nested Heading\n"
       "#+begin_src python\n"
       "def add(a, b):\n"
       "    return a + b\n"
       "#+end_src\n"
       "#+begin_src clojure\n"
       "(defn add [a b] (+ a b))\n"
       "#+end_src\n"
       "#+begin_src emacs-lisp\n"
       "(message \"hi\")\n"
       "#+end_src\n"
       "| a | b |\n"
       "|---+---|\n"
       "| 1 | 2 |\n"
       "[[./pic.png]]\n"))

(defn- render-org
  "org-pipeline → capture HAST + stringify → callback (ir, metadata, html)."
  [text cb]
  (let [metadata (atom {:toc [] :assets #{}})
        captured (atom nil)]
    (-> (pipeline/org-pipeline metadata nil nil)
        (.use (pipeline/capture-hast captured))
        (.use rehype-stringify)
        (.process text)
        (.then (fn [file] (cb (ir-md/hast->ir @captured) @metadata (str file))))
        (.catch (fn [e] (cb nil {:error (.-message e)} nil))))))

(deftest org->ir-structure
  (async done
    (render-org sample
      (fn [ir meta _html]
        (is (some? ir) (str "org-pipeline produced an IR" (when (:error meta) (str " — error: " (:error meta)))))
        (when ir
          (let [kinds (set (map :kind (node/preorder ir)))]
            (is (contains? kinds :heading)    "org headings become :heading nodes")
            (is (contains? kinds :code-block) "#+begin_src becomes :code-block nodes")
            (is (contains? kinds :table)      "org tables become :table nodes")
            (is (or (contains? kinds :image) (contains? kinds :figure)) "org images become :image/:figure nodes")))
        (done)))))

(deftest org->toc
  (async done
    (render-org sample
      (fn [ir _meta _html]
        (when ir
          (let [toc (ir-toc/toc-of ir)]
            (is (<= 2 (count toc)) "the heading TOC lists the org headings")
            (is (every? (comp seq :id) toc) "every TOC entry has a slug id")
            (is (some #(= 1 (:level %)) toc) "the top-level heading is level 1")
            (is (some #(= 2 (:level %)) toc) "the nested heading is level 2")))
        (done)))))

(deftest org-nested-src-block-language-classes
  (async done
    (render-org sample
      (fn [_ir _meta html]
        (is (some? html) "org-pipeline serialized HTML")
        (when html
          ;; the per-language code classes survive the GitHub sanitizer, so both rehype-highlight and the
          ;; tree-sitter post-pass can highlight the nested src blocks (emacs-lisp via the language alias).
          (is (re-find #"language-python" html)     "python src-block keeps its language class")
          (is (re-find #"language-clojure" html)    "clojure src-block keeps its language class")
          (is (re-find #"language-emacs-lisp" html) "emacs-lisp src-block keeps its language class"))
        (done)))))
