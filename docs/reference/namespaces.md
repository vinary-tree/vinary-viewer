# Reference · Namespaces

This is a current map of the vinary-viewer source tree — every ClojureScript
namespace (and the handful of Node-side `.js` / macro `.clj` helpers that sit
inside a layer) and its role. Architecture overview:
[../architecture/01-overview.md](../architecture/01-overview.md).

The tree splits into two processes joined by the preload IPC seam, plus a set of
process-agnostic libraries (the common document **IR**, **streaming**, and the
terminal **CLI/TUI** renderers) that both the GUI renderer and the headless
tools build on:

| Layer | Prefix | Process | Count |
|-------|--------|---------|-------|
| Main process | `vinary.main.*` | Electron main | 24 cljs + 4 js |
| App / re-frame | `vinary.app.*` | renderer | 10 |
| Input / keybindings | `vinary.input.*` | renderer | 7 cljs + 1 clj |
| Renderer services | `vinary.renderer.*` | renderer | 20 |
| UI components | `vinary.ui.*` | renderer | 22 |
| Common document IR | `vinary.ir.*` | shared | 25 |
| Document streaming | `vinary.stream.*` | shared | 5 |
| Terminal renderer | `vinary.terminal.*` | Node (vv-cli/vv-tui) | 5 |
| Terminal UI | `vinary.tui.*` | Node (vv-tui) | 7 |
| Headless CLI | `vinary.cli.*` | Node (vv-cli) | 2 |
| Top-level libraries | `vinary.*` | shared | 2 |

---

## 1. Main process (`vinary.main.*`)

