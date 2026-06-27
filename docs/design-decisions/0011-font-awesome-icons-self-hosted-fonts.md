# 0011 — Font Awesome icons + self-hosted fonts, vendored at build time; no CSS framework

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** vinary-viewer maintainers

## Context

Two gaps in the chrome's visual layer needed closing, and one framework question needed settling.

1. **No icon set.** Every "icon" was a raw Unicode glyph rendered as a text child of a Reagent element —
   `←  →  ⟳` (`vinary.ui.views`), `×  +` (`vinary.ui.tabs`), `‹  ›` (`vinary.ui.sidebar`),
   `>` (`vinary.ui.menubar`), and `◀ ▶ × − ⌫ ⌨ ↶ ↷` (`vinary.ui.keybindings-editor`). These render
   inconsistently across platforms and installed fonts, vary in weight/baseline, and carry no file-type
   semantics in the git tree.

2. **Fonts declared but not shipped.** `resources/public/css/app.css` set
   `--vv-font-variable: "Inter", …` and `--vv-font-fixed: "Fira Code", …` but the app bundled **no
   `@font-face`**, so a reader without those fonts installed silently fell back to `system-ui` /
   `monospace`.

3. **CSS framework?** Whether to adopt Bootstrap or Tailwind for the styling layer.

The relevant constraints: a Spacemacs-themed, hairline-thin, airy aesthetic with consistently small
(3–8px) rounded corners; live theming by swapping a single stylesheet `<link>` (the `--vv-*` token
system); a sandboxed renderer with a **local-first / "does not load remote web content"** posture and a
*planned* strict CSP (see [`docs/security/threat-model.md`](../security/threat-model.md)); and a
shadow-cljs build that does **not** process CSS. A prior precedent exists for vendoring third-party
binary assets: tree-sitter grammars are compiled to WASM by `scripts/sync-grammars.mjs`, pinned in
`scripts/grammars.lock.json`, git-ignored, and regenerated on demand.

## Decision

**Adopt Font Awesome Free 7 (Classic Solid) as a self-hosted webfont, self-host the UI/mono fonts, vendor
all three from npm at build time, and keep the bespoke `--vv-*` CSS (no framework).**

### Icons — Font Awesome Free, Classic Solid, single-color

`vinary.ui.icons` is the single source of truth: a keyword → `fa-solid fa-<name>` map plus `icon`,
`file-icon` (extension → glyph, generic-file fallback), and `folder-icon` (a closed/open pair for the
CSS disclosure toggle). It emits plain hiccup `[:i]` — no React interop. Every call site that held a
Unicode glyph now calls it (`views`, `tabs`, `sidebar`, `menubar`, `keybindings-editor`, `tree`).

- **Classic, not Sharp** — the chrome is uniformly rounded (`.vv-nav-btn` 3px … `.vv-modal` 8px); Sharp's
  hard corners would fight it. **Solid** because Free's only *complete* weight is Solid (Free Regular is
  ~163 icons), so a full sweep stays consistent without a Solid/Regular mix.
- **Single-color via inherited `color`** so glyphs ride the `--vv-*` palette and the existing `:hover`
  accent rules recolor them, and re-theme automatically on the live `<link>` swap. `app.css` only adds
  per-surface sizing + a muted file-tree color; it sets **no** icon colors.
- **CSS-only folder open/closed** from native `<details>[open]` (no JS): `folder-icon` renders both
  glyphs and `app.css` reveals whichever matches the disclosure state.
- The in-chip **key-symbol** glyphs in the keybindings editor (`⏎ ⌫ ← → ↑ ↓`) stay Unicode — they are
  semantic key names inside mono text, where an icon would break baseline alignment.

### Fonts — self-hosted Noto Sans (variable) + Fira Code (variable)

`resources/public/assets/fonts/fonts.css` declares `@font-face` for **Noto Sans** (UI/body, replacing
Inter) and **Fira Code** (code/mono), latin + latin-ext subsets, pointing at vendored variable `.woff2`.
`app.css` switches `--vv-font-variable` to `"Noto Sans"`. `index.html` links fonts → Font Awesome →
`app.css`, in that cascade order.

### Build-time vendoring (the unifying mechanism)

Font Awesome and both fonts are **dev-only npm dependencies**; nothing enters the JS bundle.
`scripts/sync-assets.mjs` copies their CSS/`.woff2` into `resources/public/assets/`, idempotently (skips
bytes already present) and records every file's package version + sha256 in `scripts/assets.lock.json`.
`scripts/check-assets.mjs` verifies the vendored files against the lock and that `fonts.css` references
resolve. `assets:sync` is wired into `compile` / `watch` / `release` / `dev`; the vendored output
(`assets/fa/`, `assets/fonts/{noto-sans,fira-code}/`) is git-ignored. This mirrors the grammar-WASM
workflow exactly. Upgrades are a dependency bump + rebuild — no committed binaries.

