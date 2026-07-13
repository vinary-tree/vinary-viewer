# 30 — Terminal preview (`vv --cli` / `vv --tui`)

vinary-viewer previews documents **in the terminal**, not only in the desktop GUI. `vv --cli <file>` renders a
document **once to stdout** — pipe-friendly ANSI text with inline kitty / sixel graphics — and `vv --tui <file>`
opens an **interactive full-screen pager** with in-page find, a Contents outline, and scrolling. Both are a
**second renderer over the shared [common IR](../theory/08-common-document-ir.md) / streaming spine**: the
front-ends, the streaming decoder, and the TOC/find capabilities are reused *verbatim* from the GUI — only the
output-facing layers are new, and the **GUI is untouched**. See
[ADR-0019](../design-decisions/0019-terminal-preview-layer.md) and
[Theory 10](../theory/10-terminal-rendering-second-renderer.md) for the full design.

![The shared spine (front-ends, WPDA decoder, capabilities) feeds two back-ends: ir.backend.html for the GUI and ir.backend.ansi for the terminal, which the cli and tui drivers render with the terminal caps/graphics/pdf/stream layer](../diagrams/component-terminal-renderer.svg)

*Diagram source: [`../diagrams/component-terminal-renderer.puml`](../diagrams/component-terminal-renderer.puml).*

## What you get

| Capability | Behaviour |
|---|---|
| **`vv --cli <file>`** | Renders once to stdout and exits — headings, word-wrapped paragraphs, lists, `│`-guttered blockquotes, `▏`-guttered highlighted code, unicode-box tables, OSC-8 hyperlinks, severity-coloured logs, and colored diffs. Pipe-friendly: colour / graphics auto-disable when not a TTY (`vv --cli x.md | less`). |
| **`vv --tui <file>`** | A full-screen pager: `↑`/`k` `↓`/`j` scroll, `Space`/`b` page, `g`/`G` top/bottom, `/` find (`n`/`N` next/prev), `t` Contents outline, `q` quit. Resizes (`SIGWINCH`) re-wrap the document. |
| **All formats** | Markdown, Org, LaTeX, source (tree-sitter highlighted), tables, logs, archives, directories, images, **PDF** (reflowed text), and diffs — every IR front-end is previewable in the terminal for free. |
| **Inline graphics** | On a kitty- or sixel-capable terminal, `vv --cli` draws images inline; elsewhere (piped, unsupported `TERM`, `--no-graphics`) it prints a labelled `🖼 name — reason` placeholder. |
| **Bounded-memory streaming** | A huge log streams to stdout / into a bounded viewport ring, so `vv --cli huge.log | less` and a TUI on a growing log both keep memory flat. |
| **Contents & find** | `--toc` prints the outline first in the CLI; the TUI has a selectable Contents overlay and reverse-video find — both reuse the shared TOC capability. |
| **Remote** | `vv --cli ssh://…` / `vv --tui ssh://…` open remote URIs with TTY-gated auth prompts ([feature 29](29-remote-files-over-ssh.md)). |

The two surfaces trade **display fidelity** (no rasterised PDF pages, no rendered Mermaid, no typeset math) for
a **dependency-light, pure-core, fully-testable** layer that never touches Electron or a browser. Degradation
is a designed, labelled outcome, not a failure.

## How it works

### A second renderer over the shared spine

The GUI and the terminal are the **same document engine with different output**. A format is parsed once by the
shared front-ends into the common IR; a *back-end* then lowers that IR to a concrete medium. The GUI's back-end
(`ir.backend.html`) emits HTML for a DOM; the terminal's (`ir.backend.ansi`) emits **styled ANSI** for a
character grid. Everything upstream — the front-ends, the WPDA streaming decoder, and the TOC / find
capabilities — is reused unchanged. Adding the CLI and the TUI required **no change** to any of them.

![vv --cli reads a file with the in-process content service, lowers it through the shared front-end to the IR, then to ANSI via ir.backend.ansi with the highlight and image ports, and writes it to stdout](../diagrams/seq-cli-render.svg)

*Diagram source: [`../diagrams/seq-cli-render.puml`](../diagrams/seq-cli-render.puml).*

### `ir.backend.ansi` — a width-aware character-cell layout engine

The ANSI back-end walks the IR and lays each block onto a grid of `width` columns. It:

