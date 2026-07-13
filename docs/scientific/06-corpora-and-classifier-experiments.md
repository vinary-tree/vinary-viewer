# 06 — Corpora and classifier experiments

**Status: Measured + ledgered.** This page inventories the test corpora and fixtures the project measures
against, and records three findings about the *file-kind classifier* as a scientific ledger — each a case
where a defect survived because a test measured a **stub** instead of the real subject. The unifying
methodology lesson is stated once, up front, and then witnessed three times.

> **Methodology lesson.** *Drive the real classifier, not a hand-written stub.* A test whose input is a
> fabricated `{kind: "org"}` payload cannot observe a bug in the code that *decides* the kind. Every finding
> below is an instance of the same mistake and the same fix: replace the stub with the production classifier.

---

## 1. The corpora

vinary-viewer's tests measure against fixtures rather than mocks wherever a real artifact is cheap to carry.

| Corpus / fixture | Where | Exercises |
|------------------|-------|-----------|
| Format sample docs | `docs/screenshots/samples/*` (`showcase.md`, `math.md`, `report.docx`, `data.csv`, `app.log`, `bundle.zip`, `live-refresh.mmd`, `fuzzy-rho.pdf`) | headless screenshot regeneration + manual preview checks |
| Smoke fixtures | `test/fixtures/*` (an in-process `ssh-server`, `smoke.pdf`, an extension probe) | the [smoke harnesses](../engineering/03-test-strategy.md) |
| The byte-parity corpora | the 300 KiB `.tex` / `.md` and 301 KiB `.org` documents driven by the Electron smoke | [01 — Byte-parity verification](01-byte-parity-verification.md) |
| The MathJax corpus | 154 documents / 1743 inline-math occurrences | [05 — MathJax ink-loss experiment](05-mathjax-inkloss-experiment.md) |
| Unit-test inputs | generated inside `test/vinary/**` (batch splittings, brace-nesting logs, semiring elements) | the [property tests](../engineering/03-test-strategy.md) |

The corpora exist so that measurements are over *representative* inputs — a real 301 KiB Org file, a real
brace-nested log — rather than over inputs shaped to pass.

---

## 2. Finding A — the JS/CLJS classifier divergence

**Context.** The file-kind classifier exists **twice**: the ClojureScript classifier used by the app, and its
JavaScript twin `content_service.js` used by the Electron-free content service (and thus by the terminal tools
and the smoke). Two implementations of one decision are two chances to disagree.

**Defect.** `content_service.js` kept `.org` in its `textExts` set and had **no Org arm**, so an `.org` file —
a text file that happens to have a bundled tree-sitter grammar — was "upgraded" to `source` and rendered as
highlighted source instead of through uniorg. An Org table (`| a | b |`) could additionally trip the
delimited-CSV sniff in `openLocal` (Finding B).

**Why it survived CI.** The Electron smoke drove Org through a **hand-written `{kind: "org"}` stub**, not the
real classifier — so it asserted on a payload that presupposed the very decision the bug got wrong. Every other
fixture stubbed its payload too, which is exactly why *both* this divergence and the missing `:meta {:size}`
(Finding C) survived.

**Fix + guard.** The smoke now drives Org through the **real `content_service` classifier**
([`CHANGELOG`](../../CHANGELOG.txt)), so the JS twin's classification is measured, not assumed.

---

## 3. Finding B — the delimited-sniff false positive

**Defect.** `openLocal`'s content sniff for delimited data (CSV/TSV) matched on the presence of pipe-delimited
rows. An Org table — legitimately full of `| … | … |` lines — looks exactly like a pipe-delimited table, so an
Org document could be mis-sniffed as a spreadsheet.

**Lesson.** A *content* sniff is a heuristic classifier, and a heuristic must be measured against inputs that
adversarially resemble the wrong class. The Org corpus (which contains tables) is that adversarial input; once
Org was driven through the real path (Finding A), the false positive became observable and was fixed so `.org`
nested even inside an archive now renders correctly.

---

## 4. Finding C — the `:meta {:size}` streaming gate

**Defect.** Large Markdown documents **never actually streamed**. The stream gate `stream.flag/enabled?`
compares `$`(:size\ \text{meta}) \ge \text{threshold}`$`, but `service.cljs`'s `:text` route — which serves
Markdown, Org, source, and diagram — sent **no `:meta` at all**, so the comparison was permanently
`$`0 \ge 256\ \text{KiB}`$`, i.e. always false. For its first four phases the progressive engine ran on *no
real file*.

**Why it survived CI.** The byte-parity smoke passed because it **stubs `meta: {size}`** into a fake content
service — again measuring a stub, not the route. The stub supplied the size the real route omitted.

**Fix + guard.** The `:text` route now sends `:meta {:size}`, and a unit test (`flag-test`) pins that a `nil`
size never streams — turning the invariant "no size ⇒ no stream" into an executable assertion rather than an
accident.

---

## 5. The pattern, and the correction

All three findings share a signature and a remedy:

```text
signature :  a test's input is a STUB of the subject's output
             ⇒ the test cannot observe defects in the code that PRODUCES that output
remedy    :  drive the real producer (classifier / route / content_service),
             and reserve stubs for the collaborators you are NOT testing
```

The correction the project adopted is structural: the Electron smoke now exercises the **real**
`content_service` classifier and the **real** `:text` route for the formats those bugs hid in. The scientific
value of the episode is the generalized rule — *a stub in the position of the subject is a blind spot*, and
corpus-driven, real-path measurement is how the blind spot is removed.

## 6. See also

- [engineering/03 — Test strategy](../engineering/03-test-strategy.md) — the harnesses and the dev-vs-real split.
- [01 — Byte-parity verification](01-byte-parity-verification.md) — the smoke whose stub hid Finding C.
- [05 — MathJax ink-loss experiment](05-mathjax-inkloss-experiment.md) — the corpus-over-example lesson, applied to rendering.
- [`CHANGELOG.txt`](../../CHANGELOG.txt) — the `[0.3.0-dev]` entries recording all three findings.
