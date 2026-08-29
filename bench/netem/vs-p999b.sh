#!/bin/bash
# Completion pass for the p999 campaign: the arms the QUIC-collapse timeout starved.
# tessera first this time - arm order decides who starves when a guard fires.
cd /opt
: > /root/vs-p999b.out
./profiles.sh lte >/dev/null 2>&1
for rep in 1 2 3 4; do
  timeout 1100 ./bench/bin/bench vs --n 20000 --gapUs 20000 --arms tessera,sctp 2>/dev/null     | grep -E "^sctp|^tessera" | sed "s/^/lte rep$rep /" >> /root/vs-p999b.out
done
./profiles.sh clear >/dev/null 2>&1
echo DONE >> /root/vs-p999b.out
