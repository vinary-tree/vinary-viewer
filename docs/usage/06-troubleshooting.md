# Troubleshooting

This is a symptom-first guide. The fastest tools are the renderer DevTools
console, `window.__vvdb()`, and `window.__vvds()`.

---

## 1. Blank window

The window opens but no UI appears.

Check the compiled artifacts:

```bash
ls dist/main/main.js resources/public/js/main.js
npm run compile
```

Check the preload mediator from the renderer console:

```js
window.vv
```

It should expose methods such as `open`, `onContent`, `requestSettings`, and
`syncRetainedFiles`. If it is missing, verify you launched from the repository
root and that `resources/preload.js` exists.

---

## 2. File does not live-refresh

Most likely causes:

| Cause | Check |
|-------|-------|
| The file is not retained by any open tab history. | `__vvdb()` and inspect `:ui :tabs`; local files in any tab history should be retained. |
| The file is unreadable or was moved. | `test -r <path>` |
| The editor uses unusual save semantics. | Append a line in place with `printf` and see if refresh fires. |
| The render failed after content arrived. | Check the document body for `Error:` and inspect DevTools console. |

Retained local paths are synced from the renderer to main after tab/history
changes. The main process keeps watchers only for that retained set.

---

## 3. Markdown shows "Rendering..." or an error

Diagnose by visible state:

| State | Meaning |
|-------|---------|
| `Rendering...` | Content arrived but async Markdown rendering has not committed HTML yet. Check DevTools for a rejected render promise. |
| `Error: ...` | The document has `:doc/error`, from a main-process read failure or renderer render failure. |
| Escaped text | The file was classified as `text`, not `markdown`. Check extension. |

Markdown rendering returns HTML plus TOC and asset metadata. Stale async render
results are ignored if their stamp no longer matches the current document
stamp.

---

## 4. Scrolling flickers over embedded SVGs

The current mitigation has three parts:

1. Markdown rendering extracts TOC metadata during render, so the Contents panel
   does not re-parse the whole HTML just to discover headings.
2. Embedded SVG sizing is applied only when measurements change, avoiding
   repeated style writes during scroll.
3. The view waits for async figure sizing before refreshing cached heading
   offsets and applying the initial scroll restore.

If flicker remains, capture a reproduction with the file path, viewport size,
theme, and whether the SVGs are local files or remote images.

---

## 5. PDF is blank, stuck "rendering…", or slow

PDFs render **in the renderer DOM via pdf.js** (ADR-0013), not in a native view. A page draws to a
`<canvas>` inside `.vv-content` once it scrolls into view.

Checks:

| Symptom | Check |
|---------|-------|
| Blank area | Confirm the file is classified as `pdf` and readable; check the DevTools console for a `[pdf]` error. |
| A **"rendering…"** chip lingers on a page | The page is heavy (mesh shadings / transparency / dense vector art) and rasterizes on the **main thread** — give it a moment; it is working, not frozen. |
| A **"⚠ failed to render — click to retry"** chip | The page errored; click it to retry, and read the logged `[pdf] page N render failed` reason in the console. |
| PDF does not refresh after save | Confirm the path is retained and watched. |

pdf.js parses on a worker but paints on the main thread, so a figure-dense page can block briefly;
`isEvalSupported true` speeds up shaded figures (see [feature 11](../features/11-native-pdf.md)).

Pages that render **white** and only appear when DevTools opens (or after an action), then white out again,
were the in-renderer **virtualization** stranding pages: docking DevTools (a resize) clears and re-lays-out
the page canvases (`rescale!`), and a too-aggressive reliance on the `IntersectionObserver` re-firing — plus
two async render races — could leave them blank until a scroll. The fix re-renders the visible pages
**deterministically** after a resize/rescale (`render-visible!`) and guards the async renders with a
generation epoch. (The page canvas is additionally CPU-backed via `willReadFrequently` with
`backgroundThrottling` off — *defensive* hardening for GPU-compositing setups; note this machine (NVIDIA +
Wayland) runs Electron with **software** compositing — `app.getGPUFeatureStatus().gpu_compositing` =
`disabled_software` — so the bug here was the virtualization path, not the GPU compositor. `VV_SOFTWARE_GL=1`
forces software rendering if you ever need to rule the GPU out.)

---

## 6. Markdown preview shows a duplicated band while scrolling (software compositing)

