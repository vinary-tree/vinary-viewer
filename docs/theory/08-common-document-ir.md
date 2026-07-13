# The common document IR — a weighted-transducer pipeline

**Status: Implemented (v0.3.0), default-on behind the `:vv/ir` escape hatch.**

![Common document IR pipeline](../diagrams/component-common-ir.svg)

*Format front-ends parse into one tagged IR; a weighted tree transducer lowers it to HTML through a single
sanitizer; capabilities (TOC, find, positions, figures) read the same tree.*

---

## 1 · Motivation

Before v0.3.0 every document type rendered on its **own** path: Markdown through the remark/rehype pipeline
to an HTML string; office through a *second*, main-process HTML producer with its *own* regex sanitizer; PDF
through a pdf.js canvas; source through CodeMirror; tables/logs/archives through bespoke Reagent views. Three
independent producers funnelled into one `.markdown-body` sink guarded by **two** different sanitizers, and the
cross-cutting capabilities were either duplicated or hard-scoped to one format (figure-sizing and source-span
tagging were Markdown-only; a table of contents existed only for Markdown and PDF).

The **common document IR** replaces that divergence with a single principle: *parse every format into one
metadata-rich intermediate representation, transform it, and lower it to the desired output.* Capabilities are
then defined **once**, over the IR, and every node is **taggable** with metadata (source spans, bounding boxes,
semantic roles, provenance). The design is a ClojureScript re-implementation of the weighted-automata /
transducer machinery from the author's `lling-llang` and `mettail-rust` projects — chosen because those
crates cannot reach the sandboxed browser cleanly (their richest layer is gated behind a native Z3
dependency), so they serve as **blueprints** rather than libraries.

### 1.1 · Key terms

