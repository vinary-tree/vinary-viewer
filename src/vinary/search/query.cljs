(ns vinary.search.query
  "Case folding and query normalisation, shared by every search surface in the app.

   WHY THIS IS ITS OWN NAMESPACE. There were two case-folders in the codebase and the difference between
   them was load-bearing but undocumented. In-page find used a length-PRESERVING fold, because the buffer
   it searches is indexed back into DOM text nodes and a fold that changed a string's length would
   silently mis-highlight everything after the offending character. Everywhere else — the file-tree
   filter, the command palette, the terminal finder, URI completion — reached for `str/lower-case`,
   which is correct there only because nothing maps an index back to anything.

   That distinction should be a choice a caller makes deliberately, not an accident of which file the
   code happens to live in. So there are two strategies with names:

     :strict   length-preserving; REQUIRED when a buffer index maps back to a source position
     :simple   `String.prototype.toLowerCase`; correct when it does not, and fractionally cheaper

   `\"İ\"` (U+0130) is the canonical witness: it lower-cases to TWO UTF-16 units."
  (:require [clojure.string :as str]))

;; Presence of any code unit outside ASCII. No `g` flag, so the instance carries no lastIndex and is safe
;; to share across calls.
(def ^:private non-ascii-re (js/RegExp. "[^\\u0000-\\u007F]"))

(defn ascii?
  "Is every code unit of `s` in U+0000–U+007F? Over that range `toLowerCase` is exactly A–Z → a–z with
   everything else fixed, hence 1:1 — which is what lets `:strict` take the native path."
  [s]
  (not (.test non-ascii-re s)))

(defn fold-strict
  "Lower-case `s` WITHOUT changing its length.

   `String.prototype.toLowerCase` is not length-preserving in JavaScript: \"İ\" (U+0130) lowercases to two
   UTF-16 units. Folding a whole chunk would therefore desynchronise every buffer index after such a
   character from its source offset, silently mis-highlighting the rest of the document. So we fold per
   code unit and keep the original wherever the result is not exactly one unit.

   ASCII FAST PATH: one native regex test buys the whole chunk out of the per-code-unit loop, which is the
   dominant per-character cost of flattening a document (docs/scientific/10)."
  [s]
  (if (ascii? s)
    (.toLowerCase s)
    (let [n (count s)
          out (js/Array. n)]
      (dotimes [i n]
        (let [c (.charAt s i)
              l (.toLowerCase c)]
          (aset out i (if (= 1 (.-length l)) l c))))
      (.join out ""))))

(defn fold-simple
  "Lower-case `s`, length not guaranteed. Correct wherever no index is mapped back to a source position."
  [s]
  (str/lower-case (str s)))

(defn fold
  "Case-fold `s` under `strategy` — :strict (length-preserving) or :simple. Defaults to :strict, because
   the failure mode of the wrong choice is silent corruption in one direction and a negligible cost in the
   other."
  ([s] (fold s :strict))
  ([s strategy]
   (if (= :simple strategy) (fold-simple s) (fold-strict s))))

(defn normalize
  "Fold, collapse whitespace runs to a single space, and trim. Returns \"\" for nil/blank input, which
   every caller treats as 'clear'.

   Collapsing matters for more than tidiness: Markdown source wrapped across lines renders as
   `<p>the quick\\nbrown fox</p>`, and a query normalised the same way as the buffer is what lets
   `quick brown` match it. Trimming is what stops a query from ever containing a newline, and therefore
   from matching across a block boundary."
  ([q] (normalize q :strict))
  ([q strategy]
   (if (string? q)
     (-> (fold q strategy)
         (str/replace #"\s+" " ")
         (str/trim))
     "")))
