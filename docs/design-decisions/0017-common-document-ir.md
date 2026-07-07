# 0017 — A common document IR with a weighted-transducer pipeline

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** vinary-viewer maintainers

## Context

Every document type rendered on its own path (see the [renderer-architecture map](../architecture/06-renderer-runtime.md)
and [ADR-0002](0002-render-markdown-in-renderer.md)/[0003](0003-ref-innerHTML-no-vdom-body.md)):

- **Markdown** — remark/rehype → an HTML string → `.markdown-body` innerHTML.
- **Office** — a *second*, main-process HTML producer (`content_service.js`) with its **own regex sanitizer**,
  landing in the *same* `.markdown-body` sink.
- **Plain text** — a third producer (`plain-html`).
- **PDF / source / table / log / archive** — bespoke views (pdf.js canvas, CodeMirror, Reagent-over-data).

So three HTML producers funnelled into one sink behind **two divergent sanitizers**, and cross-cutting
capabilities were duplicated or single-format: figure-sizing and `data-vv-source-*` positions were
**Markdown-only**; a table of contents existed only for Markdown and PDF; office had **no** TOC and went
through a weaker regex sanitizer than the GitHub allowlist Markdown used.

The maintainer proposed the principled alternative: **parse every format into one common AST/IR, transform it,
and lower it to the output**, with every node taggable with metadata, modelled on the maintainer's own
weighted-automata / PDA / transducer work (`lling-llang`, `mettail-rust`, MeTTaIL).

## Decision

Introduce a **common document IR** (`vinary.ir.*`) — a uniform tagged tree with open per-node metadata — and
route rendering and capabilities through it. Specifically:

1. **Re-implement the pattern in ClojureScript**, treating `lling-llang`/`mettail-rust` as *blueprints*. The
   Rust crates cannot reach the sandboxed browser cleanly — `lling-llang` makes Z3 a non-optional dependency
   gating its richest (symbolic) layer, and no WASM/FFI boundary exists — so a native re-implementation is the
   correct fit for an Electron renderer.
2. **Carry the full weighted machinery** (semiring core, weighted tree transducer, WPDA + streaming decoder,
   Earley-over-lattice + packed forest) — DOM-free and node-tested — even though the directed HTML lowering
   only needs the Boolean semiring, so that ambiguous PDF/log **segmentation** can be ranked by a semiring
   (Tropical) rather than a hard-coded heuristic. The theory is documented in
   [theory/08](../theory/08-common-document-ir.md).
3. **Lossless-by-construction PDF (hybrid).** Extract a reflowable page/block/line/run **text facet** from
   pdf.js while keeping the pdf.js **canvas raster** as the faithful visual facet. No display fidelity is ever
   discarded — vector graphics stay exact on the canvas facet while text becomes reflowable/searchable on the
   IR facet. (This resolves the "reflow is lossy for vector graphics" objection: the two facets coexist on the
   same page node; a page with no extractable runs is canvas-only.)
4. **One HTML back-end + one sanitizer.** `ir/backend/html` lowers the IR to HAST and serialises it with the
   same `rehype-stringify`; `ir/backend/sanitize` holds the single GitHub-allowlist schema, shared by the
   Markdown pipeline and the office path. Because the front-ends preserve tags/properties verbatim,
   `HAST -> IR -> HAST` round-trips **losslessly** — the rendered HTML is byte-identical to the legacy output
   (`ir.parity-test` + the electron smoke), so the cutover is invisible.
5. **Migrate incrementally behind a flag** (`:vv/ir`), format by format, with the app green at every step;
   flip the flag **default-on** once parity was proven; then **retire the legacy** — make the IR the
   unconditional render path and remove (comment out) the legacy string render, the main-process office regex
   sanitizer, and the flag itself.

## Consequences

**Positive.**

- Office documents now gain a **heading TOC** and are sanitised by the **GitHub allowlist** (a strict upgrade
  from the regex sanitizer), because office HTML is parsed through `rehype-raw` + the shared schema + slug.
- Capabilities are defined once over the IR: `capability/toc` reproduces the Markdown TOC harvest and extends
  it to office/source/PDF; figure-sizing already covered office (both render via `markdown-body`).
- The PDF text facet enables find/copy over extracted text and a font-size **outline** for the many PDFs that
  ship no `getOutline`, without touching the faithful canvas render.
- The weighted core (semiring/WPDA/Earley/forest) is reusable substrate for future ambiguity-ranked parsing.

**Legacy retired.**

- The common IR is the **unconditional** render path. The legacy Markdown string `render`, the main-process
  office regex sanitizer (`content_service.js/sanitizeHtml`), and the `:vv/ir` flag / `:ir/set-enabled` toggle
  are **removed** (kept `#_`-/comment-disabled for reference per the repo's comment-don't-delete rule). Office
  HTML is now sanitised **solely** by the GitHub-allowlist IR schema — strictly stronger than the retired
  regex — and there is no un-IR office path, so no un-sanitised path exists. Markdown/office render errors
  surface as the normal error view. Byte-parity is guaranteed structurally by the lossless HAST round-trip
  (`ir.parity-test`), not by an A/B fallback.

**Neutral / deliberate.**

- Table/log/archive keep their **interactive Reagent views** (paging/sorting), which IR-produced HTML would
  regress; their IR is the canonical structural parse, and the content-agnostic in-page find already covers
  them, so the capabilities that *make sense* for those types are served.
- Source and PDF **outlines are wired into the sidebar Contents**. The source view derives a code outline from
  its tree-sitter parse (`syntax/parse-outline` → `:toc/set`) and a Contents click scrolls the CodeMirror view
  to the line (`:toc/scroll` resolves an `L<line>` id via `syntax/scroll-source-to-line!`). The PDF renderer
  falls back to the font-size outline (`ir.frontend.pdf/outline`, page-anchor ids) when `getOutline` is empty,
  so outline-less PDFs gain a navigable Contents that reuses the existing page-scroll.

**Risks and mitigations.**

- *Performance* — the heavy WPDA/Earley/forest run **only** on ambiguous segmentation (PDF/log); Markdown/
  office/source lowering is an `O(n)` Boolean single-derivation walk. Segmentation is beam-bounded.
- *Parity* — guaranteed structurally by the lossless HAST round-trip (`ir.parity-test`: `HAST -> IR -> HAST ->
  HTML == HAST -> HTML`) and exercised end-to-end by the dev + release electron smoke (markdown renders with
  the expected H1/math/mermaid; office renders + sanitises). With the legacy retired there is no A/B fallback —
  the round-trip losslessness is the guarantee.
