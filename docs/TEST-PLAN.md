# Test plan

What has been measured, what has not, and the order to close the gap. Figures cited here come from runs on
record (`docs/BENCH-netem.md`); anything unmeasured is marked as a gap rather than estimated.

## Three axes, moved one at a time

Runs are named by coordinates — `L1·E3·W2` is the pure-JDK build, on a LAN, moving bulk data. Move one axis per
run, and measure the `rawudp` floor in the same session so link drift cancels.

- **Implementation** (L0–L4) — which datapath and threading model sits under the protocol.
- **Environment** (E0–E5) — what the packets actually traverse.
- **Workload** (W1–W5) and **faults** (F1–F7) — what is asked of it, and what is done to it.

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
5. **E4 at full scale**, once the harness has proven itself.
6. **L2 and beyond** — implementation work, once there is a real-network baseline to regress against.

Buy the cheapest information first, and never let an implementation change land without a measurement that would
have caught its regression.
