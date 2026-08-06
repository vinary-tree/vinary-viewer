(ns vinary.main.diff-source
  "Local filesystem resolution for paths referenced by a diff.

   This is deliberately Electron-free: `vinary.main.service` owns the IPC and piped-document base-directory
   policy, while this namespace owns the actual ancestor walk and optional UTF-8 read. Keeping the filesystem
   operation here lets the node test build exercise the exact production implementation against real files."
  (:require [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]))

(defn resolve-from
  "Locate the regular file named by `rel`, first relative to `start-dir` and then relative to each ancestor.
   An absolute reference is checked exactly once. Returns its absolute path, or nil when the input is not a
   usable path or no candidate exists. Relative traversal terminates naturally when `dirname` reaches the
   filesystem root, so deeply nested patch locations remain resolvable without an arbitrary depth cutoff."
  [start-dir rel]
  (when (and (string? start-dir) (not (str/blank? start-dir))
             (string? rel) (not (str/blank? rel)))
    (if (.isAbsolute path rel)
      (let [candidate (path/normalize rel)]
        (when (try (.isFile (.statSync fs candidate)) (catch :default _ false))
          candidate))
      (loop [dir (path/resolve start-dir)]
        (let [candidate (path/resolve (path/join dir rel))]
          (if (try (.isFile (.statSync fs candidate)) (catch :default _ false))
            candidate
            (let [parent (path/dirname dir)]
              (when (not= parent dir)
                (recur parent)))))))))

(defn load-local
  "Resolve `rels` from `start-dir`.

   With `:include-paths?`, return `{rel {:path absolute :content optional-utf8}}`; `:include-content?` controls
   the optional read. Without `:include-paths?`, retain the original `{rel utf8-content}` contract and read
   every resolved file. Missing files and legacy reads that fail are omitted. A structured entry keeps its
   resolved path when an optional content read fails, because navigation and enrichment are independent."
  ([start-dir rels] (load-local start-dir rels nil))
  ([start-dir rels {:keys [include-paths? include-content?]}]
   (let [structured? (boolean include-paths?)
         read?       (if structured? (boolean include-content?) true)]
     (reduce
      (fn [acc rel]
        (if-let [resolved (resolve-from start-dir rel)]
          (let [content (when read?
                          (try (.readFileSync fs resolved "utf8")
                               (catch :default _ nil)))]
            (cond
              structured? (assoc acc rel (cond-> {:path resolved}
                                           (some? content) (assoc :content content)))
              (some? content) (assoc acc rel content)
              :else acc))
          acc))
      {}
      (or rels [])))))
