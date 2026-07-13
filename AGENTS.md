# Repository Guidelines

## Project Structure & Module Organization
`src/vinary` contains the ClojureScript app, compiled by shadow-cljs into **five** builds (`main`, `renderer`, `cli`, `tui`, `test`). `main` is the Electron main process (IO, dialogs, config, file watchers, git, the native web view, SSH, passwords); `renderer` renders documents; `ui` holds Reagent/re-frame interface pieces; `app` contains app-db, navigation, commands, effects, subscriptions, and DataScript helpers; `input` owns keymaps and resolver logic. The shared `ir` (common document IR), `stream` (bounded-memory streaming), and `terminal`/`cli`/`tui` (the terminal previewer) layers back both the GUI and the `vv --cli`/`--tui` tools. PDFs render **in-renderer** via pdf.js (the old main-owned native PDF view was retired — ADR-0013). Runtime assets live in `resources/`; generated browser output goes to `resources/public/js`; compiled Node output goes to `dist/`. Tests are under `test/`, and project docs live in `docs/`. (The top-level `src/*.js` patch files and `src/mouse-forward-back` are dormant v0.1.0 vmd-patch artifacts, not loaded by the running app.)

## Build, Test, and Development Commands
- `npm run compile`: compile `main` and `renderer` shadow-cljs builds.
- `npm run watch`: rebuild both app builds during development.
- `npm run dev`: compile, then launch Electron with `electron .`.
- `npm run start`: run Electron against existing compiled output.
- `npm run release`: produce release builds using the configured `:simple` optimizations.
- `npm run compile:cli` / `compile:tui`: build the `vv --cli` / `vv --tui` terminal renderers.
- `npm test`: compile the `test` build and run it, then the ssh-config / ssh-transport / content-service / git-tree / cli / tui smokes. `npm run test:electron` (and `test:electron:release`) drive the app end-to-end.
- `node test/lint.js`: run JS parse checks, CSS brace balance, and `--vv-*` theme-variable checks.

## Coding Style & Naming Conventions
Use idiomatic ClojureScript with two-space indentation, kebab-case vars/functions, and namespace-to-file mapping such as `vinary.input.keymaps-registry` in `src/vinary/input/keymaps_registry.cljs`. Keep renderer filesystem access behind the existing `contextBridge`/IPC boundary; the renderer should operate on plain JSON/EDN data. JavaScript files use CommonJS, `'use strict'`, and minimal dependencies. CSS custom properties use the `--vv-*` prefix and must be defined by every theme in `resources/public/css/themes/`.

## Testing Guidelines
ClojureScript tests use `cljs.test`; place pure logic tests in `test/vinary/*_test.cljs` with namespaces ending in `-test` so the `:ns-regexp "-test$"` build finds them. Keep DOM-free unit coverage in the shadow-cljs test build. Use JS harnesses such as `test/test-sidebar.js` for Node browser/Electron shims. There is no numeric coverage gate; add regression tests for navigation, keybindings, rendering helpers, and IPC-facing behavior.

## Commit & Pull Request Guidelines
Recent history follows Conventional Commit style: `fix: ...`, `feat(scope): ...`, and `docs(scope): ...`. Keep commits focused and imperative. Pull requests should explain user-visible behavior, list validation commands run, link related issues, and include screenshots or short recordings for UI, theme, sidebar, tab, or renderer changes.

## Security & Configuration Tips
User config lives under `~/.config/vinary-viewer/`; do not commit local `keybindings.edn`, grammar WASM files, or generated build output. Treat external links, local file paths, diagram rendering, and grammar loading as trust boundaries, and update `docs/security/threat-model.md` when those boundaries change.