The Electron main process: all filesystem, `child_process`, network, and native
window/view work. Renderer code never imports these namespaces.

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.main.core` | Electron app/window lifecycle: creates the sandboxed renderer window + contextBridge preload, wires the menu, and opens the initial argv file. |
| `vinary.main.service` | Main-process IO service: read files, push content over the Mediator IPC seam, retained-file watcher reconciliation, Markdown asset watchers, git tree, sibling-PDF/source detection. Registers the file / streaming / diff-source / remote-asset / retained-file `vv:*` handlers. |
| `vinary.main.service-util` | Pure, electron/DOM-free routing helpers for the IO service (so the node `:test` build can assert routing without Electron). |
| `vinary.main.content_service.js` | *(JS)* Electron-free content engine: bounded paged reads for large logs / delimited tables, office (docx via mammoth, ODF) + workbook parsing, log sniffing, byte/line **streaming sessions** (`streamOpen`/`Pull`/`Close`), remote SFTP reads, and diff-source + remote-asset resolution. Reused verbatim by vv-cli/vv-tui. |
| `vinary.main.file-kind` | Pure file-kind classifier (extension → `kind`) shared by the service and tests; also `remote-uri?` / `archive-uri?` / `pdf-sibling-path` / `source-sibling-paths`. |
| `vinary.main.config` | `keybindings.edn` load/save/watch; pushes raw EDN **text** over `vv:keymap` (clj→js would flatten keyword command-ids). |
| `vinary.main.settings` | `settings.edn` load/save/watch: theme + fonts + view prefs. |
| `vinary.main.recent` | `recent.edn` load/save/watch: the dir→last-child `:trail`, the `:recent-files` MRU (File ▸ Open Recent), and the `:web-history` URL MRU. |
| `vinary.main.connections` | Persisted **non-secret** SSH connection metadata (`connections.edn`): host entries for URI/host hints. Secrets never land here (see `ssh.cljs`). |
| `vinary.main.window` | Persisted main-window geometry (`window.edn`): position, size, maximized state, clamped to a visible display. Main-only. |
| `vinary.main.grammars` | Grammar registry (main side): decides which files are `source` (→ the read-only CodeMirror view) and pushes the user grammar + filetype registry. |
| `vinary.main.shell` | Menu-bar shell actions: the multi-file Open dialog, clipboard writes, reveal/open-path, open-external, app-info, quit, devtools, and app-window zoom. |
| `vinary.main.startup` | Pure, electron-free startup configuration (so the node `:test` build can assert it). |
| `vinary.main.web` | In-app HTTP browsing: a main-owned `WebContentsView` (http/https) with bounds, navigation relay, page zoom, snapshot cache, TOC bridge, and the page-key/scroll relay to the web preload. |
| `vinary.main.pdf` | *RETIRED* — the native PDF `WebContentsView` superseded by the in-renderer pdf.js view (ADR-0013). `init!` is commented out in `core.cljs`, so the `vv:pdf-*` channels have **no live listener**; the namespace is kept for recoverability. |
| `vinary.main.adblock` | Native ad/tracker blocking (`@ghostery/adblocker-electron`, MPL) on `persist:vinary-web`: engine build/enable/refresh/schedule + cache (ADR-0014). |
| `vinary.main.extensions` | Scoped Chrome-extension runtime: Web-Store install/load/reconcile, manifest→toolbar model, popup open, state push (ADR-0015). |
| `vinary.main.ext-popup` | Browser-action popup host (origin-locked, content-sized `WebContentsView` on the web session). |
| `vinary.main.ext-config` | `extensions.edn` load/save/watch + synchronous main-side `load-config` (ad-block + extension prefs). |
| `vinary.main.ext-util` | Pure, electron-free helpers: Web-Store id parse, config merge, enable/disable reconcile, manifest action model, popup geometry, ad-block cache/list. |
| `vinary.main.passwords` | Native password-manager bridge: provider CLIs run only in the trusted main process; the renderer sees sanitized metadata and the web preload receives fills directly. Secrets never enter renderer app-db (ADR-0016). |
| `vinary.main.password-adapters` | Provider adapters (e.g. CLI-backed vaults). All provider execution stays in main. |
| `vinary.main.password-config` | Main-side, non-secret bridge config: provider ordering and enablement. |
| `vinary.main.password-util` | Pure helpers for the password bridge (no IO, no secrets) — node-testable. |
| `vinary.main.ssh` | Electron/IPC wiring for the SSH/SFTP transport: injects the host-key trust prompt + the secret prompt + error/status sinks, and owns the `vv:ssh-*` channels. Secrets stay main-side (ADR-0027). |
| `vinary.main.ssh_transport.js` | *(JS)* SFTP transport over `ssh2`: a connection pool keyed by `user@host:port`, the auth chain (agent → identity files → password → keyboard-interactive/MFA), and host-key TOFU against `known_hosts`. Electron-free (prompts/sinks injected via `configure()`). |
| `vinary.main.ssh_agent.js` | *(JS)* ssh-agent client implementing `SSH_AGENTC_ADD_IDENTITY` (the `AddKeysToAgent` directive), which `ssh2`'s agent classes do not expose. |
| `vinary.main.ssh_config.js` | *(JS)* Pure, dependency-light `~/.ssh/config` + `known_hosts` parsing and host-key crypto helpers (no `fs`/`net`/`electron`) — node-testable in isolation. |

![Main components](../diagrams/component-main-service.svg)

*Source: [`../diagrams/component-main-service.puml`](../diagrams/component-main-service.puml).*

---

## 2. IPC seam

| File | Responsibility |
|------|----------------|
| `resources/preload.js` | Exposes `window.vv` via `contextBridge`; all renderer↔main IPC crosses here (89 `vv:*` channels). |
| `resources/web-preload.js` | Runs inside the HTTP web view (isolated): reports the heading outline + active heading, scrolls on TOC jumps and forwarded page keys, and runs the native password-form observer + self-contained link hints. |

See [ipc-channels.md](ipc-channels.md).

---

## 3. App layer (`vinary.app.*`)

The re-frame loop and its state stores. `db`/`ds` hold state; `events`/`fx`/`subs`
are the loop; `nav`/`uri`/`zoom`/`link` are pure transforms.

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.app.db` | Default app-db: UI/navigation/settings/keybinding state. Tabs/history and ephemeral UI live here; DataScript owns content. |
| `vinary.app.ds` | DataScript content cache keyed by `:doc/path`, the `:ds/rev` bridge, and content-cache helpers (`snapshot`, `eid-for-path`, `doc-attr`, `active-doc`, retention). |
| `vinary.app.nav` | Pure browser-tab + per-tab-history transforms over app-db. A tab is a view `{:id :uri :hist … :representation :diff-view …}`. |
| `vinary.app.uri` | URI/path normalization and local-vs-HTTP-vs-remote predicates (`file-path`, `http?`, `remote?`, `dirname`, `segments`). |
| `vinary.app.zoom` | Pure context-aware zoom helpers (`context` → `:pdf`/`:web`/`:window`, `percent`, `presets`) shared by the zoom events and the `:view/zoom-percent` sub. |
| `vinary.app.link` | One source of truth for "what does following this rendered-document link do" — classify + route a preview/link activation. |
| `vinary.app.events` | re-frame events for content, streaming, tabs, history, representation/diff switches, settings, menu, tree, TOC, find, hints, SSH, passwords, extensions, and shell commands. |
| `vinary.app.fx` | re-frame effects — the only place async/IO/DataScript-mutation touches the world (effects at the edge). |
| `vinary.app.subs` | Subscriptions over app-db and DataScript snapshots. |
| `vinary.app.commands` | Command registry used by keybindings and the palette. |

