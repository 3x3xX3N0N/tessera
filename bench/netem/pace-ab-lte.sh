#!/bin/bash
# The 166x reproducer, A/B'd: does paceDisengaged kill the self-inflicted queue-overflow loss under
# kernel netem? Arms alternate; the forensics line carries lost= and the stall split.
export BENCH_OPTS="-Xmx700m"
cd /opt
: > /root/pace-ab-lte.out
./profiles.sh lte >/dev/null 2>&1
for rep in 1 2 3; do
  for pace in 0 8; do
    timeout 600 ./bench/bin/bench bulk --mb 20 --runs 1 --paceDisengaged $pace 2>/dev/null       | grep -E "^bulk     run|lost=" | sed "s/^/rep$rep pace$pace /" >> /root/pace-ab-lte.out
  done
done
./profiles.sh clear >/dev/null 2>&1
echo DONE >> /root/pace-ab-lte.out
