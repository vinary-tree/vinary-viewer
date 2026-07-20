# 0030 — Fallback project roots for files outside any git repository

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** Vinary Tree (maintainer)

## Context

The Files tab was populated by exactly one mechanism: `git rev-parse --show-toplevel`, run from the
opened file's parent directory ([`repo-tree`](../../src/vinary/main/service.cljs)). When that failed —
the file lives in no repository, or `git` is not on `PATH` — `send-tree!`'s `when-let` sent nothing,
`:tree/received` never fired, and the file never reached the sidebar.

The failure was silent and, worse, ambiguous:

- with no other project loaded, the sidebar read **"No files open"** while a document was plainly open;
- with other projects loaded, the active file was simply **absent**, with nothing to indicate it existed.

Scratch notes in `/tmp`, a downloaded PDF, a standalone `.org` file in a home directory — none of it was
navigable. Yet the renderer had been multi-root for some time: `[:ui :projects]` is a **vector** of
`{:root :files}`, `file-tree` renders one `<details.vv-project>` per entry, and `:tree/received` already
upserted keyed by `:root`. The capability was entirely present on the consumer side; only the **producer**
declined to emit a root.

## Decision

**When a path belongs to no git repository, adopt its containing directory as a project root of its own**,
and send it over the existing `vv:tree` channel marked `:synthetic? true`.

### 1. The root — a directory is its own root; a filesystem root is refused

`service-util/fallback-root` picks the directory to adopt from precomputed inputs (the `route` pattern —
pure decision, effects at the edge):

| Opened path | Root adopted |
|---|---|
| a **file** `/notes/a.md` | `/notes` (its parent) |
| a **directory** `/notes` | `/notes` **itself** |
| a file at `/a.md` | *(refused)* |
| `C:\`, `\\server`, `\\server\share` | *(refused)* |

A directory must be its own root: `open!` is called with directories (they open as a tab), and
`path/dirname` of `/notes` is `/`. Turning the whole filesystem into a "project" is never useful, so
`filesystem-root?` refuses every spelling of a root — POSIX `/`, a Windows drive root, and a UNC
server/share, which node's own `path/dirname` likewise reports as its own parent. The UNC test is gated
on the backslash spelling so a POSIX `//foo` — legal, and not a root — is not mistaken for a share.

The home directory is **not** refused. Opening `~/notes.md` and getting a browsable `~` is reasonable, and
the walk bounds below keep it cheap.

### 2. The listing — a bounded, breadth-first walk

`dir-walk/walk-dir` is the non-git counterpart of `git ls-files`, emitting root-relative, `/`-joined paths
so the tree view's `(str root "/" %)` reconstitution is unchanged.

A git repository is **self-delimiting** (the root bounds it) and **self-filtering**
(`--exclude-standard` consults `.gitignore`). An arbitrary directory is neither, so the walk supplies both
properties itself:

```math
\text{listed}(f) \iff \operatorname{depth}(f) \le D \;\wedge\; \operatorname{rank}(f) < N
\;\wedge\; \neg\,\exists\, d \in \operatorname{ancestors}(f) : \operatorname{skip}(d)
```

with `$D = 6$` and `$N = 5000$` (`service-util/walk-limits`), and

```math
\operatorname{skip}(d) \iff d \text{ starts with ``.''} \;\vee\; d \in \textit{heavy-dirs}
```

where *heavy-dirs* = `node_modules`, `target`, `dist`, `build`, `out`, `__pycache__`.

Three properties are deliberate:

- **Breadth-first.** When a cap is hit, the user gets a useful *shallow* tree rather than one
  arbitrarily deep branch. This is the whole reason the walk is not the obvious recursive descent.
- **Hidden *directories* are skipped; hidden *files* are kept.** One rule covers `.git`, `.venv`,
  `.cache`, `.next`, `.tox`. Keeping hidden files is parity: `git ls-files --exclude-standard` lists
  `.gitignore`, so the fallback does too.
- **Symlinks are resolved but never descended.** A `Dirent` reports the *link's* own type, so a symlink is
  neither `isDirectory` nor `isFile`; it is `stat`ed through to its target and listed when that target is
  a file. A symlinked **directory** is never entered — a cycle (`ln -s . self`) would not terminate.

An unreadable directory contributes nothing rather than aborting the walk.

### 3. The root is realpath'd

`git rev-parse --show-toplevel` returns a **resolved** path; `path/dirname` does not. Since the renderer
dedupes roots by exact string equality, a directory reached through a symlink would otherwise land in the
sidebar **twice**, once per spelling. `dir-tree` therefore `realpathSync`es the root, falling back to the
unresolved path if that fails.

### 4. Containment, not just equality — synthetic roots yield

Exact-root dedup already existed, but it cannot see that `/notes/sub` sits *inside* `/notes`. Without
containment, opening a few files under one directory stacks up overlapping trees of the same files.
`vinary.app.projects/merge-project` therefore applies:

1. a root **already present** is replaced **in place** — re-opening a file refreshes its tree without
   reordering the sidebar;
