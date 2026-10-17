# Experiment 12 — raw data

The measurements behind [12 — Slide-deck detection and flip latency](../../12-slide-deck-detection-and-flip-latency.md).
Every file is the instrument's own output, tee'd once; nothing here was edited by hand except where a line says so.

## Detection

| File | What it is |
|---|---|
| `second-screen-2026-10-16.txt` | Beamer's two-screen options: page geometry, page labels and text placement, measured on LuaLaTeX output (§4) |
| `positive-corpus.{txt,json}` | The controlled Beamer corpus: 14 variants × pdfLaTeX / LuaLaTeX / XeLaTeX, expected verdicts written into `scripts/exp-deck-corpus.cjs` before the run (§5) |
| `positive-corpus-2026-10-17.{txt,json}` | The same corpus with a fourth engine, `xelatex2` (XeLaTeX run twice), registered before the run: 56 cases (§5.1, H-X2) |
| `xelatex-labels-2026-10-17.txt` | XeLaTeX's page labels after one pass, two passes, and one pass over an edited deck (H-XSTALE, its registration reproduced verbatim), with LuaLaTeX as the control and a latexmk check (§5.1). Assembled by a shell block from the instruments' outputs; the verdict paragraphs at its end are written by hand. |
| `local-corpus-summary.{txt,json}` | The natural corpus (2456 local PDFs), counts by generic category only — never a file name, path or content (§6) |
| `local-corpus-summary-after.{txt,json}` | The same corpus re-classified in full after the export-canvas change (§7) |
| `export-canvas-prediction.txt` | The pre-registration of the export-canvas experiment: P1–P3, and a dated correction (§7) |
| `export-corpus-before.{txt,json}` | LibreOffice Impress at its seven slide-size presets, classified BEFORE the change (P1, P2) |
| `export-corpus-after.{txt,json}` | The same, AFTER the change (P3) |

## Flip latency

| File | What it is |
|---|---|
| `flip-prediction.txt` | The pre-registration (H-flip-1…4) and its two dated addenda (H-sched, H-sync, H-sync2), each written before the runs it judges (§8) |
| `flip-environment-{AB,AC,ABC}.txt` | Governor, load, the top background processes and the builds, recorded before each sequence |
| `flip-AB-{A,B}-{1,2,3}.txt` | The sequence that judged H-sync (A = baseline, B = `dispatch-now!` in `key-scroll!`). The A runs' per-sample JSON was overwritten by a naming collision in the next sequence; their summaries were restored verbatim from the instrument's printed output. B's JSON is intact (`flip-AB-B-*.json`). |
| `flip-AC-{A,C}-{1,2,3}.{txt,json}` | The sequence that judged H-sync2 (C = both hops synchronous). The instrument then recorded the `scrollTop` write only after its own listener ran, so C's write delay reads "no scrollTop write observed" (§8.4). |
| `flip-ABC-{A,B,C}-{1,2,3}.{txt,json}` | The replication with the final instrument, all three builds alternated |
| `flip-ABC-analysis.txt` | `scripts/analyze-flip-runs.cjs` over the replication: pooled quantiles, per-run medians, bootstrap intervals |
