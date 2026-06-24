# 8 — Security and internals

`vinary-viewer` does two things that deserve a clear-eyed security account: it **modifies `vmd`'s
package** and it **grabs X11 input**. This chapter states the trust model, the threat surface, and the
specific choices that keep the implementation safe.

## Trust model

`vinary-viewer` is a **local developer tool you install on your own machine**. It:

- runs with **your** privileges (never elevated; `install.sh` does not use `sudo`),
- makes **no network connections** and contains **no telemetry**, and
- only writes to: `vmd`'s package directory (the patches + `.vv.bak` backups), `~/.vmdrc`,
  `~/.config/vinary-viewer/`, your shell rc files (the `vmd()` wrapper), and `VV_HOME`.

Because the renderer script is loaded into `vmd` with `nodeIntegration`, **`sidebar.js` is as privileged
as `vmd` itself** — it can use Node APIs. You should trust `vinary-viewer`'s code to the same degree you
trust `vmd`. The code is small and readable on purpose; nothing is minified or fetched at runtime.

## Threat surface and the choices that contain it

### Loading `sidebar.js` (the bootstrap)

The bootstrap is an inline `require()` of an **absolute path** to a file you installed. It is not a remote
URL and not user-controlled. `vmd`'s main process installs a `file:` protocol interceptor
(`interceptStringProtocol('file', …)`) to serve rendered Markdown; in principle that could interpose on a
`<script src="file://…">` fetch. The inline `require()` deliberately avoids the question — it uses Node's
module loader rather than Chromium's resource fetch, so the interceptor is never in the path. The whole
bootstrap is wrapped in `try/catch`, so a missing or broken script can't stop `vmd` from loading.

### Invoking `git`

The file tree comes from `git ls-files`, invoked via **`execFileSync('git', [args])`** — an argument
array, **no shell**. Repository paths and names are passed as data, never interpreted by a shell, so a
file named, say, `$(rm -rf ~).md` is harmless. `git` is run with the document's directory as `cwd`.

### Building the DOM from file paths

The tree and table of contents are built with **`createElement` + `textContent`**, never `innerHTML`.
File names and heading text are therefore inserted as **text**, not parsed as HTML, so a path or heading
containing `<script>` or other markup cannot inject nodes. The in-page find highlighter wraps matches in
`<mark>` elements it creates itself, operating only on existing text nodes inside `.markdown-body`.

### Reading SVG files for figure sizing

`scaleFigures` reads embedded SVGs with `fs` to measure their `viewBox` and font size. It only **reads**
local files that the document already references, parses two numbers out of them, and sets a CSS width.
No SVG content is executed or injected.

### Patching `vmd`

The three patches (`create-window.js`, `renderer/main.js`, `vmd.html`) are **idempotent** (guarded by
unique markers — re-running never double-applies) and **reversible** (each backs up the original to
`<file>.vv.bak`; `uninstall.sh` restores them). If `vmd` is upgraded, `npm` replaces the files with fresh
stock and the wrapper re-applies the patches, refreshing the backups. A patch whose anchor no longer
matches a future `vmd` logs a warning and is skipped rather than corrupting the file.

### Grabbing X11 input

The native addon establishes a **passive** `XGrabButton` on **`vmd`'s own window** for buttons 8/9 only.
It requires no special privilege, affects no other window, and activates only while the pointer is over
`vmd`. It opens its own X display connection and never reads keystrokes or other buttons. See
[06 — Native addon](06-native-addon.md).

## What `vinary-viewer` deliberately does **not** do

- No network I/O, no telemetry, no auto-update.
- No `eval`, no `innerHTML`, no remote code.
- No writes outside the locations listed under [Trust model](#trust-model).
- No elevated privileges; no changes to other applications or to the X session beyond the scoped button
  grab.

## A note on malicious documents

`vmd` — not `vinary-viewer` — renders Markdown to HTML. `vinary-viewer`'s features operate on the
*already-rendered* DOM (reading `textContent`, wrapping text nodes) and on local files the document
references. They add no new way for a document to run code. As always, treat opening an untrusted document
in any Electron-based viewer (including stock `vmd`) with the same caution you'd treat opening it in a
browser.
