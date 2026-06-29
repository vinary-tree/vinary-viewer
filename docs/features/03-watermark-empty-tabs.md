# Watermark on empty tabs

**Status: Available now.**

---

## 1 · What it is

When no document is open — at launch with no file argument, or after you close the last tab —
the content area is not blank. It shows a **faint, grayscale Vinary Tree logo**: the full crest
(a heraldic shield enclosing a tree-of-life and the wordmark *VINARY TREE*), desaturated and
faded to a quiet background mark. The watermark is deliberately low-contrast (8% opacity) so it
reads as a quiet brand mark, not a call to action.

Unlike the rest of the chrome, the watermark is **theme-independent**: it is the logo's own
artwork run through a CSS `grayscale(1)` filter at low opacity, so it reads the same soft gray on
every theme rather than being re-tinted per theme.

---

## 2 · How to use it

There is nothing to configure. The watermark appears whenever there are no open tabs:

1. Launch `vv` with no file argument, **or** close every open tab (each tab's `×`).
2. The content area centers the Vinary Tree logo watermark.
3. It looks the same across themes ([feature 06](06-themes-and-live-switching.md)) — a faint
   grayscale logo at 8% opacity.

**Replacing the emblem.** The watermark asset is `resources/public/assets/vinary-tree-logo.svg`.
Drop a replacement SVG at that path to change it — no code change is needed, since the markup
references the file by name. (A separate monochrome `shield.svg` placeholder also ships but is no
longer referenced — see the design note below.)

---

## 3 · How it works internally

### The content-view falls through to the watermark when there are no tabs

The content area is a Strategy keyed on the document, and its **first** branch is the empty case.
From `src/vinary/ui/views.cljs`:

```clojure
(defn content-view []
  (let [doc  @(rf/subscribe [:doc/active])
        tabs @(rf/subscribe [:tabs])]
    [:div.vv-content {:on-scroll (fn [^js e] (toc/spy! (.-currentTarget e)))}
     (cond
       (empty? tabs)               [watermark]
       (:doc/error doc)            [:div.vv-error "Error: " (:doc/error doc)]
       ;; … image / markdown / pdf / source / web branches …
       :else                       [:div.vv-empty "Rendering…"])]))
```

The very first `cond` clause is `(empty? tabs) → [watermark]`. Because `:tabs` is a subscription
over the open documents ([feature 02](02-multi-tab-previews.md)), closing the last tab makes
`tabs` empty and the watermark appears reactively — no imperative "show the splash" call.

The watermark component itself is trivial:

```clojure
(defn watermark []
  [:div.vv-watermark
   [:img.vv-watermark-logo {:src "assets/vinary-tree-logo.svg" :alt ""}]])
```

It is a centering flexbox wrapping a single `<img>` of the logo asset.

### The CSS fades the logo into a watermark

From `resources/public/css/app.css`:

```css
.vv-watermark { display: flex; align-items: center; justify-content: center; height: 100%; }
.vv-watermark-logo { width: 40%; max-width: 360px; height: auto; opacity: 0.08;
  filter: grayscale(1); user-select: none; pointer-events: none; }
```

What each declaration does:

- **`.vv-watermark { display: flex; … height: 100% }`** — fills the empty content pane and centers
  its child on both axes.
- **`width: 40%; max-width: 360px; height: auto`** — sizes the logo to 40% of the pane width, capped
  at 360 px, preserving its aspect ratio (responsive, unlike a fixed-size emblem).
- **`filter: grayscale(1)`** — fully desaturates the logo's own colors to gray, so the watermark does
  not clash with any theme's palette.
- **`opacity: 0.08`** — fades the whole thing to 8%, making it a faint background mark.
- **`user-select: none; pointer-events: none`** — the watermark is decorative: it cannot be selected
  or intercept clicks.

Because the logo is desaturated and faded entirely in CSS, the same asset renders identically in
every theme — no per-theme variant and no theme token are involved.

---

## 4 · Design notes / trade-offs

- **Why a full-color logo + `grayscale(1)` instead of a tinted mask?** The shipped watermark uses the
  real Vinary Tree crest (`vinary-tree-logo.svg`) drawn as an `<img>` and desaturated by CSS. An
  earlier design (ADR-0007) instead used a **monochrome `shield.svg` as a CSS mask** over a box filled
  with the theme foreground `--vv-fg`, so the silhouette re-tinted per theme. That approach was
  **superseded** by the full-color logo: a grayscale filter keeps the crest's artwork (not just a
  silhouette) while staying theme-neutral, and needs no `currentColor` stencil. The `shield.svg`
  placeholder still ships but is **orphaned** (referenced by nothing).
- **Why 8% opacity?** It is a brand watermark, not UI. Low contrast keeps it from competing with any
  content that might overlay it and reads as "empty, but ours".
- **Theme-independent by design.** Because the watermark is grayscale, it neither needs nor reads a
  `--vv-*` token; it looks the same on dark and light themes.

Background on the superseded mask approach is recorded in
[ADR-0007 CSS-mask themed watermark](../design-decisions/0007-css-mask-themed-watermark.md) (see its
*Update* note). Theming mechanics for the rest of the UI are in
[feature 06](06-themes-and-live-switching.md) and
[reference/css-variables.md](../reference/css-variables.md).

---

## 5 · Diagram

The emblem pipeline — *full-color logo SVG → `<img>` → `filter: grayscale(1)` + `opacity: 0.08` →
faint grayscale logo* — is illustrated by the object/component diagram owned by this pillar:
[`../diagrams/object-watermark.puml`](../diagrams/object-watermark.puml).

![Watermark logo render pipeline](../diagrams/object-watermark.svg)

In the diagram, **tan** marks the filesystem SVG asset, **teal** the renderer markup + CSS
(paint-only, no IPC), and the final green box the rendered emblem — the same color contract used
across all vinary-viewer diagrams ([`../diagrams/_vv-theme.iuml`](../diagrams/_vv-theme.iuml)).
