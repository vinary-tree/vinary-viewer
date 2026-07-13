# Diagram conventions and catalog

This directory holds **every** figure in the vinary-viewer documentation suite. All diagrams are
authored as **PlantUML** (`*.puml`) source and rendered to **SVG**. This page explains how to render
them, the regression gates a render must pass, the shared-theme convention and its colour **tiers**,
the canonical colour → concept legend, *why* we standardised on PlantUML over Mermaid, and the catalog
of diagrams with the documents that embed each one.

> **Terminology defined before use.** A *diagram source* is a `.puml` text file. A *rendered diagram*
> is the `.svg` produced from it. The *shared theme* `_vv-theme.iuml` is a PlantUML *include file*
> (`.iuml`) of colour and style definitions that every diagram `!include`s, so a given colour **always**
> denotes the same concept across the whole suite. A *tier* is one of the four values at which a
> concept's colour appears — see [§2](#2-the-include-_vv-themeiuml-convention).

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

**Pinned toolchain.** The reference SVGs are produced by **PlantUML `1.2026.5`** with **Graphviz
`14.1.5`**. Re-rendering with this pair reproduces the byte-for-byte SVGs, so `git status` stays clean
unless a `.puml` actually changed; a different PlantUML may re-flow layouts or reject syntax this suite
relies on (see the syntax notes in §2).

> **Toolchain-version caveat.** Only **Graphviz-laid** kinds (component, class, state) depend on the
> Graphviz version; **sequence, activity, and deployment** diagrams do not use Graphviz and re-render
> byte-identically on any Graphviz. A workstation with a *different* Graphviz (e.g. `15.x`) will re-flow the
> Graphviz-laid diagrams on re-render even when their `.puml` is unchanged — so **render only the `.puml`
> you actually changed** and do not bulk re-render, or the diff will fill with spurious SVG churn.

### Render-time regression gates

PlantUML does **not** exit non-zero when it merely *deprecates* your syntax. It renders a yellow
warning banner **into the SVG** and silently drops the styling it could not apply. Three activity
diagrams shipped in exactly that state — banners committed, all step colours lost — because nobody
looked at the output. So a render is only complete once these two greps come back empty:

```bash
plantuml -tsvg docs/diagrams/*.puml
grep -lF deprecated        docs/diagrams/*.svg   # must print nothing
grep -lF "contains errors" docs/diagrams/*.svg   # must print nothing
```

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

### Choosing a diagram kind

Before drawing, consult the **pgmcp diagramming catalog** for the available tooling and the diagram
kinds it recommends per illustration; pick the kind that expresses the *structure of the idea* (a
control flow wants an activity diagram, a round trip wants a sequence diagram with phase dividers, an
ownership question wants swimlanes) rather than the kind that is easiest to draw.

### Embedding a rendered diagram in a Markdown doc

A documentation page embeds a diagram by linking the rendered SVG **and** always citing the `.puml`
source path so the figure stays reproducible:

```markdown
![Two-build container view](../diagrams/container-two-build.svg)

*Diagram source: [`../diagrams/container-two-build.puml`](../diagrams/container-two-build.puml).*
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

`_vv-theme.iuml` ([`docs/diagrams/_vv-theme.iuml`](_vv-theme.iuml)) defines the palette, the global
`skinparam` defaults (background `#FBF8EF`, the *Inter* font, no shadowing, rounded corners, thin grey
arrows), and a reusable `<<planned>>` stereotype (dashed grey fill) for **Forthcoming** components.

Because the palette is `!define`d in one place and *re-used* everywhere, a reader who learns the legend
once can read every diagram in the suite. The palette is deliberately drawn from the application's own
Spacemacs `--vv-*` design tokens (see `resources/public/css/themes/spacemacs-dark.css`), so the
diagrams visually match the running UI.

### Hue names the concept; lightness names the role

The Spacemacs tokens are **foreground** colours: they are tuned to be legible as *ink*, or as a small
*swatch*, against a light background. Used directly as large area fills they saturate the canvas, and —
worse — a container and its children drawn from the same accent become indistinguishable. So each
concept exists at **four tiers**, and which one you use is decided by **role**, never by taste:

| Macro | Tier | Used for |
|-------|------|----------|
| `<CONCEPT>_FILL` | palest | `package` / `node` / container-`rectangle` backgrounds |
| `<CONCEPT>_TINT` | mid | `component`, `participant`, `state`, `object`, `class` fills; activity steps |
| `<CONCEPT>_BORDER` | dark | arrow strokes for that concept |
| `<CONCEPT>_COLOR` | accent | legend swatches and class spots only — **never** an area fill |

