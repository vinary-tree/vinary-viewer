(ns vinary.main.service-util
  "Pure, electron/DOM-free routing helpers for the main IO service (vinary.main.service), so the node
   :test build can cover the content-routing decision without loading electron/fs."
  (:require [clojure.string :as str]))

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

;; ---- synthetic (non-git) project roots ----
;; A file outside every git repository still deserves a sidebar tree, so its containing directory is
;; adopted as a project root of its own. A git repo is self-delimiting and self-filtering (ls-files
;; --exclude-standard); an arbitrary directory is neither, so the walk needs its own bounds and its own
;; exclusions. Those policies are pure and live here; the fs walk itself lives in vinary.main.service.

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
