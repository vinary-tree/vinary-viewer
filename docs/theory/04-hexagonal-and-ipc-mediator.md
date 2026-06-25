# Theory 04 — Hexagonal Architecture and the IPC Mediator

> **Where this fits.** Theory 01 said *all impurity lives in effects at the edge*.
> This document explains the architectural shape that statement implies —
> **hexagonal architecture (ports & adapters)** — and then zooms in on the most
> important edge in the app: the **IPC seam** between Electron's renderer and main
> processes, which is a textbook **Mediator** realised by the preload
> `contextBridge`.

## 1. Ports & adapters in one picture

**Hexagonal architecture** (Alistair Cockburn; also called *ports & adapters*) puts
the *pure application core* in the middle and isolates every external concern —
filesystem, IPC, DOM, network, clock — behind a **port**: an abstract boundary the
core talks to without knowing how it is fulfilled. Each port is fulfilled by an
**adapter**: concrete code for one specific technology.

```
                    ┌───────────────────────────────────────┐
   filesystem ◀────▶│ adapter: vinary.main.service (fs, git) │
   (chokidar) ◀────▶│                                       │
                    │            PORTS (boundaries)          │
   IPC / preload◀──▶│   "open/close a file"  "content in"   │
                    │   "render markdown"    "write a doc"   │
   DOM / CSS  ◀────▶│   "highlight ranges"   "scroll to id" │
                    │                                       │
                    │        ╔═══════════════════════╗       │
                    │        ║  PURE APPLICATION CORE ║       │
                    │        ║  events + subs + state ║       │
                    │        ╚═══════════════════════╝       │
                    └───────────────────────────────────────┘
```

The win is **testability and substitutability**: the core can be reasoned about,
tested, and replayed (Theory 01) with the adapters mocked, and a port's adapter can
be swapped (a different watcher, a different renderer) without touching the core.

### 1.1 re-frame *is* the ports-&-adapters mechanism

You do not need a separate framework to get hexagonal structure here — re-frame
already provides it, via two duals introduced in Theory 01:

- **Coeffects are *input* ports.** When a handler needs something from the world,
  it declares a coeffect; the framework injects it. The default is `:db` (the
  current `app-db`).
- **Effects are *output* ports.** When a handler wants something to happen, it
  returns an effect *as data*; a registered **effect handler** (`reg-fx`) is the
  **adapter** that performs it.

This is **Dependency Injection** by construction: the core (event handlers)
declares *what it needs* and *what should happen*, and the doing is injected. The
core never calls `fs`, `ipcRenderer`, the DOM, or DataScript directly — it only
returns descriptions.

## 2. Effects at the edge: the current adapter set

Every adapter in vinary-viewer is a registered effect (or a coeffect read). The
complete output-port/adapter set lives in `vinary.app.fx`; here is the whole list,
each annotated with the external concern it adapts:

| Effect (port) | Adapter does | External concern |
|---------------|--------------|------------------|
| `:ds/transact` | `d/transact! conn tx` | **DataScript** store (the only write path) |
| `:markdown/render` | run the unified pipeline; `.then`/`.catch` → dispatch | **async Markdown** rendering |
| `:theme/apply` | set `#vv-theme-link` `<link>` href | **DOM / CSS** (live theme swap) |
| `:find/run` | `finder/search!` → dispatch `[:find/count n]` | **DOM** (paint highlight Ranges) |
| `:find/cycle` | `finder/cycle!` → dispatch `[:find/idx i]` | **DOM** (move focused match) |
| `:find/clear` | `finder/clear!` | **DOM** (remove highlights) |
| `:toc/scroll` | `getElementById` + `scrollIntoView` | **DOM** (scroll to heading) |
| `:vv/open` | `window.vv.open path` | **IPC** → main (start watching/reading) |
| `:vv/close` | `window.vv.close path` | **IPC** → main (stop watching) |

