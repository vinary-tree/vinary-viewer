(ns vinary.diff
  "Pure unified/git diff model + the side-by-side (split) renderer — DOM-free and fs-free, so it is fully
   node-testable and shared by the GUI and the CLI.

   `parse` turns `.diff`/`.patch` text into a `{:preamble :files}` structure (git AND plain `diff -u`, renames,
   new/deleted files, binary markers, `\\ No newline at end of file`, and a git-format-patch preamble/commit
   message). `split-rows` aligns one file's hunks into side-by-side rows; `split-html` renders the whole
   multi-file two-column view — optionally ENRICHED with the real on-disk file so unchanged regions between
   hunks show as full context (long runs collapse into native `<details>` gaps — no JS).

   The UNIFIED (single-column, colored) view is produced separately by `vinary.ir.frontend.diff`, which lowers
   to the shared HTML and ANSI back-ends. This namespace owns only the parse + the GUI-only split layout."
  (:require [clojure.string :as str]))

;; ─────────────────────────────── parse ───────────────────────────────

(defn- strip-path
  "Normalize a `--- a/foo`, `+++ b/foo`, or `diff --git` path token: drop a trailing tab+timestamp (plain
   `diff -u`), a surrounding pair of quotes (git quotes paths with odd bytes), and a leading `a/`/`b/` prefix.
   `/dev/null` → nil (an absent side: a new or deleted file)."
  [s]
  (let [s (-> (str s) (str/replace #"\t.*$" "") str/trim)
        s (if (and (> (count s) 1) (str/starts-with? s "\"") (str/ends-with? s "\""))
            (subs s 1 (dec (count s)))
            s)]
    (cond
      (= s "/dev/null")     nil
      (re-find #"^[ab]/" s) (subs s 2)
      :else                 (not-empty s))))

(defn- git-header-paths
  "The two paths in a `diff --git a/OLD b/OLD` line, as [old new] (best-effort; the `--- `/`+++ ` lines below
   are authoritative when present). Handles the common unquoted, space-free case and falls back to nil paths."
  [line]
  (if-let [[_ a b] (re-matches #"^diff --git (?:\"?a/)?(.+?)\"? (?:\"?b/)?(.+?)\"?$" line)]
    [(not-empty a) (not-empty b)]
    [nil nil]))

(defn- hunk-header
  "Parse an `@@ -oldStart,oldCount +newStart,newCount @@ heading` line → a hunk map (counts default to 1 when
   omitted, as in `@@ -5 +5 @@`), or nil when `line` is not a standard two-sided hunk header."
  [line]
  (when-let [[_ os oc ns nc heading]
             (re-matches #"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$" line)]
    {:old-start (js/parseInt os 10) :old-count (if oc (js/parseInt oc 10) 1)
     :new-start (js/parseInt ns 10) :new-count (if nc (js/parseInt nc 10) 1)
     :heading   (str/trim (or heading "")) :lines []}))

(defn- blank-file []
  {:old-path nil :new-path nil :hunks []
   :rename? false :new-file? false :deleted? false :binary? false :mode nil})

(defn- flush-hunk
  "Append the in-progress `hunk` (if any) to `file`'s :hunks."
  [file hunk]
  (if hunk (update file :hunks conj hunk) file))

(defn- flush-file
  "Append (flush-hunk file hunk) to `files` when a file is in progress."
  [files file hunk]
  (if file (conj files (flush-hunk file hunk)) files))

(defn- body-line
  "Classify a hunk-body line by its leading marker → {:kind :context|:insert|:delete :text} or
   {:kind :no-newline} for the `\\ No newline…` marker, or nil when the line is not part of a hunk body."
  [line]
  (cond
    (= line "")                 {:kind :context :text ""}          ; a blank context line emitted without its space
    (str/starts-with? line " ") {:kind :context :text (subs line 1)}
    (str/starts-with? line "+") {:kind :insert :text (subs line 1)}
    (str/starts-with? line "-") {:kind :delete :text (subs line 1)}
    (str/starts-with? line "\\") {:kind :no-newline}              ; "\ No newline at end of file"
    :else                       nil))

(defn parse
  "Parse unified/git diff `text` → {:preamble string :files [file …]}.
     file — {:old-path :new-path :rename? :new-file? :deleted? :binary? :mode :hunks [hunk …]}
     hunk — {:old-start :old-count :new-start :new-count :heading :lines [line …]}
     line — {:kind :context|:insert|:delete :text :old-n int|nil :new-n int|nil :no-newline? bool}
   Paths have their `a/`/`b/` prefixes stripped; an absent side (new/deleted file) is nil. Lines before the
   first file section (a git-format-patch header + commit message + diffstat) are returned as :preamble."
  [text]
  ;; rem-old / rem-new = the hunk's declared old/new line budgets still unconsumed. A hunk terminates the
  ;; instant BOTH reach 0 — so trailing content (e.g. a git-format-patch `-- ` signature, whose leading `-`
  ;; would otherwise read as a deletion) falls OUTSIDE the hunk and is ignored.
  (loop [ls       (str/split (str text) #"\n" -1)
         preamble []
         files    []
         file     nil
         hunk     nil
         old-n    0
         new-n    0
         rem-old  0
         rem-new  0]
    (if (empty? ls)
      {:preamble (str/join "\n" preamble)
       :files    (flush-file files file hunk)}
      (let [line (first ls)
            rest-ls (rest ls)
            in-hunk? (and hunk (or (pos? rem-old) (pos? rem-new)))]  ; still inside the current hunk's budget
        (cond
          ;; ── new git file section ──────────────────────────────────
          (str/starts-with? line "diff --git ")
          (let [[a b] (git-header-paths line)]
            (recur rest-ls preamble (flush-file files file hunk)
                   (assoc (blank-file) :old-path a :new-path b) nil 0 0 0 0))

          ;; ── git extended headers (only meaningful inside a section, before a hunk) ─
          (and file (not in-hunk?) (re-matches #"^(old|new) mode \d+$" line))
          (recur rest-ls preamble files file hunk old-n new-n rem-old rem-new)

          (and file (not in-hunk?) (str/starts-with? line "new file mode "))
          (recur rest-ls preamble files (assoc file :new-file? true :mode (str/trim (subs line 13))) hunk old-n new-n rem-old rem-new)

          (and file (not in-hunk?) (str/starts-with? line "deleted file mode "))
          (recur rest-ls preamble files (assoc file :deleted? true :mode (str/trim (subs line 18))) hunk old-n new-n rem-old rem-new)

          (and file (not in-hunk?) (str/starts-with? line "rename from "))
          (recur rest-ls preamble files (assoc file :rename? true :old-path (strip-path (subs line 12))) hunk old-n new-n rem-old rem-new)

          (and file (not in-hunk?) (str/starts-with? line "rename to "))
          (recur rest-ls preamble files (assoc file :rename? true :new-path (strip-path (subs line 10))) hunk old-n new-n rem-old rem-new)

          (and file (not in-hunk?) (or (str/starts-with? line "index ")
                                       (str/starts-with? line "similarity index ")
                                       (str/starts-with? line "dissimilarity index ")
                                       (str/starts-with? line "copy from ")
                                       (str/starts-with? line "copy to ")))
          (recur rest-ls preamble files file hunk old-n new-n rem-old rem-new)

          ;; ── binary payloads (no textual hunks) ────────────────────
          (and (not in-hunk?)
               (or (str/starts-with? line "Binary files ") (str/starts-with? line "GIT binary patch")))
          (recur rest-ls preamble files (assoc (or file (blank-file)) :binary? true) hunk old-n new-n rem-old rem-new)

          ;; ── old-side header `--- PATH` ────────────────────────────
          (and (not in-hunk?) (str/starts-with? line "--- "))
          (let [start? (or (nil? file) (seq (:hunks file)) hunk)
                files' (if start? (flush-file files file hunk) files)
                file'  (-> (if start? (blank-file) file)
                           (assoc :old-path (strip-path (subs line 4))))]
            (recur rest-ls preamble files' file' nil 0 0 0 0))

          ;; ── new-side header `+++ PATH` ────────────────────────────
          (and (not in-hunk?) (str/starts-with? line "+++ "))
          (recur rest-ls preamble files
                 (assoc (or file (blank-file)) :new-path (strip-path (subs line 4))) hunk old-n new-n rem-old rem-new)

          ;; ── hunk header `@@ … @@` ─────────────────────────────────
          (and (not in-hunk?) (str/starts-with? line "@@ "))
          (if-let [h (hunk-header line)]
            (recur rest-ls preamble files (flush-hunk (or file (blank-file)) hunk)
                   h (:old-start h) (:new-start h) (:old-count h) (:new-count h))
            ;; combined/merge diff (`@@@`) or an unparseable header — keep a heading-only hunk so the file still
            ;; lists; its body lines have no budget (rem 0/0) so they fall through to the ignore arm.
            (recur rest-ls preamble files (flush-hunk (or file (blank-file)) hunk)
                   {:old-start 0 :old-count 0 :new-start 0 :new-count 0 :heading (str/trim line) :lines []}
                   0 0 0 0))

          ;; ── "\ No newline at end of file" annotates the PREVIOUS hunk line — handled whenever a hunk is open,
          ;;    even after its budget is spent (the marker can follow the hunk's very last line, e.g. an EOF
          ;;    insert with no trailing newline), so it is caught before the budget-gated body/flush arms below.
          (and hunk (str/starts-with? line "\\"))
          (recur rest-ls preamble files file
                 (if (seq (:lines hunk))
                   (update-in hunk [:lines (dec (count (:lines hunk)))] assoc :no-newline? true)
                   hunk)
                 old-n new-n rem-old rem-new)

          ;; ── hunk body (only while the hunk's budget is unconsumed) ─
          (and in-hunk? (body-line line))
          (let [{:keys [kind text]} (body-line line)]
            (case kind
              :no-newline
              ;; annotate the last emitted line of the current hunk; consumes no budget
              (let [hunk' (if (seq (:lines hunk))
                            (update-in hunk [:lines (dec (count (:lines hunk)))] assoc :no-newline? true)
                            hunk)]
                (recur rest-ls preamble files file hunk' old-n new-n rem-old rem-new))
              :context
              (recur rest-ls preamble files file
                     (update hunk :lines conj {:kind :context :text text :old-n old-n :new-n new-n})
                     (inc old-n) (inc new-n) (dec rem-old) (dec rem-new))
              :insert
              (recur rest-ls preamble files file
                     (update hunk :lines conj {:kind :insert :text text :old-n nil :new-n new-n})
                     old-n (inc new-n) rem-old (dec rem-new))
              :delete
              (recur rest-ls preamble files file
                     (update hunk :lines conj {:kind :delete :text text :old-n old-n :new-n nil})
                     (inc old-n) new-n (dec rem-old) rem-new)))

          ;; ── a completed hunk: flush it, then re-process this same line as a section/top-level line ──
          (and hunk (not in-hunk?))
          (recur ls preamble files (flush-hunk file hunk) nil old-n new-n 0 0)

          ;; ── outside any file section → preamble (git-format-patch header / commit message / diffstat) ──
          (nil? file)
          (recur rest-ls (conj preamble line) files file hunk old-n new-n rem-old rem-new)

          ;; ── anything else inside a section but outside a hunk (e.g. the `-- ` signature) → ignore ──
          :else
          (recur rest-ls preamble files file hunk old-n new-n rem-old rem-new))))))

;; ─────────────────────────────── display helpers ───────────────────────────────

(defn file-label
  "A human path label for a file section: `old → new` for renames, else the present side (new, else old),
   else `/dev/null`. Used by the unified file banner and the split header."
  [{:keys [old-path new-path rename?]}]
  (cond
    (and rename? old-path new-path (not= old-path new-path)) (str old-path " → " new-path)
    new-path new-path
    old-path old-path
    :else "/dev/null"))

(defn file-status
  "A short status word for a file section, for a badge on the banner."
  [{:keys [rename? new-file? deleted? binary?]}]
  (cond deleted? "deleted" new-file? "added" rename? "renamed" binary? "binary" :else "modified"))

(defn referenced-paths
  "Distinct present (non-nil, non-/dev/null) file paths a diff refers to — the set to resolve on disk for the
   split view's full-file enrichment. Prefers the new side (the post-image, matching the working tree)."
  [{:keys [files]}]
  (->> files
       (mapcat (fn [f] [(:new-path f) (:old-path f)]))
       (remove nil?)
       distinct
       vec))

;; ─────────────────────────────── split (side-by-side) rows ───────────────────────────────

(defn- cell [line] (when line {:n (or (:old-n line) (:new-n line)) :text (:text line) :no-newline? (:no-newline? line)}))

(defn- pair-runs
  "Zip a run of deletes with the following run of inserts into aligned change/delete/insert rows."
  [dels inss]
  (let [n (max (count dels) (count inss))]
    (mapv (fn [i]
            (let [d (nth dels i nil) ins (nth inss i nil)]
              {:kind (cond (and d ins) :change d :delete :else :insert)
               :old (cell d) :new (cell ins)}))
          (range n))))

(defn split-rows
  "Align one parsed `file`'s hunks into a flat vector of side-by-side rows. Row kinds:
     :hunk    — a hunk separator {:kind :hunk :heading :old-start :new-start}
     :context — {:kind :context :old {n text} :new {n text}}   (same line both sides)
     :change  — {:kind :change  :old {…} :new {…}}              (a delete paired with an insert)
     :delete  — {:kind :delete  :old {…} :new nil}
     :insert  — {:kind :insert  :old nil  :new {…}}
   A delete-run immediately followed by an insert-run pairs up (the common `-`/`+` block); leftovers become
   one-sided rows. Pure — derivable from the diff alone, no source files needed."
  [file]
  (into []
        (mapcat
         (fn [hunk]
           (loop [ls (:lines hunk), dels [], inss [], rows [(assoc (select-keys hunk [:heading :old-start :new-start]) :kind :hunk)]]
             (if (empty? ls)
               (into rows (pair-runs dels inss))
               (let [{:keys [kind] :as ln} (first ls)]
                 (case kind
                   :context (recur (rest ls) [] [] (-> (into rows (pair-runs dels inss))
                                                       (conj {:kind :context :old (cell ln) :new (cell ln)})))
                   :delete  (recur (rest ls) (conj dels ln) inss rows)
                   :insert  (recur (rest ls) dels (conj inss ln) rows)
                   (recur (rest ls) dels inss rows)))))))
        (:hunks file)))

;; ─────────────────────────────── split → HTML ───────────────────────────────

(defn- esc [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(def ^:private gap-threshold
  "Unchanged runs longer than this (in the enriched view) collapse into a `<details>` gap; shorter runs render
   inline so a couple of lines of context are never hidden behind a click."
  10)

(defn- num-cell [n side] (str "<span class=\"vv-diff-num vv-diff-num-" side "\">" (when n (esc n)) "</span>"))

(defn- side-cell [text side]
  (str "<span class=\"vv-diff-side vv-diff-side-" side "\">"
       (if (str/blank? (str text)) "" (esc text)) "</span>"))

(defn- row-html
  "One 4-column split row (old-num, old-text, new-num, new-text) with a kind class."
  [{:keys [kind old new]}]
  (str "<div class=\"vv-diff-row vv-diff-row-" (name kind) "\">"
       (num-cell (:n old) "old") (side-cell (:text old) "old")
       (num-cell (:n new) "new") (side-cell (:text new) "new")
       "</div>"))

(defn- hunk-sep-html [{:keys [heading old-start new-start]}]
  (str "<div class=\"vv-diff-row vv-diff-row-hunk\"><span class=\"vv-diff-hunk-label\">"
       (esc (str "@@ -" old-start " +" new-start " @@" (when (seq heading) (str " " heading))))
       "</span></div>"))

(defn- context-row [old-n new-n text]
  {:kind :context :old {:n old-n :text text} :new {:n new-n :text text}})

(defn- rows->html
  "Render a seq of split rows, collapsing runs of >gap-threshold CONTEXT rows into a native `<details>` gap
   (only when `collapse?` — the enriched full-file view; the hunks-only view keeps every row visible)."
  [rows collapse?]
  (letfn [(emit [rs]
            (apply str (map (fn [r] (if (= :hunk (:kind r)) (hunk-sep-html r) (row-html r))) rs)))]
    (if-not collapse?
      (emit rows)
      ;; group consecutive context rows; long groups collapse
      (loop [rs rows, out ""]
        (if (empty? rs)
          out
          (let [[ctx more] (split-with #(= :context (:kind %)) rs)]
            (if (> (count ctx) gap-threshold)
              (recur more (str out "<details class=\"vv-diff-gap\"><summary class=\"vv-diff-gap-summary\">"
                               (esc (str "⋯ " (count ctx) " unchanged lines")) "</summary>"
                               (emit ctx) "</details>"))
              (let [[chunk more2] (if (seq ctx) [ctx more] (split-at 1 rs))]
                (recur more2 (str out (emit chunk)))))))))))

(defn- enrich-rows
  "Splice a file's hunk rows with the real on-disk NEW-file lines so unchanged regions between (and around)
   hunks show as full context. `new-lines` is the resolved file split into lines. Falls back to the plain
   hunk rows when the file has no usable new side (a deletion / missing source)."
  [file new-lines]
  (if (or (:deleted? file) (nil? (:new-path file)) (empty? new-lines))
    [(split-rows file) false]
    (let [hunks (:hunks file)]
      [(loop [hs hunks, old-at 1, new-at 1, out []]
         (if (empty? hs)
           ;; tail: unchanged lines after the last hunk
           (into out (map-indexed (fn [i t] (context-row (+ old-at i) (+ new-at i) t))
                                  (subvec (vec new-lines) (min (dec new-at) (count new-lines)))))
           (let [{:keys [old-start new-start old-count new-count] :as h} (first hs)
                 ;; unchanged gap [new-at, new-start): pull text from the real file, old numbers advance in step
                 gap  (map-indexed (fn [i t] (context-row (+ old-at i) (+ new-at i) t))
                                   (subvec (vec new-lines) (max 0 (dec new-at)) (max 0 (dec new-start))))]
             (recur (rest hs)
                    (+ old-start old-count) (+ new-start new-count)
                    (into (into out gap) (split-rows (assoc file :hunks [h])))))))
       true])))

(defn- file-split-html [file sources]
  (let [status (file-status file)
        src    (get sources (:new-path file))
        [rows collapse?] (if src (enrich-rows file (str/split src #"\n" -1)) [(split-rows file) false])]
    (str "<section class=\"vv-diff-file vv-diff-split\" data-status=\"" status "\">"
         "<header class=\"vv-diff-file-head\" id=\"vv-diff-file-" (:idx file) "\">"
         "<span class=\"vv-diff-file-status vv-diff-status-" status "\">" status "</span>"
         "<span class=\"vv-diff-file-name\">" (esc (file-label file)) "</span></header>"
         (cond
           (:binary? file) "<div class=\"vv-diff-row vv-diff-row-note\">Binary file — no textual diff</div>"
           (and (empty? (:hunks file)) (:rename? file)) "<div class=\"vv-diff-row vv-diff-row-note\">Renamed with no content change</div>"
           (empty? (:hunks file)) "<div class=\"vv-diff-row vv-diff-row-note\">No changes</div>"
           :else (str "<div class=\"vv-diff-grid\">" (rows->html rows collapse?) "</div>"))
         "</section>")))

(defn split-html
  "Render a parsed diff `model` as the multi-file side-by-side view (an HTML string for `markdown-body`).
   `sources` (optional) maps a file's new-path → its on-disk text; present files get full-file context
   (unchanged runs collapse into `<details>` gaps), absent ones fall back to the hunk windows."
  ([model] (split-html model nil))
  ([{:keys [files]} sources]
   (str "<div class=\"vv-diff vv-diff-splitview\">"
        (apply str (map-indexed (fn [i f] (file-split-html (assoc f :idx i) (or sources {}))) files))
        "</div>")))
