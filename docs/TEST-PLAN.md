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
| Bulk transfer / sustained throughput | measured | W2 done 2026-08-25 (`bench bulk`, BulkTransferTest): loopback 22 MB/s, capacity-bounded links 70–88 % of ceiling, complete delivery; found the reliability horizon and the credit famine |
| Concurrent connections, server under load | measured | W5 done 2026-08-26: 400 connections on one server, fair (1.01×) and complete; footprint ~1 MB/pair idle is the open item |
| Cold start | measured | characterised 2026-08-26 (`bench coldstart`, fresh JVM per sample): 328 ms cold pure-JDK / 580 ms native vs 10–16 ms warm, attributed stage by stage. ~180 ms is BouncyCastle's **first class load** (of which ~120 ms is signed-jar verification), not ML-KEM — that hypothesis is falsified by an ordering control. Irreducible per-connect crypto ≈ 2.5 ms CPU; the rest is once-per-process and a startup warm-up removes it |
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
| E4d | NetemSim vs the kernel, matched parameters | is the simulator faithful? | **median yes, tail no** (2026-08-28, BENCH "NetemSim against the kernel"): p50 within 1.00-1.17x, p99 overstated 1.04x (transcont) to 2.67x (wifi-busy), scaling with jitter heaviness. It changes verdicts: the same repair-clock A/B reads 4.1x in the sim and 1.22x on the kernel. `bench/netem/sim-vs-tc.sh` |
| E4c | Real netem on a real path | does the repair clock reproduce off-simulator? | **no** (2026-08-28, BENCH "Real netem on a real path"): ~60 paired runs on a shaped 324 ms leg, at the live radio's own loss statistics, show no benefit and a slight negative - including the capacity-limited+lossy case where the sim predicted 1.5-8.4x. **NetemSim and real netem disagree at matched parameters**; the simulator is not validated against hardware. `bench/mesh/shape.py` |
| E4b | WAN mesh, 10 worst-path regions | does a long backbone path lose packets? | done 2026-08-28 (BENCH "E4 at ten regions"): **no** — raw UDP lost nothing on all 90 legs up to 358 ms. Tessera 100 % on all 90 (UDP lost on 3), median cost +0.50 ms p50 / +2.60 ms p99. One transient loss on `scl→syd` cost ~500 ms to recover: the low-rate tail on a real 324 ms path. The repair-clock A/B is **unrunnable here** — no loss to recover |
| E4 | WAN mesh, N regions | routing, transit, peering congestion, MTU, ECN, time-of-day | first mesh done 2026-08-26 (`bench/mesh/mesh.py`, BENCH "E4") — 6 continents, 30 directed paths: Tessera 0.00 % loss everywhere at +0.10 ms p50 / +0.80 ms p99 median over raw UDP, and delivered 100 % on the one lossy leg (jnb↔sao, where UDP dropped 0.67 %/0.33 %). Time-of-day and congested-transit sweeps still open |
| E5b | Simulator fidelity vs the radio | can any profile reproduce the live 5G result? | **answered 2026-08-28** (BENCH "The radio misprediction, resolved"): yes, once the uplink cap is not treated as a constant. Loss-model fits failed (worse in 5/5 seed-paired runs); with uplink headroom the repair clock wins 1.5-8.4x at p99, 5/5. Also fixed: the `rawudp` bench arm never applied the uplink asymmetry either |
| E5 | Mobile / CGNAT | radio scheduling, carrier NAT, handover, doze, battery | first contact done (5G hotspot, BENCH-netem "E5"): sub-Mbit uplink + CGNAT flow death measured; modelled as `CELL_HOTSPOT` preset, answered by rebind-on-silence + bloat shedding (`CellHotspotTest`, `RebindTest`); doze/battery/handover-on-real-radio still open |

## W — workloads and F — faults

| ID | Workload | Why different | Status |
|---|---|---|---|
| W1 | Small messages, paced | latency-dominated; all current numbers | done |
| W2 | Bulk transfer | throughput-dominated; stresses credit slow-start (measured: 90% of steady in ~0.5 s) and pays FEC overhead (measured 1.10–1.35× where healthy) on every byte | done 2026-08-25 (bench `bulk`, BulkTransferTest; BENCH "W2 bulk local") — loopback 22 MB/s, capacity-bounded links 70–88% of ceiling with full delivery; **found the reliability horizon live**: every high-BDP lossy preset wedges (evicted/skipDelivered tells), promoting item 3 |
| W3 | Connect storm | N concurrent handshakes; per-accept ML-KEM cost only measured serially | done 2026-08-26 (`bench storm`, ConnectStormTest; BENCH "W3") — an honest distributed crowd is **not taxed at all** (0 Retries at 200 simultaneous clients); the same load from ONE address trips the per-source bucket then the global valve (1829 Retries at 500, underPressure) and still refuses nobody (`dropped=0`, 500/500). Contract pinned: Retry allowed, refusal not |
| W4 | Idle then burst | where NAT mappings expire and radios must be promoted | local half done 2026-08-26 (`bench idle`, `IdleBurstTest`; BENCH "W4") — **the congestion state costs the first burst nothing**: the receiver credit target is byte-identical across gaps of 1/5/30 s (11 arms) and the first post-idle message costs 158–626 µs against a 93–257 µs paced steady-state p50, the same spread a gap=0 burst costs. The hypothesis that slow start restarts after idle is **falsified**. The real cost was elsewhere: **there was no keepalive frame**, so idle beyond `idleTimeoutMs` (then 10 s) tore the connection down — **fixed 2026-08-27** (`Frame.Ping` on a `pingIntervalMs` timer; defaults now 25 s ping / 60 s timeout, `KeepaliveTest`). Radio half (doze, carrier NAT expiry, RRC promotion) still open — needs a handset, see E5 |
| W5 | Many connections, one server | memory per connection, accept throughput, fairness | done 2026-08-26 (`bench conns`, ManyConnectionsTest; BENCH "W5") — fairness is fine (max/min 1.00–1.01× to 400 conns, 100 % delivery, no per-connection threads); **the finding is footprint: ~1 MB per connection pair idle, flat across 50–400**, almost all eagerly-allocated fixed rings (`RING`=8192, `BODY_RING`=4096) sized for the 2000 msg/s worst case — ~420 MB for 1000 idle connections. Making those `ConnConfig`-sizable is the follow-up, not taken here |

