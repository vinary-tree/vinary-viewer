#!/usr/bin/env bash
# vinary-viewer — (re)apply the vmd enhancements. Self-locating and portable; safe to run on every
# launch (the vmd() wrapper does), so the patches survive vmd npm upgrades. Each patch is
# marker-guarded and backs up the stock file, so re-running is idempotent.
#
#   VV_HOME      install dir (defaults to this script's own directory)
#   VV_VMD_DIR   vmd's npm package dir (auto-detected from `command -v vmd`)
set -uo pipefail

# --- locate ourselves (the install dir) ---
SELF="$(readlink -f "${BASH_SOURCE[0]:-$0}")"
VV_HOME="${VV_HOME:-$(dirname "$SELF")}"

# --- locate vmd's package dir (bin lives at <prefix>/bin/vmd → pkg at <prefix>/lib/node_modules/vmd) ---
if [ -z "${VV_VMD_DIR:-}" ]; then
  VMD_BIN="$(command -v vmd || true)"
  if [ -n "$VMD_BIN" ]; then VV_VMD_DIR="$(dirname "$(dirname "$VMD_BIN")")/lib/node_modules/vmd"; fi
  if [ ! -d "${VV_VMD_DIR:-/nonexistent}" ]; then VV_VMD_DIR="$(npm root -g 2>/dev/null)/vmd"; fi
fi
if [ ! -d "${VV_VMD_DIR:-/nonexistent}" ]; then
  echo "vinary-viewer: vmd package not found (is vmd installed and on PATH?)" >&2; exit 0
fi
export VV_HOME VV_VMD_DIR

VMD_HTML="$VV_VMD_DIR/renderer/vmd.html"
BACKUP="${VMD_HTML}.vv.bak"
MARKER="$VV_HOME/sidebar.js"
TAG="  <script>try{require('$VV_HOME/sidebar.js');}catch(e){console.warn('vinary-viewer',e);}</script>"

# 1. Inline-require bootstrap in vmd.html (an inline require() so Node's module loader runs sidebar.js
#    directly, bypassing vmd's temporary file: protocol interceptor). Insert before <script src="main.js">.
if [ -f "$VMD_HTML" ] && ! grep -qF "$MARKER" "$VMD_HTML"; then
  cp -f "$VMD_HTML" "$BACKUP"
  TMP="$(mktemp "${VMD_HTML}.XXXXXX")"
  awk -v tag="$TAG" '/<script src="main\.js">/ && !d { print tag; d=1 } { print }' "$VMD_HTML" > "$TMP"
  if grep -qF "$MARKER" "$TMP"; then
    mv -f "$TMP" "$VMD_HTML"
  elif grep -qi '</body>' "$VMD_HTML"; then                # fallback anchor
    awk -v tag="$TAG" '/<\/body>/ && !d { print tag; d=1 } { print }' "$VMD_HTML" > "$TMP"
    mv -f "$TMP" "$VMD_HTML"
  else
    rm -f "$TMP"; echo "vinary-viewer: no insertion anchor in vmd.html; left unchanged" >&2
  fi
fi

# 2. main-process patch: image rendering + app-command nav + native thumb-button hook (own markers/backup).
node "$VV_HOME/patch-create-window.js" 2>/dev/null || true

# 3. renderer patch: grow vmd's history on every navigation so Alt+Left/Right work (own marker/backup).
node "$VV_HOME/patch-renderer-main.js" 2>/dev/null || true

# 4. Native X11 hook for the mouse back/forward thumb buttons: build the addon once for vmd's Electron
#    ABI if it isn't built yet (create-window.js require()s it in a try/catch, so an unbuilt/failed
#    addon just leaves the thumb buttons inert).
MFB="$VV_HOME/mouse-forward-back"
if [ -f "$MFB/binding.gyp" ] && [ ! -f "$MFB/build/Release/mouse-forward-back.node" ]; then
  EV="$(node -p "require('$VV_VMD_DIR/node_modules/electron/package.json').version" 2>/dev/null || true)"
  if [ -n "$EV" ]; then
    ( cd "$MFB" \
      && npm install --no-audit --no-fund --ignore-scripts >/dev/null 2>&1 \
      && node-gyp rebuild --target="$EV" --arch=x64 --dist-url=https://electronjs.org/headers >/dev/null 2>&1 ) || true
  fi
fi
