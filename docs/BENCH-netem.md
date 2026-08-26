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
