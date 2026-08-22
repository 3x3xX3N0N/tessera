# Tessera

A message transport over UDP, in Kotlin, built for **instant connect and flat tail latency on lossy links** —
cellular, Wi-Fi, LEO satellite. Post-quantum 1-RTT handshake with 0-RTT data, sliding-window network coding instead
of retransmit-only recovery, receiver-driven congestion control, and a Rust SIMD datapath.

No QUIC/BBR lineage: the design draws on Noise, Homa, RLNC and CUBIC, and on IETF mechanisms that are worth keeping
(varints, connection IDs, ACK ranges with ECN, transport-parameter TLVs, DPLPMTUD, header protection, qlog).
`docs/SPEC.md` lists what was deliberately borrowed and what was deliberately left behind.

> Status: **research prototype.** The protocol is implemented end to end and measured, but it is v0 on the wire,
> single-path, unaudited, and not deployed anywhere. Expect the wire format to change.

## Measured

Real links, `tc netem` on Linux, six profiles, 1200-byte messages at 2000 msg/s. `rawudp` is plain UDP — the floor
any transport must beat. Full tables and the five-run history in [`docs/BENCH-netem.md`](docs/BENCH-netem.md).

| link profile | plain UDP | Tessera |
|---|---|---|
| transcontinental (180 ms RTT) | 100 %, p99 92.1 ms | **100 %, p99 92.2 ms** |
| Starlink-shaped (1.8 % bursty loss) | 98.2 %, p99 47.0 ms | **100 %, p99 65.2 ms** |
| LTE (4.2 % bursty loss) | 95.8 %, p99 91.4 ms | **100 %, p99 124 ms** |
| busy Wi-Fi (2.7 % loss + reorder) | 97.3 %, p99 87.8 ms | **100 %, p99 88.4 ms** |
| 5G mmWave (4.8 % bursty loss) | 95.2 %, p99 43.7 ms | **100 %, p99 68.2 ms** |

At 50 msg/s with 5 % loss the p99 lands within 1–5 ms of plain UDP's while delivering everything it drops.

**Connect**: 6000/6000 successful over those impaired links. The 0-RTT payload arrives one one-way delay after
`connect()` returns — 92 ms on the 180 ms-RTT link, i.e. the physical floor. On loopback: **298 µs** for a resumed
connection, 772 µs for a fresh post-quantum one.

Other numbers: shared-dictionary compression takes a 135 B telemetry message to **56 B** (1.6 µs); the Rust
datapath does **2.6× the packets/s at 2.7× lower CPU** than the JDK channel path; SIMD GF(256) is **24× faster**
than the scalar kernel.

## Design in one screen

- **Instant connect** — Noise-IK shape with hybrid X25519 + ML-KEM-768, responder keys known in advance (pinned or
  a short-lived credential), so there is no certificate chain on the wire. Packet one carries application data:
  184 B on a fresh connection, **1232 B on a resumed one** (stateless encrypted ticket, no KEM).
- **Loss recovery without waiting a round trip** — systematic sliding-window RLNC over GF(256). Redundancy tracks
  the measured loss rate *and burst length*; a trailing repair symbol follows an idle stream so an isolated loss
  costs milliseconds, not an RTT. Residual ARQ catches what coding cannot.
- **Congestion control that does not build queues** — receiver-driven cumulative credit (Homa lineage). A CUBIC +
  HyStart++ fallback exists for fairness but only engages on real congestion evidence: ECN-CE, or loss *with*
  queueing delay. Random link loss never throttles the sender.
- **Messages, not streams** — the transport delivers whole messages; ordering and stream semantics belong in a
  library above it. 7-byte short header (1 flag + 4 connection id + 2 packet number).
- **Built to be measured** — an in-process link simulator (`NetemSim`) mirrors the netem profiles so every
  impairment reproduces as a unit test, plus a qlog-schema tracer and a `tc netem` matrix runner.

## Build and run

Requires JDK 21. The `:native` module additionally needs a Rust toolchain; everything works without it (the JDK
datapath and scalar FEC are the fallback).

```bash
./gradlew test                       # core, transport, native — ~170 tests
./gradlew :bench:installDist
B=bench/build/install/bench/bin/bench

$B connect                           # 0-RTT connect latency, fresh vs resumed
$B adapt --lossSim 0.05              # adaptive FEC under 5 % loss
$B tessera --netem lte --n 5000      # against a simulated LTE link
$B rawudp --netem lte --n 5000       # the plain-UDP floor for comparison
$B compress                          # shared-dictionary payload codec
$B native                            # JDK vs Rust datapath

sudo -E bench/netem/run-matrix.sh    # Linux/WSL: the full tc netem matrix
```

Modules: `core` (wire format, FEC, congestion control, crypto, ACK/path, PMTUD, tracing, codec) ·
`transport` (connections) · `native` (Rust SIMD GF(256) + batched UDP via Panama FFM) · `bench` (harness,
link simulator presets, netem profiles).

## Documentation

- [`docs/SPEC.md`](docs/SPEC.md) — wire format, frames, handshake, recovery, congestion control, and the
  borrowed-from-QUIC / left-behind tables.
- [`docs/BENCH-netem.md`](docs/BENCH-netem.md) — every benchmark run, including the ones that failed and what
  they found. The netem harness located roughly fifteen real defects; they are all written down.

## Known gaps

Multipath is designed but not implemented. Long loss bursts at low message rates still fall back to the probe
timeout (LTE 50 msg/s p99 ≈ 208 ms). No formal analysis of the handshake, no interop, no audit, one flaky
real-time simulator test. See the "left open" list at the end of `docs/SPEC.md`.
