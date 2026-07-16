# 0028 — Composable rendering features (native deterministic recognizers on the shared spine)

> **Superseded by [ADR-0029](0029-mature-parsers-shared-ir-features.md).** The native recognizer direction
> was reversed before it shipped: the mature parsers (remark-gfm/micromark, uniorg, unified-latex) already
> deliver the functionality, so re-implementing CommonMark/Org was unwarranted. The *valuable* part of this
> ADR — one common IR, composable features shared across formats, dual back-ends — survives and rides on the
> mature parsers' ASTs. See ADR-0029. This document is retained as the record of the explored-and-reversed
> approach.

- **Status:** Superseded by [ADR-0029](0029-mature-parsers-shared-ir-features.md)
- **Date:** 2026-07-16
- **Deciders:** Vinary Tree (maintainer)

## Context

By [ADR-0017](0017-common-document-ir.md) every format already targets one common document IR
(`vinary.ir.node/Node`) and lowers OUT to two back-ends — `vinary.ir.backend.html` (GUI) and
`vinary.ir.backend.ansi` (terminal). By [ADR-0018](0018-document-streaming-pipeline.md) a streaming spine
(`vinary.stream.*`) drives IR blocks into the DOM/terminal incrementally, and a weighted-automata substrate
(`vinary.ir.{wpda,decode,earley,forest,transducer,layer,semiring}`) exists — but until now it was used only
by the log stream, while **every markup format parsed through a monolithic third-party parser**
(`remark-gfm`, `uniorg`, `unified-latex`) whose output was mirrored into the IR after the fact.

That left the same construct implemented in parallel silos: a GFM table, an Org table, an HTML `<table>`, and
a LaTeX `tabular` are *the same rendering feature*, yet none was streaming, composable, or reusable across
formats. The rich passes (math, mermaid, figures, syntax) ran on the *lowered HTML string* and matched by CSS
shape, forcing every non-Markdown frontend to pay a **selector tax** (`org-normalize`, `tex-normalize`) and
leaving the ANSI back-end to re-implement everything.

This ADR decomposes document rendering into a library of **composable rendering features** — layers over the
streaming/IR/back-end spine — where each construct (heading, table, emphasis, code, link, …) is one shared
module, and a **format is a set of features plus a recognizer**. The full design, exhaustive GFM/Org
enumerations, and a 9-finding adversarial red-team are recorded in
`docs/theory/11-composable-rendering-features.md`; this ADR states the decision and its realization.

## Decision

### 1. Four segregated contribution protocols

`vinary.ir.feature` defines a **feature** as a plain-data descriptor bundling one or more *independent*
contributions (interface segregation — a feature never null-fills hooks it does not use):

| Contribution | Shape | Role |
|---|---|---|
| **Recognizer** | a `vinary.stream.protocol/StreamParser` (composed from block/inline features, or delegated) | surface syntax → IR blocks |
| **Lowering** | `{:html (fn [node recurse]) :ansi (fn [node ctx])}` keyed by IR `:kind` | IR node → back-end output (BOTH back-ends inherit) |
| **Enrichment** | `{:id :after :before :html :ansi}` | ordered post-lowering pass (math/mermaid/figures/syntax) |
| **Capability** | `(fn [ir] entries)` | IR → derived view (TOC/outline, find index) |

`vinary.ir.feature.registry/build` assembles a format-spec + features into a **renderer** — the single value
the GUI scheduler / `vv-cli` / `vv-tui` consume — with lowering-override assembly (collision-checked),
topological enrichment ordering (cycle-checked), and the capability set. Assembly is a pure function of
`(format-spec, feature-index)`, memoized once per format in `vinary.ir.feature.formats` (never per document).

### 2. Recognition is DETERMINISTIC — the red-team's central correction (G1)