`_FILL` and `_TINT` are *derived* from `_COLOR`, holding the hue fixed and moving the other two HLS
coordinates, so the whole palette is reconstructible from the eleven accents alone:

```math
\mathrm{FILL} := \mathrm{HLS}\bigl(h,\; 0.920,\; 0.65\,s\bigr)
\qquad
\mathrm{TINT} := \mathrm{HLS}\bigl(h,\; 0.845,\; 0.80\,s\bigr)
```

where $`(h, l, s)`$ are the hue, lightness and saturation of the accent `_COLOR`.

**Outlines are one neutral grey**, `#4A4A4A`, on every tier — the fill hue already names the concept,
and a uniform outline keeps nested boxes reading as figure-on-ground. This is not merely taste:
PlantUML `1.2026.5` rejects inline `;line:<colour>` on components and packages, so per-element outlines
would require a `<<stereotype>>` per concept, which would in turn force `hide stereotype` and suppress
the semantically meaningful `<<planned>>`, `<<schema-less>>`, `<<bridge>>` and `<<actor>>` labels that
seven diagrams rely on.

### PlantUML 1.2026.5 syntax notes

Each of these was verified against the pinned toolchain. They are recorded because violating one does
not always produce an error — sometimes it produces a *banner in your SVG*.

| Form | Result |
|---|---|
| `[component] #TINT` | ✅ the only inline component fill that parses |
| `package "P" #FILL {` | ✅ the only inline package fill that parses |
| `[component] #TINT;line:#BORDER` | ❌ hard parse error |
| `package "P" #FILL;line:#BORDER` | ❌ hard parse error |
| `rectangle "R" #TINT;line:#BORDER` | ⚠ renders a **deprecation banner into the SVG** |
| `skinparam component { … }` on one line | ❌ parse error — the brace block must span lines |
| `skinparam package { … }` | ❌ not a valid block — use flat `packageBorderColor`, `packageFontColor`, … |
| `:text; <<#COLOR>>` | ✅ the current activity-step fill |
| `#COLOR:text;` | ⚠ deprecated — banner **and** the fill is silently dropped |
| `<<CONCEPT_TINT>>` | ✅ theme macros expand inside `<<…>>` and inside `skinparam` blocks |
| `\|<#HEX>&#160;&#160;&#160;&#160;\|` | ✅ a legend swatch needs padding or it collapses to a 1-px sliver |
| `\|<#HEX>&nbsp;\|` | ❌ the named entity renders **literally** as the text `&nbsp;` |

### Mathematics in diagram labels

PlantUML typesets LaTeX through the bundled **JLaTeXMath**, emitting embedded vector glyphs. Write a
formula in a label as `<latex>…</latex>` (inline) or `<math>…</math>`, **never** as Unicode literals:

```text
title re-frame's six dominoes\n<latex>\mathrm{view} := f(\mathrm{state})</latex>
state "Visible (matches: n)" as Matches MARKDOWN_TINT : <latex>\mathit{idx} \in 1..n</latex>
```

Code-shaped expressions (Clojure forms) are typeset inside the math with `\mathtt{…}`. Unicode remains
correct for **non-mathematical** label text — arrows (`→`, `⟶`), separators (`·`), and enumerations.
Keep formulae out of edge/transition labels where Graphviz cannot reserve room for them: an oversized
`<latex>` span in a self-transition label will overlap its own arrows. Put the equation in a `note`
attached to the state instead, as `state-find.puml` does.

---

## 3. Canonical colour → concept legend

A colour **always** means one concept, suite-wide. Every diagram also carries its own self-contained
`legend` block so it can be read in isolation; this table is the master reference. The hex below is the
**accent** (`_COLOR`) — the value you will see in a legend swatch, and never as an area fill.

