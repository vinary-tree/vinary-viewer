# Cold-start optimization ledger

A scientific ledger for minimizing the time from launching `vv <file>` to seeing the file's content.
Every experiment records a hypothesis, the change, the measured before/after, and whether it was kept.

## Method
- **Profiler:** `VV_PROFILE=1` gates `[vv-profile]` marks in both processes (`vinary.main.profile`,
  `vinary.renderer.profile`), on one wall-clock timeline (`Date.now()` in main; `performance.timeOrigin +
  performance.now()` in the renderer, forwarded to main's stdout via a `console-message` listener). Marks:
  `entry → ready → window` (main) · `eval → init → paint` (renderer) · `did-finish-load → open-sent` (main) ·
  `received → rendered` (renderer). `rendered` = the first content node (`.markdown-body`/`.cm-editor`/
  `.vv-pdf-doc`/…) appears.
- **Harness:** `node scripts/profile-cold-start.mjs [--runs N] [--keep] [fixture …]` launches the GUI exactly
  as the `vv` launcher does (`electron "$REPO" <file>` — NO `--no-sandbox`, which would shift `process.argv`
  and make `doc-uris` open the repo dir instead of the file), N× per fixture under xvfb (forcing X11 so the
  windows stay headless, not on the host Wayland compositor), and reports median ms-from-`entry` per phase.
- Build: `npm run compile` (main + renderer), then run the harness.

## Baseline (before any optimization)
`--runs 3`, headless xvfb, this machine. Median ms from `entry`:

| fixture   | ready | window | eval | init | paint | did-finish | open-sent | received | **rendered** |
|-----------|------:|-------:|-----:|-----:|------:|-----------:|----------:|---------:|-------------:|
| empty.md  |  131  |  154   | 1014 | 1014 | 1028  |    1270    |   1272    |   1391   |   **1455**   |
| math.md   |  127  |  151   | 1025 | 1025 | 1040  |    1278    |   1280    |   1391   |   **1490**   |
| doc.org   |  111  |  134   |  993 |  993 | 1007  |    1240    |   1243    |   1367   |   **1457**   |
| doc.tex   |  115  |  138   |  988 |  988 | 1003  |    1236    |   1238    |   1352   |   **1455**   |
| code.py   |  113  |  136   | 1012 | 1012 | 1026  |    1261    |   1263    |   1373   |   **1412**   |
| smoke.pdf |  110  |  134   |  986 |  986 | 1001  |    1244    |   1247    |   1368   |   **1481**   |

### Phase breakdown (typical) and analysis
| interval | cost | what it is |
|---|---:|---|
| entry → ready | ~115 ms | Electron/Chromium app init (fixed; not our code) |
| ready → window | ~22 ms | `BrowserWindow` create + `loadFile` |
| **window → eval** | **~850 ms** | **renderer process spawn + load/eval of the 11 MB bundle — the dominant cost, ~60%** |
| eval → init | ~0 ms | entry ns → `init` |
| init → paint | ~15 ms | boot to the empty app shell painting |
| paint → did-finish-load | ~235 ms | main-side `did-finish-load` + 7 sequential `init!` round-trips before the file open |
| open-sent → received | ~120 ms | file read + `vv:open`→`vv:content` IPC |
| received → rendered | ~70 ms | the format's render (markdown/pdf/source…) |
| **entry → rendered** | **~1450 ms** | **total cold start to content** |

**Findings.** (1) The bundle load/eval (`window→eval`, ~850 ms) dominates and is **uniform across formats** —
math/org/latex/pdf/source all land at ~1450 ms — because the eager 11 MB bundle loads MathJax (+3 MB fonts),
CodeMirror, and the full markdown/org/latex pipeline regardless of what was opened. This is the single biggest
lever: shrink the boot bundle (code-split) and/or avoid re-paying it per open (warm daemon). (2) A resident
daemon eval's the bundle once at login, so `window→eval` (and a V8 snapshot) become non-issues for warm opens —
confirming the plan's reframing. (3) The 7 pre-open `init!` round-trips add ~235 ms before the file even opens.

## Phase 2 — warm daemon (single-instance + Unix socket)
**Hypothesis.** A resident process (`electron "$REPO" --daemon`) that opens *no* window at start but stays alive
across `window-all-closed` lets each `vv <file>` open a window in the already-warm process instead of a fresh
cold start, so the per-open cost drops even before any window pool (Phase 3).

**Change.** `app.requestSingleInstanceLock()` makes the first process the primary; it also listens on a
systemd-independent Unix socket (`$XDG_RUNTIME_DIR/vinary-viewer.sock`, `vinary.main.daemon`). The `vv` launcher
is now a thin node client (`scripts/vv-open.mjs`) that sends resolved paths over the socket — and starts
`--daemon` itself if none is reachable (no systemd required; a systemd user unit, if present, merely keeps it
warm at login). Session-global services (`extensions`/`web`/`adblock`/passwords/shell) register once behind a
`services-inited?` guard; `grammars/init!` gained the same guard so a 2nd window can't re-register its handler.

**Measurement.** One resident `--daemon`, then N socket opens of a math `.md`, headless xvfb, this machine,
medians (`tmp/warm-measure.mjs`; `window→rendered` uses the same per-window marks as the cold baseline):

| metric | cold (fresh process) | warm daemon, 1st window | warm daemon, 2nd+ window |
|---|---:|---:|---:|
| `window → rendered` (per-window cost) | ~1275 ms | ~876 ms | **~697 ms** |
| perceived open (`client → rendered`) | ~1450 ms † | ~935 ms | **~729 ms** |

† the cold figure is `entry→rendered`; the real `vv`-process cold start is *higher* still — it also pays the
Electron-binary load + main-process V8 init that precede the profiler's `entry` mark, all of which the socket
client skips entirely.

**Analysis.** Even with **no window pool yet**, the warm daemon roughly **halves** the open (~1275 → ~697 ms
per-window; ~1450 → ~729 ms perceived). Three effects compound: (1) the main-process/app init (~115 ms
`entry→ready`, plus the un-profiled pre-`entry` binary+V8 bootstrap) is paid once at login, not per open;
(2) the 7 pre-open `init!` round-trips (~235 ms) collapse because the session services are already initialized
(guarded) and their data is cached; (3) Chromium's on-disk **code cache** makes the 2nd+ renderer's 11 MB
bundle *compile* far cheaper than the very first (876 → ~697 ms). The residual ~697 ms is dominated by the
per-window bundle **eval** (each `BrowserWindow` still spawns its own renderer and re-evals the bundle) plus the
file read+render — exactly what **Phase 3's pre-booted window pool** removes (attach the file to an
already-evaluated renderer). **Kept.**