Read this table as *"the complete list of ways the pure core is allowed to affect
the world."* If a behaviour is not expressible as one of these effects, it does not
belong in an event handler — it belongs in a new effect. (The catalogue with full
signatures is [`../reference/events-effects-subs.md`](../reference/events-effects-subs.md).)

Three of these adapters — `:vv/open`, `:vv/close`, and the *inbound* counterparts
wired in `bridge!` — cross the most consequential boundary in the app: the
process boundary. That is the IPC seam.

## 3. The IPC seam as a Mediator

Electron runs two processes (Theory: see also
[`../architecture/02-process-and-build-topology.md`](../architecture/02-process-and-build-topology.md)):

- the **main process** — privileged Node.js; owns the filesystem, the
  `BrowserWindow`, and the IO/watch service;
- the **renderer process** — Chromium; runs the entire re-frame UI and has **no**
  filesystem access.

They must communicate, but you do **not** want the renderer reaching into Node or
sprinkling `ipcRenderer.send`/`.on` calls throughout the UI. That would be a
many-to-many tangle *and* a security hole (a compromised renderer with raw IPC/Node
is a remote-code-execution risk — see [`../security/threat-model.md`](../security/threat-model.md)).

The **Mediator** pattern (Gamma, Helm, Johnson & Vlissides, 1994) solves exactly
this: route *all* communication between colleagues through one mediator object, so
no colleague references another directly. vinary-viewer's mediator is the **preload
`contextBridge` object, `window.vv`** — a single, minimal, JSON-only API. Every
cross-process message goes through it; nothing else is exposed.

> **Colour convention.** Throughout the diagrams, the IPC seam is **amber**
> (`#B1951D`), and *all IPC arrows are amber*, precisely because this seam is a
> single conceptual boundary you should be able to spot at a glance.

### 3.1 The preload, literately

The entire mediator is one file, `resources/preload.js`. It runs in an **isolated**
context (because `contextIsolation` is on — §4) with Node access, *before* any page
script, and exposes one object. Walk it line by line:

```javascript
// resources/preload.js
const { contextBridge, ipcRenderer } = require('electron');   // (1)

contextBridge.exposeInMainWorld('vv', {                       // (2)
  // renderer → main
  open:  (path) => ipcRenderer.send('vv:open',  path),        // (3)
  close: (path) => ipcRenderer.send('vv:close', path),
  requestKeymap: () => ipcRenderer.send('vv:keymap-request'), // (3′) «now available»

  // main → renderer (each returns an unsubscribe fn)
  onContent: (cb) => {                                         // (4)
    const h = (_e, payload) => cb(payload);                   // (5) strip the IpcEvent
    ipcRenderer.on('vv:content', h);
    return () => ipcRenderer.removeListener('vv:content', h); // (6) unsubscribe
  },
  onError:  (cb) => { /* same shape for 'vv:error'  */ },
  onTree:   (cb) => { /* same shape for 'vv:tree'   */ },
  onKeymap: (cb) => { /* same shape for 'vv:keymap' */ },     // (4′) «now available»
});
```

1. **`require('electron')`** — only the preload (privileged, isolated) has this.
   The renderer cannot `require` anything (`nodeIntegration` is off).
2. **`exposeInMainWorld('vv', {…})`** — installs *exactly* `window.vv` on the
   renderer's global, and nothing else. This is the narrow waist of the whole app's
   trust boundary.
3. **Outbound methods (`open`/`close`)** wrap `ipcRenderer.send` on the fixed
   channels `vv:open` / `vv:close`. The renderer calls `window.vv.open(path)`; it
   never sees `ipcRenderer`. *(`requestKeymap`, on `vv:keymap-request`, belongs to
   the keybinding system **now available** — same pattern.)*
