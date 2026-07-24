# 09 — The in-page-find and scroll-ownership experiments

**Status: complete for v0.3.0-dev.** A user reported four symptoms that arrived together; they turned out
to be **three independent defects plus one design gap**, only one of which was in the find module's own
code. This is the ledger of how each was isolated, measured, fixed, and re-measured, including the
hypotheses that did **not** survive contact with the instruments.

The methodology is the one written down in [07 — Measurement methodology](07-measurement-methodology.md):
instrument first, predict before measuring, record the raw numbers, and keep the refuted hypotheses so they
are not re-attempted. The interventions are recorded as a decision in
[ADR-0032 — Scroll ownership and derived input focus](../design-decisions/0032-scroll-ownership-and-derived-input-focus.md).

---

## 1 · Symptom

Reported verbatim, on the **Vim** keymap, viewing **small Markdown** files and **PDFs**:

1. "When I use the find feature and enter some text, it does not seem to highlight correctly in the window."
2. "Navigating among matches does not seem to work right."
3. "If I press `Esc` to close the find widget, scrolling often freezes at the current location in the view.
   If I try to scroll up or down, the view moves a few pixels and then snaps back to the previous position.
   Page Up and Page Down seem to work okay, but scrolling often gets stuck and rubber bands."
4. "Correlated with the rubber band scrolling, the keyboard shortcuts to open the find dialog no longer work
   (if the rubber band scrolling is active)."

The **correlation** in (4) is the most informative part of the report, and also the most misleading: it
suggested a single cause. It is not one. Symptoms 3 and 4 begin at the same instant — the first `Esc` close
of the find bar — but by two unrelated mechanisms.

Two details of (3) are load-bearing and were treated as hard constraints on any accepted hypothesis:

- **"Page Up and Page Down seem to work okay."** Any explanation had to say why *those* keys were exempt.
- **"moves a few pixels and then snaps back."** Something was actively writing the scroll position back;
  a merely-unresponsive scroller would not move at all.

---

## 2 · Hypotheses

Stated before measuring, each with a prediction that could falsify it.

| # | Hypothesis | Falsifiable prediction |
|---|------------|------------------------|
| **A** | `[:ui :input :in-input?]` is stuck `true` because the find bar unmounts a **focused** `<input>`, and Chromium fires no `blur` for a removed element. The keymap resolver then drops every *bare printable* key — which under Vim is `/` (open find), `n`/`N` (cycle) and `j`/`k` (scroll). | `__vvdb().ui.input['in-input?'] === true` immediately after an `Esc` close, and the failure is keymap-specific: `Ctrl+F` (default) survives, `/` (vim) does not. |
| **H1-A** | The eased scroll chase cannot terminate because its target is **unreachable**: `max-top` is derived from the *integer* `scrollHeight`/`clientHeight`, which can exceed the true fractional maximum. | Probe E3 reports `overshoot > 0.5`. |
| **H1-B** | The chase cannot terminate because its **step falls below the scroll quantum**. It settles at `\|dt\| < 0.5` but steps by `0.25·\|dt\|`, so for `\|dt\| ∈ [0.5, 2.0)` it asks for `< 0.5 px` — and if offsets snap to whole pixels that is *no movement*, so `dt` never shrinks. The geometric decay must pass through that window. | Probe E3 reports `quantum === 0`, and the animator frame log shows `dt` frozen with `stuck: true`. |
| **H2** | Nothing cancels a programmatic scroll when the user scrolls, so even a *correct* chase fights the wheel. | No `cancelAnimationFrame` for the chase exists anywhere in `src/`. |
| **H4** | `find.cljs` used `el.scrollIntoView`, which scrolls **every** scrollable ancestor including `#app`. | The scroll tracer records writes to `#app` / `body` / `html`. |
| **H6** | A scroll → re-render → forced-layout re-entrancy saturates the main thread. | The tracer or a profile shows per-scroll `toc/refresh!` passes. |
| **D-mml** | MathJax's screen-reader MathML duplicate is searched, doubling every match inside math and letting the cursor land on an invisible node. | A query present only in the assistive copy reports a non-zero count. |
| **D-node** | Matches confined to one text node miss any phrase crossing an inline element, and nearly everything in a PDF (pdf.js emits one `<span>` per text run). | A multi-word query spanning `<code>`, or spanning two pdf.js runs, reports zero matches. |

