# 0033 — Asynchronous text input, one scheduler, one search model

- **Status:** Accepted
- **Date:** 2026-07-27
- **Deciders:** Vinary Tree (maintainer)

## Context

A user reported that typing into the in-page find widget lagged by "a few hundred milliseconds" per
character and that "if I type too quickly some keys are missed", and asked that **all** search bars and
text-input widgets become asynchronous. The full instrument-measure-intervene record, with the raw numbers
and the refuted hypotheses, is
[scientific/10 — The text-input latency experiments](../scientific/10-input-latency-experiments.md). This
ADR records only the decisions that outlive that bug.

Measurement separated the report into **two independent defects with different mechanisms, in different
widgets** — and, importantly, showed that neither is fixed by fixing the other:

- **Late characters.** In-page find rebuilt the entire flattened document buffer — full `TreeWalker`, a
  `getComputedStyle` per distinct element shape, a per-character case fold — on every debounce landing,
  *even when the DOM had not changed*. Measured at **450 ms of blocked main thread per keystroke** on a
  1.1 MB document, with a 480 ms worst-case wait for a character to appear.

- **Missing characters.** Four fields were *controlled* React inputs whose `:value` came from a
  subscription, so the character the browser had just inserted was authoritative only until React's next
  commit. Typing `views` into the file-tree filter produced `vews`; typing `Noto Sans` into a Preferences
  font field produced `Not Sans`. The tracer caught the writer with its own before/after values
  (`"vi"` → `"v"`, `"Noto"` → `"Not"`).

Three pre-existing conditions made this possible, and each is a *class* of problem rather than an
incident:

1. **Deferral had no owner.** Four separate idioms existed, each re-derived where it was needed:
   `:dispatch-later` plus a generation counter (find), a rAF-or-idle tick (streaming), a boolean latch
   plus one rAF (scroll-spy, in three copies), and clear-timer/set-timer (settings, URI completion). They
   answer three questions — when to start, how long to run, how to stop — and every widget needing all
   three assembled its own answer. Only one of them could express cancellation.

2. **Model computation lived in render functions.** The file tree re-folded every path of every open
   project inside its render; the palette allocated one map per file across all projects *before*
   filtering any of them. A render function is the one place a result cannot be memoised — and, per the
   measurement, a slow render is precisely what lets a commit land between two keystrokes.

3. **Five hand-rolled matchers.** In-page find (substring over a flattened buffer, length-preserving
   fold), the file tree (`str/includes?` on a lower-cased path), the palette (a private subsequence loop),
   the URI bar (prefix), and the terminal finder (per-line substring). None was wrong for its widget; each
   was welded to it, so changing the semantics anywhere meant writing a sixth.

## Decision

### 1 · A text field owns its own value

`vinary.ui.text-input/async-input` replaces the controlled `<input>` in every widget whose value is
model-driven: the find bar, the file-tree filter, the command palette, and the Preferences text and number
fields.

A local draft shadows the model while the user is editing, so React's committed value always equals what
the DOM already holds and a commit can never subtract a character.

**Knowing when to stop shadowing is the whole of the subtlety**, and getting it wrong reintroduces the
defect. Comparing the model against the draft is not sufficient: while typing the model *always* trails,
and treating that as an external push would drop the draft mid-word. So the component keeps the values it
has published but not yet seen echoed back — a model change matching one of them is its own echo; one
matching none is genuinely external (`:find/reset`, `:palette/open`, a tab switch) and the draft is
abandoned. `reconcile` is pure and unit-tested against exactly that case.

**Contract for callers**, because both halves are load-bearing:

- `on-change` fires synchronously with the raw value and must be *cheap* — store the string, nothing more.
- `on-change` must store the value **verbatim**. A handler that normalises would break the echo test: its
  write would look external. Normalise on read.

**Why this cannot be solved by making the app faster.** The clobber needs the widget's own render to be
slow enough that a keystroke lands mid-render. A faster widget only shrinks the window; it does not close
it. The find bar is five elements and never clobbered *even while a 450 ms search blocked the thread* —
which is the same fact from the other side. Local ownership makes the failure impossible rather than
unlikely.

### 2 · One scheduler, and two paces that are not interchangeable

`vinary.async.scheduler` supplies four keyed primitives — `debounce!`, `coalesce!`, `slice!`, `cancel!` —
and absorbs all four previous idioms.

