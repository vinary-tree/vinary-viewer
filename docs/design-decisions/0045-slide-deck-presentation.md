# 0045 — Slide-deck presentation: a Slides layout inside the PDF engine, keys that flip slides, and a window-level slide show

- **Status:** Accepted. Amended by [ADR-0048](0048-speaker-notes-and-linked-presenter-windows.md) (§9 below: a plain
  `show notes` deck's note pages now leave the audience's sequence)
- **Date:** 2026-10-17
- **Deciders:** vinary-viewer maintainers

## Context

Vinary Viewer renders PDFs in the renderer with pdf.js ([ADR-0013](0013-in-renderer-pdfjs.md)) as one continuous,
scrolling document. That fits a paper. It does not fit a slide deck — a Beamer deck compiled by pdfLaTeX, LuaLaTeX
or XeLaTeX, or a PowerPoint, Google Slides, LibreOffice Impress or Keynote export — which is read and presented
**one slide at a time**. Before this decision:

| # | What went wrong with a deck | Why |
|---|---|---|
| 1 | Arrow keys scrolled 80 px and PgDn 90 % of a viewport, so a slide was usually shown half off the screen. | The PDF view had a single, continuous layout. |
| 2 | Beamer's overlay pages (`\pause`, `<2->`), one page per build step, could only be stepped through one by one. | Nothing read the page labels that group a frame's overlays. |
| 3 | There was no way to present: the menu bar, sidebar, tabs and address bar always surrounded the slide. | — |
| 4 | Beamer's navigation symbols did nothing. | The link layer handled URI and destination links only; PDF *named actions* were dropped. |
| 5 | A live refresh while authoring blanked the view and lost the position; bytes read while pdflatex was still writing left the view empty, and a failed re-read replaced the deck with an error page. | The view was keyed by `pdf:<path>:<stamp>`, cleared its DOM before the asynchronous prepare and only logged a prepare that failed; a failed read became `:doc/error`. |

The user asked for three things: automatic detection of Beamer decks with a slide-oriented keyboard; a
"View as Slide Show" action for any PDF; and assurance that the client/server architecture still works when the
deck is remote (`vv sftp://host/talk.pdf`) and refreshed live by the [ADR-0035](0035-authenticated-remote-daemon-events.md)
target daemon while the author edits or presents. On 2026-10-16 the user decided that a detected deck opens in a
Slides layout inside its tab (changeable in Preferences), that a slide **overview grid** is in scope, and that a
presenter console on a second display and slide navigation in `vv --tui` are deferred (filed as feature requests).
The remote half — one verified read per change, ordered and fanned out — is
[ADR-0046](0046-reload-safe-pipelined-remote-reads.md); this ADR is the slide show.

Five constraints shaped every decision below:

- **Every page must stay in the DOM.** In-page find searches the text layers of all pages, the link layer carries
  the document's links, and the Contents scroll-spy probes 100 px below the top of the scroller for the page
  anchor `#vv-pdf-page-N` (`renderer.toc/active-heading`).
- **`.vv-content` is the one confined scroll owner** ([ADR-0032](0032-scroll-ownership-and-derived-input-focus.md)),
  and React owns its class list.
- **Focus and modality are derived, never cached** (ADR-0032).
- **Three keymap presets** (Standard, Vim, Emacs) each scroll with their own keys, and Vim's Space is a leader prefix.
- **The app's own prompts must stay usable during a show** — the SSH authentication modal above all, since a
  remote deck can prompt mid-talk — and **a document must never be able to make the window fullscreen**.

## Decision

**A pure deck model classifies every PDF when the engine prepares it; a detected deck mounts in a Slides layout
built inside the existing pdf.js engine; every keymap's scroll commands become slide steps through one scroll
delegate, run inside the key handler; F5 presents in a window-level fullscreen that main owns; and a live refresh
swaps the new document in under the same view, on the same slide.**

### Vocabulary

| Term | Meaning |
|---|---|
| bp | A PDF point, $`1\,\mathrm{bp} = \tfrac{1}{72}\,\mathrm{in}`$. Page sizes are width × height in bp at scale 1. |
| page | One PDF page, with 0-based index $`p \in [0, n)`$ where $`n`$ is the page count. |
| slide | A page as the Slides layout shows it: one page fills one screen. |
| frame | Beamer's unit, one `\begin{frame}`. A frame with overlays compiles to several consecutive pages that share the frame's number as their PDF **page label**. |
| overlay | One page of a frame — one build step. |
| deck | A PDF the detector classifies as slides. The deck *model* (§2) is built for every PDF, deck or not. |
| tier · source | How sure the detector is (`definite`: the PDF says so; `probable`: its geometry and producer say so) and which rule fired. |
| layout | How the engine lays pages out: the continuous **Document layout** or the one-slide-per-screen **Slides layout**. |
| slot · sheet | In the Slides layout each page's placeholder becomes a full-viewport **slot**; the page itself sits in its **sheet** (`.vv-pdf-sheet`), centred in the slot. |
| presenting | A running slide show in one window: chrome hidden, optionally fullscreen. |
| jump | A move that records the GoBack / GoForward history: a link, `:first`, `:last`, a typed number, an overview pick. |

