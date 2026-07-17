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

## Experiments
| # | hypothesis | change | fixture | before → after (perceived, entry/client→rendered) | kept? |
|---|---|---|---|---|---|
| — | baseline | — | all | ~1450 ms | — |
| 1 | a resident warm process halves per-open cost | single-instance + Unix-socket daemon (Phase 2) | math.md (warm 2nd+) | ~1450 → **~729 ms** (~1275 → ~697 ms window→rendered) | ✅ |
| 2 | a warm window pool makes opens near-instant | pre-booted hidden window pool + claim/refill (Phase 3a) | math.md (pool hit) | ~729 → **~127 ms** (claim→rendered ~101 ms) — ~11× vs cold | ✅ |
| … | | | | | |
