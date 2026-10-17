# Slide shows — Beamer and slide-tool decks, one slide per screen

**Status: Available now.**

---

## 1 · What it is

A PDF that is a **slide deck** — a Beamer deck from pdfLaTeX, LuaLaTeX or XeLaTeX, or a PowerPoint, Google Slides,
LibreOffice Impress or Keynote export — opens in the **Slides layout**: one slide per screen, fitted and centred,
with no scrollbar. The keys you already scroll with flip slides instead, in every keymap set; Shift skips a Beamer
frame's remaining overlays; typing a number and Enter goes to the slide that carries that number. **F5** starts a
**slide show**: the app's chrome disappears, the slide fills the window on black, and the window goes fullscreen until
you press Escape. **Tab** opens an **overview** grid of every frame. While you edit the deck — locally, or over
`ssh://` / `sftp://` — each recompile swaps in place, on the slide you are on, and a half-written PDF is never shown.

Any PDF can be shown this way: **View ▸ View as Slide Show** switches a paper into the Slides layout, and F5 presents
any PDF. The design is recorded in [ADR-0045](../design-decisions/0045-slide-deck-presentation.md); the detection
corpus and the flip-latency measurements are [experiment 12](../scientific/12-slide-deck-detection-and-flip-latency.md).

| Term | Meaning |
|---|---|
| deck | A PDF the viewer classifies as slides (§2, *Which PDFs open as slides*). |
| frame | Beamer's unit, one `\begin{frame}`. A frame with overlays (`\pause`, `<2->`) compiles to several pages — its **overlays** — that share the frame's number as their page label. |
| Slides layout · Document layout | One slide per screen · the continuous, scrolling pages of [feature 11](11-native-pdf.md). |
| slide show (presenting) | The Slides layout full-window, chrome hidden, usually fullscreen. |
| overview | A grid with one tile per frame, over the slides. |

![A Beamer deck in the Slides layout, with the slide bar at the bottom](../screenshots/slides-layout.png)

*A LuaLaTeX Beamer deck ([`samples/slides.tex`](../screenshots/samples/slides.tex)) in the Slides layout: page 4 of
8, frame 2 of 5, its third and last overlay. The address bar's slides button is pressed; the slide bar replaces the
zoom control.*

---

## 2 · How to use it

### Which PDFs open as slides

A PDF opens as slides when the detector recognizes it as a deck. The rules are tried in order, and the first that
matches decides ($`n`$ is the page count; two sizes count as equal when both dimensions agree within
$`0.5\,\mathrm{bp}`$, where $`1\,\mathrm{bp} = \tfrac{1}{72}\,\mathrm{in}`$ is a PDF point):

| Recognized as | Because |
|---|---|
| **definite · Beamer** | Its `Creator` (or XMP `CreatorTool`) names Beamer — `beamer.cls` writes "LaTeX with Beamer class" — and it has $`n \ge 2`$ pages, or one page of Beamer paper. |
| **definite · full-screen mode** | It asks to open in full-screen mode (`/PageMode /FullScreen`). The viewer reads that as evidence only; it never goes fullscreen by itself. |
| **probable · slide export** | $`n \ge 2`$ pages, all one size, and that size is a slide tool's exact export canvas: 960 × 540, 720 × 540, 720 × 405, 720 × 450, Impress's 793.7 × 595.3, 793.7 × 446.5 and 793.7 × 496.1, or Keynote's 1024 × 768 and 1920 × 1080 (provenance in the ADR). |
| **probable · Beamer geometry** | $`n \ge 2`$ pages, all one Beamer paper size, from a TeX engine — a Beamer deck whose `\hypersetup{pdfcreator=…}` was overridden. |
| not a deck | Everything else. **Landscape pages alone never make a deck**: landscape figures, Letter-landscape print-outs and forms are documents. |

A recognized deck opens as slides unless Preferences ▸ Slides ▸ *Open detected slide decks as* is *Document*. Either
way, the choice per PDF is yours:

