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

## 1. Receiver-credit growth rule — TOP DEFECT

`ReceiverCredit`'s slow-start doubling is funded by the *send* rate, which a tail-drop bottleneck inflates: the
sender always looks blocked because its packets leave and then die in the queue. The shipped growth cap
(`GROWTH_CAP_BDP = 4`) is a midpoint between two requirements that pull opposite ways, and **has never itself
been measured against a link**.

- **Evidence:** TEST-PLAN "F8b fix campaign" (live, 2026-08-24) and "The growth rule, swept deterministically"
  (2026-08-28). The deterministic sweep reproduces the campaign's trade exactly: 2x fails the bootstrap contract
  at 64 % of offered but delivers 4x more on a shallow bottleneck; 8x passes bootstrap and loses 54 %.
- **Refuted so far:** tighten-on-dead-credit (evidence arrives after the spray), tighten-on-settled-rate
  (same, less badly), additive-probe-when-settled (never fires — the rate EWMA never settles under loss). All
  three live behind parameters defaulting to off.
- **Next action, now runnable:** `probe --growthCap 2,4,8 --runs 15` on `scl→syd` unshaped (the bootstrap half)
  and under `shape.py apply scl --rate 2mbit --limit 64` (the bottleneck half). `ConnConfig.creditGrowthCapBdp`
  and the probe dimension landed 2026-08-28 for exactly this.
- **What would settle it:** hardware reproducing the model's crossover would give 4x an evidence base for the
  first time. Hardware *not* reproducing it is the more important result — see item 2.

## 2. NetemSim is not validated against hardware — NEW, and it undercuts a lot

The in-process simulator has been the primary instrument behind every congestion and repair decision in
`BENCH-netem.md`. On 2026-08-28 it was compared against real `tc netem` at matched parameters for the first
time and **disagreed**: the sim predicts a 1.5-8.4x repair-clock win on a capacity-limited fading link, and the
same configuration on real hardware (2 Mbit tbf, same GE loss, same rate, ~60 paired runs) shows nothing.

- **Evidence:** BENCH "Real netem on a real path" and "The radio misprediction, resolved".
- **Consequence, already applied:** a simulator-only result is a hypothesis, not a finding.
- **Next action:** matched-parameter comparison across several profiles, not just the one that surfaced this —
  `lte`, `wifi-busy`, `transcont` in-process against `tc netem` with identical delay/jitter/loss/rate on a mesh
  node. The question is whether the disagreement is one profile, one mechanism, or the simulator's timing model.
- **What would settle it:** a profile-by-profile table of sim vs hardware for the same workload. If the gap is
  systematic and directional, the sim needs recalibrating against hardware before it decides anything else.

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
  host route on the Windows box, since Ethernet wins on interface metric.
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
