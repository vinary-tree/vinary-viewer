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

## Experiments
| # | hypothesis | change | fixture | before → after (entry→rendered) | kept? |
|---|---|---|---|---|---|
| — | baseline | — | all | ~1450 ms | — |
| … | | | | | |
