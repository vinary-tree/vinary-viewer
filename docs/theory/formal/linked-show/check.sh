#!/usr/bin/env bash
# Model-check the linked-show protocol (docs/theory/11 §6). Every positive configuration must finish with no
# error; every negative control (one rule removed) must violate the property named beside it — a control that
# passes, or fails on another property, means the model does not test what it claims to.
#
# usage: docs/theory/formal/linked-show/check.sh [--deep] [workers]   (TLC 2.19+ on PATH as `tlc`)
# --deep adds the larger positive configurations (three pages, four or five of the people's actions: millions of
# distinct states and minutes each on 16 workers).
# Pin it when other work is running, e.g. `taskset -c 16-31 docs/theory/formal/linked-show/check.sh 16`.
# KEEP_LOGS=<dir> keeps every configuration's full TLC output (the counterexample traces) in <dir>.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
deep=0
if [ "${1:-}" = "--deep" ]; then deep=1; shift; fi
workers="${1:-4}"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/linked-show-tlc.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
# keep TLC's extracted standard modules and its state queue out of the shared /tmp
export TLA_JAVA_OPTS="${TLA_JAVA_OPTS:-} -Djava.io.tmpdir=$scratch -XX:+UseParallelGC"

# configuration → expected outcome ("ok", or the property that must be violated)
expect=(
  "fifo ok"
  "reorder ok"
  "snap-fifo ok"
  "snap-reorder ok"
  "away-fifo ok"
  "away-reorder ok"
  "away-snap-fifo ok"
  "neg-nohold Convergence"
  "neg-noheldcheck Convergence"
  "neg-noapplying NoEcho"
  "neg-nostaleguard Convergence"
  "neg-nomidguard Convergence"
  "neg-announce NoUndo"
  "neg-upsnap BoundedCorrections"
  "neg-staleremount NoEcho"
  "neg-awaydrop Convergence"
)
if [ "$deep" = 1 ]; then expect+=("fifo-deep ok" "reorder-deep ok"); fi

failures=0
for row in "${expect[@]}"; do
  cfg="${row%% *}"; want="${row#* }"
  log="$scratch/$cfg.log"
  # a negative control stops at its first violation: one worker makes that search -- the states it counts and the
  # (shortest) counterexample it prints -- the same on every run; the positives explore everything, so workers help
  case "$cfg" in neg-*) w=1 ;; *) w="$workers" ;; esac
  ( cd "$here" && tlc -workers "$w" -deadlock -nowarning -metadir "$scratch/$cfg" \
      -config "$cfg.cfg" LinkedShow.tla ) > "$log" 2>&1
  if [ -n "${KEEP_LOGS:-}" ]; then mkdir -p "$KEEP_LOGS" && cp "$log" "$KEEP_LOGS/"; fi
  states="$(grep -Eo '[0-9,]+ distinct states found' "$log" | tail -1)"
  if grep -q 'Model checking completed. No error has been found.' "$log"; then
    got="ok"
  elif viol="$(grep -Eo 'Invariant [A-Za-z]+ is violated' "$log" | head -1)" && [ -n "$viol" ]; then
    got="$(echo "$viol" | awk '{print $2}')"
  elif grep -q 'Temporal properties were violated' "$log"; then
    got="temporal"
  elif grep -q 'Action property [A-Za-z]* is violated' "$log"; then
    got="$(grep -Eo 'Action property [A-Za-z]+' "$log" | head -1 | awk '{print $3}')"
  else
    got="tlc-error"
  fi
  if [ "$got" = "$want" ]; then verdict="as predicted"; else verdict="UNEXPECTED"; failures=$((failures + 1)); fi
  depth="$(grep -Eo 'depth of the complete state graph search is [0-9]+' "$log" | grep -Eo '[0-9]+$')"
  trace="$(grep -c '^State [0-9]*:' "$log")"
  printf '%-18s expected %-12s got %-12s %-13s %s%s\n' "$cfg" "$want" "$got" "$verdict" "${states:-?}" \
    "$( [ "$got" = ok ] && echo ", depth ${depth:-?}" || echo ", counterexample of $trace states")"
  if [ "$verdict" = "UNEXPECTED" ]; then sed -n '1,400p' "$log"; fi
done
exit "$failures"
