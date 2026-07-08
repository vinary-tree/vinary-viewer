(ns vinary.ir.frontend.log-stream
  "Streaming log front-end: line batches → :record IR blocks, emitted INCREMENTALLY with bounded memory. A
   record = a header line + following continuation lines, up to (but not including) the next header AT BRACE
   DEPTH 0 — multi-line JSON/braced entries stay whole because the depth comes from the WPDA brace grammar
   (vinary.ir.grammar.log), which keeps a header-looking line inside `{ … }` from splitting the record.

   `feed` emits ONLY the records completed by its batch; the still-open record is retained in the returned
   parser until the next depth-0 header or `finish`. Working set = the open record's lines + the (single) WPDA
   config + a record counter (one int) — TRULY bounded regardless of document length. Pure + DOM-free (a
   StreamParser); the DOM append happens later in vinary.stream.sink."
  (:require [vinary.ir.node :as node]
            [vinary.ir.grammar.log :as glog]
            [vinary.stream.protocol :as proto]))

;; A continuation line: indented, a lone closing brace/bracket, a stack frame, or an "… N more" line — i.e. a
;; line that visibly belongs to the record above it rather than starting a new one.
(def ^:private continuation-re #"^(\s|\}|\]|at\s|Caused by\b|\.\.\.\s\d+\smore)")

(defn continuation-line? [line] (boolean (re-find continuation-re (or line ""))))

(defn net-brace
  "A line's net brace delta: (# of '{') − (# of '}'). Coarse (ignores braces inside strings) but sufficient to
   keep multi-line JSON records whole."
  [line]
  (- (count (re-seq #"\{" (or line ""))) (count (re-seq #"\}" (or line "")))))

(defn- line-node
  "One log line → a div.vv-log-line ELEMENT wrapping a :text leaf. The text MUST live in a :text-kind child,
   not the element's :text field: the IR→HAST lowering only emits a leaf's text for :text nodes, so a bare
   :line leaf would render as an empty <div> (the line text would vanish)."
  [s]
  (node/node :line [(node/leaf :text s)] {:tag "div" :attrs {"className" ["vv-log-line"]}}))

(defn- record-node
  "Build a :record IR block from its lines with a unique, stable anchor id `vv-log-record-<n>`, where `n` is the
   record's 0-based index in the stream. A monotonic counter — NOT a full-text slug — because a streaming log can
   run to millions of records: a slug/`seen`-map anchor (rehype-slug-style) would retain every record's slug and
   grow the working set unbounded with the document, whereas the index costs one int. Log-record ids are only
   scroll/find/TOC-jump targets read back off the node (never markdown `[](#id)` link targets), so they need
   uniqueness + stability, not rehype-slug parity. Each line lowers to a div.vv-log-line; the record to a
   div.vv-log-record carrying the id."
  [lines n]
  (let [kids (mapv line-node lines)
        id   (str "vv-log-record-" n)]
    (node/node :record kids {:tag "div" :attrs {"className" ["vv-log-record"] "id" id}
                             :id id :role :log-record})))

(defrecord LogStreamParser [frontier open n]
  proto/StreamParser
  (feed [_ lines]
    (loop [ls (seq lines), fr frontier, open open, n n, blocks []]
      (if (nil? ls)
        {:parser (LogStreamParser. fr open n) :blocks blocks}   ; ctor form: ->LogStreamParser isn't declared yet inside its own methods
        (let [line      (first ls)
              at-depth0? (zero? (glog/depth fr))        ; depth BEFORE this line's braces are applied
              boundary? (and at-depth0? (not (continuation-line? line)) (seq open))
              [open n blocks] (if boundary?
                                [[line] (inc n) (conj blocks (record-node open n))]   ; emitted record keeps the OLD index
                                [(conj open line) n blocks])]
          (recur (next ls) (glog/advance-net fr (net-brace line)) open n blocks)))))
  (finish [_] {:blocks (if (seq open) [(record-node open n)] [])}))

(defn parser
  "A fresh streaming log parser (empty open record, brace depth 0, record counter 0)."
  []
  (->LogStreamParser (glog/initial-frontier) [] 0))
