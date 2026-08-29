#!/bin/bash
cd /opt && ./profiles.sh lte >/dev/null 2>&1
pkill -f "secnetper[f]" 2>/dev/null; sleep 1
(setsid nohup env MSQUIC_NO_GSO=1 /opt/msquic/build/bin/Release/secnetperf > /root/snp-server.log 2>&1 &)
sleep 2
timeout 90 env MSQUIC_NO_GSO=1 /opt/msquic/build/bin/Release/secnetperf -target:127.0.0.1 -rstream:1 -streams:5 -runtime:30s -up:1200 -down:1200 -platency:1 > /root/snp-smoke.out 2>&1
grep -h "Result:" /root/snp-smoke.out
./profiles.sh clear >/dev/null 2>&1
pkill -f "secnetper[f]" 2>/dev/null
true
