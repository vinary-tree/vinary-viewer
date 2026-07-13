# Lint and coding conventions

This page documents the one automated style gate — [`test/lint.js`](../../test/lint.js) —
and the human conventions it does not (yet) enforce: the namespace↔file mapping
rule, the ClojureScript and JavaScript style rules, and the renderer-never-touches-
the-filesystem discipline that is half compiler-enforced and half review-enforced.

> **Audience.** Read this before your first commit, and again when adding a
> `--vv-*` theme token, a new theme, or a new JavaScript file to the repository.

---

## 1. `test/lint.js` — the three checks

`test/lint.js` is a small CommonJS script (`'use strict'`) with **no
dependencies** beyond Node built-ins. It performs three checks and exits non-zero
on any failure. Run it directly:

```bash
node test/lint.js
```

> **Naming discrepancy (finding).** The script's own header comment says it is
> "run by `npm run lint`" and "gates CI / `npm run check`". **Neither `lint` nor
> `check` exists as an npm script** in `package.json`, and there is no CI config in
> the repository (see [08-ci-and-validation-discipline.md](08-ci-and-validation-discipline.md)).
> The real, working invocation is `node test/lint.js`, as documented in `AGENTS.md`
> and [`usage/02-installation-and-build.md`](../usage/02-installation-and-build.md).
> A proposed CI matrix and a convenience `lint` script would make the header
> accurate; until then, use the direct command.

### 1.1 Check 1 — every JavaScript source parses

The script runs `node --check` on an **explicit allowlist** of JavaScript files —
it does not glob — so a new JS file is only linted once it is added to the
`jsFiles` array. The list covers the legacy sidebar/patch files
(`src/sidebar.js`, `src/patch-*.js`), the main-process content service
(`src/vinary/main/content_service.js`), the pdf loader, the native
mouse-button addon, every smoke harness, every `scripts/*.mjs` and
`scripts/screenshots.cjs`, and the extension polyfill. A parse error prints the
child `stderr` and increments the failure count:

```javascript
for (const f of jsFiles) {
  try { execFileSync(process.execPath, ['--check', path.join(root, f)]); log(true, `parses: ${f}`); }
  catch (e) { log(false, `parse error: ${f}\n${(e.stderr && e.stderr.toString().trim()) || e.message}`); }
}
```

Because the list is explicit, **remember to add a new JavaScript file to
`jsFiles`** when you create one, or it is silently unlinted.

### 1.2 Checks 2 & 3 — CSS brace balance and theme-token completeness, on both surfaces

vinary-viewer has **two CSS surfaces**, each with its own class system and its own
theme directory, and lint checks **both**:

| Surface | Stylesheet | Themes dir | Class prefix | Status |
|---------|-----------|-----------|--------------|--------|
| The standalone v0.2/0.3 app — **the shipped product** | `resources/public/css/app.css` | `resources/public/css/themes/` | `.vv-*` | Linked by `index.html`. |
| The legacy v0.1.0 vmd-patch sidebar | `src/style.css` | `src/themes/` | `.vmd-*` | Injected into vmd's renderer; kept for users still on that tool. |

Both surfaces share the same `--vv-*` design-token palette. For each surface, lint
runs two checks:

- **Check 2 — brace balance.** Count `{` and `}` and assert they are equal. This
  is a cheap catch for a truncated or unbalanced rule block:

  ```javascript
  const open = (css.match(/{/g) || []).length, close = (css.match(/}/g) || []).length;
  log(open === close, `${t.css} braces balanced (${open} open / ${close} close)`);
  ```

- **Check 3 — theme-token completeness.** Collect every `--vv-*` variable the
  stylesheet *references* via `var(--vv-…)`, subtract the ones the stylesheet
  *defines itself* in `:root` (the runtime-overridable font defaults), and assert
  every theme file in the surface's themes directory defines **all** the remaining
  "themed" variables. A theme that references a token no theme defines is a
  runtime rendering bug (an unresolved `var()` collapses to nothing), caught here:

  ```javascript
  const used = new Set([...css.matchAll(/var\(\s*(--vv-[a-z0-9-]+)/g)].map(m => m[1]));
  const selfDefined = new Set([...css.matchAll(/(--vv-[a-z0-9-]+)\s*:/g)].map(m => m[1])); // :root defaults
  const themed = [...used].filter(v => !selfDefined.has(v));
  // …for each theme file: assert it defines every var in `themed`, else MISSING: …
  ```

