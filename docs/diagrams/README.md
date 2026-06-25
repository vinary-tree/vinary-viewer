# Diagram conventions and catalog

This directory holds **every** figure in the vinary-viewer documentation suite. All diagrams are
authored as **PlantUML** (`*.puml`) source and rendered to **SVG**. This page explains how to render
them, the shared-theme convention, the canonical color → concept legend, *why* we standardised on
PlantUML over Mermaid, and the catalog of diagrams with the documents that embed each one.

> **Terminology defined before use.** A *diagram source* is a `.puml` text file. A *rendered diagram*
> is the `.svg` produced from it. The *shared theme* `_vv-theme.iuml` is a PlantUML *include file*
> (`.iuml`) of color and style definitions that every diagram `!include`s, so a given color **always**
> denotes the same concept across the whole suite.

---

## 1. How to render

PlantUML is a Java program that turns `.puml` text into SVG/PNG. On this workstation it is already on
`PATH` as `plantuml` (Arch Linux package `plantuml`).

```bash
# From the repository root. Render every diagram in this directory to SVG, in place.
plantuml -tsvg docs/diagrams/*.puml

# Render a single diagram.
plantuml -tsvg docs/diagrams/container-two-build.puml

# Render to a separate output directory (keeps the .puml sources clean).
plantuml -tsvg -o ../rendered docs/diagrams/*.puml
```

Each command writes `<name>.svg` next to (or, with `-o`, under) the corresponding `<name>.puml`.
The `-tsvg` flag selects SVG output; SVG is the canonical format because it is theme-crisp at any zoom
and embeds cleanly in Markdown.

If `plantuml` is not installed:

```bash
# Arch Linux
sudo pacman -S plantuml
# Debian / Ubuntu
sudo apt-get install plantuml
# Any platform with a JDK + Graphviz: download plantuml.jar, then
java -jar plantuml.jar -tsvg docs/diagrams/*.puml
```

PlantUML uses **Graphviz** (`dot`) for the layout of some diagram kinds (component, class, state). If
`dot` is missing you will see a *"Cannot find Graphviz"* banner in the rendered image; install the
`graphviz` package to fix it. Sequence, activity, and deployment diagrams do **not** require Graphviz.

### Embedding a rendered diagram in a Markdown doc

A documentation page embeds a diagram by linking the rendered SVG **and** always citing the `.puml`
source path so the figure stays reproducible:

```markdown
![Two-build container view](../diagrams/container-two-build.svg)

*Figure — source: [`docs/diagrams/container-two-build.puml`](../diagrams/container-two-build.puml)*
```

Documentation pages should embed the rendered SVG rather than inline PlantUML source. The `.puml` file is
still the single source of truth — always cite it near the figure, and never hand-edit an `.svg`.

---

## 2. The `!include _vv-theme.iuml` convention

Every `.puml` file in this directory begins with:

```text
@startuml <name>
'' One-line note: "PlantUML over Mermaid because <reason>."
!include _vv-theme.iuml
...
@enduml
```

`_vv-theme.iuml` ([`docs/diagrams/_vv-theme.iuml`](_vv-theme.iuml)) defines:

- the **per-concept color macros** (`MAIN_COLOR`, `RENDERER_COLOR`, `IPC_COLOR`, …) and their darker
  border twins (`MAIN_BORDER`, …);
- global `skinparam` defaults (background `#FBF8EF`, the *Inter* font, no shadowing, rounded corners,
  thin grey arrows);
- a reusable `<<planned>>` stereotype (dashed grey fill) for **Forthcoming** components.

Because the palette is `!define`d in one place and *re-used* everywhere, a reader who learns the legend
once can read every diagram in the suite. The palette is deliberately drawn from the application's own
Spacemacs `--vv-*` design tokens (see `resources/public/css/themes/spacemacs-dark.css`), so the
diagrams visually match the running UI.

> **Path note.** Diagrams `!include _vv-theme.iuml` by its **bare name** (they all live in the same
> directory, and `plantuml` resolves includes relative to the file being rendered). The fact-sheet's
> shorthand `!include ../diagrams/_vv-theme.iuml` describes the *conceptual* location; on disk the
> include is a sibling, so the bare name is what the `.puml` files actually use.

---

## 3. Canonical color → concept legend

