# vinary-viewer — Documentation Suite

> **vinary-viewer** is a reactive desktop **document previewer** built in
> **ClojureScript** with **re-frame** on **Electron**. You point it at a file
> (`vv README.md`), and it shows a live, always-current preview: edit the file in
> any editor and the rendered view refreshes the instant you save — **without
> losing your scroll position, your in-page search, or your theme**. It previews
> **Markdown** (GitHub-flavoured, with heading anchors and syntax highlighting),
> **images**, and **plain text**, across **multiple tabs**, with a **git file
> tree**, an **in-page find**, a **table-of-contents scroll-spy**, **navigation
> history**, and **live theme switching**.

---

## Status banner

| Field | Value |
|-------|-------|
| Version | **`0.2.0-dev`** — ClojureScript / re-frame / Electron rewrite (`package.json`) |
| Predecessor | `0.1.0` was a *vmd-patching* tool; that codebase is **superseded** and its old top-level `docs/01..09-*.md` describe the *previous* product, not this one |
| Live runtime | `src/vinary/**` (ClojureScript) + `resources/**` (`preload.js`, `public/`) |
| Build | `shadow-cljs` two-build (`:main` → Electron main; `:renderer` → Chromium) |

This suite documents the **live `0.2.0-dev` application only**. Throughout, every
capability is tagged so you always know what runs today versus what is on the
roadmap:

- **Available now** — implemented in the source you can read today (this includes the full
  **custom-keybinding system**: a command registry, preset default/vim/emacs keymaps, a modal/chord
  resolver, a `~/.config/vinary-viewer/keybindings.edn` config, and a command palette).
- **Forthcoming (planned)** — designed but not built: the `vv` / `vinary-viewer`
  launcher binaries, the `~/.config/vinary-viewer` **grammar registry**, **native
  PDF** (Electron `BrowserView`), **diagram rendering** (d2 / PlantUML / Mermaid →
  SVG), and a **tree-sitter source view**.

> Anything not explicitly tagged *Forthcoming* is **Available now**.

### What the app can do today

The use-case diagram below partitions every feature into **Available now**
(teal) and **Forthcoming (planned)** (dashed grey). Source:
[`diagrams/usecase-features.puml`](diagrams/usecase-features.puml).

![Use-case map: available-now vs planned features](diagrams/usecase-features.svg)

---

## How to read this suite

Pick the path that matches your goal. Each path lists the documents in the order
they build on one another.

### "I just want to use it" → start in [`usage/`](usage/)

1. [`usage/01-getting-started.md`](usage/01-getting-started.md) — first preview in
   under a minute.
2. [`usage/02-installation-and-build.md`](usage/02-installation-and-build.md) —
   `shadow-cljs` compile/watch/release and running under Electron.
3. [`usage/03-opening-files-and-tabs.md`](usage/03-opening-files-and-tabs.md) —
   command-line argument, the git tree, multi-tab browsing.
4. [`usage/04-keyboard-shortcuts.md`](usage/04-keyboard-shortcuts.md) — the
   default keys (`Ctrl+F`, `Alt+←/→`) and the vim/emacs custom-keybinding system.
5. [`usage/05-configuration.md`](usage/05-configuration.md) and
   [`usage/06-troubleshooting.md`](usage/06-troubleshooting.md).

For a feature-by-feature catalogue (one page each), see
[`features/`](features/README.md).

### "I want to understand *why* it is built this way" → [`theory/`](theory/), then [`architecture/`](architecture/)

The **theory pillar** explains the *ideas* — the patterns, the data model, the
invariants — independent of any one file. Read it in order:

1. [`theory/01-reactive-architecture.md`](theory/01-reactive-architecture.md) —
   unidirectional data flow; re-frame's six dominoes mapped onto the **Command**
   and **Observer** patterns; the single-source-of-truth equation
   `view ≔ f(state)`.
2. [`theory/02-state-model-datascript-app-db.md`](theory/02-state-model-datascript-app-db.md)
   — the **two stores** (DataScript for documents, app-db for ephemeral UI), the
   minimal schema, and the **`:ds/rev` bridge** that ties them together.
3. [`theory/03-live-refresh-spine.md`](theory/03-live-refresh-spine.md) — the
   **flagship spine**: editor-save → painted DOM, and the invariant that a content
   refresh mutates only `:doc/*`, never `:ui/*`.
4. [`theory/04-hexagonal-and-ipc-mediator.md`](theory/04-hexagonal-and-ipc-mediator.md)
   — ports & adapters, *effects at the edge*, and the **Mediator** `contextBridge`
   IPC seam.
