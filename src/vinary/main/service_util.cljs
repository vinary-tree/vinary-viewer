(ns vinary.main.service-util
  "Pure, electron/DOM-free routing helpers for the main IO service (vinary.main.service), so the node
   :test build can cover the content-routing decision without loading electron/fs.")

(def parser-kinds
  "Local kinds main streams/parses into a bounded preview payload (logs, delimited tables, office docs,
   archives, and plain text sniffed for the same) — vs. reading the bytes as escaped source text."
  #{"office" "table" "log" "archive" "text"})

(defn route
  "Pure content-routing decision for a local path, from precomputed predicates. A real directory wins
   over every name-based classification: an extensionless directory name (e.g. \"publication\")
   classifies as \"text\", and routing that into the parser fs.readSyncs a directory fd → EISDIR, so a
   directory must LIST instead. Returns one of :directory :parsed :image :html :pdf :text.
     directory? — path is a real filesystem directory (already excludes archive URIs)
     archive?   — path is an archive URI / archive file main lists internally
     kind       — file-kind/kind-of classification of the path's name"
  [{:keys [directory? archive? kind]}]
  (cond
    directory?                                  :directory
    (or archive? (contains? parser-kinds kind)) :parsed
    (= "image" kind)                            :image
    (= "html" kind)                             :html
    (= "pdf" kind)                              :pdf
    :else                                       :text))
