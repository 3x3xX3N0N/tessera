#!/bin/bash
export BENCH_OPTS="-Xmx700m"
cd /opt
./profiles.sh lte >/dev/null 2>&1
timeout 240 ./bench/bin/bench vsbulk --file /root/bbb.avi --mb 10 --arms tessera > /root/vb-lte.out 2>&1
echo "exit=$?" >> /root/vb-lte.out
./profiles.sh transcont >/dev/null 2>&1
timeout 240 ./bench/bin/bench vsbulk --file /root/bbb.avi --mb 10 --arms tessera > /root/vb-tc.out 2>&1
echo "exit=$?" >> /root/vb-tc.out
./profiles.sh clear >/dev/null 2>&1
echo ===LTE; tail -4 /root/vb-lte.out; echo ===TRANSCONT; tail -4 /root/vb-tc.out
