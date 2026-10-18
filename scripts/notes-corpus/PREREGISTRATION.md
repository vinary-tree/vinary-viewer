# Pre-registration — H-NOTES-CORPUS (speaker-note detection on real TeX output)

**Registered:** 2026-10-18, before the detector ran on any file of this corpus.

**Registered `expectations.json` sha256: `e3f8ada158b242080db95791064e5bf5076666e72f82ae790f9a2dc3f332d8d2`**
(`sha256sum scripts/notes-corpus/expectations.json`; the experiment script prints the hash of the file it reads and
whether it matches this line).

The record of the run, its results and its deviations is
[docs/scientific/13 — Speaker-note detection](../../docs/scientific/13-speaker-note-detection.md). This file is not
edited after the run, and neither is `expectations.json`. An expectation that turns out to be wrong is recorded there
as a deviation, with its explanation.

## 1 · Terms

| Term | Meaning |
|---|---|
| deck | A PDF compiled from one of the Beamer sources in this directory. |
| note page | A whole page of speaker notes that Beamer's `\setbeameroption{show notes}` puts after the slide page it belongs to (ADR-0048 §1). Pages are numbered from 0. |
| artifact | One deck as one engine wrote it: a source (and note template), an engine and a number of passes. Its id is `<variant>.<engine>`, for example `i-templates-lined.xelatex2`. |
| engine | `pdflatex` (pdfLaTeX, one pass), `lualatex` (LuaLaTeX, one pass), `xelatex` (XeLaTeX, one pass), `xelatex2` (XeLaTeX, two passes in one directory). |
| label | The page label of a page, as pdf.js's `getPageLabels` returns it; `null` when the PDF has no `/PageLabels`. |
| the detector | `evidence/detect` (`src/vinary/slides/evidence.cljs`) over the notes model of `src/vinary/slides/notes.cljs`, as `vv --cli --deck-info` runs it from the development build `dist/cli/vv-cli.js`. |
| decision | The five fields `--deck-info` prints about notes and frames: `notes` (`interleaved`, `split` or `none`), `notePages` (how many pages are hidden), `notesProof` (the steps that found them), `notesReason` (why none are hidden) and `frames` (how many frames the audience steps through). |
| $`H`$ | The set of pages the detector hides: the model's `:note-pages`, read from the same compiled code (§6). |
| $`N`$ | The set of note pages according to the oracle (§4). |
| SIG, ROTATED-DATE | The note-page signature and the rotated-date test of ADR-0048 §2.2. |

## 2 · Question

On what pdfLaTeX, LuaLaTeX and XeLaTeX really write, does the detector reach the decision that ADR-0048 §2 predicts
for every deck, and is it safe: does it never hide a page that is a slide, and, when it hides pages, hide exactly the
note pages?

## 3 · Hypotheses

**H1 — decisions.** For every one of the 94 artifacts, `vv --cli --deck-info` prints the `notes`, `notePages`,
`notesProof`, `notesReason` and `frames` that `expectations.json` gives it. H1 is refuted by any artifact with any
differing field.

**H2 — safety.** For every artifact,

```math
\text{S1:}\quad H \subseteq N, \qquad\qquad \text{S2:}\quad H \neq \emptyset \;\Rightarrow\; H = N .
```

S1 says that no slide is ever hidden from the audience. S2 says that a deck whose note pages are hidden has all of them
hidden. H2 is refuted by one artifact that violates S1 or S2. H2 does not ask for completeness: a deck with note pages
that is shown as it is ($`H = \emptyset \neq N`$) satisfies it. ADR-0048 accepts that outcome in several places, and
the expectations predict it for 28 artifacts (§7).

**Registered secondary predictions**, reported beside H1 and H2:

- **P-labels.** Every artifact's labels equal the labels in `expectations.json` (they were read in the pre-look, §8;
  the run re-reads them from freshly compiled PDFs).
- **P-hidden.** Every artifact's $`H`$ equals the `hidden` set in `expectations.json`.
- **P-instrument.** For every artifact, the hidden set's size, style, proof and reason, read from the compiled
  namespaces, equal the `--deck-info` line's `notePages`, `notes`, `notesProof` and `notesReason`.
