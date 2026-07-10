# 0024 — Org export blocks, front matter, and math: rendering what uniorg drops

- **Status:** Accepted
- **Date:** 2026-07-09
- **Deciders:** Vinary Tree (maintainer)

## Context

[ADR-0020](0020-org-mode-via-uniorg.md) added Org as an input frontend over the
[common document IR](0017-common-document-ir.md): `.org` → `uniorg-parse` → `uniorg-rehype` → the shared app
suffix → `hast->ir`. The premise was that Org is a **semantic superset** of GFM — its node set contains GFM's —
so everything downstream of parsing could be shared verbatim.

The premise is right. The implementation had three gaps, all of the same kind: **a shared pipeline is not a
shared selector.** Several post-passes match a specific hast shape, and uniorg emits a different one, so the pass
silently does nothing.

### The failure that surfaced it

An Org invoice — a real document — rendered as a **completely blank preview pane**: no error, no `Rendering…`
placeholder, while View Source highlighted it correctly. It parses to exactly:

```
{ 'org-data': 1, keyword: 19, 'export-block': 1 }        ← 19 #+… lines + one #+BEGIN_EXPORT latex block
```

`uniorg-rehype` has no `keyword` handler (keywords are dropped), and its `export-block` handler emits a raw node
**only** when `backend === 'html'`, returning `null` for every other backend. So the hast root came out with zero
children, `ir.backend.html/lower` serialized `""`, and `:doc/html` became the empty string.

`""` is **truthy** in ClojureScript. The view's `(:doc/html doc)` catch-all matched, mounted an empty
`.markdown-body`, and set `innerHTML = ""`. From the app's perspective the render *succeeded* — which is exactly
why there was no error to see. Source view is a separate component keyed off the file extension, so it was
unaffected.

Two more gaps had the same shape:

- **Math never rendered.** `renderer.math/render-html-math` selects `code.language-math, code.lang-math,
  code.math-inline, code.math-display`. uniorg emits `span.math.math-inline` and `div.math.math-display` — never
  a `code` — and GitHub's sanitize schema strips `className` from `span`/`div` entirely. So `$E = mc^2$` in an
  Org file rendered as the literal text `E = mc^2`.
- **Task lists lost their checkboxes.** uniorg's `list-item` carries `checkbox: "on" | "off" | "trans" | null`
  but its default handler emits a bare `<li>`, discarding it.
- **TODO keywords rendered unstyled.** uniorg emits `<span class="todo-keyword TODO">`, where the *second* class
  is the keyword itself — unbounded, since it varies with the configured sequence — and GitHub's schema strips
  `className` from `span` outright.

Separately, two defects the Org work had left in adjacent code:

- **`content_service.js`'s classifier was never updated.** `.org` sat in `textExts` and `classifyName` had no org
  arm, so `vv --cli` / `vv --tui` upgraded it to `"source"` (a tree-sitter-org grammar is bundled) and printed
  highlighted Org markup instead of rendering it. It also let an Org `| a | b |` table trip the delimited-CSV
  content sniff in `openLocal`.
- **Markdown streaming never fired.** `stream.flag/enabled?` gates on `(:size meta)`, but `service.cljs`'s
  `:text` route — which serves markdown, org, source, and diagram — sent no `:meta` at all, so the gate always
  compared `0` against the 256 KiB threshold. The electron smoke only exercised streaming because it *stubs*
  `meta: {size}` into its fake content service.

## Decision

### 1. Normalize Org's hast into the GFM shapes the shared passes already match

`uniorg-rehype` accepts an `options.handlers` map, merged over its `defaultHandlers` and dispatched on the Org
node type; a handler returning a falsy value falls through to the built-in one. That is the injection point — no
fork, no AST-rewriting plugin.