![Renderer components](../diagrams/component-renderer.svg)

*Source: [`../diagrams/component-renderer.puml`](../diagrams/component-renderer.puml).*

---

## 4. Input layer (`vinary.input.*`)

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.input.keys` | Normalize a DOM `KeyboardEvent` into a canonical chord token (modifiers folded cross-platform). |
| `vinary.input.keymap` | Keymap registry: the bundled presets (embedded at compile time via `shadow.resource/inline`), key normalization, and the active-keymap atom + user-delta merge. |
| `vinary.input.keymaps-registry` | app-db registry of keymap **sets** — the three read-only built-ins (Standard/Vim/Emacs) plus user sets — and the persistence envelope. |
| `vinary.input.resolver` | Modal/chord resolver (Interpreter): normalize → resolution context → `step` decision (`:prefix`/`:dispatch`/`:consume`/`:pass`/`:retry`); pending sequence + chord timer in resolver-local atoms; global keydown handling. |
| `vinary.input.events` | Input mode, pending sequence, command palette, keymap config/selection, and the keybinding-editor events. |
| `vinary.input.fx` | Input-related effects: active-keymap installation, debounced persistence, chord-timeout, and the smooth `:dom/scroll` / `:dom/focus`. |
| `vinary.input.kbedit-history` | Reversible editor commands (the Command pattern as data) for keybinding undo/redo. |
| `vinary.input.presets` | *(macro `.clj`)* SUPERSEDED — a compile-time preset-EDN slurp kept for reference; presets are now inlined by `vinary.input.keymap` via `shadow.resource/inline` (which tracks the EDN as a recompile dependency). |

---

## 5. Renderer services (`vinary.renderer.*`)

Renderer-side, DOM-touching helpers behind the UI: parsing, rendering, layout,
find, scroll, and the pdf.js / CodeMirror engines.

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.renderer.core` | Renderer boot: re-frame loop, DataScript→re-frame reactivity bridge, IPC bridge (the `on*` handlers), keybinding-resolver install, React root mount, dev hooks. |
| `vinary.renderer.markdown` | Markdown → `{html, toc, assets}` via the common IR (`render-ir`), plus the office / org / latex IR render entry points. |
| `vinary.renderer.markdown-pipeline` | The DOM-free remark → rehype parse+transform pipeline (through metadata collection), extracted from `markdown` so streaming and the IR frontend share one spine. |
| `vinary.renderer.math` | MathJax TeX-to-SVG rendering and cached Markdown math postprocessing. |
| `vinary.renderer.mathjax-version-shim` | OBSOLETE after the MathJax 4 migration — retained (not deleted) as a record of why it existed. |
| `vinary.renderer.mermaid` | Mermaid source-to-SVG rendering for Markdown fences and direct Mermaid files; pre-DOM sizing + refit. |
| `vinary.renderer.figures` | Measure and size embedded local SVG/image figures (pre-DOM, no flash) + refit on font change. |
| `vinary.renderer.latex` | LaTeX → HTML string, the reusable front-end shared by standalone `.tex` documents and embedded-in-Org LaTeX (ADR-0025). |
| `vinary.renderer.toc` | Content-agnostic heading-offset cache + binary-search scroll-spy + TOC jump helpers (Markdown, PDF, HTML …). |
| `vinary.renderer.scroll` | Per-navigation content-pane scroll restore (`want!`/`apply!`) and late-retry guard. |
| `vinary.renderer.source-nav` | Bidirectional source⇄preview jump helpers — content-agnostic (any format that stamps `data-vv-source-*`). |
| `vinary.renderer.pdf` | In-renderer pdf.js engine: worker bootstrap, canvas/text/link layers, virtualization, zoom, outline, and the `:pdf/reflow` effect (ADR-0013). |
| `vinary.renderer.pdf-layout` | Pure, DOM-free PDF geometry/zoom/outline helpers (unit-tested). |
| `vinary.renderer.pdf-cache` | PDF byte cache (keyed by `:doc/path`, not DataScript) + the in-page-find text-materialization hook; deliberately free of any `pdfjs-dist` require. |
| `vinary.renderer.virtual-layout` | Pure, DOM-free vertical-stack geometry shared by the in-renderer PDF view and the streaming preview body (pre-estimated sibling spacer). |
| `vinary.renderer.find` | CSS Custom Highlight API in-page search (`search!`/`cycle!`/`clear!`). |
| `vinary.renderer.media` | Local media URL cache-busting and source helpers for Markdown previews. |
| `vinary.renderer.syntax` | Read-only source view: a CodeMirror 6 editor highlighted by web-tree-sitter grammars; grammar registry, source selection, and source⇄line scroll helpers. |
| `vinary.renderer.hints` | Vimium-style link hints for the content pane: collect visible targets, assign labels, follow a chosen target. |
| `vinary.renderer.history-input` | Coalescing for browser-style history commands that can arrive through multiple native input channels. |

