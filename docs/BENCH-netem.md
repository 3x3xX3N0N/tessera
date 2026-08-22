# Tessera under tc netem: first link-profile matrix (2026-08-22)

First run of `bench/netem/run-matrix.sh` on Linux (WSL2). Everything below is reproducible with
`sudo -E bench/netem/run-matrix.sh` (about 25 minutes; knobs in the script header). Raw output is in
`bench/results/` (`summary.txt`, one `.csv`/`.log` per run, `<label>_env.txt` with the applied qdisc and a ping
under it, `run-matrix.log` for the whole console, `run1-summary.txt` for the earlier run discussed under "variance").

## Environment

| | |
|---|---|
| Kernel | `Linux 6.18.33.2-microsoft-standard-WSL2 x86_64` (WSL2 distro `nixos-a`, NixOS 26.05 "Yarara"), 16 logical CPUs, 32 GB |
| tc | `tc utility, iproute2-7.0.0, libbpf 1.7.0` (`/run/current-system/sw/bin/tc`); `CONFIG_NET_SCH_NETEM=m`; `normal`/`pareto` distribution tables present |
| JDK | OpenJDK 21.0.12+2-nixos from `nix-shell -p jdk21` (the distro has no `java`; `run-matrix.sh` bootstraps it) |
| Build | Gradle 8.13 wrapper, Kotlin 2.1.20, `./gradlew :bench:installDist` (bench depends on core + transport only, so `:native`/cargo is not built) |
| Bench JVM | `-XX:+UseZGC -XX:+ZGenerational`, built and run from a native ext4 path (`~/tessera-netem`), not `/mnt/c` |

## Method

* All traffic is on `lo`. One netem qdisc on `lo` sits on the egress path of **both** directions, so the effective
  RTT is ~2 x `delay` and the rate cap is shared by data and acks. Every profile was verified with
  `tc qdisc show dev lo` and a 10-packet ping before each sweep; `profiles.sh clear` returns `lo` to
  `qdisc noqueue 0: root refcnt 2` and the matrix script does that in an EXIT trap.
* `gemodel p r` is Gilbert-Elliott with every packet in the bad state lost: the **average** loss is p/(p+r), not p
  (lte and 5g-mmwave are ~4.8 %, starlink ~1.6 %). The profile lines were kept as designed; their comments and the
  table below state the real averages. Every profile line (gemodel, pareto/normal tables, reorder) was accepted by
  this tc/kernel as written, so the `loss <p>% 25%` fallback was not needed (it is still available as
  `LOSS_MODEL=simple`).
* Bench: `bench <mode> --n 5000 --gapUs 500` = 2000 messages/s of 1200 B (19.7 Mbit/s of payload), 500 unmeasured
  warm-up messages, one-way latency per message (same host, shared clock). The receiver thread gives up after
  `(500 + n) x gap + 2 s = 4.75 s`, so **"delivered" means delivered inside that budget**.
* `adapt` under netem is run with `--lossSim 0` (its default 5 % in-process loss model exists for hosts without
  netem); it is therefore `tessera` plus the estimator read-out. The plain-loopback baseline keeps the defaults.
* `connect` = 2000 CPU-only iterations, then 500 fresh PQ and 500 resumed handshakes over the wire (+20 warm-up
  each). The bench has no per-iteration catch: one failed handshake aborts the run.
