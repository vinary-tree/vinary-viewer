# 0040 — The Commit Graph document, the blame gutter, and history modes

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** vinary-viewer maintainers

## Context

[ADR-0039](0039-commits-sidebar-and-git-data-layer.md) built the read-only git data layer and its
first surface, the Commits sidebar tab — and deliberately shaped several of its decisions (the
stored lane fold R2, the hash-keyed selection R3, the committer-date field, the reserved
`lineRange` request key) for the surfaces that were still to come. This ADR lands those surfaces,
in three pieces that shipped as three commits:

1. the **per-line blame gutter** in the source view (`e11ce2b`) — *who last touched this line,
   when, and what did that commit change*;
2. the full-pane, GitLens-style **Commit Graph document** (`6578587`) — the roomy sibling of the
   sidebar's mini rail, with a keyboard model, windowed rows, and ref badges;
3. **file history and line-range history** (`5b87524`) — `git log --follow` and `git log -L` as
   *modes* of the existing Commits store.

The constraint that shaped all three: **no new spine**. The data layer, the store, the selection,
the diff-opening path, and the navigation pipeline already exist; each feature must be a new
*edge* on them, never a parallel copy. Where that constraint forced a change to something
ADR-0039 documented (the watcher-ownership events), the change is recorded here and the reference
tables were corrected in the same pass.