![A PDF view's modes: Document layout, Slides layout, Presenting](../diagrams/state-slides-modes.svg)

*Diagram source: [`../diagrams/state-slides-modes.puml`](../diagrams/state-slides-modes.puml).*

### Decisions at a glance

The decisions are numbered as in the design (D1–D14), and the scope added on 2026-10-16 keeps its S-numbers;
code comments cite both.

| # | Decision | Section |
|---|---|---|
| D1 | A pure, DOM-free deck model: `vinary.slides.deck` and `vinary.slides.evidence` | §2 |
| D2 | Detection runs in `pdf/prepare!`, in parallel with the page measure; the result rides the leased artifact `{:doc :sizes :deck}` | §1 |
| D3 | The Slides layout is built inside the existing engine: full-viewport slots, a sheet per page, a fit per page | §3 |
| D4 | The scrollbar is hidden by CSS keyed on the engine's own class | §3 |
| D5 | Snap to a slot at the end of a scroll, never during one | §3 |
| D6 | Every scroll command reaches the Slides layout through one scroll delegate | §4 |
| D7 | A `:slides` keymap layer **below** the mode, identical in all presets | §4 |
| D8 | Whether the layer applies is derived on every keydown, never cached | §4 |
| D9 | Escape's slide steps run inside `:input/escape`, before its mode switch | §4 |
| D10 | Fullscreen is window-level and owned by main; chrome is hidden with CSS | §5 |
| D11 | A live refresh swaps the document in place and keeps the last good version | §7 |
| D12 | The overview grid is a separate overlay that borrows the mounted document | §6 |
| D13 | `/PageMode /FullScreen` is evidence only; nothing goes fullscreen on open | §1, §5 |
| D14 | The explicit Slides/Document choice belongs to the document view and is restored by history | §12 |
| S4 | One answer to "which overlay owns the keyboard" (`vinary.app.overlay`) | §4 |
| S5 | GoTo links and GoBack/GoForward work in the Document layout too | §8 |
| S8 | Optional slide transitions (fade, push) | §10 |
| S9 | Beamer two-screen decks show their slide half | §9 |
| S10 | Touch and pen swipes flip slides | §11 |

### 1. Detection — evidence, tiers, and the rule table

**Where it runs (D2).** `renderer.pdf/prepare!` opens the bytes with pdf.js and then runs two things in parallel
(`Promise.all`): `page-sizes`, the measure the layout needs anyway, and `vinary.slides.evidence/gather`, four
document-level reads on the same proxy — the `/Info` `Creator` and `Producer`, the XMP `xmp:CreatorTool`
(`getMetadata`), the catalog's `/PageMode` (`getPageMode`) and the page labels (`getPageLabels`). Each call is
caught on its own: one that fails yields `nil`, so the detector can never be the reason a PDF fails to open.
`deck/deck` then classifies the evidence and builds the frames, and `prepare!` returns the artifact
`{:doc :sizes :deck}`, which the warm cache leases across mounts. Three things follow:

- the **first** mount already knows its layout, so a deck never flashes as a document first;
- detection costs no I/O and no round trip, because it reads the document already loaded;
- a local file, an `sftp://` URI, an archive member and a web-linked PDF are detected identically.

**Definitions.** Let $`\tau = 0.5\,\mathrm{bp}`$ be the size tolerance (TeX rounds `\paperwidth` to the scaled
point; exporters round to whole points). Two sizes are equal when both dimensions agree within it,

```math
(w_1, h_1) \simeq (w_2, h_2) \iff |w_1 - w_2| \le \tau \;\wedge\; |h_1 - h_2| \le \tau ,
```

and a document is **uniform** when every page has one size $`s`$ up to $`\simeq`$. A size **doubles** a Beamer
paper $`(w, h)`$ when it is $`\simeq (2w, h)`$ (horizontal) or $`\simeq (w, 2h)`$ (vertical) — the page of a
Beamer two-screen deck (§9).

**The rules** (`vinary.slides.deck/classify`) are tried in order, and the first match decides:

| # | Tier | Source | Rule |
|---|---|---|---|
| 1 | definite | `:beamer` | `Creator` or `CreatorTool` matches `\bbeamer\b` in any case (`beamer.cls` sets `pdfcreator={LaTeX with Beamer class}`), and either $`n \ge 2`$, or $`n = 1`$ and that page is a Beamer paper or one doubled. |
| 2 | definite | `:fullscreen-mode` | The catalog's `/PageMode` is `FullScreen` [ISO-32000 §7.7.2]. |
| 3 | probable | `:slide-export` | $`n \ge 2`$, uniform, and $`s`$ is a slide tool's export canvas (below). |
| 4 | probable | `:beamer-geometry` | $`n \ge 2`$, uniform, $`s`$ is a Beamer paper or one doubled, and the `Producer` names a TeX engine: pdfTeX, LuaTeX, LuaHBTeX, XeTeX, xdvipdfmx, dvipdfm(x) or Tectonic. |
| — | — | not a deck | Everything else. Landscape pages alone never qualify. |

**Beamer's papers** (`beamer.cls` [BEAMER], the `aspectratio` class option; `deck/beamer-papers`):

| Option | 43 (default) | 169 | 1610 | 149 | 141 | 54 | 32 | 2013 |
|---|---|---|---|---|---|---|---|---|
| Paper (bp) | 362.835 × 272.126 | 453.543 × 255.118 | 453.543 × 283.465 | 396.850 × 255.118 | 420.945 × 297.638 | 354.331 × 283.465 | 382.677 × 255.118 | 396.850 × 257.953 |

**Slide-tool export canvases** (`deck/export-canvases`), with the provenance of each entry as recorded in
[experiment 12 §2 and §7](../scientific/12-slide-deck-detection-and-flip-latency.md#7--slide-tool-export-canvases):
*measured* (exported here from LibreOffice Impress 26.8 at each slide-size preset), *observed* (an export found in
the natural corpus), *documented* (the vendor's published preset), *reported* (a third-party source only).

| Canvas (bp) | Tools | Provenance |
|---|---|---|
| 960 × 540 | PowerPoint Widescreen (13.333 × 7.5 in); Impress Widescreen | Impress **measured** |
| 720 × 540 | PowerPoint / Impress On-screen Show (4:3), 10 × 7.5 in | Impress **measured** |
| 720 × 405 | Google Slides 16:9; Impress On-screen Show (16:9) | Impress **measured** |
| 720 × 450 | Impress On-screen Show (16:10); PowerPoint On-screen Show (16:10), 10 × 6.25 in | Impress **measured**; PowerPoint **documented** [MS-SLIDES] |
| 793.7 × 595.3 | Impress Screen 4:3, 28 × 21 cm | **measured** (793.7 × 595.28); **observed** at 794 × 595 from an older Impress |
| 793.7 × 446.5 | Impress Screen 16:9, 28 × 15.75 cm — Impress's default | **measured** |
| 793.7 × 496.1 | Impress Screen 16:10, 28 × 17.5 cm | **measured** |
| 1024 × 768 | Keynote Standard (4:3) | **observed** |
| 1920 × 1080 | Keynote Wide (16:9) | **reported** [KN-1920]; Apple names the preset without its size [KN-APPLE] |

**Why each rule.**

- **Rule 1** trusts what Beamer itself writes. The page-count clause keeps out a one-page `beamerposter`, which
  carries the same Creator but is not a deck.
- **Rule 2** reads an author's explicit request to present. It is evidence **only** (D13): the app never goes
  fullscreen because a document asks (§5).
- **Rule 3** uses exact canvases because a slide tool exports every slide at its slide size, while *landscape* is
  a poor signal. In a natural corpus of 2 456 local PDFs, 551 files had uniform landscape pages (527 of one page,
  24 of several), and their sizes are those of figures, Letter- and A4-landscape print-outs and drawings
  ([experiment 12 §6.3–§6.4](../scientific/12-slide-deck-detection-and-flip-latency.md#63--the-cases-detection-must-get-right)).
- **Rule 4** recovers a Beamer deck whose `\hypersetup{pdfcreator=…}` was overridden. Its documented false positive
  — an article typeset on Beamer paper by a TeX engine — is accepted, because View as Document is one keystroke away.

**The tier is informational.** Both tiers open as slides; the tier is recorded in `:doc/deck`, printed by
`--deck-info`, and evaluated by experiment 12. On a controlled corpus of 42 real Beamer PDFs (14 variants × three
engines) every verdict matched its pre-registered expectation; the natural corpus and the Impress presets are in
experiment 12 §5–§7.

**The diagnostic.** `vv --cli --deck-info talk.pdf` runs the same evidence reader, the same page measure (the Node
legacy build of pdf.js) and the same classifier, and prints one JSON line per PDF — the instrument of experiment
12, so the experiments test the product's code rather than a copy of it.

![Deck detection, speaker notes and the layout a PDF opens in](../diagrams/activity-pdf-deck-detection.svg)

*Diagram source: [`../diagrams/activity-pdf-deck-detection.puml`](../diagrams/activity-pdf-deck-detection.puml). It now
also shows ADR-0048's speaker-notes model — its first-paint decision, the view's own deck, and the background settle.*

### 2. The DOM-free deck model

**What and why (D1).** `vinary.slides.deck` holds everything about a deck that needs no pdf.js, no DOM and no
re-frame: classification, frames, navigation, and the pure input mappings. `vinary.slides.evidence` reads the
evidence with document-level calls only, so it runs on the browser and the Node legacy builds of pdf.js alike. The
GUI engine, `vv --cli --deck-info` and the unit tests share one definition, and the deferred presenter console and
terminal slide mode can reuse it unchanged.

**The deck value** (`deck/deck`), built for every PDF:

| Key | Meaning |
|---|---|
| `:deck?` `:tier` `:source` | the classification of §1 |
| `:pages` | $`n`$ |
| `:frames` | the frames as inclusive page ranges `[[s e] …]`, a partition of $`[0, n)`$ |
| `:frame-of` | page → frame, one entry per page |
| `:labels` | the per-page labels, or `nil` (always `nil` on a two-screen deck) |
| `:second-screen` | `:horizontal`, `:vertical` or `nil` (§9) |

Only its summary `{:deck? :tier :source :pages :frame-count :second-screen}` (`deck/summary`) is stored, as
`:doc/deck`, by `:pdf/deck-detected` — and only when it changed. The per-page vectors stay in the leased artifact,
because DataScript holds lean entities ([ADR-0010](0010-bounded-content-retention-and-render-metadata.md)).

**Frames.** Beamer labels every overlay page with its frame number (`\thispdfpagelabel{\insertframenumber}`), so
a frame is a maximal run of *adjacent* pages that share one non-blank label. Labels are used only for Beamer
sources (`:beamer`, `:beamer-geometry`), only when there is exactly one per page, and never on a two-screen deck,
whose labels are corrupt (§9). Every other PDF gets one frame per page. In literate form (`deck/frames-from-labels`):

```text
frames-from-labels(n, labels, group?):
  use the labels only if group? and |labels| = n           ▹ else every page is its own frame
  if n ≤ 0: return []
  frames ← [] ; start ← 0
  for i ← 1 … n − 1:
    if the labels are used, labels[i−1] is non-blank and labels[i] = labels[i−1]:
      continue                                              ▹ page i is another overlay of this frame
    append [start, i − 1] to frames ; start ← i             ▹ a different label closes the frame
  append [start, n − 1] to frames
  return frames
```

Only adjacency counts, so `\againframe`, which repeats a label later, starts a new frame. The vectors are built
with transients, one `conj!` per frame and one per page.

**Navigation** (`deck/step deck nav op`). The navigation state is
$`\mathit{nav} = \{\mathit{page}, \mathit{back}, \mathit{fwd}\}`$: the current page and the GoBack / GoForward stacks.

| Op | Result | Records history? |
|---|---|---|
| `:next` / `:prev` | one page (one overlay) forward / back, clamped at the ends | no |
| `:next-frame` | the **first** page of the next frame (the rest of this frame's overlays are skipped); on the last frame, its last page | no |
| `:prev-frame` | the **last** page of the previous frame — the slide fully built, as a presenter expects when going back; on the first frame, page 0 | no |
| `:first` / `:last` | page 0 / page $`n-1`$ | yes |
| `[:goto p]` · `[:jump p]` | page $`p`$, clamped | yes |
| `[:goto-label s]` | the page `resolve-goto` names (below); nothing when it names none | yes |
| `:back` / `:forward` | walk the history | moves between the stacks |

A jump pushes the page it leaves onto `back` and clears `fwd`; each stack keeps at most 64 entries
(`deck/history-limit`). `resolve-goto` reads a typed reference as a **label first** — in a Beamer deck the label is
the frame number printed on the slide, so "7" opens frame 7's first overlay — and otherwise as a 1-based page
number.

**Input mappings**, all pure and unit-tested:

- `scroll->op` turns a scroll command into a slide op (§4).
- `wheel-step` follows pdf.js's presentation mode [PDFJS-PM]. A wheel event's delta $`d`$ is normalized as pdf.js
  normalizes it (`wheel-notches`) — the length of $`(\Delta x, \Delta y)`$ divided by 900 (30 px per line × 30 lines)
  for pixel deltas or by 30 for line deltas, positive towards the next slide — after scaling a pixel delta back by the
  window zoom, since Chromium reports a notch's 120 device-independent pixels as $`120/z`$ CSS pixels at zoom $`z`$
  (experiment 12 §8.7; the divisor was 1 200 until that review, which put a 100 % notch exactly on the threshold
  and made a zoomed-in window take two notches per slide). An accumulator $`a`$ is reset to 0 when $`d`$ has the opposite sign, then
  $`a \gets a + d`$; when $`|a| \ge 0.1`$ one slide flips and $`a \gets 0`$, and wheel input is ignored for 50 ms
  after a flip, so one trackpad fling cannot skip several slides.
- `swipe-op` classifies a touch or pen gesture (§11).
- `digits-step` keeps a typed slide number to at most six digits.
- `overview-tiles` and `grid-move` drive the overview (§6); `transition-plan` decides a flip's animation (§10).
- `effective-layout` decides the layout: presenting → Slides; else the document view's explicit choice; else a
  detected deck opens as Slides unless the `:slides-open-as` preference is `:document`; else Document. The
  engine, the menus, the toolbar and the slide bar all evaluate this one function (`vinary.app.slides` feeds it the
  `:doc/deck` summary), so they can never disagree about which layout is showing.

### 3. The Slides layout inside the existing engine

**Slots and sheets (D3).** Entering the Slides layout keeps every page in the DOM and changes only its geometry:

```text
.vv-content                          the scroller (overflow hidden in this layout)
└─ .vv-pdf-doc.vv-pdf-slides         the engine's container
   ├─ .vv-pdf-page#vv-pdf-page-1     slot 0: W × H, top 0 — the Contents anchor id lives here
   │  └─ .vv-pdf-sheet               page 1 at its own fit, centred in the slot
   │     ├─ canvas.vv-pdf-canvas     the raster (double-buffered)
   │     ├─ .vv-pdf-text             the text layer (find, selection, copy)
   │     └─ .vv-pdf-anno             the link layer
   ├─ .vv-pdf-page#vv-pdf-page-2     slot 1: top H
   └─ …
```

Each slot is exactly the scroller's client size $`W \times H`$, and the slots stack with no gap. Page $`i`$, of
shown size $`(w_i, h_i)`$ — its slide half on a two-screen deck — is drawn at its own scale

```math
s_i = \min\!\Bigl(16,\ \max\!\Bigl(0.25,\ \min\Bigl(\frac{W - 2m}{w_i},\ \frac{H - 2m}{h_i}\Bigr)\Bigr)\Bigr),
```

where the margin $`m`$ is 16 px in a tab and 0 while presenting (`pdf-layout/slide-scale`). The fit is per page so
that a portrait title page does not shrink every landscape slide to its own fit. The upper clamp is 16 rather than
the Document layout's 8 because a 16:9 Beamer page, 453.543 bp wide, needs $`3840 / 453.543 \approx 8.47`$ to fill
a 3 840-px-wide display.

Because every page is still in the DOM, find, text selection, copy, the link layer and the Contents outline keep
working unchanged; the page ids sit on the full-height slots, so the scroll-spy's probe 100 px below the top falls
inside the current slot.

**Moving between slides (D5).** A slide is shown by writing the scroller's `scrollTop` to its slot's top at once
(`go-to-page!`): a slide show flips, it does not glide. Anything else that moves the scroller — a smooth Contents
jump, find centring a match, a stray pixel scroll — is settled when it ends, on `scrollend` or 150 ms after the
last `scroll` event, onto the slot nearest the top:

```math
\mathrm{slot}(t) = \min\bigl(n - 1,\ \max(0,\ \operatorname{round}(t / H))\bigr),
```

where $`t`$ is the viewport top in document coordinates. Snapping during a smooth scroll would fight it and land
on the wrong slide. After find centres a match, the viewport's centre lies in the match's slot, so rounding the
top lands on that slot.

**The scrollbar (D4)** is hidden by `.vv-content:has(> .vv-pdf-doc.vv-pdf-slides) { overflow: hidden; }`. The
rule keys on the engine's own class, which `set-layout!` toggles *before* it measures, rather than on a class of
`.vv-content`, because React owns that element's classes and app-db lags a frame behind on first open. The wheel
is therefore handled by the engine (`wheel-step`); Ctrl and Meta wheel events are left alone.

**A render band of one slot either side.** The engine renders the canvases of the pages an `IntersectionObserver`
reports near the viewport and releases the rest. In the Slides layout its root margin is `50%` of the scroller's
height above and below (`slides-overscan` 0.5; Chromium resolves a percentage top or bottom margin against the
root's height [BLINK-IO]). With slide $`i`$ at the top, the band is $`[(i - \tfrac12)H,\ (i + \tfrac32)H]`$, and slot $`j`$,
which covers $`[jH, (j+1)H]`$, meets it exactly when

```math
jH < (i + \tfrac32)H \;\wedge\; (j + 1)H > (i - \tfrac12)H
\iff i - \tfrac32 < j < i + \tfrac32
\iff j \in \{i - 1,\ i,\ i + 1\}.
```

So the next and previous slides are always pre-rendered for an instant flip, and at most three page canvases are
alive. The Document layout's overscan of 1.5 viewports would keep five fullscreen-sized canvases. Experiment 12
measured both bounds: every neighbour flip found its target already rendered (147 of 147), and no frame had more
than three page canvases (H-flip-2, H-flip-4).

**Canvases.** Every page renders into a **detached** canvas that replaces the old one only when complete
(double-buffering), so a re-render after a resize, a DPR change or a live refresh keeps showing the previous raster,
CSS-stretched, instead of flashing white. Each canvas is held to a device-pixel budget (`pdf-layout/budget-dpr`): a
canvas of CSS size $`w \times h`$ on a display of pixel ratio $`r`$ is rasterized at

```math
r' = \begin{cases} r & \text{if } w\,h\,r^2 \le P,\\[2pt] \max\bigl(0.25,\ \sqrt{P / (w\,h)}\bigr) & \text{otherwise,} \end{cases}
\qquad P = 2^{25},
```

where $`P = 2^{25} = 33\,554\,432`$ device pixels is pdf.js's own viewer default `maxCanvasPixels` [PDFJS-OPT], so a
fullscreen slide on a HiDPI display can never allocate a gigapixel canvas. When the window resizes — growing into
fullscreen, say — the slots are resized at once (cheap style writes; the next slide never peeks in) and re-rendered
sharp 120 ms after the last resize. A `matchMedia("(resolution: …dppx)")` listener re-renders when the window moves
to a display with another pixel ratio, such as a projector.

**Zoom is inert in the Slides layout.** Slides fit themselves, so `:view/zoom` and `:view/zoom-set` do nothing
there, the View ▸ Fit submenu is hidden, and the bottom bar shows the slide bar instead of the zoom control. The
engine never reports a fit scale (`:pdf/scale-resolved`) in this layout, which would overwrite the window-wide
`[:ui :pdf :scale]` that other PDF tabs share.

### 4. Keys

**One scroll delegate (D6).** Every keymap's scroll command already arrives at the `:dom/scroll` effect as the same
options map `{:dy :dx :to :shift?}`: the bare arrows and PgUp/PgDn/Home/End from `key-scroll!` (a capture-phase
listener that works in every preset), Vim's `j k h l C-d C-u C-f C-b gg G`, Emacs's `C-n C-p C-v M-v`, and the
palette's scroll commands. `vinary.renderer.scroll` gained a **delegate** seam (`set-delegate!`, `clear-delegate!`,
`delegate!`, identity-checked so an unmounting view never clears a newer view's registration), and `:dom/scroll`
offers the command to it first whenever the content pane is the target. The Slides layout registers a delegate
that maps the options through `deck/scroll->op` and always claims the command — a pixel scroll would only be undone
by the snap, and the smooth-scroll animator never starts a chase the snap would fight.

| Scroll command | Slide op |
|---|---|
| `:to :top` / `:to :bottom` (Home / End, Vim `gg` / `G`) | `:first` / `:last` |
| `:dx :right` / `:dx :left` (→ / ←, Vim `l` / `h`) | `:next` / `:prev` |
| `:dy` downward — a positive number, `:page` or `:half` (↓, PgDn, Vim `j` `C-d` `C-f`, Emacs `C-n` `C-v`) | `:next` |
| `:dy` upward — a negative number, `:-page` or `:-half` (↑, PgUp, Vim `k` `C-u` `C-b`, Emacs `C-p` `M-v`) | `:prev` |
| any of the above with `:shift?` (Shift+PgDn / Shift+PgUp) | `:next` → `:next-frame`, `:prev` → `:prev-frame` |

**A `:slides` layer below the mode (D7).** The keys a slide show needs that are not scroll keys form a `:slides`
layer, identical in `resources/keymaps/{default,vim,emacs}.edn` (a unit test asserts it) and overridable in
`keybindings.edn` under `:keymaps {:slides {…}}`: Space / Shift+Space, Backspace, Enter, Shift+arrows (frame steps),
`b` `.` / `w` `,` (blanking), Tab / `o` (overview) and the digits. The resolver's root trie is the shallow merge

```text
root = merge(:all, (if slides-active? then :slides else {}), current mode)
```

so the layer sits **below** the mode: a mode's own binding of the same key wins (Vim's Space leader in normal mode),
and the merge never replaces a mode's prefix map wholesale, as it would if the layer were merged above the mode. F5
and Shift+F5 are in every preset's `:all`.

**Derived activity (D8, S4).** `:slides-active?` is computed on every keydown from the live state, never cached
(ADR-0032): the engine reports a mounted Slides layout (`pdf-cache/slides-active?`); the focus is on `<body>` or
inside `.vv-content`, not in the sidebar, the address bar, a button or an overlay's own surface; no text field has
focus; and `vinary.app.overlay/blocking?` is false. That last predicate is the one answer the renderer now gives to
"does an overlay own the keyboard?" — a modal dialog (Preferences, About, Extensions, Passwords, the key-binding
editor, the SSH prompt), the menubar's menu or a context menu, the palette, or link hints. It replaced six
hand-written copies that had drifted (the SSH prompt was in none of the first four). Only the resolver sets
`:slides-active?`, so the commands gated on it — the digits, Enter and Backspace — are keyboard-only and never
offered by the palette.

**View moves run at once.** The browser's event loop may run its "update the rendering" step between two tasks
[HTML-EL], and experiment 12 found that after a discrete input event Chromium renders the next frame before ordinary
posted tasks — and re-frame's queue drains in such a task (H-sched). A key handled through `rf/dispatch` therefore
made its change just after a frame, and the slide showed one frame late. Let
$`T_f = 1000/60 \approx 16.7\,\mathrm{ms}`$ be the frame period, $`h`$ the handler's work and
$`U \sim \mathcal{U}[0, T_f)`$ the wait from the key to the next frame boundary. A move made before that boundary
shows in that frame, so

```math
L = h + U, \qquad \operatorname{median}(L) \approx h + \tfrac{T_f}{2},
```

whereas the queued path paid one more frame period. Two changes remove the deferral:

1. `vinary.app.commands/immediate-commands` names the commands that only move the view — the scroll commands, the
   slide steps, the typed number with its Enter and Backspace, and the blank screens — and `commands/run` sends
   them through `dispatch-now!` when a key handler asks (`{:now? true}`): `rf/dispatch-sync` from a DOM handler,
   falling back to `rf/dispatch` inside an event handler, where re-frame forbids `dispatch-sync` [RF-API].
   `key-scroll!` uses it too.
2. The delegate, the wheel and swipes run a slide step on the engine at once (`renderer.pdf/step!` →
   `command!`) whenever `:slides/command` would change nothing in app-db. It would change app-db only for a
   blanked screen or a half-typed slide number (`vinary.app.slides/command-needs-db?`), and those still go through
   the event; a unit test ties the shortcut to `slides-command-fx`.

Making only the first hop synchronous (H-sync) was refuted — the delegate's second, queued hop kept the frame of
delay. With both changes the neighbour flip went from a median of 24.3 ms to 7.9 ms (p95 29.5 → 15.1 ms), and a
Home/End jump from 61.3 ms to 38.8 ms ([experiment 12 §8](../scientific/12-slide-deck-detection-and-flip-latency.md#8--flip-latency);
the sequence is drawn in [`seq-key-to-frame`](../diagrams/seq-key-to-frame.svg)).

**Typed numbers and blanking.** Digits collect in `[:ui :slides :digits]` (only while the mirror says the Slides
layout), shown by a "Go to 12" chip and forgotten 2.5 s after the last digit (a keyed `:async/debounce`). Enter goes
to `[:goto-label typed]`, else to the next slide; Backspace deletes a digit, else goes back; any slide step forgets
the number. While a show is **blanked**, a slide step only ends the blanking — the audience sees the slide they left,
not the next one.

**Escape (D9).** Escape's slide steps are cancel steps inside `vinary.input.events/escape-fx`, after the palette and
the find bar and **before** its switch to Vim's normal mode, so Escape never has a mode side effect while it is
cancelling something on a slide. The first step that applies wins:

| Order | Condition | Escape does |
|---|---|---|
| 1 | the palette is open | close it |
| 2 | the find bar is visible | close it |
| 3 | the overview is open | close it |
| 4 | a slide number is being typed | forget it |
| 5 | the show is blanked | unblank |
| 6 | a show is running | end the show |
| 7 | the mode is not `:normal` | switch to `:normal` |
| 8 | otherwise | clear the pending key sequence |

### 5. Presenting

**Starting (D10).** `[:slides/present {:from :first|:current|<page>}]` — F5, Shift+F5, the View menu, the slide
bar's Present button, the context menu's Start From This Slide — does nothing unless a PDF is shown, deck or not. While a show
is running, `:first` goes back to the first slide (F5 again) and a page index goes there. Otherwise it records
`[:ui :presentation] {:active? :tab-id :path :from :fullscreen?}`, clears a typed number and a blanking, focuses the
content, and — when the `:slides-fullscreen?` preference is on (the default) — asks main for fullscreen with
`[:vv/set-fullscreen {:on true}]`. The engine sees `:presenting?` and `:present-from` through the
`:pdf/layout-request` subscription: presenting forces the Slides layout, drops the margin to 0, shows the requested
slide once (`:current` keeps the page in view), and attaches the presenting-only listeners.

**Window-level fullscreen, owned by main.** `vinary.main.fullscreen` handles `vv:set-fullscreen {on}` for the
**sender's** window only (`BrowserWindow.fromWebContents(event.sender)`, then `setFullScreen` [ELECTRON-BW]), and
remembers whether *it* made that window fullscreen. Its pure `decide` table:

| Request | Window fullscreen now? | Main owns it? | Main does | Owned after |
|---|---|---|---|---|
| on | no | — | `setFullScreen(true)` | yes |
| on | yes | either | nothing — already fullscreen, ours or the user's | unchanged |
| off | yes | yes | `setFullScreen(false)` | no |
| off | yes | no | nothing — the user's own fullscreen is never left | no |
| off | no | — | nothing | no |

`wire!` reports every `enter-full-screen` and `leave-full-screen` to the window's renderer on
`vv:fullscreen-changed {fullscreen owned}`, and a fullscreen the user leaves (F11, the window manager) is no longer
main's to leave. A boot pull (`vv:fullscreen-request`) seeds `[:ui :fullscreen?]` for a window restored fullscreen,
and `window/persistable-bounds` never saves fullscreen geometry as the window's size. The HTML Fullscreen API was
rejected (Alternatives): it moves the element into the top layer [WHATWG-FS], above the app's own SSH prompt,
context menu and palette; browsers use Escape to leave it [MDN-FS]; and it needs a user gesture [MDN-FS].

**Chrome hidden with CSS.** `.vv-root.vv-presenting` hides the menu bar, sidebar, tabs, address bar, bottom bar and
status bar, and puts the slide on black. Nothing unmounts — the slides view keeps its find, text and link layers,
and Escape brings the chrome straight back. The menubar's Alt access keys are off during a show, so Alt+letter cannot
open an invisible menu. What is not chrome still appears above the show:

| Layer over the slides | z-index |
|---|---|
| a transition's snapshot of the outgoing slide | 6 |
| the black / white blank screen | 7 |
| the overview grid | 8 |
| the "Go to 12" chip; the refresh note (compact during a show) | 9 |
| the find bar | 10 |
| the SSH error toast (scaled down during a show) | 60 |
| the command palette | 100 |
| the context menu | 120 |
| modal dialogs, including the SSH authentication prompt | 130 |

**During a show.** `b` `.` blank the screen to black and `w` `,` to white; the same key again, a slide step, a
click or Escape brings the slide back. A plain primary click on the slide reports `[:slides/click {:shift?}]`, which
advances (Shift: goes back) when the `:slides-click-advance?` preference is on — never a click on a link or a
control, the end of a text selection, or the end of a drag of 4 px or more; a Shift+press is prevented so it cannot
extend a selection. The pointer hides after 2.5 s without moving.

**What ends a show:** Escape's last step; End Slide Show (the context menu, the palette); the view unmounting
(`:slides/unmounted` — a tab switch, a navigation, Reflow), since a show is of a *view*; the window leaving the
fullscreen the show asked for; and focusing chrome the show hides (`:nav/focus` on the address bar, the sidebar or
the focus toggle), which ends the show first so the focus lands on something visible. Ending a show asks main to
leave fullscreen only when the show had asked for it, and not when the window already left it.

![Presenting: F5 → window-level fullscreen owned by main → Escape](../diagrams/sequence-slides-present.svg)

*Diagram source: [`../diagrams/sequence-slides-present.puml`](../diagrams/sequence-slides-present.puml).*

### 6. Overview — a separate overlay (D12)

Tab, `o`, View ▸ Slide Overview, the slide bar's grid button or the palette's Slide overview / Go to slide… opens
`ui/slides-overview`: one tile per **frame**, over the Slides layout, in a tab or during a show. It is a separate
overlay in `.vv-content-wrap`, a sibling of `.vv-content` at z 8, mounted only while `[:ui :slides :overview?]` is
set. That placement buys four properties:

- the slides view stays mounted underneath, so closing is instant and the position is kept;
- the overlay is invisible to in-page find, the Contents scroll-spy and the content MutationObserver, which all work
  on `.vv-content`;
- it **borrows** the mounted view's `PDFDocumentProxy` through `pdf-cache/slides-snapshot` — never a second proxy —
  and never touches the engine's single global document and find-hook slots;
- tiles are per frame, which a layout mode of the engine could not express, since the engine's page ids are per page.

**Tiles and keys.** A frame's tile shows its last page by default (the frame fully built — pdfpc's overview opens a
frame the same way [PDFPC]) or its first, per the `:slides-overview-page` preference. Enter or a click opens the
tile's page and Shift+Enter or Shift+click the frame's other end; an opened tile is a jump, so Back returns, and it
also ends a blanking. Arrows, PgUp/PgDn and Home/End move the selection (`deck/grid-move`: left/right walk the
reading order, up/down keep the column and stop at the edges); digits select a frame by its label (forgotten after
1.5 s); Escape, Tab and `o` close the grid; Ctrl/Alt/Meta chords pass through to the app. The overlay is a focused
`[data-vv-keynav]` surface, the carve-out that keeps `key-scroll!` off a pane with its own keyboard model
([ADR-0040](0040-commit-graph-blame-and-history.md)).

**Geometry.** For an overview pane of width $`W_o`$ and height $`H_o`$, with minimum tile width $`t = 180`$ px and
gap $`g = 16`$ px (`pdf-layout/grid-geometry`):

```math
c = \min\Bigl(8,\ \max\Bigl(1,\ \Bigl\lfloor \frac{W_o - g}{t + g} \Bigr\rfloor\Bigr)\Bigr),\qquad
t_w = \frac{W_o - g\,(c + 1)}{c},\qquad
r = t_w\,\alpha + 22 + g,
```

where $`c`$ is the number of columns, $`t_w`$ the tile width, $`r`$ the row height, $`\alpha`$ the height-to-width
ratio of the first frame's tile page as shown (16:9 when unknown) and 22 px the label row; PgUp/PgDn move by
$`\max(1, \lfloor H_o / r \rfloor)`$ rows. The column count depends on the width alone, so a short deck keeps
ordinary-sized tiles.

**Thumbnails** (`renderer.pdf-thumbs`) render lazily: an `IntersectionObserver` on the overview's own scroller with
a 50 % root margin, a queue of at most two pdf.js renders at a time, each into a detached canvas swapped in when
complete, at most $`1024^2`$ device pixels per thumbnail, and far canvases released with their backing store zeroed.
The observed band is the overview's height plus half of it above and below, $`2H_o`$ in all, so for uniform pages
only the tiles of about $`\lceil 2H_o / r \rceil + 1`$ rows of $`c`$ columns hold a canvas at once, and a
150-page deck costs a few dozen small canvases. A generation counter fences every asynchronous step: a render that
finishes after the overview closed, or after a live refresh replaced the document (`:doc-gen`), does nothing, and a
render against a destroyed document rejects into a swallowed catch.

### 7. Live refresh in place (D11)

**One view per (tab, path).** `content-view` keys the PDF view `pdf:<tab-id>:<path>` (`http-pdf:<tab-id>:<path>`
for a PDF opened from a web link) — no stamp — so a live refresh arrives as a new `stamp` on the **same** component
instance. Its decisions are a pure table (`vinary.ui.pdf-view-lifecycle/update-actions`): remount when another path
occupies the slot; mount when nothing is mounted or preparing; **refresh** when the render carries a stamp other
than the mounted one's and that stamp has not already failed to open; and **always** apply the view state once
mounted, because a refresh lands without a re-render and a change it skipped would otherwise stay lost. A version
that arrives while the first mount is still being prepared is refreshed to as soon as that mount lands
(`refresh-after-mount?`).

**Keep the last good version.** The byte cache (`renderer.pdf-cache`) holds two versions per path,
`{:good {:bytes :stamp} :candidate {:bytes :stamp}}`. Arriving bytes are only a **candidate**. The view prepares the
candidate in a new warm-cache lease beside the live document and, when pdf.js opens it:

1. `pdf/replace-doc!` swaps it into the **mounted** controller. It keeps the reading anchor — the page at the
   viewport top and how far down that page the top sits; in the Slides layout, the slide — clamped if the deck
   shrank; cancels the old renders and bumps the generation (`:gen`) that fences them; bumps `:doc-gen`, which tells
   the overview to re-borrow the document; grows or shrinks the placeholders when the page count changed; relayouts
   and restores the anchor; re-renders the visible pages double-buffered, so the old pixels stay until each new
   raster is complete; and re-extracts the outline. No DOM teardown, no scroll reset.
2. Only then is the previous lease released. The new stamp's arrival already marked that warm-cache entry stale
   (`:render-cache/invalidate`), so the release destroys the old `PDFDocumentProxy` — after the swap, never before.
3. The candidate is promoted to the good version, and `:pdf/deck-detected` and `:pdf/refreshed` follow.

At most one refresh is in preparation; a newer stamp supersedes it. When pdf.js cannot open the candidate — bytes
read while pdflatex was still writing, a corrupt file — the candidate is rejected, the last good document stays
mounted, and `:pdf/refresh-failed` writes the shared **refresh note**. A PDF with no good version to fall back to (a
first open, or a remount whose good version was evicted) shows the error page instead (`:pdf/open-failed`), never
an empty view under a note promising a last good version.

**The refresh note** is one DataScript attribute, `:doc/refresh-note`, shared with ADR-0046 and rendered by
`ui/refresh-note` as a small `role=status` banner over the content with Retry (re-read through the ordinary open
flow) and ×:

| Kind | Written by | When |
|---|---|---|
| `"render-failed"` | `:pdf/refresh-failed` (this ADR) | refreshed bytes did not open in pdf.js while a good version exists |
| `"refresh-failed"` | `:content/error` (ADR-0046) | main could not re-read a document the window already shows |
| `"incomplete"` | `:content/received` (ADR-0046) | main delivered a PDF it caught mid-write and could not wait out |

The next applied read clears any note; a PDF that opens clears a `"render-failed"` one. Main's side — one verified,
numbered read per change, fanned out to every window and ordered by the renderer's content guard — is ADR-0046's,
and it is why a torn PDF is not delivered as a complete one: none was, in 27 311 local and 926 remote complete
deliveries during real compile loops
([experiment 11 §9.7](../scientific/11-remote-read-and-reload-safety-experiments.md#97--h4-and-h5--what-a-change-costs-and-torn-reads-in-real-compile-loops-2026-10-17)).

![Live refresh in place: the new PDF swaps in under the same view, on the same slide](../diagrams/sequence-pdf-refresh-in-place.svg)

*Diagram source: [`../diagrams/sequence-pdf-refresh-in-place.puml`](../diagrams/sequence-pdf-refresh-in-place.puml).*

### 8. Named actions and the jump history in both layouts (S5)

The link layer is rebuilt from pdf.js's link annotations as the app's own `<a>` elements; nothing in the PDF runs.
A URI link opens externally (`:shell/open-external`); a destination link is a **jump** to its page; and a *named*
action (`/S /Named`) gets a link **only** when it is on the allow-list `deck/named-actions`:

| Named action | Does |
|---|---|
| `NextPage` · `PrevPage` · `FirstPage` · `LastPage` | `:next` · `:prev` · `:first` · `:last` — the four standard names of ISO 32000-1 §12.6.4.11 [ISO-32000] |
| `GoBack` · `GoForward` | walk the jump history (viewer-specific names, emitted by Beamer's back–find–forward navigation symbol and by hyperref's `\Acrobatmenu`) |
| `Find` | open the find bar (`[:find/open]`) |
| every other name — `Print`, `SaveAs`, `Quit`, `FullScreen`, … | nothing: no element is created |

So a document can page itself and open find, but cannot print, save, quit, or make the window fullscreen (D13).

In the Slides layout the paging ops go through `[:slides/command op]`, the same path as the keys, so a blanked
screen or a typed number sees them first. In the Document layout `doc-step!` runs the **same** `deck/step` against
the reader's current page and the controller's history, then scrolls the resulting page's top to the viewport top.
There is one history per mounted view, shared by both layouts and kept across a layout switch, at most 64 entries in
each direction. It lives in the engine controller, so it ends with the view (a tab switch or a navigation); the
app's own Back/Forward history is separate. In the Slides layout the palette's "Back to the slide before the last
jump" and "Forward again after Back" walk it too.

### 9. Two-screen decks (S9)

Beamer's `\setbeameroption{show notes on second screen=<side>}` composes every page from two paper-sized halves:
the slide, and the notes on the named side (`right` is Beamer's default). Experiment 12 §4 measured what that puts in
the PDF:

1. every page doubles the paper — $`2w \times h`$ for `right`/`left`, $`w \times 2h`$ for `top`/`bottom`;
2. the notes half repeats the slide's words in a thumbnail, so whole-page find finds each word twice;
3. the page labels are corrupted in ways that are nevertheless well-formed, so no consistency check can detect them;
4. `previous slide on second screen` doubles the page the same way but keeps correct labels; geometry cannot tell
   the two apart.

**Decisions.** A uniform size that doubles a Beamer paper is a **second-screen axis** (`deck/second-screen-axis`),
and such a deck is classified like any Beamer deck (rules 1 and 4 accept the doubled papers). In the Slides layout —
in a tab, in a show and in the overview — only the slide half is shown, chosen by the `:slides-notes` preference,
which names where the **notes** are in the words of Beamer's option (`deck/slide-side`):

| `:slides-notes` | Doubled in width | Doubled in height |
|---|---|---|
| `:auto` (default) | slide on the left (notes right, Beamer's default) | slide on top (notes below) |
| `:right` / `:left` | slide left / slide right | — (the other axis's default: slide on top) |
| `:bottom` / `:top` | — (slide on the left) | slide on top / slide below |
| `:off` | whole pages | whole pages |

The half alone is fitted and rasterized, through a viewport shifted so its corner is the canvas origin, so the notes
cost no pixels and a fullscreen slide keeps the whole pixel budget. The text and link layers keep the full-page
viewport, offset by the half's corner, and the sheet clips the rest (`.vv-pdf-split`). The text runs of the hidden
half are hidden too (`.vv-pdf-offslide`), judged from each run's own inline position (`pdf-layout/run-percent`)
without reading layout, so find, selection and copy see what the eye sees. Such a deck gets one frame per page and
no labels, because a corrupt label would make the counter, the typed number and the frame steps lie; the page number
is always right. The Document layout always shows whole pages, so the notes stay readable and searchable there.
Plain `show notes`, which interleaves note pages at the slide's own size, gives no geometric signal and is shown as is.
*Amended by ADR-0048 §2–§3:* such a deck's note pages are now found from its page labels, or by comparing page text,
and leave the audience's sequence; a deck whose note pages cannot be proven is still shown as is.

### 10. Transitions (S8)

A flip may animate, per the `:slides-transition` preference: `:none` (the default), `:fade` (220 ms) or `:push`
(260 ms). `deck/transition-plan` decides purely from the preference, the two pages and the operating system's
`prefers-reduced-motion: reduce` [MQ5]: no animation when no transition is chosen, when the page does not change, or
when the user asked for reduced motion. The direction follows the page order, so Back after a jump pushes the old
slide the other way. Only flips that run through the engine's `command!` animate — keys, the wheel, clicks, swipes,
the slide bar, links and the overview — while scroll-driven moves (find, the Contents outline) always jump.

The mechanism leaves the instant flip untouched: the outgoing slide's canvas is copied into an overlay at its
on-screen rectangle in `.vv-content-wrap` (z 6, ignoring the pointer, inverted with a dark-inverted document), the
scroller jumps as always, and the Web Animations API fades the copy out, or pushes it one slot width away while the
incoming sheet slides in from the other side. A flip during a transition finishes it at once. A viewer preference
replaces the PDF's own per-page transitions, which pdf.js does not expose: its `PDFPageProxy` has no API for a page's
`/Trans` or `/Dur` entries [PDFJS-API].

### 11. Swipes (S10)

A touch or pen gesture on the slides, from the primary pointer's press to its release, flips one slide when it is
quick, long and mostly horizontal (`deck/swipe-op`):

```math
\Delta t \le 700\,\mathrm{ms} \;\wedge\; |\Delta x| \ge 48\,\mathrm{px} \;\wedge\; |\Delta x| \ge 1.5\,|\Delta y| ,
```

leftward ($`\Delta x < 0`$) to the next slide, rightward to the previous, like turning a page. A mouse drag never
flips a slide, because it selects text. The Slides layout sets `touch-action: pan-y pinch-zoom`, so it takes
horizontal gestures itself while vertical pans and pinch-zoom stay the browser's. A swipe runs through `step!` like a
key, so a blanked screen sees it first.

### 12. The layout choice belongs to the document view (D14) — and so does the slide

An explicit View as Slide Show / View as Document is recorded as `:pdf-layout` (`:slides` or `:document`) on the tab
**and** its current history entry (`nav/set-pdf-layout`), like the view facet. Navigating to another document — a
new entry — drops it, so a choice made on deck A never forces the next PDF opened in that tab into slides; Back and
Forward restore it with the entry; a facet switch within a document group keeps it.

**Remembering the slide.** A history entry is `{:uri :scroll :line :facet :pdf-layout :page}`. When the view being
left shows the active document in the Slides layout, the `:view-pos` coeffect adds its slide as `:page` (read from
the engine through `pdf-cache/slides-snapshot`), because a pixel `:scroll` depends on the window size in that
layout. Restoring an entry with a `:page` parks `{:path :page}` (`[:slides/want-page …]`); the PDF view of that path
takes it when it mounts, opens the engine on that slide and discards any pending pixel restore. This holds for
remote `ssh://` and `sftp://` documents too, which are keyed by their URI (S2).

### 13. Preferences

The Preferences dialog's **Slides** section, persisted in `settings.edn`:

| Key | Values (default first) | Alt key | Effect |
|---|---|---|---|
| `:slides-open-as` | `:slides` · `:document` | O | how a **detected** deck opens |
| `:slides-overview-page` | `:last` · `:first` | W | which page of a frame its overview tile shows and opens |
| `:slides-transition` | `:none` · `:fade` · `:push` | R | how a flip animates (§10) |
| `:slides-notes` | `:auto` · `:right` · `:left` · `:bottom` · `:top` · `:off` | N | where a two-screen deck's notes are (§9) |
| `:slides-fullscreen?` | `true` · `false` | U | whether a show makes its window fullscreen |
| `:slides-click-advance?` | `true` · `false` | K | whether a click on the slide advances a show |

The settings layer stores an unchecked checkbox as `false`, so the two default-on preferences read `(not= false v)`:
an absent key means on, and only an explicit `false` turns them off. Whether a *particular* PDF shows as slides is
not a preference — it is the per-view choice of §12.

### 14. Entry points

Every slide action is reachable without the keyboard (the full list is in
[feature 35](../features/35-slide-shows.md#2--how-to-use-it)): View ▸ View as Slide Show (`l`) ↔ View as Document
(`m`), Start Slide Show (`h`, F5), Start from Current Slide (`u`, Shift+F5) and, in the Slides layout, Slide Overview
(`e`); the address bar's slides toggle for any PDF; the PDF's context menu (Start Slide Show, Start From This Slide,
the layout toggle; End Slide Show during a show); and, in the Slides layout, a slide bar in place of the zoom control
(◀ page / N ▶ · frame f / F · overview · present). The palette gains a **Slides** category, and "View as slide show /
document" in **View**. Reflow Text's access key moved from `r` to `w`, so that no two visible View items share a key.

## Consequences

**Measured.**

- A neighbour flip shows in the next frame: median 7.9 ms, p95 15.1 ms on a 60 Hz display, down from 24.3 / 29.5 ms;
  every neighbour is pre-rendered, and at most three page canvases are alive (experiment 12 §8).
- 42 of 42 controlled Beamer builds were classified as predicted, and every slide-tool export at a known canvas in
  the natural corpus was found (experiment 12 §5–§7).

**What the user sees.**

- A deck opens as slides on its first mount, with no flash; detection adds no I/O.
- Every keymap flips slides without rebinding; Shift steps whole frames; digits + Enter go to the slide whose number
  is printed on it.
- F5 presents any PDF; the window goes fullscreen only when the show asks for it and leaves only a fullscreen the app
  entered; the SSH prompt, the palette and the find bar stay usable above the show.
- A live refresh — local or remote, while authoring or presenting — keeps the slide, never flashes, never shows a
  half-written deck, and never replaces the deck with an error page.
- Beamer's navigation symbols work, in both layouts.

**Trade-offs accepted.**

- Zoom does nothing in the Slides layout; slides fit themselves.
- In the Slides layout, Tab opens the overview instead of moving the focus, and Space is a slide key; both are
  rebindable under `:keymaps {:slides …}`. In Vim's normal mode Space stays the leader, so → or `j` flip there.
- A Beamer deck from a single XeLaTeX pass has no page labels, so it steps one page at a time and its overview shows
  every page; so does a two-screen deck. *Corrected on 2026-10-17: this said that xdvipdfmx writes no labels. hyperref
  writes XeLaTeX's labels from the ones the previous run left in the `.aux` file, so the second pass has them, and one
  pass over an edited deck carries the previous version's labels
  ([experiment 12](../scientific/12-slide-deck-detection-and-flip-latency.md) §5.1).*
- Rule 4's documented false positive: an article typeset on Beamer paper by a TeX engine opens as slides.
- A real deck at a canvas the detector does not know opens as a document; View as Slide Show switches it, and
  `--deck-info` shows why.
- The jump history ends with its view.
- A failed refresh no longer shows an error page: the last good version stays behind a note. That is the point, but
  it is a visible behaviour change.
- The presenter console and the terminal slide mode remain deferred feature requests; both can reuse
  `vinary.slides.*` unchanged.

## Alternatives considered

- **Logic inside `renderer/pdf.cljs` (D1).** Rejected: it would tie detection and navigation to pdf.js and the DOM,
  out of reach of the node unit tests, the CLI diagnostic and the deferred terminal and presenter work.
- **Detect after the first mount, or in main (D2).** Rejected: after the mount, a deck would flash as a document
  first; in main, every PDF would be parsed twice — once by main for the evidence and once by the renderer to draw
  it — on every path main serves (local, `sftp://`, archive members, web downloads), while the renderer already holds
  the loaded document.
- **One page in the DOM at a time (D3).** Rejected: find, the text layer and the Contents outline need every page.
  **CSS scroll-snap alone** was rejected too: snapping is the least of it — the full-viewport slots and the per-page
  fit still need the engine, which must also know the current slide for the counter, the menus and the history.
- **A class on `.vv-content` to hide the scrollbar (D4).** Rejected: React owns that class list, and app-db lags a
  frame behind on first open.
- **Snapping during the scroll (D5).** Rejected: it fights smooth scrolls and lands on the wrong slide.
- **Making `key-scroll!` stand aside for slides (D6).** Rejected: only the bare arrow keys would change; Vim's,
  Emacs's and the palette's scroll commands would still scroll pixels, and the smooth-scroll animator would fight the
  snap.
- **A `:slides` layer merged above the mode (D7).** Rejected: a shallow merge above the mode replaces whole prefix
  maps — Vim's Space leader would stop working.
- **Caching "slides active" in app-db (D8).** Rejected by ADR-0032: focus must be derived, and a cached flag goes stale
  when a focused element is removed.
- **Handling Escape outside `:input/escape` (D9).** Rejected: Escape's switch to normal mode would run as a side effect
  of cancelling a slide step.
- **The HTML Fullscreen API (D10).** Rejected: its top layer hides the SSH prompt, the context menu and the palette;
  the browser owns Escape; it needs a user gesture. **Always leaving fullscreen when a show ends** was rejected too: it
  would pull the user out of a fullscreen they had chosen.
- **A second engine instance for a refresh, or a remount (D11).** Rejected: a second instance duplicates the page ids
  and observes the wrong root; a remount flashes and loses the position.
- **The overview as a layout mode of the engine (D12).** Rejected: tiles are per frame while the engine's page ids are
  per page, and a grid inside `.vv-content` would be searched by find and seen by the scroll-spy.
- **Honouring `/PageMode /FullScreen` by going fullscreen on open (D13).** Rejected: the user chose the Slides layout
  for detected decks, and a document must never be able to make the window fullscreen.
- **A sticky per-tab layout choice, like the diff view's (D14).** Rejected: an explicit choice on deck A would force
  the next PDF opened in that tab into slides.
- **Only the first hop synchronous (H-sync).** Measured and refuted: the delegate's queued second hop kept the frame
  of delay (experiment 12 §8.4).
- **"Landscape means deck".** Rejected by the natural corpus: hundreds of landscape figures and print-outs.
- **Trusting a two-screen deck's labels.** Rejected by measurement: they are wrong in well-formed ways.

## Verification

| Level | What is checked |
|---|---|
| Unit (`:node-test`) | `slides/deck_test`: every detection rule and its negatives (landscape Letter, a one-page beamerposter, mixed sizes, the tolerance, A4 landscape against Impress's 28 × 21 cm), frames and `\againframe`, every `step` op with clamping and the history bound, goto, digits, scroll, wheel, swipe, transitions, named actions, the second-screen geometry. `slides/evidence_test`: every signal, rejections and synchronous throws. `renderer/pdf_layout_test`: slot geometry, anchors across a rescale, the pixel budget, text-run positions. `renderer/pdf_cache_test`: candidate, promote, reject. `ui/pdf_view_lifecycle_test`. `app/slides_test`: the per-view layout, the remembered slide. `app/slides_keys_test`: the layer identical in every preset, its resolution below the mode, user overrides, typed numbers, the Escape order, the overlay predicates, the delegate seam, the immediate commands and the engine shortcut's soundness. `app/slides_present_test`: starting, ending, blanking, clicks, F5 in every preset, main's `decide` table, no fullscreen bounds saved. `app/slides_overview_test`, `ui/slides_entry_test` (menus, unique access keys, context menu), `app/refresh_note_test` |
| Electron smoke (dev and release builds) | detection (Beamer, slide export, FullScreen page mode, a landscape non-deck); the layout (slots fill the pane, navigation, the toggle, a refresh keeps the slide); the keys through the delegate and the layer; presenting (fullscreen requested, chrome hidden, blanking, click-advance, an SSH prompt over the show, Escape, leaving fullscreen); the overview (thumbnails bounded on a 150-page deck); every entry point; named actions; the remembered slide; the Document-layout jump history; swipes; transitions; second-screen notes, including a real LuaLaTeX deck; live refresh in place and the refresh note |
| CLI smoke | `--deck-info` on a real Beamer deck, a PowerPoint-shaped export and an ordinary PDF; a missing file reported without stopping the rest |
| Real-main end to end | `fullscreen-e2e`: F5 enters real fullscreen and Escape leaves it; a fullscreen the user made survives a show; leaving fullscreen ends the show; `window.edn` never gets fullscreen geometry. `remote-pdf-e2e`: a Beamer deck over SFTP opens as slides in three windows, one read per change, ordered under a delayed read, a torn and a refused read keep the deck behind a note, a connection dropped mid-read **while presenting** keeps the slide. `watch-e2e`: a PDF rewritten in place goes from v1 straight to v2; a half-written one keeps the last complete version |
| Measurement | [experiment 12](../scientific/12-slide-deck-detection-and-flip-latency.md): the detection corpora and the flip-latency guards H-flip-1…4, re-runnable with `npm run bench:flip` |

**Implementation record.** F1 `d351d6a` deck model · F2 `ff11395` detection on prepare · C1–C3 `674875f`,
`34320a0`, `ecf0176` sheets, double-buffering, refresh in place · F3 `c493bfd` Slides layout · F4 `90c2c34` keys ·
F5 `23a6886` presenting · F6 `3d9488e` overview · F7 `15635f1` entry points · F8 `25a3908` named actions · F9
`a45d1f4` remembered layout and slide · S4 `498d394` · S5 `0490dc3` · S10 `3590fd5` · S8 `805f92c` · S9 `a34d814` ·
`--deck-info` `aa38734` · Impress and Keynote canvases `527e06d` · keys at once `37d8d41` · experiment 12 `3699d0b`.

## References

- [docs/scientific/12 — Slide-deck detection and flip latency](../scientific/12-slide-deck-detection-and-flip-latency.md)
  and [docs/scientific/11 — Remote read and reload-safety experiments](../scientific/11-remote-read-and-reload-safety-experiments.md).
- [Feature 35 — Slide shows](../features/35-slide-shows.md); [ADR-0013](0013-in-renderer-pdfjs.md),
  [ADR-0010](0010-bounded-content-retention-and-render-metadata.md),
  [ADR-0032](0032-scroll-ownership-and-derived-input-focus.md), [ADR-0035](0035-authenticated-remote-daemon-events.md),
  [ADR-0040](0040-commit-graph-blame-and-history.md), [ADR-0046](0046-reload-safe-pipelined-remote-reads.md).
- **[ISO-32000]** ISO 32000-1:2008, *Document management — Portable document format — Part 1: PDF 1.7*: §7.7.2
  *Document Catalog* (`/PageMode /FullScreen`) and §12.6.4.11 *Named Actions*. Adobe's public copy:
  <https://opensource.adobe.com/dc-acrobat-sdk-docs/pdfstandards/PDF32000_2008.pdf>
- **[BEAMER]** T. Tantau, J. Wright, V. Miletić and samcarter, *The beamer class*, CTAN package `beamer`.
  <https://ctan.org/pkg/beamer>
- **[PDFPC]** pdfpc, the PDF presenter console, manual page (`man/pdfpc.in`): next and previous keys, Shift+PgDn/PgUp
  by "user slide", `b` to blank, Tab for the overview, Return / Shift+Return to open a frame's last / first overlay,
  Escape to leave blank, overlays found through page labels. <https://github.com/pdfpc/pdfpc/blob/master/man/pdfpc.in>
- **[PDFJS-PM]** Mozilla, *PDF.js*, `web/pdf_presentation_mode.js`: `PAGE_SWITCH_THRESHOLD = 0.1`,
  `MOUSE_SCROLL_COOLDOWN_TIME = 50`. <https://github.com/mozilla/pdf.js/blob/master/web/pdf_presentation_mode.js>
- **[PDFJS-OPT]** Mozilla, *PDF.js*, `web/app_options.js`: `maxCanvasPixels` = $`2^{25}`$.
  <https://github.com/mozilla/pdf.js/blob/master/web/app_options.js>
- **[PDFJS-API]** Mozilla, *PDF.js API*: `PDFDocumentProxy` (`getMetadata`, `getPageMode`, `getPageLabels`) and
  `PDFPageProxy`. <https://mozilla.github.io/pdf.js/api/draft/module-pdfjsLib-PDFDocumentProxy.html>,
  <https://mozilla.github.io/pdf.js/api/draft/module-pdfjsLib-PDFPageProxy.html>
- **[ELECTRON-BW]** Electron, `BrowserWindow`: `setFullScreen`, `isFullScreen`, `fromWebContents`, the
  `enter-full-screen` / `leave-full-screen` events. <https://www.electronjs.org/docs/latest/api/browser-window>
- **[WHATWG-FS]** WHATWG, *Fullscreen API Standard*, §2: "To fullscreen an element … Add to the top layer given
  element." <https://fullscreen.spec.whatwg.org/>
- **[MDN-FS]** MDN, `Element.requestFullscreen()`: "Transient user activation is required"; "Most browsers use the Esc
  key to exit normal fullscreen mode". <https://developer.mozilla.org/en-US/docs/Web/API/Element/requestFullscreen>
- **[RF-API]** re-frame API, `dispatch-sync`: "It is an error to use `dispatch-sync` within an event handler."
  <https://day8.github.io/re-frame/api-re-frame.core/>
- **[HTML-EL]** WHATWG, *HTML Living Standard*, §8.1.7.3 *Processing model* (the event loop).
  <https://html.spec.whatwg.org/multipage/webappapis.html#event-loop-processing-model>
- **[MQ5]** W3C, *Media Queries Level 5*, §12.1 `prefers-reduced-motion`. <https://www.w3.org/TR/mediaqueries-5/>
- **[BLINK-IO]** Chromium, Blink `intersection_observer/intersection_geometry.cc`, `ResolveMargin`: top and bottom
  root margins resolve against the root's height, left and right against its width.
  <https://chromium.googlesource.com/chromium/src/+/refs/heads/main/third_party/blink/renderer/core/intersection_observer/intersection_geometry.cc>
  (The W3C draft, <https://www.w3.org/TR/intersection-observer/>, words every percentage against the width.)
- **[MS-SLIDES]** Microsoft, "Change the size of your slides" (On-screen Show (16:10), 10 × 6.25 in).
  <https://support.microsoft.com/en-us/powerpoint/change-the-size-of-your-powerpoint-slides>
- **[KN-APPLE]** Apple, "Keynote for Mac: Optimizing your presentation for iOS compatibility" — the Standard (4:3)
  and Widescreen (16:9) slide sizes, named without dimensions. <https://support.apple.com/en-ca/101426>
- **[KN-1920]** "Presentation App Default Page Size", *The PowerPoint Blog*, 2026-01-13: "Apple Keynote: 1920 x
  1080px (~96ppi), 26.67 x 15in". <https://thepowerpointblog.com/2026/01/13/>
