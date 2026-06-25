# Themes and live switching

**Status: Available now.**

---

## 1 · What it is

vinary-viewer ships two themes — **Spacemacs Dark** (the default) and **Spacemacs Light** — and
lets you switch between them **instantly at runtime** from a toolbar dropdown, with no relaunch.
The entire UI re-colors in one step: the document, the sidebars, the tab strip, syntax
highlighting, find highlights, and even the empty-tab watermark.

This works because the styling is split into two layers:

- A **structural** stylesheet (`app.css`) that defines *layout and shape* and references **only**
  CSS custom properties (`var(--vv-*)`) for every color — it contains no literal colors.
- A **palette** stylesheet per theme (`themes/<name>.css`) that defines the values of those
  `--vv-*` properties on `:root`.

Switching theme is therefore just **swapping which palette stylesheet is linked** — the structural
CSS is unchanged, and the browser re-resolves every `var(--vv-*)` to the new theme's values. One
`<link>` href change re-themes everything.

---

## 2 · How to use it

1. In the toolbar (top of the content pane), open the theme **`<select>`** dropdown.
2. Choose **Spacemacs Dark** or **Spacemacs Light**. The whole UI re-colors immediately.

**Example.** With a Markdown file open, switch from Dark to Light: the page background goes from
charcoal to cream, headings re-color, code blocks re-tint, and the watermark on any empty tab
flips from a light silhouette to a dark one — all without reloading the document or losing your
scroll position.

The default on launch is **`spacemacs-dark`**.

> The theme `.css` files carry header comments mentioning `VV_THEME` / "relaunch" — those are
> **stale legacy notes** from an earlier tool generation. The live mechanism today is the runtime
> `<link>` swap described below; there is no relaunch and no environment variable involved.

---

## 3 · How it works internally

### The two stylesheet layers, linked in order

`resources/public/index.html` links the **active palette first**, then the structural stylesheet:

```html
<link id="vv-theme-link" rel="stylesheet" href="css/themes/spacemacs-dark.css">
<link rel="stylesheet" href="css/app.css">
```

- The palette `<link>` carries the id **`vv-theme-link`** — the handle the live-switch effect
  edits.
- Loading the palette first means `:root { --vv-* }` is defined before `app.css` references it,
  though CSS custom properties resolve regardless of declaration order; the ordering is for
  clarity.

### Structural CSS references only `var(--vv-*)`

`resources/public/css/app.css` opens with this contract and never writes a literal color:

```css
/* vinary-viewer — structural stylesheet. Colors come from the active theme's --vv-* palette
   (resources/public/css/themes/<name>.css); this file references var(--vv-*) only. */
html, body { margin: 0; height: 100%; background: var(--vv-bg1); color: var(--vv-fg); … }
```

Every colored rule uses a `--vv-*` token — backgrounds (`--vv-bg1`, `--vv-bg2`, `--vv-bg-code`,
`--vv-bg-quote`), text (`--vv-fg`, `--vv-fg-dim`, `--vv-fg-strong`), structure (`--vv-border`,
`--vv-highlight`), headings (`--vv-head1..4`, `--vv-meta`), syntax (`--vv-const`, `--vv-func`,
`--vv-em`, `--vv-var`, `--vv-comment`, `--vv-code`), and errors (`--vv-error`). The full catalog
is [reference/css-variables.md](../reference/css-variables.md).

### Palette CSS defines the tokens on `:root`

Each theme file sets the `--vv-*` values. The two palettes, side by side (selected tokens):

| Token | Meaning | spacemacs-dark | spacemacs-light |
|-------|---------|----------------|-----------------|
| `--vv-bg1` | page / main background | `#292b2e` | `#fbf8ef` |
| `--vv-bg2` | panels, `<pre>`, zebra | `#212026` | `#efeae9` |
| `--vv-bg-code` | inline code bg; hover | `#2f2b33` | `#e8e3e3` |
| `--vv-fg` | base foreground | `#b2b2b2` | `#655370` |
| `--vv-fg-dim` | muted foreground | `#686868` | `#a094a2` |
| `--vv-fg-strong` | text on accent bg | `#ffffff` | `#2c2434` |
| `--vv-border` | rules, header underlines | `#5d4d7a` | `#b3b9be` |
| `--vv-highlight` | selection; current item | `#444155` | `#d3d3e7` |
| `--vv-head1` | h1 / links / keyword (blue) | `#4f97d7` | `#3a81c3` |
| `--vv-head2` | h2 / strings (teal-green) | `#2d9574` | `#2d9574` |
| `--vv-head3` | h3 / diff add (green) | `#67b11d` | `#67b11d` |
| `--vv-head4` | h4 / inline code (yellow) | `#b1951d` | `#b1951d` |
| `--vv-const` | numbers, literals (purple) | `#a45bad` | `#4e3163` |
| `--vv-error` | errors / diff del (red) | `#e0211d` | `#e0211d` |

