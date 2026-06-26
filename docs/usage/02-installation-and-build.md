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

## 2. The two builds

vinary-viewer is compiled into two JavaScript artifacts, one for each Electron
process. Both are declared in [`../../shadow-cljs.edn`](../../shadow-cljs.edn).

![Two-build container view](../diagrams/container-two-build.svg)

*Diagram source: [`../diagrams/container-two-build.puml`](../diagrams/container-two-build.puml).*

| Build | Target | Entry | Output | Role |
|-------|--------|-------|--------|------|
| `main` | `:node-script` | `vinary.main.core/main` | `dist/main/main.js` | Electron main process: window, file IO, native PDF/web views, config, watchers, git tree. |
| `renderer` | `:browser` | `vinary.renderer.core/init` | `resources/public/js/main.js` | Chromium renderer: re-frame/Reagent UI, Markdown rendering, tabs, source preview, sidebar, keybindings. |

The renderer build explicitly stubs Node modules such as `fs` and `path`. All
privileged work crosses the `window.vv` mediator exposed by `resources/preload.js`.

---

## 3. Build commands

| Command | Expands to | Use |
|---------|------------|-----|
| `npm run compile` | `shadow-cljs compile main renderer` | One-shot debug build. |
| `npm run watch` | `shadow-cljs watch main renderer` | Rebuild on source changes. |
| `npm run release` | `shadow-cljs release main renderer` | Optimized release build used by the installer. |
| `npm run start` | `electron .` | Launch existing artifacts. |
| `npm run dev` | `shadow-cljs compile main renderer && electron .` | Build then launch for development. |
| `npm test` | `shadow-cljs compile test && node dist/test/test.js` | ClojureScript unit tests. |
| `node test/lint.js` | JavaScript/CSS checks | Parse checks and theme variable checks. |
| `npm run test:electron` | `electron --no-sandbox test/electron-smoke.js` | Electron smoke test. |

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
