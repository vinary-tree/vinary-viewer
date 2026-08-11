# 0038 — Diff documents adopt the project they describe (Files-tree `:extras`)

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** vinary-viewer maintainers

## Context

A diff is *about* a repository, but the Files tree never learned that. Two gaps, one per way a diff
arrives:

1. **A piped diff sent no tree at all.** `git diff | vv -t diff` (ADR-0036) opens the spilled
   snapshot, and `open!` deliberately skipped the sidebar for it:

   ```clojure
   ;; a piped-stdin document skips the sidebar tree like an archive does: its spill dir is not a project,
   ;; and adopting $XDG_RUNTIME_DIR as a synthetic root would be pure noise.
   (when-not (or (archive-uri? path) (doc-overrides/stdin-doc? path)) (send-tree! wc path))
   ```

   That skip was right about the spill directory — and blind to the document. A piped **diff**
   names its repository twice over: the doc-overrides entry already records the invoking cwd
   (`:cwd`, kept for the side-by-side view's enrichment), and the diff's own `a/… b/…` paths are
   repository members.

2. **An on-disk diff only ever showed the tree of its own location.** `send-tree!` roots at the
   opened file — `repo-tree` runs `git rev-parse --show-toplevel` from the *diff's* directory. A
   patch reviewed from `~/patches/fix.patch` produced a synthetic `~/patches` root
   (ADR-0030), while the checkout it describes — the thing you would actually navigate while
   reading it — never appeared.

Everything needed to close both gaps already existed. The renderer has been multi-root since
ADR-0030 (`[:ui :projects]` is a vector with containment-aware merge rules), and every local diff
already resolves its referenced paths through `vv:load-diff-sources` the moment it opens
(ADR-0026's navigable file headers): the resolved targets literally enumerate files of the
described repository. Only the producer declined to say so — the same shape of gap ADR-0030 closed
for non-repo files.

## Decision

**When a local diff document's provenance proves a git repository, main pushes that repository as a
normal `vv:tree` project entry, with the diff itself attached via a new optional `:extras` key** —
listed inside the project like an ordinary member even when it does not live under the root. Two
seams produce the push, both ending in the existing `send-tree-entry!`; nothing about tree delivery,
merge, or watching changes shape.

### The payload

```clojure
{:root "/abs/repo" :files [...] :synthetic? false
 :extras [{:path "/abs/spill-or-file" :name "(piped diff)" :kind "diff" :transient? true}]}
```

`:extras` rides the **project entry**. Scoped (watcher/refresh) payloads never carry or touch it —
the scope branch of `projects/apply-tree-update` only replaces `:files`, verified by
`extras-untouched-by-scoped-updates`.

### Seam 1 — `open!`, stdin diffs (`send-stdin-diff-tree!`)

The skip becomes a `cond` (`service.cljs`): archive → nothing; stdin →
`send-stdin-diff-tree!`; else the unchanged `send-tree!`. `send-stdin-diff-tree!` gates on the
document's **effective kind** being `"diff"` (`doc-overrides/effective-kind` — an explicit `-t diff`
or a later Settings ▸ File Type pick both count) and on **no `:git` override** (R7 below), reads the
invoking cwd from `doc-overrides/stdin-base-dir`, and runs the existing `repo-tree` on it (it
already accepts a directory). A repo found → record the adoption and send the entry through
`with-diff-extra`; none found → the ADR-0036 skip stands, verbatim. `send-tree!` itself is
untouched — `git-tree-smoke` regex-binds its shape.

### Seam 2 — `vv:load-diff-sources`, all local diffs (`adopt-diff-project!`)

After `load-diff-sources` resolves `{rel → {:path abs}}` for a local diff, the handler calls
`(when includePaths (adopt-diff-project! (.-sender e) diffPath resolved))` — the `includePaths`
pass is the eager path-only lookup that runs whenever a diff opens, so adoption needs no new
request. `adopt-diff-project!` takes each resolved target's directory, computes its toplevel with
`git rev-parse --show-toplevel` — short-circuiting directories already under a found root via the
existing `relative-scope` — picks `service-util/dominant-root`, and sends `repo-tree root` through
`with-diff-extra`. A per-window `adopted-diff-roots` atom (`wc-id → {diff-path root}`) makes
repeats free: Split rebuilds, re-renders, and watcher-driven re-resolutions re-enter the handler
but re-adopt nothing. Cost is bounded to one `rev-parse` per distinct target directory not already
under a found root and one `ls-files` per actual adoption.

The guard's lifetime matches document retention: `unwatch-file!` drops the diff's entry from every
window's sub-map when retention releases the diff (reopening re-adopts cleanly), and
`release-window!` drops the window's whole map.

### Decisions in detail

The labels below are the planning ledger's, kept verbatim because the shipped code cites them
(`service-util/dominant-root`'s docstring cites D10); the ledger assigned no D2.

- **D1 — git repositories only.** No synthetic adoption from a cwd: `curl … | vv -t diff` from
  `$HOME` would adopt the home directory as a project — pure noise. An **on-disk** diff in a
  non-git directory already gets its synthetic root from the unchanged `send-tree!` (ADR-0030), so
  nothing is lost.
- **D3 — no extra when the diff lies inside the adopted root.** `with-diff-extra` checks with the
  existing `relative-scope`; `service-util/diff-extra` returns nil for `:inside-root?`. `git
  ls-files --others` already lists an in-repo patch as an ordinary untracked row — a second, pinned
  row would be a double listing.
- **D4 — extras ride the project entry, never `:files`.** `:files` are root-relative and the view
  reconstitutes `(join-path root file)`; an outside-root absolute path cannot be encoded that way
  without inventing `../` rows that every consumer (scopes, watchers, filters) would misread.
- **D5 — lifecycle mirrors the backing file.** A `:transient?` extra (stdin) is pruned by the
  **renderer** when document retention drops its path: `retention-fx` conditionally dispatches the
  new `:tree/prune-extras` (`projects/prune-extras`), at exactly the retention edge where main
  unlinks the spilled temp file. An on-disk extra persists like any tree row and leaves with
  **Remove from Files**.
- **D6 — `:name` is `"(piped diff)"` for stdin, the basename otherwise.** Decided main-side in
  `service-util/diff-extra`: the spill's real basename is the cryptic `stdin`.
- **D7 — rendered pinned above the folders, reusing file-row semantics.** `extra-row` emits
  `a.vv-file.vv-file-extra` (italic, one CSS rule) through a `file-attrs` helper factored out of
  the ordinary file row, so active-highlight, reveal, Ctrl+click new-tab, and the `:file` context
  menu come free. The icon lookup falls back to `".<kind>"` when the display name carries no
  extension, so `"(piped diff)"` still gets the code glyph.
- **D8 — the filter matches extras by `:name`,** and a project survives when files *or* extras
  survive (`tree-model/filtered`); `visible-tree-paths` prepends extras so keyboard order matches
  visual order. `tree-state/active-scopes` gains an extras arm: activating an extra (a path under
  no project) reveals its adopting project's root.
- **D9 — remote (`ssh://`/`sftp://`) diffs are a structural no-op.** The handler's remote branch
  is untouched; remote adoption is explicitly out of scope.
- **D10 — a multi-repo diff adopts the dominant root.** `service-util/dominant-root`: the most
  frequent non-nil toplevel, first-seen winning ties (a strictly-greater fold over the
  first-seen-ordered seq — a CLJS hash map would not preserve insertion order).
- **D11 — no DataScript changes.** Extras live in `[:ui :projects]` beside the trees they decorate;
  the document cache is not involved.

### R7 — the commit-diff opt-out (forward-compatible)

Both seams skip documents whose doc-overrides entry carries `:git`:
`doc-overrides/git-info` returns the commit-diff facts (`{:root :from :to}`) for a *git-produced*
diff document, and `send-stdin-diff-tree!` / `adopt-diff-project!` bail when it is non-nil. The
reader ships now and returns nil for everything — inert until the git data layer registers `:git`
keys for commit diffs it spills — so those future documents are excluded by construction rather
than by a later retrofit: a commit diff is a transient synthetic that dies with its tab, not a
project member.

### The renderer merge rules

`projects/merge-extras` unions two `:extras` vectors, deduped by `:path` with the incoming entry
winning (a re-send may relabel), first-seen order kept, nil when empty. `merge-project`'s four
branches each account for extras: the **in-place replace branch must union** — a root-level
Refresh or a plain re-open of a repo file sends a full, extras-less entry, and replacement would
silently wipe the attached diffs (pinned by `extras-union-on-in-place-replace` and a dedicated
tree-e2e check); the covered-by-synthetic and absorb branches carry extras defensively (extras
never ride synthetic entries today — D1 adopts git roots only); the covered-by-git drop loses
nothing for the same reason.

## Consequences

- `git diff | vv -t diff` shows the invoking repository in Files with an italic **(piped diff)**
  row pinned at the top, clickable like any file; closing the tab removes the row at the same
  retention edge that unlinks the spill. `vv ~/patches/fix.patch` adds the described checkout
  (alongside `~/patches`' own synthetic root, per ADR-0030's unchanged rules) with `fix.patch`
  attached persistently. A patch **inside** its repo stays a single ordinary row.
- `vv:tree`'s payload gains `:extras`, which round-trips exactly as `:synthetic?` did in ADR-0030
  (`clj->js` → structured clone → `js->clj :keywordize-keys true`; the `?`-suffixed keys survive
  keywordization intact).
- Seam 2 spends synchronous `rev-parse`/`ls-files` calls on the main process — bounded to one per
  distinct target directory / one per adoption, and amortized to zero by the `adopted-diff-roots`
  guard (the same synchronous-git trade-off features 04 records for `repo-tree`).
- Test surface: pure seams in `core_test` (`diff-extra-entries`, `dominant-root-selection`);
  merge/prune/filter/scope semantics in `projects_test` (four extras deftests, including the
  dedupe and extras-less-refresh cases), `tree_model_test` (`filtering-extras`), and
  `tree_state_test` (`active-scopes-for-extras`); wiring pinned by four source-binding asserts in
  `git-tree-smoke.js` (both seams are silent no-ops when unplugged); and a hermetic
  outside-the-repo patch fixture in `tree-e2e.js` (four checks: adoption + extra shape, the
  DOM-rendered revealed row, union across an extras-less refresh, and the in-repo single-listing
  guard).
- **Stdin adoption has no true e2e** — the harness cannot pipe to the daemon. Accepted residual
  risk, covered by the pure units, the seam-1 source-binding smoke asserts, and seam 2's e2e
  equivalence (both seams share `with-diff-extra` → `send-tree-entry!` and the renderer path).

## Alternatives considered

- **Adopt a synthetic root from the invoking cwd** when it is not a repository. Rejected: piping
  from `$HOME` or `/` would inject a giant, irrelevant walk into Files ($HOME noise) — exactly
  what ADR-0036's skip existed to prevent. Git-only keeps the inference a fact (ADR-0030's
  fact-vs-inference asymmetry).
- **Encode the diff into `:files`.** Rejected as impossible for the interesting case: `:files` are
  root-relative by contract, and the adopted repository's most useful diffs live *outside* it.
- **A main-pushed removal channel for pruning transient extras.** Rejected: the renderer's
  retention model already knows the edge — `retention-fx` runs on every tab/history change and
  main unlinks the spill from the very same retained-set sync, so a second push channel would
  duplicate an existing signal and could only race it.
- **Per-tab extras.** Rejected: projects are window-scoped (`[:ui :projects]` serves every tab),
  and an attachment that vanished when you switched tabs would contradict the sidebar's whole
  model; transient lifetime is instead tied to *retention*, which is already cross-tab.

## See also

- [ADR-0009 — Mediator IPC over point-to-point](0009-mediator-ipc-over-point-to-point.md): why the
  renderer learns about repositories only through main's pushes.
- [ADR-0026 — Diff rendering, side-by-side, and repo filetypes](0026-diff-rendering-side-by-side-and-repo-filetypes.md):
  `vv:load-diff-sources` and the eager path-only resolution seam 2 rides.
- [ADR-0030 — Fallback project roots](0030-fallback-project-roots.md): the multi-root merge rules,
  the `:synthetic?` payload precedent, and the fact-vs-inference asymmetry this ADR extends.
- [ADR-0034 — Expansion-scoped file-tree watchers](0034-expansion-scoped-file-tree-watchers.md):
  the scoped payloads that deliberately never touch extras.
- [ADR-0036 — Piped-stdin documents and explicit file types](0036-stdin-documents-and-explicit-file-types.md):
  the spill, the overrides registry, and the `:cwd` fact seam 1 consumes.
- [ADR-0037 — Collapsible per-file diff previews](0037-collapsible-diff-file-previews.md): the
  sibling diff-experience ADR of the same cycle.
- [Feature 31 — Diff project adoption](../features/31-diff-project-adoption.md) ·
  [Feature 04 — File tree and filter](../features/04-git-file-tree-and-filter.md) ·
  [Feature 28 — Diff rendering](../features/28-diff-rendering.md).
