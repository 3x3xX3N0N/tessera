#!/bin/bash
# One node's share of the multi-box vs experiment: 6 interleaved reps x 4 arms x 2 profiles.
# Arms alternate within each bench invocation; DONE marks completion for the collector.
cd /opt
: > /root/vs-multi.out
for p in lte wifi-busy; do
  ./profiles.sh $p >/dev/null 2>&1
  for rep in 1 2 3 4 5 6; do
    timeout 420 ./bench/bin/bench vs --n 1500 --gapUs 20000 --arms udp,tls,quic,tessera 2>/dev/null       | grep -E "^udp|^tls|^quic|^tessera" | sed "s/^/$p rep$rep /" >> /root/vs-multi.out
  done
  ./profiles.sh clear >/dev/null 2>&1
done
echo DONE >> /root/vs-multi.out
