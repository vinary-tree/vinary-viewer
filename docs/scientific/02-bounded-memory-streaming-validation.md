# 02 — Bounded-memory streaming validation

**Status: current for v0.3.0-dev.** The streaming pipeline's defining promise is that a document of *any* size
renders with a **bounded working set** — memory that does not grow with the document length `$`N`$`. This page
states that promise as a proposition, sketches its proof, and then exhibits the tests and smoke assertions that
witness it at every layer of the pipeline: the WPDA frontier, the streaming front-end, the transport and main
session, and the terminal viewport.

> **Prerequisite.** [theory/09 — Document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md),
> especially §3 (the log grammar), §4 (segmentation), and §5 (the bounded-memory property this page validates).

![Per-line segmentation: regular classifier over a context-free pushdown](../diagrams/activity-log-segmentation.svg)
*Diagram source: [`../diagrams/activity-log-segmentation.puml`](../diagrams/activity-log-segmentation.puml).*

---

## 1 · The data-stream model and the risk it removes

Before v0.3.0 the IR was built on **whole documents**: read the whole file, build the whole IR, lower the whole
HTML, and replace `innerHTML` once. The parse/transport space is then `$`\Theta(N)`$` — file, parse, and rendered
HTML coexist — and a large log or Markdown file freezes or OOMs the renderer. Streaming instantiates the
**data-stream model** (Muthukrishnan 2005): a one-pass computation whose working memory is *sublinear* — here,
*constant* — in the input length. The document is treated as a bounded-memory incremental stream of
lines/tokens/runs; the WPDA drives the ambiguous segmentation step; and IR + DOM are emitted incrementally,
never holding the whole parse.

