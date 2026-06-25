# 0008 — Split state: DataScript SSOT for docs/tabs, app-db for ephemeral UI

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

The app has two very different kinds of state:

1. **Document/tab data** — relational, queryable, and updated by an external source (the file watcher):
   each open file's `:doc/path`, `:doc/kind`, `:doc/text`, `:doc/html`, `:doc/error`, `:doc/open?`,
   `:doc/order`. There can be many docs; tabs are "the open docs, ordered."
2. **Ephemeral UI state** — which tab is active, the current theme, the find box's visibility/query, the
   navigation history stack, the active TOC heading, keybinding/palette state. This is plain,
   per-session UI state.

The defining feature, **live-refresh**, pushes new document data at any time. If document data and UI
state lived in one blob, a live content update could accidentally stomp on UI state (scroll position,
active tab, find) — the very thing live-refresh must preserve.

## Decision

**Two stores, each suited to its data, joined by the bridge from
[ADR-0004](0004-ds-rev-bridge-vs-re-posh.md):**

- **DataScript** (`vinary.app.ds`) is the **single source of truth for documents/tabs**. One open file =
  one `:doc` entity keyed by `:doc/path` (`schema = {:doc/path {:db/unique :db.unique/identity}}`; the
  other `:doc/*` attributes are schema-less scalars). Tabs are derived by query
  (`ds/open-docs` → docs with `:doc/open? true`, ordered by `:doc/order`).
- **re-frame `app-db`** (`vinary.app.db`) holds **only ephemeral UI**: `:ui {:active-path, :theme,
  :active-heading, :sidebar-visible?, :tree-selected, :history, :find, :input, :palette}` plus the
  `:ds/rev` revision counter.

A content update transacts into **DataScript** and **never** writes UI state. The active-path, scroll,
find, and history live in `app-db`, untouched by live-refresh.

## Consequences

- **Live-refresh cannot disturb the UI.** Because `:content/received` only transacts `:doc/*` data, your
  scroll position, active tab, find box, and history survive every re-render. This separation is *the
  mechanism* behind "edit your file and keep your place." (`vinary.app.events` comments call this out
  explicitly.)
- **The right tool for each job.** Documents get DataScript's relational queries (e.g. "all open docs
  ordered," "the entity for this path," "the next order index"); UI gets re-frame's ergonomic,
  replayable `app-db`.
- **The UI is replayable / time-travelable.** Since UI state is ordinary `app-db`, re-frame-10x can
  time-travel it; DataScript transactions are observable via the bridge. Debugging is tractable.
- **A bridge is required.** The cost of two stores is that re-frame must be told when DataScript changed
  — handled minimally by the `:ds/rev` bridge ([ADR-0004](0004-ds-rev-bridge-vs-re-posh.md)).

## Alternatives considered

- **One store (everything in `app-db`).** Rejected: no relational queries for documents, and — critically
  — live content updates and UI state would share one structure, making it easy to clobber scroll/active
  state on refresh. We would have to hand-roll the very isolation the split gives for free.
- **One store (everything in DataScript).** Rejected: modeling transient UI (find query text, palette
  selection index, timeout ids) as datoms is awkward and loses re-frame's straightforward `app-db`
  ergonomics and 10x tooling for UI flows.

## Trade-offs

- We accept **two stores plus a bridge** instead of one unified store. The bridge is tiny
  ([ADR-0004](0004-ds-rev-bridge-vs-re-posh.md)), and in return we get relational document queries, and
  a hard guarantee that live-refresh never touches UI state. For an app whose headline feature is
  "update content while preserving the user's place," that guarantee is worth the extra store.

> See [theory/02-state-model-datascript-app-db.md](../theory/02-state-model-datascript-app-db.md) for the
> full state model and [architecture/04-state-schema-reference.md](../architecture/04-state-schema-reference.md)
> for the attribute/schema reference.
