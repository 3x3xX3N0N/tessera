#!/usr/bin/env bash
# Compare the in-process NetemSim against real kernel `tc netem` at MATCHED parameters.
#
# Why: every profile number in docs/BENCH-netem.md comes from NetemSim, because the netem matrix is Linux-only
# and this project was developed on Windows. On 2026-08-28 the simulator was found to disagree with real netem
# on a shaped WAN path (BENCH, "Real netem on a real path"), which puts every simulator-derived decision in that
# file on notice. This script isolates the disagreement.
#
# The isolation is the point. Both arms run:
#   - on the SAME machine, the same JVM, the same loopback, with the same workload;
#   - through impairment parameters that profiles.sh and NetemSim's Preset table define identically.
# The only variable is which netem does the work: NetemSim in-process, or the kernel on `lo`.
#
# Arms ALTERNATE per repetition rather than running in blocks, for the reason the whole instrument exists: a
# machine drifts, and two blocks measured at different times are two experiments (BENCH, the withdrawn 7x).
#
# usage: sim-vs-tc.sh [reps] [profiles...]      (defaults: 5 reps, the four profiles that fit 2000 msg/s)
set -u
cd "$(dirname "$0")"
REPS=${1:-5}; shift || true
PROFILES=${*:-"lte wifi-busy transcont 5g-mmwave"}
N=${N:-2000}; GAP=${GAP:-500}; SIZE=${SIZE:-1200}; WARMUP=${WARMUP:-300}

run() {  # run <extra bench args...> -> "p50 p99 p999 loss"
    ./bench/bin/bench tessera "$@" --n "$N" --gapUs "$GAP" --size "$SIZE" --warmup "$WARMUP" 2>/dev/null \
      | sed -n 's/^tessera n=[0-9]* delivered=\([0-9]*\).*loss=\([0-9.]*\)%  p50=\([0-9]*\)us p90=[0-9]*us p99=\([0-9]*\)us p999=\([0-9]*\)us.*/\3 \4 \5 \2/p'
}

echo "sim-vs-tc: ${REPS} reps x ${PROFILES// /, }  (n=$N gapUs=$GAP size=$SIZE)"
echo "rep,profile,arm,p50_ms,p99_ms,p999_ms,loss_pct"
for rep in $(seq 1 "$REPS"); do
    for p in $PROFILES; do
        # SIM arm: kernel clean, impairment in-process
        ./profiles.sh clear >/dev/null 2>&1
        set -- $(run --netem "$p")
        [ $# -eq 4 ] && echo "$rep,$p,sim,$(($1/1000)),$(($2/1000)),$(($3/1000)),$4" || echo "$rep,$p,sim,NA,NA,NA,NA"
        # SIM arm with the kernel's ordering semantics (NetemSim.jitterAfterRate)
        set -- $(run --netem "$p" --jitterAfterRate)
        [ $# -eq 4 ] && echo "$rep,$p,sim2,$(($1/1000)),$(($2/1000)),$(($3/1000)),$4" || echo "$rep,$p,sim2,NA,NA,NA,NA"
        # TC arm: impairment in the kernel, none in-process
        ./profiles.sh "$p" >/dev/null 2>&1
        set -- $(run)
        [ $# -eq 4 ] && echo "$rep,$p,tc,$(($1/1000)),$(($2/1000)),$(($3/1000)),$4" || echo "$rep,$p,tc,NA,NA,NA,NA"
        ./profiles.sh clear >/dev/null 2>&1
    done
done
./profiles.sh clear >/dev/null 2>&1
echo "done (qdisc cleared)"
