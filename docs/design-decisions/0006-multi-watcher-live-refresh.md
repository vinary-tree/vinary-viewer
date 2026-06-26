# 0006 — One `chokidar` watcher per retained path for live-refresh

- **Status:** Accepted, refined by [ADR-0010](0010-bounded-content-retention-and-render-metadata.md)
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

The defining feature is **live-refresh**: when you edit a retained local file, the preview updates
without a manual reload. That requires the main process to watch retained files and re-send their
content on change. Two questions: **what to watch**, and **how to detect a save reliably** across the
many ways editors write files.

A **retained file** is a local file still reachable from at least one open tab history. ADR-0010
added that ownership boundary after the app moved from a one-path tab model to browser-like per-tab
histories.

A subtlety: many editors do an **atomic save** — write a temporary file, then rename it over the
original. To a file watcher that often looks like the original being **removed and re-added** rather
than changed, and the write may land in several syscalls.

## Decision

Run **one `chokidar` watcher per retained path**, tracked in a `path → watcher` atom, and listen for
both `change` **and** `add`, with write-coalescing:

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

(defn sync-retained! [wc paths]
  (let [old @retained-paths
        new (retained-path-set paths)]
    (reset! retained-paths new)
    (doseq [path (set/difference old new)]
      (unwatch-file! path))))
```

- `:ignoreInitial true` — do not fire on the initial `add` when the watch starts (we already sent the
  content via `send-content!`).
- `:awaitWriteFinish {:stabilityThreshold 80 :pollInterval 20}` — wait until the file size has been
  stable for ~80 ms (polling every 20 ms) before firing, so a multi-syscall save coalesces into **one**
  re-send.
- Listening to **both** `change` and `add` catches in-place writes *and* atomic-save re-creates.

The watcher lifecycle is tied to the retained-file set: `open!` starts a path watcher once, and
`sync-retained!` closes watchers for paths no open tab history can still reach.

## Consequences

- **Precise.** The watcher set is exactly the set of retained files — never a whole directory, never
  files no tab history can reach. No spurious updates from unrelated file changes.
- **Robust to editor behavior.** The `change`+`add` pair plus `awaitWriteFinish` makes live-refresh work
  across in-place saves and atomic-save editors, and avoids firing mid-write (which could read a
  truncated file).
- **Clean teardown.** Closing or navigating tabs updates the retained set; any watcher not reachable
  from an open history is released without directory-wide bookkeeping.
- **No duplicate watchers.** The `(when-not (get @watchers path) …)` guard means opening an
  already-open file re-sends content but does not stack a second watcher.

## Alternatives considered

- **A single recursive directory watcher.** Rejected: it would fire on **every** file in the tree
  (including files you are not previewing), require filtering, and complicate teardown (when do you stop
  watching a directory?). It also scales poorly on large repositories. Per-path watchers are both
  cheaper and more precise for "watch what the open tab histories retain."
- **Listen to `change` only.** Rejected: it would miss atomic saves (which surface as `add`), so many
  editors' saves would not trigger a refresh — defeating the feature.
- **No `awaitWriteFinish` (fire immediately).** Rejected: risks reading a partially written file and/or
  firing multiple times for one save. Coalescing gives one clean re-send per save.

## Trade-offs

- We accept **N watchers for N retained files** (a little more per-file bookkeeping) instead of one
  directory watcher. For a previewer that typically has a handful of tabs open, N is tiny, and the
  precision (only watch what's shown), correctness (atomic saves), and clean lifecycle are well worth
  it. See also [features/01-live-refresh.md](../features/01-live-refresh.md) and
  [usage/06-troubleshooting.md §2](../usage/06-troubleshooting.md).
