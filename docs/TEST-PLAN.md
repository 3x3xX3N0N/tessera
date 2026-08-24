# Test plan

What has been measured, what has not, and the order to close the gap. Figures cited here come from runs on
record (`docs/BENCH-netem.md`); anything unmeasured is marked as a gap rather than estimated.

## Three axes, moved one at a time

Runs are named by coordinates — `L1·E3·W2` is the pure-JDK build, on a LAN, moving bulk data. Move one axis per
run, and measure the `rawudp` floor in the same session so link drift cancels.

- **Implementation** (L0–L4) — which datapath and threading model sits under the protocol.
- **Environment** (E0–E5) — what the packets actually traverse.
- **Workload** (W1–W5) and **faults** (F1–F8) — what is asked of it, and what is done to it.

## Coverage today

| Property | Status | Evidence |
|---|---|---|
| Protocol logic, unit level | measured | 172 tests, both datapaths, repeated runs |
| Loss recovery under emulated impairment | measured | 6 profiles × 5 runs; 100 % delivery, p99 within 0.1–33 ms of plain UDP |
| 0-RTT connect, emulated links | measured | 6000/6000 connects; payload lands at one one-way delay |
| Native vs pure-JDK datapath | partial | loopback + sim only; the netem matrix was run **native-only** |
| Bulk transfer / sustained throughput | **gap** | nothing above 1200 B × 2000/s has ever run |
| Concurrent connections, server under load | **gap** | one connection at a time, always |
| Cold start | partial | known: 128 ms cold vs 8.4 ms warm; never characterised |
| Multipath | **gap** | designed, not built |
| Real network | **gap** | no NIC-to-NIC, no router, no ISP, no radio — ever |

## L — implementation tiers

Each tier inherits the previous tier's suite unchanged; that is what the `UdpIo` seam is for, and it is already
proven — `:transport:nativeTest` runs all transport tests against the second implementation via one system property.

| Tier | What | Cost |
|---|---|---|
| **L0** | As shipped, native datapath. Every published netem number came from here. | exists; reference only |
| **L1** | Pure JDK (`-Dtessera.native=off`). No native code in-process, scalar FEC. Nothing detectable below ~10 kpps; 3.6× worse p99 at 50 kpps. Only configuration that compiles on JDK 22+. | one flag |
| **L2** | Third `UdpIo` implementation feeding a host event loop. Needs an async delivery hook too — `receive()` is a blocking queue poll. | ~200 lines I/O + ~40 async + 10-line test task |
| **L3** | Embedded in a host application, driving real traffic. | integration project |
| **L4** | Multipath: second path, per-path packet-number spaces, repair striped across paths. | substantial |

## E — environments

| Env | What | Adds | Status |
|---|---|---|---|
| E0 | Loopback, one JVM | protocol logic only | done |
| E1 | In-process link simulator (`NetemSim`) | delay, jitter, bursty loss, reorder, rate — seeded | done |
| E2 | `tc netem`, Linux kernel | real qdisc, kernel scheduling, socket buffers | 5 runs |
| E3 | Two hosts on a LAN | real NIC, driver, switch, interrupts, GSO/GRO | **gap** |
| E4 | WAN mesh, N regions | routing, transit, peering congestion, MTU, ECN, time-of-day | **gap** |
| E5 | Mobile / CGNAT | radio scheduling, carrier NAT, handover, doze, battery | **gap** |

## W — workloads and F — faults

| ID | Workload | Why different | Status |
|---|---|---|---|
| W1 | Small messages, paced | latency-dominated; all current numbers | done |
| W2 | Bulk transfer | throughput-dominated; stresses credit slow-start (~0.5 s) and pays FEC overhead (1.13–1.29×) on every byte | **gap** |
| W3 | Connect storm | N concurrent handshakes; per-accept ML-KEM cost only measured serially | **gap** |
| W4 | Idle then burst | where NAT mappings expire and radios must be promoted | **gap** |
| W5 | Many connections, one server | memory per connection, accept throughput, fairness | **gap** |

| ID | Fault | Should prove | Status |
|---|---|---|---|
| F1 | Bursty loss (Gilbert–Elliott) | FEC covers the burst, residual ARQ the rest | done |
| F2 | Reordering | no spurious loss, no packet-number decode failure | done |
| F3 | Grant blackout | cumulative credit means a lost grant cannot stall the sender | done |
| F4 | Path migration | rebinding survives, challenge/response revalidates | sim only |
| F5 | Peer disappears mid-transfer | bounded detection, no wedged connection | **gap** |
| F6 | MTU black hole | DPLPMTUD finds the real limit | sim only |
| F7 | Replay / malformed input | anti-replay holds, no crash, no amplification | unit only |
| F8 | Coexistence with another transport on one bottleneck | Tessera does not starve a scavenging or loss-reactive peer flow | **gap** |
| F9 | Scheduled outage (satellite handover, obstruction dropout) | a link that goes away on a cadence, not at random: delivery survives, and the tail is bounded by the gap plus a repair round | sim only |

## F9 — scheduled outage