---

## 6. UI layer (`vinary.ui.*`)

Reagent view components and their pure view-helpers.

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.ui.views` | Main shell, content strategy (per-kind view selection), Markdown body lifecycle, toolbar, status/modeline; hosts the in-pane directory browser (`:doc/kind = "directory"`) and the Ctrl-hover breadcrumb URI bar. |
| `vinary.ui.tabs` | The tab strip + the shared tab-item used by the horizontal strip and the sidebar's vertical Tabs list. |
| `vinary.ui.sidebar` | The left sidebar shell: a tabbed pane hosting the multi-project Files tree and the Contents (TOC) outline. |
| `vinary.ui.tree` | The git file-tree (the sidebar's Files tab), one `{:root :files}` per project, filtering, and reveal-active. |
| `vinary.ui.context-menu` | The themed right-click context menu: targets and actions. |
| `vinary.ui.menubar` | The custom, theme-matched menu bar (File / View / Settings / Help), incl. the `View ▸ Fit` radio submenu. |
| `vinary.ui.menu-focus` | Pure helpers for keyboard focus inside custom menus (skipping separators/disabled rows). |
| `vinary.ui.zoombar` | The always-visible bottom zoom bar: − / editable % field / preset dropdown / +, dispatching the context-aware `[:view/zoom …]`. |
| `vinary.ui.palette` | Command palette / fuzzy finder: one overlay, three sources (`:command`/`:file`/`:theme`). |
| `vinary.ui.settings` | The Preferences dialog: variable + fixed-width fonts, sizes, and view prefs (theme lives here too). |
| `vinary.ui.extensions` | Settings ▸ Extensions dialog: ad-blocking controls + the Chrome-extension manager. |
| `vinary.ui.ext-toolbar` | Browser-action toolbar (extension icons in the address-bar row; click → open popup). |
| `vinary.ui.keybindings-editor` | Visual keybinding editor (Settings ▸ Key Bindings ▸ Customize…): a two-pane modal with undoable edits. |
| `vinary.ui.about` | The Help ▸ About dialog: app name + version + repo link. |
| `vinary.ui.modal` | Shared modal-dialog shell: the scrim overlay + bordered/elevated panel with a title bar. |
| `vinary.ui.icons` | Font Awesome (Free Solid, self-hosted) icon set. |
| `vinary.ui.access-keys` | Shared helpers for desktop-style Alt access keys in custom renderer UI. |
| `vinary.ui.platform` | Tiny renderer-side OS detection (`single-click-open?`): single-click opens on Linux, double-click on Windows/macOS. |
| `vinary.ui.preview-context` | Pure helpers for Markdown preview context menus: term extraction and source-location formatting. |
| `vinary.ui.preview-navigation` | Navigation events for links activated from preview surfaces (classify link/target → the right open event). |
| `vinary.ui.passwords` | Renderer UI for the native password-manager bridge: provider status + login picker + save prompt — **status/metadata only, never a secret**. |
| `vinary.ui.ssh` | Renderer UI for SSH/SFTP remote files: the auth prompt (password / key passphrase / multi-prompt MFA) and the connection-error surface. The typed secret stays in the modal's local state. |

---

## 7. Common document IR (`vinary.ir.*`)

One weighted-transducer intermediate representation that every format front-end
targets and every back-end lowers from (ADR-0017). The design lineage is the
`lling-llang` weighted-automata toolkit: **front-ends** parse a format into the
IR, **layers/transducers** transform it, **back-ends** lower it to HTML or ANSI,
and **capabilities** derive views (e.g. a TOC) from it. Markdown, office, org,
latex, diff, source, tables, logs, PDF-reflow, and archives all render through
this one tree.

### 7.1 Core algebra + parsing machinery

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.ir.node` | The common document IR node — one uniform tagged tree (constructors, `children`, walkers). |
| `vinary.ir.meta` | Per-node metadata accessors + stable anchor identity (metadata rides in the node). |
| `vinary.ir.semiring` | Semiring-generic weights: the algebra `(K, ⊕, ⊗, 0̄, 1̄)` that lets one parser/transducer work over many weight domains. |
| `vinary.ir.transducer` | Weighted top-down tree transducer over the IR (blueprint: `lling-llang` tree_transducers). |
| `vinary.ir.lattice` | A token lattice — a weighted DAG of alternative token hypotheses consumed by the Earley parser. |
| `vinary.ir.wpda` | Weighted pushdown automaton (blueprint: `lling-llang` pushdown). |
| `vinary.ir.decode` | Streaming/incremental WPDA decoder (blueprint: `lling-llang` PdaDecoder). |
| `vinary.ir.earley` | Earley recognizer over a token lattice (predict/scan/complete). |
| `vinary.ir.forest` | Packed parse-forest extraction from an Earley chart. |
| `vinary.ir.layer` | A composable IR→IR transform pipeline (blueprint: `lling-llang` LayerPipeline). |
| `vinary.ir.flag` | RETIRED (ADR-0017) — the `:vv/ir` migration flag that gated the common-IR render path during rollout. |

