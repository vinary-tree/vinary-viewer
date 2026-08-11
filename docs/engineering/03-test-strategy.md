# Test strategy — the taxonomy

vinary-viewer's tests split into two layers by *what they can prove without a
host*: a **DOM-free ClojureScript unit build** that exercises the pure logic
(the IR, streaming, TUI, terminal, renderer-helper, and app subsystems), and a
set of **JavaScript smoke harnesses** that drive the wiring — real Electron, real
Node file IO, real SSH transport — that a unit test cannot reach. This page
documents that taxonomy, the fixtures the smokes stand on, and the dev-vs-release
Electron-smoke split.

> **Audience.** Read this when adding a test, deciding *which layer* a new test
> belongs in, or diagnosing why `npm test` passes but `npm run test:electron:release`
> fails (or vice versa).

---

## 1. The two layers, and the rule for choosing

| Layer | Host | Proves | Where |
|-------|------|--------|-------|
| **Unit** (`:node-test`) | Headless Node, no DOM | Pure, deterministic logic: algebra laws, segmentation, parse output, layout math, key handling. | `test/vinary/**/*_test.cljs` |
| **Smoke** (JavaScript) | Electron, or Node with real IO | The *wiring*: IPC, streaming pull-loops, terminal-capability degradation, SSH transport, archive extraction, extension loading. | `test/*-smoke.js` |

The rule the codebase follows, stated in the smoke harnesses themselves: **a
smoke exists for the value that lives in the wiring** — argv → content service →
IR front-ends → back-end → output, plus streaming and capability degradation —
"none of which a pure unit test exercises", while "the pure pieces … are
unit-tested separately" ([`test/cli-smoke.js`](../../test/cli-smoke.js) header).
If a behavior is a pure function of its inputs, it is a unit test; if its value is
that two real subsystems agree at a seam, it is a smoke.

![The vinary-viewer test taxonomy](../diagrams/component-test-taxonomy.svg)

*Diagram source: [`../diagrams/component-test-taxonomy.puml`](../diagrams/component-test-taxonomy.puml).*

---

## 2. The DOM-free unit build (`:node-test`)

The `test` build (`shadow-cljs.edn`) is `:target :node-test` with `:ns-regexp
"-test$"`, so it discovers every namespace ending in `-test` under `test/vinary/`,
compiles them to `dist/test/test.js`, and runs them with `node dist/test/test.js`.
The build is **DOM-free by construction**: it runs in bare Node, so nothing it
tests may touch the DOM, `window`, or Electron. That constraint is the point — it
forces the pure core to *be* pure, and it makes the whole unit suite runnable in a
CI container with no display.

The suite (39 namespaces at time of writing) covers these subsystems:

| Subsystem | Namespaces (`test/vinary/…`) | What is proven |
|-----------|------------------------------|----------------|
| **IR core** (`ir.*`) | `ir/semiring_test`, `ir/transducer_test`, `ir/wpda_test`, `ir/decode_test`, `ir/earley_test`, `ir/node_test`, `ir/meta_test`, `ir/parity_test` | Semiring laws across all algebras, weighted-transducer composition, the WPDA + streaming decoder, Earley-over-lattice parsing, node/metadata invariants, and batch-vs-stream **byte-parity**. |
| **IR back-ends** | `ir/backend/html_test`, `ir/backend/ansi_test` | Lowering one IR to HTML (renderer/GUI) and to ANSI (terminal) — the shared producer both hosts reuse. |
| **IR capabilities** | `ir/capability/toc_test` | Cross-cutting capabilities (the Contents outline) computed from the IR. |
| **IR front-ends** | `ir/frontend/{data,log_stream,office,org,pdf,source}_test` | Each format parsing into the IR: tables/CSV, streaming logs, docx/ODF, Org, PDF text-run reflow, tree-sitter source. |
| **Streaming** (`stream.*`) | `stream/flag_test`, `stream/transport_test` | The per-kind streaming gate (a `nil` size never streams) and the credit-1 double-buffered transport. |
| **Renderer helpers** | `renderer/{figures,latex,math,media,source_nav,toc}_test` | Pure renderer logic: SVG figure sizing, LaTeX normalization, MathJax handling, media-path rewriting, the source↔preview jump math, and the binary-search scroll-spy offset cache. |
| **App** (`app.*`) | `app/nav_test`, `app/uri_test` | Navigation history and URI parsing/classification. |
| **TUI** (`tui.*`) | `tui/{find,keys,state,toc,viewport}_test` | The pure keys→state→frame pipeline: find, key binding, viewport ring, TOC overlay. |
| **Terminal** (`terminal.*`) | `terminal/graphics_test` | The kitty/sixel graphics encoding logic. |
| **Main** (pure) | `main/file_kind_test`, `main/startup_test` | File-kind classification and startup argument handling — the pure slices of the main process. |
| **UI / misc** | `ui/icons_test`, `core_test`, `diff_test`, `grammar_catalog_test` | Icon mapping, the virtual-layout geometry helpers ([ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md)), the diff parser, and the grammar-catalog reader. |

