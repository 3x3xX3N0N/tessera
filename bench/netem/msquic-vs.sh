#!/bin/bash
# Production-QUIC campaign v2: raw client output kept per rep - a grep that matches nothing must not be able
# to erase a rep again (v1 lost all 8 reps to exactly that and reported empty rows).
S=/opt/msquic/build/bin/Release/secnetperf
cd /opt
rm -f /root/snp-rep-*.out
./profiles.sh lte >/dev/null 2>&1
for cc in cubic bbr; do
  for rep in 1 2 3 4; do
    pkill -f "secnetper[f]" 2>/dev/null; sleep 1
    (setsid nohup env MSQUIC_NO_GSO=1 $S -cc:$cc > /root/snp-server.log 2>&1 &)
    sleep 2
    timeout 700 env MSQUIC_NO_GSO=1 $S -target:127.0.0.1 -rstream:1 -streams:5 -runtime:300s -up:1200 -down:1200 -platency:1 > /root/snp-rep-$cc-$rep.out 2>&1
    echo "exit=$?" >> /root/snp-rep-$cc-$rep.out
    pkill -f "secnetper[f]" 2>/dev/null
  done
done
./profiles.sh clear >/dev/null 2>&1
touch /root/snp-done
