(ns vinary.grammar-catalog-test
  "Guards the standard-repo-file highlighting layer: grammar-catalog/built-in-filetypes must resolve well-known
   filenames/patterns to a BUNDLED grammar so a Makefile / Gemfile / .gitconfig / .gitignore / .git/config lights
   up in both the GUI source view and the CLI. Classification (source-vs-text) is guarded separately in
   file-kind-test; this only checks the grammar resolution."
  (:require [cljs.test :refer [deftest is testing]]
            [vinary.grammar-catalog :as gc]))

(defn- gid [path] (:id (gc/grammar-for-path path gc/bundled-grammars {})))
(defn- lang-id [language] (:id (gc/grammar-for-language language gc/bundled-grammars)))

(deftest built-in-repo-file-grammars
  (testing "build systems"
    (is (= "make"  (gid "/p/Makefile")))
    (is (= "make"  (gid "/p/GNUmakefile")))
    (is (= "make"  (gid "/p/build.mk")))
    (is (= "cmake" (gid "/p/CMakeLists.txt"))))
  (testing "ruby-based project files → the bundled ruby grammar"
    (is (= "ruby" (gid "/p/Gemfile")))
    (is (= "ruby" (gid "/p/Rakefile")))
    (is (= "ruby" (gid "/p/widget.gemspec"))))
  (testing "CI / shell"
    (is (= "groovy" (gid "/p/Jenkinsfile")))
    (is (= "bash"   (gid "/p/.bashrc"))))
  (testing "git + editor config are INI-shaped → the bundled ini grammar (no new grammar needed)"
    (is (= "ini" (gid "/p/.gitconfig")))
    (is (= "ini" (gid "/p/.gitmodules")))
    (is (= "ini" (gid "/p/.editorconfig")))
    (is (= "ini" (gid "/home/me/repo/.git/config")) "the .git/config path pattern"))
  (testing "ignore files share gitignore syntax → the newly-bundled gitignore grammar"
    (is (= "gitignore" (gid "/p/.gitignore")))
    (is (= "gitignore" (gid "/p/.dockerignore")))
    (is (= "gitignore" (gid "/p/.prettierignore"))))
  (testing "the bundled catalog actually contains the two newly-added grammars"
    (is (some? (gc/by-id "make")))
    (is (some? (gc/by-id "gitignore")))))

(deftest nested-language-grammars
  (testing "```math fences + $…$ math + `#+BEGIN_EXPORT latex` all resolve to the bundled latex grammar"
    (is (= "latex" (lang-id "latex")))
    (is (= "latex" (lang-id "tex")))
    (is (= "latex" (lang-id "math")) "the ```math info-string / #+begin_src math alias")
    (is (= "latex" (lang-id "LaTeX")) "resolution is case-insensitive"))
  (testing "org #+begin_src / #+begin_export contents nest via their language's grammar"
    (is (= "python" (lang-id "python")))
    (is (= "python" (lang-id "py")))
    (is (= "elisp"  (lang-id "emacs-lisp")) "org #+begin_src emacs-lisp")
    (is (= "elisp"  (lang-id "el")))))
