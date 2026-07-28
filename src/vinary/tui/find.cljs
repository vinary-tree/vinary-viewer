(ns vinary.tui.find
  "Pure in-buffer search over rendered ANSI lines. Matching runs on the VISIBLE text (ANSI escapes stripped) so
   escape bytes never corrupt match offsets; highlighting then re-inserts reverse-video (`ESC[7m … ESC[27m`) at the
   raw indices for those visible columns, composing with any existing SGR colour (ESC[27m clears ONLY reverse, and
   each line is highlighted independently so no style leaks across rows). No terminal I/O — unit-testable."
  (:require [clojure.string :as str]
            [vinary.search.cursor :as cursor]
            [vinary.search.query :as query]
            [vinary.search.scan :as scan]))

(def ^:private ESC (str (char 27)))          ; the ESC byte, no literal control char in source
(def ^:private sgr-re #"\[[0-9;]*m|\]8;;[^]*(?:|\\)")

(defn strip [s] (str/replace (str s) sgr-re ""))

(defn search
  "Find every case-insensitive match of `q` across `lines`. Returns a vector of {:line :col :len} in VISIBLE
   (ANSI-stripped) column coordinates, in reading order.

   The per-line scan is `vinary.search.scan/scan-all`, shared with the GUI finder, which ran the identical
   loop over its flattened document buffer. The folding is `:strict` for the same reason it is there: a
   returned column is mapped back to a raw byte offset by `highlight`, so a fold that changed a line's
   length would re-insert the reverse-video escapes in the wrong places."
  [lines q]
  (if (str/blank? q)
    []
    (let [qf (query/fold-strict q)
          n  (count q)]
      (into []
            (comp (map-indexed
                   (fn [i line]
                     (map (fn [[s _]] {:line i :col s :len n})
                          (scan/scan-all (query/fold-strict (strip line)) qf))))
                  cat)
            lines))))

(defn- escape-ranges
  "[start end) byte ranges of every ANSI escape in `s` (via a stateful JS regex), so the highlighter can copy them
   verbatim without counting them as visible columns."
  [s]
  (let [re (js/RegExp. (.-source sgr-re) "g")]
    (loop [ranges []]
      (if-let [m (.exec re s)]
        (recur (conj ranges [(.-index m) (+ (.-index m) (.-length (aget m 0)))]))
        ranges))))

(defn highlight
  "Wrap each visible match span (`spans` = seq of {:col :len} in visible columns for THIS line) in reverse-video,
   preserving the line's existing ANSI. Tracks whether we are INSIDE a match and re-asserts `ESC[7m` after every
   interior escape — a document RESET (`ESC[0m`, emitted at each styled-run boundary) also clears reverse video, so
   a match spanning a style boundary would otherwise lose its highlight mid-span. Returns the line."
  [raw spans]
  (if (empty? spans)
    raw
    (let [starts (set (map :col spans))
          ends   (set (map (fn [{:keys [col len]}] (+ col len)) spans))
          esc-start (into {} (escape-ranges raw))
          n (count raw)]
      (loop [i 0, v 0, in? false, out ""]
        (cond
          (>= i n) (if in? (str out ESC "[27m") out)
          (contains? esc-start i)                       ; copy an ANSI escape verbatim; re-assert reverse if in a span
          (let [e (esc-start i)]
            (recur e v in? (str out (subs raw i e) (if in? (str ESC "[7m") ""))))
          :else
          (let [start? (contains? starts v)
                end?   (contains? ends v)
                in?'   (cond start? true end? false :else in?)
                pre    (if start? (str ESC "[7m") "")
                post   (if end? (str ESC "[27m") "")]
            (recur (inc i) (inc v) in?' (str out post pre (subs raw i (inc i))))))))))

;; ── find session state (matches + a cursor over them) ────────────────────────
(defn start [lines q]
  (let [ms (search lines q)] {:query q :matches ms :idx (when (seq ms) 0)}))

(defn current [find] (when-let [i (:idx find)] (nth (:matches find) i nil)))

;; the wrapping cursor is vinary.search.cursor, shared with the GUI finder. It returns nil for an empty
;; match list, so "step over nothing" is a decision made here (leave the session alone) rather than one
;; inherited from whichever copy of the arithmetic happened to be in scope.
(defn- step [find d]
  (if-let [idx (cursor/step (count (:matches find)) (:idx find) d)]
    (assoc find :idx idx)
    find))
(defn next-match [find] (step find 1))
(defn prev-match [find] (step find -1))

(defn line-spans
  "The match spans ({:col :len}) that fall on content line `i`, for `highlight`."
  [find i]
  (->> (:matches find) (filter #(= i (:line %))) (mapv #(select-keys % [:col :len]))))
