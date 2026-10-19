# 0048 — Speaker notes during a slide show, and two linked windows driven from either one

- **Status:** Accepted (2026-10-19), implemented in full; the timer, the pace, the wall clock and slide transitions
  were moved out of it (the scope change below). Amends [ADR-0045](0045-slide-deck-presentation.md) (§5
  *Presenting*, §9 *Two-screen decks* and its Consequences). Builds on
  [ADR-0046](0046-reload-safe-pipelined-remote-reads.md) (the `seq` guard),
  [ADR-0047](0047-persisted-state-stores.md) (a window never receives its own write back; standby windows),
  [ADR-0009](0009-mediator-ipc-over-point-to-point.md) (every cross-process message goes through main) and
  [ADR-0010](0010-bounded-content-retention-and-render-metadata.md) (lean DataScript entities).
- **Date:** 2026-10-17
- **Deciders:** vinary-viewer maintainers
- **Scope change (2026-10-17, the user's decision):** the presenter timer, the pace and the wall clock (P4), and slide
  transitions in the presenter view and across linked windows, are not part of this decision. They are filed as the
  feature request `vinary-viewer-presenter-timer-pace-and-wall-clock-slide-transitions-in-the-presenter-view-and-across-linked-windows-64e248`. Their design is kept in §6.3 and §6.5 as that request's starting point; until it
  lands, a flip with the pane shown, or followed from the other window, is instant.

## Context

ADR-0045 made a PDF deck a slide show: F5 hides the chrome, fits one slide per screen and asks main for a
window-level fullscreen. It left speaker notes and a presenter console as deferred requests. Presenters asked for
both: *"I need a way to view the notes for each slide in full-screen mode when notes are provided. Entering
presentation mode on a deck with notes hides the notes and makes it where you cannot review them unless you disable
presentation mode. It would also be useful to view the notes from one vv window and the presentation from another,
and have their transitions synchronized from either window."*

What the code did, read before this decision (file references are to the tree at `5b30829`):

| # | Finding | Where |
|---|---|---|
| F1 | **A second-screen deck hides its notes while presenting.** Presenting forces the Slides layout, which shows and rasterizes only the slide half and hides the notes half's text runs. The only way to see the notes during a show — Preferences ▸ *Notes on a second screen: Show whole pages* — shows them to the audience as well. | `slides/deck.cljs` `effective-layout`; `renderer/pdf.cljs` `split-side`, `render-page!`, `hide-offslide-runs!` |
| F2 | **An interleaved `show notes` deck is mis-presented.** Beamer's `\setbeameroption{show notes}` puts a note page after every overlay that has notes (and one more for every `\note` written between frames), and labels it with its *physical* page number. ADR-0045 §9 shows such decks as they are: the note pages are flipped through as slides, every note page becomes a frame of its own (frames are runs of equal labels), and typing `2` goes to page index 1 — the first note page — because labels are tried first. | `deck.cljs` `frames-from-labels`, `resolve-goto`; experiment 12 data, finding 5 |
| F3 | **pdfpc notes are never read.** pdfpc's `\pdfpcnote` writes hidden `/Text` annotations (`/F 6`, Hidden + Print), and pdfpc also keeps notes in a `talk.pdfpc` file beside the PDF. The deck evidence reads only `Creator`, `Producer`, `CreatorTool`, `/PageMode` and the labels; the link layer keeps only `/Link` annotations. pdf.js 5.4.149 does return the hidden notes (`getAnnotations` keeps every annotation that is not Invisible or NoView). | `slides/evidence.cljs` `gather`; `pdf.cljs` `build-link-layer!`; `pdf.worker.mjs` `_isViewable` |
| F4 | **Nothing lets a renderer open a window or reach another one.** The preload exposes no window API; the window-open handler denies; `open-window!` is the only caller of `claim-window!`, and a claim shows and focuses its window. Every window opens with the same saved bounds, and one module-level save timer serves all windows, so one window's move cancels another's pending save. SSH prompts go to the focused window — which, during a show, may be the projector. A Wayland client cannot place its own windows. | `main/core.cljs`, `main/window.cljs`, `main/ssh.cljs`, `main/windows.cljs` |
| F5 | **`s`, `S` and `F` are free** in every layer and mode of all three presets: none binds them at the root of a layer (`s` occurs only inside Vim's `SPC` sequences, `f` is Vim's link hints, `n` its search-next). The palette and the menu bar carry no descriptions. | `resources/keymaps/*.edn`; `ui/palette.cljs`, `ui/menubar.cljs` |
| F6 | **One position funnel.** Every slide change — keys, clicks, links, the overview, find, swipes, a refresh's clamp — goes through the engine's `report!`, coalesced to one `:slides/changed` per animation frame and read when the frame fires. | `pdf.cljs` `report!` |

What the formats carry, established before this decision (§1 and §2 have the details):

- Beamer decks carry page labels when compiled by pdfLaTeX or LuaLaTeX, and by XeLaTeX from its second pass on.
- A single XeLaTeX pass writes none (§2.4). A three-frame `show notes` deck gave the labels `1 2 1 4 2 3 7` under all
  three engines: after one pass under pdfLaTeX and LuaLaTeX, and after two under XeLaTeX, whose first pass gave `null`.
- pdfpc's own sources (v4.7.0, unchanged on master as of 2026-04-23) define its sidecar file, XMP keys, timer and end
  slide.

## Decision

### Vocabulary

| Term | Meaning |
|---|---|
| speaker notes | What the presenter reads and the audience does not see. |
| split notes | Notes on one half of every page — Beamer's `show notes on second screen=<side>` (ADR-0045 §9). The other half is the slide. |
| note page | A whole page of notes, interleaved after the overlay it belongs to — Beamer's `show notes`. |
| text notes | Notes carried as text: a page's annotation contents (`\pdfpcnote`, sticky notes) or the pdfpc sidecar file. |
| shown page · hidden page | A page the audience sees in the Slides layout · a note page, which the audience never sees there. |
| presenter view | This window shows the current slide beside a pane with the slide counter, the notes and the next slide. |
| audience view | This window shows only the slide — the Slides layout as ADR-0045 made it. |
| linked show | Two windows of one deck whose slide shows move together; main runs it as a **session**. |
| presenter window · audience window | The window that linked the show (it shows the presenter view) · the window main opened for it. |
| position | What a window shows: a physical page index and a blank colour (`nil`, `:black`, `:white`). |
| move · ack · push | A window's new position, sent to main · main's reply to the sender, carrying the number it gave the move · main's copy of the move, sent to every other window. |
| `seq` · `mid` | Main's per-session move number · the sender's own per-window move counter. |

### Decisions at a glance

| # | Decision |
|---|---|
| N1 | A pure, DOM-free notes model, `vinary.slides.notes`, describes where a deck's notes are: split, interleaved, text, or none. |
| N2 | Interleaved note pages are found from the labels by an exact automaton. Ambiguity is settled by a probe that walks back from the last candidate and reads two pages when a deck has no notes. A deck without labels is settled by a content scan. A parse from a driver whose labels lag a pass (XeLaTeX, dvipdfmx, dvips) is confirmed page by page before its note pages leave the sequence. The probe, the scan and the confirmation run in the background after the first paint. |
| N3 | Note pages leave the audience's sequence but stay in the DOM: navigation runs over shown pages, a page snaps only downward, typed numbers match shown labels. |
| N4 | Text notes come from the annotations pdfpc reads and from the pdfpc sidecar file, per page; both are shown when both exist. They are rendered as sanitized Markdown when the deck carries pdfpc metadata (as pdfpc does), as plain text otherwise, and never as raw HTML. |
| N5 | pdfpc's settings are read from the raw XMP and from the sidecar (XMP first): notes position, duration, start and end time, last minutes, end slide, Markdown, hidden pages and forced overlays. |
| N6 | The GUI and `vv --cli --deck-info` run one detection pipeline, `evidence/detect`, so the CLI prints the GUI's decision. |
| N7 | Main reads the sidecar beside the PDF — locally and over SFTP — and attaches it to the PDF's payload. A local sidecar is watched by the PDF's own content watcher, so creating, editing or deleting it re-delivers the PDF; over SFTP a change to the sidecar alone shows at the PDF's next read. A piped-in PDF and an archive member have no sidecar. |
| P1 | `s` toggles the **presenter view** in any slides view, windowed or presenting. A single-window show starts in the audience view. |
| P2 | The engine is never remounted: the pane is an absolutely positioned sibling of the slides, and the slides shrink beside it. |
| P3 | The pane renders the notes regions and the next slide from the engine's document into canvases of its own (`vinary.renderer.pdf-region`). While the pane is shown, a flip is instant (transitions clipped to the slides are deferred, §6.3). |
| P4 | The counter counts frames up to pdfpc's end slide (`vinary.slides.presenter/counter`). The timer, the pace and the wall clock are deferred to a feature request (§6.5). |
| P5 | When a single-window show starts on a deck with notes, a hint names `s` and *Present in New Window*, once: then `:slides-notes-hinted?` is saved. |
| F1 | `F` (Shift+f) toggles this window's fullscreen during a show, through main's existing ownership rule. |
| L1 | **Present in New Window** (the View menu, the palette, and `S` = Shift+s; offered for every PDF view) links a second window: main opens an **audience window** from the warm pool without taking the focus, on another display when there is one, fullscreen there when the platform lets it place windows. The speaker notes stay in the window it was chosen in. |
| L2 | Main sequences the session: every move gets a `seq`, an ack goes to its sender and a push to every other member. A window holds a push that arrives while its own move is on the way. The protocol converges, never echoes and never undoes a move (proof and model check: [theory/11](../theory/11-linked-slide-show-protocol.md)). |
| L3 | A window sends its position through one interceptor that runs after the engine's report or a local blank change, never after an incoming message. |
| L4 | The audience window starts at the position main gave it and announces itself with a `ready` message that moves nothing. |
| L5 | Either window ends the show; main closes the audience window it opened. A member that closes, reloads, crashes or loses its display ends the session. |
| L6 | window.edn is saved per window, and never for an audience window. |
| L7 | SSH prompts never go to an audience window. |
| L8 | The trust boundary widens by exactly three capabilities, each constrained (§8.9); the threat model is updated. |
| L9 | With the two windows on different displays, **Swap screens** in the presenter view moves each window to the other's display, keeping each one's fullscreen (§8.7). |

---

### 1. Where notes come from

| Source | How the notes are carried | Read | Detected by |
|---|---|---|---|
| Beamer `show notes on second screen=<side>` | every page is a slide half plus a notes half | yes (the split exists — ADR-0045 §9) | geometry: `deck/second-screen-axis`; side from the preference, pdfpc's notes position, or the geometric default |
| Beamer `show notes` | a note page after each overlay that has an in-frame note, and one for each `\note` between frames, labelled with its physical page number | yes | the label automaton (§2.1), the probe (§2.3), or the content scan (§2.4) |
| Beamer `show only notes` | note pages only — a handout of notes | shown as it is: it *is* the notes, a document | — |
| pdfpc `\pdfpcnote`, any sticky note | the `/Contents` of `/Text`, `/FreeText`, `/Highlight`, `/Underline` and `/Squiggly` annotations on each page — the set pdfpc reads | yes, per page, on demand | `page.getAnnotations()` |
| pdfpc sidecar `talk.pdfpc` | JSON (`pdfpcFormat` ≤ 2) or pdfpc's legacy text format, beside the PDF | yes (N7) | main reads it with the PDF |
| pdfpc XMP keys | `pdfpc:`-prefixed elements in the XMP packet (`pdfpc.sty`) | yes (N5) | the raw XMP |
| PowerPoint / Keynote / Google Slides notes pages | each page is a portrait page with the slide drawn above its notes | no: there is no separate slide page to show the audience, and the slide's rectangle on the page comes from each tool's notes template, not from the PDF | not a deck (no export canvas matches — ADR-0045 rule 3) |

**Out of scope, with reasons.** A notes PDF that is a *separate file* from the slides (Beamer `show only notes`
compiled on its own) has nothing in either file that pairs it with the slides; it opens as a document, and two windows
— one per file — show both. Notes-page exports from presentation programs are not decks (the table's last row).

### 2. Finding interleaved note pages (N2)

#### 2.1 The label automaton

Beamer labels every slide page with its frame number (`\thispdfpagelabel{\insertframenumber}`, `beamerbaseframe.sty`).
A note page gets no label of its own, so hyperref labels it with the page counter, which is its physical page number.
In `show notes` mode a note page follows every overlay that has an in-frame `\note`, and every `\note` written
*between* frames adds one more page at once (`beamerbasenotes.sty`). So two note pages can follow each other: a
frame's last note page, then the page of a `\note` written after that frame.

Let the deck have $`n`$ pages with labels $`L_0, \dots, L_{n-1}`$, all non-negative integers. A **parse** assigns each
page a role, *slide* or *note*. It is valid when it obeys the rules Beamer's output obeys:

1. page 0 is a slide, and the current frame number is $`f := L_0`$;
2. a slide page carries the current frame number or the next one, $`L_i \in \{f, f+1\}`$, and then $`f := L_i`$.
   The same number is an overlay, or a frame marked `noframenumbering`; the next number is a new frame, `\againframe`
   included (it steps the frame counter like any frame);
3. a note page is any page after page 0 that carries its own physical page number, $`L_i = i + 1`$. It leaves $`f`$
   unchanged.

**The valid parses, gap by gap.** Call page $`j`$ **eligible** when $`j \ge 1`$ and $`L_j = j + 1`$: by rule 3 only an
eligible page can be a note page. Every other page, and page 0, is a **required slide**, and sets $`f`$ to its own
label, whatever came before it. Between two consecutive required slides $`r < r'`$ lies a **gap**, the eligible pages
$`r + 1, \dots, r' - 1`$; after the last required slide lies the **tail**, the gap that runs to the end of the deck. Two
facts make the gaps independent of each other:

- The labels of a gap's pages are consecutive ($`L_j = j + 1`$), and a slide's label is the previous slide's or one more
  (rule 2). So two slides chosen in one gap, with no slide between them, are adjacent pages: the slides a parse chooses
  in a gap form one **block** $`[x, y]`$ of consecutive pages, or there are none.
- A required slide fixes $`f`$ to its own label, so what a parse does in one gap cannot constrain another gap.

A parse is therefore one choice per gap, and the valid parses are all the combinations of the gaps' valid choices
(the proof is [theory/12](../theory/12-speaker-notes-detection.md) §2). Each gap has few valid choices:

| Choice in the gap after $`r`$ (up to $`r'`$) | Valid when |
|---|---|
| no block: every page of the gap is a note page | $`L_{r'} \in \{L_r, L_r + 1\}`$ — always, in the tail |
| a block $`[x, y]`$, the pages outside it note pages | $`x \in \{L_r - 1, L_r\}`$ (page $`x`$'s label $`x + 1`$ follows $`L_r`$) and $`y \in \{L_{r'} - 2, L_{r'} - 1\}`$ ($`L_{r'}`$ follows page $`y`$'s label $`y + 1`$); in the tail, any $`y \ge x`$ |

**When $`L_0 \le 1`$, only the first gap can offer a choice.** Along any valid parse a slide's label is at most its page
index plus one, so a later required slide $`r`$ has $`L_r \le r`$, and $`x \in \{L_r - 1, L_r\}`$ leaves no block that
starts in its gap ($`x \ge r + 1`$): every later gap's only valid choice is "no block". The first gap is the deck's
**plain run**, described next.

**The first note page decides a parse.** Suppose $`L_0 \le 1`$: Beamer numbers the first frame 1, or 0 when that frame
is marked `noframenumbering`. Then a valid parse is determined by where its first note page is, or by having none.

- Before the first note page, every page is a slide.
- After it, the physical page number has run ahead of the frame number. A slide raises $`f`$ by at most one, so at a
  page $`i`$ after the first note page, $`f \le L_0 + (i - 2) \le i - 1`$. A note label $`i + 1`$ is then neither $`f`$
  nor $`f + 1`$, and a slide label is not $`i + 1`$, so every later page has exactly one possible role.
- For the same reason, a page can be either a slide or a note only while every page before it began a new frame: the
  labels so far read $`1, 2, \dots, i`$, and $`L_i = i + 1`$. Call this the deck's **plain run**, of length $`m`$.
- Under the parse whose first note page is $`a`$, every page from $`a`$ to the end of the plain run is a note page.
  Its frame number stays $`a`$, which no later label $`j + 1 > a + 1`$ in the run can carry.
- A deck whose first label is 0 is never ambiguous.

| Valid parses | Result (`interleaving`'s `:status`) |
|---|---|
| none | **invalid** (`:invalid`): shown as it is |
| exactly one, without note pages | not interleaved (`:none`) |
| exactly one, with note pages | **proven** (`:proven`): that parse's note pages, subject to §2.5's confirmation |
| several, differing only in the plain run of a deck with $`L_0 \le 1`$, with distinct first note pages | **ambiguous** (`:ambiguous`): each parse with note pages is a *candidate*, named by its first note page, and the probe (§2.3) chooses; whether the parse with no note page in the run is valid is recorded too |
| several, otherwise, which needs $`L_0 \ge 2`$: a frame counter set by hand before the first frame | **undecidable** (`:undecidable`): shown as it is |

**Why only a deck with $`L_0 \ge 2`$ is undecidable.** With $`L_0 \le 1`$, a deck with several valid parses is always
ambiguous: its parses differ only in its plain run, and their first note pages are distinct
([theory/12](../theory/12-speaker-notes-detection.md), Theorem 12). `interleaving` returns `:undecidable` when any of
five conditions holds: more than one gap with a choice, which never occurs, whatever $`L_0`$ is (theory/12,
Proposition 8); a gap with a choice other than the first; $`L_0 \ge 2`$; a choice whose block does not start right
after its gap's required slide; or two parses with the same first note page. Every deck that meets one of them has
$`L_0 \ge 2`$. Some such decks meet only that condition: their parses still differ only in the first gap, by distinct
first note pages, and the probe could tell them apart. Showing them as they are is conservative, not wrong (theory/12
§2.3).

**A restarted frame counter.** A label that drops — the frame counter restarted, as `appendixnumberbeamer` does at
`\appendix`, or set back by hand — is outside the rules. A rule that admitted a restart to 1 was considered and
rejected. It would make the most common pattern ambiguous: a first frame with two overlays and notes, `1 2 1 …`, would
also parse as slide, new frame, restart. A drop usually leaves no valid parse, and the deck is shown as it is. But not
always. The plain run of a deck **without** notes, followed by an appendix that restarts at 1, reads `1 2 3 4 5 1 2`. The
rules admit exactly one parse of those labels: page 0 a slide, pages 1–4 note pages, page 5 an overlay of frame 1, page
6 a new frame. Nothing in the labels refutes it, and it would hide four slides. So a parse counts as proven **by the
labels alone** only when they cannot also be read as all slides with the counter restarted at every drop, that is, when
some label is at least two more than the one before it (`restartable?` is false). Labels that are `restartable?` send
the parse to confirmation by page content (§2.5), whatever the producer. A proven parse's labels are `restartable?`
exactly when its note pages are one run directly after the plain run, all annotating the run's last slide: any note
page after a later slide makes its label jump by two or more ([theory/12](../theory/12-speaker-notes-detection.md),
Proposition 16). Among the decks whose counter never restarts, the $`n`$-page ones proven with such labels have
exactly $`2^{\,n-2} - 1`$ forms (theory/12, Corollary 17): $`L_0 = 1`$, note pages $`1, \dots, m`$, page $`m + 1`$ an
overlay of frame 1, and only slides after it. A trusted producer's deck of one of those forms is confirmed instead of
hidden at once. That costs reading its pages, and nothing more when the confirmation passes on the true parse: with the
`default` template, or `compressed` when every slide that has notes has text (theory/12 §4.1, T1 and T4). Otherwise
the confirmation fails and the deck is shown as it is (§2.5).

The automaton runs only for Beamer sources (the same gate as `frames-from-labels`), never on a second-screen deck
(those labels are corrupt — ADR-0045 §9), and only when every label is a non-negative integer.

The computation is one pass over the pages and one over the gaps (`notes/interleaving`, `gap-choices`):

```
procedure INTERLEAVING(L[0..n-1])
  eligible(j) ⇔ j ≥ 1 and L[j] = j + 1                                   ⟨rule 3: only these can be note pages⟩
  R ← [0] followed by every j in 1..n-1 that is not eligible             ⟨the required slides, ascending⟩
  gaps ← the consecutive pairs (r, r') of R, then (the last of R, nil)   ⟨nil: the tail⟩
  for each gap (r, r') do
    C(r, r') ← the blocks [x, y] the table allows, plus "no block" when it is valid
  if some C(r, r') is empty               then return INVALID            ⟨no parse is valid⟩
  if every C(r, r') has one choice        then return PROVEN(the pages outside the chosen blocks), or NONE if none
  if only the first gap has several choices, L[0] ≤ 1, and their first note pages are distinct
                                          then return AMBIGUOUS(candidates ← those first note pages,
                                                                base ← the note pages every parse shares after the run)
  return UNDECIDABLE
```

#### 2.2 The note-page signature

Beamer's note-page templates (`beamerouterthemedefault.sty`, `beamerbaseauxtemplates.sty`) draw:

| Template | The slide, as a thumbnail | The date, rotated by 90° | The notes |
|---|---|---|---|
| `default` | at a quarter of its size, top right | yes, at the left edge | yes |
| `compressed` | at an eighth of its size | no | yes |
| `plain` | no | no | yes |
| `lined` | no | no | no (ruled lines to write on) |

A thumbnail is a **scaled copy** of the slide. Its text runs carry the slide's own strings, at $`\sigma`$ times their
height, at positions $`\sigma \cdot (x, y) + (t_x, t_y)`$, for one scale $`\sigma`$ and one offset $`(t_x, t_y)`$.
`note-page-signature?` compares a slide page $`s`$ with a candidate page $`j`$:

- **The thumbnail.** Every upright text run of $`s`$ is matched with the upright runs of $`j`$ that carry the same
  string. Each match gives a scale $`\sigma = h_j / h_s`$ (the ratio of the two runs' heights) and an offset
  $`(t_x, t_y) = (x_j, y_j) - \sigma \cdot (x_s, y_s)`$; only $`\sigma \le 0.4`$ counts. A **copy** is the set of matches in
  one cell of a grid over $`(\ln \sigma, t_x, t_y)`$ whose cells are $`\ln 1.02`$ wide in $`\ln \sigma`$ and
  $`0.005\,d`$ wide in each offset, $`d`$ being the note page's diagonal — or in one cell of the seven grids shifted
  from it by half a cell along any of the three axes. Every copy found this way agrees within 2 % in scale (its
  largest scale is less than 1.02 times its smallest) and within 0.5 % of the diagonal in each offset. Every set of
  matches that agrees within half a cell on each axis falls into one cell of some grid: scales within a ratio of
  $`\sqrt{1.02} \approx 1.00995`$, half a cell in $`\ln \sigma`$, and offsets within 0.25 % of the diagonal
  ([theory/12](../theory/12-speaker-notes-detection.md) §3.3–§3.4). Half a cell is the limit: a set that spreads more
  on some axis can be split by all eight grids. A slide run counts once per copy, however many
  runs of the note page repeat its string. The signature is true when some copy covers at least half of the
  characters of $`s`$'s upright text. The note page's runs are sorted by string and then height, so a slide run's
  matches are one range that binary searches find, and they are counted before any is built. The cells of the eight
  grids live in a hash table of the comparison's own, hashed by simple tabulation with tables drawn at random on every
  call ([experiment 14](../scientific/14-note-signature-cost.md) §4.4).
- **Bounds.** A page with more than 4 096 runs, or whose runs' strings hold more than 65 536 UTF-16 code units, is not
  compared, and neither is a pair with more than 16 384 matches (runs of equal strings with $`\sigma \le 0.4`$; other
  pairs do not count): the signature is `nil`, no evidence. No slide comes near the bounds: the most measured on a
  real page are 443 runs, 1 569 code units and 372 matches (experiment 14 §3.2). Together they bound one comparison.
  Every crafted case of experiment 14 takes a median of at most about 16 ms in the release build, where the
  comparison before it took up to 2.58 s (§8.9).
- **An empty slide.** When $`s`$ has no text, its thumbnail has none either, and the default template's date decides.
  The signature is true when $`j`$ has a run rotated by $`\pm 90°`$ that reads as a date `YYYY-MM-DD`, false when it
  has none, and `nil` (no evidence) when $`j`$ has no text at all.

*Why a scaled copy, and not "the slide's words, small".* A Beamer theme repeats the title, the author and the date in a
small footline on every slide. So the next slide always shows some of a slide's words at a small size, but at the
**same** size as on the slide ($`\sigma = 1`$). A thumbnail has $`\sigma = 0.25`$ or $`0.125`$, and the bound $`0.4`$
admits both templates and nothing near full size.

*Why half the characters.* A thumbnail copies the whole slide, so in practice it covers all of it. Half leaves room for
runs that pdf.js splits differently. A slide that merely quotes another one, small, would have to quote most of it.

On a real LuaLaTeX `show notes` deck, every thumbnail run has $`\sigma = 0.250`$, and the offsets agree to 0.01 pt. The
template's own frame title repeats the slide's title at $`\sigma = 0.69`$, and the bound excludes it.

#### 2.3 The probe

An ambiguous deck's candidates are named by their first note pages $`a`$, all inside the plain run. Under candidate
$`a`$, page $`a`$ is a note page of page $`a - 1`$, so the probe (`notes/probe-next`, which `evidence/settle` drives) tests
the signature of the pair $`(a - 1, a)`$. When the pages are what the rules and the templates describe, **at most one candidate passes**:

- for the true first note page $`a^\ast`$, page $`a^\ast`$ is a thumbnail of page $`a^\ast - 1`$;
- for $`a < a^\ast`$, pages $`a - 1`$ and $`a`$ are two different slides;
- for $`a > a^\ast`$, they are two note pages of the same slide, whose thumbnails coincide at $`\sigma = 1`$.

The probe walks the candidates from the last to the first:

- **The pair passes.** That candidate is the parse.
- **It fails, but page $`a`$ carries the default template's rotated date.** Page $`a`$ is then a note page of an
  earlier slide, and the walk goes on to the previous candidate.
- **Otherwise, the walk stops.** Page $`a`$ is a slide, and no earlier candidate allows that. The parse with no note page
  in the plain run stands when it is valid (`fallback-notes`: its note pages are the ones every parse shares after the
  run, so usually none, `:no-note-pages`), and the deck is shown as it is when it is not (`:probe-undecided`). A walk
  that passes every candidate's pair without a decision ends the same way.

Pages are read with page-level pdf.js calls, which work in the Node build too.

The walk starts at the end because a deck whose labels read $`1, 2, \dots, n`$ has every page as a candidate.
Every frame being one page is common, and when such a deck has notes, they are its last pages. A deck without notes
therefore costs two pages' text, one pair. With the `default` template, a deck with $`k \ge 1`$ note pages after its
last slide costs $`k + 1`$ pages: the walk tests $`k`$ pairs, and consecutive pairs share a page
([theory/12](../theory/12-speaker-notes-detection.md), Corollary 29).
A `compressed` deck has no date, so its walk stops at the first failing pair. Such a deck with several note pages
after its last slide is shown as it is.

#### 2.4 Decks without labels

A **single** XeLaTeX pass writes no labels. hyperref's xdvipdfmx driver (`hxetex.def`) writes `/PageLabels` from the
labels that the *previous* run left in the `.aux` file, and asks for a rerun when there are none. pdfLaTeX and LuaLaTeX
write them in the same run. So a deck compiled once by XeLaTeX, or by any tool that writes no labels, has neither the
labels the automaton reads nor the frames of ADR-0045.

Such a deck is **scanned** (`notes/scan-step`): every page's text is read once, in order, and page $`j`$ is a note page
when its signature matches the last slide page before it. Consecutive note pages all match that one slide. A scan that
finds none decides `:no-note-pages`; a deck of more than 2 000 pages is not scanned (`:too-many-pages`, §8.9).

The probe and the scan run **in the background after the first paint**, so the first paint never waits for page text.
A proven deck from pdfTeX or LuaTeX is decided from its labels before the first paint (§2.5 covers the other
engines). The result reaches the mounted view like a refresh
does: the document stays, the deck model is rebuilt, and a current page that turns out to be a note page snaps to its
slide (§3). Until then the deck shows every page, as today. That takes a few hundred milliseconds for a scanned
hundred-page deck, and the probe reads a handful of pages.

A deck whose note template draws neither a thumbnail nor the date (`plain`, `lined`) cannot be recognized without
labels, so it is shown as it is. `vv --cli --deck-info` waits for the probe, the scan and the confirmation (§2.5), and
prints the final decision.

#### 2.5 Labels that lag a pass

hyperref writes the labels in the run itself only for pdfTeX and LuaTeX (and the obsolete VTeX). Its drivers for
XeLaTeX, dvipdfm(x) and dvips (`hxetex.def`, `hdvipdfm.def`, `pdfmark.def`) write the labels that the previous run
recorded. So one pass over an **edited** deck carries the previous version's labels, and its log has no warning.
[Experiment 12](../scientific/12-slide-deck-detection-and-flip-latency.md) §5.1 measured it: after a frame was
inserted, a single XeLaTeX pass labelled the new deck with the old deck's labels. A watch loop that runs one XeLaTeX
pass per save, feeding a live-refreshing viewer, is always in that state.

Labels from another version can still obey the rules of §2.1. The automaton would then prove a parse whose note pages
are, in this version, slides, and the audience would not see them. So, unless the Producer names pdfTeX, LuaTeX or
LuaHBTeX (`trusts-labels?`), a parse from labels (proven, or chosen by the probe) is **confirmed** before its note pages
leave the audience's sequence — and so is a trusted producer's proven parse whose labels are `restartable?` (§2.1):

```
procedure CONFIRM(parse)
  for each note page j of parse, in order do
    s ← the last slide page of parse before j                       ⟨the slide that page j annotates⟩
    if SIG(s, j) ≠ true and not ROTATED-DATE(j) then return false   ⟨page j may be a slide⟩
  return true
```

Here `SIG` is the signature of §2.2, and `ROTATED-DATE(j)` holds when page $`j`$ has a run rotated by $`\pm 90°`$ that
reads as a date `YYYY-MM-DD` (also §2.2). The confirmation runs in the background after the first paint, like the
probe, and stops at the first page that fails. A confirmed parse is used, and a deck that fails is shown as it is.
The cost is at most two pages' text per note page, and a parse without note pages costs nothing. The page reader keeps
the last four pages it read, so each note page and each slide that has notes is read once when no slide has more than
four note pages; with five or more, a slide can be read again
([theory/12](../theory/12-speaker-notes-detection.md) §5). A proven parse whose distinct pages
(`confirm-page-count`: its note pages and the slides they annotate) number more than 2 000 is not confirmed: it is
refused before any page is read (`:too-many-pages`). Every other read counts against the budget of 2 000 page reads
for the whole decision (§8.9), so a confirmation that passed that check, or that follows the probe, can still end
`:too-many-pages` part-way (theory/12 §4.5). Either way the deck is shown as it is. A confirmation that fails decides
`:confirm-failed`.

*What is given up.* A deck from one of these drivers whose note template draws neither a thumbnail nor the date
(`plain`, `lined`) is never confirmed, so it is shown as it is, as it would be without labels (§2.4). Its labels may
well be right, but nothing on its pages can tell a stale parse from a fresh one. Showing a note page to the audience
costs less than hiding a slide from it.

*Why the probe's choice is confirmed too.* The probe tests one pair, $`(a - 1, a)`$. A stale parse can agree there and
still mark a later slide as a note page.

#### 2.6 What the first paint decides, and the reasons

`notes/initial` (through `plan`) decides at the first paint what the labels alone prove, and leaves the rest
`:pending` for the background (`evidence/settle`), which settles it once per document version. The deck is shown as it
is until then, and whenever a reason is given:

| The deck | At the first paint | In the background | Reason when no page is hidden |
|---|---|---|---|
| a second-screen deck (doubled pages, or pdfpc's notes position) | split notes (`:split`) | — | — |
| not a Beamer source | — | — | `:not-beamer` |
| a single page | — | — | `:one-page` |
| no labels | — | the scan (§2.4) | `:no-note-pages`, or `:too-many-pages` beyond 2 000 pages |
| a label that is not a non-negative integer | — | — | `:labels-not-integers` |
| `:proven`, a trusted producer, labels not `restartable?` | **the note pages, hidden at once** (proof `[:labels]`) | — | — |
| `:proven` otherwise | — | the confirmation (§2.5) | `:confirm-failed`, or `:too-many-pages` |
| `:ambiguous` | — | the probe (§2.3), then the confirmation when the producer is not trusted | `:no-note-pages`, `:probe-undecided`, `:confirm-failed` |
| `:none` · `:invalid` · `:undecidable` | — | — | `:no-note-pages` · `:invalid` · `:undecidable` |

A reason names what the evidence found, not what the PDF holds. `:no-note-pages` says that the parse without note
pages stood, or that the scan found none, so a deck whose note pages the evidence cannot see reports it too: a notes
handout whose every page is a note page (page 0 is always a slide, rule 1), a `compressed` note page of an empty slide,
a `compressed` deck whose probe stopped (§2.3), or a `plain` or `lined` deck without labels (§2.4)
([experiment 13](../scientific/13-speaker-note-detection.md) §9.2).

In the background, a page whose text cannot be read decides `:unreadable`, and anything unexpected `:error`; both show
the deck as it is. A document closed or replaced while its work runs gets nothing applied. The decision's proof is the
vector of the steps that found the note pages — `[:labels]`, `[:scan]`, `[:labels :confirm]`, `[:labels :probe]` or
`[:labels :probe :confirm]` — and `:doc/deck` and `--deck-info` carry it with the reason.

The whole decision in one picture: the first paint's table in its order, the work it leaves for the background, and
every reason a deck is shown as it is:

![Which pages of a Beamer deck are note pages: the first paint's decision, the background work it leaves, and the reasons](../diagrams/activity-note-page-decision.svg)

*Diagram source: [`../diagrams/activity-note-page-decision.puml`](../diagrams/activity-note-page-decision.puml).*

### 3. Navigating over the audience's pages (N3)

`deck/deck` takes an optional `:hidden` set of page indices (and the pdfpc `:skipped` set and forced overlays, §5) and
computes, besides what ADR-0045 §2 computes:

- `:shown`, the ascending pages the audience sees, and `:ordinal`, each page's position among them (nil when hidden);
- frames from the labels of the shown pages only, each stored as its first and last shown page (physical indices);
- `:frame-of` for every page, a hidden page taking its slide's frame.

With no `:hidden` it is the identity: every existing `deck_test` case passes unchanged. The steps of ADR-0045 §2 run over
shown pages — `:next`/`:prev` move one ordinal, `:first`/`:last` go to the first and last shown pages — and:

- **`snap`** maps a hidden page to its slide, the nearest shown page *below* it (page 0 is never a note page, so there
  always is one). A snap only ever moves down, so two windows that disagree about which pages are hidden — one
  mid-refresh, or one whose background scan (§2.4) has finished — cannot snap each other back and forth (§8.4;
  [theory/11 §5.6](../theory/11-linked-slide-show-protocol.md));
- **`[:show p]`**, a new op, clamps and snaps `p` and records **no** history — it is how a window follows another;
  `[:goto p]` and `[:jump p]` still record history;
- **`resolve-goto`** matches the labels of shown pages only and falls back to the 1-based ordinal, so in a `show notes`
  deck typed `2` goes to frame 2.

Each view navigates its own deck (`renderer.pdf` `deck-for`: the document's evidence plus its notes model under the
view's `:slides-notes` preference), and the engine reports it as `:doc/deck`. In the Slides layout the engine marks the
hidden pages' placeholders `.vv-pdf-note-page`. CSS hides their text and link layers, so find — which skips
`display: none` — never takes the audience to a note page and Tab never reaches its links, and the engine never
rasterizes them (`unseen?`). Every page the engine is asked to show — by a flip, a jump, a refresh or the start of a
show — is snapped first, and a scroll that ends on a note page's slot is snapped at its end, so no frame shows a
note page's slot. The slide a flip shows next is not always in the adjacent slot, since a note page may lie between,
so the engine keeps a **keep-set** rendered: the current slide and the slides `:next` and `:prev` would show
(`deck/step`). `release-far!` releases every rendered page outside the keep-set and the overscanned band, so the live
canvases stay bounded. Without hidden or skipped pages the keep-set is exactly the band ADR-0045 §3 keeps. The
Document layout shows every page, notes included. `:slides-notes :off` also disables the pairing: every page is shown
as it is (the escape hatch if detection is wrong), and pdfpc's notes position no longer splits a page (§5).

### 4. Text notes (N4)

**Annotations.** For a page, the text notes are the `/Contents` of its annotations of the five types pdfpc reads, joined
with a newline in `/Annots` order (pdfpc's rule); a `\pdfpcnote` without an overlay specification is repeated on every
overlay page by `pdfpc.sty` itself, so per-page reading is per-overlay reading. pdfpc also stops *drawing* those
annotations; vinary-viewer draws a page as the PDF says, because it is a general viewer and a visible highlight on a
reviewed deck is content. `\pdfpcnote`'s annotations are hidden by their own flags, so a pdfpc deck looks the same.

**How each engine writes a note.** The string a reader gets depends on the engine that compiled the deck. All three
engines compiled the same `\pdfpcnote` (in `test/fixtures/beamer-pdfpc.tex`; its header has the commands and results):

| Engine (its Producer) | `/Contents` as written | `café` reads as | `\\` in the note becomes |
|---|---|---|---|
| LuaTeX (`LuaTeX-…`) | UTF-16BE hex with a byte-order mark | `café` | the two characters `\\` (`pdfpc.sty` converts the note to hex before it redefines `\\`) |
| XeTeX (`xdvipdfmx (…)`) | UTF-16BE hex: xdvipdfmx re-encodes a non-ASCII string itself | `café` | a newline |
| pdfTeX (`pdfTeX-…`) | the note's UTF-8 bytes, in a literal string | `cafÃ©` | a newline |

A text string without a byte-order mark is PDFDocEncoding (PDF 32000-1 §7.9.2.2), one byte per character. So every
reader, pdf.js and Poppler (and so pdfpc) alike, decodes pdfTeX's UTF-8 a byte at a time: `é`, the bytes `C3 A9`,
becomes `Ã©`. vinary-viewer repairs that case, and only that case:

```
procedure REPAIR(s, producer)                        ⟨s: an annotation's /Contents as pdf.js decoded it⟩
  if producer does not name pdfTeX then return s     ⟨no other engine writes UTF-8 into a literal string⟩
  b ← PDFDOC⁻¹(s)                                    ⟨the bytes that pdf.js's PDFDocEncoding decoded to s⟩
  if b is undefined then return s                    ⟨s holds a character that no byte decodes to⟩
  if no byte of b is ≥ 0x80 then return s            ⟨plain ASCII: nothing to repair⟩
  if b is not valid UTF-8 then return s              ⟨Latin-1 text, decoded right the first time⟩
  return UTF-8-DECODE(b)
```

`PDFDOC⁻¹` inverts pdf.js's decoding of a literal string, `stringToPDFString` in pdf.js 5.4.149:

- 0x18–0x1A, 0x1C–0x1F and 0x80–0x9E map through its table to the PDFDocEncoding characters (`˘`, `•`, `–`, `ł`,
  …), and 0xA0 maps to `€`;
- 0x1B starts a language escape, which is removed;
- every other byte is the Latin-1 character of the same number.

No two bytes other than 0x1B decode to the same character, so the inverse is defined exactly when every character of
$`s`$ is one that some byte decodes to, and it is then unique. It gives back the bytes pdf.js read only when pdf.js
decoded them a byte at a time, with no escape to remove: a string without the byte 0x1B that either has no byte-order
mark, or has one but fails pdf.js's `TextDecoder` and is not UTF-16 of odd length (which loses its last byte)
([theory/12](../theory/12-speaker-notes-detection.md) §6.1–§6.3, Theorems 34 and 35). For an escape, or for a string
pdf.js decoded by its byte-order mark, the inverse, when it exists, is not the bytes the string came from. pdf.js
gives the viewer only the decoded string, never the bytes, so this inverse is the only way back to them. The cases it
cannot tell apart are given under the trade-offs.

LuaTeX's `\\` is left as it is: pdfpc receives the same two characters, and both GFM and Discount render them as one
backslash.

**The sidecar.** pdfpc keys its sidecar notes by 0-based page index, with the page's label and its overlay number
(its position in the run of equal labels) to relocate a note when pages were inserted; vinary-viewer applies the same
relocation rule. Legacy files key notes by 1-based user slide and copy them to every page of it.

**Both.** pdfpc shows only the annotation note when a page has both; vinary-viewer shows the annotation note, then the
sidecar note when it differs, because a viewer should not drop a note its author wrote. pdfpc never copies annotation
notes into the sidecar, so the two do not duplicate each other.

**Rendering.** pdfpc renders notes as Markdown (Discount, raw HTML passed through to a WebKit view with JavaScript off)
unless `DisableMarkdown` is set. vinary-viewer renders text notes as Markdown through the app's own pipeline — GFM and
`rehype-sanitize`, the same trust boundary as a Markdown document ([secure raw HTML](../security/threat-model.md)) —
when the deck carries pdfpc metadata (XMP or a sidecar) and Markdown is not disabled, and as plain text
(`white-space: pre-wrap`, the annotation's `dir`) otherwise: a sticky note from a PDF reader is not Markdown. Discount
extensions without a GFM equivalent (`%` title blocks, `>%class%` divs, SmartyPants) render as text.

### 5. pdfpc's settings (N5)

pdfpc parses its XMP keys without namespaces: every element whose name starts with `pdfpc:` counts, by its local
name, and the last one wins. `pdfpc.sty` nests its block as a second `rdf:Description`, which is not valid RDF/XML, so
an RDF-aware parser may drop it. vinary-viewer therefore reads the **raw** XMP (`metadata.getRaw()`) the way pdfpc
does. Values are parsed as pdfpc parses them (`atoi` for numbers, exactly `"true"` for booleans).

| Setting | XMP key | Sidecar key | Used for |
|---|---|---|---|
| notes position | `NotesPosition` (left/right/top/bottom, else none) | `beamerNotePosition` (case-insensitive) | which half holds split notes: an explicit `:slides-notes` preference first, then this, then the geometric default. A position on a deck without a doubled page is honoured as pdfpc honours it: the page is split along that axis. |
| duration | `Duration` (minutes) | `duration` | the countdown (deferred, §6.5) |
| start / end time | `StartTime` / `EndTime` (`HH:MM`, local) | `startTime` / `endTime` | the pre-talk countdown and the duration (deferred, §6.5) |
| last minutes | `LastMinutes` (default 5) | `lastMinutes` | the warning threshold (deferred, §6.5) |
| end slide | `EndUserSlide` (1-based frame) | `endSlide` (0-based frame) | the counter's total and where End goes first (§6.5); the pace (deferred, §6.5) |
| Markdown off | `DisableMarkdown` (`"true"`) | `disableMarkdown` | plain-text notes |
| hidden pages | — | `pages[].hidden` | `:skipped`: next and previous step over them; Home, End, typed numbers and the overview still reach them (pdfpc's rule) |
| forced overlays | — | `pages[].forcedOverlay`; legacy `[skip]` | the page joins the previous frame (frames for decks without labels) |

**One pass.** A deck compiled once with `pdfpc.sty` already carries its XMP keys, but LaTeX adds a last page,
"Temporary page!", because hyperxmp learns the page count from the `.aux`. That page carries the last frame's label,
so it joins the last frame as an overlay. The second pass removes it. Both facts were measured
(`test/fixtures/beamer-pdfpc.tex`). vinary-viewer shows the deck as it was compiled.

**Precedence.** XMP, then the sidecar, then the defaults — pdfpc's order without its command line. The
`:slides-notes` preference overrides the notes position: a side splits there, and `:off` splits nothing — whole pages
(ADR-0045 §9's geometric second screen included). **Not used:**
`DefaultTransition` (transitions are the viewer's preference, ADR-0045 §10), `noteFontSize` (pdfpc writes it on every
save, so it is not an author's choice; window zoom scales the pane), `savedSlide` (pdfpc's bookmark).

**The sidecar's format** is decided as pdfpc decides it: JSON first; a JSON root that is not an object, or a format
above 2, is not a sidecar this version understands (it is ignored and named in `--deck-info`); anything that does not
parse as JSON is read as the legacy format, which has `[section]` headers and `### <n>` notes. vinary-viewer never
writes the sidecar.

### 6. The presenter view, in one window (P1–P5)

#### 6.1 What the presenter sees

`s` (Slides: *Speaker notes (presenter view)*) toggles the window between the audience view and the presenter view
whenever the `:slides` layer is active — windowed in the Slides layout, or while presenting. The presenter view puts the
current slide on the left (58 % of the width) and a pane on the right holding:

- a **status strip**: `Slide 7 / 12 · overlay 2 / 3` (the timer, the wall clock and the pace are deferred, §6.5);
- the **notes**: the notes regions of the current page (its split half, or its note page), then its text notes;
- the **next slide**: what the next step will show (the next shown page), or *End of the slide show*.

A **single-window show starts in the audience view**, because its screen may be mirrored to a projector; the previous
view is remembered in `[:ui :presentation :notes-view-before]` and restored when the show ends. A deck without notes
still gets the counter and the next slide, with *This deck has no speaker notes* in the notes box, or *No notes on this
slide* when other pages have them.

**The hint (P5).** The notes a single-window show hides were the request's first complaint, so the first show that
hides them says so. When a show starts in the audience view of a single window, and the deck has notes — split
halves, note pages, a sidecar with notes, pdfpc metadata, or a text note on any page rendered so far (the link layer
already reads every rendered page's annotations) — a chip at the bottom reads *Speaker notes: **s** shows them here ·
**Shift+S** presents in a new window*. It fades after 6 s or at the next key or click, and is `role="status"`
(polite). Then `:slides-notes-hinted? true` is saved in settings.edn, which every window receives (ADR-0047), so it is
shown once per installation. It is never shown in an audience window, nor when the show starts in the presenter view.
Notes found after the show started — a background scan that finishes, a text note on a later page — show it then, if
it has not been shown. The chip floats where the *Go to 12* chip does (`vinary.ui.slides-hud`), and a typed digit
dismisses it first. A mirrored projector shows it too, for those six seconds, once.

#### 6.2 DOM and CSS (P2)

`.vv-content-wrap` gets the class `.vv-presenter-view`, and a pane, `[slides-presenter/pane]`, sits in a fixed child
slot after the content view, so the engine is never remounted (remounting would end the show — ADR-0045 §5). CSS:
`.vv-presenter-view > .vv-content { margin-right: 42% }` and `.vv-presenter-pane { position: absolute; top: 0; right: 0;
bottom: 0; width: 42%; z-index: 5 }`. The pane is absolutely positioned because `.vv-content-wrap` is a flex column whose
other children must not reflow. z-index 5 sits below the transition snapshot (6; no flip makes one while the pane is
shown, §6.3), the blank screen (7), the overview
(8), the chip (9) and find (10), so a single-window blank also covers the notes — right for a mirrored screen — and the
overview covers the window. The slides' existing ResizeObserver refits the slide at once. **No new `--vv-*` tokens:**
while presenting the pane uses the show's palette (`#000`, `#111`, `#ddd`); windowed, the theme's `--vv-bg2`, `--vv-fg`,
`--vv-border` and `--vv-meta`.

#### 6.3 Rendering the pane (P3)

`vinary.renderer.pdf-region` renders one rectangle of one page of a borrowed document into a box — `attach!`, `show!`,
`rebuild!`, `detach!` — generalizing the overview's thumbnail renderer: an offset viewport so the rectangle's corner is
the canvas origin, `layout/budget-dpr` under `layout/max-canvas-pixels`, a detached canvas swapped in only when complete
(no flash), generation fences, and `RenderTask.cancel()` when the page changes. The pane borrows the engine's document
through `pdf-cache/slides-snapshot` as the overview does, re-borrows on `:slides/doc-gen` (a live refresh), and renders
the notes region and the next slide's audience rectangle into canvases of its own — the engine rasterizes only the slide
half, so there is nothing to crop. A pdf.js text layer under the notes region keeps them selectable and readable by a
screen reader; runs outside the region are hidden (`hide-runs-outside!`, generalizing `hide-offslide-runs!`). Each box
re-renders on resize and on a change of the device-pixel ratio, so a 2× laptop beside a 1× projector is sharp on both.

**Transitions.** While the pane is shown, a flip is **instant**: the engine does not run the `:slides-transition`
animation, because the outgoing slide's snapshot is appended to `.vv-content-wrap`, and `:push` would slide it by the
scroller's width — across the pane. *Deferred (§6.5's feature request):* wrap the snapshot in a
`.vv-slides-transition-clip` placed at `.vv-content`'s rectangle with `overflow: hidden` (in the other layouts the clip
is the same size as before), so the presenter view animates like the audience view.

**Find** is rooted at `.vv-content`, so it searches the slides, not the pane (documented).

#### 6.4 Keys, Escape, focus, accessibility

The presenter view is a layout, not a modal: Escape keeps its ADR-0045 chain and still ends the show. The pane is not
focusable; a click on it leaves the focus on the document, so the slide keys keep working. The pane is an
`<aside role="complementary" aria-label="Speaker notes and next slide">`; canvases are `aria-hidden` (the text layer
carries the text); the next slide is `role="img"` with `aria-label="Next: slide 8 of 12"`; the counter is plain text.
*Deferred with the timer (§6.5):* the timer is `role="timer" aria-live="off"`, with a visually hidden polite live region
that speaks only when a threshold is crossed.

#### 6.5 The counter (P4), and the deferred timer

The **counter** counts frames: `Slide u+1 / e+1`, where $`e`$ is pdfpc's end slide (else the last frame), and reads like
`23 / 20` past it (`vinary.slides.presenter/counter`, with `overlay-counter` for `overlay o / k`). **End** goes first to
the last page of the end slide, and from there (or beyond) to the last page — so a deck's backup slides after
`EndUserSlide` stay one more End away, as in pdfpc (`vinary.slides.deck/step :last`).

**Deferred: the timer, the pace and the wall clock.** At the user's request (2026-10-17) they are not part of this
decision; the feature request `vinary-viewer-presenter-timer-pace-and-wall-clock-slide-transitions-in-the-presenter-view-and-across-linked-windows-64e248` carries them, with the transitions of §6.3. The pure model already exists —
`vinary.slides.presenter` (`clock`, `pace`, `schedule`, `talk-start`, …), tested but not yet shown by any view — and its
design, kept here for that request, is:

`vinary.slides.presenter/clock` is pure: given `now`, the show's start, the deck's pdfpc settings and the position, it
returns the strings and the phase the strip shows. It follows pdfpc's `timer.vala`:

- With a start **and** an end time, the duration is $`D = t_{\mathrm{end}} - t_{\mathrm{start}}`$ (plus a day when the end
  is not after the start), and a duration setting is ignored. With an end time and a duration, the scheduled start is
  $`t_{\mathrm{end}} - D`$. An end time alone does nothing (pdfpc's rule). A time earlier than the current minute means
  tomorrow.
- **Pre-talk:** with a scheduled start in the future, the timer counts down to it (`-HH:MM:SS`). The talk starts at the
  scheduled start, or when the presenter first leaves the slide the show started on, whichever is first.
- **Talk:** with a duration it counts down, $`D - t`$ for elapsed time $`t`$, and negative once over time; without one it
  counts up.
- **Phases** (colour plus words — never colour alone): pre-talk; *last minutes* when $`0 < D - t < 60 \cdot m`$ for
  `LastMinutes` $`m`$ (default 5); *over time* when $`t \ge D`$. The **pace**, shown as words (*2 min behind*, *1 min
  ahead*), is pdfpc's: with frame $`u`$ and end frame $`e`$ (both 0-based), the expected elapsed time is

```math
t_{\text{expected}} = D \cdot \frac{u + \tfrac{1}{2}}{e + 1},
```

  and the presenter is behind when $`t > t_{\text{expected}} + 60\,\mathrm{s}`$, ahead when
  $`t < t_{\text{expected}} - 60\,\mathrm{s}`$.

- **Restart the presenter timer** (palette, `:when :presenting?`) restarts the talk now.
- The timer is per window and is not synchronized: only a presenter view shows it.

### 7. `F` — this window's fullscreen (F1)

`F` (Shift+f; *Fullscreen (this window)*, `:when :presenting?`) toggles the window's fullscreen through the existing
`vv:set-fullscreen`, so `vinary.main.fullscreen/decide` still owns it: main leaves only a fullscreen it entered. It is
needed on Wayland (§8.7), with one display, and when the `:slides-fullscreen?` preference is off.

### 8. Two linked windows (L1–L9)

#### 8.1 What the presenter does

**Present in New Window** (`:slides/present-linked`) is offered in three places: the View menu, after *Start from
Current Slide* (access key `p`, shown with `Shift+S`, `:pdf-only`); the palette (category Slides, `:when :pdf-view?`);
and the key `S` (Shift+s). `S` is bound in the `:all` layer of every preset, next to F5, and not in the `:slides`
layer, so the key works wherever the menu item does: in the document layout too, and with the focus on the sidebar,
as F5 does. Both the menu item and the palette row carry the tooltip *Speaker notes stay in this window*. That needs
one new optional field, `:tooltip`, on menu items and on commands, rendered as the row's `title`.

The command is offered for every PDF view, not only for decks with notes. The counter and the next slide help without
notes. And a deck without labels learns its notes only after its first paint (§2.4), so a command that appeared
then would be a surprise. Chosen in window A, it starts the show from the current slide if none is running — **without**
asking for fullscreen yet — switches A to the presenter view, and asks main for a linked show. Main opens window B, the
audience window, from the warm pool when one is ready: B appears without taking the focus (`showInactive`), on another
display when there is one, and goes fullscreen there when the platform lets main place it. From then on either window
drives the show — keys, clicks, swipes, overview picks, typed numbers, links, find, blanking — and the other follows.
A window that follows a move shows it instantly; animating both windows alike is deferred with the timer (§6.5). `S` again while linked re-sends the state and raises B; nothing new opens. A window takes part
in at most one linked show; joining an existing window is not offered, because the audience window's whole lifecycle
(placement, fullscreen, closing) is main's, and an existing window's is the user's.

#### 8.2 Who owns what

Main owns the session: its members and their roles, the `seq`, and the authoritative position. Each window owns how it
renders: its layout, view, transitions, notes model, zoom, overview, typed digits, find, history and fullscreen.
A position is a **physical page index** of the same bytes — main has no deck model (pdf.js runs only in renderers) —
and a move is a whole position, never a step, so "the last move wins" is well defined.

| Synchronized through main | Per window |
|---|---|
| the page shown | the layout and the presenter/audience view, zoom, device-pixel ratio |
| the blank colour | the overview, typed digits and their chip, find |
| the end of the show | the jump history (a follower uses `[:show p]`), fullscreen, tabs, focus |
| | transitions: a window animates only the flips it makes itself; a move it follows is shown at once (§8.1) |

In the presenter view of a linked show, blanking shows a badge (*Audience: black*) instead of blacking the presenter's
screen.

#### 8.3 Channels

They follow the bridge-effect pattern of `app/fx.cljs` and the `on*` pattern of the preload, which gains
`presenterOpen`, `presenterMove`, `presenterReady`, `presenterLeave`, `presenterSwap` and `onPresenterState` (none in
`web-preload.js`).

| Channel | Direction | Payload |
|---|---|---|
| `vv:presenter-open` | renderer → main | `{path, page, blank, fullscreen}` |
| `vv:presenter-move` | renderer → main | `{session, mid, page, blank}` |
| `vv:presenter-ready` | renderer → main | `{session}` — the audience's deck is showing; moves nothing |
| `vv:presenter-leave` | renderer → main | `{session, reason: "stopped" \| "unmounted"}` |
| `vv:presenter-swap` | renderer → main | `{session}` — Swap screens (§8.7) |
| `vv:presenter-state` | main → renderer | one of: **joined** `{session, seq, role: "presenter", page, blank, joined: true, display: "separate" \| "same" \| "manual"}`; **push** `{session, seq, page, blank}`; **ack** `{session, seq, ack: mid, accepted}`; **peer** `{session, peer: "live"}`; **swapped** `{session, swapped: true, lost: [role…]}` (the roles whose window could not go fullscreen again) or `{session, swapped: false, why: "same-display" \| "busy"}`; **ended** `{session, ended: true, stop, reason}`; **refused** `{session: null, refused: "busy" \| "not-focused" \| "not-retained" \| "invalid"}` |
| `vv:open-files` (existing) | main → renderer | gains `link: {session, seq, role: "audience", page, blank, fullscreen}` — ADR-0046's "fields, not channels" |
| `vv:fullscreen-changed` (existing) | main → renderer | gains `swap: true` on the one report that ends a swap (§8.7) |

The session id (`"show-<n>"`) is minted by main, which identifies a sender by `event.sender`; ids need not be secret.

#### 8.4 The protocol (L2–L4)

**Main** (`vinary.main.slides-link`, pure; executed by the shell `vinary.main.presenter`) keeps per session
`{seq, page, blank, members {wc → role}}` and per member the highest `mid` it accepted. A move from a member whose
`mid` is higher than that is **accepted**: `seq := seq + 1`, the position is stored, an ack `{seq, ack: mid,
accepted: true}` goes to the sender and a push `{seq, page, blank}` to every other member. A move with an older `mid`
— impossible over Electron's ordered IPC, possible over a reordering transport — is acknowledged
`{ack: mid, accepted: false}` and changes nothing. A move from a non-member, or with an invalid payload (`page` not an
integer in $`[0, 10^6)`$, `blank` not one of `nil`/`"black"`/`"white"`, `mid` not a positive integer), is dropped.

**A window** (`vinary.app.slides-link`, pure, at `[:ui :slides-link]`) keeps `known` (the position it last exchanged
with main), `seq` (the newest seq it has seen), `own-seq` (the newest seq of its own accepted moves), `out` (the mids it
has sent that main has not acknowledged), `held` (a push that arrived while `out` was not empty) and `applying` (the
page of a push being shown, until the engine reports it):

```
on a local change (the engine's report, or a blank key):
  cur ← (applying, if a push is being shown, else the engine's reported page;  the blank colour)
  if cur ≠ known then  mid ← next mid;  out ← out ∪ {mid};  known ← cur;  send move(mid, cur)

on push(s, pos):
  if s ≤ seq then drop                                   ⟨ADR-0046's guard⟩
  seq ← s
  if joining then start ← pos                           ⟨the deck is not showing yet⟩
  else if out = ∅ then show(pos)
  else held ← (s, pos)                                   ⟨my move is on the way: hold, do not flicker⟩

on ack(s, mid, accepted):
  out ← out \ {mid}
  if accepted then own-seq ← max(own-seq, s);  seq ← max(seq, s)
  if out = ∅ then
    if held ≠ ∅ and held.s > own-seq then show(held.pos)   ⟨else my move, ordered after it, is main's state⟩
    held ← ∅

show(pos):  known ← pos;  blank ← pos.blank;  applying ← pos.page;  run the EFFECT [:slides/command [:show page]]
```

`show` runs the `:slides/command` **effect**, not the event: the event would un-blank (ADR-0045 §5). The engine
reports after every `[:show p]` — even when the page did not change — so a page the engine snapped is sent back to main
as a correction, and a follower never echoes the move it was given (its report equals `known`).

**Why this converges.** Main orders every move. When a push reaches a window whose own move is still on the way, main
either ordered the push before that move — and then showing it would take the window back to a slide main has already
superseded, with nothing to bring it forward again — or main has already numbered the move, and its ack tells the window
where the push stands. So a window holds the push and, once its own moves are acknowledged, shows it only if it is
newer than its own last accepted move (`held.s > own-seq`). The stale guard and the mid guard keep a late message from
overwriting a newer one when messages arrive out of order. [theory/11](../theory/11-linked-slide-show-protocol.md)
proves, for **any delivery order** — Electron's ordering of `send` messages is not assumed — that the windows converge
on main's last move, that following never sends anything, that no person's move is undone, and that the exchange
settles; it model-checks the same with TLC, with FIFO and reordering channels and with two windows that disagree for a
while about which pages are hidden; and seven negative controls, each removing one rule, each break the property that
rule protects. Three rules came out of that work and are part of this decision: the interceptor uses `applying`
(without it, a blank key pressed between a push and its report sends the stale page back — an echo), the audience
announces itself with `ready` instead of a move (a move would undo a step the presenter took while it was loading), and
a snap only moves down (two windows that snap in opposite directions would correct each other for ever —
theory/11 §5.6).

#### 8.5 Outgoing and incoming (L3)

**Outgoing** goes through one global interceptor, `slides-link-out`. After every event except `:slides/link-state`
(an incoming message) that is either a report (`:slides/changed`) or a change of the blank colour, it calls the pure
`slides-link/outgoing`, which sends when the link is live, the mirror says the engine is presenting (`:presenting?`, a
field the report gains: true only once the engine itself applied the show's start, so a window's pre-show reports never
go out), the layout is `:slides`, the mirror's path is the link's, and the position differs from `known`. This one
funnel catches every page change and every blank change, including any a future event adds. The engine's report is
processed **at once** (`dispatch-sync`, from the animation frame that read the position — a frame callback runs
outside any event, so the dispatch is never nested): a report read before a push but processed after it would end
that push's showing (`applying`) and send the old page back, an echo. Rejected: an interceptor
per event (a new event that blanks would silently desync) and a Reagent `track!` (side effects in reactions).

**Incoming** is `:slides/link-state`: a push or a released held state runs `show`; a joining window updates `:start`.

#### 8.6 Joining, leaving, and the rest of the lifecycle (L4, L5)

B receives `vv:open-files` with `link`: `:files/opened` joins the show (phase `:joining`, `slides-link/link-join`)
in the same app-db update that opens the deck in a tab. A join dispatched as an event of its own would be queued
behind a push main sent right after the open, and the push would find no link. When B's deck mounts (`:pdf/deck-detected` for that path, a deck or not — F5 presents any
PDF), B starts the show at `:start` in the audience view, with `known = start`, asks for its own fullscreen when the
link says so, and sends `vv:presenter-ready`; main tells A `{peer: "live"}`, and A's pane drops its *Waiting for the
audience window…* notice (after 10 s it adds a hint to look at that window). Pushes that arrive while B is joining only
move `:start`.

| Event | Detected by | Effect |
|---|---|---|
| Escape or *End Slide Show* in either window | `slides-stop-fx` → leave `"stopped"` | the other window gets `ended {stop: true}` and stops, leaving any fullscreen it asked for; main closes the audience window |
| the presenter's view unmounts (tab switch or close, navigation, Reflow) | `slides-unmounted-fx` → leave `"unmounted"` | the same as stopped |
| the audience's view unmounts | the same | the presenter gets `ended {stop: false, reason: "audience-left"}` and keeps presenting, with a notice; main closes the audience window, which existed only for the show |
| a member window closes, reloads or crashes | `closed`, `did-start-navigation` (main frame), `render-process-gone` | as an unmount of that member's view; *Shift+S* links again |
| the audience window's display is removed | `screen` `display-removed` | the session ends (`"display-removed"`) and the audience window closes, so the OS never moves a fullscreen projector window over the presenter |
| a display is added, or its metrics change | each window's DPR watcher | nothing in main; link again to use a new display |
| a live refresh | ADR-0046 fan-out — one read, the same `seq` to both windows | each window replaces its document in place and keeps its page; a clamped page is reported, differs from `known`, and the windows converge |
| the warm pool | — | standby windows never receive `vv:presenter-state` (members only) |
| no window could be claimed, or a cold audience window closed or crashed before it loaded | the claim throws, or the window's `closed` / `render-process-gone` while it is still *unclaimed* (§8.8) | the presenter gets `ended {stop: false, reason: "no-window"}`; a crashed window still open is closed |

The presenter's pane carries one line about the link (`slides-link/notice`; the audience window shows none — its
screen is the projector's). The first that applies is shown:

| When | The line |
|---|---|
| the request is on its way | *Opening the audience window…* |
| the audience's deck is not showing yet | *Waiting for the audience window…* — after 10 s, *… It may be behind another window, or on another screen.* |
| Swap screens moved nothing | *The screens are already being swapped.* (`busy`) or *Both windows are on one display.* (`same-display`) |
| a window did not get its fullscreen back after a swap | §8.7 |
| `display: "manual"` | *Move the audience window to the other screen, then press Shift+F in it.* |
| `display: "same"` | *One display: share the audience window in a call.* |
| the link ended and this window keeps presenting | `audience-left`: *The audience window closed. Shift+S opens it again.* · `display-removed`: *The audience window's display was removed. Shift+S opens it again.* · `no-window`: *No audience window could be opened.* |
| main refused the request | `busy`: *This window is already in a linked show.* · `not-focused`: *No audience window opened: this window was not focused. Press Shift+S again.* · `not-retained`: *No audience window opened: this window no longer holds the deck.* · `invalid`: *No audience window opened: the request was not valid.* |

#### 8.7 Displays, placement, fullscreen, and swapping screens (L1, L9)

Pure `vinary.main.displays`: `display-of` (the display with the largest intersection), `audience-display` (prefer a
display that is not the presenter's, then an external one, then the largest, then the lowest id; nil with one display),
`placement` (a rectangle centred in the target's work area) and `placement-supported?` (false on a native Wayland
session — `startup/wayland-session?` with `VV_OZONE` not `x11` — where a client cannot place its windows). The shell
un-maximizes the claimed window (a pool window may have been maximized by `window/remember!`), sets its bounds on the
target display, and shows it inactive. The link's `fullscreen` is the presenter's `:slides-fullscreen?` preference
**and** a separate display **and** placement supported; B then asks for its own fullscreen with the existing
`vv:set-fullscreen`, owned by `decide`. A asks for its own fullscreen when the joined message says `display:
"separate"`, so a presenter never covers the only display: with one display, B is the window to share in a call, and
A's pane says so (*One display: share the audience window in a call.*). On
Wayland, B opens where the compositor puts it (`display: "manual"`), and A's pane says: *Move the audience window to
the other screen, then press Shift+F in it.* After B goes fullscreen, main re-focuses A (best effort — window managers
differ; correctness never depends on the focus, since either window drives).

**Swap screens (L9).** `audience-display` guesses which display is the projector, and the guess can be wrong — a laptop
lid closed onto an external monitor, or three displays. When the joined message said `display: "separate"`, the
presenter view's status strip shows **Swap screens** (also a palette command, *Swap the presenter and audience
screens*, `:when` a linked show is live on separate displays). It sends `vv:presenter-swap`. The pure
`displays/swap-plan` takes both windows' bounds, fullscreen flags and the displays, and returns, for each window, the
`placement` on the other window's display and whether to restore fullscreen there — or nil when both are on one
display (the user moved one by hand), and then main answers `swapped: false, why: "same-display"`, which the pane
shows as *Both windows are on one display*.

A fullscreen window cannot be moved, so a swap must leave fullscreen and enter it again. Two existing rules stand in
the way, and the swap keeps both. `fullscreen/wire!` treats every `leave-full-screen` as the user's: it clears main's
ownership, and it reports the change, which ends the show in that window (`fullscreen-changed-fx`, ADR-0045). So the
shell runs the plan in four steps, with each member marked *swapping* (a flag on the `BrowserWindow`, which `wire!`'s
two handlers check first):

1. record each member's fullscreen state and ownership; each fullscreen member leaves fullscreen, and main waits for
   its `leave-full-screen` (at most 2 s; leaving is asynchronous on Linux, animated on macOS). For a swapping window,
   `wire!` neither reports nor clears the ownership;
2. place each window on its new display (`setBounds`, un-maximized first, as when B was claimed);
3. each window that was fullscreen enters it again, and main waits for its `enter-full-screen` (at most 2 s);
4. unmark the windows, give each its recorded ownership back, and report each window's real state once, as
   `vv:fullscreen-changed {fullscreen, owned, swap: true}`.

The swap is the one place where main leaves a fullscreen it did not enter, and it restores it within the same swap.
Afterwards each window has the fullscreen it had and the same owner, so main still leaves only a fullscreen it
entered: the user's own fullscreen survives the end of the show. The renderer never sees the transient leave. If a
window could not enter fullscreen again (a window manager refused it), the final report says `fullscreen: false`.
With `swap: true`, `fullscreen-changed-fx` then keeps the show running windowed, and sets the presentation's
`:fullscreen?` to false, instead of ending it. The presenter's pane says *The audience window could not go fullscreen
again; press Shift+F in it.*, *This window could not go fullscreen again; press Shift+F.* when it is the presenter's,
or *Neither window could go fullscreen again; press Shift+F in each.*

Main then re-focuses the window the swap came from (best effort) and answers `swapped: true`, with `lost` naming any
window that did not get its fullscreen back. Nothing in the session changes: roles, positions and the `seq` are
untouched, so the protocol of §8.4 never sees a swap. A swap requested while another runs is refused (`why: "busy"`). A
member that closes, or a display that is removed, during a swap ends the session as it would at any other time; the
windows that remain are unmarked, and their state is reported.

#### 8.8 window.edn, the warm pool, remote decks, SSH prompts (L6, L7)

- **window.edn** is debounced per window (the timer lives in `remember!`'s closure — this also fixes two ordinary
  windows cancelling each other's save), and an audience window — `win.vvEphemeral`, read when saving, because pool
  windows are wired before anyone knows their role — is never saved. The fullscreen rule (`persistable-bounds`) is
  unchanged.
- **The warm pool.** `open-window!` gains the source `:presenter` and stays the only caller of `claim-window!`, which
  takes `{:on-claim :show :ephemeral? :bounds :refill-delay-ms}`. `:on-claim` is called with the window before
  anything is sent to it: the shell makes the window the session's audience member (`slides-link/claimed`) and
  returns the `link` its `vv:open-files` carries — or nil when the show ended meanwhile, and the window is closed.
  `:show :inactive` shows it without the focus, `:bounds` places it (un-maximized first), and `:refill-delay-ms`
  (5 s) defers the pool's refill, so a new window does not boot during the first transitions. The cold path wires
  the window hidden and calls `:on-claim` only once it has loaded — so no message of the show reaches it before its
  renderer listens, and none precedes its `vv:open-files` — then places it and shows it inactive. Until then the
  window is *unclaimed*: if it closes or crashes first, the claim fails (`no-window`). A pool window is current
  because it was a standby window (ADR-0047).
- **Remote decks.** B's open of an `ssh://` path A already retains costs one more pipelined read (ADR-0046 measured
  32 MiB at 50 ms RTT in about 1 s); afterwards every change is one read fanned out to both. Pushes wait in `:start`
  until B's deck mounts.
- **SSH prompts.** `vinary.main.windows` gains a prompt redirect: `prompt-window` is the active window, unless that is an
  audience window, in which case it is its presenter. Host-key dialogs, secret prompts and SSH toasts use it, so they
  never appear on the projector.

#### 8.9 The trust boundary (L8)

A renderer can now cause one new window to open, can move another window's slide, and can move the two windows of its
session between displays. Each capability is constrained:

- the request must come from an app window's **own** main webContents (never a web view or an embedded view);
- to open a window, that window must be **focused** (a stand-in for a user gesture), it may be in at most one session
  with at most one claim in flight, and the path must be one it **already retains** (`service/window-retains?`);
- moves are accepted only from members and delivered only to members;
- a swap is accepted only from a focused member of a live session whose windows are on separate displays, one swap at
  a time, and it moves only that session's two windows;
- every payload is validated.

Annotation and sidecar text is rendered as text, or as Markdown through the document sanitizer — never as raw HTML. The
new commands are not reachable from a PDF's named actions or links (ADR-0045's allow-list is unchanged). The sidecar is
read only as the PDF's same-stem sibling (the last dot is looked for in the basename only), capped at 4 MiB and refused
unread above it, through a non-blocking open of a regular file only (so a FIFO at that name cannot stall the read) and
`fstat` on the handle it then reads. The background notes work makes at most 2 000 page-text reads per deck
(`notes/max-content-pages`): the scan of a deck of more pages, and the confirmation of a proven parse with more
distinct pages, are refused before any page is read, and any other work that would read more stops part-way with
`:too-many-pages` (§2.5). That budget bounds what pdf.js's worker spends extracting text. Each signature runs on the
thread that calls it, the renderer's in the GUI, and its caps bound it there: 4 096 runs and 65 536 code units per
page, and 16 384 matches per pair (§2.2). It groups the strings by sorting and keeps its cells in a table hashed with
tables drawn at random on every call, so a hostile PDF has no keys to flood. The slowest crafted pages take about
16 ms ([experiment 14](../scientific/14-note-signature-cost.md)). [The threat model](../security/threat-model.md)
records the new boundary.

---

## Consequences

**What the user sees.**

- In any show, `s` shows the notes beside the slide — split halves, note pages, `\pdfpcnote` and sidecar notes —
  with the next slide and the frame counter. A single-window show still starts as the audience sees it.
- A `show notes` deck is presented without its note pages: they leave the slide sequence, the overview, the counter and
  typed numbers, and appear in the presenter view instead. A deck without page labels (a single XeLaTeX pass) gets the
  same once its background scan ends.
- *Present in New Window* (Shift+S) puts the audience view on the other display and keeps the presenter view here;
  either window drives the show, and Escape in either ends it. *Swap screens* fixes a wrong
  guess of which display is the projector.
- The first single-window show of a deck with notes says, once, how to see them.
- `F` fullscreens the window it is pressed in.
- Two ordinary windows no longer cancel each other's window.edn save.

**Trade-offs accepted.**

- The pane is not searched by find, and a presenter view in a window shared on a call shows the notes (the audience
  view is one key away).
- The skew between linked windows is about one animation frame (the report is coalesced) plus sub-millisecond IPC —
  invisible across two displays. A synchronous position hook would save that frame at the cost of a second position
  channel that must agree with the report; rejected.
- A key pressed in one window within the same frame as a move arriving from the other is superseded by it: the arriving
  move was ordered first, and the key had not been reported yet. Both windows still agree.
- pdfpc features outside notes — its timer (deferred with this decision's own, §6.5), transitions, bookmarks, note
  editing and note font size — are not adopted; vinary-viewer never writes the sidecar.
- The presenter view has no timer yet, and its flips — and every flip a window follows — are instant (§6.3, §6.5).
- On native Wayland the audience window must be moved to the projector by hand (Shift+F then fullscreens it), and
  *Swap screens* is not offered.
- A swap briefly shows both windows windowed while they change displays.
- A `plain` or `lined` notes deck from XeLaTeX, dvipdfmx or dvips is shown as it is: its labels cannot be confirmed
  (§2.5).
- REPAIR (§4) decodes two kinds of annotation wrongly in a pdfTeX deck
  ([theory/12](../theory/12-speaker-notes-detection.md) §6.4, Theorem 38). Both need text whose characters, mapped back
  to bytes, form valid UTF-8 with a byte of 0x80 or more:
  - *Text that really is PDFDocEncoding.* `\pdfpcnote` writes the UTF-8 of the source, so such an annotation was
    added by another tool. Every byte of 0x80 or more must belong to a UTF-8 sequence. For two bytes, that is one of
    0xC2–0xDF (Â–ß: most accented capitals, ×, ß) directly before one of 0x80–0xBF (symbols such as ’ ” » © °). A note
    may hold several sequences, and ˘ ˇ ˆ ˝ ˛ ˚ ˜, which come from bytes below 0x20, need not belong to one: `Ã©`,
    `Ã©Ã©` and `Ã©˘` are all shown mis-decoded.
  - *Text that pdf.js decoded by its byte-order mark* (UTF-16, or UTF-8 that starts with U+FEFF), when its characters
    map back to bytes that form such sequences: `FE FF 00 C3 00 A9` reads `Ã©`, and is shown as `é`.

  A lone accented letter, such as the `é` of `café` mapped back to `E9`, is not valid UTF-8, so ordinary text is left
  alone.
- `S` is a bare letter in the `:all` layer, so it acts wherever F5 does, with the focus on the sidebar too.

## Alternatives considered

**Notes.**
- *A per-page notes heuristic on every deck at open* (rotated date or thumbnail): $`O(n)`$ text extractions before the
  first paint. Labels are free; the probe reads two pages of a deck without notes ($`k + 1`$ for $`k \ge 1`$ trailing
  note pages);
  only label-less Beamer decks are scanned; the probe and the scan run after the first paint.
- *A label rule that admits a restarted frame counter* (`appendixnumberbeamer`): makes `1 2 1 …`, the most common
  opening, ambiguous, and the probe would then choose the wrong parse (§2.1).
- *"A slide's words, small, on the next page" as the note-page test*: a theme's footline repeats words small on every
  slide, and a slide with fewer than three words could never match. The scaled-copy test excludes the first and
  handles the second (§2.2).
- *The greedy rule "label = page + 1 means a note page"*: misclassifies $`1, 2, 3, 4`$ and every plain $`1..n`$ deck.
  The automaton is exact over the stated rules.
- *Removing note pages' slots*: breaks the engine's slot-per-page arithmetic and the Document layout. Navigating over
  shown pages and hiding note pages' text layers is local.
- *Making the deck model compute notes itself*: `notes` needs `deck`'s geometry, which would make a namespace cycle;
  `deck` takes a generic `:hidden`, and `notes` depends on `deck` in one direction.
- *pdfpc's precedence (an annotation hides the sidecar note)*: drops an author's note silently.
- *Trusting every engine's labels*: one XeLaTeX pass over an edited deck carries the previous version's labels, so a
  proven parse could hide a slide from the audience (§2.5). Confirming only XeLaTeX would miss dvipdfmx and dvips, which
  write labels the same way.

**The presenter view.**
- *A notes drawer over the slide*: covers the slide and cannot show rendered note regions.
- *Swapping the slide for its notes in place*: both cannot be seen at once.
- *A second engine instance*: the engine registers process-global slots (`cache/set-slides!`, the ensurer).
- *Rendering the whole presenter view from the borrowed document and hiding the engine*: renders everything twice, and
  find and links would target a hidden view.
- *Cropping the engine's canvas, or rasterizing whole pages and cropping with CSS*: the engine rasterizes only the slide
  half; whole pages would double the audience canvas, change the fit, and render notes in the audience window for
  nothing.
- *Downgrading `:push` to `:fade` in the presenter view*: the presenter would see a different animation from the
  audience.

**Linked windows.**
- *BroadcastChannel or localStorage between renderers*: no total order across senders (windows diverge), no lifecycle,
  cannot open windows, reaches the pool, and bypasses the audited IPC seam (ADR-0009).
- *MessagePorts brokered by main*: still need a sequencer, with a more complex lifecycle.
- *Leader and follower*: control from either window was asked for.
- *Main applying relative steps*: main has no deck model.
- *First-writer-wins with base-seq rejection*: loses the later key press and costs a correction round trip.
- *ADR-0047's rule as it stands (skip the originator, no ack)*: the originator cannot order a foreign state against its
  own move, so the windows diverge (theory/11 §1 and §6.3, control N-hold).
- *Echoing every state to everyone*: sends a window its own value back — ADR-0047's regression.
- *Acks as `invoke` replies*: they travel on a different pipe from `webContents.send`, so their order relative to
  pushes is not guaranteed.
- *The audience announcing its start page as a move*: undoes a step the presenter took while the audience window loaded
  (theory/11 §5.3 and §6.3, control N-announce).
- *Auto-linking whenever two windows show the same deck*: too surprising.
- *A display field on `vv:set-fullscreen`*: main places the window before fullscreen anyway, and Electron cannot target
  a Wayland output.
- *Surviving a member's reload by a boot pull*: a reloaded renderer may not even have the deck open.

**The command and the hint** (chosen with the user).
- *Offering Present in New Window only for decks with notes*: a deck without labels learns its notes after its first
  paint, so the command would appear late, and the counter and the next slide help without notes.
- *Binding `S` in the `:slides` layer only*: the menu item, offered in the document layout too, would then show a key
  that does nothing there.
- *No swap, moving the windows by hand*: a wrong guess of the projector would cost leaving fullscreen in each window,
  dragging both, and fullscreening each again.
- *Ending the show when a swap's fullscreen is lost*: a window manager's refusal would end a talk. The show continues
  windowed and the pane says how to recover.
- *A hint at every show of a deck with notes*: a repeated chip on a projector is noise; once per installation.

## Verification

| Rule | Evidence |
|---|---|
| The generated decks the tests use | `test/pdf-gen-smoke.mjs`: `pdf-gen.js`'s note pages, labels, annotations and pdfpc XMP against real Beamer and pdfpc output (`test/fixtures/beamer-*.pdf`), with four negative controls |
| The automaton, the signature, the probe, the scan, the confirmation, pdfpc settings, text notes and REPAIR (on `beamer-pdfpc-pdftex.pdf`) | unit: `vinary.slides.notes-test`; the proofs of [theory/12](../theory/12-speaker-notes-detection.md) and their machine checks against `notes.cljs` itself (§7.1); and [experiment 13](../scientific/13-speaker-note-detection.md), a corpus of 94 small Beamer decks of real TeX output (pdfLaTeX, LuaLaTeX, XeLaTeX after one and two passes, and one XeLaTeX pass over an edited deck) whose expected decisions were written and hashed before the detector first ran on it: no slide was hidden in any of the 94, every deck that hid pages hid all of its note pages (54 of 54), and all 470 decision fields were the registered ones; and [experiment 14](../scientific/14-note-signature-cost.md), the signature's cost (at most about 16 ms at the median on every crafted page, in node's V8 and Electron's) and its equivalence to the code it replaced: a proof, a property test with 18 of 18 negative controls killed (`vinary.slides.notes-signature-equivalence-test`), 38 830 page pairs and the CLI's decisions for 155 decks, all without a disagreement |
| Navigation over shown pages, `[:show p]`, snap, `resolve-goto` | unit: `vinary.slides.deck-test` (extended) |
| The shared pipeline and its CLI fields | `test/cli-smoke.js` `--deck-info` on generated and LuaLaTeX/XeLaTeX fixtures, the pdfpc deck and its sidecar, a sidecar over the cap and a legacy sidecar |
| N7, the sidecar read: its name, the 4 MiB cap, a FIFO, an unreadable file, a directory, over SFTP | `test/content-service-smoke.js` `testPdfpcSidecar`; five mutants, each killed — a blocking open, the cap off by one, the dot looked for in the whole path, no remote sidecar, a remote read past the cap |
| N7, the sidecar watched with the PDF (the real main) | `test/watch-e2e.js`: creating, rewriting and deleting `talk.pdfpc` beside an open deck each re-deliver the PDF and the deck follows; two mutants killed — the sidecar not watched, the sidecar not sent |
| A byte-cache version carries its sidecar read | unit: `vinary.renderer.pdf-cache-test` `the-sidecar-is-part-of-its-version` |
| §3 in the engine: note pages marked and never drawn in the Slides layout, flips over them, the next slide kept rendered, typed numbers, the overview, find, a show started from a note page, `:off`, one XeLaTeX pass settled by the scan, the sidecar's hidden page | the `npm run test:electron` NOTE PAGES block, on the real LuaLaTeX, XeLaTeX and pdfpc fixtures. Seven mutants, each killed at the check named before its run: the note pages not hidden, note pages rasterized, no keep-set, no background settle, the sidecar dropped by the view, the note-page CSS removed, and no snap in `go-to-page!`. The last one paints the first frame of a show started from a note page on that page's blank slot, and `snap!`, at the end of the scroll, repairs it one frame later. Only a record taken at the end of each frame (a `ResizeObserver` callback, after layout, before paint) sees that frame: the show starts inside Reagent's own animation-frame callback (its `flushSync` commit), so an end-state check and a record taken in an animation-frame callback both missed it — both were tried first ([test strategy §3.5](../engineering/03-test-strategy.md#35-the-harnesses-that-run-the-real-main-process)). A double mutant, with no snap anywhere, is killed too |
| The presenter view, the pane, instant flips while it is shown, find | `npm run test:electron` blocks |
| The protocol | [theory/11](../theory/11-linked-slide-show-protocol.md): proofs for any delivery order, and TLC (FIFO and reordering channels, two windows snapping pages; seven negative controls); unit tests of both pure models (`vinary.main.slides-link-test`, 20 tests; `vinary.app.slides-link-test`, 27); and `vinary.app.slides-link-sim-test`, a seeded randomized simulation that drives the real pure code of both windows and main: 2 200 runs over seven configurations (FIFO and reordering channels, windows snapping pages, deeper runs, random ones), each step checking P2, P4 and P6 and each quiescent state P1 and P3, with seven negative controls that each break the property their rule protects |
| Display choice, placement, the swap plan | unit: `vinary.main.displays-test` (10 tests: the display holding most of a window, the audience display, centred placement in the work area, the sessions that let main place windows, what the presenter is told, swapping screens, nothing to swap, where the audience window of a new show goes, and which removed display ends a show) |
| The window side of a linked show: Shift+S, the joined message, the other window's moves, the end, the one outgoing interceptor, leaving, the audience joining and starting, Swap screens, the pane's line, `S` in every preset | unit: `vinary.app.slides-linked-test` (10 tests), over the real event handlers and the interceptor |
| The real main: Shift+S opens a second window that paints and presents the deck while the notes stay in the first; a flip in either window moves the other; a blank in either is the other's, and Escape's first step clears it in both; Escape in either window ends both, and main closes the audience window; the audience window closed by hand leaves the presenter presenting, told why; Shift+S while linked opens nothing new | `npm run test:presenter-e2e` (`test/presenter-e2e.js`), twice: with the warm pool (the audience window is the pool window that was ready) and with `VV_POOL=0` (a cold window, joined once it has loaded). Xvfb gives Chromium one display, so the show is the one-display one (`display: "same"`) |
| Swap screens in the shell: leave, move, enter again, then one report per window with its fullscreen and owner back; a window the window manager will not make fullscreen again; a window closed part-way | unit: `vinary.main.fullscreen-test`, on fake windows — no display server the tests can run gives Chromium two displays (Xvfb reports one, with RandR monitors added by `xrandr --setmonitor` and with two screens joined by Xinerama; probed 2026-10-19). A mutant without `wire!`'s swapping guard fails both tests |
| A remote deck in a linked show (§8.8): the audience window's open of an `ssh://` deck the presenter retains costs at most one more read; flips from either window; a change on the server is one read, shown in both on the same slide; Escape ends both | `test/remote-pdf-e2e.js` arm H: the real main, a real SFTP server and a cold audience window, with the fixture's byte and OPEN counters |
| SSH prompts of an audience window go to its presenter | unit: `vinary.main.windows-test` `prompts-follow-the-redirect-to-a-live-window` |
| window.edn: per-window saves, an audience window never saved | unit: `vinary.main.window-test` (`a-second-window-does-not-cancel-the-first-windows-save`, `an-ephemeral-window-is-never-saved-and-close-flushes`, `persistable-bounds-skips-an-ephemeral-window`) |
| The hint: when it shows, once per installation | unit: `vinary.app.slides-presenter-test` (`the-notes-hint-is-due`, `the-notes-hint-shows-once`, `the-notes-hint-goes`); and the `npm run test:electron` PRESENTER VIEW block, step 2: the first show of a deck with notes names `s` in a `role="status"` hint, `settings.edn` remembers it, the next key dismisses it and still does its work, no frame of a second show has it, and its own timeout and a typed digit dismiss it too |

## References

- [theory/11 — The linked slide show protocol](../theory/11-linked-slide-show-protocol.md) and its TLA+ model,
  [`formal/linked-show/LinkedShow.tla`](../theory/formal/linked-show/LinkedShow.tla)
- [theory/12 — Speaker-notes detection](../theory/12-speaker-notes-detection.md): the proofs behind §1–§5 (the label
  automaton, the restart guard, the copy test, the probe, the scan, CONFIRM, `PDFDOC⁻¹` and REPAIR), and their checks
  against `notes.cljs` itself, [`formal/speaker-notes/check.sh`](../theory/formal/speaker-notes/check.sh)
- [Experiment 13 — Speaker-note detection on real TeX output](../scientific/13-speaker-note-detection.md): §2's
  detector on 94 small Beamer decks from pdfLaTeX, LuaLaTeX and XeLaTeX, against expectations registered before it
  ran
- [Experiment 14 — The cost of one note-page comparison](../scientific/14-note-signature-cost.md): §2.2's signature
  timed against hostile pages, the floods that ruled out two designs, and the proof that the committed one returns
  what the code before it returned
- [ADR-0045](0045-slide-deck-presentation.md), [ADR-0046](0046-reload-safe-pipelined-remote-reads.md),
  [ADR-0047](0047-persisted-state-stores.md), [ADR-0009](0009-mediator-ipc-over-point-to-point.md),
  [ADR-0010](0010-bounded-content-retention-and-render-metadata.md)
- Diagrams: [the linked show's messages](../diagrams/seq-linked-slide-show.svg)
  ([source](../diagrams/seq-linked-slide-show.puml)) and [its states](../diagrams/state-linked-slide-show.svg)
  ([source](../diagrams/state-linked-slide-show.puml))
- Beamer: `beamerouterthemedefault.sty` (the `note page` template), `beamerbasenotes.sty`; the Beamer user guide
  §19.2 *Notes* — <https://ctan.org/pkg/beamer>
- pdfpc v4.7.0 — <https://github.com/pdfpc/pdfpc> (`src/classes/metadata/pdf.vala`, `timer.vala`,
  `schema/pdfpc.json`); `pdfpc.sty` v0.7.1 — <https://ctan.org/pkg/pdfpc>
- Electron: `BrowserWindow.showInactive`, `screen`, `webContents.send` —
  <https://www.electronjs.org/docs/latest/api/browser-window>, <https://www.electronjs.org/docs/latest/api/screen>
- L. Lamport, *Specifying Systems* (Addison-Wesley, 2002) — TLA+ and TLC —
  <https://lamport.azurewebsites.net/tla/book.html>
