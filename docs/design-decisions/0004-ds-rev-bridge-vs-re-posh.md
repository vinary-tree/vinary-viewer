# 0004 — A `:ds/rev` transaction-revision bridge instead of re-posh internals

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

Documents and tabs live in a **DataScript** database ([ADR-0008](0008-datascript-plus-app-db-split.md)),
but the UI is **re-frame**, which reacts to changes in its own `app-db`, not to a separate DataScript
`conn`. We need re-frame subscriptions that read DataScript to **recompute when DataScript changes**.

[**re-posh**](https://github.com/denistakeda/re-posh) is the well-known library that marries DataScript
to re-frame, and it *is* a declared dependency. But re-posh wires reactivity through its own
subscription machinery and internal bookkeeping, which is more magic than we want for the small,
well-understood set of DataScript-reading subscriptions here (`:tabs`, `:doc/active`, `:doc/toc`).

## Decision

Use an explicit, hand-rolled **transaction-revision bridge** and leave re-posh **dormant**:

1. On startup, listen on the `conn`: `ds/install-bridge!` calls
   `d/listen! conn ::reframe (fn [_tx-report] (rf/dispatch [:ds/changed]))`.
2. The `[:ds/changed]` event simply **bumps a counter** in `app-db`:
   `(update db :ds/rev inc)` (`vinary.app.events`). `:ds/rev` starts at `0` in `vinary.app.db`.
3. DataScript-reading subscriptions declare `:ds/rev` as an input, so they recompute on every
   transaction:

```clojure
;; vinary.app.subs
(rf/reg-sub :tabs
  :<- [:ds/rev]
  (fn [_ _] (ds/open-docs (ds/snapshot))))

(rf/reg-sub :doc/active
  :<- [:ds/rev] :<- [:ui/active-path]
  (fn [[_rev path] _] (when path (ds/active-doc (ds/snapshot) path))))
```

The flow is: **transact → `d/listen!` fires → `[:ds/changed]` → `:ds/rev` increments → dependent subs
recompute → views update.**

## Consequences

- **Reactivity is guaranteed and debuggable.** Every DataScript change becomes an ordinary re-frame
  event (`[:ds/changed]`) you can see in re-frame-10x, and a single observable value (`:ds/rev`) drives
  recomputation. There is no hidden subscription graph to reason about.
- **It is tiny and explicit.** The whole bridge is one listener + one event + one counter. Anyone
  reading `ds.cljs`/`events.cljs`/`subs.cljs` can follow it end to end.
- **re-posh stays present but unused.** It remains a dependency (so adopting it later is a small step)
  but contributes no runtime behavior today; the bridge above is the sole reactivity path.
- **Coarse granularity by design.** `:ds/rev` bumps on **every** transaction, so *all* DataScript-reading
  subs are invalidated on any change. For this app's handful of such subs that is cheap and simpler than
  per-query invalidation.

## Alternatives considered

- **Use re-posh as intended.** Rejected (for now): more machinery and indirection than the three
  subscriptions need, and the reactivity becomes implicit. Kept as a dependency to ease a future switch
  if the relational UI grows.
- **Manual `d/listen!` per query.** Rejected: re-implements what one revision counter does, with more
  moving parts and no benefit at this scale.

## Trade-offs

- We trade **fine-grained, per-query invalidation** (what a full DataScript↔re-frame integration could
  offer) for an **explicit, trivially-correct, observable** bridge. Given the small number of
  DataScript-reading subscriptions, the coarse "recompute on any transaction" granularity is a
  negligible cost and a large simplicity win.
