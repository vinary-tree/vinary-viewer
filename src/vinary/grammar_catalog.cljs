(ns vinary.grammar-catalog
  "Compile-time catalog of bundled tree-sitter grammars.

   The generated EDN lives in resources/grammars/catalog.edn so both the Electron main process and
   renderer resolve source extensions from the same registry."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [shadow.resource :as rc]))

(def bundled-grammars
  (->> (reader/read-string (rc/inline "grammars/catalog.edn"))
       (mapv (fn [g]
               (update g :extensions (fn [exts] (mapv str/lower-case exts)))))))

(def bundled-source-exts
  (into #{} (mapcat :extensions) bundled-grammars))
