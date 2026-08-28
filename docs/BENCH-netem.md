# Tessera under tc netem: first link-profile matrix (2026-08-22)

> **The starlink rows below do not include satellite handover (2026-08-23).** When these runs were made, the
> `starlink` profile was `delay 35ms 12ms loss gemodel 0.5% 30% rate 100mbit` — a fast, bursty-lossy, symmetric
> link and nothing else. Real Starlink's defining behaviour is a satellite handover roughly every 15 s that takes
> the link away for a moment, plus a strongly asymmetric uplink. Every `starlink` number on this page was measured
> **without** that, so it is a lower bound on latency and a best case for delivery, and it must not be read as a
> measurement of a LEO link.
>
> The profile has since been corrected: `NetemSim.Preset.STARLINK` and `profiles.sh starlink` now add a 200 ms
> outage every 15 s (and, in the sim, a 12 Mbit uplink cap). **The exact profile these rows were measured with is
> preserved as `STARLINK_LOSSY_ONLY` / `profiles.sh starlink-lossy-only`** — re-run against that to reproduce or
> extend anything here. Handover behaviour is covered by `OutageTest` (F9 in docs/TEST-PLAN.md); no matrix run has
> been made against the corrected profile yet, so this page carries no handover row.

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
| starlink (as measured; now `starlink-lossy-only`, no handover) | `delay 35ms 12ms loss gemodel 0.5% 30% rate 100mbit` | 35 +- 12 ms | 60.0 / 71.6 / 86.6 | GE p=0.5 % r=30 %: **1.6 %** avg, ~3-packet bursts | 100 Mbit/s |
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

---

## L1 re-baseline — pure JDK (`-Dtessera.native=off`), tree `e24c4e3`

Every table above was measured on the **native** datapath while the README recommends the pure-JDK one. This run
closes that gap: all 26 runs confirmed `io: client=ChannelUdpIo`, and `run-matrix.sh` now passes `JAVA_OPTS`
through so the datapath can be selected at all (its privilege-dropping `env` was silently stripping it).

**Read the loopback rows with suspicion.** This ran while another build was compiling Kotlin and Rust on the same
host, and the CPU-bound rows show it: baseline `adapt` p99 4448 us here against 1325 us in run 5. The impaired
profiles are link-dominated and unaffected. The low-latency rows want a re-run on a quiet machine before anyone
quotes them.

### 2000 msg/s (delivered %, p50 / p99 ms; overhead = bytes sent / payload delivered)

