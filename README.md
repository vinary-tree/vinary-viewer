# vinary-viewer (`vv`)

**A reading environment for Markdown, built on [vmd](https://github.com/yoshuawuyts/vmd).**

`vmd` is a wonderfully simple GitHub-flavored-Markdown previewer. `vinary-viewer` keeps that
simplicity and adds the things you want when you are *reading a whole documentation tree* rather than
glancing at one file: a repository file-tree, a live table of contents, a working in-page find,
correctly sized figures, full-history navigation (including your mouse's back/forward thumb buttons),
and switchable color themes — all installed by patching your existing `vmd` in place, with a one-command
revert.

> Platform: **Linux + X11**. `vinary-viewer` enhances a locally installed `vmd`; it does not replace or
> redistribute it.

---

## Table of contents

- [Why](#why)
- [Features](#features)
- [How it looks: the architecture at a glance](#how-it-looks-the-architecture-at-a-glance)
- [Requirements](#requirements)
- [Install](#install)
- [Usage](#usage)
- [Configuration](#configuration)
- [Uninstall](#uninstall)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [Development](#development)
- [License and attribution](#license-and-attribution)

---

## Why

`vmd` renders a single Markdown file in a centered 888 px column and opens links in your browser. That
is perfect for a quick look and frustrating for sustained reading of a documentation set: there is no
way to see the other files, no table of contents, the bundled find is broken, embedded SVG diagrams are
stretched to the column width (magnifying their text), and history navigation does not survive opening
files from anywhere but a link.

`vinary-viewer` fixes each of these without forking `vmd`. It **patches the installed `vmd` package in
place** (every change is marker-guarded and backed up) and adds a thin renderer script. A transparent
`vmd()` shell wrapper re-applies the patches on launch, so they survive `npm` upgrades of `vmd`.

## Features

| Feature | What it does | Trigger |
| --- | --- | --- |
| **File-tree sidebar** | A collapsible tree of the git repository containing the open document; the current file is highlighted. Hidden entirely outside a repo (layout is then stock `vmd`). | automatic |
| **Files / Contents tabs** | The sidebar has two tabs. **Contents** is a scroll-spy table of contents of the previewed Markdown — it follows the section in view and is click-navigable. Disabled for non-Markdown. | sidebar tabs |
| **File-search filter** | A filter box that narrows the tree to files whose repo-relative path matches your query. | type in the Files tab |
| **In-page find** | A custom highlighter scoped to the document body (so the text you type is never itself highlighted), with match cycling. Replaces `vmd`'s broken search webview. | `Ctrl`/`Cmd`+`F`, `Enter` / `Shift`+`Enter` |
| **Full-width layout** | Removes the 888 px cap so content fills the window, keeping `vmd`'s 45 px gutter. | automatic |
| **Figure font-matching** | Each embedded SVG is sized so *its internal text matches the document font* — scaling down as well as up — instead of being stretched to the column. Raster images are centered at natural size. | automatic |
| **Dedicated image view** | Opening an image file, or clicking a figure in a document, shows just that image at full width. | click |
| **History navigation** | File-history back/forward across *every* open: keyboard, OS app-command, and your mouse's back/forward **thumb buttons**. | `Alt`+`←` / `Alt`+`→`, mouse 8/9 |
| **Named themes** | Color themes via a CSS-variable palette. Bundled: `spacemacs-dark` (default) and `spacemacs-light`. Add your own. | `VV_THEME` / config file |

Each feature is documented in depth under [`docs/`](#documentation).

## How it looks: the architecture at a glance

`vinary-viewer` attaches to both of `vmd`'s Electron processes. The main process gets three small,
marker-guarded patches and a native mouse-button hook; the renderer loads one script (`sidebar.js`) via
an inline-`require` bootstrap and an injected stylesheet.

![vinary-viewer architecture](docs/figures/architecture.svg)

See [`docs/03-architecture.md`](docs/03-architecture.md) for the full explanation, including the load
sequence and the `data-filepath` observer that drives the tree and table of contents.

## Requirements

- **Linux** with an **X11** session (the mouse-button addon uses `XGrabButton`).
- **[`vmd`](https://github.com/yoshuawuyts/vmd)** installed and on your `PATH` (`npm install -g vmd`).
- **Node.js** and **`npm`** (you already have these via `vmd`).
- A **C++ toolchain**, **`node-gyp`**, and **libX11 development headers** — only to build the native
  thumb-button addon. If they are missing, everything else still works; the thumb buttons are simply
  inert (keyboard and on-screen navigation are unaffected).

## Install

```sh
git clone https://github.com/vinary-tree/vinary-viewer.git
cd vinary-viewer
./install.sh
```

`install.sh` deploys the runtime files to `~/.local/share/vinary-viewer`, points `vmd` at the
stylesheet, patches `vmd` and builds the native addon, and adds a transparent `vmd()` wrapper to your
shell. Open a new shell (or `source ~/.zshrc`) and use `vmd` as usual:

```sh
vmd README.md
```

The install is **idempotent** and **re-runnable**, and it **migrates** a prior loose `~/.vmd` setup.

## Usage

Run `vmd <path>` on any Markdown file, image, or other text/HTML file. Then:

- **Browse** the repository in the **Files** tab; click any file to open it in the viewer.
- **Filter** the tree by typing in the box under the repo name.
- **Navigate sections** with the **Contents** tab — it scroll-spies the document; click a heading to jump.
- **Find** with `Ctrl`/`Cmd`+`F`; cycle matches with `Enter` / `Shift`+`Enter`; close with `Esc`.
- **Go back / forward** through your file history with `Alt`+`←` / `Alt`+`→`, or your mouse's
  back/forward thumb buttons.
- **Open an image** (e.g. a `.svg` diagram) to see it full width; **click a figure** in a document to do
  the same.

## Configuration

| Variable / file | Purpose | Default |
| --- | --- | --- |
| `VV_HOME` | Install location (read by `install.sh` / `uninstall.sh`). | `~/.local/share/vinary-viewer` |
| `VV_THEME` (env) | Active theme name. Highest priority. | — |
| `~/.config/vinary-viewer/theme` | Persistent theme name (one line, e.g. `spacemacs-light`). | — |
| `~/.vmdrc` `styles.extra` | Set by the installer to point `vmd` at our stylesheet. | `…/vinary-viewer/style.css` |

Switch themes for one launch:

```sh
VV_THEME=spacemacs-light vmd README.md
```

…or persist it:

```sh
mkdir -p ~/.config/vinary-viewer && echo spacemacs-light > ~/.config/vinary-viewer/theme
```

See [`docs/05-theming.md`](docs/05-theming.md) to write your own theme.

## Uninstall

```sh
./uninstall.sh
```

This restores the stock `vmd` files from their backups, removes the `styles.extra` line and the `vmd()`
wrapper, and deletes the install directory. Restart your shell (or `unset -f vmd`).

## Project layout

```
vinary-viewer/
├── install.sh · uninstall.sh        installer / reverter (system-level)
├── package.json                     metadata, semver version, dev scripts (private; not published)
├── src/
│   ├── sidebar.js                   renderer enhancements (tree, tabs/TOC, find, figures, theme)
│   ├── style.css                    structural stylesheet — references var(--vv-*) only
│   ├── themes/                      named themes: spacemacs-dark (default), spacemacs-light
│   ├── apply.sh                     (re)apply the vmd patches; run on every launch by the wrapper
│   ├── patch-create-window.js       main-process patch: [vmd-img] [vmd-nav] [vmd-mfb]
│   ├── patch-renderer-main.js       renderer patch: [vmd-hist]
│   └── mouse-forward-back/          native X11 thumb-button addon (sources; built on install)
├── scripts/build-addon.sh           build the addon for vmd's Electron ABI
├── test/                            headless test harness + lint
└── docs/                            full documentation + figures
```

## Documentation

| Document | Topic |
| --- | --- |
| [01 — Introduction](docs/01-introduction.md) | Motivation, scope, and design philosophy. |
| [02 — Installation](docs/02-installation.md) | Requirements, what `install.sh` does, configuration, upgrades. |
| [03 — Architecture](docs/03-architecture.md) | Electron processes, the bootstrap, the four patches, the `data-filepath` spine. |
| [04 — Features](docs/04-features.md) | Every feature in depth, incl. the figure font-matching derivation. |
| [05 — Theming](docs/05-theming.md) | The `--vv-*` palette, writing and selecting themes. |
| [06 — Native addon](docs/06-native-addon.md) | Why Electron 3 swallows the thumb buttons and how the X11 hook recovers them. |
| [07 — History navigation](docs/07-history-navigation.md) | The unified history model and its four input paths. |
| [08 — Security and internals](docs/08-security-and-internals.md) | Threat model of patching `vmd` and grabbing X input. |
| [09 — Troubleshooting and uninstall](docs/09-troubleshooting-and-uninstall.md) | Diagnosis, upgrades, full revert. |

## Development

`vinary-viewer` is a private npm package (metadata and dev scripts; **not** published to the registry —
it is installed via `install.sh`). The native addon is a sub-package with its own `package.json`.

```sh
npm run lint        # parse every JS file, check CSS brace balance + theme completeness
npm test            # headless harness (mocks Electron) — exercises tree/routing/history/folder/theme
npm run check       # lint + test
npm run build:addon # (re)build the native addon for vmd's Electron ABI
```

`npm version <patch|minor|major>` bumps the semantic version (see [semver.org](https://semver.org)).

## License and attribution

Licensed under the **Apache License 2.0** — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

- The native mouse-button hook is vendored from **mouse-forward-back** (Justin Ostrander, MIT); its
  license is retained at `src/mouse-forward-back/LICENSE`.
- **vmd** (Yoshua Wuyts, MIT) is patched in place, never redistributed.
- The bundled themes reproduce the color values of the **Spacemacs** theme for visual consistency.

A project of the **[vinary-tree](https://github.com/vinary-tree)** organization.
