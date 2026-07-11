(ns vinary.app.uri
  "URI helpers. A tab's :uri is the canonical address it shows — an absolute local file path
   (\"/home/u/README.md\") or an http(s) URL. Local paths are displayed file://-prefixed in the URI bar."
  (:require [clojure.string :as str]))

(defn http? [uri] (boolean (and uri (re-find #"(?i)^https?://" uri))))
(defn archive? [uri] (str/starts-with? (str uri) "vv-archive://"))

;; ---- remote (SSH/SFTP) URIs: ssh://[user@]host[:port]/path and the sftp:// alias ----
;; Both drive the SFTP subsystem for file ops; the scheme is retained only for display. Like archive
;; URIs, these are virtual addresses served entirely main-side (never a local path), so file-path
;; PRESERVES them and every path-arithmetic helper below keeps the user@host[:port] AUTHORITY intact.
(defn ssh?    [uri] (boolean (and uri (re-find #"(?i)^ssh://"  uri))))
(defn sftp?   [uri] (boolean (and uri (re-find #"(?i)^sftp://" uri))))
(defn remote? [uri] (or (ssh? uri) (sftp? uri)))

(defn remote-parts
  "Split a remote uri into [prefix path]: prefix = \"scheme://[user@]host[:port]\" (the authority, no
   trailing '/'), path = the remote path beginning with '/' (or \"/\" when absent). The authority is
   matched as [^/]* so a user@host:port is never split on its ':' or '@'. Returns [uri \"/\"] for a
   non-remote or malformed uri (callers gate on `remote?` first)."
  [uri]
  (if-let [m (re-find #"(?i)^(s(?:sh|ftp)://[^/]*)(/.*)?$" (str uri))]
    [(nth m 1) (or (nth m 2) "/")]
    [(str uri) "/"]))

(defn archive-chain [uri]
  (when (archive? uri)
    (try
      (let [u (js/URL. uri)
            chain (.parse js/JSON (or (.get (.-searchParams u) "chain") "[]"))]
        (vec (array-seq chain)))
      (catch :default _ []))))

(defn archive-display [uri]
  (if-let [[root & entries] (seq (archive-chain uri))]
    (str "file://" root "!/" (str/join "!/" entries))
    (str uri)))

(defn file-path
  "The main-openable local address for a uri: a leading file:// stripped; http(s) → nil; archive virtual
   URIs preserved; else the uri as-is."
  [uri]
  (cond (nil? uri)                        nil
        (http? uri)                       nil
        (archive? uri)                    uri
        (remote? uri)                     uri        ; ssh://sftp:// opened by main's remote reader
        (str/starts-with? uri "file://")  (subs uri 7)
        :else                             uri))

(defn normalize
  "Canonical tab :uri from user/tree input: blank → nil; http(s) kept; file:// stripped to a path; else
   the text as-is (an absolute path)."
  [text]
  (let [t (str/trim (or text ""))]
    (cond (str/blank? t)                  nil
          (http? t)                       t
          (archive? t)                    t
          (remote? t)                     t
          (str/starts-with? t "file://")  (subs t 7)
          :else                           t)))

(defn display
  "How a uri appears in the URI bar: http(s) as-is; a local path shown file://-prefixed."
  [uri]
  (cond (nil? uri) "" (http? uri) uri (archive? uri) (archive-display uri)
        (remote? uri) uri :else (str "file://" uri)))

(defn basename
  "Short tab label: a file's basename, or for http the last path segment (falling back to the host)."
  [uri]
  (cond
    (nil? uri)  "New Tab"
    (http? uri) (try (let [u   (js/URL. uri)
                           seg (last (remove str/blank? (str/split (.-pathname u) #"/")))]
                       (or (not-empty seg) (.-hostname u)))
                     (catch :default _ uri))
    (archive? uri) (or (some-> (last (archive-chain uri)) (str/split #"/") last) "Archive")
    (remote? uri) (let [[_ p] (remote-parts uri)
                        segs  (remove str/blank? (str/split p #"/"))]
                    (or (last segs) "/"))       ; remote file/dir name; "/" at the remote root
    :else       (last (str/split uri #"/"))))

(defn- strip-trailing-slash
  "Drop a single trailing '/' from a path (but keep the root '/')."
  [p]
  (if (and (> (count p) 1) (str/ends-with? p "/")) (subs p 0 (dec (count p))) p))

(defn dirname
  "Parent directory path of a local-file uri (file:// stripped). nil for http(s) or when there is no
   parent (the filesystem root). A trailing slash is ignored, so a directory uri yields its parent."
  [uri]
  (cond
    (archive? uri)
    (let [[root & entries] (archive-chain uri)]
      (when root
        (if (seq entries)
          (let [parent (vec (butlast entries))]
            (str "vv-archive://open?chain="
                 (js/encodeURIComponent (.stringify js/JSON (clj->js (into [root] parent))))))
          (dirname root))))
    ;; remote: keep the authority on the parent — "ssh://a/x/y" -> "ssh://a/x"; "ssh://a/foo" -> "ssh://a/";
    ;; the remote root ("ssh://a/" or "ssh://a") has no parent -> nil
    (remote? uri)
    (let [[prefix p] (remote-parts uri)
          p* (strip-trailing-slash p)
          i  (str/last-index-of p* "/")]
      (cond
        (nil? i)  nil
        (zero? i) (when (> (count p*) 1) (str prefix "/"))
        :else     (str prefix (subs p* 0 i))))
    :else
    (when-let [p (some-> (file-path uri) strip-trailing-slash)]
      (let [i (str/last-index-of p "/")]
        (cond
          (nil? i)  nil                          ; no slash → no parent
          (zero? i) (when (> (count p) 1) "/")   ; \"/foo\" → \"/\"; \"/\" → nil
          :else     (subs p 0 i))))))

(defn segments
  "Ordered breadcrumb segments of a local-file uri, root → leaf: each {:name :path}, where :path is the
   cumulative absolute path navigable to that point. The filesystem root is included as {:name \"/\"
   :path \"/\"}. nil for http(s)."
  [uri]
  (cond
    (archive? uri)
    (let [[root & entries] (archive-chain uri)
          root-segs (segments root)
          entry-segs (map-indexed
                      (fn [i name]
                        (let [chain (into [root] (take (inc i) entries))]
                          {:name name
                           :path (str "vv-archive://open?chain="
                                      (js/encodeURIComponent (.stringify js/JSON (clj->js chain))))}))
                      entries)]
      (vec (concat root-segs entry-segs)))
    ;; remote: the authority is the root crumb (lists the remote "/"), then one navigable crumb per segment
    (remote? uri)
    (let [[prefix p] (remote-parts uri)
          named      (remove str/blank? (str/split (strip-trailing-slash p) #"/"))]
      (into [{:name prefix :path (str prefix "/")}]
            (map-indexed (fn [i name]
                           {:name name
                            :path (str prefix "/" (str/join "/" (take (inc i) named)))})
                         named)))
    :else
    (when-let [p (some-> (file-path uri) strip-trailing-slash)]
      (let [named (rest (str/split p #"/"))]      ; \"/a/b\" → (\"a\" \"b\"); \"/\" → ()
        (into [{:name "/" :path "/"}]
              (map-indexed (fn [i name]
                             {:name name
                              :path (str "/" (str/join "/" (take (inc i) named)))})
                           named))))))

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
