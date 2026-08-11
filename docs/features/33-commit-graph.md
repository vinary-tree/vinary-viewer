# Commit Graph — the full-pane history document

**Status: Available now.**

---

## 1 · What it is

The **Commit Graph** is the GitLens-style, full-pane sibling of the
[Commits tab](32-commits-tab.md)'s mini rail
([ADR-0040](../design-decisions/0040-commit-graph-blame-and-history.md)): a repository's history
as a **document in its own tab** — titled **"Commits · \<repo\>"** — with a wide lane graph
(branches forking and merging in color, up to 12 lanes), ref badges (`HEAD →` branch, tags,
remotes), subject / author / date columns, a full keyboard model, and mouse selection. It shares
**one store and one selection** with the sidebar panel: select commits in either surface and the
other highlights them; Enter, a double-click, or the context menu opens the same
diff-against-parent (or Diff Selected pair) as ordinary diff tabs with everything
[feature 28](28-diff-rendering.md) provides.

Because it is a *document*, everything documents already do applies unchanged: it lives in a tab,
participates in Back/Forward history, survives reloads without losing your scroll position or
cursor, and refreshes live when the repository changes — while never appearing in Open Recent
(a virtual `vv-git-graph://` URI is not a file to reopen).

---

## 2 · How to use it

1. **Open it.** Any of:
   - the command palette → **Open commit graph** (uses the active document's repository, or your
     pinned one);
   - right-click a project header in the **Files** tree → **Open Commit Graph** (git projects
     only — the item no-ops for a synthetic root);
   - the **Graph** pill in the Commits panel's header.

   A tab named **Commits · \<repo\>** opens. Its header shows the repo name, a commit count
   (`247+ commits` until the walk is exhausted, then the exact count), and the branch/tag combo.
2. **Read the rows.** Each 24 px row is: the **lane rail** (dot = this commit, curves = its
   edges; colors cycle through the 8-hue lane palette) · **ref badges** + the subject ·
   the author · the date (`YYYY-MM-DD HH:mm`). Badges render `HEAD -> main` as an accented
   branch badge, `tag: v1.0` as a tag badge, and `origin/main` as a dimmed remote badge. Rows
   past the 12-lane cap draw in the last lane and flag the row with a **`»`** chip
   ("graph truncated at the lane cap"). Hover a row for full-subject and author-email tooltips.
3. **Switch the viewed ref.** The header's branch combo is the same filterable widget as the
   panel's — type to filter, ↓/↑ + Enter, grouped Local / Remote / Tags. (It disables during
   [history modes](34-git-blame-and-file-history.md) with "History follows HEAD".)
4. **Drive it with the keyboard.** Click the pane (it takes focus on open) and:

   | Key | Action |
   | --- | --- |
   | `↓` / `↑` | Move the cursor and select that commit (the first press lands on the newest commit) |
   | `Shift` + `↓`/`↑`/paging keys | Extend the selection as a range from the anchor |
   | `Ctrl` + `↓`/`↑`/paging keys | Move the **cursor only** — the selection stands |
   | `PgDn` / `PgUp` | Move by one viewport of rows |
   | `Home` / `End` | Jump to the newest / oldest **loaded** commit |
   | `Space` | Toggle the cursor commit in the selection |
   | `Enter` | Open the cursor commit's diff against its first parent |

   The cursor row is marked with an accent bar and always scrolled into view.
5. **Drive it with the mouse.** Click selects a commit; **Ctrl+click** toggles it into the
   selection; **Shift+click** extends a range; **double-click** opens its diff vs parent.
   Right-click for **Diff vs Parent**, **Diff Selected (2)** (exactly when two commits are
   selected — in either surface, since the selection is shared), **Copy hash**,
   **Copy short hash**, and **Copy subject**.
6. **Scroll to load more.** Pages of 250 commits load automatically as you near the bottom —
   no button. The header count grows until the walk is exhausted.
7. **Let it follow the repo.** Commit, fetch, or switch branches in any terminal: the graph
   refreshes within about a second, preserving the selection and cursor by hash (a rewritten-away
   commit simply drops out).

**Empty and error states.** A repository with no commits shows "No commits yet"; a data-layer
failure (git missing from PATH, output past 64 MiB, …) shows the error sentence in the pane;
opening the graph on a directory that is not a repository fails the open with
"Not a git repository" like any other document error. While a page loads, the header shows a
spinner glyph.

**Example.** In this repository: palette → *Open commit graph*. Arrow down through the recent
history — merges fork and join across the rail; press `Space` on two release commits and
right-click → *Diff Selected (2)* to read the whole release; type `v` in the branch combo to jump
the view to a tag.

---

## 3 · How it works internally

**Identity: a synthetic document through the unchanged pipeline.** Opening dispatches
`[:doc/open "vv-git-graph://<root>"]` — a normal navigation. `vinary.app.uri` recognizes the
scheme (`git-graph?` / `git-graph-root`, the "Commits · \<repo\>" tab basename);
`service-util/route` routes it **first** (the URI names no filesystem object, so no stat/name arm
may see it); main validates the root with one sync `rev-parse --show-toplevel` and answers a tiny
`vv:content {path, kind "git-graph", git {root}, stamp}` — no tree send, no file watcher.
`:content/received` stores the root as the DataScript attribute **`:doc/git-root`**, keeps the
kind out of Open Recent, and dispatches `[:git-graph/data-ensure root]` → the same idempotent
`:commits/ensure` the sidebar panel mounts through. The `views.cljs` arm keys the component **by
path only** — a reload/re-send never remounts it, so scroll and cursor survive; data freshness
flows through the store, not `:doc/stamp`.

