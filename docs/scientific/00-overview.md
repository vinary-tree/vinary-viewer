# 00 — The scientific pillar: verification and measurement methodology

**Status: current for v0.3.0-dev.** This pillar documents *how vinary-viewer knows it is correct* — the
proofs, property tests, byte-parity gates, and smoke assertions that back the v0.3.0 common-document-IR and
streaming work — and, where correctness is not the question, *how it will measure*. It is the scientific
ledger of the project: every claim below is traced to a source file, a named test, or a changelog entry.

![The common-document-IR pipeline and where each verification acts](../diagrams/component-ir-pipeline.svg)
*Diagram source: [`../diagrams/component-ir-pipeline.puml`](../diagrams/component-ir-pipeline.puml).*

---

## 1 · Verification-first, not benchmark-first

Most performance-sensitive projects lead with micro-benchmarks. vinary-viewer is a **document previewer**, and
its v0.3.0 cycle was a *migration* — every format was re-routed through one common intermediate representation
(the [common document IR](../theory/08-common-document-ir.md)), and large documents were re-routed through a
[bounded-memory streaming pipeline](../theory/09-document-streaming-and-the-wpda.md). The dominant risk of a
migration is not that it is slow; it is that it **silently changes output** or **weakens a security property**.
The engineering effort therefore went into *verifying equivalence and safety*, not into shaving milliseconds.

Concretely, the project validates five classes of property, each with its own instrument:

| # | Property class | The question it answers | Instrument (this pillar) |
|---|----------------|-------------------------|--------------------------|
| 1 | **Byte-parity** | Does the new path emit *exactly* the bytes the old path did? | [01 — Byte-parity verification](01-byte-parity-verification.md) |
| 2 | **Bounded memory** | Does a stream's working set stay `$`O(1)`$` in document length? | [02 — Bounded-memory streaming validation](02-bounded-memory-streaming-validation.md) |
| 3 | **Algebraic law** | Do the parser's weights actually form a semiring? | [03 — Semiring algebraic laws](03-semiring-algebraic-laws.md) |
| 4 | **Security invariance** | Is per-block sanitization identical to whole-document sanitization? | [04 — Sanitizer context-freedom](04-sanitizer-context-freedom.md) |
| 5 | **Empirical regression** | Did a rendering defect appear, and is the fix effective? | [05 — MathJax ink-loss experiment](05-mathjax-inkloss-experiment.md), [06 — Corpora & classifier experiments](06-corpora-and-classifier-experiments.md) |

Runtime performance benchmarking is a **documented, deliberate gap** — the project ships no `perf`/`hyperfine`
harness today. [07 — Measurement methodology](07-measurement-methodology.md) writes down the methodology that
*will* be used when such a harness is added, so that the first benchmark is rigorous rather than ad hoc. Saying
this plainly is itself a scientific-integrity requirement: an absent measurement must be named, not implied.

### 1.1 · Key terms

Defined once here, used throughout the pillar.

| Term | Definition |
|------|------------|
| **IR** | *Intermediate representation* — the one tagged tree (`vinary.ir.node/Node`) every format parses into ([theory/08](../theory/08-common-document-ir.md)). |
| **HAST** | *HTML Abstract Syntax Tree* — the rehype tree model the Markdown/office front-ends mirror into the IR. |
| **Byte-parity** | Two producers emit **byte-identical** output for the same input; equality is over the serialized string, not a structural "looks the same". |
| **Batch path** | The whole-document render: whole file `$`\to`$` whole IR `$`\to`$` whole HTML `$`\to`$` one `innerHTML` write. |
| **Stream path** | The incremental render: input batches `$`\to`$` per-block IR `$`\to`$` appended DOM ([theory/09](../theory/09-document-streaming-and-the-wpda.md)). |
| **Semiring** | An algebra `$`(K, \oplus, \otimes, \bar{0}, \bar{1})`$` whose choice tunes what a weighted parse computes ([03](03-semiring-algebraic-laws.md)). |
| **WPDA** | *Weighted pushdown automaton* — recognises context-free nesting, weighted by a semiring. |
| **Property test** | A test asserting a *universally-quantified* law over many generated inputs, not one example (e.g. "for every batch split, streamed == whole"). |
| **Smoke gate** | An end-to-end assertion in the Electron smoke (`test/electron-smoke.js`) that fails the whole run on regression. |
| **Oracle** | The trusted reference an output is compared against — here, usually the *retired legacy path* or the *batch render*. |

