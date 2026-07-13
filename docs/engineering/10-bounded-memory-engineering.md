# 10 — Bounded-memory engineering

The [theory pillar](../theory/09-document-streaming-and-the-wpda.md) *proves* that a streamed document's
parse working set is bounded; this page documents the **engineering practices** that make bounded memory a
property of the running application rather than a property of a proof — where the app preallocates, what it
retains, how it windows rendering, and one bound it deliberately leaves unclaimed.

> **Why this is an engineering concern.** A document previewer is pointed at whatever the user has: a 5 MiB
> log, a 300-page PDF, a directory of hundreds of files. If memory grew with input size, the app would freeze
> or OOM on exactly the inputs a previewer is most useful for. Bounding memory is therefore a correctness
> requirement, not an optimization — and `CLAUDE.md`'s standing rule, *"preallocation is a best practice, not
> a premature optimization,"* is the design stance this page operationalizes.

---

## 1. Four mechanisms, one property

Bounded memory is upheld by four distinct mechanisms, each at a different layer. Read the table as a stack:
the higher rows bound *retention* (what is kept), the lower rows bound *rendering* (what is materialized).

| Mechanism | Bounds | Where | ADR / theory |
|-----------|--------|-------|--------------|
| **Bounded content retention** | how many document *contents* are cached | `vinary.app.ds` (DataScript) | [ADR-0010](../design-decisions/0010-bounded-content-retention-and-render-metadata.md) |
| **Streaming parse** | the *parse* working set of one large document | `vinary.stream.*` + `vinary.ir.decode` | [ADR-0018](../design-decisions/0018-document-streaming-pipeline.md), [theory/09](../theory/09-document-streaming-and-the-wpda.md) |
| **Render windowing** | the *layout/paint* cost of a long streamed body | `content-visibility` CSS + `vinary.renderer.virtual-layout` | [ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md), [theory/09 §7.1](../theory/09-document-streaming-and-the-wpda.md) |
| **Paced commit** | how much work lands per frame | `vinary.stream.scheduler` (rAF / idle) | [ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md) |

![Bounded content retention — tab histories own the cache lifetimes](../diagrams/component-content-retention.svg)

*Diagram source: [`../diagrams/component-content-retention.puml`](../diagrams/component-content-retention.puml).*

---

## 2. Preallocation as policy

Where a collection's final size is known, it is allocated at that size up front rather than grown
incrementally. This avoids the repeated reallocation-and-copy that turns an `$`O(n)`$` fill into an amortized
churn of intermediate buffers, and it makes peak memory predictable. In the streaming decoder the analogous
discipline is a *fixed* bound: the beam width `$`\beta`$` and maximum stack depth `$`d_{\max}`$`
(`decode/default-max-stack` `$`= 4096`$`) are constants chosen ahead of time, so the frontier can never grow
to track input length ([theory/09 §5](../theory/09-document-streaming-and-the-wpda.md)). Bounding *by
construction* — a fixed-capacity structure — is strictly stronger than bounding *by policy*, and the app uses
it wherever a hard ceiling is available.

---

## 3. Bounded content retention (ADR-0010)

The renderer caches **document content** — `:doc/html`, `:doc/toc`, `:doc/text`, `:doc/assets` — in
DataScript, keyed by `:doc/path`. Without a bound, every file ever opened would stay resident. The bound is
**tab-history reachability**: a document is *retained* only while some open tab's Back/Forward history can
still reach it; when the last such reference is closed, its content is evicted and the main process releases
its `chokidar` watcher. Tab histories, not a fixed cache size, define ownership — so the cache is exactly as
large as the user's live working set, and no larger. This is the update ADR-0010 made to the original
live-refresh model, and it is why closing tabs actually frees memory.

---

## 4. Render windowing — bounded paint, intact DOM

Streaming bounds the *parse*, but the appended DOM node count still grows with the streamed prefix, and
layout+paint of a very large DOM is the scroll-jank risk. The streamed body (`.markdown-body.vv-streamed`)
therefore windows its *rendering* with two CSS declarations per top-level block:

```css
content-visibility: auto;
contain-intrinsic-size: auto 48px;
```

`content-visibility: auto` lets the browser **skip layout and paint for off-screen blocks** — bounding render
work to roughly the viewport regardless of document size — while `contain-intrinsic-size` supplies a
placeholder height (`auto` remembering each block's real size once painted) so the scrollbar stays stable.
For PDFs and pre-DOM figure sizing the analogous work lives in `vinary.renderer.virtual-layout`, which
estimates block heights so the scroller is correctly sized from the first batch
([ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md)).

---

## 5. The deliberately-unclaimed bound: no DOM node eviction

A further optimization is *available* and deliberately *not taken*: removing off-screen DOM nodes to bound the
node **count**, not just the render cost. It is declined for a concrete reason. Three whole-document
capabilities — in-page find, the content-agnostic scroll-spy, and text selection — all walk the *live* DOM;
removing nodes would break all three, and for logs (whose bytes are not retained) would additionally force
re-streaming dropped byte ranges. Because `content-visibility` already delivers the load-bearing win —
bounded *render* — with no capability loss, node removal buys little and costs much. Naming this non-goal is
itself the point: a silent "we could window harder" would read as unfinished work, when it is a considered
trade-off ([theory/09 §7.1](../theory/09-document-streaming-and-the-wpda.md)).

---

## 6. How this is verified

Engineering practice is only credible if instrumented. The bounds above are checked by the
[scientific pillar](../scientific/02-bounded-memory-streaming-validation.md): the WPDA bounded-working-set
property test, the credit-1 backpressure, and the main-process session-registry `streamCount → 0` no-leak
assertion in the Electron smoke. This page documents the *engineering*; that page documents the *evidence*.

## 7. See also

- [theory/09 — Document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md) — the proof this page realizes.
- [scientific/02 — Bounded-memory streaming validation](../scientific/02-bounded-memory-streaming-validation.md) — the tests.
- [ADR-0010](../design-decisions/0010-bounded-content-retention-and-render-metadata.md), [ADR-0018](../design-decisions/0018-document-streaming-pipeline.md), [ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md).
