#!/usr/bin/env bash
# vinary-viewer installer (v0.2 — a standalone ClojureScript / Electron application, inspired by vmd).
#
#   • npm install + shadow-cljs release (builds the GUI dist/main/main.js + the terminal dist/cli, dist/tui)
#   • installs the `vv`/`vinary-viewer` launcher into $BIN (default ~/.local/bin) — one command, three modes:
#       vv [--gui] files…   GUI (default)      vv --cli file   terminal render      vv --tui file   terminal viewer
#   • migrates off the v0.1.0 vmd-patching tool (advisory — see below)
#
# Idempotent + re-runnable. Override with BIN=/some/dir and/or VV_BUILD=compile ./install.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="${BIN:-$HOME/.local/bin}"
# 'release' = optimized production build (:simple optimization — strips devtools + minifies; NOT
# :advanced, whose property-renaming breaks the re-frame / DataScript / remark interop). 'compile' = dev.
VV_BUILD="${VV_BUILD:-release}"

echo "==> vinary-viewer install (repo: $REPO)"
command -v npx >/dev/null 2>&1 || { echo "error: node/npx not found on PATH" >&2; exit 1; }

# tree-sitter build --wasm compiles each grammar with Emscripten. The `tree-sitter-cli` npm devDep supplies
# the tree-sitter binary itself, but not the C→wasm toolchain — we need either a local `emcc` (from
# `brew install emscripten`) or a running Docker daemon (tree-sitter falls back to an emscripten image).
# Fail fast with a clear message; a missing toolchain otherwise surfaces as ~47 opaque "Command failed:
# tree-sitter build --wasm" lines mid-install.
if ! command -v emcc >/dev/null 2>&1 && ! docker info >/dev/null 2>&1; then
  cat >&2 <<'EOF'
error: need one of the following to compile tree-sitter grammars to WASM:
  • emcc on PATH  (macOS: brew install emscripten)
  • a running Docker daemon  (macOS: open -a Docker, then wait for it to be ready)
EOF
  exit 1
fi

# shadow-cljs 3.2.0 launches the JVM with `--sun-misc-unsafe-memory-access=allow` (JDK 24+). On older JDKs the
# launcher aborts with "Unrecognized option" before shadow-cljs prints anything useful. Resolve a 24+ JDK now so
# the failure (if any) is one actionable line instead of a Maven-download red herring — and so a machine that HAS
# a new-enough JDK (just not first on PATH) builds without manual JAVA_HOME juggling.
java_major_of() {  # echo the major version of the java binary in $1 (nothing if unusable)
  [ -x "$1" ] || return 1
  "$1" -version 2>&1 | awk -F'"' '/version/ {split($2, a, "."); print a[1]; exit}'
}

# Candidate: honour an explicit JAVA_HOME, else the java on PATH.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_bin="$JAVA_HOME/bin/java"
else
  java_bin="$(command -v java 2>/dev/null || true)"
fi
java_major="$(java_major_of "$java_bin" 2>/dev/null || true)"

# Too old / missing? On macOS, auto-locate a 24+ JDK via java_home before giving up, and put it on PATH so the
# later `npx shadow-cljs` runs inherit it too.
if [ -z "${java_major:-}" ] || [ "$java_major" -lt 24 ] 2>/dev/null; then
  if [ -x /usr/libexec/java_home ] && jh="$(/usr/libexec/java_home -v 24 2>/dev/null)" && [ -n "$jh" ]; then
    export JAVA_HOME="$jh"
    export PATH="$JAVA_HOME/bin:$PATH"
    java_bin="$JAVA_HOME/bin/java"
    java_major="$(java_major_of "$java_bin" 2>/dev/null || true)"
    echo "==> using JDK ${java_major:-?} for shadow-cljs (JAVA_HOME=$JAVA_HOME)"
  fi
fi

