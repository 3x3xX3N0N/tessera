#!/bin/bash
# Bulk campaign: the whole movie on clean loopback, slices under impairment. Two reps each.
cd /opt
: > /root/vsbulk.out
run() {  # run <label> <extra args...>
  local label=$1; shift
  ./bench/bin/bench vsbulk "$@" 2>/dev/null | grep -E "^tls|^quic|^tessera" | sed "s/^/$label /" >> /root/vsbulk.out
}
./profiles.sh clear >/dev/null 2>&1
for rep in 1 2; do run "clean-fullvideo rep$rep" --file /root/bbb.avi; done
./profiles.sh transcont >/dev/null 2>&1
for rep in 1 2; do run "transcont-150MB rep$rep" --file /root/bbb.avi --mb 150; done
./profiles.sh lte >/dev/null 2>&1
for rep in 1 2; do run "lte-30MB rep$rep" --file /root/bbb.avi --mb 30; done
./profiles.sh clear >/dev/null 2>&1
echo DONE >> /root/vsbulk.out
