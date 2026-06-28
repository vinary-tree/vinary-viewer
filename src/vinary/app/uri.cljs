(ns vinary.app.uri
  "URI helpers. A tab's :uri is the canonical address it shows — an absolute local file path
   (\"/home/u/README.md\") or an http(s) URL. Local paths are displayed file://-prefixed in the URI bar."
  (:require [clojure.string :as str]))

(defn http? [uri] (boolean (and uri (re-find #"(?i)^https?://" uri))))

(defn file-path
  "The local filesystem path for a uri: a leading file:// stripped; http(s) → nil; else the uri as-is."
  [uri]
  (cond (nil? uri)                        nil
        (http? uri)                       nil
        (str/starts-with? uri "file://")  (subs uri 7)
        :else                             uri))

(defn normalize
  "Canonical tab :uri from user/tree input: blank → nil; http(s) kept; file:// stripped to a path; else
   the text as-is (an absolute path)."
  [text]
  (let [t (str/trim (or text ""))]
    (cond (str/blank? t)                  nil
          (http? t)                       t
          (str/starts-with? t "file://")  (subs t 7)
          :else                           t)))

(defn display
  "How a uri appears in the URI bar: http(s) as-is; a local path shown file://-prefixed."
  [uri]
  (cond (nil? uri) "" (http? uri) uri :else (str "file://" uri)))

(defn basename
  "Short tab label: a file's basename, or for http the last path segment (falling back to the host)."
  [uri]
  (cond
    (nil? uri)  "New Tab"
    (http? uri) (try (let [u   (js/URL. uri)
                           seg (last (remove str/blank? (str/split (.-pathname u) #"/")))]
                       (or (not-empty seg) (.-hostname u)))
                     (catch :default _ uri))
    :else       (last (str/split uri #"/"))))

(defn- strip-trailing-slash
  "Drop a single trailing '/' from a path (but keep the root '/')."
  [p]
  (if (and (> (count p) 1) (str/ends-with? p "/")) (subs p 0 (dec (count p))) p))

(defn dirname
  "Parent directory path of a local-file uri (file:// stripped). nil for http(s) or when there is no
   parent (the filesystem root). A trailing slash is ignored, so a directory uri yields its parent."
  [uri]
  (when-let [p (some-> (file-path uri) strip-trailing-slash)]
    (let [i (str/last-index-of p "/")]
      (cond
        (nil? i)  nil                          ; no slash → no parent
        (zero? i) (when (> (count p) 1) "/")   ; \"/foo\" → \"/\"; \"/\" → nil
        :else     (subs p 0 i)))))

(defn segments
  "Ordered breadcrumb segments of a local-file uri, root → leaf: each {:name :path}, where :path is the
   cumulative absolute path navigable to that point. The filesystem root is included as {:name \"/\"
   :path \"/\"}. nil for http(s)."
  [uri]
  (when-let [p (some-> (file-path uri) strip-trailing-slash)]
    (let [named (rest (str/split p #"/"))]      ; \"/a/b\" → (\"a\" \"b\"); \"/\" → ()
      (into [{:name "/" :path "/"}]
            (map-indexed (fn [i name]
                           {:name name
                            :path (str "/" (str/join "/" (take (inc i) named)))})
                         named)))))

(defn ancestor-paths
  "The navigable ancestor paths of a local-file uri, root → leaf — i.e. (map :path (segments uri))."
  [uri]
  (mapv :path (segments uri)))

(defn complete-split
  "Split a path-ish input at its last separator (either '/' or '\\\\'): [dir-part base]. `dir-part`
   includes the trailing separator (so it names the directory to list); a trailing-separator input
   yields base \"\". For path auto-completion in the URI bar."
  [text]
  (let [text (str text)
        i    (max (.lastIndexOf text "/") (.lastIndexOf text "\\"))]
    (if (neg? i) ["" text] [(subs text 0 (inc i)) (subs text (inc i))])))

(defn matches-prefix?
  "Case-insensitive basename prefix match for completion, hiding dotfiles unless `base` starts with '.'."
  [name base]
  (and (or (str/starts-with? base ".") (not (str/starts-with? name ".")))
       (str/starts-with? (str/lower-case name) (str/lower-case base))))

(defn common-prefix
  "Longest common string prefix of a sequence of strings (\"\" for an empty sequence or no shared prefix).
   Drives Tab-completion: Tab fills the input up to the matches' common prefix (Fish/readline semantics)."
  [strs]
  (if (empty? strs)
    ""
    (reduce (fn [acc s]
              (let [n (min (count acc) (count s))]
                (loop [i 0]
                  (if (and (< i n) (= (.charAt acc i) (.charAt s i))) (recur (inc i)) (subs acc 0 i)))))
            (first strs) (rest strs))))

(defn web-matches
  "Visited URLs from browser history that complete `text` — case-insensitive, prefix matches first (these
   drive the ghost suggestion), then substring matches; order within each group is preserved (history is
   most-recent-first). Returns at most `cap` URLs. Powers the address bar's history completion for web
   pages (the filesystem path completion is for local files)."
  [history text cap]
  (let [q (str/lower-case (str text))]
    (if (str/blank? q)
      []
      (->> history
           (filter #(str/includes? (str/lower-case %) q))
           (sort-by (fn [u] (if (str/starts-with? (str/lower-case u) q) 0 1)))
           (take cap)
           vec))))
