# 0016 — Compile the Electron main process with `:simple`, not `:advanced`

- **Status:** Accepted
- **Date:** 2026-06-28
- **Deciders:** vinary-viewer maintainers

## Context

shadow-cljs builds two app targets (`shadow-cljs.edn`): `:renderer` (Chromium, `:target :browser`) and
`:main` (Node, `:target :node-script`). In a `release` build, Closure's **`:advanced`** optimization renames
object **properties** unless an extern protects them. ClojureScript's externs **inference** auto-protects an
interop call `(.foo obj)` only when the receiver carries a `^js` type hint (or is otherwise inferred to be a JS
object); an un-hinted interop method that appears **exactly once** gets no extern and is renamed.

The `:renderer` build already opts out of advanced for this reason — it pins `:release {:compiler-options
{:optimizations :simple}}` with the comment *"advanced property-renaming breaks the re-frame / DataScript /
unified-remark interop."* The `:main` build set **no** optimization override, so `npm run release` compiled it
at the `:node-script` default, which property-renames.

That latent hazard shipped as a crash. The `View ▸ Developer Tools` handler in `vinary.main.shell`
(`shell.cljs`) reads the window's `webContents` through a getter whose **return value was untyped**, then calls
`(.toggleDevTools (wc))`. `.toggleDevTools` is the **only** call of that method in the codebase, so advanced
renamed it — in the shipped `dist/main/main.js` it became `wk().Xc()`. A native Electron `webContents` has no
`.Xc`, so opening DevTools threw `TypeError: wk(...).Xc is not a function` **in the main process** — but only in
a `release`/packaged build (`npm run dev` is non-optimized, so it never reproduced there). An audit found ≥5
more un-hinted main-process interop sites surviving **only** because a sibling `^js`-hinted call site happened
to add the same method name to the inferred externs — i.e. a whole class of one-refactor-away crashes.

## Decision

**Give the `:main` build the same `:simple` release optimization the `:renderer` build already uses**, and tag
the at-risk getters' return values `^js` as local, idiomatic defense-in-depth.

```clojure
;; shadow-cljs.edn
{:main {:target :node-script :main vinary.main.core/main :output-to "dist/main/main.js"
        :release {:compiler-options {:optimizations :simple}}}}
```

```clojure
;; src/vinary/main/shell.cljs — ^js return tag → un-hinted callers get an inferred extern
(defn- wc ^js [] (some-> ^js @win* .-webContents))
```

`:simple` still minifies and dead-code-eliminates; it simply does **not** rename properties, so every
Electron/Node interop method survives verbatim. Output size is irrelevant for a local Node script that is never
shipped over a network.

## Consequences

- **The DevTools crash is fixed**, and the entire un-hinted-interop crash class in the main process is
  neutralized at the root — not patched site by site.
- A **release-build regression gate** was added (`npm run test:electron:release`): it builds `release` and runs
  the Electron smoke against it, asserting DevTools opens with no main-process crash and that the dev-only
  re-frame-10x menu item is absent. The crash is advanced-optimization-only, so a dev-build smoke cannot catch
  it — the gate must use a release build. (The fix is also checkable at the bytecode level: the release
  `dist/main/main.js` now contains the literal `.toggleDevTools(`, not a renamed `.Xc`.)
- The two app builds are now **consistent** (both `:simple` for release), removing a surprising asymmetry.

## Alternatives considered

- **Hint every main-process interop site `^js` and keep `:advanced`.** Rejected: it is invasive, fragile (the
  next un-hinted call reintroduces the hazard), and buys nothing — `:advanced` size savings are meaningless for
  a local Node script.
- **Leave `:main` at `:advanced` and only hint the DevTools getter.** Rejected: it fixes one symptom and leaves
  the ≥5 other latent sites primed to crash on a future edit.

## Trade-offs

- Marginally larger (un-renamed) main-process output — immaterial for a local file.
- We rely on `:simple` continuing to preserve interop names (it does by definition — `:simple` never renames
  properties), rather than on per-site externs.

Cites: ADR-0001 (Electron 42 platform), the `:renderer` build's pre-existing `:simple` override
(`shadow-cljs.edn`), `vinary.main.shell/wc`.
