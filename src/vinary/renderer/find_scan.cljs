(ns vinary.renderer.find-scan
  "Pure, DOM-free core of in-page find: flatten a token stream into one searchable buffer, scan it for
   matches, and map a buffer index back to the text node and offset it came from.

   WHY THE BUFFER EXISTS. The previous implementation searched each text node in isolation, so a match
   could never cross a node boundary. Rendered markup breaks text at every inline element — `<code>`,
   `<a>`, `<em>`, syntax-highlight tokens — and pdf.js emits ONE `<span>` per text run, so in a PDF almost
   no multi-word query matched at all. Flattening first removes the restriction entirely; the CSS Custom
   Highlight API already supports Ranges that span nodes, so nothing downstream had to change.

   The second thing the buffer buys is whitespace normalization. Markdown source wrapped across lines
   becomes `<p>the quick\\nbrown fox</p>`; searching for `quick brown` used to fail on the newline. The
   buffer collapses every whitespace run to a single space, so what you see is what you can search for.

   TOKENS. The DOM shell (vinary.renderer.find) walks the document and emits:

     {:kind :text :id <int> :s <string>}   a text node's raw data; :id indexes the shell's node table
     {:kind :soft}                         an inline-block / <br> boundary → one space
     {:kind :hard}                         a block boundary → a newline

   Separators are what stop a query matching across a paragraph break. `normalize-query` trims, so a
   non-blank query never begins or ends with whitespace, and it can never contain a newline — therefore a
   match can span a :soft separator (a wrapped line, a PDF line break) but never a :hard one (a paragraph,
   a table cell, a diff column).

   SEGMENTS. `build` returns {:text buffer :segs [b0 n0 o0 len0  b1 n1 o1 len1 …]} — flat quads, ascending
   in b, where buffer indices [b, b+len) map to node n at offsets [o, o+len) with a constant delta. A new
   quad is only started where contiguity breaks, so for ordinary prose |segs| ≈ the number of text nodes,
   not the number of characters. The explicit `len` is what lets `locate` reject an index that fell on a
   synthetic separator rather than silently attributing it to the preceding node."
  (:require [clojure.string :as str]))

(defn- ws? [c]
  (or (= c " ") (= c "\n") (= c "\t") (= c "\r") (= c "\f") (= c " ")))

(defn safe-lower
  "Lower-case `s` WITHOUT changing its length.

   `String.prototype.toLowerCase` is not length-preserving in JavaScript: \"İ\" (U+0130) lowercases to two
   UTF-16 units. Lower-casing a whole chunk would therefore desynchronise every buffer index after such a
   character from its node offset, silently mis-highlighting the rest of the document. So we lower per code
   unit and keep the original wherever the result is not exactly one unit."
  [s]
  (let [n (count s)
        out (js/Array. n)]
    (dotimes [i n]
      (let [c (.charAt s i)
            l (.toLowerCase c)]
        (aset out i (if (= 1 (.-length l)) l c))))
    (.join out "")))