## Phase 3a — warm window pool
**Hypothesis.** The residual ~697 ms of a warm-daemon open is the per-window bundle **eval** — each new
`BrowserWindow` spawns its own renderer and re-evaluates the 11 MB bundle. If the process keeps a small pool of
**pre-booted, hidden, fully-warmed** windows (bundle already evaluated, `init!`s already run), a real open can
just *attach* the file to one and show it, skipping the eval entirely — leaving only the file read + render.

**Change.** `create-window!` was split into `wire-window!` (build + load + per-window `init!`s + session
services, `show?` toggles visible/hidden) and `open-files!` (send `vv:open-files` to an already-loaded
renderer). A pool of hidden windows (`pool`, target `VV_POOL`, default 1; `VV_POOL=0` disables) is pre-booted
after `whenReady` (and refilled after each claim). `claim-window!` pops a warm window, attaches the files,
`show`s it, and refills; if the pool is empty it falls back to a cold `create-window!`. Pool windows live only
in `pool` (never `windows`) so `active-window`/menus never target a hidden one; a claim moves it into `windows`.
The daemon pre-warms the pool at startup and serves every socket open from it.

**Measurement.** One `--daemon` (`VV_POOL=1`), then N socket opens of a math `.md`, headless xvfb, medians
(`tmp/pool-measure.mjs`). `claim→rendered` = a new `claim` mark (pool hit) to the content node appearing:

