# Glossary

Every term, acronym, and symbol used in the vinary-viewer documentation suite,
defined once and alphabetized. Each entry notes **where it is first introduced**
(the document that motivates it); this glossary is the canonical, context-free
definition you can return to. Symbols and notation are collected at the end.

> Convention reminder: mathematics is written as MathJax math spans — inline as a
> backtick span wrapped in dollar signs, display as a fenced ` math ` block — and the
> operator $`:=`$ reads "is defined as". A leading colon (`:doc/path`, `:ui/find`)
> marks a **Clojure keyword** — an interned, self-evaluating identifier used as a
> map key or an event/effect/subscription name.

---

## A

**app-db** — The single ClojureScript map that holds **all ephemeral UI state**
for the renderer: tabs, the active tab id, per-tab history stacks, saved scroll
entries, the theme, the in-page find state, the git tree, and the `:ds/rev`
revision counter. It is
re-frame's one application-state value; subscriptions read it, events return a new
version of it. In vinary-viewer the *documents themselves* do **not** live here —
they live in DataScript (see **SSOT**). The initial value is
`vinary.app.db/default-db`. *First introduced in `theory/01-reactive-architecture.md`;
detailed in `theory/02-state-model-datascript-app-db.md`.*

**Adapter** — In the hexagonal/ports-&-adapters pattern, the concrete code that
implements a **port** for a specific technology (e.g. the chokidar file watcher is
an adapter for a "watch a file" port). *First introduced in
`theory/04-hexagonal-and-ipc-mediator.md`.*

**AST (Abstract Syntax Tree)** — A tree representation of parsed source. The
Markdown pipeline produces two ASTs in sequence: an **mdast** (Markdown AST, from
`remark-parse`) and an **hast** (HTML AST, after `remark-rehype`) before
serialising to an HTML string. *First introduced in
`theory/05-strategy-renderer-registry.md`; detailed in the Markdown-rendering
feature/architecture docs.*

**awaitWriteFinish** — A chokidar option that waits for a file's size to stop
changing before emitting a `change` event, so a partially-written save is not
previewed. vinary-viewer uses `{stabilityThreshold 80 pollInterval 20}` (poll
every 20 ms; consider the write finished after 80 ms of no size change). *First
introduced in `theory/03-live-refresh-spine.md`.*

## B

**:browser (target)** — The shadow-cljs compilation target for the **renderer**
build: it compiles ClojureScript to run in Chromium. Node built-ins (`fs`,
`path`, `url`) are stubbed to `false` so the renderer cannot reach the filesystem
directly. *First introduced in `theory/04-hexagonal-and-ipc-mediator.md`;
detailed in `architecture/02-process-and-build-topology.md`.*

## C

**chokidar** — The Node file-watching library vinary-viewer uses in the main
process. Watchers are reconciled to the renderer's **retained local file paths**
and Markdown asset paths; they listen for `change` and `add` events and re-send
the owning content on each. *First introduced in
`theory/03-live-refresh-spine.md`.*

**coeffect** — In re-frame, an **input** an event handler needs from the world
(the current `app-db`, the current time, a random seed, …), supplied to the
handler so it can stay a **pure function**. The default coeffect is `:db` (the
current `app-db`). The dual of an **effect**. *First introduced in
`theory/01-reactive-architecture.md`.*

**Command (pattern)** — A *Gang-of-Four* behavioural pattern that **reifies a
request as an object** so it can be stored, queued, logged, and replayed.
re-frame events *are* Commands: each event is a plain data vector
(`[:history/back]`), dispatched rather than called. vinary-viewer's **navigation
history** is the textbook payoff — each visited path is a reified entry on a stack
that back/forward replay. *First introduced in `theory/01-reactive-architecture.md`;
the history model is `theory/07-command-history-model.md`. See Gamma et al.
(1994), ISBN 978-0201633610.*

**contextBridge** — The Electron API (run in the **preload** script) that exposes
a small, explicit, serialisable API from an isolated privileged context to the
renderer's `window`, *without* handing the renderer Node or `ipcRenderer`
directly. vinary-viewer exposes exactly one object, `window.vv`. It is the
concrete realisation of the **Mediator** seam. *First introduced in
`theory/04-hexagonal-and-ipc-mediator.md`.*

