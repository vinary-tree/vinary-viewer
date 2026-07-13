# The build system — one source tree, five artifacts

This page documents how vinary-viewer `0.3.0-dev` compiles. It covers the five
`shadow-cljs` builds and their roles, the one-source-tree/many-artifacts model,
the release optimization level and why it is `:simple` rather than `:advanced`,
the different treatment of Node modules in the main process versus the renderer,
the MathJax font-resolve workaround, and the two-file dependency split between
Clojure tooling (`deps.edn`) and npm packages (`package.json`).

> **Audience.** Read this when changing a build target, adding a dependency, or
> diagnosing why an interop call that works in `npm run dev` crashes in a release
> build. For the user-facing build commands, see
> [`usage/02-installation-and-build.md`](../usage/02-installation-and-build.md).

---

## 1. Key terms

| Term | Definition |
|------|------------|
| **shadow-cljs** | The ClojureScript build tool (`thheller/shadow-cljs`) that compiles `.cljs` to JavaScript. It reads [`shadow-cljs.edn`](../../shadow-cljs.edn) for build definitions and `deps.edn` for the classpath. |
| **build target** | A shadow-cljs `:target` — the *kind* of output. This project uses `:node-script` (a runnable Node script), `:browser` (a bundle for a web page), and `:node-test` (a Node test runner). |
| **Closure Compiler** | Google's JavaScript optimizer that shadow-cljs invokes for release builds. Its optimization levels — `:none`, `:simple`, `:advanced` — trade minification aggressiveness against safety. |
| **`:advanced` property renaming** | Under `:advanced`, Closure renames object *properties* (e.g. `.toggleDevTools` → `.Xc`) unless an *extern* protects them. This is the hazard [ADR-0016](../design-decisions/0016-main-process-simple-optimization.md) avoids. |
| **extern / `^js` hint** | ClojureScript infers an extern (protecting a property from renaming) for an interop call `(.foo obj)` only when the receiver carries a `^js` type hint or is otherwise inferred to be a JS object. |
| **release vs dev build** | `shadow-cljs release <builds>` applies Closure optimization + strips devtools; `shadow-cljs compile <builds>` is a fast, unoptimized dev build. |

---

## 2. The five builds

vinary-viewer is one ClojureScript source tree (`src/vinary/**`) that compiles
into **five** JavaScript artifacts, each declared as a build in
[`shadow-cljs.edn`](../../shadow-cljs.edn). Three hosts run them: the Electron
**main** process (Node), the Electron **renderer** (Chromium), and headless
**Node** for the tests and the two terminal tools.

![The five shadow-cljs builds compiled from one source tree](../diagrams/container-five-builds.svg)

*Diagram source: [`../diagrams/container-five-builds.puml`](../diagrams/container-five-builds.puml).*

| Build | Target | Entry | Output | Role |
|-------|--------|-------|--------|------|
| `main` | `:node-script` | `vinary.main.core/main` | `dist/main/main.js` | Electron **main** process: windows, file IO, dialogs, config, watchers, native web/PDF views, the git file tree, and the content service. |
| `renderer` | `:browser` | `vinary.renderer.core/init` | `resources/public/js/main.js` | Electron **renderer** (Chromium): the re-frame / Reagent UI, Markdown/Org/LaTeX/diff rendering, tabs, source preview, sidebar, keybindings. |
| `test` | `:node-test` | *(ns-regexp `-test$`)* | `dist/test/test.js` | The DOM-free ClojureScript unit-test runner: every `test/vinary/**/*_test.cljs`. |
| `cli` | `:node-script` | `vinary.cli.core/main` | `dist/cli/vv-cli.js` | `vv --cli`: the headless terminal document renderer (ANSI + kitty/sixel), Electron-free. |
| `tui` | `:node-script` | `vinary.tui.core/main` | `dist/tui/vv-tui.js` | `vv --tui`: the interactive raw-ANSI terminal viewer. |

Each row is a literal transcription of a `:builds` entry. For example, the `cli`
build is:

```clojure
;; shadow-cljs.edn
:cli {:target    :node-script
      :main      vinary.cli.core/main
      :output-to "dist/cli/vv-cli.js"
      :release   {:compiler-options {:optimizations :simple}}}
```