# Still unusable -> actionable error.
if [ -z "${java_major:-}" ] || [ "$java_major" -lt 24 ] 2>/dev/null; then
  echo "error: shadow-cljs 3.2.0 needs JDK 24+; found java ${java_major:-none} (checked ${java_bin:-PATH})." >&2
  echo "       macOS: brew install --cask temurin, then re-run (install.sh auto-detects it via /usr/libexec/java_home)." >&2
  echo "       Linux: install a JDK 24+ and put it first on PATH (or set JAVA_HOME)." >&2
  exit 1
fi

echo "==> npm install"
# The Rholang grammar ships in the private @f1r3fly-io/tree-sitter-rholang-js-with-comments package (served from
# the GitHub Packages npm registry — needs ~/.npmrc auth + a GitHub PAT). It is an OPTIONAL dependency, so a
# missing/misconfigured token normally just warns and npm continues. Should a specific registry/auth error (e.g.
# an invalid-PAT 401) still be fatal, retry ONCE without optional deps so the core install proceeds; the skipped
# Rholang grammar (and how to enable it) is reported after the grammar sync below.
if ! ( cd "$REPO" && npm install --no-fund --no-audit ); then
  echo "    (warn: npm install failed — often the private @f1r3fly-io Rholang grammar needs a GitHub PAT;"
  echo "     retrying without optional dependencies so the core install can proceed…)"
  ( cd "$REPO" && npm install --no-fund --no-audit --omit=optional )
fi

echo "==> syncing terminal-renderer assets (tree-sitter grammars + graphics wasm)"
# grammars/graphics wasm are runtime assets shared by the GUI source-preview and the cli/tui renderers, so
# sync them ONCE here — not once per :cli and again per :tui (the release:cli / release:tui npm scripts each
# prepend grammars:sync + graphics:sync, which is why a plain install re-fetched every grammar twice).
# --skip-existing leaves already-built grammars untouched (fast, idempotent re-installs; no source.json churn);
# the sync is quiet by default (pass --verbose to grammars:sync to see the per-repo git/tree-sitter output).
# --allow-failures: a grammar that can't be downloaded/built (a transient network hiccup, or the auth/local-gated
# rholang/metta) is SKIPPED rather than aborting the install — skipped grammars are reported just below. Run
# graphics:sync on its own line (not chained with &&) so the graphics wasm still syncs even if a grammar failed.
( cd "$REPO" && npm run grammars:sync -- --skip-existing --allow-failures )
( cd "$REPO" && npm run graphics:sync )

# Report skipped grammars and — for the auth/local-gated ones — exactly how to enable them. sync-grammars.mjs
# writes the outcome to .cache/tree-sitter-grammars/last-sync.json (gitignored); read the failed ids from it.
SYNC_REPORT="$REPO/.cache/tree-sitter-grammars/last-sync.json"
FAILED_IDS="$(node -e 'const fs=require("fs");try{const s=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));process.stdout.write((s.failed||[]).map(f=>f.id).join(" "))}catch(_){}' "$SYNC_REPORT" 2>/dev/null || true)"
if [ -n "$FAILED_IDS" ]; then
  echo ""
  echo "==> NOTE: some tree-sitter grammars were skipped (the app still works without them — those file"
  echo "    types just won't get syntax highlighting):  $FAILED_IDS"
  echo "    Re-run ./install.sh to retry them (already-built grammars are left untouched)."
  case " $FAILED_IDS " in
    *" rholang "*)
      echo ""
      echo "    • rholang — its grammar ships in the PRIVATE npm package"
      echo "        @f1r3fly-io/tree-sitter-rholang-js-with-comments  (GitHub Packages registry),"
      echo "      which needs a GitHub Personal Access Token. To enable it:"
      echo "        1. Create a classic PAT with the 'read:packages' scope:"
      echo "             https://github.com/settings/tokens/new?scopes=read:packages"
      echo "        2. Add these two lines to ~/.npmrc:"
      echo "             @f1r3fly-io:registry=https://npm.pkg.github.com"
      echo "             //npm.pkg.github.com/:_authToken=YOUR_GITHUB_PAT"
      echo "        3. Re-run ./install.sh"
      ;;
  esac
  case " $FAILED_IDS " in
    *" metta "*)
      echo ""
      echo "    • metta — its grammar is built from the sibling checkout ../MeTTa-Compiler. To enable it:"
      echo "        git clone https://github.com/F1R3FLY-io/MeTTa-Compiler ../MeTTa-Compiler"
      echo "      (so ../MeTTa-Compiler/tree-sitter-metta exists), then re-run ./install.sh"
      ;;
  esac
