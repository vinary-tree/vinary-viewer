# 0010 - Bound content retention to tab histories and emit Markdown render metadata

- **Status:** Accepted
- **Date:** 2026-06-26
- **Deciders:** vinary-viewer maintainers

## Context

vinary-viewer now models a tab as a view with its own history stack in `vinary.app.nav`. A *retained
file* is a local file URI that is still reachable from at least one open tab history entry. A file can be
retained even when it is not the currently visible URI, because Back/Forward must still restore it
without losing live-refresh behavior.

The older live-refresh contract treated closing the current tab URI as the main watcher lifetime
boundary. That was too narrow: navigating `a.md -> b.md`, then closing the tab, left `a.md` outside the
visible tab strip but still eligible to remain watched unless every history entry was considered.

Markdown rendering also produced only an HTML string. The renderer then parsed that HTML again to
derive the table of contents and local media paths. Those extra parses were small for short documents,
but they scaled linearly with rendered HTML size and repeated work the HAST tree already contained.

![Bounded content retention and render metadata](../diagrams/component-content-retention.svg)

*Figure - source: [`docs/diagrams/component-content-retention.puml`](../diagrams/component-content-retention.puml).*

## Decision

Use the tab histories as the ownership boundary for content and watcher retention.

- `vinary.app.nav/retained-file-paths` computes the retained local-file set from every open tab history.
- `vinary.app.events` syncs that set to main with `:vv/sync-retained-files` after navigation, tab close,
  history traversal, and HTTP navigation changes.
- `vinary.main.service/sync-retained!` closes file watchers and releases media watcher ownership for
  paths removed from the retained set.
- `vinary.app.ds/retract-unretained-tx` evicts DataScript content-cache entities whose `:doc/path` is no
  longer retained.

Also make Markdown rendering return structured metadata:

```clojure
{:html "... rendered document ..."
 :toc [{:level 1 :text "Title" :id "title"}]
 :assets ["/absolute/path/to/diagram.svg"]}
```

`vinary.renderer.markdown/render` now collects `:toc` and `:assets` during the existing HAST traversal,
after URL rewriting and image wrapping. `vinary.app.subs/:doc/toc` reads stored `:doc/toc` instead of
parsing HTML in a subscription.

Finally, `vinary.renderer.toc` caches measured heading offsets after render and resize. During scroll,
it uses binary search over that cache. The scroll-time complexity changes from `O(h)` layout reads per
animation frame, where `h` is the number of headings, to `O(log h)` plain data comparisons per frame
after an `O(h)` measurement pass on render/resize.

## Consequences

- Watcher lifetime is now tied to actual reachability from open tab histories, not just the visible URI.
- Closing a tab that visited many local files releases every unshared watcher and media owner associated
  with that tab history.
- DataScript remains a bounded content cache; it is not an unbounded archive of every file visited.
- Markdown TOC and asset extraction no longer require separate `DOMParser` passes over the final HTML.
- Scroll-spy no longer queries headings and calls `getBoundingClientRect` on every scroll frame.

## Alternatives considered

- **Keep explicit `vv:close path` calls.** Rejected because a single path close call cannot express the
  ownership set created by per-tab history stacks.
- **Watch only the visible active file.** Rejected because Back/Forward would restore stale content after
  editing an inactive but retained history entry.
- **Continue parsing rendered HTML for TOC/assets.** Rejected because the render pipeline already has the
  structured HAST tree and can collect the same facts before stringification.

## Trade-offs

The renderer sends one extra retained-set message after navigation changes. In return, main has a single
authoritative ownership set, cleanup is deterministic, and memory use is bounded by open tab histories
instead of by all files ever visited during the session.