1. **word-wraps** spans to the column budget, honouring **display width** — ANSI escapes count as zero cells and
   East-Asian-wide (CJK) glyphs as two;
2. **coalesces** adjacent same-style spans into one SGR run (a coloured log line emits *one*
   `ESC[…m … ESC[0m`, not one pair per word — a large byte saving on a streamed log); and
3. **decorates** per kind — bold level-coloured headings, `│`-guttered blockquotes, `▏`-guttered code with
   tree-sitter → SGR highlighting, unicode-box tables, OSC-8 hyperlinks, severity-coloured log records, and the
   diff line colouring ([feature 28](28-diff-rendering.md)).

It is a **pure function of `(ir, opts)`** — `opts` carries `:width :color? :truecolor? :hyperlinks? :highlight
:image` — so it is deterministic and golden-file tested, and it consumes the *exact* IR the HTML back-end
consumes. For the TUI, `render-lines` additionally returns `{:lines :anchors}`: the flat vector of visual lines
plus a heading-id → line-index map, which is the **authoritative** source for TOC jump (not fragile text
matching, so it is correct even when a heading wraps or repeats).

### Terminal capabilities & graphics

`terminal.caps/detect` resolves `{:width :color? :truecolor? :hyperlinks? :graphics}` from `process.stdout` +
environment, honouring `NO_COLOR` and `isatty` so output degrades cleanly when piped. `:graphics` is `:kitty`
(from `KITTY_WINDOW_ID` / `TERM`), `:sixel` (foot / WezTerm / mlterm / …), or `nil`; `--graphics kitty|sixel`
forces the protocol (bypassing detection — useful for a misdetected terminal and to make graphics testable
headlessly), and `--no-graphics` always wins.

