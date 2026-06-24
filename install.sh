#!/usr/bin/env bash
# vinary-viewer installer.
#   • deploys the runtime files to $VV_HOME (default ~/.local/share/vinary-viewer)
#   • points vmd at our stylesheet via ~/.vmdrc (styles.extra)
#   • patches vmd's package + builds the native mouse-button addon (via apply.sh)
#   • installs a transparent vmd() shell wrapper so the patches survive vmd npm upgrades
# Idempotent + re-runnable. Migrates off a prior ~/.vmd install. Override the location with
# VV_HOME=/some/dir ./install.sh
set -uo pipefail

REPO="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
SRC="$REPO/src"
VV_HOME="${VV_HOME:-${VV_PREFIX:-$HOME/.local/share/vinary-viewer}}"

# ---- 0. preflight ----------------------------------------------------------------------------
[ "$(uname -s)" = "Linux" ] || { echo "vinary-viewer targets Linux (Electron 3 + an X11 addon)." >&2; exit 1; }
VMD_BIN="$(command -v vmd || true)"
if [ -z "$VMD_BIN" ]; then
  echo "vmd is not on your PATH. Install it first, then re-run:  npm install -g vmd" >&2; exit 1
fi
VV_VMD_DIR="$(dirname "$(dirname "$VMD_BIN")")/lib/node_modules/vmd"
[ -d "$VV_VMD_DIR" ] || VV_VMD_DIR="$(npm root -g 2>/dev/null)/vmd"
[ -d "$VV_VMD_DIR" ] || { echo "Could not locate vmd's package dir (looked near $VMD_BIN)." >&2; exit 1; }
echo "vmd package : $VV_VMD_DIR"
echo "install dir : $VV_HOME"

# ---- 1. deploy runtime files -----------------------------------------------------------------
mkdir -p "$VV_HOME"
cp -f "$SRC/sidebar.js" "$SRC/style.css" "$SRC/apply.sh" \
      "$SRC/patch-create-window.js" "$SRC/patch-renderer-main.js" "$VV_HOME/"
chmod +x "$VV_HOME/apply.sh"
rm -rf "$VV_HOME/themes"; cp -r "$SRC/themes" "$VV_HOME/themes"
# native addon: deploy SOURCE only; the .node binary is built locally by apply.sh (step 3).
rm -rf "$VV_HOME/mouse-forward-back"; mkdir -p "$VV_HOME/mouse-forward-back"
cp -f "$SRC/mouse-forward-back/package.json" "$SRC/mouse-forward-back/binding.gyp" \
      "$SRC/mouse-forward-back/mouse-forward-back.cc" "$SRC/mouse-forward-back/index.js" \
      "$SRC/mouse-forward-back/LICENSE" "$VV_HOME/mouse-forward-back/"

# ---- 2. point vmd at our stylesheet via ~/.vmdrc (styles.extra). Idempotent; migrates the path. --
VMDRC="$HOME/.vmdrc"; touch "$VMDRC"
TMP="$(mktemp)"; grep -v '^[[:space:]]*styles\.extra[[:space:]]*=' "$VMDRC" > "$TMP" || true
{ cat "$TMP"; echo "styles.extra = $VV_HOME/style.css"; } > "$VMDRC"; rm -f "$TMP"

# ---- 3. patch vmd + build the addon (apply.sh self-locates VV_HOME and detects vmd) -----------
VV_HOME="$VV_HOME" VV_VMD_DIR="$VV_VMD_DIR" bash "$VV_HOME/apply.sh"

# ---- 4. install the transparent vmd() wrapper, migrating off any prior ~/.vmd wrapper ---------
MARK_BEGIN='# >>> vinary-viewer >>>'
MARK_END='# <<< vinary-viewer <<<'
WRAP="$MARK_BEGIN
# Re-apply vinary-viewer's vmd patches (vmd lives in an npm package that upgrades overwrite), then
# launch vmd. Managed by vinary-viewer/install.sh; run uninstall.sh to remove.
vmd() { \"$VV_HOME/apply.sh\" >/dev/null 2>&1; command vmd \"\$@\"; }
$MARK_END"

added_to=""
for RC in "$HOME/.zshrc" "$HOME/.bashrc"; do
  [ -e "$RC" ] || continue
  TMP="$(mktemp)"
  awk '
    /^# >>> vinary-viewer >>>/ { inblk=1; next }
    inblk { if (/^# <<< vinary-viewer <<</) inblk=0; next }
    /^[[:space:]]*vmd\(\)[[:space:]]*\{/ { fn=1; buf=$0 ORS; leg=0; next }
    fn { buf=buf $0 ORS;
         if (/apply-sidebar\.sh/ || /\/\.vmd\//) leg=1;
         if (/^[[:space:]]*\}/) { if (!leg) printf "%s", buf; fn=0; buf=""; leg=0 }
         next }
    { print }
  ' "$RC" > "$TMP"
  printf '%s\n' "$WRAP" >> "$TMP"
  mv "$TMP" "$RC"
  added_to="$added_to $RC"
done
[ -n "$added_to" ] || { echo "$WRAP" >> "$HOME/.profile"; added_to=" $HOME/.profile"; }

echo
echo "vinary-viewer installed. The vmd() wrapper was added to:$added_to"
echo "Open a new shell (or 'source' that file), then run:  vmd path/to/file.md"
echo "Switch themes with:  VV_THEME=spacemacs-light vmd …   (or write the name into ~/.config/vinary-viewer/theme)"