```
  package.json devDeps              build step                      runtime (file://, no network)
  ┌──────────────────────────┐   ┌──────────────────────┐   ┌─────────────────────────────────────┐
  │ @fortawesome/…-free       │   │ npm run assets:sync   │   │ index.html                          │
  │ @fontsource-variable/…    │──▶│  scripts/sync-assets  │──▶│  <link> fonts.css                   │
  │   noto-sans, fira-code    │   │  (copy + sha256 lock) │   │  <link> fa/css/fontawesome.min.css  │
  └──────────────────────────┘   └──────────┬───────────┘   │  <link> fa/css/solid.min.css        │
        (not in JS bundle)                   ▼                │  <link> css/app.css  (--vv-*)        │
                              resources/public/assets/  ······▶│         ▼                            │
                              fa/…, fonts/…  (git-ignored)     │  renderer: vinary.ui.icons → <i>     │
                                                               └─────────────────────────────────────┘
```

### No CSS framework

Neither Bootstrap nor Tailwind is adopted. The UI is hand-authored `.vv-*` classes over `--vv-*` design
tokens — the app already *has* the design-token system a framework would add — themed by a whole-
stylesheet `<link>` swap, with zero runtime styling JS.

## Consequences

- One coherent, platform-independent icon set across the whole UI; new icons are a one-line edit to the
  `vinary.ui.icons` map (verified by `test/vinary/ui/icons_test.cljs`).
- Noto Sans and Fira Code render identically for every reader regardless of locally-installed fonts;
  glyph coverage spans latin + latin-ext.
- Fully offline and CSP-ready: no CDN, no `@font-face` to a remote origin, no inline-script icon kit —
  consistent with the local-first posture and a future `font-src 'self'` / `style-src 'self'` CSP.
- Upgrading Font Awesome or a font is `npm update …` + rebuild; the working tree carries no vendored
  binaries, and `assets.lock.json` gives a reviewable version/sha audit trail.
- Glyphs inherit `color`, so they participate in theme switching and `:hover` accents for free; folder
  icons reflect open/closed state with no JavaScript.
- The build gains a fast, idempotent pre-step (`assets:sync`) on `compile`/`watch`/`release`/`dev`.

## Alternatives considered

- **Font Awesome Pro (Classic Light/Thin, or Sharp).** Lighter weights match the hairline aesthetic
  best, but Pro is paid; Free is the call. The codebase is built to flip — Pro is a one-namespace change
  (swap the dep, vendor `light.min.css`, `fa-solid` → `fa-light` in `vinary.ui.icons`).
- **CDN / auto-replace kit, or `@fortawesome/react-fontawesome` SVG.** The CDN kit injects remote
  requests + inline `<script>`/styles a strict CSP would block; react-fontawesome adds React interop and
  a peer-dep with no benefit for a local desktop app where bundle size is moot. The self-hosted webfont
  is the minimal change — today's glyphs are already font characters colored by `color`.
- **Google Fonts CDN for the fonts.** Rejected: a remote `fonts.googleapis.com` link breaks offline use,
  contradicts the no-remote-content posture, and a future `font-src` CSP would block it.
- **Commit the vendored `.woff2`/CSS.** Rejected in favor of build-time vendoring + a lock, so upgrades
  are a version bump and the repo stays free of binary blobs (mirrors the grammar-WASM precedent).
- **Bootstrap.** A component framework that imposes its own visual identity (fights the Spacemacs theme)
  and drives interactivity with JavaScript — counter to the CSS-first goal; its components are already
  built in re-frame.
- **Tailwind.** Closer in spirit (utility/token-first, runtime-JS-free), but redundant with the existing
  `--vv-*` tokens, needs a separate PostCSS/CLI build (shadow-cljs processes no CSS), and ClojureScript's
  computed/keyword hiccup classes are invisible to its content scanner.

## Trade-offs

The build runs an extra idempotent copy step, and `fonts.css` must be kept in step with the subsets the
vendoring script copies (guarded by `check-assets.mjs`). In return: a consistent, themeable, offline,
CSP-ready icon + font layer with trivial upgrades, no committed binaries, and no framework weight or
second build pipeline — the crispest result for a bespoke, token-driven, CSS-first UI.
