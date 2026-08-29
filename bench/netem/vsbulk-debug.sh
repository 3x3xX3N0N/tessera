#!/bin/bash
export BENCH_OPTS="-Xmx700m"
cd /opt
./profiles.sh transcont >/dev/null 2>&1
timeout 600 ./bench/bin/bench vsbulk --file /root/bbb.avi --mb 60 --arms tessera,quic > /root/vsbulk-debug.out 2>&1
echo "real-exit=$?" >> /root/vsbulk-debug.out
./profiles.sh clear >/dev/null 2>&1
tail -15 /root/vsbulk-debug.out
