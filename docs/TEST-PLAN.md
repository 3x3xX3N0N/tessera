# Test plan

What has been measured, what has not, and the order to close the gap. Figures cited here come from runs on
record (`docs/BENCH-netem.md`); anything unmeasured is marked as a gap rather than estimated.

## Three axes, moved one at a time

Runs are named by coordinates — `L1·E3·W2` is the pure-JDK build, on a LAN, moving bulk data. Move one axis per
run, and measure the `rawudp` floor in the same session so link drift cancels.

- **Implementation** (L0–L4) — which datapath and threading model sits under the protocol.
- **Environment** (E0–E5) — what the packets actually traverse.
- **Workload** (W1–W5) and **faults** (F1–F10) — what is asked of it, and what is done to it.

## Coverage today

| Property | Status | Evidence |
|---|---|---|
| Protocol logic, unit level | measured | 226 tests (core 127, transport 87, native 12), both datapaths, repeated runs |
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
| F5 | Peer disappears mid-transfer | bounded detection, no wedged connection | partial — server *restart* covered by stateless reset (unit + endpoint); silent disappearance still falls to the idle timeout |
| F6 | MTU black hole | DPLPMTUD finds the real limit | sim only |
| F7 | Replay / malformed input | anti-replay holds, no crash, no amplification | unit only |
| F7b | Resource exhaustion on the un-authenticated initial path | a flood of well-formed garbage initials cannot force unbounded ML-KEM-768 decapsulation; a source that never reads the reply never reaches the KEM at all; an honest 0-RTT connect pays no extra round trip while the server is not under pressure | unit + endpoint |
| F8 | Coexistence with another transport on one bottleneck | Tessera does not starve a scavenging or loss-reactive peer flow | F8b measured in-sim: the neighbour is safe — **Tessera collapses on any saturated tail-drop bottleneck, even alone** (open defect, see F8b outcome); F8a (LEDBAT) + tc run open |
| F9 | Scheduled outage (satellite handover, obstruction dropout) | a link that goes away on a cadence, not at random: delivery survives, and the tail is bounded by the gap plus a repair round | sim; burst fix landed, p95 cost open |
| F10 | Slow consumer (receiver memory) | a reader that stops draining backpressures the peer via `MaxData` instead of growing the inbox; a lost advert cannot deadlock the sender; a dead peer cannot hang a flow-blocked `send()` | unit + endpoint, both datapaths |

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

## F7b — resource exhaustion on the un-authenticated initial path

The gap: `ZeroRtt.Server.accept` performed an X25519 agreement and an ML-KEM-768 decapsulation — ~0.5 ms of one
core, ~2000/s, forced by ~19 Mbit/s of garbage — before anything authenticated the sender. The responder's public
key is published on purpose (`tessera echo` prints it), so the attack needs no secret. See "Address validation" in
`docs/SPEC.md` for the mechanism; `bench/src/main/kotlin/tessera/bench/AddressValidationBench.kt` for the numbers.

| Case | Covered by |
|---|---|
| Honest 0-RTT connect, server idle: no Retry, no extra round trip | `AddressValidationEndpointTest.anHonestZeroRttConnectCostsNoExtraRoundTripWhenTheServerIsIdle` |
| Under pressure: honest client still connects, one Retry, exactly one KEM, 0-RTT payload preserved | `...underPressureAnHonestClientStillConnectsAtTheCostOfOneRetry` |
| A source that never reads the reply (stands in for a spoofed one) reaches zero KEM operations | `...aSpoofedSourceThatNeverReadsTheReplyNeverReachesTheKem` |
| 4000 garbage initials cannot drive KEM past the configured budget, with pressure off | `...aFloodOfGarbageInitialsCannotDriveKemOpsPastTheBudget` |
| Token accepted for its own address/bucket; rejected when forged, truncated, expired, wrong port, wrong host, wrong secret | `core/AddressValidationTest` (single-bit forgery over every byte) |
| Per-source bucket, global KEM budget, pressure detection (rate and failure-rate), bounded table under a 200k-source walk | `core/AddressValidationTest` |

Not covered, and worth a netem run later: whether the pressure detector's 1-second window oscillates on a link
whose RTT is comparable to the window, and what a flood does to *established* connections' tail latency.

## F10 — slow consumer (receiver memory)

