# 0007 — A CSS-mask + `currentColor` themed watermark (one asset, all themes)

- **Status:** Superseded in implementation — see the **Update (2026-06-29)** below (kept as the historical record)
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Update (2026-06-29)

**This decision was not shipped as described.** The v0.2 watermark (`vinary.ui.views/watermark`) has,
since the first v0.2 commit, rendered the **full-color Vinary Tree logo** (`vinary-tree-logo.svg`) as a
plain `<img class="vv-watermark-logo">`, desaturated and faded entirely in CSS:

```css
.vv-watermark-logo { width: 40%; max-width: 360px; height: auto; opacity: 0.08; filter: grayscale(1); }
```

There is **no `.vv-shield` element and no CSS `mask`** in the shipping code, and the monochrome
`shield.svg` placeholder this ADR describes is **orphaned** (referenced by nothing). The grayscale-logo
approach keeps the crest's artwork (not just a one-tint silhouette) and is theme-independent without a
`currentColor` stencil. The mask design below is retained as the historical record; current behavior is
documented in [feature 03 — Watermark on empty tabs](../features/03-watermark-empty-tabs.md).

## Context

When no document is open, the content area shows a **watermark** — a faint Vinary Tree emblem
(`vinary.ui.views/watermark` → `<div class="vv-shield">`). The emblem must look right in **both** the
dark and light themes (and any future theme), and it must be **subtle** (a faded watermark, not a solid
logo). The naive approach — a per-theme PNG/SVG image baked with the right color and opacity — means
maintaining one image **per theme** and re-exporting them whenever the palette changes.

## Decision

Use a **single monochrome SVG** as a **CSS mask** over a theme-token background color, with the opacity
applied in CSS. The SVG (`resources/public/assets/shield.svg`) is authored with `stroke="currentColor"`
/ `fill="currentColor"` (no baked colors), and the CSS tints and fades it:

```css
/* resources/public/css/app.css */
.vv-shield {
  width: 300px; height: 360px;
  background-color: var(--vv-fg);     /* the tint follows the active theme's foreground */
  opacity: 0.07;                      /* the fade is in CSS, not the asset */
  -webkit-mask: url(../assets/shield.svg) center / contain no-repeat;
  mask:         url(../assets/shield.svg) center / contain no-repeat;
}
```

The mask uses the SVG's shape as a stencil; the **visible** color is the element's
`background-color: var(--vv-fg)`, and `opacity: 0.07` makes it a faint watermark. Because the tint is a
theme token and the fade is CSS, the **same asset** renders correctly in every theme.

## Consequences

- **One asset, every theme.** Switching to `spacemacs-light` changes `--vv-fg`, so the watermark
  re-tints automatically — no second image, no re-export. New themes get a correct watermark for free.
- **Tint and opacity follow the theme** by construction. The watermark can never be "the wrong color for
  this theme," because its color *is* a theme variable.
- **The SVG stays a clean placeholder.** Authoring it as `currentColor`-only means the precise Vinary
  Tree crest can be dropped in later without touching the CSS coloring logic. (The current SVG is a
  faithful placeholder: a heraldic shield + tree-of-life + "VINARY TREE" wordmark.)
- **Small footprint.** A single small SVG instead of N raster images per theme.

## Alternatives considered

- **A baked image per theme** (e.g. `shield-dark.png`, `shield-light.png`). Rejected: duplicate assets
  to maintain, re-export on every palette tweak, and an easy source of "this theme's watermark looks
  wrong." The mask approach derives the color from the theme instead of duplicating it.
- **An inline `<svg>` with `fill` bound to a CSS variable.** Workable, but it puts the emblem markup in
  the component tree and complicates the "drop in the real crest later" story; a masked external asset
  keeps the emblem as a single replaceable file and the styling entirely in CSS.

## Trade-offs

- A CSS **mask** shows only the **silhouette** of the SVG in one tint — we give up multi-color artwork in
  the watermark. For a faint, single-tone watermark that is exactly the desired look, and in exchange we
  get one asset that is automatically correct in every present and future theme. (If a multi-color
  emblem is ever wanted for a non-watermark context, that would be a separate inline-SVG decision.)
