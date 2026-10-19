# Experiment 14 — raw data

The measurements behind [14 — The cost of one note-page comparison](../../14-note-signature-cost.md). Three kinds of
file here were written for this directory: this README, [`MANIFEST.txt`](MANIFEST.txt), and the diffs in `designs/`
and `instruments/`, which publish the code that was measured ([The designs](#the-designs-designs),
[The instruments](#the-instruments-instruments)). Every other file is an instrument's own output, written once (by
`tee` or by the runner), or a script that produced one. None of those was edited by hand, except that local paths are
shortened ([Substitutions](#substitutions)). `MANIFEST.txt` lists every file of the experiment's scratch directory,
published or not, with its size and sha256, so that nothing is left out silently.

## Reading this directory

- **The ledger comes first.** [`PREREGISTRATION.txt`](PREREGISTRATION.txt) holds every hypothesis, prediction and
  rule, each written before the runs it judges, then each result, in the order they happened. It is append-only.
  Corrections are new dated entries. The last two sections hold the four found later: two while doc 14 §8 was
  written, and two while this directory was assembled. The other files are named by the ledger's entries, and their
  paths are relative to this directory.
- **Times** are the machine's clock, as the ledger gives them. All runs took place on 2026-10-18.
- **`.txt` and `.json`.** A benchmark arm's `.txt` is what the script printed: the median, p95, min and max of each
  case, and the instrument's checks. Its `.json` holds every sample of the arm (`VV_BENCH_JSON`).
- **A `functional-check*` file is not a result.** It records a few runs of an instrument made to see that it works,
  before the registered runs (doc 14 §8, item 14).
- **Design names.** R is the reference: `notes.cljs` before the fix, at commit `2b78f18`. F, F′ and F″ are the three
  designs (doc 14 §4). In file names, `fprime` stands for F′ and `fsec` for F″.

## Layout

```
data/14/
├── README.md, MANIFEST.txt, PREREGISTRATION.txt  this page, the account of every file, the ledger
├── environment.txt, arm-ratios.txt, functional-check{,-release}.txt, dev.*, release.*   H-PAIRWORK (R)
├── census-synthetic.*, census-all.*              the census of the pages (c0, c1) and E2 on F
├── compile-*.log, unit-*.log                     design F's builds and unit runs
├── mutate*.py, mutants*.sh, mutants/             the negative controls of E1 (F; F′ and F″ are below)
├── e3-files.txt, e3-deck-info.jsonl              E3: its 155 PDFs, and the one output R, F, F′ and F″ all print
├── wait-quiet.sh, quiet/                         design F's bench after the fix
├── flood/                                        H-FLOOD and H-FAR: R and F under crafted cells
├── fprime/  ─┬─ bench/                           design F′: its builds, E1, E2, its bench
│             └─ mutants/
├── strflood/                                     H-STRFLOOD (exploratory) and the census of the pages' text
├── fsec/  ───┬─ bench/                           design F″: its bench (Q1″, Q2⁗, Q5, Q6)
│             ├─ prof/                            the CPU profile's summary
│             ├─ text2/                           H-TEXT2
│             ├─ combined/                        H-COMBINED
│             └─ mutants/                         the final F″'s negative controls
├── designs/                                      R → F, R → F′, R → F″, … as diffs
└── instruments/                                  the benchmark's and the census's versions, as diffs
```

## The ledger

| File | What it is |
|---|---|
| `PREREGISTRATION.txt` | The ledger: the registrations of H-PAIRWORK, of the designs F, F′ and F″, and of H-FLOOD, H-FAR, H-TEXT2 and H-COMBINED, each written before its runs, with every result and deviation (doc 14 §2, §8). Its first 64 960 bytes are, byte for byte, the copy taken before H-TEXT2's result was appended (`MANIFEST.txt`), so nothing written before that point was changed afterwards. |

## H-PAIRWORK: the comparison before the fix (doc 14 §6.1)

| File | What it is |
|---|---|
| `environment.txt` | The machine before the runs: CPU, core 3's governor and SMT sibling, the load, and the busiest processes |
| `functional-check.txt` | Bench v0 on R's dev build, B, C and D only, a few runs on a loaded machine: a check of the instrument |
| `functional-check-release.txt` | Bench v1 on R's release build, the same cases: a check of the release loader |
| `dev.{txt,json}`, `release.{txt,json}` | The registered runs on R (bench v1): A(512 … 4096), B, C and D, in the dev and the release build |
| `arm-ratios.txt` | Release over dev, per case, from the two runs |
| `compile-dev.log`, `compile-release.log` | R's two builds: test (dev) and cli (`:simple`), 0 warnings |

## The census and E2 (doc 14 §3.2, §7.2)

| File | What it is |
|---|---|
| `census-synthetic.{txt,json}` | Census c0 over experiment 13's 146 synthetic decks and the 9 test fixtures: runs per page, visits and matches per pair |
| `census-synthetic.list` | Its input: one `group<TAB>path` line per PDF, the paths relative ([Substitutions](#substitutions)) |
| `census-all.{txt,json}` | Census c1 with `--differential` (E2) on design F, over the same decks and 20 real decks of the author's own. The real decks are reported by group and count only; their list is not published (`MANIFEST.txt`). |
| `fprime/census-fprime.{txt,json}` | The same, on F′ |
| `strflood/census-text.{txt,json}` | Census c3: each page's text in UTF-16 code units, the measure the text cap bounds (most on a real page: 1 569) |
| `fsec/census-fsec.{txt,json}` | Census c3 with `--differential` on the final F″: 38 830 pairs, 0 that differ from R |

## Design F (doc 14 §4.2, §6.2, §7)

| File | What it is |
|---|---|
| `compile-fix-dev{,2,3}.log`, `compile-fix-release.log` | F's builds, 0 warnings |
| `unit-fix-1.log` | The unit build with E1's first draft: the draft's two failures are the ones the ledger records as its deviations (1) and (2) (doc 14 §8, item 5). The terminal SVG encoder's test also failed, with `ENOENT` on `resources/public/js/resvg.wasm`, a generated file the worktree had not built yet. |
| `unit-fix-2.log` | The unit build after the draft's two corrections: 863 tests, 44 385 assertions, 0 failures. It ran before `a-cell-key-names-one-cell` was added to E1. |
| `unit-e1-control.log` | E1 alone on F unmutated: the negative controls' control |
| `mutate.py`, `mutants.sh`, `mutants/summary.txt` | F's negative controls mE1 … mE5: the mutations, the runner, and each mutant's failing tests |
| `e3-files.txt`, `e3-deck-info.jsonl` | E3: the 155 PDFs (experiment 13's 146 and the 9 fixtures), and the output of `vv --cli --deck-info` over them. That output is byte-identical for R, F, F′ and F″ (sha256 `15044915…`, `MANIFEST.txt`), so it is published once (doc 14 §7.3). |
| `wait-quiet.sh` | The waiter: once the load is below 12, it runs another experiment's find-e2e re-run alone, and then F's bench |
| `quiet/fixed-{dev,release}.{txt,json}` | F's bench after the fix (bench v1): A, B, C and D (doc 14 §6.2) |
| `quiet/summary.txt` | The waiter's record: the loads, and each step's exit status. Its `find-e2e EXIT=1` line belongs to the other experiment's run, whose log is not data of this page. |

## H-FLOOD and H-FAR: design F under crafted cells (doc 14 §6.3)

| File | What it is |
|---|---|
| `flood/run.sh` | The runner: R (`wt-ref`) and F (`wt-pairwork`), release then dev, each arm gated on the load (bench v2) |
| `flood/ref-{release,dev}.{txt,json}`, `flood/F-{release,dev}.{txt,json}` | C, C-rand, C-flood and C-far on R and on F |
| `flood/summary.txt` | The ratios the predictions judge: C-flood / C-rand and C-far / C-rand per arm |
| `flood/functional-check-F-release.txt` | The shaped cases on F's release build, a few runs: a check of the instrument |
| `flood/probe-string-hash.{cjs,txt}` | The string probe in node: 4 096 keys of three kinds set and got in a fresh `js/Map` |
| `flood/ref-compile-test.log`, `flood/ref-release-cli.log` | R's builds in `wt-ref` |

## Design F′ (doc 14 §4.3, §6.4)

| File | What it is |
|---|---|
| `fprime/compile-1.log`, `fprime/compile-release.log` | F′'s builds, 0 warnings |
| `fprime/unit-e1-1.log` | E1 for F′ alone, its first run: 10 tests, 2 108 assertions, 0 failures |
| `fprime/nan-bits.cjs` | The premise of E1's test (f): the bit patterns of NaN that the comparison's arithmetic produces (doc 14 §9.1) |
| `mutate-fprime.py`, `mutants-fprime.sh`, `fprime/mutants/summary.txt` | F′'s negative controls m′1 … m′8 and mE1, mE2, mE4 |
| `fprime/unit-e1-control.log` | E1 alone on F′ restored: the control |
| `fprime/unit-all.log` | The whole unit build on F′: 870 tests, 46 483 assertions, 0 failures |
| `fprime/functional-check-release.txt` | The bench on F′'s release build, a few runs: a check, not a result |
| `fprime/bench/run.sh`, `environment.txt`, `summary.txt` | F′'s bench (bench v2): the runner, the machine and the bundles' sha256, and the medians the rules judge |
| `fprime/bench/fprime-{release,dev}.*`, `fprime-release-m{32768,16384,8192}.*` | F′ at the match cap 65 536, then at the registered fallback's match counts |
| `fprime/bench/{F,fprime}-electron-release.*` | The replication in Electron's V8: F and F′ |
| `fprime/bench/probe-string-hash-electron.txt` | The string probe in Electron's V8: array-index keys that share their low bits cost 295 times as much as random array-index keys |

## H-STRFLOOD (doc 14 §6.5; exploratory)

| File | What it is |
|---|---|
| `strflood/probe-string-keys.cjs` | Eleven patterns of 4 096 string keys, each set and got in a fresh `js/Map` |
| `strflood/node.txt`, `strflood/electron.txt` | Its medians in node's V8 and in Electron's, for ten patterns. The eleventh, "random index below 2^23", was skipped, because its keys were not all distinct (doc 14 §8, item 16) |

## Design F″ (doc 14 §4.4, §6.6–§6.8)

| File | What it is |
|---|---|
| `fsec/compile-1.log` | E1's first compile for F″. It failed on a docstring's unescaped quotes, which the compiler reports as a malformed `defn-` (`cap-cases`), and wrote no build. |
| `fsec/unit-e1-1.log` | The E1 run chained after that compile. It ran on the build already in the worktree, F′'s, so it is F′'s E1 on F′'s code (10 tests, 2 108 assertions), not a run of F″. The ledger's correction (3) records this, and no result rests on it. |
| `fsec/compile-{2,3}.log`, `fsec/compile-release-1.log` | F″'s builds after the fix, 0 warnings |
| `fsec/unit-e1-2.log` | E1 alone on F″: 14 tests, 2 126 assertions, 0 failures |
| `fsec/unit-all-1.log` | The whole unit build on F″: 874 tests, 46 501 assertions, 0 failures |
| `fsec/functional-check-release.txt`, `functional-check-fprime-S-{node,electron}.txt` | Bench v3 on F″ and on F′'s S shapes, a few runs: checks |
| `fsec/prof/S-release-self.txt` | Self time by function over the S shapes on F″'s release build (descriptive) |
| `fsec/bench/run.sh`, `environment.txt`, `summary.txt` | F″'s bench (bench v3) and what Q1″, Q2⁗, Q5 and Q6 judge |
| `fsec/bench/fsec-{node,electron}-release.*`, `fsec-node-dev.*` | A, B, C, D, the shaped cases and S, per engine and build |
| `fsec/bench/fsec-{node,electron}-release-L{32768 … 1048576}.*` | Q6: the L shapes at each text size T |
| `fsec/bench/fprime-{node,electron}-release-S.*` | Q5: F′'s S shapes, in each engine |
| `fsec/bench/fsec-node-release-cap65536.*` | F″ with the match cap rebound to 65 536 (descriptive) |
| `fsec/text2/` | H-TEXT2 (bench v4): its runner, environment, summary, a check, and the four L shapes at each T in each engine |
| `fsec/combined/` | H-COMBINED (bench v5): its runner, environment, summary, a check, and the X shapes at each (T, M) in each engine (36 arms) |

The final F″ (the adopted caps; doc 14 §7):

| File | What it is |
|---|---|
| `fsec/compile-final-test.log`, `fsec/compile-final-release.log` | Its builds, 0 warnings |
| `fsec/unit-e1-final.log` | E1 alone: 14 tests, 2 126 assertions, 0 failures |
| `mutate-fsec.py`, `mutants-fsec.sh`, `fsec/mutants/summary.txt` | Its 18 negative controls (m″1 … m″7, m′1 … m′8, mE1, mE2, mE4), each killed (doc 14 §7.1) |
| `fsec/mutants/unit-control.log` | E1 alone on the final F″ restored: the control |
| `fsec/unit-all-final.log` | The whole unit build: 874 tests, 46 501 assertions, 0 failures |

## The designs (`designs/`)

Each design is a diff, so that it reads as the change it made, and each is checked to rebuild exactly the file that was
measured or verified (the sha256 column).

| Diff | From → to | The result's sha256 |
|---|---|---|
| `R-to-F.diff` | R (`2b78f18`) → design F | that of the F measured in §6.2 and §6.3 (`MANIFEST.txt`, `notes-fixed.cljs`) |
| `R-to-Fprime.diff` | R → design F′ | `3ffb781d…` (`fprime/bench/environment.txt`) |
| `R-to-Fsec-benched.diff` | R → F″ as benched in §6.6 (`max-signature-chars` 32 768) | `6c259e70…` (`fsec/bench/environment.txt`) |
| `Fsec-benched-to-Fsec.diff` | that → the final F″: `max-signature-chars` 65 536 and two docstrings | `9e19b3d3…` (the ledger, the final F″) |
| `Fsec-to-committed.diff` | the final F″ → the committed `notes.cljs`: comments and docstrings only, its own and those the previous commit (`49f7eb5`) made to the file | `ed1ff22d…` |
| `E1-F-to-Fprime.diff` | E1 as it tested F → E1 as it tested F′ | that of `equivalence-test-F.cljs` (`MANIFEST.txt`) |
| `E1-Fprime-to-committed.diff` | E1 as it tested F′ → the committed E1, which tested F″ | that of `equivalence-test-fprime.cljs` (`MANIFEST.txt`) |

The designs rebuild forward from R. E1's two earlier versions rebuild backward from the committed test, because E1 did
not exist before the fix:

```bash
git show 2b78f18:src/vinary/slides/notes.cljs > notes.cljs
patch notes.cljs < designs/R-to-Fsec-benched.diff       # F″ as benched: sha256 6c259e70…
patch notes.cljs < designs/Fsec-benched-to-Fsec.diff    # the final F″:  sha256 9e19b3d3…

cp test/vinary/slides/notes_signature_equivalence_test.cljs e1.cljs
patch -R e1.cljs < designs/E1-Fprime-to-committed.diff  # E1 as it tested F′
patch -R e1.cljs < designs/E1-F-to-Fprime.diff          # E1 as it tested F
```

The committed `notes.cljs` compiles to the same tokens as the final F″ (doc 14 §7.4).

## The instruments (`instruments/`)

The benchmark `scripts/bench-note-signature.cjs` and the census `scripts/exp-note-signature-census.cjs` were extended
as the experiment went on, each time before the runs that needed the extension. They were never changed between a
registration and its runs. Every version that produced a published file is listed here. It is rebuilt from the
committed script by reversing the diffs, and is checked against its sha256.

```
 the benchmark                                          the census
 ─────────────                                          ──────────
 v0 b6381a2d ··· functional-check.txt                   c0 730783f6 ··· census-synthetic.*
  │  + the release loader (--bundle=release)             │  + --differential: E2
 v1 a867abf2 ··· functional-check-release.txt,          c1 ce625176 ··· census-all.*, fprime/census-fprime.*
  │              dev.*, release.*, quiet/fixed-*          │  + each page's text, every string item
  │  + rand, flood, far; --matches                      c2 47370c85 ··· (one run; c3's re-run replaced its output)
 v2 f83f7c2a ··· flood/*, fprime/functional-check-*,      │  the text of the runs only, as the code counts it
  │              fprime/bench/*                         c3 8a4667f9 ··· strflood/census-text.*, fsec/census-fsec.*
  │  + S, L-prefix, L-equal; --cap, --chars              │  comments only
 v3 e159291b ··· fsec/functional-check-*, fsec/prof/*,  committed 06d83a8a
  │              fsec/bench/*
  │  + L-wide, L-mixed
 v4 77d0a6e1 ··· fsec/text2/*
  │  + X-prefix, X-equal, X-wide, X-mixed
 v5 df52a76b ··· fsec/combined/*
  │  comments only
 committed ff42f899
```

| Version | sha256 | Written | How it is known |
|---|---|---|---|
| bench v0 | `b6381a2d…` | 11:15 | recovered (below) |
| bench v1 | `a867abf2…` | 11:17 | recovered (below) |
| bench v2 | `f83f7c2a…` | 12:11–12:13 | a copy on disk (`MANIFEST.txt`, `bench-before-fsec.cjs`) |
| bench v3 | `e159291b…` | 13:30 | a copy on disk (`bench-before-text2.cjs`); the ledger, F″'s bench |
| bench v4 | `77d0a6e1…` | 13:36 | a copy on disk (`bench-before-combined.cjs`); the ledger, H-TEXT2 |
| bench v5 | `df52a76b…` | 13:46 | the ledger, H-COMBINED; `fsec/combined/environment.txt` |
| bench, committed | `ff42f899…` | — | `scripts/bench-note-signature.cjs`: comments only, the same tokens as v5 |
| census c0 | `730783f6…` | 11:26 | recovered (below) |
| census c1 | `ce625176…` | 11:42 | a copy on disk |
| census c2 | `47370c85…` | 13:18 | recovered (below) |
| census c3 | `8a4667f9…` | 13:21 | a copy on disk |
| census, committed | `06d83a8a…` | — | `scripts/exp-note-signature-census.cjs`: comments only, the same tokens as c3 |

```bash
cp scripts/bench-note-signature.cjs bench.cjs
for d in v5-to-committed v4-to-v5 v3-to-v4 v2-to-v3 v1-to-v2 v0-to-v1; do
  patch -R bench.cjs < instruments/bench-$d.diff && sha256sum bench.cjs   # v5, v4, …, v0 in turn
done
cp scripts/exp-note-signature-census.cjs census.cjs
for d in c3-to-committed c2-to-c3 c1-to-c2 c0-to-c1; do
  patch -R census.cjs < instruments/census-$d.diff && sha256sum census.cjs
done
```

**The recovered versions.** Bench v0 and v1, census c0 and c2, and F″ as benched were never saved as files. Each was
recovered from the editing session's transcript, which holds the exact input of every file it wrote and every text
replacement it made, in order. Each recovery is verified by replaying the transcript past the recovered version, to a
version that was saved on disk, and comparing the two byte for byte:

- **The benchmark.** The script's creation is v0, its first replacement makes v1, and the replacements after it
  rebuild v2 exactly (`f83f7c2a…`, the copy on disk).
- **The census.** Its creation is c0. The three replacements that follow rebuild c1 (`ce625176…`), then c2, then c3
  (`8a4667f9…`), c1 and c3 exactly.
- **F″.** F′ (`notes-fprime.cljs`), with the transcript's rewrite applied, gives `6c259e70…`. That is the sha256 that
  `fsec/bench/environment.txt` printed for the source the bench measured. The two replacements after it then give
  the final F″ exactly (`9e19b3d3…`, `notes-fsec.cljs`).

The outputs agree with the recovered instruments as well. Bench v1 is the last version without `--matches`. The four
benchmark JSONs it wrote (`dev.json`, `release.json`, `quiet/fixed-*.json`) lack the `matches` key, and the 77 written
from v2 on all have it. A short run of the recovered v1 on R's builds printed the same visit counts and results as `dev.txt` (B 69 632
visits and nil, C 65 536 and false, D 60 and true). That run was a check of the recovery, not a measurement, and
nothing from it is published.

## Substitutions

The scratch directory and the repository have local absolute paths. Every file that named one was copied with that
path replaced, and with nothing else changed. `MANIFEST.txt` gives the original's sha256 for each such file (33 in
all), marked `published, paths short`.

| In | The scratch directory | The repository |
|---|---|---|
| logs and other text files | `<scratch>` | `<repo>` |
| the runner scripts (`*.sh`) | `J=${SCRATCH:?…}`: the script stops unless `SCRATCH` is set | `${REPO:?…}`: likewise |
| `e3-files.txt`, `e3-deck-info.jsonl`, `census-synthetic.list` | dropped: `corpus13/…` is relative to it | dropped: `test/fixtures/…` is relative to it |

## Re-running a runner

Each runner reads and writes paths under `SCRATCH`, in the layout the experiment used:

```
$SCRATCH/
├── pairwork/          this directory: the runners append to the files named here
├── wt-pairwork/       a worktree of 2b78f18 with the design under test: notes.cljs, E1 and its reference
│                      namespace, both scripts, the test build (dist/test) and the cli build (dist/cli)
├── wt-ref/            a worktree whose notes.cljs is R, with both builds (flood/run.sh)
├── benchF-root/       scripts/bench-note-signature.cjs and dist/cli/vv-cli.js built from F (fprime/bench/run.sh)
├── benchFprime-root/  the same, built from F′ (fsec/bench/run.sh)
├── wt-split/          the other experiment's worktree, for wait-quiet.sh's first step
└── tmpdir/            TMPDIR for the builds
```

Set `SCRATCH` and `REPO` (`REPO` provides Electron, at `$REPO/node_modules/electron/dist/electron`), build the worktrees'
bundles as doc 14 §11 shows, and pin as the scripts do: the timed arms on core 3, everything else on 12–31. Doc 14
§11 gives the bench's and the census's command lines directly.
