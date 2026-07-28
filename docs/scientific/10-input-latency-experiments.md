# 10 — The text-input latency experiments

**Status: complete for v0.3.0-dev.** A user reported that typing into the in-page find widget lagged by
"a few hundred milliseconds" per character and that "if I type too quickly some keys are missed". The two
halves of that sentence turned out to be **two independent defects with different mechanisms**, present in
different widgets, and neither is fixed by fixing the other. This is the ledger of how each was isolated,
measured, fixed, and re-measured — including the hypotheses and instruments that did **not** survive
contact with the measurements.

The methodology is the one written down in [07 — Measurement methodology](07-measurement-methodology.md):
instrument first, predict before measuring, record the raw numbers, and keep the refuted hypotheses so they
are not re-attempted. The interventions are recorded as a decision in
[ADR-0033 — Asynchronous text input](../design-decisions/0033-asynchronous-text-input.md).

---

## 1 · Symptom

Reported verbatim:

> All search bars and other text input-based widgets should be asynchronous so there is no lag between
> user keystrokes. Presently, I have to type slowly into the find widget because keys take a few hundred
> milliseconds to appear after I type them and if I type too quickly some keys are missed.

Two claims, and it is worth being explicit that they are *separable*:

- **late** — a character appears several hundred milliseconds after the key is pressed;
- **missing** — a character never appears at all.

A single cause would explain both only if the mechanism were "the browser drops input while busy". It is
not: Chromium queues input events across a blocked main thread rather than discarding them. So the
starting position was that these are two defects, and the instruments were built to tell them apart.

---

## 2 · Instruments

Two were built before anything was changed.

### 2.1 `vinary.renderer.input-trace` — the renderer-side tracer

DEV-only (`goog.DEBUG`-gated), modelled on `vinary.renderer.scroll-trace`, exposed as
`window.__vvinputtrace()`. It answers the two questions separately:

| question | how |
|---|---|
| why is a character **late**? | `performance.now() - event.timeStamp` at keydown. A trusted event's `timeStamp` is stamped when the browser *generated* it, so the difference is exactly how long it waited for the main thread. |
| why is a character **missing**? | A pass-through patch over the `value` accessor on `HTMLInputElement.prototype`. A native edit never goes through that setter — the browser mutates the value internally and then fires `input` — so **every** call to it is a programmatic write, and one that replaces what the user typed with something shorter is a clobber, caught with its `from`, its `to`, and its stack. |

The prototype patch rather than instrumenting known call sites is the same argument `scroll-trace` makes:
the question is *who* wrote, and instrumenting only the writers already inventoried would beg it.

Corroborating signals: `longtask` `PerformanceObserver` entries (real main-thread occupancy) and a rAF
frame-gap sampler.

### 2.2 `scripts/bench-input-latency.cjs` — the driver

Boots the real app under xvfb and types into each field with **real key events** through the
browser-process input pipeline (`webContents.sendInputEvent`), at a fixed cadence, over a fixture ladder:
a 4 kB Markdown file, a 1.1 MB Markdown file (over the streaming threshold), a 40-page PDF, and this
repository's own git tree (6 578 files) for the tree filter and palette.

Run pinned, per the standing benchmarking rule — 32-core Threadripper PRO 5975WX, `performance` governor,
`taskset -c 2-9`:

```
npm run compile
VV_BENCH_JSON=/tmp/x.json taskset -c 2-9 xvfb-run -a -s "-screen 0 1400x1000x24" \
  electron --no-sandbox scripts/bench-input-latency.cjs | tee /tmp/vv-input-baseline.txt
```

### 2.3 Two instrument failures, recorded

**The tracer's `queueMs` is inert for injected events.** Electron stamps a `sendInputEvent`-injected
event's `timeStamp` when the *renderer* dispatches it, not when the browser process sent it. Measured:
`find-large` saturating the main thread with four ~450 ms searches still reported a **4 ms** queue. The
metric is correct for a physical keyboard and useless for a harness.

*Consequence:* perceived lag is measured **from outside** instead. The benchmark fires a trivial
`executeJavaScript('1')` from the main process every 25 ms during typing and records the round trip.
These queue behind a blocked renderer exactly as keystrokes do, so the worst round trip is the worst time
a character would wait. Everything reported as `lag` below is this figure.

**The frame-gap sampler is an artifact under xvfb.** Every scenario reported a uniform ~1017 ms
"block". Chromium throttles `requestAnimationFrame` for an occluded window — which is every window under
xvfb — so the gap sampler was measuring the compositor, not the app. `blockMs` (real `longtask` entries)
and `frameGapMs` are therefore reported as separate figures; merging them made every scenario look
blocked.

### 2.4 Two harness failures, recorded

