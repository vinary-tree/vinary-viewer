# Diff project adoption — the repository a diff describes joins Files

**Status: Available now.**

---

## 1 · What it is

A diff is *about* a repository, so opening one now puts **that repository** into the sidebar Files
tab — with the diff itself listed inside it, pinned above the folders as an italic row that opens,
highlights, and right-clicks exactly like any other file. Two arrival routes are covered
([ADR-0038](../design-decisions/0038-diff-documents-adopt-described-project.md)):

- **A piped diff** — `git diff | vv -t diff` ([ADR-0036](../design-decisions/0036-stdin-documents-and-explicit-file-types.md))
  used to send *no* tree at all (a spill directory under `$XDG_RUNTIME_DIR` is not a project). Now
  the repository of the **invoking cwd** appears in Files, with the diff pinned as **(piped
  diff)**. The row is *transient*: it disappears when the diff's tab closes, at the same moment
  main unlinks the spilled snapshot.
- **An on-disk `.patch`/`.diff` stored outside its checkout** — reviewing `~/patches/fix.patch`
  used to show only a synthetic `~/patches` tree ([ADR-0030](../design-decisions/0030-fallback-project-roots.md)).
  Now the checkout the patch *describes* joins Files too (both roots coexist), with `fix.patch`
  attached as a *persistent* row that survives refreshes and watcher updates until you **Remove
  from Files**.

A patch that already lives **inside** its repository is deliberately *not* attached: `git ls-files
--others` already lists it as an ordinary untracked row, and a second, pinned listing would be a
double. Adoption is **git-repositories-only** — a diff piped from a non-repo directory keeps the
old no-tree behavior rather than adopting `$HOME` as noise.

---

## 2 · How to use it

1. In any checkout, run `git diff | vv -t diff` (or `git show … | vv -t diff`). The diff opens as
   usual ([feature 28](28-diff-rendering.md)), and the repository appears in Files with an italic
   **(piped diff)** row pinned at the top of the project.
2. Or open a patch stored elsewhere: `vv ~/patches/fix.patch`. The described checkout joins Files
   with `fix.patch` pinned inside it — alongside the patch directory's own root.
3. **Open the diff from the tree:** click the pinned row (single click on Linux, double on
   Windows/macOS). It activates the diff's tab, exactly as clicking any file row does; the row
   carries the active highlight while the diff is the shown document.
4. **Ctrl+click** opens it in a new tab; **right-click** opens the ordinary `:file` context menu.
5. **Filter:** the *Filter files…* box matches attached rows by their display name — typing
   `piped` keeps the project visible with just the **(piped diff)** row, even when no ordinary
   file matches. Keyboard tree navigation (`:tree/move`) walks the pinned rows first, matching
   the visual order.
6. **Take it away:** close the piped diff's tab (the transient row vanishes with the spill), or
   **Remove from Files** on the project header for an adopted checkout with a persistent row.

**Example.** From `~/Workspace/f1r3fly.io/vinary-viewer`, run `git diff | vv -t diff`. Files shows
the `vinary-viewer` project; at its top sits *(piped diff)* in italics. Click around the repo while
reading the diff; click *(piped diff)* to return to it. Close the tab — the row (and the project's
claim to it) is gone. Re-pipe and it comes back.

---

## 3 · How it works internally

Both routes end in the same, unchanged delivery: a normal `vv:tree` push whose project entry
carries a new optional `:extras` key:

```clojure
{:root "/abs/repo" :files [...] :synthetic? false
 :extras [{:path "/abs/spill-or-file" :name "(piped diff)" :kind "diff" :transient? true}]}
```

`:extras` rides the **entry**, never `:files` — files are root-relative and reconstituted with
`(join-path root file)`, which cannot encode an outside-root absolute path. Scoped watcher payloads
never carry or touch extras.

### MAIN — seam 1: `open!` routes stdin diffs through `send-stdin-diff-tree!`

The old blanket stdin skip in `open!` became a `cond`: archives still send nothing; a piped-stdin
document goes to `send-stdin-diff-tree!` (`src/vinary/main/service.cljs`); everything else keeps
`send-tree!`. The new function gates on the document's **effective kind** being `"diff"`
(`doc-overrides/effective-kind` — so `-t diff` and a later Settings ▸ File Type pick both qualify)
and on the R7 opt-out below, reads the invoking cwd from `doc-overrides/stdin-base-dir` (the same
`:cwd` fact the side-by-side view enriches from), and runs the existing `repo-tree` on that
directory. A repository found → the entry is sent through `with-diff-extra`; none → the ADR-0036
skip stands, verbatim.

### MAIN — seam 2: `vv:load-diff-sources` proves the repository for every local diff

When a diff opens, the renderer's `:diff/render` fx already asks main to resolve the diff's
referenced paths (`includePaths` — the eager, path-only pass behind [feature 28's navigable file
headers](28-diff-rendering.md)). The handler now finishes with:

```clojure
(let [resolved (load-diff-sources diffPath files opts)]
  ;; ADR-0038 seam 2: the resolved targets prove which repository the diff describes.
  (when includePaths (adopt-diff-project! (.-sender e) diffPath resolved))
  (clj->js resolved))
```

`adopt-diff-project!` takes each resolved target's directory, computes its git toplevel
(`rev-parse --show-toplevel`, short-circuiting directories already under a found root), picks
`service-util/dominant-root` — the most frequent toplevel, first-seen winning ties, so a
multi-repo diff adopts the repository that owns most of its targets — and sends `repo-tree root`
through `with-diff-extra`. A per-window `adopted-diff-roots` atom (`wc-id → {diff-path root}`)
makes Split rebuilds and re-renders free; `unwatch-file!` clears the diff's entries when retention
drops it (reopening re-adopts cleanly) and `release-window!` clears the window's map.