---

## 2 · The verification stack

The instruments form a layered stack. Each layer is cheaper and faster than the one above it, so a defect is
ideally caught at the lowest layer that can see it; the higher layers exist because some properties (a real DOM,
a real font, a real file descriptor) are invisible below them.

```
        ┌──────────────────────────────────────────────────────────────────────┐
  slow  │  Electron smoke  (test/electron-smoke.js)                             │
   ▲    │    real Chromium + real DOM + real fonts + real fd/session registry   │
   │    │    byte-parity innerHTML · CSP-console gate · streamCount→0 · XSS strip│
   │    ├──────────────────────────────────────────────────────────────────────┤
   │    │  Node unit + property tests  (test/vinary/**)                         │
   │    │    semiring laws · HAST round-trip parity · bounded-memory property   │
   │    │    batch-split invariance · classifier kinds · MathJax ink geometry   │
   │    ├──────────────────────────────────────────────────────────────────────┤
   │    │  Proofs / propositions  (docs/theory/08–10, restated here)            │
   ▼    │    bounded working set · exact transducer composition · CFG recognition│
  fast  └──────────────────────────────────────────────────────────────────────┘
```

The two lower layers are **DOM-free** and run under `:node-test` (fast, deterministic, no pseudo-tty or
browser). The top layer is the only one with a real rendering surface, and is therefore reserved for the
properties that *only* a real surface exhibits: that streamed and batch `innerHTML` are byte-equal after the
math/mermaid/highlight post-passes; that no Content-Security-Policy console message is emitted; that the
main-process stream session registry drains to zero after teardown. This partition is deliberate — pushing a
property as low as it will go is what keeps the fast feedback loop meaningful.

---

## 3 · What each page establishes

The seven substantive pages are ordered from the strongest, most general guarantee (byte-parity of the whole
migration) to the narrowest, most empirical (a single rendering-defect experiment) and finally to forward-looking
methodology.

- **[01 — Byte-parity verification.](01-byte-parity-verification.md)** The lossless-round-trip guarantee: the
  IR lowers to HTML *byte-identical* to the retired legacy path (`ir.parity-test`), and the streamed render is
  *byte-identical* to the batch render (`org-test/org-stream-blocks-concatenate-to-the-batch-html`; the smoke's
  300 KiB `.tex` / `.md` and 301 KiB `.org` `innerHTML` equalities). Explains *why* byte-parity is the right
  correctness criterion for a migration, including the separator-node argument.

- **[02 — Bounded-memory streaming validation.](02-bounded-memory-streaming-validation.md)** The WPDA
  bounded-working-set proposition and its test witnesses: `log-stream-test/bounded-memory-property`
  (500+ batches, one open record retained), `decode-test/bounded-frontier-under-beam`, credit-1 backpressure,
  the `streamCount → 0` no-leak smoke, and the TUI viewport `:cap` ring.

- **[03 — Semiring algebraic laws.](03-semiring-algebraic-laws.md)** The definition of a semiring, the six
  implemented weight types, the exact laws each satisfies, the `semiring-test` coverage, and *why* those laws
  are what make one weighted parser compute recognition, best-parse, and total mass by swapping the weight.

- **[04 — Sanitizer context-freedom.](04-sanitizer-context-freedom.md)** The argument that per-block
  sanitization equals whole-document sanitization because the GitHub allowlist is *context-free*, and why that
  equivalence is security-load-bearing for the streaming pipeline.

