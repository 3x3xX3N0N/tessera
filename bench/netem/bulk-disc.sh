#!/bin/bash
# TODO 2c discriminator: the W2 harness (with stall forensics) under kernel netem.
export BENCH_OPTS="-Xmx700m"
cd /opt
: > /root/bulk-disc.out
echo "=== clean control" >> /root/bulk-disc.out
./profiles.sh clear >/dev/null 2>&1
timeout 300 ./bench/bin/bench bulk --mb 20 --runs 1 >> /root/bulk-disc.out 2>&1
echo "exit=$?" >> /root/bulk-disc.out
echo "=== transcont" >> /root/bulk-disc.out
./profiles.sh transcont >/dev/null 2>&1
timeout 600 ./bench/bin/bench bulk --mb 20 --runs 1 >> /root/bulk-disc.out 2>&1
echo "exit=$?" >> /root/bulk-disc.out
echo "=== lte" >> /root/bulk-disc.out
./profiles.sh lte >/dev/null 2>&1
timeout 600 ./bench/bin/bench bulk --mb 20 --runs 1 >> /root/bulk-disc.out 2>&1
echo "exit=$?" >> /root/bulk-disc.out
./profiles.sh clear >/dev/null 2>&1
grep -E "^bulk|exit=|===" /root/bulk-disc.out