**Not clearing the field before typing.** The first baseline run reported clobbers everywhere, e.g.
`"quick brown"` typed into a field that already held `"quick brown"`. In-page find *deliberately* keeps
its query across documents, and a Preferences font field is pre-populated, so `intact` was comparing the
typed phrase against leftovers. Fixed by clearing through the native setter plus a bubbling `input`
event, so the widget's own state follows.

**Polling a value that only changes when the thing being awaited completes.** The warm-up waited for
`__vvfind().chars` to stabilise, but `chars` only updates when a *search completes*, and the warm-up
issued one search. It therefore "stabilised" immediately and measured a document that was still
streaming. Fixed by re-issuing the query each poll round.

---

## 3 · Baseline

Cadence **150 ms/char (~80 WPM)**. That cadence is the interesting one precisely because it is *slower*
than find's 100 ms debounce: every character lands a search, which is the regime the defect lives in. At
25–40 ms/char each keystroke supersedes the previous debounce and the whole burst collapses into a single
search — faster typing was, for this subsystem, *easier*, which is itself worth knowing.

| scenario | intact | clobbers | lag p95 | lag max | longtask max | search ms | buffer chars |
|---|---|---|---|---|---|---|---|
| find-small (4 kB md) | yes | 0 | 1 | 58 | 55 | 4.2 | 4 253 |
| **find-large (1.1 MB md)** | yes | 0 | **127** | **480** | **451** | **450.2** | 1 104 683 |
| find-pdf (40 pp) | yes | 0 | 1 | 37 | — | 3.6 | 4 459 |
| **tree filter** (6 578 files) | **NO** | **1** | 1 | 42 | — | — | — |
| uri bar | yes | 0 | 2 | 49 | 52 | — | — |
| palette | yes | 0 | 1 | 35 | — | — | — |
| **Preferences** | **NO** | **1** | 26 | 47 | — | — | — |

Both symptoms reproduced, in **different widgets**:

- **late** — `find-large`: one 450 ms main-thread block per keystroke, and a 480 ms worst-case wait.
- **missing** — `tree` and `prefs`, caught with the writer's own before/after values:

  | field | typed | field held | the write |
  |---|---|---|---|
  | `.vv-tree-filter` | `views` | `vews` | `from: "vi"` → `to: "v"` |
  | `.vv-pref-input` | `Noto Sans` | `Not Sans` | `from: "Noto"` → `to: "Not"` |

---

## 4 · Hypotheses

### H-BLOCK — the lag is synchronous work on the keystroke path

`finder/search!` rebuilt the entire flattened document buffer — full `TreeWalker`, a `getComputedStyle`
per distinct element shape, and a per-character case fold — on every debounce landing, *even when the DOM
had not changed*, then built one DOM `Range` per match and painted them.

**Predicts:** search time scales with document size, not query length; and the block disappears if the
buffer is reused and the remaining work is sliced.

**Confirmed.** 450 ms over 1 104 683 characters ≈ 0.41 µs/char, against 4 ms over 4 253 characters
≈ 0.9 µs/char — linear in the document, independent of the query.

### H-CLOBBER — the missing characters are React committing stale state

Every affected field was a *controlled* React input: `:value` came from a subscription, so the character
the browser had just inserted was authoritative only until React's next commit — which happens after the
re-frame dispatch queue drains **and** after Reagent's batched render.

**Predicts:** characters are destroyed by a programmatic `.value` write, not swallowed at the input event.

**Confirmed, and refined by the data in a way the original hypothesis got wrong.** See §5.

---

## 5 · Refuted: "a blocked main thread is what drops characters"

The natural reading of the report — the thread is busy, so keystrokes go missing — is **wrong**, and the
baseline table says so plainly:

- `find-large` blocked the main thread for **450 ms per keystroke** and lost **nothing** (`intact: yes`,
  `clobbers: 0`).
- `tree` and `prefs` had **no long task at all** and lost characters.

The tracer also separates the two possible mechanisms: in both losses `lostKeys` was **0**, meaning the
character *did* reach the field — the `input` event fired and the value grew — and was removed afterwards
by a programmatic write.

**The refined hypothesis, which fits every observation:** a controlled input loses a character when the
widget's **own render** is slow enough that a keystroke lands mid-render; React then commits the
pre-keystroke value. The tree rebuilds thousands of nodes per keystroke; a Preferences edit re-measures
every figure and Mermaid diagram. The find bar is five elements, so it never clobbered — *even while a
450 ms search blocked the thread*.

This matters for the fix. It means the clobber cannot be engineered away by making the app faster: a
faster widget only makes the window smaller. It has to stop being *possible*, which is what a locally
owned field value achieves.

---

## 6 · Interventions and re-measurement

### 6.1 Own the field's value locally (`vinary.ui.text-input`)

