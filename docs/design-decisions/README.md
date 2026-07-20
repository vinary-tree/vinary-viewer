# Design decisions (ADRs)

This directory records the **architecture decisions** behind vinary-viewer as lightweight
**Architecture Decision Records (ADRs)**. An ADR captures *one* significant choice: the context that
forced it, the decision taken, its status, the consequences, the alternatives considered, and the
trade-offs. ADRs are immutable history — when a decision changes, we add a **new** ADR that supersedes
the old one rather than rewriting it.

> **What an ADR is.** A short, dated document describing a single architectural decision and its
> rationale, popularized by Michael Nygard's *"Documenting Architecture Decisions"* (2011). Each ADR is
> numbered sequentially and lives forever, so the project's reasoning is reconstructable.

---

## Status legend

| Status         | Meaning                                                                                  |
|----------------|------------------------------------------------------------------------------------------|
| **Accepted**   | The decision is in force and reflected in the current code.                               |
| **Proposed**   | Under consideration; not yet implemented (often pairs with a *Forthcoming (planned)* feature). |
| **Superseded** | Replaced by a later ADR; kept for history. The superseding ADR is linked.                |

---

## Template

Copy this skeleton for a new ADR. Keep it short and pedagogical, and **cite code evidence** (file /
namespace) for each claim.

```markdown
# NNNN — <short imperative title>

- **Status:** Accepted | Proposed | Superseded (by ADR-XXXX)
- **Date:** YYYY-MM-DD
- **Deciders:** <who/role>

## Context
Why a decision was needed — the forces, constraints, and prior state.

## Decision
What we decided, stated plainly. Cite the code that embodies it.

## Consequences
What becomes true as a result — positive and negative.

## Alternatives considered
The options not taken, and why.

## Trade-offs
What we gave up to get what we gained.
```

The next free number is **0030**.

---

## Index