---

## 3 · Instruments

Three were built, all DEV-only and all gated on `goog.DEBUG`.

### 3.1 · The scroll tracer — `src/vinary/renderer/scroll_trace.cljs`

Patches `Element.prototype`'s `scrollTop`/`scrollLeft` **setters** and its `scrollTo`/`scrollBy`/
`scrollIntoView` methods, recording every write to a watched scroller into a bounded ring:
`{t, el, op, from, requested, after, moved, clamped, stack}`. Exposed as `window.__vvscrolltrace()` with
`.writers()`, `.clamped()` and `.unmoved()` views.

A prototype patch was chosen over instrumenting the ten known call sites **because the question was "who is
writing?"** — instrumenting only the suspects would have assumed the answer, and could not have implicated a
writer inside pdf.js, CodeMirror, or Chromium's own focus handling.

Recording `after` alongside `requested` is what makes the log diagnostic rather than merely descriptive: a
write whose `requested` never becomes its `after` is a *clamped* write (H1-A), and a write where `after`
equals `from` is a *no-op* write (H1-B). The two hypotheses are distinguishable in one column.

### 3.2 · The animator frame log — `vinary.input.fx/anim-log-entries`

The tracer sees the write but not the chase's own state; `top` and `dt` exist only inside the animator. So
`anim-step!` records `{ct, top, dt, written, after, maxTop, stuck}` per frame, with
`window.__vvscrollanim()` reporting whether a chase is live at all. Outside-in and inside-out.

### 3.3 · Probe E3 — the crux measurement

Pure DOM, no application code, run through the harness:

```js
const c = document.querySelector('.vv-content');
const max = c.scrollHeight - c.clientHeight;
c.scrollTop = max;        const reachedMax = c.scrollTop;   // H1-A: overshoot = max - reachedMax
c.scrollTop = 100;        const base = c.scrollTop;
c.scrollTop = base + 0.4; const q = c.scrollTop - base;     // H1-B: q === 0 ⇒ sub-pixel writes are no-ops
```

### 3.4 · The reproduction harness — `test/find-e2e.js`

Boots the **real** compiled main from inside Electron (the pattern of `test/tree-e2e.js`), so the whole
production chain runs against real files. Two decisions matter:

- The wheel is synthesized with `webContents.sendInputEvent({type:'mouseWheel', …})`, **not** a
  `new WheelEvent(...)`. A synthetic wheel event is untrusted, so Chromium never runs its default action:
  the test would scroll nothing and pass vacuously. Only `sendInputEvent` enters at the browser-process
  input pipeline and takes the compositor path a physical wheel takes — including the offset snapping that
  H1-B depends on. `ASSERT D` ("the synthesized wheel actually scrolls") exists solely to keep the
  stability assertion non-vacuous.
- `Escape` is dispatched **on the find input**, because the real close path is React's `on-key-down` there.
  A window-level dispatch would not reach it and would not reproduce the leak.

Confound recorded rather than hidden: the harness runs with `app.disableHardwareAcceleration()` (matching
the existing smoke). `VV_FIND_E2E_GPU=1` re-runs with acceleration on. Every run prints `dpr` and the
measured quantum, and every assertion is on *behaviour*, never on a hard-coded quantum.

---

## 4 · Procedure

```
compile → run test/find-e2e.js → record E3, the assertion table, the tracer and animator dumps
    ↓
land ONE intervention → re-run → record which assertions changed
    ↓
repeat
```

Interventions were landed one at a time, in the order animator → focus → finder, so each re-measurement is
attributable to a single change.

---

## 5 · Result (pre-fix)

### 5.1 · Probe E3 — H1-A refuted, H1-B confirmed

