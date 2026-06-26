(ns vinary.main.file-kind
  "Pure file-kind classification shared by the main-process service and tests."
  (:require [clojure.string :as str]))

(def ^:private markdown-exts #{".md" ".markdown" ".mdx"})
(def ^:private image-exts #{".png" ".jpg" ".jpeg" ".gif" ".svg" ".webp" ".bmp" ".ico" ".avif"})
(def ^:private source-diagram-exts
  #{".d2" ".puml" ".plantuml" ".pu" ".iuml" ".wsd" ".mmd" ".mermaid" ".dot" ".gv" ".graphviz"})

(defn extension [file-path]
  (some-> (re-find #"(\.[^./\\]+)$" (str file-path))
          second
          str/lower-case))

(defn kind-of
  "Classify file-path. source? is injected so this namespace stays pure and testable."
  [source? file-path]
  (let [ext (extension file-path)]
    (cond
      (contains? markdown-exts ext) "markdown"
      (contains? image-exts ext) "image"
      (= ".pdf" ext) "pdf"
      (or (contains? source-diagram-exts ext)
          (source? file-path)) "source"
      :else "text")))
