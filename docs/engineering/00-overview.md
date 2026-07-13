# The engineering pillar

This is the **engineering** documentation pillar for vinary-viewer `0.3.0-dev`. Where
the [theory](../theory/) pillar explains the *ideas* and the [architecture](../architecture/)
pillar explains the *runtime shape*, this pillar explains **how the project is built,
tested, released, and contributed to** — the tooling, the invariants those tools
enforce, and the reasons each mechanism exists.

> **Audience.** Read this pillar if you build the app from source, change a
> `shadow-cljs` build, add or upgrade a vendored asset, write a test, cut a
> release, or open a pull request. If you only want to *run* the app, start with
> [`usage/01-getting-started.md`](../usage/01-getting-started.md); if you want to
> understand *why* a subsystem is shaped the way it is, start with the theory
> pillar.

---

## 1. What "engineering" means here

vinary-viewer is a reactive ClojureScript / Electron desktop document previewer.
Its source lives in one tree (`src/vinary/**`) and compiles — through a single
`shadow-cljs` toolchain — into **five** distinct JavaScript artifacts that run in
three different hosts (an Electron main process, an Electron/Chromium renderer,
and two headless Node terminal tools). That one-source-tree/many-artifacts model
is the organising fact of the whole build, and it drives almost every engineering
decision documented here: which optimization level each artifact uses, which Node
modules are stubbed versus required at runtime, which assets must be vendored on
disk, and how each artifact is exercised by tests.

The pillar is deliberately *operational*. Every claim is grounded in a real
artifact you can open — [`shadow-cljs.edn`](../../shadow-cljs.edn),
[`package.json`](../../package.json), [`deps.edn`](../../deps.edn),
[`install.sh`](../../install.sh), the `scripts/*.mjs` vendoring pair scripts, the
`test/*-smoke.js` harnesses, and [`test/lint.js`](../../test/lint.js) — and each
page cites the file and line of behavior it describes.

---

## 2. Navigation map

The pages build on one another in roughly the order a contributor meets them:
build, then vendor, then test, then lint, then the terminal build, then release,
then the documentation gates, then continuous validation, then the contribution
workflow, then the memory-bounding engineering that ties back to the theory pillar.

| Page | Purpose |
|------|---------|
| [`01-build-system.md`](01-build-system.md) | The five `shadow-cljs` builds, the one-source-tree → five-artifacts model, `:release {:optimizations :simple}` and why not `:advanced`, the main-vs-renderer Node-module treatment, the MathJax font resolve, and the `deps.edn`/`package.json` split. |
| [`02-asset-vendoring.md`](02-asset-vendoring.md) | The `sync-*.mjs` / `check-*.mjs` pairs and the `*.lock.json` provenance model — what each lockfile pins (assets, grammars, pdf.js), how sync fetches and check verifies, and why vendoring happens at build time. |
| [`03-test-strategy.md`](03-test-strategy.md) | The test taxonomy: the DOM-free `:node-test` ClojureScript unit build, the nine JavaScript smoke harnesses, the fixtures, and the dev-vs-release smoke split. |
| [`04-lint-and-conventions.md`](04-lint-and-conventions.md) | `test/lint.js` (JS parse-check, CSS brace balance, `--vv-*` theme completeness across both CSS surfaces), the namespace↔file mapping rule, the ClojureScript and JavaScript style rules, and the renderer-never-touches-fs discipline. |
| [`05-terminal-build-and-launch.md`](05-terminal-build-and-launch.md) | The `vv` launcher's mode dispatch (GUI / `--cli` / `--tui` / `--help` / `--version`), how `install.sh` builds all three modes, and the `dist/cli` + `dist/tui` outputs. |
| [`06-release-process.md`](06-release-process.md) | The `install.sh` phases and env knobs, `uninstall.sh`, the SemVer + Keep-a-Changelog discipline, and how a release build differs from a dev build. |
| [`07-docs-and-screenshot-gates.md`](07-docs-and-screenshot-gates.md) | `scripts/screenshots.cjs` (headless Xvfb regeneration), the PlantUML render-regression greps, the diagram-catalog honesty loops, and the pinned diagram toolchain. |
| [`08-ci-and-validation-discipline.md`](08-ci-and-validation-discipline.md) | The finding that no CI config exists in-repo, the "tee the output, inspect once" validation discipline, and a concrete CI matrix the project *should* run. |
| [`09-contribution-workflow.md`](09-contribution-workflow.md) | Conventional Commits, pull-request expectations, the ADR template and "next free number" discipline, and the documentation guidelines this suite follows. |
| [`10-bounded-memory-engineering.md`](10-bounded-memory-engineering.md) | Preallocation as policy, bounded content retention (ADR-0010), stream/PDF virtualization, rAF/idle pacing (ADR-0023), and the deliberate decision *not* to add DOM node-eviction. |

