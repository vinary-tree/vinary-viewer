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
| The file is unreadable or was moved. | `test -r <path>` — a banner over the document says *"Couldn't reload the file — showing the last loaded version"*; hover it for the reason (below). |
| The editor uses unusual save semantics. | Append a line in place with `printf` and see if refresh fires. |
| The render failed after content arrived. | Check the document body for `Error:` and inspect DevTools console. |

Retained local paths are synced from the renderer to main after tab/history
changes. The main process keeps watchers only for that retained set.

**A banner over the document instead of new content.** When a refresh cannot be
shown, the window keeps the last good version on screen and says why in a small
banner (its tooltip carries the exact error):

| Banner | Meaning | What to do |
|--------|---------|------------|
| *Couldn't reload the file — showing the last loaded version* | Main could not read the file: deleted, renamed away, permissions, a dropped SSH connection. | Restore the file (it refreshes by itself when it reappears) or press **Retry**. |
| *The updated file couldn't be displayed — showing the last good version* | A PDF's new bytes did not open (read while LaTeX was still writing it). | Usually nothing: the next save replaces it. **Retry** re-reads now. |
| *The file on disk is incomplete (still being written?) — showing the last complete version* | Main caught the PDF half-written and it did not finish within a few seconds (a LaTeX run that stopped with an error, or a writer that is still busy). | Fix and re-run the build: the finished file replaces it in place. |
| *…incomplete — showing what could be read* | The same, on a first open: pdf.js recovered an earlier finished revision of the file. | As above. If nothing at all can be read, the error page says the PDF will appear once the file is finished. |

**×** dismisses the banner; the next successful refresh removes it. A document
that never loaded still shows the error page.

How long a half-written PDF is waited for: main re-reads it after 0.25, 0.75, 1.75
and 3.75 s (each wait ends early when the file changes again), and a file that
holds still for 1.5 s while unfinished counts as finished-but-broken. The unit of
that schedule is `VV_PDF_SETTLE_MS` (default `250`): a writer slower than that —
a very large PDF on a network filesystem — can be given longer with
`VV_PDF_SETTLE_MS=1000 vv talk.pdf`.

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
| A banner over the document — **"… showing the last good version"** (or *last complete* / *last loaded version*) — with **Retry** and **×** | A **refresh note**: the document changed on disk, and the new version could not be shown, so the last one that could stays on screen. The wording says why: *"The updated file couldn't be displayed"* — the new PDF bytes did not open in pdf.js (typically read while `pdflatex` was still writing); *"Couldn't reload the file"* — main could not re-read it (deleted, permission refused, an SSH connection lost); *"The file on disk is incomplete (still being written?)"* — the PDF's trailer is missing (a compile still running, or one that failed). Nothing is lost; the next good version replaces the old one and clears the note by itself, and **Retry** asks for it now. See [ADR-0046](../design-decisions/0046-reload-safe-pipelined-remote-reads.md). |
| A slide deck opens as a scrolling document (or a document opens as slides) | Detection reads the PDF's own evidence. Run `vv --cli --deck-info deck.pdf` to see what it found and which rule decided ([usage 07](07-terminal-cli-tui.md)); **View ▸ View as Slide Show / View as Document** switches this view either way, and Preferences ▸ Slides ▸ *Open detected slide decks as* sets the default. A Beamer deck from a single XeLaTeX pass has no page labels, so its overlays step one page at a time: compile it twice (after an edit, one pass carries the previous version's labels, so compile until latexmk is done). See [feature 35](../features/35-slide-shows.md#limitations-and-troubleshooting). |
| A Beamer deck's note pages show as slides (or a slide is skipped) | The Slides layout hides a `show notes` deck's note pages only when it can prove which they are. Run `vv --cli --deck-info deck.pdf`: `notes`, `notePages`, `notesProof` and `notesReason` say what was decided and why. pdfLaTeX and LuaLaTeX write labels that prove them in one pass; a single XeLaTeX pass has none, so the viewer compares page text instead. *Notes on a second screen* ▸ *Show whole pages* shows every page as it is. A page a `talk.pdfpc` sidecar marks hidden is skipped by → and ←, as in pdfpc. See [feature 35](../features/35-slide-shows.md#decks-with-speaker-notes). |
| The wheel does not flip slides while a show is blanked | By design: while the screen is blanked (`b` / `w`), the first wheel step, key or click only brings the slide back. |

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

## 6. Markdown preview shows a duplicated band while scrolling (NVIDIA + Wayland)

While scrolling a tall Markdown preview — especially one with several SVG figures — a horizontal band
≈ the **top fifth of the window is duplicated into a band at the bottom fifth** (the bottom pixels are
replaced by the top's). **Multiple** such bands can appear, they shift as you keep scrolling, and
**moving the mouse cursor over them makes them spread**. It often correlates with the SVG figures
appearing to flicker — disappear and reappear.

This is the **GPU process's Wayland window-surface presentation path** mis-presenting a stale region of
the window — **not** a problem with the document. Screen-captured A/B testing isolated a single lever:
with the GPU process active, **GPU-rasterized tiles** go through the broken Vulkan-on-Wayland presentation
and paint the band, and adding/removing **`--disable-gpu-rasterization`** flips it clean↔banded with every
other flag held constant (default, `--use-angle=gl`/`gl-egl`/`vulkan`, SkiaRenderer, vsync, sRGB all band
*without* it; all clean *with* it). The SVG figures are only an **amplifier** (Chromium evicts/re-decodes
their bitmaps as they scroll — the flicker — producing large repaint regions); they do not create the copy.

**The fix:** the app appends **`--disable-gpu-rasterization`** on Wayland sessions, gated by
`vinary.main.startup/disable-gpu-rasterization?` in `vinary.main.core/main`. This **keeps the GPU process
on** (best performance — hardware presentation, WebGL, video) and only moves rasterization to software,
which costs nothing here because GPU compositing/rasterization are already blocklisted to software on this
NVIDIA + Wayland combo (`app.getGPUFeatureStatus().gpu_compositing = disabled_software`). Overrides:

| Env | Effect |
|-----|--------|
| *(default on Wayland)* | GPU rasterization off, GPU process **on** — no band |
| `VV_GPU_RASTER=1` | force full GPU rasterization (band returns on this host); for systems where Wayland GPU rasterization works |
| `VV_SOFTWARE_GL=1` | full software — remove the GPU process entirely (last resort, anywhere incl. X11) |

> What does **not** fix it (verified — don't retry): `--disable-partial-raster`, `--ui-disable-partial-swap`,
> `--disable-features=Vulkan`, `--disable-gpu-vsync`, `--force-color-profile=srgb`, and any ANGLE backend
> (`--use-angle=gl`/`gl-egl`/`vulkan`) — the band persists with all of them unless `--disable-gpu-rasterization`
> is also present. Forcing Vulkan (`--use-vulkan`) makes it worse (blank window). The partial-raster/swap
> switches are still appended (`vinary.main.startup/chromium-switches`) as harmless defensive measures.

To debug a recurrence: capture it with an **external** screenshot tool while scrolling (`spectacle`/`grim`
— Electron's `capturePage` cannot see present-layer artifacts), and compare `VV_GPU_RASTER=1` vs default to
confirm GPU rasterization is the cause.

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
