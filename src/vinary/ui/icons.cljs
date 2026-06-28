(ns vinary.ui.icons
  "Font Awesome (Free Solid, self-hosted under resources/public/assets/fa/) icon
   helpers. `icon`, `file-icon`, and `folder-icon` return hiccup <i> elements
   whose glyphs inherit `color` (so the theme palette + the existing :hover accent
   rules recolor them) and are sized by css/app.css. This namespace is the single
   source of truth mapping app-level keywords / file extensions to FA class names;
   every class name here is verified to exist in Font Awesome Free 7 Solid."
  (:require [clojure.string :as str]))

(def classes
  "App icon keyword → Font Awesome class string (`fa-solid fa-<name>`)."
  {:back             "fa-solid fa-arrow-left"
   :forward          "fa-solid fa-arrow-right"
   :reload           "fa-solid fa-arrows-rotate"
   :find-prev        "fa-solid fa-chevron-up"
   :find-next        "fa-solid fa-chevron-down"
   :close            "fa-solid fa-xmark"
   :new-tab          "fa-solid fa-plus"
   :add              "fa-solid fa-plus"
   :delete           "fa-solid fa-trash-can"
   :collapse         "fa-solid fa-chevron-left"
   :expand           "fa-solid fa-chevron-right"
   :move-left        "fa-solid fa-chevron-left"
   :move-right       "fa-solid fa-chevron-right"
   :submenu          "fa-solid fa-chevron-right"
   :section-files    "fa-solid fa-folder-tree"
   :section-contents "fa-solid fa-list-ul"
   :section-tabs     "fa-solid fa-window-restore"
   :backspace        "fa-solid fa-delete-left"
   :keyboard         "fa-solid fa-keyboard"
   :undo             "fa-solid fa-rotate-left"
   :redo             "fa-solid fa-rotate-right"
   :folder           "fa-solid fa-folder"
   :folder-open      "fa-solid fa-folder-open"
   :globe            "fa-solid fa-globe"
   :file             "fa-solid fa-file"})

(def ^:private code  "fa-solid fa-file-code")
(def ^:private image "fa-solid fa-file-image")
(def ^:private text  "fa-solid fa-file-lines")
(def ^:private zip   "fa-solid fa-file-zipper")
(def ^:private audio "fa-solid fa-file-audio")
(def ^:private video "fa-solid fa-file-video")

(def ext->class
  "Lower-cased file extension → Font Awesome class. `file-class` falls back to the
   generic file glyph for anything unlisted."
  {;; prose / docs
   "md" text "markdown" text "txt" text "rst" text "org" text "adoc" text "asciidoc" text "log" text
   ;; source / config / data → code glyph
   "clj" code "cljs" code "cljc" code "cljd" code "edn" code "bb" code
   "js" code "mjs" code "cjs" code "jsx" code "ts" code "tsx" code
   "json" code "json5" code "html" code "htm" code "xml" code
   "css" code "scss" code "sass" code "less" code "styl" code
   "rho" code "scm" code "rkt" code "lisp" code "el" code "fnl" code
   "rs" code "go" code "py" code "rb" code "php" code "pl" code "raku" code
   "java" code "kt" code "kts" code "scala" code "groovy" code "gradle" code
   "c" code "h" code "cc" code "cpp" code "cxx" code "hpp" code "hh" code
   "cs" code "swift" code "m" code "mm" code "lua" code "r" code "jl" code
   "sh" code "bash" code "zsh" code "fish" code "ps1" code "bat" code
   "toml" code "yaml" code "yml" code "ini" code "cfg" code "conf" code "properties" code
   "sql" code "graphql" code "gql" code "proto" code "dockerfile" code "make" code "mk" code
   "ex" code "exs" code "erl" code "hrl" code "hs" code "ml" code "mli" code "fs" code "fsx" code
   "vue" code "svelte" code "astro" code "tf" code "nix" code "vim" code
   ;; images (svg rendered as image in this app's preview)
   "png" image "jpg" image "jpeg" image "gif" image "webp" image "bmp" image
   "ico" image "tiff" image "tif" image "avif" image "svg" image
   ;; office / portable documents
   "pdf" "fa-solid fa-file-pdf"
   "csv" "fa-solid fa-file-csv" "tsv" "fa-solid fa-file-csv"
   "doc" "fa-solid fa-file-word" "docx" "fa-solid fa-file-word" "rtf" "fa-solid fa-file-word" "odt" "fa-solid fa-file-word"
   "xls" "fa-solid fa-file-excel" "xlsx" "fa-solid fa-file-excel" "ods" "fa-solid fa-file-excel"
   "ppt" "fa-solid fa-file-powerpoint" "pptx" "fa-solid fa-file-powerpoint" "odp" "fa-solid fa-file-powerpoint"
   ;; audio / video
   "mp3" audio "wav" audio "flac" audio "ogg" audio "m4a" audio "aac" audio
   "mp4" video "webm" video "mkv" video "mov" video "avi" video "m4v" video
   ;; archives
   "zip" zip "tar" zip "gz" zip "tgz" zip "bz2" zip "xz" zip "zst" zip "7z" zip "rar" zip})

(defn- with-class
  "Build an <i> attrs map: the FA `base` class plus any extra `:class` from
   `attrs`, with the remaining attrs preserved and aria-hidden set."
  [base attrs]
  (let [cls (if-let [extra (:class attrs)] (str base " " extra) base)]
    (assoc (dissoc attrs :class) :class cls :aria-hidden true)))

(defn icon
  "Hiccup <i> for app icon `k`. Optional `attrs` are merged onto the element; an
   extra `:class` (e.g. \"vv-ico-gap\") is appended to the FA class, not replaced."
  ([k] (icon k nil))
  ([k attrs] [:i (with-class (get classes k) attrs)]))

(defn file-class
  "Font Awesome class string for a file name, chosen by its extension
   (case-insensitive); the generic file glyph for unknown / extensionless names."
  [filename]
  (let [dot (str/last-index-of filename ".")
        ext (when (and dot (pos? dot)) (str/lower-case (subs filename (inc dot))))]
    (get ext->class ext (get classes :file))))

(defn file-icon
  "Hiccup <i> file-type glyph for `filename` (see `file-class`)."
  ([filename] (file-icon filename nil))
  ([filename attrs] [:i (with-class (file-class filename) attrs)]))

(defn folder-icon
  "Folder glyph pair for a native <details> disclosure: renders the closed and the
   open folder; css/app.css reveals whichever matches the details[open] state."
  []
  [:<>
   (icon :folder {:class "vv-folder-closed"})
   (icon :folder-open {:class "vv-folder-open"})])
