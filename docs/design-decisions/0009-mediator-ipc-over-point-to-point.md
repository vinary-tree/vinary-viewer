# 0009 — A single `window.vv` mediator seam over point-to-point IPC

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

In Electron, the renderer talks to the main process via IPC. With `contextIsolation:true` and
`nodeIntegration:false` ([ADR-0001](0001-electron-42-supersedes-13.md)), the renderer has **no** raw
`ipcRenderer` and **no** Node access — by design. So *some* preload must expose the cross-process
capability. The question is **its shape**: one consolidated, well-defined seam, or many ad-hoc
`ipcRenderer` calls sprinkled wherever a component happens to need IO.

This is the classic **Mediator** pattern (Gamma, Helm, Johnson & Vlissides, *Design Patterns*, 1994,
ISBN 978-0201633610): route interactions through a single object instead of letting components reference
each other point-to-point.

## Decision

Expose **one** mediator object, `window.vv`, from `resources/preload.js` via
`contextBridge.exposeInMainWorld('vv', …)`, with a **small, JSON-only** vocabulary, and route **every**
cross-process message through it:

```js
contextBridge.exposeInMainWorld('vv', {
  open:  (path) => ipcRenderer.send('vv:open', path),    // renderer → main
  close: (path) => ipcRenderer.send('vv:close', path),
  onContent: (cb) => { ipcRenderer.on('vv:content', …); return unsubscribe; },
  onError:   (cb) => { ipcRenderer.on('vv:error',   …); return unsubscribe; },
  onTree:    (cb) => { ipcRenderer.on('vv:tree',    …); return unsubscribe; },
});
```

On the renderer side, only **two** places touch `window.vv`: the effects `:vv/open` / `:vv/close`
(`vinary.app.fx`) for outbound messages, and `vinary.renderer.core/bridge!` for inbound subscriptions
(which dispatch `[:content/received]` / `[:content/error]` / `[:tree/received]`). The main side defines
the matching handlers in `vinary.main.service/init!` (`ipcMain.on "vv:open"/"vv:close"`) and emits via
`webContents.send`.

## Consequences

- **One auditable boundary.** The entire cross-process contract is the five members of `window.vv`,
  listed in one ~30-line preload file. Reviewing the app's IO surface means reading that one file — a
  property the [threat model](../security/threat-model.md) leans on heavily.
- **No `ipcRenderer` sprawl.** Components never reach IPC directly; they dispatch re-frame events/effects
  that go through the seam. Adding a feature does not scatter new channels across the UI.
- **JSON-only, minimal capability.** The seam passes plain data (paths, content payloads) and hands the
  callback only the message **payload**, not the raw Electron event. The renderer **cannot** read the
  filesystem, spawn a shell, or invoke arbitrary channels — it has exactly the five operations above.
  (See [security/threat-model.md §3](../security/threat-model.md).)
- **Unsubscribe discipline.** Each `on*` returns an unsubscribe function, so listeners can be cleaned up
  rather than leaking — a small but real benefit of centralizing the wiring.
- **Symmetric, named channels.** `vv:open`/`vv:close` (in) and `vv:content`/`vv:error`/`vv:tree` (out)
  form a tidy, documented protocol (see [architecture/03-ipc-protocol.md](../architecture/03-ipc-protocol.md)
  / [reference/ipc-channels.md](../reference/ipc-channels.md)).

## Alternatives considered

- **Expose `ipcRenderer` (or a thin passthrough) to the renderer.** Rejected: it hands the renderer an
  open-ended capability (any channel, `invoke`, etc.), enlarging the attack surface and defeating the
  point of `contextIsolation`. The whole value of the seam is that it is *narrow*.
- **Multiple small bridges / per-feature preload objects.** Rejected: more surfaces to audit and an
  easy path back to sprawl. One `window.vv` keeps the contract in a single place.
- **Direct point-to-point `ipcRenderer.on/send` calls inside components.** Rejected: couples UI
  components to IPC details, scatters channel names, and makes the IO surface impossible to review at a
  glance.

## Trade-offs

- A single mediator is a **central indirection**: every new cross-process need must be added to
  `window.vv` and the main handler, rather than wired ad hoc at the call site. That tiny ceremony is
  exactly what we want — it forces every IO capability through one reviewed, JSON-only door. For a
  security-sensitive process boundary, the centralization is a feature, not a cost.

> Pattern reference: Gamma, Helm, Johnson & Vlissides, *Design Patterns: Elements of Reusable
> Object-Oriented Software* (Addison-Wesley, 1994), ISBN 978-0201633610 — the **Mediator** pattern. (No
> DOI; cited by ISBN.)
