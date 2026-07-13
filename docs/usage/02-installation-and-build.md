# Installation and build

This page describes the installer, launchers, shadow-cljs builds, generated
artifacts, development hot reload, and inspection hooks.

> **Audience.** Use [01-getting-started.md](01-getting-started.md) for the
> quickest first run. Use this page when changing build or runtime behavior.

---

## 1. Installer and launchers

The supported installer is the root `./install.sh` script:

```bash
./install.sh
```

It is idempotent and performs:

| Phase | Behavior |
|-------|----------|
| Dependencies | Runs `npm install --no-fund --no-audit`. |
| Build | Runs `npx shadow-cljs release main renderer` by default. Set `VV_BUILD=compile` to build debug artifacts. |
| Launchers | Writes `vinary-viewer` and symlinks `vv` into `${BIN:-$HOME/.local/bin}`. |
| Legacy notice | Detects signs of the old v0.1.0 vmd-patching install and prints manual cleanup guidance. |

Override the destination:

```bash
BIN=/usr/local/bin ./install.sh
```

Uninstall launchers only:

```bash
./uninstall.sh
```

The installer does not delete the repository and does not delete
`~/.config/vinary-viewer/`.

---

## 2. The five builds

vinary-viewer compiles into five shadow-cljs targets, all declared in
[`../../shadow-cljs.edn`](../../shadow-cljs.edn). The two that make up the desktop
GUI are one per Electron process; the remaining three are the DOM-free unit-test
runner and the two terminal renderers (`vv --cli` / `vv --tui`).

The diagram below shows the two GUI process builds (main + renderer) and the IPC
seam between them; the test/CLI/TUI builds are additive Node scripts that reuse the
same shared, Electron-free namespaces.

![Two-build container view](../diagrams/container-two-build.svg)

*Diagram source: [`../diagrams/container-two-build.puml`](../diagrams/container-two-build.puml).*

| Build | Target | Entry | Output | Role |
|-------|--------|-------|--------|------|
| `main` | `:node-script` | `vinary.main.core/main` | `dist/main/main.js` | Electron main process: window, file IO, config, watchers, git tree, the native web view, and the SSH/SFTP transport. |
| `renderer` | `:browser` | `vinary.renderer.core/init` | `resources/public/js/main.js` | Chromium renderer: re-frame/Reagent UI, Markdown rendering, in-renderer pdf.js, tabs, source preview, sidebar, keybindings. |
| `test` | `:node-test` | (ns regexp `-test$`) | `dist/test/test.js` | DOM-free ClojureScript unit tests. |
| `cli` | `:node-script` | `vinary.cli.core/main` | `dist/cli/vv-cli.js` | `vv --cli` — the headless terminal document renderer (§ [07](07-terminal-cli-tui.md)). |
| `tui` | `:node-script` | `vinary.tui.core/main` | `dist/tui/vv-tui.js` | `vv --tui` — the interactive terminal viewer (§ [07](07-terminal-cli-tui.md)). |

The `main`, `cli`, and `tui` `:node-script` builds keep npm packages (`ssh2`,
`chokidar`, …) as runtime `require()`s. The `renderer` `:browser` build explicitly
stubs Node modules such as `fs` and `path`; all privileged work crosses the
`window.vv` mediator exposed by `resources/preload.js`. Release builds use
`:simple` optimization (not `:advanced`, whose property-renaming breaks the
re-frame / DataScript / unified interop).

---

## 3. Build commands

### 3.1 GUI build and run

| Command | Expands to | Use |
|---------|------------|-----|
| `npm run compile` | `assets:sync && pdfjs:sync && shadow-cljs compile main renderer` | One-shot debug build of the GUI. |
| `npm run watch` | `assets:sync && pdfjs:sync && shadow-cljs watch main renderer` | Rebuild the GUI on source changes. |
| `npm run release` | `assets:sync && pdfjs:sync && shadow-cljs release main renderer` | Optimized (`:simple`) GUI build used by the installer. |
| `npm run start` | `electron .` | Launch existing artifacts. |
| `npm run dev` | `assets:sync && pdfjs:sync && shadow-cljs compile main renderer && electron .` | Build then launch for development. |

The `compile`/`watch`/`release`/`dev` scripts run `assets:sync` and `pdfjs:sync`
first (§3.3), so the bundled fonts, icons, and pdf.js worker are always in place.

### 3.2 Terminal builds

| Command | Expands to | Use |
|---------|------------|-----|
| `npm run compile:cli` | `grammars:sync && graphics:sync && shadow-cljs compile cli` | Debug build of `vv --cli`. |
| `npm run release:cli` | `grammars:sync && graphics:sync && shadow-cljs release cli` | Optimized build of `vv --cli`. |
| `npm run compile:tui` | `grammars:sync && graphics:sync && shadow-cljs compile tui` | Debug build of `vv --tui`. |
| `npm run release:tui` | `grammars:sync && graphics:sync && shadow-cljs release tui` | Optimized build of `vv --tui`. |

