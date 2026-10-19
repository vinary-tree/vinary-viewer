# Lint and coding conventions

This page documents the automated style and documentation gate — [`test/lint.js`](../../test/lint.js) —
and the human conventions it does not (yet) enforce: the namespace↔file mapping
rule, the ClojureScript and JavaScript style rules, and the renderer-never-touches-
the-filesystem discipline that is half compiler-enforced and half review-enforced.

> **Audience.** Read this before your first commit, and again when adding a
> `--vv-*` theme token, a new theme, a new JavaScript file, or a component that
> renders an HTML string.

---

## 1. `test/lint.js` — the seven checks

`test/lint.js` is a small CommonJS script (`'use strict'`) with **no
dependencies** beyond Node built-ins. It performs seven checks and exits non-zero
on any failure. Run it with:

```bash
npm run lint          # = node test/lint.js
```

`npm test` runs it **first** (`npm run lint && shadow-cljs compile test && …`), so a
lint failure fails the suite before anything compiles.

> **History.** The header comment long claimed "run by `npm run lint`" while no such
> script existed (`node test/lint.js` was the only invocation). The `lint` script now
> exists and `npm test` chains it, so the claim is true. There is still no CI config
> in the repository (see [08-ci-and-validation-discipline.md](08-ci-and-validation-discipline.md)).

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

### 1.3 Check 4 — the reference catalogs match the code, in both directions

Four reference pages promise to be **exhaustive**, and all four had drifted silently:
before this check existed, 159 re-frame registrations had no reference row (99 were
never mentioned anywhere), 7 rows described retired ids, 3 IPC channels were
uncatalogued, the removed `vv:load-pdf-bytes` channel was still listed, 30
namespaces and 2 JS modules had no row in the namespace reference, and 12 `--vv-*`
tokens had none in the CSS-variables reference. Check 4 makes the drift a build
failure:

| Sub-check | Code side | Doc side | Fails when |
|-----------|-----------|----------|------------|
| **4a** re-frame | every `(rf/reg-event-db\|reg-event-fx\|reg-event-ctx\|reg-fx\|reg-cofx\|reg-sub <id>` in `src/vinary/**/*.cljs` | first-column table rows of [`reference/events-effects-subs.md`](../reference/events-effects-subs.md): events in §1, effects/cofx in §2, subscriptions in §3 | a registration has no row **in its kind's section**, or a row names an id not registered for that kind |
| **4b** IPC | every `'vv:…'` / `"vv:…"` literal in `resources/preload.js`, `resources/web-preload.js` and `src/vinary/main/**` | every `` `vv:…` `` code span in [`reference/ipc-channels.md`](../reference/ipc-channels.md) | a channel in code is uncatalogued, or the catalog names a channel the code no longer has |
| **4c** namespaces | the `(ns …)` form of every `src/vinary/**/*.cljs` / `.clj` / `.cljc` file, and every JS module under `src/vinary/` (named `vinary.<dir>.<file>.js`, e.g. `vinary.main.content_service.js`) | first-column `vinary.*` table rows of [`reference/namespaces.md`](../reference/namespaces.md) | a namespace or module has no row, or a row names one the code no longer has |
| **4d** CSS tokens | every `--vv-*` custom property `app.css` or a theme file under `resources/public/css/themes/` defines (`--vv-x:`), and every one `app.css` reads (`var(--vv-x`) | first-column `--vv-*` table rows of [`reference/css-variables.md`](../reference/css-variables.md) | a token has no row, or a row names one no stylesheet defines or reads |

Ids are compared **literally** — `::local` subscriptions are written `::name` in the
doc, exactly as in the source — and an id registered as two kinds (an event and an
effect of the same name, e.g. `:find/cycle`) needs a row in **both** sections. A
deliberate exception is declared in the doc itself with an HTML comment,
`<!-- lint:allow <id-or-channel> -->`, so the exemption is reviewed with the page.

The rule this enforces: **a commit that registers an event, effect, subscription or
IPC channel — or adds a namespace, a JS module or a `--vv-*` token — documents it in
the same commit.**