Both palettes model the [Spacemacs](https://www.spacemacs.org/) colorscheme (dark and light
variants), so the previewer's colors match a Spacemacs editor. The header of each file (the
`VV_THEME`/relaunch comment) is the only stale part; the values are current.

### Live switch: swap the `<link>` href

The toolbar dropdown dispatches `:theme/set`. From `src/vinary/ui/views.cljs`:

```clojure
(def ^:private themes
  [["spacemacs-dark" "Spacemacs Dark"]
   ["spacemacs-light" "Spacemacs Light"]])

(defn toolbar []
  (let [theme @(rf/subscribe [:ui/theme]) …]
    [:div.vv-toolbar
     …
     [:select.vv-theme-select
      {:value theme :on-change #(rf/dispatch [:theme/set (.. % -target -value)])}
      (for [[v label] themes]
        ^{:key v} [:option {:value v} label])]]))
```

The event records the choice in app-db **and** fires the apply effect, from
`src/vinary/app/events.cljs`:

```clojure
(rf/reg-event-fx
 :theme/set
 (fn [{:keys [db]} [_ theme]]
   {:db (assoc-in db [:ui :theme] theme)
    :fx [[:theme/apply theme]]}))
```

The effect, from `src/vinary/app/fx.cljs`, edits the `<link>` href to point at the chosen theme's
stylesheet:

```clojure
(rf/reg-fx
 :theme/apply
 (fn [theme]
   (when-let [^js link (.getElementById js/document "vv-theme-link")]
     (set! (.-href link) (str "css/themes/" theme ".css")))))
```

That single `set! .-href` is the whole switch:

1. The browser loads `css/themes/<theme>.css`, replacing the old palette's `:root { --vv-* }`.
2. Every `var(--vv-*)` reference in `app.css` re-resolves to the new values.
3. The entire UI — including masked assets like the watermark
   ([feature 03](03-watermark-empty-tabs.md)) and `::highlight()` find colors
   ([feature 05](05-in-page-find.md)) — re-colors in place.

The current theme is also a subscription, so the dropdown reflects the active choice:

```clojure
(rf/reg-sub :ui/theme (fn [db _] (get-in db [:ui :theme])))
```

and the default lives in `src/vinary/app/db.cljs`: `:theme "spacemacs-dark"`.

---

## 4 · Design notes / trade-offs

- **Why split structural vs palette CSS?** It makes theming *purely additive*: a new theme is one
  more `themes/<name>.css` defining the `--vv-*` tokens — no structural CSS changes, no
  recompilation. The structural sheet is the single owner of layout; palettes are the single owner
  of color. (This is the DRY/single-source-of-truth principle applied to styling.)
- **Why swap a `<link>` instead of toggling a class or inlining variables?** Swapping the linked
  stylesheet keeps each theme an independent file that can be authored, diffed, and replaced on
  its own. A class-toggle approach would force all themes' values to coexist in one stylesheet; a
  JS-injected-variables approach would move palette data into code. The `<link>` swap keeps palette
  data in CSS where it belongs and re-themes in one operation.
- **Why CSS custom properties (`--vv-*`)?** They cascade and resolve live, so changing them at
  `:root` instantly affects every rule that references them — including masked SVGs and
  `::highlight()` — with no per-element work.
- **Trade-off — two built-in themes.** v1 ships exactly Dark and Light. A user-supplied theme
  registry (analogous to the planned [grammar registry](14-grammar-registry.md)) is a natural
  future extension; today, adding a theme means adding a file and an entry to the `themes` vector.

The theming approach is documented alongside the watermark CSS-mask decision in
[ADR-0007 CSS-mask themed watermark](../design-decisions/0007-css-mask-themed-watermark.md) (both
rely on the `--vv-*` token layer). See the [ADR index](../design-decisions/README.md) for the full
list, and [reference/css-variables.md](../reference/css-variables.md) for the token catalog.

---

## 5 · Diagram

- **Sequence — switching theme:** [`../diagrams/seq-theme-switch.puml`](../diagrams/seq-theme-switch.puml)
  (written by the architecture pillar). Dropdown `:on-change` → `:theme/set` → (`:db` records
  `:ui/theme`) + `:theme/apply` → `set! #vv-theme-link .href` → browser loads the palette →
  `var(--vv-*)` re-resolves → UI re-colors.

```plantuml
'' Source: docs/diagrams/seq-theme-switch.puml
'' Render with: plantuml -tsvg docs/diagrams/seq-theme-switch.puml
```

Palette: **blue-violet** = the `:ui/theme` app-db slice, **blue** = the re-frame event/effect,
**teal** = the renderer (the `<link>` swap + the re-resolved CSS). See
[`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml) and the token catalog in
[reference/css-variables.md](../reference/css-variables.md).
