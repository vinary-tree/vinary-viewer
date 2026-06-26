(ns vinary.ui.preview-context
  "Pure helpers for Markdown preview context menus: term extraction and source-location formatting.")

(def ^:private trim-punctuation
  #{\. \, \; \: \! \? \( \) \[ \] \{ \} \< \> \" \' \`})

(defn- whitespace? [ch]
  (boolean (and ch (re-matches #"\s" (str ch)))))

(defn- trim-token-bounds [s start end]
  (let [n (count s)]
    (loop [lo (max 0 (min n start))
           hi (max 0 (min n end))]
      (cond
        (>= lo hi) nil
        (contains? trim-punctuation (nth s lo)) (recur (inc lo) hi)
        (contains? trim-punctuation (nth s (dec hi))) (recur lo (dec hi))
        :else {:start lo :end hi :text (subs s lo hi)}))))

(defn term-at
  "Return the contiguous non-whitespace token at `offset`, trimmed of wrapping punctuation.
   Offsets are DOM caret offsets into the rendered text node."
  [s offset]
  (when (string? s)
    (let [n (count s)
          i (max 0 (min n (or offset 0)))
          pos (cond
                (and (< i n) (not (whitespace? (nth s i)))) i
                (and (= i n) (pos? i) (not (whitespace? (nth s (dec i))))) (dec i)
                :else nil)]
      (when pos
        (let [start (loop [j pos]
                      (if (and (pos? j) (not (whitespace? (nth s (dec j)))))
                        (recur (dec j))
                        j))
              end (loop [j (inc pos)]
                    (if (and (< j n) (not (whitespace? (nth s j))))
                      (recur (inc j))
                      j))]
          (trim-token-bounds s start end))))))

(defn offset->line-column
  "Convert a zero-based source offset to a one-based {:line :column} map."
  [source offset]
  (when (and (string? source) (number? offset))
    (let [n      (count source)
          offset (max 0 (min n offset))
          prefix (subs source 0 offset)
          line   (inc (count (re-seq #"\n" prefix)))
          last-nl (.lastIndexOf prefix "\n")]
      {:line line :column (- offset last-nl)})))

(defn location-string [path {:keys [line column]}]
  (when (and (seq path) line column)
    (str path ":" line ":" column)))

(defn parse-int-or-nil [x]
  (let [n (js/parseInt (str x) 10)]
    (when-not (js/isNaN n) n)))

(defn best-source-offset
  "Find `needle` inside source[start,end), falling back to fallback-offset when no exact match exists."
  [source start end needle fallback-offset]
  (let [start (or start fallback-offset)
        end   (or end start)]
    (if (and (string? source) (seq needle) (number? start) (number? end) (<= 0 start end (count source)))
      (let [haystack (subs source start end)
            idx      (.indexOf haystack needle)]
        (if (neg? idx) fallback-offset (+ start idx)))
      fallback-offset)))
