# 0018 — A document-streaming pipeline (bounded-memory, WPDA-segmented)

- **Status:** Accepted — implemented (v0.3.0), **default-on** for large documents. Logs/text stream as
  bounded WPDA byte-streams (Phase 1); Markdown (Phase 2) and PDF-reflow (Phase 3) as byte-parity
  progressive block-commits; capabilities hardened + default-on (Phase 4); source stays batch — CodeMirror 6
  already viewport-virtualizes (Phase 5, spike outcome).
- **Date:** 2026-07-07
- **Deciders:** vinary-viewer maintainers

> **Two engines, one spine.** As implemented, streaming has *two* modes behind the same scheduler + sink:
> a **bounded byte-stream** (logs/text — bytes are NOT in renderer memory, pulled from the main session and
> WPDA-segmented, so the working set is genuinely bounded), and a **progressive block-commit** (markdown, org,
> PDF-reflow — the whole source/text is already in memory, and CommonMark's document-global constructs
> [forward reference definitions, footnotes, whole-document slug dedup] make a byte-parity *bounded-parse*
> infeasible, so the batch renderer runs ONCE and its top-level blocks are committed across idle frames). The
> block-commit win is a **non-blocking progressive paint** (pacing the expensive post-passes + DOM writes)
> that never holds the whole HTML string; byte-parity is guaranteed because it *is* the batch render, in
> pieces. See [theory/09](../theory/09-document-streaming-and-the-wpda.md).

## Context

Every document flowed through the app as a **whole unit**. Main read the entire file, sent one `vv:content`
envelope, `:content/received` created one DataScript `:doc`, a render pass produced the whole `:doc/html`, and
`markdown-body` wrote it into the DOM with a single **whole-innerHTML replace** (see
[ADR-0003](0003-ref-innerHTML-no-vdom-body.md)). The [common document IR](0017-common-document-ir.md) carried a
full weighted-automata machinery — semiring core, weighted tree transducer, **WPDA + streaming decoder**,
Earley-over-lattice + packed forest — but the streaming decoder was **dead-wired**: required only by its own
tests, never by a front-end. The IR was built to *stream and rank* ambiguous segmentation in bounded memory,
yet nothing streamed.

The whole-unit model has two structural costs that grow with file size:

1. **Memory is unbounded.** The whole file, its whole parse, and its whole rendered HTML coexist in memory.
   A multi-hundred-MiB log or a giant Markdown file freezes or OOMs the renderer. The existing ad-hoc
   mitigations — the $\approx 5$ MiB log/table **paging** boundary in `content_service.js`, the PDF
   `IntersectionObserver` window — are per-format bandages, not a general answer, and paging shows only a
   *slice* of the document rather than the whole thing.
2. **First paint waits for the whole document.** Nothing renders until the entire file is parsed and lowered,
   so a large file is unresponsive for the full parse latency.

The maintainer's framing: treat a document as a **bounded-memory incremental stream** — consume
tokens/lines/runs, drive the **WPDA streaming decoder** for the ambiguous *segmentation* step (multi-line log
records; later PDF run→line→block), and emit IR + DOM **incrementally**, never holding the whole parse in
memory. The weighted core then lets the app *stream and rank* the best segmentation (a beam) in bounded memory.

An honest counterweight sets the policy: **streaming is not less total work.** It adds per-block overhead
(lower + post-passes + append + IPC pulls + scheduling), so wall-clock to *fully* render is not lower; it trades
throughput for **latency + bounded memory**. Streaming a small README would be pure overhead.

## Decision

Add a **streaming spine alongside the untouched batch spine**, selected per document by a **size-thresholded
flag**. The pipeline (illustrated in [seq-document-streaming](../diagrams/seq-document-streaming.svg),
detailed in [theory/09](../theory/09-document-streaming-and-the-wpda.md)):

