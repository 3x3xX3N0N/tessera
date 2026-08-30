#!/bin/bash
# usage: pair-phase.sh <label> <peerkey> <pace>
L=$1; K=$2; P=$3
for rep in 1 2 3; do
  timeout 300 /opt/t2/bin/tessera bulkpush --connect 45.63.29.123:51820 --peer-key "$K" --token bk2 --arm tessera --mb 20 --pace $P 2>&1     | grep bulkpush | sed "s/^/$L rep$rep /" >> /root/pair-ab.out
done
