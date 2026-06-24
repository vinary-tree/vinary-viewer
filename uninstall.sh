#!/usr/bin/env bash
# vinary-viewer uninstaller: restore stock vmd from the .vv.bak backups, remove the ~/.vmdrc
# styles.extra line we added, strip the vmd() wrapper from the shell rc files, and delete the
# install dir. Override the location with VV_HOME=/some/dir ./uninstall.sh
set -uo pipefail
VV_HOME="${VV_HOME:-${VV_PREFIX:-$HOME/.local/share/vinary-viewer}}"

# ---- 1. restore the three patched vmd files from their backups --------------------------------
VMD_BIN="$(command -v vmd || true)"
if [ -n "$VMD_BIN" ]; then
  VV_VMD_DIR="$(dirname "$(dirname "$VMD_BIN")")/lib/node_modules/vmd"
  [ -d "$VV_VMD_DIR" ] || VV_VMD_DIR="$(npm root -g 2>/dev/null)/vmd"
  for rel in renderer/vmd.html main/create-window.js renderer/main.js; do
    f="$VV_VMD_DIR/$rel"
    if [ -f "$f.vv.bak" ]; then mv -f "$f.vv.bak" "$f"; echo "restored $rel"; fi
  done
fi

# ---- 2. remove the styles.extra line that points at our install ------------------------------
VMDRC="$HOME/.vmdrc"
if [ -f "$VMDRC" ]; then
  TMP="$(mktemp)"; grep -vF "styles.extra = $VV_HOME/style.css" "$VMDRC" > "$TMP" || true; mv "$TMP" "$VMDRC"
fi

# ---- 3. strip the vmd() wrapper block from the shell rc files ---------------------------------
for RC in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.profile"; do
  [ -e "$RC" ] || continue
  TMP="$(mktemp)"
  awk '/^# >>> vinary-viewer >>>/ {b=1} b { if (/^# <<< vinary-viewer <<</) b=0; next } {print}' "$RC" > "$TMP"
  mv "$TMP" "$RC"
done

# ---- 4. remove the install dir ---------------------------------------------------------------
rm -rf "$VV_HOME"
echo
echo "vinary-viewer uninstalled. Restart your shell (or run 'unset -f vmd') to drop the wrapper."
