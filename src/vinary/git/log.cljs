(ns vinary.git.log
  "Pure git-log plumbing (ADR-0039): argv builders and record parsing for the Commits surfaces.
   Electron/DOM/fs-free so the node :test build covers it directly; vinary.main.git executes the
   argv and calls the parsers — the renderer only ever sees structured data.

   RECORD DISCIPLINE. The main pretty-format terminates each record with %x00 (git forbids NUL
   inside commit text, so the boundary is unforgeable) and separates the nine fields with %x1f. A
   hostile %x1f typed INTO a commit message can only corrupt its own record: the parser validates
   the field count and the 40-hex hash and drops what does not conform. Plain log and --follow file
   history emit no patch text, so splitting on %x00 is safe there. `git log -L` line history is a
   DIFFERENT discipline (%x1e record-start, --no-patch, patch bleed on pre-2.42 gits discarded) and
   ships with the history feature.

   Note the two format languages: `git log --pretty` spells hex escapes %xNN, while
   `git for-each-ref --format` spells them %NN — branches-args uses the latter."
  (:require [clojure.string :as str]))

(def ^:private field-sep "\u001f")
(def ^:private record-term "\u0000")
(def ^:private hash-re #"^[0-9a-f]{40}$")

(def pretty-format
  "Nine %x1f-separated fields, %x00-terminated: hash, parents, author name, author email,
   author date (%aI strict ISO), committer date (%cI), decorations (%D), subject, body."
  "format:%H%x1f%P%x1f%an%x1f%ae%x1f%aI%x1f%cI%x1f%D%x1f%s%x1f%b%x00")

(defn log-args
  "The exact `git log` argv for one page. --date-order is the cheapest ordering that never emits a
   parent before all of its children (the lane-assignment precondition; clock skew degrades to a
   fresh lane, never an error). --end-of-options forecloses option injection through a ref name —
   the ref is additionally rev-parse-verified by the caller before this argv ever runs. `file`
   scopes the walk to one path (with `follow?` crossing renames — a --follow constraint keeps it
   single-file)."
  [{:keys [ref skip limit file follow?]}]
  (-> ["log" "--date-order"
       (str "--max-count=" (long (or limit 250)))
       (str "--skip=" (long (or skip 0)))
       (str "--pretty=" pretty-format)]
      (cond-> follow? (conj "--follow"))
      (conj "--end-of-options" (or ref "HEAD"))
      (cond-> file (into ["--" file]))))

(defn branches-args
  "for-each-ref over heads/remotes/tags: refname, short name, worktree HEAD marker. %1f is
   for-each-ref's spelling of the 0x1f separator (verified: it emits the raw byte)."
  []
  ["for-each-ref" "--format=%(refname)%1f%(refname:short)%1f%(HEAD)"
   "refs/heads" "refs/remotes" "refs/tags"])

(defn head-args
  "The current branch's short name, or the literal HEAD when detached."
  []
  ["rev-parse" "--abbrev-ref" "HEAD"])

(defn verify-args
  "rev-parse verification for ONE user-supplied ref: --verify --quiet plus ^{commit} requires an
   existing commit-ish; --end-of-options stops a ref spelled like an option."
  [ref]
  ["rev-parse" "--verify" "--quiet" "--end-of-options" (str ref "^{commit}")])

(defn open-diff-args
  "git diff argv for a VALIDATED from/to pair. --no-ext-diff blocks repo-configured external diff
   drivers (untrusted repo config must never execute programs); --no-color because the text feeds
   the diff parser, not a terminal. `dots` \"...\" selects the symmetric-difference form, composed
   as one token from the two already-verified sides. `paths` (optional) scopes the diff."
  [{:keys [from to dots paths]}]
  (-> ["diff" "--no-color" "--no-ext-diff" "--end-of-options"]
      (cond-> dots       (conj (str from dots to))
              (not dots) (into [from to]))
      (cond-> (seq paths) (-> (conj "--") (into paths)))))

(defn- split-parents [s]
  (into [] (remove str/blank?) (str/split (or s "") #" ")))

(defn- split-refs [s]
  (into [] (comp (map str/trim) (remove str/blank?)) (str/split (or s "") #", ")))

(def ^:private record-start "\u001e")

(def line-log-format
  "The LINE-HISTORY format (R1's second discipline): %x1e OPENS each record — `git log -L` forces
   patch output on pre-2.42 gits even under --no-patch, and a record-start marker (unlike a
   terminator) lets the parser discard whatever trails the last field. No %b: a body could swallow
   bleed silently; the eight fields end at the single-line subject."
  "format:%x1e%H%x1f%P%x1f%an%x1f%ae%x1f%aI%x1f%cI%x1f%D%x1f%s")

(defn line-log-args
  "`git log -L<start>,<end>:<rel>` — line-range history. No pathspec and no --follow (line-log does
   its own content tracing across renames), no ref (history follows HEAD: a branch pick would
   contradict working-file lineage), and a single-shot -n bound (line-log paginates poorly; the
   caller marks the reply exhausted)."
  [{:keys [rel start end limit]}]
  ["log" (str "-L" (long start) "," (long end) ":" rel)
   "--no-patch" "-n" (str (long (or limit 500)))
   (str "--pretty=" line-log-format)])

(defn parse-line-log
  "Line-history text → {:commits […] :count n}, records split on the %x1e START marker. The subject
   (the last field) truncates at its first newline and anything after it — the patch text a
   pre-2.42 git bleeds despite --no-patch — is discarded, which makes both git behaviors parse
   identically. Fields beyond the eighth (a %x1f inside bleed) are ignored the same way."
  [text]
  (let [commits
        (into []
              (comp
               (map #(str/replace % #"^\n+" ""))
               (remove str/blank?)
               (keep (fn [record]
                       (let [fields (str/split record field-sep -1)]
                         (when (and (>= (count fields) 8)
                                    (re-matches hash-re (nth fields 0)))
                           {:hash           (nth fields 0)
                            :parents        (split-parents (nth fields 1))
                            :author-name    (nth fields 2)
                            :author-email   (nth fields 3)
                            :author-date    (nth fields 4)
                            :committer-date (nth fields 5)
                            :refs           (split-refs (nth fields 6))
                            :subject        (first (str/split-lines (nth fields 7)))
                            :body           ""})))))
              (str/split (str text) record-start))]
    {:commits commits :count (count commits)}))

(defn parse-log
  "Pretty-format text → {:commits [{:hash :parents :author-name :author-email :author-date
   :committer-date :refs :subject :body}] :count n}. Records are %x00-terminated; the newline git
   inserts between format records is absorbed by trimming each record's left edge. A record with
   the wrong field count or a non-40-hex hash is dropped — a hostile %x1f corrupts only itself."
  [text]
  (let [commits
        (into []
              (comp
               (map #(str/replace % #"^\n+" ""))
               (remove str/blank?)
               (keep (fn [record]
                       (let [fields (str/split record field-sep -1)]
                         (when (and (= 9 (count fields))
                                    (re-matches hash-re (nth fields 0)))
                           {:hash           (nth fields 0)
                            :parents        (split-parents (nth fields 1))
                            :author-name    (nth fields 2)
                            :author-email   (nth fields 3)
                            :author-date    (nth fields 4)
                            :committer-date (nth fields 5)
                            :refs           (split-refs (nth fields 6))
                            :subject        (nth fields 7)
                            :body           (str/trimr (nth fields 8))})))))
              (str/split (str text) record-term))]
    {:commits commits :count (count commits)}))

(defn parse-branches
  "for-each-ref text (+ the separately resolved head name) → {:head :detached? :branches
   [{:name :kind :current?}]}. kind comes from the refname prefix; current? from the %(HEAD) `*`
   marker. Unrecognized ref namespaces are dropped rather than guessed."
  [text head detached?]
  (let [branches
        (into []
              (keep (fn [line]
                      (let [fields (str/split line field-sep -1)]
                        (when (= 3 (count fields))
                          (let [[refname short marker] fields
                                kind (cond
                                       (str/starts-with? refname "refs/heads/")   "local"
                                       (str/starts-with? refname "refs/remotes/") "remote"
                                       (str/starts-with? refname "refs/tags/")    "tag"
                                       :else nil)]
                            (when (and kind (not (str/blank? short)))
                              {:name short :kind kind :current? (= marker "*")}))))))
              (str/split-lines (or text "")))]
    {:head head :detached? (boolean detached?) :branches branches}))
