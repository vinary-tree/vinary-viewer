# 08 — CI and validation discipline

Where the previous pages describe the individual gates — the [test taxonomy](03-test-strategy.md),
the [lint checks](04-lint-and-conventions.md), the [documentation gates](07-docs-and-screenshot-gates.md) —
this page describes how they are (and are not) *orchestrated*, and the manual discipline that stands in for
an automated pipeline today.

> **Term.** *CI (continuous integration)* is the practice of running a project's build, tests, and checks
> automatically on every change, so that a regression is caught by a machine rather than by a human
> remembering to run something. A *CI matrix* is the set of (host, target, build-variant) combinations that
> practice is run across.

---

## 1. Finding: there is no CI configuration in the repository

Stated plainly, because scientific integrity requires naming an absent measurement rather than implying one
(the same rule the [scientific pillar](../scientific/00-overview.md) applies to the missing benchmark harness):

**vinary-viewer ships no CI config.** There is no `.github/workflows/`, no `.gitlab-ci.yml`, no
`.circleci/`, no Jenkinsfile, and no pre-commit/`husky` hook. Every gate documented in this pillar exists and
works, but each is invoked **by hand**. You can confirm the absence yourself:

```bash
# From the repo root — all of these should list nothing:
ls .github/workflows/ .gitlab-ci.yml .circleci/ Jenkinsfile 2>/dev/null
git ls-files | grep -Ei 'workflow|\.ci\.|pre-commit'
```

This is a real, load-bearing gap: nothing prevents a change that breaks the Electron smoke or the theme lint
from being committed, because nothing runs them except a contributor's discipline. The rest of this page
documents that discipline, and specifies exactly what a CI matrix *should* run so the gap can be closed
mechanically rather than re-discovered.

---

## 2. The standing discipline: tee once, inspect, then act

The project's benchmarking and validation convention (recorded in `CLAUDE.md` and echoed in
[`usage/02`](../usage/02-installation-and-build.md)) is: **run a validation command once, capture its full
output to a file, analyze the file, and remove it when done** — rather than re-running the command to see
different parts of its output. This keeps a flaky or slow gate from being run several times, and gives a
single artifact to inspect.

```bash
# Run the full test suite once, keep the transcript, inspect it, then discard it.
npm test 2>&1 | tee /tmp/vv-test.log
grep -nE 'FAIL|Error|✗|✘' /tmp/vv-test.log      # triage failures from the one run
rm /tmp/vv-test.log
```

The same pattern applies to the [diagram render gates](07-docs-and-screenshot-gates.md) and the
[smoke harnesses](03-test-strategy.md): capture, inspect, delete. The discipline is not a substitute for
CI — it is what makes the *absence* of CI survivable, and it is the behavior a CI job would automate.

---

## 3. The gates, and where each lives

A CI job is only as good as the checks it runs. The project already has all the checks; they are simply not
wired together. The table collects them so a pipeline author has the complete list in one place.

| Gate | Command | What it proves | Documented in |
|------|---------|----------------|---------------|
| **Lint** | `node test/lint.js` | JS parses; CSS braces balance; every `--vv-*` token is defined by every theme | [04](04-lint-and-conventions.md) |
| **Unit + property tests** | `npm test` | DOM-free ClojureScript invariants (semiring laws, HAST parity, bounded memory, classifier kinds) + the Node smokes it chains | [03](03-test-strategy.md), [scientific/00](../scientific/00-overview.md) |
| **Electron smoke (dev)** | `npm run test:electron` | real-DOM byte-parity, CSP-console gate, `streamCount → 0`, XSS strip | [03](03-test-strategy.md) |
| **Electron smoke (release)** | `npm run test:electron:release` | the same, against a `:simple`-optimized build (catches interop the dev build hides) | [03](03-test-strategy.md), [01](01-build-system.md) |
| **Extension smokes** | `npm run test:extensions` / `:sandbox` | the scoped extension runtime loads and is sandboxed | [03](03-test-strategy.md) |
| **Asset provenance** | `npm run assets:check` · `pdfjs:check` · `grammars:check` | vendored files match their pinned `*.lock.json` sha256 | [02](02-asset-vendoring.md) |
| **Diagram render** | `plantuml -tsvg docs/diagrams/*.puml` + the `deprecated`/`contains errors` greps | no diagram ships a banner baked into its SVG | [07](07-docs-and-screenshot-gates.md) |
| **Diagram catalog honesty** | the two shell loops in `docs/diagrams/README.md` §5 | every `.puml` is catalogued and embedded | [07](07-docs-and-screenshot-gates.md) |

![The test taxonomy the CI matrix would run](../diagrams/component-test-taxonomy.svg)

*Diagram source: [`../diagrams/component-test-taxonomy.puml`](../diagrams/component-test-taxonomy.puml).*

---

## 4. The CI matrix the project should run

When CI is added, it should be a single Linux-x11 workflow (the only tested platform;
[`CHANGELOG`](../../CHANGELOG.txt) notes "Linux + X11 tested (Electron 42)") with these jobs, ordered
cheapest-first so a fast failure short-circuits the expensive ones:

```
CI (ubuntu-latest, x11 via xvfb)
├── setup      : node + JDK (shadow-cljs needs a JVM) + graphviz + plantuml
├── lint       : node test/lint.js
├── provenance : npm run assets:check && npm run pdfjs:check && npm run grammars:check
├── unit       : npm test                      # compiles :test, runs it + the chained smokes
├── smoke-dev  : xvfb-run npm run test:electron
├── smoke-rel  : xvfb-run npm run test:electron:release     # the release build's :simple interop
├── extensions : xvfb-run npm run test:extensions
└── docs       : plantuml -tsvg docs/diagrams/*.puml
                 ! grep -lF deprecated        docs/diagrams/*.svg    # must be empty
                 ! grep -lF "contains errors" docs/diagrams/*.svg    # must be empty
                 + the catalog-honesty loops from docs/diagrams/README.md §5
```

Two properties make this matrix worth automating specifically:

1. **The release smoke is not redundant with the dev smoke.** Closure `:simple` optimization renames and
   encapsulates enough that a test driving a global (`re_frame.core.dispatch_sync`) passes in dev and dies in
   release — exactly the failure the CHANGELOG records for the bidirectional-jump smoke. Only running *both*
   variants catches it. See [01](01-build-system.md) and [03](03-test-strategy.md).
2. **The docs job is a correctness gate, not a nicety.** PlantUML exits `0` even when it bakes a "deprecated
   syntax" banner into the SVG (see [07](07-docs-and-screenshot-gates.md)); without the greps, a broken
   diagram ships silently. This is precisely how three activity diagrams shipped bannered before the gate
   existed ([`CHANGELOG`](../../CHANGELOG.txt)).

Until such a workflow exists, treat this table and matrix as the **manual pre-merge checklist**: running it by
hand, tee-ing each command per §2, is the current substitute.

---

## 5. See also

- [03 — Test strategy](03-test-strategy.md) — the taxonomy of what these jobs run.
- [07 — Docs and screenshot gates](07-docs-and-screenshot-gates.md) — the diagram job in detail.
- [09 — Contribution workflow](09-contribution-workflow.md) — what a contributor runs before opening a PR.
- [scientific/00](../scientific/00-overview.md) — the verification-first philosophy these gates implement.
