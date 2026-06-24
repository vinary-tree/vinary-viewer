# 2 — Installation

## Requirements

| Requirement | Why | If missing |
| --- | --- | --- |
| **Linux + X11** | The thumb-button addon grabs X11 buttons; `vmd` is an Electron app. | Unsupported on `0.x`. |
| **`vmd` on `PATH`** | `vinary-viewer` patches `vmd`'s installed package. | `install.sh` aborts with the `npm install -g vmd` hint. |
| **Node.js + `npm`** | Run the patch scripts and (re)build the addon. | You already have them via `vmd`. |
| **C++ toolchain, `node-gyp`, libX11 headers** | Compile the native addon for `vmd`'s Electron ABI. | Everything else works; thumb buttons stay inert. |

> On Arch Linux the addon's build needs `base-devel` and `libx11`; `node-gyp` comes from `npm`. On
> Debian/Ubuntu: `build-essential` and `libx11-dev`.

## Quick install

```sh
git clone https://github.com/vinary-tree/vinary-viewer.git
cd vinary-viewer
./install.sh
```

Then open a new shell (or `source ~/.zshrc`) and run `vmd README.md`.

## What `install.sh` does

It is **idempotent** and **re-runnable**. Step by step:

1. **Preflight.** Confirms Linux and that `vmd` is on `PATH`. Locates `vmd`'s package dir portably from
   `command -v vmd` — the bin lives at `<prefix>/bin/vmd`, so the package is
   `<prefix>/lib/node_modules/vmd` (with `npm root -g` as a fallback). It does **not** assume a system
   prefix, so a user-local `~/.npm-global` install is found correctly.
2. **Deploy.** Copies the runtime files to `VV_HOME` (default `~/.local/share/vinary-viewer`):
   `sidebar.js`, `style.css`, `themes/`, `apply.sh`, the two `patch-*.js`, and the addon **sources**
   (the compiled `.node` is built locally, never shipped).
3. **Wire the stylesheet.** Sets `styles.extra = <VV_HOME>/style.css` in `~/.vmdrc` (replacing any prior
   value), so `vmd` injects the structural stylesheet.
4. **Patch + build.** Runs `apply.sh`, which injects the renderer bootstrap into `vmd.html`, applies the
   `create-window.js` and `renderer/main.js` patches, and builds the native addon for `vmd`'s Electron
   version. Each patch backs up the file it changes to `<file>.vv.bak`.
5. **Install the wrapper.** Adds a marker-guarded `vmd()` function to `~/.zshrc` and `~/.bashrc`:

   ```sh
   # >>> vinary-viewer >>>
   vmd() { "$HOME/.local/share/vinary-viewer/apply.sh" >/dev/null 2>&1; command vmd "$@"; }
   # <<< vinary-viewer <<<
   ```

   The wrapper re-applies the patches on each launch so they survive `vmd` upgrades, then calls the real
   `vmd`. (If you have no shell rc, the block is added to `~/.profile`.)
6. **Migrate.** Removes a prior loose `~/.vmd` wrapper and the old `styles.extra` path.

## Configuration

| Variable / file | Purpose | Default |
| --- | --- | --- |
| `VV_HOME` (env) | Install location, honored by `install.sh`/`uninstall.sh`/`apply.sh`. | `~/.local/share/vinary-viewer` |
| `VV_VMD_DIR` (env) | Override `vmd`'s package dir (normally auto-detected). | auto |
| `VV_THEME` (env) | Active theme name (highest priority). | — |
| `~/.config/vinary-viewer/theme` | Persistent theme name (one line). | — |

Install to a custom location:

```sh
VV_HOME=~/.vv ./install.sh
```

## Verifying the install

```sh
command -v vmd                     # → your shell function, not the bare binary
type vmd                           # zsh/bash: shows the vmd() wrapper body
ls ~/.local/share/vinary-viewer    # sidebar.js, style.css, themes/, apply.sh, …
ls ~/.local/share/vinary-viewer/mouse-forward-back/build/Release/   # mouse-forward-back.node (if built)
```

Launch `vmd` on any Markdown file inside a git repository: the file-tree sidebar should appear on the
left and the theme should apply.

## Upgrading `vmd`

Just upgrade as normal (`npm update -g vmd` or `npm install -g vmd@latest`). The next time you run `vmd`,
the wrapper's `apply.sh` re-detects the (now stock) package and re-applies the patches. Because the
patches are marker-guarded, re-running never double-applies; because they back up first, the new stock
files become the new restore point.

If a future `vmd` rewrites a file so a patch's anchor no longer matches, that single patch logs a warning
and is skipped — `vmd` keeps working, and the affected feature is simply absent until the patch is
updated. See [09 — Troubleshooting](09-troubleshooting-and-uninstall.md).

## Updating `vinary-viewer` itself

```sh
cd vinary-viewer && git pull && ./install.sh
```

Re-running `install.sh` redeploys the runtime files and re-applies everything.

## Uninstall

```sh
./uninstall.sh
```

Restores stock `vmd` from the `.vv.bak` backups, removes the `styles.extra` line and the `vmd()` wrapper,
and deletes `VV_HOME`. Restart your shell or run `unset -f vmd`.