- **P-twin.** The oracle's assumption M4 holds (§4).

## 4 · The oracle

Every `\note` in the corpus contains a marker token `NOTEMARK<k>X`, unique to that `\note` command. The oracle reads
every page's text with pdf.js (`node_modules/pdfjs-dist/legacy/build/pdf.mjs`, `getTextContent`), joins the items'
strings, and finds the tokens. It shares no code with the detector.

1. **Markers.** A page is a note page iff its text contains a marker token. This is the rule for every artifact except
   the two below.
2. **The twin rule.** The `lined` note template draws ruled lines and no note text, so a `lined` artifact has no marker
   to find. Its $`N`$ is the $`N`$ of its **twin**: the `default`-template artifact of the same source, engine and
   passes, which must have the same page count.
3. **The two-screen rule.** A `show notes on second screen` deck puts a slide half and a notes half on every page
   (ADR-0048 §1). It has no note pages: $`N = \emptyset`$. This rule applies only when every page doubles the Beamer
   paper in width ($`725.67 \times 272.13`$ bp within 0.5 bp).

**M4, the twin rule's assumption:** the note template does not change which pages are note pages. Beamer chooses the
pages in `beamerbasenotes.sty` and the template only draws them. The experiment checks M4 on every templated source and
engine: the templates whose note text is drawn (`default`, `compressed`, `plain`) must mark the same pages, and every
template must give the same page count. If M4 fails anywhere, the `lined` artifacts' oracle is void.

## 5 · The decision rules applied

`expectations.json` was written by [`derive-expectations.cjs`](derive-expectations.cjs) from the labels and the oracle
of a `--no-detector` run (§8). That script loads nothing from `src/` or `dist/`. It applies these rules.

**The label automaton (ADR-0048 §2.1), by brute force.** Every assignment of the roles *slide* and *note* to the pages
is tried, and an assignment is a valid parse when it obeys the three rules verbatim:

1. page 0 is a slide, and the current frame number is $`f := L_0`$;
2. a slide carries $`L_i \in \{f, f+1\}`$, and then $`f := L_i`$;
3. a note page is a page $`i \ge 1`$ with $`L_i = i + 1`$, and leaves $`f`$ unchanged.

This is independent of the detector's gap decomposition (`interleaving`). The result follows the ADR's table:

| Valid parses | Status |
|---|---|
| none | `invalid` |
| exactly one, without note pages | `none` |
| exactly one, with note pages | `proven` |
| several, and $`L_0 \ge 2`$ | `undecidable` |
| several, $`L_0 \le 1`$, differing only in a first note page $`a`$ inside the plain run (pages $`1 \dots m`$ with $`L_j = j + 1`$), every page from $`a`$ to $`m`$ being a note page | `ambiguous`: the candidates are those first note pages, and the *fallback* is the parse with no note page in the run, when it is valid |
| several, otherwise | `undecidable` |

The automaton runs only when every label is a canonical non-negative integer. Otherwise the reason is
`labels-not-integers`.

**The plan and the settle table** (`notes/plan`, `evidence/settle`, ADR-0048 §2.3–§2.5):

| Situation | Work | Outcome → decision |
|---|---|---|
| every page doubles a Beamer paper | — | `split`, no proof, no reason |
| no labels | the scan | note pages found → `interleaved`, `[scan]`; none → `none`, `no-note-pages` |
| `proven`, the Producer names pdfTeX, LuaTeX or LuaHBTeX, and the labels are not `restartable?` | — | `interleaved`, `[labels]` |
| `proven` otherwise | the confirmation | passes → `interleaved`, `[labels, confirm]`; fails → `none`, `confirm-failed` |
| `ambiguous` | the probe | no valid choice → `none`, `probe-undecided`; the fallback without note pages → `none`, `no-note-pages`; a candidate, trusted producer → `interleaved`, `[labels, probe]`; a candidate, other producer → the confirmation: `[labels, probe, confirm]` or `none`, `confirm-failed` |
| `none`, `invalid`, `undecidable` | — | `none`, with the status as the reason (`none` reads `no-note-pages`) |

