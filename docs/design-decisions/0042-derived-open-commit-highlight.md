# 0042 — The opened-commit highlight derives from the active document

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** vinary-viewer maintainers

## Context

The Commits sidebar panel ([ADR-0039](0039-commits-sidebar-and-git-data-layer.md)) and the Commit
Graph document ([ADR-0040](0040-commit-graph-blame-and-history.md)) marked "the commit you opened"
with stored click-state: a plain row click wrote `:single` into the shared per-repo selection
(`[:ui :commits :repos <root> :selection]`) and then activated the commit's diff. Stored state is
exactly wrong for this highlight — it goes stale the moment the active document changes without a
row click. Navigating Back in tab history, switching tabs, or closing the diff left the
last-clicked row highlighted even though the highlighted commit's diff was no longer (or never) the
document on screen.

The correct definition is **derivational**: a row is "open" *iff the active tab's document IS that
commit's diff in that repo*. But the renderer could not evaluate that predicate. A commit diff is a
spilled temp file (`$XDG_RUNTIME_DIR/vinary-viewer/git-diff/<uuid>/<shortA..shortB>.diff`), and its
`{root, from, to}` facts lived only in the main-process doc-overrides registry — the renderer knew
the spill only as a navigated path. Parsing the spill basename renderer-side was rejected outright:
short hashes are ambiguous against the loaded log, the basename carries no repository identity, and
it would couple the highlight to a filename convention.

One further gap: the registry recorded `from`/`to` but not *how* `from` was chosen. The renderer
cannot distinguish a parent diff (main resolved `<to>^`) from an explicit two-endpoint range —
both arrive as two hashes — yet the desired highlight differs: a parent diff marks **one** row
(the commit), an explicit pair/range marks **both** endpoint rows.

## Decision

**Thread the commit-diff facts to the renderer as document data, derive the highlight from the
active document every render, and narrow the stored selection to Ctrl/Shift multi-select marking.**

### 1. The facts ride the payload

- `vinary.main.git/handle-open-diff` registers the override as
  `:git {:root top :from from-sha :to to-sha :range? (boolean (and from (not parent?)))}`.
  `:range?` means "highlight both endpoints" and is recorded at the only place that knows which
  resolution branch ran: `true` for an explicit, verified `from` (pair diff, `A..B`, `A...B`);
  `false` for parent diffs — including the empty-tree root-commit case, whose `from` names a tree
  object no log row can ever carry, so it single-highlights `:to` with no special case.
- The `service.cljs` decorate seams (`decorate-js-payload!` / `decorate-payload`, where `language`
  / `stdin` / `baseDir` already ride) attach `git {root from to range}` to every outgoing
  kind-`diff` `vv:content` payload that has the override. The wire key is `range` (no `?`), per
  the `sourceable`/`paged` precedent; `content_service.js` needs no twin — decoration is
  `service.cljs`-only.
- `:content/received` stores it as `:doc/git {:root :from :to :range?}` for kind `"diff"`, and
  **retracts** a lingering `:doc/git` when a re-send no longer carries the facts or the doc was
  re-typed away from `"diff"` (the `:doc/language` retract precedent). `:doc/git` is added to the
  `ds/active-doc` pull list — the standing rule: a new `:doc/*` attribute is invisible until then.

### 2. The highlight is derived, never stored

- `vinary.app.commits/open-set [git root]` (pure, unit-tested): `#{}` unless `git` is present and
  names this repo; `#{to}` for a parent diff; `#{from to}` when `:range?`.
- Subs: `:doc/git` (kind-guarded off `:doc/active`) → `:commits/open-set <root>`. Layering on
  `:doc/active` gives value-equality dedup — the row components re-render only when the active
  document's identity/kind/git facts change.
- Both surfaces render a new `-open` row class (`vv-commits-row-open` / `vv-gg-row-open`) from that
  set. Because the value is recomputed from the active document, Back-navigation, tab switches,
  facet flips, and closing the diff retarget or clear the highlight with **zero** invalidation
  code — the stale-highlight bug class is unrepresentable.

### 3. The stored selection narrows to multi-select marking

