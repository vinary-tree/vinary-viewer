# Vinary Viewer — Feature Showcase

One document that exercises the renderer: **GitHub-Flavored Markdown**, tables,
tree-sitter–highlighted code, math, task lists, and an embedded diagram — all
live, all themed.

> **Live refresh.** Save this file in your editor and the preview repaints in
> place, preserving your scroll position, tabs, history, and theme.

## Supported at a glance

| Capability | Engine                       | Status |
|------------|------------------------------|:------:|
| Markdown   | unified · remark · rehype    |   ✅   |
| Math       | MathJax (SVG)                |   ✅   |
| Diagrams   | Mermaid                      |   ✅   |
| Source     | tree-sitter (46 grammars)    |   ✅   |
| PDF        | pdf.js (in-renderer)         |   ✅   |

## Tree-sitter code, highlighted

```rholang
new chan, ack in {
  contract chan(@msg, return) = {
    return!(msg) | @"stdout"!(msg)
  } |
  chan!("hello, vinary", *ack) |
  for (@reply <- ack) { Nil }
}
```

## Math, inline and display

Euler's identity, $`e^{i\pi} + 1 = 0`$, sits inline at the baseline; the Gaussian
integral stands on its own:

$$
\int_{-\infty}^{\infty} e^{-x^2}\,dx = \sqrt{\pi}
$$

## A checklist

- [x] Render GFM tables and task lists
- [x] Highlight fenced code with tree-sitter
- [x] Typeset math with MathJax
- [ ] Run out of things to preview

## An embedded diagram

![Architecture](../../figures/architecture.svg)

*Figure — the vinary-viewer process and build topology (rendered from D2).*