---

## 3. How this pillar relates to the others

The documentation suite is organised into pillars, each answering a different
question. The engineering pillar is the *implementation contract* that the other
pillars presuppose.

| Pillar | Answers | Relationship to engineering |
|--------|---------|-----------------------------|
| [theory](../theory/) | *Why is it designed this way?* | The theory pillar states invariants (bounded memory, byte-parity, single-source-of-truth); engineering documents the **tools that enforce and verify** them. E.g. [theory/09](../theory/09-document-streaming-and-the-wpda.md)'s bounded-memory property is *asserted by* the `cli-smoke` peak-RSS check documented in [03](03-test-strategy.md). |
| [architecture](../architecture/) | *What are the runtime pieces?* | The architecture pillar names the processes and namespaces; engineering documents how those pieces are **compiled into artifacts** ([01](01-build-system.md)) and **wired for launch** ([05](05-terminal-build-and-launch.md)). |
| [design-decisions](../design-decisions/README.md) (ADRs) | *What single choice was made, and why?* | ADRs are immutable point decisions; the engineering pillar is the **standing operational reference** those decisions accumulate into. [ADR-0016](../design-decisions/0016-main-process-simple-optimization.md) decided `:simple`; [01](01-build-system.md) documents `:simple` as a permanent property of the build. |

A useful rule of thumb: **an ADR records a decision at a point in time; an
engineering page records the resulting standing practice.** When a practice
changes, the ADR log gains a new entry (see [09](09-contribution-workflow.md)),
and the affected engineering page is updated to match.

---

## 4. The load-bearing facts, up front

Four facts recur across the pillar and are worth stating once, here, before any
page assumes them:

1. **One source tree, five artifacts.** `src/vinary/**` compiles to `main`,
   `renderer`, `test`, `cli`, and `tui` — five `shadow-cljs` builds sharing the
   pure IR/streaming core. See [01](01-build-system.md).
2. **`:simple`, never `:advanced`, for every release build.** Closure's
   `:advanced` property-renaming silently breaks un-`^js`-hinted Electron/interop
   calls; every release-optimized build pins `:simple`. See
   [ADR-0016](../design-decisions/0016-main-process-simple-optimization.md) and
   [01](01-build-system.md).
3. **Assets are vendored on disk and pinned by a lockfile.** Font Awesome, the
   self-hosted fonts, the tree-sitter grammars, and the pdf.js worker + data are
   copied into `resources/public/` at build time and recorded with a sha256 in a
   `*.lock.json`. See [02](02-asset-vendoring.md).
4. **The renderer never touches the filesystem.** All privileged IO crosses the
   `window.vv` contextBridge seam; the renderer build stubs `fs`, `path`, and
   `url` to `false`. See [01](01-build-system.md) and
   [04](04-lint-and-conventions.md).

---

## 5. See also

- [`usage/02-installation-and-build.md`](../usage/02-installation-and-build.md) —
  the user-facing installer/build overview this pillar deepens.
- [`architecture/02-process-and-build-topology.md`](../architecture/02-process-and-build-topology.md)
  — the process/build topology from the architecture side.
- [`design-decisions/README.md`](../design-decisions/README.md) — the ADR log and
  its template.
- [`docs/README.md`](../README.md) — the whole-suite map and the shared
  conventions (math delimiters, diagram policy, citations) every page here obeys.
- [`docs/GLOSSARY.md`](../GLOSSARY.md) — canonical definitions for every term used
  across the suite.