`restartable?` holds when every label is at most one more than the label before it. **The probe** walks the candidates
from the last to the first. For candidate $`a`$ it predicts SIG of the pair $`(a-1, a)`$: if it holds, $`a`$ is
chosen; if not, but page $`a`$ is dated, the walk goes on; otherwise it stops at the fallback. **The confirmation**
checks every note page $`j`$ of the parse against $`s`$, the parse's last slide before $`j`$: it passes when every
SIG$`(s, j)`$ holds or $`j`$ is dated. **The scan** compares page $`j`$ with the last page it kept as a slide, and
keeps $`j`$ as a note page when SIG holds.

**Frames** (ADR-0045, `deck/deck`): with no hidden page, the maximal runs of adjacent pages that share a non-blank
label; with hidden pages, the same over the shown pages only; one frame per (shown) page without labels; one frame per
page on a two-screen deck.

**The content model.** SIG and ROTATED-DATE are not computed from the pages. They are predicted from what Beamer's
note templates draw (`beamerouterthemedefault.sty`, `beamerbaseauxtemplates.sty`, `beamerbasenotes.sty`):

| Id | Prediction |
|---|---|
| M1 | Under `default` and `compressed`, a note page $`j`$ shows a thumbnail of $`t(j)`$, the last slide page before it (`\beamer@frameboxcopy`). `plain` and `lined` draw none. |
| M2 | ROTATED-DATE$`(j)`$ holds exactly for the note pages of the `default` template. |
| M3t | When $`s`$ has no text, SIG$`(s, j)`$ = ROTATED-DATE$`(j)`$ if $`j`$ has text, and `nil` (no evidence) if it has none. |
| M3a | SIG$`(s, j)`$ holds when $`j`$ is a note page with a thumbnail of $`t(j) = s`$. |
| M3b | SIG$`(s, j)`$ holds when $`j`$'s thumbnail is a later overlay of $`s`$'s own frame, which repeats $`s`$'s text. |
| M3c | SIG$`(s, j)`$ fails when $`s`$ is a note page. Its thumbnail repeats on $`j`$ at $`\sigma = 1`$ or not at all, and only its short frame title can match $`j`$'s thumbnail below the bound $`\sigma \le 0.4`$, far less than half of its characters. |
| M3d | SIG$`(s, j)`$ fails when $`j`$ is a slide: it repeats $`s`$'s strings at $`\sigma = 1`$ or not at all. |
| M3e | SIG$`(s, j)`$ fails when $`j`$ is a note page without a thumbnail, or with another frame's. |

Every predicted read is stored in `expectations.json` (`derivation.reads`, with the rule that predicted it). M2 was
checked against the input before registration. Every page of every artifact that is not two-screen has the rotated
date exactly when M2 says so: `contentModelM2Exceptions` is empty.

## 6 · The instruments

- **`vv --cli --deck-info`.** `node dist/cli/vv-cli.js --deck-info <pdf>… < /dev/null`, one call for every PDF. It is
  the GUI's own pipeline (ADR-0048 N6), and it prints the decision.
- **The hidden set $`H`$.** `--deck-info` prints only how many pages are hidden. `scripts/exp-notes-corpus.cjs` loads
  the same development bundle in a child process, without running its `main`, and calls `evidence/detect` as
  `cli.core/deck-info` does. It reads the model's `:note-pages`. P-instrument checks the two readings against each
  other.

## 7 · The expected decisions

Abbreviations: `hide {…}` is $`H`$; `L` labels, `P` probe, `C` confirm, `S` scan; `fN` is the frame count. The labels
are pdfLaTeX's. LuaLaTeX and two XeLaTeX passes wrote the same labels, and one XeLaTeX pass wrote none, except that
`j-only-notes` has none under either XeLaTeX run (`show only notes` sets `\nofiles`).