### 1.4 Check 5 — every relative link names a file that exists

A Markdown link or image to a repository path is a claim about the filesystem, and
nothing else checked it. It was found false three times on 2026-10-17: ADR-0028
embedded a diagram (`component-feature-layers.svg`) that was never committed,
ADR-0027 linked ADR-0019's file under ADR-0020's number, and ADR-0046 linked
`content-guard.cljs` for the file `content_guard.cljs`.

Check 5 reads every Markdown file under `docs/` plus `README.md` and `AGENTS.md`. It
skips fenced code blocks and inline code spans. It also skips URLs (any `scheme:`)
and in-page `#anchors`. Every other `[text](path)` or `![alt](path)` must name an
existing file or directory, relative to the document. The path is percent-decoded,
and a `#fragment` or `?query` is dropped first. Anchors *inside* other files are not
checked.

A negative control: the check run on the tree before those three fixes reports
exactly those three links.

### 1.5 Check 6 — every `:dangerouslySetInnerHTML` is tagged with `unsafe-html`

**What Reagent does.** In hiccup, `:dangerouslySetInnerHTML` asks React to put an HTML
string into an element as-is. Since 1.3.0 (2025-02-14), Reagent passes that prop to
React **only** when its value is a tagged `reagent.core/unsafe-html` value. It deletes
any other value without a warning, so the element renders **empty** [REAGENT-CL]. The
app has Reagent 2.0.1 (`deps.edn`); this is its `convert-props`
(`reagent/impl/template.cljs`) [REAGENT-SRC]:

```clojure
;; Ensure only tagged values are used for dangerouslySetInnerHTML
(when-let [d (and js-props (.-dangerouslySetInnerHTML js-props))]
  (if (instance? UnsafeHTML d)
    (set! (.-dangerouslySetInnerHTML js-props) #js {:__html (.-s d)})
    (js-delete js-props "dangerouslySetInnerHTML")))
```

`UnsafeHTML` is a type that EDN and Transit cannot produce. The tag means that hiccup
decoded from outside data — an API response, `localStorage` — cannot carry HTML into
the page [REAGENT-SEC]. A plain `{:__html s}` map, the shape React's own
documentation uses, is exactly what decoded EDN or Transit could contain, so Reagent
drops it too.

**What it cost (H-REAGENT-HTML).** The Commits tab rendered an expanded commit message
with `{:dangerouslySetInnerHTML {:__html html}}`. The app has been on Reagent 2.0.1
since v0.2, so that body has been **empty since the tab landed** (a26c9ef,
2026-08-11). Nothing tested it. It was found on 2026-10-18, while building the
presenter view's Markdown notes
([ADR-0048](../design-decisions/0048-speaker-notes-and-linked-presenter-windows.md)),
which used the same map and also rendered empty:

- the renderer computed the right HTML for each note;
- a `MutationObserver` saw each note's element inserted already empty, and never
  filled;
- the props React held for the element (`__reactProps…` on the DOM node) had no
  `dangerouslySetInnerHTML` key at all.

The Commits body now passes `(r/unsafe-html html)`, and the presenter view's notes
were written that way before they landed. The Commits body is a public function,
`vinary.ui.commits/commit-body`, so a DOM-free unit test can see what React receives.

**The check.** Every `:dangerouslySetInnerHTML` in **code** under `src/vinary` must be
followed by a call of `unsafe-html`, as `(r/unsafe-html …)` or
`(reagent.core/unsafe-html …)`. Docstrings, other strings and comments may name the
prop freely. The scan blanks them first, keeping every newline so that a match's
offset still gives its line:

```text
for each .cljs / .cljc file under src/vinary:
    code ← the file, with every string, regex literal (#"…") and ; comment replaced by spaces,
           newlines kept; a character literal (\" \; \a …) is copied as it is, so it opens nothing
    for each match of  :dangerouslySetInnerHTML  followed by its next token t:
        count the site
        unless t starts with "(r/unsafe-html" or "(reagent.core/unsafe-html":
            report  <file>:<line>
pass ⇔ nothing was reported
```

