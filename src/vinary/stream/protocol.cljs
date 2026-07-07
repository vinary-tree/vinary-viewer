(ns vinary.stream.protocol
  "The streaming front-end contract. A StreamParser turns a document that arrives as a sequence of batches
   (log lines | markdown text chunks | PDF page items) into a sequence of completed common-IR blocks
   (vinary.ir.node/Node), incrementally, with a BOUNDED working set — it only ever holds the currently-open
   block plus (for WPDA-driven segmentation) the decoder frontier, never the whole document.

   `feed`/`finish` are value-returning (the parser is threaded functionally, no globals), so every parser is
   pure and node-testable with synthetic batches. They return ONLY the blocks completed by this call; an
   in-progress block (e.g. an open multi-line log record) is retained in the returned parser until a later
   batch closes it or `finish` flushes it.")

(defprotocol StreamParser
  (feed   [parser batch]
    "Consume one input batch. Returns {:parser parser' :blocks [ir-node …]} — the blocks COMPLETED by this
     batch (possibly empty). Internal state stays bounded (open block + decoder frontier).")
  (finish [parser]
    "End of input. Returns {:blocks [ir-node …]} flushing any still-open block."))

(defn drain
  "Convenience for tests / whole-document use: feed every batch then finish, returning the full block seq.
   (Streaming callers use feed/finish incrementally instead so memory stays bounded.)"
  [parser batches]
  (let [[p blocks] (reduce (fn [[p acc] batch]
                             (let [{:keys [parser blocks]} (feed p batch)]
                               [parser (into acc blocks)]))
                           [parser []]
                           batches)]
    (into blocks (:blocks (finish p)))))