* Two sweeps were added after the first 2000 msg/s results (see "Reading"): **lowrate** = `tessera` at
  50 msg/s (`--n 2000 --gapUs 20000`) under the same lossy profiles, and **rttonly** = `rawudp` vs `tessera` at
  50 msg/s with the profile's loss removed (`LOSS_MODEL=none`: delay/jitter/reorder/rate only) and the bench's
  in-process 5 % loss model instead (`--lossSim 0.05` drops the client's data packets but never grants or acks).
  With n = 2000, p999 is the 3rd-largest sample.
* Two complete runs were made; numbers are from run 2 unless marked "run 1".

## Profiles (as applied) and effective RTT

| profile | netem line | one-way (nominal) | ping RTT idle min/avg/max ms | loss model (average) | rate |
|---|---|---|---|---|---|
| lan-clean | none (`noqueue`) | 0 | 0.03 / 0.04 / 0.07 | none | - |
| transcont | `delay 90ms 2ms loss 0.1% rate 1gbit` | 90 ms | 177.9 / 179.7 / 182.6 | random 0.1 % | 1 Gbit/s |
| starlink | `delay 35ms 12ms loss gemodel 0.5% 30% rate 100mbit` | 35 +- 12 ms | 60.0 / 71.6 / 86.6 | GE p=0.5 % r=30 %: **1.6 %** avg, ~3-packet bursts | 100 Mbit/s |
| lte | `delay 45ms 15ms distribution normal loss gemodel 1% 20% rate 30mbit` | 45 +- 15 ms | 71.0 / 97.1 / 146.7 | GE p=1 % r=20 %: **4.8 %** avg, ~5-packet bursts | 30 Mbit/s |
| wifi-busy | `delay 8ms 20ms distribution pareto loss 3% reorder 5% rate 80mbit` | 8 +- 20 ms (pareto) | 0.05 / 25.4 / 59.1 | random 3 %; 5 % of packets skip the delay | 80 Mbit/s |
| 5g-mmwave | `delay 12ms 8ms distribution pareto loss gemodel 2% 40% rate 400mbit` | 12 +- 8 ms (pareto) | 16.2 / 26.8 / 52.2 | GE p=2 % r=40 %: **4.8 %** avg, ~2.5-packet bursts | 400 Mbit/s |

Loss actually measured by `rawudp` over 5000 packets at 2000 msg/s: transcont 0.22 % (run 1: 0.14 %), starlink
1.40 % (1.64 %), lte 3.32 % (5.30 %), wifi-busy 3.00 % (2.74 %), 5g-mmwave 5.58 % (3.92 %).

**Idle RTT is not loaded RTT.** With `rate` set, netem never reorders: each packet is scheduled no earlier than its
predecessor, so positive jitter accumulates into a standing queue at high packet rates. The `rawudp` one-way
median at 2000 msg/s vs 50 msg/s: wifi-busy 69.9 vs 4.5 ms, lte 75.8 vs 47.7 ms, 5g-mmwave 27.5 vs 10.1 ms,
starlink 43.7 vs 35.4 ms, transcont 90.8 vs 90.3 ms. Compare latencies only within the same send rate.

## Plain-loopback baseline (README commands, Linux)

```
connect  cpu: client-build p50=237us p99=661us | server-accept p50=187us p99=506us | first-flight budget fresh=184B resumed=1232B
connect  wire fresh-PQ 0-RTT payload at server p50=905us p99=2699us | first response at client p50=1104us p99=2826us (n=500)
connect  wire resumed  0-RTT payload at server p50=277us p99=1835us | first response at client p50=380us p99=2021us (n=500)
adapt   n=5000 delivered=5000 loss=0.00%  p50=109us p90=171us p99=1039us p999=1587us      (5 % in-process loss, 500 us gap)
adapt    fecRedundancy=0.115 (floor 0.02; v0 constant was 0.50) estimator lossRate=0.044 wireLoss=0.044 srtt=237us | repair(pro=650 react=198 tlp=0)
tessera  n=5000 delivered=5000 loss=0.00%  p50=133us p90=222us p99=1617us p999=2622us      (--lossSim 0.05, 1000 us gap)
```
Same shape as the Windows numbers in SPEC.md (adapt p50 77 / p99 940 us, resumed connect p50 322 us); WSL
loopback is ~1.4x slower per packet.

## Results per profile (2000 msg/s matrix + the two 50 msg/s sweeps)

Latencies are one-way, in ms unless marked us. "FAILED" quotes the exception the bench died with.

### lan-clean (control, RTT 0.04 ms)

| mode | delivered | p50 | p99 | p999 |
|---|---|---|---|---|
| rawudp | 5000/5000 (100 %) | 56 us | 869 us | 6104 us |
| tessera | 5000/5000 (100 %) | 125 us | 303 us | 531 us |
| adapt | 5000/5000 (100 %) | 130 us | 307 us | 851 us |

* adapt: `fecRedundancy=0.071` (lossRate 0.000, srtt 138 us), 417 proactive / 0 reactive repairs.
* connect fresh-PQ: at server p50 962 / p99 3270 us, first response p50 1147 / p99 3575 us;
  resumed: at server p50 316 / p99 3891 us, first response p50 432 / p99 4110 us.
* run 1: rawudp p99 171 / p999 293 us, tessera p99 228 / p999 418 us, fresh 817 / 3264 us, resumed 283 / 2986 us
  (the 6.1 ms rawudp p999 in run 2 is a host hiccup).
* lowrate: tessera 100 %, p50 259 / p99 466 / p999 642 us. rttonly (5 % sim loss): rawudp 95.5 %, p50 150 /
  p99 509 us; tessera 100 %, p50 245 / p99 5044 / p999 5574 us (121 tail-loss probes, 0 reactive: at 50 msg/s a
  single loss has no successor inside the loss timer, so the ~2 ms TLP repairs it).

### transcont (RTT 180 ms, 0.1 % loss)

| mode | delivered | p50 | p99 | p999 |
|---|---|---|---|---|
| rawudp | 4989/5000 (99.78 %) | 90.77 | 92.07 | 92.15 |
| tessera | 0/5000 inside the 4.75 s budget | - | - | - |
| adapt | 0/5000 inside the 4.75 s budget | - | - | - |

* Both Tessera runs **crawled**: 5937 packets in ~50 s = ~120 msg/s, 882 credit stalls (see Reading 1); the
  server did receive 5493 of 5500 messages. run 1: `tessera` died after 136 s with
  `IllegalStateException: no receiver credit after 5000ms`.
* adapt: `fecRedundancy=0.073`, estimator lossRate 0.002, srtt 182.0 ms, 423 proactive / 0 reactive / 3 TLP.
* connect (192 s): fresh-PQ at server p50 91.39 / p99 93.97 ms, first response p50 182.34 / p99 185.51 ms;
  resumed at server p50 90.48 / p99 93.20 ms, first response p50 181.37 / p99 184.74 ms. run 1: aborted around
  handshake 460 with `TimeoutException: tessera connect ... timed out after 3000ms`.
* lowrate: tessera 2000/2000, p50 90.26 / p99 92.30 / p999 190.93 ms, 0 stalls.
* rttonly: rawudp 1910/2000 (95.5 %), p50 90.32 / p99 92.22 / p999 92.35 ms; tessera 2000/2000, p50 90.40 /
  **p99 231.05 / p999 454.21 ms** (max 614 ms); 127 drops repaired by 312 proactive + 9 reactive + 3 TLP symbols;
  80 messages (4 %) arrived > 2 ms after the median, 61 > 50 ms, 32 > 100 ms, 10 > 200 ms.

### starlink (RTT 72 ms, GE 1.6 %)

| mode | delivered | p50 | p99 | p999 |
|---|---|---|---|---|
| rawudp | 4930/5000 (98.60 %) | 43.71 | 47.07 | 50.75 |
| tessera | FAILED after 12 s: `no receiver credit after 5000ms` | | | |
| adapt | FAILED after 14 s: `no receiver credit after 5000ms` | | | |

* connect: FAILED after 12 s: `TimeoutException: tessera connect ... timed out after 3000ms` (run 1: 5 s).
* lowrate: FAILED after 53 s: `no receiver credit after 5000ms`.
* rttonly: rawudp 95.5 %, p50 35.44 / p99 47.04 / p999 47.31 ms; tessera 100 %, p50 37.47 / **p99 131.84 /
  p999 148.55 ms** (311 proactive + 46 reactive + 3 TLP; 53 messages > 50 ms above the median, 17 > 100 ms).

### lte (RTT 97 ms, GE 4.8 %)

| mode | delivered | p50 | p99 | p999 |
|---|---|---|---|---|
| rawudp | 4834/5000 (96.68 %) | 75.76 | 91.83 | 94.72 |
| tessera | FAILED after 15 s: `no receiver credit after 5000ms` | | | |
| adapt | FAILED after 13 s: `no receiver credit after 5000ms` | | | |

* connect: FAILED after 10 s: `IllegalStateException: no response` (run 1: 8 s, connect timeout).
* lowrate: FAILED after 52 s: `no receiver credit after 5000ms`.
* rttonly: rawudp 95.5 %, p50 47.71 / p99 81.30 / p999 88.42 ms; tessera 100 %, p50 51.78 / **p99 164.68 /
  p999 212.08 ms** (312 proactive + 27 reactive + 3 TLP; 49 messages > 50 ms above the median, 28 > 100 ms).

### wifi-busy (RTT 25 ms idle, ~140-175 ms loaded; 3 % loss + 5 % reorder)

| mode | delivered | p50 | p99 | p999 |
|---|---|---|---|---|
| rawudp | 4850/5000 (97.00 %) | 69.87 | 88.21 | 88.28 |
| tessera | 2992/5000 (59.84 %) - credit crawl, budget exhausted | 78.14 | 94.82 | 102.81 |
| adapt | 2754/5000 (55.08 %) - credit crawl, budget exhausted | 77.17 | 88.70 | 105.09 |

* adapt: `fecRedundancy=0.500` (the cap), estimator lossRate **0.951** against 3 % real loss, srtt 58.9 ms;
  client sent 9164 packets for 5500 sources (2730 proactive + 734 reactive repairs); server: 8902 packets
  received of which **authFail=4153**, gaps 8600, recovered 95, 3164 messages. Same in run 1 (authFail 4111).
* connect: FAILED after 4 s: `IllegalStateException: no response` (run 1: 3 s).
* lowrate: tessera 2000/2000, p50 9.84 / p90 48.50 / p99 88.43 / p999 107.14 ms (363 proactive + 105 reactive
  repairs; 139 gaps on the ack path; authFail 0).
* rttonly: rawudp 95.5 %, p50 4.53 / p90 36.51 / p99 88.27 / p999 88.41 ms; tessera 100 %, p50 10.44 /
  p90 50.12 / p99 88.46 / p999 131.35 ms (393 proactive + 131 reactive + 2 TLP; authFail 0).

### 5g-mmwave (RTT 27 ms idle, GE 4.8 %)

| mode | delivered | p50 | p99 | p999 |
|---|---|---|---|---|
| rawudp | 4721/5000 (94.42 %) | 27.47 | 44.11 | 44.17 |
| tessera | FAILED after 12 s: `no receiver credit after 5000ms` | | | |
| adapt | FAILED after 8 s: `no receiver credit after 5000ms` | | | |

* connect: FAILED after 7 s: `TimeoutException: tessera connect ... timed out after 3000ms`.
* lowrate: FAILED after 54 s: `no receiver credit after 5000ms`.
* rttonly: rawudp 95.5 %, p50 10.08 / p90 20.20 / p99 44.21 / p999 44.32 ms; tessera 100 %, p50 11.10 /
  p90 26.92 / **p99 64.21 / p999 102.48 ms** (310 proactive + 86 reactive + 3 TLP; 24 messages > 50 ms above the
  median).

### Settled `fecRedundancy` (adapt)

| profile | fecRedundancy | estimator lossRate | wire loss (rawudp) | note |
|---|---|---|---|---|
| baseline (5 % sim loss) | 0.115 | 0.044 | 4.4 % sim | converged as designed |
| lan-clean | 0.071 | 0.000 | 0 % | 0.071 = 2.3 sigma of the Kalman prior, above the 0.02 floor |
| transcont | 0.073 | 0.002 | 0.22 % | converged, but measured while crawling at 120 msg/s |
| wifi-busy | 0.500 (cap) | 0.951 | 3.0 % | reorder + authFail make almost every packet look lost |
| starlink / lte / 5g-mmwave | - | - | 1.4 / 3.3 / 5.6 % | run died on credit before the read-out |

## Reading

**Where Tessera wins today**

1. Loss hiding with a real RTT, once the credit loop is not the bottleneck (rttonly sweep, 50 msg/s, 5 % loss):
   every profile delivered 100 % vs rawudp's 95.5 %, and the median barely moved (transcont +0.1 ms, 5g +1.0,
   starlink +2.0, lte +4.1, wifi-busy +5.9 ms: AEAD/FEC work plus the repair symbols sharing the rate-limited
   queue). The price is paid in the tail: p99 231 vs 92 ms (transcont), 132 vs 47 (starlink), 165 vs 81 (lte),
   64 vs 44 (5g), 88 vs 88 (wifi-busy, where jitter dominates anyway).
