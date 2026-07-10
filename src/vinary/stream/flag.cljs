(ns vinary.stream.flag
  "The document-streaming feature gate. Streaming trades total throughput for latency + bounded memory, so it
   is worthwhile ONLY for large documents — small/medium docs keep the faster whole-document (batch) render,
   whose output stays byte-identical. `enabled?` therefore requires: the compile-time/runtime flag on, the
   kind streamable, AND the size at or above a per-kind byte threshold. Default off until each format's
   parity/perf gate passes (mirrors the retired vinary.ir.flag pattern). Pure + DOM-free.")

;; Default ON as of Phase 4: streaming is byte-parity-verified (markdown/org/PDF-reflow) and bounded-memory
;; (logs), so large documents stream by default (progressive, non-blocking). The persisted `:stream?` setting
;; overrides this (Preferences ▸ Documents ▸ "Stream large documents"), and only docs at/above the per-kind
;; threshold ever stream — small/medium docs always take the identical, faster batch path.
;;
;; The size comes from the `vv:content` payload's `:meta {:size}`. The main-process `:text` route (markdown, org,
;; source, diagram) once omitted `:meta` entirely, so `enabled?` compared 0 against the threshold and NOTHING on
;; that route ever streamed — see enabled-requires-a-known-size in stream/flag_test.
(goog-define ^boolean stream-default true)

;; Per-kind size thresholds (bytes). Below the threshold a document renders whole (batch); at/above it streams.
;; Log aligns with the existing large-log boundary (content_service.js flips to paged at ~5 MB), so large logs
;; STREAM instead of paging. A threshold of 0 means "always stream when the flag is on" (e.g. PDF reflow).
(def thresholds
  {"log"      5242880      ; 5 MiB
   "text"     5242880
   "markdown"  262144      ; 256 KiB
   "org"       262144      ; 256 KiB — same progressive engine, same prose economics as markdown
   "latex"     262144      ; 256 KiB — same progressive-paint engine (whole-doc parse, children committed)
   "pdf"           0})

(def ^:private streamable-kinds #{"log" "text" "markdown" "org" "latex" "pdf"})

(defn streamable? [kind] (contains? streamable-kinds kind))

;; Kinds whose streaming front-end is actually implemented (grows as each phase lands). `enabled?` requires
;; this so a large doc of an as-yet-unimplemented kind stays on the batch path rather than streaming with the
;; wrong parser. Phase 1: logs/text (bounded byte-stream). Phase 2: markdown (progressive block-commit).
;; Phase 3: org — the progressive engine is format-agnostic (it commits IR children, not markdown), so org
;; joins it by supplying its own block provider (markdown/org-stream-blocks); no new engine. LaTeX (.tex) joins
;; the same way via markdown/latex-stream-blocks — a whole-document parse whose IR children are committed
;; progressively (the win is a non-blocking paint, not bounded parsing).
(def implemented-kinds #{"log" "text" "markdown" "org" "latex"})

(defn implemented? [kind] (contains? implemented-kinds kind))

(defn flag-on?
  "The effective on/off, independent of size: the persisted setting overrides the compile-time default."
  [setting] (if (some? setting) (boolean setting) stream-default))

(defn enabled?
  "Should THIS document stream NOW? Flag on AND kind streamable AND its streaming front-end implemented AND
   size ≥ the kind's threshold."
  [kind size setting]
  (and (flag-on? setting)
       (streamable? kind)
       (implemented? kind)
       (>= (or size 0) (get thresholds kind js/Infinity))))
