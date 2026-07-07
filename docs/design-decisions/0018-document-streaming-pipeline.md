# 0018 — A document-streaming pipeline (bounded-memory, WPDA-segmented)

- **Status:** Accepted — logs/text implemented (v0.3.0, Phase 1); Markdown/PDF/source phased, default-off
- **Date:** 2026-07-07
- **Deciders:** vinary-viewer maintainers

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

Rollout is **phased, each phase green behind the flag** (Phase 0 scaffolding; **Phase 1 logs — the POC**;
Phase 2 Markdown via micromark, gated on byte-parity; Phase 3 PDF via `streamTextContent`; Phase 4 capabilities
hardened + windowed DOM + default-on for large docs; Phase 5 source, spike-gated). `stream.flag/implemented?`
holds the set of kinds whose streaming front-end actually exists, so a large doc of an as-yet-unimplemented
kind stays on the batch path rather than streaming with the wrong parser.

## Consequences

**Positive.**

- **Arbitrarily large files without OOM/freeze — the headline win.** The working set is bounded end-to-end: the
  parser retains only the open block + a single WPDA config; the transport holds $\le 2$ batches; main reads $\le 1$
  ahead. The parse never materialises the whole document. This generalises the ad-hoc paging/windowing into one
  mechanism and extends it (in later phases) to Markdown and PDF, rendering the *whole* file progressively
  rather than a paged slice.
- **Responsiveness.** First content paints after the first batch, not after the whole document; the idle-budget
  scheduler spreads work across frames so the UI never stalls.
- **Capabilities keep working on a growing document.** `:doc/toc` grows via `:stream/toc-append` (bounded, and
  the collision-free `ir.meta/anchor-id` keeps ids stable so there is no churn); find and the content-agnostic
  scroll-spy operate on the streamed DOM. Logs gain an error/warning **Contents outline** they never had.

**Negative / accepted trade-offs.**

- **Total CPU is not lower.** Deliberate — see Context. Mitigated by never streaming small/medium docs.
- **The DOM still grows unboundedly** for a huge document even though the *parse* is bounded. Phase 4 windows
  the DOM (drop off-screen blocks, the `release-canvas!` pattern). Until then, "bounded memory" means the
  *parse/transport* working set; the rendered node count still tracks the streamed prefix.
- **Re-mount re-streams from the top,** so a tab-switch away and back loses the precise scroll position until
  the stream catches up. Phase 4 records the viewport block's `anchor-id` and re-anchors after re-stream.
- **A second render path exists** (streaming + batch). Byte-parity gates (the parity corpus for Markdown, the
  electron smoke for logs) keep them from diverging, and the flag holds each format on batch until its gate
  passes.

**Verification (Phase 1).** DOM-free unit tests cover the log WPDA grammar, `StreamParser` feed/finish
segmentation, the **bounded-memory property** (after 500+ batches the frontier is a single config and only the
open record is retained), the leaf-vs-element lowering trap, and HTML-escaping of log content. The electron
smoke (dev **and** release) streams a 5.6 MiB / 7005-record log to completion with progressive growth, keeps
JSON/stack records whole (exact count), populates + navigates the Contents, finds text inside streamed braced
records, keeps small logs on the batch path, and asserts the main-process session registry drains to `0` on
teardown (no fd/session leak — `vv:stream-*` is wired to the real content service). Gate: 0 warnings
(`:main` `:simple` + renderer, dev + release), all node tests green, lint clean.

## Related

- [ADR-0017 — the common document IR](0017-common-document-ir.md) (the IR + WPDA this streams)
- [ADR-0010 — bounded content retention](0010-bounded-content-retention-and-render-metadata.md) (the
  whole-document retention this consciously does *not* extend to streamed docs)
- [ADR-0009 — mediator IPC](0009-mediator-ipc-over-point-to-point.md) (the `vv:stream-*` channels follow it)
- [theory/09 — document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md)
- [security/threat-model](../security/threat-model.md) (per-block sanitization)