(defn normalize-query
  "Lower-case, collapse whitespace runs to a single space, and trim. Returns \"\" for nil/blank input,
   which every caller treats as 'clear'."
  [q]
  (if (string? q)
    (-> (safe-lower q)
        (str/replace #"\s+" " ")
        (str/trim))
    ""))

(defn build
  "Fold a token stream into {:text <buffer> :segs <flat triples>}.

   The buffer is lower-cased (length-preservingly) and whitespace-collapsed; separators are emitted only
   BETWEEN real text, never at the ends, so a normalized query can never match across a paragraph break."
  [tokens]
  (let [out  (array)                    ; string chunks, joined once
        segs (array)                    ; flat [b n o len] quads
        st   #js {:pos 0                ; |buffer| so far
                  :pending nil          ; :soft | :hard — a separator not yet emitted
                  :started false        ; has any real text been emitted?
                  :node nil             ; contiguity: the (node, offset) the next char must have
                  :off 0}]
    (letfn [(emit! [chunk id off n-consumed]
              ;; `chunk` is what goes into the BUFFER; `n-consumed` is how much of the node it covers.
              ;; They differ only for a normalized lone whitespace (a node's "\n" becomes a buffer " "),
              ;; where both are length 1 — so the buffer stays index-for-index with the node.
              (if (and (= id (.-node st)) (= off (.-off st)))
                (let [last-len (- (.-length segs) 1)]         ; extend the open quad
                  (aset segs last-len (+ (aget segs last-len) n-consumed)))
                (do (.push segs (.-pos st)) (.push segs id) (.push segs off) (.push segs n-consumed)))
              (.push out (safe-lower chunk))
              (set! (.-pos st) (+ (.-pos st) (count chunk)))
              (set! (.-node st) id)
              (set! (.-off st) (+ off n-consumed)))
            (flush-separator! []
              (when-let [p (.-pending st)]
                (.push out (if (= p :hard) "\n" " "))
                (set! (.-pos st) (inc (.-pos st)))
                (set! (.-node st) nil)          ; a synthetic char belongs to no node
                (set! (.-pending st) nil)))
            (mark! [kind]
              ;; :hard dominates :soft regardless of arrival order
              (when (.-started st)
                (when (or (= kind :hard) (nil? (.-pending st)))
                  (set! (.-pending st) kind))))]
      (doseq [t tokens]
        (case (:kind t)
          :hard (mark! :hard)
          :soft (mark! :soft)
          :text
          (let [s (or (:s t) "")
                id (:id t)
                n (count s)]
            (loop [i 0]
              (when (< i n)
                (if (ws? (.charAt s i))
                  (let [j (loop [j i] (if (and (< j n) (ws? (.charAt s j))) (recur (inc j)) j))]
                    (when (.-started st)
                      (if (and (= 1 (- j i)) (nil? (.-pending st)))
                        ;; a LONE whitespace character inside a node (the overwhelmingly common case — one
                        ;; per source line wrap): emit a space that still maps to this node's offset, so
                        ;; the run stays contiguous and no new quad is needed. That is what keeps |segs|
                        ;; proportional to nodes rather than to words.
                        (emit! " " id i 1)
                        (mark! :soft)))
                    (recur j))
                  (let [j (loop [j i] (if (and (< j n) (not (ws? (.charAt s j)))) (recur (inc j)) j))]
                    (flush-separator!)
                    (emit! (.substring s i j) id i (- j i))
                    (set! (.-started st) true)
                    (recur j)))))))
        nil))
    {:text (.join out "") :segs segs}))

(defn scan
  "All non-overlapping, case-insensitive matches of the NORMALIZED query `q` in the buffer `text`, as
   [start end) index pairs in buffer order. Returns [] for a blank query."
  [text q]
  (if (or (nil? text) (nil? q) (= "" q))
    []
    (let [ql (count q)
          step (max 1 ql)]
      (loop [from 0 acc (transient [])]
        (let [i (.indexOf text q from)]
          (if (neg? i)
            (persistent! acc)
            (recur (+ i step) (conj! acc [i (+ i ql)]))))))))

(defn locate
  "Map buffer index `i` back to {:id <node id> :off <offset in that node>}, or nil when `i` falls on a
   synthetic separator or outside every segment. Binary search over the flat quads."
  [^js segs i]
  (let [n (/ (.-length segs) 4)]
    (when (and (pos? n) (number? i) (>= i 0))
      (loop [lo 0 hi n found -1]
        (if (< lo hi)
          (let [mid (bit-or (/ (+ lo hi) 2) 0)
                b   (aget segs (* mid 4))]
            (if (<= b i) (recur (inc mid) hi mid) (recur lo mid found)))
          (when (>= found 0)
            (let [base (* found 4)
                  b    (aget segs base)
                  id   (aget segs (+ base 1))
                  o    (aget segs (+ base 2))
                  len  (aget segs (+ base 3))]
              ;; the quad's explicit extent is what rejects an index sitting on a synthetic separator —
              ;; those are emitted BETWEEN quads and belong to no node
              (when (< (- i b) len)
                {:id id :off (+ o (- i b))}))))))))

(defn match-endpoints
  "The (node, offset) pair for each end of the match `[s e)`.

   The end is derived from the LAST character, not from `e`: `e` can sit on a synthetic separator or one
   past the buffer, neither of which maps to a node. Returns nil if either end fails to resolve."
  [segs [s e]]
  (let [a (locate segs s)
        b (locate segs (dec e))]
    (when (and a b)
      {:start a :end {:id (:id b) :off (inc (:off b))}})))

(defn step-idx
  "Advance a 0-based cursor over `n` matches by `dir` (+1/-1), wrapping at both ends. nil when n = 0."
  [n idx dir]
  (when (pos? n)
    (mod (+ (or idx 0) dir) n)))

(defn clamp-idx
  "Keep a cursor in range after a re-collect changed the match count. nil when nothing matches."
  [n idx]
  (when (pos? n)
    (min (max 0 (or idx 0)) (dec n))))
