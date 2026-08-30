#!/bin/bash
# Summon the deep-outstanding stall with full forensics: the ~50% recipe, 6 reps, every byte of output kept.
K=$(cat /root/sinkkey.txt)
ethtool -K enp1s0 tso off gso off tx-udp-segmentation off 2>/dev/null
tc qdisc del dev enp1s0 root 2>/dev/null
tc qdisc add dev enp1s0 root handle 1: prio bands 3
tc qdisc add dev enp1s0 parent 1:3 handle 31: tbf rate 20mbit burst 32kb limit 96000
for port in 51820 51821; do tc filter add dev enp1s0 protocol ip parent 1:0 prio 1 u32 match ip protocol 17 0xff match ip dport $port 0xffff flowid 1:3; done
(setsid nohup sh -c 'sleep 2700; tc qdisc del dev enp1s0 root # TESSERA_SHAPE_WD' >/dev/null 2>&1 &)
: > /root/stall-summary.txt
for rep in 1 2 3 4 5 6; do
  timeout 240 /opt/t2/bin/tessera bulkpush --connect 45.63.29.123:51820 --peer-key "$K" --token bk2 --arm tessera --mb 5 > /root/stall-rep$rep.log 2>&1
  rc=$?
  # a stalled rep exits 124 with no bulkpush line, so the summary must also carry the forensics that name the
  # starved guard leg - otherwise every MISS means opening the per-rep log by hand, which is how the last
  # sighting lost its evidence
  echo "rep$rep exit=$rc $(grep -h bulkpush /root/stall-rep$rep.log | head -1)" >> /root/stall-summary.txt
  grep -h -E 'PUSH-FAIL|SINK-STATS|SAMPLE' /root/stall-rep$rep.log | tail -6 | sed "s/^/  rep$rep /" >> /root/stall-summary.txt
done
tc qdisc del dev enp1s0 root 2>/dev/null
ethtool -K enp1s0 tso on gso on 2>/dev/null
echo DONE >> /root/stall-summary.txt