```
main session (createReadStream/readline, credit-1 backpressure)
  → transport pull-client (double-buffered)
  → ir.frontend.<fmt>-stream (StreamParser: feed → [ir-block], WPDA decode for segmentation)
  → stream.sink (lower block → apply-posts → insertAdjacentHTML "beforeend" + "\n")
  → stream.scheduler (requestIdleCallback loop, <= 8 ms/frame, pause/cancel)
```

The concrete sub-decisions:

1. **Alongside, not replacing — parity by construction.** `content-view` selects the streaming branch **only**
   when the flag is on *and* the doc is a streamable *and implemented* kind *and* its size clears the per-kind
   threshold; otherwise
   the exact current batch path runs, byte-for-byte. Small/medium documents therefore render identically to
   before. `stream.flag/enabled?` is the single gate (mirroring the retired `ir.flag` pattern); the per-kind
   threshold aligns logs with the existing $\approx 5$ MiB paging boundary, so large logs **stream** instead of paging.

2. **A universal pull-cursor transport (credit ~1 = backpressure).** `stream.transport` opens a main-process
   *session* over `vv:stream-{open,pull,close}` and pulls one batch at a time; the outstanding-pull credit of
   one **is** the backpressure. It double-buffers (prefetch the next batch while the current one renders) so
   main-process read latency overlaps renderer work. `content_service.js` holds a session registry
   (`createReadStream` + optional `gunzip`, `readline` for line batches, paused at the batch cap), with an
   idle-GC sweep. log/text → readline `:lines`; Markdown → `createStream` `:bytes` (Phase 2); PDF →
   in-renderer `streamTextContent` (Phase 3, no IPC).

3. **Wire the WPDA into segmentation.** `ir.grammar.log` is a WPDA whose pushdown **stack tracks brace depth**,
   so a JSON/braced log entry stays **one** record — a header-*looking* line inside `{ … }` cannot split it.
   That is precisely the context-free part a flat (finite-state) counter cannot express; the pushdown earns its
   keep on the golden balanced-brackets shape. `ir.frontend.log-stream` feeds each line's net-brace delta
   through the streaming decoder (`decode/advance-step` = advance + beam-prune one symbol) and commits a record
   only at a **depth-0 header**. This is the first real use of `decode`; PDF adds `ir.grammar.pdf` +
   `earley`/`forest` per page in Phase 3.

4. **An append-mode render sink.** `stream.sink` lowers each completed IR block with the existing
   `ir/backend/html/lower`, runs the **identical** `markdown/apply-posts` string passes (MathJax → Mermaid →
   syntax; unchanged), and `insertAdjacentHTML "beforeend"`s the batch with a trailing `"\n"` — re-emitting the
   inter-block text nodes `remark-rehype` produces, so streamed HTML matches batch HTML. Appends are serialised
   through a per-controller promise queue so async post-passes still land in document order, and each append is
   cancel-aware (skipped if the stream was torn down mid-pass).