The gap: everything the receiver holds for *partial* messages is capped (F7-adjacent, v0.7), but a **complete**
message sat in the unbounded `inbox` until the application called `receive()` — an application that stopped
calling grew receiver memory without limit. The first fix attempt clamped the congestion credit by inbox headroom
and is the cautionary tale here: it bounded the channel datapath (~1.3 MB against a 256 KiB cap, asymptotic) and
let the native, batched datapath track the offered load (7.7 MB of 8 MB) — a timing-dependent mechanism, reverted
unshipped. The shipped mechanism (`MaxData`, v0.8, see SPEC) is an invariant in app-payload bytes, which is why
its central test asserts an exact bound with zero slack and runs **both datapaths inside one test method** rather
than trusting the task-level re-run.

| Case | Covered by |
|---|---|
| A stalled reader bounds unread inbox at exactly `recvWindowBytes` (8 MB offered vs 256 KiB window), then every message arrives intact on drain and the sender unblocks — channel **and** native datapath | `transport FlowControlTest.aStalledReaderBoundsTheInboxAndResumesOnDrain` |
| An advert blackout (piggybacked + standalone both suppressed) stalls the sender, the flow probe fires into it, and lifting the blackout recovers everything | `...aLostAdvertRecoversViaTheFlowProbe` |
| A first message far above the sender's initial window is admitted by the establishment advert (no ACK exists yet to piggyback on) | `...theEstablishmentAdvertLiftsTheInitialLimit` |
| Limit monotonic/idempotent under re-sent and stale adverts; charge stops at exactly the limit; refund reopens the window; negative wire limit rejected | `core FlowControlTest` |
| Frame 0x09 golden wire vector (and the 0x08 vector that had been missing) | `core WireVectorsTest.maxDataFrame` / `closeFrame` |
| Mutated 0x09 (and 0x08) frames throw only declared exception types in the parser loop | `core FuzzTest.frameCodecRead` corpus |
| A fragment past a fin-established length, or a fin below the buffered extent, is dropped instead of wedging the reassembly slot (pre-existing IOOBE, found while designing this) | `transport ReassemblerTest`, three cases |

Not covered, deliberately: receiver-side drops of charged messages (reassembly refusal, codec failure) leak
window permanently — the receiver cannot credit back a size it never learned; honest same-version peers cannot
hit it, and the drop counters expose it. A dead peer under an active flow block is covered by design review only
(the rx-silence exit in `awaitFlowWindow`), not by a test — it needs a peer that acks probes and then vanishes,
which is cheap under `NetemSim` outage scheduling and worth adding to a netem run later.

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

### F8b outcome (2026-08-24, in-process) — the neighbour was never in danger; Tessera is the casualty

`transport CoexistenceTest` (`@Tag("timing")`, three arms, one shared `NetemSim` bottleneck for both flows' data —
one departure cursor, one tail-drop limit — and a clean delay-only return path for both flows' acks; the standard
fairness topology, deviating from the tc plan above because a return path sharing the tail-drop queue makes every
loss come with queueing delay and the shallow regime becomes inexpressible). Link 20 Mbit (2.5 MB/s), 40 ms RTT,
BDP = 80 pkts. The competitor is a real `CubicCc`-driven UDP flow (core's CUBIC/HyStart++ window + pacing,
receiver-side gap detection, RTO) — loss-*reactive* but loss-*tolerant*: no retransmission, goodput = bytes
received, i.e. closer to CUBIC video than to TCP file transfer. One seed (42), rates over 6 s windows:

| arm | queue | cubic solo → concurrent → after | tessera concurrent | drops (sim, aggregate) | tessera counters |
|---|---|---|---|---|---|
| solo control | 1000 (≈12 BDP) | — | **~0.00 MB/s** | 64 % | fec=0.500 (cap), resends 3347, ccLoss 8297/16364, creditTarget pinned at the 13.5 KB floor, srtt 41.5 vs minRtt 40.2 ms |
| deep | 1000 | 2.13 → 1.57 → 1.88 MB/s | ~0.00 MB/s | 57 % | fec=0.500, resends 2071, ccLoss 7695/16233, srtt 96.7 ms |
| shallow | 56 (~16 pkts of real backlog) | 1.22 → 1.01 → 1.23 MB/s | ~0.00 MB/s | 77 % | fec=0.500, resends 7209, **ccLoss 8978/46614 — 81 % of losses ignored by the gate**, exactly the predicted shallow-regime blindness |