| Accent (hex) | Swatch concept                                   | Where it appears                                                |
|--------------|--------------------------------------------------|-----------------------------------------------------------------|
| `#3A5BA0`    | **MAIN process / Node IO** (slate)               | `main.core`, `main.service`, fs reads, chokidar, git            |
| `#2D9574`    | **Renderer / Chromium UI** (teal)                | reagent views, the Chromium process, the rendered document body |
| `#B1951D`    | **IPC seam** (amber) — preload / contextBridge / Mediator | the `window.vv` boundary; **every IPC arrow is amber**          |
| `#A45BAD`    | **DataScript content cache** (purple)            | bounded document content (`vinary.app.ds`)                      |
| `#7590DB`    | **app-db UI/navigation state** (blue-violet)     | tabs, history, active tab, theme, find, scroll-spy state        |
| `#4F97D7`    | **re-frame machinery** (blue)                    | events · subscriptions · effects; also shadow-cljs in build views |
| `#9F8766`    | **Filesystem / editor** (tan)                    | the watched file, the external editor that saves it             |
| `#67B11D`    | **Markdown / unified** (green)                   | the remark→rehype pipeline, rendered HTML                       |
| `#BC6EC5`    | **Keymaps** (magenta)                            | keymap registry, key-binding editor, link hints                 |
| `#E67E22`    | **Common document IR** (orange)                  | the semiring · transducer · WPDA core (`vinary.ir.*`)           |
| `#E0211D`    | **Errors / retractions** (red)                   | `:doc/error`, `vv:error`, `[:db/retract …]`                     |
| `#DDDDDD`    | **Forthcoming / planned** (dashed grey)          | features not yet built; carries the `«planned»` stereotype      |

**Accessibility.** Every `_FILL` and `_TINT` derived from these accents clears **WCAG 2.x AA** against
the ink `#1A1A1A` by at least `10.8:1`, and the neutral outline `#4A4A4A` clears `5.49:1` against the
darkest `_TINT`. The accents themselves are *not* required to pass as fills, because they are never
used as one.

**Reading aids used across the suite:**

- **Sequence diagrams** use `autonumber`, activation bars, and `== phase ==` dividers, and show the
  **full round trip** (renderer → seam → main → seam → renderer) rather than a one-way slice.
- **Activity / flow diagrams** use `partition` swimlanes to separate the MAIN and RENDERER processes.
- **Dashed borders** mark trust boundaries (e.g. the Chromium sandbox) and planned components.

---

## 4. Why PlantUML, not Mermaid

This is a deliberate, suite-wide decision; each `.puml` restates the *specific* reason in its header.
The general rationale:

1. **Colour-by-concept.** PlantUML lets us bind a fill colour to an arbitrary concept via `!define`
   macros in a shared include, so the same purple always means "DataScript". Mermaid's `classDef`
   styling is per-diagram and cannot be shared as cleanly across many files.
2. **Byte-reproducible output.** Re-rendering the suite with the pinned toolchain reproduces the
   SVGs byte-for-byte, which makes an SVG diff meaningful in review: a changed figure means a changed
   source. Mermaid's output is not byte-reproducible.
3. **LaTeX in labels.** PlantUML typesets `<latex>…</latex>` and `<math>…</math>` through the bundled
   JLaTeXMath into embedded vector SVG, so a formula in a figure is real mathematics rather than a
   string of Unicode glyphs. Mermaid has no equivalent.
4. **First-class boundary objects.** The contextBridge/sandbox trust boundary is drawn as a real,
   stereotyped, dashed-border container that *encloses* the renderer. Mermaid subgraphs cannot express
   an enclosing, colour-coded trust boundary as a concept.
5. **Richer diagram kinds.** The suite uses deployment, component/container, sequence with phase
   dividers, state, activity-with-swimlanes, and use-case diagrams — all native to PlantUML with a
   single consistent theming mechanism.
6. **Self-contained legends.** PlantUML's `legend` block lets each figure carry its own colour key, so
   a figure lifted out of context is still legible.

Mermaid remains fine for trivial inline flows, but the **policy for this repository is PlantUML for
every committed figure**, themed through `_vv-theme.iuml`.

---

## 5. Diagram catalog

Every `.puml` in this directory, its kind, and the document(s) that embed the rendered SVG. Reference
paths from a doc are always relative, e.g. `../diagrams/<name>.puml`. `_vv-theme.iuml` is the shared
theme include: it is `!include`d by every diagram and is never embedded directly.

An em-dash in the last column means **no document embeds this figure**. That is the correct state for
exactly the four superseded design diagrams noted below, and a defect for anything else.

