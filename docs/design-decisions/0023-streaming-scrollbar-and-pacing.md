# 0023 — Pre-estimated streaming scrollbar + rAF pacing

- **Status:** Accepted
- **Date:** 2026-07-08
- **Deciders:** Vinary Tree (maintainer)

## Context

The document streaming pipeline ([ADR-0018](0018-document-streaming-pipeline.md)) commits a large document into
the DOM progressively. Users reported it felt "slow and clunky", with two concrete problems:

1. **The scrollbar did not represent the whole document.** The streamed body appended blocks with no pre-sized
   container, so `.vv-content` `scrollHeight` equalled only the *committed-so-far* content and **grew as content
   streamed in** — the thumb jittered, scroll position was meaningless, and scroll-to-end was impossible
   mid-stream. (Symptom: the scroll re-anchor had to defer across three animation frames because the target
   height "did not exist yet".)
2. **Commits dripped up to 100 ms apart.** The scheduler paced on `requestIdleCallback` with a 100 ms timeout;
   under main-thread load (or a backgrounded window) batches arrived 100 ms apart — the visible stutter.

The in-renderer PDF view already solved the isomorphic problem: it pre-sizes every page placeholder so the
scrollbar matches the whole document from the first frame ("preallocates exact height → no scroll jank").

## Decision

### Whole-document scrollbar via a pre-estimated sibling spacer

Extract the PDF view's pure geometry into a shared, DOM-free **`renderer/virtual_layout.cljs`** (content-agnostic
`stack` / `total` / `visible-range` / `est-heights` / `extrapolate-total` / `spacer-height` / `pads` /
`band-range`); `pdf_layout` now delegates to it. The streamed body gains a **trailing spacer** — a
zero-width, `visibility:hidden` **sibling** of `.markdown-body` (never a child, so the byte-parity innerHTML
capture stays clean) whose height pads the rendered body up to the estimated whole-document height:

```
estimatedTotal = renderedHeight / progress          (extrapolate-total, floored at renderedHeight)
spacerHeight   = max(0, estimatedTotal − renderedHeight)
scrollHeight   ≈ renderedHeight + spacerHeight = estimatedTotal
```

`renderedHeight / progress` is ≈ **invariant** across batches (both grow together), so `scrollHeight` stays
**stable** from the first batch and only refines — the scrollbar reflects the whole document immediately, with
no jitter, and scroll-to-end is reachable mid-stream. A `ResizeObserver` on the body keeps the estimate honest
as height settles (late web fonts, Mermaid/syntax post-passes, pre-sized figures). Works for both engines:
progressive Markdown/PDF-reflow (whose progress = committed/total) and logs (progress = bytesRead/size).

> Verified: mid-stream, a body of ~7,000 px was padded by a ~184,000 px spacer to the estimated whole-document
> height — the scrollbar reflected the whole doc, not the streamed prefix (`test/electron-smoke.js`).

### Steady pacing via requestAnimationFrame

The scheduler's commit pump (`ric`) now schedules on **`requestAnimationFrame` when the window is visible** — a
steady ~60 fps cadence that eliminates the idle-starvation gaps — and falls back to `requestIdleCallback(timeout)`
only when the window is **hidden** (where rAF is paused by the browser), so a backgrounded stream still
progresses. The find-materialize fast path (`rush?`) is unchanged.

### DOM windowing / region buffering

The user's "buffer content around the viewport (up to ~5 MB)" is delivered by the **existing
`content-visibility:auto`** windowing on `.vv-streamed > *`: the browser skips layout + paint for off-screen
blocks while **all** committed content stays buffered in the DOM — so scrolling is smooth (no re-fetch, no blank
gaps) and render cost is bounded. The Phase-1 spacer makes the scrollbar accurate on top of that.

DOM-node **eviction** (removing off-band nodes) was deliberately **not** added: it would *reduce* the buffer
(contrary to "buffer up to 5 MB"), trade smooth scrolling for a re-materialization blank on scroll-back, and its
only benefit — bounding node count for multi-tens-of-MB documents — is the same extreme-document memory case as
the huge-log main-process seeking that was explicitly out of scope.

## Consequences

- The scrollbar matches the whole document and true position from the first paint; no jitter; scroll-to-end
  works mid-stream. Commits arrive at a steady frame cadence instead of dripping 100 ms apart.
- Byte-parity (streamed == batch), the scroll re-anchor, find-materialize, and the content-visibility windowing
  are all preserved (the spacer is a sibling; the pump change is timing-only).
- **Files:** `renderer/virtual_layout.cljs` (new), `renderer/pdf_layout.cljs` (delegates), `ui/views.cljs`
  (`ir-stream-body` spacer + `size-spacer!` + ResizeObserver), `stream/scheduler.cljs` (`ric` rAF pump),
  `resources/public/css/app.css` (`.vv-stream-spacer`). Tests: `test/vinary/core_test.cljs`
  (`virtual-layout-helpers`), `test/electron-smoke.js` (mid-stream spacer + sibling assertions).
