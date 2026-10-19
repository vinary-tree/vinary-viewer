#!/usr/bin/env bash
# H-PAIRWORK-FIX′, as registered: F′'s bench (node, release then dev; A, B, C, D, rand, flood, far), then the
# replication under Electron's own V8 (ELECTRON_RUN_AS_NODE=1, release bundles of F and F′; rand, flood, far) and
# the string probe under Electron; core 3; the load checked (< 12) before each arm.
set -u
J=${SCRATCH:?set SCRATCH to the scratch directory}; L=$J/pairwork/fprime/bench
E=${REPO:?set REPO to the repository}/node_modules/electron/dist/electron
quiet() { local t0=$(date +%s); until awk -v l="$(cut -d' ' -f1 /proc/loadavg)" 'BEGIN { exit !(l < 12) }'; do
  [ $(( $(date +%s) - t0 )) -gt 10800 ] && { echo "gave up waiting: $(cat /proc/loadavg)" >> $L/summary.txt; exit 1; }; sleep 20; done; }
arm() {   # name, dir, runtime (node|electron), bundle, cases, [--matches=X]
  local name=$1 dir=$2 rt=$3 bundle=$4 only=$5 extra=${6:-}
  quiet
  echo "$name start $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
  if [ "$rt" = electron ]; then
    ( cd $dir && ELECTRON_RUN_AS_NODE=1 VV_BENCH_JSON=$L/$name.json taskset -c 3 $E scripts/bench-note-signature.cjs \
        --bundle=$bundle --only=$only $extra > $L/$name.txt 2>&1 )
  else
    ( cd $dir && VV_BENCH_JSON=$L/$name.json taskset -c 3 node scripts/bench-note-signature.cjs \
        --bundle=$bundle --only=$only $extra > $L/$name.txt 2>&1 )
  fi
  echo "$name EXIT=$? end $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
}
{
  echo "# environment $(date -Is)"; lscpu | grep -E 'Model name|^CPU\(s\)|Thread|max MHz'
  echo "cpu3 governor: $(cat /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor); siblings $(cat /sys/devices/system/cpu/cpu3/topology/thread_siblings_list)"
  echo "node versions: $(node -p 'JSON.stringify({node: process.versions.node, v8: process.versions.v8})')"
  echo "electron versions: $(ELECTRON_RUN_AS_NODE=1 $E -p 'JSON.stringify({electron: process.versions.electron, node: process.versions.node, v8: process.versions.v8})')"
  echo "F′ bundle sha256: $(sha256sum $J/wt-pairwork/dist/cli/vv-cli.js | cut -d' ' -f1)"
  echo "F  bundle sha256: $(sha256sum $J/benchF-root/dist/cli/vv-cli.js | cut -d' ' -f1)"
  echo "F′ notes.cljs sha256: $(sha256sum $J/wt-pairwork/src/vinary/slides/notes.cljs | cut -d' ' -f1)"
} > $L/environment.txt 2>&1
arm fprime-release $J/wt-pairwork node release A,B,C,D,rand,flood,far
arm fprime-dev $J/wt-pairwork node dev A,B,C,D,rand,flood,far
# Q2′'s fallback, as registered: the same shapes with X matches, release, for every power of two X from 32 768 down to
# 8 192 (the rule takes the largest X whose four medians are all ≤ 16 ms; all three are measured)
for X in 32768 16384 8192; do arm fprime-release-m$X $J/wt-pairwork node release C,rand,flood,far --matches=$X; done
arm F-electron-release $J/benchF-root electron release rand,flood,far
arm fprime-electron-release $J/wt-pairwork electron release rand,flood,far
quiet
echo "probe-electron start $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
ELECTRON_RUN_AS_NODE=1 taskset -c 3 $E $J/pairwork/flood/probe-string-hash.cjs > $L/probe-string-hash-electron.txt 2>&1
echo "probe-electron EXIT=$? end $(date -Is) load $(cat /proc/loadavg)" >> $L/summary.txt
echo DONE >> $L/summary.txt
