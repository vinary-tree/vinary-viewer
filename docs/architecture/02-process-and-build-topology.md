# 02 · Process & Build Topology

> **Scope.** How vinary-viewer is *built and laid out across processes*: the two **shadow-cljs**
> builds (targets, outputs, init functions, devtools, and the renderer's deliberate Node stubbing),
> the **deps.edn** library stack, the **package.json** scripts and JS dependencies, *why* main uses
> runtime `require` while the renderer stubs Node, and the **dev hot-reload** loop. Read
> [01 · Overview](./01-overview.md) first for the thesis.

---

## 1. Deployment shape

vinary-viewer ships as a standard Electron app: one **MAIN** (Node) process and one **RENDERER**
(Chromium) process per window, with a **preload** script injected across the trust boundary.

![Deployment](../diagrams/deploy-electron-processes.svg)

*Source: [`../diagrams/deploy-electron-processes.puml`](../diagrams/deploy-electron-processes.puml).*

`package.json` `"main": "dist/main/main.js"` tells Electron which file is the main entry; `electron .`
(the `start` script) launches it.

---

## 2. The two shadow-cljs builds

`shadow-cljs.edn` defines exactly two builds under `:builds`. They share the source paths from
`deps.edn` (`["src" "resources"]`) but compile to different targets.

```clojure
;; shadow-cljs.edn
{:deps {:aliases []}
 :jvm-opts ["--sun-misc-unsafe-memory-access=allow"]   ; Java 26: some deps touch sun.misc.Unsafe
 :builds
 {:main {:target    :node-script
         :main      vinary.main.core/main
         :output-to "dist/main/main.js"}
  :renderer {:target     :browser
             :output-dir "resources/public/js"
             :asset-path "js"
             :modules    {:main {:init-fn vinary.renderer.core/init}}
             :devtools   {:after-load vinary.renderer.core/reload
                          :preloads   [devtools.preload
                                       day8.re-frame-10x.preload.react-18
                                       re-frisk.preload]}
             :compiler-options {:closure-defines {"re_frame.trace.trace_enabled_QMARK_" true}}
             :js-options {:resolve {"fs" false "fs/promises" false "path" false "url" false}}}}}
```

### 2.1 `:main` — the Electron main process

| Key | Value | Meaning |
| --- | --- | --- |
| `:target` | `:node-script` | Emit a Node-runnable script. shadow-cljs uses **`:js-provider :require`** for this target, so `require("electron")` and Node built-ins stay **runtime requires** resolved by the Electron runtime — they are *not* bundled. |
| `:main` | `vinary.main.core/main` | The function invoked when the script runs. |
| `:output-to` | `dist/main/main.js` | The single emitted file; also `package.json`'s `"main"`. |

There is **no `:devtools`** block on `:main` (a Node script needs no hot-reload-after-load hook for
the page). The main build is intentionally minimal.

### 2.2 `:renderer` — the Electron renderer (Chromium)

| Key | Value | Meaning |
| --- | --- | --- |
| `:target` | `:browser` | Emit a browser bundle (DOM, ESM JS deps bundled in). |
| `:output-dir` | `resources/public/js` | Where the bundle and its `cljs-runtime` land. |
| `:asset-path` | `js` | URL prefix the bundle uses to load its own split modules. |
| `:modules {:main {:init-fn vinary.renderer.core/init}}` | — | One module named `main` → `resources/public/js/main.js`; `init` runs on load. |
| `:devtools {:after-load … :preloads …}` | — | Hot-reload calls `vinary.renderer.core/reload` after each recompile; the preloads install `binaryage/devtools`, **re-frame-10x** (React-18 preload), and **re-frisk**. |
| `:compiler-options {:closure-defines …}` | `re_frame.trace…/trace_enabled? = true` | Enables re-frame tracing (required by re-frame-10x). |
| **`:js-options {:resolve {"fs" false "fs/promises" false "path" false "url" false}}`** | — | **The renderer is denied Node.** These modules resolve to `false` (empty), so the renderer bundle *cannot* reach the filesystem. All IO crosses the preload seam. |

> **Why stub `fs`/`path`/`url` in the renderer?** Two reasons. (1) **Security**: with
> `nodeIntegration: false` the renderer has no `require` anyway; the stubs ensure that even a
> transitive dependency that *imports* `fs` compiles to a no-op instead of a build error or a
> privilege leak. (2) **Bundling**: the browser target should not try to bundle Node built-ins.
> Together they enforce the [thin-main/smart-renderer](./01-overview.md#4-the-thesis-thin-main-smart-renderer)
> boundary at build time.

### 2.3 Why main uses runtime `require` but the renderer does not

| Build | Module strategy | Effect |
| --- | --- | --- |
| `:main` (`:node-script`) | `:js-provider :require` (implicit) | `electron`, `fs`, `path`, `child_process`, `chokidar` are emitted as `require("…")` and resolved by the **Node/Electron runtime** at launch. The watcher library `chokidar` is a real `node_modules` runtime dependency. |
| `:renderer` (`:browser`) | bundle ESM, **stub Node** | The all-ESM unified/remark/rehype stack is **bundled into** `main.js`; Node built-ins are stubbed to `false`. The renderer has zero `require`. |

This asymmetry is the build-level expression of the architecture: **Node lives in main; the DOM and
the markdown pipeline live in the renderer.**

---

## 3. The ClojureScript dependency stack (`deps.edn`)

`deps.edn` paths: `["src" "resources"]`. `core.async` arrives transitively via shadow-cljs. The
`:jvm-opts ["--sun-misc-unsafe-memory-access=allow"]` accommodates Java 26 (matching the sibling
LightningBug tooling baseline).

| Dependency | Role | Version |
| --- | --- | --- |
| `org.clojure/clojure` | Clojure (build/macros) | `1.12.2` |
| `org.clojure/clojurescript` | ClojureScript compiler | `1.12.42` |
| `thheller/shadow-cljs` | Build tool (compiles both builds) | `3.2.0` |
| `reagent/reagent` | React wrapper (hiccup → React 19) | `2.0.0-alpha2` |
| `re-frame/re-frame` | Event/effect/state/view loop | `1.4.3` |
| `re-com/re-com` | UI component library | `2.27.1` |
| `com.andrewmcveigh/cljs-time` | Time utilities (**required by re-com**) | `0.5.2` |
| `datascript/datascript` | In-memory Datalog DB (document/tab SSOT) | `1.7.5` |
| `re-posh/re-posh` | re-frame ⇄ DataScript bridge **(declared but UNUSED)** | `0.3.3` |
| `day8.re-frame/re-frame-10x` | Time-travel/inspector devtool | `1.10.1` |
| `re-frisk/re-frisk` | app-db inspector devtool | `1.7.1` |
| `binaryage/devtools` | Chrome DevTools cljs formatters | `1.0.7` |
| `com.taoensso/timbre` | Logging | `6.8.0` |
| `org.slf4j/slf4j-api` + `org.slf4j/slf4j-nop` | SLF4J facade + no-op backend | `2.0.17` |
| `org.clojure/test.check` | Generative testing | `1.1.1` |

> **`re-posh` is declared but not used.** The conventional re-frame ⇄ DataScript integration is
> re-posh, and it appears in `deps.edn`, but vinary-viewer does **not** wire it. The real reactivity
> is the hand-rolled **`:ds/rev` bridge** (`ds/install-bridge!` → `d/listen!` → `[:ds/changed]` →
> `(update db :ds/rev inc)`), which is explicit and does not depend on re-posh subscription
> internals. See [04 · State Schema](./04-state-schema-reference.md#5-the-dsrev-reactivity-bridge).
> The dependency may be retained for a future migration but carries no runtime behaviour today.

---

## 4. JavaScript dependencies & scripts (`package.json`)

`name: "vinary-viewer"`, `version: "0.2.0-dev"`, `license: "Apache-2.0"`, `"main": "dist/main/main.js"`.

### 4.1 Scripts

| Script | Command | Purpose |
| --- | --- | --- |
| `compile` | `shadow-cljs compile main renderer` | One-shot compile of both builds. |
| `watch` | `shadow-cljs watch main renderer` | Recompile-on-save for both builds (hot reload). |
| `release` | `shadow-cljs release main renderer` | Optimized production build. |
| `start` | `electron .` | Launch Electron against `dist/main/main.js`. |
| `dev` | `shadow-cljs compile main renderer && electron .` | Compile once, then launch. |

### 4.2 Runtime dependencies (`dependencies`)

| Package | Role | Process bundled into |
| --- | --- | --- |
| `chokidar` `^5.0.0` | Filesystem watcher for retained local files and Markdown assets | MAIN (`require`) |
| `react`, `react-dom` `^19.2.7` | React 19 runtime (reagent renders onto it) | RENDERER (bundled) |
| `unified` `^11.0.5` | Pluggable text-processing engine | RENDERER (bundled) |
| `remark-parse` `^11` | Markdown → mdast | RENDERER |
| `remark-gfm` `^4.0.1` | GitHub-Flavoured Markdown (tables, tasklists, …) | RENDERER |
| `remark-rehype` `^11.1.2` | mdast → hast | RENDERER |
| `rehype-slug` `^6.0.0` | Stable heading `id`s (used by find + TOC) | RENDERER |
| `rehype-highlight` `^7.0.2` | Syntax-highlight code blocks (highlight.js classes) | RENDERER |
| `rehype-stringify` `^10.0.1` | hast → HTML string | RENDERER |
| `rxjs` `^7.8.2` | Reactive streams (available; auxiliary) | RENDERER |

> **Note: no `rehype-raw`.** The unified chain does *not* include `rehype-raw`, so **raw HTML
> embedded inside Markdown is not re-parsed or injected** into the output tree — it is dropped by the
> mdast→hast conversion's default (non-raw) handling. This is a meaningful safety property; see
> [03 · IPC Protocol](./03-ipc-protocol.md#7-security-seam) and
> [security/threat-model.md](../security/threat-model.md).

### 4.3 Dev dependencies (`devDependencies`)

| Package | Role |
| --- | --- |
| `electron` `^42.5.0` | The Electron runtime. |
| `shadow-cljs` `3.2.0` | The JS-side shadow-cljs CLI (mirrors the Clojure dep). |

---

## 5. Process / artifact / source map

| Process | Build | Source roots | Emitted artifact | Loaded by |
| --- | --- | --- | --- | --- |
| MAIN | `:main` `:node-script` | `vinary.main.*` | `dist/main/main.js` | Electron (`package.json` main) |
| preload | (plain JS, not a shadow build) | `resources/preload.js` | itself | `webPreferences.preload` |
| RENDERER | `:renderer` `:browser` | `vinary.renderer.*`, `vinary.app.*`, `vinary.ui.*` | `resources/public/js/main.js` (+ `cljs-runtime/`) | `resources/public/index.html` `<script src="js/main.js">` |

`resources/public/index.html` loads the renderer:

```html
<link id="vv-theme-link" rel="stylesheet" href="css/themes/spacemacs-dark.css">  <!-- defines --vv-* -->
<link rel="stylesheet" href="css/app.css">                                       <!-- structural, var(--vv-*) -->
<div id="app"></div>
<script src="js/main.js"></script>
```

The theme stylesheet is loaded **first** (so `--vv-*` tokens exist), then the structural `app.css`
that references them. The `#vv-theme-link` `id` is the swap target for live theme switching
([05 · Data Flows §6](./05-data-flows.md#6-switch-theme)).

---

## 6. The development hot-reload loop

```text
┌───────────────────────────────────────────────────────────────────────────────┐
│  shadow-cljs watch main renderer                                                │
│                                                                                 │
│   edit src/vinary/**.cljs ──▶ shadow-cljs recompiles the affected build         │
│        │                                                                        │
│        ├─ :main      ──▶ dist/main/main.js              (restart Electron to    │
│        │                                                 pick up main changes)  │
│        └─ :renderer  ──▶ resources/public/js/main.js                            │
│                              │                                                  │
│                              ▼  hot module reload (no full reload)              │
│                       :devtools :after-load  ──▶ vinary.renderer.core/reload    │
│                              │                                                  │
│                              ▼  re-mount the reagent root (state preserved in   │
│                       (re-render [views/root])      app-db + DataScript conn)   │
└───────────────────────────────────────────────────────────────────────────────┘
```

- **Renderer changes** hot-reload: shadow-cljs swaps the changed modules and calls
  `vinary.renderer.core/reload`, which simply re-runs `mount!` (`rdomc/render @root [views/root]`).
  Because `app-db` and the DataScript `conn` are `defonce`/`defonce`-held atoms, **open documents,
  the active tab, theme, and find state survive the reload**.
- **Main changes** require restarting Electron (a Node process is not hot-swapped). In practice you
  run `watch` for the fast renderer loop and restart `electron .` when you touch `vinary.main.*`.
- **Dev inspection hooks** (installed in `init`): `window.__vvdb()` returns the current `app-db`
  (clj→js), and `window.__vvds()` returns the open docs from DataScript — handy from the Chromium
  console. re-frame-10x and re-frisk provide richer inspectors.

---

## 7. See also

- [01 · Overview](./01-overview.md) — the thesis and concern→namespace map.
- [06 · Renderer Runtime](./06-renderer-runtime.md) — what `init`/`reload`/`mount!` actually do.
- [reference/namespaces.md](../reference/namespaces.md) — per-namespace responsibilities.
- [usage/02-installation-and-build.md](../usage/02-installation-and-build.md) — running the scripts.