| metric | cold | warm daemon, no pool | **warm pool (claimed)** |
|---|---:|---:|---:|
| open cost to content | ~1275 ms (window→rendered) | ~697 ms (window→rendered) | **~101 ms** (claim→rendered) |
| perceived (`client→rendered`) | ~1450 ms † | ~729 ms | **~127 ms** |

All 4 opens were served from the pool (`claims 4/4`), and the pool refilled each time (5 window boots = 1
initial + 4 refills). The ~101 ms residual is just the file read + `vv:open`→`vv:content` IPC + the format
render — no bundle eval, no `init!` round-trips.

**Analysis.** The pool removes the last big per-open cost: **~1450 → ~127 ms perceived, ~11×** faster, into
near-instant territory. The bundle eval is paid once per pool slot (in the background, off the open's critical
path) instead of once per open. Idle cost is `VV_POOL` hidden renderers (default 1 — a few hundred MB), tunable
down to 0. This is the payoff of the daemon+pool architecture; Phase 1 (code-split + warm-on-idle) will make
each pool boot cheaper and pre-JIT CodeMirror so the first source/math render in a claimed window is instant too.
**Kept.**

## Phase 3b — independent windows (per-window routing)
**Context.** The daemon + pool make it cheap to open *many* windows in one process, so the app is now
genuinely multi-window. The process- and session-level services (`shell`, `web` view, `passwords`,
`extensions`, `adblock`, `ext-popup`) were written for a single window: each captured the *first* window at
`init!` and routed everything to it. With a pool that first window may even be a *hidden* pool window, and with
several windows open, menu actions / the web view / extension popups would target the wrong (or a hidden) one.