**The original question is answered inverted.** The CUBIC neighbour keeps 64–83 % of its solo rate while sharing
and recovers fully when Tessera leaves — it is not starved. Tessera delivers approximately nothing, **including in
the solo arm with no competitor at all**: it cannot use a saturated tail-drop bottleneck. The mechanism, from the
counters: the rate cap drops packets → the Kalman loss estimator reads congestion drops as link loss and pins FEC
redundancy at its 0.5 cap → repair symbols and feedback-driven re-sends are charged to credit but **bypass the
send gate** (only `send()` blocks on `canSend`; the repair machinery free-runs) → offered load stays above
capacity permanently → goodput ≈ 0, self-sustaining. Three amplifiers: (1) the delay gate is half-blind because
bursty arrivals see a bimodal queue — burst heads pass at ~minRtt, burst tails are *dropped*, not delayed, so
srtt barely inflates (41.5 vs 40.2 ms in the solo arm despite maxQueued=1000) and half to four-fifths of losses
read as random; (2) the credit target is `rxRate × minRtt`, and rxRate ≈ 0 under collapse, so the target pins at
its floor and cannot lift goodput back up; (3) FEC at 0.5 doubles the load exactly when the queue is full — the
`PathEstimator` comment already records why damping repair by `srtt − minRtt` was rejected (it starved the
jittery profiles), so a better congestion-vs-link-loss discriminator is needed, not a revert to that.

**Why nothing ever caught this:** every netem preset's rate cap (30–1000 Mbit) exceeds the standard 2000 msg/s ×
1200 B ≈ 19.2 Mbit workload, so the cap never bound — and W2 bulk has never run (the "Coverage today" gap). This
is the first time Tessera ever saturated a rate limit, in-process or otherwise.

**Follow-up work this opens (congestion-control decisions, owner's call, not fixed here — the F9 note's
precedent):** regulate repair/re-send emission under congestion evidence (same family as the F9 drain-burst
question); a congestion-vs-link-loss discriminator for the FEC ratio; un-pin the credit target under collapse;
and revisit the delay gate against bimodal burst queueing. The test stays green while recording this — its hard
assertions are neighbour liveness and recovery only, per the no-policy-threshold rule above.

Harness caveats: single seed, in-process clock, no retransmitting-TCP goodput penalty for the competitor, and no
AQM/ECN regime (`NetemSim` cannot mark ECN and the rx path hardcodes it false — an AQM arm needs sim marking
support first).

### Why this matters before shipping two lanes

`OroborosDaemon` already runs a uTP transport with its own `DatagramChannel` and LEDBAT-shaped control. Adding a
second UDP transport with an opposing congestion philosophy to the same process, on the same uplink, is an
interaction that should be measured before it is deployed — not diagnosed afterwards from a support ticket about
slow torrent downloads.


### F9 outcome — a blackout is not congestion, and the peer already said so

Fixed, with a caveat. The ack-driven repair path walks the peer's *own* delivered map, so those sequences are
confirmed missing rather than guessed — the token bucket exists to stop speculative repairs amplifying a congested
path, and after a link outage it only meters out a recovery that could have been immediate.

`outageDrainBudget` now grants a burst when the map shows a contiguous hole of at least
`ConnConfig.outageDrainMinRun` (64, about 80 ms of solid nothing at 800 msg/s — a queue does not do that) **and**
the CUBIC fallback is not engaged. Receiver credit still bounds bytes in flight, so the burst cannot overrun the peer.

**The obvious signal does not work.** The first implementation gated on queueing delay (`srtt - minRtt`) and never
fired once: during a blackout srtt is inflated *by the blackout*, climbing from 17 ms to 38 ms across the hole on the
starlink profile, so a delay test rejects precisely the case it is meant to admit. `HybridCc.engaged` is the
uncontaminated signal — it is set only by an ECN-CE mark or by loss with queueing delay that was already there.

Paired A/B, one process, same seed, one 200 ms handover at 800 msg/s (`OutageDrainTest`):

| | p50 | p95 | p99 | p99.9 | max | throttled | bursts |
|---|---|---|---|---|---|---|---|
| metered | 44.6 | 52.1 | 574.1 | 847.3 | 909.3 | 36,601 | 0 |
| drained | 44.5 | **93.3** | **333.1** | **360.9** | **577.8** | 157 | 41 |