### 7.2 Back-ends + capabilities

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.ir.backend.html` | IR → HTML: lower the IR to a HAST tree and serialize it through the shared sanitizer. |
| `vinary.ir.backend.ansi` | IR → terminal ANSI — the terminal analog of the HTML back-end (used by vv-cli/vv-tui). |
| `vinary.ir.backend.sanitize` | The single HTML sanitize schema (GitHub's allowlist) for the IR back-end and the Markdown pipeline. |
| `vinary.ir.capability.toc` | Table-of-contents capability over the IR: walk for heading-role nodes and emit the outline. |

### 7.3 Front-ends (format → IR)

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.ir.frontend.markdown` | rehype HAST (from the app's unified pipeline) → IR. |
| `vinary.ir.frontend.office` | Office HTML (docx via mammoth, or the ODF block HTML) → IR. |
| `vinary.ir.frontend.source` | A web-tree-sitter parse tree → IR (the source-view code outline). |
| `vinary.ir.frontend.pdf` | pdf.js text-content items → IR (the hybrid, lossless PDF reflow). |
| `vinary.ir.frontend.table` | A content-service table envelope (workbook sheets, or one page of rows) → IR. |
| `vinary.ir.frontend.log` | A content-service log envelope (full text, or one page of lines) → IR. |
| `vinary.ir.frontend.log-stream` | Streaming log front-end: line batches → `:record` IR blocks, emitted incrementally with bounded memory. |
| `vinary.ir.frontend.diff` | `.diff`/`.patch` text (via `vinary.diff`) → IR for the unified colored view + per-file outline. |
| `vinary.ir.frontend.archive` | An archive/directory listing envelope → IR (directory-browser-compatible entries). |

### 7.4 Grammar

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.ir.grammar.log` | WPDA for streaming log-record segmentation (the pushdown stack tracks brace nesting so a multi-line record is one block). |

---

## 8. Document streaming (`vinary.stream.*`)

Bounded-memory incremental rendering (ADR-0019): trade total throughput for
latency + bounded memory on large documents. Default-on for the streamable kinds.

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.stream.protocol` | The `StreamParser` contract: a document that arrives as a sequence of batches → incremental IR blocks. |
| `vinary.stream.scheduler` | Drives a stream into the DOM: open a transport session, then on each idle tick pull one batch, parse it, and commit its blocks. |
| `vinary.stream.sink` | Append-mode render sink: lower IR blocks to HTML and append them to the stream body. |
| `vinary.stream.transport` | Renderer-side pull client for the main-process stream sessions (`window.vv.stream{Open,Pull,Close}`). |
| `vinary.stream.flag` | The document-streaming feature gate (which kinds stream, and the size threshold). |

---

## 9. Terminal renderer (`vinary.terminal.*`)

The headless second renderer over the shared IR/streaming spine (ADR-0019): the
GUI is untouched; these lower the same IR to ANSI for `vv-cli`/`vv-tui`.

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.terminal.caps` | Terminal capability detection: width, colour, truecolor, OSC-8 hyperlinks, inline graphics. |
| `vinary.terminal.graphics` | Headless terminal image graphics (the terminal analog of `<img>`): kitty/sixel raster + vector decode. |
| `vinary.terminal.pdf` | Headless PDF text extraction: the pdf.js **legacy** build (pure JS, no DOM/canvas) → reflowed text. |
| `vinary.terminal.stream` | The sink-agnostic, cancellable terminal streaming engine (line stream via `content_service`). |
| `vinary.terminal.syntax` | Headless tree-sitter → ANSI syntax highlighter (the terminal analog of the CodeMirror-coupled highlighter). |

---

## 10. Terminal UI (`vinary.tui.*`)

`vv-tui` — the interactive full-screen terminal viewer. A pure core (`state`,
`viewport`, `keys`, `find`, `toc`) with one impure Node-coupled driver (`term`).

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.tui.core` | Wires the raw-terminal driver → key events → the pure reducer → the viewport paint (with a `--drive` test seam). |
| `vinary.tui.term` | The raw-terminal driver — the ONLY impure module: alternate screen, raw mode, cursor. |
| `vinary.tui.keys` | Raw-stdin bytes → key events (pure, incremental — unit-testable without a terminal). |
| `vinary.tui.state` | The pure key→command reducer (`step`) across three modes (`:normal` scroll, find, toc). |
| `vinary.tui.viewport` | A pure windowed line buffer: only a `:h`-row window is painted, so per-frame cost is bounded. |
| `vinary.tui.find` | Pure in-buffer search over rendered ANSI lines (matching on ANSI-stripped visible text). |
| `vinary.tui.toc` | The pure table-of-contents overlay (resolves the document's toc entries to a navigable list). |

---

## 11. Headless CLI (`vinary.cli.*`)

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.cli.core` | `vv-cli` — a headless terminal document renderer; reads a file with the Electron-free `content_service` and prints. |
| `vinary.cli.render` | Turn a `content_service` payload into terminal ANSI, reusing the GUI's IR front-ends verbatim and lowering through `ir.backend.ansi`. |

---

## 12. Top-level libraries (`vinary.*`)

| Namespace | Responsibility |
|-----------|----------------|
| `vinary.diff` | Pure unified/git diff model + the side-by-side (split) renderer — DOM-free and fs-free, so it is fully unit-testable (ADR-0026). |
| `vinary.grammar-catalog` | Compile-time catalog of bundled tree-sitter grammars. |

---

## 13. Dependency direction

Renderer code does not import `vinary.main.*`. Main code does not import renderer
UI namespaces. The preload IPC seam (`resources/preload.js`) is the process
boundary. The `vinary.ir.*`, `vinary.stream.*`, and `vinary.diff` libraries are
DOM-free and process-agnostic, so both the renderer and the headless
`vinary.cli.*` / `vinary.tui.*` tools depend on them — never the reverse.