`terminal.graphics` decodes an image (PNG / JPEG / GIF via pngjs / jpeg-js / omggif; SVG via
`@resvg/resvg-wasm`) to RGBA, resizes it to the display size **once** (kitty must not transmit
native-resolution RGBA — a 1080p photo would be an 11 MB base64 escape), and encodes it as a **kitty** escape
(`ESC_G f=32 … C=1;<base64-RGBA>ESC\`, chunked ≤ 4096 bytes; `C=1` fixes the cursor so the caller reserves the
image's row footprint deterministically) or a **sixel** DCS string (≤ 256 colours). Given a native `w × h` px
image, a column budget `$`C`$`, and a cell of `$`cw \times ch`$` px, the aspect-preserving fit is:

```math
\text{cols} = \min\!\left(\left\lceil \tfrac{w}{cw} \right\rceil,\; C\right),\qquad
s = \frac{\text{cols}\cdot cw}{w},\qquad
\text{px}_w = \operatorname{round}(w\,s),\quad \text{px}_h = \operatorname{round}(h\,s),\qquad
\text{rows} = \left\lceil \tfrac{\text{px}_h}{ch} \right\rceil .
```

`rows` uses `$`\lceil\cdot\rceil`$`, not rounding: a 25 px image in 20 px cells occupies 2 rows, and
under-counting would let the next line overprint it. The default cell is `$`10 \times 20`$` px (a `$`1{:}2`$`
w:h ratio, typical of monospace fonts) when the real geometry is unqueried. Where the terminal has no graphics,
the format is undecodable (webp / avif / ico), the source is a remote URL, or the escape would exceed a byte
cap, the port returns a labelled placeholder. The `:image` port is built by the CLI (it needs `fs` + the doc
dir) and injected into the ANSI opts — the back-end never imports the graphics module.

### Headless PDF reflow

The terminal has no canvas, so a PDF is shown as its **reflowed text**. `terminal.pdf` runs pdf.js's legacy
build (pure JS, no canvas) to extract text items per page, which the shared, DOM-free `ir.frontend.pdf` turns
into a fixed-layout `:page/:block/:line/:run` tree and then **reflows**: each block becomes a `:paragraph`, and
a heading-sized line (taller than `$`1.3\times`$` the median line height) becomes a `:heading`. The ANSI
back-end wraps it to the terminal width, and the TUI gets scroll + find + a font-size Contents. pdf.js v5's
legacy build is ESM-only and shadow-cljs's CommonJS `:node-script` cannot dynamic-import it, so
`resources/public/js/pdf-loader.js` — a plain CommonJS file shipped alongside the compiled script — is
`require`d at **runtime** via a computed path so shadow neither bundles nor analyses its `import()`.

### One streaming engine, shared with the CLI

`terminal.stream/stream-records!` is the sink-agnostic, cancellable **open → pull → feed → emit → finish →
close** loop over `content_service`'s pull-cursor + the `log-stream` `StreamParser` — the same WPDA core the
GUI streams through. `vv --cli` supplies a stdout sink (`vv --cli huge.log | less` never holds the whole file);
the TUI appends to a bounded viewport ring paced by `setImmediate`. The working set is the open record + one
WPDA config, so a multi-GB log streams to either surface with flat RSS. (`streamOpen` is async because a remote
source stats and opens over SFTP; the loop awaits it before pulling.)

### The TUI: a pure core behind a thin driver

`vv --tui` is raw ANSI — **no ncurses / blessed** — split so all policy is in a pure, terminal-free core and all
side effects in a thin driver:

| Module | Purity | Responsibility |
|---|---|---|
| `tui.keys` | pure | raw bytes → key events (CSI + SS3 + bracketed paste; retains a split escape) |
| `tui.viewport` | pure | windowed line buffer: paints an `:h`-row window; a `:cap` makes the streaming path a bounded ring |
| `tui.find` | pure | search over ANSI-stripped text; reverse-video highlight surviving interior RESETs |
| `tui.toc` | pure | resolve TOC entries to line indices via the anchor map; a selectable overlay |
| `tui.state` | pure | key → command reducer across `:normal` / `:find` / `:toc` modes |
| `tui.term` | impure | raw mode, alternate screen, cursor, **teardown** |
| `tui.core` | impure | wiring: stdin → keys → state → paint; streaming; resize |

The document lowers once to a flat vector of visual lines; the viewport paints only `lines[top : top+h]`, so a
frame costs `$`O(h)`$` regardless of document length — the terminal analog of the GUI's windowed DOM. A batch
doc retains its IR so a `SIGWINCH` re-wraps at the new width without re-reading the file.

**Teardown is the safety crux.** A TUI that leaves the terminal in raw mode / alternate screen / cursor-hidden
is a wedged shell needing `reset`. `tui.term` registers an **idempotent** `restore!` on *every* exit path
(written with `fs.writeSync` because `process.exit` truncates async stdout, and guarded by `isTTY` because
`setRawMode` throws off a TTY). `process.on('exit')` does not fire on a signal kill, so SIGINT / TERM / HUP /
QUIT get explicit handlers (exit `128 + signo`); SIGTSTP restores then re-raises the default suspend, and
SIGCONT re-enters and repaints.

### The `--drive` test seam

Raw mode needs a TTY, which a piped test lacks. `vv --tui --drive <keyfile>` replays key bytes through the
*same* `keys → state → frame` pipeline and dumps the final frame deterministically (forced width), so
scroll / find / toc / streaming are asserted with **no pseudo-tty**; a small Linux/python-`pty` check covers
only the teardown invariants (`ESC[?1049h` on start, `ESC[?1049l` + cursor restore on `q`) that `--drive`
cannot observe.

### Mermaid → source, math → LaTeX source

A terminal cannot typeset MathJax or run Mermaid's DOM-dependent layout, so **math renders as its LaTeX
source** and **Mermaid diagrams render as their source code block** — the same fallback the GUI would show if
its post-passes were skipped. Standalone **SVG** diagrams still render via the `@resvg/resvg-wasm` graphics
path where the terminal supports graphics.

### The `vv` mode-dispatch launcher

`vv` is one command with three modes. The launcher `install.sh` generates dispatches on the first argument:

```sh
vv [--gui] [files…]   # open in the desktop GUI (default), one tab per file/URL
vv --cli  <file>      # render a document to the terminal   (exec node dist/cli/vv-cli.js)
vv --tui  <file>      # interactively page a document        (exec node dist/tui/vv-tui.js)
vv --help | --version # print and exit without launching a window
```

`--gui` is an accepted no-op (the default). `--cli` / `--tui` `exec` the compiled `:node-script` bundles;
`--help` / `--version` print and exit. Within the GUI process, `vinary.main.startup` recognizes `-h` / `--help`
/ `-V` / `--version` and prints the same usage. The CLI / TUI are additive behind their own `:node-script`
build targets (`:simple`-optimized like `:main`); no `renderer` / `main` namespace requires the terminal
layer, so the GUI is untouched.

## Key namespaces

| Piece | Where |
|---|---|
| `vv --cli` driver — arg parse, kind detection, one-shot render, remote auth prompts | `vinary.cli.core` |
| Payload → IR → ANSI (reuses the GUI front-ends verbatim), the `:image` port, streamed-block rendering | `vinary.cli.render` |
| `vv --tui` driver — raw terminal wiring, frame composition, streaming, `SIGWINCH`, `--drive` | `vinary.tui.core` |
| Pure TUI core (keys / viewport / find / toc / state) + the impure driver (`term`) | `vinary.tui.{keys,viewport,find,toc,state,term}` |
| IR → styled ANSI layout engine (`render`, `render-lines`, `diff-line-style`) | `vinary.ir.backend.ansi` |
| Capability detection (width / colour / truecolor / OSC-8 / graphics protocol) | `vinary.terminal.caps` |
| Image decode + kitty / sixel encode, aspect-preserving fit, degradation | `vinary.terminal.graphics` |
| Headless pdf.js text extraction → reflowable IR | `vinary.terminal.pdf` (+ `resources/public/js/pdf-loader.js`) |
| Cancellable bounded-memory streaming loop (CLI + TUI) | `vinary.terminal.stream` |
| tree-sitter → per-line ANSI spans for code highlighting | `vinary.terminal.syntax` |
| Electron-free file reader (shared with the GUI, run in-process) | `vinary.main.content_service` |
| The `vv` launcher + in-GUI `--help` / `--version` | `install.sh` · `vinary.main.startup` |

## Configuration

`vv --cli` flags (a subset shared by `vv --tui`):

| Flag | Effect |
|---|---|
| `-t`, `--toc` | print the document outline (Contents) first (CLI) |
| `--width N` | wrap column (default: terminal width, else 80) |
| `--no-color` / `--color` | force ANSI colour off / on (auto-off when piped / `NO_COLOR`) |
| `--no-graphics` | disable inline image graphics |
| `--graphics kitty\|sixel` | force the image protocol, bypassing detection |
| `-p`, `--plain` | plain text: no colour, no graphics, no hyperlinks |
| `--drive FILE` | (TUI, test) replay key bytes headlessly and dump the final frame |
| `-h` / `--help`, `-V` / `--version` | print and exit |

## Edge cases & limitations

- **No canvas.** Fixed-layout PDF figures degrade to their extracted text, Mermaid to its source, and math to
  its LaTeX source.
- **The TUI forces graphics off** (images → placeholder lines) so the scrolling viewport is line-exact;
  `vv --cli` in the same terminal *does* draw images.
- **Streamed scrollback is bounded.** A streamed log's viewport is a `:cap`-ed ring, so the absolute top of a
  multi-GB log is not retained (a `less`-with-scrollback-limit model); batch documents stay uncapped and fully
  scrollable.
- **`webp` / `avif` / `ico`** are not decoded → a labelled placeholder; **remote (`http(s)`) image URLs** are
  not fetched by the CLI → a placeholder.
- **The pdf.js runtime-require** depends on `resources/public/` shipping alongside the compiled script.
- **SIGKILL / SIGSTOP** are uncatchable — the acknowledged residual for the TUI teardown guarantee.

## References / see also

- [ADR-0019 — A terminal preview layer (CLI + TUI)](../design-decisions/0019-terminal-preview-layer.md)
- [Theory 10 — Terminal rendering: a second renderer over the shared spine](../theory/10-terminal-rendering-second-renderer.md)
- [ADR-0017 — Common document IR](../design-decisions/0017-common-document-ir.md) ·
  [Theory 08 — Common document IR](../theory/08-common-document-ir.md)
- [ADR-0018 — Document-streaming pipeline](../design-decisions/0018-document-streaming-pipeline.md) ·
  [Theory 09 — Document streaming and the WPDA](../theory/09-document-streaming-and-the-wpda.md)
- [feature 11 — PDF preview](11-native-pdf.md) · [feature 09 — Markdown](09-markdown-rendering.md) ·
  [feature 27 — LaTeX](27-latex-rendering.md) · [feature 28 — Diff rendering](28-diff-rendering.md)
- [feature 29 — Remote files over SSH](29-remote-files-over-ssh.md) (terminal remote parity) ·
  [feature 25 — Content previews](25-content-previews.md)