### MAIN — the extra itself, and the two guards

`with-diff-extra` builds the row via the pure `service-util/diff-extra`, which returns **nil when
the diff lies inside the adopted root** (checked with the existing `relative-scope`; the
single-listing rule) and otherwise
`{:path <abs> :name ("(piped diff)" | basename) :kind "diff" :transient? <stdin?>}`. Both seams
also skip any document whose doc-overrides entry carries `:git` (`doc-overrides/git-info`) — the
forward-compatible opt-out for git-*produced* commit diffs, which are transient synthetics that die
with their tab, not project members.

### RENDERER — merge, prune, filter, reveal, render

- **`projects/merge-extras`** unions `:extras` vectors, deduped by `:path` (incoming wins — a
  re-send may relabel), first-seen order kept. `merge-project`'s **in-place branch unions** rather
  than replaces: a root-level Refresh or a plain re-open sends a full entry *without* extras, and
  replacement would silently wipe the attached diffs. The absorb/covered branches carry extras
  defensively.
- **`projects/prune-extras`** drops `:transient?` extras whose `:path` left the retained document
  set. It runs from the new `:tree/prune-extras` event, dispatched **conditionally** by
  `retention-fx` (only when some project actually carries extras) — the same retention edge at
  which main unlinks the spilled temp file, so row and file die together. Persistent extras are
  untouched.
- **`tree-model/filtered`** narrows extras by their display `:name` (they have no root-relative
  path) and keeps a project alive when files *or* extras survive; `visible-tree-paths` prepends
  extras so keyboard order matches visual order.
- **`tree-state/active-scopes`** gains an extras arm: an active path contained by *no* project but
  attached as an extra reveals its adopting project's root scope — extras render at root level, so
  the root scope is all that is needed.
- **`ui.tree/extra-row`** renders `a.vv-file.vv-file-extra` through the `file-attrs` helper
  factored out of the ordinary file row — active-highlight, Ctrl+click, and the `:file` context
  menu come free — with an icon fallback to `".<kind>"` so *(piped diff)* still gets the code
  glyph. One CSS rule (`.vv-file-extra { font-style: italic; }`) marks it visibly part of the
  project, visibly not a git-listed row.

---

## 4 · Design notes / trade-offs

- **Why git repositories only?** A synthetic adoption from an arbitrary invoking cwd would put
  `$HOME` (or worse) into Files the first time you piped from a shell there. ADR-0030's asymmetry
  applies: a git root is a *fact*; the described-project inference is only trusted when it lands on
  one. On-disk diffs in non-repo directories still get their location's synthetic root from the
  unchanged `send-tree!`.
- **Why does the renderer prune transient extras, rather than main pushing a removal?** The
  renderer's retention model already *is* the signal: `retention-fx` runs on every tab/history
  change, and main unlinks the spill from the very same retained-set sync. A push channel would
  duplicate that edge and could only race it.
- **Why pin extras above the folders rather than sort them in?** They have no root-relative path
  to sort by, and the pinned position states what they are: attachments of the project, not
  listings of it.
- **The seam-2 git calls are synchronous** (`rev-parse` per distinct target directory,
  `ls-files` per adoption) — the same bounded main-process trade-off [feature 04](04-git-file-tree-and-filter.md)
  records for `repo-tree`, and the `adopted-diff-roots` guard amortizes repeats to zero.
- **Test surface.** Pure pieces in the node `:test` build (`diff-extra-entries`,
  `dominant-root-selection` in `core_test`; four extras deftests in `projects_test`;
  `filtering-extras`; `active-scopes-for-extras`); wiring pinned by four source-binding asserts in
  `git-tree-smoke.js` (both seams are silent no-ops when unplugged); and `tree-e2e.js` drives a
  hermetic outside-the-repo patch end-to-end — adoption, the DOM-rendered revealed row, extras
  surviving an extras-less refresh, and the in-repo single-listing guard. **Piped-stdin adoption
  has no true e2e** (the harness cannot pipe); it is covered by the units, the seam-1 smoke
  bindings, and seam 2's e2e equivalence.

### Limitations

- **Remote (`ssh://`/`sftp://`) diffs do not adopt.** The remote branch of `vv:load-diff-sources`
  is unchanged; adoption is a local-filesystem feature for now.
- **Adoption requires the diff's recorded paths to actually resolve.** Relative paths resolve
  against the diff's own directory *and its ancestors* (`diff-source/resolve-from`), so an
  outside-repo patch adopts when its recorded paths pass through a common ancestor — e.g. paths
  recorded as `repo/src/lib.rs` resolve from `~/patches` one directory above `repo`. A patch whose
  paths match nothing on disk (reviewed away from any checkout) adopts nothing — exactly as its
  Split view falls back to hunk windows.

---

## 5 · Diagram

- **Sequence — the two adoption seams:** the `== diff adoption (ADR-0038) ==` phase of
  [`../diagrams/seq-tree.puml`](../diagrams/seq-tree.puml): (stdin) `open!` → effective kind
  `diff` → `repo-tree` on the invoking cwd → `vv:tree` with the *(piped diff)* extra; (on-disk)
  `:diff/render` → `vv:load-diff-sources` → resolve targets → per-directory `rev-parse` toplevels
  → `dominant-root` → `vv:tree` + extra → `merge-project` union.

![File-tree sequence](../diagrams/seq-tree.svg)
