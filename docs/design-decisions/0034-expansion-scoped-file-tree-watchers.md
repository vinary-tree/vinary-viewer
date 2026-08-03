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