fi

# The GUI (main + renderer) serves its vendored web assets from resources/public/assets/, which must exist
# BEFORE the renderer is built: Font Awesome (toolbar icons) + Fira Code via assets:sync, and pdf.js via
# pdfjs:sync. Those dirs are gitignored (regenerated from node_modules), so a clean checkout won't have them.
# The canonical `npm run release`/`compile` chain these first; we invoke shadow-cljs directly below, so run
# them explicitly here — otherwise the built app 404s the Font Awesome CSS/webfont and toolbar glyphs are blank.
echo "==> syncing GUI assets (fonts + Font Awesome icons + pdf.js)"
( cd "$REPO" && npm run assets:sync )
( cd "$REPO" && npm run pdfjs:sync )

echo "==> building GUI ($VV_BUILD)"
( cd "$REPO" && npx shadow-cljs "$VV_BUILD" main renderer )

echo "==> building terminal tools ($VV_BUILD cli, $VV_BUILD tui)"
# build the :cli/:tui targets directly (one shadow-cljs run, no redundant re-sync — the assets are ready above)
( cd "$REPO" && npx shadow-cljs "$VV_BUILD" cli tui )

echo "==> installing launcher into $BIN"
mkdir -p "$BIN"
VERSION="$(node -p "require('$REPO/package.json').version" 2>/dev/null || echo unknown)"
# vinary-viewer is one command with three modes: GUI (default) | --cli | --tui. $REPO and $VERSION are baked in;
# runtime variables (\$@, \${1:-}, \$REPO) are escaped so they stay literal in the generated launcher.
cat > "$BIN/vinary-viewer" <<EOF
#!/usr/bin/env bash
# vinary-viewer launcher (generated by install.sh) — GUI (default) · --cli · --tui
REPO="$REPO"
case "\${1:-}" in
  --cli)        shift; exec node "\$REPO/dist/cli/vv-cli.js" "\$@" ;;
  --tui)        shift; exec node "\$REPO/dist/tui/vv-tui.js" "\$@" ;;
  --gui)        shift ;;
  --no-daemon)  shift; exec "\$REPO/node_modules/.bin/electron" "\$REPO" "\$@" ;;  # fresh process, bypass the daemon
  -h|--help)    cat <<'USAGE'
vinary-viewer — preview Markdown, PDFs, images, diagrams & source

Usage:
  vv [--gui] [files…]   open in the desktop GUI (default), one tab per file/URL
  vv --cli  <file>      render a document to the terminal  (pipe-friendly:  vv --cli x.md | less)
  vv --tui  <file>      interactively page a document       (scroll · / find · t contents · q quit)
  vv --no-daemon [files…]   open in a fresh process (do not reuse the warm daemon)
  vv --help | --version

Mode options:  vv --cli --help   ·   vv --tui --help
USAGE
                exit 0 ;;
  -V|--version) echo "vinary-viewer $VERSION"; exit 0 ;;
esac
# GUI (default): hand the files to the warm resident process over its Unix socket (a new window opens with no
# cold start). The client is systemd-INDEPENDENT — it starts the daemon itself if none is running. systemd, if
# present, merely keeps a --daemon warm at login (see the user unit below). --no-daemon (above) bypasses all this.
exec node "\$REPO/scripts/vv-open.mjs" "\$@"
EOF
chmod +x "$BIN/vinary-viewer"
ln -sf "$BIN/vinary-viewer" "$BIN/vv"
echo "    vv [files…]    -> GUI (default)"
echo "    vv --cli FILE  -> render to the terminal"
echo "    vv --tui FILE  -> interactive terminal viewer"

