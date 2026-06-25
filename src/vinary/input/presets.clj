(ns vinary.input.presets
  "SUPERSEDED (no longer required) — kept for reference. Preset keymaps are now inlined in
   vinary.input.keymap via shadow.resource/inline, which (unlike this macro's plain compile-time slurp)
   tracks the EDN files as compile dependencies so editing them triggers a recompile.

   Original purpose: compile-time embedding of the bundled preset keymaps. The renderer build stubs fs, so
   the EDN under resources/keymaps/*.edn is read at COMPILE time and inlined as data (resources/ is on the
   classpath)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defmacro bundled []
  {:default (edn/read-string (slurp (io/resource "keymaps/default.edn")))
   :vim     (edn/read-string (slurp (io/resource "keymaps/vim.edn")))
   :emacs   (edn/read-string (slurp (io/resource "keymaps/emacs.edn")))})
