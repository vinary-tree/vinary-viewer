(ns vinary.git.blame
  "Pure `git blame --line-porcelain` parsing (ADR-0040). Electron/DOM/fs-free — vinary.main.git
   executes the blame and calls this; the renderer receives coalesced HUNKS, never raw porcelain
   (a 10k-line file's porcelain is ~5 MB; its hunks are a few KB).

   THE FORMAT. Each blamed line opens with `<40-hex> <orig-line> <final-line>[ <group-count>]`;
   metadata key/value lines follow the FIRST line of a commit's first group (author, author-mail,
   author-time, summary, boundary, …), and a TAB-prefixed content line closes the record. The
   group count is ignored on purpose: records re-coalesce here by hash + consecutive line numbers,
   which also makes plain `--porcelain` input (metadata once per commit) parse identically, and a
   per-hash metadata cache fills records that carry none. Unknown keys are skipped
   (forward-compatible), CRLF is tolerated, and the all-zero hash marks an uncommitted line."
  (:require [clojure.string :as str]))

(def zero-hash "0000000000000000000000000000000000000000")

(def ^:private header-re #"^([0-9a-f]{40}) (\d+) (\d+)( \d+)?$")

(defn- apply-meta [m k v]
  (let [m (or m {})]
    (case k
      "author"      (assoc m :author-name v)
      "author-mail" (assoc m :author-email (str/replace v #"^<|>$" ""))
      "author-time" (assoc m :author-date (* 1000 (js/parseInt v 10)))   ; seconds → epoch ms
      "summary"     (assoc m :summary v)
      "boundary"    (assoc m :boundary true)
      m)))

(defn parse-line-porcelain
  "Porcelain text → {:hunks [{:hash :orig-line :final-line :count :author-name :author-email
   :author-date :summary :boundary? :uncommitted} …]} with consecutive same-hash lines coalesced.
   Hunks are emitted in :final-line order (the input's order), which is what hunk-for-line's
   binary search relies on."
  [s]
  (loop [lines (seq (str/split-lines (or s "")))
         metas {}          ; hash → metadata (cached across groups)
         cur   nil         ; the open per-line record {:hash :orig-line :final-line}
         recs  []]
    (if-let [raw (first lines)]
      (let [line (str/replace raw #"\r$" "")]
        (cond
          ;; the TAB content line closes the record (content itself is not our business)
          (str/starts-with? line "\t")
          (recur (next lines) metas nil (if cur (conj recs cur) recs))

          ;; a header opens the next record
          (re-matches header-re line)
          (let [[_ h o f _] (re-matches header-re line)]
            (recur (next lines) metas
                   {:hash h
                    :orig-line  (js/parseInt o 10)
                    :final-line (js/parseInt f 10)}
                   recs))

          ;; a metadata line annotates the open record's commit
          (some? cur)
          (let [sp (.indexOf line " ")
                k  (if (neg? sp) line (subs line 0 sp))
                v  (if (neg? sp) "" (subs line (inc sp)))]
            (recur (next lines) (update metas (:hash cur) apply-meta k v) cur recs))

          :else (recur (next lines) metas cur recs)))
      {:hunks
       (reduce (fn [hs {:keys [hash orig-line final-line]}]
                 (let [prev (peek hs)]
                   (if (and prev
                            (= (:hash prev) hash)
                            (= final-line (+ (:final-line prev) (:count prev)))
                            (= orig-line  (+ (:orig-line prev) (:count prev))))
                     (conj (pop hs) (update prev :count inc))
                     (conj hs (merge {:hash hash :orig-line orig-line :final-line final-line
                                      :count 1 :uncommitted (= hash zero-hash)}
                                     (get metas hash))))))
               []
               recs)})))

(defn hunk-for-line
  "The hunk covering 1-based line `n` (binary search over the :final-line-ordered hunks), or nil."
  [hunks n]
  (let [hunks (vec hunks)]
    (loop [lo 0
           hi (dec (count hunks))]
      (when (<= lo hi)
        (let [mid (quot (+ lo hi) 2)
              {:keys [final-line count] :as h} (nth hunks mid)]
          (cond
            (< n final-line)            (recur lo (dec mid))
            (>= n (+ final-line count)) (recur (inc mid) hi)
            :else                       h))))))

(defn rel-date
  "Compact age for a gutter chip: epoch-ms → \"now\"/\"5m\"/\"3h\"/\"6d\", falling to the ISO date
   for anything older than a month. `now-ms` is injected so the fn stays a pure value → value map."
  [ms now-ms]
  (if-not (number? ms)
    ""
    (let [s (quot (- now-ms ms) 1000)]
      (cond
        (< s 60)           "now"
        (< s 3600)         (str (quot s 60) "m")
        (< s 86400)        (str (quot s 3600) "h")
        (< s (* 30 86400)) (str (quot s 86400) "d")
        :else              (subs (.toISOString (js/Date. ms)) 0 10)))))
