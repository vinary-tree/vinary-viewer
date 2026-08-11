# 0043 — A width-adaptive Split default for diff layouts

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** vinary-viewer maintainers

## Context

Diffs render Unified or side-by-side Split per tab ([ADR-0026](0026-diff-rendering-side-by-side-and-repo-filetypes.md),
menu placement per [ADR-0037](0037-collapsible-diff-file-previews.md)). The default was
unconditionally Unified: `:diff-view` nil meant `:unified`, and ADR-0026 recorded the invariant
that Split is opt-in *so opening a diff never triggers the split build's on-disk/rev source
resolution*. In practice a wide window is exactly where Split is the better reading — two aligned
readable columns — and asking the user to pick it for every diff tab is friction with no upside,
while forcing Split in a narrow pane wraps nearly every line and reads worse than Unified.

The requested behavior: **default to Split whenever the window's width allows it, Unified
otherwise** — resolved *reactively* (a user decision): an unchosen diff follows the pane width as
it changes; an explicit pick stays sticky. This deliberately supersedes the opt-in invariant — the
cost it guarded (a source read on open) is accepted where the wide layout is the right default.

Nothing in the renderer knew the content-pane width: no resize listener wrote app-db anywhere (the
PDF fit machinery reads `clientWidth` raw and stores only its derived scale).

## Decision

**Mirror the `.vv-content` width into app-db once, make the pure default-resolution fn take a
`wide?` input, and funnel "the shown diff should be Split but isn't built" through one idempotent
ensure event.**

### The width mirror

One debounced `ResizeObserver` on the **identity-stable** `.vv-content` node (it is reused across
document switches) mirrors `clientWidth` into `[:ui :content-width]` (nil until the first
report). Wiring rules that matter:

- The `:ref` is a **top-level named fn** (`views/content-width-ref`) — an inline closure would
  change identity every render and React would re-fire `ref(nil)/ref(node)` each time, churning
  the observer. The observer instance lives in a `defonce` atom, so a hot reload never leaks a
  second one.
- The first measure is **immediate** (no debounce blind spot for a document opened straight into a
  wide window); subsequent reports coalesce through the shared scheduler at the `pdf.cljs`
  `observe-resize!` budget (120 ms). The `:ui/content-width` handler value-guards, so coalesced
  repeats are free, and projecting the width through the boolean `:ui/split-wide?` sub dedups
  per-pixel churn before it can re-render `content-view` or the toolbar.

### The threshold — `nav/split-min-content-width` = 1000 CSS px

Derived from the shipped styles: the pane's 45 px side padding (×2 = 90) + the split row grid's
two `3em` line-number gutters at the 13 px code size (= 78) + grid borders (≈3) ≈ **171 px of
fixed chrome**, so 1000 px leaves ≈ 414 px ≈ **53 monospace columns per side** — just above the
~50-column floor where the `pre-wrap` sides stop wrapping typical ≤72-column diff lines. Precedent:
VS Code's `diffEditor.renderSideBySideInlineBreakpoint` defaults to **900 px of editor width**;
this pane carries ~100 px more chrome, so 1000 yields the same columns-per-side. `clientWidth` is
CSS px, which webFrame zoom scales — zooming in narrows the measured width and falls back to
Unified with no special handling. (Incidentally, the 1000 px electron-smoke window keeps every
pre-existing diff arm deterministically below the threshold.)

### The pure seam

`nav/effective-diff-view` becomes 2-arity `(tab-view wide?)` → `(or tab-view (if wide? :split
:unified))`; `nav/split-wide?` maps nil → false (pre-measure default stays Unified). The sub
`:ui/active-diff-view` composes `:ui/active-tab` with `:ui/split-wide?`; `content-route/route` is
**unchanged** — it still shows `:diff-split` only when the effective view is `:split` *and*
`:doc/diff-split-html` exists, so a fresh wide diff paints Unified for the instant the build takes
and then flips (accepted behavior).

### The ensure funnel

One guarded event, `:diff/ensure-split` — shown facet is a diff ∧ effective view `:split` ∧
`:doc/text` present ∧ split not built → `:diff/build-split` (whose `:diff/split-ready` reply was
already stamp-gated). Exactly **two** dispatch sites cover every trigger:

- the unified diff surface's **mount** (`views/diff-unified-body`, the `:blame/source-mounted`
  pattern): the `:diff` route arm is keyed by path+stamp, so open, tab switch, history
  Back/Forward, focus-existing, close-reveal, live refresh, and facet flips all remount it — no
  per-navigation-event enumeration, and a `:tab/activate` to an already-loaded diff (which fires
  no `:content/received`) is covered for free;
- `:ui/content-width` — the live crossing while mounted (window resize, the sidebar splitter,
  the sidebar toggle, zoom).

### Semantics kept sticky

`:tab/set-diff-view` (explicit picks) is untouched; `:tab/toggle-diff-view` flips off the
width-aware **effective** view, so toggling an unchosen wide diff writes explicit `:unified` — a
toggle always produces a sticky choice. The combo caret's check-mark reads the effective view: an
unchosen wide diff shows Split checked with `:diff-view` still nil. There is deliberately no
third "Auto" menu row — nil-until-chosen *is* auto, and the two rows keep their pick semantics.

## Consequences

- `git diff | vv -t diff` (and every commit diff) opens side-by-side in a maximized window and
  Unified in a half-screen tile, with zero configuration; dragging the sidebar splitter or
  toggling the sidebar across the threshold flips an unchosen diff live in both directions.
- ADR-0026's "split is opt-in / opening a diff never reads source content" invariant is
  **amended**: an unchosen wide diff now triggers the split build (and its rev-aware/on-disk
  enrichment) on open — the accepted cost of the right default.
- `[:ui :content-width]` is the renderer's first reactive width state; anything else that later
  needs pane-width awareness should read it (or `:ui/split-wide?`-style projections) rather than
  adding a second observer.
- Unit tests pin the threshold constant, `split-wide?`'s nil/edge behavior, and the 2-arity
  resolution table; a release-safe electron-smoke section drives the real chords and window
  resizes: auto narrow → wide → narrow, the 1120 px sidebar-toggle straddle, and explicit-pick
  stickiness in both directions.

## Alternatives considered

- **Fixed-at-open resolution** (measure once when the diff opens): rejected by user decision —
  "so long as the width allows" is a live condition; a diff opened narrow then maximized should
  become Split without a manual toggle.
- **A `window resize` listener writing app-db**: observes the wrong box — the content pane's
  width also changes with the sidebar splitter/toggle at constant window size; the
  `ResizeObserver` on `.vv-content` sees every cause.
- **Reading `clientWidth` at each decision point** (the pdf.cljs/hints read-at-use precedent):
  rejected because the decision must *re-evaluate on width change* — read-at-use has no
  change signal, and `content-route/route` is pure and must stay so; a mirrored value with an
  observer is the minimal reactive form.
- **An "Auto" third state in the menu**: rejected as scope/noise — nil already means auto, the
  checkmark shows the effective result, and an explicit pick remains one gesture.