A color **always** means one concept, suite-wide. Every diagram also carries its own self-contained
`legend` block so it can be read in isolation; this table is the master reference.

| Color (hex)  | Swatch concept                                   | Where it appears                                                |
|--------------|--------------------------------------------------|-----------------------------------------------------------------|
| `#3A5BA0`    | **MAIN process / Node IO** (slate)               | `main.core`, `main.service`, fs reads, chokidar, git            |
| `#2D9574`    | **Renderer / Chromium UI** (teal)                | reagent views, the Chromium process, the rendered document body |
| `#B1951D`    | **IPC seam** (amber) — preload / contextBridge / Mediator | the `window.vv` boundary; **every IPC arrow is amber**          |
| `#A45BAD`    | **DataScript SSOT** (purple)                     | the documents/tabs relational store (`vinary.app.ds`)           |
| `#7590DB`    | **app-db ephemeral** (blue-violet)               | re-frame `app-db` UI state (active tab, theme, find, history)   |
| `#4F97D7`    | **re-frame machinery** (blue)                    | events · subscriptions · effects; also shadow-cljs in build views |
| `#9F8766`    | **Filesystem / editor** (tan)                    | the watched file, the external editor that saves it             |
| `#67B11D`    | **Markdown / unified** (green)                   | the remark→rehype pipeline, rendered HTML                       |
| `#E0211D`    | **Errors / retractions** (red)                   | `:doc/error`, `vv:error`, `[:db/retract …]`                     |
| `#DDDDDD`    | **Forthcoming / planned** (dashed grey)          | features not yet built; carries the `«planned»` stereotype      |

**Reading aids used across the suite:**

- **Sequence diagrams** use `autonumber`, activation bars, and `== phase ==` dividers, and show the
  **full round trip** (renderer → seam → main → seam → renderer) rather than a one-way slice.
- **Activity / flow diagrams** use `partition` swimlanes to separate the MAIN and RENDERER processes.
- **Dashed borders** mark trust boundaries (e.g. the Chromium sandbox) and planned components.

---

## 4. Why PlantUML, not Mermaid

This is a deliberate, suite-wide decision; each `.puml` restates the *specific* reason in its header.
The general rationale:

1. **Color-by-concept.** PlantUML lets us bind a fill color to an arbitrary concept via `!define`
   macros in a shared include, so the same purple always means "DataScript". Mermaid's `classDef`
   styling is per-diagram and cannot be shared as cleanly across many files.
2. **First-class boundary objects.** The contextBridge/sandbox trust boundary is drawn as a real,
   stereotyped, dashed-border container that *encloses* the renderer. Mermaid subgraphs cannot express
   an enclosing, color-coded trust boundary as a concept.
3. **Richer diagram kinds.** The suite uses deployment, component/container, sequence with phase
   dividers, state, activity-with-swimlanes, and use-case diagrams — all native to PlantUML with a
   single consistent theming mechanism.
4. **Self-contained legends.** PlantUML's `legend` block lets each figure carry its own color key, so
   a figure lifted out of context is still legible.

Mermaid remains fine for trivial inline flows, but the **policy for this repository is PlantUML for
every committed figure**, themed through `_vv-theme.iuml`.

---

## 5. Diagram catalog

The table lists each diagram and the document(s) that embed it. Diagrams are authored by several
writers; entries marked *(planned)* are referenced by a doc and will be rendered once their `.puml`
lands. Reference paths from a doc are always relative, e.g. `../diagrams/<name>.puml`.

