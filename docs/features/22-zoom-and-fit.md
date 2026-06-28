# Zoom & Fit

**Status: Available now.**

---

## 1 · What it is

A single, **context-aware** zoom system. Whatever the active tab shows, the same controls zoom it and the
same **percentage** reflects it. There are two surfaces:

- A **zoom bar** — a slim, always-visible strip at the bottom of the content pane:
  `[ − ] [ 100% ▾ ] [ + ]`.
- A **View ▸ Fit** submenu — radio items **Fit Width**, **Fit Page**, **Actual Size** (PDF only; see
  [feature 11](11-native-pdf.md)).

> **Defined terms.**
> **Zoom factor** — the multiplier applied to the active surface; `1.0` = 100% (the default in every
> view). **Zoom percent** — the factor × 100, rounded, shown in the bar.
> **Fit mode** — a PDF-only *rule* (fit the page width, the whole page, or 100%) that re-resolves to a
> factor whenever the window resizes; an explicit zoom clears it.
> **Surface / context** — the thing a tab actually renders (a PDF, a web page, or an app-renderer DOM
> view); the zoom target is chosen from it.

### Three zoom contexts

The active tab's **context** decides what a zoom command targets. The percentage in the bar always tracks
that surface's live zoom.

| Context | When the active tab is… | What zooms | Mechanism |
|---|---|---|---|
| `:pdf` | a `.pdf` ([feature 11](11-native-pdf.md)) | the pdf.js render **scale** | in-renderer `[:ui :pdf :scale]` |
| `:web` | an `http(s)` page **or** a local `.html`/`.htm`/`.xhtml` ([feature 19](19-web-view-keyboard-and-copy.md)) | the **web page** (the native view's own `webContents`) | `setZoomFactor` over `vv:http-zoom` |
| `:window` | Markdown, image, source, or a directory listing | the **app window** (the renderer DOM) | the app-window zoom over `vv:zoom` |

`vinary.app.zoom/context` computes this from the active URI scheme and `:doc/kind`; everything else
(menu, keymap, zoom bar) routes through it, so there is **one** rule, not three.

## 2 · How you use it

**The zoom bar** sits at the bottom of the content pane and is always visible:

```
 content pane
 ┌───────────────────────────────────────────────────────────────┐
 │                                                               │
 │   (PDF / web page / markdown / image / source / directory)    │
 │                                                               │
 │                                          ┌────────────┐       │
 │                                          │  ✓ 100%    │ ◀ preset dropdown
 │                                          │    125%    │   opens UPWARD
 │                                          │    150%    │       │
 │                                          └────────────┘       │
 ├───────────────────────────────────────────────────────────────┤
 │  …status / hovered link…                     [ − ][ 100% ▾ ][ + ] │  ◀ .vv-bottombar
 └───────────────────────────────────────────────────────────────┘
```

- **`−` / `+`** — zoom out / in by one step. These dispatch the *same* `[:view/zoom -1]` / `[:view/zoom 1]`
  as the `Ctrl+-` / `Ctrl++` keys and **View ▸ Zoom Out / Zoom In** — one single source of truth.
- **The `%` field** — editable. Type a number and press `Enter` (or click away) to jump to that exact
  zoom; `Esc` cancels the edit. (While the field has focus, app keybindings are suspended so digits type
  normally.)
- **`▾`** — opens an **upward** dropdown of preset percentages **50 · 75 · 100 · 125 · 150 · 200 · 300 ·
  400**, check-marking (`✓`) the current value. Picking one applies it immediately.

**The Fit submenu** (PDF only) is under **View ▸ Fit**; the active mode is radio-marked:

| Item | Effect | Dispatch |
|---|---|---|
| **Fit Width** | scale so the page width fills the pane | `[:pdf/fit :width]` |
| **Fit Page** | scale so a whole page is visible | `[:pdf/fit :page]` |
| **Actual Size** | 100% (factor `1.0`) | `[:pdf/fit :actual]` |

The fit mode persists in `settings.edn` and **re-fits on window resize** (see [feature 11](11-native-pdf.md)).
Any explicit zoom (bar, keys, or menu) clears the fit mode for that PDF.

### Keybindings & menu (single source of truth)

| Action | Keys | View menu | Zoom bar | Event |
|---|---|---|---|---|
| Zoom in | `Ctrl` `+` | Zoom In | `+` | `[:view/zoom 1]` |
| Zoom out | `Ctrl` `-` | Zoom Out | `−` | `[:view/zoom -1]` |
| Reset to 100% | `Ctrl` `0` | Reset Zoom | (type `100`) | `[:view/zoom 0]` |
| Zoom to an exact % | — | — | field / preset | `[:view/zoom-set <pct>]` |
| Fit (PDF) | — | Fit ▸ … | — | `[:pdf/fit …]` |

## 3 · Internals

| Piece | Where |
|---|---|
| Context resolver + current-percent + preset list (DOM-free, shared by events & subs) | `vinary.app.zoom` (`context`, `percent`, `presets`) |
| Live percent for the active surface | sub `:view/zoom-percent` → `zoom/percent` |
| Step zoom, routed by context (PDF scale / web page / app window) | event `:view/zoom` |
| Absolute zoom to a % (field / preset), routed by context | event `:view/zoom-set` (clamped 10–800%) |
| Main reports the resolved app-window / web-view factor | `vv:zoom-changed` → `:view/zoom-changed` |
| PDF scale + fit mode (fit persists in `settings.edn`) | events `:pdf/zoom`, `:pdf/fit`; engine echoes the fit-resolved scale via `:pdf/scale-resolved` |
| Web-page zoom (the native view's `webContents`) | `vinary.main.web` (`vv:http-zoom` / `vv:http-zoom-set` → `setZoomFactor`) |
| App-window zoom (renderer DOM) | `vinary.main.shell` (`vv:zoom` / `vv:zoom-set`) |
| The bar component (`.vv-bottombar`) | `vinary.ui.zoombar` |
| View ▸ Fit / Zoom items | `vinary.ui.menubar` (`:sub/fit`) |

**Why a single `context` function.** The `−`/`+` buttons, the keybindings, and the View menu all emit the
*same* `[:view/zoom …]` event; `:view/zoom-set` is the *only* extra event (for the field and presets). The
event handler asks `zoom/context` which surface is active and forwards to the PDF scale, the web page, or
the app window. Because the **same** resolver also feeds `:view/zoom-percent`, the displayed `%` can never
drift from what a `−`/`+` press would change — read and write share one rule. For PDF fit modes, the
engine reports its *resolved* scale back (`:pdf/scale-resolved`) so the bar shows the live `%` even while a
fit mode is active and the View ▸ Fit radio stays marked.

## 4 · Design notes

Zoom is **not** stored on the document — it is interaction state in `app-db` (`[:ui :pdf :scale]`,
`[:ui :web-zoom]`, `[:ui :window-zoom]`), so it never disturbs the DataScript content cache and survives
live-refresh. The PDF half of this feature is recorded in
[ADR-0013 (in-renderer pdf.js)](../design-decisions/0013-in-renderer-pdfjs.md); the web-page zoom rides
the same isolated `persist:vinary-web` session as the rest of the
[web view](19-web-view-keyboard-and-copy.md).