The property to validate is therefore not throughput but a **space bound**. Streaming does *more* total work
(per-block lowering, post-passes, IPC pulls, scheduling), so it is reserved by threshold for large documents; it
buys *latency* and *bounded memory*, not speed ([theory/09 §1](../theory/09-document-streaming-and-the-wpda.md#1--motivation)).
This page is about the second of those two.

### 1.1 · Key terms

| Term | Definition |
|------|------------|
| **Config** | A WPDA configuration `$`(q, \gamma, w)`$`: state `$`q`$`, stack `$`\gamma \in \Gamma^{*}`$`, weight `$`w \in K`$`. |
| **Frontier** | The current set (beam) of live configs — the streaming decoder's *entire* live parse state. |
| **Beam width `$`\beta`$`** | The prune width bounding the frontier size (`decode/beam-prune`). |
| **`$`d_{\max}`$`** | The maximum stack depth (`decode/default-max-stack` `$`= 4096`$`); a config whose push would exceed it is discarded. |
| **Credit** | The outstanding-pull count bounding transport — here fixed at `$`1`$` (credit-1 backpressure). |
| **`$`L_{\max}`$`** | The size of the largest single record — the only document-derived quantity the front-end retains. |
| **Working set** | The live memory of a participant (decoder, front-end, transport, main session) at any instant. |

---

## 2 · The bounded-working-set proposition

Let `$`F_i`$` be the frontier after `$`i`$` input symbols, `$`\gamma`$` a config's stack, `$`\beta`$` the beam
width, and `$`d_{\max}`$` the maximum stack depth.

> **Proposition (bounded working set).** For every `$`i`$`: `$`\;|F_i| \le \beta\;`$` and
> `$`\;|\gamma| \le d_{\max}\;`$` for every config in `$`F_i`$`. For the deterministic log brace grammar,
> `$`|F_i| = 1`$`. Hence the streaming decoder's live state is `$`O(\beta \cdot d_{\max})`$` — a constant
> independent of the document length `$`N`$`.

*Proof sketch.* The three parts are independent.

1. **Frontier width.** `$`F_i = \mathrm{prune}_{\beta}(\cdot)`$` by definition of the streaming step
   (`decode/advance-step` calls `beam-prune`), and `$`\mathrm{prune}_{\beta}`$` keeps at most `$`\beta`$` configs.
   So `$`|F_i| \le \beta`$` for all `$`i`$`, immediately.
2. **Stack depth.** `decode/advance` (through `eps-closure`) discards any successor config whose stack would
   exceed `$`d_{\max}`$` — the guard `$`|\mathrm{stack}(nc)| \le d_{\max}`$` on each ε-successor. Hence every
   surviving config satisfies `$`|\gamma| \le d_{\max}`$`.
3. **Determinism collapses the frontier.** For the log brace grammar, `$`\delta`$` is deterministic: from any
   config, each token has exactly one applicable transition ([theory/09 §3](../theory/09-document-streaming-and-the-wpda.md#3--the-wpda-log-grammar)).
   By induction on `$`i`$`, `advance` maps one config to one config and prune is a no-op, so
   `$`|F_i| = |F_{i-1}| = \dots = |F_0| = 1`$`.

Combining, the live state holds `$`\le \beta`$` configs each with a stack `$`\le d_{\max}`$`, i.e.
`$`O(\beta \cdot d_{\max})`$` — and for logs, `$`O(d_{\max})`$` — **independent of `$`N`$`.** `$`\qquad\square`$`

Two facts make this a *safe* bound rather than a hopeful one:

- **The ε-closure terminates.** The bound relies on `advance` finishing. `decode/eps-closure` keeps, per
  `$`(\text{state}, \text{stack})`$` config, the `$`\oplus`$`-optimal accumulated weight and relaxes only on
  improvement — a shortest-distance computation over a *finite* config space (finite states `$`\times`$` stacks
  bounded by `$`d_{\max}`$`), so it terminates even in the presence of ε-push cycles. This is the design's answer
  to the classic pushdown pitfall of unbounded ε-loops.
- **The bound holds for genuinely ambiguous grammars too.** For logs `$`|F_i| = 1`$`, but the beam bound
  `$`|F_i| \le \beta`$` is what keeps the frontier bounded when segmentation *is* ambiguous (multi-column PDF,
  where a Tropical weight ranks competing segmentations). The beam is the general mechanism; determinism is the
  special case.

---

## 3 · Witness A — the frontier stays bounded (`decode`, `wpda`)

Two DOM-free tests exhibit each half of the proposition on a machine *constructed to violate it without the
bound*.

`vinary.ir.decode-test/bounded-frontier-under-beam` builds a WPDA whose every `a` forks the stack two ways
(push `:x` at cost 0 *or* push `:y` at cost 1), so an unbounded decoder would hold `$`2^{n}`$` configs after
`$`n`$` symbols. With a beam of 4, it asserts the frontier never exceeds the beam, across 60 symbols:

```pseudocode
p    ← WPDA where each 'a' forks the stack (2 successors)   ' 2^n configs without a beam
beam ← 4
f ← [initial-config p]
for n in 0 .. 60:
    assert |f| ≤ beam                                       ' the beam caps the frontier width
    f ← advance-step(p, f, "a", {beam: beam})
```

Here `$`2^{60}`$` configs are impossible, yet the frontier holds `$`\le 4`$` throughout — the bound of §2 part 1,
demonstrated against exponential fan-out. The companion `advance-step-folds-to-decode` proves this bounded
per-symbol stepping computes the *same* frontier as the whole-input `decode` (streaming `$`\equiv`$` batch at the
decoder level), and `streaming-acceptance-matches-batch` proves incremental consumption accepts exactly the
language the batch recogniser does.

`vinary.ir.wpda-test/eps-closure-terminates-and-bounds` exhibits part 2 (and termination): a WPDA with an
*unbounded* ε-push cycle `$`q \xrightarrow{\varepsilon,\,\text{push } x} q`$`, closed with `max-stack 8`, yields
at most `$`9`$` configs — "at most one config per stack depth `$`0 \ldots 8`$`" — and still accepts. The cycle
does not diverge; the depth cap turns it into a finite relaxation. The golden context-free languages
`balanced-brackets-recognition` and `a-n-b-n-recognition` in the same suite confirm the machine really is a
pushdown (it accepts `$`a^{n} b^{n}`$`, which no finite-state counter can), so the bound is a bound on a genuine
CFG recogniser, not a degenerate one.

---

## 4 · Witness B — the front-end retains only the open record (`log-stream`)

![One batch's lifecycle across every participant under credit-1](../diagrams/seq-document-streaming.svg)
*Diagram source: [`../diagrams/seq-document-streaming.puml`](../diagrams/seq-document-streaming.puml).*

The decoder bound lifts to the streaming log front-end. `ir.frontend.log-stream` retains, between batches, only
the **open record's lines** plus the single WPDA config. The open record is bounded by `$`L_{\max}`$` (the
largest single record), not by `$`N`$`: a depth-0 header emits the previous record and starts a fresh one, so at
most *one* record is ever open ([theory/09 §5](../theory/09-document-streaming-and-the-wpda.md#5--the-bounded-memory-property)).

`vinary.ir.frontend.log-stream-test/bounded-memory-property` is the direct witness. It feeds 500 complete
single-line records followed by one still-open three-line record, **one record per batch**, so the parser is fed
501 times, and asserts that the retained state is bounded by the *last* record, not by the 503 lines fed:

```pseudocode
batches ← [ [record 0], [record 1], … , [record 499],       ' 500 complete single-line records
            ["2026 ERROR boom", "  at A.b", "  at C.d"] ]     ' one still-open 3-line record
p ← fold over batches: (p, b) ↦ (feed p b).parser            ' 501 feeds

assert |p.frontier| = 1                                       ' deterministic grammar ⇒ single config
assert p.open = ["2026 ERROR boom", "  at A.b", "  at C.d"]   ' ONLY the last (open) record retained
assert |p.open| ≤ 3                                           ' bounded by its own size, not by 503
assert |(finish p).blocks| = 1                                ' finish flushes exactly that one record
```

The assertions map one-to-one onto the proposition: `$`|p.\mathrm{frontier}| = 1`$` is `$`|F_i| = 1`$` for the
deterministic grammar; `$`p.\mathrm{open}`$` being just the last three lines is the `$`O(L_{\max})`$` front-end
bound. The 500 preceding records — 500× the retained size — leave no trace in the parser, which is exactly the
`$`O(1)`$`-in-`$`N`$` claim.

The **correctness** of that segmentation under streaming is guarded separately by
`streaming-across-batches-equals-whole` (the records are invariant to *how* lines are split into batches — the
batch-split invariance of [theory/09 §2](../theory/09-document-streaming-and-the-wpda.md#2--the-streaming-contract))
and `brace-keeps-record-whole` (a header-looking line inside `{ … }` does *not* split the record — the
context-free part the pushdown earns). Bounded memory that produced *wrong* records would be worthless; these
tie the space bound to a correct parse.

---

## 5 · Witness C — the pipeline-wide bound and the no-leak gate

The decoder and front-end bounds are `$`O(1)`$` in `$`N`$`, but a stream also spans a transport and a
main-process file session, and a leak there would defeat the guarantee. The design bounds each participant and
the smoke asserts the bound end-to-end.

**Credit-1 backpressure.** The transport is a pull client that holds `$`\le 2`$` batches (the current one plus one
double-buffered prefetch), and the main process reads `$`\le 1`$` batch ahead, pausing `readline` at the batch
cap. This is the credit-based demand protocol of Reactive Streams specialised to a credit of one: the consumer
grants exactly one unit of demand at a time, so the producer can never outrun it and buffer unboundedly.
`vinary.stream.transport-test/pull-yields-batches-in-order-then-done` verifies batches arrive in order and the
`done` flag terminates the pull loop under a mocked main surface; `pull-surfaces-mid-stream-drop` verifies that a
remote (SSH) source dropping mid-stream is delivered as a *partial* final batch (carrying `:error`/`:partial`
plus the lines read so far), never a silent truncation and never a fatal `:content/error`.

**The session-registry no-leak gate.** On the main side, each active stream is one `createReadStream` fd in a
bounded session registry (`content_service.js`), and it must be *released* on renderer unmount. The Electron
smoke asserts this precisely, bracketing the stream's life:

```js
const streamCount0 = contentService.streamCount();
// … open the large log; streaming engages …
assert.strictEqual(contentService.streamCount(), streamCount0 + 1,
  'exactly one main-process stream session is open while streaming');
// … switch away → the stream body unmounts → the session closes …
await waitFor(() => contentService.streamCount() === 0, 'the streamed log session closed on teardown');
assert.strictEqual(contentService.streamCount(), 0,
  'no main-process stream session may leak after teardown');
```

A leak would let `streamCount()` ratchet upward across opens; the assertion that it returns to `0` is the direct
witness that the *file-descriptor* working set is bounded too, not just the parse. The remote-SSH counterpart is
`test/content-service-smoke.js`, which asserts `content.streamCount() === 0` after a remote stream — *"no leaked
remote stream session"*.

The one working-set component the design *does not* yet bound is the rendered **DOM node count**, which tracks
the streamed prefix. This is a **deliberately-unclaimed gap**: instead of removing nodes (which would break
in-page find, the scroll-spy, and text selection, all of which walk the live DOM, and would force re-streaming
dropped byte ranges for logs), the streamed body windows its *render* with `content-visibility: auto`, so
off-screen blocks skip layout and paint. The load-bearing win — bounded *render* cost — is captured; bounding the
node *count* is left as a named future optimization ([theory/09 §7.1](../theory/09-document-streaming-and-the-wpda.md#71--windowed-rendering--bounded-render-cost-intact-dom)).
The smoke confirms the applied policy (`content-visibility:auto` on the streamed blocks) rather than the
unclaimed count bound.

---

## 6 · Witness D — the terminal viewport is a bounded ring (`tui.viewport`)

The bounded-memory guarantee carries verbatim to the terminal renderer ([theory/10](../theory/10-terminal-rendering-second-renderer.md)),
where it is realised as a **capped ring buffer** in the TUI viewport. `vinary.tui.viewport` paints only
`lines[top : top+h]`, so a frame costs `$`O(h)`$` regardless of document length; and a streamed log uses a
`:cap`-ed ring where the oldest lines drop (counted in `:dropped`), keeping resident memory flat.

`vinary.tui.viewport-test/streaming-ring-cap` is the witness. With a cap of 5, appending past the cap retains
exactly 5 lines and counts the rest as dropped, and — crucially for reading UX — a scrolled-up reader keeps the
*same content* in view when old lines drop (their `:top` is decremented to compensate):

```pseudocode
v ← viewport(width 80, height 3, cap 5)
v ← append(v, 4 lines)                    ' under cap
assert total(v) = 4 ∧ dropped(v) = 0
v ← append(v, 3 more lines)               ' 7 > cap 5 → drop 2 oldest
assert total(v) = 5 ∧ dropped(v) = 2      ' retained capped at 5; memory flat
```

This is the terminal analogue of the GUI's windowed DOM: a multi-GB log streams to either surface with a working
set of the open record plus one WPDA config, and the viewport never holds more than its cap. The pure,
value-to-value form of `tui.viewport` is what lets this be unit-tested with no pseudo-tty.

---

## 7 · The bound, collected

| Participant | Working set | Bounded by | Witness |
|-------------|-------------|------------|---------|
| WPDA decoder | `$`O(\beta \cdot d_{\max})`$`; logs `$`O(d_{\max})`$` | beam `$`\beta`$`, depth `$`d_{\max}=4096`$` | `decode-test/bounded-frontier-under-beam`, `wpda-test/eps-closure-terminates-and-bounds` |
| Log front-end | `$`O(L_{\max})`$` (one open record) `$`+\,O(1)`$` config | largest record `$`L_{\max}`$` | `log-stream-test/bounded-memory-property` |
| Transport | `$`\le 2`$` batches | credit-1 + double-buffer | `transport-test/pull-yields-batches-in-order-then-done` |
| Main session | `$`1`$` fd per stream, `$`\to 0`$` on teardown | credit-1 + unmount close | smoke `streamCount → 0`; `content-service-smoke.js` |
| TUI viewport | `$`O(\mathrm{cap})`$` | ring `:cap` | `viewport-test/streaming-ring-cap` |
| Rendered DOM | `$`\Theta(\text{streamed prefix})`$` render-*windowed* to `$`\Theta(\text{viewport})` | `content-visibility:auto` (count: **gap**) | smoke `content-visibility` check |

Every parse/transport participant is `$`O(1)`$` in `$`N`$`; the only un-bounded-in-count component, the DOM, is
render-bounded and its node-count bound is an explicitly deferred optimization. The proposition of §2 is thus not
just proved for the decoder but *witnessed at every layer the bytes flow through*.

---

## 8 · See also

- [theory/09 — Document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md) §5 (the
  proposition), §7 (scheduling/backpressure), §8 (the complexity table).
- [theory/10 — Terminal rendering](../theory/10-terminal-rendering-second-renderer.md) §4.1, §5 (the viewport
  ring and terminal streaming).
- [01 — Byte-parity verification](01-byte-parity-verification.md) — the streaming *output* guarantee that
  accompanies this *memory* guarantee.
- [03 — Semiring algebraic laws](03-semiring-algebraic-laws.md) — the weights `$`\oplus`$`/`$`\otimes`$` the beam
  ranks by, and the idempotence that makes `at-least-as-good?` a well-defined comparator for the ε-closure.

## 9 · References

1. **S. Muthukrishnan.** *Data Streams: Algorithms and Applications.* Foundations and Trends in Theoretical
   Computer Science **1**(2), 117–236, 2005. DOI [10.1561/0400000002](https://doi.org/10.1561/0400000002). —
   the one-pass, sublinear-space stream model this pipeline instantiates (the bound of §2).
2. **T. Reps, S. Schwoon, S. Jha, D. Melski.** *Weighted pushdown systems and their application to
   interprocedural dataflow analysis.* Science of Computer Programming **58**(1–2), 206–263, 2005. DOI
   [10.1016/j.scico.2005.02.009](https://doi.org/10.1016/j.scico.2005.02.009). — the weighted-pushdown model
   `vinary.ir.grammar.log` specialises.
3. **Reactive Streams** (2015). *Reactive Streams specification.* <https://www.reactive-streams.org/> — the
   credit-based demand/backpressure protocol whose credit-1 form the transport and main session use (§5).