All git access remains **strictly read-only** — the one new subprocess (`git blame
--line-porcelain`) is a query like every other, behind the same hardened
[`vinary.main.git`](../reference/namespaces.md) layer
([threat model](../security/threat-model.md#the-git-data-layer-adr-0039)).

## Decision

**One data layer, three new edges:**

- The Commit Graph is a **virtual document** (`vv-git-graph://<repo-root>`) that rides the
  *unchanged* navigation pipeline and renders a **second view over the same `[:ui :commits]`
  store** the sidebar panel feeds — one fold site, one selection, two surfaces.
- Blame is a **second CodeMirror gutter** behind its own `Compartment` in the existing source
  view, fed by main-side porcelain parsing (`vinary.git.blame`) and governed by **one global mode
  flag** (`[:ui :blame]`) with a single re-ensure hook.
- History is **two modes of the per-repo Commits state** (`:mode` / `:history-target`), so both
  surfaces display a history the moment it is requested; `git log -L` gets the second record
  discipline R1 reserved for it.

![Component — the whole git integration](../diagrams/component-git-integration.svg)

*Diagram source:
[`../diagrams/component-git-integration.puml`](../diagrams/component-git-integration.puml).*

### Decisions in detail — the Commit Graph document

- **A synthetic `vv-git-graph://` URI through the unchanged pipeline.** The graph opens as an
  ordinary document: `[:git-graph/open]` dispatches `[:doc/open "vv-git-graph://<root>"]`, and
  from there tabs, history, Back/Forward, and retention all apply with **zero new arms** in any
  of them. `vinary.app.uri` gains `git-graph?`/`git-graph-root` plus display and basename arms
  (the tab titles itself **"Commits · \<repo\>"**); `nav/local-fs-path?` excludes the scheme from
  the native Open dialog's candidates; main answers it from `send-content!` with a **tiny**
  payload — `vv:content {path, kind "git-graph", git {root}, stamp}` after one sync `rev-parse`
  validates and canonicalizes the wrapped root (else `vv:error` "Not a git repository"). This
  preserves the **single-ingestion-point invariant**: every document the renderer shows, virtual
  or real, enters through `vv:content` → `:content/received` — the Commit Graph adds a kind, not
  a channel. Contrast with ADR-0039 D4, which argued a spilled *file* over a virtual URI for
  commit diffs: a diff needs retention/watchers/enrichment, all path-keyed, while the graph
  document needs *none* of them — its content lives in `[:ui :commits]`, so the URI carries
  exactly what it must (the repo root) and nothing pretends to be a file.
- **Route FIRST, because the URI names no filesystem object.** `service-util/route` tests
  `:git-graph?` before `directory?` and before every name-based classification: any later arm
  would `stat`/read a path that does not exist. For the same reason `open!` skips the tree send
  (the repo is already a Files project) and the content watcher (freshness arrives over
  `vv:git-changed`, ADR-0039's watcher — there is no file to watch).
- **Ingestion: `:doc/git-root`, MRU exclusion, data-ensure.** `:content/received` stores the
  validated root as the new DataScript attribute **`:doc/git-root`** (added to the `active-doc`
  pull list — the [rule](../architecture/04-state-schema-reference.md) stands: a new `:doc/*`
  attribute is invisible to views until pulled), excludes the kind from Open Recent (like a
  stdin spill: the MRU must offer real, reopenable documents — though unlike a spill the graph
  *is* reopenable, its entry would be noise beside the project that names it), and dispatches
  `[:git-graph/data-ensure root]` → `[:commits/ensure root]` (R9's idempotent branches + page-0
  load — the same mount path the sidebar uses).
- **The view is keyed by PATH ONLY.** The `views.cljs` case arm keys the component
  `(str "gg:" path)` — deliberately *not* by stamp, unlike almost every other kind. A re-send
  (reload, live refresh) must never remount the component, because scroll position and the
  keyboard cursor live through it; data freshness flows through `[:ui :commits]`, which the view
  subscribes to, so a remount would buy nothing and cost the user their place.
- **A second view over one store — no second fold, no second selection.**
  `vinary.ui.git-graph/graph-view` subscribes to the same `[:commits/for-root root]` the panel
  reads: the commits, the **stored incremental lane fold** (R2, folded once in
  `:commits/log-received`), and the **hash-keyed selection** (R3). Clicks and keys dispatch the
  *existing* `:commits/select` / `:commits/activate` events, so selecting in the graph highlights
  the panel and vice versa, and "Diff Selected (2)" means the same two commits everywhere.
  `vinary.git.graph-geometry` is the only new model code — pure numbers (row height 24, lane
  width 12, per-kind SVG path strings, lane cap 12 with an `»` overflow chip, ref-badge
  normalization, keyboard cursor math, date formatting), every `d` string pinned by
  `graph_geometry_test`.
- **Hiccup SVG per-row cells — the first hiccup SVG in the codebase, deliberately.**
  [ADR-0003](0003-ref-innerHTML-no-vdom-body.md) writes *document bodies* via `innerHTML` because
  they are huge, sanitized, non-interactive blobs. A graph row is the opposite: **tiny** (a
  handful of paths and one circle), **interactive** (row classes react to selection/cursor
  subscriptions), and generated from **pure, unit-tested geometry** — exactly what
  reagent/hiccup is for. Each cell is self-contained under the sidebar rail's two-half
  convention: the previous row's edge *targets* supply the ink arriving at this row's top edge,
  the row's own edges supply the dot-anchored and pass-through ink, and quadratics with
  mid-height control points make adjacent halves meet flush — which is what lets *windowed*
  neighbors (which may not even be mounted) still compose into continuous lines.
- **Fixed-row windowing over the confined scroller, with a guarded near-end event.** The pane
  (`.vv-gg`) does not own its scroller — the document pane `.vv-content` does
  ([ADR-0032](0032-scroll-ownership-and-derived-input-focus.md)'s confined scroll owner) — so
  the view attaches a passive scroll listener to its closest `.vv-content`, mirrors
  `{scrollTop, clientHeight, offsetTop}` into a local ratom, and renders only the visible band
  (± 10 rows of overscan) between two spacer `div`s whose heights are exact because **every row
  is 24 px** — uniform height is what makes the band arithmetic closed-form. This is the
  windowing ADR-0039 D9 *rejected* for the sidebar, adopted here because the premises flip: the
  graph auto-pages on scroll (the DOM is not bounded by explicit clicks), and the pane sits
  directly inside the scroller it must command. Paging is **event-guarded, never render-driven**:
  the scroll handler dispatches `[:git-graph/near-end root approx-hi]` and the *event* decides —
  within 30 rows of the end, not loading, not exhausted → `[:commits/load-more]`. Dispatching
  from the render function instead would re-fire on every unrelated re-render and turn a
  subscription change into an IO loop.
- **A keyboard model behind the `[data-vv-keynav]` carve-out.** ↑/↓ move the cursor (Shift
  extends the range, Ctrl moves the cursor *only* — the selection stands), PgUp/PgDn move by the
  visible row count, Home/End jump, Space toggles the cursor row in the selection, Enter opens
  the cursor commit's diff-vs-parent. All of it lands on the shared events (`:commits/cursor-to`
  writes the selection and emits the `:git-graph/reveal-row` effect, which clamps the scroller so
  the cursor row stays visible — native `scrollTop` writes only, the single scroll owner). The
  prerequisite is one selector: the window-capture arrow-key scroller
  (`renderer.core/editable-target?`) would otherwise swallow every arrow before the pane saw it,
  so its `closest` selector gains **`[data-vv-keynav]`** — panes that declare their own keyboard
  model opt out of window scrolling. It is the smallest possible change to a global listener
  (one attribute in one selector), and the electron smoke drives the real path (ArrowDown moves
  the cursor in app-db *and* the DOM, Enter records the R4 request shape).
- **Watcher ownership becomes a UNION of surface slots.** `vv:git-watch` **replaces** the sending
  window's watched-root set (ADR-0039 D8) — correct with one surface, wrong with two: the graph
  unmounting would send its own idea of the set and silently release the panel's watcher (or vice
  versa). So the window's set is now derived: each surface owns a slot under
  `[:ui :commits :watch-owners {:panel …, :graph …}]`, mount/unmount writes only its own slot,
  and the new `:commits/sync-watch` event sends the **union of the slots' values** over
  `:vv/git-watch`. This *refactors ADR-0039's shipped events* — `:commits/shown` / `:commits/hidden`
  / `:commits/set-root` now write their slot and dispatch `[:commits/sync-watch]` instead of
  calling `[:vv/git-watch …]` directly — and the
  [events reference rows were corrected](../reference/events-effects-subs.md) to match. Main is
  untouched: replace-the-set stays the wire contract; the renderer simply computes the set
  honestly.
- **Entry points self-gate.** The palette ("Open commit graph") derives a root exactly as the
  panel does (pin > deepest project containing the active doc > last shown > first); the Files
  **project-header** context menu offers "Open Commit Graph" (a no-op for synthetic, non-git
  roots); the Commits panel header carries a **Graph** pill. The panel's rows also now pass the
  two-selected `:pair` into the `:commit` context-menu target — the ADR-0039 docs pass found that
  "Diff Selected (2)" was *documented* for the panel's menu but unreachable from it (the pill
  alone offered the action); both surfaces now populate it.

### Decisions in detail — the blame gutter

- **A second gutter behind its own `Compartment` — not line decorations.** The source view
  already reconfigures grammar highlighting through a `Compartment`; blame follows the identical
  pattern: the view mounts with an **empty** blame compartment, and asynchronously-arriving hunks
  reconfigure it (`set-blame!`) without touching any other extension. A CodeMirror `gutter()`
  (over `Decoration.line` annotations) is the right primitive because it pools DOM elements, is
  immune to horizontal scrolling, never perturbs the text's own layout, and provides a per-line
  `domEventHandlers` click seam — the gutter click *is* the "open this commit's diff" gesture.
  A remount (facet flip, live refresh) starts empty again by construction, and the ensure hook
  re-applies from cache.
- **Prototype-derived `GutterMarker`, `eq` for DOM reuse, `isConnected` guards.** CodeMirror
  expects `GutterMarker` sub*classes*; under the `:simple`-compiled renderer
  ([ADR-0016](0016-main-process-simple-optimization.md) records why class-extension machinery is
  treacherous territory), the marker is built with `js/Object.create` on
  `GutterMarker.prototype` instead — `GutterMarker`'s own constructor is empty, so bypassing
  `new` is safe, and plain interop assignment of `eq`/`toDOM` stays clear of `extends` entirely.
  `eq` compares the marker's info map by value, so an unchanged line's chip DOM is **reused**
  across reconfigurations. `set-blame!`/`clear-blame!` additionally guard on
  `(.-isConnected (.-dom view))`: the module-level `current-view` atom keeps the *last* view by
  design (the outline-jump contract), and a facet flip can destroy that view's DOM before an
  async blame reply lands — dispatching into a detached view would throw.
- **Porcelain parses in MAIN, hunks cross the seam (~100×).** `git blame --line-porcelain` for a
  10k-line file is ~5 MB of text; the coalesced hunks are a few KB. The pure `vinary.git.blame`
  parses per-line records (40-hex header, metadata keys, TAB content line closes a record),
  keeps a **per-hash metadata cache**, and re-coalesces consecutive same-hash lines (contiguous
  in *both* original and final numbering) into hunks — which has the pleasant corollary that
  plain `--porcelain` input (metadata once per commit) parses **identically**, since the group
  count is ignored on purpose and the cache fills records that carry none. Unknown keys are
  skipped (forward-compatible), CRLF is tolerated. The renderer receives structured hunks and a
  binary-search `hunk-for-line` — never raw porcelain.
- **One GLOBAL mode flag, one re-ensure hook, stamp-gated replies.** `[:ui :blame]` holds
  `{:on? :file :stamp :root :hunks …}` — blame is a *mode of the application*, not of one tab.
  Every source-view mount reports itself (`[:blame/source-mounted {file stamp}]`, dispatched from
  the one `mount-editor!` site), so **toggle-while-shown, facet flips, tab switches, and live
  refreshes all fall out of a single hook**: when the mode is on, `[:blame/ensure]` either
  re-applies the cached hunks (same `(file, stamp)`) or fetches. Replies are **stamp-gated** —
  `:blame/received` carries the `(file, stamp)` it was asked for and is dropped unless both still
  match — so a live-refresh race can never paint a stale gutter over new text, the same
  generation discipline as `:find` and `:commits`. One `git blame` runs per `(file, stamp)`.
- **Working-tree semantics; the zero hash means "Uncommitted".** The gutter annotates exactly
  what the source view displays — the file on disk — so blame runs against the **working tree**
  (no rev argument), and lines not yet committed arrive under git's all-zero hash, rendered as an
  italic "Uncommitted" chip whose click is a no-op (there is no commit to open). Boundary
  commits (the history edge, e.g. of a shallow clone) render dimmed. No `-M`/`-C`/`-w`: literal
  line attribution, the least surprising default for a *viewer*.
- **The repo is re-derived from the file, and gating is honest.** The renderer's root is
  advisory: `handle-blame` re-derives the repository from the blamed file's own directory (the
  active source file may belong to a different repo than the Commits panel shows). Renderer-side,
  `:blame/toggle` self-gates to **local, plain-path** files (no `ssh://`, no virtual schemes) —
  the palette pattern, a silent no-op otherwise; a local file *outside* any repository still
  passes the gate and simply receives main's honest not-a-repository error into
  `[:ui :blame :error]` — never an invented empty gutter.