| Variant | Labels | pdflatex | lualatex | xelatex (1 pass) | xelatex2 |
|---|---|---|---|---|---|
| `a-last-note` | 1 2 3 4 5 | hide {4} · L+P · f4 | hide {4} · L+P · f4 | hide {4} · S · f4 | hide {4} · L+P+C · f4 |
| `b-no-notes` | 1 2 3 4 | none · no-note-pages · f4 | the same | the same | the same |
| `c-overlays` | 1 2 1 4 2 2 7 3 | hide {1,3,6} · L · f3 | hide {1,3,6} · L · f3 | hide {1,3,6} · S · f5 | hide {1,3,6} · L+C · f3 |
| `d-between` | 1 1 2 4 5 3 | hide {3,4} · L · f3 | hide {3,4} · L · f3 | hide {3,4} · S · f4 | hide {3,4} · L+C · f3 |
| `e-againframe` | 1 1 3 2 3 6 4 | hide {2,5} · L · f4 | hide {2,5} · L · f4 | hide {2,5} · S · f5 | hide {2,5} · L+C · f4 |
| `f-noframenumbering` | 1 1 3 2 | hide {2} · L · f2 | hide {2} · L · f2 | hide {2} · S · f3 | hide {2} · L+C · f2 |
| `f0-unnumbered-first` | 0 2 1 2 | hide {1} · L · f3 | hide {1} · L · f3 | hide {1} · S · f3 | hide {1} · L+C · f3 |
| `g-appendix-no-notes` | 1 2 3 4 5 1 2 | none · confirm-failed · f7 | none · confirm-failed · f7 | none · no-note-pages · f7 | none · confirm-failed · f7 |
| `h-appendix-notes` | 1 2 3 3 1 6 2 | none · invalid · f6 | none · invalid · f6 | hide {2,5} · S · f5 | none · invalid · f6 |
| `i-templates-default` | 1 2 1 4 5 2 3 3 9 4 | hide {1,3,4,8} · L · f4 | hide {1,3,4,8} · L · f4 | hide {1,3,4,8} · S · f6 | hide {1,3,4,8} · L+C · f4 |
| `i-templates-compressed` | the same | hide {1,3,4,8} · L · f4 | hide {1,3,4,8} · L · f4 | hide {1,3,4,8} · S · f6 | hide {1,3,4,8} · L+C · f4 |
| `i-templates-plain` | the same | hide {1,3,4,8} · L · f4 | hide {1,3,4,8} · L · f4 | none · no-note-pages · f10 | none · confirm-failed · f9 |
| `i-templates-lined` | the same | hide {1,3,4,8} · L · f4 | hide {1,3,4,8} · L · f4 | none · no-note-pages · f10 | none · confirm-failed · f9 |
| `j-only-notes` | 1 2 3 4 | none · no-note-pages · f4 | the same | the same | the same |
| `k-counter-set` | 2 2 3 3 4 6 | none · undecidable · f4 | none · undecidable · f4 | hide {2,5} · S · f4 | none · undecidable · f4 |
| `l-second-screen` | 1 1 2 2 | split · f4 | split · f4 | split · f4 | split · f4 |
| `m-empty-slide-default` | 1 2 3 4 | hide {3} · L+P · f3 | hide {3} · L+P · f3 | hide {3} · S · f3 | hide {3} · L+P+C · f3 |
| `m-empty-slide-compressed` | 1 2 3 4 | none · no-note-pages · f4 | the same | the same | the same |
| `n-trailing-notes-default` | 1 2 3 4 5 | hide {3,4} · L+P · f3 | hide {3,4} · L+P · f3 | hide {3,4} · S · f3 | hide {3,4} · L+P+C · f3 |
| `n-trailing-notes-compressed` | 1 2 3 4 5 | none · no-note-pages · f5 | none · no-note-pages · f5 | hide {3,4} · S · f3 | none · no-note-pages · f5 |
| `o-run-between-default` | 1 2 3 2 2 3 | hide {1,2} · L+P · f3 | hide {1,2} · L+P · f3 | hide {1,2} · S · f4 | hide {1,2} · L+P+C · f3 |
| `o-run-between-compressed` | 1 2 3 2 2 3 | none · probe-undecided · f5 | none · probe-undecided · f5 | hide {1,2} · S · f4 | none · probe-undecided · f5 |
| `p-roman-numbers` | I 2 II III 5 | none · labels-not-integers · f5 | the same | hide {1,4} · S · f3 | none · labels-not-integers · f5 |

**The stale-labels case.** `stale-v1.tex` is compiled twice as `stale.tex`. It is replaced by `stale-v2.tex`, which
adds the frame *Inserted* after the first frame, and compiled once more.

