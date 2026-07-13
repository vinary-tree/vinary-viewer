# The release process

vinary-viewer's "release" is a **local, source-based install**: there is no
published npm package and no downloadable binary. A user clones the repository and
runs [`install.sh`](../../install.sh), which builds all three modes and installs a
launcher. This page documents the installer's phases and environment knobs,
[`uninstall.sh`](../../uninstall.sh), the SemVer + Keep-a-Changelog versioning
discipline, and how a release build differs from a dev build.

> **Audience.** Read this when cutting a release, changing the installer, or
> deciding what version number a change warrants.

---

## 1. Key terms

| Term | Definition |
|------|------------|
| **Install** | Running `./install.sh`: `npm install`, a `shadow-cljs release` of all five-artifact-relevant builds, and writing the `vv` launcher into a `bin` directory. There is no separate packaging step. |
| **`$BIN`** | The launcher install directory, `${BIN:-$HOME/.local/bin}`. Overridable. |
| **`$VV_BUILD`** | The build profile the installer uses, `release` (default) or `compile` (dev). |
| **SemVer** | [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html): a `MAJOR.MINOR.PATCH` contract the project adheres to. |
| **Keep a Changelog** | The [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) format `CHANGELOG.txt` follows. |

---

## 2. `install.sh` — phases

The installer is **idempotent and re-runnable** (`set -euo pipefail`). It performs
six phases in order:

| Phase | Command | Behavior |
|-------|---------|----------|
| 1. Preflight | `command -v npx` | Fails fast if Node/npx is not on `PATH`. |
| 2. Dependencies | `npm install --no-fund --no-audit` | Installs npm dependencies. |
| 3. Shared assets | `npm run grammars:sync -- --skip-existing && npm run graphics:sync` | Builds the tree-sitter grammar WASMs (skipping already-built ones) and copies the graphics WASM — **once**, because both the GUI source preview and the terminal renderers share them. |
| 4. GUI build | `npx shadow-cljs "$VV_BUILD" main renderer` | Builds the Electron main + renderer artifacts. |
| 5. Terminal build | `npx shadow-cljs "$VV_BUILD" cli tui` | Builds `vv-cli.js` + `vv-tui.js` in one `shadow-cljs` run (no redundant re-sync of the phase-3 assets). |
| 6. Launcher | `cat > "$BIN/vinary-viewer"` + `ln -sf … "$BIN/vv"` | Writes the mode-dispatching launcher (§4) and symlinks `vv`. |

