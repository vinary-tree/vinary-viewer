# 0032 — Scroll ownership, derived input focus, and cross-node find

- **Status:** Accepted
- **Date:** 2026-07-24
- **Deciders:** Vinary Tree (maintainer)

## Context

A user report of four symptoms — highlights that looked wrong, match navigation that did not work, a
scroll that "moves a few pixels and then snaps back" after closing the find bar, and a find shortcut that
stopped responding — resolved into **three independent defects and one design gap**. The full
instrument-measure-intervene record, with the raw numbers and the refuted hypotheses, is
[scientific/09 — The in-page-find and scroll-ownership experiments](../scientific/09-in-page-find-and-scroll-experiments.md).
This ADR records only the decisions that outlive that bug.

Three pre-existing conditions made the defects possible, and each is a *class* of problem rather than an
incident:

1. **Scroll had no owner.** Five subsystems wrote `.vv-content`'s scroll position. Two of them
   (`:toc/scroll`, the source→preview jump) had independently discovered that `el.scrollIntoView` scrolls
   *every* scrollable ancestor and had each hand-rolled the same confined-`scrollTo` formula, with a
   comment explaining why. In-page find had not, and still called `scrollIntoView`. A sixth writer, the
   keyboard scroll animator, ran a `requestAnimationFrame` chase with no cancellation path at all.

2. **Focus was cached.** Six components mirrored "is a text field focused?" into app-db through
   `:on-focus`/`:on-blur` pairs. Chromium fires no `blur` for an element that is **removed** while
   focused, so any component that could unmount mid-focus leaked a permanently-`true` flag — which the
   keymap resolver reads to decide whether a bare printable key belongs to the document or to a text box.

3. **Find matched within single text nodes.** Rendered markup breaks text at every inline element, and
   pdf.js emits one `<span>` per text run, so a large fraction of ordinary queries could not match at all.

## Decision

### 1 · One scroll owner, one formula, one animator

Every programmatic scroll of the content pane goes through `vinary.renderer.scroll`:

- **`confined-top`** is the single, pure offset formula (`:start` / `:center` / `:nearest`, with a margin),
  unit-tested in `test/vinary/renderer/scroll_test.cljs`. `:toc/scroll` and `scroll-preview-to-line!` now
  call it with `:block :start :margin 0`, which reproduces what each carried inline — behaviour-preserving
  by construction. In-page find calls it with `:block :center`.
- **`el.scrollIntoView` is not used on content.** The rule the `:toc/scroll` comment stated for itself is
  now a project-wide invariant. Measurement refined the reason: `html`, `body` and `#app` are all
  `height:100%; overflow:hidden`, so their `scrollHeight == clientHeight` and the ancestor walk cannot in
  fact move the app chrome. What it *can* move is an inner `<pre>`, `<table>` or math scroller inside the
  document — and it scrolls the match's *parent block* rather than the match.
- **The eased chase is cancellable and provably terminating.** Its arithmetic lives in the pure
  `vinary.input.scroll-math`; user `wheel`/`touchstart`/`pointerdown` abandons it, as Chromium abandons its
  own `behavior:"smooth"` scrolls.

The chase itself was **kept**, not replaced with native smooth scrolling: its accumulating target is what
makes a held arrow key's OS auto-repeat produce continuous motion, which per-press `behavior:"smooth"`
cannot do because each call restarts the curve.

Four invariants make it terminate, and each is an assertion rather than a comment:

| Invariant | Why |
|---|---|
| Re-clamp the target against the **live** maximum every frame | the document can shrink mid-chase (a PDF rescale, a streaming spacer collapsing) |
| Never request a move smaller than `min-step = 1 px` | **measured:** this scroller snaps offsets to whole pixels, so a sub-pixel write moves nothing and the remaining distance never shrinks |
| `settle-epsilon >= min-step` | otherwise there is a gap where the chase is neither settled nor able to take a floored step — the original bug in miniature |
| Stop when a frame produced no movement while unsettled | hypothesis-*independent*: non-termination becomes impossible even if the reasoning above is wrong |

### 2 · Input focus is derived, never cached

`:in-input?` is computed from `document.activeElement` at keydown time, by one shared predicate
(`vinary.renderer.text-edit/keyboard-owner?`) that both the resolver and `key-scroll!` use. The app-db copy
survives for display and for the command palette, maintained by a single document-level `focusin`/
`focusout` tracker and self-healed on every keydown; the six per-component mirrors are retired.