| ADR  | Title                                                                                             | Status     |
|------|---------------------------------------------------------------------------------------------------|------------|
| [0001](0001-electron-42-supersedes-13.md) | Electron 42 supersedes the v0.1.0 Electron 13 pin                          | Accepted   |
| [0002](0002-render-markdown-in-renderer.md) | Render Markdown in the renderer, keep main a thin IO service             | Accepted   |
| [0003](0003-ref-innerHTML-no-vdom-body.md) | Write the document body via a ref + `innerHTML` (no VDOM diffing)          | Accepted   |
| [0004](0004-ds-rev-bridge-vs-re-posh.md) | A `:ds/rev` transaction-revision bridge instead of re-posh internals        | Accepted   |
| [0005](0005-datascript-nil-as-absence.md) | Model absent attributes as absence (omit/retract), never nil datoms       | Accepted   |
| [0006](0006-multi-watcher-live-refresh.md) | One `chokidar` watcher per retained path for live-refresh                 | Accepted   |
| [0007](0007-css-mask-themed-watermark.md) | A CSS-mask + `currentColor` themed watermark (one asset, all themes)       | Accepted   |
| [0008](0008-datascript-plus-app-db-split.md) | Superseded split state: DataScript-owned docs/tabs                       | Superseded |
| [0009](0009-mediator-ipc-over-point-to-point.md) | A single `window.vv` mediator seam over point-to-point IPC          | Accepted   |
| [0010](0010-bounded-content-retention-and-render-metadata.md) | Bound content retention to tab histories and emit Markdown render metadata | Accepted |
| [0011](0011-font-awesome-icons-self-hosted-fonts.md) | Font Awesome icons + self-hosted fonts, vendored at build time; no CSS framework | Accepted |
| [0012](0012-unified-history-and-in-pane-directory-browser.md) | One history spine, an in-pane directory browser, and persisted Up/Down trail memory | Accepted |
| [0013](0013-in-renderer-pdfjs.md) | Render PDFs in the renderer with pdf.js, retiring the native PDF view | Accepted |
| [0014](0014-native-ad-blocking-ghostery.md) | Native ad/tracker blocking with @ghostery/adblocker-electron | Accepted |
| [0015](0015-scoped-extension-runtime-gpl-free.md) | A GPL-free, scoped Chrome-extension runtime (password managers + ad-blocker-class) | Accepted |
| [0016](0016-main-process-simple-optimization.md) | Compile the Electron main process with `:simple`, not `:advanced` | Accepted |
| [0017](0017-common-document-ir.md) | A common document IR with a weighted-transducer pipeline (all formats → one IR → HTML) | Accepted |
| [0018](0018-document-streaming-pipeline.md) | A document-streaming pipeline (bounded-memory, WPDA-segmented) — large docs stream, small stay batch | Accepted |
| [0019](0019-terminal-preview-layer.md) | A terminal preview layer (CLI + TUI) as a second renderer over the shared IR/streaming spine | Accepted |
| [0020](0020-org-mode-via-uniorg.md) | Org-mode (.org) support via uniorg, reusing the common IR | Accepted |
| [0021](0021-bidirectional-source-preview-jump.md) | Bidirectional "Go to source" / "Go to preview" jump | Accepted |
| [0022](0022-pre-dom-figure-sizing.md) | Pre-DOM figure & inline-Mermaid sizing (no post-insert re-scale) | Accepted |
| [0023](0023-streaming-scrollbar-and-pacing.md) | Pre-estimated streaming scrollbar + rAF pacing | Accepted |
| [0024](0024-org-export-blocks-front-matter-and-math.md) | Org export blocks, front matter, and math: rendering what uniorg drops | Accepted |
| [0025](0025-latex-rendering-via-unified-latex.md) | LaTeX (.tex) rendering via unified-latex, and the Document↔PDF switch | Accepted |
| [0026](0026-diff-rendering-side-by-side-and-repo-filetypes.md) | Diff (.diff/.patch) rendering + side-by-side, standard repo filetypes, reverse PDF↔source switch | Accepted |
| [0027](0027-remote-files-over-ssh.md) | Opening remote files & directories over SSH (ssh2, virtual URIs, polling refresh) | Accepted |
| [0028](0028-composable-rendering-features.md) | Composable rendering features (native deterministic recognizers) — explored, then reversed | Superseded |
| [0029](0029-mature-parsers-shared-ir-features.md) | Mature parsers + a shared IR/feature layer (reverses 0028); Org gap-closing; backend parity gate; weighted PDF reflow; HTML facet | Accepted |
| [0030](0030-fallback-project-roots.md) | Fallback project roots: a file in no git repository adopts its containing directory (bounded BFS walk, realpath'd root, containment-aware merge, Remove from Files) | Accepted |

---

## How the ADRs relate

These decisions reinforce one another into a coherent reactive architecture. The **foundational
twelve** (0001–0012) establish the reactive core:

- **0001** sets the platform (modern Electron), which **enables** the contextBridge seam in **0009** and
  the in-renderer rendering in **0002**.
- **0008** established the original two-store split; **0010** updates that split for the current
  tab-history model. **0004** is still the bridge that makes the DataScript cache reactive to re-frame;
  **0005** is the discipline that keeps that cache clean.
- **0002** puts rendering in the renderer; **0003** is how the rendered HTML reaches the screen.
- **0006** is the live-refresh mechanism; **0010** bounds its watcher and cache lifetimes to retained
  tab histories while preserving scroll position and tabs.
- **0007** is a focused UI decision (the empty-state watermark) that rides on the same theme-token
  system used everywhere else.
- **0010** refines **0006** and **0008** for the current tab model: tab histories define retained file
  ownership, while DataScript is a bounded content cache with render metadata.
- **0011** dresses the chrome: a self-hosted Font Awesome icon set + self-hosted Noto Sans / Fira Code
  fonts, both vendored at build time by the same mechanism as the tree-sitter grammars (**0001**'s modern
  Electron makes the `file://` self-host viable), and themed through the same `--vv-*` token system used
  everywhere else — the bespoke CSS kept over any framework.
- **0012** makes every navigation affordance a thin interface over **0010**'s per-tab history spine:
  filesystem navigation joins that history (directories render in-pane, piggybacking **0002**'s renderer
  Strategy registry and the `vv:content` spine), `Alt+Up`/`Alt+Down` retrace a persisted directory
  trail, and the native PDF/web overlays yield to DOM menus/dialogs. It reuses the **0009** mediator seam
  for the new `recent.edn` and adds no content IPC.

The **later fifteen** (0013–0027) build capability on that core without disturbing it:

- **0013** retires the main-owned native PDF view for **in-renderer pdf.js**, folding PDFs into the same
  renderer Strategy (**0002**) as every other document.
- **0014** and **0015** harden the web view — native ad/tracker blocking, and a GPL-free, *scoped* Chrome
  extension runtime (password managers + ad-blocker-class only).
- **0016** pins every release build to Closure `:simple`, not `:advanced`, so un-hinted Electron/interop
  calls survive optimization.
- **0017** is the pivot of the 0.3 cycle: one **common document IR** every format parses into, lowered to
  HTML through a single sanitizer — the spine **0018**–**0027** all build on.
- **0018** streams large documents in bounded memory over that IR (WPDA-segmented); **0023** gives streaming
  a pre-estimated scrollbar and steady rAF/idle pacing; **0022** sizes figures pre-DOM so nothing re-scales
  on insert.
- **0019** adds the **terminal previewer** (`vv-cli` / `vv-tui`) as a *second renderer* over the same IR +
  streaming spine — HTML for the GUI, ANSI for the tty.
- **0020** and **0024** bring **Org-mode** through the IR (uniorg + the shared hast suffix), rendering what
  uniorg drops (export blocks, front matter, math); **0025** brings **LaTeX** (`.tex`) via unified-latex plus
  the Document↔PDF switch; **0026** brings **diff/patch** rendering (unified + split) and standard repo
  filetypes.
- **0021** adds bidirectional "Go to source" / "Go to preview" jumps over the IR's per-node source positions.
- **0027** opens **remote files over SSH** (`ssh://` / `sftp://`) as a virtual backend that reuses the entire
  pipeline — renderers, streaming, paging, refresh — main-side, with secrets never leaving the main process.
- **0030** closes the last gap in the **Files tab**: a file belonging to no git repository adopts its
  containing directory as a project root, so the sidebar works for scratch notes and standalone documents
  exactly as it does inside a checkout.

Together, **0017**'s IR is the hinge: every 0.3 capability is a new *edge* (a front-end or a back-end) on one
core, which is why they compose without conflict.

For the broader picture see [`docs/architecture/01-overview.md`](../architecture/01-overview.md), the
theory pillar under [`docs/theory/`](../theory/), and the concrete realization in
[`docs/architecture/07-common-ir-streaming-and-terminal.md`](../architecture/07-common-ir-streaming-and-terminal.md).
