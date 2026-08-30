#!/bin/bash
# usage: pair-phase2.sh <label> <peerkey> <pace>  — 5 MB reps, guard 240 s, misses logged as MISS
L=$1; K=$2; P=$3
for rep in 1 2 3; do
  OUT=$(timeout 240 /opt/t2/bin/tessera bulkpush --connect 45.63.29.123:51820 --peer-key "$K" --token bk2 --arm tessera --mb 5 --pace $P 2>&1 | grep bulkpush)
  if [ -n "$OUT" ]; then echo "$L rep$rep $OUT" >> /root/pair-ab.out; else echo "$L rep$rep MISS(>240s)" >> /root/pair-ab.out; fi
done