2. On clean loopback Tessera costs ~70 us of median latency over raw UDP (125 vs 56 us) with a tighter tail
   (p999 531 vs 6104 us in run 2, 418 vs 293 us in run 1) at 7 % proactive redundancy.
3. connect over 180 ms RTT behaves exactly as the design says: the 0-RTT payload is in the server application
   after one one-way delay (p50 91.4 ms) and the client has its first response after one RTT (p50 182.3 ms);
   resumed is only ~1 ms faster than fresh PQ at that RTT (0.9 vs 0.3 ms of handshake CPU on loopback), so the
   ML-KEM handshake is invisible on a WAN. p99 is p50 + 2.6 ms (jitter), no retransmissions needed.

**Where it does not yet**

1. **Receiver-driven credit caps the sender at ~23 packets in flight, whatever the RTT.**
   `ReceiverCredit.tick()` sizes grants from the receiver's own `PathEstimator.deliveredBytesPerSec`, which is
   only fed by acks of the receiver's own packets; a pure receiver sends nothing ack-eliciting, so the rate stays
   0 and the BDP collapses to the floor `10 x MAX_DATAGRAM` (minRtt defaults to 50 ms as well). With the sender's
   initial window (10 x 1350 B) the steady-state ceiling is ~28 KB = ~23 packets of 1228 B per grant round trip:
   23 / 0.182 s = 126 msg/s predicted, 120 msg/s measured on transcont, i.e. 6 % of the 2000 msg/s offered
   (882 stalls, all measured messages arrived after the bench's 4.75 s budget). On loopback the grant round trip
   is 0.1 ms and the cap is invisible, which is why the README numbers look fine.
2. **A lost grant is never re-issued, so a few lost grants deadlock the connection.** The receiver grants only
   when `granted - received < target/2`; a sender that is out of credit sends nothing, `received` stops growing,
   and the lost credit (~7-15 KB per grant) is gone for good. After 3-4 lost grants `send()` blocks until
   `creditWaitMs` and throws `no receiver credit after 5000ms`. This killed tessera/adapt on starlink, lte and
   5g-mmwave within 8-15 s at 2000 msg/s and within 52-54 s at 50 msg/s (so it is a loss problem, not a rate
   problem); transcont at 0.1 % loss died after 136 s in run 1 and survived run 2.
3. **connect aborts on the first unlucky handshake.** Two paths: (a) the server's handshake reply is lost; the
   bench server has already answered and closed the connection, so the retransmitted Initial (200 ms timer) no
   longer matches `byConnId` and the 0-RTT replay filter rejects it: `TimeoutException after 3000ms` (starlink,
   5g-mmwave, transcont run 1); (b) the server's first data packet is lost and its three tail-loss probes go out
   1 ms apart (a fresh server connection has no srtt, so `lossTimeoutUs()` sits on its 1 ms floor) inside the
   same Gilbert-Elliott burst: `no response` (lte, wifi-busy). Per handshake the odds are ~2 x loss rate, so the
   1040-handshake bench cannot finish at 1.6-4.8 % loss.
4. **Repair costs more than "< 1/2 RTT" in this regime.** At 50 msg/s with 5 % loss on transcont, p99 = 231 ms =
   one-way 90 ms + 141 ms. Nearly every repair was proactive (312 vs 9 reactive): at the settled 12.5 %
   redundancy a repair symbol goes out every 8 packets = every 160 ms at this rate, so a lost packet waits 0-160 ms
   for it; the p999 of 454 ms (max 614) are the losses that needed the full ack-driven path (gap seen when the
   next packet lands 20 ms later, +90 ms for the ack, +90 ms for the repair) or two symbols. Per profile the
   p99 - p50 gap is 141 ms (transcont), 94 ms (starlink), 113 ms (lte), 53 ms (5g-mmwave): 0.8 x, 1.3 x, 1.2 x and 2.0 x the idle RTT, i.e. an RTT-scale ack-and-repair round trip plus
   the inter-repair wait. At 2000 msg/s a proactive symbol would come every 4 ms and this would look very
   different, but item 1 prevents that measurement today.
5. **Reordering breaks the 1-byte packet number (wifi-busy at 2000 msg/s):** the server failed AEAD
   authentication on 4153 of 8902 received packets (47 %), recovered only 95 losses, and the estimator saw 95 %
   loss, pinning redundancy at the 0.5 cap and adding 734 reactive repairs (with `reorderThreshold = 1` every
   reordered packet is a "loss"). Reading the code: `ShortHeader.pnLenFor` picks a 1-byte PN whenever
   `pn - largestAcked < 63`, which item 1's 23-packet window guarantees, and the receiver decodes relative to
   `largestSeen +- 128`. netem's `reorder 5%` sends one packet in twenty with no delay, i.e. 70-100 packets ahead
   of the queue at the ~1100 pkt/s actually sent, and everything that then arrives more than 128 behind it decodes
   to the wrong PN and the wrong nonce. At 50 msg/s (both wifi-busy sweeps) authFail is 0 and delivery 100 %, which
   fits. To be confirmed by the transport owners.
6. `fecWindow = 32` packets is 16 ms at 2000 msg/s. Reactive repair only helps while the lost symbol is still in
   the encoder window when the ack arrives, i.e. for RTT < 16 ms at this rate; every impaired profile here is above
   that. Masked by item 1 today; it is the next wall once credit scales with BDP.

**Harness caveats**

* "delivered" is within the bench's receive budget; crawling runs report 0-60 % "loss" that is budget exhaustion
  (transcont: delivered 0, server got 5493/5500).
* The connect bench needs a per-iteration catch and failure counter to give numbers on lossy links at all.
* Profiles with `rate` plus jitter build a standing queue under load (see "Idle RTT is not loaded RTT"); the 2000
  msg/s latencies include it.
* Run-to-run variance: loopback tails vary 2-20x (WSL scheduling); loss-driven failures vary in timing (transcont
  tessera/connect failed in run 1 only). Treat single runs as indicative; re-run before quoting.

**Suggested next steps (transport/core, not done here)**

1. `ReceiverCredit`: estimate the delivery rate at the receiver from bytes received per interval (it already sees
   `onReceived`) and seed `minRtt` from the handshake or ack `rxTimeUs`; size the floor by RTT.
2. Make grants idempotent (advertise an absolute credit limit, like a window, instead of an additive delta) or
   re-issue credit on a timer while the sender is below target: Homa re-sends grants on timeout for this reason.
3. Keep `connId -> stored handshake reply` for a few seconds after close (or the bench server keeps the
   connection open); catch and count failures in `Connect.kt`.
4. Space tail-loss probes by RTT (handshake RTT is known) and allow more than three; 2-byte PN floor or a
   reorder-aware `pnLenFor`; `reorderThreshold = 3` as in QUIC; `fecWindow` sized by rate x RTT.

## Files

```
bench/netem/profiles.sh      profiles + clear/show/rtt/list/version, LOSS_MODEL={gemodel,simple,none}
bench/netem/run-matrix.sh    build (nix JDK bootstrap), baseline, matrix, lowrate, rttonly; EXIT trap clears lo
bench/results/env.txt        versions + parameters      bench/results/summary.txt   every summary line, [label]-prefixed
bench/results/<label>_<mode>.csv|.log   per run         bench/results/<label>_env.txt   qdisc + ping under the profile
bench/results/run-matrix.log full console of run 2      bench/results/run1-summary.txt  run 1 (2000 msg/s matrix only)
```

---

## Run 2 — wave-2 tree (`f16420d`: all fixes except the credit-primary CC `fc29252`), same harness
Native datapath active in WSL (cargo via nix). `8 run(s) failed` (all `connect` + the CWND freezes). Delivered % is
*within the bench deadline*; the server-side `msgs=` counters show every message arrived on wifi-busy / 5g — late.

### 2000 msg/s (delivered %, p50 / p99 / p999 ms one-way)
| profile | rawudp | tessera | adapt | connect |
|---|---|---|---|---|
| lan-clean | 100 %, 0.05/0.16/0.45 | 100 %, 0.12/**0.30**/0.47 | 100 %, 0.12/0.37/0.68 | fresh 0.80/2.4, resumed 0.28/2.8 |
| transcont (180 ms RTT) | 99.9 %, 90.8/94.8/112 | 88 % (tail cut by deadline: CUBIC slow start), 91.3/96.8/103 | **100 %, 91.1/92.2/92.5** | FAILED: timeout (deterministic) |
| starlink | 98.6 %, 43.7/47.0/47.2 | **99.4 %, 44.5/47.3/61.5** | 27 % (CWND throttled) | FAILED: no response |
| lte | 94.8 %, 75.0/91.9/95.3 | FAILED: send blocked CWND_LIMITED | FAILED: CWND_LIMITED | FAILED: timeout |
| wifi-busy | 97.0 %, 68.5/88.2/88.3 | 0 % in deadline (server got 5499; CWND + plpmtu 1200) | 0 % in deadline (server got 5499) | FAILED: timeout |
| 5g-mmwave | 95.7 %, 26.9/44.1/44.3 | 4.6 % in deadline | 0 % in deadline (server got 5500) | FAILED: no response |

### 50 msg/s, real loss (tessera only)
lan-clean 100 % 0.30/0.77/1.97 · transcont **100 % 90.4/92.3/92.5** · starlink **100 % 37.5/55.1/145** · lte FAILED (CWND) ·
wifi-busy 70 % (plpmtu stuck at 1200 → 2 fragments/msg, CWND) · 5g-mmwave 99.8 % 12.1/78.2/375.

### 50 msg/s, RTT-only + 5 % in-process loss (rawudp → tessera, p50/p99/p999 ms)
| profile | rawudp (95.5 %) | tessera |
|---|---|---|
| lan-clean | 0.19/0.63/0.98 | 100 %: 0.33/1.89/4.98 |
| transcont | 90.3/92.3/92.6 | **100 %: 90.6/97.0/104** (run 1: 231/454 — tail repair) |
| starlink | 34.9/47.2/47.7 | 68 % in deadline (CWND on in-process drops) |
| lte | 47.1/81.6/93.6 | 44 % in deadline (CWND) |
| wifi-busy | 4.2/88.3/88.5 | 64 % in deadline (CWND, plpmtu 1200) |
| 5g-mmwave | 9.9/44.2/44.3 | 99.6 %: 12.8/44.4/55.8 |

### Reading
1. Everything that was a *deadlock* in run 1 now completes; credit sizing is fixed (transcont adapt at full rate with p999 92.5 ms,
   below rawudp's 112 ms).
2. Every remaining throughput failure is the loss-based fallback reacting to random loss (`stalls(cwnd=…)`, `ccLoss=N/N`) —
   fixed on main in `fc29252` (credit-primary; CUBIC engages only on ECN-CE or loss with queueing delay). Not in this run.
3. Tail repair works: transcont RTT-only p99 231 → 97 ms (OWD + 6 ms); low-rate starlink/5g deliver 100 % / 99.8 %.
4. `connect` fails on every impaired profile incl. 25 ms RTT — open (wave 3: in-process netem sim + fix).
5. PMTUD parks at 1200 under loss (wifi/5g), doubling packets for 1200 B messages — open (wave 3).
6. The bench's fixed deadline hides late arrivals as "lost" — open (wave 3: `late=` accounting, generous deadline).

---

## Run 3 — wave-3 tree (`4c2406d`: credit-primary CC, connect trains, graceful close, PMTUD verify, reorder window, tail-repair gating)
`0 run(s) failed`. Native datapath. `late=` is now reported; delivered % is real loss (messages that never arrived).

### 2000 msg/s (delivered %, p50 / p99 / p999 ms one-way) and connect (fresh | resumed, 500 each, fail count)
| profile | rawudp | tessera | adapt | connect fail |
|---|---|---|---|---|
| lan-clean | 100 %, 0.05/0.20/0.46 | 100 %, 0.13/0.37/0.85 | 100 %, 0.14/1.17/11.0 | 0 \| 0 |
| transcont (180 ms RTT) | 99.96 %, 90.8/92.1/92.2 | **100 %, 91.1/92.2/94.9** | **100 %, 91.1/92.2/92.8** | 0 \| 0 (was deterministic FAIL) |
| starlink (1.2 % GE) | 98.8 %, 43.8/47.0/47.2 | 100 %, 44.6/134/158 | 100 %, 44.5/149/158 | 0 \| 0 |
| lte (5.5 % GE) | 94.5 %, 74.0/93.7/97.9 | 100 %, 78.9/271/293 | 100 %, 80.6/275/460 | 0 \| 0 |
| wifi-busy (3.4 %, reorder) | 96.6 %, 65.2/88.2/88.3 | 99.94 %*, 75.7/88.5/262 | 99.94 %*, 75.5/88.4/250 | 0 \| 0 |
| 5g-mmwave (4.9 % GE) | 95.1 %, 27.5/44.1/44.2 | 100 %, 32.1/106/133 | 100 %, 31.4/108/148 | 0 \| 0 |

### 50 msg/s, real loss (tessera)
lan-clean 98.65 %* 0.29/0.64/1.0 · transcont **100 % 90.4/92.4/92.8** · starlink 98.8 %* 37.6/73.5/150 · lte 98.8 %* 54.1/160/246 ·
wifi-busy 98.7 %* 12.7/88.4/88.7 (run 2: 70 %, plpmtu stuck) · 5g 97.9 %* 12.4/75.3/115 (run 2 p999 375).

### 50 msg/s, RTT-only + 5 % in-process loss (rawudp 95.5 % → tessera; p50/p99/p999 ms)
| profile | rawudp | tessera |
|---|---|---|
| lan-clean | 0.18/0.51/0.78 | 98.6 %*: 0.30/1.86/4.74 |
| transcont | 90.3/92.3/92.7 | **100 %: 90.5/97.4/98.1** |
| starlink | 35.2/46.9/47.3 | 98.6 %*: 37.6/**48.6**/62.3 |
| lte | 46.7/78.9/98.4 | 98.4 %*: 53.9/**82.5**/91.5 (run 2: 44 % delivered) |
| wifi-busy | 3.0/87.4/88.4 | 97.7 %*: 14.3/86.1/88.7 |
| 5g-mmwave | 9.9/44.2/44.4 | 98.5 %*: 12.1/**44.3**/47.9 |

\* every sub-100 % figure is the same signature: the **last 24–27 messages of the run** (e.g. lan-clean low-rate missing
seq 1973–1999 with zero wire loss, zero stalls). Root cause: NativeUdpIo's TxBatch holds the final partial batch when
nothing triggers a flush (confirmed: transcont, whose acks keep flushing, is 100 %). Fix in progress (flush timer ≤ T,
flush on app send return, flush before linger).

### Reading
1. **Connect is solved**: 6000 connects over impaired links, 0 failures; 0-RTT payload lands at one-way delay on every profile.
2. **Reliability is solved modulo the batch-flush tail**; no deadlocks, no CWND freezes, `late=0` everywhere.
3. **Low-rate tails are at the floor**: with 5 % loss, p99 is within 2–6 ms of raw UDP's on transcont/starlink/lte/5g while
   delivering 100 % vs 95.5 %.
4. **High-rate bursty-loss recovery is the open performance item**: starlink/lte/5g p99 at 2000 msg/s is 1–2 RTT above
   the floor because the reorder window now delays reactive repair and proactive redundancy is not burst-aware.
   Wave 4: fire gap-triggered repairs immediately (repairs are harmless on reorder), burst-aware redundancy (paired
   repairs when measured burst length > repair spacing). Targets: lte p99 < OWD + 1 RTT, starlink p99 < 100 ms.

---

## Run 4 — `e0dca1e` (run 3 + never-dropped resend queue, parse-before-mark, flush hardening)
Delta from run 3 only:
- **High-rate residual loss gone**: wifi-busy 5000/5000 in both modes (run 3: 4997). All six profiles 100 % at 2000 msg/s.
- **Starlink p99 at 2000 msg/s 134 → 59 ms** (adapt 149 → 56 ms) from the unpaced resend queue alone; lte (≈300 ms) and
  5g (≈105 ms) unchanged — the wave-4 recovery work targets those.
- Connect 5999/6000: one resumed connect failed on wifi-busy (0.2 %), first failure since wave 3 — open.
- **Low-rate tail persists on Linux** (lan-clean 50 msg/s: missing exactly seq 1970–1999, zero wire loss). The Windows
  flush fix does not cover it; suspect the Linux GSO super-datagram path (64-segment / 64 KB kernel limit → EMSGSIZE not
  in the fallback list → silent drop). Assigned with a Linux cargo-test recipe.
- RTT-only sweep unchanged from run 3 (transcont 100 % p99 97.4 ms; starlink/lte/5g p99 within 2–4 ms of raw UDP).

---

## Run 4 (recover agent, in-process NetemSim on Windows, native datapath) — original section from agent/recover

Same machine for before and after: Windows 11, JDK 21, native datapath, **in-process `NetemSim` presets** (`bench adapt --netem <p>`,
seed 1, 5000 messages of 1200 B at 2000 msg/s, 500 warm-up, `--lossSim` defaults to 0 under `--netem` now). "before" is main
`e0dca1e` built from the same sources; "link one-way" is the delay the simulator imposed on its packets (queueing included) —
the floor a raw datagram sees on that run. Latencies one-way in ms; overhead = client wire bytes / payload bytes delivered.

| profile | link one-way p50 / p99 | before: p50 / p99 / p999, late, stalls | after: p50 / p99 / p999, late, stalls | overhead before → after | FEC ratio, burst mean |
|---|---|---|---|---|---|
| lte (GE 5.7 %) | 81 / 100 | 77.9 / **246.8** / 398.8, late=703, credit 26 + cwnd 32 | 82.3 / **117.4** / 136.2, late=0, credit 11 (290 ms, slow start) + cwnd 45 (385 ms) | 1.240 → 1.291 | 0.17–0.20, 3.8 |
| starlink (GE 1.8 %) | 44 / 46 | 44.5 / 57.7 / **138.2**, late=0, credit 3 | 44.6 / 52.8 / **108.4**, late=0, credit 4 (131 ms, slow start) | 1.149 → 1.147 | 0.09–0.10, 2.4 |
| 5g-mmwave (GE 5.0 %) | 37 / 176 | 41.3 / 179.8 / 204.3, late=0, credit 5 | 41.9 / 185.3 / 270.2, late=0, credit 7 (99 ms) + cwnd 17 (259 ms) | 1.214 → 1.245 | 0.16, 2.6 |

Reading. lte: p99 went from floor + 1.5 RTT to floor + 17 ms, and the 703 late messages (2 s of blocked sends: lost
additive grants, a target pinned at the min-RTT BDP by the loss-shrink rule, and the CUBIC fallback the resulting bursts
engaged) are gone. starlink: p99 at floor + 7 ms and the p999 one ARQ round shorter. 5g-mmwave on this machine is
dominated by the simulator's pareto ratchet (its own p99 is 176 ms and the scheduler released 450 packets late); tessera's
p99 is within 10 ms of the link's in both runs and the run-to-run spread of the link tail exceeds the difference.
Re-sends are now exact: lte 21 (all from the feedback map, 0 spurious) against 347 blind ones before; starlink 0 against 97.

`RecoveryTest` (fixed seeds 41/42/43, same harness, bound = link one-way p99 + 1 RTT for starlink / lte, + 0.5 RTT for 5g):
starlink p99 53.1 ms (bound 116), lte 111.9 (bound 184–190), 5g-mmwave 121.0 (bound 132); 5000/5000 delivered, late=0 on
all three; the grant-blackout test (every Grant frame — standalone and ACK-borne — dropped for 1 s at 2000 msg/s on
starlink) stalls the sender, resumes 58–167 ms after grants return and never stalls again.

Linux (WSL2, nixos-a, kernel 6.18, native datapath), the run-3/4 "tail loss" at 50 msg/s: `bench tessera --netem lan-clean
--n 2000 --gapUs 20000` now delivers 2000/2000 (`late=0`, `dropped=0 sendErrors=0`); the cause was the bench's receive
deadline (n × gap from the start, not counting the 500 warm-up messages — it expired exactly when the sends finished and
every message still in flight, plus the send loop's drift, was counted lost), not the datapath. The GSO path was hardened
anyway: send errors are counted (`ioStats`: `sendErrors`, `gsoFallback`, `dropped`, first error) instead of ending the
rx/timer threads, oversized runs are split to the kernel's limits, and a refused super-datagram goes out per datagram
(`cargo test` adds `gso_splits_runs_beyond_the_kernel_limits`, 100 × 1350 B through `send_gso`, green on Linux and Windows).

---

## Run 5 — wave-4 tree (`78154ee`: decoder fix, immediate gap repair, burst-aware FEC, cumulative credit, PTO cap, bench deadline/rawudp fixes)
**0 runs failed. Every profile 100 % delivered in every mode, `late=0` everywhere, connect 6000/6000.**

### 2000 msg/s (delivered %, p50 / p99 / p999 ms one-way) — raw UDP vs Tessera (adapt)
| profile | rawudp | Tessera | Tessera p99 − raw p99 |
|---|---|---|---|
| lan-clean | 100 %, 0.05/0.23/1.56 | 100 %, 0.14/0.65/5.7 | +0.4 ms |
| transcont (180 ms RTT) | 100 %, 90.8/92.1/92.2 | 100 %, 91.1/**92.2**/92.5 | +0.1 ms |
| starlink (1.8 % GE) | 98.2 %, 43.6/47.0/48.5 | 100 %, 44.6/**65.2**/100 | +18 ms (run 3: +87) |
| lte (4.2 % GE) | 95.8 %, 74.6/91.4/97.2 | 100 %, 78.1/**124**/348 | +33 ms (run 3: +180) |
| wifi-busy (2.7 %, reorder) | 97.3 %, 65.7/87.8/88.3 | 100 %, 77.1/**88.4**/91.1 | +0.6 ms (p999 run 4: 257) |
| 5g-mmwave (4.8 % GE) | 95.2 %, 26.2/43.7/44.2 | 100 %, 32.4/**68.2**/198 | +24 ms (run 3: +64) |

Connect: fail=0 on all six profiles, fresh and resumed; 0-RTT payload at one-way delay on every profile.

### 50 msg/s, real loss (Tessera): all 2000/2000
lan-clean 0.29/0.63/1.2 · transcont 90.4/92.4/92.7 · starlink 37.4/67.4/176 · lte 54.3/208/366 · wifi-busy 16.1/88.4/88.6 · 5g 12.4/75.2/120.

### 50 msg/s, RTT-only + 5 % in-process loss (raw UDP 95.5 % → Tessera 100 %; p99 ms)
lan-clean 0.58 → 1.98 · transcont 92.2 → 97.1 · starlink 47.1 → **48.0** · lte 80.8 → **84.1** · wifi-busy 78.5 → 88.4 · 5g 44.2 → **43.1**.

### Reading
1. The reliability/connect story is closed on these six links: zero failures, zero late, zero undelivered across 36
   runs and 6000 connects. The earlier low-rate "tail" was the bench's own deadline (fixed); the "wrong solve" was a
   decoder bookkeeping bug (fixed, now validator-guarded).
2. At full rate under bursty loss Tessera's p99 is now 18–33 ms above raw UDP's on starlink/lte/5g (was 64–180 ms), while
   raw UDP drops 2–5 % of messages. On reorder-dominated Wi-Fi and on clean/high-RTT links the whole distribution sits on
   the link floor.
3. Remaining tail: bursts that take both a source and its trailing repair at low rate fall to the PTO path (lte 50 msg/s
   p99 208 ms; p999 ≈ 350 ms at full rate). A second trailing repair spaced by the measured burst length is the cheap fix.