While scrolling a tall Markdown preview — especially one with several SVG figures — a horizontal band
≈ the **top fifth of the window is duplicated into a band at the bottom fifth** (the bottom pixels are
replaced by the top's). **Multiple** such bands can appear, they shift as you keep scrolling, and
**moving the mouse cursor over them makes them spread**. It often correlates with the SVG figures
appearing to flicker — disappear and reappear.

This is a **software display-compositor present-stage bug**, not a problem with the document. On hosts
where Electron runs with GPU compositing disabled — `app.getGPUFeatureStatus().gpu_compositing =
disabled_software` (e.g. NVIDIA + Wayland) — Chromium normally presents only each frame's **damage
rect** to the window surface (*partial swap*). Presenting only the damaged sub-rect into a
rotated/recycled buffer can leave a **stale band** of an earlier scroll offset in the buffer's
undamaged region — the band you see. Cursor motion generates extra frames that rotate through more
stale buffers, so the bands multiply and shuffle. The SVG figures are an **amplifier** (Chromium
evicts and re-decodes their bitmaps as they scroll off/on screen — the flicker — producing large
damage regions); they do **not** create the copy.

Two *different* Chromium stages can strand stale pixels under software compositing, so the app appends
two switches in `vinary.main.core/main` (see `vinary.main.startup/chromium-switches`) — **raster ≠ swap**
(raster produces tile bitmaps; swap/present pushes the final frame to the screen):

| Switch | Stage | What it fixes |
|--------|-------|---------------|
| `--ui-disable-partial-swap` | **present** (`viz` display compositor) | redraws + presents the whole viewport every frame, so no stale band survives — the fix for **this** scrolling-band artifact |
| `--disable-partial-raster` | **raster** (`cc` tiles) | re-rasterizes tiles fully (a distinct stale-content-within-a-tile mode) |

Both apply in the **main** process at launch (not visible in renderer DevTools). If you still see a
band after a rebuild, these experiments bisect the cause:

| Experiment | Expected if it is partial-swap | Meaning |
|-----------|-------------------------------|---------|
| Rebuild (`npm run compile`) + relaunch | **band gone** | confirmed — partial-swap was the cause |
| `VV_SOFTWARE_GL=1` **without** the swap switch | **band persists** | `disableHardwareAcceleration` removes the GPU process but does *not* disable partial swap, so a persisting band *corroborates* the swap diagnosis rather than refuting it |
| `--ozone-platform=x11` + `VV_SOFTWARE_GL=1` | band gone only here | the defect is specific to the Wayland software present path — run under Xwayland (the prior "X11 segfaults the GPU process" caveat is moot with hardware acceleration off) |
| Temporarily make the `figures/*.svg` 404 | banding drops sharply | confirms the SVGs are the amplifier (diagnostic only, not a fix) |

If none of the above clears it, the renderer-side fallback is to render the figures as **inline SVG**
(part of the DOM, not a decode-cache-evictable `<img>`) in `src/vinary/renderer/figures.cljs` — see the
related SVG-scroll note in §4 above. (Related cause; the same present-stage bug underlies the SVG flicker
there.)

---

## 7. Source highlighting is missing

The source preview still opens without a grammar, but highlighting may be plain.

Checks:

```text
~/.config/vinary-viewer/grammars/<lang>/grammar.wasm
~/.config/vinary-viewer/grammars/<lang>/highlights.scm
```

Then restart or request grammars again through the normal app startup path. If a
source language has no matching grammar, CodeMirror still shows the text.

---

## 8. Keybindings do not behave as expected

Checks:

| Symptom | Check |
|---------|-------|
| Wrong mode | Inspect `__vvdb()` at `:ui :input :mode`. |
| Wrong active set | Inspect `__vvdb()` at `:ui :keymaps :active`. |
| Custom set not loaded | Check `~/.config/vinary-viewer/keybindings.edn` parses as EDN. |
| Chord times out | Increase the set's timeout or simplify the binding. |
| Key types into input instead of dispatching | Expected when focus is in a text input and the active mode allows text entry. |

Use `Settings > Key Bindings > Customize...` to confirm the binding list the
resolver is using.

---

## 9. DevTools inspection

Renderer console helpers:

```js
__vvdb()
__vvds()
__vvkeymap("vim")
```

Use `__vvdb()` for tabs, history, active theme, settings, keybinding UI state,
and sidebar state. Use `__vvds()` for cached document content metadata.

---

## 10. "A JavaScript error occurred in the main process" dialog

If the **main** process hits an unexpected error, vinary shows an error dialog
with a **Copy details** button — click it to put the full stack on your
clipboard (the full trace is also printed to the terminal / logs via
`console.error`). Paste that into a bug report. The app keeps running after the
dialog is dismissed.

---

*Back: [05-configuration.md](05-configuration.md).*
