# 0039 — The Commits sidebar tab and the read-only git data layer

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** vinary-viewer maintainers

## Context

vinary-viewer had **no git history surface at all**. The only live git calls in the entire
application were the Files tree's — `ls-files` (twice) and `rev-parse --show-toplevel`, all
synchronous, all in service of listing a repository's *files* — and not a single `.git` directory
was watched. Yet the viewer already renders diffs better than most dedicated tools (ADR-0026's
unified + enriched split views, ADR-0037's collapsible per-file previews, ADR-0038's project
adoption), and a diff pipeline without a way to *ask the repository for a diff* leaves the most
common review gesture — "show me what this commit changed" — outside the application.

The requirements, from the user request that motivated this cycle:

1. a **commit log** with GFM-rendered commit messages;
2. **diffs of arbitrary commits** — a selected commit against its parent by default, with
   free-form ranges;
3. a **filterable branch combo** (local/remote/tags, type-to-filter);
4. a GitLens-style **mini lane-graph rail** beside the log;
5. **strictly read-only git** — no mutating subcommand anywhere, ever. The viewer must be safe to
   point at any repository, including a hostile one: repository config, ref names, and commit
   messages are untrusted input (see the
   [threat-model section](../security/threat-model.md#the-git-data-layer-adr-0039) this ADR adds).

The same request also asked for a full Commit Graph *document*, git blame, and per-file/line-range
history; those land in their own ADR (0040 and the history feature). This ADR covers the sidebar
tab and the data layer both will share — several decisions below (the stored lane fold, the
selection shape, the reserved `lineRange` key) exist precisely so the later surfaces snap onto the
same spine instead of forking it.

## Decision

**A three-layer architecture, pure at the bottom, async in the middle, reactive at the top:**

1. **Pure `vinary.git.*`** — `vinary.git.log` (the exact argv builders and the record parsers) and
   `vinary.git.graph` (deterministic, incremental lane assignment). Electron/DOM/fs-free, so the
   node `:test` build covers them directly and neither knows a subprocess exists.
2. **Async `vinary.main.git`** — the `vv:git-log` / `vv:git-branches` / `vv:git-open-diff` request
   handlers, the ownership-scoped `.git` watcher behind `vv:git-watch` → `vv:git-changed`, and the
   commit-diff spill lifecycle. Electron-free by design (webContents arrive as arguments; the
   service only binds channels), like dir-walk and service-util. Every subprocess is async
   (`run-git` wraps `execFile`, 64 MiB maxBuffer, always-resolving `{:ok}/{:error}`) — the sync
   `git` helper in `vinary.main.service` stays for tiny interactive calls and must never run a log
   or a diff.
3. **The renderer panel** — `vinary.ui.commits` (the sidebar tab), the reusable
   `vinary.ui.combo-select`, and the pure model `vinary.app.commits`, all over one app-db slice
   `[:ui :commits]` (see the
   [state-schema reference](../architecture/04-state-schema-reference.md)). The renderer never
   runs git and never sees unparsed git output — only structured data crosses the seam.

![Sequence — Commits panel log delivery and live refresh](../diagrams/seq-commits-log.svg)

*Diagram source: [`../diagrams/seq-commits-log.puml`](../diagrams/seq-commits-log.puml).*

### Decisions in detail

The labels are the planning ledger's (D1–D10, plus the cross-plan reconciliations R1–R4 shared
with the forthcoming graph/history surfaces), kept because the shipped code cites them.

- **D1 — the panel follows the active document's repository, with an explicit pin.**
  `commits/derive-root` picks, in order: the **pinned** root while its project is still open
  (`:commits/set-root`, offered by a header switcher when more than one git project is open); else
  the **deepest** non-synthetic `[:ui :projects]` root containing the active path; else the
  **last root shown** — the panel must not thrash to a different repo while the user browses
  non-repo files; else the **first** git project. nil ⇔ no git project open (empty state).
  Synthetic (ADR-0030) roots are never candidates — only a git root can serve git data. The panel
  component is *keyed by the derived root*, so a root change remounts the body, which re-syncs
  watcher ownership and ensures that repo's data through the ordinary mount path.

- **D2 — `--date-order`, 250 per page, parsed in main.** The log argv is exactly
  `git log --date-order --max-count=250 --skip=N --pretty=<R1 format> [--follow]
  --end-of-options <ref> [-- <file>]` (`glog/log-args`, pinned byte-for-byte by
  `test/git-log-smoke.js`). `--date-order` is the **cheapest ordering that never emits a parent
  before all of its children** — the lane-assignment precondition. `--topo-order` gives the same
  guarantee but must buffer the full commit walk before printing anything; the default order gives
  no guarantee at all. Commit-date clock skew can still violate the invariant in pathological
  histories, and the lane algorithm **degrades instead of erroring**: a commit no lane expects
  simply opens a fresh lane. Parsing happens in the **main process**: the exec is already async
  there, the parse of a 250-record page is sub-millisecond, and the renderer then receives
  structured commits over IPC instead of a megabyte of text to split at interactive priority.
  Paging is `--skip`-based — page N costs git an O(skip) walk — accepted because pages are only
  ever fetched forward from a human "Load more" click; commit-graph files make even the cold
  `--date-order` start sub-second on very large repos. `exhausted` = count < limit.

- **The nine-field `%x1f`/`%x00` record discipline (R1).** One record per commit:
  `%H %P %an %ae %aI %cI %D %s %b`, fields separated by `%x1f`, records terminated by `%x00`
  (`glog/pretty-format`). git **forbids NUL inside commit text**, so the record boundary is
  unforgeable; a hostile `%x1f` typed *into* a commit message can only corrupt its own record —
  the parser validates the field count (exactly 9) and the 40-hex hash and **drops** what does not
  conform. The committer date rides along for the future graph document's benefit so the format
  never needs a second migration. Plain log and `--follow` file history emit no patch text, so
  splitting on `%x00` is safe for both; `git log -L` line history *does* interleave patches on old
  gits and therefore gets a **separate discipline** (`%x1e` record-start, `--no-patch`, bleed
  discarded) reserved under the request schema's `lineRange` key — validated now, answered with an
  explicit `{error "lineRange unsupported"}` until the history feature lands it. One more
  format-language trap is recorded where it bit: `git log --pretty` spells hex escapes `%xNN`,
  while `git for-each-ref --format` spells them `%NN` — `branches-args` uses `%1f`, verified
  empirically to emit the raw byte.

- **D3 — GFM message bodies, lazily, through the single sanitizer.** A commit *subject* renders as
  an escaped text node, always. A commit *body* becomes HTML only when its row is first expanded:
  `:commits/toggle-expand` dispatches `:commits/render-body`, which runs `md/render-ir` — the same
  sanitizing markdown pipeline every document goes through, never a second HTML path — with
  **base-dir = the repository root**, so relative links in a message resolve exactly as a README's
  would. The result is cached per hash in `:bodies` (pruned to surviving hashes on refresh); a
  render failure stores `false` and the view falls back to a plain-text `<pre>`. Rendering bodies
  eagerly for a 250-row page was rejected: nearly all of that work would be invisible, and a
  hostile repository could make page cost proportional to its message bytes.

- **D4 — a commit diff is a spilled `.diff` *document*, diffed against the FIRST parent.**
  Activating a row asks main for `{root, to hash, parent? true}`; `handle-open-diff` resolves
  `<to>^` via `rev-parse --verify`, and when that fails — a **root commit** — substitutes git's
  well-known **empty-tree hash** `4b825dc642cb6eb9a060e54bf8d69288fbee4904` (R4), so a root
  commit's "diff against my parent" is the same plain `git diff <from> <to>` as every other
  commit: one code path, no `git show` arm, no second parser. Merge commits diff against the first
  parent only (`-m`/`--cc` combined diffs are explicitly out of scope). The diff text spills to a
  private `$XDG_RUNTIME_DIR/vinary-viewer/git-diff/<uuid>/<shortA..shortB>.diff` (dir `0700`, file
  `0600` — the twin of the ADR-0036 stdin spill), and main registers a doc-overrides entry:

  ```clojure
  {:kind "diff" :stdin? true :cwd <root> :git {:root <root> :from <sha> :to <sha>}}
  ```

  Each key buys a specific behavior from machinery that already exists: `:kind "diff"` renders it
  as a diff with all sniffing disabled (ADR-0036); `:stdin? true` buys **MRU exclusion** (a
  transient synthetic does not belong in Open Recent), **no file watcher** (nothing edits it), and
  **unlink-on-release** (the file dies at the same retention edge as its document); `:cwd` is the
  enrichment base directory; `:git` is simultaneously the **ADR-0038 tree-adoption opt-out** (R7 —
  a commit diff is not a project member) and the fact record that switches `vv:load-diff-sources`
  to rev-aware enrichment (D6). The handler returns only `{path title}` and the **renderer**
  navigates (`[:tab/navigate path]`): main cannot create tabs, and navigation must originate where
  history, retention, and facets live. A dedupe atom keyed `[root from to]` makes re-activating
  the same range focus the existing document instead of re-spilling; `forget-spill!` drops the
  entry when retention unlinks the file, and a boot-time `sweep-stale!` (the stdin sweep's twin,
  same 10-minute age gate) clears spills of crashed sessions.

  **Why a spilled file and not a virtual URI** (say, `vv-git-diff://root@from..to`)? Because
  **every downstream subsystem keys on real file paths**: retention and the watcher sync
  (`nav/retained-file-paths` → `vv:retained-files`), the doc-overrides registry itself, the diff
  pipeline's source resolution (`diffPath` → base dir), and the MRU exclusion all take paths. A
  virtual scheme would have needed a parallel arm in each of those — the archive and ssh backends
  show what that costs — for zero user-visible gain, whereas the spill file makes a commit diff an
  **ordinary ADR-0036 citizen** whose whole lifecycle (override, retention, unlink, sweep) already
  existed. The trade — a few kilobytes in the user runtime dir per viewed diff, reclaimed on tab
  close — is recorded and accepted.

- **D5 — ranges: two-commit selection and a free-form grammar, validated by main.** Ctrl+click
  toggles a commit in the R3 selection; Shift+click range-extends from the anchor; a **"Diff
  selected"** pill appears exactly when two commits are selected and diffs them **older→newer**
  (`commits/diff-pair` — log order is newest-first, so the higher index is the older side). The
  free-form input parses with the pure `commits/parse-range`: `A..B` → `{:from :to}`, `A...B` →
  the symmetric-difference form (composed as one argv token from the two *already-verified*
  shas), a single token `R` → `{:to R :parent? true}` — the same semantics as clicking `R`'s row.
  The renderer validates **shape only**; every ref is validated by **main** with
  `git rev-parse --verify --quiet --end-of-options <ref>^{commit}` before any consuming argv runs,
  so an option-shaped ref (`--all`, `--output=…`) is rejected as "unknown revision" rather than
  parsed as an option (the injection guard `git-log-smoke.js` pins). Errors — the renderer's
  `unrecognized range` or main's `unknown revision: …` — surface inline under the input.

- **D6 — rev-aware Split enrichment: blob content, working-tree targets (R8).** The
  `vv:load-diff-sources` handler gained a third arm: a diff whose doc-overrides entry carries
  `:git` enriches through `git/load-rev-sources` instead of the on-disk reader. **Content** comes
  from `git cat-file blob <to>:<rel>` — the diff was *computed from those blobs*, so split-view
  row alignment is exact even when the working tree has drifted since the commit (strictly more
  correct than reading today's file; `git-log-smoke.js` proves the divergence on a hermetic repo).
  `cat-file` is plumbing: no textconv, no smudge filters, no repo-config-selected programs.
  **Targets** (the navigable file headers) still resolve against the **working tree** — a header
  click means "open this file *now*", and a path that no longer exists correctly stays inert. That
  split — historical content, present targets — is why neither half can serve the other's
  purpose. A missing blob (a rename's old path, a binary) contributes nothing and the renderer's
  existing hunk-window fallback covers that file, exactly as on disk. The renderer is unchanged.

- **D7 — the branch combo is a new reusable `combo-select`.** Data:
  `for-each-ref --format=%(refname)%1f%(refname:short)%1f%(HEAD) refs/heads refs/remotes
  refs/tags` plus `rev-parse --abbrev-ref HEAD` → `{head detached? branches [{name kind
  current?}]}` (a detached HEAD labels itself with its short hash). The widget assembles the
  house parts rather than inventing new ones: the combo-button shell idea, the ADR-0033
  `async-input` as the type-to-filter field (a *local* ratom is its model, so the echo contract
  holds trivially), and `vinary.search.match` under a new `:branch-combo` search-config surface
  (`{:mode :substring}` — ref names are matched by fragment, not fuzzily). Rows group into
  Local / Remote / Tags with the current branch first and check-marked; a 200-row cap bounds a
  monster repo's ref list with a "type to filter" hint. The same widget serves the repo switcher
  and, later, the Commit Graph toolbar.

- **D8 — a bounded, ownership-scoped `.git` watcher.** One chokidar instance per repo over exactly
  `[<git-dir>/HEAD, <common-dir>/packed-refs, <common-dir>/refs]`. The two directories are
  resolved separately (`rev-parse --absolute-git-dir` / `--git-common-dir`) because **worktrees
  keep HEAD and refs in different places** — a literal `.git` path would silently watch nothing
  there. The watch is recursive to `depth 4` — an explicit, argued **exception to ADR-0034's
  depth-0 discipline**: that discipline exists because *project trees* are unbounded, while a
  `refs/` tree is tiny (namespaces × refs, no user content), and watching it shallow would miss
  `refs/remotes/origin/…` — the exception honors the rule's *spirit* (bounded, ownership-scoped,
  released deterministically) while acknowledging its premise does not hold here. Ownership is the
  tree-watcher twin: the panel syncs its window's watched-root set over `vv:git-watch` on
  mount/root-change/unmount (`[]` releases), `release-window!` drops a destroyed window's
  ownership, and the last owner out closes the repo's watcher. Events debounce **300 ms per
  root** — a fetch touches many refs and the panel needs ONE refresh — then push `vv:git-changed
  {root}` to live owners. The refresh is deliberately **conservative**: branches + a page-0
  replace only, for repos the panel actually loaded; the `:gen` bump strands any in-flight page
  reply, `commits/keep-surviving` prunes hash-keyed UI state (selection, expanded set, body
  cache) to hashes that still exist, and a viewed ref that vanished (branch deleted) falls back
  to HEAD.

- **The stored incremental lane fold (R2) — one fold site, two consumers.**
  `vinary.git.graph/assign` threads a state (`:lanes`, a vector of the hash each lane expects to
  meet next) through pages of commits and is **property-tested incremental**:
  `assign(s, a++b)` ≡ the rows of `assign(s, a)` followed by the rows of
  `assign(state', b)` at every split point. `:commits/log-received` is the **single fold site**:
  it appends each page's rows into `[:repos <root> :graph {:rows :state :max-lane}]` (page 0
  refolds from `init-state`), so the sidebar rail and the future Commit Graph document read one
  source of truth and "Load more" costs one page's fold, never a recompute. Row shape
  `{:hash :lane :edges [{:from :to :kind}] :active}` with kinds
  `#{:pass :continue :branch :merge :collapse}`; lanes are leftmost-first and reuse freed slots.

- **The shared selection shape (R3).** `:selection {:cursor <hash> :anchor <hash> :selected
  #{hash…}}` — **hash-keyed, never index-keyed**, so it survives a page-0 refresh that reorders
  or drops rows (`keep-surviving` filters by liveness; cursor/anchor fall to nil when their
  commit was rewritten away). The reducers (`commits/select` with `:single`/`:toggle`/`:range`
  modes, `:range` falling back to `:single` when either end left the loaded window) are pure and
  registered once; the Commit Graph document will drive the *same* events rather than growing its
  own selection.

  > **Amended 2026-08-11 by [ADR-0042](0042-derived-open-commit-highlight.md):** the shape and
  > hash-keying stand, but `:selected` narrowed to **Ctrl/Shift multi-select marking only** and
  > the `:single` mode was removed — a plain click activates (panel) or moves the cursor (graph)
  > without writing the selection, and the *opened-commit* highlight is now derived from the
  > active document's `:doc/git`, never stored here.

- **D9 — the mini rail: self-contained SVG cells under `content-visibility`.** Each row carries
  its own small `<svg>` (10 px lanes × 40 px rows, dot radius 3.5): a cell is a pure function of
  *(previous row's edges, this row's edges, this row's lane)* — the previous row's edge **targets**
  supply the ink arriving at this row's top edge, the row's own edges supply the dot-anchored and
  pass-through ink, and quadratics with mid-height control points keep the halves visually
  continuous at every row border. Self-containment is what makes the cells windowing-compatible:
  `.vv-commits-row` uses `content-visibility: auto` + `contain-intrinsic-size: auto 40px`, so
  off-screen rows skip layout/paint without any JS. A `virtual_layout`-style JS windowing pass
  (spacers + band arithmetic, as the streaming scrollbar uses) was **rejected for v1**: the DOM is
  bounded (pages arrive 250 at a time, by explicit clicks), and the panel does not own its
  scroller — the sidebar body does — so spacer arithmetic would couple the panel to a container it
  does not control; the upgrade path is recorded here for the day profiling disagrees. Lanes cap
  at 8 in the sidebar (deeper lanes clamp to the last slot; colors cycle `lane mod 8`); the
  palette is `--vv-lane-0..7`, defined in **all four** theme files and enforced complete by
  `test/lint.js`'s theme-variable check.

- **D10 — every empty/error state is a sentence, not a hang.** No git project open → the panel
  says so; a missing git binary reads as `git is not available on PATH` (never a raw ENOENT); an
  empty repository answers `{:commits [] :exhausted true :empty true}` → "No commits yet"; the
  first load shows "Loading history…"; range typos and unknown revisions surface inline under the
  range input; output past the 64 MiB maxBuffer reads `git output too large (>64 MiB)`. A stale
  preload (running daemon older than the renderer) degrades through the fx layer's
  feature-detection to "git bridge unavailable (restart the viewer daemon)".

![Sequence — a commit diff from row click to rev-aware Split](../diagrams/seq-git-open-diff.svg)

*Diagram source: [`../diagrams/seq-git-open-diff.puml`](../diagrams/seq-git-open-diff.puml).*

## Consequences

- **New IPC surface, new threat surface, documented together.** Four channels (`vv:git-log`,
  `vv:git-branches`, `vv:git-open-diff` invokes; `vv:git-watch` send) plus one push
  (`vv:git-changed`) join the [IPC reference](../reference/ipc-channels.md), and the
  [threat model gains a git-data-layer section](../security/threat-model.md#the-git-data-layer-adr-0039):
  argv arrays and never a shell, `--end-of-options` everywhere a ref follows, rev pre-validation,
  `--no-ext-diff`, `cat-file` plumbing for blob reads, subjects as text and bodies through the one
  sanitizer, private `0700`/`0600` spills, and **no mutating subcommand in the namespace** — the
  layer is queries (`log`, `for-each-ref`, `rev-parse`, `diff`, `cat-file`) by construction.
- **The sidebar gains its fourth tab** (Files · Contents · Tabs · **Commits**), and the renderer
  gains 20 `:commits/*` events, 5 effects (`:vv/git-log|git-branches|git-open-diff|git-watch`,
  `:commits/render-body`), and 4 subscriptions — all catalogued in the
  [events/effects/subs reference](../reference/events-effects-subs.md), with the `[:ui :commits]`
  slice in the [state-schema reference](../architecture/04-state-schema-reference.md).
- **Every theme now carries the lane palette.** `--vv-lane-0..7` (Spacemacs accents; the light
  theme adjusts lanes 0 and 5 for contrast) in both theme directories, kept complete by lint.
- **A commit diff is an ordinary document.** It opens in a tab, obeys history and retention,
  renders unified and split, collapses per file (ADR-0037), and its Split is *more* correct than
  an on-disk diff's (blob-exact alignment, D6). It never joins Open Recent and never adopts into
  the Files tree; its spill dies with its tab.
- **The data layer is already shaped for what follows.** The stored fold (R2), the shared
  selection (R3), the committer-date field, and the reserved `lineRange` key are load-bearing for
  the Commit Graph document and blame/history (ADR-0040 and onward); `git log -L` line-range
  history is **not** in this ADR — its record discipline is specified (R1) and its request key
  answers an explicit error today.
- **Test surface.** Pure layers in the node `:test` build: `test/vinary/git/log_test.cljs` (argv
  verbatim, nine-field parsing incl. hostile records dropped), `test/vinary/git/graph_test.cljs`
  (linear/diamond/octopus/criss-cross/skew topologies and the paging-equivalence property at
  every split), `test/vinary/app/commits_test.cljs` (root derivation, the range grammar, the
  selection reducers, refresh survival), and the `git-info-facts` deftest in
  `doc_overrides_test.cljs`. `test/git-log-smoke.js` runs the contract against a **real hermetic
  repository**: the argv byte-for-byte, record round-trips with newlines and `", "` in bodies,
  `--date-order`'s children-first output, the rev-parse injection guard rejecting `--all`, the
  `cat-file`-vs-drifted-worktree divergence (the D6 proof), the empty-repo message, and
  source-binding regexes that fail loudly if any seam is unplugged.

## Alternatives considered

- **Parse git output in the renderer.** Rejected: the renderer would either block its interactive
  thread splitting megabytes of text or grow another sliced-work scheduler client for a task main
  performs in under a millisecond after an exec it must own anyway (the renderer has no
  subprocesses). Structured IPC replies also keep unparsed, hostile-adjacent bytes out of the
  renderer entirely.
- **`--topo-order`, or git's default order.** The default order gives no children-before-parents
  guarantee at all — lanes would be wrong routinely, not pathologically. `--topo-order` gives the
  guarantee but buffers the entire walk before the first record, hurting exactly the first-paint
  path the panel cares about; `--date-order` prints incrementally and its failure mode (clock
  skew) degrades to a cosmetic fresh lane rather than an error.
- **A virtual URI scheme for commit diffs** (`vv-git-diff://…`). Rejected in D4: every downstream
  subsystem — retention, doc-overrides, diff-source resolution, MRU — keys on real paths, and a
  scheme would need a parallel arm in each; the spill file reuses the ADR-0036 lifecycle wholesale.
- **Eager GFM rendering of message bodies.** Rejected in D3: page cost would scale with message
  bytes a user mostly never expands, through the markdown pipeline at interactive priority.
- **A `git show` arm for root commits.** Rejected in R4: `git show`'s output framing differs
  enough to need a second parse path; substituting the empty-tree hash keeps one `git diff`
  invocation for every case.
- **JS windowing (`virtual_layout`) for the rail/list.** Rejected for v1 in D9:
  `content-visibility` already skips off-screen work for a DOM that is bounded by explicit paging,
  and the panel does not own the scroller the spacer arithmetic would have to command.

## See also

- [ADR-0009 — Mediator IPC over point-to-point](0009-mediator-ipc-over-point-to-point.md): the
  single preload seam the four new channels ride.
- [ADR-0026 — Diff rendering, side-by-side, and repo filetypes](0026-diff-rendering-side-by-side-and-repo-filetypes.md):
  the diff pipeline a spilled commit diff enters unchanged, and `vv:load-diff-sources`.
- [ADR-0033 — Asynchronous text input](0033-asynchronous-text-input.md): the `async-input` and
  search-model parts `combo-select` assembles.
- [ADR-0034 — Expansion-scoped file-tree watchers](0034-expansion-scoped-file-tree-watchers.md):
  the ownership discipline the `.git` watcher twins — and the depth-0 rule D8 argues its bounded
  exception to.
- [ADR-0036 — Piped-stdin documents and explicit file types](0036-stdin-documents-and-explicit-file-types.md):
  the spill + doc-overrides lifecycle a commit diff adopts wholesale.
- [ADR-0038 — Diff documents adopt the project they describe](0038-diff-documents-adopt-described-project.md):
  the R7 `:git` opt-out registered here and honored there.
- [Feature 32 — Commits tab](../features/32-commits-tab.md) ·
  [Feature 31 — Diff project adoption](../features/31-diff-project-adoption.md) ·
  [Feature 28 — Diff rendering](../features/28-diff-rendering.md) ·
  [Feature 04 — File tree and filter](../features/04-git-file-tree-and-filter.md).
- Reference: [IPC channels](../reference/ipc-channels.md) ·
  [events/effects/subs](../reference/events-effects-subs.md) ·
  [namespaces](../reference/namespaces.md) ·
  [state schema](../architecture/04-state-schema-reference.md) ·
  [threat model](../security/threat-model.md#the-git-data-layer-adr-0039).
