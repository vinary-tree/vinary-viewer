(ns vinary.ui.slides-presenter-test
  "The presenter pane's text notes reach React with their content (ADR-0048 §4, Rendering): a Markdown note's
   sanitized HTML as reagent.core/unsafe-html — Reagent (1.3.0 and later) silently drops a plain {:__html s} map,
   which once left every Markdown note empty (H-REAGENT-HTML, docs/engineering/04 §1.5) — and a plain note as text,
   each in its own
   direction. DOM-free: r/as-element builds the React element; its props are what React would write. And a link in a
   note opens outside the app only for http, https and mailto, in any case (external?)."
  (:require [cljs.test :refer [deftest is]]
            [goog.object :as gobj]
            [reagent.core :as r]
            [vinary.ui.slides-presenter :as slides-presenter]))

(defn- props ^js [hiccup] (.-props ^js (r/as-element hiccup)))

(deftest a-markdown-note-reaches-react-with-its-html
  (let [memo (atom {})
        p    (props (slides-presenter/text-note memo true {:text "Say *this*" :dir "ltr" :from :annotation}))]
    (is (= "<p>Say <em>this</em></p>" (gobj/getValueByKeys p "dangerouslySetInnerHTML" "__html")))
    (is (= ["ltr" "annotation" "vv-presenter-note markdown-body"] [(.-dir p) (gobj/get p "data-from") (.-className p)]))
    (is (= {"Say *this*" "<p>Say <em>this</em></p>"} @memo) "rendered once per text: the memo keeps it")))

(deftest a-plain-note-is-text
  (let [p (props (slides-presenter/text-note (atom {}) false {:text "Say *this*" :dir "rtl" :from :sidecar}))]
    (is (= "Say *this*" (.-children p)))
    (is (nil? (gobj/get p "dangerouslySetInnerHTML")))
    (is (= ["rtl" "sidecar" "vv-presenter-note vv-presenter-note-plain"]
           [(.-dir p) (gobj/get p "data-from") (.-className p)]))))

(deftest a-note-link-opens-outside-only-for-http-https-and-mailto
  (doseq [href ["https://example.org/" "http://x" "HTTP://X" "mailto:a@b" "MailTo:someone"]]
    (is (true? (slides-presenter/external? href)) (str href " opens outside the app")))
  (doseq [href ["ftp://x" "file:///etc/passwd" "javascript:alert(1)" "#frag" "rel/path.md" "" " https://x"
                "xhttps://x" "https" nil]]
    (is (false? (slides-presenter/external? href)) (str (pr-str href) " has nowhere to go"))))