| ID | Fault | Should prove | Status |
|---|---|---|---|
| F1 | Bursty loss (Gilbert–Elliott) | FEC covers the burst, residual ARQ the rest | done |
| F2 | Reordering | no spurious loss, no packet-number decode failure | done |
| F3 | Grant blackout | cumulative credit means a lost grant cannot stall the sender | done |
| F4 | Path migration | rebinding survives, challenge/response revalidates | sim only; client rebind-on-rx-silence added (v0.9, `RebindTest`) after E5 measured ~1/3 of cellular flows born dead to CGNAT mapping death |
| F5 | Peer disappears mid-transfer | bounded detection, no wedged connection | partial — server *restart* covered by stateless reset (unit + endpoint); silent disappearance still falls to the idle timeout |
| F6 | MTU black hole | DPLPMTUD finds the real limit | sim only |
| F7 | Replay / malformed input | anti-replay holds, no crash, no amplification | unit + endpoint 2026-08-26 (`core/FuzzTest`, `transport/EndpointFuzzTest`) — parser fuzzing extended past the `read()` boundary to the live socket: **no product defect found** in 25.5 M core cases + 45 k malformed initials + 45 k demux-miss packets + 44 k *authenticated* fuzzed frame bodies + 900 k reassembly fragments. Amplification measured, not assumed: 0.020x aggregate on malformed initials, 0.32x on the demux miss. **Two harness defects fixed** — `-Dtessera.fuzz.iterations` never reached the forked test JVM, and the corpus was eager (OOM above ~500 k). See F7 below for what was *not* reached |
| F7b | Resource exhaustion on the un-authenticated initial path | a flood of well-formed garbage initials cannot force unbounded ML-KEM-768 decapsulation; a source that never reads the reply never reaches the KEM at all; an honest 0-RTT connect pays no extra round trip while the server is not under pressure | unit + endpoint |
| F8 | Coexistence with another transport on one bottleneck | Tessera does not starve a scavenging or loss-reactive peer flow | F8b measured and the collapse **fixed** (v0.9: solo 2.01 MB/s zero-drop asserted; contested = scavenger, neighbour ≥78 %); **F8a measured 2026-08-25 — prediction inverted, Tessera yields even to LEDBAT** (LedbatCoexistenceTest); **AQM/ECN wired + measured** (AqmEcnTest: marks replace drops, 3× faster, 23× fewer drops); **tc run done** (real-netem matrix 2026-08-25: 0% loss all profiles, sim validated — BENCH "The tc run"); fairness policy **DECIDED 2026-08-26**: scavenger by default (it is the "no standing queue" design goal, and no neighbour is starved); cost and the lever to revisit recorded below |
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

## F7 — replay and malformed input

The gap `unit only` named: `core/FuzzTest` (2026-08-23) holds each wire parser to a declared-exception contract on
attacker bytes, which is necessary and not sufficient. Those parsers sit behind a demux, a rate limiter and a crypto
layer, and the properties an off-path attacker actually attacks are properties of the whole endpoint — no
amplification, no crash escaping the rx thread, anti-replay — none of which a `read()`-level sweep can see.
`EndpointFuzzTest` (2026-08-26) closes that, and `FuzzTest` gained the core entry points the original sweep missed.

**What was fuzzed, and how.** Generation means seeded random bytes; mutation means operators applied to a valid
encoding — bit flip, byte splat, truncation, garbage extension, length-field corruption (an 8-byte all-ones varint
dropped wherever a length is expected), **duplication of a byte run**, **transposition of two runs**.

| Surface | Reached how | Cases at the large run |
|---|---|---|
| `PacketHeader`, `ShortHeader`, `VarInt`, `FrameCodec`, `CompactMsg`, `ConnParams` TLV | generation + mutation, direct | ~2 M each |
| Frame *streams* under duplication and transposition (new) | mutation of multi-frame bodies | 2 M |
| `RetryToken.verify`, `StatelessReset.matches` (new) | mutation of a genuine token; no forgery verified | 2 M each |
| The FEC `SymbolValidator` at three symbol sizes (new) | generation | 500 k each |
| `ZeroRtt.Server.accept`, `Resumption.Server.accept`, `PacketProtection.open`/`unprotectHeader`, `RlncDecoder.onRepair` | mutation of valid bodies | 300 – 2 M (KEM- and window-cost capped) |
| **Malformed initials at a live server socket** (new) | mutation of a captured real initial + generated long-header garbage | 44 995 datagrams |
| **Demux miss / stateless reset** (`onUnmatchedShort`) (new) | generation | 45 000 datagrams |
| **`parseFrames`, post-authentication** (new) | mutated frame bodies sealed under the client's own session key, so the server really opens and parses them | 44 488 sent, 31 606 parsed, 1 139 connection pairs |
| **`Reassembler`** (new) | generated (msgId, offset, len, fin) tuples including the contradictions an honest sender cannot produce | 900 000 fragments |

**Measured, not asserted from the design.** Aggregate bytes the server emitted per byte received: **0.0199x** across
45 k malformed initials (the design bound is 3x; what garbage actually buys is a ~31 B Retry or nothing), **0.32x**
across 45 k demux-miss packets (`onUnmatchedShort` refuses to answer anything shorter than its own 40 B reset, so a
runt gets silence). Worst single case 2.21x — and that figure is attribution noise, not amplification: a 2 ms
receive window credits a reply provoked by an earlier datagram to the current one. Anti-replay: 50 verbatim replays
of one real initial produced exactly one connection and one KEM; 32 verbatim replays of one authenticated packet
delivered its message once and counted 31 duplicates.