- **View ▸ View as Slide Show** (Alt+V, L), the address bar's slides button, or right-click a page ▸ *View as Slide
  Show* switches the shown PDF to the Slides layout; the same places offer **View as Document** (Alt+V, M) to switch
  back.
- The choice belongs to that **document view**: Back and Forward bring it back with the page, and the next PDF you open
  in the tab starts from detection again.

### Keys

Nothing needs rebinding: every scroll key of every keymap set flips slides in the Slides layout, and a small set of
slide keys is added on top.

| Action | Standard | Vim (normal mode) | Emacs |
|---|---|---|---|
| Next slide — one overlay | → ↓ PgDn, Space, Enter | → ↓ PgDn, `j` `l` `C-d` `C-f`, Enter | → ↓ PgDn, `C-n` `C-v`, Space, Enter |
| Previous slide | ← ↑ PgUp, Shift+Space, Backspace | ← ↑ PgUp, `k` `h` `C-u` `C-b`, Shift+Space, Backspace | ← ↑ PgUp, `C-p` `M-v`, Shift+Space, Backspace |
| Next **frame** — skip this frame's remaining overlays | Shift+→ Shift+↓ Shift+PgDn | the same | the same |
| Previous frame — shown fully built | Shift+← Shift+↑ Shift+PgUp | the same | the same |
| First / last slide | Home / End | Home / End, `g g` / `G` | Home / End |
| Go to slide *n* | digits, then Enter | the same | the same |
| Delete the last typed digit | Backspace | the same | the same |
| Overview grid | Tab, `o` | the same | the same |
| Start a show from the first / the current slide | F5 / Shift+F5 | the same | the same |
| During a show: black / white screen | `b` `.` / `w` `,` | the same | the same |
| Cancel, one step at a time | Escape, Ctrl+G | Escape | Escape, `C-g` |

- **The mouse and touch.** The mouse wheel and a trackpad flip slides one at a time: one notch of a mouse wheel is
  one slide at any window zoom (about 90 device-independent pixels of scrolling flip one, as in pdf.js's
  presentation mode), and after each flip the wheel rests for 50 ms, so one fling cannot skip several slides. During a show a click advances and Shift+click goes back — not on a link, not when it ends a text selection or a drag. On a touch
  screen or with a pen, a quick, mostly horizontal swipe of at least 48 px flips a slide (leftward forward); a mouse
  drag selects text instead.
- **Typing a number.** A "Go to 12" chip shows what you typed. The number names the label printed on a Beamer slide
  — its frame number — first, and otherwise the page number; Enter goes there, Backspace deletes a digit, and Escape,
  any slide step or 2.5 s of quiet forgets it.
- **Escape unwinds one step at a time**, after the palette and the find bar: the overview, a typed number, a blanked
  screen, then the show itself.
- **When the slide keys apply.** Space, Shift+Space, Backspace, Enter, the Shift+arrows, `b` `.` `w` `,`, Tab, `o` and
  the digits form a `:slides` layer that is active only while a PDF shows in the Slides layout **and** the keyboard is
  on the document — not in a text field, the sidebar, the address bar, or an open menu, dialog or prompt. The layer
  sits below the current mode, so in Vim's normal mode Space stays the leader (use →, `j` or PgDn there; Space flips
  in insert mode). It is identical in every set and can be overridden like a mode, under `:keymaps {:slides {…}}` in
  `keybindings.edn` ([feature 15](15-custom-keybindings.md)). F5 and Shift+F5 work in any mode.

### Presenting

1. **Start.** F5 presents the shown PDF from its first slide; Shift+F5, the slide bar's Present button or **View ▸
   Start from Current Slide** from the slide you are on; right-click a page ▸ **Start From This Slide** from that page. Any PDF
   can be presented, deck or not. F5 again during a show goes back to the first slide.
2. **What changes.** The menu bar, sidebar, tabs, address bar, bottom bar and status bar hide; the slide fills the
   window on black with no margin; the window goes fullscreen (Preferences ▸ Slides ▸ *Present full screen*, on by
   default). Only the window you started in goes fullscreen. The pointer hides after 2.5 s without moving. The SSH
   password prompt, the palette, the context menu and the find bar still appear over the show, and a refresh note
   stays visible but compact.