A local draft shadows the model while the user is editing, so React's committed value always equals what
the DOM already holds. The subtle half is knowing when to *stop* shadowing, since the model legitimately
changes from elsewhere (`:find/reset`, `:palette/open`, a tab switch).

**A naive comparison of model against draft does not work**, and getting this wrong reintroduces the very
defect: while typing, the model *always* trails, and treating that as an external push would drop the
draft mid-word. So the component keeps the values it has published but not yet seen echoed back; a model
change matching one of them is its own echo, and one matching none is external. `reconcile` is pure and
unit-tested against exactly that case (`test/vinary/ui/text_input_test.cljs`).

### 6.2 Slice the work, and pace it to the input queue (`vinary.async.scheduler`)

`slice!` drives a step function under a frame budget (default 8 ms), yielding between slices, and a newer
request cancels the running job between slices rather than after it.

**A refuted choice, measured:** the first implementation yielded through `ric` (the rAF-or-idle tick
already used for streaming). That caps throughput at one slice per *frame*, and animation frames are
throttled to ~1 Hz for an occluded window — which is every window under xvfb, and any backgrounded window
in production. Measured: the chunked find took **380 ms to settle on a 4 kB document** and **never
finished at all** on the 1.1 MB one.

The distinction the two primitives now carry:

- **`ric`** paces work to the **display**. Correct for committing DOM as a document streams in — there is
  no point producing frames faster than the screen shows them.
- **`yield!`** (a `MessageChannel` post) paces work to the **input queue**. Correct for computation: let
  the browser deliver a keystroke, then resume at once.

### 6.3 Cache the flattened buffer

`docs/theory/06` already asserted the property — *the buffer depends only on the DOM, not on the query* —
and nothing acted on it. Reuse is gated on the same document, the same content element, and no mutation
since, tracked by a monotonic mutation counter.

**Steady state, 808 kB document, successive queries** (`quick` → `quick b` → …):

| query | settle ms | CPU ms | buffer reused? |
|---|---|---|---|
| `quick` | 357 | 304 | no — the first build |
| `quick b` | 9 | 8 | **yes** |
| `quick br` | 10 | 9 | **yes** |
| `quick bro` | 9 | 9 | **yes** |

**A 34× reduction** in per-keystroke cost, with zero DOM mutations observed between searches.

### 6.4 Refuted: one scheduler key for the whole search

The first chunked version ran flatten *and* match under one key, so a new query cancelled both. The
benchmark then still reported `cached: no` on every keystroke — because with a 150 ms cadence and a
~300 ms flatten, **every character discarded a partly-finished walk and started another**. The buffer was
never committed at all while the user typed.

**Fixed by splitting the keys**: flattening is query-independent and is *not* cancelled by a query change;
matching is query-specific and is cancelled immediately. A mutation arriving mid-build leaves the buffer
usable but not reusable, which is what stops a continuously mutating document from looping forever on
rebuild-and-discard.

### 6.5 Refuted: incremental match-set narrowing

The obvious optimisation — the user typed one more character, so filter the previous matches instead of
rescanning — is **unsound**, because the match set is non-overlapping and therefore not a superset of the
starts of any query extension.

**Counterexample**, pinned as a test in `test/vinary/search/scan_test.cljs`: in `"aaab"`, scanning `"aa"`
yields the single start `{0}`; `"aab"` matches at `1 ∉ {0}`.

Reuse must therefore happen at the **buffer** level — which is the cheaper place anyway, since scanning is
a native `indexOf` loop and building the buffer is not.

### 6.6 Bound the stream rush

