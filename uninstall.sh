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

# Tear down the login service that keeps the warm daemon up (installed by install.sh): systemd on Linux,
# launchd on macOS. This stops the resident daemon and prevents it restarting at the next login.
if command -v systemctl >/dev/null 2>&1; then
  systemctl --user disable --now vinary-viewer.service 2>/dev/null || true
  rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/vinary-viewer.service"
  systemctl --user daemon-reload 2>/dev/null || true
  echo "removed systemd user service vinary-viewer.service"
elif [ "$(uname -s)" = "Darwin" ]; then
  LABEL="io.vinarytree.vinary-viewer"
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
  echo "removed launchd agent $LABEL"
fi

echo "done. (The repo and ~/.config/vinary-viewer/ are left intact.)"
