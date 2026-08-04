# 0037 — Collapsible per-file diff previews (a controlled `<details>` wrapper in the shared IR)

- **Status:** Accepted (amends [0026](0026-diff-rendering-side-by-side-and-repo-filetypes.md)'s
  two-backend structural contract)
- **Date:** 2026-08-04
- **Deciders:** vinary-viewer maintainers

## Context

A multi-file diff rendered every file's preview in full, with no way to put a reviewed file away. The
unified view's structure was FLAT — an `<h2>` banner followed by sibling line divs, no per-file
container — because ADR-0026's contract lowers one IR through two backends (GUI HTML and terminal
ANSI), and the flat form was trivially byte-safe for the terminal. Four requirements arrived together:
clicking a file's header must toggle that file's preview (both layouts); a diff-only View-menu action
must collapse/expand all files; Vim's `f` link-hints must treat the headers as toggle targets; and the
`[Unified | Split]` segmented control should fold into the `Preview ▾` facet combo's caret menu.

Three hard constraints shaped the design (verified empirically against the compiled pipeline):

1. The ANSI backend merges a wrapper's children into one wrapped run unless the wrapper has a
   block-kind (`:heading`) child, and it reads TUI Contents-jump anchors only off TOP-LEVEL blocks'
   `attr "id"` / meta `:id`.
2. The rendered body is innerHTML (ADR-0003), rebuilt WHOLESALE by every live-refresh remount and
   split-enrichment swap — raw DOM `open` flips cannot survive.
3. Both layouts share the positional `vv-diff-file-N` id space, which anchors the Contents outline,
   the scroll-spy, and TOC jumps.

## Decision

**Structure.** Each file becomes a controlled `<details class="vv-diff-file" open>` wrapper in BOTH
views. In the unified IR (`ir/frontend/diff.cljs`) the banner is re-tagged `<summary>` (its IR kind
stays `:heading`, which is all the ANSI backend and both Contents outlines dispatch on) and emitted as
the wrapper's FIRST child; the wrapper carries the file id **as IR meta only** (`:id` — read by the
terminal's `render-lines` for anchors, never serialized), while the canonical DOM id lives solely on
the summary, so `getElementById` stays unambiguous. `outline` scans preorder (the banners are nested
now). The split builder (`diff.cljs`) makes the same `section→details` / `header→summary` change; its
nested unchanged-run `vv-diff-gap` details stay native and uncontrolled. At the diff's production
block separator `"\n"` the ANSI output is **byte-identical** to the pre-wrapper form — within-block
joins equal the former between-block separators — pinned by golden tests (`unified-ansi-golden`,
`unified-ansi-anchors`); the `"\n\n"` default legitimately differs and no production surface uses it
for diffs.

