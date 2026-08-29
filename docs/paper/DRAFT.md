# Timer-Driven ARQ Has a Latency-Tail Floor That FEC Does Not: A p999 Measurement Across Deployable Transport Classes Under Identical Kernel Impairment

*Draft — arXiv cs.NI shape. Every number traces to `docs/BENCH-netem.md`; where a number could not be located it is marked [VERIFY]. Citations to be filled are marked [CITE: ...].*

## Abstract

We measure the deep latency tail (p999) of message delivery over a bursty-lossy emulated cellular link for one representative of each deployable reliable-transport class: kernel TLS 1.3 over TCP (CUBIC + SACK + RACK), kernel SCTP in unordered mode, QUIC (both an independent Java implementation, kwik, and Microsoft's production msquic under CUBIC and BBR), and Tessera, a research transport whose loss recovery is sliding-window random linear network coding (RLNC/FEC) with residual time-based ARQ. All arms run the same 1200 B echo workload at 50 msg/s under the same `tc netem` qdisc (LTE profile: 90 ms RTT, Gilbert–Elliott loss averaging ~4.8 % in ~5-packet bursts, 30 Mbit/s). At run lengths long enough to sample rare events (n = 20,000 messages per run), the p999 ladder spans two orders of magnitude by *mechanism class*: FEC-based recovery at ~0.31 s; production ARQ (msquic, either congestion controller) at ~3–6.6 s; kernel ARQ (unordered SCTP ~16.7 s, TLS/TCP ~21.7 s). Two alibis are eliminated: BBR and CUBIC are indistinguishable in msquic (so the tail is not the congestion controller), and unordered SCTP shows the same spiral without any head-of-line blocking (so it is not stream semantics). The mechanism is the lost retransmit: when the retransmission of a lost packet dies in the same loss burst, timer exponential backoff is the only exit, and timers cost seconds; FEC never needs a specific packet to survive. We additionally report two methodology findings that we believe generalize: (1) short runs systematically flatter ARQ transports — the same TLS arm measured p99 ≈ 0.61 s at n = 1,500 and ≈ 19 s at n = 20,000, because the effective sample size for a tail claim is the number of *runs*, not messages; (2) UDP GSO batching silently invalidates netem benchmarks — a qdisc drops a ~50-packet superpacket as one loss event, which alone inflated msquic's p999 by 3.4×.

## 1 Introduction

Reliable transports are compared, overwhelmingly, at the median — and at the median the transports compared here are nearly identical. On our impaired link, kernel TLS over TCP delivers a 1200 B request/response at a median of 120 ms, msquic at ~115 ms, and the FEC-based transport at 123 ms, all within a few milliseconds of the 103 ms raw-UDP floor. The differences that matter to interactive and real-time-adjacent applications live in the tail, and the tail is where measurement is hardest: the events that decide p999 are rare, heavy, and correlated, so both under-sampling and mechanism attribution errors are easy to make. This project's own measurement log contains several retracted tail claims, each made from a sample sized for a mean.

This paper makes one empirical claim and defends it against the obvious alternative explanations:

**Claim.** Under bursty loss, loss recovery driven by retransmission timers has a latency-tail floor measured in seconds, and recovery by forward erasure coding does not. The gap at p999 is a category (hundreds of milliseconds versus tens of seconds), not a percentage, and it survives giving the ARQ side its best case: a production implementation, its own benchmark tool, either of two congestion controllers, and message semantics with no head-of-line blocking.

**Mechanism.** At ~4.8 % bursty loss, a retransmitted packet occasionally dies in the same burst as the original. ARQ then has no signal but a timer, and retransmission-timeout (RTO) exponential backoff turns the second consecutive loss of the *same* data into a multi-second stall — in TCP's case additionally stalling everything queued behind it. FEC-based recovery is indifferent to *which* packets survive: any sufficient set of coded symbols reconstructs the window, so there is no distinguished packet whose repeated loss must be waited out.

The measurement platform is deliberately mundane — one Linux box per experiment, kernel `tc netem`, loopback — because the point is a controlled comparison of recovery mechanisms, not a characterization of any real radio. (The same project's live 5G-hotspot sessions, recorded in the same log, show why: the radio's non-stationarity exceeds most mechanism effects at feasible run counts.)

Contributions:

1. A p999 ladder across four deployable transport classes plus raw UDP under identical kernel impairment, at run lengths that actually sample the tail (Section 3).
2. Elimination of the two standard alibis — congestion control (BBR vs CUBIC in msquic: indistinguishable) and head-of-line blocking (unordered SCTP: same spiral) — isolating timer-driven ARQ itself as the mechanism (Section 4).
3. Two reusable methodology results: run-length sensitivity of ARQ tails (TLS p99 moved 30× with n), and the GSO/qdisc interaction as a validity threat to any netem benchmark of a batching sender, with before/after numbers (Section 5).
4. An unusually complete threats-to-validity section (Section 6), because the FEC-side transport is a research prototype built by the authors and the comparison is on its favoured workload.

## 2 Method

### 2.1 Harness

All headline numbers come from `bench vs` (and its n = 20,000 "p999 campaign" mode): four-plus transports, one machine, one loopback interface, one `tc netem` root qdisc, identical workload. Each arm echoes 1200 B messages at 50 msg/s, round-trip latency recorded per message, with handshakes completed before the measured window. Arms are interleaved within each repetition so that host drift lands on all arms alike. The arms are implementations, not protocol ideals:

- **raw UDP** — the substrate; loss shows up as loss. Calibration control.
- **TLS 1.3 / TCP** — JDK TLS atop the kernel's TCP (CUBIC + SACK + RACK).
- **SCTP** — the kernel's message-oriented L4, run **unordered**: per-message reliability, no cross-message ordering — its most Tessera-like mode, chosen to remove the head-of-line variable.
- **QUIC** — two implementations: kwik (independent, spec-derived Java; NewReno-class CC) and **msquic** (Microsoft's production QUIC, built from source, driven by its own `secnetperf` tool in 1200 B/1200 B request/response mode, ~50 req/s at this RTT via 5 concurrent streams), under **both CUBIC and BBR**.
- **Tessera** — the research transport: systematic sliding-window RLNC over GF(256) with adaptive redundancy, residual RACK-style time-based ARQ, receiver-driven credit congestion control (Homa-style) with a CUBIC fallback, Noise-IK/ML-KEM handshake. Unaudited prototype, v0 wire format.

The multi-box experiments run the same interleaved design detached on five cloud nodes in five regions (`ewr del blr sgp icn`), six repetitions per node.

### 2.2 The impairment

The deep-tail campaign uses one profile, `lte`, from `bench/netem/profiles.sh`, applied as the root qdisc on `lo`:

```
tc qdisc add dev lo root netem delay 45ms 15ms distribution normal \
    loss gemodel 1% 20% rate 30mbit
```

That is 45 ms one-way delay with ±15 ms normal jitter (so ~90 ms nominal RTT on loopback, where one qdisc sits on the egress of *both* directions), Gilbert–Elliott loss with p = 1 %, r = 20 % — an **average** loss of p/(p+r) ≈ 4.8 % in mean bursts of 1/r = 5 packets — and a 30 Mbit/s rate cap shared by data and acks. The first `vs` table (Section 3.1) additionally uses `wifi-busy` (8 ms ± 20 ms pareto jitter, 3 % random loss, 5 % reorder, 80 Mbit) and `transcont` (90 ms ± 2 ms, 0.1 % loss, 1 Gbit).

Two loopback caveats attach to every number: the effective RTT is ~2× the `delay` parameter, and an *echo* workload sends both directions through the same qdisc — each message crosses the impairment twice and the rate cap is shared.

### 2.3 Closed vs open loop

The Java arms (udp, tls, sctp, kwik, tessera) are **open-loop**: the sender paces at 50 msg/s regardless of completions, so a stalled transport accumulates backlog and the stall is charged to latency. msquic is driven by its own tool in **closed-loop** request/response (5 concurrent streams), which throttles offered load during a stall. Closed-loop measurement flatters a stalling transport — its worst seconds carry fewer in-flight messages — so this asymmetry favours msquic, and the FEC-vs-production-ARQ gap survives it. It is nonetheless a real difference and is carried in Section 6.

### 2.4 Run length, and why it is the method

The campaign's defining choice is n = 20,000 messages per run (~6.7 minutes at 50 msg/s), against the n = 1,500 (~30 s) used in the first-pass comparisons. A run's p999 then rests on ~20 observations instead of ~2. Section 5.1 shows this is not a refinement but a verdict-changer. Repetition structure: Tessera 24 runs, TLS 20, SCTP 13, kwik 5, msquic 3 per congestion controller (plus one failed rep per CC — a mid-run connection loss, recorded as tail data). The ragged counts are themselves data: the ARQ arms' own stalls kept blowing the per-invocation timeout guard, and when a guard fires it is the *last* arms in invocation order that vanish — the first pass starved the arms scheduled after kwik's collapsed-CC runs, and the completion pass reordered arms by importance.

Statistics are stated in paired or per-run form wherever possible (sign tests over interleaved pairs; per-run p999 medians and ranges across runs), never by pooling messages across runs: messages within a run are dependent — one loss burst poisons its neighbours — so the effective n for a tail claim is the number of runs.

## 3 Results

### 3.1 First contact, three profiles (n = 1,500 per run)

The initial `bench vs` table (2 runs/arm, one box) establishes shape, not magnitude:

| lte (~4.8 % GE loss) | delivered | p50 | p99 | p999 |
|---|---|---|---|---|
| raw UDP | 90–94 % | 104–110 ms | 143–150 ms | 153–161 ms |
| TLS 1.3 / TCP | 100 % | 118–124 ms | 487–614 ms | 598–700 ms |
| QUIC (kwik) | 100 % | 9553 ms | 28,911 ms | 33,307 ms |
| Tessera | 100 % | 124–129 ms | 208–339 ms | 241–522 ms |

On `wifi-busy` (3 % random loss + reorder) Tessera's p99 was 153–169 ms against TLS's 156–309 ms and kwik's 372 ms; on `transcont` (0.1 % loss, 180 ms RTT) even the 1-in-1000 loss doubles TLS's p99 (287–351 ms vs Tessera's 192–194 ms). The kwik lte row is disqualified on its face: a loss-based NewReno at 5 % random loss collapses its window and queues (9.5 s p50 *with 100 % delivery* is a transport patiently queueing behind itself) — it measures the CC, not QUIC-the-design.

### 3.2 Powered and multi-box: the TLS comparison

Eleven complete interleaved pairs on one box (one TLS run of twelve hung outright and was killed at a 300 s guard; another delivered 1113/1500): **Tessera's p99 beat TLS's in 11 of 11 pairs** (p999 likewise), one-sided sign test p = 4.9e-4; per-pair ratio median 2.83×, minimum 1.87×. TLS's cross-run p99 spread was 49.5× (451.5 ms–22,361 ms) — not noise masking the effect but the effect itself, at run granularity.

Five boxes, 30 interleaved pairs per profile:

- **lte:** Tessera vs TLS **30/30 pairs**, median 2.96×, minimum 1.45×, sign test p = 9.3e-10, and 6/6 on every node. Full delivery: Tessera 30/30 runs, TLS 26/30 (one run delivered 35/1500), kwik 23/30.
- **wifi-busy (the honest, narrower result):** Tessera vs TLS 25/30, median 1.25×, minimum 0.93× — TLS won 5 pairs, three on one node. Under light *random* loss, kernel CUBIC+RACK is at its best and the edge is a quarter, not a triple. Tessera vs kwik here is fair (kwik's CC is not collapsed): 30/30, median 2.07×.

### 3.3 The p999 ladder (n = 20,000, lte, five nodes)

The final table, both passes combined; every run's p999 rests on ~20 observations:

| arm | runs | full delivery | p50 med | p99 med | p999 med (range) |
|---|---|---|---|---|---|
| raw UDP | 20 | 0/20 (~5 % loss) | 103 ms | 145 ms | 159 ms |
| TLS 1.3 / TCP | 20 | 15/20 (worst 13,753/20,000) | 120 ms | 19,222 ms | 21,677 ms (4.9 s – 900 s) |
| QUIC (kwik) | 5 | 3/5 | 7,780 ms | 40,308 ms | 52,374 ms |
| SCTP (unordered) | 13 | **1/13** (worst 1,564/20,000) | 291 ms | 10,945 ms | 16,696 ms |
| Tessera | 24 | **24/24** | 123 ms | **209 ms** | **311 ms** (277–354) |

And msquic, same qdisc, GSO artifact removed (Section 5.2), 300 s runs, 3 completing reps per CC (one rep per CC failed with a mid-run connection loss):

| msquic rep set | p50 | p99 | p999 |
|---|---|---|---|
| CUBIC ×3 | 115–116 ms | 0.44–1.5 s | 3.0–6.6 s |
| BBR ×3 | 115 ms | 0.80–2.1 s | 4.3–6.4 s |

The ladder, in one column (run lengths differ across rows; the ordering is robust to that):

| transport class | p999 |
|---|---|
| FEC + residual ARQ (Tessera) | **0.31 s** |
| production ARQ (msquic, cubic or bbr) | ~3–6.6 s |
| kernel ARQ, no HOL (SCTP unordered) | ~16.7 s |
| kernel ARQ + HOL (TLS/TCP) | ~21.7 s |

**Delivery completeness is part of the result.** Tessera delivered 480,000/480,000 messages across the campaign — the only reliable arm that never dropped one. SCTP unordered completed full delivery in 1 of 13 runs (one run delivered 8 % of its messages); TLS in 15 of 20. Tessera's cross-run p999 range (277–354 ms, five machines) is tighter than TLS's p999 varied *within* a single run.

## 4 Why: the mechanism, with the alibis removed

**The lost retransmit.** At 4.8 % Gilbert–Elliott loss in ~5-packet bursts, the retransmission of a lost packet has a materially elevated chance of dying in the same or an adjacent burst. An ARQ transport then has no arrival to trigger fast retransmit / RACK reordering logic on *that* data; the only remaining signal is the retransmission timer, and RTO backoff doubles per attempt. Two or three consecutive losses of the same segment produce a multi-second stall by arithmetic, not by defect. This is the shape visible in every ARQ row: medians at the link floor, p99/p999 in seconds, and a worst-case TLS tail of 900 s where the sender could not sustain 50 msg/s under backpressure at all.

**Alibi 1 — "it's the congestion controller."** kwik's lte rows are exactly this, and are labelled so throughout. msquic exists in the experiment to answer it: its loss *recovery* (RACK-style + PTO) is shared between its congestion controllers, and **CUBIC and BBR are indistinguishable at the tail** (3.0–6.6 s vs 4.3–6.4 s). If the tail were window starvation, BBR — which does not read loss as congestion — would have moved it. It did not: the tail is the recovery, not the window. Production engineering is worth a real 4× (kernel-class ~20 s down to ~5 s), but the mechanism floor remains.

**Alibi 2 — "it's head-of-line blocking."** SCTP ran unordered: per-message reliability, no cross-message ordering, no stream to block. It still delivered fully in 1 of 13 runs with p99 ≈ 11 s. The spiral is in the per-message ARQ timer machinery itself; TLS shows the same spiral with HOL stacked on top (which is why TLS's p999 exceeds SCTP's, and why kwik — HOL-free but CC-collapsed — is worst of all).

**Why FEC has no such floor.** Tessera's repair symbols are linear combinations over a sliding window; recovering a burst of b lost sources needs any b independent equations covering the window, not the survival of b specific retransmissions. There is no distinguished packet to lose twice. The residual ARQ path exists (and its own low-rate physics — recovery ≈ burst-length × inter-message gap + RTT, measured at 150–300 ms on this profile — is precisely Tessera's p999 of ~311 ms ≈ 3.4× its median). The FEC floor is real but it is RTT-and-rate-scale, not timer-scale. Notably, the project's own log falsified an earlier internal claim that this tail was probe-timeout backoff: measurement showed the PTO fires ~3 times per 2,000 messages and is structurally unreachable while the application keeps sending; the tail is equation accumulation. The distinction matters here because it means the FEC transport's tail scales with message rate and burst statistics, while the ARQ transports' tails scale with timer backoff — different physics, hence the category gap.

## 5 Methodology findings

### 5.1 Run length: the effective n is runs, not messages

The same TLS arm, same box, same qdisc, same rate: p99 ≈ 610 ms at n = 1,500 (30 pairs multi-box: median 609.4 ms); **p99 ≈ 19.2 s at n = 20,000**, with 5 of 20 runs failing full delivery and one run's tail at 900 s. This is not a contradiction but sampling: a 30-second run rarely lives long enough to contain a lost-retransmit RTO spiral; a 6.7-minute run contains one almost surely. The early SCTP rows show the same shape. Short runs were flattering the ARQ transports — this project's recurring failure mode (tail claims from mean-sized samples, several retracted in the log) caught pointing the *other* way for once. Corollaries: (a) report per-run tail statistics and cross-run ranges, never pooled-message percentiles; (b) a comparison's run length must be sized to the rarest event either arm can produce, which for timer-backoff ARQ under bursty loss is minutes, not seconds; (c) when a timeout guard can kill an invocation, arm order decides who starves — order by importance.

### 5.2 The GSO/qdisc artifact: a validity threat to every batching-sender netem benchmark

msquic's initial runs under the qdisc showed 1–16 RPS, 10 s tails, and entire 300-second runs completing **zero** requests — numbers that would have slandered a production transport. The cause is not msquic: it batches sends into UDP GSO superpackets, and **a qdisc drops a superpacket as one unit** — one netem loss event at the stated per-packet probability kills ~50 wire-packets, and the rate cap serializes 60 KB monsters. The stated loss process is simply not the applied loss process. `ethtool -K lo tx-udp-segmentation off` does **not** fix it (msquic probes GSO with a live send that still succeeds; the kernel software-segments after the qdisc either way); the sender itself must stop requesting segmentation (here: patching the datapath to honour `MSQUIC_NO_GSO`). Same 30 s smoke test, GSO on vs off: **p999 1.7 s → 505 ms — a 3.4× pure artifact.** The standing rule this yields: *any* netem measurement of a GSO-batching sender is invalid until segmentation is verifiably disabled at the sender — a zero-completion sanity run is the cheap tell. This applies to Tessera's own batched native datapath in future netem-on-Linux runs; the Java arms in this campaign all send per-datagram and are unaffected.

## 6 Threats to validity

Stated at full strength, because the FEC arm is the authors' own transport.

1. **One box per powered campaign.** The n = 20,000 ladder ran on five nodes for the Java arms but msquic's numbers are from one box (`ewr`); the 11-pair TLS result is one box. The multi-box lte result is box-independent (6/6 on every node), but the wifi-busy result is not (one node split 3/3) — heterogeneity a one-box experiment cannot see, demonstrated inside this very dataset.
2. **Echo doubles the qdisc.** Loopback puts one qdisc on both directions: every message crosses the impairment twice, acks and data share the rate cap, and the effective loss per round trip is roughly double the per-leg figure. All arms suffer it equally, but it is not any real link's topology.
3. **Message-shaped traffic favours the FEC design.** 1200 B independent messages at a fixed rate is Tessera's home turf. TCP and QUIC are stream transports being used message-wise; a bulk-stream throughput comparison would favour theirs and was not run here. Relatedly, Tessera's own bulk behaviour has an open unexplained item: `transcont` bulk sits at ~4 MB/s on a nominal 1 Gbit link with loss, cwnd, credit and flow control all shown non-binding — nobody can yet say why. The claim in this paper is a tail-latency claim about message workloads, nothing more.
4. **Closed-loop msquic vs open-loop everything else** (Section 2.3). The asymmetry flatters msquic; it also means msquic's per-message latencies are not sampled from an identical offered-load process. The category ordering is robust to it; the exact msquic magnitudes are not comparable to the open-loop rows at percentage precision.
5. **300 s msquic runs vs 400 s for the other arms.** Different run lengths under-sample msquic's rare events relative to the Java arms — again an asymmetry in msquic's favour, again unable to move a category boundary but able to move its numbers.
6. **kwik rows are congestion-controller collapse, not QUIC.** Labelled so throughout; the fair kwik comparison is wifi-busy only. The production-QUIC claim rests on msquic alone.
7. **Cross-pass rows are not pair-matched.** The final p999 table combines two passes; the number of shared-minute pairs is smaller than the row counts suggest. At these magnitudes (hundreds of ms vs tens of seconds) it cannot matter; at percentage-level differences it would.
8. **Tessera is an unaudited research prototype** with a v0 wire format, no interop evidence, no formal analysis beyond the Noise-IK provenance of its handshake, and a security assurance not comparable to TLS's regardless of any latency number. Its FEC overhead is also a real cost this paper does not charge against it: at 50 msg/s the low-rate configuration measured 1.14–2.37× wire bytes per payload byte depending on settings and profile, which on a metered or narrow uplink is the wrong trade (measured live: its overhead saturated a ~0.56 Mbit hotspot uplink that raw UDP fit).
9. **One profile at depth.** The p999 campaign is lte only. wifi-busy already shows the edge narrowing to 1.25× (and not universal) under light random loss; profiles are named after technologies they are not (a lesson this project's log records at length — its simulator's tail was itself wrong by 2–3× until a 4-sigma clamp matching the kernel's tables was found). The category claim is specifically about *bursty* loss.
10. **Emulation, not radios.** Live 5G-hotspot sessions in the same log show non-stationarity that exceeds mechanism effects at feasible run counts; nothing here predicts a specific radio.

## 7 Related work

- **QUIC loss recovery.** [CITE: RFC 9002, QUIC loss detection and congestion control] specifies the PTO/RTO machinery whose backoff behaviour under repeated loss is the mechanism measured here; [CITE: RFC 9000/9001, QUIC transport and TLS mapping]. Tessera borrows several QUIC wire mechanisms by specification (varints, truncated packet numbers, ACK ranges, header protection, key update, amplification limits, MAX_DATA-shape flow control — per its NOTICE provenance) while containing no code derived from any QUIC implementation and no BBR.
- **RACK.** [CITE: RACK-TLP, time-based loss detection for TCP] — the recovery style shared by the kernel TCP and msquic arms; this paper measures the regime where even time-based detection falls through to the timer because the retransmit itself is lost.
- **Receiver-driven scheduling.** Homa [CITE: Ousterhout et al., Homa: a receiver-driven low-latency transport] is the provenance of Tessera's credit-based congestion control, including grant re-issue on timeout — relevant here because a CC that does not read random loss as congestion is what keeps the FEC arm's median at the floor while kwik's collapses.
- **RLNC / sliding-window FEC.** [CITE: sliding-window random linear network coding literature] is the provenance of the recovery mechanism; [CITE: FEC-for-transport work, e.g. coding extensions proposed for QUIC] situates FEC-vs-ARQ trade-offs, to which this paper adds a deep-tail measurement under matched kernel impairment.
- **CUBIC / HyStart++.** [CITE: RFC 9438; RFC 9406] — the kernel arm's CC and Tessera's loss-based fallback, implemented from the documents.
- **LEDBAT.** [CITE: RFC 6817] — measured against Tessera elsewhere in the project's log (Tessera yields *harder* than the scavenger, inverting the pre-registered prediction); included as provenance for the coexistence posture, not used in this paper's ladder.
- **Handshake provenance.** Noise IK [CITE: Perrin, Noise Protocol Framework; Kobeissi & Bhargavan, Noise Explorer] and ML-KEM [CITE: FIPS 203]; orthogonal to the tail result but part of the measured system.

## 8 Conclusion

Under identical kernel-netem bursty-loss impairment, at run lengths that sample rare events, the p999 of message delivery separates by recovery mechanism into categories: ~0.31 s for FEC-based recovery, ~3–6.6 s for production timer-driven ARQ (indifferent to congestion controller), and ~17–22 s for kernel ARQ (indifferent to head-of-line semantics). The mechanism is the lost retransmit: ARQ's last resort is a backoff timer, and timers cost seconds; FEC's last resort is another equation, and equations cost a message gap. Production engineering moves an ARQ transport within its category — a real 4× — but does not move it out, because the floor is the mechanism, not the implementation. The result is bounded exactly as measured: one bursty-loss profile at depth, message workloads, emulation, and a prototype on the FEC side whose overhead and assurance are not free. The methodology results travel further than the transport does: tail claims need run-sized samples of run-sized events, and a netem benchmark of a batching sender is measuring a different network than it names.

---

*Reproduction: `bench vs` and `bench/netem/profiles.sh` in the Tessera repository; raw tables in `docs/BENCH-netem.md` ("The p999 campaign, final"; "Production QUIC at last"; "Five boxes, sixty pairs"; "Tessera next to TLS 1.3 and QUIC").*
