#!/usr/bin/env bash
# H-PAIRWORK-FIX″, as registered: F″'s bench in node and in Electron's node mode (release), node dev; the L shapes at
# every T of Q6 in both engines; F′'s S shapes in both engines (Q5); F″ at the old cap 65 536 (descriptive, no
# prediction). Core 3; the 1-minute load checked (< 12) before each arm.
set -u
J=${SCRATCH:?set SCRATCH to the scratch directory}; L=$J/pairwork/fsec/bench
E=${REPO:?set REPO to the repository}/node_modules/electron/dist/electron
FSEC=$J/wt-pairwork; FPRIME=$J/benchFprime-root
quiet() { local t0=$(date +%s); until awk -v l="$(cut -d' ' -f1 /proc/loadavg)" 'BEGIN { exit !(l < 12) }'; do
  [ $(( $(date +%s) - t0 )) -gt 10800 ] && { echo "gave up waiting: $(cat /proc/loadavg)" >> $L/summary.txt; exit 1; }; sleep 20; done; }
arm() {   # name, dir, runtime (node|electron), bundle, then the bench's own options
  local name=$1 dir=$2 rt=$3 bundle=$4; shift 4
  quiet
  echo "$name start $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
  if [ "$rt" = electron ]; then
    ( cd $dir && ELECTRON_RUN_AS_NODE=1 VV_BENCH_JSON=$L/$name.json taskset -c 3 $E scripts/bench-note-signature.cjs \
        --bundle=$bundle "$@" > $L/$name.txt 2>&1 )
  else
    ( cd $dir && VV_BENCH_JSON=$L/$name.json taskset -c 3 node scripts/bench-note-signature.cjs \
        --bundle=$bundle "$@" > $L/$name.txt 2>&1 )
  fi
  echo "$name EXIT=$? end $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
}
{
  echo "# environment $(date -Is)"; lscpu | grep -E 'Model name|^CPU\(s\)|Thread|max MHz'
  echo "cpu3 governor: $(cat /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor); siblings $(cat /sys/devices/system/cpu/cpu3/topology/thread_siblings_list)"
  echo "node versions: $(node -p 'JSON.stringify({node: process.versions.node, v8: process.versions.v8})')"
  echo "electron versions: $(ELECTRON_RUN_AS_NODE=1 $E -p 'JSON.stringify({electron: process.versions.electron, node: process.versions.node, v8: process.versions.v8})')"
  echo "F″ release bundle sha256: $(sha256sum $FSEC/dist/cli/vv-cli.js | cut -d' ' -f1)"
  echo "F″ test build sha256: $(sha256sum $FSEC/dist/test/test.js | cut -d' ' -f1)"
  echo "F″ notes.cljs sha256: $(sha256sum $FSEC/src/vinary/slides/notes.cljs | cut -d' ' -f1)"
  echo "F′ release bundle sha256: $(sha256sum $FPRIME/dist/cli/vv-cli.js | cut -d' ' -f1)"
  echo "bench sha256 (F″ root / F′ root): $(sha256sum $FSEC/scripts/bench-note-signature.cjs | cut -d' ' -f1) / $(sha256sum $FPRIME/scripts/bench-note-signature.cjs | cut -d' ' -f1)"
} > $L/environment.txt 2>&1
MAIN=A,B,C,D,rand,flood,far,S
arm fsec-node-release      $FSEC node     release --only=$MAIN
arm fsec-electron-release  $FSEC electron release --only=$MAIN
arm fsec-node-dev          $FSEC node     dev     --only=$MAIN
for T in 32768 65536 131072 262144 524288 1048576; do
  arm fsec-node-release-L$T     $FSEC node     release --only=L --chars=$T
  arm fsec-electron-release-L$T $FSEC electron release --only=L --chars=$T
done
arm fprime-node-release-S      $FPRIME node     release --only=S
arm fprime-electron-release-S  $FPRIME electron release --only=S
arm fsec-node-release-cap65536 $FSEC node     release --only=C,rand,flood,far --cap=65536
echo DONE >> $L/summary.txt
