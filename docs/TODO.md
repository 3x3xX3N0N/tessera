# Open work

The single list. Before this file existed the open items lived across `SPEC.md`, `TEST-PLAN.md` and
`BENCH-netem.md`, and reconstructing them meant reading the code — which on 2026-08-28 turned up six claims that
had gone stale, including a "v1 open items" list that still named six shipped features and a defect marked open
three days after it was fixed.

Rules for this file, so it does not rot the same way:

- **Every item says what would settle it**, not just what is wrong. An item nobody can falsify is a mood.
- **Status is evidence, not intention.** "In progress" is not a status; "measured in-process only" is.
- **When an item closes, it moves to the bottom with its evidence**, because the argument is usually worth more
  than the outcome.
- Claims here point at the document that holds the measurement. This file summarises; it never becomes the
  source.

---

## 1. Receiver-credit growth rule — TOP DEFECT (the constant is measured; the rule is not)

`ReceiverCredit`'s slow-start doubling is funded by the *send* rate, which a tail-drop bottleneck inflates: the
sender always looks blocked because its packets leave and then die in the queue. The shipped growth cap
(`GROWTH_CAP_BDP = 4`) is a midpoint between two requirements that pull opposite ways. As of 2026-08-28 it has
been measured on a link and **is never worst**; what remains open is the growth *rule*, since a ceiling cannot
prevent an overshoot it only detects afterwards.

- **Evidence:** TEST-PLAN "F8b fix campaign" (live, 2026-08-24) and "The growth rule, swept deterministically"
  (2026-08-28). The deterministic sweep reproduces the campaign's trade exactly: 2x fails the bootstrap contract
  at 64 % of offered but delivers 4x more on a shallow bottleneck; 8x passes bootstrap and loses 54 %.
- **Refuted so far:** tighten-on-dead-credit (evidence arrives after the spray), tighten-on-settled-rate
  (same, less badly), additive-probe-when-settled (never fires — the rate EWMA never settles under loss). All
  three live behind parameters defaulting to off.
