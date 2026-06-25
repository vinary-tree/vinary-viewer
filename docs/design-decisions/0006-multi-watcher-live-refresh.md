# 0006 — One `chokidar` watcher per open path for live-refresh

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

The defining feature is **live-refresh**: when you edit an open file, the preview updates without a
manual reload. That requires the main process to watch the open files and re-send their content on
change. Two questions: **what to watch**, and **how to detect a save reliably** across the many ways
editors write files.

A subtlety: many editors do an **atomic save** — write a temporary file, then rename it over the
original. To a file watcher that often looks like the original being **removed and re-added** rather
than changed, and the write may land in several syscalls.

## Decision

Run **one `chokidar` watcher per open path**, tracked in a `path → watcher` atom, and listen for both
`change` **and** `add`, with write-coalescing:

```clojure
;; vinary.main.service (essence)
(defonce ^:private watchers (atom {}))   ; path -> chokidar watcher

(defn open! [wc path]
  (send-content! wc path)
  (send-tree! wc path)
  (when-not (get @watchers path)                                  ; watch once per path
    (let [w (watch path (clj->js {:ignoreInitial true
                                  :awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}}))]
      (.on w "change" (fn [_] (send-content! wc path)))
      (.on w "add"    (fn [_] (send-content! wc path)))           ; atomic-save re-create
      (swap! watchers assoc path w))))

(defn close! [path]
  (when-let [w (get @watchers path)] (.close w) (swap! watchers dissoc path)))
```

- `:ignoreInitial true` — do not fire on the initial `add` when the watch starts (we already sent the
  content via `send-content!`).
- `:awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}` — wait until the file size has been
  stable for ~80 ms (polling every 20 ms) before firing, so a multi-syscall save coalesces into **one**
  re-send.
- Listening to **both** `change` and `add` catches in-place writes *and* atomic-save re-creates.

The watcher's lifecycle is tied to the tab: `open!` starts it (once), and `close!` (driven by
`[:tab/close]` → `:vv/close`) **stops and dissociates** it.

## Consequences

- **Precise.** The watcher set is exactly the set of open files — never a whole directory, never files
  you are not viewing. No spurious updates from unrelated file changes.
- **Robust to editor behavior.** The `change`+`add` pair plus `awaitWriteFinish` makes live-refresh work
  across in-place saves and atomic-save editors, and avoids firing mid-write (which could read a
  truncated file).
- **Clean teardown.** Closing a tab stops its watcher at the exact right moment; there are no leaked
  watchers and no need for directory-wide bookkeeping. Re-opening rebinds a fresh watcher.
- **No duplicate watchers.** The `(when-not (get @watchers path) …)` guard means opening an
  already-open file re-sends content but does not stack a second watcher.

## Alternatives considered

- **A single recursive directory watcher.** Rejected: it would fire on **every** file in the tree
  (including files you are not previewing), require filtering, and complicate teardown (when do you stop
  watching a directory?). It also scales poorly on large repositories. Per-path watchers are both
  cheaper and more precise for "watch what's open."
- **Listen to `change` only.** Rejected: it would miss atomic saves (which surface as `add`), so many
  editors' saves would not trigger a refresh — defeating the feature.
- **No `awaitWriteFinish` (fire immediately).** Rejected: risks reading a partially written file and/or
  firing multiple times for one save. Coalescing gives one clean re-send per save.

## Trade-offs

- We accept **N watchers for N open files** (a little more per-file bookkeeping) instead of one
  directory watcher. For a previewer that typically has a handful of tabs open, N is tiny, and the
  precision (only watch what's shown), correctness (atomic saves), and clean lifecycle are well worth
  it. See also [features/01-live-refresh.md](../features/01-live-refresh.md) and
  [usage/06-troubleshooting.md §2](../usage/06-troubleshooting.md).