4. **Inbound subscriptions (`onContent`/`onError`/`onTree`)** take a callback and
   register an `ipcRenderer.on` listener. *(`onKeymap`, on `vv:keymap`, is the
   keybinding system's inbound channel, **now available**.)*
5. **Each handler strips the Electron `IpcRendererEvent`** (`_e`) and passes the
   renderer only the **plain JSON `payload`** — so the renderer never touches an
   Electron object, only serialisable data.
6. **Each returns an unsubscribe function** (`removeListener`), so the renderer can
   tear a listener down cleanly (important for hot-reload and lifecycle hygiene).

> **What is deliberately *not* here.** No `fs`, no `path`, no raw `ipcRenderer`, no
> arbitrary channel — the renderer's entire view of the OS is this handful of
> `window.vv` methods over a fixed, named `vv:*` channel set. (The data-streaming
> core today is `open`/`close` + `onContent`/`onError`/`onTree`; `requestKeymap` /
> `onKeymap` are the keybinding system's channels, **now available**.) This
> minimality — a small, fixed, JSON-only surface — is the security posture (§4 and
> [`../security/threat-model.md`](../security/threat-model.md)).

### 3.2 The two sides of the seam in re-frame

On the **renderer side**, the core never calls `window.vv` from a handler; it goes
through effects (outbound) and `bridge!` (inbound), keeping the core pure:

```clojure
;; outbound: effect adapters (vinary.app.fx)
(rf/reg-fx :vv/open  (fn [path] (when-let [vv (.-vv js/window)] (.open  vv path))))
(rf/reg-fx :vv/close (fn [path] (when-let [vv (.-vv js/window)] (.close vv path))))

;; inbound: subscribe the seam to re-frame events (vinary.renderer.core/bridge!)
(defn bridge! []
  (when-let [vv (.-vv js/window)]
    (.onContent vv (fn [p] (rf/dispatch [:content/received (js->clj p :keywordize-keys true)])))
    (.onError   vv (fn [p] (rf/dispatch [:content/error   (js->clj p :keywordize-keys true)])))
    (.onTree    vv (fn [p] (rf/dispatch [:tree/received    (js->clj p :keywordize-keys true)])))))
```

Inbound payloads are JSON; `js->clj … :keywordize-keys true` turns them into idiomatic
ClojureScript maps (`{:path … :kind … :text …}`) before they enter the loop as events.

On the **main side**, the IO service is the *adapter for the filesystem port*; it
handles the outbound channels and pushes content/tree/error back over
`webContents.send`:

```clojure
;; vinary.main.service/init!
(defn init! []
  (.on ipcMain "vv:open"  (fn [e path] (open!  (.-sender e) path)))
  (.on ipcMain "vv:close" (fn [_e path] (close! path))))
```

So the data crosses the seam as: renderer effect → `window.vv.open` →
`ipcRenderer.send "vv:open"` → `ipcMain.on "vv:open"` → `service/open!` → (reads,
watches, then) `webContents.send "vv:content"` → `ipcRenderer.on` → `onContent` →
`rf/dispatch [:content/received]`. The precise channels and payload shapes are in
[`../architecture/03-ipc-protocol.md`](../architecture/03-ipc-protocol.md) and
[`../reference/ipc-channels.md`](../reference/ipc-channels.md). The role of this
seam within the flagship round trip is the amber band of the live-refresh sequence
([`../diagrams/seq-live-refresh.puml`](../diagrams/seq-live-refresh.puml), embedded
in Theory 03).

## 4. Security posture of the boundary (actual vs recommended)

The Mediator is also the security boundary, so state the **actual** posture
precisely (full analysis in [`../security/threat-model.md`](../security/threat-model.md)):

**In force today** (`vinary.main.core/create-window!`):

```clojure
:webPreferences {:contextIsolation true     ; preload runs in its own world; page JS can't reach it
                 :nodeIntegration  false     ; the renderer cannot require() Node modules
                 :preload (preload-path)}    ; the only bridge is window.vv
```

- **`contextIsolation true`** — the preload's context is isolated from page
  scripts, so a page cannot tamper with the bridge or reach `ipcRenderer`.
- **`nodeIntegration false`** — the renderer has no `require`, no `process`, no
  Node built-ins.
- **Minimal, JSON-only `contextBridge`** — only `window.vv`'s handful of methods
  cross, on a fixed named `vv:*` channel set, and only serialisable data flows (the
  preload strips Electron event objects).

**Recommended / Forthcoming hardenings** (not yet applied — flag, don't claim):

- **`sandbox: true`** for the renderer. Today the renderer is *not* sandboxed and
  the preload uses CommonJS `require('electron')`; enabling the sandbox would
  further constrain the renderer (and would move the preload to the
  sandbox-compatible API surface).
- **A strict Content-Security-Policy** on the renderer document.
- The Markdown pipeline has **no `rehype-raw`**, so **raw embedded HTML inside
  Markdown is *not* passed through** to the output — a meaningful default reduction
  of injection surface (verified against the pipeline in
  `vinary.renderer.markdown`; see Theory 05). This is current behaviour, not a
  recommendation.

## 5. Where the ports become explicit: the planned `domain.protocols`

Today the ports are **implicit** in the re-frame effect/coeffect contract — they
exist as the *set of registered effects* (§2) rather than as named interfaces. A
planned refinement makes them explicit:

> **«Forthcoming (planned).»** A `vinary.domain.protocols` namespace would declare
> the ports as ClojureScript **protocols** (e.g. a `FileSource` with
> `open!`/`close!`/`watch`, a `Renderer` with `render`, a `Highlighter` with
> `paint!`/`clear!`). The current `service`/`fx`/`finder` namespaces would then be
> *named adapters* implementing those protocols, and tests could substitute
> in-memory adapters at the protocol boundary. This is an organisational change —
> it does not alter the runtime data flow established here — and is tracked in
> [`../design-decisions/`](../design-decisions/README.md).

Naming this as planned (rather than pretending it exists) keeps the documentation
honest: the *hexagonal shape* is real today via effects; the *explicit protocol
ports* are a roadmap item.

## 6. Summary

- vinary-viewer is **hexagonal**: a pure core with every external concern behind a
  **port** fulfilled by an **adapter**. re-frame *is* the mechanism — **coeffects
  are input ports, effects are output ports** (Dependency Injection by construction).
- The complete **adapter set** is the registered effects in `vinary.app.fx`
  (DataScript, Markdown, theme, find, TOC scroll, IPC).
- The **IPC seam is a Mediator**: the preload `contextBridge` exposes exactly
  `window.vv` (a small fixed set of methods over named `vv:*` channels —
  `open`/`close` + `onContent`/`onError`/`onTree` today, plus the
  `requestKeymap`/`onKeymap` channels for the keybinding system now available),
  routing *all* cross-process communication through one minimal, JSON-only object —
  drawn **amber** everywhere.
- The **actual security posture** is `contextIsolation` + `nodeIntegration:false` +
  minimal bridge + no `rehype-raw`; **`sandbox:true`** and a strict **CSP** are
  recommended **Forthcoming** hardenings.
- Explicit **`domain.protocols`** ports are **planned**; the hexagonal shape exists
  today through effects.

Next: [Theory 05 — the Strategy renderer](05-strategy-renderer-registry.md).

## References

- Cockburn, A. "Hexagonal architecture (Ports & Adapters)."
  <https://alistair.cockburn.us/hexagonal-architecture/> (no DOI).
- Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (1994). *Design Patterns.*
  Addison-Wesley. **ISBN 978-0201633610** (no DOI). — Mediator.
- Electron documentation — context isolation, `contextBridge`, sandbox.
  <https://www.electronjs.org/docs/latest/tutorial/context-isolation> and
  <https://www.electronjs.org/docs/latest/tutorial/sandbox> (no DOI).
- re-frame documentation. <https://day8.github.io/re-frame/> — effects/coeffects
  as the injection mechanism.
