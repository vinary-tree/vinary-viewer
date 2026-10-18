#!/usr/bin/env bash
# Check the speaker-notes theory (docs/theory/12 §7) against the model's own code. The Clojure scripts evaluate the
# pure functions of src/vinary/slides/notes.cljs verbatim (notes_extract.clj); pdfdoc_check.cjs evaluates pdf.js's
# own decoder and reads the model's tables from notes.cljs. Each script exits non-zero on any violation; this script
# exits with the number of runs that failed (a missing tool or pdf.js worker counts as a failure).
#
# usage: docs/theory/formal/speaker-notes/check.sh [--full]
#   --full  also enumerates every label sequence of length <= 6 over the labels 0..7 (about five minutes and 1.5 GB
#           of resident memory, under -Xmx6g), every escape-free three-byte string through pdf.js's decoder (16.6
#           million strings), and every string of up to six bytes over the eleven-byte alphabet of §6.1's case split
#           (1.9 million; five without). The whole --full run takes about seven minutes.
# Needs `clojure` (the Clojure CLI) and `node` on PATH, and pdf.js 5.4.149's worker, by default
# <root>/node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs (present after `npm install`); PDFJS_WORKER=<file>
# overrides it. Pin it when other work is running, e.g. `taskset -c 2-9 docs/theory/formal/speaker-notes/check.sh`.
# KEEP_LOGS=<dir> keeps every script's full output in <dir>.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../../.." && pwd)"
full=0
if [ "${1:-}" = "--full" ]; then full=1; fi
worker="${PDFJS_WORKER:-$root/node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs}"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/speaker-notes-check.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
# notes_extract.clj finds notes.cljs under VV_ROOT
export VV_ROOT="$root"

failures=0
# run NAME COMMAND...: run one script from the scratch directory (so the repository's own deps.edn is not used),
# print its verdict, its time and its last line, and its whole output when it failed
run() {
  local name="$1"; shift
  local log="$scratch/$name.log"
  local start end status
  start="$(date +%s%N)"
  ( cd "$scratch" && "$@" ) > "$log" 2>&1
  status=$?
  end="$(date +%s%N)"
  if [ -n "${KEEP_LOGS:-}" ]; then mkdir -p "$KEEP_LOGS" && cp "$log" "$KEEP_LOGS/"; fi
  if [ "$status" -eq 0 ]; then verdict="ok"; else verdict="FAILED ($status)"; failures=$((failures + 1)); fi
  printf '%-24s %-12s %7d ms   %s\n' "$name" "$verdict" "$(( (end - start) / 1000000 ))" "$(tail -n 1 "$log")"
  if [ "$status" -ne 0 ]; then sed -n '1,200p' "$log"; fi
}

for tool in clojure node; do
  if ! command -v "$tool" > /dev/null 2>&1; then echo "$tool is not on PATH"; failures=$((failures + 1)); fi
done
if [ "$failures" -gt 0 ]; then exit "$failures"; fi

# §2.1-§2.4: the gap decomposition, its lemmas, the statuses and the restart theorems, against brute force
run automaton-n5 clojure -J-Xmx6g -M "$here/verify_automaton.clj" 5 7
if [ "$full" = 1 ]; then run automaton-n6 clojure -J-Xmx6g -M "$here/verify_automaton.clj" 6 7; fi
# §2.4.8 and §4.6: what the restart guard (and the probe) do with every true deck of each family
run restart-none-n9 clojure -J-Xmx6g -M "$here/restart_guard.clj" none 9
run restart-appendix-n12 clojure -J-Xmx6g -M "$here/restart_guard.clj" appendix 12
run restart-drops-n11 clojure -J-Xmx6g -M "$here/restart_guard.clj" drops 11
run restart-L0-by-hand-n9 clojure -J-Xmx6g -M "$here/restart_guard.clj" drops-L0-by-hand 9
run restart-forward-n9 clojure -J-Xmx6g -M "$here/restart_guard.clj" forward 9
# §6: pdf.js's decoder, PDFDOC⁻¹ and REPAIR
if [ -f "$worker" ]; then
  if [ "$full" = 1 ]; then
    run pdfdoc node "$here/pdfdoc_check.cjs" "$root" "$worker" --three
  else
    run pdfdoc node "$here/pdfdoc_check.cjs" "$root" "$worker"
  fi
else
  echo "pdfdoc: no pdf.js worker at $worker (run npm install, or set PDFJS_WORKER)"
  failures=$((failures + 1))
fi
# §3 and §4.5: the shifted grids, and the page reader under CONFIRM
run grids-and-reader node "$here/grid_and_budget_check.cjs"
exit "$failures"
