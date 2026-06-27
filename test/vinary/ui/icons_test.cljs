(ns vinary.ui.icons-test
  "Regression coverage for the Font Awesome icon registry: every app icon keyword
   resolves to a non-blank fa-solid class, `icon` renders an aria-hidden <i> with
   the mapped (and extra-appended) class, and `file-class` maps representative
   extensions to the expected glyphs with a generic fallback."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vinary.ui.icons :as icons]))

(deftest every-icon-key-resolves
  (testing "every app icon keyword maps to a non-blank fa-solid class"
    (doseq [[k cls] icons/classes]
      (is (string? cls) (str "class for " k " is a string"))
      (is (not (str/blank? cls)) (str "class for " k " is non-blank"))
      (is (str/starts-with? cls "fa-solid fa-") (str "class for " k " is fa-solid")))))

(deftest every-file-class-resolves
  (testing "every extension maps to a non-blank fa-solid class"
    (doseq [[ext cls] icons/ext->class]
      (is (str/starts-with? cls "fa-solid fa-") (str "class for ." ext)))))

(deftest icon-renders-hiccup
  (testing "icon returns an aria-hidden <i> carrying the mapped class"
    (let [[tag attrs] (icons/icon :back)]
      (is (= :i tag))
      (is (= "fa-solid fa-arrow-left" (:class attrs)))
      (is (true? (:aria-hidden attrs)))))
  (testing "an extra :class is appended to the FA class, not replaced"
    (let [[_ attrs] (icons/icon :undo {:class "vv-ico-gap"})]
      (is (= "fa-solid fa-rotate-left vv-ico-gap" (:class attrs)))))
  (testing "other attrs are preserved"
    (let [[_ attrs] (icons/icon :close {:title "Close"})]
      (is (= "Close" (:title attrs)))
      (is (= "fa-solid fa-xmark" (:class attrs))))))

(deftest file-class-by-extension
  (testing "known extensions map to specific glyphs (case-insensitive)"
    (is (= "fa-solid fa-file-lines"  (icons/file-class "README.md")))
    (is (= "fa-solid fa-file-code"   (icons/file-class "core.cljs")))
    (is (= "fa-solid fa-file-code"   (icons/file-class "main.rho")))
    (is (= "fa-solid fa-file-image"  (icons/file-class "logo.PNG")))
    (is (= "fa-solid fa-file-image"  (icons/file-class "diagram.svg")))
    (is (= "fa-solid fa-file-pdf"    (icons/file-class "spec.pdf")))
    (is (= "fa-solid fa-file-csv"    (icons/file-class "data.csv")))
    (is (= "fa-solid fa-file-zipper" (icons/file-class "bundle.tar.gz"))))
  (testing "unknown / extensionless / dotfile names fall back to the generic file glyph"
    (is (= "fa-solid fa-file" (icons/file-class "Makefile")))
    (is (= "fa-solid fa-file" (icons/file-class "noext")))
    (is (= "fa-solid fa-file" (icons/file-class "weird.zzz")))
    (is (= "fa-solid fa-file" (icons/file-class ".gitignore")))))

(deftest folder-icon-renders-toggle-pair
  (testing "folder-icon emits a fragment of closed + open glyphs for the CSS toggle"
    (let [[frag closed open] (icons/folder-icon)]
      (is (= :<> frag))
      (is (= "fa-solid fa-folder vv-folder-closed" (:class (second closed))))
      (is (= "fa-solid fa-folder-open vv-folder-open" (:class (second open)))))))