The `cli`/`tui` scripts run `grammars:sync` and `graphics:sync` first — the
terminal renderers share the source-highlighting grammars and the image WASM with
the GUI. `./install.sh` builds all four GUI+terminal artifacts (it syncs the shared
grammar/graphics assets once, then runs `shadow-cljs <release|compile> main renderer`
and `… cli tui`).

### 3.3 Asset synchronization

These fetch/stage runtime assets into `resources/`. The build scripts above invoke
the `:sync` variants automatically; run them directly for CI or to refresh assets.

| Command | Does |
|---------|------|
| `npm run assets:sync` / `assets:check` | Stage / verify the self-hosted fonts and Font Awesome icons. |
| `npm run pdfjs:sync` / `pdfjs:check` | Stage / verify the pdf.js distribution + worker. |
| `npm run grammars:sync` / `grammars:check` | Build / verify the bundled tree-sitter grammar WASM. |
| `npm run graphics:sync` | Stage the resvg image WASM used by terminal graphics. |

### 3.4 Tests and lint

| Command | Expands to | Use |
|---------|------------|-----|
| `npm test` | `shadow-cljs compile test && node dist/test/test.js && node test/ssh-config-smoke.js && node test/ssh-transport-smoke.js && node test/content-service-smoke.js && node test/git-tree-smoke.js && npm run test:cli && npm run test:tui` | The full non-Electron suite: it compiles the `test` build and runs the DOM-free unit tests, then the SSH-config, SSH-transport, content-service, and git-tree smokes, and finally the CLI and TUI smokes. |
| `npm run test:cli` | `compile:cli && node test/cli-smoke.js && node test/graphics-smoke.js` | Build `vv --cli` and run the CLI + terminal-graphics smokes. |
| `npm run test:tui` | `compile:tui && node test/tui-smoke.js` | Build `vv --tui` and run the TUI smoke (headless `--drive` replay). |
| `npm run test:electron` | `electron --no-sandbox test/electron-smoke.js` | Electron GUI smoke test (debug artifacts). |
| `npm run test:electron:release` | `release && VV_RELEASE=1 electron --no-sandbox test/electron-smoke.js` | The GUI smoke against a fresh optimized release build. |
| `npm run test:extensions` | `electron --no-sandbox test/extensions-smoke.js` | Browser-extension runtime smoke (no sandbox). |
| `npm run test:extensions:sandbox` | `electron test/extensions-smoke.js` | The extension smoke with the Chromium sandbox enabled. |
| `node test/lint.js` | JavaScript/CSS checks | Parse checks and theme variable checks across both CSS surfaces. |

### 3.5 Screenshots

| Command | Does |
|---------|------|
| `npm run screenshots` | Compile the renderer and capture UI screenshots headlessly under `xvfb-run`. |
| `npm run screenshots:display` | The same capture, but on your real display (no `xvfb`). |

Pass an initial file to the start script with npm's `--` separator:

```bash
npm run start -- README.md
```

---

## 4. Development hot reload

For active renderer work:

```bash
npm run watch
npm run start
```

The `renderer` build uses shadow-cljs devtools and calls
`vinary.renderer.core/reload` after a successful hot reload. That remounts the
Reagent root while preserving the existing re-frame `app-db` and DataScript
connection, so tabs and cached content survive renderer code reloads.

Main-process changes require restarting Electron because Node loads
`dist/main/main.js` once at startup.

---

## 5. DevTools hooks

Debug builds expose small inspection helpers in the renderer console:

| Hook | Returns |
|------|---------|
| `window.__vvdb()` | Current re-frame `app-db`, including tabs, history, theme, keybinding UI state, and settings. |
| `window.__vvds()` | Current DataScript content-cache documents. |
| `window.__vvkeymap("vim")` | Development helper that dispatches `[:keymap/select "vim"]`. |

The debug renderer also preloads re-frame-10x, re-frisk, and Chrome DevTools
formatters.

---

## 6. Artifact map

| Path | Produced by | Purpose |
|------|-------------|---------|
| `dist/main/main.js` | `main` build | Electron main-process bundle. |
| `resources/public/js/main.js` | `renderer` build | Renderer bundle loaded by `index.html`. |
| `resources/public/index.html` | Hand-authored | Browser entry for the renderer bundle. |
| `resources/preload.js` | Hand-authored | `contextBridge` IPC mediator. |
| `resources/web-preload.js` | Hand-authored | In-app HTTP web view heading and scroll-spy bridge. |
| `resources/public/css/app.css` | Hand-authored | Structural CSS using `--vv-*` theme tokens. |
| `resources/public/css/themes/*.css` | Hand-authored | Theme palettes. |

---

## 7. Validation discipline

For validation, capture output to a temporary log, inspect it, and remove the
log after use. Example:

```bash
npm run compile > /tmp/vv-compile.log 2>&1
```

This keeps hard-to-reproduce build or test output available while you diagnose
failures, without leaving temporary logs in the repository.

---

*Next: [03-opening-files-and-tabs.md](03-opening-files-and-tabs.md).*
