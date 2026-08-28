# Tessera

A message transport over UDP, in Kotlin, built for **instant connect and flat tail latency on lossy links** —
cellular, Wi-Fi, LEO satellite. Post-quantum 1-RTT handshake with 0-RTT data, sliding-window network coding instead
of retransmit-only recovery, receiver-driven congestion control, and a Rust SIMD datapath.

No QUIC/BBR lineage: the design draws on Noise, Homa, RLNC and CUBIC, and on IETF mechanisms that are worth keeping
(varints, connection IDs, ACK ranges with ECN, transport-parameter TLVs, DPLPMTUD, header protection, qlog).
`docs/SPEC.md` lists what was deliberately borrowed and what was deliberately left behind.

> Status: **research prototype.** The protocol is implemented end to end and measured on live intercontinental
> paths and a real 5G radio — but it is v0 on the wire, single-path, has no keepalive, and has had **no security
> audit and no interop testing** with any other implementation. Expect the wire format to change. Use it to
> measure and to learn from, not to carry anything that matters.

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

### Measured on real networks, not only simulated ones

Simulated links flatter a transport, so the same code is run across a live global mesh. Six regions
(New Jersey, Frankfurt, Johannesburg, Tokyo, São Paulo, Sydney), 30 directed paths, 82 ms to 348 ms RTT,
compared against raw UDP **and** against ICMP as an absolute floor on the identical path:

| arm | vs the ICMP floor (median) | loss |
|---|---|---|
| ICMP ping | — (reference) | 0.111 % |
| raw UDP | +0.30 ms | 0.033 % |
| **Tessera** | **+1.00 ms** | **0.000 %** |

A post-quantum-encrypted, FEC-protected, reliably delivered 1200-byte message costs about a millisecond over an
ICMP echo that guarantees nothing — and loses nothing where both UDP and ping lose a little. A separate
three-arm campaign over 10 regions and 90 directed paths, **134,500 messages per arm**, put Tessera at +0.40 ms
against the ping floor with **0 messages lost**, where raw UDP lost 6.

**Over a real 5G radio** dropping one packet in six, raw UDP delivered 250/300 while Tessera delivered 300/300.
Reliability on a radio is not free, though: the median cost is high unless the repair clock is enabled, which is
what [`ConnConfig.repairClockEquationsPerRtt`](docs/BENCH-netem.md) is for — it measured a ~7× cut in median
latency there. That knob ships **off**, because the in-process 5G profile predicted it would not help and the
real radio disagreed; see the write-up, which is also the clearest example in this repo of a simulator
mispredicting a real link.

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

Requires JDK 21. The `:native` module additionally needs a Rust toolchain, and the whole project builds and runs
without one — the JDK datapath and the scalar FEC kernel are the fallback, and a build with no cargo on PATH says
so and carries on. That was a promise this README made and the build broke until 2026-08-27: `:transport` depends
on `:native`, whose resources depended on an unguarded `cargo build`, so a clone without Rust could compile
`:core` and nothing else. CI now runs a job with the toolchain deliberately removed, because that is the only
place the claim can be checked honestly.

What the native module buys, measured rather than assumed: **2.5x packet throughput and 2.6x lower CPU per
packet**, at **an order of magnitude worse tail latency** (p99 38 us to 437 us) because it batches, plus 1.8 us
per message on the RLNC kernel — about 5 % of the per-message cost over raw UDP. Useful at high packet rates and
on CPU-constrained nodes; not load-bearing for the latency-focused workloads this transport targets.

> **JDK 22 and later:** `core`, `transport` and `tools` build and run fine, but `:native` does **not** — it uses
> JDK 21 preview-era FFM names (`allocateUtf8String`, `Linker.Option.isTrivial()`) that were renamed in JDK 22.
> Build without that module, or run with `-Dtessera.native=off`, until it is ported.

```bash
./gradlew test                       # core, transport, native — 263 tests
./gradlew :transport:timingTest      # 24 more that are real-time and load-sensitive
./gradlew :bench:installDist
B=bench/build/install/bench/bin/bench

$B connect                           # 0-RTT connect latency, fresh vs resumed
$B adapt --lossSim 0.05              # adaptive FEC under 5 % loss
$B tessera --netem lte --n 5000      # against a simulated LTE link
$B rawudp --netem lte --n 5000       # the plain-UDP floor for comparison
$B compress                          # shared-dictionary payload codec
$B native                            # JDK vs Rust datapath
$B bulk --netem transcont --runs 5   # back-to-back send; reports median/range/spread, never one number
$B profile                           # where the per-message cost goes: codec vs plumbing
$B conns --n 200                     # many connections: footprint, accept rate, fairness
$B soak / storm / idle / coldstart / gate

sudo -E bench/netem/run-matrix.sh    # Linux/WSL: the full tc netem matrix
```