`:selected` now means exactly one thing: the Ctrl/Shift marking that feeds the exactly-two "Diff
selected" gate and the context-menu pair items.

- `commits/select` loses its `:single` mode (replaced behavior, removed rather than entombed; a
  stray `:single` dispatch now fails fast as a `case` miss in dev). `:range`'s out-of-window
  fallback keeps its literal self-anchored single-row map — it never called `:single`.
- Panel plain click = **activate only**. Graph plain click = cursor move only (new
  `:commits/cursor-set`); opening stays on double-click/Enter.
- `:commits/cursor-to`'s non-extend arm is cursor-only; the `:move-only?` option is gone (plain
  and Ctrl arrows now do the same thing; Shift still extends a `:range`).
- `:commits/clear-selection` (previously dead) is wired to **Escape in the Commit Graph**, and
  clears `:anchor`/`:selected` while **keeping `:cursor`** — clearing marks must not teleport
  keyboard focus. Escape is consumed only while marks exist; otherwise it propagates so
  window-level Escape behavior is untouched. The sidebar panel deliberately gets no Escape
  binding: it is not a focus surface, and adding focus management for one key is out of scope.
- `keep-surviving`, `diff-pair`, and the context-menu pair computation are untouched — they only
  ever read `:selected`/`:cursor`.

### 4. The panel keeps showing the repo while its spill is active

`commits/derive-root` never sees a spill path as "inside a project" (spills live outside every
root), so it falls through to `:last-root` — which `:commits/shown` records on every panel mount.
Clicking a row requires a mounted panel, so the panel root survives the activation and the
highlight actually renders. The one uncovered flow — activating from the Commit Graph when the
panel had never mounted — is closed by `:git-graph/shown` now recording `:last-root` too.

### 5. Two visually distinct row states

- `-open` takes the strong `.vv-file-active`-family look: `--vv-highlight` background with a
  `--vv-fg-strong` semibold subject — the same visual claim as the file tree's "this is open".
- `-sel` is restyled as marking: a subtle `--vv-bg2` fill with a 1 px **dashed `--vv-accent`
  ring** (`outline`, so it composes with `-cur`'s inset `box-shadow` bar and with `-open`'s fill —
  a multi-selected endpoint of the open range shows the ring over the strong fill). Side effect:
  multi-selected rows no longer collide with the text-selection colour that `--vv-highlight`
  doubles as; `-open` inherits that known `.vv-file-active` caveat unchanged.

## Consequences

- The highlighted row(s) always answer "what is the active document?", never "what was last
  clicked". A pair diff marks both endpoints while it is on screen; Back to a parent diff
  retargets to its single commit; Back to a non-diff clears everything.
- Under the single content pane, the graph document and a commit diff can never be on screen
  simultaneously — so a *graph* row's `-open` is wired for consistency (and any future split-pane)
  but is unreachable on screen today. The smoke arms therefore assert the panel-side derivation
  plus the graph's negatives (no sticky `-sel`/`-open` after activate + Back).
- One stored selection per repo still feeds both surfaces (R3): marks made in the panel render in
  the graph and vice versa; plain clicks in either surface neither extend nor clear them.
- The `vv:content` payload for git-produced diffs gains the optional `git` field
  (`{root, from, to, range}`); the Commit Graph's existing `git {root}` payload key is now the
  same documented field.
- `test/git-log-smoke.js` pins the registered override shape including `:range?`;
  `doc_overrides_test` pins the round-trip; `commits_test` pins `open-set` and the narrowed
  reducer; the electron smoke drives the full chain (click → spill → payload → derived class) in
  both build flavors, including the reported Back-navigation scenario.

## Alternatives considered

- **Parse the spill basename in the renderer** — rejected: short-hash ambiguity, no repo
  identity, couples correctness to a filename convention.
- **Store "opened commit" in app-db when the activation reply arrives** — rejected: that is the
  same stored-state bug class this change removes; every navigation path (Back/Forward, tab
  switch, close, facet flip, live refresh) would need manual invalidation, and any missed path is
  a stale highlight.
- **A separate main→renderer push announcing the opened commit** — rejected: more IPC surface for
  facts that are document data; the payload decorate seam already exists and carries them with
  the document itself.
