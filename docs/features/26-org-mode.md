# 26 — Org-mode (.org) documents

vinary-viewer renders Emacs **Org-mode** files the way GitHub does — as a formatted document — and, because Org
is a *literate* format, it syntax-highlights the code inside every `#+begin_src <lang>` block.

## What you get

| Capability | Behaviour |
|---|---|
| **Preview** | `.org` opens rendered (headings, lists, tables, links, inline `*bold*` / `/italic/` / `=verbatim=` / `~code~`, images, blockquotes). GFM-like, like the Markdown preview. |
| **Contents outline** | The sidebar Contents panel lists the Org headings (slugged, with scroll-spy) — click to jump; the outline tracks your scroll position. |
| **Nested code highlighting** | Each `#+begin_src python` / `clojure` / `emacs-lisp` / … block is highlighted in its own language (web-tree-sitter, with a highlight.js fallback). `emacs-lisp` and `el` map to the bundled Elisp grammar. |
| **View Source** | Toggle to the raw `.org`, syntax-highlighted by a bundled tree-sitter-org grammar (headings, directives, `#+begin_src` block names, tags, comments, timestamps, markup). |
| **Live refresh** | Editing the `.org` on disk re-renders it in place (like Markdown), preserving the Contents outline and scroll position. |
| **Figures** | Embedded images are pre-sized like everywhere else ([feature 12](12-diagram-rendering.md) / [ADR-0022](../design-decisions/0022-pre-dom-figure-sizing.md)) — no post-insert re-scale. |

## How it works

Org is a new **input frontend** over the [common document IR](../theory/08-common-document-ir.md), not a separate
engine. A `.org` file is parsed to HTML-AST by **uniorg** (`uniorg-parse` → `uniorg-rehype`), then run through the
**exact same** post-parse pipeline as Markdown (sanitize → slug → highlight → URL-rewrite → image-wrap → source
positions → metadata) and the **same** `hast → IR`. Everything downstream — the heading TOC, figure pre-sizing,
scroll-spy, and nested-language highlighting — is inherited unchanged. See
[ADR-0020](../design-decisions/0020-org-mode-via-uniorg.md) for the full design.

```plantuml
@startuml
skinparam defaultTextAlignment center
skinparam ArrowColor #555555
rectangle ".org file" as F #FDE68A
rectangle "uniorg-parse →\nuniorg-rehype\n(Org → HTML-AST)" as U #FDE68A
rectangle "shared app suffix\n(sanitize / slug / highlight /\nsource-positions / metadata)" as S #DCFCE7
rectangle "common IR\n(headings, code-blocks,\ntables, images…)" as IR #E0F2FE
rectangle "preview HTML\n+ Contents outline\n+ nested-language highlighting" as OUT #BBF7D0
F -> U -> S -> IR -> OUT
@enduml
```

## Known limitation

Right-click **"Go to source" / "Go to preview"** ([feature 13](13-source-preview-tree-sitter.md)) is **Markdown-only**:
`uniorg-rehype` does not carry source positions onto its HTML-AST, so Org preview nodes have no per-element line
map. Org navigates by **heading** through the Contents outline instead. This is a upstream (uniorg) limitation,
not a design choice — if uniorg gains position support the jump lights up for Org with a one-line change.

Streaming is Markdown/PDF/log-only for now; `.org` documents render on the (fast, byte-identical) batch path.