- **DONE 2026-08-28 — the cap is now measured on a link** (BENCH, "The credit growth cap, measured on a link at
  last"). Clean high-BDP half: 2x is 3x worse at p50 against a 20 % bar, the one fully resolved result; 8x halves
  p99 against 4x but only 91 % against a 129 % bar. Real shallow-bottleneck half: 8x is the only arm that lost
  anything and the only one with a multiple-sized spread (4.48x); 2x is the most stable and slowest. **4x is
  never worst**, in any of 75 runs — so the compromise survives contact with a link, and there is no evidence for
  changing it. Both directions the deterministic sweep predicted reproduced on hardware.
- **What is still open:** the *rule*, not the constant. A cap is a ceiling, and a ceiling only binds after the
  target has reached it — the overshoot is already in the queue by then (three refuted fixes above). 4x being
  never-worst is an argument for leaving it alone, not for believing it is right.
- **Fourth candidate modelled 2026-08-29: the queue-delay gate** (`growthDelayGateUs`, off by default; the first
  whose evidence arrives BEFORE the spray). Model verdict, three findings deep: (a) a minRtt/4 noise floor
  blinds it on high-RTT links (transcont's FULL queue is 47 ms against a 45 ms floor) — floor is minRtt/16;
  (b) gating on srtt blinds it wherever the ramp is fast — it reads the last raw sample (`PathEstimator.
  lastRttUs`); (c) with both fixed it is a **category win on shallow bottlenecks (54 % loss -> 0, delivered
  4.2x at cap 8x)** and exactly no-harm elsewhere — but on transcont it NEVER fires, because the queue fully
  drains between growth events and the overflow is a sub-RTT burst no RTT sample sees. **Delay-gating cannot
  see burst spray; only pacing can — and pacing measured 2.3x on kernel-netem transcont. The redesign is the
  pair: delay-gated growth + disengaged-path pacing.**
- **The pair modelled 2026-08-29 (`thePairAcrossShapes`): it beats or ties shipped-4x on every shape.**
  Shallow 34 % loss -> 0 with 4.2x delivered; narrow-uplink 4.1 % -> 0; transcont 37 % -> 32 % with +25 %
  delivered (better, not solved — calibration is monotone toward tighter pacing, 1.5x reaching 22 %); deep
  unchanged; **clean bootstrap intact** (peak 1.5x BDP, 91 Mbit average on a 100 Mbit link). Model-pacer
  caveat stated in the test: tick-quantized budgets are not the real pacer's continuous 8.0, so the model's
  2.0 working point does not transfer as a number, only as a shape.
- **Hardware A/B attempted 2026-08-29 and hijacked by a better finding** (BENCH, "The pair on a real path"):
  shipped-4x completed 0/3 twenty-MB transfers on the shallow shape (the model's spray prediction, worse), but
  the 5 MB config comparison was drowned by **the deep-outstanding stall reproducing at ~50 % in BOTH arms** —
  scl->syd + tbf 20mbit/64pkt + multi-MB push is the first on-demand recipe for TEST-PLAN §8's ghost. No
  growth-rule comparison on this path means anything until that stall is understood.
- **RETRACTED AND REPLACED 2026-08-30** (BENCH, "The discriminator ran, and refuted its own hypothesis").
  The 08-29/30 reading — "the credit famine again, escape hatch held shut by the `Connection.kt:1427`
  caught-up guard" — **was wrong**, and the dumps it called for are what refuted it: at the stall the sink
  reports `lowestUndelivered=4188 largest=4187 reassembling=0`, so **both legs of that guard are satisfied**.
  32 KB chunks do not leave a permanent hole; the RLNC decoder closes them. The guard needs no change.
  The real defect is item 12 below — the receiver abandons whole messages and never says so. The famine
  signatures on the sender are real but separable (rep1 GRANT_LIMITED 83.6 s, rep3 UNLIMITED 2.96 s): even
  with unlimited credit the abandoned bytes are gone.
- **Standing, unchanged, for a new reason:** no growth-rule comparison on the scl->syd path means anything
  until item 12 is fixed.
- **Watch item (2026-08-29):** one full-suite run showed `EndpointFuzzTest` fail with **5.19x amplification
  reproduced twice-quiesced, identical ratio all three times** — a deterministic-response signature, gone on
  the repeat run and in isolation. Hypothesis: a mutated LONG packet keeps the intact version word and a live
  ConnId, matches `byConnId`, and draws a handshake-reply retransmit (~218 B for 42 B) — the duplicate-initial
  resend path, which predates the version field. If it recurs, capture the input hex BEFORE re-running
  anything; the XML is overwritten by the next run, which is how this sighting lost its evidence.

## 2. NetemSim is not validated against hardware — NEW, and it undercuts a lot

The in-process simulator has been the primary instrument behind every congestion and repair decision in
`BENCH-netem.md`. On 2026-08-28 it was compared against real `tc netem` at matched parameters for the first
time and **disagreed**: the sim predicts a 1.5-8.4x repair-clock win on a capacity-limited fading link, and the
same configuration on real hardware (2 Mbit tbf, same GE loss, same rate, ~60 paired runs) shows nothing.

- **Evidence:** BENCH "Real netem on a real path" and "The radio misprediction, resolved".
- **Consequence, already applied:** a simulator-only result is a hypothesis, not a finding.
- **CHARACTERISED 2026-08-28** (BENCH, "NetemSim against the kernel"; `bench/netem/sim-vs-tc.sh`). Same machine,
  same JVM, same loopback, matched parameters, alternating arms. **The median is right everywhere** (1.00-1.17x)
  and **the tail is systematically overstated**, scaling with jitter heaviness: p99 1.04x transcont, 1.37x lte,
  2.14x 5g-mmwave, 2.67x wifi-busy.
- **It changes verdicts, not just numbers.** The same repair-clock A/B run in both worlds: the sim says 4.1x
  benefit, the kernel says 1.22x, because the sim inflates the clock-off arm 8.3x and the clock-on arm 2.5x. The
  error interacts with the mechanism under test, so it cannot be divided out.
- **Refuted:** that the departure clamp (`d = max(d, tail)`) causes it. `NetemSim.jitterAfterRate` implements the
  alternative and collapses the median instead (wifi-busy p50 88 -> 5 against the kernel's 72), so the kernel
  queues on jitter too. Kept as a documented dead end.
- **Open, and sharper:** the error is tail-only and concentrated on the two **pareto** profiles, which points at
  the jitter distribution — netem's tables are discrete and clamped, `sample()` is continuous.
- **ROOT-CAUSED AND FIXED 2026-08-28** (BENCH, "The tail error, root-caused and fixed"): netem's distribution
  tables saturate at 4 sigma; `sample()` clamped NORMAL but not PARETO. `link-sim-vs-tc.sh` (raw UDP, no
  transport) showed the raw link itself diverging 2.7x/3.3x on exactly the two pareto profiles — and the kernel's
  own numbers named it (p99 = p999 = delay + 4 x jitter, exactly). One `coerceIn(-4, 4)`: raw link converges
  exactly, transport tails converge on both pareto profiles.
- **Residual, narrower:** lte's transport p99 still ~2x while its raw link matches — a loss-model interaction.
  The sim's GE arm also loses 6.35 % (identical every rep, same seed) vs the kernel's ~4.7 % at `p=1% r=20%`.
  Next: compare the two GE chains' realised loss and burst-length distributions the same raw-link way.
- **Standing consequence:** a tail claim about a *mechanism* on a heavy-tailed profile is a hypothesis until a
  shaped link confirms it. `sim-vs-tc.sh` and `bench/mesh/shape.py` make that check routine.

## 2b. GSO invalidates netem measurements of batching senders — standing guard

Found 2026-08-29 via msquic (BENCH, "Production QUIC at last"): a qdisc drops a GSO superpacket as ONE unit
(~50 wire-packets), so any netem run whose sender batches with UDP_SEGMENT measures a different loss process
than the profile states. `ethtool` on the interface does not fix it; the sender must not request segmentation.
**Applies to Tessera's native Rust datapath** (batched sends) in any future netem-on-Linux run — verify with a
zero-completion sanity check before trusting such numbers. **Extended 2026-08-29 to TCP/TSO** (BENCH, "The fair
bulk comparison"): shaped TLS read identical to Tessera with TSO on and 4.2x-volatile with it off — the rule is
now *any segmenting sender*, and the harnesses disable tso/gso/tx-udp-segmentation on the shaped NIC.

## 2c. RESOLVED 2026-08-29 — not the famine; the node bulk rows measured CPU (was: bulk stalls 5/5)

The discriminator ran (BENCH, "TODO 2c resolved"): the W2 harness completes 3/3 under kernel netem with full
delivery — **the famine fix holds on hardware**. The clean control is the finding: 0.72 MB/s on the 1-vCPU
node against 39 MB/s on a desktop, so the "stalls" were CPU-bound timeouts, and every single-node bulk row
against a kernel transport conflates CPU with transport. Remaining actions: (a) DONE 2026-08-29 — `tessera bulksink`/`bulkpush` over scl->syd (BENCH, "The fair bulk
comparison"): unshaped parity with kernel TCP on the real 324 ms path, stability advantage under modelled
bursty loss (1.16x vs 4.2x rep spread), every hash MATCH; (b) the transcont forensics showed **16.6 % self-inflicted loss from the
unpaced disengaged-path dump** (0.1 % profile; burst mean 27.6 into netem's 1000-packet queue) — the
`paceDisengaged` item now has a kernel-netem reproducer and a 166x number attached to the shipped default.

## 2d. The premise audit — does the loss regime Tessera targets exist end-to-end? (external review, 2026-08-29)

kixelated's review (BENCH, "External review: the premise challenge"): random loss is not an internet thing;
bufferbloat is; FEC belongs at L1/L2. Most of it is CONFIRMED by this repo's own data (0/90 lossy backbone
legs; bloat-dominated radio sessions; the measured FEC premium). What would settle the remainder:
- **Measure real end-to-end residual loss** where it plausibly exists: a saturating-but-not-saturated radio
  (the hotspot leaked 16.7 % to L3 once), satellite if reachable, long-tail WiFi. If nothing leaks
  non-congestive loss at meaningful rates, the p999 ladder is a result about a regime nobody inhabits.
- **Add bufferbloat as a first-class comparator axis**: deep-queue profiles, latency-under-load, against
  TCP+BBR (which exists to win exactly this). The scavenger/LEDBAT work is adjacent but was never the axis.
- The paper's scope statement must state the conditional; "faster" claims without the antecedent are the
  hypnosis the review names.

## 2e. Partial reliability / latest-wins — DESIGNED 2026-08-29 (`docs/DESIGN-EXPIRY.md`); implementation deferred behind the growth-rule redesign

Fast-changing state (positions, ticks, sensor readings) wants old updates DROPPED, not reliably delivered
late; Tessera reliably delivers everything. MoQ-territory semantics (per-message TTL / supersede-by-key) are
the one missing feature that makes the "small data that changes a lot" workload true rather than aspirational.
The design is written: `docs/DESIGN-EXPIRY.md`. Its spine is the immutable-equations constraint (an emitted
repair cannot be unmixed, so expiry means "stop spending + settle the hole", never "forget the symbol"), one
idempotent `Expire` range frame, TTL and supersede-by-key as two API layers over one mechanism, and four
settling tests — including the honest one: if latest-wins does not beat reliable delivery on staleness under
loss, the document records that and the feature dies. Implementation deliberately waits for the growth-rule
redesign, since expiry changes what the repair machinery spends.

## 3. Multipath — designed, not built

`SPEC.md` has the design; `registerPath` exists and nothing calls it. Weeks, not days.

## 4. Gap-budget throttle, high-BDP half

The ack-driven re-send throttle is the binding constraint on every lossy high-BDP bulk number in BENCH-netem.
The *low-rate* half of the repair-overhead question is now measured (`bench amp`, `tailRepairMinLoss`); this half
is untouched.

## 5. Mechanisms shipped without a policy or without hardware validation

Each is real, tested, and off or unvalidated by choice — the work is the measurement, not the code.

| mechanism | state | what is missing |
|---|---|---|
| ACK cadence (frame 0x0A) | shipped, no automatic policy | choosing a cadence from the measured rate is a control loop; this repo does not ship control loops it has not run a matrix against |
| Tail-repair loss gate (`tailRepairMinLoss`) | off by default, measured **in-process only** | hardware validation — same class of claim as item 2, same missing evidence |
| Repair clock (`repairClockEquationsPerRtt`) | off by default | its supporting evidence is now one under-powered radio session; ~60 paired runs on shaped hardware show no benefit |
| Credit growth cap | shipped at 4x | see item 1 |

## 6. Environments that need hardware nobody has pointed at this yet

- **Radio (E5/W4):** doze, RRC promotion, handover. Needs a handset. The 5G-hotspot arm also needs an elevated
  host route on the Windows box, since Ethernet wins on interface metric. **Session three (2026-08-28, BENCH
  "Radio session three") adds:** a mechanism A/B needs order-of-magnitude more pairs or runs longer than the
  radio's spell length — 8 interleaved pairs of 6 s runs resolved nothing against a 22x spread. The probe now
  survives mid-run connection death, which that session's first attempt did not.
- **IPv6 with headroom:** every IPv6 measurement so far was taken on a path that could not carry the offered
  load, so "does the transport work over IPv6" is still unanswered rather than answered negatively. Needs a
  v6-enabled node — `enable_ipv6` must be set at instance creation; Vultr's `POST /ipv6/enable` 404s afterwards.

## 7. Credibility: no interop, no audit, no formal analysis

**L1 PASSED 2026-08-29** — an independent decoder written from `docs/SPEC.md` alone (clean-room agent, both
rounds' compliance attested, verified independently by re-running the decoder against the capture: 40/40
byte-exact, 212/212 packets). It took two rounds: round 1 was blocked by the spec lacking the entire packet
key schedule and mis-stating the short header; round 2 passed on the amended spec's first try and found the
frame catalog missing the actual wire message encoding. **The SPEC diff across the exercise is the score**, and
it was substantial — the document now contains a normative "Packet protection (v0)" section, a corrected Packet
section, and the real message frames, none of which existed before. Ledger: `docs/INTEROP.md`; evidence:
`interop/reports/`, `interop/cleanroom-decoder/`. Next rung: L2 (active responder), which first needs the
control-frame layouts specified (round-2 gap R2-5).
Interop now has its own ladder (`docs/INTEROP.md`, 2026-08-29): L0 publish golden vectors + a captured session
with secrets (hours, not clean-room-bound); L1 clean-room passive decoder (days, highest information per
effort); L2 clean-room active responder; L3 sustained interop. The contamination rule is what makes it a
measurement: the implementer's only inputs are SPEC.md and the vectors, gaps are fixed in SPEC and never by
hinting, and the SPEC diff over the exercise IS the score. Note AUDIT-PLAN's phase 2 and INTEROP's L1/L2 are
the same milestone approached from two sides: the wire freeze.
The audit now has a phased plan (`docs/AUDIT-PLAN.md`, 2026-08-29): threat model -> Noise-IK delta document
(the handshake pattern already has machine-checked proofs; the work is documenting the deviations) -> wire
freeze (same milestone as the interop decoder) -> paid design review (~$20-50k, only after the free phases).
Phases 0-2 are unstaffed and unblocked.

## 8. Unexplained measurements with concrete leads

- `transcont` sits at ~4 MB/s on a nominal 1 Gbit link, with loss, cwnd, credit and flow control all shown
  non-binding. Nobody can say why.
- The ~29 µs per-message plumbing residual over raw UDP — profile it directly (perf/JFR) rather than assuming
  syscalls; the AEAD taught that exact mistake.
- Something scales badly in the deep-outstanding regime: a *smaller* reliability horizon measured faster on
  high-BDP links, which should not happen.
- `lte` at 10 msg/s shows p99 2.7 s against 387 ms at 50 msg/s on the same profile (`bench amp`, 2026-08-28).
  Recorded, not explained.

## 9. Test-suite health

The `timingTest` set is load-sensitive by nature and has grown; a full run routinely surfaces one or two
failures that pass in isolation. **Re-run a timing failure in isolation before believing it**, and do not trust
a single `bench gate` reading either — the wifi p99 scenario measured 288-5091 ms across five runs of identical
code.

## 10. Operational

Ten Vultr nodes (`sao scl jnb syd blr sgp mex icn ewr del`) are up and billing at roughly **$1.70/day**.
`python bench/mesh/mesh.py destroy` is the off switch. `bench/mesh/shape.py clear all` before destroying if any
node is still shaped — and verify with `show`, because `clear` has lied once already.

## 11. CLOSED 2026-08-29 — the wire has a version field (was: no upgrade path)

`docs/SPEC.md` listed "version negotiation + greased versions" among the mechanisms kept from QUIC for the
whole of v0. It was never built: v0 packets carry no version, and `Wire.VERSION = 0x54530000` only tags the
build. The row has been moved to a **Claimed, not implemented** column rather than deleted, because the gap
between the two is the interesting part.

This is not cosmetic. `README.md` says to expect the wire format to change, and `WireVectorsTest` notes that a
peer built from an older commit no longer interoperates — so today a version skew is an undiagnosable decode
failure rather than a clean mismatch. Ossification insurance is the one lesson the whole design inherits from
SCTP's failure to deploy, and it is the lesson currently not applied.

- **Settled 2026-08-29** exactly at the bar this item set: wire 0x54530001 puts `version(4)` in every long
  header after the flags byte, readable before any key exists. Right magic + wrong version draws an 18-byte
  notice carrying the responder's version (rate-limited, smaller than any initial); the client fails the
  connect with a named error citing both versions in <5 s instead of a silent timeout. Wrong magic is dropped
  silently — answering scanners would make every port a beacon. The mismatch notice is unauthenticated, so it
  carries Retry's trust rules (same source address, pending connects only). `VersionNegotiationTest` (4);
  the version override in `ConnConfig.wireVersion` is the single-build stand-in for the two-build test.
- **What the change cost, recorded:** the flood tests went green-vacuous — random garbage now dies at the magic
  check before the validator, so `garbage()` had to stamp a valid version word (the magic check is a noise
  filter, not a DoS defence). The pinned wire vectors tripped as designed and were re-signed; interop
  session-1's capture is marked STALE (pre-field wire) and session-2 captured on 0x54530001.
- **Greasing landed 2026-08-29, same day:** the mismatch notice carries a shuffled version list (real + one
  random grease `0xXAYA`), clients skip unknown entries, and a greased initial takes the ordinary mismatch path
  with no special case anywhere. `VersionNegotiationTest` (5). **This item is fully closed.**

## 12. Reassembly abandonment is silent and permanent — NEW 2026-08-30, and it hangs applications

`Reassembler.onFragment` (`Connection.kt:2778`) refuses a fragment for a new message id once
`partial.size >= maxConcurrentReassembly` (default **64**) and calls `abandon(msgId)`. That is permanent: the id
enters `abandoned`/`abandonedBelow` and every later fragment of it — including a retransmit's — is
credited-and-dropped. The sender cannot detect this. The message's packets were received and acked, its fec seqs
are complete, and its flow credit is returned, so nothing is outstanding and nothing retransmits, while the
application blocks forever on a message that will never arrive. **This is the deep-outstanding stall** (TEST-PLAN
§8's ghost), reproducing 4/6 on scl->syd + tbf 20mbit/64pkt + 5 MB of 32 KB chunks.

- **Evidence:** BENCH, "The discriminator ran, and refuted its own hypothesis" (2026-08-30). `abandonedHeld=24`
  with `abandoned=786528` bytes: `786528/24 = 32772 = 32768 + 4`, exactly whole chunks, twice over. The sink
  times out 4 chunks short of 5 MB. The binding cap is the **count**, not the byte budget — 64 x 32 KB = 2.1 MB
  against a 64 MB `maxReassemblyBytes`, and a 3.7-5.4 MB cwnd puts more than 64 chunks in flight without
  anything being wrong.
- **What would settle it:** the same 6-rep hunt at 0/6 stalls with every hash MATCH, plus a test that drives
  >64 concurrent partial messages and asserts either delivery or a *loud* failure.
- **HALF DONE 2026-08-30 — abandonment is now loud.** The literal "refuse without abandoning" option was dropped
  on inspection: dropping the fragment still discards bytes the sender will never resend (the packet was acked, the
  fec seq is complete, and the receiver has no message-level retransmit request — the sink dumps show `resend=0`,
  it never asks for anything). It converts a hang into a hang with a tidier data structure. So the shipped half is
  the loud one: `Frame.Close.CODE_UNDELIVERABLE` (code 1), the receiver's `receive()` throwing
  `UndeliverableMessageException` once its inbox is drained, the peer's non-zero close code surfacing the same way,
  and `ConnStats.undeliverableMsgs`. `ConnConfig.failOnUndeliverable` (default on) turns it off for the two cases
  where a dropped message is not a broken promise: drop-accounting tests, and 2e's deliberate expiry.
  `UndeliverableTest` (2) — verified non-vacuous by re-running the end-to-end case with the knob off, where it fails
  as the hang it replaces. Full suite green.
- **STILL OPEN — the invariant, which is the actual fix.** Loud failure is a report, not a repair: the caps are
  still mutually inconsistent, so a peer obeying flow control perfectly can still overrun the receiver. The window
  MUST NOT fund more concurrent messages than the reassembler will hold — 16 MB / 32 KB = 512 against a cap of 64,
  8x over. Derive one bound from the other (or advertise the smaller). Recorded as normative in SPEC's frame
  catalog, since a clean-room implementer (item 7) cannot infer it. **What would settle it:** the 6-rep scl->syd
  hunt at 0/6 stalls with every hash MATCH, on a build whose loud failure never fires.
- **Related:** the same family as the 2026-08-27 close defect — *acked is not delivered*, one layer up. There
  the packet predicate outran delivery; here the receiver destroys a message the packet layer thinks is
  delivered.
- **Blocks:** every growth-rule A/B on a chunked-bulk path (item 1).

---

## Closed, with the argument worth keeping

- **Close could drop the final message** (2026-08-27). `lingerNeeded()` was packet-level, and *acked is not
  delivered*: a source recovered from repairs has acked packets while its fec seq is still a hole in the peer's
  decoder. An earlier investigation had ruled this predicate out on reasoning that only covered packets nobody
  received. BENCH, "The close defect".
- **Keepalive** (2026-08-27). "No keepalive" was never a neutral position awaiting a decision — it was a default,
  and the one that broke every application with quiet periods. Shipped with an amplification vector that
  `EndpointFuzzTest` caught the same day.
- **The radio misprediction** (2026-08-28). Not the loss model — the profile's **uplink cap**, fitted to one
  phone-hour and reused as a constant. At that cap both arms were queue-bound, and a queue-bound arm measures the
  queue. Superseded in part by item 2.
- **Low-rate packet amplification** (2026-08-28). ~2.5 packets per source at 10 msg/s, essentially one tail
  repair each: below ~1/T msg/s every source is a tail. Gated behind `tailRepairMinLoss`, off by default.
- **The v0.2 "Open items / v1" list** (2026-08-28). Corrected: it still named AEAD wiring, header protection,
  full Gaussian elimination, DPLPMTUD, 0-RTT anti-replay and the native datapath as missing. All had shipped.
