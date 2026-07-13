# 09 — Contribution workflow

This page is the standing reference for *how a change enters vinary-viewer*: the commit convention, the
pull-request expectations, when a decision needs an ADR, and the documentation guidelines every contributed
document obeys. It collects into one operational checklist what the [ADR log](../design-decisions/README.md),
[`AGENTS.md`](../../AGENTS.md), and the [suite conventions](../README.md) state individually.

---

## 1. Commits: Conventional Commits

The project follows the **Conventional Commits** style (`AGENTS.md`, and visible throughout `git log`): a
commit subject is `<type>(<scope>): <imperative summary>`.

| Type | Used for | Example (from history) |
|------|----------|------------------------|
| `feat` | a user-visible capability | `feat(ssh): open remote files & directories over ssh:// / sftp:// (ssh2)` |
| `fix` | a bug fix | `fix(ssh): make keyboard-interactive (MFA) actually work …` |
| `docs` | documentation only | `docs(diagrams): re-tier the palette by value` |

Rules that keep history bisectable and reviewable:

- **Imperative mood, present tense** — "add", not "added"/"adds".
- **One focused change per commit.** A commit should compile and pass its relevant gate on its own.
- **Scope is the subsystem** (`ssh`, `diff`, `latex`, `diagrams`, `ir`, `stream`), matching the source layer
  or feature it touches.
- **Never rewrite published history.** To add a follow-up (a fixup, a formatting pass), make a *new* commit on
  top; do not amend, rebase, or `--fixup` a commit that has been pushed. (This mirrors the repository's
  working rule of committing straight to `main` without rewriting.)

---

## 2. Pull requests

A pull request (`AGENTS.md`) should:

1. **Explain the user-visible behavior** it changes — what a user can now do, or what stops misbehaving.
2. **List the validation commands run**, with their outcome. At minimum the relevant subset of the
   [pre-merge checklist](08-ci-and-validation-discipline.md#4-the-ci-matrix-the-project-should-run): `node
   test/lint.js`, `npm test`, and — for anything touching rendering, IPC, or the renderer — `npm run
   test:electron` *and* `npm run test:electron:release`.
3. **Link related issues.**
4. **Include a screenshot or short recording** for any UI, theme, sidebar, tab, or renderer change. The
   project regenerates its 800×600 screenshots headlessly with `npm run screenshots`
   ([07](07-docs-and-screenshot-gates.md)); a PR-specific capture is separate from those.

Because there is [no CI](08-ci-and-validation-discipline.md), the "validation commands run" section is not
ceremony — it is the *only* evidence a reviewer has that the gates passed.

---

## 3. When a change needs an ADR

An **Architecture Decision Record** captures one significant, hard-to-reverse choice: the context that forced
it, the decision, its consequences, alternatives, and trade-offs. Add one when a change picks an architecture
that a future contributor might otherwise undo without knowing why — a new trust boundary, a new virtual
backend, a rendering-pipeline change, a build-optimization choice.

The mechanics (from [`design-decisions/README.md`](../design-decisions/README.md)):

- Copy the template in that README. Keep it short and **cite code evidence** (file / namespace) for each claim.
- Number it with the **next free number** the README tracks (an ADR is immutable once written).
- When a decision *changes*, do **not** rewrite the old ADR — write a new one that supersedes it and link both
  directions. The status legend (`Accepted` / `Proposed` / `Superseded`) records the lifecycle.

The relationship to this pillar: an ADR records a decision *at a point in time*; an engineering page records
the *standing practice* it becomes (see [engineering/00 §3](00-overview.md)). A PR that changes a practice
therefore usually touches both — a new ADR and the affected engineering page.

---

## 4. Documentation guidelines

Every contributed document — a feature page, an ADR, a theory or engineering page — obeys the suite
conventions defined once in [`docs/README.md`](../README.md#conventions-used-throughout) and elaborated in
[`docs/diagrams/README.md`](../diagrams/README.md). The load-bearing few:

- **Math is MathJax, delimited for GitHub.** Inline math is a backtick span wrapped in dollar signs; display
  math is a fenced block whose info-string is `math`. Never bare `$…$`/`$$…$$` (GitHub's CommonMark pass
  strips backslash escapes first). A literal dollar sign in prose is an inline code span.
- **Diagrams are committed PlantUML.** Every figure is a `.puml` under `docs/diagrams/` that
  `!include _vv-theme.iuml`, rendered to `.svg`, embedded as the SVG with its `.puml` source cited beneath —
  never an inline ` ```plantuml ` / ` ```mermaid ` block (GitHub renders neither as a figure). Colour is
  per-concept and suite-stable. New or changed diagrams must pass the [render gates](07-docs-and-screenshot-gates.md)
  and be added to the catalog.
- **Define terms before use**, and canonically in [`GLOSSARY.md`](../GLOSSARY.md).
- **Cite honestly.** Link a DOI only where it resolves; never present a non-DOI (an ACM `10.5555` placeholder)
  as a DOI. Do not invent citations.

These are the same guidelines the pgmcp `documentation_guidelines` tool serves to every agent working in this
workspace; they are reproduced in the suite so a human contributor sees them too.

---

## 5. The pre-merge checklist, condensed

```text
[ ] commit(s) follow Conventional Commits, one focused change each
[ ] node test/lint.js              — green
[ ] npm test                       — green
[ ] npm run test:electron          — green   (if renderer/IPC/rendering touched)
[ ] npm run test:electron:release  — green   (   ″   — the :simple build differs)
[ ] npm run *:check                — green   (if a vendored asset changed)
[ ] diagrams: rendered + gates empty + catalogued   (if a .puml changed)
[ ] new ADR added                  (if an architecture decision was made)
[ ] docs updated to match          (stale prose is a defect)
[ ] PR body: behavior + validation-run + issue link + screenshot (if UI)
```

## 6. See also

- [`AGENTS.md`](../../AGENTS.md) — the source of the commit/PR/style rules.
- [08 — CI and validation discipline](08-ci-and-validation-discipline.md) — the gates this checklist runs.
- [`design-decisions/README.md`](../design-decisions/README.md) — the ADR template and index.
- [`docs/README.md`](../README.md) — the whole-suite conventions every document obeys.