`debounce!` and `coalesce!` are complements, and the difference is which end of a burst wins: a debounce
fires *after* it with the last value (right for a query still being typed); a coalesce fires *during* it,
at the next frame, reading the latest state itself (right for scroll and selection handlers). The
boolean-latch-plus-rAF pattern that had been written out three times is now the latter. `ric` moved
here from `vinary.stream.scheduler`, which now requires it back.

The invariant it exists to supply:

> No task started from a keystroke occupies the main thread for longer than `budget-ms` (default 8)
> without yielding, and re-arming a key cancels whatever that key had in flight.

**Two yielding primitives, and choosing between them is a real decision, not a detail:**

| | paces to | correct for |
|---|---|---|
| `ric` (rAF, or idle when hidden) | the **display** | committing DOM as a document streams in — there is no point producing frames faster than the screen shows them |
| `yield!` (a `MessageChannel` post) | the **input queue** | computation — let the browser deliver a keystroke, then resume at once |

Using `ric` for computation caps throughput at one slice per frame, and animation frames are throttled to
~1 Hz for an occluded window. Measured: the chunked find took 380 ms to settle on a 4 kB document and
never finished at all on a 1.1 MB one. This is recorded because the mistake is natural — `ric` was the
tick that already existed.

`debounce!` keeps **one live timer per key**, replaced on each arming. The pattern it replaces —
schedule a timer per keystroke and let the stale ones fire into a generation check — is observationally
similar but leaves one timer per character alive and cannot express cancellation at all. The generation
counter in find *stays*, because it answers a different question: an in-flight search that has already
begun cannot be un-begun, and its reply must still be recognised as stale when it lands.

### 3 · In-page find is incremental, and flattening is not cancelled by a query change

`docs/theory/06` already asserted the property this rests on — *the buffer depends only on the DOM, not on
the query* — and nothing acted on it. The flattened buffer is now reused while the same document, the same
content element, and no intervening mutation hold. Measured: **304 ms → 9 ms** per keystroke on an 808 kB
document, a 34× reduction.

**Two scheduler keys, not one**, and this is the non-obvious part. Flattening is query-independent, so a
new query must *not* cancel it: under one key, a 150 ms typing cadence against a ~300 ms flatten meant
every character discarded a partly-finished walk and started another, so the buffer was never committed
at all while the user typed. Matching *is* query-specific and is cancelled immediately.

**A mutation counter, not a dirty flag.** The buffer and the Ranges each carry the count they were built
at, so each is judged stale on its own — and a mutation arriving *during* a build leaves its result usable
but not reusable, which a shared boolean cannot express and which is what stops a continuously mutating
document from looping forever on rebuild-and-discard.

`rush?` in the stream scheduler — find's "make the whole document searchable first" hook — now means
*larger batch, still one batch per yield* rather than *no yield at all*. Recurring straight into the drain
committed every remaining block in a single task: a multi-second freeze triggered by the first character
typed into find.

### 4 · One search model, with the semantics as a parameter

`vinary.search.{query,match,scan,cursor,config}` replaces the five matchers.

