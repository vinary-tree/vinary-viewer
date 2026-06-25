(ns vinary.input.presets
  "Compile-time embedding of the bundled preset keymaps. The renderer build stubs fs, so the EDN under
   resources/keymaps/*.edn is read at COMPILE time and inlined as data (resources/ is on the classpath)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defmacro bundled []
  {:default (edn/read-string (slurp (io/resource "keymaps/default.edn")))
   :vim     (edn/read-string (slurp (io/resource "keymaps/vim.edn")))
   :emacs   (edn/read-string (slurp (io/resource "keymaps/emacs.edn")))})