2. a synthetic root covered by a known **synthetic** root does not become a second tree; instead its
   freshly walked subtree is **merged into** that root, re-based onto it (`/notes/sub`'s `c.md` becomes
   `/notes`'s `sub/c.md`), replacing whatever that subtree previously held;
3. a synthetic root covered by a **git** root is **dropped** outright — git re-lists the entire
   repository on every open of its own, so its listing is never stale;
4. otherwise it is appended, and any **synthetic** root it now covers is **absorbed** (the broader view
   wins, so `/notes/sub` followed by `/notes` leaves one tree, not two).

Containment only ever removes **synthetic** roots. The asymmetry is the point: **a git root is a fact, a
synthetic root is an inference.** A git repository nested inside a browsed directory is a project in its
own right and must survive being enclosed.

**Why rule 2 merges rather than drops.** Dropping was the original decision, and end-to-end testing
falsified it. `git ls-files --others` guarantees that *the file you just opened is in the tree* — the
precise invariant [`test/git-tree-smoke.js`](../../test/git-tree-smoke.js) exists to protect, and the
v0.2 bug it was written for. With a plain drop, a file created **after** its ancestor was walked never
appeared: opening `/notes/sub/brand-new.md` produced the root `/notes/sub`, which `/notes` covered, so the
update was discarded and `/notes`'s stale listing stood. Merging the walked subtree restores the
invariant, and because the incoming walk is a complete listing of that subtree, it also drops files that
have since been deleted. A file added in an *unrelated* subtree stays stale until something under it is
opened — at which point that subtree refreshes by the same rule.

`under?` compares on **segment boundaries**, so `/a/bc` is not under `/a/b` — a bare `starts-with?` would
silently merge sibling directories with a shared prefix.

### 5. Removal — the sidebar gains a way back

`[:ui :projects]` previously only ever accumulated; nothing pruned it, not even closing a tab. Browsing
scratch files would now steadily clutter the sidebar, so the project header's context menu (a new
`:project` target kind, distinct from `:dir`) gains **Remove from Files** → `:tree/remove-project`.

Removal is a sidebar decision, not a persisted exclusion: the root returns if a file under it is opened
again, since `send-tree!` runs from `open!` and not from a watcher refresh.

## Consequences

- A file outside every repository is now navigable from the Files tab; the "No files open" sidebar with a
  document plainly open is gone.
- `vv:tree`'s payload gains `:synthetic?`. It round-trips unchanged (`clj->js` → structured clone →
  `js->clj :keywordize-keys true`).
- **`vinary.main.dir-walk` is a new namespace, deliberately Electron-free.** `vinary.main.service` cannot be
  required outside Electron (it pulls electron, chokidar, and the content service), so the walk lives
  beside it rather than inside it, and the `:node-test` build exercises the **real** walk against real
  temporary directories ([`test/vinary/main/dir_walk_test.cljs`](../../test/vinary/main/dir_walk_test.cljs))
  instead of a hand-copied mirror.
- **A new end-to-end harness runs the real main process.** Every pre-existing Electron harness mocks the
  `vv:open` seam, so none of them would have executed `send-tree!` at all.
  [`test/tree-e2e.js`](../../test/tree-e2e.js) (`npm run test:tree-e2e`) requires the real
  `dist/main/main.js` inside Electron and asserts against both `window.__vvdb()` and the sidebar DOM,
  including the context-menu removal path. See
  [engineering/03-test-strategy.md §3.5](../engineering/03-test-strategy.md).
- The fallback sits inside `open!`'s existing archive/remote gate, so `vv-archive://` and `ssh://` URIs
  still synthesize nothing.
- No renderer subsystem needed changing: the tree view, the command palette's fuzzy finder, and
  `visible-tree-paths` all read `[:ui :projects]` generically.

## Alternatives considered

- **List only the opened file.** Minimal and zero-risk, but the Files tab would be barely more useful than
  the tab bar. Rejected.
- **List immediate children only** (reusing `list-dir`). One `readdir`, always fast — but subfolders would
  be dead ends, unlike every git project root beside them. Rejected: the sidebar should not behave
  differently depending on whether a directory happens to be a repo.
- **Exact-root dedup only** (no containment). Simpler, and it *is* what the existing upsert did — but it
  produces visibly duplicated, overlapping trees the moment you open two files at different depths.
  Rejected.
- **Recursive descent instead of BFS.** Simpler to write; degrades badly at the cap, surfacing one deep
  branch and hiding every top-level file. Rejected.
- **A renderer-side hook** (e.g. on `nav-result`). The renderer is sandboxed — no `fs`, no `git` — so it
  would need a new IPC round-trip to re-implement what `send-tree!` already does, and it would miss
  `:facet/ensure-loaded`, which calls `window.vv.open` directly without creating a tab. Rejected.

## See also

- [ADR-0009 — Mediator IPC over point-to-point](0009-mediator-ipc-over-point-to-point.md): why the renderer
  reaches `git` and the filesystem only through main.
- [ADR-0006 — Multi-watcher live refresh](0006-multi-watcher-live-refresh.md): rejects recursive whole-tree
  *watching*; this ADR adds a bounded one-shot *walk*, which is not the same commitment.
- [Feature 04 — File tree and filter](../features/04-git-file-tree-and-filter.md).
- [`docs/architecture/04-state-schema-reference.md`](../architecture/04-state-schema-reference.md) for
  `[:ui :projects]`.
