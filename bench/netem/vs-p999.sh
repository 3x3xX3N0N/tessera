#!/bin/bash
# p999-powered campaign: n=20000 per run puts ~20 observations behind each run's p999.
# 5 arms x 4 interleaved reps on lte. Kernel sctp module + libsctp installed by the launcher.
cd /opt
: > /root/vs-p999.out
./profiles.sh lte >/dev/null 2>&1
for rep in 1 2 3 4; do
  timeout 3000 ./bench/bin/bench vs --n 20000 --gapUs 20000 --arms udp,tls,quic,sctp,tessera 2>/dev/null     | grep -E "^udp|^tls|^quic|^sctp|^tessera" | sed "s/^/lte rep$rep /" >> /root/vs-p999.out
done
./profiles.sh clear >/dev/null 2>&1
echo DONE >> /root/vs-p999.out
