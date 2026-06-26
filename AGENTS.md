# Repository Guidelines

## Project Structure & Module Organization
`src/vinary` contains the ClojureScript app. `main` is the Electron main process for IO, dialogs, config, native PDF, and diagram services; `renderer` renders documents; `ui` holds Reagent/re-frame interface pieces; `input` owns keymaps and resolver logic; `app` contains app-db, navigation, commands, effects, subscriptions, and DataScript helpers. Top-level `src/*.js` files support sidebar and patch workflows; `src/mouse-forward-back` is the native mouse-button addon. Runtime assets live in `resources/`; generated browser output goes to `resources/public/js`; compiled Node output goes to `dist/`. Tests are under `test/`, and project docs live in `docs/`.

## Build, Test, and Development Commands
- `npm run compile`: compile `main` and `renderer` shadow-cljs builds.
- `npm run watch`: rebuild both app builds during development.
- `npm run dev`: compile, then launch Electron with `electron .`.
- `npm run start`: run Electron against existing compiled output.
- `npm run release`: produce release builds using the configured simple optimizations.
- `npm test`: compile the `test` build and run `node dist/test/test.js`.
- `node test/lint.js`: run JS parse checks, CSS brace balance, and theme CSS variable checks.

## Coding Style & Naming Conventions
Use idiomatic ClojureScript with two-space indentation, kebab-case vars/functions, and namespace-to-file mapping such as `vinary.input.keymaps-registry` in `src/vinary/input/keymaps_registry.cljs`. Keep renderer filesystem access behind the existing `contextBridge`/IPC boundary; the renderer should operate on plain JSON/EDN data. JavaScript files use CommonJS, `'use strict'`, and minimal dependencies. CSS custom properties use the `--vv-*` prefix and must be defined by every theme in `src/themes/`.

## Testing Guidelines
ClojureScript tests use `cljs.test`; place pure logic tests in `test/vinary/*_test.cljs` with namespaces ending in `-test` so the `:ns-regexp "-test$"` build finds them. Keep DOM-free unit coverage in the shadow-cljs test build. Use JS harnesses such as `test/test-sidebar.js` for Node browser/Electron shims. There is no numeric coverage gate; add regression tests for navigation, keybindings, rendering helpers, and IPC-facing behavior.

## Commit & Pull Request Guidelines
Recent history follows Conventional Commit style: `fix: ...`, `feat(scope): ...`, and `docs(scope): ...`. Keep commits focused and imperative. Pull requests should explain user-visible behavior, list validation commands run, link related issues, and include screenshots or short recordings for UI, theme, sidebar, tab, or renderer changes.

## Security & Configuration Tips
User config lives under `~/.config/vinary-viewer/`; do not commit local `keybindings.edn`, grammar WASM files, or generated build output. Treat external links, local file paths, diagram rendering, and grammar loading as trust boundaries, and update `docs/security/threat-model.md` when those boundaries change.
