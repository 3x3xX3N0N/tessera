#!/bin/bash
# Shaped bulk rows, one arm per invocation so a stall costs one cell, not the campaign.
# BENCH_OPTS bumps the JVM heap: the payload is loaded whole, and the default heap on a 1 GB box cannot
# hold even a 150 MB slice comfortably alongside the transport's rings.
export BENCH_OPTS="-Xmx700m"
cd /opt
: > /root/vsbulk.out
run() {  # run <label> <arm> <mb> <timeout>
  timeout "$4" ./bench/bin/bench vsbulk --file /root/bbb.avi --mb "$3" --arms "$2" 2>/dev/null     | grep -E "^tls|^quic|^tessera" | sed "s/^/$1 /" >> /root/vsbulk.out
  echo "$1 $2 exit=$?" >> /root/vsbulk-exits.out
}
: > /root/vsbulk-exits.out
./profiles.sh transcont >/dev/null 2>&1
for rep in 1 2; do
  for arm in tls quic tessera; do run "transcont-150MB rep$rep" "$arm" 150 600; done
done
./profiles.sh lte >/dev/null 2>&1
for rep in 1 2; do
  for arm in tls quic tessera; do run "lte-20MB rep$rep" "$arm" 20 900; done
done
./profiles.sh clear >/dev/null 2>&1
echo DONE >> /root/vsbulk.out