- **`stale.xelatex`.** The last pass wrote v1's labels `1 2 1 4 2 3 7 8`. The automaton proves the parse
  $`\{1, 3, 6, 7\}`$, but page 6 is the slide *Three*. The producer is xdvipdfmx, so the parse is confirmed. The
  confirmation fails at the pair (5, 6): SIG fails by M3d, and page 6 has no date. Expected: `none`,
  `confirm-failed`, f8.
- **`stale.lualatex`** (the control). LuaLaTeX wrote v2's own labels `1 2 1 4 2 3 4 8`. Expected: hide
  $`\{1, 3, 7\}`$, `[labels]`, f4.

**Expected safety classes** (from `hidden` against the pre-look's $`N`$): 54 *exact* ($`H = N \neq \emptyset`$),
12 *no-notes* ($`H = N = \emptyset`$), 28 *shown as it is* ($`H = \emptyset \neq N`$), 0 *unsafe*.

**Decision paths covered:** `[labels]` 19, `[labels, confirm]` 7, `[labels, probe]` 8, `[labels, probe, confirm]` 4,
`[scan]` 16, `no-note-pages` 18, `confirm-failed` 6, `invalid` 3, `undecidable` 3, `probe-undecided` 3,
`labels-not-integers` 3, `split` 4. The paths `too-many-pages`, `unreadable`, `error`, `not-beamer` and `one-page`
are not exercised.

## 8 · What was looked at before registering

Only the input. `scripts/exp-notes-corpus.cjs --no-detector` compiles the corpus and runs the oracle, then stops. It
neither runs `--deck-info` nor loads the bundle.

1. **Pre-look 1 and 2.** These used the corpus without `o-run-between` and `p-roman-numbers`: 82 artifacts. The
   second run only added two fields to the output, and its table of labels and note pages was identical to the
   first's.
2. **The first derivation.** It covered those 82 artifacts and was not registered. That draft had sha256
   `820167bf64d348f3b52f5a81433dfa268df397fdcb9a24fe805ee1a7ff65c1dc`. Before anything was registered, the corpus gained
   `o-run-between` (the only source whose probe can be undecided) and `p-roman-numbers` (labels that are not integers),
   so as to cover two more paths of the settle table.
3. **Pre-look 3.** It ran on the final corpus of 94 artifacts. Its 82 earlier rows were identical to pre-look 2's, and
   the 12 new rows had the labels predicted from Beamer's rules. The derivation of the 82 earlier artifacts did not
   change.

The page texts were read for the facts the content model rests on:

- overlay 1 does not carry the text of overlay 2;
- the `default` note page carries the thumbnail, the date, the short title, the frame title and the note;
- `compressed` carries the thumbnail, the frame title and the note;
- `plain` carries the note only;
- `lined` carries no text at all;
- the empty slide has no text.

## 9 · Analysis plan

- One run, into a fresh directory. Every TeX run is pinned with `taskset -c 16-23`. The run prints a table per
  artifact: labels, expected decision, observed decision, $`N`$, $`H`$ and its safety class. It writes the full results
  as JSON.
- Results are reported as counts ($`k`$ of $`n`$). Nothing is stated as a percentage confidence.
- Every disagreement is analysed without changing any code. The analysis re-reads the code paths that ran, inspects the
  artifact's labels and page text, and names the rule that fired. A disagreement is attributed in one of three ways:
  - **the detector**: it departs from ADR-0048's rules;
  - **the expectation**: a content-model prediction (M1–M3) or a derivation error, recorded as a deviation;
  - **the oracle**: the marker, twin or two-screen rule.
- A violation of S1, a hidden slide, is reported first.

## 10 · Procedure

```bash
npm run compile:cli:test                     # the development build of vv --cli (its namespaces are reachable)
TMPDIR=<scratch> node scripts/exp-notes-corpus.cjs --no-detector --out=<dir> --json=<prelook.json>
node scripts/notes-corpus/derive-expectations.cjs <prelook.json> scripts/notes-corpus/expectations.json
sha256sum scripts/notes-corpus/expectations.json   # → this file, then never edited again
TMPDIR=<scratch> node scripts/exp-notes-corpus.cjs --out=<fresh dir> --json=<results.json> | tee <run.txt>
```
