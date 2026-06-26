# Theory 01. Reactive Architecture

This page defines the core architectural idea: the rendered UI is derived from
state, and all state changes move through explicit events and effects.

---

## 1. Central equation

`view ≔ f(state)`

The view is defined by state. A user action, file change, render completion, or
native-view callback does not mutate the visible UI directly; it dispatches an
event that updates state or requests an effect. Subscriptions derive data from
state, and Reagent renders the shell from those subscriptions.

---

## 2. Unidirectional loop

```text
event
  -> coeffects
  -> pure handler
  -> new app-db plus effects
  -> effect handlers
  -> subscriptions
  -> views
```

Terms:

| Term | Meaning |
|------|---------|
| Event | Plain data vector naming an intent, such as `[:content/received payload]`. |
| Coeffect | Input supplied to an event handler, such as current `app-db` or content scroll. |
| Effect | Data describing work to perform after the handler, such as `[:ds/transact tx]`. |
| Subscription | Memoized query over app-db, DataScript snapshots, or other subscriptions. |

This is re-frame's model. It keeps event handlers mostly pure and moves IO to
registered effects.

---

## 3. Store split

vinary-viewer uses two renderer stores:

| Store | Owns |
|-------|------|
| app-db | UI and navigation state: tabs, active tab, per-tab histories, scroll entries, settings, keybinding editor state, find, sidebar, TOC active heading. |
| DataScript | Cached document content: raw text, rendered HTML, TOC metadata, embedded assets, errors, stamps. |

DataScript is observed by re-frame through `:ds/rev`: each transaction bumps a
counter in app-db, and content subscriptions re-read the current DataScript
snapshot when that counter changes.

---

## 4. Pattern mapping

| Pattern | Where it appears |
|---------|------------------|
| Command | re-frame events; keybinding command registry; tab/history navigation entries. |
| Observer | re-frame subscriptions; `:ds/rev` bridge from DataScript into the subscription graph. |
| Strategy | `content-view` chooses Markdown, image, PDF, source, text, web, error, or empty-state rendering by context. |
| Mediator | `window.vv` is the single IPC mediator between renderer and main. |

The pattern names are used as explanatory vocabulary, not as heavyweight class
hierarchy requirements.

---

## 5. Current instantiation

| Layer | Code |
|-------|------|
| Events | `vinary.app.events`, `vinary.input.events` |
| Effects | `vinary.app.fx`, `vinary.input.fx` |
| Subscriptions | `vinary.app.subs` |
| Navigation transforms | `vinary.app.nav` |
| Content cache | `vinary.app.ds` |
| Commands | `vinary.app.commands` |
| Key resolver | `vinary.input.resolver` |
| Views | `vinary.ui.*` |
| Renderer entry | `vinary.renderer.core/init` |
| IPC mediator | `resources/preload.js` |

---

## 6. Why this matters

Live refresh is the clearest payoff. A file save updates content in DataScript,
which invalidates content subscriptions through `:ds/rev`. It does not rewrite the
app-db tab/history/settings/keybinding slices. The preview can repaint while the
reader keeps the same tab, same scroll intent, same search state, and same UI
configuration.

---

## References

- Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (1994). *Design Patterns:
  Elements of Reusable Object-Oriented Software.* Addison-Wesley. ISBN
  978-0201633610.
- re-frame documentation: <https://day8.github.io/re-frame/>
- DataScript: <https://github.com/tonsky/datascript>
- Reagent: <https://reagent-project.github.io/>