**No product defect was found.** Rejections were counted, as designed (`rxErrors` 16 254, `decodeErrors` 675,
`oversizeDropped` 25 over the authenticated sweep — a counted rejection is a pass). Two defects *in the fuzz harness*
were found and fixed, both of which had made the previous coverage claim weaker than it read:

 1. `-Dtessera.fuzz.iterations=N` was documented but never worked: the test JVM is forked and does not inherit the
    daemon's `-D`, so every "large run" was silently the default run. Now forwarded in the root `build.gradle.kts`.
 2. With the property actually working, the corpus — eagerly materialised into a list — died of `OutOfMemoryError`
    above roughly 500 k iterations. It is now lazy and restartable.

**Not reached, and the coverage claim is only worth what these exclusions say.** The `native` datapath's own rx loop
(`NativeIo.kt`) — the fuzzers drive the JDK channel path; the two demuxes are separately written, so `NativeUdpIo`'s
is *not* covered by this. The Retry *response* path on the client (`TesseraClient.onReply` parsing a malformed
Retry). Malformed handshake *replies* at the client (`onHandshakeReply` applies the peer's `ackFreq` unclamped;
post-authentication, so a legitimate server only). `ZstdDictCodec` on hostile compressed payloads. Path migration
and key update driven by fuzzed input. And no coverage measurement was taken, so "44 488 authenticated frame
bodies" is a count of inputs, not a statement about which branches of `parseFrames` ran.

Replay: `-Dtessera.fuzz.seed=N` pins a single seed, `-Dtessera.fuzz.iterations` / `-Dtessera.fuzz.endpoint.iterations`
set the sweep size (the committed defaults sit inside the ordinary suite: ~10 k core cases per entry point, 600
endpoint cases per seed).

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
| Receiver-dropped messages credit the window instead of leaking it: 40 messages offered through a window only four wide, every one refused by the reassembly byte budget, sender still finishes and `limit <= consumed + abandoned + window` holds — both datapaths | `transport FlowControlTest.receiverDroppedMessagesCreditTheFlowWindow` |
| The credit rule itself: running maximum per abandoned id, exact once the fin arrives, clamped to one max-size message, bounded ledger that stops crediting rather than double-counting, abandoned messages never revive, contradiction drops credit nothing | `transport ReassemblerTest`, five cases |

The leak-credit test has teeth: with the abandoned term removed from the advert the sender gets 4 of 40 messages
through and then hangs (2026-08-25), which is exactly `recvWindowBytes / maxMessageBytes`.

Not covered, deliberately: the leak still stands when a shared-dictionary codec is negotiated (wire bytes are not
app-payload bytes, and an expanding encode would over-credit — the unsafe direction), and for a `codec.decode`
failure, which happens past the reassembly accounting; see SPEC's v0.8 non-goals. A dead peer under an
active flow block is covered by design review only
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

### F8a outcome (2026-08-25, in-process) — the prediction inverted

Measured (LedbatCoexistenceTest: RFC 6817 flow with slow start over the CubicFlow scaffolding, one-way delay
exact via the shared clock; LTE-shaped 30 Mbit / 90 ms bottleneck; scavenger first, Tessera bulk joins 6 s,
leaves; standing queue 1 BDP / 0.25 BDP — the sim's `limit` also holds ~135 propagation-stage packets, so the
limits are 405 / 202): **Tessera is the more timid scavenger.** LEDBAT keeps 57% of solo at 1 BDP (23% at
0.25 BDP) and recovers fully; Tessera trickles at 0.04–0.06 MB/s — the same yielding posture as F8b's
shallow-contested arm, now confirmed against a flow *designed* to get out of the way. Asserted: liveness both
directions + LEDBAT's recovery; shares recorded, no threshold. The send-rate-ceiling mitigation is NOT built:
it existed to bound Tessera's bullying, and there is no bullying to bound. The open policy question is the
opposite — whether Tessera should claim more of a contested link (the ceiling becomes relevant only then, as
the counterweight). Full numbers in BENCH-netem "F8 remainder".

### F8 fairness policy — DECIDED 2026-08-26: scavenger by default, and why

The policy this plan deliberately left open ("no pass/fail threshold until someone sets a policy") now has all
the evidence it was waiting for, so here is the decision and its reasoning.

**What was measured.** Against loss-signalled competition Tessera takes a minority share and, under pressure,
a trickle: 0.46–0.57 MB/s against CUBIC on a deep buffer with the neighbour keeping ≥78 % of solo; a trickle in
the shallow regime; and 0.03–0.06 MB/s against a LEDBAT scavenger that itself keeps 30–45 % of solo and
recovers fully. Where congestion is signalled by *marks* rather than loss, the picture inverts completely:
over a step-marking AQM, Tessera finished 3× faster with 23× fewer forced drops than the identical drop-only
queue. So Tessera is a strict scavenger against loss-signalled flows and a first-class citizen wherever ECN is
deployed.

**The decision: keep the scavenger posture as the default.** Three reasons, in order of weight.

1. *It is the design goal, not a shortfall.* SPEC's target table names "no standing queue" as the thing
   Tessera does differently from a transport whose CC probes build queues. A transport that declines to fill
   buffer will lose a throughput contest to one that fills it — that is arithmetic, not a defect. CUBIC and
   LEDBAT both win their share by occupying queue Tessera deliberately leaves empty.
2. *It is the safe side of the interaction.* No measured configuration starves a neighbour. The failure mode
   that would matter to a deployment — Tessera crowding out the video call on the same uplink — does not occur.
3. *Claiming more share means re-opening the worst defect this project has had.* The v0.9 dead-credit governor
   fixed congestion collapse precisely by letting the credit target retreat when credit dies in flight. Raising
   the floor so Tessera holds more of a contested link works against that mechanism directly.

**The cost, stated plainly.** Bulk transfer over a loss-signalled contested bottleneck is the regime where this
default is inadequate: 0.04 MB/s is not a usable bulk rate, and no amount of patience makes it one. Interactive
and small-message traffic — W1, the actual design target — is unaffected, because it does not need share to
meet its latency budget. A deployment that wants Tessera to move bulk across a contested last mile should
expect to change this, and should know it is trading queueing delay for it.

