#!/usr/bin/env bash
# Is the tail error in the LINK MODEL itself? Raw UDP through both netems - no transport, no repair, no CC.
# If sim and kernel disagree here, the cause is NetemSim's delay/jitter process, not a transport interaction.
set -u
cd "$(dirname "$0")"
REPS=${1:-4}
PAT='s/^rawudp *n=[0-9]* delivered=[0-9]* late=[0-9]* loss=\([0-9.]*\)%  p50=\([0-9]*\)us p90=[0-9]*us p99=\([0-9]*\)us p999=\([0-9]*\)us.*/\2 \3 \4 \1/p'
echo "rep,profile,arm,p50_ms,p99_ms,p999_ms,loss_pct"
for rep in $(seq 1 "$REPS"); do
  for p in wifi-busy 5g-mmwave lte transcont; do
    ./profiles.sh clear >/dev/null 2>&1
    set -- $(./bench/bin/bench rawudp --netem "$p" --n 4000 --gapUs 500 --size 1200 --warmup 100 2>/dev/null | sed -n "$PAT")
    [ $# -eq 4 ] && echo "$rep,$p,sim,$(($1/1000)),$(($2/1000)),$(($3/1000)),$4" || echo "$rep,$p,sim,NA,NA,NA,NA"
    ./profiles.sh "$p" >/dev/null 2>&1
    set -- $(./bench/bin/bench rawudp --n 4000 --gapUs 500 --size 1200 --warmup 100 2>/dev/null | sed -n "$PAT")
    [ $# -eq 4 ] && echo "$rep,$p,tc,$(($1/1000)),$(($2/1000)),$(($3/1000)),$4" || echo "$rep,$p,tc,NA,NA,NA,NA"
    ./profiles.sh clear >/dev/null 2>&1
  done
done
./profiles.sh clear >/dev/null 2>&1
echo "done"