# ---- desktop entry + hicolor icons (Linux application-menu integration) --------------------------
# Registers the app in the desktop launcher with its logo, from the vendored set in resources/icons/
# (the Full-Automaton mark). macOS/Windows show the icon at runtime via the window / dock (core.cljs),
# so this XDG step is Linux-only; the cache refreshes are optional (guarded, non-fatal).
if [ "$(uname -s)" = "Linux" ]; then
  DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
  ICONS="$DATA_HOME/icons/hicolor"
  APPS="$DATA_HOME/applications"
  echo "==> installing desktop entry + icons"
  for sz in 16 32 48 64 128 256 512; do
    case $sz in 16) src="favicon-16.png" ;; *) src="appicon-$sz.png" ;; esac
    install -Dm644 "$REPO/resources/icons/$src" "$ICONS/${sz}x${sz}/apps/vinary-viewer.png"
  done
  install -Dm644 "$REPO/resources/icons/vinary-viewer-appicon.svg" "$ICONS/scalable/apps/vinary-viewer.svg"
  mkdir -p "$APPS"
  cat > "$APPS/vinary-viewer.desktop" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Vinary Viewer
GenericName=Document Viewer
Comment=Fast Markdown / document / source viewer
Exec=$BIN/vinary-viewer %F
Icon=vinary-viewer
Terminal=false
Categories=Utility;Viewer;TextTools;
StartupWMClass=vinary-viewer
StartupNotify=true
EOF
  update-desktop-database "$APPS" 2>/dev/null || true
  gtk-update-icon-cache -f -t "$ICONS" 2>/dev/null || true
  echo "    desktop entry: $APPS/vinary-viewer.desktop"
  echo "    hicolor icons: $ICONS/{16x16..512x512,scalable}/apps/vinary-viewer.*"
fi

# ---- optional: keep the warm daemon up at login — systemd (Linux) / launchd (macOS) --------------
# The daemon does NOT require a service manager (the `vv` launcher starts it over the socket on demand); this
# just keeps it warm from login so even the FIRST open is instant. Linux uses a systemd user unit; macOS uses a
# launchd LaunchAgent. On systems with neither, this is skipped — nothing else changes.
if command -v systemctl >/dev/null 2>&1; then
  UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT_DIR/vinary-viewer.service" <<EOF
[Unit]
Description=vinary-viewer warm document-viewer daemon
After=graphical-session.target
PartOf=graphical-session.target

[Service]
Type=simple
ExecStart=$REPO/node_modules/.bin/electron $REPO --daemon
Restart=on-failure
RestartSec=2

[Install]
WantedBy=graphical-session.target
EOF
  systemctl --user daemon-reload 2>/dev/null || true
  # Enable at login AND (re)start now onto the freshly-built dist/. `restart` (not `enable --now`) is the key:
  # `--now` is a no-op on an ALREADY-running daemon, so a re-install would otherwise leave the OLD dist/main
  # loaded (and its warm pool windows on the OLD renderer) until the next manual restart. `restart` starts the
  # daemon if it's stopped and reloads it if it's running — so a re-install always ends on the new build.
  if systemctl --user enable vinary-viewer.service 2>/dev/null \
     && systemctl --user restart vinary-viewer.service 2>/dev/null; then
    echo "    systemd: vinary-viewer.service enabled + (re)started on the new build"
  else
    echo "    systemd: unit installed; start with 'systemctl --user enable --now vinary-viewer.service'"
  fi