### 2.1 One source tree, five artifacts

The five artifacts are not five codebases; they are five *entry points* over one
shared body of ClojureScript. The pure, DOM-free core — the common document IR
(`vinary.ir.*`), the streaming spine (`vinary.stream.*`), the semiring/WPDA/
transducer machinery — is compiled into whichever artifact needs it. The terminal
tools (`cli`, `tui`) reuse the *same* IR front-ends and streaming decoder as the
renderer, lowering to ANSI through `vinary.ir.backend.ansi` instead of HTML; the
`test` build compiles the same core with no host at all and asserts its
properties. This is why a single bug fix in the IR core is verified once (in the
`test` build) and inherited by all four running artifacts.

The build commands that produce these artifacts are npm scripts (§6); the GUI
pair is `main renderer`, and the terminal pair is `cli` / `tui`:

```bash
shadow-cljs compile main renderer   # GUI dev build  (npm run compile)
shadow-cljs release main renderer   # GUI release     (npm run release)
shadow-cljs compile cli             # terminal render (npm run compile:cli)
shadow-cljs compile tui             # terminal viewer (npm run compile:tui)
shadow-cljs compile test            # unit tests      (part of npm test)
```

---

## 3. `:release {:optimizations :simple}` — and why never `:advanced`

Every build that has a `:release` profile pins `:optimizations :simple`. The four
release-optimized builds — `main`, `renderer`, `cli`, `tui` — all carry the same
one-line override:

```clojure
:release {:compiler-options {:optimizations :simple}}
```

The reason is a whole *class* of crash, documented in full in
[ADR-0016](../design-decisions/0016-main-process-simple-optimization.md) and
summarized here because it governs the entire build.

Closure's `:advanced` mode renames object **properties** to shrink output. It
only leaves a property alone if an *extern* protects it, and ClojureScript infers
an extern for an interop call `(.foo obj)` only when the receiver is `^js`-hinted
(or inferred to be a JS object). An un-hinted interop method that appears
**exactly once** in the codebase gets no extern and is renamed. The shipped
symptom: `View ▸ Developer Tools` called `(.toggleDevTools (wc))` on an untyped
getter's return value; `.toggleDevTools` was the only such call, so `:advanced`
renamed it to `.Xc`, and a native Electron `webContents` has no `.Xc` — the main
process threw `TypeError: wk(...).Xc is not a function`, **but only in a release
build** (dev builds are unoptimized, so it never reproduced there). An audit found
five more un-hinted interop sites surviving only by accident.