**The lever, for whoever revisits it.** `ReceiverCredit.floorBytes` (currently a constructor default of
10 × `MAX_DATAGRAM`, *not* plumbed through `ConnConfig`) is how far the governor may retreat; bounding the 0.9
per-tick decay is the other half. Any change there must be gated on re-running `CoexistenceTest`'s three arms,
`LedbatCoexistenceTest`'s two, and above all the **solo control arm** — the solo arm is what catches a
re-collapse, and it is the reason the collapse was found in the first place.

**Not built, deliberately:** the send-rate ceiling this plan originally proposed as F8a's mitigation. It
existed to bound Tessera's bullying of a scavenger; measurement inverted the premise, so there is nothing to
bound. It becomes relevant only as the counterweight to a future decision to claim more.

### F8b — versus ordinary TCP (CUBIC)

The neighbour's video stream, or any other TCP flow on the same uplink. The interaction depends on the queue:

- **Deep buffer** — TCP fills it, queueing delay appears, Tessera's gate engages, and behaviour should be
  reasonable. This is the benign case and the one most likely to be tested by accident.
- **Shallow buffer or AQM** — loss arrives *without* sustained queueing delay, which is precisely the signal
  Tessera is built to ignore. This is where it may take more than its share, and it is the case that must be
  measured deliberately because it will not show up otherwise. *(AQM answered 2026-08-25: with
  `NetemSim.ecnThreshold` step marking and the CE path wired end to end — rx consume → credit target shrink →
  ACK echo → HybridCc engage — a marking AQM turns Tessera into an ECN-native citizen: AqmEcnTest's marking
  arm finishes 3× faster with 23× fewer forced drops than the identical drop-only queue. The drop-only
  shallow queue remains the F8b-measured scavenger regime.)*

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

### F8b fix campaign (2026-08-24) — partial: the damage-bounding layer landed, the funding source is named and open

What landed (all conditional on evidenced congestion; dormant on healthy and radio-loss paths — full suites green
both datapaths, timing sentinels green isolated, lte bench inside the v0.8 band):