`materialize!` (find's "make the whole document searchable first" hook) set a `rush?` flag under which the
markdown drain **recurred straight into itself with no yield**, committing every remaining block in one
task — a multi-second freeze on a 1.1 MB document, triggered by the *first character* typed into find.
`rush?` now means *larger batch, still one batch per yield*: 768 blocks instead of 48, through `yield!`
rather than `ric`.

### 6.7 An ASCII fast path for the length-preserving fold

The per-code-unit fold exists because `String.prototype.toLowerCase` is not length-preserving (`"İ"`
U+0130 folds to two UTF-16 units), and the buffer's indices map back to DOM text-node offsets. But over
U+0000–U+007F the mapping is 1:1 by construction, so one native regex test buys an entire chunk out of
the loop. The invariant is still pinned for `İ ǅ ẞ Ⅷ ΑΣ`, and the fast and slow paths are asserted to
agree on ASCII.

### 6.8 Stop four DataScript queries per keystroke

`:facet/active` and `:view/switch` were plain db-subs running a `d/q` plus a 22-attribute `d/pull` on
**every app-db write**, and both are permanently subscribed. Every character typed anywhere in the app
paid for four DataScript queries that could not have changed their answer. Layered on a cheap inputs slice
plus the already-layered `:doc/group`, mirroring the `::keymaps-slice` fix the same file had already
applied once for the same reason.

---

## 7 · Result

Same harness, same machine, same pinning, cadence 150 ms:

| scenario | intact | clobbers | lag max (before → after) | search CPU (before → after) | buffer reused? |
|---|---|---|---|---|---|
| find-small | yes | 0 | 58 → 78 | 4.2 → 0.0 | yes |
| **find-large** | yes | 0 | **480 → 75** | **450.2 → 5.3** | **yes** |
| find-pdf | yes | 0 | 37 → 33 | 3.6 → 0.1 | yes |
| **tree filter** | **NO → yes** | **1 → 0** | 42 → 46 | — | — |
| uri bar | yes | 0 | 49 → 41 | — | — |
| palette | yes | 0 | 35 → 32 | — | — |
| **Preferences** | **NO → yes** | **1 → 0** | 47 → 189 | — | — |

- **The lag is gone**: the worst keystroke-to-response wait on the 1.1 MB document falls from 480 ms to
  75 ms, and the per-keystroke CPU cost from 450 ms to 5.3 ms.
- **No character is lost** in any widget at any measured cadence.
- `find-small`'s lag max rising 58 → 78 ms is not the search (its CPU is 0.0 ms and it reports no long
  task from find); it is the environmental floor described in §2.3, which sits around 90–100 ms under
  xvfb. Both figures are below it.
- **Preferences' 189 ms is real and is by design.** The live font preview — which re-measures every figure
  and Mermaid diagram — now lands *once, 150 ms after typing stops*, instead of on every character. The
  probe window includes that settle, so the cost shows up here; it is no longer *between* keystrokes,
  which is what the user asked for and what `intact: yes` confirms.

---

## 8 · Regression guards

`test/find-e2e.js` gained a typist-latency scenario over a 1.1 MB fixture and over this repository's own
6 578-file tree, typing real keys at 150 ms/char:

| assertion | property |
|---|---|
| **T1** | every character typed survives — the field holds exactly what was typed |
| **T2** | the tracer reports zero programmatic writes that discarded characters |
| **T3** | the worst main-process round trip stays under 200 ms while typing |
| **T4** | the flattened buffer is actually being reused across keystrokes |

The 200 ms budget is derived, not guessed: the pre-fix engine measured 440–480 ms on this fixture, the
fixed one 29–75 ms, and the environmental floor is ~90 ms. It sits above the floor with margin and below
half the regression it must catch.

**Both gates were verified against negative controls**, and the result is cleanly factorial — each
assertion is sensitive to exactly one mechanism:

| control | T3 (responsive) | T4 (cache engaged) |
|---|---|---|
| buffer cache disabled | pass (80 ms) | **fail** |
| frame budget removed | **fail (407 ms)** | pass |
| neither | pass (74–93 ms) | pass |

That the cache-disabled build still passes T3 is the point of the design: chunking keeps the thread
responsive even when the work is redone, and caching removes the work. They are independent properties and
the suite treats them as such.

Unit coverage (DOM-free): `vinary.search.{query,match,scan,cursor}`, `vinary.async.scheduler`,
`vinary.ui.text-input`, `vinary.app.tree-model` — including one test per matching mode, the
unsound-narrowing counterexample as an explicit non-property, and the scheduler's budget boundary tested
against a substituted clock rather than by sleeping.

---

## 9 · Summary of what was refuted

Recorded so none of it is re-attempted:

1. **"A blocked main thread drops characters."** It does not — Chromium queues them. The clobber needs a
   slow *widget render*, not a slow app. (§5)
2. **`event.timeStamp` as the latency instrument.** Correct for a physical keyboard, inert for an injected
   one; measured 4 ms while the thread was saturated. (§2.3)
3. **The rAF frame-gap sampler as a block detector.** An artifact of compositor throttling under a
   headless harness. (§2.3)
4. **`ric` (frame pacing) for chunked computation.** Caps throughput at one slice per frame and stalls
   entirely when frames are throttled. (§6.2)
5. **One scheduler key for flatten + match.** Every keystroke discarded a partly-finished flatten, so the
   cache never engaged. (§6.4)
6. **Incremental narrowing of a non-overlapping match set.** Unsound; counterexample in `"aaab"`. (§6.5)
7. **A single dirty *flag* for buffer and Ranges.** Cannot express a mutation arriving mid-build, which
   must leave a result usable but not reusable — replaced by a monotonic counter each artefact stamps
   itself with. (§6.4)

One further judgement, made from measurement rather than principle: **the URI bar's completion filter was
left in its render function.** It depends on the local draft, which by construction is not in app-db, so
it cannot be moved into a subscription without moving the draft back — reintroducing the round trip the
draft exists to avoid. Measured at `lag p95 = 1–2 ms` with zero clobbers, it is not a bottleneck.