| Document | `max` | `reachedMax` | **`overshoot`** | **`quantum`** | `dpr` |
|---|---|---|---|---|---|
| Markdown (18 294 px) | 17 418 | 17 418 | **0** | **0** | 1 |
| PDF (8 444 px) | 7 568 | 7 568 | **0** | **0** | 1 |

`overshoot = 0` on both: the integer-derived maximum **is** reachable, so **H1-A is refuted**.
`quantum = 0` on both: writing `scrollTop + 0.4` produces **no movement at all**, so **H1-B holds**.

### 5.2 · The animator frame log — the mechanism, directly observed

Twelve consecutive frames, identical:

```json
{ "ct": 17417, "top": 17418, "dt": 1, "written": 17417.25,
  "after": 17417, "maxTop": 17418, "stuck": true }
```

Read across: the chase wants `17418`, is at `17417`, so `dt = 1`. Its step is `0.25 × 1 = 0.25`, so it
writes `17417.25`. The scroller snaps that back to `17417` — `after == ct`, no movement. `dt` is therefore
still `1` next frame, which is **not** below the `0.5` settle threshold, so the chase re-arms. Forever.

This also explains, exactly, the two constraints from §1:

- **Why the view "moves a few pixels and snaps back":** the wheel scrolls, and the very next animation
  frame writes the scroller back toward the target it is still chasing.
- **Why Page Up / Page Down "work okay":** they do not go through the keymap resolver at all
  (`key-scroll!` is a capture-phase listener that never consults `:in-input?`), so unlike Vim's `j`/`k`
  they survive defect **A**; and each press injects a large fresh `dt`, so visible motion happens before
  the chase re-traps at its residual.

### 5.3 · The scroll tracer — the writer, named

Writers recorded during the 20-frame wheel-stability window:

```json
{ "at vinary$input$fx$anim_step_BANG_ (vinary.input.fx.js:196)": 306,
  "at vinary$input$fx$anim_step_BANG_ (vinary.input.fx.js:198)": 306,
  "at <anonymous>:4:17": 1 }
```

612 writes (two per frame, `scrollTop` and `scrollLeft`) from one function, over ~5 s of an idle page. No
writes to `#app`, `body` or `html` appear anywhere in the log — **H4 is refuted as a cause** (see §8).

### 5.4 · The harness — the baseline

`14 passed, 12 failed`. Failing: `ASSERT A` and `G` (the focus flag), `B` (the chase does not terminate),
`C` (the rubber band), `E` (`/` does not re-open find), `F1` (Enter does not release the keyboard).

---

## 6 · Interventions

Landed in three commits, each re-measured before the next.

### 6.1 · The chase (defects H1-B, H2)

The arithmetic moved into a pure, DOM-free `vinary.input.scroll-math`, with four invariants:

1. **Re-clamp per frame** against the live `scrollHeight`/`clientHeight`, so a document that shrinks
   mid-chase (a PDF rescale, a streaming spacer collapsing) cannot strand the target.
2. **Step floor.** A frame never requests a move smaller than `min-step = 1 px`.
3. **Settle threshold ≥ step floor.** `settle-epsilon` was raised from `0.5` to `1.0`. This is the subtle
   one, and a unit test found it: with a `0.5` threshold and a `1.0` floor there is a gap
   `\|dt\| ∈ [0.5, 1.0)` where the chase is neither settled nor able to take a floored step, because a step
   may not overshoot — the original bug in miniature. `no-gap-between-settling-and-stepping` now asserts
   `settle-epsilon >= min-step` directly, so a future tweak to either constant cannot silently re-open it.
4. **Stall bail-out.** A frame that produced no movement while unsettled ends the chase. This is
   hypothesis-*independent*: it makes non-termination impossible even if 1–3 are wrong.

Plus a hard frame cap (600 ≈ 10 s), cancellation on `wheel`/`touchstart`/`pointerdown`, and
`cancelAnimationFrame` on the orphaned frame when the chase switches elements (which previously left a
second callback chain running, double-stepping).