| profile | rawudp | Tessera (pure JDK) | native (run 5) | overhead |
|---|---|---|---|---|
| transcont (180 ms RTT) | 99.98 %, 90.9/**122.7** | 100 %, 91.1/**92.3** | 100 %, p99 92.2 | 1.125 |
| starlink † | 98.5 %, 43.6/47.2 | 100 %, 44.5/64.6 | — (different link) | 1.156 |
| lte (5.6 % GE) | 94.4 %, 70.6/89.7 | 100 %, 77.2/141.9 | 100 %, p99 149.6 | 1.276 |
| wifi-busy (3 %, reorder) | 97.0 %, 64.5/**88.5** | 100 %, 73.4/**88.6** | 100 %, p99 88.4 | 1.177 |
| 5g-mmwave (5.7 % GE) | 94.3 %, 26.5/44.2 | 100 %, 33.5/57.6 | 100 %, p99 68.2 | 1.207 |

† starlink here is the **corrected** profile (200 ms handover every 15 s); run 5's was loss-only. Not comparable.

**Conclusion: on an impaired link the datapath choice is irrelevant** — every profile lands within run-to-run
noise of the native numbers. Dropping `:native` costs nothing where a real link is in the path, which is the
configuration anyone embedding this should use.

Two rows worth reading twice. On **transcont**, raw UDP's p99 (122.7 ms) is *worse* than Tessera's (92.3 ms)
despite a p50 of 90.9 — 32 ms of tail from the loaded host, which the recovery machinery absorbs and plain UDP
does not. On **wifi-busy**, Tessera delivers everything at the same p99 as raw UDP losing 3 %.

### 50 msg/s, real loss — all 2000/2000 delivered

| profile | p50 / p99 / p99.9 ms | overhead |
|---|---|---|
| lan-clean | 0.39 / 2.38 / 9.12 | 2.162 |
| transcont | 90.5 / **93.4** / 102.0 | 2.149 |
| starlink † | 37.6 / **203.7** / 326.0 | 2.233 |
| lte | 54.1 / 182.3 / 380.4 | 2.343 |
| wifi-busy | 15.3 / **88.5** / 89.0 | 2.371 |
| 5g-mmwave | 12.6 / 76.5 / 131.3 | 2.285 |

† At 50 msg/s a 2000-message run is 40 s, so the **low-rate sweep does cross handovers** while the 2.5 s runs in
the matrix above do not. p99 203.7 ms against a 200 ms outage is close to optimal: nothing can be delivered while
the link is down, so the outage length is the floor, and those messages arrive as the link returns.

**The overhead is the story here: 2.15–2.37x.** At low rate every message gets a trailing repair, which is what
buys the flat tail (transcont p99 is 2.9 ms above its floor). On a metered or battery-powered link that doubling
is a real cost and may be the wrong trade — `tailRepairMinUs`/`tailRepairMaxUs` are the knobs.

### 50 msg/s, RTT-only + 5 % in-process loss (rawudp 95.5 % → Tessera 100 %)

| profile | rawudp p99 | Tessera p99 | cost of full delivery |
|---|---|---|---|
| lan-clean | 0.92 | 2.63 | +1.7 ms |
| transcont | 92.4 | **97.6** | +5.2 ms |
| starlink † | 47.1 | 144.4 | (handover, not comparable) |
| lte | 81.0 | **84.3** | +3.3 ms |
| wifi-busy | 81.2 | 88.4 | +7.2 ms |
| 5g-mmwave | 44.2 | **44.2** | +0.03 ms |

Recovering every dropped message costs between 0.03 ms and 7 ms of p99 across these links.

### Harness note

The main matrix runs 5000 messages at 500 us — **2.5 s**, against a 15 s handover cadence, so the corrected
starlink profile's defining behaviour is not exercised there. `OutageDrainTest` covers it deliberately (20 s), and
the low-rate sweep crosses it incidentally. Either lengthen the starlink runs or give the profile a one-shot
outage early in each run so every run sees exactly one.

## v0.8 flow-control A/B — the MaxData gate costs nothing measurable (2026-08-24)

v0.8 put a flow-control gate in `send()` (one `FlowSender.canCharge` comparison + charge per message, and a
`MaxData` piggyback on every ACK: 9 bytes). A/B on the in-process NetemSim `lte` preset — **not** the tc matrix,
which needs Linux/WSL and stays open — native datapath, 2000 msg/s, 1200 B, n=5000, link one-way floor
p50 ≈ 81 ms, 4 runs per side:

| side (commit) | p50 ms | p99 ms (min–max) | p999 ms (min–max) | delivery |
|---|---|---|---|---|
| before (4519133) | 83.8–84.2 | 116.9–134.4 | 128.4–345.5 | 4 × 5000/5000, 0 late |
| after (5388a25) | 83.1–84.5 | 115.0–122.6 | 135.4–350.6 | 4 × 5000/5000, 0 late |

p50 identical, p99 ranges fully overlap, p999 is 5-sample noise on a 5.6 %-loss link in both columns. Expected:
the bench reader drains promptly, so against the default 16 MiB window the gate never engages — this measures
the per-message bookkeeping and the 9-byte advert, and both vanish into run-to-run noise. The *engaged* path
(reader stalled, sender blocked) is correctness-tested in `transport FlowControlTest`, not benchmarked: a blocked
sender has no latency to measure.

## v0.9 credit-redesign A/B — lte inside the v0.8 band (2026-08-24)

The dead-credit growth governor (SPEC v0.9; ReceiverCredit redesign fixing the F8 collapse) touches the credit
path every profile rides. In-process lte, native datapath, 2000 msg/s, 1200 B, n=5000, 6 runs:

| | p50 ms | p99 ms | p999 ms | delivery |
|---|---|---|---|---|
| v0.8 baseline (4 runs) | 83.1–84.5 | 115.0–122.6 | 135–351 | 4 × 5000/5000 |
| v0.9 (6 runs) | 81.9–84.4 | 112.5–125.2 | 126–560 | 6 × 5000/5000 |

p50/p99 ranges overlap fully; p999 is a 5-sample statistic and noisy on both sides (the 560 is one run's
outlier against a baseline that itself spans 135–351). No radio-profile cost from the governor — as designed:
dead credit on lte is ~its loss rate, far under every threshold, so none of the new machinery engages. The F8
collapse numbers (0 → 2.01 MB/s solo, zero drops) live in TEST-PLAN F8b.

## FIRST LIVE PACKETS (2026-08-24, evening ET) — Windows client ↔ Vultr ewr, real internet

Everything above this line was one machine. This is the first time a Tessera packet crossed a network:
residential Windows client (native datapath, GSO on) to a Vultr `vc2-1c-1gb` in New Jersey (Ubuntu 24.04,
JVM datapath, tools release v0.1.1 = commit 6aa7b4c), ICMP baseline 11–20 ms (avg 16). Echo round trips,
`--rate 50 --size 1200 --count 2000`, three interleaved Tessera/UDP pairs on the identical path
(`docs/LIVE-TEST.md` recipe); box destroyed after. Total cloud cost: $0.04.

| pair | arm | delivered | p50 | p90 | p99 | p999 | min |
|---|---|---|---|---|---|---|---|
| 1 | tessera | **2000/2000** | 10.9 | 14.7 | **15.7** | **19.8** | 5.6 |
| 1 | udp | 1999/2000 (0.05 %) | 9.7 | 13.8 | 17.1 | 30.2 | 4.8 |
| 2 | tessera | 2000/2000 | 16.1 | 20.0 | 20.9 | 32.8 | 10.5 |
| 2 | udp | 2000/2000 | 9.5 | 13.5 | 14.4 | 19.2 | 4.7 |
| 3 | tessera | 2000/2000 | 14.9 | 18.8 | 19.9 | 27.6 | 9.6 |
| 3 | udp | 2000/2000 | 10.5 | 14.4 | 15.4 | 23.0 | 5.6 |

Connects: fresh-PQ 0-RTT payload echoed in 20.2–30.9 ms; resumed 15.9–21.8 ms (54–79 % of fresh).

**Read honestly:** this path was too clean to test the thesis — ~0 % loss, so there is nothing for FEC to buy
back. In the one pair where UDP did lose a packet (pair 1), Tessera delivered 2000/2000 with a *better* tail
than raw UDP (p99 15.7 vs 17.1, p999 19.8 vs 30.2) at +1.2 ms of p50 crypto/coding cost. Pairs 2–3 show a
+5 ms shift across *every* percentile including min — a floor shift, not a tail effect (path drift, box JIT/GC,
or client-side state; un-attributed), exactly the minute-to-minute drift LIVE-TEST.md warns interleaving is
for. Wire behaviour was clean throughout: 0 losses detected by the transport, fec at its 0.071 floor, MaxData
flow charged == consumed, no cc engagement, no stalls. The interesting live regimes — lossy cellular / busy
Wi-Fi last miles — are still unmeasured; this run proves the tooling, the cloud path, and the v0.9 wire
end-to-end. CSVs: `live-results/` (local, untracked).

### The +5 ms "floor shift" chased down: per-flow ECMP, not Tessera (2026-08-24, follow-up)

Pairs 2–3 above showed Tessera +5 ms across *every* percentile including min — a deterministic per-connection
floor, which smelled like a pipeline defect. Three controls localized it:

1. **Loopback control** (native client ↔ JVM echo, the live topology, 4 fresh connections): Tessera min 0.3 ms
   vs UDP 0.2 ms, stable every run — client and server pipelines exonerated. (The first attempt at this control
   tripped the known native-Windows single-family bind: a native client bound `0.0.0.0` cannot reach `[::1]`,
   and a native echo on `::` never hears 127.0.0.1 — the IPV6_V6ONLY defect just bit a real workflow, raising
   its priority. Closed 2026-08-25: the library clears the option itself, so this control arm no longer needs a
   JVM echo — see SPEC "Closed: native dual-stack on Windows".)
2. **Interface check**: traffic rides wired 2.5 GbE (metric beats Wi-Fi) — no radio variance.
3. **Flow-distribution test** (fresh box, 16 alternating short flows, 400 msgs each): min-RTT is **bimodal for
   BOTH transports** — fast route ~5.6 ms, slow route ~9.4 ms. Tessera drew slow 3/8, UDP 2/8, and in one pair
   UDP was slow while Tessera was fast *simultaneously*. All 16 flows delivered 400/400.

**Verdict: transport-agnostic per-flow path selection** (ECMP/flow-hashing between the residential ISP and
Vultr ewr, ~4 ms apart; ICMP ping to the box spans 6–14 ms for the same reason). Every probe run is a new
source port, a new hash, a coin flip. **Methodology consequence for all future live A/Bs:** a single
Tessera-vs-UDP pair on the internet compares two random route draws, not two transports — run many short flows
per arm and compare distributions, or pin comparisons to the same flow. Total cloud cost of the chase: ~$0.02.

## E5 FIRST CONTACT: 5G hotspot last mile (2026-08-24, night ET) — the thesis regime, and it bites back

Same Windows client, internet forced over a phone's 5G hotspot (wired Ethernet unplugged; route verified);
same Vultr ewr box (v0.1.1 tools). ICMP to the box: 45–75 ms, avg 56. All runs 1200 B unless noted; box
destroyed after; ~35 MB of mobile data, ~$0.02 of cloud.

**1. The uplink is the whole story: ~65–75 KB/s (~0.55 Mbit).** Raw-UDP rate ladder:
50/s → 3.7 % loss, p50 54 ms · 150/s → **62.6 % loss** · 300/s → 78.7 % loss, p50 770 ms (bloat).
No netem preset models a sub-Mbit uplink (`rateUpBps` is only used by starlink at 12 Mbit) — a real E5 gap.

**2. Tessera's low-rate overhead saturates what UDP fits.** At 50 msg/s (60 KB/s payload), UDP cruises;
Tessera's 2–3× low-rate overhead (tail repair per message + acks) offers ~150–180 KB/s into a 70 KB/s pipe:
the 8-pair distribution run collapsed — p50s of 2.7–15.6 s (carrier bufferbloat, multi-second queue), 6.75–100 %
loss, failed connects, and the queue contaminated *following* runs (one UDP run inherited a 3.1 s p50). The
documented low-rate trade ("overhead 2.15–2.37x buys the flat tail") is actively harmful on a narrow uplink.

**3. When total load fits, Tessera is clean on real 5G.** After cool-downs: 10/s → 80/80, p50 78 ms;
25/s → **300/300, p50 50.9 ms** (below the UDP baseline's 54); 35/s → 420/420, p50 57.9 ms, p999 173 ms.
PMTUD completes to 1350 over the real path (no MTU cliff: 1376 B wire crossed fine), fec stays at floor.

**4. Born-dead flows: the cellular NAT kills mappings, and nothing rebinds.** ~1/3 of Tessera connections
(and 1 of 13 UDP flows) delivered 0 % *after a successful handshake + 0-RTT echo* — the flow dies during the
warm-up phase and never recovers; a fresh connection on a fresh port works immediately. Transport-agnostic
cause (CGNAT/middlebox flow expiry or policing), but Tessera holds a dead 5-tuple for its whole run while
**already owning the cure**: path migration/rebind exists and validates — there is just no client-side
"rebind on rx-silence" trigger. Designed fix, not yet built.

**Follow-ups this opens (E5 work-package):** a sub-Mbit-uplink netem preset + making the tail-repair/ack
overhead adaptive to a starved uplink (the send direction needs to notice that its *feedback* path is the
bottleneck); client rebind-on-rx-silence for NAT mapping death; probe-side connect retry accounting (a
born-dead flow currently reads as 100 % message loss rather than a flow-level event). CSVs:
`live-results/cell/` (local, untracked).

### E5 answered in-repo (2026-08-24, follow-up): CELL_HOTSPOT preset + bloat-gated overhead shedding

The uplink-saturation finding above is now locally reproducible — `NetemSim.Preset.CELL_HOTSPOT` (0.56 Mbit up /
20 Mbit down once `uplinkPeer` is set, 25 ms ± 8 ms normal, 0.5 % loss, 64-packet queue; `cell-hotspot` in
`bench/netem/profiles.sh`, symmetric there since tc-on-lo has one direction) — and answered:
`ConnConfig.bloatShedUs` (default 250 ms) sheds the accessory repair load (tail repairs, the PTO train's extra
copy) once standing queueing delay passes `max(bloatShedUs, minRtt)`, a bufferbloat-scale gate two orders of
magnitude above the radio-jitter ratchet that sank the earlier delay-keyed damping. `CellHotspotTest` (timing)
pins it at 40 msg/s × 1100 B (~73 % of the uplink in payload): with shedding, 500/500 delivered in 13.5 s
against a 12.5 s nominal, srtt regulated at ~306 ms with 172 of 430 tail repairs shed; without, 16.2 s and 60 %
more tail traffic. The contrast is milder than the live carnage because the v0.9 dead-credit governor already
bounds the un-shed arm — the two mechanisms compose: the governor stops the collapse, shedding buys back the
remaining latency. `ConnStats.repairsShed` is the tell.

## E5 REMATCH (2026-08-25): same hotspot, same card — the collapse is gone, the physics remain

Same phone hotspot, same 8-pair 50 msg/s card that produced the original carnage, now with the v0.9 dead-credit
governor, bloat shedding, and rebind-on-silence in the client (server: v0.1.1 release — wire-compatible; both
probe arms source-pinned to the Wi-Fi adapter via `--bind`, Ethernet left up: the udp arm previously *ignored*
`--bind` and rode the wrong NIC, fixed in the tools in this pass).

| metric | original (08-24) | rematch |
|---|---|---|
| born-dead flows | ~4 of 13 (0 % delivered) | **0 of 11** |
| worst tessera p50 | 15,640 ms | 1,828 ms |
| cross-run queue poisoning | yes (udp inherited 3.1 s p50s) | none (udp pairs 48–58 ms throughout) |
| full-delivery runs at 50/s | rare | 6 of 8 (+ the instrumented 300/300) |
| 25 msg/s (fits uplink) | 300/300, p50 50.9 ms | 250/250, p50 58.9 ms |

The instrumented 50/s run shows the machinery: 300/300 at p50 403 ms, `rebinds=0` (no spurious rebinds — bloat
delays acks but does not silence them), cwnd pinned at its floor by the engaged fallback (`ccLoss=115/155`,
174 real drops feeding dead credit, fec 0.257), and **3,811 tail repairs suppressed** — via the F8 `engaged`
gate rather than the bloat gate (`shed=0`): the two shedding mechanisms overlap and the engaged one fires first
when drops are present. p50 in the hundreds of ms at 50/s is the remaining physics: 90 %+ utilization of a
~0.56 Mbit uplink with a carrier queue simply queues; the transport now bounds it instead of amplifying it.

**Open from the rematch:** 3 of 11 tessera runs died with `IllegalStateException` — one `send blocked for
5000ms (GRANT_LIMITED)` during a genuine radio bad spell (the adjacent udp run lost 24.5 % too), two
`closed` immediately after connect at 35–50/s. Leading suspect for `closed`: the v0.1.1 echo server's own
send() blocking under the bloat (its acks from us arrive seconds late) until the echo tool gives up and closes
the connection, which sends CLOSE back to us. **Answered 2026-08-25 — see "The `closed` mystery, reproduced"
below: the suspect chain was right, and both error shapes were the same defect.**

## The `closed` mystery, reproduced and fixed (2026-08-25, in-process)

`EchoCloseReproTest`: the echo tool's exact serve loop over CELL_HOTSPOT's numbers with the carrier's real
buffer depth (limit=1024 ≈ 15 s at 0.56 Mbit) at 50 msg/s × 1100 B. The queue alone could NOT reproduce it —
with the v0.9 governor on both ends (and even with shedding off on both ends) a FIFO queue keeps delivering
stale grants, so no single credit stall reaches 5 s: 300/300 clean in every queue-only arm. The missing
ingredient was the radio's *scheduler stall*: adding one 6 s outage 3 s in — survivable by design, under the
10 s idle timeout — killed **both** ends at once with `send blocked for 5000ms (GRANT_LIMITED)`. The echo
tool treats any send exception as fatal and closes, so its death arrives at the probe as CLOSE → the probe's
next send() throws `closed`. Both live error shapes (`send blocked` and `closed`) were the one defect:
`awaitSendAllowed`'s unconditional 5 s `creditWaitMs` bound made any stall in the 5–10 s band
connection-fatal, while `awaitFlowWindow` next to it already waited indefinitely against an audible peer.

Fix, in three measured steps (each intermediate died in the repro): (1) scoping the throw to unvalidated
paths still died — the client rebinds during the stall, the server migrates, and the new path cannot
revalidate through the silence, so an ordinary credit stall lands on an unvalidated path; (2) discriminating
by `pv.canSend` still died — a *momentary* amp refusal as the link comes back inherited the deadline of the
ordinary stall that preceded it. Final rule: the bounded throw fires only after `creditWaitMs` of
**continuous refusal by the amplification budget with an audible peer** (the anomaly IntegrationTest pins:
peer talks, validation keeps failing, budget deliberately withheld); every other stall waits while the peer
is heard from and throws only on rx-silence beyond `idleTimeoutMs`, mirroring the flow-window wait. After
the fix the repro delivers 300/300 through the stall (3× stable, isolated + under load), with the client's
rebind (×2, fired during the silence as designed) and the server's migration exercising together. Full
default + native + timing suites green, cache defeated. Same-session flake note: `CellHotspotTest` failed
once under full-suite load with `shed=0` — JVM load inflated the sim's minRtt to ~74 ms, thinning the
250 ms bloat-gate margin (srtt−min ≈ 236 ms); passes isolated (shed=117). Same under-load family as the
2000 msg/s test.

## W2 bulk local — first data behind the workload (2026-08-25, in-process)

New bench mode `bulk` (Bulk.kt): back-to-back `send()` with no pacing gap, so credit slow-start, cwnd and
the flow window are the only clock. 1100 B messages (single-fragment at base PLPMTU: message count ==
source count, overhead unmuddied by fragmentation), 50 MB per run, single sim per preset — which, per the
documented tc-on-lo semantics, means acks/grants share the shaped queue with the data flood on rate-capped
presets. `BulkTransferTest` (@timing) additionally runs the split topology (data bottleneck / clean ack
return, the physical full-duplex shape, CoexistenceTest's).

| run | goodput | delivered | ramp to 90% steady | overhead | note |
|---|---|---|---|---|---|
| loopback | 21.99 MB/s | 50/50 MB | ~1250 ms | 1.104 | ceiling; 20 MB run: 16.4 MB/s, ramp ~500 ms |
| lan-clean | 19.69 MB/s | 50/50 MB | ~1000 ms | 1.104 | |
| lte (30 Mbit) | 2.64 MB/s | 50/50 MB | ~1000 ms | 1.276 | 70% of the 3.75 MB/s ceiling despite shared-queue ack contention |
| 20 Mbit split (test) | 2.19 MB/s | 20/20 MB | — | — | 88% of ceiling, complete delivery; the same arm single-sim + limit=100 collapsed to 0.11 MB/s (grants queued behind data — half-duplex-like, recorded, not asserted) |
| transcont (1 Gbit, 180 ms) | 0.67 MB/s | **5.9/45.5 kmsg** | — | 1.856 | **WEDGED — reliability horizon, see below** |
| starlink | 5.19 MB/s | **10.2/45.5 kmsg** | ~500 ms | 1.650 | wedged the same way |
| wifi-busy | 1.06 MB/s | **41.0/45.5 kmsg** | ~500 ms | 1.354 | partial wedge |
| 5g-mmwave | 3.91 MB/s | **44.1/45.5 kmsg** | ~500 ms | 1.470 | partial wedge |

The ~0.5 s credit slow-start claim is confirmed measured: 90% of steady rate inside 500 ms on the 20 MB
loopback run (the 50 MB runs read ~1-1.25 s because "steady" includes the higher late-run rate).

**The reliability-horizon defect (TEST-PLAN item 3) is now measured, not theoretical.** Bulk is the first
workload whose in-flight backlog can structurally exceed BODY_RING (4096) / DELIVERED_BITS (8192): on
transcont the BDP alone is ~16 k packets. Transcont 20 MB post-mortem: client `evicted=125`, server
`skipDelivered=517` (both predicted tells), `lowestUndelivered=2587` vs `largest=8489` — ~5.9 k messages
permanently undeliverable, 3976 gaps never repaired, resend machinery throttled 130 751 times.
(**Correction to the first write-up and the a34342f commit message:** the "`charged > limit`" read as a
broken MaxData invariant was a misreading of the stats line — those numbers were the `credit(limit…sent…)`
segment, whose slight overshoot is documented by-design in SenderCredit ("uncharged-but-counted packets");
no MaxData violation was observed. The window still *wedges* — consumed stops advancing — but the
accounting held.) End state is a true deadlock — receiver cannot advance, sender cannot send — surfaced after
10 s as `send blocked with a silent peer for 10000ms (GRANT_LIMITED)` by the exit added in the `closed`
fix (the same session's earlier commit), which is that exit doing exactly its job. Every high-BDP lossy
preset (transcont, starlink, wifi-busy, 5g-mmwave) trips some degree of this under bulk; every
capacity-bounded or clean run delivers 100%. This promotes the horizon fix to the next block of work.

## Reliability horizon FIXED — the wedge is gone (2026-08-25, in-process)

Fix (SPEC "The reliability horizon"): `send()` waits on `nextFecSeq − peerLowestUndelivered < BODY_RING` —
the retained symbol a new source overwrites is only destroyed once the peer's cumulative delivered edge
(FEC feedback, on every ACK) has passed it. Eviction structurally impossible; silent loss became
backpressure; no wire change. `horizonStalls/ms` joins the stalls segment; `horizonAssumedDelivered`
tripwires the receiver's DELIVERED_BITS wrap assumption (any count = invariant break).

Bench `bulk` 20 MB re-runs, same single-sim topology as the pre-fix table (delivered / goodput):

| preset | pre-fix | post-fix |
|---|---|---|
| loopback | 100%, 16.4 MB/s | 100%, 16.8 MB/s (unregressed; hzn=0 stalls) |
| transcont | **32% then deadlock** | **100%**, 1.60 MB/s (re-sends 5853, credit stalls 9.8 s) |
| starlink | **56%** | **100%**, 0.90 MB/s |
| wifi-busy | **90%** | **100%**, 0.89 MB/s |
| 5g-mmwave | **97%** | **100%**, 3.32 MB/s |

BulkTransferTest gained the transcont-shaped split-topology arm: complete delivery asserted with
`resendEvicted == 0` and `horizonAssumedDelivered == 0` (2.24 MB/s, horizon stalled 508×/4.8 s and
recovered every time; `skipDelivered` stays recorded-not-asserted — it also counts benign residual-ARQ
duplication, which the transcont arm produces ~150 of). Goodput on lossy high-BDP paths is bound by the
gap-budget resend throttle, not the horizon (hzn stall time ≪ credit stall time) — raising it is a tuning
question for later, with the wedge gone the numbers are honest floors.

## F8 remainder: AQM/ECN wired end to end, and F8a answered (2026-08-25, in-process)

**ECN was blind end to end before this** — the rx path hardcoded `ecnCe=false` into the ack tracker and a
rising CE count in the peer's ACKs never reached `HybridCc` (only SenderCredit's 10% cut listened). Now:
`NetemSim.ecnThreshold` step-marks at a queue depth (L4S/DCTCP-style shallow marking — congestion without
delay or loss, the regime the delay gate is structurally blind to), the mark rides an in-process side channel
(`NetemSim.EcnCe`, content-hash keyed; real ECN lives in IP TOS bits JDK sockets cannot touch), the receiver
consumes it pre-decryption, shrinks its credit target, echoes it in the ACK CE count, and the sender engages
the CUBIC fallback per rise. Stats: `ce=<rx>/<acked>ack` in the stats line, `ceMarked` on the sim.

AqmEcnTest A/B (20 Mbit / 40 ms RTT / limit 100, split topology, 10 MB bulk, mark threshold 20):

| arm | time | forced tail drops | delivery |
|---|---|---|---|
| step-marking AQM | **12.1 s** | **154** | 9090/9090 |
| drop-only queue | 38.4 s | 3537 | 9090/9090 |

Every mark was consumed (ceMarked=2794 = ceSeen=2794): on a marking AQM Tessera behaves like an ECN-native
transport — 3x faster and 23x fewer drops than the same queue signalling by loss alone.

**F8a (vs LEDBAT, RFC 6817) — the prediction inverted.** TEST-PLAN predicted "Tessera takes the bandwidth
and the scavenger gets out of the way." Measured (LedbatCoexistenceTest: LTE-shaped 30 Mbit / 90 ms RTT
bottleneck, scavenger first and in steady state, Tessera bulk joins for 6 s, then leaves; standing queue
1 BDP and 0.25 BDP):

| regime | LEDBAT solo | LEDBAT contested | LEDBAT after | Tessera contested |
|---|---|---|---|---|
| 1 BDP (limit 405) | 2.30 MB/s | 1.32 (57% of solo) | 1.50+ | 0.04 MB/s |
| 0.25 BDP (limit 202) | 1.96 MB/s | 0.45 (23%) | 1.14 | 0.06 MB/s |

Tessera is the MORE timid scavenger: against a transport designed to yield, it yields harder (the v0.9
dead-credit governor reads the contested queue's death rate and floors its target). Asserted: liveness both
ways and LEDBAT's recovery; shares recorded, per the no-threshold policy stance. Consequence for the F8a
mitigation item: the configurable send-rate ceiling existed to bound Tessera's bullying — there is no
bullying to bound, so it is not built; the open policy question is the opposite one (whether Tessera should
claim MORE of a contested link, at which point a ceiling becomes relevant as the counterweight).

Harness notes recorded the honest way: the first LEDBAT lacked slow start (RFC growth is ~1 MSS/RTT — solo
never converged and every ratio was meaningless) and the first queue limits ignored that the sim's `limit`
also holds the ~135 propagation-stage packets; both fixed before the numbers above.

## The tc run — real kernel netem validates the sim (2026-08-25, WSL2/NixOS)

The long-open "tc variant" item: `sudo -E bench/netem/run-matrix.sh` (sweep 1: matrix + baselines + connect)
under WSL2 (kernel 6.18.33.2, NixOS 26.05, iproute2-7.0.0, nix-resolved JDK 21), NATIVE datapath with GSO on,
first tc run since v0.9 + the horizon + shedding + ECN landed. Every profile, 5000 msgs at 2000 msg/s:

| profile | rawudp loss / p50 | tessera loss / p50 / p999 |
|---|---|---|
| lan-clean | 0% / 0.05 ms | 0% / 0.13 ms / 1.9 ms |
| transcont | 0.12% / 90.8 ms | **0%** / 91.1 ms / 101 ms |
| starlink | 1.72% / 43.7 ms | **0%** / 44.5 ms / 216 ms |
| lte | 4.68% / 73.9 ms | **0%** / 82.8 ms / 334 ms |
| wifi-busy | 3.08% / 61.1 ms | **0%** / 77.8 ms / 89 ms |
| 5g-mmwave | 4.22% / 28.7 ms | **0%** / 33.5 ms / 86 ms |

5000/5000 on every profile against real qdiscs; overheads 1.14–1.33, fecRedundancy settling 0.08–0.29 by
profile. Cross-validation of NetemSim: the lte tessera p50 (82.8 ms) sits inside the in-process band the
docs have carried all along (82–84.4 ms), and transcont/starlink/wifi p50s track the sim's within ~1 ms +
jitter. Kernel netem and the in-process lookalike agree; the sim's numbers are trustworthy where they have
been quoted. Full logs and CSVs in bench/results/ (not committed); env in bench/results/env.txt.

**Under-load flake note (same session):** `BulkTransferTest.highBdpLossyBulkNeverOutrunsTheReliabilityHorizon`
passes isolated every time (4/4) but can fail under the full timing suite: with the JVM thrashing, the
tick-driven gap-budget refill (`GAP_REFILL_PER_TICK` = 0.05/tick) starves, repair crawls, and the sender sits
in one very long horizon/credit stall — a load-induced livelock, not a horizon break (`evicted=0`, `assumed=0`
in every failing run). Same family as the 2000 msg/s flake; more evidence for the open gap-budget-throttle
tuning item (the throttle is now the binding constraint on every lossy high-BDP bulk number in this file).

**Correction to the flake note above — it is a DEFECT, not a flake: the high-BDP credit famine
(2026-08-25, open).** Repeated isolated runs of the transcont bulk arm are bistable (~1 in 3 completes):
failing runs stall permanently at src ≈ 5.4-6.8k with the sender in one endless GRANT_LIMITED stall against
an audible peer (completed-stall time small, hzn=0, evicted=0, assumed=0 — the horizon holds; the famine is
in the credit/grant path). The onset window sits just past the first decoder rotation
(DECODER_ROTATE 4096 + DECODER_OVERLAP 1024 = 5120) — prime suspect. The old unconditional 5 s creditWaitMs
bound converted exactly this into "send blocked for 5000ms (GRANT_LIMITED)" — the error shape seen live on
the 5G radio during its bad spell — so the famine predates this session and was being misread as a radio
artifact. BulkTransferTest keeps the horizon invariants asserted and records delivery completeness until the
famine is fixed; the famine is the top open defect going forward.

## The high-BDP credit famine: root-caused and FIXED (2026-08-25, in-process)

Diagnosed with a 2 s state sampler on the transcont bulk arm. The famine's anatomy, verbatim from a stuck
run: `client mode=GRANT_LIMITED credit(limit=30438721 sent=31989156 room=-1550435)` with the limit creeping
at ~375 B/s while the server actively re-granted 11×/s. Chain: (1) the accessory machinery (repairs,
resends) charges credit without blocking on it — the documented uncharged-but-counted overshoot — and under
the pre-stall storm dug 1.5–5.4 MB holes past the limit (one run: 11.5k resends, 6.8k fate-unknown, 1.8k
arriving as duplicates); (2) repairs then healed the gaps, so `deadCreditFrac` decayed HEALTHY; (3) the
healthy release branch was `real/3` with **no floor** — against zero flow it released only the
control-packet dribble ÷ 3, the observed ~375 B/s. Healthy-but-stalled was the one state v0.9's
floor-trickle reasoning missed: the trickle lived only on the unhealthy branch ("healthy ⇒ flow exists").
The dead-credit governor's own release rule re-created the exact deadlock it was built to prevent, one
branch over. Confirmed link to the field: the old unconditional 5 s creditWaitMs bound converted this into
"send blocked for 5000ms (GRANT_LIMITED)" — the live 5G bad-spell error was the famine, not the radio.

Fix campaign, failures recorded:
1. **Floor on the healthy branch** (`max(real/3, floor)`): killed the *permanent* deadlock — the limit then
   advanced at one floor quantum per RTT-window (measured 75 KB/s) — but a 5.4 MB hole still took ~73 s;
   2/6 diag runs still tripped the 16 s stall detector.
2. **Hard credit-overshoot gate on repairs** (refuse accessory sends past one floor quantum of negative
   room): produced a TIGHTER deadlock than the famine. Room froze just past the gate (−13 983 vs −13 500),
   gated repairs (5 691) never filled the receiver's gaps, no arrivals meant no credit, and with the held
   pool drained the trickle was dead — the limit froze exactly. Lesson: arriving repairs are themselves the
   credit engine; gating them on credit cuts the loop that refills it. Reverted.
3. **Held-pool drain (landed)**: while healthy, release = `max(real/3, floor, heldGap/8)` per window. The
   pool is finite so this is not the v0.9-rejected unconditional release (a permanent rate proportional to
   flow); an over-funded burst that re-kills credit flips the dead-credit freeze, which drops release back
   to the bare floor. A −5.4 MB hole now refills in ~8 windows.

Result: famine diag 6/6 senders complete (was 2-3/6 stuck at each earlier stage); the transcont bulk arm
delivers 18181/18181 in 3/3 isolated runs at 1.25–2.22 MB/s and its complete-delivery assert is restored.
Core pins the rule (`heldGapCreditReleasesAtTheFloorEvenWhenHealthyAndStalled`, verified to fail on the
pre-fix branch). The famine entry above ("it is a DEFECT, not a flake") is answered by this section.

**Famine fix, rounds 4-5 (same day — the drain needed three keys).** Round 3's unconditional healthy drain
regressed coexistence hard: Tessera's contested share jumped 10x (0.04 -> 0.40-0.75 MB/s), LEDBAT was
crushed from 57% of solo to 9-17% at 26% drops, and even NetemTest felt it — the pool was NOT finite under
contested loss, because each drained slice funded a burst whose deaths refilled it (~150 KB/s self-
sustaining recycle). Two further guards, each measured in: (4) stall-shape alone (real < floor) could not
tell famine from a contested-blocked sender (a blocked sender creates no gaps either — LEDBAT still at
13-14%); the discriminator that worked is transport-fed **fully-caught-up** (every source delivered, nothing
reassembling — the famine's exact state, near-unreachable while contested), which restored the 1 BDP arm
but left shallow leaking through recurring caught-up trickle states; (5) **stale-deaths-only** (3 windows
with no new gap charge — fresh deaths mean contested recycling) closed shallow. Final state, all isolated:
LEDBAT arms back to the committed scavenger posture (Tessera 0.03-0.05 MB/s, LEDBAT 39-45% of solo, full
recovery), transcont bulk 18181/18181, famine diag 6/6, core pin green. The drain rule:
`caught-up && real < floor && 3 gap-quiet windows -> max(floor, heldGap/8); else v0.9 semantics`.

## Item 5: F9 p95 paced drain + the perf-regression gate (2026-08-25, in-process)

**F9 paced drain.** The recorded open item ("pacing the burst over one RTT should keep most of the tail
improvement without the p95 cost — not attempted") is now attempted and confirmed. The outage-drain budget
is granted in full but released into the gap token bucket at burst/srtt (PathState.drainReserve): the hole
still drains in ~one RTT, the 12 Mbit uplink never sees the ~450-re-send clump. OutageDrainTest paired A/B,
4 isolated runs: drained p95 54.6–64.1 ms (one-clump burst: 93.3; metered: 47.5–47.8) — the p95 cost fell
from +41 ms to +7–16 ms — while the tail win held in full (p99.9 441–473 vs metered 772–1546, max 621–657
vs 810–1883). 16,000/16,000 both arms, every run. Updated table in TEST-PLAN "F9 outcome".

**Perf-regression gate** (`bench gate`, Gate.kt; TEST-PLAN item 5's second half — every A/B this week was
hand-run). Four scenarios: lte/wifi-busy at 2000 msg/s (delivery exact, p50 +30%, p99 +80%), bulk loopback
and bulk transcont single-sim (delivery exact — the two historical wedges — goodput floor 50%). Baseline in
bench/gate-baseline.txt, machine-relative, re-recorded with `bench gate --record`; exit 1 on any failure.
First recorded baseline (this machine): lte p50 85.9 ms / p99 119.6 (inside the historical band), wifi p50
129.7 / p99 820 (wifi's pareto jitter makes its baseline the noisiest — bands are deliberately wide; the
gate is for gross regressions: any loss, 2x latency, halved throughput), bulk loopback 22.1 MB/s, bulk
transcont 18181/18181 at 0.44 MB/s. Verification run: 10/10 metrics PASS.

## The low-rate p999 tail: the recorded explanation was wrong (2026-08-25, in-process)

The long-standing note — "long loss bursts at low message rates fall back to the probe timeout (LTE at
50 msg/s, p99 ≈ 208 ms)" — names PTO backoff as the mechanism. Measured, it is not.

**PTO essentially never fires during a low-rate stream.** `probes=3` per 2000 messages, on lte AND on
5g-mmwave. The PTO is armed off `lastElicitingSendUs` — the *last* ack-eliciting send, refreshed by every
packet we transmit, i.e. every 20 ms at 50 msg/s — while the timeout itself is ~190 ms on lte. It is
structurally unreachable while the application keeps sending; it only becomes eligible once the stream
stops. Whatever the low-rate tail is, it is not the probe timeout.

**What it actually is: RLNC equation accumulation.** Repair symbols are emitted *per source*, not per unit
time — at 50 msg/s the run shows 2500 tail repairs + 622 proactive for 2516 sources, so equations arrive
about every 16 ms. Recovering a burst of `b` lost sources needs `b` independent equations covering that
window, so recovery latency ≈ `b × inter-message gap + RTT`. With the profile's measured burst statistics
(mean 3.7, p95 9 packets ≈ 3 sources at 2.9 packets/message) that predicts roughly 50–150 ms + 109 ms RTT
— which is the measured spread.

The distribution shape confirms it. The 66 messages above 100 ms decay smoothly with no clustering at any
timer value:

| bucket | count | | bucket | count |
|---|---|---|---|---|
| 100–120 ms | 14 | | 200–220 ms | 7 |
| 120–140 ms | 7 | | 220–240 ms | 4 |
| 140–160 ms | 11 | | 240–260 ms | 4 |
| 160–180 ms | 6 | | 260–280 ms | 4 |
| 180–200 ms | 7 | | 280–300 ms | 2 |

A fixed timeout produces a spike; a variable-`k` accumulation produces exactly this decay.

**Falsified alternative, recorded per the working agreement.** I suspected the feedback-resend wait
(`lossTimeout + min(fecWindow × sendGapEwma, lossTimeout)`, which at 50 msg/s degenerates to 2 × lossTimeout
≈ 378 ms because the window-span term is capped). Dropping the second term entirely measured **p999
266/394/266 ms vs a 259/302/301 ms baseline** — no improvement, and overhead rose 2.355 → 2.37. Reverted.
The reason is structural: a resend is gated by `lossTimeout` ≈ 189 ms ≈ 1.7 RTT, which is already past where
most of the tail lives, so ARQ cannot beat FEC in this regime no matter how its wait is tuned.

**Conclusion: this is the configuration's physics, not a defect.** Recovery latency at low rate on a
high-RTT lossy link is bounded below by min(FEC equation accumulation, ARQ round trip), and both are
150–300 ms here. Baseline for the record: lte 50 msg/s p50 54.5 ms, p99 178–191 ms, p999 259–302 ms;
5g-mmwave 50 msg/s p50 12.3 ms, p999 163 ms (the v0.5-era SPEC note of "~375 ms on 5g low-rate" is stale by
more than 2x). The one lever that would move it is emitting repairs on a **time** basis rather than per
source when the send rate is low — more equations per unit time, paid for in bandwidth on a link that is
by definition not busy. That is a design option with a real cost, not a bug fix, and it is not taken here.

## Soak and suspend/resume (2026-08-25, in-process) — the last of the smaller opens

**Soak** (`bench soak`, Soak.kt): one connection under sustained load, sampling the structures that would leak
if any did — heap after an explicit GC, threads, `Reassembler.pending`, the leak-credit ledger
(`reassemblyAbandonedPending`, newly exposed on ConnStats), and the key generation. 20 min on the lte profile
at 2000 msg/s: **2,401,450 messages delivered, exactly on rate throughout** (no degradation over the run),
`reasm` and `abandoned` flat at **0**, threads flat at **8**, and **143 automatic key rotations** — the first
sustained exercise of the rotation that landed the same day.

Heap needs care, and the first version of this bench got it wrong. The series ran
115 → 138 → 149 → 151 → 168 → 187 → **105** → 132 → 132 → 262 → 172 → 216 MB: a sawtooth, because `System.gc()`
is advisory and G1 collects on its own schedule. An endpoint-to-endpoint slope over that reports whatever the
last sample caught — it claimed "+4.66 MB/min", which is noise dressed as a trend. The statistic that actually
answers the question is the **post-GC floor per half**, since live data that is never released puts a rising
floor under every later sample: floor 115.3 MB in the first half, **104.9 MB in the second**. A sample late in
the run *below* the earliest sample falsifies monotonic growth outright. **No leak.** The bench now reports
the floor comparison and says so in words; the naive slope is gone.

**Suspend/resume** (`SuspendResumeTest`, @timing): the endpoint stops and comes back — distinct from F9's
outage, where the link drops packets while both ends keep running. Two arms over a delay-only 45 ms link,
modelled as a symmetric blackhole:

- Suspend **shorter** than `idleTimeoutMs`: 640/640 delivered, `send()` never throws, and the peer's credit
  target is unchanged across the gap (1,038,828 → 1,038,828). Our own absence is not congestion evidence —
  F9's rule applied to the endpoint rather than the link.
- Suspend **longer**: fails cleanly with `closed` rather than hanging or silently dropping.

Two things this cost, both worth keeping:

1. **The credit assertion was unfalsifiable twice before it was real.** First it read the *client's*
   `creditTargetBytes` — but that is the credit an endpoint grants its peer, and the client receives almost
   nothing, so it sits at the floor forever. Reading the server's fixed that. Then it still could not move on
   loopback, where the ~0.2 ms RTT puts the BDP the target tracks *below* the 10-datagram floor, so the target
   can never leave it: hence the delay-only link. A test that cannot fail is worse than no test.
2. **The connection-level idle timeout keys on `max(lastRxUs, lastTxUs)`, and `lastTxUs` is set in `transmit()`
   before the send** — so our own timers refresh it even when nothing reaches the wire. With rebind-on-silence
   enabled, the 6 s arm *survived* a 3 s idle timeout: the client rebound twice and each announcement kept the
   clock fresh. That is defensible for a mobile client (it is trying to recover, which is what rebind is for),
   but it means "idle timeout" means *no attempts*, not *no progress*. It is the same trap `awaitFlowWindow`
   and `awaitReliabilityHorizon` each work around with their own rx-silence checks against `lastRxUs` alone.
   The test disables rebind so the blackhole is faithful — a powered-down device cannot rebind out of it, and
   `selfRebind`'s fresh socket escapes an io wrapper besides.

## The gate's own noise — and a claim retracted (2026-08-25)

`bench gate` gated wifi-busy p99 at +80% of baseline. **That metric is not stable enough to gate.** Five
identical runs, unchanged code and config, measured wifi-busy p99 at **1138 / 3281 / 4402 / 5091 / 288 ms** —
a seventeen-fold spread. lte p99 over the same period held 116–126 ms (8 %), and wifi's own p50 held
124–155 ms, so the instability is specific to the deep tail of a profile that is pareto jitter + 3 % loss +
5 % reorder, where p99 is decided by where one burst happens to land.

**A claim from earlier today is therefore withdrawn.** The PTO-backoff "regression" of wifi p99 820 → 3597 ms,
and its "fix" back to 1240/960 ms, are all inside that noise band: the gate did **not** demonstrate a
regression, and this file previously said it did. What was real, and stands on its own, is the **ratchet bug
found by reading the code**: `armTlpProbe` re-raised the probe mark to the current `nextPn` on every probe, so
on a still-sending path the mark stayed permanently ahead of acks lagging an RTT behind and the backoff could
never reset — wrong by inspection, independent of any measurement, and the agent's own policy test had encoded
it. The fix is kept for that reason, not for the numbers that prompted the look.

Two changes follow:
- wifi-busy p99 is now **recorded, not gated** (`wifi-2k.p99_ms_recorded`); its p50 and delivery still gate,
  as do lte's p50/p99 and both bulk scenarios. A gate that cries wolf gets ignored, which is worse than no gate.
- `bulk-transcont.delivered` failed once as 13783/18181 — the gate's own 180 s join expiring, not a transport
  fault. Patience raised to 600 s: an exact-delivery gate must measure the transport, never its own timeout.

Re-recorded baseline and **three consecutive clean passes** afterwards. The general lesson is the one this
project already writes down and I violated anyway: before trusting a number as evidence, measure what that
number does when nothing changes.

## W5 — many connections, one server (2026-08-26, in-process)

The last untouched workload row: every previous number in this file used **one** connection, so per-connection
cost, accept throughput and fairness between connections were unmeasured assumptions. `bench conns` puts N
connections on one client socket and one server socket (both demux on connId), so what it measures is the cost
of a *connection*, not of a socket or a thread. Heap is read as a post-GC floor, the statistic the soak settled
on. **Both endpoints live in this JVM, so every figure below is per connection PAIR** — halve it for one end.

| connections | established | accept rate | heap idle | per pair idle | per pair at 20 msg/s | fairness (max/min) | delivery |
|---|---|---|---|---|---|---|---|
| 50 | 50/50 in 260 ms | 192/s | 102.8 MB | 942 KB | 2826 KB | 1.00× | 100 % |
| 100 | 100/100 in 504 ms | 198/s | 159.4 MB | 983 KB | 2458 KB | 1.00× | 100 % |
| 200 | 200/200 in 645 ms | 310/s | 266.3 MB | 1034 KB | 2345 KB | 1.01× | 100 % |
| 400 | 400/400 in 869 ms | 460/s | 465.6 MB | 1004 KB | 2330 KB | 1.01× | 98.7 % † |

**Fairness is not a problem.** max/min delivered is 1.00–1.01× at every scale: the crowd does not starve
anyone, which was the open question. Accept rate *rises* with N (192 → 460/s) because it is JIT-bound, and each
accept includes an ML-KEM-768 decapsulation. Threads stay flat — connections do not spawn any.

**Per-connection footprint is the finding: ~1 MB per pair idle, flat across 50–400**, so it is genuine marginal
cost, not amortised warm-up. It is almost entirely **eagerly-allocated fixed-size rings, sized for the 2000 msg/s
worst case and paid for by every connection whether or not it ever sends**:

| structure | per endpoint |
|---|---|
| `PathState.ringPn` / `ringTimeUs` / `ringLo` / `ringHi` — `LongArray(RING = 8192)` × 4 | 256 KB |
| `PathState.ringSize` (Int) + `ringKind` (Byte), same 8192 | 40 KB |
| connection `bodyLenRing` / `symRingFec` / `symRingSentUs` / `symRing`, `BODY_RING = 4096` | ~110 KB |
| rx/tx scratch, ackedBits, resendQ, pend/lost rings | ~20 KB |

≈ **420 KB per endpoint**, ~840 KB per pair — which is the measured number. A server holding 1000 idle
connections therefore pays roughly 420 MB for ring capacity those connections will never use. That is a
deliberate trade (fixed arrays mean no hot-path allocation and no GC pressure) taken when only one connection
had ever been measured; it is the wrong trade for a many-connection server, and the fix is to make `RING` and
`BODY_RING` sizable per `ConnConfig` rather than compile-time constants. **Not done here** — it touches the
recovery machinery's assumptions and deserves its own change with the F1/F9 suites as the gate.

† The 400-connection delivery dip is **the harness, not the transport**: `bench conns` runs one rx and one tx
thread per connection, so 400 connections means 800 threads on 16 cores. It is recorded rather than swept away,
but it is not evidence about the transport, and the regression test below uses 100.

`ManyConnectionsTest` (@timing) pins what matters: 100 connections accepted, **every** connection delivers
everything (no starvation), and the per-pair footprint stays under 3 MB — a multiple, not a drift, since the
real number is tracked by `bench conns`.

## W3 — connect storm (2026-08-26, in-process)

The per-accept ML-KEM-768 decapsulation had only ever been timed **serially**, and the v0.7 address validation
was built and measured against a *hostile* flood. The honest case — a crowd of legitimate clients arriving at
the same instant, as after a server restart or a network blip — is a different question, and `bench storm`
answers it: all clients are released from one latch, so the storm is simultaneous rather than merely fast.

The first runs measured the wrong thing, which is itself the useful part. N connects down **one shared client
socket** is one source address, and the per-source token bucket exists precisely to throttle that — so it
measures the defence, not the workload. `--multi` gives each client its own socket and therefore its own bucket,
which is what N real clients look like. Both are worth having, and the contrast is the result:

| storm | connected | retriesSent | validator dropped | underPressure | connect p50 / max |
|---|---|---|---|---|---|
| 100, own addresses | 100/100 | **0** | 0 | false | 230 / 329 ms |
| 200, own addresses | 200/200 | **0** | 0 | false | 347 / 536 ms |
| 200, one address | 200/200 | 266 | 0 | false | 220 / 403 ms |
| 500, one address | 500/500 | **1829** | 0 | **true** | 741 / 1090 ms |

**An honest distributed crowd is not taxed at all** — zero Retries at 200 simultaneous clients, every one
admitted straight to the KEM. The same offered load from a single address trips the per-source bucket and then
the global pressure valve, costing 1829 Retries and roughly 3× the connect latency — **and still refuses
nobody**: `dropped = 0` and 500/500 connected in every run. That is exactly what v0.7 designed for, now
measured from the honest direction for the first time rather than only the adversarial one.

Connect latency across a storm is KEM queueing, and it scales with the crowd (p50 230 ms at 100, 347 ms at 200)
against ~0.5 ms of CPU per decapsulation. Both figures include the harness's own cost: `--multi` runs one socket
and one rx thread per client, so 200 clients is 200 threads on 16 cores.

`ConnectStormTest` (@timing) pins the contract that matters — **Retry is allowed, refusal is not**: 64 honest
clients arriving together, all connected, all accepted, `validator.dropped == 0`. It deliberately does *not*
assert zero Retries: whether the storm trips the 200/s global ceiling depends on how the burst lands across the
window, and pinning that would be pinning noise.

## E5 second radio session — the closed fix holds, and the thesis shows up live (2026-08-26)

Same 5G hotspot, fresh ewr box, **tools v0.1.2 on both ends** (HEAD: survivable-stall close fix, reliability
horizon, credit famine fix, forward-progress PTO backoff). Laptop multi-homed, so every arm is pinned with
`--bind 10.254.94.210` (the Wi-Fi adapter) — without it the probe rides the wired NIC, the bug that invalidated
a previous rematch. Wired control taken first from the same box: tessera p50 10.5 ms, min 5.2 ms.

| arm | delivered | p50 | p90 | p99 | p999 | min |
|---|---|---|---|---|---|---|
| tessera 25/s | 300/300 | **62.0** | 73.2 | **82.8** | 85.4 | 41.0 |
| raw udp 25/s (adjacent) | 300/300 | 68.1 | 79.5 | 110.1 | 121.5 | 47.7 |
| tessera 35/s | 300/300 | 102.1 | 749.7 | 9514 | 9586 | 44.2 |
| tessera 42/s | 300/300 | 352.4 | 513.4 | 655.7 | 695.7 | 36.2 |
| tessera 50/s | **300/300** | 465.5 | 790.1 | 2290.8 | 2410.7 | 196.1 |
| raw udp 50/s (adjacent) | **121/300 — 59.7 % LOST** | 300.0 | 403.3 | 539.5 | 559.5 | 37.1 |
| tessera 200/s × 600 | 506/600 (15.7 %) | 1397 | 17190 | 18073 | 18113 | 51.7 |

**1. The `closed` fix holds on the real radio.** The 2026-08-25 rematch lost 2 of 11 runs to
`IllegalStateException: closed` at 35–50 msg/s, traced in-process to the unconditional 5 s `creditWaitMs`
bound killing a connection through a survivable radio stall. Every arm here ran to completion at exactly those
rates. Zero deaths, zero rebinds needed.

**2. The design thesis, measured live.** At 50 msg/s the uplink is saturated (1200 B × 50 ≈ 0.48 Mbit against
a ~0.56 Mbit measured uplink) and the two transports diverge completely: **raw UDP loses 59.7 % of messages;
Tessera delivers 100 %**, paying latency instead (p50 465 ms). That is the whole argument of the project —
FEC plus residual ARQ converting loss into delay on a lossy last mile — and it had never been shown on a real
radio before. At 25 msg/s, where nothing is saturated, Tessera is simply better at every percentile
(62/73/83 ms vs 68/80/110 ms) while both deliver everything.

**3. The knee is not measurable from single runs.** 35/s produced a worse p99 (9.5 s) than 42/s (656 ms) —
the radio is not stationary, and run-to-run variance between 35 and 50 msg/s exceeds the effect of the rate
itself. Anything claimed about the knee needs the distribution methodology (many short flows per arm), which
is exactly the rule the ECMP investigation established for the wired path.

**4. W2 over the wire, at 4× the uplink.** 200 msg/s offers ~1.9 Mbit into ~0.56: 506/600 delivered with an
18 s tail. The transport **throttles rather than collapses** — 28 294 repairs gated by the F8 engaged gate,
3 080 gap-budget throttles, 44 shed — and wire output settles near 0.8 Mbit, above the link but far below the
offered load. No wedge, no famine, no death: the failure mode at 4× oversubscription is delay and a deadline,
which is the correct one. The "15.7 % lost" is mostly still-in-recovery at the probe's 10 s tail deadline.

Cold-connect cost over the radio (fresh PQ 188–361 ms, resumed 65–145 ms, 19–48 % of fresh) is consistent with
the cold-start breakdown measured the same day: the fresh figure is dominated by first-touch BouncyCastle
initialisation, not by the KEM or the radio.
### Two live constraints observed while running the above (2026-08-26)

Neither was measured deliberately; both showed up because the session ran long enough to see them, and both
matter more than the numbers they interrupted.

**The hotspot disconnects itself when idle.** The tether drops without traffic, so the planned doze arm cannot
be run as designed — locking the phone and returning later measures *interface loss*, not doze. This is the
real-world face of the W4 finding from the same day: a quiet mobile application loses its connection twice
over, once to the transport's own idle timeout (there is no keepalive, so a gap past `idleTimeoutMs` ends in
`closed`) and once to the tether going away underneath it. The keepalive question stops being theoretical
here — on a radio, traffic is what holds both the connection *and* the link open.

**The address family changed across a reconnect.** The same hotspot, same carrier, same session: IPv4 CGNAT
(`10.254.94.210`) for the runs above, then IPv6-only (`2600:1002:…`, no IPv4 at all) after it dropped and came
back. Consequences worth stating plainly:

- It vindicates the native dual-stack bind merged the same day — a client that binds single-family would be
  unable to reach *anything* after a reconnect that flips the family.
- Rebind-on-silence cannot rescue this. `selfRebind` opens `AddressFamily.defaultBind()` (dual-stack
  wildcard), which is right, but the *peer* address is fixed at connect: an IPv4 server is unreachable from an
  interface that now has only IPv6, no matter how the local socket is bound. That is interface loss, not a
  dead NAT mapping, and it is a gap in what F4/rebind covers — recorded here rather than fixed, because the
  answer is probably happy-eyeballs-style re-resolution at the application layer, not a transport rebind.
- Any live test on a tethered radio must therefore re-check the local address before each arm rather than
  reusing one captured at the start of the session.

## Cold start, characterised — and the ML-KEM hypothesis falsified (2026-08-26, in-process)

The coverage table carried "128 ms cold vs 8.4 ms warm; never characterised", and `Probe.kt` carried the guess
that went with it: "the first connect in a fresh JVM pays class loading and the first ML-KEM operation — on
loopback that is ~100 ms of pure CPU". A number with a guess attached is the one combination this project does
not tolerate, so `bench coldstart` measures it. The only honest way is **one fresh JVM per sample** — a second
connect in the same process has already paid for every class, every JIT decision and every one-time library
init, which is the whole of what is under study — so the parent spawns children and aggregates their stage
lines (median across JVMs; a mean would follow whichever child lost the CPU).

Windows 11, 16 cores, JDK 21, loopback, 10 fresh JVMs per script. **Other agents were compiling on this machine
throughout**, so treat every absolute figure as an upper bound with ±15 % of run-to-run spread; the *ratios* and
the A/B contrasts below were re-run and are stable.

| stage | native=auto | native=off | warm floor | what it is |
|---|---|---|---|---|
| jvm-startup | 69 ms | 59 ms | — | JVM start to the first line of `main()`, before a tessera class exists (not counted below) |
| securerandom | 45.0 | 44.5 | 0.048 | first `SecureRandom()` + first `nextBytes`: seeding |
| **x25519 (first BC touch)** | **198.1** | **182.9** | 0.242 | see below — almost none of this is X25519 |
| mlkem-keygen | 15.9 | 17.4 | 0.395 | `Handshake.generate()` — a *server* cost; a client pinned to a key never runs it |
| mlkem-encap | 12.2 | 11.9 | 0.747 | `Handshake.initiate()`, on the client's critical path |
| mlkem-decap | 1.9 | 1.7 | 0.519 | `Handshake.respond()`, on the server's |
| zerortt-build | 12.0 | 12.3 | 0.435 | frame codec + packet crypto, first use |
| zerortt-accept | 1.4 | 1.4 | 0.770 | replay window + AddressValidator's fixed table, first use |
| nativelib | 50.5 | 1.4 | — | `dlopen` of `tessera_native` + every Panama downcall handle |
| endpoints | 171.5 | 32.2 | — | TesseraServer + TesseraClient: sockets, BufferPool, rx and timer threads |
| connect | 27.9 | 33.8 | — | the wire round trip with everything above already warm |
| connect-warm | 5.6 | 8.3 | — | a second connect in the same JVM |
| **total-cold** | **580** | **328** | — | first connect, nothing warmed, timed end to end |
| **total-warm** | **16.0** | **10.3** | — | second connect, same JVM |

The stage medians sum to 536 ms (auto) and 339 ms (off) against end-to-end totals of 580 and 328 — the
decomposition names essentially all of it.

**The ML-KEM hypothesis is wrong, and an ordering control proves it.** `stages` runs X25519 first, so X25519 is
the first BouncyCastle primitive the JVM ever touches — and is charged with whatever that costs. A control
script touches a trivial `SHA256Digest` first and runs the KEM before X25519:

| stage | in `stages` order | in the control's order |
|---|---|---|
| bc-first-touch (SHA-256) | — | **177 ms** |
| mlkem-keygen | 15.9 | 15.1 |
| mlkem-encap | 12.2 | 20.4 (now it is first to touch X25519) |
| x25519 | **198.1** | **0.6** |

X25519 costs **0.6 ms** cold once BouncyCastle is loaded. The ~180 ms is the *first BouncyCastle class load*,
whichever primitive happens to trigger it, and a SHA-256 digest pays it just as fully as a KEM does.
ML-KEM's own first-use cost is keygen 15 + encap ~8 (20.4 minus the X25519 classes it absorbed) + decap 2
≈ **25–35 ms** — real, but a twentieth of the cold connect, not its bulk.

**And ~120 ms of that 180 ms is JAR signature verification.** `bcprov-jdk18on-1.80.jar` is signed; the JVM
verifies the signature on the first class loaded from it. Re-running the control with a copy of the jar whose
`META-INF/*.SF` and `*.RSA` entries were stripped (5 fresh JVMs each, same classpath otherwise):

| bcprov jar | bc-first-touch |
|---|---|
| signed (as shipped) | 168, 174, 177, 184, 190 ms |
| signature stripped | 54, 54, 54, 57, 80 ms |

That is a **~120 ms** one-time tax paid by the first BouncyCastle class, and it is the largest single component
of a cold connect on the pure-JDK datapath. It is close enough to the original "128 ms" figure to suspect that
number was mostly this and nothing else. **This is not a recommendation to strip the signature** — the
signature is the provenance guarantee — it is an attribution. The 54 ms that remains is genuine class loading.

**Irreducible vs amortisable.**

- *Irreducible, per connect, forever*: the warm-floor column. A fresh PQ connect's own crypto is encap 0.75 ms +
  decap 0.52 ms + build/accept ~1.2 ms ≈ **2.5 ms of CPU**, plus the wire round trip. That is the PQ floor and
  no amount of warming moves it. A warm connect end to end is 10–16 ms on loopback.
- *Amortisable, once per process*: everything else. SecureRandom seeding (45 ms), the BouncyCastle first touch
  (180 ms, of which ~120 ms is signature verification), first-use class loading in the KEM, codec and accept
  paths (~30 ms), `tessera_native` + Panama handles (50 ms), endpoint construction (32–172 ms). **A host
  application that touches these at startup moves them off the first connect entirely**, and the `stages`
  script is the demonstration: with every subsystem above already exercised, the connect itself costs 28–34 ms
  rather than 328–580 ms. No new API was needed to show this and none is proposed here; a warm-up hook is a
  three-line call into `Handshake.generate()` plus an endpoint construction on a background thread at startup.
- *Amortisable, once per machine*: nothing found. The `tessera_native` extraction into the tmpdir is keyed by
  digest and so is genuinely once-per-build, but the runs above all reused an already-extracted copy, so the
  50 ms is `dlopen` plus downcall-handle construction, not the file copy.

**The native datapath is dearer cold and no cheaper warm on this workload**, which is worth recording because
the lead we were given ran the other way ("the pure-JDK path may be cheaper cold and dearer warm"). Cold:
328 ms JDK vs 580 ms native — 50 ms of library load and a further ~140 ms in endpoint construction. Warm:
10.3 ms JDK vs 16.0 ms native for a second connect. A single connect on loopback is exactly the workload the
batching datapath cannot help with (one datagram per flush, no run to coalesce), so this says nothing against
the native path at throughput — `NativeBench` already measured that — but it does mean **a process that makes
one connection and little traffic should run `-Dtessera.native=off`.**

Reproduce: `bench coldstart [--jvms 12] [--native auto|on|off]`. No regression test was added: cold start is a
one-shot wall-clock measurement whose spread under concurrent build load is wider than any interesting
regression, and a tight assertion would pin noise rather than a contract.
## W4 — idle, then burst (2026-08-26, loopback)

Every workload measured before this one sends continuously (W1 paced, W2 bulk) or exactly once (W3 connect).
Nobody had measured what the transport does to the *first* messages after a quiet gap — which is the shape of
almost every real application: a chat client, an RPC channel, a game between rounds. `bench idle` warms a fresh
connection, goes silent for N seconds, then sends a back-to-back burst, and reports the first message's one-way
latency against the paced steady state plus the state the burst actually met.

The radio half of W4 (doze, carrier NAT expiry, RRC promotion) needs a handset and is not covered here.

### The leads, and what happened to them

**Falsified: "the first burst after idle is stalled behind slow start again."** It is not, and the reason is
structural. `ReceiverCredit.target` moves on exactly two events — congestion evidence (ECN-CE or dead credit,
which shrinks it) and a drained-or-blocked sender (which grows it). Silence is neither. The arrival-rate EWMA
*does* decay to zero through the gap's silent rate windows, but it only feeds the BDP **floor** under the
target (`coerceAtLeast(max(floorBytes, bdp))`), and a falling floor cannot cut a target.

Measured with a back-to-back warm-up (`--warmGapUs 0`), which grows the target past its 13.5 KB floor — a
paced warm-up never drains 75 % of the target, so slow start never fires and the target sits at the floor,
which cannot tell "survived the gap" from "had nothing to lose":

| gap | rx credit target before → at the burst | first msg | burst p50 / p99 | delivered | credit stalls |
|---|---|---|---|---|---|
| 0 s | 400380 → 400380 B | 207 µs | 193 / 467 µs | 50/50 | 0 (0 ms) |
| 1 s | 71844 → 71844 B | 476 µs | 730 / 1063 µs | 50/50 | 0 (0 ms) |
| 5 s | 54000 → 54000 B | 179 µs | 162 / 653 µs | 50/50 | 1 (0 ms) |
| 30 s | 66192 → 66192 B | 162 µs | 501 / 1014 µs | 50/50 | 0 (0 ms) |

Byte-identical across every gap, in every run (11 arms over four runs; the sender's remaining credit likewise).
First-message latency across all arms was 158–626 µs against a paced steady-state p50 of 93–257 µs, and the
gap=0 control sits inside that same spread — **the 2–3× over steady state is the back-to-back burst shape, not
the idleness**. Delivery was 50/50 in every arm; zero re-sends, zero losses, zero rebinds, and the tail-repair
timer contributed one trailing repair per burst exactly as it does mid-stream.

**Confirmed but benign: the delivery-rate estimate is exactly stale across the gap.**
`PathEstimator.onDelivered` closes a rate window only when an ack arrives, and no acks arrive while idle, so
`deliveredBytesPerSec` is byte-identical before the gap and at the burst in all 8 measured arms (e.g.
22 250 171 → 22 250 171 B/s across 30 s). The first ack *after* the burst then closes a window as long as the
gap, dividing the burst's bytes by 30 s, and the 0.5/0.5 EWMA halves the estimate (→ 11 125 126 B/s). Not
fixed, and not a defect today: that field feeds only `expectedCompletionUs`, i.e. the multipath scheduler,
which is designed but not built. The pacer deliberately uses the transport's own windowed
`path.deliveredBytesPerSec` instead (and only while `cc.engaged`), for the ack-clumping reason documented
there. **It would need an answer before multipath ships** — a window that never closes should be discarded on
reopen rather than counted.

**Ruled out by measurement, not by argument:** `sendGapEwmaUs` inflating the feedback-resend wait (samples are
capped at 8× the current EWMA, so a 30 s gap moves a 1 ms EWMA to 2.4 ms and it recovers within a few sends);
the PTO firing across the gap (it arms off `lastElicitingSendUs` but is gated on `lastDataPn >
largestAcked` — an idle connection has nothing unacked); rebind-on-silence firing on ordinary idle (0 rebinds
in every arm, as `RebindTest.quietButAliveConnectionsNeverRebind` already pins, because the trigger measures
`solicitingSinceUs` rather than raw rx silence); PMTUD and the flow window, neither of which moved.

### The actual finding: there is no keepalive

The dominant local cost of idle-then-burst is not in the congestion control at all. The protocol has no
keepalive frame, and the idle timeout keys on `max(lastRx, lastTx)`, so a quiet application is
indistinguishable from a dead one:

```
idle  timeout probe: 15 s gap on a DEFAULT connection (idleTimeoutMs=10000, no keepalive)
      -> IllegalStateException: closed
```

The first post-idle `send()` throws — loudly, which is the right failure mode, but it does mean **any
application with quiet periods longer than `idleTimeoutMs` must either raise it or generate its own traffic**.
Nothing in the transport will do it for them. That is a design gap rather than a bug, and it is deliberately
not fixed here: a keepalive frame is a wire-format and a battery decision (on the radio profiles of E5 a
keepalive is exactly the thing that keeps a radio promoted), and W4's local half is not the place to make it.
The 10 s default is also short for the workload — a chat client idles longer than that between messages.

### What `IdleBurstTest` (@timing) pins

Two contracts. `aGrownCreditTargetSurvivesIdleAndTheBurstAfterItDoesNotStall` asserts the target is unchanged
byte-for-byte across a 5 s gap, that the burst loses nothing and spends under 200 ms stalled on credit, and
that the first message lands within 25 ms (~40× the measured 158–626 µs — loose enough for a loaded suite,
tight enough that a grant round trip per burst cannot hide under it). It guards itself against being vacuous by
first asserting the warm-up actually grew the target past twice its floor.

**Teeth, demonstrated:** patching `ReceiverCredit.tick` to reset the target to its floor after 50 consecutive
silent rate windows — an RFC 2861-style idle restart, the behaviour the falsified hypothesis assumed — fails
the test at the intended assertion: `the credit target moved across 5 s of idle ==> expected: <526388> but was:
<13500>`. The injection was reverted.

`idleBeyondTheIdleTimeoutTearsTheConnectionDownBecauseThereIsNoKeepalive` pins the no-keepalive property from
both sides with one 3 s gap: uneventful under a 30 s `idleTimeoutMs`, `IllegalStateException` under a 1 s one.

Caveat, per the project's standing one: loopback flatters. These numbers say nothing about a carrier NAT that
drops the mapping during the gap, which is the half of W4 that still needs a real radio.
## F7 — the fuzz sweep, and the amplification numbers it actually measured

Not a netem run: a fuzz run, recorded here because it is the only place amplification has ever been *measured*
rather than argued from the design. 2026-08-26, this host, JDK 21, channel datapath, loopback.

`./gradlew :core:test --tests 'tessera.core.FuzzTest' -Dtessera.fuzz.iterations=2000000` — **25.5 M cases**
across 17 parser entry points, 30 s wall clock, zero undeclared failures. Per-entry-point counts and timings are
printed by the sweep itself (`[fuzz] <name>: N cases in M ms`), so a run that quietly did nothing is visible.

`./gradlew :transport:test --tests 'tessera.transport.EndpointFuzzTest' -Dtessera.fuzz.endpoint.iterations=15000`
— 5 m 30 s, all green:

| Sweep | Cases | Sent | Server emitted | Ratio |
|---|---|---|---|---|
| Malformed / mutated initials at a live socket | 44 995 | 40.7 MB | 810 KB | **0.0199x** |
| Short packets for unknown ids (demux miss, stateless reset) | 45 000 | 4.59 MB | 1.47 MB | **0.32x** |

Against a design bound of 3x until the path is validated. The initial figure is low because garbage buys a ~31 B
Retry or nothing at all (110 admitted, 22 461 retried, 4660 dropped over the sweep); the demux-miss figure is
higher and structurally so — a reset is a fixed 40 B and the provoking packets were 5–200 B — but `onUnmatchedShort`
refuses to answer anything shorter than the reset itself, which is what keeps it under 1x. Worst *single* case was
2.21x, and that is an artifact of the 2 ms receive window attributing an earlier datagram's reply to the current
one, not an amplifying input; the aggregate is the number that means something.

The authenticated arm — 44 488 mutated frame bodies sealed under a real session key, of which 31 606 reached
`parseFrames` across 1139 rebuilt connection pairs — produced 16 254 `rxErrors`, 675 `decodeErrors` and 25
`oversizeDropped`, and no crash, no hang and no wedged endpoint. A counted rejection is the designed outcome on
that path, so those counts are the pass, not a caveat. Rebuilding the pair 1139 times is also a finding of sorts:
a fuzzed `Frame.Close` is a legitimate teardown, which is why the sweep has to re-establish rather than assert
survival of the connection.

**No product defect.** Two harness defects, both of which had made the standing F7 claim weaker than it read:
`-Dtessera.fuzz.iterations` never reached the forked test JVM (so every previous "large run" was the default run),
and the corpus was eager, dying of `OutOfMemoryError` above ~500 k. Both fixed; the numbers above are from after
the fix, which is why they can be quoted at all. `docs/TEST-PLAN.md` (F7) lists what these sweeps did **not**
reach — the native rx loop foremost.

## E4 — global mesh, six continents (2026-08-26)

First off-host mesh: one node per region in **ewr, fra, nrt, syd, sao, jnb**, all 30 directed paths probed at
50 msg/s × 1200 B, Tessera and raw UDP adjacent in time on the identical path. Orchestrated by
`bench/mesh/mesh.py` (deploy / setup / run / destroy, state in `state.json` so a crash cannot orphan a node).
Total cost ≈ 5 cents; every node destroyed afterwards.

**Result: Tessera costs essentially nothing on a clean backbone, and wins where the backbone is not clean.**

| | Tessera | raw UDP |
|---|---|---|
| loss, mean over 30 paths | **0.000 %** | 0.033 % |
| loss, worst path | **0.00 %** | 0.67 % |
| p50 vs UDP | median **+0.10 ms** | — |
| p99 vs UDP | median **+0.80 ms** | — |
| p99 better than UDP | 8 of 30 paths | — |

The one place the backbone did lose packets is the interesting one. **Johannesburg ↔ São Paulo** — the longest
south-hemisphere leg, ~335 ms RTT — dropped 0.67 % and 0.33 % of raw UDP datagrams in the two directions.
Tessera delivered **100 %** on both, at the same latency (334.9 vs 335.5 ms p50). Found in the wild, not
emulated: that is the FEC thesis on a real path, at no measurable latency cost.

Worst single deviation is `fra→jnb` at +14 ms p50 — but the reverse leg `jnb→fra` is +13.7 ms the *other* way
(Tessera 170.2, UDP 183.9). A symmetric pair of opposite-signed deltas is the signature of **per-flow ECMP**
picking different routes per source port, which is exactly what the wired-path investigation established
earlier: a single pair proves nothing, distributions do. It is not a transport cost.

### The first matrix was measuring the harness, and would have reported a 6× regression that does not exist

Recorded because it nearly shipped as a finding. The run was originally parallel — six workers, one per source
node — which put **five inbound Tessera streams and five outbound on every 1-vCPU node at once**. AEAD plus
RLNC per packet then saturates the CPU, while the raw-UDP echo it is compared against does almost no work. The
concurrent matrix read `fra→ewr` at **p50 506 ms against UDP's 81 ms**, and "Tessera p99 better on 1 of 30".

The tell was that Tessera's *min* was 180 ms on a path whose propagation floor is 80 ms. A transport cannot
inflate propagation delay; a saturated CPU can. Re-run alone, that path is **81.3 ms vs UDP 81.0 ms** — 0.3 ms
apart. `mesh.py` now runs serially by default with the reasoning in the code, because the flag matters less
than knowing why it is set.

### Tessera vs ICMP ping — how close to the floor (2026-08-26)

A separate, smaller run after the main mesh: 10 regions, 90 directed paths, 3 flows each, **134,500 messages
per arm**. Three arms per path, back to back: ICMP ping, raw UDP, Tessera — all **matched at 1200 B and 50/s**,
so the only variable is the protocol rather than the packet size or the send rate that ping defaults to.

Ping is the **floor, not a competitor**: it promises nothing — no delivery, no ordering, no encryption — so the
fair question is how close a transport that promises all three can get to the physics of the path.

| | median over the ICMP floor | messages lost |
|---|---|---|
| **Tessera** | **+0.40 ms** | **0** of 134,500 |
| raw UDP | +0.20 ms | 6 |
| ICMP ping | — (reference) | 0 |

80 % of paths land within −2.8 to +2.9 ms of the floor. So a post-quantum-encrypted, FEC-protected, reliably
delivered 1200-byte message costs about **four tenths of a millisecond** over an ICMP echo that guarantees
nothing, and loses nothing where UDP already loses a little.

**Tessera measured at or below the ICMP floor on 31 of 90 paths, and that is not a transport beating physics.**
ICMP is routinely rate-limited or handled on a router's slower control path, so ping is a reference line rather
than a true lower bound. The honest reading of a +0.40 ms median against a ±3 ms spread is that at this
resolution the three arms are indistinguishable on a clean backbone — which is the point: the cost of
reliability here is not measurable, while the benefit (0 lost vs 6, and 15 % vs 0 % on the worst mesh paths)
is.

**Two harness bugs found while running it**, both of the class that leaves cloud resources alive: the account
hit a 10-instance cap mid-deploy, and `deploy` only wrote `state.json` *after* the whole loop — so the failure
left ten running instances with no state file to destroy them. They were recovered by label and adopted;
`deploy` now saves after every instance. This is the same failure shape as Vultr silently dropping `script_id`:
a path that fails without saying so and bills until someone notices.

## Where the per-message cost goes: the codec/plumbing split (2026-08-27)

`bench profile` — Windows 11, 1200 B messages, loopback, 20 000 messages per arm at 200 µs spacing,
microbenches n = 100 000 (median of five passes after a warm-up pass). Three runs:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| AEAD seal (datapath) | 7.41 µs | 7.12 µs | 6.96 µs |
| AEAD open (datapath) | 8.56 µs | 8.52 µs | 8.68 µs |
| header protect + unprotect | 0.97 µs | 0.98 µs | 0.99 µs |
| RLNC push / repair(w=32) / onRepair | 0.04 / 2.32 / 2.80 µs | 0.04 / 2.26 / 2.78 | 0.04 / 2.30 / 2.75 |
| **codec, per message** | **17.07 µs** | **16.75 µs** | **16.77 µs** |
| loopback UDP p50 | 24.1 µs | 24.8 µs | 24.0 µs |
| loopback Tessera p50 | 70.6 µs | 71.0 µs | 68.4 µs |
| **delta** | **46.5 µs** | **46.2 µs** | **44.4 µs** |
| plumbing (residual) | ≤ 29.4 µs | ≤ 29.4 µs | ≤ 27.6 µs |

**Roughly 37 % codec, 63 % plumbing** — and the codec half is almost entirely ChaCha20-Poly1305. RLNC costs
**0.14 µs per message** at the estimator's 0.02 redundancy floor: a repair is 2.3 µs to build and 2.8 µs to
absorb, but it is amortised over 50 sources, and the native GF(256) kernel does the work. The AEAD is 16.6 µs,
sealing at ~165 MB/s.

This was the measurement that had to precede any AF_XDP work, and it says **XDP is the second-best target.**
The plumbing residual bounds what a kernel-bypass datapath could remove at ≤ 29 µs, and only part of that is
syscalls and copies — the rest is ack/credit/estimator bookkeeping and JVM scheduling, which XDP does not
touch. A native ChaCha20-Poly1305 next to the existing Rust GF(256) kernel addresses a **measured** 16.6 µs
with a known technique, in a codebase that already has the FFM binding and the build for it.

**Two measurement defects found and fixed in the bench itself**, both of the kind that produce a confident
wrong number:

1. **The microbenches ran before the loopback stage**, and opening a connection is what installs the native
   GF(256) kernel. So the first run measured RLNC on the *scalar* kernel (repair 31.2 µs, onRepair 34.8 µs)
   and subtracted it from a loopback arm that had used the native one — comparing two different codecs. With
   the stages reordered the same numbers are 2.3 and 2.8 µs: **the scalar kernel overstated RLNC by 12×**, and
   it printed `kernel=Scalar` in the header, which is how it was caught.
2. **The AEAD stage measured the wrong path.** Core's `Aead` allocates a fresh `ChaCha20Poly1305`,
   `KeyParameter` and output array per call; the transport's internal `PacketCrypto` reuses all three. The
   fix measures both. The expected result was that allocation dominated — it does not: **the cold path is only
   1.1× the datapath cost (1.2 µs per message)**, so the 16.6 µs is real ChaCha20 throughput, not setup. The
   hypothesis was wrong and the measurement is what says so.

An earlier fourth run measured a 32.9 µs delta against the 44–47 µs of these three; the codec figure was stable
across all four (16.6–17.1 µs). The delta is the noisy term, so **the split is ~17 µs codec against 15–30 µs
plumbing**, not a single ratio.

Caveats, stated because the number invites over-reading: loopback has no propagation to hide cost behind, so
this over-attributes rather than under-attributes; "plumbing" is a *residual*, not a measurement; and this is
one Windows host, where a Linux AF_XDP prize would be measured on Linux. It sizes the two options against each
other, which is what the decision needed — it does not predict either one's payoff.


## Native ChaCha20-Poly1305: the AEAD half of the codec cost (2026-08-27)

`bench profile` named the AEAD as the whole of Tessera's codec cost (16.6 us per 1200 B message against 0.14 us
for RLNC), so the AEAD is where the measured work went. The native crate now carries a ChaCha20-Poly1305
written from RFC 8439 — ChaCha20 §2.3, Poly1305 §2.5 over five 26-bit limbs, the §2.8 AEAD — exported as
`tessera_aead_seal` / `tessera_aead_open` and bound through Panama as `AeadNative`.

| 1200 B packet | BouncyCastle (JVM) | native (Rust, scalar) | |
|---|---|---|---|
| seal | 7.1 us (165 MB/s) | **2.64 us (454 MB/s)** | 2.7x |
| open | 8.6 us | **2.70 us** | 3.2x |
| per message | 15.7 us | **5.3 us** | −10.4 us |

Against the 44–47 us loopback delta that `bench profile` measured, ~10 us is a fifth of the whole per-message
cost of being Tessera rather than a datagram — and this is scalar Rust, with no SIMD ChaCha20 yet.

**The number is not the point; the verification is.** A hand-written AEAD fails silently — wrong ciphertext is
still ciphertext, and a round-trip test passes against its own mistake. Three independent layers:

1. **RFC 8439's own vectors**, in the crate's unit tests: the §2.3.2 block function, the §2.4.2 keystream,
   Poly1305 §2.5.2 and §A.3 #2/#4, and the §2.8.2 AEAD. All passed on the first run.
2. **Property sweeps** beside them: keystream involution at every length across the 64-byte boundary, chunked
   `update` against one-shot at every split point, single-bit tag sensitivity, every single-bit tamper of
   ciphertext/AAD/tag/nonce refused, and a refused open leaving the buffer byte-identical.
3. **A differential fuzz against BouncyCastle** (`AeadNativeTest`): 4000 random cases over random keys, nonces,
   AAD sizes and lengths biased to the block and MAC boundaries. The whole sealed blob is compared, not a
   boolean — agreeing on ciphertext but not the tag is a distinct bug class. Each case is checked three ways:
   bytes equal to BouncyCastle, native opens BouncyCastle's output, BouncyCastle opens the native output.

BouncyCastle stays the reference and the fallback. Agreement with it is the standing condition for preferring
the native path, recorded in `NOTICE` next to the provenance entry.

**Not yet wired into the transport datapath.** `transport.PacketCrypto` still calls BouncyCastle on heap arrays;
routing it through `AeadNative` needs per-thread off-heap scratch and is a change to the security-critical hot
path, so it is a separate step with its own measurement. The 10.4 us is therefore **available, not yet banked** —
no end-to-end claim is made here, and `bench profile` has not moved.


## Correction: the JDK's own AEAD gets the win, and the native one was premature (2026-08-27)

Measured immediately after the native ChaCha20-Poly1305 landed, prompted by the fair question of why a Kotlin
transport was growing a hand-written cipher in Rust. Same host, same 1200 B packet, ciphers reused, nonce
varying per iteration:

| 1200 B message | seal | open | per message |
|---|---|---|---|
| BouncyCastle (what the transport uses today) | 7.00 us (171 MB/s) | 9.67 us | **16.67 us** |
| **JDK SunJCE** (HotSpot-intrinsified) | 3.03 us (396 MB/s) | 2.97 us | **6.00 us** |
| native Rust, RFC 8439 (this repo) | 2.64 us (454 MB/s) | 2.70 us | **5.34 us** |

**Switching JCE provider recovers 10.7 of the 11.3 us available — in a few lines of Kotlin, with no native
code, no FFI crossing, no hand-written crypto and no new audit surface.** The Rust implementation beats SunJCE
by 0.66 us, and two Panama crossings plus the off-heap scratch copies a heap-array caller needs would consume
most of that margin. Against the 44–47 us loopback delta the two options are indistinguishable.

**The reasoning error is the useful part.** `bench profile` established "the AEAD costs 16.6 us", and the next
step taken was *write a faster AEAD* rather than *ask why this AEAD is slow*. The answer was never "Java is
slow at ChaCha20" — the JDK intrinsifies it — but "BouncyCastle's pure-Java implementation is slow", which is a
different problem with a one-line fix. The profiler was right about where the cost was and said nothing about
what to do; that second step was an assumption wearing a measurement's clothes. **Before optimising a
dependency, measure the platform's own implementation of the same primitive.**

Two constraints found while checking whether SunJCE can actually take the datapath:
- **Nonce uniqueness holds.** SunJCE refuses to re-init for encryption under a key+nonce pair it has already
  used (a deliberate nonce-reuse guard, and a good one). The transport is safe: a "verbatim" re-send puts the
  retained *plaintext* symbol on the wire under a fresh packet number, so every seal has a distinct
  `iv xor pn`.
- **The 8-byte tag cannot use SunJCE.** The JCE `Cipher` API verifies a full 16-byte tag and offers no
  truncated mode, and SunJCE exposes no standalone Poly1305 `Mac`; `PacketCrypto.openTruncated` needs
  primitive-level access to recompute the full tag and compare its prefix. tagLen 16 (the default) can move;
  the negotiated tagLen 8 stays on BouncyCastle.

Status: the native AEAD is committed, verified against RFC 8439 and differentially against BouncyCastle, and
**not wired into anything**. On this measurement it should probably stay that way or be reverted — carrying a
hand-written cipher for a 0.66 us margin is a bad trade. The SunJCE switch is the change worth making, and it
has not been made yet: no end-to-end number here has moved.


## The datapath AEAD moved to the JDK provider, and that changes the roadmap (2026-08-27)

`transport.PacketCrypto` now seals and opens through `javax.crypto` ChaCha20-Poly1305 (SunJCE, HotSpot
intrinsified) instead of BouncyCastle. Kotlin, no native code, no FFI. BouncyCastle stays for the negotiated
8-byte tag — the JCE `Cipher` API has no truncated-tag mode and SunJCE exposes no standalone Poly1305 `Mac`, so
`openTruncated` still needs primitive access — and as the fallback when the provider is absent.

The hand-written Rust ChaCha20-Poly1305 committed earlier the same day was **reverted**: it beat the JDK by
0.66 us, which two Panama crossings and off-heap scratch copies would largely consume, and a hand-written
cipher is a permanent audit and maintenance cost for that margin.

**Wire format unchanged, and pinned.** Both implementations are RFC 8439, so an upgraded peer must interoperate
with one that never moved. `PacketCryptoWrapperTest` already asserted the transport's sealed bytes equal core's
BouncyCastle output; `JceAeadEquivalenceTest` widens that to every length across the block and MAC boundaries
for both tag lengths, round-trips through the truncated-tag path where the two implementations meet inside one
packet, refuses a flipped ciphertext bit on both paths, and asserts the provider is actually present — a
silently missing provider would leave the transport correct but as slow as before, with every other test still
green.

**End-to-end, on the same host as the earlier profile runs:**

| | before (BouncyCastle) | after (SunJCE) |
|---|---|---|
| AEAD seal + open | 15.1–16.7 us | **7.1 us** |
| codec per message (AEAD + header protection + RLNC) | 16.8 us | **7.9 us** |
| loopback one-way delta over plain UDP | 44–47 us | **36.8–39.8 us** |
| attribution | 37 % codec / 63 % plumbing | **21 % codec / 79 % plumbing** |

**This changes the standing recommendation.** The profiler's original verdict — "AF_XDP is the second-best
target, fix the measured AEAD first" — has been acted on, and the ranking now inverts: with the codec at 7.9 us
and the residual at ~29 us, **plumbing is what is left**. That residual is still a subtraction, not a
measurement, and it holds ack/credit/estimator bookkeeping and JVM scheduling alongside the syscalls and copies
a kernel-bypass datapath could actually remove. So the next step is not to write XDP either: it is to profile
the residual directly (perf, or JFR on the send and rx threads) and find out how much of those 29 us is
syscall-and-copy at all. The same mistake is available twice — the first time the answer was "BouncyCastle is
slow, not Java", and there is no reason to assume the residual is what it looks like from the outside.

`bench profile` was itself corrected in the same change: its crypto stage measured BouncyCastle, which after
the move is no longer what the transport calls. It now charges the JDK figures and reports BouncyCastle beside
them as the superseded path. Left alone it would have kept reporting a 16.8 us codec for a datapath that pays
7.9 — an instrument describing a system that no longer exists.


## The repair clock: the low-rate tail, and why it ships off (2026-08-27, in-process)

The recorded analysis of the low-rate p999 tail ended with a named lever: "the one lever that would move it is
emitting repairs on a **time** basis rather than per source when the send rate is low." This builds it.
`ConnConfig.repairClockEquationsPerRtt` puts a time floor under the equation cadence — one repair per
`srtt / perRtt` — while the source cadence is slower than that target, the estimator reports loss, the stream is
still running, and CC/amplification/bloat all allow. **Default 0: off.** Every number below is lte or 5g-mmwave
at 50 msg/s, 2000 messages, 1200 B.

The dial on **lte** is clean and monotonic:

| equations/RTT | p50 | p99 | p999 | wire overhead |
|---|---|---|---|---|
| 0 (off) | 54.4 ms | 191 ms | 318 ms | 2.36 |
| 6 | 54.7 | 174 | 262 | 2.49 |
| 8 | 57.5 | 129 | 268 | 3.15 |
| 12 | 57.6 | 122 | 186 | 3.43 |
| 16 | 58.4 | **98** | **145** | 4.05 |

At 16 the tail this project has carried since v0.5 — 150–300 ms — becomes 98/145 ms, for +72 % wire bytes and
+4 ms on p50. The model in the earlier entry predicted exactly this: recovery is `b x inter-equation gap + RTT`,
and the clock is the only thing that shortens the gap without waiting for the application.

**It ships off because it does not pay everywhere, and two wrong designs are why.**

1. **Uncapped, it made a short-RTT link worse.** The first version used `srtt / perRtt` alone. On 5g-mmwave
   (srtt short against a 20 ms send gap) that period fell to ~2 ms: the clock fired **14,128 times**, wire
   overhead hit **7.1x**, and *every percentile got worse* — p50 12.4 -> 18.0 ms, p99 103 -> 144, p999 159 ->
   223. The repairs queued in front of the traffic they were meant to protect. Fixed with a second floor at
   `sendGapEwma / 2`: past a couple of equations per source interval there is nothing further to recover.
2. **Made responsive, it stopped working at all.** The obvious repair for (1) was to fire only while the peer's
   FEC feedback reports an outstanding hole — self-limiting, no blanket cost. Measured, the clock went nearly
   inert (146 firings) and lte gained nothing (p99 181 vs 182 off). **FEC only works proactively**: by the time
   feedback names a hole you have become ARQ, and the earlier entry already measured that ARQ cannot win in
   this regime (a re-send is gated by `lossTimeout` ≈ 189 ms ≈ 1.7 RTT, past where the tail lives). Reverted.

With the per-source cap in place and the clock at 12/RTT:

| | p99 | p999 | overhead |
|---|---|---|---|
| lte off -> on | 184 -> **114 ms** | 279 -> **205 ms** | 2.36 -> 3.38 |
| 5g-mmwave off -> on | 102 -> 96 ms | 159 -> 164 ms | 2.29 -> 3.32 |

5g-mmwave is no longer harmed, and gains **nothing** for the same +45 % bandwidth. The split makes sense: lte's
tail is equation accumulation, which more equations fix; 5g-mmwave's is the link's own deep fades, where the
extra equations are lost along with everything else. A default-on clock would bill every 5g deployment for a
benefit it cannot receive, so the operator turns it on for a link whose shape matches the first row — high RTT,
driven slowly, losses that are recoverable rather than blackouts.

**What would make it automatic is a discriminator between those two tails**, and there is no evidence for one
yet; the estimator's burst statistics are the obvious candidate and guessing at a threshold from two profiles
would be exactly the kind of unmeasured tuning this file exists to prevent. Recorded as the open question.

`RepairClockTest` pins the engagement rules rather than the speed (which is link-dependent and lives here):
off by default, never on a fast stream, never on a clean link, and inside the per-source ceiling — that last
one being the guard whose absence produced the 7.1x run.


## Sizeable rings: a 62 % smaller connection, and a throughput result nobody expected (2026-08-27, in-process)

W5 measured **1013.8 KB per connection idle** and left it as an open item. The cause is not retained data: it
is fixed arrays, allocated at construction whether or not a connection ever fills them. `RING` = 8192 gives six
per-path ring arrays at ~297 KB, and `BODY_RING` = 4096 another ~112 KB of side tables — times the two
connection objects a loopback pair holds. Both are now `ConnConfig.packetRing` / `ConnConfig.bodyRing`
(powers of two, validated: `packetRing >= 2 x bodyRing`, `bodyRing >= fecWindow`, `bodyRing <= DELIVERED_BITS`).

| packetRing / bodyRing | idle footprint |
|---|---|
| 8192 / 4096 (old default) | 1013.5 KB |
| **2048 / 1024 (new default)** | **389.1 KB** |
| 512 / 256 | 225.3 KB |
| 256 / 128 | 256.0 KB (no further gain — other state dominates) |

**The unexpected part: the smaller ring is FASTER on a high-BDP link, by a lot.** `bench bulk --mb 20`, three
runs each, ranges that do not overlap:

| profile | 8192 / 4096 | 2048 / 1024 |
|---|---|---|
| transcont | 0.60 / 0.76 / 1.44 MB/s | **2.29 / 4.98 / 5.22 MB/s** |
| starlink | 0.79 / 0.92 MB/s | **2.85 / 3.28 MB/s** |
| lan-clean | 17.30 / 16.61 MB/s | 17.87 / 16.68 MB/s (equal) |

The stall counters say why. At 8192/4096 the sender stalls on the reliability horizon *rarely but for ages*
(956 stalls / 6943 ms); at 2048/1024 it stalls *often but briefly* (3555 / 2665 ms). A deeper horizon lets the
sender get far ahead on a lossy high-BDP path and then pay for it in one enormous recovery, while a shallower
one keeps the pipe at a depth the repair machinery can actually service. **The horizon's depth is not the
throughput limiter; past some point more depth costs throughput** — which is worth a look on its own, since it
suggests something in the deep-outstanding regime (recovery load, or the credit machinery) scales badly.

**A recorded prior objection had to be re-tested, and it no longer holds.** `BODY_RING`'s KDoc carried
"1024 = 512 ms lost 2 of 2000 messages on wifi-busy" — a measured failure at exactly the value the throughput
data wanted. Re-run at 2000 msg/s on wifi-busy: 3/3 clean at n=2000 and 2/2 clean at n=10000, `evicted=0`
throughout, at both ring sizes. That failure predates the **reliability horizon**, which now blocks the sender
rather than letting it evict the retained symbol of an undelivered source — the fix engineered the failure mode
away and left the note stale. It has been corrected rather than deleted.

Cost of the smaller default, stated plainly: at 2000 msg/s on lte, 2048/1024 shows no horizon stalls at all
(the same as the old default), but 512/256 does — 76 stalls totalling 1343 ms — so the floor is not free and
the knob exists for that reason. Delivery was 100 % and `evicted=0` at every size tested, on every profile: the
horizon degrades to stalling, never to loss.

These parameters are **local**, not negotiated — two peers may size their rings differently with no wire
implication — so the default change carries no compatibility risk.


## Pacing the disengaged path, and a bench that cannot resolve what it was asked (2026-08-27, in-process)

Chasing "can this go faster" from the ring result. The transcont numbers said the reliability horizon was
acting as an accidental window limiter, so the question was what happens when it is lifted. The answer turned
out to be a defect, and then a lesson about the instrument.

**The defect: the pacer is engaged-only.** `paceAllowed` opens with `if (!path.cc.engaged) return true`, so a
path congestion control has *not* engaged on is entirely unpaced. On a clean high-BDP link the receiver grants a
BDP-sized credit limit, the sender emits it as fast as the CPU allows, and a bottleneck with a shallow queue
drops the overflow. Measured on transcont at `bodyRing` 4096: netem dropped **15.1 %** (6025 of them queue
overflow, `maxQueued` pinned at the 1000 limit), CUBIC then engaged on 1095 of those losses and collapsed
`cwnd` to **35 KB** — 0.19 MB/s at a 180 ms RTT, which is the 0.66 MB/s goodput observed. At `bodyRing` 1024
the horizon happens to cap the burst just under the queue (`maxQueued` 955 of 1000), loss stays at 0.13 %,
`cwnd` stays at 21 MB, and goodput is 5.2 MB/s. **The old default was not slow because the pipe was too shallow;
it was fast because the pipe was accidentally shallow enough.**

`ConnConfig.paceDisengaged` (default 0.0 = off, the historical behaviour) paces a disengaged path at that
multiple of the observed delivery rate. It removes the self-inflicted loss deterministically:

| transcont, 3 runs | netem drops |
|---|---|
| unpaced | 0.46 % / **4.98 %** / 0.16 % |
| paced x2 | 0.16 % / 0.16 % / 0.16 % |
| paced x8 | 0.16 % / 0.16 % / 0.16 % |

Unpaced *sometimes* bursts into the queue and sometimes does not; paced never does. That also explains the
throughput scatter — a run that overflows is slow, a run that misses is fast.

**No throughput improvement is claimed, and one was nearly claimed wrongly.** A first single-run sweep read
x8 as beating x0 on all three profiles (5g 3.40 vs 3.24, lte 1.56 vs 0.98, transcont 3.30 vs 1.93) and the
obvious write-up was "pacing is a win everywhere". Three runs per arm reversed it: transcont x0 measured
4.21/4.57/4.58 against x8's 3.31/3.31/3.28, and the same x0 config that had just read 1.93 read 4.2-4.6. **A
2.4x spread on identical code**, which is larger than every effect being compared. Goodput on lte, wifi-busy and
5g-mmwave overlapped between arms; only lan-clean showed a consistent (small) x8 win.

So the honest state is: the loss defect is real and fixed behind a flag; the throughput question is
**unanswered**, because `bench bulk` cannot resolve differences smaller than its own variance. Making it able
to — repetition and a reported interval rather than one number, the way `bench gate` was forced to after the
wifi p99 retraction — is the prerequisite for any further throughput work, and is the actual next task. Chasing
the number with this instrument would produce confident nonsense.

**Headroom, for scale:** transcont is a 1 Gbit link and the best honest reading is ~4.6 MB/s — under 4 % of it.
Whatever the ceiling is, it is not loss (0.16 %), not cwnd (21 MB), not credit and not flow control, all of
which the stats show non-binding. That is worth knowing and is not yet explained.

### A harness defect that faked a security alarm

The full suite failed once during this work with **6.24x amplification on a malformed initial** —
`EndpointFuzzTest`, 42 B sent, 262 B back, against a 3x design bound. It did not reproduce in isolation (3/3
green, worst single case 2.07x, aggregate ratio 0.0173).

The cause is attribution, not amplification. The sweep sends one datagram and drains for 2 ms of silence,
crediting whatever arrives to that datagram. Under full-suite load the server's reply to an earlier case misses
its own 2 ms window and is billed to a later, smaller one — inflating that case's ratio without any packet
being wrongly sent. The aggregate assertion (`back <= sent`) is immune to this and passed throughout.

Fixed by making the check self-verifying rather than looser: a per-case violation is now re-measured alone on a
quiescent socket with a 50 ms window, and only a reproduction fails the test. The strict 3x bound is kept, the
load artifact cannot fabricate a failure, and the cost is paid once per suspicion instead of on all 1800 cases.
Third harness defect of this class on the project (after the mesh's concurrent-matrix "6x regression" and the
cleanTest lock), and the same shape every time: a measurement that attributes a result to the wrong cause.


## Fixing the instrument, and what it then found (2026-08-27, in-process)

`bench bulk` reported one number for a quantity that varies 2.4x run to run, which made every A/B run through it
unsound. It now takes `--runs` (default 5), reports **median, range and spread**, states in words what that
spread makes resolvable, and pairs runs across arms: run `i` builds its link from `--seed + i`, so two arms with
the same seed see the same sequence of links and only the host varies.

The first thing it showed was the mechanism behind its own variance — goodput tracks the link's drop rate
exactly. On transcont, run 5 saw 0.11 % drops and 4.19 MB/s; run 3 saw 6.49 % and 2.73.

### The pacing question, answered — and it depends on the rings

Paired, five runs per arm, transcont:

| paceDisengaged | median | range | spread | drops |
|---|---|---|---|---|
| 0 | 1.98 MB/s | 1.54-4.56 | **2.96x** | 0.16-7.36 % |
| 2 | 1.43 | 1.40-1.44 | 1.03x | ~0.1 % |
| 8 | **3.41** | 3.30-3.42 | **1.04x** | ~0.1 % |

and across profiles at x8 vs off: 5g-mmwave median 2.80 -> 3.39 with spread 4.04x -> 1.12x, lte 1.55 -> 1.74,
starlink and lan-clean neutral, wifi-busy -10 % inside its own spread. **An earlier single-run sweep had recorded
5g-mmwave as a pacing regression; it is the opposite.** That entry is wrong and this supersedes it.

On that evidence the pacer was switched on by default — and then switched back off, because the benefit is
conditional on the ring sizes. At the shipped 8192/4096 the same paired comparison reverses (transcont median
0.77 unpaced vs 0.44 paced). What pacing does unconditionally is remove the self-inflicted queue-overflow loss
and collapse the spread; the throughput win needs the small rings too.

### Correction: the ring default is reverted, because it cost coexistence

The 2048/1024 default from earlier the same day is **reverted to 8192/4096**. `LedbatCoexistenceTest` failed,
and the pass/fail was useless (flaky at every setting), so the continuous numbers were taken instead — six runs
per arm, pacing held constant:

| rings | LEDBAT share while contested | recovery after Tessera leaves |
|---|---|---|
| 8192/4096 | 0.52-1.46, median ~0.65 | 0.52-0.96, median **0.78** of solo |
| 2048/1024 | 0.20-0.77, median ~0.34 | 0.40-0.64, median **0.46** of solo |

Small rings make Tessera take roughly twice the bandwidth from a LEDBAT scavenger and leave it suppressed
afterwards. The recorded fairness policy is scavenger-by-default, so that is not a default — it is an opt-in with
a stated price. **The regression was merged on a single green `timingTest` run**; one pass of a suite that flakes
is not evidence, and the ring commit should have had the paired numbers the same way this correction does.

### The close defect: found, mechanism named, fixed

The full suite reproduced `NetemTest.sendThenClose...` during this work, and the forensics added earlier in the
day did their job on the first sighting: **9 of 600 messages lost, `lateArrivals=NONE (dropped for good)`,
`CLOSE-PEER-UNDELIVERED=62` on the sender and `PEERCLOSE-HOLE=62` on the receiver.** Both counters name the same
62 fec seqs.

The mechanism: `lingerNeeded()` was entirely packet-level, and packet-level state can be clean while the
application-level guarantee is not. A source lost on the wire is recovered from repairs, so its *packets* are
acked while its fec seq is still a hole in the peer's decoder. The peer reports that hole on every ACK and close
ignored it — `finishClose()` announced the CLOSE, the peer freed state on receipt, and the hole became
permanent.

The 2026-08-26 investigation ruled this predicate out, reasoning that "a genuinely unacked final source holds the
linger open". True, and insufficient: **acked is not delivered**, and the loss here is of packets that arrived.
The fix is one clause — linger while `peerLowestUndelivered <= peerLargestFec` — bounded by `closeLingerMs` like
every other linger reason, with the timer still driving repairs and feedback re-sends while it waits.

Signature note for the record: the historical sightings were 1 message, native datapath, wifi-busy; this one was
9 messages, **channel** datapath, 5g-mmwave. Same mechanism, so the old signature was a coincidence of which
runs happened to be seen, not a property of the defect.


## Cross-version interop for the AEAD switch (2026-08-27, two processes)

Every test of the SunJCE move so far had both ends inside one JVM, which means both ends used the same provider
— so "the bytes are identical" was proven by unit test but never exercised by two peers that disagree. A real
deployment upgrades one side first.

Built the pre-switch commit (7f78915, BouncyCastle datapath) in a worktree and ran `tessera echo` / `tessera
probe` between the two builds as separate processes over a real socket, 1000 x 1200 B at 200 msg/s each way:

| | delivered | authFail | decodeErrors | fresh-PQ connect | resumed |
|---|---|---|---|---|---|
| new (SunJCE) probe -> old (BC) echo | 1000/1000 | 0 | 0 | 127.0 ms | 10.3 ms |
| old (BC) probe -> new (SunJCE) echo | 1000/1000 | 0 | 0 | 119.2 ms | 7.7 ms |

Both directions, including 0-RTT resumption, which exercises the handshake AEAD as well as the datapath one.
The wire format is unchanged in practice and not merely in theory.

**This is still not a live test.** It is two processes on one host over loopback: no real RTT, no MTU discovery
against a real path, no NAT, no middlebox, no radio. Everything measured since the compact — the close fix, the
ring and pacing knobs, the repair clock — has come from `NetemSim` on a single Windows machine, and the
simulator's fixed 1000-packet bottleneck queue is itself the parameter that drove the whole deep-pipe story.
The close fix in particular was found in simulation and fixed against simulation; its predicate depends on FEC
feedback arriving, which a real path delays.


## Live: 6 nodes, and the close fix over a real 348 ms path (2026-08-27)

Six Vultr nodes (ewr fra jnb nrt sao syd) — the only six with a complete recorded pairwise matrix, so every
path had a baseline to be read against. Everything below ran on the working-tree build (SunJCE AEAD, the close
fix, reverted ring defaults, pacing off), pushed with the new `mesh.py push` because the nodes otherwise fetch a
published release and a live run against it would have measured the old code while looking like a success.

**Baseline, 30 directed paths, three arms.** `mesh.py ping` is new: `run` measured tessera and udp only, so
until now a baseline had no absolute floor to read them against.

| arm | vs the ICMP floor (median) | mean loss |
|---|---|---|
| ping | — (reference) | 0.111 % |
| raw UDP | +0.30 ms | 0.033 % |
| **Tessera** | **+1.00 ms** | **0.000 %** |

Tessera-minus-UDP is **+0.40 ms** median, which is exactly what the original 30-region campaign recorded — so
neither the AEAD provider switch nor the close fix costs anything measurable over real paths, and Tessera again
loses nothing where UDP and ICMP both lose a little.

**The close guarantee, live, under induced loss.** `probe --mode close` sends N messages back-to-back and closes
at once; the client cannot witness its own loss, so the echo side is the witness — and the number to read there
is `msgs=` on the stats line, not the `echoed N` above it, which counts echoes sent back before the closing
client stopped listening (it read 27-123 of 200 on runs that received every message).

On jnb->sao, 348 ms RTT, 10 reps x 200 messages, with **8 % loss applied to the data direction** (`tc netem` on
the sender's egress — the first attempt put it on the *receiver's* egress, which holes acks and feedback but not
sources, and duly reported `recovered=0`):

| | |
|---|---|
| received | **201/201 on every one of the 10 connections** (200 + the 0-RTT token) |
| recovered by RLNC | 20-38 symbols per connection |
| gaps seen | 35-58 per connection |
| short tails | **0** |

About 300 source packets were lost and every one was recovered before the connection closed.

**What this does not show.** `lingered=0.0s` on every rep, and neither `CLOSE-PEER-UNDELIVERED` nor
`PEERCLOSE-HOLE` appeared in any stats line — they print only when non-zero. So the condition the fix guards
against never arose in 20 live close cycles: the new linger clause was not exercised, only carried. The defect
is ~1-in-11 under full-suite load in simulation, and 20 clean live cycles is consistent both with the fix
working and with the condition simply not occurring. The honest claim is that the close path is healthy live
under 8 % loss on a 348 ms path, not that the fix is proven there.

`tc` was removed from both nodes afterwards (verified back to `fq`).


## The 5G radio result: the simulator's radio profile mispredicted (2026-08-27, live)

Probing a Vultr node in ewr from a real 5G phone hotspot (wired Ethernet unplugged, so the radio is the only
path), 50 msg/s x 1200 B, 300 messages. First the two transports on the same link:

| arm | delivered | p50 | p99 | min |
|---|---|---|---|---|
| raw UDP | **250/300 (16.7 % lost)** | 64.6 ms | 141 ms | 37.8 ms |
| Tessera | **300/300 (0 % lost)** | 799 ms | 2750 ms | 113.7 ms |

The radio drops one packet in six. Tessera delivers everything — and pays for it with a median of 799 ms on a
path whose minimum is 113. That is the low-rate, high-loss regime the repair clock was built for, and which
`ConnConfig.repairClockEquationsPerRtt` shipped **off** because the in-process `5g-mmwave` profile said it
bought nothing there.

**On the real radio it buys a great deal.** Interleaved A/B, alternating arms to control for radio drift:

| repairClock | p50 per run |
|---|---|
| 0 (shipped default) | 396 / 606 / 799 / 996 / 1840 / 2320 ms |
| 12 | 110 / 113 / 120 / 185 / 1128 ms |

Every clean run at 12 landed **110-185 ms**; every run at 0 was **396 ms or worse**. Roughly a **7x cut in
median latency**, and the ranges of the clean runs do not overlap. The mechanism is visible in the counters: at
clock=0 the runs show `gated=` 25, 2874, 6328, 9001 — repairs the congestion controller refused — while the
clock arm emits 480-580 equations and delivers.

**Both arms fail sometimes, and that is the radio.** One clock=0 run lost 15 % of messages and one clock=12 run
lost 28 %; the hotspot's own conditions move far more than the setting does. So the honest claim is the median,
not the tail: the clock does not make this radio reliable, it makes the common case roughly seven times faster.

**The lesson is about the simulator, not the clock.** `NetemSim`'s `5g-mmwave` profile predicted no benefit —
p999 159 -> 164 ms, "nothing, for the same +45 % bandwidth" — and that prediction is what kept the feature off
by default. A real 5G radio, at 16 % loss with deep variable fades, behaves nothing like that profile. **A
netem profile named after a technology is not that technology**, and no amount of repetition inside the
simulator would have found this. It took unplugging the Ethernet.

Not changing the default on this evidence: one radio, one location, one carrier, one hour, and a hotspot that
may be shaped. But this is now the strongest candidate for a default change in the project, and the next radio
session should start here.


## Correction: the hotspot uplink was never simulated, and the radio A/B is under-powered (2026-08-27)

Two corrections to the 5G entry above, both found by trying to reproduce its own claim.

**1. The `cell-hotspot` profile was never actually applied as a hotspot.** Its asymmetry — 0.56 Mbit up against
20 Mbit down — only takes effect once `NetemSim.uplinkPeer` is set, and **no bench ever set it**. Every
`cell-hotspot` run in this file therefore measured a *symmetric 20 Mbit link*, which is not a hotspot at all. The
uplink saturation that the profile exists to model, and that breaks Tessera on a real phone (its repair overhead
exceeding a ~0.6 Mbit uplink), had never been simulated. `Main.kt` now sets `uplinkPeer` to the server address;
the same run goes from p50 28.7 ms to **p50 337 ms**, which is the bloat the profile documents.

So the earlier conclusion "the simulator mispredicted the radio" is **wrong as stated**. The simulator was never
asked the question. The profile's parameters are in fact well calibrated against a fresh live rate ladder:

| offered | delivered | loss | goodput |
|---|---|---|---|
| 50/s = 60 KB/s | 1500/1500 | 0 % | 60 KB/s |
| 75/s = 90 KB/s | 1100/1200 | 8.3 % | 82 KB/s |
| 100/s = 120 KB/s | 745/1200 | 37.9 % | 75 KB/s |
| 125/s = 150 KB/s | 652/1200 | 45.7 % | 82 KB/s |
| 150/s = 180 KB/s | 648/1500 | 56.8 % | 78 KB/s |

Goodput plateaus at ~78-82 KB/s ≈ **0.63 Mbit/s** against the profile's modelled 0.56; RTT p50 51.5 ms against
its ~50 ms nominal; queue bloat to p99 1.9 s against its documented ~1.4 s. The model was right and unused.

**2. The repair-clock radio result is under-powered, and the "~7x" figure should not be quoted.** The link is
violently non-stationary. With `repairClock=0`, across one session on one radio, message loss measured **0 %,
0 %, 15 %, 43 % and 66.5 %** — the same binary, the same command, minutes apart. That spread is larger than the
effect being attributed to the setting, which is exactly the error `bench bulk` was rebuilt to prevent and which
this probe has no defence against: it reports one run.

What survives the correction is weaker but still real: across every run taken, `repairClock=12` never measured
worse than `0` on median latency and never lost more than one arm's worth, while `0` produced the four worst
runs of the session. It is a promising signal on a link that cannot currently resolve it. **What is needed is
`--runs` in the probe with interleaved arms and a reported interval, the same discipline `bench bulk` got** — and
until then no radio A/B in this file should be read as a measurement of a setting rather than of the weather.