- **`query`** — two folding strategies with names. `:strict` is length-preserving and is **required**
  wherever a returned index maps back to a source position (the find buffer's indices map to DOM text-node
  offsets; the terminal finder's columns map to raw byte offsets). `:simple` is correct where they do not.
  That distinction was previously load-bearing and undocumented, decided by which file the code lived in.
- **`scan`** — the non-overlapping `indexOf` loop that existed verbatim in two places, plus the predicates.
- **`cursor`** — the wrapping match cursor, previously written twice with different empty-list behaviour.
- **`match`** — mode-dispatched, returning one shape: `nil` or `{:score :spans}`. Five modes:
  `:substring`, `:subsequence`, `:prefix`, `:word-prefix`, `:regex`.
- **`config`** — the single place a mode is chosen.

**The defaults reproduce exactly what each widget did before**, so this lands as de-duplication with no
user-visible change; a refactor and a behaviour change landing together is a refactor whose behaviour
change cannot be reviewed. Changing the file tree to fuzzy matching, or ranking the palette by match
quality, is now one keyword against a tested model, and `config/mode-for` already consults
`[:ui :settings :search-modes]` so a settings toggle is a UI addition rather than an engine change.

**Recorded as foreclosed:** incremental narrowing of a match set is **unsound**, because a non-overlapping
match set is not a superset of the starts of any query extension. In `"aaab"`, scanning `"aa"` yields
`{0}`; `"aab"` matches at `1 ∉ {0}`. Reuse must happen at the buffer level — which is the cheaper place
anyway, since scanning is native and building the buffer is not. The counterexample is pinned as a test.

### 5 · Model computation leaves render functions

The file tree's fold-and-filter (`vinary.app.tree-model`) and the palette's candidate assembly
(`vinary.app.palette`) are pure namespaces behind layer-3 subscriptions, recomputing only when their
inputs change. The palette's `:file` source now ranks raw path strings and materialises an item only for
survivors, so the cap bounds allocation rather than merely truncating the display.

`:facet/active` and `:view/switch` are layered on a cheap inputs slice instead of being plain db-subs:
each was running a DataScript `d/q` plus a 22-attribute `d/pull` on *every app-db write*, and both are
permanently subscribed. This mirrors the `::keymaps-slice` fix the same file had already applied once, for
the same reason.

### 6 · Live preview is debounced, not removed

Preferences applies a font change 150 ms after typing stops rather than per character. `:fonts/apply`
re-measures every figure and Mermaid diagram on screen when a size changes; doing that between two
keystrokes is what made those fields drop characters. The preview is kept — it simply lands once per pause.

## Consequences

**Positive.**

- Worst keystroke-to-response wait on a 1.1 MB document: **480 ms → 75 ms**. Per-keystroke search CPU:
  **450 ms → 5.3 ms**.
- No character is lost in any widget at any measured cadence (150 / 40 / 25 ms per character).
- Four deferral idioms became one, with cancellation available for the first time.
- Five matchers became one model whose semantics are a parameter and which is unit-tested per mode.
- Two subscriptions stopped querying DataScript on every keystroke anywhere in the app.

**Costs and limits, stated plainly.**

- **Filter lists trail by ~90 ms.** The tree and palette commit their query after a pause. The *field*
  never waits; the list does. This is the standard trade and it is the reason the field had to own its own
  value first — with a local draft the delay is invisible, without one it would be the defect.
- **A field's model may lag its DOM value.** Anything reading `[:ui :find :query]` or
  `[:ui :tree-filter]` synchronously during a burst sees a value one or more characters behind. This is
  inherent to the design, not a bug, and is why `on-change` must store verbatim.
- **A superseded job reports nothing.** `slice!` callbacks never fire for a cancelled run, so callers must
  not treat a missing callback as an error.
- **The URI bar keeps its bespoke draft.** Its completion filter depends on that draft, which by
  construction is not in app-db, so it cannot move into a subscription without moving the draft back —
  reintroducing the round trip the draft exists to avoid. Measured at `lag p95 = 1–2 ms` with zero
  clobbers, it is not a bottleneck. It shares the matcher and the scheduler; only the component differs.
- **Two DEV-only instruments ship in the source.** `vinary.renderer.input-trace` is `goog.DEBUG`-gated and
  absent from `:release`, exactly like `scroll-trace`. `finder/state-snapshot`'s cost fields are *not*
  gated, deliberately: a release build is the one whose latency matters, and they are numbers already
  sitting in the state atom.

## Alternatives considered

- **Uncontrolled inputs (`:default-value`).** Simpler, but leaves no way for application state to push a
  value — and `:find/reset`, `:palette/open` and tab switches all legitimately need to. The draft-plus-echo
  design keeps that path and is what the pending-value bookkeeping buys.
- **A Web Worker for the search.** DOM `Range`s and `TreeWalker` are main-thread-only, so the walk cannot
  move; only the scan could, and the scan is a native `indexOf` that was never the cost.
- **Capping the match count.** Would bound the Range-building work, but makes the counter lie. Chunking
  makes it non-blocking without changing what is true.
- **`scheduler.yield()`.** The right primitive, and not available in this Electron's Chromium.
  `MessageChannel` is the established equivalent.
- **Making the widgets fast enough that the clobber window closes.** Rejected on the measurement in §5 of
  the ledger: it makes the failure less likely rather than impossible.

## References

- [scientific/10 — The text-input latency experiments](../scientific/10-input-latency-experiments.md)
- [ADR-0032 — Scroll ownership and derived input focus](0032-scroll-ownership-and-derived-input-focus.md)
  — the previous find rewrite, whose walk this makes incremental
- [theory/06 — Find via the CSS Custom Highlight API](../theory/06-find-css-custom-highlight.md) — states
  the buffer-independence property this exploits
- [diagrams/activity-async-text-input.puml](../diagrams/activity-async-text-input.puml)