5. **The render is never snapshotted.** On completion the app deliberately does **not** copy the DOM back into
   `:doc/html`. Snapshotting would hold the whole rendered document in memory — exactly what streaming exists to
   avoid — so a re-mount (tab-switch back, live-refresh) simply **re-streams** from the top, which stays
   bounded for arbitrarily large files. *(This supersedes the initial plan's "snapshot on done for instant
   rehydration": bounded memory is the load-bearing goal and wins the trade.)*

6. **The sanitizer is per-block, and that is sound.** Each block is lowered through the same single
   GitHub-allowlist sanitizer the batch path uses (see [ADR-0017](0017-common-document-ir.md)). The allowlist
   is **context-free** — a node is safe or unsafe independent of its siblings — so applying it per block is
   identical to applying it to the whole document. See the streaming note in
   [security/threat-model](../security/threat-model.md).

Rollout was **phased, each phase green behind the flag**, and is now complete:

- **Phase 0** — scaffolding (flag, protocol, transport, `advance-step`, main sessions), inert.
- **Phase 1** — **logs/text**, the bounded-byte-stream POC (WPDA record grammar → append sink → paced DOM).
- **Phase 2** — **Markdown** as a byte-parity **progressive block-commit** (not micromark-incremental: the
  batch `base-pipeline` runs once — full document context, so slug dedup / forward reference definitions /
  footnotes / source positions are all correct by construction — and the resulting IR document's children are
  committed across idle frames; the sink joins them verbatim, so `concat(map lower children)` *is*
  `lower(whole-document)`).
- **Phase 3** — **PDF-reflow** as the same progressive block-commit (its `reflow-ir` also needs a
  document-global median line-height). The fixed-layout **canvas view is untouched** — it already windows pages
  via `IntersectionObserver` + `release-canvas!`, the PDF's genuine bounded-memory stream.
- **Phase 4** — capabilities hardened + **default-on**: find-materialize (drain-before-search, the PDF
  `ensure-all-text!` analog), live-refresh scroll re-anchor, a **Preferences ▸ Documents** toggle,
  `stream-default` flipped **true** (large docs stream by default; the setting overrides), and a **windowed
  DOM**. The window is done with `content-visibility: auto` (+ `contain-intrinsic-size: auto 48px`) on the
  streamed body's top-level blocks (`.markdown-body.vv-streamed > *`): the browser **skips layout + paint for
  off-screen blocks** — bounding the render cost so scrolling and off-screen appends stay smooth on arbitrarily
  large documents — while every node **stays in the DOM**, so find, the scroll-spy, and text selection keep
  working over the whole document. This was chosen over `release-canvas!`-style node *removal* deliberately:
  removal would break those three capabilities (they walk the live DOM) and, for logs, force re-streaming
  dropped byte ranges; `content-visibility` bounds the *expensive* part (render) without that cost.
- **Phase 5** — **source stays batch** (spike outcome): CodeMirror 6 already **viewport-virtualizes** its
  rendering (bounded DOM for arbitrarily large files) and the tree-sitter outline parse is already async, so
  block-streaming a live editor would only fight its own virtualization — there is no clean, beneficial path.

`stream.flag/implemented?` holds the set of kinds whose streaming front-end exists (`log`/`text`/`markdown`),
so a large doc of an as-yet-unimplemented kind stays on the batch path rather than streaming with the wrong
parser. (PDF-reflow streaming is gated by the reflow toggle + the flag in `content-view`, not `implemented?`.)

## Consequences

**Positive.**

- **Arbitrarily large logs without OOM/freeze — the headline win (bounded byte-stream).** For logs/text the
  working set is bounded end-to-end: the parser retains only the open record + a single WPDA config; the
  transport holds $\le 2$ batches; main reads $\le 1$ ahead. The parse never materialises the whole document.
  This generalises the ad-hoc paging into one mechanism and renders the *whole* file progressively rather than
  a paged slice.
- **Responsiveness for every large doc (progressive block-commit).** Markdown/PDF-reflow can't be bounded-parse
  (document-global constructs), but committing the batch render's blocks across idle frames still means first
  content paints early and the UI never stalls — the expensive post-passes (MathJax/mermaid/tree-sitter) and
  DOM writes are paced, and the whole HTML string is never held. First content paints after the first batch,
  not after the whole document.
- **Capabilities keep working on a growing document.** `:doc/toc` grows via `:stream/toc-append` (bounded, and
  the collision-free `ir.meta/anchor-id` keeps ids stable so there is no churn); find and the content-agnostic
  scroll-spy operate on the streamed DOM. Logs gain an error/warning **Contents outline** they never had.

**Negative / accepted trade-offs.**

- **Total CPU is not lower.** Deliberate — see Context. Mitigated by never streaming small/medium docs.
- **The rendered DOM's node *count* still grows with the streamed prefix**, even though its *render cost* is
  now windowed (`content-visibility`, Phase 4 above) and it is never *larger* than the batch DOM. Bounding the
  node count too would need node removal + re-streaming (logs) and would break find/spy/selection — a poor
  trade for the render win already captured. "Bounded memory" precisely means the *parse/transport* working
  set for logs, the non-held HTML string for the progressive kinds, and the *bounded render* (not node count)
  for the windowed DOM.
- **Re-mount re-streams from the top** (the render is never snapshotted). A tab-switch away/back or a
  live-refresh loses the precise scroll position *transiently*; Phase 4's re-anchor saves the scrollTop and
  restores it once the re-stream settles (`scheduler/when-settled`).
- **A second render path exists** (streaming + batch). Byte-parity gates (the electron smoke's byte-identical
  streamed-vs-batch comparison for Markdown and PDF-reflow; the exact-count log assertions) keep them from
  diverging.

**Verification.** DOM-free unit tests cover the log WPDA grammar, `StreamParser` feed/finish segmentation, the
**bounded-memory property** (after 500+ batches the frontier is a single config and only the open record is
retained), the leaf-vs-element lowering trap, and HTML-escaping of log content. The electron smoke (dev **and**
release) covers the whole spine: a 5.6 MiB / 7005-record log streams to completion (progressive growth, JSON/
stack records kept whole by exact count, Contents populated + navigated, find inside streamed braced records,
session registry drains to `0` — no fd leak); a >256 KiB Markdown streams **byte-identically** to its batch
render (slug dedup across 603 repeated headings, a forward reference resolved at EOF, loose lists, highlighted
code, tables, source positions); PDF-reflow streams byte-identically to the batch reflow; the Preferences
toggle is present + default-on + persists; the streamed body is **render-windowed** (`content-visibility:auto`
asserted on its blocks, with find/spy/parity/re-anchor all still passing *with* windowing active — proving it
is capability-preserving); and a streamed doc re-anchors its scroll (3000 → 3000) across a live-refresh. Gate
at every phase: 0 warnings (`:main` `:simple` + renderer, dev + release), all node tests green, lint clean.

## Amendment (2026-07-09) — the `:meta {:size}` prerequisite, and Org joins the progressive engine

Two corrections landed with [ADR-0024](0024-org-export-blocks-front-matter-and-math.md).

**The size gate was never satisfied for prose.** `stream.flag/enabled?` requires $`\mathit{size} \geq \mathit{threshold}`$, and the size
arrives as `(:size meta)` on the `vv:content` payload. But `service.cljs`'s `:text` route — the route that serves
**markdown**, org, source, and diagram — sent no `:meta` at all, so the gate always compared `0` against 256 KiB
and the progressive engine never ran on a real file. The byte-parity smoke passed only because it *stubs*
`meta: {size}` into a fake content service. The `:text` route now `statSync`s and sends `:meta {:size …}`, and
`flag_test` locks in that a `nil` size never streams. Consequence: large Markdown documents now genuinely stream,
as this ADR always intended.

**The progressive engine is format-agnostic.** It commits **IR children**, not Markdown nodes, so any
tree-producing frontend can stream through it by supplying its own parse prefix. `stream-blocks` is generalized
to `stream-blocks*` (parameterized by the pipeline builder); `org-stream-blocks` is a thin wrapper over
`org-pipeline`. `flag` gains `"org"` (256 KiB), `scheduler`'s `posts-for` / `sep-for` gain an `"org"` arm, and
`ir-stream-body` gains an `"org"` block provider. No new engine, no new parser, and byte-parity holds by the same
construction — the separators live in emitted whitespace `:text` leaves, so `concat(map lower children) ==
lower(document)` exactly.

## Related

- [ADR-0024 — Org export blocks, front matter, and math](0024-org-export-blocks-front-matter-and-math.md)
  (fixes the size gate; puts Org on this engine)
- [ADR-0017 — the common document IR](0017-common-document-ir.md) (the IR + WPDA this streams)
- [ADR-0010 — bounded content retention](0010-bounded-content-retention-and-render-metadata.md) (the
  whole-document retention this consciously does *not* extend to streamed docs)
- [ADR-0009 — mediator IPC](0009-mediator-ipc-over-point-to-point.md) (the `vv:stream-*` channels follow it)
- [theory/09 — document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md)
- [security/threat-model](../security/threat-model.md) (per-block sanitization)