**One store, two surfaces.** `vinary.ui.git-graph/graph-view` subscribes to
`[:commits/for-root root]` — the same `[:ui :commits :repos <root>]` slice as the panel, including
the **stored incremental lane fold** (ADR-0039 R2) and the **hash-keyed selection** (R3). All
interaction dispatches the shared events (`:commits/select`, `:commits/activate`,
`:commits/cursor-to`), which is the whole reason selections mirror across surfaces. On mount the
view claims the `:graph` slot of `[:ui :commits :watch-owners]` and `:commits/sync-watch` sends
the **union** of the panel's and the graph's interests over `vv:git-watch` — so closing the panel
never releases the graph's `.git` watcher, or vice versa.

**Geometry and rows.** The pure `vinary.git.graph-geometry` owns every number: 24 px rows, 12 px
lanes, cap 12, per-edge-kind SVG path strings (`:pass`/`:collapse`/`:continue`/`:branch`/`:merge`
mapped to verticals and mid-height quadratics under the two-half continuity convention),
`row-geometry` (which folds the *previous* row's edge targets in as top-half ink and flags
over-cap rows for the `»` chip), `refs->badges` normalization, `next-cursor` keyboard math, and
`fmt-date`. Each row is a small **hiccup SVG** — interactive, self-contained, and pinned by
`graph_geometry_test`'s exact `d`-string assertions.

**Windowing and paging.** The pane attaches a passive scroll listener to its enclosing
`.vv-content` (the confined scroll owner, ADR-0032), mirrors
`{scrollTop, clientHeight, offsetTop}` into a ratom, and renders only the visible band ± 10 rows
between two exact-height spacers (every row is 24 px, so the arithmetic is closed-form). The
same listener dispatches `[:git-graph/near-end root approxRow]`; the **event** — never the render
function — decides to `:commits/load-more` when the band nears the loaded end (within 30 rows)
and the repo is neither loading nor exhausted. Keyboard reveal is the `:git-graph/reveal-row`
effect: clamp the scroller's `scrollTop` so the cursor row is inside the viewport.

**The keyboard carve-out.** The window-capture arrow-key scroller
(`renderer.core/editable-target?`) would swallow arrows before the pane saw them; its `closest`
selector gained **`[data-vv-keynav]`**, which the `.vv-gg` pane declares — the smallest possible
change to one global selector, asserted end-to-end by the electron smoke (ArrowDown moves the
cursor in app-db *and* the DOM; Enter records `{to, parent?}`).

---

## 4 · Design notes / trade-offs

- **Why a document and not a bigger panel?** A history you *study* deserves a tab: history,
  Back/Forward, side-by-side with the diff you just opened. And the synthetic-URI route costs no
  new navigation machinery at all — see ADR-0040's alternatives.
- **Why hiccup SVG when the app renders bodies via `innerHTML`
  ([ADR-0003](../design-decisions/0003-ref-innerHTML-no-vdom-body.md))?** Because every ADR-0003
  premise flips here: the cells are tiny, must react to selection subscriptions, and come from
  pure unit-tested geometry. This is the first hiccup SVG in the codebase, on purpose.
- **Why JS windowing when the sidebar uses `content-visibility`
  ([ADR-0039](../design-decisions/0039-commits-sidebar-and-git-data-layer.md) D9)?** The panel's
  DOM is bounded by explicit "Load more" clicks and it does not own its scroller; the graph
  auto-pages on scroll (unbounded DOM) and sits directly inside the scroller its spacers command.
  Both premises flip, so the choice flips.
- **Test surface.** `graph_geometry_test` (exact path strings, cap clamps + overflow, badges,
  cursor math, dates, chip labels); route arms in the service-util / file-kind / content-route
  suites; and the electron-smoke graph arm over mocked `vv:git-log`/`vv:git-branches`/
  `vv:git-open-diff` (rows, rails, dots, and badges render; keyboard cursor moves; Enter's
  request shape is R4's).

### Limitations

- **No jump-to-commit fetch.** The graph pages forward from the newest commit; there is no
  "fetch around hash X" request (`vv:git-log :around` is explicitly out of scope), so reaching a
  very old commit means scrolling the pages in.
- **Tooltips are `title` attributes** — the full subject and the author email. The panel's
  GFM-rendered message bodies are not shown in the graph (expand the row in the sidebar for
  that).
- **The selection is shared with the panel** — deliberately. Selecting in one surface replaces
  the selection in the other; there is no per-surface selection.
- **Lanes cap at 12.** Wider histories clamp to the last lane and flag rows with `»`; colors
  cycle `lane mod 8` through `--vv-lane-0..7`.
- **Merge commits diff against their first parent** (Enter/double-click), as everywhere in the
  Commits surfaces; select the merge and a parent explicitly for the other leg.

---

## 5 · Diagrams

- **Component — the whole git integration:**
  [`../diagrams/component-git-integration.puml`](../diagrams/component-git-integration.puml) —
  the main-side data layer and watcher, the `vv:git-*` channels, the shared
  `[:ui :commits]`/`[:ui :blame]` stores with the watch-owner union, and all four consuming
  surfaces (Commits panel, this graph document, the blame gutter, spilled diff documents).

![Component — the git integration](../diagrams/component-git-integration.svg)

See also the [Commits tab](32-commits-tab.md)'s sequence diagrams — the log delivery, stored
lane fold, and live-refresh legs are byte-for-byte the same store this document reads.
