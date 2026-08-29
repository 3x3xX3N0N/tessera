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
- **Next action:** a growth rule whose step is bounded by something loss does not agitate. The rate EWMA is
  disqualified (it never settles under loss); candidates worth modelling first in `CreditGrowthSweepTest`, since
  that model demonstrably transfers: the *minimum* observed rate over a window, or a step sized from
  `minRtt`-BDP rather than the current estimate.

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
zero-completion sanity check before trusting such numbers.

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

The cheapest real progress is an **independent minimal decoder written from `docs/SPEC.md` alone**. If the spec
cannot be implemented from the document, that is a finding; if it can, it is the first genuine interop evidence.
No security audit and no formal handshake analysis — both stated in the README, neither scheduled.

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