Every impairment the harness modelled until 2026-08-23 was stochastic — delay, jitter, Gilbert-Elliott loss,
reorder, rate. A satellite handover is not: it recurs on a schedule, and a transport that treats it as congestion
(or whose timers overshoot its cadence) fails differently than one facing random loss. `NetemSim` now models it
(`outageEveryUs` / `outageDurationUs` / `outageJitterUs`, plus `outageOnceAtUs` for a one-shot dropout);
`bench/netem/profiles.sh starlink` fakes the same thing under tc with a background loop toggling a 100 %-loss rule,
since netem has no periodic outage of its own.

`STARLINK` is the profile that carries it: **200 ms of outage every 15 s** (see the preset's comment for the
sources), which is ~2.8 RTT — beyond any FEC window, so recovery is retransmission or nothing. The pre-handover
profile survives as `STARLINK_LOSSY_ONLY`, because every starlink row in `docs/BENCH-netem.md` was measured with it.

Covered by `transport/.../OutageTest.kt`, 65 s of real time at 50 msg/s, seed 31 (first run, 2026-08-23):

| | |
|---|---|
| Delivery | 3250 / 3250, 5 handovers, 145 packets swallowed by the gaps, 0 `send()` failures |
| Latency | p50 36.1 · p95 47.2 · p99 229.6 · p99.9 347.2 · max 368.0 ms |
| Over one outage length (200 ms) | 42 messages of 3250 (1.3 %) |
| Liveness | no empty 5 s window; the two windows straddling a handover trade 237 / 261 deliveries, i.e. catch-up, not stall |

So the transport does survive the cadence: the tail is one outage plus roughly one repair round trip
(368 ms ≈ 200 + 2.4 RTT), no PTO backoff overshoots the 15 s cadence, and credit recovers after each zero-delivery
window.

**At 2000 msg/s it does not.** Correcting the preset made `RecoveryTest.burstyProfiles...` (which had been using
`STARLINK` as a pure loss profile) cross a handover, and it failed loudly: one 200 ms outage swallowed **512
packets**, and the resulting p99 was **1076 ms** against a 116 ms bound — five outage lengths. The client counters
name the mechanism: `resend=445 ... throttled=37893`. The ack-driven repair path's token bucket
(`ConnConfig.gapRepairFraction = 0.25`) refills at a quarter of the source rate, so a gap of 512 packets can only
be re-sent at ~500 packets/s and takes ~1 s to drain, whatever the link is doing by then. That bucket exists to
stop blind re-sends from amplifying a lossy link; a *blackout* is exactly the case where it is counter-productive,
because the peer's feedback map already names every missing packet (`feedback=411` of the 445) — the re-sends are
known-needed, not speculative. Whether the bucket should be bypassed for feedback-confirmed re-sends, or refilled
from the post-outage delivery rate, is a congestion-control decision and is left to the owner of that code; it is
**not** fixed here. `RecoveryTest`'s two starlink cases were repointed at `STARLINK_LOSSY_ONLY`, which is what they
were always measuring.

**Gaps that remain**: no matrix run against the corrected profile; nothing has tested a multi-second obstruction
dropout against the 10 s idle timeout; and the tc-side handover helper has never been run on Linux — only its
process lifecycle (start, toggle, restore-on-TERM, no stray process after `clear`) was verified, with a stubbed
`tc`.

## F8 — coexistence with other transports

Tessera's congestion control is deliberately **not** loss-reactive: receiver-driven credit ignores loss that
arrives without queueing delay, and the CUBIC fallback engages only on ECN-CE or on loss *with* delay above
`max(2 ms, 25 % of minRtt)`. That is correct for a lossy radio link and potentially antisocial on a shared
bottleneck. Nothing has ever measured what happens when Tessera shares a link with something else.

Two sub-cases, because the answers differ and the consequences differ.

### F8a — versus a scavenging transport (uTP / LEDBAT)

LEDBAT is designed to yield: it targets a small one-way delay and backs off as soon as queueing appears. Tessera
is designed not to. **The predicted outcome is that Tessera takes the bandwidth and the scavenger gets out of the
way** — and where both lanes live in the same daemon, that looks like a bug in the scavenger rather than a
policy choice in Tessera.

| | |
|---|---|
| Bottleneck | netem `rate` with a real queue (LTE profile, 30 Mbit) at 1 BDP and at 0.25 BDP |
| Flow A | Tessera bulk transfer (W2) |
| Flow B | a LEDBAT-style scavenging flow, started first and already in steady state |
| Measure | each flow's throughput share in 5 s windows; queueing delay seen by each; time for B to fall below 10 % share; whether B recovers when A stops |

There is no pass/fail threshold until someone sets a policy. The experiment's job is to produce the number that
makes the policy decision possible, and to test one mitigation: a configurable send-rate ceiling on Tessera so a
deployment can bound the damage without abandoning credit-driven control.

### F8b — versus ordinary TCP (CUBIC)

The neighbour's video stream, or any other TCP flow on the same uplink. The interaction depends on the queue:

- **Deep buffer** — TCP fills it, queueing delay appears, Tessera's gate engages, and behaviour should be
  reasonable. This is the benign case and the one most likely to be tested by accident.
- **Shallow buffer or AQM** — loss arrives *without* sustained queueing delay, which is precisely the signal
  Tessera is built to ignore. This is where it may take more than its share, and it is the case that must be
  measured deliberately because it will not show up otherwise.

Measure both regimes, report each flow's share and completion time, and record whether Tessera's `ignoredLosses`
counter is climbing — that counter is the direct evidence of the mechanism at work.

### Why this matters before shipping two lanes

`OroborosDaemon` already runs a uTP transport with its own `DatagramChannel` and LEDBAT-shaped control. Adding a
second UDP transport with an opposing congestion philosophy to the same process, on the same uplink, is an
interaction that should be measured before it is deployed — not diagnosed afterwards from a support ticket about
slow torrent downloads.

## Reporting format

```
<label> n=N delivered=D late=L loss=X%  p50=… p95=… p99=… p99.9=…  max=…
        overhead=bytes_sent/payload_delivered
```

- **p99.9** ("three nines") is the tail statistic. **Max is reported but is not a statistic** — one sample, defined
  by a single GC pause or route flap.
- **Latency means different things per environment.** E0–E2 report one-way delay (shared clock). E3+ report round
  trips against the probe's own clock — no clock sync is assumed. Never put them in the same column.
- **Delivered / late / lost are three states.** Conflating late with lost hid a bench bug for three campaigns.

## E4 — the WAN mesh

Each node runs echo and probe and measures every other node: N×(N−1) directed paths. Serially that is a day;
scheduled as a round-robin tournament (a 1-factorization of the complete graph) it is under an hour, with ⌊N/2⌋
disjoint pairs in flight per round and every node doing one measurement at a time.

| Nodes | Directed paths | Rounds | Wall clock @ 90 s | Instance cost |
|---|---|---|---|---|
| 6 | 30 | 5 | ~8 min | $0.04/hr |
| 12 | 132 | 11 | ~17 min | $0.08/hr |
| 31 | 930 | 31 | ~47 min | $0.21/hr |

Report latency per unordered path (a round trip traverses the same loop both ways) and loss/jitter per directed
path (those genuinely differ). Validate the whole harness at 6 nodes before scaling.

## E5 — mobile

A cellular radio must be promoted out of idle before it can send: RRC idle→connected costs 100 ms–2 s. **A 0-RTT
connect on a cold radio pays that before the first packet leaves.** Until measured, "instant connect" is a claim
about wired paths, and the honest result is a curve of connect latency against prior idle time (0 s, 5 s, 30 s,
5 min), not a single number.

| Rung | Setup | Reveals | Cost |
|---|---|---|---|
| M0 | Phone as hotspot, laptop probes | radio promotion, CGNAT, carrier UDP policy, uplink grant delay | zero development |
| M1 | On-device via Termux + JDK | device power state, no tether hop | ~1 hour setup |
| M2 | Android client | doze, background sockets, OS handover, battery | ~1 week |
| M3 | Instrumented RF | controlled handover | overkill until needed |

Mobile-specific questions: CGNAT mapping lifetime (sets the keepalive period, which costs battery); Wi-Fi↔cellular
handover mid-transfer; and the cost of redundancy — 1.13–1.29× FEC overhead is billable data and radio-on time, so
a latency win that costs 20 % more bytes may still be the wrong trade on a phone.

## Ways these measurements have already lied

Each produced a confidently wrong number that survived at least one campaign.

- **A deadline that expired mid-run** — the bench counted the last 27 messages as lost on a clean link, for three
  campaigns. Harness, not protocol.
- **Measuring one configuration and shipping another** — all 26 netem measurements used the native datapath while
  the recommended configuration is pure JDK.
- **A cold JVM** — 128 ms first connect vs 8.4 ms warm is class loading and the first ML-KEM operation, not the
  network. Discard warm-up connects and say how many.
- **An emulator artefact read as a protocol result** — netem with both `rate` and jitter ratchets packets into a
  standing queue at high send rates. Compare latencies only within a rate.
- **A microbenchmark ratio quoted as end-to-end gain** — 2.6× packets/s and 24× on the FEC kernel are both real
  and both invisible below ~10 kpps.
- **Trusting the build cache** — Gradle restored cached results in 12 s and presented them as a pass. Force
  re-execution to verify.

## Order of work

1. **Re-baseline at L1** — one flag, ~30 min, makes every later comparison honest.
2. **E4 mesh at 6 nodes** — first real packets off-host, ~5 cents, validates the harness.
3. **M0 hotspot** — a real radio, no code; answers the radio-promotion question.
4. **W2 bulk** — the workload with no data behind it at all.
5. **F8 coexistence** — cheap (it is a netem run), and it gates shipping two UDP lanes in one daemon.
6. **E4 at full scale**, once the harness has proven itself.
7. **L2 and beyond** — implementation work, once there is a real-network baseline to regress against.

Buy the cheapest information first, and never let an implementation change land without a measurement that would
have caught its regression.
