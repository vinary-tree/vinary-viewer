(ns vinary.main.service-util
  "Pure, electron/DOM-free routing helpers for the main IO service (vinary.main.service), so the node
   :test build can cover the content-routing decision without loading electron/fs."
  (:require [clojure.string :as str]))

(def parser-kinds
  "Local kinds main streams/parses into a bounded preview payload (logs, delimited tables, office docs,
   archives, and plain text log-sniffed for the same) — vs. reading the bytes as escaped source text.
   Plain text stays in this set for the LOG sniff and the sourceable text tail only: the delimiter sniff
   that used to flip prose into a delimited table is disabled in content_service (plain text renders
   literally; a delimited table is named .csv/.tsv/… or explicitly typed)."
  #{"office" "table" "log" "archive" "text"})

(defn route
  "Pure content-routing decision for a local path, from precomputed predicates. A real directory wins
   over every name-based classification: an extensionless directory name (e.g. \"publication\")
   classifies as \"text\", and routing that into the parser fs.readSyncs a directory fd → EISDIR, so a
   directory must LIST instead. Returns one of :directory :parsed :image :html :pdf :text.
     directory? — path is a real filesystem directory (already excludes archive URIs)
     archive?   — path is an archive URI / archive file main lists internally
     kind       — file-kind/kind-of classification of the path's name"
  [{:keys [git-graph? directory? archive? kind]}]
  (cond
    ;; the Commit Graph virtual document (ADR-0040) routes FIRST — its uri names no filesystem
    ;; object, so no stat/name-based arm below may ever see it
    git-graph?                                  :git-graph
    directory?                                  :directory
    (or archive? (contains? parser-kinds kind)) :parsed
    (= "image" kind)                            :image
    (= "html" kind)                             :html
    (= "pdf" kind)                              :pdf
    :else                                       :text))

;; ---- synthetic (non-git) project roots ----
;; A file outside every git repository still deserves a sidebar tree, so its containing directory is
;; adopted as a project root of its own. A git repo is self-delimiting and self-filtering (ls-files
;; --exclude-standard); an arbitrary directory is neither, so the walk needs its own bounds and its own
;; exclusions. Those policies are pure and live here; the fs walk itself lives in vinary.main.dir-walk.

(def heavy-dirs
  "Non-hidden directories a synthetic root never descends into: build output and dependency trees. They
   dominate a working directory by file count and none of it is worth browsing. The git path gets this
   for free from --exclude-standard; a plain directory has no .gitignore to consult, so it is named here."
  #{"node_modules" "target" "dist" "build" "out" "__pycache__"})

(def walk-limits
  "Bounds on the synthetic-root walk. Open a file in $HOME with no cap and the walk never ends, so depth
   and total entries are both capped. The walk is breadth-first, so hitting a cap yields a useful shallow
   tree rather than one arbitrarily deep branch."
  {:max-depth 6 :max-entries 5000})

(defn skip-dir?
  "Should the synthetic-root walk refuse to descend into this directory name? Hidden directories are
   skipped wholesale — one rule covering .git, .venv, .cache, .next, .tox — as are `heavy-dirs`. Hidden
   FILES are kept: `git ls-files --exclude-standard` lists .gitignore, and parity with it is the goal."
  [name]
  (boolean (and name (or (str/starts-with? name ".") (contains? heavy-dirs name)))))

(defn filesystem-root?
  "Is this path the top of a filesystem — POSIX \"/\", a Windows drive root (\"C:\", \"C:\\\"), or a UNC
   server/share (\"\\\\server\", \"\\\\server\\share\", which node's path/dirname likewise reports as its
   own parent)? Such a path is never adopted as a project root: the walk would be both useless and
   effectively unbounded. The UNC test is gated on the backslash spelling so a POSIX \"//foo\" — legal,
   and not a root — is not mistaken for a share."
  [path]
  (boolean
   (when path
     (let [unc? (str/starts-with? path "\\\\")                         ; two literal backslashes
           p    (str/replace path #"\\" "/")                           ; one spelling of the separator
           p    (if (and (> (count p) 1) (str/ends-with? p "/"))       ; "C:/" → "C:", but "/" stays "/"
                  (subs p 0 (dec (count p)))
                  p)]
       (or (= p "/")
           (some? (re-matches #"[A-Za-z]:" p))                         ; drive root
           (and unc? (some? (re-matches #"//[^/]*(/[^/]*)?" p))))))))  ; \\server or \\server\share

(defn fallback-root
  "The directory to adopt as a project root for a path in no git repository, or nil to refuse. A
   DIRECTORY is its own root — its parent would be a surprise, and for \"/notes\" the parent is \"/\". A
   FILE uses its parent. `parent` is precomputed by the caller (node path/dirname) so this stays pure.
   A filesystem root is refused; the home directory is not, since opening ~/notes.md and getting a
   browsable ~ is reasonable and `walk-limits` keeps it bounded."
  [{:keys [path directory? parent]}]
  (let [root (if directory? path parent)]
    (when (and root (not (str/blank? root)) (not (filesystem-root? root)))
      root)))

;; ---- projecting one listing onto expanded scopes ----
;; A scope is a root-relative directory, "/"-joined, with "" for the root. A git listing is the whole repository, so
;; one listing answers every expanded directory of the root at once; these project it.

(defn prefix-files
  "The files of `files` (root-relative) under directory `scope` — all of them for the root scope \"\"."
  [scope files]
  (if (str/blank? scope)
    (vec files)
    (let [prefix (str scope "/")]
      (filterv #(str/starts-with? % prefix) files))))

(defn- strict-ancestors
  "The scopes strictly containing `scope`: \"\" and each proper \"/\"-prefix of it (none for the root itself)."
  [scope]
  (when-not (str/blank? scope)
    (loop [i (.indexOf scope "/") out [""]]
      (if (neg? i) out (recur (.indexOf scope "/" (inc i)) (conj out (subs scope 0 i)))))))

(defn maximal-scopes
  "The outermost of `scopes`: those no OTHER member contains. When one listing serves a directory and its
   descendants, the ancestor's payload already carries the descendants' files, and the renderer replacing the
   ancestor's subtree leaves exactly what replacing it and then each descendant's would — so only these need
   sending. O(n × depth): each scope looks its ancestors up in a set."
  [scopes]
  (let [ss (set scopes)]
    (into #{} (remove (fn [s] (some ss (strict-ancestors s)))) ss)))

(defn- containing-dirs
  "The directories containing root-relative FILE path `f`: \"\" and each \"/\"-prefix of it."
  [f]
  (loop [i (.indexOf f "/") out [""]]
    (if (neg? i) out (recur (.indexOf f "/" (inc i)) (conj out (subs f 0 i))))))

(defn bucket-files
  "{scope → (prefix-files scope files)} for every scope in `scopes`, built in ONE pass over `files`: each file joins
   the bucket of every wanted directory containing it, in `files` order. O(files × depth) rather than the
   O(files × scopes) of projecting scope by scope — a Files filter can hold hundreds of directories open."
  [scopes files]
  (let [wanted  (set scopes)
        buckets (reduce (fn [m f]
                          (reduce (fn [m d] (if (contains? wanted d) (assoc! m d (conj! (get m d) f)) m))
                                  m (containing-dirs f)))
                        (reduce (fn [m s] (assoc! m s (transient []))) (transient {}) wanted)
                        files)]
    (persistent! (reduce (fn [m s] (assoc! m s (persistent! (get buckets s)))) (transient {}) wanted))))

(defn diff-extra
  "The Files-tree :extras entry for a diff document adopted into a project (ADR-0038), or nil when the
   diff lies INSIDE the adopted root — `git ls-files --others` already lists it there, and a second row
   would be a double listing. `inside-root?` and `basename` are precomputed by the caller (node path)
   so this stays pure. A piped-stdin diff displays as \"(piped diff)\" (its spill basename is the
   cryptic \"stdin\") and is :transient? — the renderer prunes it when document retention drops the
   spill, matching the service unlinking the temp file."
  [{:keys [path basename inside-root? stdin?]}]
  (when-not inside-root?
    {:path       path
     :name       (if stdin? "(piped diff)" basename)
     :kind       "diff"
     :transient? (boolean stdin?)}))

(defn dominant-root
  "The most frequent non-nil root among `roots`, first-seen winning ties; nil when every root is nil.
   A diff whose targets resolve into several repositories adopts the one that owns most of them
   (ADR-0038 D10). The strictly-greater comparison over the first-seen-ordered seq is what makes the
   tie-break deterministic — a CLJS hash map would not preserve insertion order."
  [roots]
  (let [rs     (filterv some? roots)
        counts (frequencies rs)]
    (reduce (fn [best r]
              (if (or (nil? best) (> (get counts r) (get counts best)))
                r
                best))
            nil
            rs)))
