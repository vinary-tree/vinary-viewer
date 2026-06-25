#!/usr/bin/env bash
# vinary-viewer uninstaller (v0.2): removes the `vv` / `vinary-viewer` launchers.
# Does NOT delete the repo or your ~/.config/vinary-viewer/ (theme, keybindings.edn, grammars/).
# Match the install location with BIN=/some/dir ./uninstall.sh
#
# (To revert a v0.1.0 vmd-patching install, use the v0.1.0 uninstaller:
#    git show v0.1.0:uninstall.sh | bash )
set -euo pipefail

BIN="${BIN:-$HOME/.local/bin}"
removed=0
for f in vv vinary-viewer; do
  if [ -e "$BIN/$f" ] || [ -L "$BIN/$f" ]; then rm -f "$BIN/$f"; echo "removed $BIN/$f"; removed=1; fi
done
[ "$removed" = 0 ] && echo "no launchers found in $BIN"
echo "done. (The repo and ~/.config/vinary-viewer/ are left intact.)"