Modules: `core` (wire format, FEC, congestion control, crypto, ACK/path, PMTUD, tracing, codec) ·
`transport` (connections) · `native` (Rust SIMD GF(256) + batched UDP via Panama FFM) · `bench` (harness,
link simulator presets, netem profiles).

## Testing over a real network

The bench runs both endpoints in one process. To measure across two machines, use the standalone endpoints:

```bash
./gradlew :tools:installDist
T=tools/build/install/tessera/bin/tessera

# generate the responder keypair once; prints the --peer-key string for the probe
$T keygen --out server.key

# machine A (the listener)
$T echo --token <shared-secret> --key-in server.key --port 51820 --also-udp

# machine B (the measurer)
$T probe --connect [<addr>]:51820 --peer-key <base64> --token <shared-secret> --rate 50 --count 2000 --connect-warmup 2 --out run.csv

# the same path with plain datagrams, for comparison
$T probe --connect [<addr>]:51821 --transport udp --token x --rate 50 --count 2000
```

The handshake pins the responder's static keys out of band — that is what `--peer-key` carries, and there is no
PKI involved. `--token` is a shared secret carried in the 0-RTT payload; a connection without it is dropped with
no reply, so a listener cannot be used as a reflector and does not answer scanners.

Round trips are measured against the probe's own clock, so no clock synchronisation between the machines is
needed — but these are RTTs, unlike the one-way figures the netem benches report. The full two-machine
runbook, including firewall and NAT setup and how to read the results, is in [`docs/LIVE-TEST.md`](docs/LIVE-TEST.md).

`--connect-warmup` discards
the first connects in a fresh JVM, which pay class loading and the first ML-KEM operation (~100 ms of pure CPU
on loopback) and would otherwise swamp a WAN measurement.

## Documentation

- [`docs/SPEC.md`](docs/SPEC.md) — wire format, frames, handshake, recovery, congestion control, and the
  borrowed-from-QUIC / left-behind tables.
- [`docs/LIVE-TEST.md`](docs/LIVE-TEST.md) — running a real two-machine test: connectivity, commands, caveats.
- [`docs/TEST-PLAN.md`](docs/TEST-PLAN.md) — what is measured and what is not, the tier/environment/workload
  axes, the WAN-mesh and mobile procedures, and the measurement mistakes already made.
- [`docs/BENCH-netem.md`](docs/BENCH-netem.md) — every benchmark run, including the ones that failed and what
  they found. The netem harness located roughly fifteen real defects; they are all written down.

## Known gaps

Multipath is designed but not implemented, and the transport is single-path today.

**No keepalive.** The idle timeout is 10 s and nothing holds a connection open, so an application that goes quiet
between bursts — a chat client, an RPC channel, a game between rounds — will see its connection torn down. This
is a wire-format and battery decision that has not been made yet, and it is the most likely thing to surprise
someone building on this.

Long loss bursts at low message rates cost 150–300 ms to recover. That is **RLNC equation accumulation** — repairs
are emitted per source rather than per unit time, so a burst of `b` losses waits for `b` messages' worth of
equations — and *not* the probe timeout, which measurement shows fires 3 times per 2000 messages and is
structurally unreachable while the application keeps sending. An earlier version of this section named the probe
timeout; that explanation was measured and disproved (`docs/BENCH-netem.md`, "the recorded explanation was
wrong"). `repairClockEquationsPerRtt` is the lever, and it is off by default.

Roughly 1 MB per connection at rest, most of it fixed ring arrays (`packetRing` / `bodyRing` shrink it ~62 %, at
a measured cost in fairness to a LEDBAT scavenger). No formal analysis of the handshake, no interop with any other
implementation, **no security audit**. The `timingTest` set is real-time and load-sensitive by nature: re-run a
failure in isolation before believing it. See the "left open" list at the end of `docs/SPEC.md`.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). The patent grant is deliberate: anyone implementing this wire
format independently should be able to do so without a licensing question hanging over it. Third-party
dependencies and design provenance are listed in [`NOTICE`](NOTICE).
