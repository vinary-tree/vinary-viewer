# 0002 — Render Markdown in the renderer, keep main a thin IO service

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** vinary-viewer maintainers

## Context

Markdown must be converted to HTML somewhere. There are two natural homes in an Electron app: the
**main** (Node) process or the **renderer** (Chromium) process. The chosen Markdown stack is the
**unified / remark / rehype** ecosystem, which is distributed as **ESM** and is browser-friendly. The
main process, meanwhile, already has a clear, narrow job: read files and watch them.

## Decision

**Render in the renderer.** The pipeline lives in `vinary.renderer.markdown/render`:

```text
unified → remark-parse → remark-gfm → remark-rehype → rehype-slug → rehype-highlight → rehype-stringify
```

It is a **pure transform** with no DOM access and returns a **`Promise<string>`** of HTML. It is invoked
from a re-frame **effect** (`:markdown/render` in `vinary.app.fx`), which `.then`s the result back into
the loop as `[:content/rendered path html]` and `.catch`es failures as `[:content/error …]`.

The **main process stays a thin IO service** (`vinary.main.service`): it reads the file
(`fs.readFileSync … "utf8"`) and sends the **raw text** to the renderer over `vv:content`; it does
**not** render.

## Consequences

- **The ESM remark stack bundles cleanly** in the `:browser` renderer build, where ESM is first-class.
  Keeping it out of the `:node-script` main build avoids ESM/CJS interop friction in Node.
- **Main is minimal and side-effect-at-the-edge.** Its responsibilities are read + watch + git; it has
  no rendering dependencies. This sharpens the hexagonal split (IO at the edges, logic in the renderer).
- **Rendering is async and composes with re-frame.** Because `render` returns a Promise consumed by an
  effect, the event handlers stay **pure** (effects are the only impure boundary), preserving
  re-frame's replayability and time-travel debugging.
- **Security analysis is localized.** Since rendering and the `innerHTML` write are both in the renderer,
  the XSS analysis (no `rehype-raw`, so raw HTML is not passed through) is a single, contained argument —
  see [security/threat-model.md](../security/threat-model.md).
- The renderer receives text and produces HTML; the **image** kind sends no text at all (the renderer
  loads images by `file://` path), and the **text** kind is HTML-escaped into `<pre>` rather than parsed.

## Alternatives considered

- **Render in main, send HTML.** Rejected: it would pull the ESM remark stack into the Node build
  (interop friction), thicken the main process, and move the `innerHTML`-sink security reasoning across
  the process boundary for no benefit. Main would also have to know about highlighting/slug concerns
  that are presentational.
- **A synchronous render in the view.** Rejected: blocking the UI thread on parsing large documents
  would hurt responsiveness, and it would make the event handlers impure. The async-fx approach keeps
  the loop pure and the UI responsive.

## Trade-offs

- We pay for a **round trip through the effect** (text in → Promise → HTML back as a second event)
  instead of rendering inline. In exchange we get pure handlers, a clean async boundary, a thin main
  process, and a clean bundling story for the ESM stack. For a previewer this latency is imperceptible
  and the architectural clarity is worth it.
