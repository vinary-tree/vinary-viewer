# 7 — History navigation

`vinary-viewer` gives `vmd` complete file-history navigation — back and forward across **every** way you
open a file — and routes four different input methods to it.

## The problem in stock `vmd`

`vmd` keeps a history object (`hist`) in its renderer, but stock `vmd` only **pushes** to it inside
`handleLink` — i.e. only when you click a *link*. Opening a file any other way (the OS file dialog, a
fresh launch) doesn't grow the history, so the **Back/Forward menu items stay disabled** and their
accelerators (`Alt`+`←` / `Alt`+`→`) do nothing. And there was no path at all for mouse thumb buttons.

## The unified model

Rather than invent a parallel history stack, `vinary-viewer` makes **all** navigation feed `vmd`'s own
`hist`, and makes every input method emit the same pair of IPC messages the menu already uses:
`history-back` and `history-forward`.

![history flow](figures/history-flow.svg)

The pieces:

| Marker / code | Role |
| --- | --- |
| `[vmd-hist]` (`renderer/main.js`) | In `onContent`, push the new file path to `hist` on **every** open (not just link clicks). This keeps `hist` populated, so the Back/Forward menu — and thus the `Alt`+`←`/`→` accelerators — are enabled. |
| `[vmd-nav]` (`create-window.js`) | Forward OS `app-command` (`browser-backward`/`browser-forward`) to the renderer as `history-back`/`history-forward`. |
| `[vmd-mfb]` (`create-window.js` + addon) | The native X11 hook turns mouse thumb buttons 8/9 into the same `history-back`/`history-forward` IPC. |
| `sidebar.js` (mouse handler) | Where the window manager *does* deliver the thumb buttons as DOM mouse buttons 3/4, emit the same IPC directly. |

All four converge on `history-back`/`history-forward`, which `vmd`'s renderer already handles by calling
`hist.back()` / `hist.forward()` and then `navigateTo(...)` — re-rendering the target document.

## The four input paths

1. **Keyboard — `Alt`+`←` / `Alt`+`→`.** These are `vmd`'s menu accelerators for Back/Forward. They were
   effectively dead because `hist` rarely grew; `[vmd-hist]` fixes that by growing `hist` on every open,
   re-enabling the menu items and their accelerators.
2. **OS app-command.** Some mice/desktop environments deliver the thumb buttons as an X11
   *app-command* (`browser-backward`/`browser-forward`) rather than as raw buttons. `[vmd-nav]` catches
   `win.on('app-command', …)` and forwards it.
3. **Mouse thumb buttons (native).** On the common case where Electron 3 swallows buttons 8/9, the native
   addon grabs them at the X server and emits the history IPC. See
   [06 — Native addon](06-native-addon.md).
4. **DOM mouse buttons.** If your window manager *does* surface the thumb buttons to the page as mouse
   buttons 3/4, `sidebar.js`'s mouse handler emits the same IPC (debounced), so that path works too.

These paths are complementary, not redundant: which one actually carries your thumb buttons depends on
your mouse driver and desktop environment, so `vinary-viewer` wires up all of them and they all land on
the same history.

## Why unify on `vmd`'s native history

An earlier design kept a separate history stack in `sidebar.js`. It was removed because it competed with
`vmd`'s own `hist` and could desynchronize from it (e.g. when a link click grew `vmd`'s stack but not the
custom one). Driving the single, authoritative `hist` — and merely *feeding* and *triggering* it from the
new inputs — keeps one consistent history no matter how a document was opened.

## Try it

Open a repository in `vmd`, click through a few files in the tree, then:

- press `Alt`+`←` / `Alt`+`→`, **or**
- press your mouse's back/forward thumb buttons (with the pointer over the `vmd` window).

Either way you move through the same file history.