| Diagram (`.puml`)                     | Kind                         | Embedded by (doc)                                                                 |
|---------------------------------------|------------------------------|-----------------------------------------------------------------------------------|
| `_vv-theme.iuml`                       | shared theme include             | *(included by every diagram; not embedded directly)*                              |
| `system-context.puml`                  | C4 system context                | `architecture/01-overview.md`                                                     |
| `container-two-build.puml`             | C4 container / build             | `usage/02-installation-and-build.md`, `architecture/02-process-and-build-topology.md` |
| `component-main-service.puml`          | component (main process)         | `architecture/06-renderer-runtime.md`, `features/04-git-file-tree-and-filter.md`  |
| `component-renderer.puml`              | component (renderer)             | `architecture/06-renderer-runtime.md`                                             |
| `class-state-model.puml`               | class / state model              | `architecture/04-state-schema-reference.md`, `theory/02-state-model-datascript-app-db.md` |
| `object-ds-rev-bridge.puml`            | object (the `:ds/rev` bridge)    | `theory/02-state-model-datascript-app-db.md`, `design-decisions/0004-ds-rev-bridge-vs-re-posh.md` |
| `object-history-stack.puml`            | object (history stack)           | `theory/07-command-history-model.md`, `features/07-navigation-history.md`          |
| `object-watermark.puml`                | object (watermark mask)          | `features/03-watermark-empty-tabs.md`, `design-decisions/0007-css-mask-themed-watermark.md` |
| `flow-unidirectional-dataflow.puml`    | activity / data-flow             | `theory/01-reactive-architecture.md`, `architecture/05-data-flows.md`              |
| `activity-content-strategy.puml`       | activity (content Strategy)      | `theory/05-strategy-renderer-registry.md`, `features/09-markdown-rendering.md`     |
| `activity-live-refresh-spine.puml`     | activity (live-refresh spine)    | `theory/03-live-refresh-spine.md`, `features/01-live-refresh.md`                   |
| `usecase-features.puml`                | use-case                         | `features/README.md`                                                              |
| `seq-open-file.puml`                   | sequence (full round trip)       | `usage/01-getting-started.md`, `features/02-multi-tab-previews.md`                 |
| `seq-markdown-render.puml`             | sequence (render round trip)     | `features/09-markdown-rendering.md`, `theory/01-reactive-architecture.md`          |
| `seq-live-refresh.puml`                | sequence (watcher → re-render)   | `features/01-live-refresh.md`, `theory/03-live-refresh-spine.md`                   |
| `seq-theme-switch.puml`                | sequence                         | `usage/05-configuration.md`, `features/06-themes-and-live-switching.md`            |
| `seq-find.puml`                        | sequence (find highlight)        | `features/05-in-page-find.md`, `theory/06-find-css-custom-highlight.md`            |
| `seq-history.puml`                     | sequence (back / forward)        | `features/07-navigation-history.md`                                               |
| `seq-tab-close.puml`                   | sequence (close + watcher stop)  | `features/02-multi-tab-previews.md`, `usage/03-opening-files-and-tabs.md`          |
| `seq-tree.puml`                        | sequence (git tree → open)       | `features/04-git-file-tree-and-filter.md`                                         |
| `seq-toc.puml`                         | sequence (scroll-spy TOC)        | `features/10-scroll-spy-toc.md`                                                   |
| `state-tab-lifecycle.puml`             | state (tab lifecycle)            | `features/02-multi-tab-previews.md`                                               |
| `state-find.puml`                      | state (find machine)             | `features/05-in-page-find.md`, `theory/06-find-css-custom-highlight.md`            |
| `deploy-electron-processes.puml`       | deployment (trust boundary)      | `security/threat-model.md`                                                        |
| `component-keybindings-inprogress.puml`| component *(now available)*  | `features/15-custom-keybindings.md`, `usage/04-keyboard-shortcuts.md`              |
| `component-diagram-rendering-planned.puml` | component *(planned)*        | `features/12-diagram-rendering.md`                                               |
| `component-grammar-registry-planned.puml`  | component *(planned)*        | `features/14-grammar-registry.md`                                                |
| `component-native-pdf-planned.puml`    | component *(planned)*            | `features/11-native-pdf.md`                                                       |
| `component-tree-sitter-planned.puml`   | component *(planned)*            | `features/13-source-preview-tree-sitter.md`                                       |

The three diagrams embedded by the documents in this assignment — `seq-open-file.puml`,
`container-two-build.puml`, `seq-theme-switch.puml`, and `deploy-electron-processes.puml` — are present
on disk; the remaining rows above are authored by the diagram and pillar writers and are catalogued
here for completeness. Rows marked *(planned)* / *(now available)* render Forthcoming features and
carry the `«planned»` stereotype per §1.

> This README owns only the *prose* convention and catalog (it is the one non-`.puml` file in this
> directory); it does **not** itself emit `.puml` sources. If a diagram is added or renamed, update this
> table so it stays the authoritative index.

---

*See also: [`docs/README.md`](../README.md) for the documentation map, and
[`docs/GLOSSARY.md`](../GLOSSARY.md) for term definitions referenced throughout.*
