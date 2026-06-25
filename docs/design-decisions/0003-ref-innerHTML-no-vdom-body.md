# 0003 — Write the document body via a ref + `innerHTML` (no VDOM diffing)

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

The rendered Markdown is an **HTML string** produced by the unified pipeline
([ADR-0002](0002-render-markdown-in-renderer.md)). The UI is reagent (React 19). The naive way to show
the HTML in React is `dangerouslySetInnerHTML`, which makes React **own** the body subtree and re-diff
it on every render. For a document previewer the body can be large (long Markdown files, big code
blocks), and it changes wholesale on every live-refresh — there is no fine-grained, per-node update to
benefit from. We also need a feature, **in-page find**, that highlights text *over* the body without
React fighting it.

## Decision

Render the document body **imperatively**: a reagent **form-3** component (`vinary.ui.views/markdown-body`
via `reagent.core/create-class`) holds a **ref** to a plain `<div class="markdown-body">`, and on
`:component-did-mount` / `:component-did-update` writes the HTML once with
`set! (.-innerHTML node) html` (`set-inner!`). React renders the *wrapper* `<div>`; it never sees or
diffs the body's internals.

```clojure
;; vinary.ui.views (essence)
:component-did-mount  (fn [this] (set-inner! @node (second (r/argv this))))
:component-did-update (fn [this] (set-inner! @node (second (r/argv this))))
:reagent-render       (fn [_html] [:div.markdown-body {:ref (fn [el] (reset! node el))}])
```

## Consequences

- **Responsiveness on large documents.** Swapping `innerHTML` once per content change is a single DOM
  operation; React does no reconciliation of thousands of body nodes. Live-refresh repaints are cheap.
- **In-page find composes cleanly.** The find feature (`vinary.renderer.find`) paints matches with the
  **CSS Custom Highlight API** (`CSS.highlights`), which colors `Range`s **without mutating the DOM**.
  Because React is not diffing the body, the highlight overlay and the content never contend — the
  highlights survive content updates being re-applied by find, and the body is not clobbered by a React
  re-render mid-search. (See [ADR-0003 ↔ find] note below.)
- **The body is outside React's model.** This is the deliberate cost: per-node React features (event
  handlers on body elements, component state inside the body) are **not** available there. The body is a
  presentational, read-only HTML blob — which is exactly what a rendered document is.

## Alternatives considered

- **`dangerouslySetInnerHTML` (React-owned body).** Rejected: React would re-diff the whole body subtree
  on each change for no win (the HTML changes wholesale), and an externally-applied highlight or DOM
  tweak could be reverted by the next React render. The ref approach gives React strictly less to do.
- **Parse HTML to hiccup and render as a VDOM tree.** Rejected: it would reintroduce full per-node
  diffing (the thing we are avoiding), add a parsing step, and complicate find — all to gain per-node
  React features the document body does not need.

## Trade-offs

- We give up **per-node VDOM diffing of the document body** (and the per-node React features that come
  with it) in exchange for **constant-time body updates** and **clean composition with the
  non-DOM-mutating find highlighter**. Everything *around* the body (toolbar, tabs, tree, TOC, find bar)
  remains ordinary reagent/React, so we keep React where it helps and bypass it only for the one large,
  wholesale-replaced subtree.

> **ADR-0003 ↔ find.** This decision is *why* the find feature could choose the CSS Custom Highlight API
> (no DOM mutation): the body is a stable, React-untouched node, so painting `Range`s over it is safe.
> See [security/threat-model.md §5](../security/threat-model.md) for the `innerHTML` XSS analysis (raw
> HTML is not passed through because `rehype-raw` is not enabled).