Phases 4 and 5 invoke `shadow-cljs` **directly** rather than through the npm
`compile`/`compile:cli` scripts, so the shared grammar/graphics syncs from phase 3
run exactly once. Building through the npm scripts would re-prepend
`grammars:sync` + `graphics:sync` to each terminal build — the double-fetch the
installer explicitly avoids (see
[05-terminal-build-and-launch.md §4](05-terminal-build-and-launch.md#4-how-installsh-builds-all-three-modes)).

After phase 6 the installer prints a PATH reminder if `$BIN` is not already on
`PATH`, and a "try it" line: `vv README.md · vv --cli README.md | less · vv --tui
README.md · vv --help`.

---

## 3. Environment knobs

The installer's two environment overrides:

| Knob | Default | Effect |
|------|---------|--------|
| `BIN` | `$HOME/.local/bin` | Where the `vinary-viewer` + `vv` launchers are written. E.g. `BIN=/usr/local/bin ./install.sh`. |
| `VV_BUILD` | `release` | The `shadow-cljs` profile for phases 4–5. `release` = optimized (`:simple`, strips devtools, minifies); `compile` = fast dev build. E.g. `VV_BUILD=compile ./install.sh`. |

Both are read once at the top of the script:

```bash
BIN="${BIN:-$HOME/.local/bin}"
VV_BUILD="${VV_BUILD:-release}"
```

`uninstall.sh` reads the same `BIN` knob, so a non-default install location must be
matched at uninstall time (§5).

---

## 4. The generated launcher

Phase 6 writes a Bash launcher with `$REPO` and `$VERSION` baked in, dispatching
on the first argument into the GUI (default), `--cli`, `--tui`, `--help`, or
`--version`. Runtime variables in the heredoc (`\$@`, `\${1:-}`, `\$REPO`) are
escaped so they stay literal in the generated file. The full dispatch table is in
[05-terminal-build-and-launch.md §1](05-terminal-build-and-launch.md#1-the-vv-launcher--one-command-five-modes).
`$VERSION` is read from `package.json` at install time:

```bash
VERSION="$(node -p "require('$REPO/package.json').version" 2>/dev/null || echo unknown)"
```

so `vv --version` reports the version that was current when the launcher was
written.

---

## 5. Migration off v0.1.0, and `uninstall.sh`

**Migration notice.** v0.1.0 was a *vmd-patching* tool: it patched the system
`vmd` npm package, set `~/.vmdrc`, and installed a `vmd()` shell wrapper. The
standalone 0.2/0.3 app replaces all of that, but the installer does **not**
auto-edit your shell rc or the global `vmd` package. Instead it *detects* signs of
the old install (`$HOME/.local/share/vinary-viewer/sidebar.js`, or a `vmd()`
wrapper in `~/.bashrc` / `~/.zshrc`) and prints manual cleanup guidance — including
the exact command to revert the old install:

```bash
git -C "$REPO" show v0.1.0:uninstall.sh | bash
```

Your `~/.config/vinary-viewer/` (theme, `keybindings.edn`, `grammars/`) is
preserved across the migration.

**Uninstall.** [`uninstall.sh`](../../uninstall.sh) removes only the launchers:

```bash
./uninstall.sh                    # remove vv + vinary-viewer from $HOME/.local/bin
BIN=/usr/local/bin ./uninstall.sh # match a non-default install location
```

It deletes `${BIN}/vv` and `${BIN}/vinary-viewer` and reports what it removed (or
"no launchers found"). It deliberately does **not** delete the repository or
`~/.config/vinary-viewer/`, so re-installing or removing the launchers never
destroys a user's theme, keybindings, or built grammars. To revert a v0.1.0
vmd-patching install, use the v0.1.0 uninstaller via the `git show` command above.

---

## 6. How a release build differs from a dev build

The single toggle `$VV_BUILD` selects the profile; the differences are:

| Aspect | `release` (`shadow-cljs release`) | `compile` (dev) |
|--------|-----------------------------------|-----------------|
| Optimization | Closure `:simple` — minifies + dead-code-eliminates, **never renames properties** ([ADR-0016](../design-decisions/0016-main-process-simple-optimization.md)). | `:none` — fast, readable output. |
| Devtools | Stripped: no shadow-cljs devtools, no re-frame-10x, no re-frisk (they gate on `goog.DEBUG`, false in release). | Preloaded: re-frame-10x, re-frisk, Chrome DevTools formatters. |
| `re-frame.trace` | Off. | On (`re_frame.trace.trace_enabled_QMARK_` = true via `:closure-defines`). |
| Regression gate | `npm run test:electron:release` asserts it boots, DevTools opens with no renamed-interop crash, and re-frame-10x is absent (see [03-test-strategy.md §5](03-test-strategy.md#5-the-dev-vs-release-smoke-split)). | `npm run test:electron` (dev-only steps run here). |

The installer defaults to `release` so a normal install ships the optimized,
devtools-free artifacts; `VV_BUILD=compile` is for developing against an install
without a separate `npm run dev`.

---

## 7. Versioning discipline — SemVer + Keep a Changelog

`CHANGELOG.txt`'s header states both contracts explicitly:

> "The format is based on Keep a Changelog (<https://keepachangelog.com/en/1.1.0/>),
> and this project adheres to Semantic Versioning (<https://semver.org/spec/v2.0.0.html>)."

- **Semantic Versioning.** Versions are `MAJOR.MINOR.PATCH`. The current version
  is **`0.3.0-dev`** (`package.json`); the `-dev` pre-release suffix marks
  work-in-progress toward `0.3.0`. Under SemVer's `0.y.z` rule, the API is still
  considered unstable, so a `MINOR` bump (`0.2` → `0.3`) can carry breaking
  changes — which is why the changelog's `0.3.0-dev` entry leads with the
  cross-cutting common-document-IR change.
- **Keep a Changelog.** `CHANGELOG.txt` is grouped by version, newest first, with
  the standard change categories (**Added**, **Changed**, **Fixed**, …). Each
  entry is written for a human reader and links the ADR or theory doc that
  explains the change. The top block is the *unreleased* `[0.3.0-dev]` section;
  cutting a release means renaming that block to `[0.3.0] - YYYY-MM-DD`, bumping
  `package.json`'s `version`, and starting a fresh unreleased block.

A release, then, is a small, disciplined ritual: land the changes with Conventional
Commits (see [09-contribution-workflow.md](09-contribution-workflow.md)), keep the
`[x.y.z-dev]` changelog block current as you go, and when ready, promote the block
and bump the version. The predecessor `0.1.0` is preserved at git tag `v0.1.0`,
which is what makes the `git show v0.1.0:uninstall.sh` migration command work.

---

## 8. References and see also

- [`install.sh`](../../install.sh) · [`uninstall.sh`](../../uninstall.sh) — the
  scripts this page documents.
- [`CHANGELOG.txt`](../../CHANGELOG.txt) — the versioned change log.
- [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html) ·
  [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) — the two
  contracts the header cites.
- [ADR-0016](../design-decisions/0016-main-process-simple-optimization.md) — the
  `:simple` optimization the release build uses.
- [05-terminal-build-and-launch.md](05-terminal-build-and-launch.md) — the
  launcher dispatch and terminal builds phases 5–6 produce.
- [09-contribution-workflow.md](09-contribution-workflow.md) — the commit +
  changelog discipline a release ritual depends on.
