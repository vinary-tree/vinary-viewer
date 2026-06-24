#!/usr/bin/env bash
# Build the native mouse-forward-back addon against vmd's Electron ABI. Standalone (run after a vmd
# upgrade changes the ABI, or to pre-build before install); apply.sh also does this automatically on
# first launch if the addon isn't built. Requires: node, node-gyp, a C++ toolchain, and libX11 dev headers.
set -uo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
MFB="$HERE/../src/mouse-forward-back"

VMD_BIN="$(command -v vmd || true)"
[ -n "$VMD_BIN" ] || { echo "vmd not found on PATH (npm install -g vmd)." >&2; exit 1; }
VMD_DIR="$(dirname "$(dirname "$VMD_BIN")")/lib/node_modules/vmd"
[ -d "$VMD_DIR" ] || VMD_DIR="$(npm root -g 2>/dev/null)/vmd"
[ -d "$VMD_DIR" ] || { echo "could not locate vmd's package dir (near $VMD_BIN)." >&2; exit 1; }

EV="$(node -p "require('$VMD_DIR/node_modules/electron/package.json').version" 2>/dev/null || true)"
[ -n "$EV" ] || { echo "could not detect vmd's bundled Electron version under $VMD_DIR." >&2; exit 1; }

echo "Building mouse-forward-back for vmd's Electron $EV …"
cd "$MFB"
npm install --no-audit --no-fund --ignore-scripts
node-gyp rebuild --target="$EV" --arch=x64 --dist-url=https://electronjs.org/headers
echo "Built: $MFB/build/Release/mouse-forward-back.node"