| Term | Definition |
|------|------------|
| **IR** | *Intermediate representation* — the uniform tagged tree every format parses into (`vinary.ir.node`). |
| **HAST** | *HTML Abstract Syntax Tree* — the rehype tree model; the Markdown/office front-ends mirror it into the IR and the back-end lowers back to it. |
| **Semiring** | An algebraic structure $`(K, \oplus, \otimes, \bar{0}, \bar{1})`$ (§3) whose choice tunes *what* a parse/transduction computes. |
| **WPDA** | *Weighted pushdown automaton* — recognises context-free nesting, weighted by a semiring (§5). |
| **Tree transducer** | A rewrite system mapping input trees to weighted output trees (§4). |
| **Facet** | A per-node metadata view (e.g. a PDF page's *reflowable-text* facet vs its *faithful-canvas* facet). |

---

## 2 · The IR node

The IR is a single record — `vinary.ir.node/Node` — carrying `{:kind :children :text :meta}`. A record (not a
bare map) gives V8 hidden-class-stable field access and protocol dispatch on the transducer hot path, while the
open `:meta` map keeps per-format facets typeless. Formally the IR is an **unranked, ordered, node-labelled
tree**: writing $`\Sigma`$ for the set of kinds, a node is

$`n = (k,\ \langle c_1,\dots,c_m\rangle,\ t,\ \mu)`$ with $`k \in \Sigma`$, children $`c_i`$ themselves IR
nodes, optional leaf text $`t`$, and metadata $`\mu`$.

Metadata keys (all optional): `:span {:start/:end {:line :column :offset}}` (source provenance, the analogue of
`lling-llang`'s `SyntaxNode.Range`), `:bbox {:x :y :w :h :page}` (PDF/geometry facet), `:role` (semantic role,
defaulting to `:kind`), `:level`, `:id`, `:attrs` (HAST properties), `:lang`, `:tex`/`:display?`,
`:provenance`, and `:weight` (a semiring element, §3).

**Anchor identity.** `vinary.ir.meta/anchor-id` folds a node's slug and a per-document occurrence counter into
a stable, collision-free id (rehype-slug-compatible: `notes`, then `notes-1`, `notes-2`, …), so two
byte-identical headings — or repeated PDF page titles — still receive **distinct** ids. This is what lets the
scroll-spy and find-jump target a specific occurrence rather than collapsing duplicates.

---

## 3 · Semirings — one algorithm, many objectives

A **semiring** is a set $`K`$ with two associative binary operations and their identities,

$`(K,\ \oplus,\ \otimes,\ \bar{0},\ \bar{1})`$,

where $`\oplus`$ is commutative with identity $`\bar{0}`$, $`\otimes`$ has identity $`\bar{1}`$ and annihilator
$`\bar{0}`$ (so $`a \otimes \bar{0} = \bar{0}`$), and $`\otimes`$ **distributes** over $`\oplus`$:

```math
a \otimes (b \oplus c) \;=\; (a \otimes b)\ \oplus\ (a \otimes c).
```

The power of the abstraction (Goodman 1999; Mohri 2009) is that **one** parser or transducer computes an entire
family of results by swapping $`K`$: $`\oplus`$ combines *alternative* derivations and $`\otimes`$ combines
*sequential* steps, so recognition, best-derivation, total probability mass, and multi-objective scores all
fall out of the same code. `vinary.ir.semiring` implements six weight types, each verified against the axioms in
`semiring-test`:

| Semiring | $`\oplus`$ | $`\otimes`$ | $`\bar 0,\ \bar 1`$ | Computes |
|----------|-----------|------------|--------------------|----------|
| **Boolean** | $`\lor`$ | $`\land`$ | $`\bot,\ \top`$ | recognition ("is there *any* valid derivation?") |
| **Tropical** | $`\min`$ | $`+`$ | $`+\infty,\ 0`$ | best / lowest-cost derivation (Viterbi in $`-\log`$) |
| **Log** | $`-\log(e^{-a}+e^{-b})`$ | $`+`$ | $`+\infty,\ 0`$ | total mass in $`-\log`$ space (log-sum-exp) |
| **Probability** | $`+`$ | $`\times`$ | $`0,\ 1`$ | total probability mass |
| **Product** | componentwise | componentwise | componentwise | two objectives at once |
| **Lexicographic** | ordered-pair min | componentwise | componentwise | primary objective, tie-broken |

**Boolean** and **Tropical** are additionally **idempotent** ($`a \oplus a = a`$), which induces the *natural
order* $`a \preceq b \iff a \oplus b = a`$ that `best-parse` uses to select the optimum. Directed HTML lowering
uses the Boolean semiring (a single derivation); ambiguous PDF/log segmentation uses Tropical (rank by cost).

---

## 4 · Weighted tree transducers

Transformation is expressed as a **weighted top-down tree transducer** (`vinary.ir.transducer`; after Fülöp &
Vogler 2009). A transduction maps an input IR tree to a set of weighted output trees. A rule fires by
`(state, input-kind[, arity])` and its right-hand side is an *output pattern* that (a) builds output nodes,
(b) recursively transduces chosen input children at chosen states, and (c) — via `:all` — recurses over **all**
children of an unranked node (documents are unranked). A derivation's weight is the firing rule's weight
$`\otimes`$ the product of its children's weights; alternative derivations form the $`\oplus`$-set:

```math
w(\text{output}) \;=\; w_{\text{rule}} \;\otimes\; \bigotimes_{i} w(\text{child}_i).
```

Transductions **compose exactly**: `compose-transduce` realises $`(\tau_1 ; \tau_2)`$ by transducing with
$`\tau_1`$ and then transducing each output with $`\tau_2`$, multiplying weights. A **deterministic** lowering
— one rule per kind under the Boolean semiring — yields exactly one output; that is how `vinary.ir.backend.html`
lowers the IR to HAST, which a single `rehype-stringify` then serialises. Because the Markdown/office
front-ends preserve each element's tag and properties verbatim, `HAST -> IR -> HAST` round-trips **losslessly**,
so the rendered HTML is byte-identical to the legacy pipeline (verified in `ir.parity-test`) — the migration is
invisible.

---

## 5 · Weighted pushdown automata and Earley-over-lattice

Fixed-layout inputs (a PDF page's positioned glyph runs; a log's physical lines) must be **segmented** into
logical structure — runs into lines, lines into blocks — and that segmentation is genuinely **ambiguous**
(multi-column layouts, multi-line records). The IR carries two interchangeable weighted recognisers for it:

- A **weighted pushdown automaton** $`P=(Q,\Sigma,\Gamma,q_0,Z_0,F,\Delta,\rho)`$ (`vinary.ir.wpda`) with a
  streaming decoder (`vinary.ir.decode`) whose `legal-next`/`advance` drive one input symbol at a time under a
  bounded $`\varepsilon`$-closure — the pushdown analogue of grammar-constrained generation. It recognises
  context-free nesting that no finite-state machine can (e.g. balanced brackets, $`a^n b^n`$).
- An **Earley recogniser over a lattice** (`vinary.ir.earley`; Earley 1970) whose *Scan* step follows lattice
  edges rather than a single string position, intersecting a CFG with a hypothesis **DAG**. It emits a
  **packed parse forest** (`vinary.ir.forest`) — shared structure representing exponentially many parses
  compactly — from which `best-parse` extracts the $`\oplus`$-optimal derivation (Viterbi over the forest).

For PDF the line/block grouping decision carries a Tropical *vertical-misalignment* cost, so the forest ranks
alternative segmentations while a deterministic greedy baseline handles the common single-column case; the
machinery is beam-bounded so pathological pages cannot explode.

---

## 6 · The pipeline and per-format mapping

The dataflow (see the diagram above) is **front-end -> IR core -> back-end/capabilities**:

| Format | Front-end (`ir/frontend/*`) | What the IR adds |
|--------|-----------------------------|------------------|
| Markdown | `hast->ir` (mirrors the rehype HAST) | canonical tree; the lowering + TOC now run through it |
| Org (.org) | uniorg → HAST → **reuses Markdown's `hast->ir`** (ADR-0020/0024) | the same TOC / math / task-lists / streaming, no Org-specific renderer |
| LaTeX (.tex) | unified-latex → HTML string → `raw` node → **reuses Markdown's `hast->ir`** (ADR-0025) | the same TOC / MathJax / figures / highlighting; a macro preprocessor; no LaTeX compiler |
| Office (docx/ODF) | `html->ir` (rehype-raw + shared sanitizer + slug) | a **heading TOC** and the **GitHub-allowlist sanitizer** it previously lacked |
| Source code | `tree->ir` (web-tree-sitter) | the SyntaxNode analogue + a line-anchored **code outline** |
| PDF | `doc->ir` (pdf.js text runs) | a **reflowable** page/block/line/run facet (find/copy/reflow) *augmenting* the faithful canvas |
| Table / log / archive | `payload->ir` | the canonical structural parse (`:table/:row/:cell`, `:line`, listing `:list`) |

**Lossless-by-construction PDF.** The PDF page node carries two facets: the reflowable text/structure facet
(above) and the **faithful visual facet** — the pdf.js canvas raster, pixel-exact at device DPI. No display
fidelity is ever discarded; a page with no extractable runs falls back to canvas-only. This resolves the
apparent "reflow is lossy for vector graphics" tension: vectors stay exact on the canvas facet while text
becomes reflowable on the IR facet (see the discussion in the design decision, ADR-0017).

---

## 7 · References

1. **J. Goodman.** *Semiring Parsing.* Computational Linguistics 25(4), 573–605, 1999.
   [ACL Anthology J99-4004](https://aclanthology.org/J99-4004/).
2. **M. Mohri.** *Weighted Automata Algorithms.* In *Handbook of Weighted Automata*, Springer, 2009.
   DOI [10.1007/978-3-642-01492-5_6](https://doi.org/10.1007/978-3-642-01492-5_6).
3. **Z. Fülöp and H. Vogler.** *Weighted Tree Automata and Tree Transducers.* In *Handbook of Weighted
   Automata*, Springer, 2009. DOI [10.1007/978-3-642-01492-5_9](https://doi.org/10.1007/978-3-642-01492-5_9).
4. **J. Earley.** *An Efficient Context-Free Parsing Algorithm.* Communications of the ACM 13(2), 94–102, 1970.
   DOI [10.1145/362007.362035](https://doi.org/10.1145/362007.362035).
5. **A. V. Aho and J. D. Ullman.** *The Theory of Parsing, Translation, and Compiling.* Prentice-Hall, 1972
   (pushdown transducers; packed parse forests).
