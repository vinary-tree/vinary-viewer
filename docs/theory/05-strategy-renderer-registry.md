# Theory 05. Strategy Renderer

The content area is a Strategy selector: the active context determines which
renderer displays the document.

---

## 1. Strategy table

| Condition | Renderer |
|-----------|----------|
| No tabs | Empty watermark. |
| Active document has `:doc/error` | Error view. |
| Active URI is HTTP/HTTPS | Main-owned web view host. |
| `:doc/kind = "pdf"` | Main-owned PDF host. |
| `:doc/kind = "image"` | Image preview. |
| `:doc/kind = "mermaid"` | Renderer-side Mermaid SVG preview. |
| `:doc/kind = "source"` | Read-only CodeMirror source view. |
| Markdown tab with View Source enabled | Source view over cached Markdown text. |
| `:doc/kind = "directory"` | In-pane directory browser (`dir-view`). |
| `:doc/html` present | Markdown/text HTML body. |
| Otherwise | Rendering placeholder. |

The order matters. Errors dominate stale content; native views must be selected
before generic HTML; View Source is a per-tab override.

---

## 2. Markdown strategy

Markdown is rendered in the renderer with unified/remark/rehype:

```text
remark-parse
  -> remark-gfm
  -> remark-math
  -> remark-rehype
  -> rehype-slug
  -> rehype-highlight
  -> render metadata collection
  -> rehype-stringify
  -> MathJax SVG postprocess
  -> Mermaid SVG postprocess
  -> tree-sitter fenced-code postprocess
```

The result is HTML plus heading metadata and local asset paths. Raw HTML is parsed and
sanitized against GitHub's allowlist by `rehype-raw` + `rehype-sanitize`.

---

## 3. Source strategy

Source files use CodeMirror 6. web-tree-sitter highlighting is applied when a
bundled or user grammar matches the file by extension, filename, or pattern.
Non-Mermaid diagram-source extensions enter this strategy as source code.

---

## 4. Mermaid strategy

`.mmd` and `.mermaid` files use `vinary.ui.views/mermaid-view`, which calls
`vinary.renderer.mermaid/render-source` and writes the resulting SVG into a
scrollable renderer-owned host. Mermaid fenced blocks in Markdown are handled as
part of the Markdown strategy instead of as separate document kinds.

---

## 5. Native strategies

PDF and HTTP/HTTPS rendering use main-owned native views. The renderer mounts a
host element and sends bounds across IPC. Main owns the actual `WebContentsView`
and reports navigation/TOC information back where appropriate.

---

## 6. Directory strategy

A `:doc/kind = "directory"` document selects `vinary.ui.views/dir-view`, the in-pane
directory browser. Unlike Markdown (HTML written into a host node) or the native
strategies (a `WebContentsView`), the directory strategy is **pure Reagent over
data**: it reads the document's `:doc/entries` vector (sent by main, see
[features/16-directory-browser.md](../features/16-directory-browser.md)), sorts them
dirs-first/case-insensitive with `nav/sort-entries`, and renders an interactive
**detailed list** (name · size · modified). The highlighted entry is *derived*, not
stored, by `nav/effective-selected` (explicit selection → persisted trail child →
first entry), so directories take part in the same navigation history and
trail-memory model as every other document kind.

---

## References

- Gamma, E., Helm, R., Johnson, R., & Vlissides, J. (1994). *Design Patterns:
  Elements of Reusable Object-Oriented Software.* Addison-Wesley. ISBN
  978-0201633610.
- unified: <https://unifiedjs.com/>
- CodeMirror: <https://codemirror.net/>