**State.** A per-tab **`:diff-collapsed`** set of collapsed file ids (`nav.cljs`; absent/`#{}` = all
expanded) — the `:diff-view` tier: survives tab switches, live refreshes, and the unified⇄split flip;
never in history entries; **cleared when the tab navigates to a different uri** (the ids are
positional — diff A's set must not collapse arbitrary files of diff B) and on close. The applier
(`renderer/diff_view.cljs apply-collapsed!`) projects the set onto whatever DOM is mounted: called
synchronously by `markdown-body` right after `set-inner!` (before paint — no expanded flash; before
the rAF'd scroll restore measures layout) and by the `:diff/apply-collapsed` fx on every state change.
This is the ADR-0003 corollary: state that must outlive innerHTML lives in the app model and is
re-projected after every rebuild (the sidebar tree's controlled-`<details>` pattern, applied inside
rendered content for the first time).

**One behavior source for the toggle.** The delegated content click handler
(`attach-content-interactions!`) gains a first branch: a click landing on `.vv-diff-file-head`
preventDefaults the native `<details>` activation and dispatches `[:diff/toggle-file id]`. Real
clicks, keyboard summary activation, and the link-hints' synthetic `.click()` all converge there. The
hints layer adds `.vv-diff-file-head` to its collection selector, classifies banners as a serializable
`{:kind :toggle :path <id>}` target (the pure `classify-target` core is DOM-free and unit-tested), and
follows by `getElementById → .click()` — id re-find survives a scroll between collect and follow.

**Surfaces.** View ▸ **Collapse All Files** — one `:diff-only` item whose label is realized
dynamically ("Expand All Files" once everything is collapsed) at the single seam (`filter-items`) both
the render path and the keyboard/access-key path flow through; its static event
(`:diff/toggle-collapse-all`) picks the direction from state. The gating generalizes the pdf-only
mechanism into a ctx map (`{:pdf? :diff? :diff-all-collapsed?}`), with the diff gate defined once in
`facet.cljs` (`diff-preview-active?` = kind "diff" ∧ ¬source facet) and shared by the menu, the combo,
and the events' self-gating. Palette commands `:view/diff-collapse-all` / `:view/diff-expand-all`;
Vim `z M` / `z R` (the fold idiom; deliberately no `z a` — there is no cursor-over-file concept, the
`f` hints are the per-file affordance). A Contents click on a collapsed file **auto-expands** it
before scrolling (a jump is intent to see the file; the offset must measure expanded layout).

**Toolbar consolidation.** The diff layout choice moved INTO the `Preview ▾` combo's caret menu:
`combo-button` learned divider rows and self-dispatching rows (`:on-pick`), and `view-switch` injects
`Unified`/`Split` rows for a shown diff preview at the UI layer — the facet model stays
content-agnostic. A lone diff's menu is exactly those two rows (its redundant single file row is
suppressed; combo mode is forced so the caret exists); a grouped diff lists files, a divider, then the
layouts. The `[Unified | Split]` segmented control (`seg-button` + `.vv-seg*` CSS) was **removed** —
an explicit user-requested replacement, not a disable (zero other consumers, verified).

## Alternatives considered

- **DOM-walking collapse without an IR wrapper** (hide sibling ranges after each banner). Rejected:
  fragile range arithmetic re-derived on every render, no native semantics, and the split view already
  had a per-file container — the wrapper unifies both views under one applier.
- **A `<summary>` shell around the existing `<h2>`.** Legal HTML, but two nodes where one serves; the
  kind-based dispatch means the tag swap costs neither backend nor outline anything.
- **Uncontrolled native `<details>`** (no state, DOM-owned). Rejected: every innerHTML rebuild (live
  refresh, enrichment swap, layout flip) would snap files back open — the shipping `vv-diff-gap`
  demonstrates exactly that reset today.
- **The wrapper carrying the DOM id.** Rejected: two elements with one id family (wrapper + summary)
  or moving the id off the visible banner would break `getElementById` consumers (TOC scroll, hints)
  or the hidden-target scroll-offset rule; IR-meta-only on the wrapper gives the terminal its anchor
  with zero DOM footprint.
- **A second toolbar control for collapse-all.** Rejected in favor of the menu + palette + vim chords;
  the toolbar just *lost* a control — adding one back would undo the requested cleanup.

## Consequences

- CLI/TUI diffs are byte-identical and their Contents jumps land on the same lines (golden-pinned).
  The GUI gains per-file folding with state that survives everything short of navigating away.
- In-page find over a collapsed file follows the platform's closed-`<details>` visibility model — the
  same one the split view's collapsed gaps already exhibit (see `28-diff-rendering.md` for the exact
  wording pinned by the electron smoke).
- The `.vv-diff` mono-font CSS rule still matches only the split wrapper; the unified per-file wrapper
  deliberately does not carry that class (the unified body-line font quirk predates this change and is
  out of scope).
- Every re-render pays one `querySelectorAll("details.vv-diff-file")` (empty for non-diff bodies).
- Test surface: IR structure + outline preorder + two ANSI goldens (`diff_test`), collapse-state set
  semantics + navigation clearing + gate truth table (`diff_collapse_test`), pure hint classification
  (`core_test`), and the electron smoke drives every surface end-to-end (banner click, menu label
  flip both ways, combo caret layout picks on mouse-down, collapse surviving the layout switch, hint
  toggle, `z M`/`z R`).