| Diagram (`.puml`) | Kind | Embedded by (doc) |
|---|---|---|
| `activity-content-strategy.puml` | activity | `features/08-image-view.md` |
| `activity-directory-open.puml` | activity | `design-decisions/0012-…` · `features/16-directory-browser.md` |
| `activity-kbedit-undo.puml` | activity | `features/15-custom-keybindings.md` |
| `activity-link-hints.puml` | activity | `features/15-custom-keybindings.md` |
| `activity-live-refresh-spine.puml` | activity | `theory/03-live-refresh-spine.md` |
| `activity-log-segmentation.puml` | activity | `theory/09-document-streaming-and-the-wpda.md` |
| `activity-unified-history.puml` | activity | `design-decisions/0012-…` |
| `class-state-model.puml` | class / state model | `architecture/04-state-schema-reference.md` |
| `component-common-ir.puml` | component | `theory/08-common-document-ir.md` |
| `component-content-retention.puml` | component | `design-decisions/0010-…` · `features/01-live-refresh.md` · `theory/03-live-refresh-spine.md` |
| `component-diagram-rendering-planned.puml` | component *(planned)* | `features/12-diagram-rendering.md` |
| `component-grammar-registry-planned.puml` | component *(legacy design)* | — *superseded by `component-grammar-registry.puml`* |
| `component-grammar-registry.puml` | component | `features/14-grammar-registry.md` |
| `component-keybinding-editor.puml` | component | `features/15-custom-keybindings.md` |
| `component-keybindings-inprogress.puml` | component *(now available)* | `features/15-custom-keybindings.md` |
| `component-main-service.puml` | component | `reference/namespaces.md` |
| `component-native-pdf-planned.puml` | component *(legacy design)* | — *superseded by `component-native-pdf.puml`* |
| `component-native-pdf.puml` | component *(legacy design)* | — *superseded by the in-renderer pdf.js pipeline; see [ADR-0013](../design-decisions/0013-in-renderer-pdfjs.md) and `seq-pdf-render.puml`* |
| `component-renderer.puml` | component | `reference/namespaces.md` |
| `component-source-preview.puml` | component | `features/13-source-preview-tree-sitter.md` |
| `component-tab-dual-representation.puml` | component | `features/02-multi-tab-previews.md` |
| `component-tree-sitter-planned.puml` | component *(legacy design)* | — *superseded by `component-source-preview.puml`* |
| `container-two-build.puml` | C4 container / build | `architecture/01-overview.md` · `usage/02-installation-and-build.md` |
| `deploy-electron-processes.puml` | deployment (trust boundary) | `architecture/02-process-and-build-topology.md` · `security/threat-model.md` |
| `flow-unidirectional-dataflow.puml` | activity / data-flow | `theory/01-reactive-architecture.md` |
| `object-ds-rev-bridge.puml` | object | `theory/02-state-model-datascript-app-db.md` |
| `object-history-stack.puml` | object | `theory/07-command-history-model.md` |
| `object-watermark.puml` | object | `features/03-watermark-empty-tabs.md` |
| `seq-content-page-streaming.puml` | sequence | `architecture/03-ipc-protocol.md` |
| `seq-document-streaming.puml` | sequence | `design-decisions/0018-…` · `theory/09-document-streaming-and-the-wpda.md` |
| `seq-extension-install.puml` | sequence | `features/21-browser-extensions.md` |
| `seq-find.puml` | sequence | `features/05-in-page-find.md` · `theory/06-find-css-custom-highlight.md` |
| `seq-history.puml` | sequence | `features/07-navigation-history.md` |
| `seq-instant-overlay-snapshot.puml` | sequence | `features/19-web-view-keyboard-and-copy.md` |
| `seq-keymap-select.puml` | sequence | `features/15-custom-keybindings.md` |
| `seq-link-click-scroll.puml` | sequence | `features/07-navigation-history.md` |
| `seq-live-refresh.puml` | sequence | `features/01-live-refresh.md` |
| `seq-markdown-render.puml` | sequence | `features/09-markdown-rendering.md` |
| `seq-open-file.puml` | sequence | `usage/01-getting-started.md` |
| `seq-overlay-hide.puml` | sequence | `design-decisions/0012-…` |
| `seq-pdf-render.puml` | sequence | `features/11-native-pdf.md` |
| `seq-tab-close.puml` | sequence | `features/02-multi-tab-previews.md` |
| `seq-theme-switch.puml` | sequence | `features/06-themes-and-live-switching.md` |
| `seq-toc.puml` | sequence | `features/10-scroll-spy-toc.md` |
| `seq-tree.puml` | sequence | `features/04-git-file-tree-and-filter.md` |
| `state-dir-trail-memory.puml` | state | `design-decisions/0012-…` · `features/17-breadcrumb-and-up-down-navigation.md` |
| `state-find.puml` | state | `features/05-in-page-find.md` · `theory/06-find-css-custom-highlight.md` |
| `state-tab-lifecycle.puml` | state | `features/02-multi-tab-previews.md` |
| `system-context.puml` | C4 system context | `architecture/01-overview.md` |
| `usecase-features.puml` | use-case | `README.md` |

