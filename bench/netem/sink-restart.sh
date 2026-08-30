#!/bin/bash
# restart the sink with the given credit knobs; print the fresh peer key
pkill -f "bulksin[k]" 2>/dev/null; sleep 1
(setsid nohup /opt/t2/bin/tessera bulksink --token bk2 --port 51820 --growthCap $1 --delayGateUs $2 > /root/sink.log 2>&1 &)
sleep 6
grep -o "peer-key [A-Za-z0-9+/=]*" /root/sink.log | head -1 | cut -d" " -f2