5. [`theory/05-strategy-renderer-registry.md`](theory/05-strategy-renderer-registry.md)
   — the **Strategy** that picks a document body by `:doc/kind`.
6. [`theory/06-find-css-custom-highlight.md`](theory/06-find-css-custom-highlight.md)
   — painting search matches as Ranges with the **CSS Custom Highlight API**,
   without touching the DOM.
7. [`theory/07-command-history-model.md`](theory/07-command-history-model.md) —
   navigation as reified **Commands** on a `{:stack :idx}` history.

Then read the **architecture pillar** for the concrete realisation:

- [`architecture/01-overview.md`](architecture/01-overview.md),
  [`02-process-and-build-topology.md`](architecture/02-process-and-build-topology.md),
  [`03-ipc-protocol.md`](architecture/03-ipc-protocol.md),
  [`04-state-schema-reference.md`](architecture/04-state-schema-reference.md),
  [`05-data-flows.md`](architecture/05-data-flows.md),
  [`06-renderer-runtime.md`](architecture/06-renderer-runtime.md).

### "I want to contribute" → [`architecture/`](architecture/) + [`reference/`](reference/) + [`design-decisions/`](design-decisions/README.md)

- Architecture as above for the lay of the land.
- [`reference/`](reference/) — exhaustive tables you will keep open while coding:
  [`events-effects-subs.md`](reference/events-effects-subs.md),
  [`ipc-channels.md`](reference/ipc-channels.md),
  [`css-variables.md`](reference/css-variables.md),
  [`namespaces.md`](reference/namespaces.md).
- [`design-decisions/`](design-decisions/README.md) — the numbered ADR-style log
  (`0001`…`0009`) recording *why* each pivotal choice was made (DataScript over
  plain app-db, the hand-rolled `:ds/rev` bridge over re-posh, imperative
  `innerHTML` over VDOM, rendering in the renderer, etc.).
- [`security/threat-model.md`](security/threat-model.md) — the Electron security
  posture and recommended hardenings.

---

## Document map

Every document in the suite, with its pillar and one-line purpose.

| Document | Pillar | What it covers |
|----------|--------|----------------|
| [`README.md`](README.md) | entry | This index, status, reading paths, conventions |
| [`GLOSSARY.md`](GLOSSARY.md) | entry | Every term, acronym, and symbol, defined once |
| [`theory/01-reactive-architecture.md`](theory/01-reactive-architecture.md) | theory | Unidirectional flow; six dominoes; Command + Observer; `view ≔ f(state)` |
| [`theory/02-state-model-datascript-app-db.md`](theory/02-state-model-datascript-app-db.md) | theory | Two stores; DataScript primer; minimal schema; `:ds/rev` bridge; nil-as-absence |
| [`theory/03-live-refresh-spine.md`](theory/03-live-refresh-spine.md) | theory | The save→render→paint spine; the `:doc/*`-only invariant; convergence/LWW |
| [`theory/04-hexagonal-and-ipc-mediator.md`](theory/04-hexagonal-and-ipc-mediator.md) | theory | Ports/adapters; effects at the edge; the Mediator `contextBridge` seam |
| [`theory/05-strategy-renderer-registry.md`](theory/05-strategy-renderer-registry.md) | theory | Strategy-by-`:doc/kind`; content-view precedence; planned registry-as-data |
| [`theory/06-find-css-custom-highlight.md`](theory/06-find-css-custom-highlight.md) | theory | Painting Ranges without DOM mutation; `collect-ranges`/`paint!`/`cycle!` |
| [`theory/07-command-history-model.md`](theory/07-command-history-model.md) | theory | Navigation as Commands; `{:stack :idx}`; truncate-on-new-path |
| [`architecture/01-overview.md`](architecture/01-overview.md) | architecture | System-level component map |
| [`architecture/02-process-and-build-topology.md`](architecture/02-process-and-build-topology.md) | architecture | Two Electron processes; the two shadow-cljs builds |
| [`architecture/03-ipc-protocol.md`](architecture/03-ipc-protocol.md) | architecture | The `vv:*` channels and payload shapes |
| [`architecture/04-state-schema-reference.md`](architecture/04-state-schema-reference.md) | architecture | DataScript schema + full `app-db` shape |
| [`architecture/05-data-flows.md`](architecture/05-data-flows.md) | architecture | Open / refresh / find / history flows end-to-end |
| [`architecture/06-renderer-runtime.md`](architecture/06-renderer-runtime.md) | architecture | Renderer boot order, reagent mount, dev hooks |
| [`design-decisions/README.md`](design-decisions/README.md) + `0001`…`0009` | design | Why each pivotal choice was made |
| [`usage/01..06`](usage/) | usage | Getting started, install/build, files & tabs, shortcuts, config, troubleshooting |
| [`features/README.md`](features/README.md) + `01`…`15` | features | One page per feature (live refresh … custom keybindings) |
| [`reference/events-effects-subs.md`](reference/events-effects-subs.md) | reference | Every re-frame event, effect, subscription |
| [`reference/ipc-channels.md`](reference/ipc-channels.md) | reference | Every IPC channel, direction, payload |
| [`reference/css-variables.md`](reference/css-variables.md) | reference | The `--vv-*` design tokens |
| [`reference/namespaces.md`](reference/namespaces.md) | reference | Every ClojureScript namespace and its role |
| [`security/threat-model.md`](security/threat-model.md) | security | Electron isolation posture; recommended hardenings |
| [`diagrams/`](diagrams/README.md) | diagrams | All PlantUML sources + the shared theme |