Two examples show why the unit layer is where invariants live:

- **Byte-parity** ([theory/08](../theory/08-common-document-ir.md)) is asserted in
  `ir/parity_test` — streaming a document must produce HTML byte-identical to
  rendering it whole.
- **Bounded memory** ([theory/09 §5](../theory/09-document-streaming-and-the-wpda.md#5--the-bounded-memory-property))
  is asserted in `ir/frontend/log_stream_test`, which feeds 500+ batches and
  checks the frontier stays a single WPDA config and only the last open record is
  retained.

The build is run as the first step of `npm test`:

```bash
shadow-cljs compile test && node dist/test/test.js
```

---

## 3. The JavaScript smoke harnesses

Ten JavaScript harnesses (`test/*-smoke.js`) drive the wiring. Each is a plain
Node or Electron script using the built-in `assert` module; there is no test
framework. They fall into three groups by host.

### 3.1 Electron smokes (isolated display protocol)

| Harness | Drives |
|---------|--------|
| [`electron-smoke.js`](../../test/electron-smoke.js) | The flagship. Boots the **real renderer** (`resources/public/index.html`) in Electron, wires `vv:stream-*` to the **real** `content_service.js` (genuine `createReadStream`/`readline` batching + the session registry), and asserts: streamed `innerHTML` is **byte-identical** to batch over a corpus; the mid-stream scrollbar spacer sizes the whole document ([ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md)); the session registry returns to `0` after teardown (**no fd/session leak**); DevTools opens with no main-process crash; and re-frame-10x is hidden by default. |
| [`tree-e2e.js`](../../test/tree-e2e.js) | Boots the compiled renderer and **real main service** against synthetic and git fixtures. It covers scoped structural refresh, add/delete/rename (including unstaged tracked git paths), refresh-before-open DOM ordering, watcher release, watcher-ready reconciliation, directory/project context-menu Refresh, and the Files rail-icon Refresh All. |
| [`watch-e2e.js`](../../test/watch-e2e.js) | Boots two real windows over the same path and proves one shallow tree watcher fans out to every retaining renderer, releases owners independently on collapse/window destruction, coexists with retained-document watchers, and is reacquired on expansion. |
| [`extensions-smoke.js`](../../test/extensions-smoke.js) | The Chrome-extension + ad-block runtime ([ADR-0015](../design-decisions/0015-scoped-extension-runtime-gpl-free.md)): `session.extensions.loadExtension` loads the unpacked MV3 fixture, its content script injects into a matched HTTP page, its action popup loads with native `chrome.*` (runtime/storage/action), and the native ad-blocker fetches filter lists (network-gated). |
| [`daemon-smoke.js`](../../test/daemon-smoke.js) | The resident-daemon control seam ([IPC §6](../architecture/03-ipc-protocol.md#6-the-daemon-socket-process--process)): `vv1 ping` reports the pid/version/**loaded-bundle mtime**/window count `install.sh` verifies against; the v0.2 open message still opens a window; an unknown command opens none; `vv1 stop` exits the process and frees the socket **and** the single-instance lock; and `vv` retires a stale **idle** daemon but never one with a window open. It also guards the backward-compatibility contract that makes all this safe — a control frame must not parse as JSON, or probing a pre-`vv1` daemon would open a stray window on it. Spawns real daemons under a private `XDG_RUNTIME_DIR` and a renamed mirror app (its own userData → its own lock), so it cannot disturb the developer's live daemon. |

### 3.2 Terminal smokes (Node, no display)

| Harness | Drives |
|---------|--------|
| [`cli-smoke.js`](../../test/cli-smoke.js) | `vv-cli` end-to-end **without Electron**: each format fixture lowers to structured ANSI (box-drawing tables, gutters, SGR colour); `NO_COLOR` emits **zero** escape bytes (the isatty/`NO_COLOR` degradation contract); `--toc` prints the outline; and a >5 MiB log streams through the WPDA log-stream parser with **bounded peak RSS** — memory does not scale with file size. |
| [`graphics-smoke.js`](../../test/graphics-smoke.js) | The terminal image pipeline through the built binary: a Markdown image encodes to a **kitty** (`ESC_G f=32`) or **sixel** (`ESC P`) escape under `--graphics kitty\|sixel`; `--no-graphics` and a piped (non-TTY) stdout degrade to a labelled placeholder with **zero** escapes; an SVG rasterizes at its intrinsic size; a tall image is followed by its row-footprint newlines; and webp/remote srcs degrade rather than crash. |
| [`tui-smoke.js`](../../test/tui-smoke.js) | The interactive TUI **without a pseudo-tty**, via the `--drive <keyfile>` seam: keys replay through the same keys→state→frame pipeline the live terminal uses, and the final frame is dumped deterministically. Asserts scroll, find (jump + reverse-video highlight), the TOC overlay + jump, and that a log larger than the viewport ring stays bounded. A small, skippable Python-`pty` check covers the one thing `--drive` cannot: alternate-screen teardown + cursor restore on `q`. |

### 3.3 Main-process / IO smokes (Node, no display)

| Harness | Drives |
|---------|--------|
| [`content-service-smoke.js`](../../test/content-service-smoke.js) | `content_service.js` — the main-process file reader — against real archives (`tar-stream`, `zlib`, `yauzl`) with a hand-rolled `crc32`, proving archive listing/extraction and content classification. |
| [`git-tree-smoke.js`](../../test/git-tree-smoke.js) | The sidebar file-tree git seam: `repo-files` lists with `git ls-files --cached --others --exclude-standard` and subtracts `git ls-files --deleted`, so new non-ignored files appear while ignored clutter and deleted/unstaged-renamed tracked paths do not linger. It exercises both exact commands against a throwaway repo and asserts `send-tree!` still falls back to `dir-walk/dir-tree` when there is no repo ([ADR-0030](../design-decisions/0030-fallback-project-roots.md)). |
| [`ssh-config-smoke.js`](../../test/ssh-config-smoke.js) | Hermetic unit tests for `ssh_config.js` (pure, no fs/net): `parseSshUri` for `ssh://` / `sftp://` authority, port, user, and home-relative path handling ([ADR-0027](../design-decisions/0027-remote-files-over-ssh.md)). |
| [`ssh-transport-smoke.js`](../../test/ssh-transport-smoke.js) | `ssh_transport.js` end-to-end against the **hermetic in-process ssh2 SFTP fixture** (no network, no external host); also asserts ssh2 runs **pure-JS** (no native crypto addon) and that `AddKeysToAgent` adds a key to a throwaway ssh-agent. |
| [`daemon-events-smoke.js`](../../test/daemon-events-smoke.js) | The authenticated target-daemon protocol: private descriptor lifecycle, both negative sides of mutual HMAC + SFTP-namespace proof, framed requests, SSH direct forwarding, invalidations, remote tree conversion, reconnect restoration, in-flight cancellation/owner release, and no-target SFTP fallback. |

### 3.4 What `npm test` actually runs

The default `npm test` is **Node-only** — it runs the unit build plus the listed
Node smokes, and it deliberately excludes Electron smokes, which need a display:

```bash
# npm test (terminal test compilers validate vendored assets; they do not sync/fetch)
shadow-cljs compile test && node dist/test/test.js \
  && node test/ssh-config-smoke.js && node test/ssh-transport-smoke.js \
  && node test/daemon-events-smoke.js \
  && node test/content-service-smoke.js && node test/git-tree-smoke.js \
  && node test/headless-runner-smoke.mjs \
  && npm run test:cli && npm run test:tui
# test:cli  → compile:cli:test (grammars:check + graphics:check) + cli-smoke + graphics-smoke
# test:tui  → compile:tui:test (grammars:check + graphics:check) + tui-smoke
```

The Electron smokes are separate scripts (`test:electron`, `test:electron:release`,
`test:extensions`, `test:extensions:sandbox`, `test:tree-e2e`, `test:watch-e2e`,
`test:remote-daemon-events-e2e`) because they require a running Electron with a display protocol. The repository
headless runner supplies that protocol without using the developer's desktop (see §3.6 and
[08-ci-and-validation-discipline.md](08-ci-and-validation-discipline.md)).

`remote-daemon-events-e2e` is the real-app SSH/SFTP counterpart to `tree-e2e`: it boots main + renderer,
crosses the in-process SSH2 SFTP/direct-forward fixture, and proves target content/tree watcher delivery for
both URI schemes, including URI-encoded tree clicks, project Refresh/Refresh All, collapsed-scope release, and
re-expansion refresh. Its outer Node runner owns the isolated Electron profile and removes it only after the
Electron child has fully exited.

### 3.5 The one harness that runs the REAL main process

`electron-smoke.js` and `scripts/screenshots.cjs` both **mock** the `vv:open` IPC seam — they inject
content through `state.contentByPath` / `vv:open-files` — so main's real `open!` → `send-tree!` →
`repo-tree` / `dir-walk` chain never executes in them. That left the sidebar's project-root logic
verifiable only in pieces.

[`tree-e2e.js`](../../test/tree-e2e.js) closes that gap. It `require`s the **real compiled main**
(`dist/main/main.js`) from inside an Electron process — the only way it can be required, since it
auto-invokes `vinary.main.core/main` on load — so the entire production chain runs:

```
CLI argv → startup/doc-uris → vv:open-files → [:files/opened] → [:doc/open]
__vvopen(path) ───────────────────────────────────────────────→ [:doc/open]
  → load-fx → vv:open → main open! → send-tree!
  → repo-tree (git) OR dir-walk/dir-tree (synthetic) → vv:tree → [:tree/received]
  → projects/merge-project → [:ui :projects] → ui.tree renders <details.vv-project>
```

It asserts against **both** `window.__vvdb()` (app-db verbatim) and the real sidebar DOM, and drives
context menus with genuine `MouseEvent`s. Besides project-root behavior, it covers automatic structural
add/rename/delete refresh, the no-content-save-relist invariant, child/ancestor/tab watcher release,
refresh-before-open at the first DOM `open` mutation, watcher-ready race reconciliation, and manual
directory/project/Files-tab refresh end to end. Its watched fixture and isolated `$XDG_CONFIG_HOME` are
separate throwaway directories removed by the harness.

```bash
npm run test:tree-e2e     # compile main+renderer, then run the real app through the headless backend
```

> **Poll, never single-read.** Every UI assertion in this harness goes through a polling helper.
> Reagent re-renders on an animation frame and the IPC round-trip is async, so reading the DOM once
> immediately after an app-db assertion races the re-render — which is exactly how the
> Remove-from-Files check first failed against a correct implementation.

> **Note on `test/test-sidebar.js`.** This is the legacy v0.1.0 vmd-patch sidebar
> harness. It is parse-checked by [`test/lint.js`](../../test/lint.js) but is not
> part of `npm test`; the shipped 0.2/0.3 app does not load `src/sidebar.js`.

### 3.6 Cross-platform headless Electron backends

[`scripts/run-electron-headless.mjs`](../../scripts/run-electron-headless.mjs) is the single display owner for
Electron tests, screenshots, and the input benchmark:

| Platform | Backend | Isolation behavior |
|----------|---------|--------------------|
| Linux | `x11` (the `auto` default) | Starts a private `xvfb-run` display and removes inherited Wayland variables. |
| Linux | `wayland` | Starts Weston with its `headless` backend, pixman renderer, fake input seat, private `XDG_RUNTIME_DIR`, and private socket; then terminates Weston and removes that directory after the child exits. |
| macOS | `native` (`auto`) | Uses WindowServer, but `VV_HEADLESS=1` keeps BrowserWindows hidden and changes the app activation policy so it has no Dock presence. |
| Windows | `native` (`auto`) | Uses the native compositor, but keeps BrowserWindows hidden with `skipTaskbar`. |

The Linux virtual surfaces remain mapped *inside* their private compositor: realistic layout, animation-frame,
focus, and paint behavior matters to these tests. Only the macOS/Windows native mode suppresses mapping.
`test/headless-runner-smoke.mjs` validates backend selection, environment isolation, command construction, and
the two native-hidden plans on every platform; the explicit Linux gates exercise the actual servers:

```bash
npm run test:electron:x11
npm run test:electron:wayland
VV_HEADLESS_BACKEND=wayland npm run test:tree-e2e   # select Weston for any migrated command
npm run test:electron:display                      # deliberately use the current desktop
```

`VV_HEADLESS_WIDTH` / `VV_HEADLESS_HEIGHT` set the virtual output dimensions. A missing `xvfb-run` or `weston`
is a hard failure for the selected backend rather than permission to fall back to the real desktop.

---

## 4. Fixtures

The smokes stand on a small set of committed fixtures under
[`test/fixtures/`](../../test/fixtures/):

| Fixture | Used by | Purpose |
|---------|---------|---------|
| `ext-probe/` (an unpacked MV3 extension: `manifest.json`, `background.js`, `content.js`, `popup.{html,js}`, `icon16.png`) | `extensions-smoke.js` | A minimal extension that exercises content-script injection and native `chrome.*` in the action popup. |
| `smoke.pdf` | PDF paths in the smokes / screenshots | A tiny real PDF for the pdf.js pipeline. |
| `ssh-server.js` (`startSftpServer`) | `ssh-transport-smoke.js`, `electron-smoke.js` | A hermetic **in-process** `ssh2.Server` SFTP server, so the SSH transport and the remote-streaming smoke run with no network and no external host. |

Most other fixtures are *generated* by the harness at run time (e.g.
`graphics-smoke.js` builds its PNG/JPEG/GIF images with `pngjs`/`jpeg-js`/`omggif`
so no binary assets are committed), which keeps the repository small and the
fixtures inspectable.

---

## 5. The dev-vs-release smoke split

`electron-smoke.js` runs against **both** build profiles, through two scripts:

```bash
npm run test:electron          # headless runner → electron-smoke.js                   (DEV build)
npm run test:electron:release  # release + headless runner with VV_RELEASE=1           (RELEASE build)
```

The release variant exists because an entire class of bug is **release-only** —
Closure `:simple` still transforms the code enough that some test *drivers* behave
differently. Two adaptations make the one harness run correctly in both profiles:

1. **`re-frame-10x` presence is asserted by profile.** The dev build preloads
   re-frame-10x; the release build strips it (`goog.DEBUG` is false). The A4 check
   reads `process.env.VV_RELEASE`: when set, it asserts `View ▸ re-frame-10x` is
   **absent**; otherwise it asserts it is **present**. This is the direct
   regression gate for [ADR-0016](../design-decisions/0016-main-process-simple-optimization.md)'s
   crash class (the smoke also confirms DevTools opens with no `.Xc`-style rename
   crash).

2. **Some steps are dev-gated because `:simple` encapsulates the re-frame global.**
   The bidirectional source↔preview jump step drove `re_frame.core.dispatch_sync`
   *from the test*, but the release `:simple` build encapsulates that global, so
   the run died with `ReferenceError: re_frame is not defined` before any later
   assertion. Those jump steps — and the PDF-reflow toggle step, for the same
   reason — are now **dev-gated**; the release build covers the underlying pure
   jump math through the DOM-free `renderer/source_nav_test` unit tests instead
   (CHANGELOG, `[0.3.0-dev]`). The Org "View Source" step was rewritten to drive
   the real `View` menu item rather than the global, so it runs in **both** builds.

The general principle: **a step that can only be exercised through a dev-only
global is gated to the dev smoke, and its logic is re-proven in the DOM-free unit
layer, which runs identically under any optimization.** This keeps the release
smoke focused on what is genuinely release-specific — that the optimized artifact
boots, renders, and does not crash on renamed interop.

---

## 6. References and see also

- [`test/cli-smoke.js`](../../test/cli-smoke.js) et al. — the smoke harnesses whose
  headers this page paraphrases.
- [04-lint-and-conventions.md](04-lint-and-conventions.md) — the lint pass that
  parse-checks every JS harness.
- [05-terminal-build-and-launch.md](05-terminal-build-and-launch.md) — the `cli` /
  `tui` builds the terminal smokes exercise.
- [ADR-0016](../design-decisions/0016-main-process-simple-optimization.md) — the
  reason the release smoke exists.
- [ADR-0023](../design-decisions/0023-streaming-scrollbar-and-pacing.md) — the
  scrollbar-spacer behavior `electron-smoke.js` asserts.
- [theory/09](../theory/09-document-streaming-and-the-wpda.md) — the bounded-memory
  and byte-parity properties the unit layer pins.
- `AGENTS.md` (repository root) — the testing-guidelines section this page expands.