The accumulating-target design was **kept**. It exists so a held arrow key's OS auto-repeat produces
continuous motion, which per-press `behavior:"smooth"` cannot do — each call restarts the curve. Replacing
the animator would have traded one defect for a regression.

### 6.2 · Input focus (defect A)

`:in-input?` is now **derived** from `document.activeElement` at keydown time rather than mirrored into
app-db by each component. A cached focus flag is leak-by-construction, and five components carried the same
latent leak. The app-db copy survives for display and for the palette, maintained by one document-level
`focusin`/`focusout` tracker and self-healed on every keydown.

The find bar additionally blurs before closing and restores focus to `.vv-content` (which gained
`tabindex="-1"` — that also repaired `:focus/content`, until now a silent no-op because a plain `<div>`
cannot take focus). `preventScroll: true` on every such `.focus()` call: without it the focus itself
scrolls the pane, which would have made *closing find* move the reader's position — a new scroll writer
introduced by the fix for a scroll bug.

### 6.3 · The finder (defects D-node, D-mml, and the design gap)

Matching was rewritten around a flattened buffer (`vinary.renderer.find-scan`, pure and unit-tested): the
content pane is walked into a token stream, folded into one whitespace-normalized string plus a
buffer-index → (node, offset) segment table, scanned once, and mapped back into **multi-node** Ranges.
Block boundaries become `\n` in the buffer and a normalized query is trimmed, so a match can span a wrapped
line or a pdf.js `<br>` but never a paragraph, a table cell, or a diff column.

The reject list excludes `mjx-assistive-mml` **by tag name**. This is the one content-specific entry in the
subsystem, and it is unavoidable: MathJax hides that duplicate with `clip`, not `display:none`, so it has
real layout boxes and passes every generic visibility test. (`renderer.core` already strips the same
element from copied selections, for the same reason.)

Scrolling to a match now goes through the shared confined `scroll/confined-top`, targeting the **Range's**
rect rather than its parent block, and never `el.scrollIntoView`.

**The design gap.** Under a modal keymap, `n`/`N` were *structurally unreachable*: they are bare printable
characters, so with the query box focused they were typed into it, and the only way out — `Escape` — closed
find and cleared the highlights. There was no state in which a Vim user could press `n`. Enter now
**commits** under a modal keymap (keeping the bar and the highlights, returning the keyboard to normal
mode) and keeps its browser meaning of "next match" under a non-modal one, where the cycle keys are `F3` /
`Shift+F3` and reach the resolver regardless of focus.

---

## 7 · Re-measurement (post-fix)

| Stage landed | Harness result | What it establishes |
|---|---|---|
| baseline | **14 passed, 12 failed** | every reported symptom reproduces |
| + the chase (§6.1) | **20 passed, 6 failed** | `ASSERT B`/`C` flip on Markdown **and** PDF while `ASSERT D` still passes — the rubber band is fixed and the test is not vacuous. The remaining failures are exactly the focus ones. |
| + input focus (§6.2) | **26 passed, 0 failed** | `/`, `n`, `N` reachable again; `:in-input?` false after close. Under `default`, `Ctrl+F` passed at every stage — the keymap-specificity predicted in §2 |
| + the finder (§6.3) | **47 passed, 0 failed** | cross-node matching on Markdown and PDF, painted-count agreement, cycling on screen, no ancestor scrolled, assistive-MathML excluded, re-open re-runs, the debounce race closed |

The final animator log settles in a handful of frames with `reason: "settled"` and no `stuck` entry.

---

## 8 · Refuted, rejected, and self-inflicted

Recorded so they are not re-attempted.

