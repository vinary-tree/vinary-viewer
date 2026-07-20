# 0031 — Per-content-type preview fonts + a Fira Code ligature toggle

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** vinary-viewer maintainers

## Context

[ADR-0011](0011-font-awesome-icons-self-hosted-fonts.md) established self-hosted fonts and a single
variable-width prose token (`--vv-font-variable`) that styled *all* rendered document prose plus the app
chrome, and a single fixed-width token (`--vv-font-fixed`) for code. Its
[2026-07-20 amendment](0011-font-awesome-icons-self-hosted-fonts.md#amendments-2026-07-20) made **Noto
Sans** the default prose font (full-coverage OTF) and kept **Latin Modern Roman** bundled but only
*manually* selectable.

That left two typographic gaps:

1. **One prose font for every format.** A Markdown note and a LaTeX (`.tex`) paper rendered in the same
   family. But math always typesets in **New Computer Modern** (`@mathjax/mathjax-modern-font`, a
   Computer-Modern serif; see [ADR-0025](0025-latex-rendering-via-unified-latex.md)). A LaTeX document
   whose body is a humanist sans (Noto) beside Computer-Modern math reads as two documents; the authentic
   LaTeX look wants a Computer-Modern *body* too. Markdown/Org, by contrast, are conventionally
   sans-prose + serif-math (the GitHub-style contrast), which Noto already gives.
2. **Fira Code ligatures are unconditionally on.** Fira Code ships programming ligatures (`->`, `=>`,
   `==`, `!=`, `//`, …) via its `calt`/`liga` OpenType features, which the browser renders by default.
   Some readers rely on them; others find them misleading when reading code literally (an `=>` that is
   really `=` then `>`), and there was no way to turn them off.

## Decision

**Choose the prose font per document format, and make the Fira Code ligature state a preference — both
driven by CSS custom properties on `:root`, with no new fonts.**

### Per-format prose font

- A new token **`--vv-font-latex`** (default `"Latin Modern Roman", Georgia, serif`) holds the LaTeX
  prose font, independent of the user's `--vv-font-variable`.
- The content scroller **`.vv-content`** now carries a **`data-doc-kind`** attribute equal to the active
  document's `:doc/kind` (`views.cljs`). One rule selects the LaTeX font by format:

  ```css
  .vv-content[data-doc-kind="latex"] .markdown-body { font-family: var(--vv-font-latex); }
  ```

  It is more specific than the base `.markdown-body { font-family: var(--vv-font-variable); }`, so LaTeX
  (`.tex`, `:doc/kind "latex"`) documents render prose in Latin Modern while **every other format keeps
  Noto Sans**. The rule targets `.markdown-body`, where both the batch (`markdown-body`) and the streaming
  (`ir-stream-body`) renderers mount LaTeX HTML, so it covers batch and progressive paints alike. A `.tex`
  **Source** view uses `.vv-source` (mono), not `.markdown-body`, so it is unaffected.
- **Format decides, not content.** A Markdown/Org document keeps Noto even when it contains `$…$` math
  (only the math is Computer-Modern); only a whole LaTeX document switches its prose. This is predictable
  and reuses the existing `:doc/kind` dispatch.
- A **`:font-latex`** preference (a "LaTeX-preview font" field) overrides `--vv-font-latex`, symmetric
  with the variable/fixed font fields.

### Fira Code ligature toggle

- A new token **`--vv-code-liga`** (default `none`) is consumed as
  `font-variant-ligatures: var(--vv-code-liga)` by **every mono surface** — the ~22 selectors that use
  `--vv-font-fixed` / `"Fira Code"` (code blocks, source view, logs, tables, diffs, the URI bar +
  autosuggestion ghost, breadcrumb, command palette, keybinding chips, zoom field, …). `none` disables
  Fira Code's ligatures; a **`:code-ligatures?`** preference ("Enable Fira Code ligatures", default
  **false**) flips the token to `normal` to turn them on everywhere at once.
