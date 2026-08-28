#!/usr/bin/env bash
# The decisive question behind "is NetemSim valid?": does its error change a VERDICT?
#
# sim-vs-tc.sh established that the simulator matches real netem at the median and overstates the tail by
# 1.4-2.7x on profiles with heavy-tailed jitter, reordering or bursty loss. A systematic offset is survivable —
# you correct for it. What is not survivable is the offset interacting with the mechanism under test, because
# then the simulator does not merely mis-scale a number, it changes which arm wins.
#
# So: run the SAME A/B (the repair clock, whose live-vs-sim disagreement started this) in both worlds, on one
# machine, interleaved. The regime is the one the clock exists for — low rate, lossy link, where equations
# accumulate at the application's cadence.
#
# usage: clock-sim-vs-tc.sh [reps] [profile]      (default: 5 reps of lte)
set -u
cd "$(dirname "$0")"
REPS=${1:-5}; PROFILE=${2:-lte}
N=${N:-600}; GAP=${GAP:-20000}; SIZE=${SIZE:-1200}; WARMUP=${WARMUP:-100}

run() {  # run <extra args...> -> "p50us p99us p999us"
    ./bench/bin/bench tessera "$@" --n "$N" --gapUs "$GAP" --size "$SIZE" --warmup "$WARMUP" 2>/dev/null \
      | sed -n 's/^tessera n=.*p50=\([0-9]*\)us p90=[0-9]*us p99=\([0-9]*\)us p999=\([0-9]*\)us.*/\1 \2 \3/p'
}
emit() {  # emit <rep> <arm> <clock> <p50us p99us p999us...>
    local rep=$1 arm=$2 clk=$3; shift 3
    if [ $# -eq 3 ]; then echo "$rep,$arm,$clk,$(($1/1000)),$(($2/1000)),$(($3/1000))"
    else echo "$rep,$arm,$clk,NA,NA,NA"; fi
}

echo "clock-sim-vs-tc: $REPS reps, profile=$PROFILE, $N msgs at $((1000000/GAP))/s"
echo "rep,arm,clock,p50_ms,p99_ms,p999_ms"
for rep in $(seq 1 "$REPS"); do
    for clk in 0 12; do
        ./profiles.sh clear >/dev/null 2>&1
        emit "$rep" sim "$clk" $(run --netem "$PROFILE" --repairClock "$clk")
        ./profiles.sh "$PROFILE" >/dev/null 2>&1
        emit "$rep" tc "$clk" $(run --repairClock "$clk")
        ./profiles.sh clear >/dev/null 2>&1
    done
done
./profiles.sh clear >/dev/null 2>&1
echo "done (qdisc cleared)"
