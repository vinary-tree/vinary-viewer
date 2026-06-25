(ns vinary.main.diagram
  "Diagram rendering in the main process: a source file (d2 / PlantUML / Mermaid / Graphviz) is shelled
   out to its CLI and converted to an SVG string, which is sent to the renderer as the document's HTML
   (so the existing innerHTML content path + live-refresh handle display/refresh for free)."
  (:require ["child_process" :as cp]
            ["path" :as path]
            ["os" :as os]
            ["fs" :as fs]
            [clojure.string :as str]))

(defn- run [cmd args opts]
  (cp/execFileSync cmd (clj->js args)
                   (clj->js (merge {:encoding "utf8" :maxBuffer (* 48 1024 1024)} opts))))

(defn- tmp-svg [p]
  (path/join (os/tmpdir) (str "vv-diagram-" (.-name (path/parse p)) ".svg")))

(defn render
  "Render a diagram source file to an SVG string (by extension). Throws on an unsupported type or a
   renderer error (the caller turns that into a vv:error)."
  [^String p]
  (case (str/lower-case (path/extname p))
    ".d2"                       (let [out (tmp-svg p)] (run "d2" [p out] {}) (.readFileSync fs out "utf8"))
    (".puml" ".plantuml" ".pu" ".iuml" ".wsd")
                                (run "plantuml" ["-tsvg" "-pipe"] {:input (.readFileSync fs p "utf8")})
    (".mmd" ".mermaid")         (let [out (tmp-svg p)] (run "mmdc" ["-i" p "-o" out "-q"] {}) (.readFileSync fs out "utf8"))
    (".dot" ".gv" ".graphviz")  (run "dot" ["-Tsvg" p] {})
    (throw (js/Error. (str "unsupported diagram type: " (path/extname p))))))