- **A gutter click opens the commit's diff through one shared entry.** `:blame/line-click`
  resolves the clicked line's hunk and dispatches the new **`:git/open-commit-diff`** — the one
  renderer-side "open a commit diff" entry (the graph's Enter/double-click funnels into the same
  `:commits/activate` → `vv:git-open-diff` path): a nil `from` asks main for the first parent,
  with the empty tree closing the root-commit case (R4). The diff document that opens is an
  ordinary ADR-0039 spill with rev-aware Split enrichment.
- **Surface bindings.** Palette "Toggle git blame" (`:git/blame-toggle`), **`C-S-g`** in the
  Standard keymap's `:all` block (the chord is unclaimed in all three presets; vim/emacs users
  reach it through the palette), and the `window.__vvblame` DEV seam the electron smoke drives.

### Decisions in detail — history modes

- **History is a MODE of the shared store, not a third surface.** `[:ui :commits :repos <root>]`
  gains `:mode` (`:log` / `:file-history` / `:line-history`) and `:history-target`; entering a
  history **replaces that repo's listing** (`enter-history` resets commits/fold/selection), so
  the sidebar list and the Commit Graph both show it immediately — for free, because they are
  views over the store. Entry **pins the panel to the file's repo and reveals the Commits tab**
  (`[:sidebar/show]` + `[:sidebar/tab :commits]`): the results must be *looked at*, and a pin
  elsewhere would hide them. A dismissible chip in both headers ("History: file" /
  "History: file · L10–42", `graph-geometry/history-chip-label`) exits back to the branch log
  (`:git/history-exit` — reset to `:log`, reload page 0).