| Claim | Prediction | Measurement | Verdict |
|---|---|---|---|
| **H1-A** unreachable `max-top` | E3 `overshoot > 0.5` | `overshoot = 0` on both documents | **Refuted** |
| **H4** `scrollIntoView` scrolls `#app`/`body` | tracer records writes there | zero such writes; `html, body` and `#app` are `height:100%; overflow:hidden`, so `scrollHeight == clientHeight` and the ancestor walk has nothing to move | **Refuted as a cause.** The call was still replaced — it scrolls *inner* `<pre>`/table scrollers, and it targeted the wrong element (§6.3) |
| **H6** scroll → re-render → forced-layout re-entrancy | tracer/profile shows `toc/refresh!` per scroll | `markdown-body`'s did-update only refreshes when `html`/`path` changed; a heading change re-renders the sidebar only | **Refuted** |
| Fix by dispatching `[:input/set-in-input false]` from `:find/close` | fixes the symptom | falsified by inspection: it misses the second unmount path (`:find/toggle`) and actively *lies* when find is closed while focus is elsewhere | **Rejected** |
| Replace the animator with native `behavior:"smooth"` | held-key auto-repeat stays smooth | contradicts the documented reason the chase exists — each call restarts the curve | **Rejected** |
| Vim is at fault for symptoms 3 and 4 | a different keymap would not show them | the defect is keymap-independent; Vim is simply the only set whose find and scroll bindings are *single unmodified characters*, which is the class the leak swallows | **Refuted** |

### 8.1 · A self-inflicted regression, caught by the gate

The first version of the rewritten walk called `Element.checkVisibility()` on every element. On the
existing smoke's 7 005-record streamed log that forced layout tens of thousands of times, and the whole
Electron smoke went from passing to hitting its 240 s hard timeout — hanging precisely in the *streamed
find* assertion.

The fix folds visibility and boundary classification into **one** `getComputedStyle` resolution, memoized by
element *shape* (`parentTag|parentClass|tag|class`) rather than identity, so the number of style resolutions
is `O(#distinct shapes)` — a handful for a log or a split diff — instead of `O(#elements)`. The suite went
from a 240 s timeout back to **1 m 49 s**.

Recorded because it is the most valuable negative result here: a correctness rewrite of a subsystem that
walks the whole DOM is a *performance* change whether or not it is intended as one, and the existing
streaming gate is what caught it.

---

## 9 · Regression guards

| Guard | Where | Holds |
|---|---|---|
| `no-gap-between-settling-and-stepping` | `test/vinary/input/scroll_math_test.cljs` | `settle-epsilon >= min-step` — the structural invariant |
| `sub-pixel-steps-never-stall` | same | no unsettled frame requests a sub-pixel move |
| `chase-terminates`, `chase-survives-a-shrinking-document`, `chase-bails-out-when-it-cannot-move`, `chase-has-a-hard-frame-cap` | same | termination, against a *pixel-snapping* model scroller |
| `matches-cross-node-boundaries`, `matches-never-cross-a-block-boundary` | `test/vinary/renderer/find_scan_test.cljs` | the flattening is both permissive and bounded |
| `safe-lower-preserves-length` | same | lower-casing cannot desynchronise buffer indices from node offsets |
| `ASSERT A`–`G` | `test/find-e2e.js` | the four reported symptoms, on Markdown **and** PDF, under **vim** and **default** |
| `ASSERT H1`–`H11` | same | match correctness: cross-node, painted-count agreement, on-screen cycling, no ancestor scrolled, re-open re-runs, the debounce race, assistive-MathML exclusion |
| DEV scroll tracer absent | `test/electron-smoke.js` (release run) | the `Element.prototype` patch can never ship |
| streamed-log find | `test/electron-smoke.js` | the walk stays fast enough for a 5.6 MiB document |

Run with `npm run test:find-e2e`, `npm test`, `npm run test:electron`, `npm run test:electron:release`.

---

## 10 · See also

- [ADR-0032 — Scroll ownership and derived input focus](../design-decisions/0032-scroll-ownership-and-derived-input-focus.md)
- [Feature 05 — In-page find](../features/05-in-page-find.md)
- [Theory 06 — In-page find with the CSS Custom Highlight API](../theory/06-find-css-custom-highlight.md)
- [07 — Measurement methodology](07-measurement-methodology.md)
- [08 — The daemon window-lifetime crash experiment](08-daemon-window-lifetime-experiment.md) — the same
  instrument-then-intervene shape, and the source of the "record the negative result" convention.