`:simple` is the fix: it still minifies and dead-code-eliminates (and strips the
renderer's devtools/re-frame-10x preloads), but it **never renames properties**,
so every Electron/Node/interop method name survives verbatim. The size cost of
un-renamed output is immaterial: none of these artifacts is shipped over a
network — `dist/main/main.js`, `vv-cli.js`, and `vv-tui.js` are local Node
scripts, and the renderer bundle is loaded from a local `file://`.

The rule generalizes to a build-wide invariant:

> **Invariant.** No vinary-viewer build uses Closure `:advanced`. The `renderer`
> build has always pinned `:simple` (advanced renaming breaks the re-frame /
> DataScript / unified-remark interop); [ADR-0016](../design-decisions/0016-main-process-simple-optimization.md)
> extended the same pin to `main`; and `cli` / `tui` were born with it (the ANSI
> path shares the unified/rehype/content-service interop that would break).

The invariant is protected by a **release-build regression gate**,
`npm run test:electron:release`, which builds `release` and runs the Electron
smoke against it (see [03-test-strategy.md](03-test-strategy.md#5-the-dev-vs-release-smoke-split)).
A dev-build smoke cannot catch an advanced-only crash, so the gate must use a
release build.

---

## 4. Main requires Node at runtime; the renderer stubs it away

The `main` and `renderer` builds treat Node's built-in modules in opposite ways,
because they run in opposite hosts.

### 4.1 Main — `:node-script` keeps `require` at runtime

`:node-script` uses shadow-cljs's `:js-provider :require`, so `require("electron")`
and Node built-ins (`fs`, `path`, `child_process`, …) stay **runtime requires**,
resolved by the Electron runtime when `dist/main/main.js` loads. They are not
bundled. This is correct: the main process *is* a Node process, and the Electron
runtime provides these modules. The `shadow-cljs.edn` comment states it directly:

```clojure
;; Electron MAIN process (Node). :node-script uses :js-provider :require, so `require("electron")`
;; and node built-ins stay runtime requires (resolved by the Electron runtime) — no bundling.
```

### 4.2 Renderer — `:browser` stubs `fs` / `path` / `url` to `false`

The renderer runs in Chromium, which has no filesystem. Any code path that would
`require("fs")` there is a bug: the renderer must reach privileged IO **only**
across the `window.vv` contextBridge seam (exposed by
[`resources/preload.js`](../../resources/preload.js)), never the filesystem
directly. So the `renderer` build resolves those modules to `false`:

```clojure
;; shadow-cljs.edn — renderer :js-options
:resolve {"fs" false "fs/promises" false "path" false "url" false
          ...}
```

Resolving a module to `false` makes any accidental import compile to an empty
object, turning a would-be filesystem call into an immediate, visible failure
rather than a silent capability leak. This build-level stub is the compile-time
half of a discipline whose review-time half — the renderer never `require`s `fs`
— is described in [04-lint-and-conventions.md](04-lint-and-conventions.md#5-the-renderer-never-touches-the-filesystem).

### 4.3 The MathJax default-font resolve

The renderer's `:resolve` map carries one more, subtler, entry. MathJax 4's SVG
output statically imports its **default** font through Node's `#default-font/*`
subpath import (declared in MathJax's own `package.json` `imports` field, which
points at the `newcm` font). shadow-cljs's browser bundler cannot resolve a Node
subpath import, so the renderer bundle would fail to build. The fix redirects that
import to the Modern (Latin Modern) font the app actually renders with:

```clojure
"#default-font/svg/default.js"
{:target :npm :require "@mathjax/mathjax-modern-font/cjs/svg/default.js"}
```

This does two things at once: it makes the browser bundle **resolvable**, and it
makes the statically-imported default font **match** the `:fontData` the renderer
passes to MathJax at runtime — so the default and the configured font are the same
Latin Modern face, not two different families. The `test` build (`:node-test`)
needs no such redirect: it leaves the import a runtime `require`, which Node
resolves on its own via the `imports` field. (For the MathJax dynamic-font
chunking hazard this pairs with, see the project memory note on synchronous MathJax
font loading.)

---

## 5. The dependency split — `deps.edn` (Clojure) and `package.json` (npm)

vinary-viewer draws dependencies from two package ecosystems, and the split is
along a clean line: **the ClojureScript language + build + UI stack comes from
Maven via `deps.edn`; the JavaScript runtime libraries and dev tooling come from
npm via `package.json`.**

| File | Ecosystem | Holds | Examples |
|------|-----------|-------|----------|
| [`deps.edn`](../../deps.edn) | Maven / Clojure CLI | The ClojureScript compiler, the reactive UI stack, and Clojure test tooling. | `org.clojure/clojurescript`, `reagent`, `re-frame`, `re-com`, `datascript`, `re-posh`, `day8.re-frame/re-frame-10x`, `org.clojure/test.check`. |
| [`package.json`](../../package.json) | npm | JavaScript libraries the compiled code calls at runtime, plus the Node dev toolchain. | `electron`, `shadow-cljs`, `react`/`react-dom`, `pdfjs-dist`, `web-tree-sitter`, `@mathjax/src`, the `unified`/`remark`/`rehype` pipeline, `ssh2`, `mermaid`. |

`shadow-cljs` appears in **both** files (`deps.edn` pins the JVM artifact
`thheller/shadow-cljs 3.2.0`; `package.json` pins the npm launcher
`shadow-cljs 3.2.0`) because it has a foot in each world: it is a JVM program that
consumes the Maven classpath *and* an npm package that Node invokes. The two pins
are kept at the same version deliberately.

`deps.edn`'s `:paths` is `["src" "resources" "test"]`, so the compiler's classpath
includes the source, the runtime resources, and the tests. The comment in
`deps.edn` records that these versions are aligned with the sibling f1r3fly tools
(LightningBug and the pgmcp webui) so the three share one dependency baseline;
`core.async` arrives transitively via shadow-cljs.

One JVM-level note lives at the top of `shadow-cljs.edn`: Java 26 requires
`--sun-misc-unsafe-memory-access=allow` for dependencies that touch
`sun.misc.Unsafe`, so the build passes that JVM option.

```clojure
;; shadow-cljs.edn — Java 26 needs this for some deps that touch sun.misc.Unsafe
:jvm-opts ["--sun-misc-unsafe-memory-access=allow"]
```

---

## 6. Which asset syncs each build command runs

The npm build scripts are not bare `shadow-cljs` invocations; each prepends the
**asset syncs** its artifact needs (the vendoring mechanism is
[02-asset-vendoring.md](02-asset-vendoring.md)). The GUI builds need the UI assets
and pdf.js; the terminal builds need the tree-sitter grammars and the graphics
wasm. The split matters because it explains why a plain `npm run compile` does
**not** sync grammars.

| Command | Expands to |
|---------|------------|
| `npm run compile` | `assets:sync` + `pdfjs:sync` + `shadow-cljs compile main renderer` |
| `npm run release` | `assets:sync` + `pdfjs:sync` + `shadow-cljs release main renderer` |
| `npm run compile:cli` | `grammars:sync` + `graphics:sync` + `shadow-cljs compile cli` |
| `npm run release:cli` | `grammars:sync` + `graphics:sync` + `shadow-cljs release cli` |
| `npm run compile:tui` | `grammars:sync` + `graphics:sync` + `shadow-cljs compile tui` |
| `npm run release:tui` | `grammars:sync` + `graphics:sync` + `shadow-cljs release tui` |

The GUI's source-preview *also* needs the tree-sitter grammars, but its build
scripts do not sync them — [`install.sh`](../../install.sh) syncs grammars
**once**, up front, because they are runtime assets shared by both the GUI source
preview and the terminal renderers. Syncing them once (rather than once per `cli`
and again per `tui`) is why the installer re-fetches nothing on a warm re-install;
see [05-terminal-build-and-launch.md](05-terminal-build-and-launch.md) and
[06-release-process.md](06-release-process.md).

---

## 7. Artifact map

| Path | Produced by | Purpose |
|------|-------------|---------|
| `dist/main/main.js` | `main` build | Electron main-process bundle; `package.json` `"main"` points here. |
| `resources/public/js/main.js` | `renderer` build | Renderer bundle loaded by `resources/public/index.html`. |
| `dist/test/test.js` | `test` build | The unit-test runner; run with `node dist/test/test.js`. |
| `dist/cli/vv-cli.js` | `cli` build | The `vv --cli` terminal renderer. |
| `dist/tui/vv-tui.js` | `tui` build | The `vv --tui` terminal viewer. |

Note that `package.json` has **no `bin` field**: the `vv` launcher is written by
`install.sh`, not published by npm, and it mode-dispatches into these artifacts
(see [05-terminal-build-and-launch.md](05-terminal-build-and-launch.md)).

---

## 8. References and see also

- [ADR-0016 — Compile the Electron main process with `:simple`, not `:advanced`](../design-decisions/0016-main-process-simple-optimization.md)
  — the decision this page's §3 summarizes.
- [`shadow-cljs.edn`](../../shadow-cljs.edn) — the authoritative build definitions.
- [`deps.edn`](../../deps.edn) / [`package.json`](../../package.json) — the two
  dependency manifests.
- [02-asset-vendoring.md](02-asset-vendoring.md) — the sync scripts each build
  command runs.
- [05-terminal-build-and-launch.md](05-terminal-build-and-launch.md) — how the
  `cli` / `tui` artifacts are launched.
- [`architecture/02-process-and-build-topology.md`](../architecture/02-process-and-build-topology.md)
  — the process/build topology from the architecture pillar.
- shadow-cljs user guide — <https://shadow-cljs.github.io/docs/UsersGuide.html> —
  the `:target`, `:js-provider`, and `:release` semantics cited here.
- Closure Compiler compilation levels — <https://developers.google.com/closure/compiler/docs/compilation_levels>
  — the `:simple` vs `:advanced` distinction.
