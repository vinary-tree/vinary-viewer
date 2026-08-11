# 0041 — A vertical icon rail replaces the sidebar tab strip

- **Status:** Accepted
- **Date:** 2026-08-11
- **Deciders:** vinary-viewer maintainers

## Context

The sidebar began as a two-tab pane (Files / Contents) whose horizontal strip rendered each tab as
an icon plus a bold 12 px label. The Tabs panel made three; the Commits panel
([ADR-0039](0039-commits-sidebar-and-git-data-layer.md)) made four — and the arithmetic stopped
working. Measured against the shipped styles (`padding 6px 12px`, 12 px/600 text, a 12 px icon with
a 6 px gap), the four labeled tabs need **≈355–380 px** including the collapse chevron, against a
**280 px default** sidebar width and a 140 px resize floor. The strip had no wrap, no overflow
handling, and no `min-width: 0` anywhere — labels are single unbreakable words, so the tabs could
not shrink — and it simply extended past the sidebar's edge, burying the collapse affordance.

Two further pressures shaped the answer:

- **The panels are deliberately separate, full-height destinations.** The vertical Tabs panel
  exists to *replace* the horizontal document tab bar when too many documents are open; the Files
  tree hosts multiple projects; the Commits panel carries its own header row of controls. Merging
  panels into shared views (an accordion) was explicitly ruled out.
- **The collapse UI was already split in two**: a chevron inside the strip to collapse, and a
  separate 16 px thin rail to re-open — two mechanisms for one concern.

## Decision

**Replace the horizontal tab strip with a 36 px vertical icon rail on the sidebar's left edge, and
unify it with the collapse affordance.**

- One `<button>` per panel (Files, Contents, Tabs, Commits) using the existing `:section-*`
  glyphs, with a `title` tooltip, `aria-label`, and `aria-pressed`; the container is a
  `role="toolbar"` with `aria-orientation="vertical"`. The active panel shows an inset 2 px
  indicator bar (`--vv-head1`), the pattern the Commit Graph's cursor row already uses.
- **Click semantics (the VS Code idiom):** collapsed → `[:sidebar/show <panel>]` expands straight
  into that panel; a different panel while expanded → `[:sidebar/tab <panel>]`; the *active* panel
  while expanded → `[:sidebar/toggle]` collapses to the rail. Collapsed, the sidebar **is** the
  rail (`.vv-sidebar-collapsed`, ≈37 px) — every icon stays clickable, so the old thin re-open
  strip and the in-strip chevron are both subsumed by one mechanism. No event shapes changed.
- **Vertical scales where horizontal could not**: the rail grows downward one icon at a time, so a
  fifth or tenth panel costs nothing horizontally — the same cure the vertical Tabs panel applies
  to the document tab bar. The freed 36 px top row goes to the panels themselves (the Files filter
  box and the Commits header now sit at the top of the pane).
- `[:ui :sidebar-width]` remains the **total** width including the rail (the resize drag already
  measures from the window's left edge), with the clamp floor raised 140 → **180 px** so the panel
  column keeps ≥ ~143 px beside the rail. A previously persisted 140–179 value self-heals: the CSS
  `min-width` overrides the inline width, and the next drag re-clamps it.
- `data-vv-rail="files|contents|tabs|commits"` attributes are the **test contract** — every
  harness selects rail buttons by attribute, never by label text (there are no labels), through
  state-guarded helpers (clicking the active icon now collapses, so bare re-clicks must check
  `__vvdb` first).
- The Files button keeps the `:files-tab` context menu (**Refresh All**); panel lifecycles are
  untouched because panels always rendered only in the visible branch — collapsing is
  indistinguishable from switching tabs for the tree's watcher-scope sync and the Commits panel's
  `vv:git-watch` ownership.

## Consequences

- The sidebar/main **chrome-row alignment is intentionally given up on the sidebar side**:
  `--vv-chrome-tabrow-h` used to size both the sidebar strip and the document tab bar so their
  rows lined up pixel-for-pixel. The variable still sizes the document tab bar; the sidebar now
  spends that row on panel content.
- Superseded chrome is **removed** (stated here and in the commit): `.vv-sidebar-tabs`,
  `.vv-sidebar-tab*`, `.vv-sidebar-tabs-spacer`, `.vv-sidebar-collapse*`, and the old thin
  `.vv-sidebar-rail*`, plus their icon-sizing rules. This is replaced UI, not disabled logic — the
  rail is its successor, in the same file.
- The rail's taller body geometry exposed a latent one-shot-reveal defect (the active tree row
  could end 2 px clipped after a late web-font settle) — fixed separately by re-asserting the
  reveal on `document.fonts.ready`.
- The docs screenshots showing the strip are regenerated (`npm run screenshots`).

## Alternatives considered

- **Responsive labels** (icon+label when wide, icon-only below a container-query threshold).
  Chromium 148 supports container queries, and this was the initial pick — but it still burns the
  36 px row, still crowds again at the 180 px floor and with every future panel, and leaves the
  split collapse UI untouched. Rejected.
- **An always-icon-only horizontal strip.** Fits ~6 panels at the default width, then crowds
  again; keeps the row and the split collapse UI. Rejected.
- **Accordion / stacked sections** (the VS Code Explorer model — Files and Contents visible
  together). Strongest for simultaneity, but it merges panels into a shared view, which the
  user's constraints explicitly exclude: all four panels are first-class, distinct, full-height
  destinations. Rejected.

## See also

- [ADR-0039 — Commits sidebar and the git data layer](0039-commits-sidebar-and-git-data-layer.md)
  (the fourth panel that broke the arithmetic).
- [ADR-0034 — Expansion-scoped file-tree watchers](0034-expansion-scoped-file-tree-watchers.md)
  (why panel-visibility lifecycles matter and remain unchanged).
- [Feature 04 — File tree and filter](../features/04-git-file-tree-and-filter.md),
  [Feature 32 — Commits tab](../features/32-commits-tab.md).