The rule is syntactic, and deliberately strict:

- A value computed elsewhere, as in `{:dangerouslySetInnerHTML tagged}`, is reported
  even when `tagged` holds an `unsafe-html` value. Tag the HTML where the prop is
  written, so a reader can check it there.
- A JavaScript props object for `r/create-element` (`#js {:dangerouslySetInnerHTML
  #js {:__html s}}`) bypasses Reagent's conversion and would be correct, but it is
  reported too. There is no such site; the app builds its elements as hiccup.
- Every namespace under `src/vinary` aliases `reagent.core` as `r`, so another alias
  is reported rather than guessed at.

**Negative controls.**

| Gate | On the tree before the fix | After |
|---|---|---|
| G-A1: `test/vinary/ui/commits_body_test.cljs`, the body's props through `r/as-element` | **fails**: `(not (= "<p>Fixes <em>it</em></p>" nil))` — React receives no inner HTML | passes |
| G-A2: this check | **fails**: `✗ 1 :dangerouslySetInnerHTML site(s) under src/vinary pass their HTML through reagent.core/unsafe-html — Reagent DROPS these silently: src/vinary/ui/commits.cljs:135` | `✓ 1 :dangerouslySetInnerHTML site(s) under src/vinary pass their HTML through reagent.core/unsafe-html` |
| G-A3: `test/electron-smoke.js`, the Commits tab's arm D — expand a mocked commit whose body is `a body` | **fails**: `Timed out waiting for an expanded commit shows its message`; every earlier check passes | passes: a `<p>` in the body holds `a body` |

The pipeline wraps each text node of the body in a source-position `<span>` (the
`data-vv-source-*` attributes behind the source ↔ preview jump), so G-A3 asks for a
paragraph that holds the text, not for the text directly inside a `<p>`: a first
form of the check that matched `<p …>a body</p>` failed on the fixed tree for that
reason alone.

Checks 6 and 7 share one lexer, `lexClj`. It returns the blanked `code` above and,
for check 7, every regex literal's body with the offset of its opening quote. A `"`
opens a regex only right after a `#` that is code, so the `#` of a `\#` character
literal opens nothing.

### 1.6 Check 7 — no inline flag group in a regex literal

**What ClojureScript does with a flag group.** A regex literal compiles to a
JavaScript regex literal. ClojureScript 1.12.42, the app's version (`deps.edn`), moves
a **leading** `(?flags)` into the literal's flags and copies the rest verbatim
(`cljs/compiler.cljc`, `emit-constant*`) [CLJS-EMIT]:

```clojure
(let [[_ flags pattern] (re-find #"^(?:\(\?([idmsux]*)\))?(.*)" (str x))]
  (emits \/ (.replaceAll (re-matcher #"/" pattern) "\\\\/") \/ flags))
```

So `#"(?i)^https?://"` becomes `/^https?:\/\//i`. An inline modifier group, such as
`(?i:…)` or `(?-i:…)`, reaches JavaScript as it is. That syntax comes from TC39's
RegExp Modifiers proposal, now at Stage 4 [TC39-MOD]. V8 accepts it, but Closure
Compiler's regex check does not. A `(?i)` anywhere but the start is not JavaScript at
all.

**What it cost.** The presenter view's note links used
`#"^(?i:https?|mailto):"` (`vinary.ui.slides-presenter/external?`). Links worked,
because Electron's V8 parses the group. But every `shadow-cljs release` printed:

```text
File: …/src/vinary/ui/slides_presenter.cljs:106:53
Malformed Regular Expression: Malformed parenthetical: (?i:https?|mailto):
```

Only the release build runs that check; `shadow-cljs compile` does not. So the
warning never appeared in development builds, and the user found it. The released
bundle carried the group as written, `/^(?i:https?|mailto):/`. The same bundle shows
a leading `(?i)` compiled to the `i` flag: `/^https?:\/\//i`. The fix is
`#"(?i)^(?:https?|mailto):"`, and `external?` is now public with a unit test
(`test/vinary/ui/slides_presenter_test.cljs`).