**Change.** A new leaf namespace `vinary.main.windows` holds the live-window registry and the shared resolver
`active` (focused window, else most-recently-shown; **never** a hidden pool window — those aren't registered)
and `from-wc` (the window that owns an `event.sender`). `core` registers a window only when it is *shown*
(claimed / cold-created), so the registry excludes the pool. Every service now routes dynamically instead of to
a captured window: `shell/cur-win`, `passwords`, `extensions`, and `adblock` send to `windows/active-wc`; the
shared web view **re-parents** to the window whose renderer requested it (`vv:http-show` → `from-wc`,
preserving the live page) and its relays follow that owner; extension popups anchor to the clicking window
(`from-wc`); each window's Back/Forward keys are wired at most once (tracked as a set, since the view can move).

**Verification.** `tmp/multiwin-test.mjs`: one daemon, two different `.md` files over the socket → two
independent windows each render their own content, `claims 2/2`, pool refilled, and **0** main-process routing
errors. The 316-test suite stays green; `release main` compiles. No latency change (the pool already delivered
~127 ms) — this is correctness: opening files in multiple windows, and using the shared web view / extensions /
passwords, now always act on the window the user is looking at. **Kept.**

## Phase 1 — fast warm per-window boot (measured NOT needed, given the pool)
Phase 1 planned to (a) defer MathJax off `init`, (b) code-split the bundle, and (c) warm-on-idle (pre-build
`mj-engine`, pre-JIT CodeMirror, pre-`init` tree-sitter) so the *first* math/source/org/latex render in a warm
window has no stall. Before implementing, we **measured** per-format warm-pool opens (`tmp/format-pool.mjs`,
one daemon `VV_POOL=1`, each fixture claimed from the pool):

| fixture | what it exercises | claim→rendered |
|---|---|---:|
| `f.md`  | markdown pipeline | ~100 ms |
| `f.py`  | **CodeMirror source view + tree-sitter** | **~70 ms** |
| `f.org` | uniorg | ~84 ms |
| `f.tex` | unified-latex | ~109 ms |

**Finding.** Every format already opens in ~70–109 ms from the pool, and the source/CodeMirror path is the
*fastest*. A pool window boots the whole bundle and then sits idle, so by claim time MathJax is built
(`install-stylesheet!` runs at `init`), the markdown/org/latex processors and CodeMirror modules are evaluated,
and V8 has tiered up the hot paths. The "first render stalls" that (a)+(c) target **do not occur** — the pool
subsumes them. So warm-on-idle and MathJax-defer are **not implemented**: they would add complexity for no
measured gain, which the data-driven mandate forbids. The user's explicit "make CodeMirror load as fast as
possible" is satisfied — source opens in ~70 ms, faster than any other format.

**Code-split (b)** remains *only* as a cold-path / memory / pool-refill lever: it would shrink the per-window
bundle eval (~700 ms warm, ~850 ms cold), so a cold first open (no daemon) is cheaper, the pool refills faster
under burst-opening, and each hidden pool window costs less memory. It does **not** improve the warm open (the
pool already evaluated the bundle). Because it restructures the fragile unified/remark/rehype/uniorg +
unified-latex import graph (`:simple`-only; advanced breaks the interop), it carries real risk for a
warm-path-neutral gain — a trade-off surfaced to the user rather than taken unilaterally.

## Phase 1/4 — code-split + V8 snapshot: feasibility investigation (the "go further" scope)
The user opted to pursue the full split (mathjax/codemirror/org/latex) **and** a V8 startup snapshot. Investigating
each against the actual architecture surfaced hard walls and a decisive data point — recorded here so the
reasoning is not re-attempted blindly (per the "document what does not work" mandate).

**Wall 1 — the 4-way split can't be done without re-architecting to async boundaries.** shadow-cljs `:modules`
code-splitting only removes bytes from the boot module if the split namespaces are reached **exclusively through
async loadables**; any *static* `ns/var` reference from a boot-reachable namespace pulls the whole namespace
back into the boot module. But:
- `renderer.markdown-pipeline` (the DOM-free hub, **shared by `:renderer` + `:cli` + `:tui` + `:test`**)
  statically calls `math/render-html-math`, `math/render-tex-blocks`, and `latex/latex->html` — math and LaTeX
  render *inside* the synchronous pipeline, so mathjax + unified-latex can't leave it without making the
  pipeline async (which would break the node targets that bundle it whole).
- `renderer.syntax` (CodeMirror) is called from **synchronous** source-view handlers in `ui/views`
  (`pos-at-coords`, `line-info-at`, `viewport-top-line`, `selection-start`) and sync fx in `app/fx`
  (`scroll-source-to-line!`, `current-viewport-line`) — these return values to synchronous DOM events and
  can't await a module load.

**Wall 2 — a V8 snapshot of the renderer bundle is not viable as built.** The `:browser` bundle ends with a
top-level `vinary.renderer.core.init();` (runs at eval, touches `document`) and bootstraps
`goog.global = window / self`. `@electron/mksnapshot` runs the script in a **bare V8** (no `window`/`document`),
so it breaks immediately. A snapshot would need a dedicated *define-but-don't-run* build variant plus
browser-global shims — a separate, fragile build, and it only speeds bundle **eval** (login-start + pool
refill + non-daemon cold), never a warm open.

**Decisive data point — splitting CodeMirror is neutral-to-negative for the daemon+pool architecture.** The pool
already opens source in **~70 ms** (the fastest format — Phase 1 table above). If CodeMirror were split out:
- a **pool** window either preloads it on idle (→ same memory, no boot benefit) or does not (→ a source open
  then pays a chunk-load stall, **regressing** the ~70 ms);
- only a **non-daemon cold markdown** open (no source, no daemon) would benefit — a narrow case, since a
  speed-sensitive user runs the daemon.

So the daemon+pool (already shipped) is the measured-optimal cold-start solution; the split/snapshot trade real
complexity + risk (and a possible warm-source regression) for cold-path-only, often-nil gains. The clean way to
lazy-load CodeMirror *if wanted anyway* is the **Lightning Bug** pattern (`../lightning-bug/`): encapsulate the
whole editor behind an imperative-handle boundary and ship it as an ESM module with
`:keep-as-import #{"@codemirror/*"}`, so the host never statically references CodeMirror internals and the chunk
loads on demand. Surfaced to the user rather than implemented, given the nil warm-path benefit.

## Phase 1 (revisited) — CodeMirror IS splittable via the Lightning Bug boundary
The "go further" decision + the Lightning Bug reference (`../lightning-bug/`) reopened the CodeMirror split. The
earlier "Wall 1" (sync source-view handlers) is real but surmountable: the handlers only ever run on an EXISTING
`EditorView`, so the chunk is always loaded by the time they fire. The clean boundary (commit `c58d9d9`):
- **`renderer.syntax`** → now `@codemirror`-FREE tree-sitter core + HTML highlighting (still shared by `:cli`);
  the Decoration builders were refactored to emit `@codemirror`-free **spans** (`highlight-spans`), byte-identical
  HTML output.
- **`renderer.source-view`** (NEW, lazy) → the `@codemirror` `EditorView` half (spans→Decorations + the editor).
- **`renderer.cm`** (NEW) → a `shadow.lazy` facade (`ensure!`/`ready?` + guarded/buffered pass-throughs); the
  source-view render path awaits `ensure!`, and `core` idle-preloads the chunk so pool windows stay warm.
- `shadow-cljs.edn` `:renderer` gained `:module-loader true` + a `:source-view` module.

**Measured — same-session cold A/B** (`profile-cold-start.mjs --runs 5`, worktree at `bc08913` vs HEAD `c58d9d9`):

| metric | pre-split | post-split | Δ |
|---|---:|---:|---:|
| boot **eval** (entry→eval) | ~645 ms | ~645 ms | **0** |
| entry→**rendered** (median) | ~1180 ms | ~1100 ms | **−80 ms** |
| `main.js` bytes | 11,703,155 | 11,873,481 | +170 KB |
| separate chunk | — | `source-view.js` 259 KB | — |

**Finding.** The split does NOT shrink boot bytes — shadow's `:module-loader` pulls in ~430 KB of
`goog.module`/`goog.net` infra that exceeds the 259 KB `@codemirror` chunk it defers (the browser bundle uses
only `@codemirror/state` + `/view`, ~259 KB — not the ~1 MB `node_modules` source). BUT boot **eval time is
unchanged** (V8 lazy-compiles the added `goog.module` classes — bytes are a misleading proxy), and
entry→`rendered` is ~80 ms faster because `@codemirror`'s top-level execution is deferred out of the boot path.
So the split is **cold-start-neutral-to-slightly-positive with zero eval penalty**, and it delivers real
architecture: `:cli` no longer pulls `@codemirror`, markdown-only windows never instantiate the editor, the
chunk is preloadable by the pool, and the `:module-loader` infra is now in place to amortize future chunks.
317 tests green; all four targets compile; all 6 formats render cold (incl. `code.py` source via the chunk).
**Kept** (`c58d9d9`). The actual boot-eval lever is the V8 snapshot (next).

## Experiments
| # | hypothesis | change | fixture | before → after (perceived, entry/client→rendered) | kept? |
|---|---|---|---|---|---|
| — | baseline | — | all | ~1450 ms | — |
| 1 | a resident warm process halves per-open cost | single-instance + Unix-socket daemon (Phase 2) | math.md (warm 2nd+) | ~1450 → **~729 ms** (~1275 → ~697 ms window→rendered) | ✅ |
| 2 | a warm window pool makes opens near-instant | pre-booted hidden window pool + claim/refill (Phase 3a) | math.md (pool hit) | ~729 → **~127 ms** (claim→rendered ~101 ms) — ~11× vs cold | ✅ |
| 3 | first source/org/latex render in a warm window stalls (warm-on-idle needed) | — (measured before implementing) | py/org/tex (pool hit) | claim→rendered 70 / 84 / 109 ms — **no stall**; hypothesis REJECTED | ✗ not needed |
| 4 | splitting @codemirror to a lazy chunk shrinks boot eval | `syntax`/`source-view`/`cm` boundary + `:module-loader` (`c58d9d9`) | cold A/B + warm pool | eval unchanged (~645 ms), rendered −80 ms, bytes +170 KB; warm source 70→93 ms | ✅ (architecture; eval-neutral) |
| … | | | | | |