| Node | Handler behaviour |
|---|---|
| `keyword` | capture `TITLE` / `SUBTITLE` / `AUTHOR` / `DATE`, return `nil` (uniorg still drops the node) |
| `export-block` | `html` → `nil` (keep the raw-HTML passthrough); otherwise `<pre><code class="language-<backend>">` |
| `list-item` with a checkbox | emit GFM's `<li class="task-list-item"><input type="checkbox" disabled>` |
| `plain-list` containing checkboxes | emit `<ul class="contains-task-list">` |
| `footnotesSection` option | emit `<h2>Footnotes</h2>` instead of the default bare `<h1>Footnotes:</h1>` |

Plus two hast transformers that run **before** the shared app suffix (so their output is sanitized, slugged, and
highlighted like any other content):

- `org-front-matter` — prepend `<h1>TITLE</h1>` and `<p><em>AUTHOR · DATE</em></p>`.
- `org-normalize` — one walk, two rules. Math: `span.math.math-inline` → `<code class="math-inline">` and
  `div.math.math-display` → `<pre><code class="math-display">…</code></pre>` (the `<pre>` wrapper is required
  because `render-html-math` replaces the code element's **parent** for display math). TODO keywords:
  `span.todo-keyword <KEYWORD>` → `span.todo` / `span.done`. Collapsing to a *state* class is what makes the
  allowlist possible at all — the keyword class is unbounded, so no schema could enumerate it.

The task-list shape needs **no** schema change: GitHub's allowlist already permits `ul.contains-task-list`,
`li.task-list-item`, and `input[type=checkbox][disabled]`. The sanitize schema gains **class names only** — no new
tags, attributes, or protocols — for `code.vv-tex-attempt` and `span.todo` / `span.done`.

### 2. `#+BEGIN_EXPORT latex`: attempt MathJax, fall back to a highlighted code block

Emacs' `ox-html` drops non-HTML export blocks. That is right for an **exporter** and wrong for a **viewer**:
silently swallowing 1188 characters of a document's body is a worse failure than showing it as code. So a
non-`html` export block always renders as a fenced code block in its backend's language, and a `latex` block is
first *attempted* as math.

The attempt needs two gates, because MathJax fails in two different ways. Measured against the app's own engine:

| input | throws? | emits `data-mjx-error`? |
|---|---|---|
| `\begin{center}…\end{center}` (the invoice) | no | **yes** — `Unknown environment 'center'` |
| `E = mc^2` | no | no |
| `\begin{align} a &= b \end{align}` | no | no |
| `\textbf{Hello} \\ World` | no | **no** — typesets into garbage |

1. **MathJax does not throw.** The engine loads the `noerrors` + `noundefined` TeX packages precisely to suppress
   raising, so the reliable failure signal is the **error node**, not an exception. `tex-error?` checks for
   `data-mjx-error` / `<merror>`; a genuine throw (a font "retry") is caught too.
2. **Success does not imply correctness.** Row 4 is prose built only from math-legal macros: it typesets cleanly
   into nonsense, and no error check can catch it. `tex-block-math?` therefore screens the source *before*
   attempting: it must carry a positive math signal (a math environment, `\[`, `$$`, `\(`) **and** no
   document-structure macro (`\begin{center|tabular|itemize|…}`, `\includegraphics`, `\usepackage`, …).

MathJax's `<svg>` is not in the sanitize allowlist, so — exactly as `render-html-math` already does — this pass
runs **post-sanitize** on the serialized HTML string, finding its targets via a `vv-tex-attempt` marker class the
frontend stamps and the schema preserves. On failure the marker is removed and the code block survives, so the
later tree-sitter pass highlights it as `language-latex`.

It is fast-pathed on a substring test, because the streaming sink runs `apply-posts` once **per block**: a
document with no marker never pays for a `DOMParser` round-trip. There is no `DOMParser` in `:node-test`,
`vv --cli`, or `vv --tui`, so the block simply stays a highlighted code block there — the same fallback.

### 3. Test the rendered-nothing case explicitly, everywhere

`ir.backend.html/blank?` is the contract; **truthiness is not**. The view tests it before its `(:doc/html doc)`
catch-all and shows an explicit *"Nothing to preview"* notice. The guard is format-agnostic: Markdown and office
documents get it too.

### 4. Fix the classifier twin and the streaming size gate

`content_service.js` gains an `orgExts` set and an `'org'` arm in `classifyName`, plus an `openLocal` branch that
short-circuits **before** the content sniff. `cli/core.cljs` and `tui/core.cljs` add `"org"` to `text-kinds`, and
`cli/render.cljs` gains an `"org"` arm backed by `org->ir` — the same `org-pipeline`, the same IR, the same ANSI
backend. `service.cljs`'s `:text` route now sends `:meta {:size …}`, which turns on streaming for large Markdown
**and** Org.

Making Org reuse GFM's task-list shape exposed a gap on the far side of the IR: `ir.backend.ansi` ignored the
`<input type="checkbox">` entirely, so the terminal printed a checked and an unchecked item identically — for
**Markdown too**. The backend now reads the state (`☐` / `☑`; an ordered task item keeps its ordinal; a plain
item stays a bullet). This is the payoff of a shared IR working in the other direction: fixing one backend fixed
both front-ends at once.

### 5. Put Org on the progressive streaming engine

The engine commits **IR children**, not Markdown nodes — it is already format-agnostic. `stream-blocks` is
generalized to `stream-blocks*`, parameterized by the pipeline builder, and `org-stream-blocks` is a thin wrapper
over `org-pipeline`. `stream.flag` gains `"org"` (threshold 256 KiB), `scheduler`'s `posts-for` / `sep-for` gain
an `"org"` arm, and `ir-stream-body` gains an `"org"` block provider. No new engine, no new parser.

Byte-parity holds by construction and is guarded by a test: the inter-block whitespace lives in emitted `:text`
leaves rather than a re-synthesized `\n`, so `concat(map lower children) == lower(document)` exactly.

## Consequences

**Good.** The invoice renders (title, author/date, highlighted LaTeX body). Org gains math, task lists, sane
footnotes, and colourised TODO keywords — all by reusing passes that already existed. `vv --cli` / `vv --tui`
render Org through uniorg. Large Markdown documents finally stream, as ADR-0018 always intended. A whole class of
"the render succeeded and produced nothing" bugs now surfaces a notice instead of a blank pane.

**Costs and risks.**

- `#+TITLE` becomes an `<h1>`, so it is slugged and **joins the Contents outline**. Existing Org documents gain a
  top-level outline entry. This matches `ox-html`, but it is a visible change.
- Enabling the streaming size gate changes behaviour beyond Org: large Markdown documents that previously
  rendered whole now paint progressively. This is the designed behaviour (ADR-0018) and is byte-parity-tested,
  but it had never actually run against a real file.
- We deliberately diverge from `ox-html` on non-HTML export blocks. An Org purist may expect them dropped.
- `tex-block-math?` is a heuristic. It will decline some legitimate math (bare `E = mc^2` with no delimiters, in
  an export block) in exchange for never rendering prose as garbage math. Deleting the pre-check restores the
  literal "always attempt, fall back on error" behaviour at that cost.

**Test debt repaid.** The electron smoke stubbed the main process for every fixture, so no test ever exercised
the real classifier — which is why the `content_service.js` divergence and the missing `:meta {:size}` both
survived CI. Org now drives through the real `content_service.openUri`, and `flag_test` locks in that a `nil`
`:size` never streams.

**A third silent gate.** `npm run test:electron:release` could not pass at all. The bidirectional-jump feature
([ADR-0021](0021-bidirectional-source-preview-jump.md)) drove `re_frame.core.dispatch_sync` from the test, but
the release `:simple` build deliberately encapsulates that global, so the run aborted with
`ReferenceError: re_frame is not defined` before reaching any later assertion — including every Org one. The jump
steps are now dev-gated with the rationale the PDF-reflow block had already established (release covers the pure
line→element math through the DOM-free `source-nav` unit tests), and Org's *View Source* step drives the real
`View ▸ View Source` menu item, so it exercises both builds. Three gates now genuinely run: node tests, dev
smoke, release smoke.