3. **During the show.** Every key above works. `b` or `.` blanks the screen to black and `w` or `,` to white; the same
   key again, a slide step, a click or Escape brings the slide back — a slide step only unblanks, so the audience sees
   the slide they left.
4. **End.** Press Escape (once per open step), choose **End Slide Show** from the right-click menu or the palette, or
   leave fullscreen yourself (F11, your window manager). Switching tabs, navigating, or focusing the address bar or
   the sidebar also ends the show. A window you had made fullscreen before the show stays fullscreen afterwards; the
   app leaves only a fullscreen it entered.

### The overview

Tab or `o` (also **View ▸ Slide Overview**, the slide bar's grid button, or the palette's *Slide overview* / *Go to
slide…*) opens a grid with **one tile per frame**, each showing the frame fully built (Preferences can show each
frame's first overlay instead). The current frame's label is highlighted.

| Key or gesture | In the overview |
|---|---|
| ← → | the previous / next tile, in reading order |
| ↑ ↓ | the tile above / below, in the same column |
| PgUp / PgDn | a screenful of rows up / down |
| Home / End | the first / last tile |
| digits | select the frame with that label (the number on its slide) |
| Enter · click | open the tile's page |
| Shift+Enter · Shift+click | open the frame's other end (its first overlay, by default) |
| Escape · Tab · `o` | close the overview |

Opening a slide from the overview is a **jump**, so Back (the palette's *Back to the slide before the last jump*, or
the deck's own back link) returns. Thumbnails render lazily near the visible part of the grid, and far ones are
released, so a 150-slide deck stays light.

![The overview grid over a Beamer deck: one tile per frame](../screenshots/slides-overview.png)

### Without the keyboard

- **View menu** (PDF only): *View as Slide Show* (L) ↔ *View as Document* (M); *Start Slide Show* (H, F5); *Start
  from Current Slide* (U, Shift+F5); and, in the Slides layout, *Slide Overview* (E, Tab). *Fit* is shown only in the
  Document layout, because slides fit themselves; *Reflow Text* now uses W.
- **Right-click a page:** *Start Slide Show*, *Start From This Slide* and *View as Slide Show* / *View as Document*;
  during a show, *End Slide Show*.
- **The address bar's slides button** (a person at a chalkboard), shown for any PDF, switches the layout and shows
  pressed in the Slides layout.
- **The slide bar** replaces the zoom control at the bottom in the Slides layout: ◀ · the page field (a physical page
  number: type it and press Enter, or Escape to cancel) · "/ N" · ▶ · "frame f / F · o/O" (for a deck with overlays)
  · the overview button · the present button (from this slide). The arrows, the page field and the address bar's
  button hand the keyboard back to the slides afterwards, so the next Space or Enter flips a slide instead of pressing
  the button again.
- **The palette** (Ctrl+Shift+P) offers a *Slides* category while a PDF is in the Slides layout — next / previous
  slide and frame, first and last, back and forward through the jump history, the overview and *Go to slide…*, the
  black and white screens and *End slide show* during a show, *Start slide show* (from the first or the current
  slide) for any PDF — and *View as slide show / document* under *View*.

### Links inside the deck

A link to another page is a **jump**: it records where you came from, and Beamer's back link (or the palette) returns
there, in both layouts. Beamer's navigation symbols work: the next / previous slide arrows are ordinary links, and the
back–find–forward symbol's *GoBack*, *Find* (opens the find bar) and *GoForward* are honoured, as are *NextPage*,
*PrevPage*, *FirstPage* and *LastPage* links. Every other PDF named action — *Print*, *SaveAs*, *Quit*,
*FullScreen*, … — stays inert, so a document cannot print, save, quit or make the window fullscreen.

### Preferences

**Settings ▸ Preferences… ▸ Slides**, stored in `settings.edn` ([configuration](../usage/05-configuration.md)):

| Preference | Alt key | Choices — default first | `settings.edn` key |
|---|---|---|---|
| Open detected slide decks as | O | Slides (one per screen) · Document (continuous pages) | `:slides-open-as` `:slides` / `:document` |
| Overview shows each frame | W | Fully built (its last overlay) · At its first overlay | `:slides-overview-page` `:last` / `:first` |
| Slide transition | R | None (instant) · Fade · Push | `:slides-transition` `:none` / `:fade` / `:push` |
| Notes on a second screen | N | Auto (notes on the right, or below) · Notes on the right · on the left · below · above · Show whole pages | `:slides-notes` `:auto` / `:right` / `:left` / `:bottom` / `:top` / `:off` |
| Present full screen | U | on · off | `:slides-fullscreen?` |
| Click advances the slide show | K | on · off | `:slides-click-advance?` |

*Fade* (220 ms) dissolves the outgoing slide and *Push* (260 ms) slides it off while the next one slides in from the
side the deck moves toward. Only the flips you ask for animate — keys, the wheel, clicks, swipes, the slide bar, links
and the overview — while find and the Contents outline jump instantly; a flip during a transition ends it at once, and
the system's *reduce motion* setting turns transitions off.

### Why a PDF is (or is not) a deck: `--deck-info`

`vv --cli --deck-info` prints, for each PDF, the evidence the detector reads and the verdict it reaches — the GUI's
own code, run headless ([terminal preview](30-terminal-preview.md)). For the two sample PDFs in
`docs/screenshots/samples/` — the deck in the screenshots and a six-page US-Letter article:

```console
$ cd docs/screenshots/samples && vv --cli --deck-info slides.pdf fuzzy-rho.pdf
{"path":"slides.pdf","pages":8,"sizes":[[453.54,255.12]],"distinctSizes":1,"creator":"LaTeX with Beamer class","producer":"LuaTeX-1.24.0","creatorTool":null,"pageMode":"UseOutlines","labels":{"count":8,"distinct":5},"deck":true,"tier":"definite","source":"beamer","frames":5,"secondScreen":null}
{"path":"fuzzy-rho.pdf","pages":6,"sizes":[[612,792]],"distinctSizes":1,"creator":"LaTeX with hyperref","producer":"pdfTeX-1.40.27","creatorTool":null,"pageMode":"UseOutlines","labels":null,"deck":false,"tier":null,"source":null,"frames":6,"secondScreen":null}
```

`sizes` lists up to five distinct page sizes in bp; `labels` counts the page labels without listing them (they are
document content). Eight pages with five distinct labels are five frames; the paper is one frame per page.

### Example

Compile [`docs/screenshots/samples/slides.tex`](../screenshots/samples/slides.tex) with `lualatex` and open
`slides.pdf`: it opens on slide 1 in the Slides layout. Press → twice to build the *Detection* frame's bullets one at a
time, Shift+→ to skip to *Flip latency*, `4` then Enter to jump to frame 4, and Tab to see all five frames. Press F5
to present from the start, `b` to blank the screen and `b` again to bring it back, then Escape to end the show. Run
`latexmk -pvc -lualatex slides.tex` in a terminal and edit a frame: every recompile swaps in on the slide you are
looking at.

---

## 3 · How it works internally

```text
bytes (vv:content → byte cache, as a CANDIDATE)
  └─ renderer.pdf/prepare!        pdf.js getDocument; page-sizes ∥ slides.evidence/gather
       └─ slides.deck/deck         classify (rules in order) → frames → {:deck? :tier :source :pages :frames …}
            └─ {:doc :sizes :deck} leased through the warm cache; :pdf/deck-detected → :doc/deck
                 └─ mount-prepared!   layout = deck/effective-layout(presenting?, the view's choice, deck?, preference)
                      ├─ Document layout  (feature 11: continuous pages, zoom, Fit)
                      └─ Slides layout    slots W × H, one sheet per page at its own fit, ±1 render band
                           ├─ keys      key-scroll! / keymap → scroll delegate or :slides layer → step! → command!
                           ├─ report!   :slides/changed → [:ui :slides] → slide bar, menus, chip, tests
                           ├─ F5        :slides/present → [:ui :presentation] → .vv-presenting + vv:set-fullscreen
                           ├─ Tab       :slides/overview → ui/slides-overview + renderer.pdf-thumbs (borrowed doc)
                           └─ refresh   new stamp → refresh! → replace-doc! (same view, same slide) | refresh note
```

**Detection on prepare.** `renderer.pdf/prepare!` reads the evidence — the `/Info` Creator and Producer, the XMP
CreatorTool, the `/PageMode`, the page labels — in parallel with the page measure the layout needs anyway, so a deck
is known before its first mount: no flash, no extra I/O, and the same result for a local, remote, archived or
web-linked PDF. The pure model `vinary.slides.deck` classifies it and groups Beamer's overlays into frames from their
shared page labels; the result rides the artifact the warm cache leases, and its summary becomes `:doc/deck` for the
menus and the toolbar ([ADR-0045 §1–§2](../design-decisions/0045-slide-deck-presentation.md#1-detection--evidence-tiers-and-the-rule-table)).

**The Slides layout is the same engine.** Each page's placeholder becomes a slot exactly one viewport tall and the
page sits in it at its own fit, so every page stays in the DOM: find, selection, copy, links and the Contents outline
work unchanged. A flip writes the scroller's position to the slot's top at once; anything that scrolls smoothly is
snapped to the nearest slot when it ends. Half a viewport of render overscan keeps exactly the previous and next
slides rendered, so a flip never waits for pdf.js, and at most three page canvases are alive (§3 of the ADR).

**Keys reach the slides through one delegate.** Every keymap's scroll command arrives at one effect as the same
options map; in the Slides layout a registered *scroll delegate* turns it into a slide step. The slide keys proper
form the `:slides` layer under the mode. A key's slide step runs inside the key handler itself, so the slide shows in
the next frame: a median of 7.9 ms from key to frame on a 60 Hz display, down from 24.3 ms when the step waited for
re-frame's queue ([experiment 12 §8](../scientific/12-slide-deck-detection-and-flip-latency.md#8--flip-latency)).

**Presenting is window-level.** The show asks main to make *its* window fullscreen; main decides, remembers that it
did, and leaves only a fullscreen it entered. CSS hides the chrome, so nothing unmounts and the app's own prompts stay
on top ([ADR-0045 §5](../design-decisions/0045-slide-deck-presentation.md#5-presenting)).

**The overview borrows the document.** It is an overlay beside the slides, not a layout of them, so it opens and
closes instantly; its thumbnails are drawn lazily from the mounted view's own `PDFDocumentProxy`
([ADR-0045 §6](../design-decisions/0045-slide-deck-presentation.md#6-overview--a-separate-overlay-d12)).

**A refresh swaps in place.** The PDF view is keyed by tab and path, not by the content stamp, so a recompile arrives
as a new stamp on the same view. The new bytes are a *candidate*: they are prepared beside the live document and swapped
in only once pdf.js opens them, keeping the slide and the old pixels until each new raster is ready. A candidate that
does not open is dropped and the last good version stays, behind the refresh note
([ADR-0045 §7](../design-decisions/0045-slide-deck-presentation.md#7-live-refresh-in-place-d11)); main delivers one
verified read per change to every window ([ADR-0046](../design-decisions/0046-reload-safe-pipelined-remote-reads.md)).

**Namespaces.**

| Namespace | Role |
|---|---|
| `vinary.slides.deck` · `vinary.slides.evidence` | the pure deck model: detection, frames, navigation, input mappings; the evidence reader |
| `vinary.renderer.pdf` · `.pdf-layout` · `.pdf-cache` · `.pdf-thumbs` | the engine's two layouts, slide steps, presenting listeners, transitions, swipes, named actions; pure geometry; the byte cache and the slides hook; the overview's thumbnails |
| `vinary.renderer.scroll` | the scroll delegate |
| `vinary.app.slides` · the `:slides/*` and `:pdf/*` events in `vinary.app.events` | the app-db side: preferences, the effective layout, the position mirror, presenting, the overview, typed numbers, blanking |
| `vinary.app.commands` · `vinary.input.resolver` · `resources/keymaps/*.edn` | the Slides commands, the immediate view moves, the `:slides` layer |
| `vinary.ui.slides-bar` · `.slides-hud` · `.slides-overview` · `.refresh-note` · `.pdf-view-lifecycle` | the slide bar and toolbar button; the chip and blank screen; the overview; the refresh banner; the refresh-in-place decisions |
| `vinary.main.fullscreen` | main's fullscreen decision, per window |
| `vinary.cli.core` (`--deck-info`) | the headless diagnostic |

Every event, effect and subscription is catalogued in [events-effects-subs](../reference/events-effects-subs.md), the
three fullscreen channels in [ipc-channels](../reference/ipc-channels.md), and the `[:ui :slides]`,
`[:ui :presentation]`, `:doc/deck` and `:doc/refresh-note` shapes in the
[state schema](../architecture/04-state-schema-reference.md).

---

## 4 · Design notes / trade-offs

- **Why inside the existing engine, not a separate slide viewer?** Every feature of the PDF view — find, links,
  selection, the Contents outline, dark-invert, live refresh — keeps working in the Slides layout because it is the
  same view with different geometry ([ADR-0045 §3](../design-decisions/0045-slide-deck-presentation.md#3-the-slides-layout-inside-the-existing-engine)).
- **Why exact canvases and never "landscape"?** In a survey of 2 456 local PDFs, landscape pages were figures and
  print-outs far more often than slides; slide tools export at exact sizes
  ([experiment 12 §6](../scientific/12-slide-deck-detection-and-flip-latency.md#6--the-natural-corpus)).
- **Why window-level fullscreen?** The HTML Fullscreen API's top layer would hide the SSH prompt, the palette and the
  context menu, and the browser would own Escape.
- **Why is the layout choice per document view?** So that switching one deck to slides does not force the next paper
  opened in the tab into slides — and Back still brings the choice back.

### Limitations and troubleshooting

- **A Beamer deck from a single XeLaTeX pass steps one page at a time.** hyperref writes XeLaTeX's page labels from
  the ones the previous run left in the `.aux` file, so the first pass has none (its log says *Rerun to get
  /PageLabels entry*). Overlays then cannot be grouped into frames: Shift+→ moves one page, the overview shows every
  page, and typed numbers are page numbers. Compile a second time and the deck steps by frame. pdfLaTeX and LuaLaTeX
  write the labels in the same run.
- **After an edit, one XeLaTeX pass carries the previous version's labels**, and logs no warning. Frames are then
  grouped as they were before the edit: an inserted frame's pages join the next frame's first page. Compile until
  the auxiliary files settle, as latexmk does, and the labels match the deck. A watch loop that runs one pass per save
  is always one version behind. The dvipdfmx and dvips drivers write labels the same way; pdfLaTeX and LuaLaTeX do
  not ([experiment 12](../scientific/12-slide-deck-detection-and-flip-latency.md) §5.1).
- **A paper opens as slides.** Either it asks for full-screen mode, or it is typeset on Beamer paper by a TeX engine —
  the documented false positive of the *Beamer geometry* rule. Choose View as Document (remembered for that view), or
  set *Open detected slide decks as* to *Document*.
- **A deck opens as a document.** Landscape pages are not decks by design, and an export at a canvas the detector does
  not know is not recognized. Choose **View ▸ View as Slide Show**; `vv --cli --deck-info` shows what the detector saw.
- **A two-screen deck shows its notes, or half a slide.** Decks built with `\setbeameroption{show notes on second
  screen=<side>}` have every page twice the paper wide (or tall); the Slides layout shows the slide half, assuming
  Beamer's default (notes on the right, or below). If you wrote another side, pick it under *Notes on a second screen*
  — or *Show whole pages*. Such a deck steps one page at a time and is numbered by page, because pgfpages corrupts its
  page labels undetectably ([measurements](../scientific/data/12/second-screen-2026-10-16.txt)). View as Document
  always shows whole pages, notes included and searchable. Plain `show notes` (note pages between the slides) is shown
  as is.
- **Zoom does nothing in the Slides layout**, and *Fit* is hidden: slides fit themselves. Use View as Document to zoom.
- **Tab opens the overview** in the Slides layout instead of moving the focus, and **Space is a slide key** — both
  rebindable under `:keymaps {:slides …}`. In Vim's normal mode Space stays the leader.
- **The show does not go fullscreen** when *Present full screen* is off — it then fills the window — and **leaving
  fullscreen ends the show**, by design.
- **The middle of Beamer's slide-navigation symbol does nothing.** It asks the viewer for Acrobat's *GoToPage* dialog,
  which is not on the allow-list. Type the slide's number and Enter, or press Tab.
- **The jump history** (Beamer's back link, *Back to the slide before the last jump*) belongs to the view and ends with
  it, when you switch tabs or navigate. The app's own Back/Forward is separate and still returns to the slide.
- **A refresh note appears over the deck.** The new version could not be displayed — pdf.js could not open it, main
  could not re-read the file, or the file on disk is still incomplete — so the last good version stays on screen.
  *Retry* re-reads the file, *×* dismisses the note, and the next good version clears it.
- **Remote decks** behave the same: `vv sftp://host/talk.pdf` opens as slides, each change is read once and swapped in
  on the same slide, and a connection dropped while presenting keeps the slide until *Retry* reconnects.
- **Not available yet:** a presenter console on a second display (current and next slide, timer, notes) and slide
  navigation in `vv --tui`; both are filed feature requests.

---

## 5 · Diagrams

- **State — the modes of a PDF view:** the Document layout, the Slides layout and presenting, with typing, blanking
  and the overview as sub-states, and what triggers every transition.

![A PDF view's modes: Document layout, Slides layout, Presenting](../diagrams/state-slides-modes.svg)

*Diagram source: [`../diagrams/state-slides-modes.puml`](../diagrams/state-slides-modes.puml).*

- **Activity — detection:** evidence and page sizes in parallel, the rules in order, frames, and the layout a PDF
  opens in.

![Deck detection and the layout a PDF opens in](../diagrams/activity-pdf-deck-detection.svg)

*Diagram source: [`../diagrams/activity-pdf-deck-detection.puml`](../diagrams/activity-pdf-deck-detection.puml).*

- **Sequence — presenting:** F5, the fullscreen request to main, the chrome hidden; Escape, and main leaving only a
  fullscreen it entered.

![Presenting: F5 → window-level fullscreen owned by main → Escape](../diagrams/sequence-slides-present.svg)

*Diagram source: [`../diagrams/sequence-slides-present.puml`](../diagrams/sequence-slides-present.puml).*

- **Sequence — live refresh in place:** a changed PDF swapped in under the same view, and every failure kept behind the
  refresh note.

![Live refresh in place: the new PDF swaps in under the same view, on the same slide](../diagrams/sequence-pdf-refresh-in-place.svg)

*Diagram source: [`../diagrams/sequence-pdf-refresh-in-place.puml`](../diagrams/sequence-pdf-refresh-in-place.puml).*

See also: [ADR-0045](../design-decisions/0045-slide-deck-presentation.md) ·
[ADR-0046](../design-decisions/0046-reload-safe-pipelined-remote-reads.md) ·
[experiment 12](../scientific/12-slide-deck-detection-and-flip-latency.md) ·
[experiment 11](../scientific/11-remote-read-and-reload-safety-experiments.md) ·
[keyboard shortcuts](../usage/04-keyboard-shortcuts.md) · [configuration](../usage/05-configuration.md) ·
[PDF preview](11-native-pdf.md) · [remote files](29-remote-files-over-ssh.md) · [glossary](../GLOSSARY.md).
