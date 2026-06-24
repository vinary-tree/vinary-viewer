# 9 — Troubleshooting and uninstall

## The first tool: DevTools

`vmd` ships Chromium DevTools. Open **View ▸ Toggle Developer Tools** (in `vmd`'s menu) and check the
**Console**. `vinary-viewer` logs warnings prefixed `vinary-viewer` / `[vmd-sidebar]` when something
fails to load, which usually points straight at the cause.

## Common issues

### The sidebar doesn't appear

- **You're not in a git repository.** The tree is intentionally hidden outside a repo (the layout is then
  stock `vmd`). Open a file that lives inside a git repo.
- **`git` isn't on `PATH`.** The tree is built from `git ls-files`; install `git`.
- **The bootstrap didn't load.** In DevTools, look for a `vinary-viewer` warning. Confirm the bootstrap
  line is present in `vmd`'s `vmd.html`:

  ```sh
  grep vinary-viewer "$(dirname "$(dirname "$(command -v vmd)")")/lib/node_modules/vmd/renderer/vmd.html"
  ```

  If absent, run `~/.local/share/vinary-viewer/apply.sh` (or just launch `vmd` via the wrapper).

### The theme/colors aren't applied

- **`styles.extra` isn't set.** Check `~/.vmdrc` contains `styles.extra = …/vinary-viewer/style.css`.
- **An unknown `VV_THEME`.** A bad name falls back to `spacemacs-dark`; check `VV_THEME` and
  `~/.config/vinary-viewer/theme` for typos (a name must be a bare slug).
- **The theme `<style>` is missing.** In DevTools, confirm a `<style id="vv-theme">` exists in `<head>`;
  if not, look for a `sidebar.js` warning (e.g. the theme file couldn't be read).
- **A `.vmdrc` in a parent directory overrides yours.** vmd loads its config by walking **up from the
  directory you launch it in**, so a stray `.vmdrc` in an ancestor folder can replace your
  `styles.extra` (and thus the stylesheet/theme) with a different one. If colors look wrong only when
  you start vmd from a particular tree, check for an ancestor `.vmdrc` (`grep -l styles.extra
  $(pwd)/.vmdrc $(dirname $(pwd))/.vmdrc …`) and remove or reconcile it.

### The mouse thumb buttons don't navigate

- **The addon isn't built.** Check for the binary:

  ```sh
  ls ~/.local/share/vinary-viewer/mouse-forward-back/build/Release/mouse-forward-back.node
  ```

  If missing, build it: `npm run build:addon` (needs a C++ toolchain, `node-gyp`, and libX11 headers —
  see [06 — Native addon](06-native-addon.md)).
- **Pointer isn't over the window.** The grab is scoped to `vmd`'s window; hover it, then press.
- **Your WM delivers them differently.** If the buttons arrive as an app-command or as DOM buttons 3/4,
  the `[vmd-nav]`/`sidebar.js` paths handle them instead — those need no addon. Keyboard `Alt`+`←`/`→`
  always works as a fallback.

### `Alt`+`←` / `Alt`+`→` do nothing

The Back/Forward menu is enabled only when `vmd`'s history is populated, which the `[vmd-hist]` patch
ensures. Confirm the patch applied:

```sh
grep -l vmd-hist "$(dirname "$(dirname "$(command -v vmd)")")/lib/node_modules/vmd/renderer/main.js"
```

If absent, re-run `apply.sh`. Note you need to have opened at least one *other* document for there to be
history to go back to.

### Features disappeared after a `vmd` upgrade

Expected and self-healing: `npm` overwrote `vmd` with stock files. The `vmd()` wrapper re-applies the
patches the next time you launch `vmd`. If a feature is still missing, a future `vmd` may have changed a
file so a patch's anchor no longer matches — check DevTools / the `apply.sh` output for a `[vmd-…] anchor
not found` warning, and update the affected patch.

### Re-applying or rebuilding by hand

```sh
~/.local/share/vinary-viewer/apply.sh     # re-inject bootstrap + re-apply patches + build addon if needed
cd vinary-viewer && npm run build:addon    # force-rebuild the native addon
cd vinary-viewer && ./install.sh           # redeploy everything from the repo
```

## Uninstall

```sh
cd vinary-viewer && ./uninstall.sh
```

This:

1. restores `vmd`'s `vmd.html`, `create-window.js`, and `renderer/main.js` from their `.vv.bak` backups,
2. removes the `styles.extra` line from `~/.vmdrc`,
3. strips the `vmd()` wrapper block from `~/.zshrc` / `~/.bashrc` / `~/.profile`, and
4. deletes the install directory (`VV_HOME`).

Restart your shell (or run `unset -f vmd`) so the wrapper is gone.

### Manual revert (if a backup is missing)

The surest reset is to reinstall `vmd` itself, which replaces all of its files with stock:

```sh
npm install -g vmd
```

Then remove the `styles.extra` line from `~/.vmdrc`, delete the `vinary-viewer` block from your shell rc,
and remove `~/.local/share/vinary-viewer`.

## Reporting a problem

Include: your Linux distribution and desktop environment, `vmd --version`, the relevant **DevTools
Console** output, and whether `~/.local/share/vinary-viewer/mouse-forward-back/build/Release/
mouse-forward-back.node` exists. File issues at
<https://github.com/vinary-tree/vinary-viewer/issues>.