CommonMark block and inline parsing are **deterministic**: the open-block-spine line algorithm and the
`process_emphasis` delimiter-stack scan each yield exactly one spec-defined parse. The first design routed
them through the weighted `earley`/`forest` machinery with Viterbi precedence; that was an asymptotic
regression (Earley is `O(n^3)` vs the spec algorithms' `O(n)`) and a fragile "tune weights to match a
deterministic rule" exercise. **Corrected:** markup uses deterministic `O(n)` recognizers
(`vinary.ir.recognizer.{block,inline}`) reusing only the deterministic `wpda` state types + the `StreamParser`
spine. The nondeterministic `decode`(beam)/`earley`/`forest`/Tropical path is **reserved for genuinely
ambiguous segmentation** (PDF reflow, fuzzy recovery). Precedence is structural (phase order + the spec scans),
not weight-ranked.

### 3. The native GFM recognizer

`vinary.ir.recognizer.block` is a faithful port of the CommonMark block-structure algorithm (tabs/columns,
`findNextNonspace`, indented detection, the open-block-spine phase-1/2/3 loop, lazy paragraph continuation,
last-line-blank tracking, an `:after-line` hook, finalize), *feature-parameterized* — the engine is
construct-agnostic. `vinary.ir.recognizer.inline` is the CommonMark inline parser (a mutable linked tree,
`vinary.ir.recognizer.mtree`, spliced in place by the emphasis/link algorithms). `vinary.ir.feature.gfm.block`
supplies every CommonMark block construct + the GFM table extension; the inline parser supplies every inline
construct + GFM strikethrough (§6.5) and the GFM autolink extension (§6.9).

Because reference-link and footnote **definitions are non-local** (forward references — G2), markup parses the
WHOLE document before inline resolution: the block runner buffers lines and, on `finish`, parses and resolves
inline content against the now-complete refmap. Its memory bound is honestly `O(document)` — *true*
`O(open block)` streaming remains a property of logs and self-contained huge blocks.

### 4. Lowering & enrichment stay IR-level; the raw-HTML seam is the ONE sanitize boundary (G3)

Features match on IR `:kind`, never CSS shape, so both back-ends inherit each feature and the selector tax
disappears. Raw HTML (§6.10 inline, §4.6 blocks) becomes a `:raw-html` IR leaf lowered through the single
`vinary.ir.backend.html/lower-raw` seam — `ir→hast` (raw HTML as `raw` nodes) → `rehype-raw` (parse) →
`rehype-sanitize` (the ONE shared GitHub allowlist, `vinary.ir.backend.sanitize/schema`) → stringify. Native
raw HTML is sanitized exactly like the legacy pipeline; the security surface provably cannot widen. `<script>`,
`onerror`, `onclick` are stripped (tested). `ir→hast` gained an optional `overrides` argument that is
byte-identical to the pre-registry output when omitted (round-trip parity preserved).

### 5. Markup languages are dialects over one feature core

The registry hosts every markup dialect uniformly. Per the sanctioned-hybridization tenet, a format's
*recognizer* may delegate where a mature parser exists and HAST is already the IR's shape:

| Format | Recognizer | Notes |
|---|---|---|
| **GFM** | native deterministic block+inline (`O(n)`) | this ADR |
| **HTML** | delegated → `rehype-parse`+sanitizer (`office/html->ir`) | structured, slugged, sanitized IR |
| **LaTeX** | delegated → `unified-latex` + shared tex-processor | reuses `cli.render/latex->ir` path, synchronous |
| **Org** | delegated → `org-pipeline` (uniorg), synchronous | interim per the migration strategy; native is the escalation |
| **log** | brace-WPDA `StreamParser` (the genuine WPDA) | the first registry citizen |

The composable layer model (recognition is the only per-format difference; lowering/enrichment/capability are
shared):

![Composable rendering features — recognition per-format, everything downstream shared](../diagrams/component-feature-layers.svg)

*Recognition is the only per-format layer; lowering, enrichment, and capability are shared across every markup dialect, and raw HTML passes through the one sanitize seam.*

### 6. Formal correctness properties

For a document of `n` characters, native markup recognition runs in `O(n)` time. The block phase maintains the
invariant that the open-block spine is exactly the path from the document root to the deepest open block; a
block is committed to IR precisely when it is finalized. The inline phase's emphasis resolution is the
deterministic rule-of-three: for delimiter runs of lengths `a` and `b`, a pair matches unless

```math
(\mathrm{canOpen}_{\text{closer}} \lor \mathrm{canClose}_{\text{opener}}) \;\land\; (a \bmod 3 \neq 0) \;\land\; ((a + b) \bmod 3 = 0),
```

in which case it is skipped — reproducing the spec exactly with no weight tuning.

## Consequences

**Positive.** One shared feature vocabulary across every markup dialect (and, ultimately, every document
type); both back-ends inherit each feature; the selector tax is eliminated at the IR level; one auditable
sanitize seam; the weighted substrate is rigorously scoped to genuine ambiguity; ~340 unit tests validate the
native GFM parser against CommonMark spec examples.

**Negative / follow-on.** The native GFM parser is not yet the app's production markdown renderer — flipping
it in must be gated on differential testing vs `cmark-gfm` over a corpus (P2 gate). Native Org (its own
deterministic recognizer) remains the escalation beyond the delegated interim. Structured formats (diff/table/
log/archive/pdf) GUI-unification, native `:segment` bounded streaming, and math/mermaid enrichment wiring are
sequenced follow-ons.

## References

- [ADR-0017 Common document IR](0017-common-document-ir.md), [ADR-0018 Document streaming](0018-document-streaming-pipeline.md), [ADR-0019 Terminal preview layer](0019-terminal-preview-layer.md)
- `docs/theory/11-composable-rendering-features.md` — formal model, exhaustive spec enumerations, red-team ledger
- `docs/reference/gfm-feature-catalog.md`, `docs/reference/org-feature-catalog.md` — construct catalogs + coverage matrix
- [CommonMark 0.29 spec](https://spec.commonmark.org/0.29/), [GFM spec](https://github.github.com/gfm/), [Org syntax](https://orgmode.org/worg/org-syntax.html)