---

## Conventions used throughout

These conventions are shared by every document. They exist so the suite reads as
one coherent whole.

1. **Term-before-use.** Every acronym, symbol, and domain term is defined the
   first time it appears, and again — canonically — in
   [`GLOSSARY.md`](GLOSSARY.md). When a term is first introduced, the glossary
   records *which document introduced it*.

2. **Mathematics in backticks with Unicode.** Formulae are written inline in
   backticks using Unicode symbols, e.g. the single-source-of-truth equation
   `view ≔ f(state)`, the history push
   `stack′ ≔ (conj (vec (take (inc idx) stack)) path)`, or the find cursor
   `idx ≔ (idx + dir) mod n`. The symbol `≔` means "is defined as".

3. **Diagrams are PlantUML.** Every diagram lives under
   [`diagrams/`](diagrams/README.md) as a `.puml` file that begins with
   `@startuml` and `!include _vv-theme.iuml`. Each file's header comment states
   *why PlantUML was chosen over Mermaid for that illustration*. Documents embed
   the rendered `.svg` image and cite the `.puml` source path, so the figure is
   visible in ordinary Markdown viewers while remaining one click from its source.

4. **Color legend.** Colours are **per-concept and stable across the whole
   suite** — a colour always means the same thing. The palette is defined once in
   [`diagrams/_vv-theme.iuml`](diagrams/_vv-theme.iuml) and is drawn from the
   app's own Spacemacs `--vv-*` tokens. The mapping:

   | Colour | Hex | Concept |
   |--------|-----|---------|
   | ▮ Slate | `#3A5BA0` | **Main** process (Node IO) |
   | ▮ Teal | `#2D9574` | **Renderer** / Chromium / reagent view |
   | ▮ Amber | `#B1951D` | **IPC seam** (preload, `contextBridge`, Mediator) — *all IPC arrows are amber* |
   | ▮ Purple | `#A45BAD` | **DataScript** single source of truth |
   | ▮ Blue-violet | `#7590DB` | **app-db** ephemeral UI state |
   | ▮ Blue | `#4F97D7` | **re-frame** machinery (events, fx, subs) |
   | ▮ Tan | `#9F8766` | **Filesystem / editor** (chokidar) |
   | ▮ Green | `#67B11D` | **Markdown** (unified / remark / rehype) |
   | ▮ Red | `#E0211D` | **Errors / retractions** |
   | ▯ Dashed grey | `#DDDDDD` | **Forthcoming (planned)** — `«planned»` stereotype |

   Every diagram repeats a `legend` mapping its colours to concepts, so each is
   self-contained. See [`diagrams/README.md`](diagrams/README.md) for the full
   diagram catalogue.

5. **Citations.** Sources are cited inline with a link, and DOIs are used **only
   when verified** — see [`GLOSSARY.md`](GLOSSARY.md#references) and each
   document's *References* section. Foundational design-pattern claims cite
   *Design Patterns* (Gamma, Helm, Johnson & Vlissides, 1994, **ISBN
   978-0201633610** — this book has no DOI). The Model-View-Controller lineage
   cites Krasner & Pope (1988) via the **ACM Digital Library**
   (`https://dl.acm.org/doi/10.5555/50757.50759`). Library and platform claims
   cite the projects' official documentation.

---

## Attribution

vinary-viewer is original work licensed **Apache-2.0**, *a new application
inspired by [vmd](https://github.com/yoshuawuyts/vmd)* (MIT). The full
attribution and third-party notices live in the repository `NOTICE` file and in
[`design-decisions/README.md`](design-decisions/README.md). Author: *Vinary Tree*
&lt;dylon@vinarytree.io&gt;.