elif [ "$(uname -s)" = "Darwin" ]; then
  # macOS equivalent of the systemd unit: a per-user launchd LaunchAgent. Same NON-socket-activated model — the
  # daemon binds its own socket at runtime; launchd just keeps the process warm. RunAtLoad starts it at login;
  # KeepAlive{SuccessfulExit=false} restarts it only on a crash (a clean Cmd-Q stays quit until next login),
  # mirroring systemd's Restart=on-failure. Same `electron <repo> --daemon` command as the systemd ExecStart.
  LABEL="io.vinarytree.vinary-viewer"
  AGENT_DIR="$HOME/Library/LaunchAgents"
  PLIST="$AGENT_DIR/$LABEL.plist"
  LOG_DIR="$HOME/Library/Logs"
  mkdir -p "$AGENT_DIR" "$LOG_DIR"
  # Point at the REAL Electron binary, not node_modules/.bin/electron (a symlink to cli.js, a `#!/usr/bin/env node`
  # script). A launchd agent runs with a minimal PATH (/usr/bin:/bin:/usr/sbin:/sbin) that excludes a Homebrew/nvm
  # `node`, so the cli.js shebang would fail to launch; the native binary needs no node. Invoking it directly with
  # `<repo> --daemon` is exactly what cli.js does. Path derived from electron/path.txt (version/arch-robust).
  ELECTRON_BIN="$REPO/node_modules/electron/dist/$(cat "$REPO/node_modules/electron/path.txt" 2>/dev/null)"
  if [ ! -x "$ELECTRON_BIN" ]; then ELECTRON_BIN="$REPO/node_modules/.bin/electron"; fi   # fallback (shouldn't happen post-build)
  cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$ELECTRON_BIN</string>
        <string>$REPO</string>
        <string>--daemon</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>LimitLoadToSessionType</key>
    <string>Aqua</string>
    <key>StandardOutPath</key>
    <string>$LOG_DIR/vinary-viewer.daemon.log</string>
    <key>StandardErrorPath</key>
    <string>$LOG_DIR/vinary-viewer.daemon.log</string>
</dict>
</plist>
EOF
  # (Re)load onto the freshly-built dist/. Like the systemd `restart` (not `enable --now`): `kickstart -k`
  # restarts an already-running daemon so a re-install always ends on the new build. `bootout` first so
  # `bootstrap` re-reads the (possibly changed) plist. Modern launchctl (bootstrap/bootout/kickstart), not
  # the deprecated load/unload.
  DOM="gui/$(id -u)"
  launchctl bootout "$DOM/$LABEL" 2>/dev/null || true
  if launchctl bootstrap "$DOM" "$PLIST" 2>/dev/null; then
    launchctl kickstart -k "$DOM/$LABEL" 2>/dev/null || true
    echo "    launchd: $LABEL loaded + (re)started on the new build"
  else
    echo "    launchd: plist installed at $PLIST; load with 'launchctl bootstrap $DOM \"$PLIST\"'"
  fi
else
  echo "    (no systemd/launchd — the daemon starts on demand via 'vv'; no service needed)"
fi

# ---- migrate off v0.1.0 (the vmd-patching tool) -------------------------------------------------
# v0.1.0 patched the system `vmd` npm package, set ~/.vmdrc styles.extra, and installed a vmd() shell
# wrapper. This standalone app replaces all of that. We do NOT auto-edit your shell rc or the global
# vmd package; we detect the old install and tell you how to revert it. Your
# ~/.config/vinary-viewer/ (theme, keybindings.edn, grammars/) is preserved.
OLD_DEPLOY="$HOME/.local/share/vinary-viewer"
if [ -e "$OLD_DEPLOY/sidebar.js" ] || grep -qs 'vmd()' "$HOME/.bashrc" "$HOME/.zshrc" 2>/dev/null; then
  echo "==> NOTE: a v0.1.0 vmd-patching install was detected. To fully revert it, run:"
  echo "        git -C \"$REPO\" show v0.1.0:uninstall.sh | bash"
  echo "    and remove any 'vmd()' wrapper block from your ~/.bashrc / ~/.zshrc."
fi

case ":$PATH:" in *":$BIN:"*) : ;; *) echo "==> NOTE: add $BIN to your PATH" ;; esac
echo "==> done. Try:  vv README.md   ·   vv --cli README.md | less   ·   vv --tui README.md   ·   vv --help"
