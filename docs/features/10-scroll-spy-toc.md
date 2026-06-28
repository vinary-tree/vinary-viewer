# Scroll-spy table of contents

**Status: Available now.**

The Contents panel lists headings for the active Markdown document and highlights
the section nearest the top of the content viewport as the user scrolls.

---

## 1. Current behavior

| Action | Behavior |
|--------|----------|
| Open Markdown with headings | Contents panel shows heading text and nesting level. |
| Scroll | Active heading updates at most once per animation frame. |
| Hover an entry | A full-text `title` tooltip shows the entry's heading text (so long, truncated entries stay readable). |
| Click an entry | The corresponding heading scrolls into view. |
| Navigate to HTTP content | The web view reports its own heading outline and active heading. |

Image, PDF, source, and plain text previews do not produce Markdown heading
metadata.

---

## 2. Metadata extraction

Markdown rendering returns a structured result:

```clojure
{:html "<article>...</article>"
 :toc [{:level 1 :text "Title" :id "title"}]
 :assets ["/abs/path/to/diagram.svg"]}
```

The TOC is collected during the HAST traversal in `vinary.renderer.markdown`.
That means the renderer no longer has to parse the final HTML string just to
discover headings. `:content/rendered` stores the vector as `:doc/toc`, and the
`:doc/toc` subscription reads that cached metadata.

Benefits:

| Benefit | Reason |
|---------|--------|
| Less duplicate parsing | The Markdown render already has the heading nodes. |
| Stable anchors | TOC ids come from the same slugged heading nodes as the rendered body. |
| Better scroll startup | TOC data is available with the HTML commit. |

---

## 3. Scroll-spy offsets

The live DOM is still needed for measurement. `vinary.renderer.toc` caches
heading offsets from the rendered document and uses binary search to find the
active heading for the current scroll position.

High-level algorithm:

```text
refresh offsets after render and figure sizing
on scroll:
  requestAnimationFrame once
  read scrollTop
  binary-search cached heading offsets
  dispatch active heading id
```

`requestAnimationFrame` coalesces bursts of scroll events to screen refreshes.
The binary search avoids scanning every heading on every frame.

---

## 4. SVG-heavy Markdown

Large embedded SVG diagrams can change layout after the HTML is first inserted.
The current sequence handles that:

1. Insert rendered Markdown HTML.
2. Measure local SVG figures and apply inline sizing only when dimensions change.
3. Wait for the sizing promise to settle.
4. Refresh heading offsets.
5. Apply pending scroll restore.

This prevents the active TOC entry and restored scroll position from fighting
with late figure layout.

---

## 5. Web view TOC

HTTP/HTTPS documents are displayed in a main-owned web view. Its preload script
collects headings, caches offsets, and reports active heading changes back to
the renderer through `vv:web-toc` and `vv:web-active-heading`. The renderer uses
the same Contents panel model for both Markdown and web content.

---

## 6. Related decision

See [ADR-0010](../design-decisions/0010-bounded-content-retention-and-render-metadata.md)
for the render-metadata and scroll-stability rationale.