- **Shortfall-driven engagement.** `ccLoss` classifies a loss as congestion on *persistent delivery shortfall
  with nonzero flow* — 6 consecutive rate windows where the delivery EWMA is under 80 % of the send EWMA; a
  zero-delivery window resets the count (blackout, not congestion — protects F9's `outageDrainBudget`), a
  blocked-sender window holds it (collapse blocks the sender; its silence is not health). The verdict goes to a
  new `HybridCc.onCongestionLoss` (mirrors ECN-CE), bypassing the internal `srtt − minRtt` gate that is
  structurally blind to bimodal tail-drop queues. Engagement is hysteretic (renewing lease + 16-srtt memory).
- **Engaged-only regulation of the repair machinery**: reactive repairs (previously 4 *per ack*, no window
  check), verbatim/feedback re-sends, tail repairs and queue drains obey `cc.canSend` plus a delivery-rate pacer
  at 1.1 × the windowed delivery EWMA (`ConnStats.repairsGated`). The estimator's own
  `deliveredBytesPerSec` was ack-clump-inflated by orders of magnitude and unusable for pacing at the time
  (fixed 2026-08-25, see below; the transport keeps its own EWMA regardless).
- **FEC-feed freeze while engaged** (both sides of the observation): congestion drops no longer pin
  `fecRedundancy()` at its 0.5 cap.

What the campaign measured, and why the constants are what they are:

| experiment | result |
|---|---|
| starved threshold 2 windows, raw ratio | engagement a run-to-run coin flip (0 %–100 % of losses classified): acks lag a burst ~2 rtt, post-burst windows read healthy and reset the evidence |
| threshold 2, EWMA ratio | wifi-busy falsely engaged (jitter/reorder misalignment): pacing a healthy link fed a spurious-loss storm — p99 945 ms vs a 369 ms bound, `throttled=28013`; post-blackout catch-up also engaged and zeroed the outage drain (F9) |
| threshold 6, EWMA ratio (**landed**) | every sentinel green: genuine collapse starves indefinitely, both look-alikes are 1–4-window transients |
| engagement + pacing + freeze alone | insufficient: sends spray *between* engagement coverage (a lagging window detector cannot catch an instant burst), ~93 % of accepted messages permanently lost, MaxData window leaks to exhaustion, sender dead at 16 MiB charged |
| **credit growth cap, 2 × measured BDP** | **collapse fixed outright: solo 2.01 MB/s of 2.5, zero drops, no CUBIC needed** — but breaks slow-start's bootstrap contract (`receiverCreditReachesBdpAtHighRtt`, `cumulativeGrants…`: during ramp the rate is small *because* the credit is small) |
| credit growth cap, 8 × measured BDP | solo 1.62 MB/s, core contracts green — but grant-blackout recovery flaked ~50 % (the stall collapses the rate EWMA, the cap pins at the floor) and the equilibrium varied 0.13–1.62 across runs; reverted |

### The growth rule, swept deterministically (2026-08-28) — the trade is real, three fixes refuted

Every F8b measurement above was a real-time netem run, on a harness whose variance this project records at up to
17x. The growth rule does not have to be measured that way: `ReceiverCredit` takes its clock as a parameter and
has no other wall-clock dependency, so it can be driven under a **virtual clock** against a modelled tail-drop
bottleneck. Same inputs, same outputs, every time; a full sweep costs milliseconds. `CreditGrowthSweepTest`.

What a model cannot tell you is whether the model is the network — this one is a tail-drop queue with an unpaced
sender and no CUBIC, i.e. the F8b shape and not a link. So it is used only for questions it can answer honestly.
The first is whether the campaign's recorded trade is real. It is, exactly:

| growth cap | clean 180 ms bootstrap (`receiverCreditReachesBdpAtHighRtt`'s scenario) | shallow 20 Mbit bottleneck, 75 KB queue |
|---|---|---|
| 2x | **64 % of offered — fails the contract** | 24.3 MB delivered, 23.6 % loss |
| **4x (ships)** | 97 % — passes | 5.8 MB, 34.2 % |
| 8x | 99 % | 2.9 MB, 54.4 % |
| 16x | — | 1.7 MB, 71.9 % |

The two requirements pull opposite ways and the shipped 4x is the midpoint. Note what that table also says: on
this shape the shipped cap delivers **a quarter of what 2x delivers**, and 2x reaches ~97 % of link capacity —
which is the same direction and magnitude as the campaign's own live result for 2x ("solo 2.01 MB/s of 2.5"),
from an independent method. Two other shapes: a *deep* queue absorbs everything up to 8x (zero loss, identical
goodput), and a narrow uplink never binds the cap at all (BDP below the floor).

**A discrepancy found while reading, not measured:** `tick()`'s comment argued for "8x, not 2-4x", citing
wifi-busy crawling at a 4x cap — against a constant that has been 4 since it was introduced, and against the
campaign table above, which rejected 8x. The code shipped one value while its comment argued for another.
Corrected. **4x has never itself been measured against a link**; it is the midpoint that keeps the contract.

**Three candidate fixes, all refuted by the sweep:**

1. *Tighten the cap once dead credit appears* (8x -> 2x on evidence). Bootstrap 99 %, bottleneck **49.2 % loss** —
   worse than shipped. The evidence arrives too late: `tick()`'s own design note already says a deep queue
   absorbs an evidence-free probe until the buffer is full, and the sweep priced it.
2. *Tighten once the rate estimate settles* (the honest version of the same idea — the cap must be loose only
   while the estimate lags). Bootstrap 99 %, bottleneck 43.0 % loss. Better, still worse than shipped.
3. *Probe additively once settled* — bound the growth **step** rather than the ceiling, since the overshoot is a
   property of the step. Changed **nothing at all**: under tail-drop loss the rate EWMA bounces more than 1.25x
   window-over-window indefinitely, so "settled" never latches. A trigger that never fires is not a conservative
   trigger, it is an absent one.

The common failure of (1) and (2) is worth stating because it constrains what a fix can look like: **a ceiling
only binds once the target has reached it, and by then the overshoot is already in the queue.** Any rule that
reacts to congestion after the fact loses to a rule that never grows that far. That points at the step, but (3)
shows the step cannot be gated on a rate estimate that loss keeps agitating.

All three are in the code behind parameters that default to off (`growthCapTightBdp`, `tightenOnSettledRate`,
`additiveWhenSettled`), so the next attempt starts from a sweep harness and three eliminated branches rather
than from the beginning. **The item stays open.**

**The named funding source (KNOWN OPEN, `core/CreditControl.kt`):** `ReceiverCredit`'s slow-start doubling
treats a blocked sender as demand and grants the 8 MB ceiling within ~150 ms of saturation — on a saturated
tail-drop bottleneck the sender always looks blocked (its packets leave; they die in the queue). The fix that
works is capping growth by the *measured receive rate* (the one signal congestion cannot inflate); making that
coexist with the two contracts it broke — doubling must outrun the rate measurement during slow start, and must
survive a grant blackout whose stall collapses the rate EWMA — is a growth-rule redesign needing a proper
parameter/seed sweep, not a constant. Until then the solo/deep collapse stands as measured above, bounded in
blast radius by the landed layer (repairs no longer free-run, FEC no longer pins, shallow-regime engagement
covers 50–80 % of losses).

**Close can drop the final message (2026-08-25, OPEN — seen twice, unresolved):**
`NetemTest.sendThenCloseDeliversEveryMessageOnBothDatapaths` failed twice on 2026-08-25 with an identical
signature — native datapath, wifi-busy, seed 12, **1 of 600 undelivered and always the last one** (msg 599),
once during the MaxData work and once during the soak work. It passes **6/6 in isolation** and only fails
under full-suite load, yet it lives in the deterministic suite, so load-sensitivity there is itself the smell.

Mechanism hypothesis, not yet confirmed: the final message is the one packet whose loss RACK cannot detect,
because RACK needs a *later* packet to be acked and there is none. Detection therefore falls to the PTO. Close
lingers until `!lingerNeeded()` ("nothing it sent needs re-sending") or `closeLingerMs`; if `lingerNeeded()`
reports nothing outstanding *before* that PTO fires, `finishClose()` drops the tail permanently. Under load the
PTO slips and the window widens, which is why load exposes it.

**Investigated 2026-08-26, not reproduced; three mechanisms ruled OUT.** It did not reproduce under eight CPU
burners (3 runs, 600/600 each), which suggests the trigger is *in-process* contention during a full-suite run —
other tests' NetemSim scheduler threads competing — rather than CPU starvation as such.

- *Not* linger-bounded recovery. A probe ran the same shape with `closeLingerMs` at 10 s / 1 s / 300 ms /
  100 ms / **30 ms**: 600/600 delivered at every setting. Tail recovery in this regime completes from repairs
  already in flight, so shortening the linger does not drop the tail and the original hypothesis (linger ends
  before the tail's PTO fires) is not supported.
- *Not* the `lingerNeeded()` gap it assumed. The predicate already returns true on `bytesInFlight > 0` and
  `lastDataPn > largestAcked`, so a genuinely unacked final source holds the linger open.
- *Not* a poisoned RLNC solve. The recovery loop deliberately delivers before marking ("a symbol that does not
  parse stays undelivered so a verbatim re-send can still bring it"), so a bad solve cannot mark a seq
  delivered and cause the re-sent source to be skipped.
- Reassembly refusal is also out for this test: at 1200 B and PLPMTU 1350 the messages are single-fragment
  (`src` ≈ `count`), so the reassembler is not on the path at all.

**Hunted again 2026-08-26 and still not reproduced — ~34 deliberate attempts, all clean:** 8 consecutive full
`cleanTest test` runs (the exact context of both sightings), 12 iterations of the same scenario running against
four other live NetemSim connections churning in the same JVM (the in-process contention the full suite creates),
3 runs under eight CPU burners, 6 isolated runs, and 5 `closeLingerMs` settings. Zero drops. Two sightings in
roughly 23 full-suite runs puts the rate near 1 in 11, so eight clean runs lowers the estimate without clearing
it — this is rare, not absent, and must not be written off.

Two explanations survive: the message is dropped for good, or it lands after the test's own 15 s read deadline
under load. Rather than keep spending the machine on a 1-in-11 event, **the test now diagnoses itself**: on a
miss it keeps reading for a further 20 s and reports either `lateArrivals=NONE (dropped for good, not a patience
problem)` or the exact arrival offsets. Combined with what the failure text already dumps — both `ConnStats`,
so `skipDelivered`, `fec(lowestUndelivered, largest)`, `close(sent/rcvd)`, the re-send counters, and the
`HZN-ASSUMED` tripwire that would fire if the DELIVERED_BITS horizon assumption were ever exercised — the next
occurrence should name its own cause instead of restarting the hunt. Capture it in full; do not grep it down.

**Hunted again 2026-08-27 — 8 more full-suite runs, still not reproduced (~42 deliberate attempts total).**
At the estimated 1-in-11 rate, eight clean runs happen ~47 % of the time with the defect fully present, so this
lowers the estimate and clears nothing. All four arms delivered 600/600 on every run.

Two process failures during that hunt, both worth recording because each produced a *false clean result*:
- The first eight-run batch **never executed a single test**. Killing the previous hunt orphaned a test JVM that
  kept `transport/build/test-results/test/binary/output.bin` locked, so every run died in ~2 s on
  `:transport:cleanTest` with `Unable to delete directory`. The shell loop still exited 0. Fixed by
  `./gradlew --stop`, confirming no `java.exe` survives, and adding `Unable to delete` to the grep the hunt
  watches. **A full-suite run that finishes in seconds is a failed run, not a fast one.**
- Gradle does not show test stdout, so "BUILD SUCCESSFUL" alone does not prove the close test ran. Confirm it
  from `transport/build/test-results/test/TEST-tessera.transport.NetemTest.xml` (tests/failures counts and the
  `close ...` lines are both in there) before believing a clean hunt.

**What landed instead: teardown forensics.** Stats are read after everything settles, so they cannot show what
was outstanding AT the teardown instant — the one thing separating "the sender closed too early" from "the
receiver tore down on a CLOSE while recovery was still in flight". Two counters now record it as it happens and
print only when non-zero (`ConnStats`):
- `closePeerUndelivered` — sender side, at `finishClose`: `nextFecSeq - peerLowestUndelivered`, i.e. how many of
  our own fec seqs the peer had not yet reported delivered when we announced the close. `-1` when the peer never
  sent FEC feedback, so an absent signal is not mistaken for a clean one.
- `peerCloseHole` — receiver side, at `onPeerClose`: `largestFecSeen - lowestUndeliveredFec + 1`, what we were
  still missing when the CLOSE made us free state.

The next sighting should therefore name its own mechanism. The standing hypothesis, consistent with every clue
that survived 2026-08-26 (always msg 599, always native, only under full-suite load, and a 30 ms linger still
delivering 600/600 — so *not* the sender's linger), is that the receiver frees state on a CLOSE that overtakes a
repair which would have recovered the tail; the native datapath's batched rx makes that ordering-sensitive in a
way the channel path is not. Unconfirmed, and deliberately not acted on: no fix should land for this until a
sighting says which counter fired.

**The high-BDP credit famine (2026-08-25) — FIXED same day** (BENCH "The high-BDP credit famine"): the
accessory machinery's uncharged-but-counted credit spend dug multi-MB holes past the limit; once repairs
healed the gaps the dead-credit EWMA read HEALTHY, and the healthy release branch (`real/3`, no floor)
released ~nothing against zero flow — the v0.9 trickle's deadlock, one branch over. (The decoder-rotation
onset correlation was coincidental — the onset tracked where the storm's overshoot peaked.) Fix, five measured rounds (BENCH): the held-gap pool
drains at `max(floor, heldGap/8)` per window only under three keys — healthy + stall-shaped window,
transport-reported fully-caught-up, and 3 gap-quiet windows (stale deaths only) — else exact v0.9
semantics. Rejected en route, each with numbers: a hard credit gate on repairs (tighter deadlock — repairs
ARE the credit engine) and both under-guarded drains (10x contested aggression; LEDBAT crushed to 9-17%).
The old 5 s creditWaitMs bound used to convert this into "send blocked for 5000ms (GRANT_LIMITED)" — the
live 5G error was the famine, not the radio. Delivery asserts restored; core pins the release rule.

Secondary defects the campaign surfaced: permanent message loss when the loss backlog exceeds the
BODY_RING (4096) / DELIVERED_BITS (8192) horizons — **FIXED 2026-08-25** after W2 measured it as a full wedge
(sender-side horizon wait `nextFecSeq − peerLowestUndelivered < BODY_RING`; SPEC "The reliability horizon";
BulkTransferTest's transcont arm asserts complete delivery with `resendEvicted == 0`); `PathEstimator.deliveredBytesPerSec`
inflated by ack clumping — **FIXED 2026-08-25**: the estimator now accumulates delivered bytes over windows of
max(srtt, 10 ms) and publishes at window boundaries with a 0.5/0.5 EWMA, the same shape `ReceiverCredit.tick`
and the transport's pacer already use. `AckPathTest.deliveryRateSurvivesAckClumping` feeds 1 MB/s as clumps of
100 acks 10 µs apart and pins the published rate at 1 MB/s ± 20 %; against the old instantaneous code it read
100 MB/s (100x). The transport's own pacer EWMA is left in place — it is load-bearing for F8 and verified by
wall-clock tests. Still open: `pmtud = false` makes 1200 B messages two fragments
(quadratic loss sensitivity — test configs should size messages under `bodyMax`).

### F8b campaign, round two (2026-08-24, later) — the collapse is FIXED: dead-credit-governed growth (v0.9)

The named funding source got its redesign the same day. The insight that unlocked it: **gap credits are the
receiver's direct congestion observable** — they are literally bytes the sender charged that died in flight, a
few percent on any radio profile, 50–80 % under collapse, and nothing the sender or a queue can inflate. The
growth rule and the crediting rule are both governed by it now; `docs/SPEC.md` v0.9 has the mechanism. The
campaign's second experiment table, each row a measured failure that shaped a rule:

| experiment | result → rule it produced |
|---|---|
| target-freeze alone at ≥25 % dead | sender unmoved: gap credits slid the limit at the death rate — **held-back death** (credited on delay, not instantly) |
| unconditional real/3 release of held death | a designed-in permanent 1.33x overload — **release only while healthy** |
| zero release while unhealthy | deadlock: no flow → silent windows → evidence frozen → no release — **floor-quantum trickle**, whose own deliveries regenerate the healthy windows (a smaller trickle was eaten whole by the PTO/tail-repair background, which charges credit without blocking on it) |
| fills discarded at window rolls | jitter reordering (wifi-busy) read as ~35 % dead, decay starved a healthy link to 60 KB targets — **carry the fill balance across windows** |
| evidence-only spray control (storm freeze + gentle probe, no cap) | a deep queue absorbs the probe silently; dead credit appears only once the buffer is FULL — 47 % drops from cyclic re-floods — **the 4x-real-BDP cap is not redundant**: it keeps the target away from queue capacity where evidence is structurally late |
| 8x cap | admitted spray big enough to re-enter drop-freeze; contested arms hit send() timeouts — **4x** |
| 2x cap | crawl fixed point on jitter links + slow-start bootstrap deadlock — the reason a plain cap failed in round one; at 4x with honest (reorder-corrected) dead credit, both contracts hold |

Result: solo arm **0 → 2.01 MB/s of 2.5 with zero drops** (now asserted in CoexistenceTest at 1.0); deep-buffer
contested 0.46–0.57 MB/s with the CUBIC neighbour at ≥78 % of solo and full recovery; shallow-contested Tessera
yields to a trickle — scavenger posture, the safe side of the fairness policy that remains deliberately open
(a contested-shallow send() historically hit the 5 s creditWaitMs timeout; since the E5 `closed` fix that
bound is amp-only and the send simply waits against the audible peer). Every suite green on both
datapaths; the timing sentinels green in genuine isolated runs (OutageDrainTest kept its pre-existing ~1-in-5
paired-A/B variance; the 2000 msg/s test its documented under-full-suite-load flake); in-process lte bench
inside the v0.8 band. The round-one engaged-CUBIC layer stays as the backstop for regimes the credit governor
misjudges (it is what makes the shallow-contested arm degrade gracefully instead of flooding).

### F8 follow-up (2026-08-25) — the PTO backoff now needs forward progress to reset

The recorded loose end from the campaign: `tlpBackoff` was cleared by *any* newly-acked packet, so on a congested
path that still returned some traffic — an ack for a repair, a re-send, a stale probe — the next PTO fired at the
base timeout with a full `PTO_TRAIN + 1` train, and the exponential that is supposed to stop a struggling path
being probed harder never engaged. The reset is now conditional on forward progress past the outstanding probe
(SPEC "PTO schedule"); it is a correctness rule about what an ack proves, so it is not gated on `HybridCc.engaged`
— gating there would have left the same stray-ack reset in place on every path CUBIC has not engaged on.

| Case | Covered by |
|---|---|
| An ack below the outstanding probe's first pn leaves the backoff standing; three such PTOs in a row reach backoff 3 | `transport TlpBackoffTest.ackBelowTheOutstandingProbeLeavesTheBackoffStanding`, `...strayAcksUnderCongestionLetTheBackoffGrow` |
| An ack reaching the probe, or covering data sent after it, resets to 0 and disarms the mark | `...anAckReachingTheProbeResets`, `...ackForDataSentAfterTheProbeAlsoResets` |
| With no probe outstanding any ack still resets (the old behaviour, preserved) | `...withNoProbeOutstandingAnyAckResets` |

Deterministic policy tests only: the counter's effect on wall-clock probe timing belongs to the quarantined timing
suite (`OutageTest`, `OutageDrainTest`, `RecoveryTest.grantBlackoutResumesWithinOneResendIntervalAndNeverStallsAgain`),
which is where a regression would show. Blackout recovery is unaffected by construction — during a blackout no acks
arrive at all, so both rules back off identically, and the first ack after recovery covers post-probe pns.

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

**Paced drain landed (2026-08-25):** the budget is still granted in full but released into the token bucket
at `burst/srtt` (PathState.drainReserve) — the hole drains in ~one RTT, the uplink never sees the clump.
Paired A/B, four isolated runs (same harness as above; metered arm's tail varies run to run, the drained arm's
does not):

| | p50 | p95 | p99 | p99.9 | max |
|---|---|---|---|---|---|
| metered (range) | 44.5 | 47.5–47.8 | 415–459 | 772–1546 | 810–1883 |
| drained+paced (range) | 44.5 | **54.6–64.1** | 345–355 | **441–473** | 621–657 |

The p95 cost fell from +41 ms (the one-clump burst's 93.3) to +7–16 ms while the tail improvement held in
full — the trade the original caveat predicted pacing would buy. Both arms 16,000/16,000 in every run.
`outageDrainMinRun = Long.MAX_VALUE` still restores pure metering.


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
full idle timeout (10 s at the time; 60 s since keepalive shipped). Stateless reset (RFC 9000 §10.3 shape, `core/StatelessReset.kt`, see `docs/SPEC.md`) closes
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
- **A cold JVM** — the first connect costs 328 ms (pure JDK) / 580 ms (native) against 10–16 ms warm, and it is
  not the network. Nor is it what we said it was for months: "class loading and the first ML-KEM operation"
  charged the KEM with ~180 ms that an ordering control showed belongs to BouncyCastle's *first class load* —
  a SHA-256 digest pays it in full, and ~120 ms of it is verifying the signed bcprov jar. ML-KEM's own first-use
  cost is 25–35 ms. Discard warm-up connects and say how many; see BENCH-netem, "Cold start, characterised".
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
4. **W2 bulk** — done 2026-08-25 in-process (bench `bulk` + BulkTransferTest); its first run surfaced the
   reliability horizon as a measured wedge, which is the argument for doing the horizon fix next.
5. **F8 coexistence** — F8b ran in-process (2026-08-24): the neighbour is safe, **Tessera collapses on any
   saturated bottleneck** — which promotes the W2/bottleneck congestion work above it; F8a (LEDBAT) and the tc
   variant stay open.
6. **E4 at full scale**, once the harness has proven itself.
7. **L2 and beyond** — implementation work, once there is a real-network baseline to regress against.

Buy the cheapest information first, and never let an implementation change land without a measurement that would
have caught its regression.


## IPv6 delivers nothing above ~400 B (2026-08-27, OPEN — found on the first live IPv6 test)

The transport is dual-stack and unit-tested as such, and no live measurement had ever run over IPv6 — the mesh
harness never requested it (`enable_ipv6` was absent from `deploy`, and Vultr cannot add it afterwards: the
`POST /ipv6/enable` endpoint 404s). Adding it took under an hour and immediately found a defect.

Against a Los Angeles node reached over IPv6, from a wired connection:

| arm | payload | result |
|---|---|---|
| raw UDP over IPv6 | 1200 B | **200/200 delivered, 3 runs of 3** (p50 113-121 ms) |
| raw UDP over IPv4 | 1200 B | 200/200 (p50 75.6 ms) |
| Tessera over IPv4 | 1200 B | 200/200 (p50 75.3 ms) |
| **Tessera over IPv6** | **1200 B** | **0/400, 0/200, 0/60 — total failure** |
| Tessera over IPv6 | 400 B | 200/200 |
| Tessera over IPv6 | 200 B | 150/150 |

**The path is not the problem**: raw UDP carrying the same 1200 bytes over the same IPv6 address is clean three
times out of three. The handshake is not the problem either — both the fresh post-quantum connect and the
resumed connect echo their 0-RTT payload over IPv6 (247 ms / 118 ms), so small packets traverse fine in both
directions. It is the steady-state stream of large messages that vanishes.

Established:
- **Size-dependent, not rate-dependent.** 1200 B fails at 50 msg/s *and* at 5 msg/s; 400 B succeeds at 50 msg/s.
- **Both datapaths.** Fails identically with `-Dtessera.native=off`, so this is not the Rust I/O layer or its
  v4-mapped address rewriting.
- Occasional partial successes (150/150 at 1100 B with 136 packets lost and recovered by FEC, 1200 B at 700 ms
  p50 against IPv4's 79.9) are consistent with large packets being dropped and FEC sometimes covering the hole.

**Leading hypothesis, not yet confirmed.** IPv6 guarantees only a **1280-byte** minimum MTU, and grep finds no
reference to 1280 anywhere in this codebase. `BASE_PLPMTU` is 1200 — which is safe over IPv6 (1200 + 40 + 8 =
1248) — but a run that worked reported `plpmtu=1350(SEARCH_COMPLETE)`, and 1350 + 40 + 8 = 1398 exceeds 1280.
A 1200-byte *message* also carries framing and tag, so it exceeds 1280 on the wire where a raw 1200-byte
datagram (1248) does not. That arithmetic fits every observation, but the PLPMTUD state on a *failing* run was
not captured, so the mechanism is unproven: it could equally be that DPLPMTUD raises to 1350 on a probe that
succeeds and never demotes when data at that size is black-holed.

Next steps: capture `plpmtu` and the probe/loss counters on a failing run; check whether DPLPMTUD demotes on
sustained loss at the current size; and decide whether the base and the search ceiling should be
address-family aware.


### Correction, same day: that is mostly the path, not IPv6 support

The entry above concluded "Tessera delivers nothing above ~400 B over IPv6". Wrong, and the error is the same
one this file keeps recording: **a property of the path attributed to the transport.**

The missing control was raw UDP over IPv6 *at Tessera's actual offered load*. The original comparison ran UDP at
50 pkt/s against Tessera at 50 **messages**/s — but the counters show Tessera sending ~1560 packets for ~450
sources, a **3.5x packet amplification** from its low-rate repair overhead. Matched properly:

| arm | offered | result |
|---|---|---|
| raw UDP over IPv6 | 50 pkt/s | 400/400, 0 % lost |
| raw UDP over IPv6 | **175 pkt/s** | **180/400, 55 % lost** |
| raw UDP over IPv4 | 175 pkt/s | 400/400, 0 % lost |

**This provider's IPv6 path collapses somewhere under 175 pkt/s while its IPv4 path does not.** Tessera at
50 msg/s offers ~175 pkt/s, so it sits exactly in the failing region; raw UDP at 1x sits under it. The
address-family difference is real but it is the *network's*, not the transport's.

The 1280-MTU hypothesis is also **not supported**: both a succeeding and a failing run reported
`plpmtu=1350(SEARCH_COMPLETE)`, so 1350-byte probes were acknowledged over IPv6 and the path carries ~1398-byte
packets. PLPMTUD state was identical either side of the failure and is not the differentiator.

**What remains a genuine transport finding** is the amplification itself: at 50 msg/s Tessera puts ~3.5 packets
on the wire per source. **Measured on its own 2026-08-28** (`bench amp`, BENCH-netem "Low-rate packet
amplification"): 2.52 pkt/src at 10 msg/s and 2.39 at 50 against 1.38 at 200, with the tail repair accounting
for essentially one per source below ~1/T msg/s. `ConnConfig.tailRepairMinLoss` gates it on measured loss —
2.14 -> 1.16 pkt/src on cell-hotspot at 10 msg/s for +37 ms at p999 — and ships **off by default**. That is the per-message tail repair the `cell-hotspot` profile's KDoc already calls
fatal on a narrow uplink, and it is what converts a working link into a failing one wherever the path is
rate-limited rather than bandwidth-limited. It is the same low-rate overhead the repair clock exists to trade
against, seen from the other side.

Still genuinely untested, and the reason to keep an IPv6 node next time: whether Tessera behaves correctly on an
IPv6 path with *headroom*. Every IPv6 measurement so far has been taken on a path that cannot carry its offered
load, so "does the transport work over IPv6" is still unanswered rather than answered negatively.
