# Configuration

Today, the one thing you configure at runtime is the **theme**, via the in-app selector. This page
explains how theming works, walks through **writing your own theme**, and documents the **planned**
on-disk configuration directory. It also clears up some **stale** comments left over from the legacy
tool.

---

## 1. Themes (Available now)

### 1.1 The selector

The toolbar has a theme `<select>` (top-right). Two themes ship today:

| Value             | Label             | Default? |
|-------------------|-------------------|----------|
| `spacemacs-dark`  | Spacemacs Dark    | ✅ yes    |
| `spacemacs-light` | Spacemacs Light   |          |

Choosing one dispatches `[:theme/set <name>]`. **What you see:** the whole UI recolors instantly.

![Theme-switch sequence](../diagrams/seq-theme-switch.svg)

*Figure — source: [`docs/diagrams/seq-theme-switch.puml`](../diagrams/seq-theme-switch.puml)*

### 1.2 How the live switch works — the link-swap mechanism

Theming is a **two-stylesheet** design:

1. **A structural stylesheet** — `resources/public/css/app.css` — defines *layout* and references
   **only** CSS custom properties, e.g. `color: var(--vv-fg)`, `background: var(--vv-bg1)`. It never
   hard-codes a color.
2. **A theme palette** — `resources/public/css/themes/<name>.css` — defines those custom properties on
   `:root`, e.g. `--vv-bg1: #292b2e;`. Each theme is *just* a palette of `--vv-*` tokens.

`resources/public/index.html` loads the palette first (through a single, **id-bearing** link) and the
structural sheet second:

```html
<link id="vv-theme-link" rel="stylesheet" href="css/themes/spacemacs-dark.css">
<link rel="stylesheet" href="css/app.css">
```

Switching themes swaps **only** the palette link's `href` (`vinary.app.fx`):

```clojure
(rf/reg-fx
 :theme/apply
 (fn [theme]
   (when-let [^js link (.getElementById js/document "vv-theme-link")]
     (set! (.-href link) (str "css/themes/" theme ".css")))))
```

Because every visible color resolves through a `--vv-*` token, replacing the palette link recolors the
entire UI in one operation — backgrounds, headings, links, code highlighting, the find highlights, and
the watermark tint (which is `background-color: var(--vv-fg)` behind a CSS mask). The chosen theme is
also stored in `app-db` (`[:ui :theme]`) and reflected by the selector's `value`.

> **Cycle command.** There is also a `[:theme/cycle]` event that advances to the next theme in the list
> (`spacemacs-dark` → `spacemacs-light` → back). It is a command target for the forthcoming keybinding
> system; you can dispatch it today, and the selector will follow.

### 1.3 The `--vv-*` palette

Each theme defines the full token set on `:root`. The token groups (from
`themes/spacemacs-dark.css`):

| Group        | Tokens (examples)                                                              | Used for                                  |
|--------------|-------------------------------------------------------------------------------|-------------------------------------------|
| backgrounds  | `--vv-bg1`, `--vv-bg2`, `--vv-bg-code`, `--vv-bg-quote`                        | page, panels, inline code, blockquotes    |
| text         | `--vv-fg`, `--vv-fg-dim`, `--vv-fg-strong`, `--vv-fg-inverse`                  | body, muted, emphasized, on-accent text   |
| structure    | `--vv-border`, `--vv-highlight`, `--vv-disabled`                               | rules, selection, disabled controls       |
| headings/meta| `--vv-head1`…`--vv-head4`, `--vv-meta`                                         | h1–h6, folders, meta                       |
| syntax/emph. | `--vv-const`, `--vv-func`, `--vv-em`, `--vv-var`, `--vv-comment`, `--vv-error`, `--vv-code` | bold/numbers, functions, emphasis, variables, comments, errors, code text |
| find         | `--vv-find-hit-bg`, `--vv-find-hit-fg`, `--vv-find-active-bg`                  | the in-page find highlight colors          |

The structural sheet maps highlight.js classes (from `rehype-highlight`) onto these tokens too, so code
blocks are themed by the same palette.

---

## 2. Worked example — write your own theme

Make a "Solarized-ish" dark theme by **copying the dark palette** and changing values.

### Step 1 — copy the palette

```bash
cp resources/public/css/themes/spacemacs-dark.css \
   resources/public/css/themes/solar-dark.css
```

### Step 2 — edit the tokens

Open `resources/public/css/themes/solar-dark.css` and change the `--vv-*` values. You only need to edit
colors; keep **every token defined** (the structural sheet expects them all). For example:

```css
/* resources/public/css/themes/solar-dark.css */
:root {
  --vv-bg1: #002b36;     /* base03 */
  --vv-bg2: #073642;     /* base02 */
  --vv-bg-code: #073642;
  --vv-bg-quote: #073642;

  --vv-fg: #839496;      /* base0  */
  --vv-fg-dim: #586e75;  /* base01 */
  --vv-fg-strong: #fdf6e3;
  --vv-fg-inverse: #002b36;

  --vv-border: #094f5a;
  --vv-highlight: #094f5a;
  --vv-disabled: #586e75;

  --vv-head1: #268bd2;   /* blue   */
  --vv-head2: #2aa198;   /* cyan   */
  --vv-head3: #859900;   /* green  */
  --vv-head4: #b58900;   /* yellow */
  --vv-meta:  #cb4b16;   /* orange */

  --vv-const:   #d33682; /* magenta */
  --vv-func:    #6c71c4; /* violet  */
  --vv-em:      #859900;
  --vv-var:     #268bd2;
  --vv-comment: #586e75;
  --vv-error:   #dc322f; /* red */
  --vv-code:    #93a1a1; /* base1 */

  --vv-find-hit-bg: #586e75;
  --vv-find-hit-fg: #fdf6e3;
  --vv-find-active-bg: #b58900;
}
```

### Step 3 — register it in the selector

Add the new theme to the selector list in `vinary.ui.views` (the `themes` vector), so it appears in the
dropdown:

```clojure
;; vinary.ui.views
(def ^:private themes
  [["spacemacs-dark"  "Spacemacs Dark"]
   ["spacemacs-light" "Spacemacs Light"]
   ["solar-dark"      "Solarized Dark"]])   ; ← your new theme
```

> If you also want the `[:theme/cycle]` command to include it, add `"solar-dark"` to the
> `theme-cycle` vector in `vinary.app.events`.

### Step 4 — rebuild and select it

```bash
npm run compile && npm run start     # or just leave `npm run watch` running
```

Open the theme `<select>` and choose **Solarized Dark**. The UI recolors to your palette. Because the
switch is the link-swap from §1.2, no relaunch is needed once the file and selector entry exist.

> **Why the file lives under `resources/public/css/themes/`.** The `:theme/apply` effect builds the URL
> as `css/themes/<name>.css` relative to the renderer's `index.html`, so any palette dropped there is
> immediately addressable by name.

---

## 3. Stale legacy comments — ignore these

The current theme files contain **outdated header comments** carried over from the superseded v0.1.0
vmd-patching tool. They are **not accurate** for this application:

- `themes/spacemacs-dark.css` and `themes/spacemacs-light.css` say to *"select it with
  `VV_THEME=<name>` (or write the name into `~/.config/vinary-viewer/theme`), then relaunch vmd."* —
  **stale.** There is no `VV_THEME` environment variable, no `~/.config/vinary-viewer/theme` file, and
  no vmd. The real selection mechanism is the in-app `<select>` (the link-swap above), with **no
  relaunch**.
- `resources/public/index.html` says theming *"becomes reactive (re-frame) in P2"* — this **is already
  done**: the reactive `:theme/set` → `:theme/apply` path is live today.
- References to `src/style.css` / `src/themes/` in those comments point at the **legacy** tree; the
  current sheets are under `resources/public/css/`.

These comments will be cleaned up; until then, follow this page, not the file headers.

---

## 4. Planned configuration directory — Forthcoming

A user-level configuration directory is **planned and not yet built**:

| Path (planned)                                   | Purpose                                                                 | Status                |
|--------------------------------------------------|-------------------------------------------------------------------------|-----------------------|
| `~/.config/vinary-viewer/theme`                  | a default theme name read at startup                                    | Forthcoming (planned) |
| `~/.config/vinary-viewer/keybindings.edn`        | the keybinding preset + user overrides (see [04-keyboard-shortcuts.md](04-keyboard-shortcuts.md)) | Forthcoming (planned) |
| `~/.config/vinary-viewer/grammars/`              | a tree-sitter grammar registry for the planned source-preview kind      | Forthcoming (planned) |

None of these paths are read by the application today. They are documented so the eventual layout is
predictable. **Do not create them expecting an effect yet.**

---

## 5. Summary

| You want to…                | Do this                                                              | Status                |
|-----------------------------|---------------------------------------------------------------------|-----------------------|
| Switch theme                | toolbar theme `<select>` (or dispatch `[:theme/set …]` / `[:theme/cycle]`) | Available now    |
| Write a new theme           | copy a `themes/*.css`, edit `--vv-*`, add to the selector list, rebuild | Available now    |
| Set a default theme on disk | `~/.config/vinary-viewer/theme`                                     | Forthcoming (planned) |
| Configure keybindings       | `~/.config/vinary-viewer/keybindings.edn`                           | Forthcoming (planned) |

---

*Next: [06-troubleshooting.md](06-troubleshooting.md).*