Rejected: adding `[:dispatch [:input/set-in-input false]]` to `:find/close`. It patches one of five leak
sites, misses the second unmount path (`:find/toggle`), and actively *lies* when find is closed while
focus is somewhere else — closing find from the palette while typing a path in the URI bar would tell the
resolver the keyboard was free.

`.vv-content` gained `tabindex="-1"` so there is somewhere to hand the keyboard back to. That also repaired
`:focus/content`, which had been a silent no-op because a plain `<div>` cannot take focus. Every such
`.focus()` passes `preventScroll: true` — without it, focusing a scroller scrolls it, and the fix for a
scroll bug would have introduced a new scroll writer.

### 3 · Find matches across nodes, and only visible text

Matching flattens the content pane into one whitespace-normalized buffer plus a buffer-index → (node,
offset) segment table (`vinary.renderer.find-scan`, pure and unit-tested), scans it once, and maps matches
back into **multi-node** Ranges. The CSS Custom Highlight API already supported those; nothing downstream
changed. Painting still mutates no DOM (ADR-0003), which has a second payoff here: `CSS.highlights.set` is
not a mutation, so the `MutationObserver` that detects a changed document cannot be re-triggered by
painting.

Block boundaries become `\n` in the buffer and a normalized query is trimmed, so a match may span a wrapped
line or a pdf.js `<br>` but never a paragraph, a table cell, or a diff column.

One content-specific exclusion is unavoidable and is named rather than inferred: **`mjx-assistive-mml`**,
MathJax's screen-reader MathML duplicate of every equation. It is hidden with `clip`, not `display:none`,
so it has real layout boxes and passes every generic visibility test; without naming it, every equation's
text matched twice and cycling landed on an invisible node. (`renderer.core` already strips the same
element from copied selections, for the same reason.)

### 4 · Under a modal keymap, Enter commits the search

Vim binds `n`/`N` to next/previous match — bare printable characters. With the query box focused they were
typed into it, and the only way out, `Escape`, closed find and cleared the highlights: **there was no state
in which a Vim user could press `n`.** Enter now commits under a modal keymap (the bar and highlights stay,
the keyboard returns to normal mode) and keeps its browser meaning of "next match" under a non-modal one,
where the cycle keys are `F3` / `Shift+F3` and reach the resolver regardless of focus.

## Consequences

**Good.**

- The class of bug in (2) is eliminated, not patched: there is no cached focus state left to go stale.
- Scroll behaviour is testable. Termination is now four unit-test properties over a model scroller that
  snaps to whole pixels, instead of something only reproducible by scrolling a real document by hand.
- `:focus/content` works for the first time.
- Find covers phrases it never could before — most visibly in PDFs, where nearly every multi-word query
  previously found nothing.

**Costs and risks.**

- The walk now resolves computed style. It is memoized by element *shape*, so the number of resolutions is
  `O(#distinct shapes)` rather than `O(#elements)` — but this is a real cost, and getting it wrong once
  already pushed the Electron smoke past its timeout on a 7 005-record streamed log (scientific/09 §8.1).
- Deciding block boundaries from computed `display` needs an escape hatch for CSS *blockification*: an
  absolutely-positioned `<span>` computes to `display:block`, and pdf.js text runs are exactly that. The
  rule is one line, but it is subtle, and both worked examples (pdf.js runs, split-diff grid cells) are
  written down beside it.
- A DEV-only tracer patches `Element.prototype`'s scroll accessors. It is gated on `goog.DEBUG`, and the
  **release** smoke asserts `window.__vvscrolltrace === undefined` rather than trusting the optimizer.

## See also

- [scientific/09 — The in-page-find and scroll-ownership experiments](../scientific/09-in-page-find-and-scroll-experiments.md)
- [ADR-0003 — ref-`innerHTML` body, no VDOM](0003-ref-innerHTML-no-vdom-body.md) — why find paints over
  Ranges instead of wrapping matches
- [Feature 05 — In-page find](../features/05-in-page-find.md), [Theory 06 — In-page find with the CSS
  Custom Highlight API](../theory/06-find-css-custom-highlight.md)