### 0.3.0 additions — common IR · streaming · terminal · Org/LaTeX/diff · SSH · engineering & scientific pillars

| Diagram (`.puml`) | Kind | Embedded by (doc) |
|---|---|---|
| `component-ir-pipeline.puml` | component | `architecture/07-common-ir-streaming-and-terminal.md` · `scientific/00-overview.md` |
| `component-ir-two-backends.puml` | component | `theory/10-terminal-rendering-second-renderer.md` |
| `state-tui-terminal.puml` | state | `theory/10-terminal-rendering-second-renderer.md` |
| `component-terminal-layer.puml` | component | `design-decisions/0019-terminal-preview-layer.md` |
| `component-terminal-renderer.puml` | component | `features/30-terminal-preview.md` · `architecture/07-…` · `engineering/05-terminal-build-and-launch.md` |
| `seq-cli-render.puml` | sequence | `features/30-terminal-preview.md` · `usage/07-terminal-cli-tui.md` |
| `component-diff-model.puml` | component | `features/28-diff-rendering.md` · `design-decisions/0026-…` |
| `seq-diff-split-enrich.puml` | sequence | `features/28-diff-rendering.md` |
| `component-remote-backend.puml` | component | `features/29-remote-files-over-ssh.md` · `architecture/07-…` · `design-decisions/0027-…` |
| `seq-ssh-open.puml` | sequence | `features/29-remote-files-over-ssh.md` · `usage/08-remote-files-ssh.md` |
| `activity-ssh-auth.puml` | activity | `features/29-…` · `usage/08-remote-files-ssh.md` · `design-decisions/0027-…` |
| `component-password-bridge.puml` | component | `features/23-password-manager-bridge.md` |
| `flow-org-shared-suffix.puml` | flow | `design-decisions/0020-org-mode-via-uniorg.md` |
| `flow-org-pipeline.puml` | flow | `features/26-org-mode.md` |
| `activity-org-export-routing.puml` | activity | `features/26-org-mode.md` |
| `flow-latex-pipeline.puml` | flow | `design-decisions/0025-…` · `features/27-latex-rendering.md` |
| `state-doc-pdf-switch.puml` | state | `design-decisions/0025-…` · `design-decisions/0026-…` · `features/27-latex-rendering.md` |
| `flow-source-preview-jump.puml` | flow | `design-decisions/0021-bidirectional-source-preview-jump.md` |
| `component-apply-posts.puml` | component | `design-decisions/0022-pre-dom-figure-sizing.md` |
| `container-five-builds.puml` | C4 container / build | `architecture/02-process-and-build-topology.md` · `engineering/01-build-system.md` |
| `component-test-taxonomy.puml` | component | `engineering/03-test-strategy.md` · `engineering/08-ci-and-validation-discipline.md` |
| `activity-asset-vendoring.puml` | activity | `engineering/02-asset-vendoring.md` |
| `seq-byte-parity.puml` | sequence | `scientific/01-byte-parity-verification.md` |
| `activity-inkloss-experiment.puml` | activity | `scientific/05-mathjax-inkloss-experiment.md` |

### Keeping this table honest

The catalog is a claim about the filesystem, so check it against the filesystem. Both loops must print
nothing (modulo the four superseded rows, which are expected to have no embedding doc):

```bash
# 1. Every .puml appears in this catalog.
for f in docs/diagrams/*.puml; do
  grep -qF "\`$(basename "$f")\`" docs/diagrams/README.md || echo "MISSING from catalog: $(basename "$f")"
done

# 2. Every .puml is embedded by at least one document.
for f in docs/diagrams/*.puml; do
  b=$(basename "$f" .puml)
  grep -rq --include='*.md' --exclude-dir=diagrams "$b.svg" docs/ || echo "NOT EMBEDDED: $b"
done
```

> This README owns only the *prose* convention and catalog (it is the one non-`.puml` file in this
> directory); it does **not** itself emit `.puml` sources. If a diagram is added or renamed, update this
> table so it stays the authoritative index.

---

*See also: [`docs/README.md`](../README.md) for the documentation map, and
[`docs/GLOSSARY.md`](../GLOSSARY.md) for term definitions referenced throughout.*