- It is applied **only to mono surfaces, never to prose**, so the Noto Sans / Latin Modern typographic
  ligatures (fi, fl, …) are untouched. `font-variant-ligatures` inherits, so a container selector (e.g.
  `.vv-source .cm-scroller`) covers its descendant text.

### Wiring

Both tokens ride the existing settings pipeline unchanged: the Preferences fields dispatch
`:settings/set`, which calls the **`:fonts/apply`** effect (`fx.cljs`) — extended to set `--vv-font-latex`
(when the string is non-empty) and `--vv-code-liga` (`normal`/`none` from the boolean, when present) on
`:root` — plus `:vv/save-settings` to persist to `settings.edn`. Absent preferences leave the `app.css`
defaults authoritative, exactly like the existing font settings.

```
  Preferences field ──▶ :settings/set k v ──▶ :fonts/apply {…}  ──▶ document.documentElement.style
   (settings.cljs)        (events.cljs)         (fx.cljs)             --vv-font-latex / --vv-code-liga
                                   └──────────▶ :vv/save-settings ──▶ settings.edn  (re-applied on boot)
```

## Consequences

- LaTeX previews look like LaTeX: Computer-Modern body + Computer-Modern math. Markdown/Org keep the
  sans-prose + serif-math contrast. Neither needs a new font — Latin Modern Roman was already bundled.
- The prose-font choice is now a pure function of `:doc/kind` (one CSS attribute), so adding another
  per-format font later is one token + one rule.
- Readers who dislike code ligatures get a literal-code default; fans enable them with one checkbox, and
  the toggle is uniform across every mono surface (no surface ligates while another does not — notably the
  URI bar and its metric-matched autosuggestion ghost stay in lockstep).
- Two new `settings.edn` keys (`:font-latex`, `:code-ligatures?`); both optional and forward-compatible
  (unknown keys are ignored by older builds).
- No CSP, packaging, or build change, and no new dependency.

## Alternatives considered

- **Keep one prose font for all formats.** Simplest, but abandons the LaTeX look this project cares about
  (its math is already Computer-Modern). Rejected.
- **Switch the prose font by *content* (any document containing math → serif).** Unpredictable (a Markdown
  note with one inline formula would flip to serif) and expensive to detect. Format-scoping via the
  existing `:doc/kind` is deterministic. Rejected.
- **Ship "Fira Mono" (Fira Code without ligatures) as a separate face.** A whole extra font to vendor for
  a behavior that is one CSS property on the font already bundled. Rejected in favor of
  `font-variant-ligatures`.
- **`font-feature-settings: "liga" 0, "calt" 0` instead of `font-variant-ligatures`.** Lower-level and
  would need re-listing both features; `font-variant-ligatures: none | normal` is the high-level,
  single-keyword control and composes with a CSS variable cleanly. Rejected.
- **A global `font-variant-ligatures` on a common ancestor.** Would leak into prose and disable the Noto /
  Latin Modern typographic ligatures. The per-mono-surface grouped rule keeps prose untouched. Rejected.

## Trade-offs

The ligature rule enumerates the mono surfaces (a grouped selector) rather than deriving them, so a new
mono surface must be added to both its `--vv-font-fixed` declaration and the ligature list — a comment in
`app.css` flags this. In return: per-format typography and a uniform ligature toggle with no new fonts, no
new pipeline, and two optional settings that inherit the existing apply / persist / boot machinery.

## See also

- [ADR-0011](0011-font-awesome-icons-self-hosted-fonts.md) — self-hosted fonts + build-time vendoring (the
  *delivery* of the faces this ADR *selects between*).
- [ADR-0025](0025-latex-rendering-via-unified-latex.md) — LaTeX rendering and the Computer-Modern math font
  the LaTeX prose font is chosen to match.
- [reference/css-variables.md §2.8](../reference/css-variables.md#28-structural-font-and-ligature-tokens) —
  the `--vv-font-*` / `--vv-code-liga` token catalog.
- [usage/05-configuration.md §2](../usage/05-configuration.md#2-settings) — the `settings.edn` keys.
