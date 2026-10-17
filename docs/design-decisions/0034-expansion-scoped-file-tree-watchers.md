# 0034 — Scope file-tree watchers to expanded directories and refresh before opening

- **Status:** Accepted
- **Date:** 2026-08-03
- **Deciders:** vinary-viewer maintainers

## Context

The Files tree was a snapshot sent when a document opened. Creating, deleting, or renaming a file
afterward left that snapshot stale. Recursively watching every project root would fix correctness at
the cost of subscriptions and events from directories the user was not browsing. It would also mix
navigation membership changes with ADR-0006's retained-document content refresh.

Native `<details>` introduced a second problem: opening immediately and refreshing asynchronously
briefly rendered stale children. Expansion has to commit the new subtree and the open state together.

## Decision

The renderer owns persistent disclosure intent as `[project-root absolute-directory]` scopes in
`vinary.app.tree-state`. `vinary.ui.tree` renders controlled `<details>` elements. A direct user
expansion cancels the browser's native toggle, invokes `vv:tree-refresh`, and commits the scoped tree
payload and open scope in one `:tree/expand-ready` event. A failed refresh leaves the directory closed.
Pending scopes are excluded from effective expansion, so a concurrent filter or active-file reveal cannot
render a direct-click target open before that reply commits.

Only *effective* expansion scopes are sent through `syncTreeExpanded`: every ancestor must also be
open, the project must be visible under the current filter, and the Files view must be mounted.
`vinary.main.service` reconciles those scopes into shared, shallow (`depth 0`) Chokidar watchers.
Collapse, ancestor collapse, tab switch, sidebar hide, project removal, and window destruction release
ownership. A ready-time reconciliation closes the listing-to-watcher startup race.

Watchers react to structural `add`, `unlink`, `addDir`, and `unlinkDir` events, plus `.gitignore`
changes. Ordinary file-content saves remain ADR-0006's concern and do not re-list the tree. Automatic
and nested manual refreshes send root-relative scoped payloads; the renderer replaces only that
subtree. Project **Refresh** sends a full root, while the Files-tab **Refresh All** refreshes every
project currently retained in Files. Returning to Files refreshes remembered open project roots before
remounting the tree.
Late manual-refresh replies are applied only to projects that are still present, so **Remove from Files**
cannot be undone by an older in-flight request.

Main treats renderer paths as requests, not authority: a refresh root must already have been offered
to that window and still be visible, and a requested directory must remain lexically beneath it.

[ADR-0035](0035-authenticated-remote-daemon-events.md) reuses this ownership model for `ssh://` and
`sftp://`: the target daemon becomes the shallow-watcher owner and the source transports the same scoped
payloads into the existing renderer protocol.

## Consequences

- Expanded directories track file/directory add, delete, and rename without recursively watching
  unopened branches.
- A directory's first open frame contains its refreshed children; there is no stale-open flash.
- Collapsed or hidden trees intentionally become stale and reconcile when expanded or restored.
- Empty directories remain absent because the tree is still derived from file paths.
- One expanded path means one shallow OS watcher shared across windows, with per-window ownership.
- Each newly created watcher performs one extra ready-time listing to eliminate a startup race.

## Alternatives considered

- **One recursive watcher per root.** Rejected because large projects produce work for unopened
  branches and duplicate content-save notifications.
- **Watch every rendered directory.** Rejected because collapsed disclosures do not need freshness.
- **Open first, refresh later.** Rejected because it visibly renders stale state.
- **Polling.** Rejected because it adds idle work and still leaves a freshness interval.
- **Track empty directories independently.** Rejected to preserve the existing file-derived model.

## Trade-offs

Expansion now crosses an asynchronous IPC gate, so a slow or unreadable directory opens later (or
stays closed) instead of toggling immediately. The ownership protocol and controlled disclosure state
add bookkeeping, in exchange for bounded subscriptions and an atomic visible result.

## Amendment — 2026-10-17: asynchronous listings, one per root at a time, applied in listing order

**Context.** Every listing behind this ADR — an open's, an expansion's, a Refresh's, and each watcher's
refresh — ran `git` with `execFileSync` on the main process, and each watcher re-listed the whole repository
for its own scope. A Files filter forces hundreds of directories open; their watchers' ready-time refreshes
expired together, and about twenty synchronous whole-repository listings ran back to back. Under machine-wide
load that held main's event loop for 2.4 s while typing in the filter, and every IPC reply waited behind it
([docs/scientific/10 §11](../scientific/10-input-latency-experiments.md)).

**Decision.**

1. **Main runs no git synchronously.** `vinary.main.tree-listing` spawns git asynchronously (argv array, no
   shell, stdin and stderr ignored, 64 MiB and 120 s bounds), and the synthetic walk
   ([ADR-0030](0030-fallback-project-roots.md)) is asynchronous too. A gate in `test/git-tree-smoke.js` keeps any
   synchronous child process out of main's ClojureScript, and find-e2e / tree-e2e count main's git processes.
2. **One listing per root at a time.** Every listing request goes to a listing FLIGHT keyed by root
   (`vinary.main.listing-flight`, pure). At most one listing per key runs, and a request **never joins** the
   running listing — that listing may have started before the change that prompted the request. Requests made
   meanwhile share ONE trailing listing, so a burst costs at most two listings per root, and every request is
   served by the first listing launched after it.
3. **Watcher refreshes are delivered together.** A watcher's debounced refresh requests a listing of its root;
   when the listing completes, every refresh it serves is delivered at once. For a git root each owner receives
   its outermost due scopes, projected from the one listing in one pass; for a synthetic root, every due directory
   (each walked from itself), shortest first. A per-watcher generation drops refreshes of watchers that stopped
   or were replaced meanwhile.
4. **Entries are applied in listing order.** Each entry carries its listing's stamp, `:listing {:epoch :n}`, and
   the renderer's project model keeps the stamp that last wrote each scope, so an entry arriving after a newer
   one — a Refresh All reply that waited for its slowest root, a target daemon's reply trailing its own push —
   never overwrites it: the result is the one applying every entry in stamp order would give.
5. **Removal stays authoritative.** A listing begun before its root was removed from Files is not delivered
   (`removed-since?`, a per-window roots-sync generation); a Files restore no longer re-adds a project removed
   while it was in flight.

**Alternatives considered.**

- **Join the running listing** (as ADR-0046's content reads join a running read). Rejected: a tree refresh is
  prompted by a change, and the running listing may predate it — the refresh would show the old tree.
- **A per-root debounce.** Rejected: a steady stream of changes keeps postponing it (starvation), and it delays
  even an isolated change.
- **Cache listings.** Rejected: a cached listing is stale by construction once a change is reported, and a
  monorepo's listing is megabytes held per root.
- **A git utility process.** Rejected for now: it needs the same per-root serialization, plus a process boundary
  to maintain; the measured residual cost of asynchronous `spawn` on main is under 1 % of the phase.
- **Order entries by arrival.** Rejected: aggregated and forwarded replies arrive late by design.

**Consequences.** A tree arrives one asynchronous listing after its document's content (the renderer already
handled either order). The ownership checks this ADR describes now also run when a listing completes, since a
window, a scope or a project can go away while it runs. The cost of the bursts this amendment coalesces is
measured in docs/scientific/10 §11, before and after.