**The check.** Each regex literal under `src/vinary` is scanned for flag groups:

```text
for each regex literal r under src/vinary (from lexClj):
    i ← 0
    while i < |r|:
        if r[i] = '\'  : skip r[i+1]                      ⟨an escape: \( \[ …⟩
        elif inside a [class]: leave it at its ']'
        elif r[i..] starts with "(?":
            g ← r[i..] matched against  \(\?([A-Za-z]*)(?:-([A-Za-z]*))?([:)])
            if g and not (g has no letters and no '-')   ⟨(?: and the lookarounds pass⟩
               and not (i = 0 and g ends in ')' and has no '-' and its letters ⊆ {i, m, s, u}):
                report  <file>:<line> g
        i ← i + 1
pass ⇔ nothing was reported
```

The flags allowed in the leading group are the ones JavaScript knows and the
compiler moves (`i m s u`). The compiler would also move `d` and `x`, but `x` is no
JavaScript flag, and nothing in the app uses `d`.

**Negative control.** On the tree before the fix, the check reports exactly the site
the release build named. After the fix it passes:

| Tree | The check |
|---|---|
| before the fix (`75dabca`) | `✗ 219 regex literals under src/vinary use no inline flag group — a release build rejects these (write a leading (?i) instead): src/vinary/ui/slides_presenter.cljs:106 (?i:` |
| after the fix | `✓ 219 regex literals under src/vinary use no inline flag group` |

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
- **[REAGENT-CL]** Reagent, `CHANGELOG.md`, 1.3.0 (2025-02-14): "Breaking IF you use
  `:dangerouslySetInnerHTML` … you now need to use `reagent.core/unsafe-html` to tag
  these html strings which will be placed as-is into DOM, or Reagent will ignore the
  value." <https://github.com/reagent-project/reagent/blob/v2.0.1/CHANGELOG.md>
- **[REAGENT-SRC]** Reagent 2.0.1, `src/reagent/impl/template.cljs`, `convert-props`
  (byte-identical to the jar the app builds with).
  <https://github.com/reagent-project/reagent/blob/v2.0.1/src/reagent/impl/template.cljs>
- **[REAGENT-SEC]** Reagent, `doc/Security.md`, *Workarounds*: "Reagent 1.3.0+:
  `:dangerouslySetInnerHTML` values must be created using `reagent.core/unsafe-html`
  function which tags them using a type, that can't be used accidentally from Transit
  and EDN". <https://github.com/reagent-project/reagent/blob/v2.0.1/doc/Security.md>
- **[CLJS-EMIT]** ClojureScript 1.12.42, `src/main/clojure/cljs/compiler.cljc`, the
  `emit-constant*` method for `java.util.regex.Pattern` (lines 359–366 of the jar the
  app builds with): a leading `(?[idmsux]*)` becomes the literal's flags.
  <https://github.com/clojure/clojurescript/blob/r1.12.42/src/main/clojure/cljs/compiler.cljc>
- **[TC39-MOD]** TC39, *RegExp Modifiers* (Stage 4; champion Ron Buckton): the
  `(?flags-flags:subexpression)` group, which turns flags on or off within part of a
  pattern. <https://github.com/tc39/proposal-regexp-modifiers>
- `AGENTS.md` (repository root) — the coding-style and naming source.
- [`reference/css-variables.md`](../reference/css-variables.md) — the `--vv-*`
  token catalogue Check 3 depends on.
- [01-build-system.md](01-build-system.md) — the compile-time `fs`/`path`/`url`
  stub that pairs with §5.
- [03-test-strategy.md](03-test-strategy.md) — where regression tests live.
- [theory/04-hexagonal-and-ipc-mediator.md](../theory/04-hexagonal-and-ipc-mediator.md)
  — the boundary §5 enforces.