Both arms deliver 16,000/16,000. The floor for this scenario is 270 ms (a 200 ms gap plus one 70 ms round trip), so
the tail moves from 2.1x the floor to 1.23x.

**The caveat is the p95 regression: 52 ms to 93 ms.** Bursting ~450 re-sends into a 12 Mbit uplink briefly congests
it and delays ordinary traffic behind the recovery. That is a genuine trade — a better tail bought with a worse
upper-middle — and it is why the threshold is a `ConnConfig` knob rather than a constant: set
`outageDrainMinRun = Long.MAX_VALUE` to restore the metered behaviour. Whether the trade is right is a deployment
policy question, and it interacts with F8: a burst is exactly what a scavenging neighbour would feel.

Still open: pacing the burst over one RTT instead of emitting it at once should keep most of the tail improvement
without the p95 cost. Not attempted.


### Real-time tests are quarantined, not tolerated

Tests that assert wall-clock behaviour across a simulated link are unreliable when the suite saturates the
host: `NetemTest.twoThousandMessagesPerSecondDeliverEverythingOnTime`,
`OutageDrainTest.aBlackoutAtRateDrainsWithoutBeingThrottled`, and the three `CoexistenceTest` arms (F8b —
throughput shares over real seconds). All are tagged `timing`, excluded from `test` and
`nativeTest`, and run alone by `./gradlew :transport:timingTest`. A suite that is habitually red teaches everyone
to ignore red, which is how a genuine regression gets waved through — so the default suite is deterministic and
the timing work is a deliberate, serial step.

**Their numbers vary run to run and must be read as paired comparisons, never as absolutes.** The F9 A/B measured
p99 574 -> 333 ms on one run and 377 -> 337 ms on another, because how much traffic a handover swallows depends on
where it lands (297 packets in one arm, 465 in the other). The direction is consistent — fewer throttle events,
lower p99/p99.9/max — but quoting a single magnitude would be dishonest.

## F5 — peer disappears mid-transfer (server restart via stateless reset)

CONNECTION_CLOSE handles a peer that tears down *with* its keys. A restarted or crashed server has *lost* the
connection's keys and cannot authenticate a CLOSE, so before this the client retransmitted into a black hole for the
full idle timeout (10 s). Stateless reset (RFC 9000 §10.3 shape, `core/StatelessReset.kt`, see `docs/SPEC.md`) closes
that: the server re-derives the connection's 16-byte token from its restart-surviving ticket key and echoes it in a
reset packet; the client recognises the token it was handed at handshake and frees the connection at once.

| Case | Covered by |
|---|---|
| Token deterministic, 16 bytes, differs per id and per secret; distinct from the Retry secret; constant-time match | `core/StatelessResetTest` |
| Client tears its connection down on a valid reset (well within the idle timeout); a wrong trailing token does not | `transport/StatelessResetTest.clientTearsDownOnAValidResetButNotAWrongOne` |
| Server statelessly emits the exact token for an unknown short id on the demux miss, on both datapaths | `transport/StatelessResetTest.serverStatelesslyEmitsTheRightTokenForAnUnknownId` (runs under `test` and `nativeTest`) |
| No reflection/amplification: a packet shorter than the reset draws none | `transport/StatelessResetTest.aTooShortPacketDrawsNoResetSoThereIsNoAmplification` |

Not covered, and left open: the reverse direction (a *client* restart — the server would need a token for the
client's id, which is not implemented); a real end-to-end restart of a live `TesseraServer` process (the tests craft
the reset from a raw socket, since a reset on the wire is exactly a short-header-shaped datagram with the token as its
last 16 bytes); and silent disappearance with no restart, which still falls to the idle timeout by design.

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
5. **F8 coexistence** — F8b ran in-process (2026-08-24): the neighbour is safe, **Tessera collapses on any
   saturated bottleneck** — which promotes the W2/bottleneck congestion work above it; F8a (LEDBAT) and the tc
   variant stay open.
6. **E4 at full scale**, once the harness has proven itself.
7. **L2 and beyond** — implementation work, once there is a real-network baseline to regress against.

Buy the cheapest information first, and never let an implementation change land without a measurement that would
have caught its regression.