- **Mode-aware loading through one builder.** `:commits/load` merges
  `commits/history-args {:mode :target}` into the `vv:git-log` request — `:file-history` →
  `{:file … :follow true}`, `:line-history` → `{:lineRange {file start end}}`, `:log` → nil —
  and adds the viewed `:ref` **only for the plain log**. `--follow` file history reuses the
  nine-field main format unchanged (it emits no patch text; the single-file pathspec constraint
  is `--follow`'s own).
- **The `%x1e` line-log discipline (R1's second format).** `git log -L` *forces patch output on
  pre-2.42 gits even under `--no-patch`* — a record **terminator** would glue the bleed into the
  preceding record's last field, while a record **START** marker (`%x1e` opens each record) lets
  the parser discard everything after the last field up to the next `%x1e`. The format therefore
  carries **no `%b`** — a body field could swallow bleed *silently*, and a corrupted body is
  worse than no body — and ends at the single-line `%s`, which the parser additionally truncates
  at its first newline (bleed begins with a newline); a stray `%x1f` inside bleed only widens
  the field count past eight and is ignored the same way. The result: pre-2.42 and ≥ 2.42 gits
  parse **identically**, pinned by unit fixtures (`log_test/line-log-argv-and-records`) and by
  the hermetic `git-blame-smoke` against the real installed git. The main `%x00` discipline is
  untouched — where no patches can appear, an unforgeable terminator remains the stronger
  boundary.
- **HEAD-pinned, single-shot, repo-relative with an outside-repo guard.** Line history runs from
  **HEAD** with no ref argument — `-L` (like `--follow`) traces *working-file lineage*, and a
  branch pick would name a different file history than the one on screen; both branch combos
  disable in history modes with the title "History follows HEAD" saying exactly that. The `-L`
  walk is **single-shot** (`-n 500`, reply marked `exhausted`): `-L` paginates poorly (each page
  would redo the content trace), and 500 line-touching commits is beyond any review horizon.
  `handle-log`'s `lineRange` branch resolves the file **repo-relative** (backslashes normalized)
  and rejects a blank or `..`-escaping result with "file is outside the repository" before any
  argv runs. Swapped bounds normalize renderer-side (`:git/line-history` sorts start/end).
- **Flat, dots-only rails — lanes would lie.** A history listing is **non-contiguous**: parents
  of listed commits are mostly *not* in the listing, so lane assignment would fabricate
  connectivity that does not exist. `:commits/log-received` therefore stores an **empty fold**
  in history modes, and both surfaces degrade their rails to lane-0 dots (each row renders
  `{:lane 0 :edges []}`). An honest dot column beats a fictional graph.
- **The line source is the source view's own selection.** "Line Range History" with no explicit
  range reads the mounted source view's primary selection through the new
  `:git/selection-line-history` effect (`cm/selection-lines` — the cursor line twice when the
  selection is empty), then dispatches `:git/line-history`. Reading the DOM is fx business, not
  event business. Entry points: palette "File history" / "Line range history", **File History**
  in the Files-tree `:file` row menu and the tab context menu, **Line Range History** in the
  source view's `:source-body` menu — every one self-gates on deriving a git root.

## Consequences

- **The renderer grows 26 registrations — 21 events, 5 effects — all catalogued.** Events: `:blame/source-mounted`,
  `:blame/toggle`, `:blame/ensure`, `:blame/received`, `:blame/error`, `:blame/line-click`,
  `:git/open-commit-diff`; `:git-graph/open`, `:git-graph/data-ensure`, `:git-graph/shown`,
  `:git-graph/hidden`, `:git-graph/near-end`, `:git-graph/cursor-move`,
  `:git-graph/toggle-at-cursor`, `:git-graph/activate-cursor`; `:commits/sync-watch`,
  `:commits/cursor-to`; `:git/file-history`, `:git/line-history`,
  `:git/line-history-from-selection`, `:git/history-exit`. Effects: `:vv/git-blame`,
  `:blame/apply-view`, `:blame/clear-view`, `:git-graph/reveal-row`,
  `:git/selection-line-history`. All rows live in the
  [events/effects/subs reference](../reference/events-effects-subs.md); no new subscriptions
  (both new surfaces read the ADR-0039 subs).
- **ADR-0039's watcher events changed shape.** `:commits/shown` / `:commits/hidden` /
  `:commits/set-root` now write `[:ui :commits :watch-owners :panel]` and dispatch
  `[:commits/sync-watch]` rather than emitting `[:vv/git-watch …]` directly; the reference rows
  were corrected in this pass. The `vv:git-watch` wire contract is unchanged.
- **New state, documented in the schema reference.** `[:ui :blame]`
  (`{:on? :file :stamp :root :hunks :loading? :error}` plus the `(file, stamp)` cache keys);
  `[:ui :commits :watch-owners {:panel :graph}]`; per-repo `:mode` / `:history-target`; and the
  DataScript attribute `:doc/git-root` — added to the `active-doc` pull list, per the standing
  rule that the pull vector is the authoritative attribute list.
- **One new IPC channel** (`vv:git-blame`, invoke), one new keybinding (`C-S-g`, Standard
  preset), four palette commands under **Git** (Toggle git blame · Open commit graph · File
  history · Line range history), and the `window.__vvblame` DEV seam.
- **A latent dedupe bug surfaced and was fixed under review** (`be0778a`): the commit-diff spill
  dedupe keyed `[root from to]` alone, so `A..B` followed by `A...B` over the same verified
  endpoints returned the *two-dot* spill instead of computing the symmetric difference (and a
  path-scoped request would have collided the same way). The key now carries **every
  argv-shaping input** — root, both shas, the dots form, and the path scope — and the spill's
  label/tab title spells the actual dots form. Found by the ADR-0039 documentation pass checking
  shipped behavior against the design; recorded here as this cycle's argument for writing docs
  against HEAD rather than against the plan.
- **Test surface.** `blame_test` (coalescing arithmetic, the metadata cache, zero hash,
  boundary, CRLF, unknown keys, `hunk-for-line` edges, date buckets), `graph_geometry_test`
  (exact `d` strings per edge kind, cap clamps and the overflow flag, badge normalization,
  cursor math, date/chip formatting), `log_test/line-log-argv-and-records` (the `-L` argv, clean
  and *bleeding* fixtures parsing identically), `commits_test/history-mode-request-args`;
  `git-blame-smoke.js` against a real hermetic repo (the zero hash for a dirty line, `--follow`
  crossing a rename, `-L --no-patch` record cleanliness on git ≥ 2.42, `%x1e` record openings,
  source-binding regexes); electron-smoke arms driving the real blame gutter over a mocked seam
  and the real graph over mocked git channels (rows/badges render, ArrowDown moves the cursor in
  db *and* DOM, Enter records the R4 shape); route arms across the service-util / file-kind /
  content-route suites.

## Alternatives considered

**Commit Graph:**

- **A stamp-keyed view** (the house default for content views). Rejected: a live refresh would
  remount the component and reset scroll and cursor for a document whose data does not live in
  DataScript anyway.
- **An `innerHTML` rail in the ADR-0003 style.** Rejected: the cells are tiny, interactive, and
  generated by pure tested geometry — hiccup's case exactly; ADR-0003's premises (huge sanitized
  static bodies) do not hold.
- **`content-visibility` instead of windowing** (the sidebar's D9 choice). Rejected here: the
  graph auto-pages on scroll, so its DOM is unbounded, and unlike the panel it sits directly
  inside the `.vv-content` scroller its spacers must command — both D9 premises flip.
- **Dispatching load-more from the render function.** Rejected: render is re-entered for
  unrelated reasons; paging decisions belong in a guarded event fed by the scroll listener.
- **A second selection/fold for the graph.** Rejected: R2/R3 existed precisely so the graph
  could reuse them; two selections would immediately disagree.
- **Per-window watch booleans instead of owner slots.** Rejected: `vv:git-watch` replaces the
  window's set, so the set must be *derived* from every mounted surface's interest — slots plus
  a union event make either surface's unmount unable to release the other's watcher.
- **`:git-graph/open` navigating directly instead of `[:doc/open]`.** Rejected: the point of the
  synthetic URI is that history/tabs/retention need no graph-specific arms.

**Blame gutter:**

- **`Decoration.line` annotations in the text.** Rejected: they perturb line layout, scroll
  horizontally with the text, offer no pooled per-line DOM, and have no per-line click seam; a
  gutter is the purpose-built primitive.
- **`shadow.cljs.modern/defclass extends GutterMarker`.** Rejected in favor of the prototype
  derivation: class-extension machinery under `:simple` is exactly the kind of transform-time
  surprise ADR-0016 exists to avoid, and `GutterMarker`'s empty constructor makes `Object.create`
  provably equivalent.
- **Parsing porcelain in the renderer.** Rejected for the ADR-0039 reasons, amplified: blame
  porcelain is the largest per-file payload in the layer (~100× the hunks), and main already owns
  the subprocess.
- **Per-tab blame state.** Rejected: "show me authorship" is a reading *mode*; as per-tab state
  it would demand a toggle per tab and still need the global mount hook to survive facet flips.
- **Blaming `HEAD` instead of the working tree.** Rejected: the gutter must annotate the text the
  user is looking at; uncommitted lines as an explicit zero-hash state are more honest than
  attributing them to the last committed version.

**History modes:**

- **A dedicated history panel/document.** Rejected: both existing surfaces already know how to
  render a commit listing; a mode costs two chips and a fold guard, a third surface costs a
  keyboard model, a selection, and a watcher slot.
- **The `%x00` terminator for `-L` records.** Rejected: pre-2.42 patch bleed would concatenate
  into the final field; only a record-*start* marker makes trailing garbage discardable. Keeping
  `%b` was rejected with it — a field that can silently absorb bleed is worse than none.
- **Paged `-L`.** Rejected: `-L` re-traces content per invocation and offers no cheap skip; a
  single bounded shot marked `exhausted` is honest and sufficient.
- **Branch-selectable history.** Rejected: `--follow`/`-L` trace the *working file's* lineage;
  a ref pick would silently answer a different question. Disabling the combo with an explanatory
  title states the constraint instead of hiding it.
- **Lane-folding history listings.** Rejected: parents are absent from a filtered listing, so
  lanes would draw connectivity that is not in the data — dots only.

## See also

- [ADR-0003 — ref + `innerHTML`, no VDOM body](0003-ref-innerHTML-no-vdom-body.md): the
  document-body strategy the hiccup-SVG rows deliberately do *not* follow, and why.
- [ADR-0009 — Mediator IPC over point-to-point](0009-mediator-ipc-over-point-to-point.md): the
  single preload seam `vv:git-blame` joins.
- [ADR-0026 — Diff rendering, side-by-side, and repo filetypes](0026-diff-rendering-side-by-side-and-repo-filetypes.md):
  the pipeline every diff opened from these surfaces renders through.
- [ADR-0032 — Scroll ownership and derived input focus](0032-scroll-ownership-and-derived-input-focus.md):
  the confined `.vv-content` scroll owner the windowing and `:git-graph/reveal-row` obey, and the
  window-capture listener the `[data-vv-keynav]` carve-out amends.
- [ADR-0033 — Asynchronous text input](0033-asynchronous-text-input.md): the `async-input` +
  search-model parts inside the reused branch combo.
- [ADR-0034 — Expansion-scoped file-tree watchers](0034-expansion-scoped-file-tree-watchers.md):
  the ownership discipline the watch-owner union preserves across two surfaces.
- [ADR-0036 — Piped-stdin documents and explicit file types](0036-stdin-documents-and-explicit-file-types.md):
  the spill lifecycle behind every commit diff these features open.
- [ADR-0038 — Diff documents adopt the project they describe](0038-diff-documents-adopt-described-project.md):
  the `:git` opt-out that keeps those diffs out of the Files tree.
- [ADR-0039 — The Commits sidebar tab and the git data layer](0039-commits-sidebar-and-git-data-layer.md):
  the data layer, store, fold, and selection all three features build on — and the ADR whose
  watcher-event rows this one refactors.
- [Feature 33 — Commit Graph](../features/33-commit-graph.md) ·
  [Feature 34 — Git blame & file history](../features/34-git-blame-and-file-history.md) ·
  [Feature 32 — Commits tab](../features/32-commits-tab.md) ·
  [Feature 31 — Diff project adoption](../features/31-diff-project-adoption.md) ·
  [Feature 28 — Diff rendering](../features/28-diff-rendering.md) ·
  [Feature 13 — Source preview](../features/13-source-preview-tree-sitter.md) ·
  [Feature 04 — File tree and filter](../features/04-git-file-tree-and-filter.md).
- Reference: [IPC channels](../reference/ipc-channels.md) ·
  [events/effects/subs](../reference/events-effects-subs.md) ·
  [namespaces](../reference/namespaces.md) ·
  [state schema](../architecture/04-state-schema-reference.md) ·
  [threat model](../security/threat-model.md#the-git-data-layer-adr-0039).