The **term-before-use rule for tokens**: the app-surface's `--vv-*` variables are
the canonical set, catalogued in [`reference/css-variables.md`](../reference/css-variables.md).
Adding a token means (a) defining it in **every** theme under
`resources/public/css/themes/`, or (b) giving it a `:root` default in `app.css` if
it is a runtime-overridable font default. Lint enforces (a); skipping a theme is a
hard failure. (That app-surface coverage was previously unchecked — linting the
shipped surface, not just the legacy one, closed the gap.)

---

## 2. The namespace ↔ file mapping rule

ClojureScript maps a namespace's kebab-case segments to an **underscored** file
path: hyphens in a namespace segment become underscores in the corresponding file
and directory name. This is a hard rule the compiler enforces, so a mismatch is a
build error, not a lint warning. The canonical example from `AGENTS.md`:

```text
namespace   vinary.input.keymaps-registry
file        src/vinary/input/keymaps_registry.cljs
                                 ^^^^^^^^^^^^^^^^^^  kebab-case ns → underscored file
```

The same mapping is why the test namespaces `vinary.ir.frontend.log-stream-test`
live in `test/vinary/ir/frontend/log_stream_test.cljs`. When you rename a
namespace, rename its file to match, keeping the directory structure aligned with
the dotted segments.

---

## 3. ClojureScript style

The house ClojureScript style, from `AGENTS.md`:

- **Two-space indentation.** Idiomatic ClojureScript throughout.
- **kebab-case** for vars and functions (`retained-file-paths`, `apply-posts`),
  matching the namespace segments.
- **Effects at the edge.** The renderer operates on plain JSON/EDN data;
  privileged/side-effecting work is pushed to the main process across the IPC seam
  (see §5 and [theory/04](../theory/04-hexagonal-and-ipc-mediator.md)).
- **Regression tests over coverage gates.** There is no numeric coverage
  threshold; the expectation is to add a regression test for navigation,
  keybindings, rendering helpers, and IPC-facing behavior — placed in the DOM-free
  `:node-test` build (see [03-test-strategy.md](03-test-strategy.md)).

> This project does **not** follow the Rust `unwrap`/`expect` convention some
> sibling f1r3fly projects use — there is no Rust here. The idioms above are the
> ones that apply to a ClojureScript / JavaScript codebase.

---

## 4. JavaScript style

The JavaScript in the repository — the main-process content service, the native
addon, the smoke harnesses, and the sync scripts — follows a minimal, explicit
style, from `AGENTS.md`:

- **CommonJS** modules with **`'use strict';`** at the top of every file. (The
  `scripts/*.mjs` sync scripts are the exception: they are native ES modules,
  because they are standalone Node tools, not part of a CommonJS require graph.)
- **Minimal dependencies.** The harnesses and scripts lean on Node built-ins
  (`fs`, `path`, `crypto`, `assert`, `child_process`) and avoid pulling in test
  frameworks or utility libraries. `test/lint.js` and the `check-*.mjs` scripts
  have zero third-party dependencies.

---

## 5. The renderer never touches the filesystem

The most important standing discipline is that **the renderer never performs
filesystem or privileged IO directly**; it reaches every such capability across
the `window.vv` contextBridge seam exposed by
[`resources/preload.js`](../../resources/preload.js). This is enforced at two
levels:

1. **Compile-time (the build).** The `renderer` build resolves `fs`,
   `fs/promises`, `path`, and `url` to `false` (see
   [01-build-system.md §4.2](01-build-system.md#42-renderer--browser-stubs-fs--path--url-to-false)),
   so an accidental `require("fs")` in renderer code compiles to an empty object
   and fails immediately rather than leaking a capability.

2. **Review-time (the convention).** `AGENTS.md` states it as a rule: "Keep
   renderer filesystem access behind the existing `contextBridge`/IPC boundary;
   the renderer should operate on plain JSON/EDN data." A code review rejects a
   renderer namespace that reaches for Node IO instead of dispatching an event
   that a main-process effect handles.

The two halves are complementary: the build stub makes the *mistake* fail loudly;
the convention keeps the *design* clean so the mistake is not made. Together they
uphold the ports-and-adapters boundary the theory pillar describes
([theory/04](../theory/04-hexagonal-and-ipc-mediator.md)).

---

## 6. References and see also

- [`test/lint.js`](../../test/lint.js) — the gate this page documents.
- `AGENTS.md` (repository root) — the coding-style and naming source.
- [`reference/css-variables.md`](../reference/css-variables.md) — the `--vv-*`
  token catalogue Check 3 depends on.
- [01-build-system.md](01-build-system.md) — the compile-time `fs`/`path`/`url`
  stub that pairs with §5.
- [03-test-strategy.md](03-test-strategy.md) — where regression tests live.
- [theory/04-hexagonal-and-ipc-mediator.md](../theory/04-hexagonal-and-ipc-mediator.md)
  — the boundary §5 enforces.
