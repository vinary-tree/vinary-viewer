# Reference · CSS Variables (`--vv-*` design tokens)

> **What this is.** Every CSS Custom Property (`--vv-*`) that vinary-viewer defines, with its
> meaning, its **dark** (`spacemacs-dark`, the default) and **light** (`spacemacs-light`) value, and
> where it is used. The **theme** files (`resources/public/css/themes/<name>.css`) define the tokens
> on `:root`; the **structural** file (`resources/public/css/app.css`) references *only* `var(--vv-*)`
> — so a theme is a pure palette and switching one is a single `<link>` swap (see
> [architecture/05-data-flows.md §6](../architecture/05-data-flows.md#6-switch-theme)).

The palette is modelled after the **Spacemacs** colorscheme (`spacemacs-theme`, dark and light
variants).

---

## 1. How the tokens flow

```text
resources/public/index.html
  <link id="vv-theme-link" href="css/themes/spacemacs-dark.css">   ← defines :root { --vv-* }
  <link href="css/app.css">                                        ← uses var(--vv-*) only
                    │
   :theme/apply fx swaps #vv-theme-link.href → css/themes/<name>.css
                    │
            the whole cascade re-resolves (no JS restyle, no re-render)
```

> **Token-naming convention.** `--vv-bg*` = backgrounds; `--vv-fg*` = text; structural
> (`--vv-border`, `--vv-highlight`, `--vv-disabled`); headings/meta (`--vv-head1..4`, `--vv-meta`);
> syntax/emphasis (`--vv-const`, `--vv-func`, `--vv-em`, `--vv-var`, `--vv-comment`, `--vv-error`,
> `--vv-code`); and the in-page-find trio (`--vv-find-*`).

---

## 2. Token catalog

### 2.1 Backgrounds

| Token | Meaning | Dark | Light | Used by (`app.css`) |
| --- | --- | --- | --- | --- |
| `--vv-bg1` | Page / main background | `#292b2e` | `#fbf8ef` | `html,body`, `.vv-toolbar`, `.vv-tree-filter`, `.vv-find-input`, `.vv-tab-active`; `::highlight(vv-find-current)` text |
| `--vv-bg2` | Panels, `<pre>`, table zebra | `#212026` | `#efeae9` | `.vv-tree`, `.vv-toc`, `.vv-tabs`, `.vv-nav-btn`, `.vv-theme-select`, `.vv-find`, `.markdown-body pre`, `table tr:nth-child(2n)`, `.vv-tab-close:hover` |
| `--vv-bg-code` | Inline-code background; hover surfaces | `#2f2b33` | `#e8e3e3` | `.markdown-body code`, tree/tab/toc/`nav-btn` hover, `.vv-tree-filter:focus`, `.vv-find-btn:hover` |
| `--vv-bg-quote` | Blockquote background | `#293235` | `#edf2e9` | `.markdown-body blockquote` |

### 2.2 Text

| Token | Meaning | Dark | Light | Used by |
| --- | --- | --- | --- | --- |
| `--vv-fg` | Base foreground | `#b2b2b2` | `#655370` | `html,body`, tree files, tabs (hover), toolbar buttons, find input, `.markdown-body`, blockquote, `.vv-plain` |
| `--vv-fg-dim` | Muted foreground | `#686868` | `#a094a2` | `.vv-empty`, `.vv-find-count`, inactive tabs, toc items, `h6`, placeholders, `.vv-tab-close` |
| `--vv-fg-strong` | Emphasized text on accent bg | `#ffffff` | `#2c2434` | `::selection`, `.vv-file-active`, `::highlight(vv-find)` text |
| `--vv-fg-inverse` | Dark text on a bright bg | `#1c1c1c` | `#1c1c1c` | the find-active hit foreground (pairs with `--vv-find-active-bg`) |

### 2.3 Structure

| Token | Meaning | Dark | Light | Used by |
| --- | --- | --- | --- | --- |
| `--vv-border` | Rules, borders, header underlines | `#5d4d7a` | `#b3b9be` | most `border`/`border-*` (tree, toc, tabs, toolbar, find, `nav-btn`, `theme-select`, headings underline, `pre`, `th/td`, `hr`) |
| `--vv-highlight` | Text selection; current tree/TOC item | `#444155` | `#d3d3e7` | `::selection` bg, `.vv-file-active` bg, `::highlight(vv-find)` bg |
| `--vv-disabled` | Disabled control text | `#44475a` | `#b3aebb` | *(reserved for disabled controls)* |

### 2.4 Headings & meta

| Token | Meaning | Dark | Light | Used by |
| --- | --- | --- | --- | --- |
| `--vv-head1` | h1 / links / keyword / active accent (blue) | `#4f97d7` | `#3a81c3` | `h1`, `a`, tree/toc headers, active tab/file/toc, `hljs-keyword/-tag`, hover accents |
| `--vv-head2` | h2 / blockquote rule / strings (teal-green) | `#2d9574` | `#2d9574` | `h2`, blockquote left-border, `hljs-string`, `::highlight(vv-find-current)` bg |
| `--vv-head3` | h3 / diff addition (green) | `#67b11d` | `#67b11d` | `h3`, `hljs-addition` |
| `--vv-head4` | h4 / inline code (yellow) | `#b1951d` | `#b1951d` | `h4`, `.markdown-body code` text |
| `--vv-meta` | h5 / folders / meta / find count (tan) | `#9f8766` | `#da8b55` | `h5`, `.vv-dir-name`, `hljs-meta` |

### 2.5 Syntax & emphasis

| Token | Meaning | Dark | Light | Used by |
| --- | --- | --- | --- | --- |
| `--vv-const` | strong / numbers, literals (purple) | `#a45bad` | `#4e3163` | `.markdown-body strong`, `hljs-number/-literal/-symbol/-bullet` |
| `--vv-func` | function / tag names (magenta) | `#bc6ec5` | `#6c3163` | `hljs-name/-attr/-title/-section/-built_in/-selector-*` |
| `--vv-em` | emphasis / `<em>` (bright green) | `#86dc2f` | `#ba2f59` | `.markdown-body em` |
| `--vv-var` | variables, types (blue-purple) | `#7590db` | `#715ab1` | `hljs-variable/-template-variable/-type/-params` |
| `--vv-comment` | comments (teal) | `#2aa1ae` | `#2aa1ae` | `hljs-comment/-quote` |
| `--vv-error` | errors / diff deletion (red) | `#e0211d` | `#e0211d` | `.vv-error`, `.vv-tab-close:hover`, `hljs-deletion` |
| `--vv-code` | default code-block text | `#cbc1d5` | `#655370` | `.markdown-body pre code`, `.hljs` |

### 2.6 In-page find highlights

These pair with the **CSS Custom Highlight API** registrations (`::highlight(vv-find)` /
`::highlight(vv-find-current)`); note `app.css` styles those pseudo-elements with the heading/structure
tokens above, while the `--vv-find-*` tokens are the theme-authored palette intended for find.

| Token | Meaning | Dark | Light |
| --- | --- | --- | --- |
| `--vv-find-hit-bg` | Every match background | `#665c2e` | `#f6e9a0` |
| `--vv-find-hit-fg` | Match text | `#f0e68c` | `#5c4d00` |
| `--vv-find-active-bg` | Current (focused) match background | `#f0c674` | `#f0a500` |

> **Note on find styling.** Today `app.css` paints `::highlight(vv-find)` with `--vv-highlight` /
> `--vv-fg-strong` and `::highlight(vv-find-current)` with `--vv-head2` / `--vv-bg1`. The dedicated
> `--vv-find-hit-*` / `--vv-find-active-bg` tokens are defined in the themes for find-specific tuning;
> wiring the `::highlight()` rules to them is a small, isolated change. Both are documented so the
> intent is clear.

---

## 3. The watermark (theme-tinted)

The empty-tab watermark is **not** a `--vv-*` token but is theme-aware: `.vv-shield` uses
`background-color: var(--vv-fg)` at `opacity: 0.07`, masked by `assets/shield.svg`
(`mask: url(...) center/contain no-repeat`, 300×360). Because the fill is `--vv-fg`, the shield tints
and fades correctly per theme. `shield.svg` is a `currentColor` heraldic shield + tree-of-life +
"VINARY TREE" wordmark (a faithful placeholder for the full crest). See
[features/03-watermark-empty-tabs.md](../features/03-watermark-empty-tabs.md).

---

## 4. Authoring a theme

A theme is a single file `resources/public/css/themes/<name>.css` that defines **all** the `--vv-*`
tokens on `:root`. To add one:

1. Copy `spacemacs-dark.css` to `<name>.css` and change the values.
2. Add `["<name>" "<Label>"]` to the `themes` vector in `vinary.ui.views`.
3. Select it from the toolbar `<select>` (dispatches `[:theme/set "<name>"]` →
   `:theme/apply` swaps `#vv-theme-link.href`).

> The theme-file header comments mentioning `VV_THEME` / "relaunch" are **stale legacy** from the
> superseded v0.1.0 vmd-patching tool. The live mechanism is the reactive `:theme/apply` fx — no
> environment variable, no relaunch. Default is `spacemacs-dark` (set in both `index.html` and
> `default-db`).

---

## 5. See also

- [features/06-themes-and-live-switching.md](../features/06-themes-and-live-switching.md) — the feature.
- [architecture/05-data-flows.md §6](../architecture/05-data-flows.md#6-switch-theme) — the switch flow.
- [theory/05-strategy-renderer-registry.md](../theory/05-strategy-renderer-registry.md) — the theming-as-Strategy design.