- **[05 — MathJax ink-loss experiment.](05-mathjax-inkloss-experiment.md)** A worked application of the
  scientific method to a real rendering defect: hypothesis, a 154-file / 1743-occurrence corpus measurement,
  result (385 split, 35 with major ink loss), a one-line fix, and re-measurement to zero.

- **[06 — Corpora & classifier experiments.](06-corpora-and-classifier-experiments.md)** The fixtures/corpora
  inventory and three ledgered findings: the JS-vs-CLJS classifier divergence, the delimited-sniff false
  positive on Org tables, and the `:meta {:size}` gate bug — with the methodology lesson (*drive the real
  classifier, not a stub*).

- **[07 — Measurement methodology.](07-measurement-methodology.md)** The rigorous benchmarking methodology the
  project will adopt when runtime benchmarks are added: tee-once-and-analyze, CPU affinity + max frequency,
  `perf record --call-graph lbr`, `hyperfine`, Valgrind massif, and reporting time *and* space.

---

## 4 · Method and provenance discipline

This pillar follows the project's scientific-ledger convention (`CLAUDE.md`): a claim is admissible only if it
is traceable. Three provenance grades appear, and each claim is tagged where its grade is not obvious:

- **Proven** — a mathematical proposition with a proof or proof sketch (e.g. the bounded-working-set bound of
  [02](02-bounded-memory-streaming-validation.md) §2, the transducer-composition exactness of
  [03](03-semiring-algebraic-laws.md) §5).
- **Tested** — an executable assertion, cited by its exact `deftest`/assertion name so the reader can open it
  (e.g. `semiring-test/tropical-laws`, `log-stream-test/bounded-memory-property`).
- **Measured** — an empirical count over a named corpus (e.g. the ink-loss experiment's 385/35 split of
  [05](05-mathjax-inkloss-experiment.md)).

Where a property is asserted but *not yet* instrumented, it is labelled a **gap** rather than described in the
present tense — runtime performance ([07](07-measurement-methodology.md)) is the largest such gap, and windowed
DOM node-count bounding is a smaller one carried in [02](02-bounded-memory-streaming-validation.md) §5.

Algorithms in this pillar are presented in **literate pseudocode** (Knuth 1984): a named fragment, a prose gloss
of intent, and a link to the concrete namespace that realises it, so the reader understands *what* it does,
*how*, and *why* before meeting the ClojureScript.

---

## 5 · See also

- Theory: [08 — Common document IR](../theory/08-common-document-ir.md),
  [09 — Document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md),
  [10 — Terminal rendering: a second renderer](../theory/10-terminal-rendering-second-renderer.md).
- Security: [threat-model](../security/threat-model.md) (the sanitizer analysis underwriting [04](04-sanitizer-context-freedom.md)).
- Design decisions: [ADR-0017 — Common document IR](../design-decisions/0017-common-document-ir.md),
  [ADR-0018 — Document streaming pipeline](../design-decisions/0018-document-streaming-pipeline.md).
- Ledger: [`CHANGELOG.txt`](../../CHANGELOG.txt) — the `[0.3.0-dev]` block records the experiments this pillar
  writes up in full.

## 6 · References

1. **D. E. Knuth.** *Literate Programming.* The Computer Journal **27**(2), 97–111, 1984. DOI
   [10.1093/comjnl/27.2.97](https://doi.org/10.1093/comjnl/27.2.97). — the presentation form used for
   algorithms throughout this pillar.
2. **Keep a Changelog**, v1.1.0. <https://keepachangelog.com/en/1.1.0/> — the ledger format the project's
   `CHANGELOG.txt` follows.
3. The semiring, weighted-transducer, WPDA, and data-stream foundations are cited in full in
   [theory/08 §7](../theory/08-common-document-ir.md#7--references) and
   [theory/09 §11](../theory/09-document-streaming-and-the-wpda.md#11--references); pages 01–07 reuse those
   references rather than re-deriving them.