**CSS Custom Highlight API** — A W3C browser API that paints styling over arbitrary
**Range** objects *without inserting any elements into the DOM*. You register a
**Highlight** (a set of Ranges) under a name in `CSS.highlights`, and style it with
the `::highlight(name)` pseudo-element. vinary-viewer's in-page find uses it so
search highlighting never disturbs the rendered document. *First introduced in
`theory/06-find-css-custom-highlight.md`. See the
[W3C CSS Custom Highlight API Module Level 1](https://www.w3.org/TR/css-highlight-api-1/).*

**CSS Custom Property (`--vv-*`)** — A CSS variable (e.g. `--vv-fg`, `--vv-bg`).
Themes define the `--vv-*` palette on `:root`; the structural stylesheet
(`app.css`) only *references* them, so switching theme is just swapping which
file defines the variables. *First introduced in `theory/01-reactive-architecture.md`;
catalogued in `reference/css-variables.md`.*

## D

**DataScript** — An immutable, in-memory database for ClojureScript with a
Datalog query engine, modelled on Datomic. Data is stored as **datoms**; you query
with `d/q` (Datalog) and read entities with `d/pull`; you write with `d/transact!`.
In vinary-viewer it is the content cache for loaded local documents, keyed by
`:doc/path`. Tabs and tab histories live in app-db.
*First introduced in `theory/01-reactive-architecture.md`; primer in
`theory/02-state-model-datascript-app-db.md`. See
[github.com/tonsky/datascript](https://github.com/tonsky/datascript).*

**datom** — DataScript's atomic unit of fact: a 5-tuple
`[entity attribute value transaction added?]`, usually written `[e a v]`. The
fact "entity 7 has `:doc/kind` `"markdown"`" is one datom. A document is a small
set of datoms sharing an entity id. *First introduced in
`theory/02-state-model-datascript-app-db.md`.*

**DI (Dependency Injection)** — Supplying a component's collaborators from
*outside* rather than having it construct them, so the core logic does not depend
on concrete IO. In re-frame, **effects** and **coeffects** are the DI mechanism:
handlers declare *what* they need and *what* should happen, and the framework
injects the doing. *First introduced in `theory/04-hexagonal-and-ipc-mediator.md`.*

**:doc/* (doc attributes)** — The DataScript attributes describing one cached
local document, all keyed off the unique `:doc/path`: `:doc/kind`,
`:doc/text`, `:doc/html`, `:doc/toc`, `:doc/assets`, `:doc/error`, and
`:doc/stamp`. *First introduced in
`theory/02-state-model-datascript-app-db.md`.*

**:ds/rev** — The integer revision counter in `app-db` that is the **bridge**
between DataScript and re-frame: a conn listener bumps it (`(update db :ds/rev
inc)`) on every transaction, and the conn-reading subscriptions declare
`:<- [:ds/rev]`, so they recompute exactly when DataScript changes. *First
introduced in `theory/01-reactive-architecture.md`; mechanics in
`theory/02-state-model-datascript-app-db.md`.*

## E

**effect (fx)** — In re-frame, a **description of a side effect** the framework
should perform after an event handler runs (e.g. `[:ds/transact tx]`,
`[:markdown/render …]`, `[:theme/apply theme]`). Handlers return effects as
**data**; registered *effect handlers* (`reg-fx`) actually do the IO. This is how
all impurity is pushed to the edge. The dual of a **coeffect**. *First introduced
in `theory/01-reactive-architecture.md`; the catalogue is in
`reference/events-effects-subs.md`.*

**Electron** — The framework that runs a Chromium UI and a Node.js backend as one
desktop app. It has two process kinds (see **main process** and **renderer
process**) that communicate over **IPC**. *First introduced in
`theory/01-reactive-architecture.md`; topology in
`architecture/02-process-and-build-topology.md`. See
[electronjs.org](https://www.electronjs.org/).*

**event** — In re-frame, a plain data vector naming "something happened",
dispatched with `rf/dispatch` (e.g. `[:content/received payload]`,
`[:find/toggle]`). Events are **reified Commands**; they are the *only* way to
change `app-db`. *First introduced in `theory/01-reactive-architecture.md`.*

## F

**fx** — Shorthand for **effect** (above) and for the namespace
`vinary.app.fx`, which registers vinary-viewer's effect handlers (`:ds/transact`,
`:markdown/render`, `:theme/apply`, `:find/run`, `:vv/open`, …). *First introduced
in `theory/01-reactive-architecture.md`.*

**form-3 component** — A reagent component written as a map of React lifecycle
methods via `reagent.core/create-class`. vinary-viewer uses one
(`markdown-body`) so it can run imperative DOM code
(`component-did-mount`/`component-did-update` → `set! innerHTML`) instead of
letting React diff the document body. (For contrast: a *form-1* component is a
plain render function; a *form-2* component is a function returning a render
function with local state.) *First introduced in
`theory/05-strategy-renderer-registry.md`; detailed in
`architecture/06-renderer-runtime.md`.*

## G

**GFM (GitHub-Flavored Markdown)** — The Markdown dialect adding tables,
task-list checkboxes, strikethrough, and autolinks. Enabled by `remark-gfm` in the
pipeline. *First introduced in `theory/05-strategy-renderer-registry.md`.*

**GoF (Gang of Four)** — Erich Gamma, Richard Helm, Ralph Johnson, and John
Vlissides, authors of *Design Patterns: Elements of Reusable Object-Oriented
Software* (1994). The suite cites them for the **Command**, **Observer**,
**Strategy**, and **Mediator** patterns. ISBN 978-0201633610 (no DOI). *First
introduced in `theory/01-reactive-architecture.md`.*

## H

**hexagonal architecture (ports & adapters)** — Alistair Cockburn's pattern that
puts pure application logic at the centre and isolates every external concern
(filesystem, IPC, DOM, network) behind **ports** implemented by **adapters**, so
the core has no direct dependency on IO. vinary-viewer realises it with re-frame
**effects/coeffects** as the ports and `fx`/`service` as the adapters. *First
introduced in `theory/04-hexagonal-and-ipc-mediator.md`. See
[alistair.cockburn.us/hexagonal-architecture](https://alistair.cockburn.us/hexagonal-architecture/).*

**Hiccup** — ClojureScript's data notation for HTML: a vector whose first element
is a keyword tag and whose rest is attributes and children, e.g.
`[:div.vv-error "Error: " msg]`. reagent renders Hiccup to React elements.
Note that vinary-viewer's *document body* is **not** Hiccup — it is an HTML string
written via `innerHTML`; the *shell* (tabs, toolbar, find bar, tree) is Hiccup.
*First introduced in `theory/05-strategy-renderer-registry.md`.*

**Highlight** — A `Highlight` object from the **CSS Custom Highlight API**: a set
of **Range**s registered under a name in `CSS.highlights`. vinary-viewer uses two:
`"vv-find"` (all matches) and `"vv-find-current"` (the focused match). *First
introduced in `theory/06-find-css-custom-highlight.md`.*

## I

**ipcMain / ipcRenderer** — Electron's IPC endpoints. `ipcRenderer` (renderer
side, reached only through the preload) `send`s messages to the main process and
`on`-subscribes to messages from it; `ipcMain` (main side) `on`-handles them.
vinary-viewer's main service registers `ipcMain.on("vv:open" | "vv:close")` and
pushes back over `webContents.send(...)`. *First introduced in
`theory/04-hexagonal-and-ipc-mediator.md`; channels in `reference/ipc-channels.md`.*

**IPC (Inter-Process Communication)** — The message passing between Electron's
main and renderer processes. In vinary-viewer it is **the only** cross-process
path, and it is mediated by the preload `contextBridge` (see **Mediator**). *First
introduced in `theory/01-reactive-architecture.md`; protocol in
`architecture/03-ipc-protocol.md`.*

## L

**LWW (Last-Writer-Wins)** — A convergence rule: when the same key is written
repeatedly, the latest write replaces earlier ones. Because `:doc/path` is a
DataScript **identity**, re-transacting a doc *upserts* it, so a stream of live
edits to one file converges to its latest content (last write wins). *First
introduced in `theory/03-live-refresh-spine.md`.*

## M

**main process** — Electron's privileged Node.js process. In vinary-viewer it
creates the `BrowserWindow`, runs the **IO service** (`vinary.main.service`: read
files, watch them, query git), and owns the only filesystem access. Built by the
shadow-cljs `:main` build to `dist/main/main.js`. *First introduced in
`theory/01-reactive-architecture.md`.*

**Mediator (pattern)** — A *GoF* behavioural pattern that routes all
communication between a set of colleagues through one mediator object, so they do
not reference each other directly (turning a many-to-many tangle into a hub). The
preload `contextBridge` (`window.vv`) is vinary-viewer's mediator between the
renderer and the main process: no point-to-point `ipcRenderer` use leaks into the
renderer. *First introduced in `theory/04-hexagonal-and-ipc-mediator.md`. See
Gamma et al. (1994), ISBN 978-0201633610.*

## N

**:node-script (target)** — The shadow-cljs target for the **main** build; it
emits a Node script and keeps `require("electron")` and Node built-ins as runtime
requires (no bundling), since the Electron runtime provides them. *First
introduced in `architecture/02-process-and-build-topology.md`.*

**nil-as-absence** — vinary-viewer's modelling rule that "no value" is represented
by the *absence* of an attribute, never by a `nil` value — because DataScript
rejects `nil`. Hence content transactions build their map with `cond->` (only
`assoc`-ing `:doc/text` when truthy) and *retract* a stale `:doc/error` rather than
setting it to `nil`. *First introduced in `theory/02-state-model-datascript-app-db.md`.*

## O

**Observer (pattern) / Observer–Observable** — A *GoF* behavioural pattern in which
**observers** register interest in a **subject** and are notified when it changes.
re-frame **subscriptions** form a memoised *signal graph* of observers over
`app-db`; vinary-viewer's `:ds/rev`-dependent subs observe DataScript indirectly.
*First introduced in `theory/01-reactive-architecture.md`. See Gamma et al.
(1994), ISBN 978-0201633610, and the MVC lineage in Krasner & Pope (1988),
[ACM DL](https://dl.acm.org/doi/10.5555/50757.50759).*

## P

**port** — In hexagonal architecture, an *abstract* boundary (an interface /
protocol) the application core talks to, with no knowledge of how it is fulfilled.
A planned `vinary.domain.protocols` namespace would name vinary-viewer's ports
explicitly; today the ports are implicit in the re-frame effect/coeffect contract.
*First introduced in `theory/04-hexagonal-and-ipc-mediator.md`.*

**preload** — A script Electron runs in the renderer's context *before* page
scripts, with Node access, in an **isolated** world (because `contextIsolation` is
on). vinary-viewer's `resources/preload.js` uses `contextBridge` to expose
`window.vv` and nothing else. *First introduced in
`theory/04-hexagonal-and-ipc-mediator.md`.*

**Promise** — A JavaScript object representing an eventually-available value. The
Markdown renderer returns a promise of `{:html ... :toc ... :assets ...}`; the
`:markdown/render` effect attaches `.then` (dispatch `[:content/rendered …]`) and
`.catch` (dispatch `[:content/error …]`), so async rendering rejoins the
unidirectional loop as a new event. *First introduced in
`theory/03-live-refresh-spine.md`.*

## R

**Range** — A DOM object describing a contiguous span of a document between two
boundary points (here, a start and end offset within a single text node).
vinary-viewer's find builds Ranges over the rendered text and hands them to a
**Highlight**. *First introduced in `theory/06-find-css-custom-highlight.md`.*

**reagent** — The ClojureScript wrapper over React used by vinary-viewer.
Components are functions returning **Hiccup**; `reagent.core/create-class` makes a
**form-3** component for lifecycle access; `reagent.dom.client` mounts the tree
(React 19 root API). *First introduced in `theory/01-reactive-architecture.md`. See
[reagent-project.github.io](https://reagent-project.github.io/).*

**re-frame** — The ClojureScript application framework vinary-viewer is built on.
It structures the app as **six dominoes**: dispatch an **event**, gather
**coeffects**, run a pure handler producing **effects** and a new **app-db**, run
the effects, recompute **subscriptions**, re-render the **view** — a closed,
unidirectional loop. *First introduced in `theory/01-reactive-architecture.md`. See
[day8.github.io/re-frame](https://day8.github.io/re-frame/).*

**re-posh** — A library that bridges DataScript and re-frame by letting
subscriptions be DataScript queries. It is a **declared but unused dependency** in
vinary-viewer: the project deliberately uses the explicit hand-rolled **`:ds/rev`
bridge** instead, for a guaranteed, inspectable invalidation signal. *First
introduced in `theory/02-state-model-datascript-app-db.md`.*

**renderer process** — Electron's Chromium process that runs the UI. In
vinary-viewer it hosts the entire re-frame app, the reagent views, the Markdown
pipeline, the find/TOC DOM code, and the DataScript conn. It has **no** filesystem
access; it talks to main only over `window.vv`. Built by the shadow-cljs
`:renderer` build. *First introduced in `theory/01-reactive-architecture.md`.*

**rehype** — The HTML half of the **unified** ecosystem. vinary-viewer uses
`remark-rehype` (mdast → hast), `rehype-slug` (add id anchors to headings),
`rehype-highlight` (syntax-highlight code), and `rehype-stringify` (hast → HTML
string). *First introduced in `theory/05-strategy-renderer-registry.md`.*

**remark** — The Markdown half of **unified**. vinary-viewer uses `remark-parse`
(text → mdast) and `remark-gfm` (GitHub-Flavored extensions). *First introduced in
`theory/05-strategy-renderer-registry.md`.*

**ring buffer** — A fixed-capacity circular queue (a candidate structure for a
**bounded** history). vinary-viewer's history is presently an *unbounded* vector;
the term appears where bounding the history is discussed as a future option.
*First introduced in `theory/07-command-history-model.md`.*

## S

**scroll-spy** — A UI technique that highlights, in a table of contents, the
section currently scrolled into view. vinary-viewer caches heading offsets after
render/figure sizing, coalesces scroll work with `requestAnimationFrame`, and
uses binary search to find the active heading. *First introduced in
`theory/01-reactive-architecture.md`; detailed in the TOC feature/architecture
docs.*

**shadow-cljs** — The ClojureScript build tool used by vinary-viewer. It defines
two builds — `:main` (`:node-script`) and `:renderer` (`:browser`) — and provides
hot-reload during `watch`. *First introduced in
`architecture/02-process-and-build-topology.md`.*

**signal graph** — The memoised dependency graph of re-frame **subscriptions**:
*layer-2* subs read `app-db`/other subs, and recompute only when an input changes;
views at the leaves re-render only when their subscriptions change. vinary-viewer's
graph has the `:ds/rev`-rooted DataScript branch and the plain app-db branch.
*First introduced in `theory/01-reactive-architecture.md`.*

**slug** — A URL/anchor-safe identifier derived from a heading's text (e.g.
"Getting Started" → `getting-started`). `rehype-slug` puts a `slug` `id` on every
heading; the TOC and `:toc/scroll` use those ids. *First introduced in
`theory/05-strategy-renderer-registry.md`.*

**SSOT (Single Source of Truth)** — The principle that each piece of state has
exactly one authoritative home. vinary-viewer has a deliberate split: **app-db
owns UI/navigation**, **DataScript owns cached document content**, and **main
owns OS/native resources**. No fact is duplicated across those owners. *First introduced in
`theory/01-reactive-architecture.md`.*

**Strategy (pattern)** — A *GoF* behavioural pattern that selects an interchangeable
algorithm at runtime. vinary-viewer's `content-view` is a Strategy that picks the
document body by `:doc/kind` (watermark / error / image / markdown / placeholder).
*First introduced in `theory/01-reactive-architecture.md`; detailed in
`theory/05-strategy-renderer-registry.md`. See Gamma et al. (1994), ISBN
978-0201633610.*

**subscription (sub)** — In re-frame, a named, **memoised**, reactive query over
`app-db` (and/or other subs), registered with `reg-sub` and read with
`rf/subscribe`. Subs are the **Observer** layer: views derive from subs, never
from `app-db` directly. vinary-viewer's conn-reading subs (`:tabs`, `:doc/active`)
list `:<- [:ds/rev]` so DataScript changes invalidate them. *First introduced in
`theory/01-reactive-architecture.md`.*

## T

**time-travel / replay** — Because events are data and all impurity is in
effects, re-frame can re-run a recorded sequence of events to reproduce any state
(and dev tooling like re-frame-10x can step through them). vinary-viewer's
**navigation history** is a domain-level instance of the same idea (replaying
visited paths). *First introduced in `theory/01-reactive-architecture.md`;
the history model is `theory/07-command-history-model.md`.*

**TOC (Table of Contents)** — The panel listing a document's headings. Markdown
TOC data is collected during render and stored as `:doc/toc`; HTTP content
reports its heading outline from the web preload. The panel supports scroll-spy
highlighting and click-to-scroll. *First introduced in
`theory/01-reactive-architecture.md`.*

**transact! (`d/transact!`)** — DataScript's write operation: apply a vector of
*tx-data* (maps to upsert, `[:db/retract …]` / `[:db/retractEntity …]` to remove)
to the connection, atomically, producing a new database value and notifying
listeners. vinary-viewer routes every write through the `:ds/transact` effect.
*First introduced in `theory/02-state-model-datascript-app-db.md`.*

**TreeWalker** — A DOM iterator that visits nodes of a chosen type. find uses
`createTreeWalker(root, SHOW_TEXT)` to walk every text node under the content
root, searching each for matches. *First introduced in
`theory/06-find-css-custom-highlight.md`.*

## U

**unidirectional data flow** — The architectural rule that state changes flow in
**one direction only**: events → handler → new state/effects → subscriptions →
view → (events). There is no back-channel from view to state except by dispatching
another event. It is what makes the system predictable and replayable. *First
introduced in `theory/01-reactive-architecture.md`.*

**unified** — The pluggable text-processing engine underlying the Markdown
pipeline; `unified()` builds a processor onto which `remark`/`rehype` plugins are
`.use`d, then `.process(md)` runs them and returns a `Promise` of the result.
*First introduced in `theory/05-strategy-renderer-registry.md`. See
[unifiedjs.com](https://unifiedjs.com/).*

**unified-latex** (`@unified-latex/*`) — The LaTeX toolchain vinary-viewer uses to
render `.tex` documents: it parses LaTeX to an AST and lowers it to an HTML string
(`convertToHtml`), and expands `\newcommand`-family macros. It runs inside its **own**
bundled `unified@10`, so only a plain HTML string crosses into the app's `unified@11`
(wrapped as a `raw` node the shared `rehype-raw` parses). *See ADR-0025 and
`features/27-latex-rendering.md`.*

**uniorg** — The Emacs Org-mode counterpart of remark: `uniorg-parse` +
`uniorg-rehype` turn an `.org` file into the same HAST the Markdown pipeline produces,
so Org reuses the whole shared spine. *See ADR-0020/0024 and
`features/26-org-mode.md`.*

---

## Vocabulary added in 0.3.0 (common IR · streaming · terminal · Org/LaTeX/diff · remote SSH)

Alphabetized. The mathematical foundations (semiring, transducer, WPDA, Earley) are cited with DOIs in
[`theory/08 §7`](theory/08-common-document-ir.md) and [`theory/09 §11`](theory/09-document-streaming-and-the-wpda.md);
these entries define the term and point to the page that develops it.

**anchor-id** — A collision-free, stable identifier `ir.meta` attaches to each IR node, so DOM ids do not
churn as a streamed document grows. *First introduced in `theory/09-document-streaming-and-the-wpda.md`.*

**ANSI (back-end)** — The escape-sequence encoding the terminal renderer emits: SGR colour/style codes plus,
where the terminal supports them, kitty/sixel graphics. `ir.backend.ansi` is the tty counterpart of
`backend.html`. *First introduced in `theory/10-terminal-rendering-second-renderer.md`.*

**beam / beam width $`\beta`$** — The prune width bounding the WPDA streaming decoder's frontier;
$`\textsf{prune}_\beta`$ keeps the $`\beta`$ lowest-weight configs. *First introduced in
`theory/09-document-streaming-and-the-wpda.md`.*

**CLI (`vv --cli`)** — The one-shot terminal renderer: reads a file, lowers it through the common IR to ANSI,
writes to stdout, and exits (pipe-friendly). *First introduced in
`theory/10-terminal-rendering-second-renderer.md`; usage in `usage/07-terminal-cli-tui.md`.*

**content-visibility** — The CSS property (`content-visibility: auto`) the streamed body sets per block so the
browser skips layout/paint for off-screen blocks — bounding *render* cost while keeping every node in the
live DOM. *First introduced in `theory/09-document-streaming-and-the-wpda.md`.*

**credit-based backpressure (credit 1)** — The demand protocol bounding the stream transport: the renderer
holds one outstanding pull, and main pauses `readline` at the batch cap, so a bounded number of batches are
ever in flight. *First introduced in `theory/09-document-streaming-and-the-wpda.md`.*

**diff (unified / split)** — Two renderings of a `.diff`/`.patch`: *unified* (one column of add/del/context
lines; GUI HTML **and** terminal ANSI) and *split* / side-by-side (two columns, GUI-only, enriched with the
on-disk pre/post files). *First introduced in `features/28-diff-rendering.md`; ADR-0026.*

**Earley (over lattice)** — A general context-free parser; vinary-viewer runs Earley over a lattice with a
packed parse forest and a semiring-weighted **Viterbi** best-parse (`ir.earley` / `ir.forest`). *First
introduced in `theory/08-common-document-ir.md`.*

**frontier** — The current beam of live WPDA **configurations** — the streaming decoder's whole live state; a
config is $`(q, \gamma, w)`$ (state, stack, weight). *First introduced in
`theory/09-document-streaming-and-the-wpda.md`.*

**hunk** — A contiguous change region in a unified diff, headed by an `@@ … @@` range line. *First introduced
in `features/28-diff-rendering.md`.*

**IR (common document intermediate representation)** — The one tagged tree (`vinary.ir.node/Node`) every
format parses into, lowered to HTML (GUI) or ANSI (terminal) through one weighted-transducer core and one
sanitizer. *First introduced in `theory/08-common-document-ir.md`; ADR-0017.*

**kitty / sixel graphics** — Two terminal image protocols the CLI/TUI use (via `terminal.graphics`) to draw
raster images inline where supported; otherwise images degrade to a text placeholder. *First introduced in
`theory/10-terminal-rendering-second-renderer.md`.*

**known_hosts / TOFU (trust-on-first-use)** — SSH host-key verification: a presented host key is checked
against `~/.ssh/known_hosts`; an unknown host prompts once and is appended on accept; a **changed** key is a
hard reject. *First introduced in `features/29-remote-files-over-ssh.md`; ADR-0027.*

**reflow (PDF)** — An opt-in view (View ▸ Reflow Text) rendering a PDF's extracted text as reflowable prose
through the page/block/line/run IR facet, augmenting — never replacing — the faithful canvas render. *First
introduced in `features/11-native-pdf.md`.*

**semiring** — An algebra $`(K, \oplus, \otimes, \bar0, \bar1)`$ whose choice tunes what a weighted parse
computes (recognition, best-parse, total mass). Implemented: Boolean, Tropical, Log, Probability, Product,
Lexicographic. *First introduced in `theory/08-common-document-ir.md`; laws in `scientific/03-semiring-algebraic-laws.md`.*

**SSH / SFTP (`ssh://`, `sftp://`)** — The remote **virtual backend**: a remote URI opens through the same
pipeline, renderers, streaming, and refresh as a local path, main-side only. *First introduced in
`features/29-remote-files-over-ssh.md`; ADR-0027.*

**ssh-agent** — The running key-agent the SSH backend tries first for authentication (before key files and
keyboard-interactive); on decrypting a key file it may `AddKeysToAgent`. *First introduced in
`features/29-remote-files-over-ssh.md`.*

**StreamParser** — The pure `feed`/`finish` protocol (`vinary.stream.protocol`) that emits only
newly-completed IR blocks and retains only the open block — the node-testable heart of streaming. *First
introduced in `theory/09-document-streaming-and-the-wpda.md`.*

**tropical semiring** — The semiring $`(\mathbb{R} \cup \{\infty\},\ \min,\ +,\ \infty,\ 0)`$; ranking
competing segmentations by least cost is a shortest-path (Viterbi) over it. *First introduced in
`theory/08-common-document-ir.md`.*

**TUI (`vv --tui`)** — The interactive terminal previewer: a full-screen pager with in-page find, a Contents
outline, and scrolling, over the same IR/streaming spine as the GUI. *First introduced in
`theory/10-terminal-rendering-second-renderer.md`; usage in `usage/07-terminal-cli-tui.md`.*

**Viterbi (best-parse)** — The $`\oplus`$-optimal derivation extracted from the packed parse forest — the
max-weight (or, in the tropical semiring, least-cost) parse. *First introduced in
`theory/08-common-document-ir.md`.*

**weighted tree transducer** — The IR's transformation model (`ir.transducer`): a tree-to-tree map carrying
semiring weights, with exact composition. *First introduced in `theory/08-common-document-ir.md`.*

**WPDA (weighted pushdown automaton)** — A pushdown automaton whose transitions carry semiring weights;
recognizes context-free nesting (e.g. balanced braces) that no finite-state machine can. `ir.grammar.log`
specializes it to keep a multi-line JSON log entry one record. *First introduced in
`theory/09-document-streaming-and-the-wpda.md`.*

---

## Symbols and notation

| Symbol | Reads as | Meaning / where used |
|--------|----------|----------------------|
| $`:=`$ | "is defined as" | Binds a name to an expression, e.g. $`\mathrm{view} := f(\mathrm{state})`$. |
| $`\mathrm{view} := f(\mathrm{state})`$ | "the view is a function of state" | The SSOT/unidirectional invariant: the rendered UI is a pure function of application state. (`theory/01`) |
| `:foo/bar` | "the keyword foo-slash-bar" | A namespaced **Clojure keyword** — a map key or an event/effect/sub name (e.g. `:doc/path`, `:find/run`). |
| `:<- [:sub]` | "subscribes to" | A re-frame sub-of-sub input declaration; the sub recomputes when `[:sub]` changes (e.g. `:<- [:ds/rev]`). (`theory/01`, `02`) |
| `mod` | modulo | Remainder after division; the find cursor wraps via $`\mathit{idx} := (\mathit{idx} + \mathit{dir}) \bmod n`$. (`theory/06`) |
| $`\mathit{stack}'`$ | "stack-prime" | The *next* value of `stack` after an update, e.g. the history push $`\mathit{stack}' := \mathtt{(conj\ (vec\ (take\ (inc\ idx)\ stack))\ path)}`$. (`theory/07`) |
| $`\forall`$ | "for all" | Universal quantifier, used in diagram annotations (e.g. `Highlight.add(range)` $`\forall`$ matches). |
| $`\in`$ | "is an element of" | Set membership, e.g. $`\mathit{idx} \in 1..n`$, $`\mathit{kind} \in \{\mathtt{"markdown"}, \mathtt{"image"}, \mathtt{"text"}\}`$. |
| $`O(\cdot)`$ | "big-O of" | Asymptotic upper bound on cost, e.g. find is $`O(\mathit{total\_text\_length})`$. (`theory/06`) |
| `n` | count | The number of find matches (or of history entries), as context dictates. |

---

## References

The complete, verified citation list. **DOIs appear only where verified**; other
sources are cited by ISBN or canonical URL, as noted.

- **Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (1994).** *Design Patterns:
  Elements of Reusable Object-Oriented Software.* Addison-Wesley. **ISBN
  978-0201633610.** *(No DOI exists for this book.)* — Command, Observer, Strategy,
  Mediator.
- **Krasner, G. E., & Pope, S. T. (1988).** "A Cookbook for Using the
  Model-View-Controller User Interface Paradigm in Smalltalk-80." *Journal of
  Object-Oriented Programming, 1*(3), 26–49. **ACM Digital Library:**
  `https://dl.acm.org/doi/10.5555/50757.50759`. *(Cited via the ACM DL stable
  URL, not as a DOI.)* — the MVC observer lineage behind re-frame's subscriptions.
- **Reenskaug, T. (1979).** "Models–Views–Controllers." Xerox PARC technical
  note. Canonical PDF:
  `https://folk.universitetetioslo.no/trygver/1979/mvc-2/1979-12-MVC.pdf`.
  *(No DOI.)*
- **Cockburn, A.** "Hexagonal architecture (Ports & Adapters)."
  `https://alistair.cockburn.us/hexagonal-architecture/`. *(No DOI.)*
- **W3C.** *CSS Custom Highlight API Module Level 1.*
  `https://www.w3.org/TR/css-highlight-api-1/`. *(W3C specification; no DOI.)*
- **re-frame documentation.** `https://day8.github.io/re-frame/`. *(No DOI.)*
- **DataScript.** `https://github.com/tonsky/datascript`. *(No DOI.)*
- **reagent.** `https://reagent-project.github.io/`. *(No DOI.)*
- **unified / remark / rehype.** `https://unifiedjs.com/`. *(No DOI.)*
- **Electron documentation.** `https://www.electronjs.org/docs`. *(No DOI.)*
- **chokidar.** `https://github.com/paulmillr/chokidar`. *(No DOI.)*
- **shadow-cljs.** `https://github.com/thheller/shadow-cljs`. *(No DOI.)*

> The tree-sitter / incremental-parsing citation — Wagner & Graham, *Efficient and
> Flexible Incremental Parsing*, ACM TOPLAS 20(2), 1998, **DOI
> [10.1145/276393.276394](https://doi.org/10.1145/276393.276394)** — belongs to
> the source-preview feature and is cited there, not in the theory pillar.
